package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

class PreviewDraftArchiveTest {
    @TempDir lateinit var root: Path
    private val libraryPath get() = root.resolve("library").also { Files.createDirectories(it) }
    private val archivePath get() = root.resolve("snapshots").also { Files.createDirectories(it) }
    private fun library() = PreviewDraftLibrary(libraryPath)
    private fun archive() = LocalSnapshotArchive(archivePath)
    private fun state(archive: LocalSnapshotArchive = archive()) = (archive.load() as SnapshotArchiveLoad.Available).state
    private fun complete(): Pair<PreviewDraftId, PreviewStoredDraft> {
        val created = library().create() as PreviewLibraryCreate.Saved
        val store = LocalDraftStore(libraryPath.resolve(created.id.value))
        assertTrue(store.save(created.record.draft.version, ProjectionFixtures.content(),
            created.record.draft.metadata + ("opaque.external" to "retained exact value")) is DraftSave.Saved)
        return created.id to (library().open(created.id).load() as PreviewDraftLoad.Available).record
    }
    private fun review(adapter: PreviewDraftPersistence, source: PreviewStoredDraft, id: String = "build.managed.archive") =
        adapter.prepareSnapshotCandidate(source, ProjectionFixtures.inputs(ProjectionFixtures.identity(id))) as DraftProjectionPreparation.Review
    private fun save(adapter: PreviewDraftPersistence, review: DraftProjectionPreparation.Review, archive: LocalSnapshotArchive = archive(),
        version: SnapshotArchiveVersion = state(archive).version) = adapter.archiveSnapshotCandidate(review,
        review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)

