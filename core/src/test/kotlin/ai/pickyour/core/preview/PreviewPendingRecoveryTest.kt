package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.contracts.ContractDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.io.IOException
import java.math.BigInteger

class PreviewPendingRecoveryTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun source(): PreviewLibraryCreate.Saved = library().create() as PreviewLibraryCreate.Saved
    private fun pending() = PreviewPendingDraft(PreviewDraftFields("pending", "sample", "v1", "a".repeat(64)),
        PreviewDraftContext("", null), DraftWorkload(currency="USD", monthlyBudgetAmount=ContractDecimal.parse("12.3400"),
            requestsPerDay=BigInteger.ZERO))

    @Test fun `stale pending recovery creates independent revision while newer original remains exact`() {
        val id = PreviewDraftId("guest-draft")
        val store = LocalDraftStore(Files.createDirectory(root.resolve(id.value)))
        val content = DraftContent("old", "context", "opaque-model",
            mapOf(OptionalTier.T3 to TierDraft(Activation.UNKNOWN, setOf("opaque-selection"), mapOf("setting" to "value"))))
        store.save(null, content, mapOf("preview.format" to "1", "preview.title" to "base", "unknown.lock" to "retained"))
        val adapter = library().open(id)
        val baseline = (adapter.load() as PreviewDraftLoad.Available).record
        val newer = (adapter.save(baseline, PreviewDraftFields("external"), PreviewDraftContext("newer", "remote")) as PreviewDraftSave.Saved).record
        assertEquals(PreviewDraftSave.Conflict, adapter.save(baseline, pending().fields))
        val copy = library().recoverPending(baseline, pending()) as PreviewLibraryCreate.Saved
        assertNotEquals(id, copy.id)
        assertEquals(0L, copy.record.revision)
        assertEquals(1, copy.record.history.size)
        assertEquals(pending().fields, copy.record.fields)
        assertEquals(pending().context, copy.record.contextInput)
        assertEquals(pending().workload, copy.record.workloadInput)
        assertEquals(content.primaryModel, copy.record.draft.content.primaryModel)
        assertEquals(content.optional, copy.record.optionalTiers)
        assertEquals("retained", copy.record.draft.metadata["unknown.lock"])
        assertEquals(id.value, copy.record.draft.metadata["preview.recoverySourceId"])
        assertEquals(baseline.revision.toString(), copy.record.draft.metadata["preview.recoverySourceRevision"])
        assertEquals(baseline.draft.version.hash.hex, copy.record.draft.metadata["preview.recoverySourceHash"])
        assertEquals(newer.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(PreviewDraftSave.Conflict, library().open(copy.id).save(baseline, pending().fields))
        assertEquals(PreviewDraftSave.Conflict, adapter.save(copy.record, pending().fields))
    }

    @Test fun `busy recovery never creates a new directory or modifies source`() {
        val first = source()
        FileChannel.open(root.resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.BUSY), library().recoverPending(first.record, pending()))
            }
        }
        assertEquals(1, (library().list() as PreviewLibraryRead.Available).entries.size)
        assertEquals(first.record.baselineIdentity, (library().open(first.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }

    @Test fun `deleted source cannot be reused for recovery and foreign root is rejected`() {
        val first = source()
        val foreign = PreviewDraftLibrary(Files.createDirectory(root.resolve("other")))
        assertThrows(IllegalArgumentException::class.java) { foreign.recoverPending(first.record, pending()) }
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(first.id, first.record))
        assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.CORRUPT), library().recoverPending(first.record, pending()))
    }

    @Test fun `post save failure returns recoverable identity without acknowledged success`() {
        val first = source()
        val failing = PreviewDraftLibrary(root, { if (it == LibraryCheckpoint.AFTER_SAVE) throw IOException("injected") })
        val result = failing.recoverPending(first.record, pending()) as PreviewLibraryCreate.Unconfirmed
        val recovered = (library().open(result.id).load() as PreviewDraftLoad.Available).record
        assertEquals(pending().fields, recovered.fields)
        assertEquals(pending().workload, recovered.workloadInput)
        assertEquals(first.record.baselineIdentity, (library().open(first.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
