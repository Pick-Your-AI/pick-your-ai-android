package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.security.MessageDigest

class PreviewActiveDraftStoreTest {
    @TempDir lateinit var root: Path
    private fun store() = PreviewActiveDraftStore(root)
    private fun id() = (PreviewDraftLibrary(root).create() as PreviewLibraryCreate.Saved).id
    @Test fun `selection failure or uncertainty never commits editor switch`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val pending = PreviewDraftFields("pending")
        val previous = PreviewDraftSession(first.id, first.record, pending, true)
        val loaded = library.open(next.id).load()
        for (outcome in listOf(PreviewActiveWrite.Uncertain, PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE))) {
            assertSame(previous, previous.switchTo(next.id, loaded, outcome))
        }
        val selected = previous.switchTo(next.id, loaded, PreviewActiveWrite.Saved)
        assertEquals(next.id, selected.id)
        assertEquals(next.record.fields, selected.fields)
        assertTrue(library.open(first.id).save(previous.record, pending) is PreviewDraftSave.Saved)
    }
    @Test fun `absent selection is distinct and successful selection survives a fresh instance`() {
        assertEquals(PreviewActiveRead.None, store().read())
        val selected = id()
        assertEquals(PreviewActiveWrite.Saved, store().select(selected))
        assertEquals(PreviewActiveRead.Selected(selected), store().read())
    }
    @Test fun `switch retains prior artifact and selection never grants draft write authority`() {
        val first = id(); val next = id()
        assertEquals(PreviewActiveWrite.Saved, store().select(first))
        assertEquals(PreviewActiveWrite.Saved, store().select(next))
        assertEquals(PreviewActiveRead.Selected(next), store().read())
        assertTrue(PreviewDraftLibrary(root).open(first).load() is PreviewDraftLoad.Available)
        assertTrue(PreviewDraftLibrary(root).open(next).load() is PreviewDraftLoad.Available)
    }
    @Test fun `malformed future and traversal payloads fail closed without default selection`() {
        for (text in listOf("v1\n", "v2\nguest-draft\n", "v1\n../escape\n", "v1\nguest-draft\nextra")) {
            store()
            val bytes = text.toByteArray()
            val hash = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            VerifiedArtifactCache(root.resolve(".active-draft"), 128).install(hash, bytes.inputStream())
            assertEquals(PreviewActiveRead.Failed(CacheFailure.CORRUPT), store().read())
        }
    }
    @Test fun `failed activation keeps old selection and uncertain activation must be reopened`() {
        val first = id(); val next = id()
        store().select(first)
        fun fault(point: CacheWritePoint) = PreviewActiveDraftStore(root,
            { VerifiedArtifactCache(it, 128) { at -> if (at == point) throw IOException("injected") } }, {})
        assertTrue(fault(CacheWritePoint.POINTER_STAGED).select(next) is PreviewActiveWrite.Failed)
        assertEquals(PreviewActiveRead.Selected(first), store().read())
        assertEquals(PreviewActiveWrite.Uncertain, fault(CacheWritePoint.POINTER_ACTIVATED).select(next))
        assertEquals(PreviewActiveRead.Selected(next), store().read())
    }
    @Test fun `unavailable and symlink targets are not selected`() {
        val missing = PreviewDraftId("guest-draft")
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), store().select(missing))
        Files.createSymbolicLink(root.resolve(missing.value), Files.createDirectory(root.resolve("other")))
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), store().select(missing))
        assertEquals(PreviewActiveRead.None, store().read())
    }
    @Test fun `root sync is retried after failed initialization`() {
        assertThrows(IOException::class.java) { PreviewActiveDraftStore(root, { VerifiedArtifactCache(it,128) }, { throw IOException("injected") }) }
        var synced = false
        PreviewActiveDraftStore(root, { VerifiedArtifactCache(it,128) }, { synced = true })
        assertTrue(synced)
    }
}
