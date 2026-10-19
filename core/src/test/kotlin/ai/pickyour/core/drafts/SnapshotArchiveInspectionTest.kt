package ai.pickyour.core.drafts

import ai.pickyour.core.evaluation.SnapshotEvaluationFixtures
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SnapshotArchiveInspectionTest {
    @TempDir lateinit var root: Path

    @Test fun `inspection cannot initialize an unused namespace or reconstruct a lost seal or index`() {
        val path = root.resolve("empty").also(Files::createDirectories)
        val archive = LocalSnapshotArchive(path)
        assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CORRUPT), archive.loadExisting())
        assertFalse(Files.exists(path.resolve("namespace")))
        assertFalse(Files.exists(path.resolve("indices/active")))
        val initialized = (archive.load() as SnapshotArchiveLoad.Available).state
        assertEquals(0, initialized.version.generation)
        val before = Files.readAllBytes(path.resolve("namespace"))
        Files.delete(path.resolve("indices/active"))
        assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CORRUPT), archive.loadExisting())
        assertFalse(Files.exists(path.resolve("indices/active")))
        assertArrayEquals(before, Files.readAllBytes(path.resolve("namespace")))
        Files.delete(path.resolve("namespace"))
        assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CORRUPT), archive.loadExisting())
        assertFalse(Files.exists(path.resolve("namespace")))
        assertFalse(Files.exists(path.resolve("indices/active")))
    }

    @Test fun `inspection captures the exact existing index without activation or source liveness`() {
        val saved = SnapshotEvaluationFixtures.save(root.resolve("saved"))
        val path = root.resolve("saved/archive")
        fun bytes() = Files.walk(path).use { paths -> paths.filter(Files::isRegularFile).toList()
            .associate { path.relativize(it).toString() to Files.readAllBytes(it).toList() } }
        val before = bytes()
        Files.walk(saved.sourcePath).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        val loaded = (saved.archive.loadExisting() as SnapshotArchiveLoad.Available).state
        assertEquals(saved.version.hash, loaded.version.hash)
        assertEquals(saved.version.generation, loaded.version.generation)
        assertEquals(saved.entry.key, loaded.entries.single().key)
        assertEquals(saved.entry.bundleHash, loaded.entries.single().bundleHash)
        assertTrue(saved.archive.readAt(loaded.version, loaded.entries.single().key) is SnapshotArchiveRead.Available)
        assertEquals(before, bytes())
    }
}
