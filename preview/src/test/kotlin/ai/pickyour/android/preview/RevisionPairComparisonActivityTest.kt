package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
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

/** Real native entry, choice and worker callbacks under simulation; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RevisionPairComparisonActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertFalse(button(R.string.revision_compare_pair_open).isEnabled)
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
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun texts(window: AlertDialog = dialog()) = views(window.window!!.decorView)
        .filterIsInstance<TextView>().filterNot { it is Button }.map { it.text.toString() }
    private fun click(label: Int, window: AlertDialog = dialog()) {
        button(label, window.window!!.decorView).performClick(); idle()
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths -> paths.filter { Files.isRegularFile(it) }
        .toList().associate { activity.noBackupFilesDir.toPath().relativize(it).toString() to
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList() } }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit comparison state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
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
    private fun history() {
        for (value in listOf("stored before", "stored after", "unrelated saved head")) {
            field(R.string.context_objective).setText(value); save()
        }
        assertEquals(2L, loaded().revision)
        assertTrue(button(R.string.revision_compare_pair_open).isEnabled)
    }
    private fun chooseBefore(): AlertDialog {
        button(R.string.revision_compare_pair_open).performClick(); idle()
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_choose_before)))
        return dialog()
    }
    private fun select(window: AlertDialog, revision: Long) {
        val rows = window.listView.adapter
        val label = activity.getString(R.string.history_revision, revision.toString())
        val index = (0 until rows.count).single { rows.getItem(it).toString() == label }
        shadowOf(window).clickOnItem(index); idle()
    }
    private fun chooseAfter(from: Long): AlertDialog {
        select(chooseBefore(), from)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_choose_after, from.toString())))
        return dialog()
    }
    private fun openPair(from: Long, to: Long) {
        select(chooseAfter(from), to)
        awaitBrowser(from, to)
    }
    private fun awaitBrowser(from: Long, to: Long) {
        await { dialog().isShowing && texts().contains(activity.getString(R.string.revision_compare_title, from.toString(), to.toString())) }
    }
    private fun inspectObjective() {
        views(dialog().window!!.decorView).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.revision_compare_inspect, 1, "objective")
        }.performClick(); idle()
    }
    private fun literal() = texts().single { it.startsWith("text: ") ||
        it == activity.getString(R.string.revision_compare_absent) || it == activity.getString(R.string.context_empty) }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun assertState(expected: Bundle) {
        val actual = state()
        val keys = listOf("title", "objective", "contextInput", "selectedId", "selectedVersion", "selectedCatalogHash",
            "draftId", "draftBaseline", "selectionResolved", "draftRequiresReopen", "workload.rejectedEdit", "workload.classification") +
            (0..7).map { "workload." + it }
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, expected.get(it), actual.get(it)) }
    }

    @Test fun twoOlderRevisionsExcludeSavedHeadAndPendingInputsWithoutWriting() {
        history()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_currency).setText("invalid")
        val before = state(); val bytes = disk(); val identity = loaded().baselineIdentity
        openPair(0, 1)
        assertTrue(texts().any { it.contains("stored before") && it.contains("stored after") })
        assertTrue(texts().none { it.contains("unrelated saved head") || it.contains("pending objective") })
        inspectObjective()
        click(R.string.revision_compare_inspect_before)
        assertEquals("text: \"stored before\"", literal())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"stored after\"", literal())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_back_changes)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_title, "0", "1")))
        assertState(before); assertEquals(bytes, disk()); assertEquals(identity, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun reversedAndIdenticalPairsRetainTheChosenDirection() {
        history()
        openPair(1, 0); inspectObjective(); click(R.string.revision_compare_inspect_before)
        assertEquals("text: \"stored after\"", literal())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"stored before\"", literal())
        click(R.string.revision_compare_close)
        openPair(1, 1)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_no_fields)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_metadata_same)))
        assertEquals(2L, loaded().revision)
    }

    @Test fun changingBeforeRevisionRetiresBothOldPickerControls() {
        history()
        val oldBefore = chooseBefore(); select(oldBefore, 0)
        val oldAfter = dialog()
        click(R.string.revision_compare_back_to_before, oldAfter)
        val newBefore = dialog()
        assertFalse(oldBefore.isShowing); assertFalse(oldAfter.isShowing)
        select(oldBefore, 2); select(oldAfter, 2)
        assertSame(newBefore, dialog())
        select(newBefore, 1)
        val newAfter = dialog()
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_choose_after, "1")))
        select(oldAfter, 2); assertSame(newAfter, dialog())
        select(newAfter, 0); awaitBrowser(1, 0)
    }

    @Test fun cancellationAtEitherPickerOrPreparationCannotResumeThePair() {
        history()
        val bytes = disk()
        for (stage in 0..2) {
            val before = chooseBefore()
            if (stage > 0) select(before, 0)
            val after = if (stage > 0) dialog() else null
            val release = if (stage == 2) blockWorker() else null
            try {
                if (stage == 2) select(after!!, 1)
                val cancelled = dialog()
                cancelled.cancel(); idle()
                release?.countDown(); finishWork()
                select(before, 2); after?.let { select(it, 2) }
                assertSame(cancelled, dialog()); assertFalse(cancelled.isShowing)
            } finally { release?.countDown() }
        }
        assertEquals(bytes, disk())
    }

    @Test fun newHistoryRequestRetiresEitherRetainedRevisionPicker() {
        history()
        for (after in listOf(false, true)) {
            val old = if (after) chooseAfter(0) else chooseBefore()
            button(R.string.draft_history).performClick(); idle()
            val current = dialog()
            assertFalse(old.isShowing)
            select(old, 1)
            assertSame(current, dialog()); assertTrue(current.isShowing)
            current.cancel(); idle()
        }
    }

    @Test fun storageReadRetiresAfterPickerWithoutChangingTheSavedBaseline() {
        history()
        field(R.string.context_context).setText("pending context")
        val before = state(); val baseline = loaded().baselineIdentity
        val after = chooseAfter(0)
        button(R.string.library_open).performClick()
        await { dialog() !== after && dialog().isShowing }
        val library = dialog()
        assertFalse(after.isShowing)
        select(after, 1)
        assertSame(library, dialog())
        assertEquals(baseline, loaded().baselineIdentity); assertState(before)
        library.cancel(); idle()
        openPair(0, 1)
    }

    @Test fun newerPairWinsWhenOlderWorkerReturnsAfterAnotherSelection() {
        history()
        val oldAfter = chooseAfter(0)
        val release = blockWorker()
        try {
            select(oldAfter, 1)
            val oldPreparing = dialog()
            val newAfter = chooseAfter(1)
            select(newAfter, 0)
            val newPreparing = dialog()
            assertFalse(oldPreparing.isShowing); assertNotSame(oldPreparing, newPreparing)
            release.countDown(); finishWork(); awaitBrowser(1, 0)
            val current = dialog()
            select(oldAfter, 2); select(newAfter, 2)
            assertSame(current, dialog())
            inspectObjective(); click(R.string.revision_compare_inspect_after)
            assertEquals("text: \"stored before\"", literal())
        } finally { release.countDown() }
    }

    @Test fun recreationCancelsTheSelectedBeforeRevisionAndPreservesPendingInputs() {
        history()
        field(R.string.context_context).setText("pending across recreation")
        val expected = state(); val bytes = disk()
        val after = chooseAfter(0)
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.revision_compare_pair_open).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertFalse(after.isShowing)
        select(after, 1); assertFalse(after.isShowing)
        assertState(expected); assertEquals(bytes, disk())
        openPair(0, 1)
    }

    @Test fun unavailableAfterMetadataIsNotFilledFromTheCurrentSavedHead() {
        val store = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
        val first = store.save(null, DraftContent("old before"), mapOf("preview.format" to "1", "preview.title" to "before title")) as DraftSave.Saved
        val second = store.save(first.draft.version, DraftContent("old after"), mapOf("preview.format" to "future")) as DraftSave.Saved
        store.save(second.draft.version, DraftContent("current head"), mapOf("preview.format" to "1", "preview.title" to "do not substitute"))
        button(R.string.reopen_draft).performClick(); idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.revision_compare_pair_open).isEnabled }
        openPair(0, 1)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_guest_unknown)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_metadata_changed)))
        assertTrue(texts().none { it.contains("guest.title") || it.contains("do not substitute") })
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_scope)))
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextRetainsBothPickerIdentitiesAndCompleteComparison() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        history()
        measure(activity.window.decorView)
        assertCompleteLabel(button(R.string.revision_compare_pair_open))
        val before = chooseBefore()
        assertMeasuredControls(before)
        select(before, 0)
        val after = dialog()
        assertMeasuredControls(after)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_choose_after, "0")))
        select(after, 1); awaitBrowser(0, 1)
        assertMeasuredControls(dialog())
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_title, "0", "1")))
        inspectObjective(); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"stored after\"", literal())
        assertMeasuredControls(dialog())
    }

    private fun assertMeasuredControls(window: AlertDialog) {
        val root = window.window!!.decorView
        measure(root)
        val density = activity.resources.displayMetrics.density
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val controls = views(root).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
        for (control in controls) {
            assertTrue(control.isShown)
            assertTrue(control.height >= (48 * density).toInt()); assertTrue(control.width >= (48 * density).toInt())
            control.requestRectangleOnScreen(android.graphics.Rect(0, 0, control.width, control.height), true)
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                assertTrue("Complete picker action: " + control.text,
                    android.graphics.Rect().also(parent::getDrawingRect).contains(bounds))
                child = parent
            }
            assertCompleteLabel(control)
        }
        val labels = views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() && it !is Button }
        labels.forEach(::assertCompleteLabel)
        window.listView?.let { rows ->
            assertEquals(3, rows.adapter.count)
            assertTrue(rows.childCount > 0)
            repeat(rows.childCount) { index ->
                assertTrue(rows.getChildAt(index).height >= (48 * density).toInt())
            }
        }
    }

    private fun measure(root: View) {
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun assertCompleteLabel(view: TextView) {
        val label = requireNotNull(view.layout)
        assertTrue("Full label height: " + view.text, label.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        val left = view.scrollX.toFloat()
        val right = left + view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        repeat(label.lineCount) { line ->
            assertEquals("No ellipsis: " + view.text, 0, label.getEllipsisCount(line))
            assertTrue("Full label width: " + view.text, label.getLineLeft(line) >= left && label.getLineRight(line) <= right)
        }
        assertEquals(label.text.length, label.getLineEnd(label.lineCount - 1))
    }
}