    @Test fun `actual source deletion leaves independently retained current envelope typed history and artifacts unchanged`() {
        val (id, source) = complete()
        val adapter = library().open(id)
        val review = review(adapter, source)
        val beforePointer = PreviewActiveDraftStore(libraryPath).read()
        val archived = save(adapter, review) as DraftSnapshotArchival.Archived
        val unchanged = (adapter.load() as PreviewDraftLoad.Available).record
        assertEquals(source.baselineIdentity, unchanged.baselineIdentity)
        assertEquals(source.fields, unchanged.fields)
        assertEquals(source.history.map { it.content }, unchanged.history.map { it.content })
        assertEquals(beforePointer, PreviewActiveDraftStore(libraryPath).read())
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(id, unchanged))
        assertFalse(Files.exists(libraryPath.resolve(id.value)))
        val retained = (archive().read(archived.entry.key) as SnapshotArchiveRead.Available).snapshot
        assertArrayEquals(review.sourceBytes, retained.sourceBytes())
        assertArrayEquals(review.candidateBytes, retained.bytes())
        assertEquals("retained exact value", retained.sourceMetadata["opaque.external"])
        assertEquals(source.history.map { it.content }, retained.sourceHistory.map { it.content })
        assertEquals(4, retained.pins.size)
        assertEquals(DraftSnapshotArchival.SourceUnavailable(DraftStorageFailure.CORRUPT), save(adapter, review))
        assertEquals(1, state().entries.size)
    }

    @Test fun `completed and tombstoned managed deletion block any archive publication from retained review`() {
        for (interrupted in listOf(false, true)) {
            val (id, source) = complete()
            val adapter = library().open(id)
            val review = review(adapter, source, "build.deleted.$interrupted")
            val version = state().version
            val deleting = if (!interrupted) library() else PreviewDraftLibrary(libraryPath, {}, {
                if (it == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) throw IOException("injected delete interruption")
            })
            if (interrupted) assertTrue(deleting.delete(id, source) is PreviewLibraryDelete.Unconfirmed)
            else assertEquals(PreviewLibraryDelete.Deleted, deleting.delete(id, source))
            assertEquals(DraftSnapshotArchival.SourceUnavailable(DraftStorageFailure.CORRUPT), save(adapter, review, version = version))
            assertEquals(version.hash, state().version.hash)
            assertTrue(state().entries.isEmpty())
            if (interrupted) assertEquals(PreviewLibraryDelete.Deleted, library().delete(id, null))
        }
    }

    @Test fun `library busy or cancelled authority cannot publish and remains held during both archive phases`() {
        val (id, source) = complete()
        val adapter = library().open(id)
        val review = review(adapter, source)
        val archive = archive()
        val expected = state(archive).version
        FileChannel.open(libraryPath.resolve(".preview-library.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            assertEquals(DraftSnapshotArchival.SourceUnavailable(DraftStorageFailure.BUSY), save(adapter, review, archive, expected))
        } }
        Thread.currentThread().interrupt()
        try { assertEquals(DraftSnapshotArchival.SourceUnavailable(DraftStorageFailure.CANCELLED), save(adapter, review, archive, expected)) }
        finally { Thread.interrupted() }
        var phases = 0
        val guarded = LocalSnapshotArchive(archivePath, SnapshotArchiveLimits(), { path, max, retention ->
            VerifiedArtifactCache(path, max, retention) { point -> if (point == CacheWritePoint.STAGED) {
                phases++
                assertEquals(PreviewLibraryDelete.Failed(DraftStorageFailure.BUSY), library().delete(id, source))
                assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.BUSY), adapter.save(source, source.fields.copy(title = "late unsaved text")))
                assertEquals(PreviewDraftLoad.Failed(DraftStorageFailure.BUSY), adapter.load())
            } }
        })
        assertTrue(save(adapter, review, guarded, expected) is DraftSnapshotArchival.Archived)
        assertEquals(2, phases)
        assertEquals(source.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }

    @Test fun `outer access failure after actual archive publication returns uncertain instead of saved feedback`() {
        val (id, source) = complete()
        val ordinary = library().open(id)
        val review = review(ordinary, source)
        val store = LocalDraftStore(libraryPath.resolve(id.value))
        val access = object : PreviewDraftAccess {
            override fun <T> run(failed: (DraftStorageFailure) -> T, action: () -> T): T {
                action()
                // Same adapter boundary used when the canonical library's lock/channel completion
                // returns an IOException after the inner transaction; no physical failure claim.
                return failed(DraftStorageFailure.IO_FAILURE)
            }
        }
        val failing = PreviewDraftPersistence(store, access)
        val result = save(failing, review) as DraftSnapshotArchival.Uncertain
        val state = state()
        assertEquals(1, state.entries.size)
        assertEquals(result.entry.bundleHash, state.entries.single().bundleHash)
        assertArrayEquals(review.sourceBytes, (archive().read(result.entry.key) as SnapshotArchiveRead.Available).snapshot.sourceBytes())
        assertTrue((save(ordinary, review) as DraftSnapshotArchival.Archived).alreadyPresent)
    }

    @Test fun `later guest edits revoke review before the archive can capture any pending or recreated form`() {
        val (id, source) = complete()
        val adapter = library().open(id)
        val review = review(adapter, source)
        val baseline = state().version
        val changed = (adapter.save(source, source.fields.copy(title = "changed after review")) as PreviewDraftSave.Saved).record
        val restored = (adapter.save(changed, source.fields) as PreviewDraftSave.Saved).record
        assertEquals(source.fields, restored.fields)
        assertNotEquals(source.baselineIdentity, restored.baselineIdentity)
        assertEquals(DraftSnapshotArchival.SourceConflict, save(adapter, review, version = baseline))
        assertEquals(baseline.hash, state().version.hash)
        val fresh = review(adapter, restored)
        val saved = save(adapter, fresh) as DraftSnapshotArchival.Archived
        assertArrayEquals(fresh.sourceBytes, (archive().read(saved.entry.key) as SnapshotArchiveRead.Available).snapshot.sourceBytes())
    }

    @Test fun `managed archive cannot enter another draft deletion subtree while outside retention survives that deletion`() {
        val (id, source) = complete()
        val (otherId, otherSource) = complete()
        val adapter = library().open(id)
        val review = review(adapter, source)
        val unsafePath = libraryPath.resolve(otherId.value).resolve("snapshots").also { Files.createDirectory(it) }
        val unsafe = LocalSnapshotArchive(unsafePath)
        val result = save(adapter, review, unsafe)
        val entriesBeforeDeletion = state(unsafe).entries
        val safe = save(adapter, review) as DraftSnapshotArchival.Archived
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(otherId, otherSource))
        assertFalse(Files.exists(unsafePath))
        assertArrayEquals(review.sourceBytes, (archive().read(safe.entry.key) as SnapshotArchiveRead.Available).snapshot.sourceBytes())
        assertEquals(source.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
        // On the unguarded proposal this has already lost an acknowledged nested snapshot through
        // a legitimate different-draft deletion. The outside-library positive remains intact.
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), result)
        assertTrue(entriesBeforeDeletion.isEmpty())
    }

    @Test fun `managed placement excludes the library namespace while standalone source siblings remain supported`() {
        val (id, source) = complete()
        val adapter = library().open(id)
        val review = review(adapter, source)
        val nested = LocalSnapshotArchive(libraryPath.resolve("snapshots").also { Files.createDirectory(it) })
        val rejected = save(adapter, review, nested)
        val independent = root.resolve("independent").also { Files.createDirectory(it) }
        val store = LocalDraftStore(independent.resolve("source").also { Files.createDirectory(it) })
        assertTrue(store.save(null, ProjectionFixtures.content(), source.draft.metadata) is DraftSave.Saved)
        val standalone = PreviewDraftPersistence(store)
        val ownSource = (standalone.load() as PreviewDraftLoad.Available).record
        val ownReview = review(standalone, ownSource, "build.standalone.archive")
        val ownArchive = LocalSnapshotArchive(independent.resolve("snapshots").also { Files.createDirectory(it) })
        val saved = save(standalone, ownReview, ownArchive) as DraftSnapshotArchival.Archived
        assertArrayEquals(ownReview.sourceBytes, (ownArchive.read(saved.entry.key) as SnapshotArchiveRead.Available).snapshot.sourceBytes())
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), rejected)
        assertTrue(state(nested).entries.isEmpty())
        assertEquals(source.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
