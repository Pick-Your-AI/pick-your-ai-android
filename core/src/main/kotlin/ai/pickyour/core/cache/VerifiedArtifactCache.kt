package ai.pickyour.core.cache

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Identifies bytes only, not publisher authenticity, rights, freshness or schema validity. */
data class ArtifactHash(val hex: String) {
    init { require(hex.matches(Regex("[0-9a-f]{64}"))) { "Expected a lowercase SHA-256 digest" } }
}

enum class CacheFailure { BUSY, IO_FAILURE, TOO_LARGE, HASH_MISMATCH, CANCELLED, CORRUPT }

sealed interface CacheRead {
    data object Missing : CacheRead
    /** Independent verified bytes; changing this array cannot alter stored artifacts. */
    data class Available(val hash: ArtifactHash, val bytes: ByteArray) : CacheRead
    data class Failed(val reason: CacheFailure) : CacheRead
}

sealed interface CacheInstall {
    data class Activated(val hash: ArtifactHash) : CacheInstall
    /** No pointer activation was attempted; a prior active pointer remains unchanged. */
    data class Failed(val reason: CacheFailure) : CacheInstall
    /** Activation may have happened; reopen and verify before reporting success or retrying. */
    data class ActivationUncertain(val hash: ArtifactHash) : CacheInstall
}

internal enum class CacheWritePoint { STAGED, ARTIFACT_PUBLISHED, POINTER_STAGED, POINTER_ACTIVATED }

/**
 * Single-artifact (or caller-validated complete bundle) cache in a dedicated app-private directory.
 * The caller supplies a trusted expected hash and is responsible for schema/rights/freshness checks.
 * No networking, archive unpacking, scheduling, automatic eviction or multi-file catalog assembly.
 * Pinned hashes remain readable after activation of newer bytes. Total retained storage is NOT bounded;
 * the owner must impose a separately reviewed retention policy before continuous production refresh.
 *
 * All cooperating instances first claim a non-reentrant JVM guard for the canonical directory, then
 * take a nonblocking exclusive OS lock, including reads. BUSY
 * requires caller retry; no unbounded waiting. The directory must not be modified by other writers.
 * Files and directory metadata are forced before acknowledgement. Atomic rename and directory fsync
 * support are required; there is no unsafe fallback. Actual Android filesystem/power-loss behavior is
 * unverified. Input streams belong to callers and are never closed. Cancellation checks cannot unblock
 * a blocking provider read; the transport must arrange cancellation/timeouts.
 */
