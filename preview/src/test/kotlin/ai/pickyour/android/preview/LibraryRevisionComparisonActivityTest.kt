package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.Intent
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual local picker, worker and browser controls in simulation; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryRevisionComparisonActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val first = PreviewDraftId("guest-draft")
    private val second = PreviewDraftId("draft-00000000-0000-0000-0000-000000000002")

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertTrue(button(R.string.library_compare_open).isEnabled)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun controls(window: AlertDialog) = views(window.window!!.decorView).filterIsInstance<Button>()
        .filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
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
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun click(label: Int, window: AlertDialog = dialog()) {
        button(label, window.window!!.decorView).performClick(); idle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit saved draft comparison")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun blockWorker(): CountDownLatch {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        return release
    }
    private fun library() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
    private fun append(id: PreviewDraftId, title: String, objective: String): PreviewStoredDraft =
        worker().submit<PreviewStoredDraft> {
            // Serialize the one fixture transaction with the Activity's owned background reads.
            val path = activity.noBackupFilesDir.toPath().resolve(id.value)
            Files.createDirectories(path)
            val adapter = PreviewDraftPersistence(LocalDraftStore(path))
            val record = when (val loaded = adapter.load()) {
                PreviewDraftLoad.Missing -> null
                is PreviewDraftLoad.Available -> loaded.record
                is PreviewDraftLoad.Failed -> error("Fixture load failed: ${loaded.reason}")
            }
            when (val saved = adapter.save(record, PreviewDraftFields(title), PreviewDraftContext(objective))) {
                is PreviewDraftSave.Saved -> saved.record
                else -> error("Fixture save was not acknowledged: $saved")
            }
        }.get(15, TimeUnit.SECONDS)
    private fun loaded(id: PreviewDraftId = first) =
        (readDraftAfterOwnedWork(activity) { library().open(id) } as PreviewDraftLoad.Available).record
    private fun histories() {
        append(first, "before title", "saved before")
        append(first, "first unrelated head title", "first unrelated head")
        append(second, "after title", "saved after")
        append(second, "second unrelated head title", "second unrelated head")
        library().list()
        button(R.string.reopen_draft).performClick(); idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.draft_history).isEnabled }
        assertEquals("first unrelated head", field(R.string.context_objective).text.toString())
    }
    private fun start(): AlertDialog {
        button(R.string.library_compare_open).performClick(); idle()
        await { dialog().isShowing && texts().contains(activity.getString(R.string.library_compare_choose_before)) }
        return dialog()
    }
    private fun choose(label: String): AlertDialog {
        repeat(9) {
            val window = dialog()
            val target = controls(window).singleOrNull { it.text.toString() == label }
            if (target != null) { target.performClick(); idle(); return dialog() }
            val next = controls(window).singleOrNull { it.text.toString() == activity.getString(R.string.library_compare_next_choices) }
            assertNotNull("Choice was omitted: $label", next)
            next!!.performClick(); idle()
        }
        error("Exceeded bounded choice pages")
    }
    private fun chooseDraft(id: PreviewDraftId) = choose(activity.getString(R.string.library_compare_pick_draft, id.value))
    private fun chooseRevision(number: Long) = choose(activity.getString(R.string.library_compare_pick_revision, number.toString()))
    private fun pair(from: Long = 0, to: Long = 0, before: PreviewDraftId = first, after: PreviewDraftId = second) {
        start(); chooseDraft(before); chooseRevision(from); chooseDraft(after); chooseRevision(to)
        awaitBrowser()
    }
    private fun awaitBrowser() = await { dialog().isShowing && texts().contains(activity.getString(R.string.library_compare_scope)) }
    private fun assertSources(from: Long, to: Long, before: PreviewDraftId = first, after: PreviewDraftId = second) {
        assertTrue(texts().contains(activity.getString(R.string.library_compare_before_source, before.value, from.toString())))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_after_source, after.value, to.toString())))
    }
    private fun inspectObjective() {
        controls(dialog()).single { it.text.toString() == activity.getString(R.string.revision_compare_inspect, 1, "objective") }
            .performClick(); idle()
    }
    private fun literal() = texts().single { it.startsWith("text: ") ||
        it == activity.getString(R.string.revision_compare_absent) || it == activity.getString(R.string.context_empty) }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
    } }
    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun assertState(expected: Bundle) {
        val actual = state()
        val keys = listOf("title", "objective", "contextInput", "selectedId", "selectedVersion", "selectedCatalogHash",
            "draftId", "draftBaseline", "selectionResolved", "draftRequiresReopen", "workload.rejectedEdit", "workload.classification") +
            (0..7).map { "workload." + it }
        @Suppress("DEPRECATION")
        keys.forEach { assertEquals(it, expected.get(it), actual.get(it)) }
    }

    @Test fun actualPauseRetiresLibraryReadAndValueControlsWithoutReopening() {
        histories()
        finishWork()
        field(R.string.draft_title).setText("pending library pause")
        field(R.string.context_objective).setText("pending library objective")
        field(R.string.workload_currency).setText("invalid")
        val before = state()
        val bytes = disk()
        val left = loaded().baselineIdentity
        val right = loaded(second).baselineIdentity
        val entry = button(R.string.library_compare_open)
        val release = blockWorker()
        lateinit var loading: AlertDialog
        try {
            entry.performClick(); idle()
            loading = dialog()
            assertTrue(loading.isShowing)
            assertTrue(texts().contains(activity.getString(R.string.library_compare_loading)))
            controller.pause()
            try {
                assertFalse("Pause must retire the pending library comparison", loading.isShowing)
                release.countDown()
                finishWork()
                entry.performClick()
                finishWork()
                assertSame("A late read or paused entry cannot open a library picker", loading, dialog())
                assertFalse(dialog().isShowing)
                assertState(before)
                assertEquals(bytes, disk())
            } finally {
                controller.resume()
            }
        } finally {
            release.countDown()
        }
        finishWork()
        assertSame("Resume cannot revive the captured library request", loading, dialog())
        assertFalse(dialog().isShowing)

        pair()
        inspectObjective()
        click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"saved after\"", literal())
        val obsolete = dialog()
        val retained = controls(obsolete)
        assertTrue(retained.isNotEmpty())
        controller.pause()
        try {
            assertFalse("Pause must retire the captured library value owner", obsolete.isShowing)
            retained.forEach { it.performClick() }
            entry.performClick()
            finishWork()
            assertSame(obsolete, dialog())
            assertFalse(dialog().isShowing)
        } finally {
            controller.resume()
        }
        idle()
        assertSame(obsolete, dialog())
        assertFalse(dialog().isShowing)
        pair(before = second, after = first)
        assertSources(0, 0, before = second, after = first)
        val fresh = dialog()
        retained.forEach { it.performClick() }
        finishWork()
        assertSame("Retired library value controls cannot replace the fresh pair", fresh, dialog())
        assertTrue(fresh.isShowing)
        assertState(before)
        assertEquals(bytes, disk())
        assertEquals(left, loaded().baselineIdentity)
        assertEquals(right, loaded(second).baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun explicitSavedDraftsKeepBothOlderSourcesAndPendingInputsThroughFullValueInspection() {
        histories()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_currency).setText("invalid")
        val before = state(); val bytes = disk(); val left = loaded().baselineIdentity; val right = loaded(second).baselineIdentity
        pair(); assertSources(0, 0)
        assertTrue(texts().contains(activity.getString(R.string.library_compare_captured_history, left)))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_captured_history, right)))
        assertTrue(texts().any { it.contains("saved before") && it.contains("saved after") })
        assertTrue(texts().none { it.contains("unrelated head") || it.contains("pending objective") })
        inspectObjective(); assertSources(0, 0)
        click(R.string.revision_compare_inspect_before); assertSources(0, 0)
        assertEquals("text: \"saved before\"", literal())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_inspect_after)
        assertSources(0, 0); assertEquals("text: \"saved after\"", literal())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_back_changes)
        assertSources(0, 0); assertState(before); assertEquals(bytes, disk())
        assertEquals(left, loaded().baselineIdentity); assertEquals(right, loaded(second).baselineIdentity)
        assertEquals(PreviewActiveRead.None, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun reversedSameAndReselectedSourcesNeverReuseAnEarlierPickerDirection() {
        histories()
        val oldDraft = start(); chooseDraft(first)
        val oldRevision = dialog(); chooseRevision(0)
        val oldAfter = dialog(); click(R.string.library_compare_change_before)
        val freshDraft = dialog()
        for (old in listOf(oldDraft, oldRevision, oldAfter)) {
            assertFalse(old.isShowing); controls(old).forEach { it.performClick() }; idle(); assertSame(freshDraft, dialog())
        }
        chooseDraft(second); chooseRevision(0); chooseDraft(first); chooseRevision(0); awaitBrowser()
        assertSources(0, 0, second, first)
        inspectObjective(); click(R.string.revision_compare_inspect_before)
        assertEquals("text: \"saved after\"", literal())
        click(R.string.revision_compare_close)
        pair(1, 1, first, first); assertSources(1, 1, first, first)
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_no_fields)))
        assertTrue(texts().contains(activity.getString(R.string.revision_compare_metadata_same)))
    }

    @Test fun cancellationAtEveryPickerAndEitherWorkerBoundaryCannotResumeInspection() {
        histories(); val bytes = disk()
        for (stage in 0..5) {
            val release = if (stage == 0) blockWorker() else null
            var compareRelease: CountDownLatch? = null
            try {
                if (stage == 0) { button(R.string.library_compare_open).performClick(); idle() } else start()
                val obsolete = mutableListOf(dialog())
                if (stage >= 2) { chooseDraft(first); obsolete += dialog() }
                if (stage >= 3) { chooseRevision(0); obsolete += dialog() }
                if (stage >= 4) { chooseDraft(second); obsolete += dialog() }
                if (stage == 5) { compareRelease = blockWorker(); chooseRevision(0); obsolete += dialog() }
                val cancelled = dialog(); cancelled.cancel(); idle()
                release?.countDown(); compareRelease?.countDown(); finishWork()
                obsolete.forEach { window -> controls(window).forEach { it.performClick() }; idle() }
                assertSame(cancelled, dialog()); assertFalse(cancelled.isShowing)
            } finally { release?.countDown(); compareRelease?.countDown() }
        }
        assertEquals(bytes, disk())
    }

    @Test fun replacingALoadOrComparisonWithHistoryRejectsTheLateResult() {
        histories(); val bytes = disk()
        for (preparing in listOf(false, true)) {
            if (preparing) { start(); chooseDraft(first); chooseRevision(0); chooseDraft(second) }
            val release = blockWorker()
            try {
                if (preparing) chooseRevision(0) else { button(R.string.library_compare_open).performClick(); idle() }
                val obsolete = dialog()
                button(R.string.draft_history).performClick(); idle()
                val history = dialog(); assertFalse(obsolete.isShowing)
                release.countDown(); finishWork()
                controls(obsolete).forEach { it.performClick() }; idle()
                assertSame(history, dialog()); assertTrue(history.isShowing); history.cancel(); idle()
            } finally { release.countDown() }
        }
        assertEquals(bytes, disk())
    }

    @Test fun startingStorageReadRetiresPickerAndBrowserEvenWithIdenticalSavedBaseline() {
        histories(); val baseline = loaded().baselineIdentity; val bytes = disk()
        for (browser in listOf(false, true)) {
            if (browser) pair() else { start(); chooseDraft(first) }
            val obsolete = dialog(); val retained = controls(obsolete)
            button(R.string.library_open).performClick(); idle()
            assertFalse(obsolete.isShowing); finishWork()
            val current = dialog(); retained.forEach { it.performClick() }; idle()
            assertSame(current, dialog()); assertTrue(current.isShowing); current.cancel(); idle()
        }
        assertEquals(baseline, loaded().baselineIdentity); assertEquals(bytes, disk())
    }

    @Test fun recreationRetiresCapturedPairAndRetainedControlsWithoutSavingPendingInputs() {
        histories()
        field(R.string.draft_title).setText("pending recreation")
        field(R.string.workload_currency).setText("invalid")
        val before = state(); val bytes = disk()
        pair(); inspectObjective(); click(R.string.revision_compare_inspect_after)
        val obsolete = dialog(); val retained = controls(obsolete)
        val saved = Bundle(); controller.pause().saveInstanceState(saved).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.library_compare_open).isEnabled && button(R.string.retry_catalog).isEnabled }
        retained.forEach { it.performClick() }; idle()
        assertFalse(obsolete.isShowing); assertSame(obsolete, dialog())
        assertState(before); assertEquals(bytes, disk()); assertFalse(button(R.string.save_draft).isEnabled)
        pair(); assertSources(0, 0)
    }

    @Test fun capturedSourcesDoNotFollowLaterWritesOrDeletedDrafts() {
        histories(); val left = loaded().baselineIdentity; val right = loaded(second)
        start(); chooseDraft(first); chooseRevision(0)
        append(first, "later title", "later unrelated writer")
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(second, right))
        val bytes = disk()
        chooseDraft(second); chooseRevision(0); awaitBrowser(); assertSources(0, 0)
        assertTrue(texts().contains(activity.getString(R.string.library_compare_captured_history, left)))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_captured_history, right.baselineIdentity)))
        assertTrue(texts().any { it.contains("saved before") && it.contains("saved after") })
        assertTrue(texts().none { it.contains("later unrelated writer") })
        assertEquals(bytes, disk()); assertEquals(2L, loaded().revision)
    }

    @Test fun failedAndUnavailableLibraryReadsKeepExplicitRecoveryWithoutChoosingAnotherDraft() {
        histories()
        FileChannel.open(activity.noBackupFilesDir.toPath().resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                button(R.string.library_compare_open).performClick(); idle(); finishWork()
                assertTrue(texts().contains(activity.getString(R.string.library_compare_failed)))
                assertEquals(listOf(activity.getString(R.string.revision_compare_close)), controls(dialog()).map { it.text.toString() })
            }
        }
        click(R.string.revision_compare_close)
        val unavailable = PreviewDraftId("draft-00000000-0000-0000-0000-000000000003")
        Files.createDirectory(activity.noBackupFilesDir.toPath().resolve(unavailable.value))
        start()
        assertTrue(texts().contains(activity.getString(R.string.library_local_id, unavailable.value)))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_unavailable)))
        assertTrue(controls(dialog()).none { it.text.toString() == activity.getString(R.string.library_compare_pick_draft, unavailable.value) })
        chooseDraft(first); chooseRevision(0); chooseDraft(second); chooseRevision(0); awaitBrowser(); assertSources(0, 0)
    }

    @Test fun everyDraftAndRetainedRevisionRemainsReachableAcrossBoundedChoicePages() {
        histories()
        for (index in 3..11) append(PreviewDraftId("draft-00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"),
            "title $index", "objective $index")
        for (index in 2..10) append(first, "retained title $index", "retained objective $index")
        start()
        val expected = (library().list() as PreviewLibraryRead.Available).entries.map { activity.getString(R.string.library_compare_pick_draft, it.id.value) }.toSet()
        val reached = mutableSetOf<String>()
        while (true) {
            reached += controls(dialog()).map { it.text.toString() }.filter { it in expected }
            if (controls(dialog()).none { it.text.toString() == activity.getString(R.string.library_compare_next_choices) }) break
            click(R.string.library_compare_next_choices)
        }
        assertEquals(expected, reached)
        chooseDraft(first)
        val revisions = mutableSetOf<String>()
        while (true) {
            revisions += controls(dialog()).map { it.text.toString() }.filter { it.startsWith("Choose revision ") }
            if (controls(dialog()).none { it.text.toString() == activity.getString(R.string.library_compare_next_choices) }) break
            click(R.string.library_compare_next_choices)
        }
        assertEquals((0L..10L).map { activity.getString(R.string.library_compare_pick_revision, it.toString()) }.toSet(), revisions)
        chooseRevision(0); chooseDraft(second); chooseRevision(0); awaitBrowser(); assertSources(0, 0)
    }

    @Test fun unsupportedColdEntryCannotInspectSavedLocalDrafts() {
        histories(); val bytes = disk()
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java, Intent(Intent.ACTION_VIEW)).setup()
        await { button(R.string.retry_catalog).isEnabled }
        assertFalse(button(R.string.library_compare_open).isEnabled)
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.library_compare_open).performClick(); idle(); finishWork()
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog()); assertEquals(bytes, disk())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    fun narrowRtlLargeTextKeepsSourceIdentitiesAndChoiceTargetsComplete() {
        histories()
        RuntimeEnvironment.setFontScale(2f)
        try {
            fun assertLayout(window: AlertDialog) {
                assertTrue(window.isShowing)
                val decor = window.window!!.decorView
                assertEquals(View.LAYOUT_DIRECTION_RTL, decor.layoutDirection)
                val width = (280 * activity.resources.displayMetrics.density).toInt()
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, 1000)
                for (button in controls(window)) {
                    assertTrue(button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    val layout = requireNotNull(button.layout)
                    assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                    assertTrue(button.isFocusable)
                }
                for (text in views(decor).filterIsInstance<TextView>().filterNot { it is Button }) {
                    val layout = text.layout ?: continue
                    assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                }
            }
            start(); assertLayout(dialog())
            chooseDraft(first); assertLayout(dialog())
            chooseRevision(0); chooseDraft(second); chooseRevision(0); awaitBrowser(); assertLayout(dialog())
            assertSources(0, 0)
            assertTrue(texts().contains(activity.getString(R.string.library_compare_saved_title, "text: \u200f\u202a\"before title\"\u202c\u200f")))
            assertTrue(texts().contains(activity.getString(R.string.library_compare_saved_title, "text: \u200f\u202a\"after title\"\u202c\u200f")))
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
