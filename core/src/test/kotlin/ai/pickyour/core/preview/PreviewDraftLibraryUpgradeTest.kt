package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheFailure
import ai.pickyour.core.cache.CacheLockSubprocess
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class PreviewDraftLibraryUpgradeTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun entries() = (library().list() as PreviewLibraryRead.Available).entries

    @Test fun `predecessor guest and UUID records keep identities pins histories context and selection`() {
        val ids = listOf(PreviewDraftId("guest-draft"),
            PreviewDraftId("draft-11111111-1111-1111-1111-111111111111"),
            PreviewDraftId("draft-22222222-2222-2222-2222-222222222222"))
        val prior = ids.associateWith { id ->
            val adapter = PreviewDraftPersistence(LocalDraftStore(Files.createDirectory(root.resolve(id.value))))
            val first = (adapter.save(null, PreviewDraftFields("old", "pin", "v1", "a".repeat(64)),
                PreviewDraftContext("objective", "context")) as PreviewDraftSave.Saved).record
            (adapter.save(first, PreviewDraftFields("current", "pin", "v1", "a".repeat(64))) as PreviewDraftSave.Saved).record
        }
        val unavailable = PreviewDraftId("draft-33333333-3333-3333-3333-333333333333")
        LocalDraftStore(Files.createDirectory(root.resolve(unavailable.value))).save(null, DraftContent(), mapOf("preview.format" to "future"))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(ids[1]))
        val reopened = entries()
        assertEquals((ids + unavailable).toSet(), reopened.map { it.id }.toSet())
        for ((id, original) in prior) {
            val record = (reopened.single { it.id == id }.state as PreviewDraftLoad.Available).record
            assertEquals(original.draft.version.store, record.draft.version.store)
            assertEquals(original.baselineIdentity, record.baselineIdentity)
            assertEquals(original.fields, record.fields)
            assertEquals(original.contextInput, record.contextInput)
            assertEquals(original.history.map { it.content }, record.history.map { it.content })
        }
        assertTrue(reopened.single { it.id == unavailable }.state is PreviewDraftLoad.Failed)
        assertEquals(PreviewActiveRead.Selected(ids[1]), PreviewActiveDraftStore(root).read())
        assertEquals(PreviewLibraryDelete.Deleted, library().delete(ids[2], prior[ids[2]]))
        assertEquals(PreviewActiveRead.Selected(ids[1]), PreviewActiveDraftStore(root).read())
        assertEquals(prior[ids[0]]!!.baselineIdentity, (library().open(ids[0]).load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals((ids.take(2) + unavailable).toSet(), entries().map { it.id }.toSet())
    }

    @Test fun `legacy bootstrap cannot exceed a populated 64 record predecessor library`() {
        repeat(PreviewDraftLibrary.MAX_DRAFTS) { index ->
            Files.createDirectory(root.resolve("draft-00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }
        assertEquals(64, entries().size)
        assertThrows(IllegalArgumentException::class.java) { library().openLegacy() }
        assertFalse(Files.exists(root.resolve("guest-draft")))
        assertEquals(64, entries().size)
    }

    @Test fun `pending deletion cannot become a newly acknowledged active selection`() {
        val target = library().create() as PreviewLibraryCreate.Saved
        val previous = library().create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        active.select(previous.id)
        val interrupted = PreviewDraftLibrary(root, {}, {
            if (it == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) throw IOException("injected")
        })
        assertTrue(interrupted.delete(target.id, target.record) is PreviewLibraryDelete.Unconfirmed)
        assertTrue(entries().single { it.id == target.id }.deletionPending)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), active.select(target.id))
        assertEquals(PreviewActiveRead.Selected(previous.id), PreviewActiveDraftStore(root).read())
    }

    @Test fun `a missing explicitly selected guest is not recreated as an empty default`() {
        val id = PreviewDraftId("guest-draft")
        Files.createDirectory(root.resolve(id.value))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(id))
        Files.delete(root.resolve(id.value))
        assertThrows(IllegalArgumentException::class.java) { library().openLegacy() }
        assertFalse(Files.exists(root.resolve(id.value)))
        assertEquals(PreviewActiveRead.Selected(id), PreviewActiveDraftStore(root).read())
    }

    @Test fun `selection returns a typed failure when the provisioned root is removed`() {
        val directory = Files.createDirectory(root.resolve("removed"))
        val active = PreviewActiveDraftStore(directory)
        Files.delete(directory.resolve(".active-draft"))
        Files.delete(directory)

        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), active.select(PreviewDraftId("guest-draft")))
        assertFalse(Files.exists(directory))
    }

    @Test fun `selection returns a typed failure without modifying a replacement root file`() {
        val directory = Files.createDirectory(root.resolve("replaced"))
        val active = PreviewActiveDraftStore(directory)
        Files.delete(directory.resolve(".active-draft"))
        Files.delete(directory)
        Files.writeString(directory, "replacement stays intact")

        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), active.select(PreviewDraftId("guest-draft")))
        assertEquals("replacement stays intact", Files.readString(directory))
    }

    @Test fun `selection contender cannot acknowledge a target or release the active deletion process lock`() {
        val target = library().create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        val deleting = PreviewDraftLibrary(root, {}, {
            if (it == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) {
                assertEquals("BUSY", externalLockAttempt())
                assertEquals(PreviewActiveWrite.Failed(CacheFailure.BUSY), active.select(target.id))
                assertEquals("BUSY", externalLockAttempt())
            }
        })
        assertEquals(PreviewLibraryDelete.Deleted, deleting.delete(target.id, target.record))
        assertEquals("ACQUIRED", externalLockAttempt())
        assertEquals(PreviewActiveRead.None, active.read())
    }

    private fun externalLockAttempt(): String {
        val classpath = listOf(CacheLockSubprocess::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val errors = Files.createTempFile(root, "selection-probe-", ".stderr")
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
