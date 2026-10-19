package ai.pickyour.android.preview

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.configuration.ConfigurationFailure
import ai.pickyour.core.configuration.EnvironmentAdmission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual packaged resources and native views under Robolectric, not an installed variant receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EnvironmentBootstrapTest {
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    @Test fun actualPackagedProfileMatchesAppAndFrozenCatalogMetadata() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val result = EnvironmentBootstrap.read(activity)
            assertSame(EnvironmentAdmission.SyntheticPreview, result)
            val catalog = activity.assets.open("catalog.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
            assertEquals(EnvironmentAdmission.SyntheticPreview.catalogSchemaVersion, catalog.getString("schema_version"))
            assertEquals(EnvironmentAdmission.SyntheticPreview.configuredCatalogId, catalog.getString("id"))
            assertEquals(EnvironmentAdmission.SyntheticPreview.configuredCatalogRevision, catalog.getInt("revision"))
            assertEquals("preview", BuildConfig.PYAI_ENVIRONMENT)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun realAndUnknownSelectionsCannotAdmitPackagedPreview() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            for (mode in listOf("staging", "production", "", "PREVIEW")) {
                val admission = EnvironmentBootstrap.read(activity, mode)
                assertFalse(EnvironmentBootstrap.admit(activity, admission))
                assertFalse(views(activity.window.decorView).any { it is EditText })
                assertNull("Admission must never initialize a draft library", activity.noBackupFilesDir.listFiles()?.firstOrNull { it.name.contains("draft") })
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingMalformedAndWrongContractAreActionableWithoutRawValues() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val valid = activity.assets.open("client-environment.json").bufferedReader().use { it.readText() }
            val cases = listOf<() -> java.io.InputStream>(
                { throw java.io.IOException("PRIVATE_CONFIGURATION_CANARY") },
                { "{\"token\":\"PRIVATE_CONFIGURATION_CANARY\"}".byteInputStream() },
                { valid.replace("\"contract_version\": \"1.0.0\"", "\"contract_version\": \"2.0.0\"").byteInputStream() },
            )
            for (source in cases) {
                val result = EnvironmentBootstrap.read(activity, openConfiguration = source)
                assertTrue(result is EnvironmentAdmission.Rejected)
                assertFalse(EnvironmentBootstrap.admit(activity, result))
                assertFalse(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("PRIVATE_CONFIGURATION_CANARY") })
                assertEquals(1, views(activity.window.decorView).filterIsInstance<Button>().size)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun blockedScreenContainsOnlyFiniteRecoveryAndCloseAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            for (reason in ConfigurationFailure.entries) {
                assertFalse(EnvironmentBootstrap.admit(activity, EnvironmentAdmission.Rejected(reason)))
                val all = views(activity.window.decorView)
                assertTrue(all.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.environment_recovery) })
                assertFalse(all.any { it is EditText })
            }
            views(activity.window.decorView).filterIsInstance<Button>().single().performClick()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun admittedPreviewStillCreatesAndRestoresLocalControls() {
        var controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            assertTrue(views(controller.get().window.decorView).any { it is EditText })
            val state = Bundle()
            controller.pause().saveInstanceState(state).stop().destroy()
            controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
            assertTrue(views(controller.get().window.decorView).any { it is EditText })
            assertSame(EnvironmentAdmission.SyntheticPreview, EnvironmentBootstrap.read(controller.get()))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rejectedActivityCannotRestoreEditOrOpenDraftsAcrossLifecycle() {
        val root = org.robolectric.RuntimeEnvironment.getApplication().noBackupFilesDir.toPath()
        val savedDraft = ai.pickyour.core.preview.PreviewDraftLibrary(root).openLegacy().save(null,
            ai.pickyour.core.preview.PreviewDraftFields(title = "PRIVATE_DURABLE_CANARY"))
        assertTrue(savedDraft is ai.pickyour.core.preview.PreviewDraftSave.Saved)
        fun durableBytes(): Map<String, List<Byte>> = java.nio.file.Files.walk(root).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) }.iterator().asSequence().associate {
                root.relativize(it).toString() to java.nio.file.Files.readAllBytes(it).toList()
            }
        }
        val before = durableBytes()
        assertTrue(before.isNotEmpty())
        fun controller() = Robolectric.buildActivity(PreviewActivity::class.java).also {
            val activity = it.get()
            val wrapped = object : android.content.ContextWrapper(activity.baseContext) {
                override fun getPackageName() = "unapproved.application.PRIVATE_CANARY"
            }
            // Controlled framework identity in this test; no injectable production configuration.
            org.robolectric.util.ReflectionHelpers.setField(activity, "mBase", wrapped)
            assertEquals("unapproved.application.PRIVATE_CANARY", activity.packageName)
        }
        var rejected = controller()
        try {
            val restored = Bundle().apply {
                putBoolean("selectionResolved", true)
                putString("draftId", "guest-draft")
                putString("title", "PRIVATE_SAVED_CANARY")
                putBoolean("environmentAdmitted", true)
            }
            rejected.create(restored).start().resume().visible()
            fun remainsBlocked() {
                val activity = rejected.get()
                val all = views(activity.window.decorView)
                assertFalse(all.any { it is EditText })
                assertTrue(all.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.environment_blocked_title) })
                assertFalse(all.filterIsInstance<TextView>().any { it.text.contains("PRIVATE_") })
                assertNull(org.robolectric.util.ReflectionHelpers.getField<Any?>(activity, "draftLibrary"))
                assertNull(org.robolectric.util.ReflectionHelpers.getField<Any?>(activity, "catalogFixture"))
                assertEquals(before, durableBytes())
            }
            remainsBlocked()
            rejected.newIntent(android.content.Intent(android.content.Intent.ACTION_MAIN))
            remainsBlocked()
            val saved = Bundle()
            rejected.pause().saveInstanceState(saved).stop().destroy()
            assertFalse(saved.containsKey("draftId"))
            assertFalse(saved.containsKey("title"))
            rejected = controller().create(saved).start().resume().visible()
            remainsBlocked()
        } finally { rejected.pause().stop().destroy() }
    }

    @Test fun configurationSourceClosesAndFreshAdmissionDoesNotRetainFailure() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var closed = false
            val invalid = object : java.io.ByteArrayInputStream("{}".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
            assertTrue(EnvironmentBootstrap.read(activity, openConfiguration = { invalid }) is EnvironmentAdmission.Rejected)
            assertTrue(closed)
            assertSame(EnvironmentAdmission.SyntheticPreview, EnvironmentBootstrap.read(activity))
        } finally { controller.pause().stop().destroy() }
    }
}
