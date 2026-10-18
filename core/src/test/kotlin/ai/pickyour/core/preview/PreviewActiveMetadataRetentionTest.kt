package ai.pickyour.core.preview

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class PreviewActiveMetadataRetentionTest {
    @TempDir lateinit var root: Path

    @Test fun `repeated creation selection and deletion retain at most current and previous selection metadata`() {
        val library = PreviewDraftLibrary(root)
        val keeper = library.create() as PreviewLibraryCreate.Saved
        val keeperPath = root.resolve(keeper.id.value)
        val original = snapshot(keeperPath)
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(keeper.id))

        repeat(PreviewDraftLibrary.MAX_DRAFTS + 8) {
            val next = library.create() as PreviewLibraryCreate.Saved
            assertEquals(PreviewActiveWrite.Saved, active.select(next.id))
            assertEquals(PreviewLibraryDelete.Deleted, library.delete(next.id, next.record))
            // The latest deleted selection remains explicit until the next confirmed switch.
            assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
            assertEquals(listOf(keeper.id), (library.list() as PreviewLibraryRead.Available).entries.map { it.id })
        }

        assertEquals(original, snapshot(keeperPath), "Unrelated saved history and pins must remain byte-identical")
        assertEquals(2, metadataArtifacts().size,
            "Superseded last-opened IDs must not accumulate after their drafts are deleted")
    }

    private fun metadataArtifacts(): List<Path> = Files.list(root.resolve(".active-draft")).use { paths ->
        paths.filter { Regex("[0-9a-f]{64}\\.bin").matches(it.fileName.toString()) }.toList()
    }

    private fun snapshot(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { path ->
            directory.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        }
    }
}
