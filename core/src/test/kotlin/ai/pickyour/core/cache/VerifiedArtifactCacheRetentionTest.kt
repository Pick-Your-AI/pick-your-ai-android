package ai.pickyour.core.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VerifiedArtifactCacheRetentionTest {
    @TempDir lateinit var root: Path
    private val first = byteArrayOf(1, 2)
    private val second = byteArrayOf(3, 4)
    private val third = byteArrayOf(5, 6)
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun cache(count: Int = 2, bytes: Long = 4) = VerifiedArtifactCache(root, 16, CacheRetentionLimits(count, bytes))
    private fun install(cache: VerifiedArtifactCache, content: ByteArray) = cache.install(hash(content), content.inputStream())
    private fun assertActive(content: ByteArray) {
        val active = assertInstanceOf(CacheRead.Available::class.java, cache().active())
        assertArrayEquals(content, active.bytes)
    }
    private fun artifactCount() = Files.list(root).use { paths -> paths.filter { it.toString().endsWith(".bin") }.count() }

    @Test fun `artifact count rejects new identities without changing active pointer or old pins`() {
        assertEquals(CacheInstall.Activated(hash(first)), install(cache(), first))
        assertEquals(CacheInstall.Activated(hash(second)), install(cache(), second))
        val pointer = Files.readAllBytes(root.resolve("active"))
        assertEquals(CacheInstall.Failed(CacheFailure.RETENTION_LIMIT), install(cache(bytes = 100), third))
        assertArrayEquals(pointer, Files.readAllBytes(root.resolve("active")))
        assertActive(second)
        assertArrayEquals(first, (cache().pinned(hash(first)) as CacheRead.Available).bytes)
        assertEquals(CacheRead.Missing, cache().pinned(hash(third)))
        assertEquals(2L, artifactCount())
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
    }

    @Test fun `byte budget accepts exact total and remains effective after reopening`() {
        assertEquals(CacheInstall.Activated(hash(first)), install(cache(count = 10), first))
        assertEquals(CacheInstall.Activated(hash(second)), install(cache(count = 10), second))
        assertEquals(CacheInstall.Failed(CacheFailure.RETENTION_LIMIT), install(cache(count = 10), byteArrayOf(5)))
        assertActive(second)
        assertEquals(2L, artifactCount())
    }

    @Test fun `first oversized retained payload fails without creating a catalog`() {
        val source = object : ByteArrayInputStream(first) {
            var closed = false
            override fun close() { closed = true }
        }
        assertEquals(CacheInstall.Failed(CacheFailure.RETENTION_LIMIT), cache(bytes = 1).install(hash(first), source))
        assertFalse(source.closed)
        assertEquals(CacheRead.Missing, cache().active())
        assertEquals(0L, artifactCount())
    }

    @Test fun `zero length payloads still consume an artifact slot`() {
        val empty = byteArrayOf()
        assertEquals(CacheInstall.Activated(hash(empty)), install(cache(count = 1), empty))
        assertEquals(CacheInstall.Failed(CacheFailure.RETENTION_LIMIT), install(cache(count = 1), first))
        assertActive(empty)
    }

    @Test fun `over budget legacy directories keep reads and verified reactivation without eviction`() {
        val unbounded = VerifiedArtifactCache(root, 16)
        install(unbounded, first)
        install(unbounded, second)
        install(unbounded, third)
        val bounded = cache(count = 1, bytes = 1)
        assertArrayEquals(third, (bounded.active() as CacheRead.Available).bytes)
        assertEquals(CacheInstall.Activated(hash(first)), install(bounded, first))
        assertArrayEquals(second, (bounded.pinned(hash(second)) as CacheRead.Available).bytes)
        assertEquals(CacheInstall.Failed(CacheFailure.RETENTION_LIMIT), install(bounded, byteArrayOf(7)))
        assertActive(first)
        assertEquals(3L, artifactCount())
    }

    @Test fun `failed preactivation publication consumes quota without becoming active`() {
        install(cache(), first)
        val failing = VerifiedArtifactCache(root, 16, CacheRetentionLimits(2, 4)) {
            if (it == CacheWritePoint.ARTIFACT_PUBLISHED) throw IOException("injected directory sync failure")
        }
        assertEquals(CacheInstall.Failed(CacheFailure.IO_FAILURE), install(failing, second))
        assertActive(first)
        assertEquals(CacheInstall.Failed(CacheFailure.RETENTION_LIMIT), install(cache(), third))
        assertEquals(CacheInstall.Activated(hash(second)), install(cache(), second))
        assertActive(second)
        assertEquals(2L, artifactCount())
    }

    @Test fun `reactivation verifies existing immutable bytes even when quotas are exhausted`() {
        install(cache(count = 1), first)
        Files.write(root.resolve("${hash(first).hex}.bin"), second)
        assertEquals(CacheInstall.Failed(CacheFailure.CORRUPT), install(cache(count = 1), first))
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), cache().active())
    }

    @Test fun `managed symlink payload is rejected without following or changing its target`() {
        install(cache(), first)
        val target = Files.write(root.resolve("outside"), second)
        Files.createSymbolicLink(root.resolve("${hash(second).hex}.bin"), target)
        assertEquals(CacheInstall.Failed(CacheFailure.CORRUPT), install(cache(count = 10, bytes = 100), third))
        assertArrayEquals(second, Files.readAllBytes(target))
        assertActive(first)
    }

    @Test fun `unrelated files stay outside the managed payload quota and are never deleted`() {
        Files.write(root.resolve("application-note.bin"), ByteArray(100))
        assertEquals(CacheInstall.Activated(hash(first)), install(cache(count = 1, bytes = 2), first))
        assertEquals(100L, Files.size(root.resolve("application-note.bin")))
        assertActive(first)
    }

    @Test fun `retention budgets reject nonpositive values`() {
        assertThrows(IllegalArgumentException::class.java) { CacheRetentionLimits(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { CacheRetentionLimits(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { CacheRetentionLimits(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { CacheRetentionLimits(1, -1) }
    }

    @Test fun `cooperating concurrent writers cannot both consume the final artifact slot`() {
        install(cache(), first)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(second, third).map { content -> pool.submit<CacheInstall> {
                start.await()
                install(cache(), content)
            } }
            start.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is CacheInstall.Activated })
            assertEquals(1, results.count { it == CacheInstall.Failed(CacheFailure.BUSY) ||
                it == CacheInstall.Failed(CacheFailure.RETENTION_LIMIT) })
            assertEquals(2L, artifactCount())
            assertArrayEquals(first, (cache().pinned(hash(first)) as CacheRead.Available).bytes)
        } finally { pool.shutdownNow() }
    }
}
