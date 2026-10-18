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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual serial-worker callback replays in a simulated Activity; no installed-device claim. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryDialogAuthorityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val library get() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
    private lateinit var baseline: String
    private lateinit var active: PreviewActiveRead

    @Before fun launchMissingDraft() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(library.openLegacy().load() is PreviewDraftLoad.Missing)
        active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
    }

    private fun preparePendingEdits() {
        field(R.string.draft_title).setText("saved title")
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) }
        baseline = loaded().baselineIdentity
        active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_budget).setText("12.3400")
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @After fun close() { controller.pause().stop().destroy() }

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

    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun loaded() = (library.openLegacy().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }

    private fun finishWork() {
        val workerField = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = workerField.get(activity) as ExecutorService
        // A create acknowledgement can submit an open operation. Settle both phases before asserting absence.
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker.submit {}.get(15, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun cancel(value: AlertDialog) {
        value.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !value.isShowing }
    }

    private fun openLibrary(): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.library_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.let { it !== previous && it.isShowing } == true }
        return dialog()
    }

    private fun openEntry(): AlertDialog {
        val picker = openLibrary()
        shadowOf(picker).clickOnItem(0)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }

    private fun assertUnchanged(cancelled: AlertDialog) {
        finishWork()
        val entries = (library.list() as PreviewLibraryRead.Available).entries
        assertEquals("A cancelled action must not change durable draft entries",
            listOf(PreviewDraftId("guest-draft")), entries.map { it.id })
        assertFalse(entries.single().deletionPending)
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertTrue(hasText(R.string.draft_unsaved))
        assertTrue(button(R.string.save_draft).isEnabled)
        assertSame("A cancelled dialog must not create a new dialog", cancelled, dialog())
        assertFalse(cancelled.isShowing)
    }

    @Test fun cancelledLibraryPickerCannotOpenEntry() {
        preparePendingEdits()
        val picker = openLibrary()
        cancel(picker)
        shadowOf(picker).clickOnItem(0)
        assertUnchanged(picker)
    }

    @Test fun cancelledLibraryEntryCannotReopenSavedDraft() {
        preparePendingEdits()
        val entry = openEntry()
        val open = entry.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(entry)
        open.performClick()
        assertUnchanged(entry)
    }

    @Test fun cancelledLibraryEntryCannotOpenDeletionConfirmation() {
        preparePendingEdits()
        val entry = openEntry()
        val delete = entry.getButton(AlertDialog.BUTTON_NEUTRAL)
        cancel(entry)
        delete.performClick()
        assertUnchanged(entry)
    }

    @Test fun cancelledDeleteConfirmationCannotDeleteSavedDraft() {
        preparePendingEdits()
        val entry = openEntry()
        entry.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        await { dialog() !== entry && dialog().isShowing }
        val confirmation = dialog()
        val delete = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(confirmation)
        delete.performClick()
        assertUnchanged(confirmation)
    }

    @Test fun cancelledNewDraftConfirmationCannotCreateOrSwitchDraft() {
        preparePendingEdits()
        button(R.string.library_new).performClick()
        val confirmation = dialog()
        val create = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(confirmation)
        create.performClick()
        assertUnchanged(confirmation)
    }

    @Test fun cancelledDuplicateConfirmationCannotCreateOrSwitchDraft() {
        preparePendingEdits()
        button(R.string.library_duplicate).performClick()
        val confirmation = dialog()
        val duplicate = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(confirmation)
        duplicate.performClick()
        assertUnchanged(confirmation)
    }

    @Test fun cancelledRecoveryConfirmationCannotCreateOrSwitchDraft() {
        preparePendingEdits()
        button(R.string.recovery_copy).performClick()
        val confirmation = dialog()
        val recover = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(confirmation)
        recover.performClick()
        assertUnchanged(confirmation)
    }

    @Test fun cancelledReopenConfirmationCannotDiscardPendingInputs() {
        preparePendingEdits()
        button(R.string.reopen_draft).performClick()
        val confirmation = dialog()
        val reopen = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(confirmation)
        reopen.performClick()
        assertUnchanged(confirmation)
    }

    @Test fun confirmedCreateDuplicateOpenAndDeleteKeepTheirSelectedIdentities() {
        preparePendingEdits()
        fun selected() = (PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read() as? PreviewActiveRead.Selected)?.id
        fun entry(id: PreviewDraftId): AlertDialog {
            val picker = openLibrary()
            val entries = (library.list() as PreviewLibraryRead.Available).entries
            val index = entries.indexOfFirst { it.id == id }
            assertTrue(index >= 0)
            shadowOf(picker).clickOnItem(index)
            await { dialog() !== picker && dialog().isShowing }
            return dialog()
        }

        // Durable reads share the writer's nonblocking locks; observe them only after acknowledgement.
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(hasText(R.string.draft_saved))
        assertTrue(button(R.string.library_open).isEnabled)
        val original = requireNotNull(selected())
        assertNotEquals(PreviewDraftId("guest-draft"), original)
        field(R.string.draft_title).setText("independent target")
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) }
        val source = (library.open(original).load() as PreviewDraftLoad.Available).record

        button(R.string.library_duplicate).performClick()
        val duplicateConfirmation = dialog()
        val duplicate = duplicateConfirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        duplicate.performClick()
        finishWork()
        assertTrue(hasText(R.string.draft_saved))
        assertTrue(button(R.string.library_open).isEnabled)
        val copy = requireNotNull(selected())
        assertNotEquals(original, copy)
        assertEquals("independent target", field(R.string.draft_title).text.toString())
        assertEquals(source.baselineIdentity, (library.open(original).load() as PreviewDraftLoad.Available).record.baselineIdentity)
        duplicate.performClick()
        finishWork()
        assertEquals(3, (library.list() as PreviewLibraryRead.Available).entries.size)
        assertEquals(copy, selected())

        entry(original).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(hasText(R.string.draft_saved))
        assertTrue(button(R.string.library_open).isEnabled)
        assertEquals(original, selected())
        val deletionEntry = entry(copy)
        deletionEntry.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        await { dialog() !== deletionEntry && dialog().isShowing }
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(button(R.string.library_open).isEnabled)
        assertTrue((library.list() as PreviewLibraryRead.Available).entries.none { it.id == copy })
        assertEquals(original, selected())
        assertEquals("independent target", field(R.string.draft_title).text.toString())
        assertEquals(source.baselineIdentity, (library.open(original).load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(setOf(PreviewDraftId("guest-draft"), original),
            (library.list() as PreviewLibraryRead.Available).entries.map { it.id }.toSet())
    }

    @Test fun reopenConfirmationCannotAdoptANewerSavedBaseline() {
        preparePendingEdits()
        button(R.string.reopen_draft).performClick()
        val stale = dialog()
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && button(R.string.library_open).isEnabled }
        val newer = loaded().baselineIdentity
        assertNotEquals(baseline, newer)
        field(R.string.draft_title).setText("later pending title")
        field(R.string.context_objective).setText("later pending objective")
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertFalse(stale.isShowing)
        assertEquals(newer, loaded().baselineIdentity)
        assertEquals("later pending title", field(R.string.draft_title).text.toString())
        assertEquals("later pending objective", field(R.string.context_objective).text.toString())
        assertTrue(hasText(R.string.draft_unsaved))
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @Test fun reopenConfirmationKeepsConflictPendingFieldsUntilFreshConsent() {
        preparePendingEdits()
        button(R.string.reopen_draft).performClick()
        val stale = dialog()
        val competing = library.openLegacy().save(loaded(), PreviewDraftFields("competing acknowledged title"),
            PreviewDraftContext("competing acknowledged objective")) as PreviewDraftSave.Saved
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_conflict) && button(R.string.reopen_draft).isEnabled }
        assertFalse(button(R.string.save_draft).isEnabled)
        field(R.string.draft_title).setText("pending title after conflict")
        field(R.string.context_objective).setText("pending objective after conflict")

        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(competing.record.baselineIdentity, loaded().baselineIdentity)
        assertEquals("pending title after conflict", field(R.string.draft_title).text.toString())
        assertEquals("pending objective after conflict", field(R.string.context_objective).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)

        button(R.string.reopen_draft).performClick()
        val fresh = dialog()
        assertNotSame(stale, fresh)
        fresh.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) && button(R.string.library_open).isEnabled }
        assertEquals("competing acknowledged title", field(R.string.draft_title).text.toString())
        assertEquals("competing acknowledged objective", field(R.string.context_objective).text.toString())
        assertEquals(competing.record.baselineIdentity, loaded().baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertFalse(button(R.string.save_draft).isEnabled)
    }

    // Preserves the independent Missing→Missing reproduction and fresh control from review5562329803.
    @Test fun olderReopenCannotDiscardLaterEditsAfterFreshMissingDraftReconciliation() {
        assertEquals(PreviewDraftLoad.Missing, library.openLegacy().load())
        field(R.string.draft_title).setText("original unsaved title")
        field(R.string.context_objective).setText("original unsaved objective")
        button(R.string.reopen_draft).performClick()
        val older = dialog()
        assertTrue(older.isShowing)
        button(R.string.reopen_draft).performClick()
        val fresh = dialog()
        assertNotSame(older, fresh)
        assertTrue(fresh.isShowing)
        fresh.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertFalse(fresh.isShowing)
        assertEquals("", field(R.string.draft_title).text.toString())
        assertEquals(PreviewDraftLoad.Missing, library.openLegacy().load())
        assertTrue(button(R.string.save_draft).isEnabled)
        field(R.string.draft_title).setText("later unsaved title")
        field(R.string.context_objective).setText("later unsaved objective")
        older.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals("later unsaved title", field(R.string.draft_title).text.toString())
        assertEquals("later unsaved objective", field(R.string.context_objective).text.toString())
        assertEquals(PreviewDraftLoad.Missing, library.openLegacy().load())
        assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertTrue(button(R.string.save_draft).isEnabled)
    }
}