class VerifiedArtifactCache internal constructor(
    directory: Path,
    private val maxBytes: Int,
    private val checkpoint: (CacheWritePoint) -> Unit,
) {
    constructor(directory: Path, maxBytes: Int = 16 * 1024 * 1024) : this(directory, maxBytes, {})

    private val root: Path
    init {
        require(maxBytes > 0)
        require(!Files.isSymbolicLink(directory)) { "Cache root must not be a symbolic link" }
        Files.createDirectories(directory)
        root = directory.toRealPath()
    }

    fun active(): CacheRead = readSafely {
        val pointer = root.resolve("active")
        if (!Files.exists(pointer, NOFOLLOW_LINKS)) return@readSafely CacheRead.Missing
        val text = readFile(pointer, 64)
        if (text.size != 64) fail(CacheFailure.CORRUPT)
        val hash = try { ArtifactHash(String(text, Charsets.US_ASCII)) } catch (_: IllegalArgumentException) {
            fail(CacheFailure.CORRUPT)
        }
        readVerified(hash, missingIsCorrupt = true)
    }

    fun pinned(hash: ArtifactHash): CacheRead = readSafely { readVerified(hash, missingIsCorrupt = false) }

    fun install(expected: ArtifactHash, input: InputStream): CacheInstall {
        var activationAttempted = false
        return try {
            locked {
                // Fixed private staging names make crash cleanup constant work, with no directory scan.
                cleanupStaging()
                try {
                    cancelled()
                    val stage = root.resolve("stage-artifact")
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileChannel.open(stage, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                        transfer(input, maxBytes) { bytes, count ->
                            digest.update(bytes, 0, count)
                            writeFully(channel, ByteBuffer.wrap(bytes, 0, count))
                        }
                        channel.force(true)
                    }
                    if (hex(digest.digest()) != expected.hex) fail(CacheFailure.HASH_MISMATCH)
                    checkpoint(CacheWritePoint.STAGED)
                    cancelled()
                    val destination = artifact(expected)
                    if (Files.exists(destination, NOFOLLOW_LINKS)) {
                        // Never rewrite an immutable pin, even if its existing contents are damaged.
                        readVerified(expected, missingIsCorrupt = true)
                        Files.delete(stage)
                    } else {
                        Files.move(stage, destination, ATOMIC_MOVE)
                    }
                    syncDirectory()
                    checkpoint(CacheWritePoint.ARTIFACT_PUBLISHED)
                    val pointerStage = root.resolve("stage-pointer")
                    FileChannel.open(pointerStage, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                        writeFully(channel, ByteBuffer.wrap(expected.hex.toByteArray(Charsets.US_ASCII)))
                        channel.force(true)
                    }
                    checkpoint(CacheWritePoint.POINTER_STAGED)
                    cancelled()
                    // Also covers an ambiguous failure returned by the filesystem's rename operation.
                    activationAttempted = true
                    Files.move(pointerStage, root.resolve("active"), ATOMIC_MOVE, REPLACE_EXISTING)
                    checkpoint(CacheWritePoint.POINTER_ACTIVATED)
                    syncDirectory()
                    CacheInstall.Activated(expected)
                } finally {
                    // Cleanup failures never turn a committed activation into a false pre-commit failure.
                    try { cleanupStaging() } catch (_: IOException) { /* Retry on the next install. */ }
                }
            }
        } catch (error: Failure) {
            if (activationAttempted) CacheInstall.ActivationUncertain(expected) else CacheInstall.Failed(error.reason)
        } catch (_: IOException) {
            if (activationAttempted) CacheInstall.ActivationUncertain(expected) else CacheInstall.Failed(CacheFailure.IO_FAILURE)
        }
    }

    private fun readSafely(block: () -> CacheRead): CacheRead = try {
        locked(block)
    } catch (error: Failure) {
        CacheRead.Failed(error.reason)
    } catch (_: IOException) {
        CacheRead.Failed(CacheFailure.IO_FAILURE)
    }

    private fun readVerified(hash: ArtifactHash, missingIsCorrupt: Boolean): CacheRead {
        val path = artifact(hash)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            if (missingIsCorrupt) fail(CacheFailure.CORRUPT)
            return CacheRead.Missing
        }
        val bytes = readFile(path, maxBytes)
        if (hex(MessageDigest.getInstance("SHA-256").digest(bytes)) != hash.hex) fail(CacheFailure.CORRUPT)
        return CacheRead.Available(hash, bytes)
    }

    private fun readFile(path: Path, maximum: Int): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail(CacheFailure.CORRUPT)
        if (Files.size(path) > maximum) fail(CacheFailure.CORRUPT)
        val output = ByteArrayOutputStream()
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            transfer(input, maximum) { bytes, count -> output.write(bytes, 0, count) }
        }
        return output.toByteArray()
    }

    private fun <T> locked(block: () -> T): T {
        cancelled()
        // On POSIX, closing ANY channel for a locked file can release this process's OS lock.
        // Claim before even opening a second channel. A set also rejects same-thread reentrancy.
        if (!synchronized(activeRoots) { activeRoots.add(root) }) fail(CacheFailure.BUSY)
        try {
            val path = root.resolve("lock")
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) fail(CacheFailure.CORRUPT)
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) fail(CacheFailure.BUSY)
                lock.use { return block() }
            }
        } finally {
            // Removal happens after the channel closes; no waiting caller retains a stale guard.
            // Only in-flight roots are retained, avoiding an ever-growing per-directory registry.
            synchronized(activeRoots) { activeRoots.remove(root) }
        }
    }

    private companion object {
        val activeRoots = HashSet<Path>()
    }

    private fun cleanupStaging() {
        Files.deleteIfExists(root.resolve("stage-artifact"))
        Files.deleteIfExists(root.resolve("stage-pointer"))
    }

    private fun syncDirectory() = FileChannel.open(root, READ).use { it.force(true) }
    private fun artifact(hash: ArtifactHash) = root.resolve("${hash.hex}.bin")

    private fun transfer(input: InputStream, maximum: Int, consume: (ByteArray, Int) -> Unit) {
        var count = 0L
        val buffer = ByteArray(8192)
        while (true) {
            cancelled()
            val allowed = minOf(buffer.size.toLong(), maximum.toLong() - count + 1).toInt()
            val read = input.read(buffer, 0, allowed)
            if (read == -1) return
            if (read <= 0 || read > allowed) fail(CacheFailure.IO_FAILURE)
            count += read
            if (count > maximum) fail(CacheFailure.TOO_LARGE)
            consume(buffer, read)
        }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteBuffer) {
        while (bytes.hasRemaining()) {
            cancelled()
            if (channel.write(bytes) <= 0) fail(CacheFailure.IO_FAILURE)
        }
    }

    private fun cancelled() { if (Thread.currentThread().isInterrupted) fail(CacheFailure.CANCELLED) }
    private class Failure(val reason: CacheFailure) : IOException()
    private fun fail(reason: CacheFailure): Nothing = throw Failure(reason)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
