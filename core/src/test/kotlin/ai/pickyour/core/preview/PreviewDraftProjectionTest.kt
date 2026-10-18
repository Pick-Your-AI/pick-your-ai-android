package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

class PreviewDraftProjectionTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun complete(): Pair<PreviewDraftId, PreviewStoredDraft> {
        val created = library().create() as PreviewLibraryCreate.Saved
        val store = LocalDraftStore(root.resolve(created.id.value))
        assertTrue(store.save(created.record.draft.version, ProjectionFixtures.content(),
            created.record.draft.metadata + ("opaque.external" to "retain exactly")) is DraftSave.Saved)
        return created.id to (library().open(created.id).load() as PreviewDraftLoad.Available).record
    }

    @Test fun `managed candidate review preserves the guest form domain history and local identity`() {
        val (id, record) = complete()
        val adapter = library().open(id)
        val beforePointer = PreviewActiveDraftStore(root).read()
        val review = adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        assertEquals(record.draft.version.hash, review.source.version.hash)
        assertEquals(record.fields.title, review.source.metadata["preview.title"])
        assertEquals("retain exactly", review.source.metadata["opaque.external"])
        val acknowledgment = review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())!!
        val result = adapter.createSnapshotCandidate(review, acknowledgment) as DraftProjectionCreation.Created
        assertEquals("build.local.projection", result.candidate.identity.id)
        assertNotEquals(id.value, result.candidate.identity.id)
        val loaded = (adapter.load() as PreviewDraftLoad.Available).record
        assertEquals(record.baselineIdentity, loaded.baselineIdentity)
        assertEquals(record.fields, loaded.fields)
        assertEquals(record.contextInput, loaded.contextInput)
        assertEquals(record.workloadInput, loaded.workloadInput)
        assertEquals(record.history.map { it.content }, loaded.history.map { it.content })
        assertEquals(beforePointer, PreviewActiveDraftStore(root).read())
        val other = complete()
        assertEquals(DraftProjectionPreparation.Conflict, library().open(other.first).prepareSnapshotCandidate(record, ProjectionFixtures.inputs()))
        assertEquals(DraftProjectionCreation.Conflict, library().open(other.first).createSnapshotCandidate(review, acknowledgment))
        assertEquals(record.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }

    @Test fun `retained managed review cannot materialize after pending or completed deletion`() {
        for (interrupted in listOf(false, true)) {
            val (id, record) = complete()
            val adapter = library().open(id)
            val review = adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
            val acknowledgment = review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())!!
            val deleting = if (!interrupted) library() else PreviewDraftLibrary(root, {}, {
                if (it == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) throw IOException("injected")
            })
            if (interrupted) assertTrue(deleting.delete(id, record) is PreviewLibraryDelete.Unconfirmed)
            else assertEquals(PreviewLibraryDelete.Deleted, deleting.delete(id, record))
            assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.CORRUPT), adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()))
            assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.CORRUPT), adapter.createSnapshotCandidate(review, acknowledgment))
            if (interrupted) assertEquals(PreviewLibraryDelete.Deleted, library().delete(id, null))
            assertFalse(Files.exists(root.resolve(id.value)))
        }
    }

    @Test fun `library contention cancellation and later edits cannot turn an earlier review into current authority`() {
        val (id, record) = complete()
        val adapter = library().open(id)
        val review = adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val acknowledgment = review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())!!
        FileChannel.open(root.resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.BUSY), adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()))
                assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.BUSY), adapter.createSnapshotCandidate(review, acknowledgment))
            }
        }
        Thread.currentThread().interrupt()
        try {
            assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.CANCELLED), adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()))
            assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.CANCELLED), adapter.createSnapshotCandidate(review, acknowledgment))
        } finally { Thread.interrupted() }
        val changed = adapter.save(record, record.fields.copy(title = "pending review changed")) as PreviewDraftSave.Saved
        assertEquals(DraftProjectionCreation.Conflict, adapter.createSnapshotCandidate(review, acknowledgment))
        val returned = adapter.save(changed.record, record.fields) as PreviewDraftSave.Saved
        assertEquals(record.fields, returned.record.fields)
        assertNotEquals(record.baselineIdentity, returned.record.baselineIdentity)
        assertEquals(DraftProjectionCreation.Conflict, adapter.createSnapshotCandidate(review, acknowledgment))
        val fresh = adapter.prepareSnapshotCandidate(returned.record, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        assertTrue(adapter.createSnapshotCandidate(fresh, fresh.acknowledgeRetainedOnly(fresh.retainedOnlyPaths.toSet())) is DraftProjectionCreation.Created)
    }
}
