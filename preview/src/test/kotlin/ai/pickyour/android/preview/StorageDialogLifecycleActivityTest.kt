package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real local storage and serial-worker callbacks in an SDK 28 Activity simulation. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StorageDialogLifecycleActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val library get() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
    private val original = PreviewDraftId("guest-draft")
    private lateinit var baseline: String
    private lateinit var active: PreviewActiveRead
    private var paused = false

    @Before fun prepareAcknowledgedDraftAndPendingInput() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertEquals(PreviewDraftLoad.Missing, library.openLegacy().load())
        field(R.string.draft_title).setText("saved title")
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) }
        finishWork()
        baseline = loaded(original).baselineIdentity
        active = selected()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_budget).setText("12.3400")
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @After fun close() {
        if (!paused) controller.pause()
        controller.stop().destroy()
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    private fun button(label: Int): Button = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }

    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }

    private fun hasText(label: Int) = views().filterIsInstance<TextView>()
        .any { it.text.toString() == activity.getString(label) }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun selected() = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun loaded(id: PreviewDraftId) =
        (readDraftAfterOwnedWork(activity) { library.open(id) } as PreviewDraftLoad.Available).record
    private fun entries() = (readLibraryAfterOwnedWork(activity) { library.list() } as PreviewLibraryRead.Available).entries
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit storage UI state")
    }

    private fun finishWork() {
        // Creation acknowledgement submits selection/open work; observe only after both real phases.
        repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() }
    }

    private fun blockWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        return release
    }

    private fun pause() { controller.pause(); paused = true }
    private fun resume() { controller.resume(); paused = false }

    private fun newConfirmation(): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.library_new).performClick()
        idle()
        return dialog().also { assertNotSame(previous, it); assertTrue(it.isShowing) }
    }

    private fun assertPendingIdentityUnchanged() {
        assertEquals("A retired confirmation must not create a durable draft", listOf(original), entries().map { it.id })
        assertEquals(baseline, loaded(original).baselineIdentity)
        assertEquals(active, selected())
        assertEquals(original, currentId())
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertTrue(hasText(R.string.draft_unsaved))
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    private fun assertNewDraftWasCreatedAndSelected() {
        val chosen = (selected() as PreviewActiveRead.Selected).id
        assertNotEquals(original, chosen)
        assertEquals(setOf(original, chosen), entries().map { it.id }.toSet())
        assertEquals(baseline, loaded(original).baselineIdentity)
        assertEquals("", loaded(chosen).fields.title)
        assertEquals(chosen, currentId())
        assertEquals("", field(R.string.draft_title).text.toString())
        assertTrue(hasText(R.string.draft_saved))
        assertTrue(button(R.string.library_open).isEnabled)
    }

    @Test fun pausedNewConfirmationCannotCreateOrSwitchDraftAfterResume() {
        val confirmation = newConfirmation()
        val create = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        pause()
        val showingAfterPause = confirmation.isShowing
        resume()
        create.performClick()
        finishWork()
        println("Retained New callback replay: showing after pause=$showingAfterPause, active=${selected()}, current=${currentId()}")
        assertPendingIdentityUnchanged()
        assertFalse(confirmation.isShowing)
    }

    @Test fun foregroundNewConfirmationCreatesAndSelectsItsActualDraft() {
        val confirmation = newConfirmation()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertNewDraftWasCreatedAndSelected()
        assertFalse(confirmation.isShowing)
    }

    @Test fun admittedNewCreationStillCompletesAndSelectsAcrossPause() {
        val confirmation = newConfirmation()
        val release = blockWorker()
        try {
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertFalse("The real confirmation must admit its queued operation before pause", button(R.string.library_open).isEnabled)
            pause()
        } finally {
            release.countDown()
        }
        finishWork()
        assertNewDraftWasCreatedAndSelected()
        assertFalse(confirmation.isShowing)
    }

    private fun operation() = PreviewActivity::class.java.getDeclaredField("storageAuthority")
        .apply { isAccessible = true }.get(activity)

    private fun openLibrary(): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.library_open).performClick()
        finishWork()
        return dialog().also { assertNotSame(previous, it); assertTrue(it.isShowing) }
    }

    private fun openEntry(): AlertDialog {
        val picker = openLibrary()
        shadowOf(picker).clickOnItem(0)
        idle()
        return dialog().also { assertNotSame(picker, it); assertTrue(it.isShowing) }
    }

    private fun deleteConfirmation(): AlertDialog {
        val entry = openEntry()
        entry.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        return dialog().also { assertNotSame(entry, it); assertTrue(it.isShowing) }
    }

    @Test fun queuedLibraryReadCannotPublishAfterPauseAndResume() {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        val release = blockWorker()
        try {
            button(R.string.library_open).performClick()
            idle()
            assertFalse("The real library read must be queued before pause", button(R.string.library_open).isEnabled)
            pause()
            resume()
        } finally {
            release.countDown()
        }
        finishWork()
        assertSame("A retired read must not publish a window after resume", previous, ShadowAlertDialog.getLatestAlertDialog())
        assertPendingIdentityUnchanged()

        val fresh = openEntry()
        fresh.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(original, currentId())
        assertEquals(PreviewActiveRead.Selected(original), selected())
        assertEquals("saved title", field(R.string.draft_title).text.toString())
        assertEquals(baseline, loaded(original).baselineIdentity)
        assertFalse(fresh.isShowing)
    }

    @Test fun pausedLibraryPickerCannotOpenAChildOrBorrowFreshWindowAuthority() {
        val picker = openLibrary()
        pause()
        shadowOf(picker).clickOnItem(0)
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(picker, dialog())
        assertFalse(picker.isShowing)
        resume()

        val fresh = newConfirmation()
        shadowOf(picker).clickOnItem(0)
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(fresh, dialog())
        assertTrue(fresh.isShowing)
    }

    @Test fun pausedLibraryEntryCannotOpenOrDeleteItsCapturedDraft() {
        val entry = openEntry()
        val open = entry.getButton(AlertDialog.BUTTON_POSITIVE)
        val delete = entry.getButton(AlertDialog.BUTTON_NEUTRAL)
        pause()
        resume()
        open.performClick()
        delete.performClick()
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(entry, dialog())
        assertFalse(entry.isShowing)
    }

    @Test fun pausedDeleteConfirmationCannotDeleteItsAcknowledgedDraft() {
        val confirmation = deleteConfirmation()
        val delete = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        pause()
        resume()
        delete.performClick()
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(confirmation, dialog())
        assertFalse(confirmation.isShowing)
    }

    @Test fun pausedDuplicateAndRecoveryConfirmationsCannotCreateOrSwitchDraft() {
        for (label in listOf(R.string.library_duplicate, R.string.recovery_copy)) {
            button(label).performClick()
            idle()
            val confirmation = dialog()
            assertTrue(confirmation.isShowing)
            val confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
            pause()
            resume()
            confirm.performClick()
            finishWork()
            assertPendingIdentityUnchanged()
            assertSame(confirmation, dialog())
            assertFalse(confirmation.isShowing)
        }
    }

    @Test fun pausedReopenCannotReadOrDiscardPendingInputUntilFreshConsent() {
        button(R.string.reopen_draft).performClick()
        idle()
        val confirmation = dialog()
        assertTrue(confirmation.isShowing)
        val before = operation()
        val reopen = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        val retryKeepingEdits = confirmation.getButton(AlertDialog.BUTTON_NEUTRAL)
        pause()
        retryKeepingEdits.performClick()
        finishWork()
        assertSame("A background retained Retry must not dispatch another read", before, operation())
        resume()
        reopen.performClick()
        finishWork()
        assertSame("Resume must not revive the old Reopen", before, operation())
        assertPendingIdentityUnchanged()
        assertFalse(confirmation.isShowing)

        button(R.string.reopen_draft).performClick()
        idle()
        val fresh = dialog()
        assertNotSame(confirmation, fresh)
        assertTrue(fresh.isShowing)
        fresh.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertNotSame(before, operation())
        assertEquals("saved title", field(R.string.draft_title).text.toString())
        assertEquals("", field(R.string.context_objective).text.toString())
        assertEquals(original, currentId())
        assertEquals(active, selected())
        assertEquals(baseline, loaded(original).baselineIdentity)
    }

    @Test fun pausedStorageEntryButtonsCannotPresentOrQueueAnOperation() {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        val before = operation()
        pause()
        for (label in listOf(R.string.library_open, R.string.library_new, R.string.library_duplicate,
            R.string.recovery_copy, R.string.reopen_draft)) {
            button(label).performClick()
            finishWork()
            assertSame("Paused entry must not start storage work", before, operation())
            assertSame("Paused entry must not show a dialog", previous, ShadowAlertDialog.getLatestAlertDialog())
            assertPendingIdentityUnchanged()
        }
        resume()
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(newConfirmation().isShowing)
    }

    @Test fun replacementAndDismissalCannotTransferStorageConfirmationAuthority() {
        val older = newConfirmation()
        val oldCreate = older.getButton(AlertDialog.BUTTON_POSITIVE)
        button(R.string.library_duplicate).performClick()
        idle()
        val fresh = dialog()
        assertNotSame(older, fresh)
        assertFalse(older.isShowing)
        assertTrue(fresh.isShowing)
        oldCreate.performClick()
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(fresh, dialog())
        assertTrue(fresh.isShowing)

        val duplicate = fresh.getButton(AlertDialog.BUTTON_POSITIVE)
        fresh.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        duplicate.performClick()
        oldCreate.performClick()
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(fresh, dialog())
        assertFalse(fresh.isShowing)
    }

    @Test fun inspectionEntryAndStorageStartRetireUnadmittedConfirmation() {
        val older = newConfirmation()
        button(R.string.draft_history).performClick()
        idle()
        val history = dialog()
        assertNotSame(older, history)
        assertTrue(history.isShowing)
        assertFalse(older.isShowing)
        older.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertPendingIdentityUnchanged()
        assertSame(history, dialog())
        history.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()

        val beforeSave = newConfirmation()
        button(R.string.save_draft).performClick()
        finishWork()
        assertTrue(hasText(R.string.draft_saved))
        val savedPending = loaded(original).baselineIdentity
        assertNotEquals(baseline, savedPending)
        assertFalse(beforeSave.isShowing)
        beforeSave.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(listOf(original), entries().map { it.id })
        assertEquals(savedPending, loaded(original).baselineIdentity)
        assertEquals(active, selected())
        assertEquals(original, currentId())
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
    }

    @Test fun admittedDeleteCompletesWhilePausedWithoutPublishingAResultDialog() {
        val confirmation = deleteConfirmation()
        val release = blockWorker()
        try {
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertFalse("The real delete must be admitted before pause", button(R.string.library_open).isEnabled)
            pause()
        } finally {
            release.countDown()
        }
        finishWork()
        assertTrue(entries().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { library.open(original) }
        assertFalse(java.nio.file.Files.exists(activity.noBackupFilesDir.toPath().resolve(original.value),
            java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(active, selected())
        assertEquals(original, currentId())
        assertTrue(hasText(R.string.library_deleted_editor))
        assertTrue(button(R.string.library_open).isEnabled)
        assertSame("An admitted result must not publish a background dialog", confirmation, dialog())
        assertFalse(confirmation.isShowing)
        resume()
        idle()
        assertSame(confirmation, dialog())
        assertFalse(confirmation.isShowing)
    }
}
