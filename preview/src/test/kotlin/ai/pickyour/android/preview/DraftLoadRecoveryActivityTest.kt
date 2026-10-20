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
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheInstall
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.DraftLoad
import ai.pickyour.core.drafts.DraftSave
import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.drafts.LocalDraftStore
import ai.pickyour.core.preview.PreviewDraftAutosave
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewDraftSave
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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real canonical files and the simulated Android lifecycle; never installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DraftLoadRecoveryActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private lateinit var expectedRetained: Map<String, String>
    private var pendingSince = 0L
    private val activity get() = controller.get()
    private val root get() = activity.noBackupFilesDir.toPath()
    private val draftRoot get() = root.resolve("guest-draft")

    @Before fun saveBaselineAndEnterPendingForm() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        field(R.string.catalog_search).setText("cat.model.fixture")
        views().filterIsInstance<Button>().single { it.text.toString().startsWith("Open FICTIONAL") }.performClick()
        ShadowAlertDialog.getLatestAlertDialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(activity.getString(R.string.selected_sample, "FICTIONAL Model")) }
        field(R.string.draft_title).setText("saved synthetic title")
        button(R.string.save_draft).performClick()
        await { hasText(activity.getString(R.string.draft_saved)) && !button(R.string.save_draft).isEnabled }
        // These recovery controls intentionally precede the automatic save deadline.
        AutosaveUiClock.pause()
        pendingSince = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("pending synthetic title")
        field(R.string.context_objective).setText("pending synthetic objective")
        field(R.string.context_context).setText("pending synthetic context")
        field(R.string.workload_region).setText("pending synthetic region")
        field(R.string.workload_budget).setText("12.00")
        assertBeforeAutosave()
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun futurePreviewFormatRequiresCompatibleReaderAndRetainsPendingForm() {
        stopAndChangeRetainedDraft { path ->
            val store = LocalDraftStore(path)
            val saved = (store.load() as DraftLoad.Available).draft
            assertTrue(store.save(saved.version, saved.content, saved.metadata + ("preview.format" to "2")) is DraftSave.Saved)
            assertTrue("The canonical envelope remains readable", store.load() is DraftLoad.Available)
        }
        requireRecovery(DraftStorageFailure.UNSUPPORTED_VERSION, "compatible reader")
    }

    @Test fun futureLocalFormatRequiresCompatibleReaderWithoutRewritingRetainedBytes() {
        stopAndChangeRetainedDraft { path ->
            val revisions = path.resolve("revisions")
            val active = String(Files.readAllBytes(revisions.resolve("active")), Charsets.US_ASCII)
            val payload = JSONObject(String(Files.readAllBytes(revisions.resolve("$active.bin")), Charsets.UTF_8))
                .put("local_format_version", 99).toString().toByteArray(Charsets.UTF_8)
            val cache = VerifiedArtifactCache(revisions, LocalDraftStore.MAX_BYTES)
            assertTrue(cache.install(ArtifactHash(digest(payload)), payload.inputStream()) is CacheInstall.Activated)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.UNSUPPORTED_VERSION), LocalDraftStore(path).load())
        }
        requireRecovery(DraftStorageFailure.UNSUPPORTED_VERSION, "compatible reader")
    }

    @Test fun corruptRetainedBytesRequireReadableCopyAndRetainPendingForm() {
        stopAndChangeRetainedDraft { path ->
            val revisions = path.resolve("revisions")
            val active = String(Files.readAllBytes(revisions.resolve("active")), Charsets.US_ASCII)
            // Deliberately damage only the synthetic test fixture; the app must never repair it.
            Files.write(revisions.resolve("$active.bin"), "synthetic corrupt retained payload".toByteArray(Charsets.UTF_8))
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), LocalDraftStore(path).load())
        }
        requireRecovery(DraftStorageFailure.CORRUPT, "readable copy")
    }

    @Test fun malformedPreviewMetadataRequiresReadableCopyWithoutReset() {
        stopAndChangeRetainedDraft { path ->
            val store = LocalDraftStore(path)
            val saved = (store.load() as DraftLoad.Available).draft
            assertTrue(store.save(saved.version, saved.content, saved.metadata - "preview.title") is DraftSave.Saved)
            assertTrue("The failure is in retained form metadata, not the canonical envelope", store.load() is DraftLoad.Available)
        }
        requireRecovery(DraftStorageFailure.CORRUPT, "readable copy")
    }

    @Test fun busyStoredDraftCanRetryExplicitlyAfterLockRelease() {
        val retained = retainedBytes()
        val expected = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity
        FileChannel.open(draftRoot.resolve("transaction.lock"), WRITE).use { channel ->
            channel.lock().use {
                assertEquals(PreviewDraftLoad.Failed(DraftStorageFailure.BUSY), adapter().load())
                reopen()
                assertTrue(hasText(activity.getString(R.string.draft_load_failed)))
                assertPendingForm()
                assertFalse(button(R.string.save_draft).isEnabled)
                assertEquals(retained, retainedBytes())
            }
        }
        settleWorker()
        assertPendingForm()
        assertFalse("Lock release alone must not retry or enable writes", button(R.string.save_draft).isEnabled)
        reopen()
        assertTrue(hasText(activity.getString(R.string.draft_saved)))
        assertEquals("saved synthetic title", field(R.string.draft_title).text.toString())
        assertEquals(expected, (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(retained, retainedBytes())
        assertBeforeAutosave()
    }

    @Test fun busyReadRetryKeepsPendingInputsUntilAnExplicitSave() {
        val original = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        val retained = retainedBytes()
        recreateWithBusyRead {
            retryKeepingEdits()
            assertTrue(hasText(activity.getString(R.string.draft_load_failed)))
            assertPendingForm()
            assertFalse(button(R.string.save_draft).isEnabled)
            assertEquals(retained, retainedBytes())
        }
        assertFalse("Releasing a lock does not start a retry", button(R.string.save_draft).isEnabled)
        retryKeepingEdits()
        assertPendingForm()
        assertTrue(hasText(activity.getString(R.string.draft_autosave_paused)))
        assertTrue(button(R.string.save_draft).isEnabled)
        assertEquals(original.baselineIdentity, (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals("The preserving retry is read-only", retained, retainedBytes())

        button(R.string.save_draft).performClick()
        await { hasText(activity.getString(R.string.draft_saved)) && !button(R.string.save_draft).isEnabled }
        val saved = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        assertEquals(original.revision + 1, saved.revision)
        assertEquals("pending synthetic title", saved.fields.title)
        assertEquals("pending synthetic objective", saved.contextInput.objective)
        assertEquals("pending synthetic context", saved.contextInput.context)
        assertEquals(original.fields.selectedId, saved.fields.selectedId)
        assertEquals(original.fields.selectedVersion, saved.fields.selectedVersion)
        assertEquals(original.fields.catalogHash, saved.fields.catalogHash)
        val after = retainedBytes()
        retained.filterKeys { it.endsWith(".bin") }.forEach { (path, digest) -> assertEquals(digest, after[path]) }
    }

    @Test fun changedRevisionRetryKeepsInputsWithoutAdoptingNewSaveAuthority() {
        val original = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        recreateWithBusyRead()
        val changed = adapter().save(original, original.fields.copy(title = "newer saved synthetic title"))
        assertTrue(changed is PreviewDraftSave.Saved)
        val retained = retainedBytes()
        retryKeepingEdits()
        assertPendingForm()
        assertTrue(hasText(activity.getString(R.string.draft_reconcile)))
        assertFalse(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        settleWorker()
        assertEquals((changed as PreviewDraftSave.Saved).record.baselineIdentity,
            (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(retained, retainedBytes())
    }

    @Test fun missingRevisionRetryKeepsInputsWithoutCreatingAReplacement() {
        recreateWithBusyRead()
        Files.delete(draftRoot.resolve("revisions/active"))
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        val retained = retainedBytes()
        retryKeepingEdits()
        assertPendingForm()
        assertTrue(hasText(activity.getString(R.string.draft_reconcile)))
        assertFalse(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        settleWorker()
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        assertEquals(retained, retainedBytes())
    }

    @Test fun unconfirmedCheckpointRetryCannotAcknowledgeAnUnchangedSavedRevision() {
        recreateWithBusyRead()
        val retained = retainedBytes()
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        assertTrue("A failed read supplies no durable acknowledgement", state.getBoolean("draftRequiresReopen"))
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.reopen_draft).isEnabled }
        settleWorker()
        assertPendingForm()
        retryKeepingEdits()
        assertPendingForm()
        assertTrue(hasText(activity.getString(R.string.draft_reconcile)))
        assertFalse(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        settleWorker()
        assertEquals(retained, retainedBytes())
    }

    @Test fun cancelledAndStaleKeepingEditsCallbacksCannotRestartARead() {
        recreateWithBusyRead()
        val retained = retainedBytes()
        button(R.string.reopen_draft).performClick()
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()!!
        val cancelledRetry = keepingEditsChoice(cancelled)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !cancelled.isShowing }
        cancelledRetry.performClick()
        settleWorker()
        assertPendingForm()
        assertTrue(hasText(activity.getString(R.string.draft_load_failed)))
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(retained, retainedBytes())

        retryKeepingEdits()
        button(R.string.reopen_draft).performClick()
        val stale = ShadowAlertDialog.getLatestAlertDialog()!!
        val staleRetry = keepingEditsChoice(stale)
        button(R.string.save_draft).performClick()
        await { hasText(activity.getString(R.string.draft_saved)) && !button(R.string.save_draft).isEnabled }
        val saved = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity
        val afterSave = retainedBytes()
        field(R.string.draft_title).setText("later pending synthetic title")
        staleRetry.performClick()
        settleWorker()
        assertEquals("later pending synthetic title", field(R.string.draft_title).text.toString())
        assertEquals("pending synthetic objective", field(R.string.context_objective).text.toString())
        assertTrue(hasText(activity.getString(R.string.draft_unsaved)))
        assertTrue(button(R.string.save_draft).isEnabled)
        assertEquals(saved, (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(afterSave, retainedBytes())
    }

    @Test fun keepingEditsRetryPreservesInvalidRawWorkloadInput() {
        recreateWithBusyRead()
        val retained = retainedBytes()
        field(R.string.workload_budget).setText("not a number")
        retryKeepingEdits()
        assertEquals("not a number", field(R.string.workload_budget).text.toString())
        assertEquals("pending synthetic title", field(R.string.draft_title).text.toString())
        assertEquals("pending synthetic objective", field(R.string.context_objective).text.toString())
        assertEquals("pending synthetic context", field(R.string.context_context).text.toString())
        assertEquals("pending synthetic region", field(R.string.workload_region).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(retained, retainedBytes())
        field(R.string.workload_budget).setText("12.00")
        assertPendingForm()
        assertTrue("Correcting input can use only the original rejoined baseline", button(R.string.save_draft).isEnabled)
        assertEquals(retained, retainedBytes())
    }

    @Test fun preservingRetryPausesAutosaveAcrossDelayAndRecreationUntilExplicitSave() {
        val original = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        val retained = retainedBytes()
        recreateWithBusyRead()
        retryKeepingEdits()
        assertPendingForm()
        assertTrue(button(R.string.save_draft).isEnabled)

        advanceAutosave(PreviewDraftAutosave.DELAY_MILLIS)
        settleWorker()
        assertEquals("A preserving read cannot autosave the pending form after its delay", retained, retainedBytes())
        assertEquals(original.baselineIdentity, (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        assertTrue(hasText(activity.getString(R.string.draft_autosave_paused)))
        assertPendingFields()
        field(R.string.draft_title).setText("pending after preserving retry")
        advanceAutosave(60_000)
        settleWorker()
        assertEquals(retained, retainedBytes())
        assertPendingFields("pending after preserving retry")

        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        assertTrue("The explicit-save recovery choice survives recreation", state.getBoolean("autosavePaused"))
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.reopen_draft).isEnabled }
        settleWorker()
        advanceAutosave(60_000)
        settleWorker()
        assertEquals(retained, retainedBytes())
        assertPendingFields("pending after preserving retry")
        assertTrue(hasText(activity.getString(R.string.draft_autosave_paused)))
        assertTrue(button(R.string.save_draft).isEnabled)

        button(R.string.save_draft).performClick()
        await { hasText(activity.getString(R.string.draft_saved)) && !button(R.string.save_draft).isEnabled }
        val saved = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        assertEquals(original.revision + 1, saved.revision)
        assertEquals("pending after preserving retry", saved.fields.title)
        assertEquals("pending synthetic objective", saved.contextInput.objective)
        assertEquals("pending synthetic context", saved.contextInput.context)
        assertEquals("pending synthetic region", saved.workloadInput.region)
        assertEquals("12.00", saved.workloadInput.monthlyBudgetAmount?.encoded)
        assertEquals(original.fields.selectedId, saved.fields.selectedId)
        assertEquals(original.fields.selectedVersion, saved.fields.selectedVersion)
        assertEquals(original.fields.catalogHash, saved.fields.catalogHash)

        field(R.string.draft_title).setText("automatic edit after explicit save")
        advanceAutosave(PreviewDraftAutosave.DELAY_MILLIS - 1)
        assertEquals(saved.baselineIdentity, (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        advanceAutosave(1)
        await { hasText(activity.getString(R.string.draft_saved)) }
        val automatic = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        assertEquals(original.revision + 2, automatic.revision)
        assertEquals(listOf("saved synthetic title", "pending after preserving retry", "automatic edit after explicit save"),
            automatic.history.map { it.fields?.title })
        val after = retainedBytes()
        retained.filterKeys { it.endsWith(".bin") }.forEach { (path, digest) -> assertEquals(digest, after[path]) }
    }

    @Test fun cancelledAndStaleKeepingEditsCallbacksCannotPauseAutosaveOrRestartReads() {
        val original = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        val retained = retainedBytes()
        button(R.string.reopen_draft).performClick()
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()!!
        val cancelledRetry = keepingEditsChoice(cancelled)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !cancelled.isShowing }
        val pendingAuthority = storageAuthority()
        cancelledRetry.performClick()
        AutosaveUiClock.idle()
        assertFalse(autosave().isPaused)
        assertSame("A dismissed action cannot start a read", pendingAuthority, storageAuthority())
        assertEquals(retained, retainedBytes())

        button(R.string.reopen_draft).performClick()
        val busyDialog = ShadowAlertDialog.getLatestAlertDialog()!!
        val busyRetry = keepingEditsChoice(busyDialog)
        button(R.string.reopen_draft).performClick()
        val acknowledgedDialog = ShadowAlertDialog.getLatestAlertDialog()!!
        val acknowledgedRetry = keepingEditsChoice(acknowledgedDialog)
        assertFalse(busyDialog.isShowing)
        assertTrue(acknowledgedDialog.isShowing)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = worker().submit { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        try {
            advanceAutosave(PreviewDraftAutosave.DELAY_MILLIS - 1)
            assertEquals(retained, retainedBytes())
            advanceAutosave(1)
            assertTrue(hasText(activity.getString(R.string.draft_saving)))
            val savingAuthority = storageAuthority()
            assertFalse(busyDialog.isShowing)
            assertFalse(acknowledgedDialog.isShowing)
            busyRetry.performClick()
            AutosaveUiClock.idle()
            assertFalse("A busy action cannot pause the in-flight autosave", autosave().isPaused)
            assertSame("A busy action cannot replace the save with a read", savingAuthority, storageAuthority())
            assertTrue(hasText(activity.getString(R.string.draft_saving)))
            assertEquals(retained, retainedBytes())
            field(R.string.draft_title).setText("newer pending during autosave")
            assertTrue(hasText(activity.getString(R.string.draft_saving_pending)))
        } finally { release.countDown() }
        blocked.get(15, TimeUnit.SECONDS)
        await { hasText(activity.getString(R.string.draft_unsaved)) && button(R.string.save_draft).isEnabled }
        val saved = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        assertEquals(original.revision + 1, saved.revision)
        assertEquals("pending synthetic title", saved.fields.title)
        val afterSave = retainedBytes()
        val acknowledgedAuthority = storageAuthority()
        assertFalse(acknowledgedDialog.isShowing)
        acknowledgedRetry.performClick()
        AutosaveUiClock.idle()
        assertFalse("An old baseline cannot pause the current pending save", autosave().isPaused)
        assertSame("An old baseline cannot start a read", acknowledgedAuthority, storageAuthority())
        assertEquals(afterSave, retainedBytes())
        assertPendingFields("newer pending during autosave")
        assertTrue(hasText(activity.getString(R.string.draft_unsaved)))
        advanceAutosave(PreviewDraftAutosave.DELAY_MILLIS - 1)
        assertEquals(saved.baselineIdentity, (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record.baselineIdentity)
        advanceAutosave(1)
        await { hasText(activity.getString(R.string.draft_saved)) }
        val automatic = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
        assertEquals(original.revision + 2, automatic.revision)
        assertEquals(listOf("saved synthetic title", "pending synthetic title", "newer pending during autosave"),
            automatic.history.map { it.fields?.title })
    }

    private fun recreateWithBusyRead(whileBusy: () -> Unit = {}) {
        settleWorker()
        val path = draftRoot
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        FileChannel.open(path.resolve("transaction.lock"), WRITE).use { channel ->
            channel.lock().use {
                controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
                await { button(R.string.reopen_draft).isEnabled }
                settleWorker()
                assertEquals(PreviewDraftLoad.Failed(DraftStorageFailure.BUSY), adapter().load())
                assertTrue(hasText(activity.getString(R.string.draft_load_failed)))
                assertPendingForm()
                assertFalse(button(R.string.save_draft).isEnabled)
                whileBusy()
            }
        }
        settleWorker()
    }

    private fun keepingEditsChoice(dialog: AlertDialog): Button {
        assertTrue(dialog.isShowing)
        val choice = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertNotNull("Recovery needs an explicit retry that retains pending input", choice)
        assertEquals("Recovery needs a visible preserving retry", View.VISIBLE, choice.visibility)
        assertEquals("Retry keeping edits", choice.text.toString())
        return choice
    }

    private fun retryKeepingEdits() {
        button(R.string.reopen_draft).performClick()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()!!
        keepingEditsChoice(confirmation).performClick()
        await { button(R.string.reopen_draft).isEnabled }
        settleWorker()
    }

    private fun stopAndChangeRetainedDraft(change: (Path) -> Unit) {
        val path = draftRoot
        val state = Bundle()
        settleWorker()
        assertBeforeAutosave()
        controller.pause().saveInstanceState(state).stop().destroy()
        change(path)
        expectedRetained = retainedBytes(path.parent)
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.reopen_draft).isEnabled }
        settleWorker()
    }

    private fun requireRecovery(reason: DraftStorageFailure, instruction: String) {
        val retained = expectedRetained
        assertEquals("The first failed load must preserve all seeded bytes", retained, retainedBytes())
        repeat(2) {
            assertEquals(PreviewDraftLoad.Failed(reason), adapter().load())
            assertTrue("The rendered recovery must explain the missing requirement: $instruction",
                views().filterIsInstance<TextView>().any { it.text.toString().contains(instruction) })
            assertPendingForm()
            assertFalse(button(R.string.save_draft).isEnabled)
            assertTrue(button(R.string.library_open).isEnabled)
            assertTrue(button(R.string.reopen_draft).isEnabled)
            // Even an attempted disabled control must not replace the unreadable stored draft.
            button(R.string.save_draft).performClick()
            settleWorker()
            assertEquals(retained, retainedBytes())
            reopen()
            assertPendingForm()
            assertEquals(retained, retainedBytes())
            val state = Bundle()
            controller.pause().saveInstanceState(state).stop().destroy()
            controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
            await { button(R.string.reopen_draft).isEnabled }
            settleWorker()
        }
        assertEquals(retained, retainedBytes())
        assertPendingForm()
    }

    private fun assertPendingForm() {
        assertBeforeAutosave()
        assertPendingFields()
    }

    private fun assertPendingFields(expectedTitle: String = "pending synthetic title") {
        assertEquals(expectedTitle, field(R.string.draft_title).text.toString())
        assertEquals("pending synthetic objective", field(R.string.context_objective).text.toString())
        assertEquals("pending synthetic context", field(R.string.context_context).text.toString())
        assertEquals("pending synthetic region", field(R.string.workload_region).text.toString())
        assertEquals("12.00", field(R.string.workload_budget).text.toString())
        assertTrue(hasText(activity.getString(R.string.selected_sample, "FICTIONAL Model")))
        assertTrue(hasText(activity.getString(R.string.library_active, "guest-draft")))
    }

    private fun assertBeforeAutosave() {
        assertTrue("Recovery controls stay before autosave",
            SystemClock.uptimeMillis() in pendingSince until pendingSince + PreviewDraftAutosave.DELAY_MILLIS)
    }

    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()!!
        assertTrue(confirmation.isShowing)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.reopen_draft).isEnabled }
        settleWorker()
    }

    private fun adapter() = PreviewDraftLibrary(root).openLegacy()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun retainedBytes(directory: Path = root): Map<String, String> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { directory.relativize(it).toString() to digest(Files.readAllBytes(it)) }
    }
    private fun hasText(text: String) = views().filterIsInstance<TextView>().any { it.text.toString() == text }
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
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun autosave() = PreviewActivity::class.java.getDeclaredField("autosave").apply { isAccessible = true }
        .get(activity) as PreviewDraftAutosave
    private fun storageAuthority() = PreviewActivity::class.java.getDeclaredField("storageAuthority").apply { isAccessible = true }
        .get(activity)
    private fun advanceAutosave(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
    private fun settleWorker() {
        AutosaveUiClock.idle()
        worker().submit {}.get(15, TimeUnit.SECONDS)
        AutosaveUiClock.idle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            AutosaveUiClock.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit draft recovery state")
    }
}
