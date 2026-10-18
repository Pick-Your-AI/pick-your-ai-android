package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.io.IOException

class PreviewDraftLibraryTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun entries() = (library().list() as PreviewLibraryRead.Available).entries
    private fun created() = library().create() as PreviewLibraryCreate.Saved

    @Test fun `failed target switch preserves pending form identity and original save authority`() {
        val first = created()
        val target = created()
        val pending = PreviewDraftFields("pending unsaved")
        val current = PreviewDraftSession(first.id, first.record, pending, true)
        for (failure in listOf(null, PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT),
            PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE))) {
            val next = current.switchTo(target.id, failure)
            assertSame(current, next)
            assertSame(first.record, next.record)
            assertEquals(pending, next.fields)
            assertTrue(next.ready)
        }
        assertTrue(library().open(first.id).save(current.record, current.fields) is PreviewDraftSave.Saved)
    }
    @Test fun `successful create followed by failed reopen retains old editor and new saved entry`() {
        val first = created()
        val current = PreviewDraftSession(first.id, first.record, PreviewDraftFields("pending"), true)
        val new = created()
        assertSame(current, current.switchTo(new.id, PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE)))
        assertTrue(entries().any { it.id == new.id && it.state is PreviewDraftLoad.Available })
        val switched = current.switchTo(new.id, library().open(new.id).load())
        assertEquals(new.id, switched.id)
        assertEquals(new.record.fields, switched.fields)
        assertTrue(switched.ready)
        assertNotSame(current, switched)
    }
    @Test fun `scan budget rejects oversized unrelated directory without silently omitting drafts`() {
        repeat(PreviewDraftLibrary.MAX_DRAFTS * 4 + 1) { Files.createFile(root.resolve("unrelated-$it")) }
        assertEquals(PreviewLibraryRead.Failed(DraftStorageFailure.LIMIT_EXCEEDED), library().list())
        assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.LIMIT_EXCEEDED), library().create())
    }
    @Test fun `create lists opens and independently renames canonical drafts`() {
        assertTrue(entries().isEmpty())
        val first = created()
        val second = created()
        assertNotEquals(first.id, second.id)
        val renamed = library().open(first.id).save(first.record, PreviewDraftFields("renamed")) as PreviewDraftSave.Saved
        assertEquals(1L, renamed.record.revision)
        assertEquals("renamed", (library().open(first.id).load() as PreviewDraftLoad.Available).record.fields.title)
        assertEquals("", (library().open(second.id).load() as PreviewDraftLoad.Available).record.fields.title)
        assertEquals(2, entries().size)
    }
    @Test fun `duplicate preserves saved pins and domain content without history or token reuse`() {
        val path = Files.createDirectory(root.resolve("guest-draft"))
        val raw = LocalDraftStore(path).save(null, DraftContent(objective="objective"),
            mapOf("preview.format" to "1", "preview.title" to "old", "private.other" to "retained")) as DraftSave.Saved
        val adapter = library().open(PreviewDraftId("guest-draft"))
        val source = (adapter.load() as PreviewDraftLoad.Available).record
        val pinned = (adapter.save(source, PreviewDraftFields("saved", "id", "version", "a".repeat(64))) as PreviewDraftSave.Saved).record
        val copy = library().duplicate(pinned) as PreviewLibraryCreate.Saved
        assertEquals(pinned.fields, copy.record.fields)
        assertEquals(0L, copy.record.revision)
        assertEquals(1, copy.record.history.size)
        val copied = (LocalDraftStore(root.resolve(copy.id.value)).load() as DraftLoad.Available).draft
        assertEquals(raw.draft.content, copied.content)
        assertEquals("retained", copied.metadata["private.other"])
        assertEquals("guest-draft", copied.metadata["preview.duplicateSourceId"])
        assertEquals("1", copied.metadata["preview.duplicateSourceRevision"])
        assertEquals(pinned.draft.version.hash.hex, copied.metadata["preview.duplicateSourceHash"])
        assertEquals(PreviewDraftSave.Conflict, library().open(copy.id).save(pinned, PreviewDraftFields("foreign")))
        assertEquals(1L, (adapter.load() as PreviewDraftLoad.Available).record.revision)
    }
    @Test fun `legacy guest and incomplete or corrupt directories stay visible`() {
        val legacy = Files.createDirectory(root.resolve("guest-draft"))
        assertEquals(PreviewDraftLoad.Missing, entries().single().state)
        LocalDraftStore(legacy).save(null, DraftContent(), mapOf("preview.format" to "future"))
        assertEquals(DraftStorageFailure.UNSUPPORTED_VERSION, (entries().single().state as PreviewDraftLoad.Failed).reason)
        assertEquals("guest-draft", entries().single().id.value)
        assertThrows(UnsupportedOperationException::class.java) { (entries() as MutableList).clear() }
    }
    @Test fun `creation faults retain stable unconfirmed identity and never acknowledge saved`() {
        for (point in LibraryCheckpoint.entries) {
            val result = PreviewDraftLibrary(root) { if (it == point) throw IOException("injected") }.create() as PreviewLibraryCreate.Unconfirmed
            val state = library().open(result.id).load()
            if (point == LibraryCheckpoint.AFTER_DIRECTORY_CREATE) assertEquals(PreviewDraftLoad.Missing, state)
            else assertTrue(state is PreviewDraftLoad.Available)
            assertTrue(entries().any { it.id == result.id })
        }
    }
    @Test fun `library lock contention creates no directory`() {
        FileChannel.open(root.resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use { assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.BUSY), library().create()) }
        }
        assertTrue(entries().isEmpty())
    }
    @Test fun `bounded catalog rejects capacity and invalid path identities`() {
        repeat(PreviewDraftLibrary.MAX_DRAFTS) { index ->
            Files.createDirectory(root.resolve("draft-00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"))
        }
        assertEquals(PreviewDraftLibrary.MAX_DRAFTS, entries().size)
        assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.LIMIT_EXCEEDED), library().create())
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftId("../escape") }
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftId("/absolute") }
    }
    @Test fun `symlink entries fail closed without following target`() {
        val target = Files.createDirectory(root.resolve("other"))
        Files.createSymbolicLink(root.resolve("guest-draft"), target)
        val other = created()
        val entries = entries()
        assertEquals(DraftStorageFailure.CORRUPT, (entries.single { it.id.value == "guest-draft" }.state as PreviewDraftLoad.Failed).reason)
        assertTrue(entries.single { it.id == other.id }.state is PreviewDraftLoad.Available)
        assertThrows(IllegalArgumentException::class.java) { library().open(PreviewDraftId("guest-draft")) }
        assertTrue(Files.list(target).use { it.count() } == 0L)
    }
}
