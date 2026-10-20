package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

class PreviewCatalogRetentionTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private val directory get() = root.resolve(PreviewCatalogRetention.DIRECTORY)
    private fun original() = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json"))
        .use { it.readBytes() }
    private fun changed(revision: Int) = (mapper.readTree(original()) as ObjectNode).apply {
        put("revision", revision)
    }.let(mapper::writeValueAsBytes)
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun cache() = VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES)
    private fun names() = Files.list(directory).use { it.map { p -> p.fileName.toString() }.toList().toSet() }
    private fun retained(bytes: ByteArray) = PreviewCatalogRetentionResult.Available(hash(bytes))
    private fun writer(
        count: Int = PreviewCatalogRetention.MAX_SNAPSHOTS,
        factory: (Path, Int, CacheRetentionLimits) -> VerifiedArtifactCache =
            { path, maximum, limits -> VerifiedArtifactCache(path, maximum, limits) },
        sync: (Path) -> Unit = { path -> FileChannel.open(path).use { it.force(true) } },
    ) = PreviewCatalogRetention(root, CacheRetentionLimits(count, PreviewCatalogRetention.MAX_RETAINED_BYTES), factory, sync)

    @Test fun `retained pins survive replacement and reopen without changing another cache head`() {
        val old = original()
        val next = changed(2)
        assertEquals(retained(old), writer().retain(old))
        assertEquals(retained(next), writer().retain(next))
        val before = names()
        assertEquals(retained(old), writer().retain(old))
        assertEquals(before, names())
        assertEquals(hash(next), (cache().active() as CacheRead.Available).hash)
        assertArrayEquals(old, (cache().pinned(hash(old)) as CacheRead.Available).bytes)
        val oldCatalog = requireNotNull(PreviewCatalogBrowser.read(old))
        val nextCatalog = requireNotNull(PreviewCatalogBrowser.read(next))
        val item = oldCatalog.items.first()
        val result = PreviewCatalogPinResolver(cache()::pinned).resolve(
            PreviewCatalogPin(item.id, item.version, oldCatalog.hash), nextCatalog)
        val exact = assertInstanceOf(PreviewCatalogPinResolution.Available::class.java, result)
        assertEquals(PreviewCatalogPinSource.RETAINED_SNAPSHOT, exact.source)
        assertEquals(oldCatalog.hash, exact.detail.snapshotHash)
        assertEquals(item.id, exact.detail.item.id)
        assertEquals(item.version, exact.detail.item.version)
    }

    @Test fun `invalid unsupported and oversized snapshots never provision a cache`() {
        val sentinel = root.resolve("unrelated-private-record")
        val privateBytes = byteArrayOf(7, 9, 11)
        Files.write(sentinel, privateBytes)
        val newer = (mapper.readTree(original()) as ObjectNode).put("schema_version", "2.0.0")
        for (bytes in listOf("{}".toByteArray(), mapper.writeValueAsBytes(newer))) {
            assertEquals(PreviewCatalogRetentionResult.InvalidSnapshot, writer().retain(bytes))
            assertFalse(Files.exists(directory))
        }
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.TOO_LARGE),
            writer().retain(ByteArray(PreviewCatalogPinResolver.MAX_BYTES + 1)))
        assertFalse(Files.exists(directory))
        assertArrayEquals(privateBytes, Files.readAllBytes(sentinel))
    }

    @Test fun `quota preserves all older pins and allows exact reuse without activation`() {
        val first = original()
        val second = changed(2)
        val third = changed(3)
        assertEquals(retained(first), writer(2).retain(first))
        assertEquals(retained(second), writer(2).retain(second))
        val before = names()
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.RETENTION_LIMIT), writer(2).retain(third))
        assertEquals(before, names())
        assertEquals(hash(second), (cache().active() as CacheRead.Available).hash)
        assertEquals(retained(first), writer(2).retain(first))
        assertEquals(hash(second), (cache().active() as CacheRead.Available).hash)
        assertArrayEquals(first, (cache().pinned(hash(first)) as CacheRead.Available).bytes)
        assertEquals(CacheRead.Missing, cache().pinned(hash(third)))
    }

    @Test fun `missing root and foreign child preserve bytes until explicit repair`() {
        val missing = root.resolve("missing").resolve("application")
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.CORRUPT),
            PreviewCatalogRetention(missing).retain(original()))
        assertFalse(Files.exists(root.resolve("missing")))
        val sentinel = byteArrayOf(5, 4, 3, 2)
        Files.write(directory, sentinel)
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.CORRUPT), writer().retain(original()))
        assertArrayEquals(sentinel, Files.readAllBytes(directory))
        Files.delete(directory)
        assertEquals(retained(original()), writer().retain(original()))
    }

    @Test fun `symbolic link roots and children never receive catalog writes`() {
        val target = Files.createDirectory(root.resolve("separate"))
        val sentinel = target.resolve("keep")
        Files.write(sentinel, byteArrayOf(1, 2, 3))
        Files.createSymbolicLink(directory, target)
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.CORRUPT), writer().retain(original()))
        assertEquals(listOf("keep"), Files.list(target).use { it.map { p -> p.fileName.toString() }.toList() })
        val alias = root.resolve("root-alias")
        Files.createSymbolicLink(alias, target)
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.CORRUPT),
            PreviewCatalogRetention(alias).retain(original()))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(sentinel))
    }

    @Test fun `failed provisioning cannot acknowledge or publish and permits a fresh retry`() {
        val bytes = original()
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.IO_FAILURE),
            writer(sync = { throw IOException("private injected detail") }).retain(bytes))
        assertTrue(Files.isDirectory(directory))
        assertTrue(names().isEmpty())
        assertEquals(retained(bytes), writer().retain(bytes))
        assertArrayEquals(bytes, (cache().pinned(hash(bytes)) as CacheRead.Available).bytes)
    }

    @Test fun `readable interrupted pin requires directory sync before acknowledgement and retry preserves head`() {
        val old = original()
        val next = changed(2)
        assertEquals(retained(old), writer().retain(old))
        // Model a forced artifact renamed before its directory fsync could be confirmed.
        val stage = directory.resolve("interrupted-artifact")
        FileChannel.open(stage, CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(next)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(stage, directory.resolve("${hash(next).hex}.bin"), ATOMIC_MOVE)
        assertArrayEquals(next, (cache().pinned(hash(next)) as CacheRead.Available).bytes)
        val before = names()
        val attempts = mutableListOf<Path>()
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.IO_FAILURE), writer(sync = { path ->
            attempts.add(path)
            if (path == directory.toRealPath()) throw IOException("private directory failure")
            FileChannel.open(path).use { it.force(true) }
        }).retain(next))
        assertEquals(listOf(root.toRealPath(), directory.toRealPath()), attempts)
        assertEquals(before, names())
        assertEquals(hash(old), (cache().active() as CacheRead.Available).hash)
        attempts.clear()
        assertEquals(retained(next), writer(sync = { path ->
            attempts.add(path)
            FileChannel.open(path).use { it.force(true) }
        }).retain(next))
        assertEquals(listOf(root.toRealPath(), directory.toRealPath()), attempts)
        assertEquals(hash(old), (cache().active() as CacheRead.Available).hash)
        assertArrayEquals(old, (cache().pinned(hash(old)) as CacheRead.Available).bytes)
        assertEquals(before, names())
    }

    @Test fun `failed and uncertain publication preserve old pins and require separate reread`() {
        val old = original()
        val next = changed(2)
        assertEquals(retained(old), writer().retain(old))
        val before = names()
        fun fault(point: CacheWritePoint) = writer(factory = { path, maximum, limits ->
            VerifiedArtifactCache(path, maximum, limits) { reached ->
                if (reached == point) throw IOException("private injected failure")
            }
        })
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.IO_FAILURE),
            fault(CacheWritePoint.STAGED).retain(next))
        assertEquals(before, names())
        assertEquals(hash(old), (cache().active() as CacheRead.Available).hash)
        assertEquals(PreviewCatalogRetentionResult.Uncertain(hash(next)),
            fault(CacheWritePoint.POINTER_ACTIVATED).retain(next))
        assertArrayEquals(old, (cache().pinned(hash(old)) as CacheRead.Available).bytes)
        assertArrayEquals(next, (cache().pinned(hash(next)) as CacheRead.Available).bytes)
        assertEquals(retained(next), writer().retain(next))
    }

    @Test fun `busy retention fails promptly without replacing existing snapshot`() {
        val old = original()
        val next = changed(2)
        assertEquals(retained(old), writer().retain(old))
        val before = names()
        FileChannel.open(directory.resolve("lock"), WRITE).use { channel ->
            channel.lock().use {
                assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.BUSY), writer().retain(next))
            }
        }
        assertEquals(before, names())
        assertEquals(hash(old), (cache().active() as CacheRead.Available).hash)
        assertEquals(retained(next), writer().retain(next))
    }

    @Test fun `cancellation does no work and an explicit later request can proceed`() {
        Thread.currentThread().interrupt()
        try {
            assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.CANCELLED), writer().retain(original()))
            assertFalse(Files.exists(directory))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals(retained(original()), writer().retain(original()))
    }

    @Test fun `corrupt immutable content is reported without repair or replacement`() {
        val bytes = original()
        assertEquals(retained(bytes), writer().retain(bytes))
        val path = directory.resolve("${hash(bytes).hex}.bin")
        val corrupt = byteArrayOf(8, 7, 6)
        Files.write(path, corrupt)
        val before = names()
        assertEquals(PreviewCatalogRetentionResult.Failed(CacheFailure.CORRUPT), writer().retain(bytes))
        assertArrayEquals(corrupt, Files.readAllBytes(path))
        assertEquals(before, names())
    }

    @Test fun `caller array mutations cannot rewrite retained original bytes`() {
        val bytes = original()
        val expected = bytes.copyOf()
        val identity = hash(bytes)
        assertEquals(PreviewCatalogRetentionResult.Available(identity), writer().retain(bytes))
        bytes.fill(0)
        val first = cache().pinned(identity) as CacheRead.Available
        assertArrayEquals(expected, first.bytes)
        first.bytes.fill(1)
        assertArrayEquals(expected, (cache().pinned(identity) as CacheRead.Available).bytes)
    }
}
