package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheLockSubprocess
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.TimeUnit

class PreviewDraftDeletionTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun created() = library().create() as PreviewLibraryCreate.Saved
    private fun entries() = (library().list() as PreviewLibraryRead.Available).entries

    @Test fun `delete removes all selected revisions while another draft and its token survive`() {
        val first = created()
        val other = created()
        val adapter = library().open(first.id)
        val latest = (adapter.save(first.record, PreviewDraftFields("edited")) as PreviewDraftSave.Saved).record
        assertEquals(2, latest.history.size)
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(first.id, latest))
        assertFalse(Files.exists(root.resolve(first.id.value), NOFOLLOW_LINKS))
        assertEquals(listOf(other.id), entries().map { it.id })
        assertTrue(library().open(other.id).save(other.record, PreviewDraftFields("still editable")) is PreviewDraftSave.Saved)
        assertEquals(0L, Files.list(root.resolve(".preview-deletions")).use { it.count() })
    }

    @Test fun `previously opened adapters cannot read or resurrect a deleted identity`() {
        val draft = created()
        val beforeDeletion = library().open(draft.id)
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(draft.id, draft.record))
        assertEquals(PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT), beforeDeletion.load())
        for (expected in listOf(null, draft.record)) {
            assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT), beforeDeletion.save(expected, PreviewDraftFields("old editor")))
        }
        assertThrows(IllegalArgumentException::class.java) { library().open(draft.id) }
        assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.CORRUPT), library().duplicate(draft.record))
        assertFalse(Files.exists(root.resolve(draft.id.value), NOFOLLOW_LINKS))
        assertTrue(created().id != draft.id)
    }

    @Test fun `stale foreign and absent delete tokens cannot remove a current saved draft`() {
        val first = created()
        val equalBytesElsewhere = created()
        assertEquals(first.record.baselineIdentity, equalBytesElsewhere.record.baselineIdentity)
        assertEquals(PreviewLibraryDelete.Conflict, library().delete(first.id, equalBytesElsewhere.record))
        assertEquals(PreviewLibraryDelete.Conflict, library().delete(first.id, null))
        val next = (library().open(first.id).save(first.record, PreviewDraftFields("newer")) as PreviewDraftSave.Saved).record
        assertEquals(PreviewLibraryDelete.Conflict, library().delete(first.id, first.record))
        assertEquals(next.baselineIdentity, (library().open(first.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(2, entries().size)
    }

    @Test fun `every interrupted deletion phase remains fail closed and cleanup can be resumed`() {
        for (point in LibraryDeleteCheckpoint.entries) {
            val draft = created()
            val adapter = library().open(draft.id)
            val faulty = PreviewDraftLibrary(root, {}, { if (it == point) throw IOException("injected") })
            assertEquals(PreviewLibraryDelete.Unconfirmed(DraftStorageFailure.IO_FAILURE), faulty.delete(draft.id, draft.record))
            assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT), adapter.save(draft.record, PreviewDraftFields("resurrect")))
            val row = entries().find { it.id == draft.id }
            if (point != LibraryDeleteCheckpoint.TOMBSTONE_REMOVED) {
                assertNotNull(row)
                assertTrue(row!!.deletionPending)
                assertTrue(row.state is PreviewDraftLoad.Failed)
            } else assertNull(row)
            assertEquals(PreviewLibraryDelete.Deleted, library().delete(draft.id, null))
            assertFalse(entries().any { it.id == draft.id })
        }
    }

    @Test fun `legacy deletion never invokes the cold launch empty draft fallback again`() {
        val id = PreviewDraftId("guest-draft")
        val adapter = library().openLegacy()
        val record = (adapter.save(null, PreviewDraftFields("legacy")) as PreviewDraftSave.Saved).record
        val pointer = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, pointer.select(id))
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(id, record))
        assertEquals(PreviewActiveRead.Selected(id), PreviewActiveDraftStore(root).read())
        assertThrows(IllegalArgumentException::class.java) { library().openLegacy() }
        assertThrows(IllegalArgumentException::class.java) { library().open(id) }
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT), adapter.save(null, PreviewDraftFields()))
        assertFalse(Files.exists(root.resolve(id.value), NOFOLLOW_LINKS))
        assertTrue(entries().isEmpty())
        assertTrue(Files.isRegularFile(root.resolve(".preview-guest-deleted"), NOFOLLOW_LINKS))
        assertTrue(library().create() is PreviewLibraryCreate.Saved)
    }

    @Test fun `corrupt and interrupted creation rows can be explicitly removed without hiding other records`() {
        val valid = created()
        val corrupt = created()
        Files.writeString(root.resolve(corrupt.id.value).resolve("revisions/active"), "broken pointer")
        val incomplete = PreviewDraftLibrary(root) { if (it == LibraryCheckpoint.AFTER_DIRECTORY_CREATE) throw IOException("injected") }
            .create() as PreviewLibraryCreate.Unconfirmed
        val before = entries()
        assertTrue(before.single { it.id == corrupt.id }.state is PreviewDraftLoad.Failed)
        assertEquals(PreviewDraftLoad.Missing, before.single { it.id == incomplete.id }.state)
        assertEquals(PreviewLibraryDelete.Conflict, library().delete(corrupt.id, corrupt.record))
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(corrupt.id, null))
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(incomplete.id, null))
        assertEquals(listOf(valid.id), entries().map { it.id })
    }

    @Test fun `symlink cleanup removes only selected links and never follows external targets`() {
        val target = Files.createDirectory(root.resolve("outside"))
        val keep = Files.writeString(target.resolve("keep"), "outside content")
        val id = PreviewDraftId("guest-draft")
        Files.createSymbolicLink(root.resolve(id.value), target)
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(id, null))
        assertEquals("outside content", Files.readString(keep))
        val draft = created()
        Files.createSymbolicLink(root.resolve(draft.id.value).resolve("extra"), target)
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(draft.id, draft.record))
        assertEquals("outside content", Files.readString(keep))
    }

    @Test fun `same JVM contenders retain the deleting operation OS lock and cannot save or create`() {
        val draft = created()
        val adapter = library().open(draft.id)
        val alias = PreviewDraftLibrary(root.resolve("."))
        val deleting = PreviewDraftLibrary(root, {}, { point ->
            if (point == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) {
                assertEquals("BUSY", externalLockAttempt())
                assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.BUSY), adapter.save(draft.record, PreviewDraftFields("racing")))
                assertEquals(PreviewLibraryRead.Failed(DraftStorageFailure.BUSY), alias.list())
                assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.BUSY), alias.create())
                assertEquals(PreviewLibraryDelete.Failed(DraftStorageFailure.BUSY), alias.delete(draft.id, draft.record))
                assertEquals("BUSY", externalLockAttempt())
            }
        })
        assertEquals(PreviewLibraryDelete.Deleted, deleting.delete(draft.id, draft.record))
        assertEquals("ACQUIRED", externalLockAttempt())
    }

    @Test fun `busy canonical record is not mistaken for corrupt data eligible for deletion`() {
        val draft = created()
        FileChannel.open(root.resolve(draft.id.value).resolve("transaction.lock"), WRITE).use { channel ->
            channel.lock().use {
                assertEquals(PreviewLibraryDelete.Failed(DraftStorageFailure.BUSY), library().delete(draft.id, null))
            }
        }
        assertFalse(entries().single().deletionPending)
        assertTrue(library().open(draft.id).load() is PreviewDraftLoad.Available)
    }

    @Test fun `bounded cleanup remains visible and repeated cleanup frees capacity without marker growth`() {
        val draft = created()
        val directory = root.resolve(draft.id.value)
        repeat(PreviewDraftLibrary.MAX_DELETE_ENTRIES) { Files.createFile(directory.resolve("extra-$it")) }
        assertEquals(PreviewLibraryDelete.Unconfirmed(DraftStorageFailure.LIMIT_EXCEEDED), library().delete(draft.id, draft.record))
        assertTrue(entries().single().deletionPending)
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(draft.id, null))
        repeat(PreviewDraftLibrary.MAX_DRAFTS + 1) {
            val next = created()
            assertEquals(PreviewLibraryDelete.Deleted, library().delete(next.id, next.record))
        }
        assertTrue(entries().isEmpty())
        assertEquals(0L, Files.list(root.resolve(".preview-deletions")).use { it.count() })
    }

    @Test fun `deletion transitions preserve foreign pending forms and block only the affected editor`() {
        val current = created()
        val other = created()
        val pending = PreviewDraftFields("pending form")
        val session = PreviewDraftSession(current.id, current.record, pending, true)
        for (outcome in listOf(PreviewLibraryDelete.Deleted, PreviewLibraryDelete.Unconfirmed(DraftStorageFailure.IO_FAILURE))) {
            assertSame(session, session.afterDeletion(other.id, outcome))
            val next = session.afterDeletion(current.id, outcome)
            assertEquals(current.id, next.id)
            assertEquals(pending, next.fields)
            assertNull(next.record)
            assertFalse(next.ready)
            assertFalse(PreviewDraftRestoration.capture(next.record, next.ready, false).canResume(null))
        }
        assertSame(session, session.afterDeletion(current.id, PreviewLibraryDelete.Conflict))
        assertSame(session, session.afterDeletion(current.id, PreviewLibraryDelete.Failed(DraftStorageFailure.BUSY)))
    }

    @Test fun `outer library close failure cannot turn a completed save into a prewrite failure`() {
        val store = LocalDraftStore(Files.createDirectory(root.resolve("standalone")))
        val access = object : PreviewDraftAccess {
            override fun <T> run(failed: (DraftStorageFailure) -> T, action: () -> T): T {
                action()
                return failed(DraftStorageFailure.IO_FAILURE)
            }
        }
        assertEquals(PreviewDraftSave.Uncertain, PreviewDraftPersistence(store, access).save(null, PreviewDraftFields("saved")))
        assertEquals("saved", (PreviewDraftPersistence(store).load() as PreviewDraftLoad.Available).record.fields.title)
    }

    private fun externalLockAttempt(): String {
        val classpath = listOf(CacheLockSubprocess::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val errors = Files.createTempFile(root, "delete-probe-", ".stderr")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, CacheLockSubprocess::class.java.name, root.resolve(".preview-library.lock").toString())
            .redirectError(errors.toFile()).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe timed out")
            val result = process.inputStream.use { String(it.readNBytes(1024), Charsets.UTF_8) }.trim()
            val diagnostic = Files.newInputStream(errors).use { String(it.readNBytes(4096), Charsets.UTF_8) }
            assertEquals(0, process.exitValue(), diagnostic)
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
            Files.deleteIfExists(errors)
        }
    }
}
