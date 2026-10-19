package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Activity capture in a simulation; historical raw Bundles keep their separate contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ComponentFormUserinfoRecoveryActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val ordinary = "https://example.invalid/resource"
    private val userinfo = "custom+fixture://synthetic:checkpoint-secret@host_name/resource"

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any {
        it.text.toString() == activity.getString(label)
    }
    private fun field(dialog: AlertDialog, label: Int): EditText {
        val caption = views(dialog.window!!.decorView).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(label)
        }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return dialog.findViewById(caption.labelFor)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for component capture UI")
    }
    private fun worker(): ExecutorService = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker().submit {}.get(15, TimeUnit.SECONDS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun managedBytes(): Map<String, String> {
        val root = activity.noBackupFilesDir.toPath()
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate {
                root.relativize(it).toString() to MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(it)).joinToString("") { byte -> "%02x".format(byte) }
            }
        }
    }
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()!!
    private fun save() {
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
        finishWork()
    }
    private fun addForm(kind: String): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().startsWith("$kind ·")
        }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        val choices = dialog()
        shadowOf(choices).clickOnItem(0)
        await { dialog() !== choices && dialog().isShowing }
        return dialog()
    }
    private fun uriField(kind: String) = when (kind) {
        "model_provider" -> "api_base_url"
        "model" -> "weights_uri"
        else -> "endpoint"
    }
    private fun settings(kind: String, uri: String = userinfo) = JSONObject().put(uriField(kind), uri).toString()
    private fun addSaved(kind: String) {
        val form = addForm(kind)
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(settings(kind, ordinary))
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && hasText(R.string.component_saved) }
        finishWork()
    }
    private fun configure(): AlertDialog {
        button(R.string.component_manage).performClick()
        val picker = dialog()
        shadowOf(picker).clickOnItem(0)
        await { dialog() !== picker && dialog().isShowing }
        val actions = dialog()
        shadowOf(actions).clickOnItem(0)
        await { dialog() !== actions && dialog().isShowing }
        return dialog()
    }
    private fun roundTrip(state: Bundle): Bundle {
        val written = Parcel.obtain()
        val read = Parcel.obtain()
        return try {
            written.writeBundle(state)
            val bytes = written.marshall()
            read.unmarshall(bytes, 0, bytes.size); read.setDataPosition(0)
            requireNotNull(read.readBundle(javaClass.classLoader))
        } finally { written.recycle(); read.recycle() }
    }
    private fun capture(): Bundle = Bundle().also { controller.saveInstanceState(it) }.let(::roundTrip)
    private fun recreate(state: Bundle = capture()) {
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java)
            .create(roundTrip(state)).start().resume().visible()
        finishWork()
    }
    private fun pending(state: Bundle) = (ComponentFormRecovery.read(state) as ComponentFormRecovery.Pending).form
    private fun assertExcluded(state: Bundle) {
        val retained = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertTrue("New Activity capture must withhold recognized URI userinfo", retained.getBoolean("unavailable"))
        assertEquals(setOf("version", "unavailable", "reason"), retained.keySet())
        assertEquals(1, retained.getInt("version"))
        assertEquals("uri_userinfo", retained.getString("reason"))
    }
    private fun assertExclusionMessage() {
        // Keep the causal baseline compilable before this new fixed resource exists.
        val resource = activity.resources.getIdentifier("component_recovery_userinfo_excluded", "string", activity.packageName)
        assertTrue("Exclusion needs a distinct fixed explanation", resource != 0)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.visibility == View.VISIBLE && it.text.toString() == activity.getString(resource)
        })
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(View.VISIBLE, button(R.string.component_recovery_discard).visibility)
    }
    private fun review(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }
    private fun assertUnchanged(before: PreviewStoredDraft, bytes: Map<String, String>) {
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals(bytes, managedBytes())
    }

    @Test fun openAddCaptureExcludesUserinfoBeforeAnyCommit() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("model_provider")
        field(form, R.string.component_settings).setText(settings("model_provider"))
        val state = capture()
        assertUnchanged(before, bytes)
        assertExcluded(state)
        recreate(state)
        assertExclusionMessage()
        assertUnchanged(before, bytes)
    }

    @Test fun openConfigureCaptureUsesItsKnownKindWithoutChangingLegacyBundleShape() {
        save(); addSaved("model")
        val before = loaded(); val bytes = managedBytes()
        val form = configure()
        field(form, R.string.component_settings).setText(settings("model"))
        val state = capture()
        assertUnchanged(before, bytes)
        assertExcluded(state)
        recreate(state)
        assertExclusionMessage()
        assertUnchanged(before, bytes)
    }

    @Test fun rejectedAddKeepsCorrectionTextButExcludesTheNextCapture() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("integration")
        val raw = settings("integration")
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(raw)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.component_invalid) && form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertEquals(raw, field(form, R.string.component_settings).text.toString())
        assertUnchanged(before, bytes)
        assertExcluded(capture())
    }

    @Test fun rejectedConfigureHasNoSaveAndExcludesTheNextCapture() {
        save(); addSaved("mcp_server")
        val before = loaded(); val bytes = managedBytes()
        val form = configure()
        val raw = settings("mcp_server")
        field(form, R.string.component_settings).setText(raw)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.component_invalid) && form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertEquals(raw, field(form, R.string.component_settings).text.toString())
        assertUnchanged(before, bytes)
        assertExcluded(capture())
    }

    @Test fun inFlightCaptureExcludesUserinfoWithoutReplayingAnUncertainOperation() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("mcp_server")
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(settings("mcp_server"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        val state = try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            capture()
        } finally { release.countDown() }
        finishWork()
        assertUnchanged(before, bytes)
        assertExcluded(state)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate(state)
        assertExclusionMessage()
        retired.performClick(); finishWork()
        assertUnchanged(before, bytes)
    }

    @Test fun queuedPreparationRetiresAcrossRecreationWhileExclusionStaysOutcomeNeutral() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("mcp_server")
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(settings("mcp_server"))
        val retiredActivity = activity; val retiredWorker = worker()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val interrupted = CountDownLatch(1)
        val held = retiredWorker.submit {
            entered.countDown()
            try {
                check(release.await(20, TimeUnit.SECONDS))
            } catch (_: InterruptedException) {
                // onDestroy interrupts this test barrier and removes the queued preparation.
                interrupted.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            retiredCommit.performClick()
            assertFalse(retiredCommit.isEnabled)
            val state = capture()
            controller.pause().stop().destroy()
            assertTrue(retiredActivity.isDestroyed)
            assertTrue(retiredWorker.isShutdown)
            assertTrue(interrupted.await(15, TimeUnit.SECONDS))
            controller = Robolectric.buildActivity(PreviewActivity::class.java)
                .create(roundTrip(state)).start().resume().visible()
            finishWork() // Only the replacement Activity's worker; the retired worker stays held.
            assertFalse(held.isDone)
            assertExcluded(state)
            assertExcluded(capture())
            assertExclusionMessage()
            retiredCommit.performClick()
            finishWork()
            assertUnchanged(before, bytes)
        } finally {
            release.countDown()
            held.get(15, TimeUnit.SECONDS)
            assertTrue(retiredWorker.awaitTermination(15, TimeUnit.SECONDS))
        }
        finishWork()
        assertExclusionMessage()
        assertUnchanged(before, bytes)
    }

    @Test fun repeatedExclusionRecoveryStoresOnlyTheFixedReasonUntilDiscard() {
        save()
        val before = loaded(); val bytes = managedBytes()
        field(addForm("mcp_server"), R.string.component_settings).setText(settings("mcp_server"))
        repeat(3) {
            val state = capture()
            assertExcluded(state)
            recreate(state)
            assertExclusionMessage()
            assertUnchanged(before, bytes)
        }
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_choose_catalog).isEnabled)
        assertNull(capture().getBundle(ComponentFormRecovery.KEY))
        assertUnchanged(before, bytes)
    }

    @Test fun newlyCapturedStaleConfigureStillUsesTheOriginalKindAndCannotOverwrite() {
        save(); addSaved("mcp_server")
        val before = loaded()
        val form = configure()
        field(form, R.string.component_settings).setText(settings("mcp_server"))
        val newer = (adapter().save(before, PreviewDraftFields("other writer")) as PreviewDraftSave.Saved).record
        val bytes = managedBytes()
        val state = capture()
        assertExcluded(state)
        recreate(state)
        assertExclusionMessage()
        assertUnchanged(newer, bytes)
    }

    @Test fun captureDoesNotLoseSameProcessCorrectionOrItsOneExplicitSave() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("model_provider")
        val raw = settings("model_provider")
        field(form, R.string.component_role).setText("primary")
        val input = field(form, R.string.component_settings)
        input.setText(raw)
        capture()
        assertTrue(form.isShowing)
        assertTrue(input.isEnabled)
        assertEquals(raw, input.text.toString())
        assertUnchanged(before, bytes)
        val corrected = "custom+fixture://Example.invalid/corrected@path?q=exact@value#@fragment"
        input.setText(settings("model_provider", corrected))
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
        val after = loaded()
        assertEquals(corrected, JSONObject(PreviewSelectionForms.settingsText(after.components.selections.single())).getString("api_base_url"))
        recreate()
        assertEquals(after.baselineIdentity, loaded().baselineIdentity)
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun completeLongUriWithinTheTwentyThousandBudgetIsStillExcluded() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("model")
        val document = settings("model", userinfo + "x".repeat(16_500))
        assertTrue(document.length > PreviewSelectionForms.MAX_SETTINGS_TEXT)
        val raw = document + " ".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS - document.length)
        field(form, R.string.component_settings).setText(raw)
        assertEquals(20_000, raw.length)
        assertExcluded(capture())
        assertUnchanged(before, bytes)
    }

    @Test fun ordinaryMalformedTypedAndOverEditorLimitTextKeepsExactRecovery() {
        save()
        val before = loaded(); val bytes = managedBytes()
        var form = addForm("mcp_server")
        for (raw in listOf("{\"endpoint\":", "{\"endpoint\":42}", "{\"endpoint\":\"https://bad authority\"}",
                "{" + " ".repeat(19_999))) {
            field(form, R.string.component_settings).setText(raw)
            val state = capture()
            assertEquals(raw, pending(state).settings)
            recreate(state)
            form = review()
            assertEquals(raw, field(form, R.string.component_settings).text.toString())
            assertUnchanged(before, bytes)
        }
    }

    @Test fun legitimateAtSignsAndOpaqueReferencesRemainExactAndExplicitlySaveable() {
        save()
        val before = loaded()
        val form = addForm("model_provider")
        val uri = "custom+fixture://Example.invalid/path@literal?q=exact@query#@fragment"
        val reference = "secret://fixture/Case:Reference_Exact"
        val raw = JSONObject().put("api_base_url", uri).put("credential_ref", reference).toString()
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(raw)
        val state = capture()
        assertEquals(raw, pending(state).settings)
        recreate(state)
        val recovered = review()
        assertEquals(raw, field(recovered, R.string.component_settings).text.toString())
        recovered.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !recovered.isShowing && loaded().revision == before.revision + 1 }
        val savedSettings = JSONObject(PreviewSelectionForms.settingsText(loaded().components.selections.single()))
        assertEquals(uri, savedSettings.getString("api_base_url"))
        assertEquals(reference, savedSettings.getString("credential_ref"))
    }

    @Test fun legacyRawReadOnlyRecoveryResavesExactlyWithoutNewCaptureAuthority() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("mcp_server")
        field(form, R.string.component_settings).setText(settings("mcp_server", ordinary))
        val historical = capture()
        val raw = settings("mcp_server")
        historical.getBundle(ComponentFormRecovery.KEY)!!.apply {
            putInt("version", 1); putString("baseline", "historical-baseline"); putString("settings", raw)
        }
        recreate(historical)
        repeat(2) {
            val readOnly = review()
            assertTrue(views(readOnly.window!!.decorView).filterIsInstance<EditText>().isEmpty())
            assertTrue(views(readOnly.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == raw && it.isTextSelectable
            })
            val resaved = capture()
            assertEquals(raw, pending(resaved).settings)
            assertEquals(2, resaved.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
            recreate(resaved)
            assertUnchanged(before, bytes)
        }
    }

    @Test fun overBudgetCaptureKeepsTheGenericMarkerWithoutParsingOrSavingAPrefix() {
        save()
        val before = loaded(); val bytes = managedBytes()
        val form = addForm("mcp_server")
        field(form, R.string.component_role).setText("r".repeat(20_000))
        field(form, R.string.component_settings).setText(settings("mcp_server"))
        val state = capture()
        val retained = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), retained.keySet())
        assertTrue(retained.getBoolean("unavailable"))
        recreate(state)
        assertTrue(hasText(R.string.component_recovery_unavailable))
        assertUnchanged(before, bytes)
    }
}
