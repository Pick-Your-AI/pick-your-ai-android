package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
import org.robolectric.shadows.ShadowChoreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual controls and serial-worker callbacks in a simulation; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RevisionComparisonBrowserActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private var fixture = 0

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int, root: View = activity.window.decorView) =
        views(root).filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun texts(window: AlertDialog = dialog()) = views(window.window!!.decorView)
        .filterIsInstance<TextView>().filterNot { it is Button }.map { it.text.toString() }
    private fun controls(window: AlertDialog = dialog()) = views(window.window!!.decorView).filterIsInstance<Button>()
        .filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun click(label: Int, window: AlertDialog = dialog()) {
        button(label, window.window!!.decorView).performClick()
        idle()
    }
    private fun inspect(index: Int, window: AlertDialog = dialog()) {
        controls(window).single {
            it.text.toString().substringAfter("Inspect change ", "").substringBefore(":").toIntOrNull() == index
        }.performClick()
        idle()
    }
    private fun value() = texts().single {
        it.startsWith("text: ") || it == activity.getString(R.string.revision_compare_absent) ||
            it == activity.getString(R.string.context_empty)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishWork() {
        repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() }
    }
    private fun blockWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        return release
    }
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.draft_saved) } }
    }
    private fun prepareSavedHistory() {
        field(R.string.context_objective).setText("saved before")
        save()
        field(R.string.context_objective).setText("saved after")
        save()
    }
    private fun picker(): AlertDialog {
        button(R.string.draft_history).performClick()
        val history = dialog()
        history.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNotSame(history, dialog())
        return dialog()
    }
    private fun select(window: AlertDialog, index: Int) { shadowOf(window).clickOnItem(index); idle() }
    private fun awaitBrowser(from: Long = 0, to: Long = 1) {
        await { dialog().isShowing && texts().contains(activity.getString(R.string.revision_compare_title, from.toString(), to.toString())) }
    }
    private fun openComparison(): AlertDialog {
        select(picker(), 1)
        awaitBrowser()
        return dialog()
    }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun assertState(expected: Bundle) {
        val actual = state()
        val keys = listOf("title", "objective", "contextInput", "selectedId", "selectedVersion",
            "selectedCatalogHash", "draftId", "draftBaseline", "selectionResolved", "draftRequiresReopen",
            "workload.rejectedEdit", "workload.classification") + (0..7).map { "workload." + it }
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, expected.get(it), actual.get(it)) }
    }
    private fun recreate() {
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && button(R.string.draft_history).isEnabled }
    }
    private fun comparison(before: DraftContent, after: DraftContent,
        oldMetadata: Map<String, String> = mapOf("preview.format" to "1", "preview.title" to "fixture")): PreviewRevisionComparison {
        val directory = java.nio.file.Files.createDirectory(activity.noBackupFilesDir.toPath().resolve("comparison-fixture-" + fixture++))
        val store = LocalDraftStore(directory)
        val first = store.save(null, before, oldMetadata) as DraftSave.Saved
        store.save(first.draft.version, after, mapOf("preview.format" to "1", "preview.title" to "fixture"))
        val baseline = (PreviewDraftPersistence(store).load() as PreviewDraftLoad.Available).record
        return requireNotNull(baseline.compareRevision(0))
    }
    private fun settings(values: Map<String, String>) =
        DraftContent(optional = mapOf(OptionalTier.T3 to TierDraft(settings = values)))
    private fun show(result: PreviewRevisionComparison, current: () -> Boolean = { true }): RevisionComparisonBrowser =
        RevisionComparisonBrowser(activity, result, current).also { it.show(); idle() }

    @Test fun actualSavedComparisonPreservesPendingInputsAndDurableBaseline() {
        prepareSavedHistory()
        val baseline = loaded().baselineIdentity
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        ShadowChoreographer.setPaused(true)
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.context_context).setText("")
        field(R.string.workload_budget).setText("12.3400")
        val before = state()
        openComparison()
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_scope)))
        assertTrue(texts().any { it.contains("Before: text: \"saved before\"") && it.contains("After: text: \"saved after\"") })
        inspect(1)
        click(R.string.revision_compare_inspect_before)
        assertEquals("text: \"saved before\"", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"saved after\"", value())
        click(R.string.revision_compare_close)
        assertFalse(dialog().isShowing)
        assertState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun everyChangedFieldAfterTheOldHundredRowLimitIsReachable() {
        val old = (0 until 108).associate { "key." + it.toString().padStart(3, '0') to "before " + it }
        val new = (0 until 108).associate { "key." + it.toString().padStart(3, '0') to "after " + it }
        val result = comparison(settings(old), settings(new))
        assertEquals(108, result.changes.size)
        show(result)
        val seen = mutableListOf<Int>()
        do {
            val entries = controls().filter { it.text.toString().startsWith("Inspect change ") }
            assertTrue(entries.size in 1..8)
            seen += entries.map { it.text.toString().substringAfter("Inspect change ").substringBefore(":").toInt() }
            val next = controls().any { it.text.toString() == activity.getString(R.string.revision_compare_next_changes) }
            if (next) click(R.string.revision_compare_next_changes)
        } while (next)
        assertEquals((1..108).toList(), seen)
        inspect(108)
        click(R.string.revision_compare_inspect_key)
        assertEquals("text: \"key.107\"", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"after 107\"", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_back_changes)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_page, 14, 14, 105, 108, 108)))
        click(R.string.revision_compare_previous_changes)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_page, 13, 14, 97, 104, 108)))
        click(R.string.revision_compare_next_changes)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_page, 14, 14, 105, 108, 108)))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun fullKeysAndLiteralPartsPreserveUnicodeControlsAndDecimalSpelling() {
        val key = "k".repeat(161)
        val raw = "A".repeat(159) + "\uD83D\uDE80\n\u202e\"\\\u2028\u2029" + "z".repeat(180)
        val decimal = "9".repeat(161) + ".0000"
        val result = comparison(settings(mapOf(key to raw)), settings(mapOf(key to decimal)))
        show(result); inspect(1)
        click(R.string.revision_compare_inspect_key)
        assertEquals("text: \"" + "k".repeat(160) + "\"", value())
        click(R.string.revision_compare_next_part)
        assertEquals("text: \"k\"", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_before)
        assertEquals("text: \"" + "A".repeat(159) + "\\ud83d\"", value())
        val first = dialog()
        click(R.string.revision_compare_next_part)
        val second = value()
        assertEquals("text: \"\\ude80\\n\\u202e\\\"\\\\\\u2028\\u2029" + "z".repeat(153) + "\"", second)
        click(R.string.revision_compare_previous_part)
        assertEquals("text: \"" + "A".repeat(159) + "\\ud83d\"", value())
        click(R.string.revision_compare_next_part)
        assertEquals(second, value())
        click(R.string.revision_compare_next_part)
        assertEquals("text: \"" + "z".repeat(27) + "\"", value())
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_value_part, 3, 3, 321, 347, 347)))
        click(R.string.revision_compare_next_part, first)
        assertEquals("text: \"" + "z".repeat(27) + "\"", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"" + "9".repeat(160) + "\"", value())
        click(R.string.revision_compare_next_part)
        assertEquals("text: \"9.0000\"", value())
        assertFalse(texts().any { it.contains("[truncated]") })
        assertEquals(raw, result.changes.single().before)
        assertEquals(decimal, result.changes.single().after)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun absentEmptyAndLiteralSentinelsStayDistinctDuringInspection() {
        val uri = "https://example.invalid/" + "q".repeat(180)
        show(comparison(DraftContent(), DraftContent(objective = "", context = "absent or unanswered", primaryModel = uri)))
        inspect(1); click(R.string.revision_compare_inspect_before)
        assertEquals(activity.getString(R.string.revision_compare_absent), value())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_inspect_after)
        assertEquals(activity.getString(R.string.context_empty), value())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_back_changes)
        inspect(2); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"absent or unanswered\"", value())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_back_changes)
        inspect(3); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"" + uri.take(160) + "\"", value())
        click(R.string.revision_compare_next_part)
        assertEquals("text: \"" + uri.drop(160) + "\"", value())
        views(dialog().window!!.decorView).filterIsInstance<TextView>().forEach {
            assertEquals(0, it.autoLinkMask)
            assertFalse(it.movementMethod is android.text.method.LinkMovementMethod)
        }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun unavailableHistoricalMetadataIsDisclosedWithoutReconstruction() {
        val result = comparison(DraftContent(), DraftContent(), mapOf("preview.format" to "future"))
        assertTrue(result.changes.isEmpty())
        assertFalse(result.guestFieldsComparable)
        assertTrue(result.metadataChanged)
        show(result)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_guest_unknown)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_metadata_changed)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_no_fields)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_scope)))
        assertFalse(controls().any { it.text.toString().startsWith("Inspect change ") })
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun unchangedRevisionHasExplicitEmptyResultWithRetainedContext() {
        val result = comparison(DraftContent(objective = "same"), DraftContent(objective = "same"))
        show(result)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_title, "0", "0")))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_page, 1, 1, 0, 0, 0)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_metadata_same)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_no_fields)))
        assertEquals(listOf(activity.getString(R.string.revision_compare_close)), controls().map { it.text.toString() })
    }

    @Test fun changedComponentRoleAndSettingsKeepTheirExactStableKey() {
        val build = activity.assets.open("build.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val selections = build.getJSONArray("selections")
        val model = (0 until selections.length()).map { selections.getJSONObject(it) }.single { it.getString("kind") == "model" }
        val changed = JSONObject(model.toString()).put("role", "alternate")
        changed.getJSONObject("settings").put("max_output_tokens", 4096)
        fun content(input: JSONObject) = DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(input.toString().toByteArray()))))
        val result = comparison(content(model), content(changed))
        assertEquals(listOf("components.selection.role", "components.selection.settings"), result.changes.map { it.field })
        show(result)
        for (index in 1..2) {
            inspect(index)
            click(R.string.revision_compare_inspect_key)
            assertEquals("text: \"" + model.getString("id") + "\"", value())
            click(R.string.revision_compare_back_field)
            click(R.string.revision_compare_inspect_after)
            assertTrue(value().contains(if (index == 1) "alternate" else "4096"))
            click(R.string.revision_compare_back_field)
            click(R.string.revision_compare_back_changes)
        }
        assertTrue(result.changes.none { it.field.contains("catalog_item") })
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun cancelledAndReplacedComparisonControlsCannotReopenDialogs() {
        val result = comparison(DraftContent(objective = "old"), DraftContent(objective = "n".repeat(400)))
        show(result)
        val oldList = dialog()
        inspect(1)
        val oldField = dialog()
        click(R.string.revision_compare_inspect_after)
        val oldValue = dialog()
        click(R.string.revision_compare_next_part)
        val current = dialog()
        inspect(1, oldList)
        click(R.string.revision_compare_inspect_before, oldField)
        click(R.string.revision_compare_next_part, oldValue)
        click(R.string.revision_compare_back_field, oldValue)
        assertSame(current, dialog())
        assertTrue(current.isShowing)
        current.cancel(); idle()
        click(R.string.revision_compare_next_part, current)
        click(R.string.revision_compare_back_field, current)
        assertSame(current, dialog())
        assertFalse(current.isShowing)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun revokedComparisonAuthorityCannotBecomeCurrentAgain() {
        var authority = true
        val browser = show(comparison(DraftContent(), DraftContent(objective = "new"))) { authority }
        val oldList = dialog()
        authority = false
        inspect(1, oldList)
        assertFalse(oldList.isShowing)
        authority = true
        inspect(1, oldList)
        browser.show(); idle()
        assertSame(oldList, dialog())
        assertFalse(oldList.isShowing)
    }

    @Test fun actualStorageStartRetiresInspectionEvenWhenSavedBaselineIsUnchanged() {
        prepareSavedHistory()
        field(R.string.context_context).setText("pending context")
        val before = state()
        val baseline = loaded().baselineIdentity
        val oldList = openComparison()
        button(R.string.library_open).performClick()
        await { dialog() !== oldList && dialog().isShowing }
        val library = dialog()
        assertFalse(oldList.isShowing)
        inspect(1, oldList)
        assertSame(library, dialog())
        assertTrue(library.isShowing)
        assertEquals(baseline, loaded().baselineIdentity)
        assertState(before)
    }

    @Test fun cancelledPreparationCannotDisplayLateWorkerResult() {
        prepareSavedHistory()
        val picker = picker()
        val release = blockWorker()
        val baseline = loaded().baselineIdentity
        try {
            select(picker, 1)
            val preparing = dialog()
            assertTrue(shadowOf(preparing).message.toString().contains("Preparing local revision 0"))
            preparing.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
            assertFalse(preparing.isShowing)
            release.countDown(); finishWork()
            select(picker, 0)
            assertSame(preparing, dialog())
            assertFalse(preparing.isShowing)
            assertEquals(baseline, loaded().baselineIdentity)
        } finally { release.countDown() }
    }

    @Test fun aNewHistoryRequestRetiresAnOlderQueuedComparison() {
        prepareSavedHistory()
        val original = picker()
        val release = blockWorker()
        try {
            select(original, 1)
            val oldPreparation = dialog()
            val latestPicker = picker()
            select(latestPicker, 0)
            val latestPreparation = dialog()
            assertFalse(oldPreparation.isShowing)
            assertNotSame(oldPreparation, latestPreparation)
            release.countDown(); finishWork()
            awaitBrowser(1, 1)
            assertTrue(texts().contains(activity.getString(R.string.revision_compare_no_fields)))
            val latest = dialog()
            select(original, 1); select(latestPicker, 1)
            assertSame(latest, dialog())
        } finally { release.countDown() }
    }

    @Test fun queuedStorageOperationRejectsComparisonResultWithTheSameSavedBaseline() {
        prepareSavedHistory()
        val original = picker()
        val release = blockWorker()
        val baseline = loaded().baselineIdentity
        try {
            select(original, 1)
            val preparing = dialog()
            button(R.string.library_open).performClick()
            assertFalse(preparing.isShowing)
            release.countDown(); finishWork()
            assertNotSame(preparing, dialog())
            assertTrue(dialog().isShowing)
            assertEquals(activity.getString(R.string.library_open), shadowOf(dialog()).title.toString())
            assertEquals(baseline, loaded().baselineIdentity)
            assertFalse(controls().any { it.text.toString().startsWith("Inspect change ") })
        } finally { release.countDown() }
    }

    @Test fun recreatedActivityRetiresComparisonAndPreservesPendingInputs() {
        prepareSavedHistory()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending recreation")
        val before = state()
        val oldList = openComparison()
        inspect(1)
        val oldField = dialog()
        click(R.string.revision_compare_inspect_after)
        val oldValue = dialog()
        recreate()
        assertFalse(oldValue.isShowing)
        inspect(1, oldList)
        click(R.string.revision_compare_inspect_before, oldField)
        click(R.string.revision_compare_back_field, oldValue)
        assertSame(oldValue, dialog())
        assertState(before)
        assertEquals(baseline, loaded().baselineIdentity)
        openComparison()
        inspect(1); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"saved after\"", value())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextComparisonControlsRemainReachableAndComplete() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        val old = (0 until 10).associate { "key." + it to "before" }
        val new = (0 until 10).associate { "key." + it to "n".repeat(400) }
        show(comparison(settings(old), settings(new)))
        assertMeasuredControls()
        click(R.string.revision_compare_next_changes)
        assertMeasuredControls()
        inspect(10)
        assertMeasuredControls()
        click(R.string.revision_compare_inspect_after)
        click(R.string.revision_compare_next_part)
        assertMeasuredControls()
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_title, "0", "1")))
    }

    private fun assertMeasuredControls() {
        val root = dialog().window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        controls().forEach { control ->
            assertTrue(control.isShown)
            assertTrue(control.height >= (48 * density).toInt())
            assertTrue(control.width >= (48 * density).toInt())
            control.requestRectangleOnScreen(android.graphics.Rect(0, 0, control.width, control.height), true)
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                val viewport = android.graphics.Rect().also(parent::getDrawingRect)
                assertTrue("Complete action viewport: " + control.text + " in " + parent.javaClass.simpleName, viewport.contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.lineCount > 0)
            assertTrue(label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            val left = control.scrollX.toFloat()
            val right = left + control.width - control.compoundPaddingLeft - control.compoundPaddingRight
            repeat(label.lineCount) { line ->
                assertEquals(0, label.getEllipsisCount(line))
                assertTrue(label.getLineLeft(line) >= left && label.getLineRight(line) <= right)
            }
            assertEquals(label.text.length, label.getLineEnd(label.lineCount - 1))
        }
    }
}
