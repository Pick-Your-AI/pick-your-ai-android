package ai.pickyour.core.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VerifiedArtifactCacheTest {
    @TempDir lateinit var root: Path
    private val first = "first complete bundle".toByteArray()
    private val second = "second complete bundle".toByteArray()
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun store() = VerifiedArtifactCache(root, 128)

    @Test fun `missing root is rejected without creating directory entries`() {
        val missing = root.resolve("not-provisioned/child")
        assertThrows(IllegalArgumentException::class.java) { VerifiedArtifactCache(missing, 128) }
        assertFalse(Files.exists(root.resolve("not-provisioned")))
    }
    private fun install(bytes: ByteArray = first) = store().install(hash(bytes), bytes.inputStream())
    private fun bytes(result: CacheRead) = assertInstanceOf(CacheRead.Available::class.java, result).bytes

    @Test fun `reopens verified active bytes and preserves old pins`() {
        assertEquals(CacheRead.Missing, store().active())
        assertEquals(CacheRead.Missing, store().pinned(hash(first)))
        assertEquals(CacheInstall.Activated(hash(first)), install())
        assertArrayEquals(first, bytes(store().active()))
        assertEquals(CacheInstall.Activated(hash(second)), install(second))
        assertArrayEquals(second, bytes(store().active()))
        assertArrayEquals(first, bytes(store().pinned(hash(first))))
        bytes(store().active())[0] = 0
        assertArrayEquals(second, bytes(store().active()))
    }

    @Test fun `absent current pointer must not reclaim prior pins on explicit selection`() {
        val cache = store()
        assertEquals(CacheInstall.Activated(hash(first)), cache.installCurrent(hash(first), first.inputStream()))
        assertEquals(CacheInstall.Activated(hash(second)), cache.installCurrent(hash(second), second.inputStream()))
        // Simulate an absent/unreadable current pointer (crash/other-path between publish steps).
        Files.delete(root.resolve("active"))
        val third = "third complete bundle".toByteArray()
        assertEquals(CacheInstall.Activated(hash(third)), cache.installCurrent(hash(third), third.inputStream()))
        // An unreadable pointer grants no authority to discard prior pins (VerifiedArtifactCache.kt:139-140).
        assertArrayEquals(first, bytes(cache.pinned(hash(first))))
        assertArrayEquals(second, bytes(cache.pinned(hash(second))))
        assertArrayEquals(third, bytes(cache.active()))
    }

    @Test fun `successful read diagnostics redact active and pinned payloads without changing storage`() {
        val retained = "synthetic private retained workload".toByteArray()
        val current = """{"workload":"synthetic current workload","secret_ref":"fixture-secret-reference"}""".toByteArray()
        val retainedHash = hash(retained)
        val currentHash = hash(current)
        assertEquals(CacheInstall.Activated(retainedHash), install(retained))
        assertEquals(CacheInstall.Activated(currentHash), install(current))
        val files = Files.list(root).use { paths -> paths.toList().associate { it.fileName.toString() to Files.readAllBytes(it) } }
        val active = assertInstanceOf(CacheRead.Available::class.java, store().active())
        val pinned = assertInstanceOf(CacheRead.Available::class.java, store().pinned(retainedHash))
        val diagnostics = listOf(active.toString(), pinned.toString())
        for ((result, expectedHash, expectedBytes) in listOf(
            Triple(active, currentHash, current), Triple(pinned, retainedHash, retained),
        )) {
            val (readHash, readBytes) = result
            val copied = result.copy()
            assertEquals(expectedHash, readHash)
            assertArrayEquals(expectedBytes, readBytes)
            assertSame(readBytes, result.bytes)
            assertSame(readBytes, copied.bytes)
            assertEquals(result, copied)
            assertEquals(result.hashCode(), copied.hashCode())
        }
        // Copy/destructure keep their existing array semantics; neither can alter retained files.
        val copied = active.copy()
        copied.bytes[0] = 0
        val changedDiagnostic = copied.toString()
        assertEquals(0.toByte(), active.bytes[0])
        assertArrayEquals(current, bytes(store().active()))
        assertArrayEquals(retained, bytes(store().pinned(retainedHash)))
        val after = Files.list(root).use { paths -> paths.toList().associate { it.fileName.toString() to Files.readAllBytes(it) } }
        assertEquals(files.keys, after.keys)
        files.forEach { (name, original) -> assertArrayEquals(original, after.getValue(name)) }
        assertEquals(currentHash.hex, Files.readString(root.resolve("active")))
        assertEquals(listOf("CacheRead.Available", "CacheRead.Available"), diagnostics)
        assertEquals("CacheRead.Available", changedDiagnostic)
    }

    @Test fun `repeat activation retains exactly one content addressed file and caller owns stream`() {
        val source = object : ByteArrayInputStream(first) {
            var closed = false
            override fun close() { closed = true }
            override fun available() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 1))
        }
        assertEquals(CacheInstall.Activated(hash(first)), store().install(hash(first), source))
        assertFalse(source.closed)
        assertEquals(CacheInstall.Activated(hash(first)), install())
        Files.list(root).use { paths -> assertEquals(1L, paths.filter { it.toString().endsWith(".bin") }.count()) }
        assertArrayEquals(first, bytes(store().active()))
    }

    @Test fun `wrong hash and provider failure retain previous active without closing input`() {
        install()
        val source = object : ByteArrayInputStream(second) { var closed = false; override fun close() { closed = true } }
        assertEquals(CacheInstall.Failed(CacheFailure.HASH_MISMATCH), store().install(hash(first), source))
        assertFalse(source.closed)
        val broken = object : InputStream() { override fun read(): Int = throw IOException("secret provider path") }
        assertEquals(CacheInstall.Failed(CacheFailure.IO_FAILURE), store().install(hash(second), broken))
        assertArrayEquals(first, bytes(store().active()))
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
    }

    @Test fun `interrupted provider failure reports cancellation and preserves the prior artifact`() {
        install()
        val source = object : InputStream() {
            var closed = false
            override fun read(): Int {
                Thread.currentThread().interrupt()
                throw IOException("interrupted provider")
            }
            override fun close() { closed = true }
        }
        try {
            assertEquals(CacheInstall.Failed(CacheFailure.CANCELLED), store().install(hash(second), source))
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(source.closed)
        } finally { Thread.interrupted() }
        assertArrayEquals(first, bytes(store().active()))
        assertArrayEquals(first, bytes(store().pinned(hash(first))))
        assertEquals(CacheRead.Missing, store().pinned(hash(second)))
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
        assertEquals(CacheInstall.Activated(hash(second)), install(second))
    }

    @Test fun `interruption at provider EOF reports cancellation when the staging channel is forced`() {
        install()
        val source = object : ByteArrayInputStream(second) {
            var closed = false
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val result = super.read(bytes, offset, length)
                if (result == -1) Thread.currentThread().interrupt()
                return result
            }
            override fun close() { closed = true }
        }
        try {
            assertEquals(CacheInstall.Failed(CacheFailure.CANCELLED), store().install(hash(second), source))
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(source.closed)
        } finally { Thread.interrupted() }
        assertArrayEquals(first, bytes(store().active()))
        assertArrayEquals(first, bytes(store().pinned(hash(first))))
        assertEquals(CacheRead.Missing, store().pinned(hash(second)))
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
        assertEquals(CacheInstall.Activated(hash(second)), install(second))
    }

    @Test fun `interrupted failure after activation remains uncertain with both artifacts readable`() {
        install()
        val cache = VerifiedArtifactCache(root, 128) {
            if (it == CacheWritePoint.POINTER_ACTIVATED) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted after activation")
            }
        }
        try {
            assertEquals(CacheInstall.ActivationUncertain(hash(second)), cache.install(hash(second), second.inputStream()))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertArrayEquals(second, bytes(store().active()))
        assertArrayEquals(first, bytes(store().pinned(hash(first))))
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
        assertEquals(CacheInstall.Activated(hash(second)), install(second))
    }

    @Test fun `exact byte limit accepts and overflow consumes only one extra byte`() {
        val cache = VerifiedArtifactCache(root, 3)
        val content = byteArrayOf(1, 2, 3)
        assertEquals(CacheInstall.Activated(hash(content)), cache.install(hash(content), content.inputStream()))
        val source = object : InputStream() {
            var consumed = 0
            override fun available() = Int.MAX_VALUE
            override fun read(): Int { consumed++; return 0 }
        }
        assertEquals(CacheInstall.Failed(CacheFailure.TOO_LARGE), cache.install(hash(first), source))
        assertEquals(4, source.consumed)
        assertArrayEquals(content, bytes(cache.active()))
    }

    @Test fun `each preactivation failure keeps prior pointer on reopen`() {
        install()
        for (point in listOf(CacheWritePoint.STAGED, CacheWritePoint.ARTIFACT_PUBLISHED, CacheWritePoint.POINTER_STAGED)) {
            val cache = VerifiedArtifactCache(root, 128) { if (it == point) throw IOException("injected disk failure") }
            assertEquals(CacheInstall.Failed(CacheFailure.IO_FAILURE), cache.install(hash(second), second.inputStream()))
            assertArrayEquals(first, bytes(store().active()))
        }
        assertEquals(CacheInstall.Activated(hash(second)), install(second))
    }

    @Test fun `failure after pointer activation reports uncertain and reopen verifies new bytes`() {
        install()
        val cache = VerifiedArtifactCache(root, 128) { if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("fsync failed") }
        assertEquals(CacheInstall.ActivationUncertain(hash(second)), cache.install(hash(second), second.inputStream()))
        assertArrayEquals(second, bytes(store().active()))
        assertArrayEquals(first, bytes(store().pinned(hash(first))))
    }

    @Test fun `abandoned stages are ignored by reads and cleaned on next install`() {
        install()
        Files.write(root.resolve("stage-artifact"), "incomplete".toByteArray())
        Files.write(root.resolve("stage-pointer"), hash(second).hex.toByteArray())
        Files.write(root.resolve("unrelated"), first)
        assertArrayEquals(first, bytes(store().active()))
        assertEquals(CacheInstall.Activated(hash(second)), install(second))
        assertFalse(Files.exists(root.resolve("stage-artifact")))
        assertFalse(Files.exists(root.resolve("stage-pointer")))
        assertArrayEquals(first, Files.readAllBytes(root.resolve("unrelated")))
    }

    @Test fun `corrupt active pointer payload and missing referenced content never report missing catalog`() {
        install()
        val artifact = root.resolve("${hash(first).hex}.bin")
        Files.write(artifact, "tampered".toByteArray())
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), store().active())
        assertEquals(CacheInstall.Failed(CacheFailure.CORRUPT), install())
        Files.delete(artifact)
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), store().active())
        Files.write(root.resolve("active"), "../secret".toByteArray())
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), store().active())
        Files.write(root.resolve("active"), ByteArray(65))
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), store().active())
    }

    @Test fun `strict hash identity prevents path traversal and aliases`() {
        for (bad in listOf("../secret", "/tmp/value", "a".repeat(63), "A".repeat(64), "a".repeat(64) + "\n")) {
            assertThrows(IllegalArgumentException::class.java) { ArtifactHash(bad) }
        }
    }

    @Test fun `symlink artifact and pointer are rejected without following target`() {
        install()
        val artifact = root.resolve("${hash(first).hex}.bin")
        val outside = root.resolve("outside")
        Files.write(outside, first)
        Files.delete(artifact)
        Files.createSymbolicLink(artifact, outside)
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), store().active())
        Files.delete(root.resolve("active"))
        Files.createSymbolicLink(root.resolve("active"), outside)
        assertEquals(CacheRead.Failed(CacheFailure.CORRUPT), store().active())
        assertArrayEquals(first, Files.readAllBytes(outside))
    }

    @Test fun `overlapping instances return busy instead of duplicate work`() {
        install()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cache = VerifiedArtifactCache(root, 128) {
                if (it == CacheWritePoint.STAGED) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val running = executor.submit<CacheInstall> { cache.install(hash(second), second.inputStream()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(CacheRead.Failed(CacheFailure.BUSY), store().active())
            assertEquals(CacheInstall.Failed(CacheFailure.BUSY), install())
            release.countDown()
            assertEquals(CacheInstall.Activated(hash(second)), running.get(5, TimeUnit.SECONDS))
            assertArrayEquals(second, bytes(store().active()))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `rejected same process and reentrant attempts preserve external process exclusion`() {
        install()
        val otherInstance = store()
        val cache = VerifiedArtifactCache(root, 128) {
            if (it == CacheWritePoint.STAGED) {
                // Same-thread reentrancy must be BUSY, not a reentrant JVM-lock acquisition.
                assertEquals(CacheRead.Failed(CacheFailure.BUSY), otherInstance.active())
                assertEquals(CacheInstall.Failed(CacheFailure.BUSY), otherInstance.install(hash(first), first.inputStream()))
                assertEquals("BUSY", externalLockAttempt())
            }
        }
        assertEquals(CacheInstall.Activated(hash(second)), cache.install(hash(second), second.inputStream()))
        assertEquals("ACQUIRED", externalLockAttempt())
    }

    private fun externalLockAttempt(): String {
        val executable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = listOf(CacheLockSubprocess::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        // JVM environment banners belong to diagnostics, not the probe's BUSY/ACQUIRED protocol.
        // A file drains stderr without risking a full pipe while waiting for the tiny probe to exit.
        val stderr = Files.createTempFile(root, "lock-probe-", ".stderr")
        val process = ProcessBuilder(executable, "-cp", classpath, CacheLockSubprocess::class.java.name, root.resolve("lock").toString())
            .redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe timed out")
            val output = process.inputStream.use { String(it.readNBytes(1024), Charsets.UTF_8) }.trim()
            val diagnostic = if (process.exitValue() != 0) {
                Files.newInputStream(stderr).use { String(it.readNBytes(4096), Charsets.UTF_8) }
            } else ""
            assertEquals(0, process.exitValue(), "Lock probe stdout: $output; stderr: $diagnostic")
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            Files.deleteIfExists(stderr)
        }
    }

    @Test fun `held OS lock prevents access even without another cache instance`() {
        FileChannel.open(root.resolve("lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertEquals(CacheRead.Failed(CacheFailure.BUSY), store().active())
                assertEquals(CacheInstall.Failed(CacheFailure.BUSY), install())
            }
        }
        assertEquals(CacheInstall.Activated(hash(first)), install())
    }

    @Test fun `cancellation before activation preserves pointer and interrupt flag`() {
        install()
        val cache = VerifiedArtifactCache(root, 128) { if (it == CacheWritePoint.POINTER_STAGED) Thread.currentThread().interrupt() }
        try {
            assertEquals(CacheInstall.Failed(CacheFailure.CANCELLED), cache.install(hash(second), second.inputStream()))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertArrayEquals(first, bytes(store().active()))
    }
}

/** A genuinely separate JVM is essential: same-JVM overlap checks alone mask POSIX lock loss. */
internal object CacheLockSubprocess {
    @JvmStatic fun main(args: Array<String>) {
        FileChannel.open(Path.of(args[0]), WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock == null) {
                println("BUSY")
            } else {
                lock.use { println("ACQUIRED") }
            }
        }
    }
}
