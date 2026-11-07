package ai.pickyour.core.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Stream-boundary fault injection only: no live transport, scheduler or Android process. */
class VerifiedArtifactCacheRecoverySequenceTest {
    @TempDir lateinit var root: Path
    private val accepted = "accepted complete synthetic catalog".toByteArray()
    private val replacement = "replacement complete synthetic catalog".toByteArray()

    @Test fun `timeout after each delivered prefix preserves accepted content and explicit retry succeeds`() {
        seed()
        for (prefix in 0..replacement.size) {
            val source = PrefixFaultStream(replacement, prefix) {
                throw SocketTimeoutException("synthetic transport timeout")
            }
            assertEquals(CacheInstall.Failed(CacheFailure.IO_FAILURE), cache().install(hash(replacement), source))
            assertEquals(prefix, source.delivered)
            assertFalse(source.closed)
            assertAcceptedOnly()
        }
        retryAndDuplicate()
    }

    @Test fun `truncated response at each prefix cannot activate partial bytes and retry remains possible`() {
        seed()
        for (prefix in replacement.indices) {
            val source = PrefixFaultStream(replacement, prefix) { -1 }
            assertEquals(CacheInstall.Failed(CacheFailure.HASH_MISMATCH), cache().install(hash(replacement), source))
            assertEquals(prefix, source.delivered)
            assertFalse(source.closed)
            assertAcceptedOnly()
        }
        retryAndDuplicate()
    }

    @Test fun `zero progress fails once without retrying or closing the caller stream`() {
        seed()
        val source = object : InputStream() {
            var reads = 0
            var closed = false
            override fun read(): Int = error("bulk reads required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                return 0
            }
            override fun close() { closed = true }
        }
        assertEquals(CacheInstall.Failed(CacheFailure.IO_FAILURE), cache().install(hash(replacement), source))
        assertEquals(1, source.reads)
        assertFalse(source.closed)
        assertAcceptedOnly()
        retryAndDuplicate()
    }

    @Test fun `cancelled slow provider releases cache ownership only after the provider returns`() {
        seed()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor()
        val source = object : ByteArrayInputStream(replacement) {
            var closed = false
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                var cancelled = false
                try {
                    while (true) {
                        try {
                            if (release.await(5, TimeUnit.SECONDS)) break
                            throw IOException("synthetic provider release deadline")
                        } catch (_: InterruptedException) {
                            // A provider may not unblock on cancellation. Its owner must release it.
                            cancelled = true
                            interrupted.countDown()
                        }
                    }
                    return super.read(bytes, offset, length)
                } finally {
                    if (cancelled) Thread.currentThread().interrupt()
                }
            }
            override fun close() { closed = true }
        }
        try {
            val result = executor.submit<Pair<CacheInstall, Boolean>> {
                worker.set(Thread.currentThread())
                val outcome = cache().install(hash(replacement), source)
                outcome to Thread.currentThread().isInterrupted
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Provider was not entered")
            worker.get().interrupt()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Provider did not observe cancellation")
            assertFalse(result.isDone, "Cache cannot claim to unblock a provider")
            assertEquals(CacheRead.Failed(CacheFailure.BUSY), cache().active())
            val duplicate = PrefixFaultStream(replacement, 0) { error("Busy install must not consume input") }
            assertEquals(CacheInstall.Failed(CacheFailure.BUSY), cache().install(hash(replacement), duplicate))
            assertEquals(0, duplicate.reads)
            assertFalse(duplicate.closed)
            release.countDown()
            assertEquals(CacheInstall.Failed(CacheFailure.CANCELLED) to true, result.get(5, TimeUnit.SECONDS))
            assertFalse(source.closed)
            assertAcceptedOnly()
            retryAndDuplicate()
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Owned worker did not terminate")
        }
    }

    private fun seed() {
        assertEquals(CacheInstall.Activated(hash(accepted)), cache().install(hash(accepted), accepted.inputStream()))
    }

    private fun assertAcceptedOnly() {
        assertBytes(accepted, cache().active())
        assertBytes(accepted, cache().pinned(hash(accepted)))
        assertEquals(CacheRead.Missing, cache().pinned(hash(replacement)))
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
    }

    private fun retryAndDuplicate() {
        repeat(2) {
            // Reopening the cache models a new consumer after a failed attempt, not process death.
            assertEquals(CacheInstall.Activated(hash(replacement)), cache().install(hash(replacement), replacement.inputStream()))
            assertBytes(replacement, cache().active())
            assertBytes(accepted, cache().pinned(hash(accepted)))
        }
        Files.list(root).use { entries -> assertEquals(2L, entries.filter { it.fileName.toString().endsWith(".bin") }.count()) }
    }

    private fun assertBytes(expected: ByteArray, read: CacheRead) {
        val available = assertInstanceOf(CacheRead.Available::class.java, read)
        assertEquals(hash(expected), available.hash)
        assertArrayEquals(expected, available.bytes)
    }

    private fun cache() = VerifiedArtifactCache(root, 256)
    private fun hash(bytes: ByteArray) = ArtifactHash(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
    )

    private class PrefixFaultStream(
        private val bytes: ByteArray,
        private val prefix: Int,
        private val fail: () -> Int,
    ) : InputStream() {
        var delivered = 0
            private set
        var reads = 0
            private set
        var closed = false
            private set
        override fun read(): Int = error("bulk reads required")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (delivered == prefix) return fail()
            val count = minOf(length, prefix - delivered)
            bytes.copyInto(target, offset, delivered, delivered + count)
            delivered += count
            return count
        }
        override fun close() { closed = true }
    }
}
