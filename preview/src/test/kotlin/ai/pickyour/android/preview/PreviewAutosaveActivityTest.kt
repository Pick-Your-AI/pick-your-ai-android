package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual canonical file writes under simulated Android lifecycle/clock, never installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewAutosaveActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun hasText(resource: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(resource) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the autosave UI state")
    }
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun drain() {
        worker().submit {}.get(15, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun saveNow() {
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
    }
    private fun seed(): PreviewStoredDraft {
        field(R.string.draft_title).setText("acknowledged")
        saveNow()
        return loaded()
    }
    private fun recreate(state: Bundle = Bundle()) {
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) }
    }

    @Test fun initialLoadAndIdleDoNotCreateAnUneditedDraft() {
        advance(60_000)
        drain()
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        assertFalse(hasText(R.string.draft_saved))
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @Test fun rapidChangesAutosaveOnlyTheCompleteLatestFormAfterThePause() {
        field(R.string.draft_title).setText("first")
        field(R.string.context_objective).setText("objective")
        field(R.string.context_context).setText("")
        field(R.string.workload_currency).setText("USD")
        field(R.string.workload_budget).setText("0.0500")
        advance(749)
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        assertFalse(hasText(R.string.draft_saved))
        field(R.string.draft_title).setText("latest")
        advance(749)
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        advance(1)
        await { hasText(R.string.draft_saved) }
        val committed = loaded()
        assertEquals("latest", committed.fields.title)
        assertEquals(PreviewDraftContext("objective", ""), committed.contextInput)
        assertEquals("USD", committed.workloadInput.currency)
        assertEquals("0.0500", committed.workloadInput.monthlyBudgetAmount?.encoded)
        assertEquals(1, committed.history.size)
        advance(60_000)
        drain()
        assertEquals(committed.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun manualSaveConsumesThePendingDelayWithoutDuplicatingARevision() {
        field(R.string.draft_title).setText("save now")
        advance(300)
        saveNow()
        val committed = loaded()
        advance(60_000)
        drain()
        assertEquals(committed.baselineIdentity, loaded().baselineIdentity)
        assertEquals(1, loaded().history.size)
    }

    @Test fun editingDuringAutosaveKeepsNewInputsUntilTheirOwnDurableAcknowledgement() {
        val first = seed()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = worker().submit { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        try {
            field(R.string.draft_title).setText("first pending")
            advance(750)
            assertTrue(hasText(R.string.draft_saving))
            assertTrue(field(R.string.draft_title).isEnabled)
            assertTrue(field(R.string.context_objective).isEnabled)
            assertFalse(button(R.string.library_open).isEnabled)
            field(R.string.draft_title).setText("newest pending")
            field(R.string.context_objective).setText("newest objective")
            assertTrue(hasText(R.string.draft_saving_pending))
            assertFalse(hasText(R.string.draft_saved))
            assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        } finally { release.countDown() }
        blocked.get(15, TimeUnit.SECONDS)
        await { button(R.string.save_draft).isEnabled && hasText(R.string.draft_unsaved) }
        val intermediate = loaded()
        assertEquals("first pending", intermediate.fields.title)
        assertEquals("newest pending", field(R.string.draft_title).text.toString())
        assertFalse(hasText(R.string.draft_saved))
        advance(750)
        await { hasText(R.string.draft_saved) }
        val last = loaded()
        assertEquals(first.revision + 2, last.revision)
        assertEquals("newest pending", last.fields.title)
        assertEquals("newest objective", last.contextInput.objective)
        assertEquals(listOf("acknowledged", "first pending", "newest pending"), last.history.map { it.fields?.title })
    }

    @Test fun invalidWorkloadCancelsThePendingSaveAndCorrectionCommitsTheWholeForm() {
        field(R.string.draft_title).setText("retained title")
        field(R.string.workload_currency).setText("USD")
        advance(300)
        field(R.string.workload_budget).setText("not a decimal")
        advance(10_000)
        drain()
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        assertEquals("not a decimal", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        field(R.string.workload_budget).setText("0.000000000001")
        advance(750)
        await { hasText(R.string.draft_saved) }
        assertEquals("retained title", loaded().fields.title)
        assertEquals("0.000000000001", loaded().workloadInput.monthlyBudgetAmount?.encoded)
        assertEquals(1, loaded().history.size)
    }

    @Test fun failedAutosavePreservesTheHeadAndPauseAcrossRecreationUntilExplicitRetry() {
        val first = seed()
        val root = activity.noBackupFilesDir.toPath()
        FileChannel.open(root.resolve(".preview-library.lock"), WRITE).use { channel ->
            channel.lock().use {
                field(R.string.draft_title).setText("retry this exact text")
                advance(750)
                await { hasText(R.string.draft_save_failed) && button(R.string.save_draft).isEnabled }
                assertFalse(hasText(R.string.draft_saved))
            }
        }
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        advance(60_000)
        drain()
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        recreate()
        await { hasText(R.string.draft_autosave_paused) && button(R.string.save_draft).isEnabled }
        advance(60_000)
        drain()
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        assertEquals("retry this exact text", field(R.string.draft_title).text.toString())
        saveNow()
        assertEquals(first.revision + 1, loaded().revision)
        assertEquals("retry this exact text", loaded().fields.title)
    }

    @Test fun aConcurrentWriterBlocksAutomaticRetryAndRecreationCannotAdoptItsAuthority() {
        val first = seed()
        field(R.string.draft_title).setText("local pending")
        val external = (adapter().save(first, PreviewDraftFields("other writer")) as PreviewDraftSave.Saved).record
        advance(750)
        await { hasText(R.string.draft_conflict) }
        assertFalse(button(R.string.save_draft).isEnabled)
        field(R.string.draft_title).setText("newer local pending")
        advance(60_000)
        drain()
        assertEquals(external.baselineIdentity, loaded().baselineIdentity)
        recreate()
        await { hasText(R.string.draft_reconcile) }
        assertFalse(button(R.string.save_draft).isEnabled)
        advance(60_000)
        drain()
        assertEquals("newer local pending", field(R.string.draft_title).text.toString())
        assertEquals(external.baselineIdentity, loaded().baselineIdentity)
        reopen()
        assertEquals("other writer", field(R.string.draft_title).text.toString())
        field(R.string.draft_title).setText("reconciled edit")
        advance(750)
        await { hasText(R.string.draft_saved) }
        assertEquals(external.revision + 1, loaded().revision)
    }

    @Test fun recreationBeforeTheDelayRetainsPendingInputAndOnlyTheNewEditorCanSaveIt() {
        val first = seed()
        val oldTitle = field(R.string.draft_title)
        oldTitle.setText("pending across recreation")
        field(R.string.context_objective).setText("retained objective")
        advance(300)
        recreate()
        await { button(R.string.save_draft).isEnabled }
        oldTitle.setText("retired callback")
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        assertEquals("pending across recreation", field(R.string.draft_title).text.toString())
        advance(750)
        await { hasText(R.string.draft_saved) }
        assertEquals(first.revision + 1, loaded().revision)
        assertEquals("pending across recreation", loaded().fields.title)
        assertEquals("retained objective", loaded().contextInput.objective)
        advance(60_000)
        drain()
        assertEquals(first.revision + 1, loaded().revision)
    }

    @Test fun aCommittedWriteWithoutUiAcknowledgementRequiresExplicitReconciliationAfterRecreation() {
        val first = seed()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = worker().submit { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        try {
            field(R.string.draft_title).setText("committed before acknowledgement")
            advance(750)
            assertTrue(hasText(R.string.draft_saving))
        } finally { release.countDown() }
        blocked.get(15, TimeUnit.SECONDS)
        // Wait for the actual worker write, leaving its UI delivery queued.
        worker().submit {}.get(15, TimeUnit.SECONDS)
        val committed = loaded()
        assertEquals(first.revision + 1, committed.revision)
        val state = Bundle()
        recreate(state)
        assertTrue(state.getBoolean("draftRequiresReopen"))
        await { hasText(R.string.draft_reconcile) }
        assertFalse(button(R.string.save_draft).isEnabled)
        assertFalse(hasText(R.string.draft_saved))
        advance(60_000)
        drain()
        assertEquals(committed.baselineIdentity, loaded().baselineIdentity)
        reopen()
        assertEquals("committed before acknowledgement", field(R.string.draft_title).text.toString())
        assertEquals(committed.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun selectingANewDraftRetiresThePriorTimerAndItsPendingInput() {
        val first = seed()
        field(R.string.draft_title).setText("pending old draft")
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.library_open).isEnabled && field(R.string.draft_title).text.isEmpty() }
        val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
        val created = (library.list() as PreviewLibraryRead.Available).entries.single { it.id.value != "guest-draft" }.id
        advance(60_000)
        drain()
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        assertEquals("", (library.open(created).load() as PreviewDraftLoad.Available).record.fields.title)
        field(R.string.draft_title).setText("only the new draft")
        advance(750)
        await { hasText(R.string.draft_saved) }
        assertEquals("only the new draft", (library.open(created).load() as PreviewDraftLoad.Available).record.fields.title)
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun aRejectedWorkerNeverAcknowledgesOrAutomaticallyRetriesAnUndispatchedWrite() {
        drain()
        worker().shutdownNow()
        field(R.string.draft_title).setText("undispatched")
        advance(750)
        assertTrue(hasText(R.string.draft_save_failed))
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue(field(R.string.draft_title).isEnabled)
        advance(60_000)
        assertFalse(hasText(R.string.draft_saved))
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
    }
}
