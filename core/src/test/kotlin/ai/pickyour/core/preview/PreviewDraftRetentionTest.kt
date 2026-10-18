package ai.pickyour.core.preview

import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.drafts.LocalDraftStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class PreviewDraftRetentionTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun loaded(id: PreviewDraftId) = (library().open(id).load() as PreviewDraftLoad.Available).record
    private fun artifacts(id: PreviewDraftId) = root.resolve(id.value).resolve("revisions")
    private fun count(id: PreviewDraftId) = Files.list(artifacts(id)).use { entries ->
        entries.filter { it.fileName.toString().endsWith(".bin") }.count()
    }
    /** Content-addressed payloads left before pointer publication still need storage accounting. */
    private fun fillWithUnactivatedArtifacts(id: PreviewDraftId) {
        repeat(LocalDraftStore.MAX_HISTORY - 1) { index ->
            val bytes = "unactivated-$index".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            Files.write(artifacts(id).resolve("$hash.bin"), bytes)
        }
    }

    @Test fun `ordinary preview writes enforce retention while preserving acknowledged and unchanged content`() {
        val first = library().create() as PreviewLibraryCreate.Saved
        fillWithUnactivatedArtifacts(first.id)
        val pointer = Files.readAllBytes(artifacts(first.id).resolve("active"))
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            library().open(first.id).save(first.record, PreviewDraftFields("still pending")))
        assertArrayEquals(pointer, Files.readAllBytes(artifacts(first.id).resolve("active")))
        val reopened = loaded(first.id)
        assertEquals(first.record.baselineIdentity, reopened.baselineIdentity)
        assertEquals(first.record.fields, reopened.fields)
        assertEquals(1, reopened.history.size)
        val unchanged = library().open(first.id).save(reopened, reopened.fields) as PreviewDraftSave.Saved
        assertEquals(reopened.baselineIdentity, unchanged.record.baselineIdentity)
        assertEquals(LocalDraftStore.MAX_HISTORY.toLong(), count(first.id))
    }

    @Test fun `preview budget accommodates every supported acknowledged history envelope`() {
        val first = library().create() as PreviewLibraryCreate.Saved
        var current = first.record
        repeat(LocalDraftStore.MAX_HISTORY - 1) { index ->
            current = (library().open(first.id).save(current, PreviewDraftFields("revision-$index")) as PreviewDraftSave.Saved).record
        }
        assertEquals(LocalDraftStore.MAX_HISTORY, loaded(first.id).history.size)
        assertEquals(LocalDraftStore.MAX_HISTORY.toLong(), count(first.id))
        assertEquals(current.baselineIdentity, loaded(first.id).baselineIdentity)
    }

    @Test fun `explicit pending recovery gets a separate budget without trimming the full source`() {
        val first = library().create() as PreviewLibraryCreate.Saved
        fillWithUnactivatedArtifacts(first.id)
        val pending = PreviewPendingDraft(PreviewDraftFields("recovered pending title"),
            first.record.contextInput, first.record.workloadInput)
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            library().open(first.id).save(first.record, pending.fields))
        val recovery = library().recoverPending(first.record, pending) as PreviewLibraryCreate.Saved
        assertNotEquals(first.id, recovery.id)
        assertEquals(pending.fields, loaded(recovery.id).fields)
        assertEquals(first.record.baselineIdentity, loaded(first.id).baselineIdentity)
        assertEquals(LocalDraftStore.MAX_HISTORY.toLong(), count(first.id))
        assertEquals(1L, count(recovery.id))
    }
}
