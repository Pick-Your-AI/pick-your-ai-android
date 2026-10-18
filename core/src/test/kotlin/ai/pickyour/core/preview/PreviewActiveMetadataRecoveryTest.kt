package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class PreviewActiveMetadataRecoveryTest {
    @TempDir lateinit var root: Path

    @Test fun `legacy accumulation is read without mutation then reclaimed in bounded batches`() {
        val library = PreviewDraftLibrary(root)
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        val directory = root.resolve(".active-draft")
        var last = PreviewDraftId("guest-draft")
        repeat(1024) { index ->
            last = PreviewDraftId("draft-${UUID.nameUUIDFromBytes("legacy-$index".toByteArray())}")
            val bytes = payload(last)
            Files.write(directory.resolve("${hash(bytes).hex}.bin"), bytes)
        }
        val bytes = payload(last)
        assertEquals(CacheInstall.Activated(hash(bytes)),
            VerifiedArtifactCache(directory, 128).install(hash(bytes), bytes.inputStream()))
        val original = snapshot(directory)
        repeat(3) { assertEquals(PreviewActiveRead.Selected(last), PreviewActiveDraftStore(root).read()) }
        assertEquals(original, snapshot(directory), "Reading a legacy selection must not reclaim anything")

        assertEquals(PreviewActiveWrite.Saved, active.select(next.id))
        val remaining = metadataArtifacts().size
        assertTrue(remaining in (1025 - 256)..1023,
            "One operation must reclaim at most one bounded batch of the 1024 legacy payloads")
        repeat(4) {
            val before = metadataArtifacts().size
            assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
            assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
            assertTrue(before - metadataArtifacts().size in 0..256)
        }
        assertEquals(1, metadataArtifacts().size)
        assertEquals(listOf(next.id), (library.list() as PreviewLibraryRead.Available).entries.map { it.id })
    }

    @Test fun `foreign entries are preserved and a scan with no progress cannot grow the directory`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(first.id))
        val directory = root.resolve(".active-draft")
        val foreign = (0 until 1024).map { Files.write(directory.resolve("foreign-$it"), byteArrayOf(3, 7)) }
        val before = snapshot(directory)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.RETENTION_LIMIT), active.select(next.id))
        assertEquals(PreviewActiveRead.Selected(first.id), active.read())
        assertEquals(before, snapshot(directory))
        foreign.forEach(Files::delete)
        assertEquals(PreviewActiveWrite.Saved, active.select(next.id))
        assertEquals(2, metadataArtifacts().size)
    }

    @Test fun `preactivation failures retain the verified current payload and retry cleans orphaned candidates`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(first.id))
        val current = metadataArtifacts().single()
        val before = Files.readAllBytes(current)
        for (point in listOf(CacheWritePoint.STAGED, CacheWritePoint.ARTIFACT_PUBLISHED, CacheWritePoint.POINTER_STAGED)) {
            assertEquals(PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE), fault(point).select(next.id))
            assertEquals(PreviewActiveRead.Selected(first.id), PreviewActiveDraftStore(root).read())
            assertArrayEquals(before, Files.readAllBytes(current))
        }
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(2, metadataArtifacts().size)
        assertArrayEquals(before, Files.readAllBytes(current))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(1, metadataArtifacts().size)
    }

    @Test fun `postactivation uncertainty preserves both payloads until verified reopen and later selection`() {
        val library = PreviewDraftLibrary(root)
        val previous = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(previous.id))
        val prior = metadataArtifacts().single()
        val priorBytes = Files.readAllBytes(prior)
        assertEquals(PreviewActiveWrite.Uncertain, fault(CacheWritePoint.POINTER_ACTIVATED).select(next.id))
        assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
        assertEquals(2, metadataArtifacts().size)
        assertArrayEquals(priorBytes, Files.readAllBytes(prior))
        assertTrue(library.open(previous.id).load() is PreviewDraftLoad.Available)
        assertTrue(library.open(next.id).load() is PreviewDraftLoad.Available)
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(1, metadataArtifacts().size)
    }

    @Test fun `interrupted reclamation fails before publication and reopening preserves the active selection`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val current = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(first.id))
        assertEquals(PreviewActiveWrite.Saved, active.select(current.id))
        val currentPath = root.resolve(".active-draft/${hash(payload(current.id)).hex}.bin")
        val currentBytes = Files.readAllBytes(currentPath)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE),
            fault(CacheWritePoint.CURRENT_PAYLOAD_REMOVED).select(next.id))
        assertFalse(Files.exists(root.resolve(".active-draft/${hash(payload(first.id)).hex}.bin")))
        assertFalse(Files.exists(root.resolve(".active-draft/${hash(payload(next.id)).hex}.bin")))
        val interrupted = snapshot(root.resolve(".active-draft"))
        assertEquals(PreviewActiveRead.Selected(current.id), PreviewActiveDraftStore(root).read())
        assertEquals(interrupted, snapshot(root.resolve(".active-draft")))
        assertArrayEquals(currentBytes, Files.readAllBytes(currentPath))
        assertTrue(library.open(first.id).load() is PreviewDraftLoad.Available)
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
        assertEquals(2, metadataArtifacts().size)
    }

    @Test fun `uncertain activation cannot authorize reclamation before its pointer is synchronized`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val uncertain = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(first.id))
        assertEquals(PreviewActiveWrite.Uncertain, fault(CacheWritePoint.POINTER_ACTIVATED).select(uncertain.id))
        val before = snapshot(root.resolve(".active-draft"))
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE),
            fault(CacheWritePoint.CURRENT_POINTER_VERIFIED).select(next.id))
        assertEquals(before, snapshot(root.resolve(".active-draft")),
            "A failure before confirming pointer durability must preserve every possible predecessor")
        assertEquals(PreviewActiveRead.Selected(uncertain.id), PreviewActiveDraftStore(root).read())
        assertEquals(2, metadataArtifacts().size)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE),
            fault(CacheWritePoint.CURRENT_PAYLOAD_REMOVED).select(next.id))
        assertEquals(PreviewActiveRead.Selected(uncertain.id), PreviewActiveDraftStore(root).read())
        assertArrayEquals(payload(uncertain.id),
            Files.readAllBytes(root.resolve(".active-draft/${hash(payload(uncertain.id)).hex}.bin")))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
        assertEquals(2, metadataArtifacts().size)
    }

    @Test fun `cancellation during reclamation preserves the current payload and interrupt flag`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val current = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(first.id))
        assertEquals(PreviewActiveWrite.Saved, active.select(current.id))
        val cancelling = PreviewActiveDraftStore(root, { directory ->
            VerifiedArtifactCache(directory, 128) { point ->
                if (point == CacheWritePoint.CURRENT_PAYLOAD_REMOVED) Thread.currentThread().interrupt()
            }
        }, {})
        try {
            assertEquals(PreviewActiveWrite.Failed(CacheFailure.CANCELLED), cancelling.select(next.id))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(PreviewActiveRead.Selected(current.id), PreviewActiveDraftStore(root).read())
        assertFalse(Files.exists(root.resolve(".active-draft/${hash(payload(next.id)).hex}.bin")))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(2, metadataArtifacts().size)
    }

    @Test fun `an unreadable pointer preserves unknown prior payloads until explicit replacement is durable`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(first.id))
        val prior = metadataArtifacts().single()
        val priorBytes = Files.readAllBytes(prior)
        Files.writeString(root.resolve(".active-draft/active"), "unreadable pointer")
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE), fault(CacheWritePoint.POINTER_STAGED).select(next.id))
        assertEquals(PreviewActiveRead.Failed(CacheFailure.CORRUPT), PreviewActiveDraftStore(root).read())
        assertArrayEquals(priorBytes, Files.readAllBytes(prior))
        assertEquals(PreviewActiveWrite.Uncertain, fault(CacheWritePoint.POINTER_ACTIVATED).select(next.id))
        assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
        assertArrayEquals(priorBytes, Files.readAllBytes(prior))
        assertEquals(2, metadataArtifacts().size)
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(1, metadataArtifacts().size)
    }

    @Test fun `an integrity invalid current payload remains recoverable by explicit selection`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(first.id))
        val prior = metadataArtifacts().single()
        val damaged = "damaged active metadata".toByteArray()
        Files.write(prior, damaged)
        assertEquals(PreviewActiveRead.Failed(CacheFailure.CORRUPT), PreviewActiveDraftStore(root).read())
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE), fault(CacheWritePoint.POINTER_STAGED).select(next.id))
        assertArrayEquals(damaged, Files.readAllBytes(prior))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(PreviewActiveRead.Selected(next.id), PreviewActiveDraftStore(root).read())
        assertArrayEquals(damaged, Files.readAllBytes(prior))
        assertEquals(PreviewActiveWrite.Saved, PreviewActiveDraftStore(root).select(next.id))
        assertEquals(1, metadataArtifacts().size)
    }

    @Test fun `corrupt incoming content and inactive symlinks fail without overwriting or following them`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(first.id))
        val directory = root.resolve(".active-draft")
        val incoming = directory.resolve("${hash(payload(next.id)).hex}.bin")
        val damaged = "damaged candidate".toByteArray()
        Files.write(incoming, damaged)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), active.select(next.id))
        assertArrayEquals(damaged, Files.readAllBytes(incoming))
        assertEquals(PreviewActiveRead.Selected(first.id), active.read())
        Files.delete(incoming)

        val external = Files.write(root.resolve("unrelated-input"), byteArrayOf(1, 4, 9))
        val link = directory.resolve("${hash("obsolete link".toByteArray()).hex}.bin")
        Files.createSymbolicLink(link, external)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), active.select(next.id))
        assertTrue(Files.isSymbolicLink(link))
        assertArrayEquals(byteArrayOf(1, 4, 9), Files.readAllBytes(external))
        assertEquals(PreviewActiveRead.Selected(first.id), active.read())
        Files.delete(link)
        Files.createDirectory(link)
        assertEquals(PreviewActiveWrite.Failed(CacheFailure.CORRUPT), active.select(next.id))
        assertTrue(Files.isDirectory(link))
        assertEquals(PreviewActiveRead.Selected(first.id), active.read())
    }

    @Test fun `reclamation retains cache lock authority against same and separate JVM contenders`() {
        val library = PreviewDraftLibrary(root)
        val first = library.create() as PreviewLibraryCreate.Saved
        val current = library.create() as PreviewLibraryCreate.Saved
        val next = library.create() as PreviewLibraryCreate.Saved
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(first.id))
        assertEquals(PreviewActiveWrite.Saved, active.select(current.id))
        var observed = false
        val changing = PreviewActiveDraftStore(root, { directory ->
            VerifiedArtifactCache(directory, 128) { point ->
                if (point == CacheWritePoint.CURRENT_PAYLOAD_REMOVED) {
                    observed = true
                    assertEquals(PreviewActiveRead.Failed(CacheFailure.BUSY), active.read())
                    assertEquals(PreviewActiveWrite.Failed(CacheFailure.BUSY), active.select(first.id))
                    val classpath = listOf(CacheLockSubprocess::class.java, kotlin.Unit::class.java)
                        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                        .distinct().joinToString(java.io.File.pathSeparator)
                    val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classpath, CacheLockSubprocess::class.java.name, directory.resolve("lock").toString())
                        .redirectError(root.resolve("lock-probe.stderr").toFile()).start()
                    try {
                        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe timed out")
                        assertEquals(0, process.exitValue())
                        assertEquals("BUSY", process.inputStream.use { String(it.readNBytes(1024)).trim() })
                    } finally { if (process.isAlive) process.destroyForcibly() }
                }
            }
        }, {})
        assertEquals(PreviewActiveWrite.Saved, changing.select(next.id))
        assertTrue(observed)
        assertEquals(PreviewActiveRead.Selected(next.id), active.read())
        assertEquals(2, metadataArtifacts().size)
    }

    private fun fault(point: CacheWritePoint) = PreviewActiveDraftStore(root,
        { VerifiedArtifactCache(it, 128) { at -> if (at == point) throw IOException("injected") } }, {})

    private fun payload(id: PreviewDraftId): ByteArray = "v1\n${id.value}\n".toByteArray(Charsets.US_ASCII)
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { it.toUByte().toString(16).padStart(2, '0') })

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
