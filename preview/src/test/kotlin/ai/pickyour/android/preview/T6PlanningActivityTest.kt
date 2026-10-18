package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Simulated native resources, lifecycle and local storage; no installed-device or measured T6 evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class T6PlanningActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private var destroyed = false
    private val browseLabels = listOf(R.string.planning_browse_guardrail, R.string.planning_browse_evaluation,
        R.string.planning_browse_deployment, R.string.planning_browse_monitoring, R.string.planning_browse_cost)

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        finishWork()
    }
    @After fun close() { if (!destroyed) controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun section() = views().filterIsInstance<T6PlanningSection>().single()
    private fun texts() = views(section()).filterIsInstance<TextView>().map { it.text.toString() }
    private fun catalog() = views().filterIsInstance<CatalogBrowseSection>().single()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the planning UI state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(2) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun save() {
        button(R.string.save_draft).performClick()
        await {
            !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.draft_saved)
            }
        }
        finishWork()
        assertTrue(texts().contains(activity.getString(R.string.planning_saved, loaded().revision.toString())))
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        finishWork()
    }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }

    @Test fun coreOnlyPlanningStaysVisibleBeforeSaveAfterSaveAndAfterRecreation() {
        assertTrue(texts().contains(activity.getString(R.string.planning_notice)))
        assertEquals(5, texts().count { it == activity.getString(R.string.planning_choices_unavailable) })
        assertFalse(texts().contains(activity.getString(R.string.planning_choices_missing)))
        save()
        assertTrue(loaded().optionalTiers.values.all { it.activation == Activation.DISABLED })
        assertEquals(5, texts().count { it == activity.getString(R.string.planning_choices_missing) })
        recreate()
        assertEquals(View.VISIBLE, section().visibility)
        assertEquals(5, texts().count { it == activity.getString(R.string.planning_choices_missing) })
        browseLabels.forEach { assertTrue(button(it).isEnabled) }
        for (label in listOf(R.string.planning_guardrail_evidence, R.string.planning_evaluation_evidence,
            R.string.planning_deployment_evidence, R.string.planning_monitoring_evidence, R.string.planning_cost_evidence))
            assertTrue(texts().any { it.contains(activity.getString(label)) })
    }

    @Test fun savedChoicesRemainConfigurationAndPendingEditsNeverReplaceTheirRevision() {
        save()
        val store = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
        val before = (store.load() as DraftLoad.Available).draft
        val entries = activity.assets.open("build.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("selections") }
        val selected = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { node -> PreviewT6Kind.entries.any { it.catalogKind == node.getString("kind") } }
            .map { node -> LocalSelection.fromJson(node.put("enabled", node.getString("kind") != "evaluation_suite").toString().toByteArray()) }
        val saved = (store.save(before.version, before.content.withComponents(DraftSelections(selected))) as DraftSave.Saved).draft
        reopen()
        await { texts().contains(activity.getString(R.string.planning_saved, saved.version.number.toString())) }
        assertEquals(4, texts().count { it == activity.getString(R.string.planning_choices_recorded, 1, 0) })
        assertEquals(1, texts().count { it == activity.getString(R.string.planning_choices_recorded, 0, 1) })
        val retained = disk()
        field(R.string.draft_title).setText("pending title")
        assertTrue(texts().contains(activity.getString(R.string.planning_pending, saved.version.number.toString())))
        recreate()
        assertTrue(texts().contains(activity.getString(R.string.planning_pending, saved.version.number.toString())))
        assertEquals(retained, disk())
        assertEquals(selected, loaded().components.selections)
    }

    @Test fun openingDraftWithholdsOldCountsUntilTheReadCompletes() {
        save()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { started.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(started.await(15, TimeUnit.SECONDS))
        try {
            reopen()
            idle()
            assertTrue(texts().contains(activity.getString(R.string.planning_loading)))
            assertEquals(5, texts().count { it == activity.getString(R.string.planning_choices_unavailable) })
            assertFalse(texts().contains(activity.getString(R.string.planning_choices_missing)))
        } finally { release.countDown() }
        finishWork()
        assertEquals(5, texts().count { it == activity.getString(R.string.planning_choices_missing) })
    }

    @Test fun eachPlanningActionReachesItsExactKindWithoutChangingPendingOrSavedDrafts() {
        save()
        field(R.string.draft_title).setText("retain pending title")
        val retained = disk()
        for ((kind, label) in PreviewT6Kind.entries.zip(browseLabels)) {
            field(R.string.catalog_search).setText("unmatched search")
            button(label).performClick()
            val state = Bundle().also { catalog().saveState(it) }
            assertEquals(kind.catalogKind, state.getString("catalogKind"))
            assertEquals("T6", state.getString("catalogTier"))
            assertEquals("", state.getString("catalogQuery"))
            assertEquals(0, state.getInt("catalogPage"))
            assertEquals("", field(R.string.catalog_search).text.toString())
            assertEquals("retain pending title", field(R.string.draft_title).text.toString())
            assertEquals(retained, disk())
        }
        recreate()
        val state = Bundle().also { catalog().saveState(it) }
        assertEquals("cost_policy", state.getString("catalogKind"))
        assertEquals("retain pending title", field(R.string.draft_title).text.toString())
        val oldAction = button(R.string.planning_browse_guardrail)
        val oldCatalog = catalog()
        controller.pause().stop().destroy()
        destroyed = true
        oldAction.callOnClick()
        assertEquals("cost_policy", Bundle().also { oldCatalog.saveState(it) }.getString("catalogKind"))
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsPlanningActionsAndTheirCatalogDestinationsReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val dp48 = (48 * density).toInt()
        for (label in browseLabels) {
            val action = button(label)
            action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
            idle()
            assertTrue("48dp measured target width: ${action.text}", action.width >= dp48)
            assertTrue("48dp measured target height: ${action.text}", action.height >= dp48)
            assertTrue(action.getGlobalVisibleRect(Rect()))
            assertEquals(0, action.layout.getEllipsisCount(action.layout.lineCount - 1))
            action.performClick()
            val kindButton = views(catalog()).filterIsInstance<Button>().single {
                it.text.toString().startsWith(activity.getString(R.string.catalog_kind, ""))
            }
            assertTrue(kindButton.getGlobalVisibleRect(Rect()))
        }
    }
}
