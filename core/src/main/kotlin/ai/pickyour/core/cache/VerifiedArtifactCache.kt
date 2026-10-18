package ai.pickyour.core.cache

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.DirectoryIteratorException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Identifies bytes only, not publisher authenticity, rights, freshness or schema validity. */
data class ArtifactHash(val hex: String) {
    init { require(hex.matches(Regex("[0-9a-f]{64}"))) { "Expected a lowercase SHA-256 digest" } }
}

enum class CacheFailure { BUSY, IO_FAILURE, TOO_LARGE, RETENTION_LIMIT, HASH_MISMATCH, CANCELLED, CORRUPT }

/** Explicit managed-payload budgets, not eviction permission or a filesystem free-space guarantee. */
data class CacheRetentionLimits(val maxArtifacts: Int, val maxRetainedBytes: Long) {
    init { require(maxArtifacts > 0 && maxRetainedBytes > 0) }
}

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

internal enum class CacheWritePoint {
    STAGED, ARTIFACT_PUBLISHED, POINTER_STAGED, POINTER_ACTIVATED,
    CURRENT_POINTER_VERIFIED, CURRENT_PAYLOAD_REMOVED,
}

/**
 * Single-artifact (or caller-validated complete bundle) cache in an existing app-private directory.
 * Caller must durably provision the root and all ancestor directory entries before construction.
 * The caller supplies a trusted expected hash and is responsible for schema/rights/freshness checks.
 * No networking, archive unpacking, scheduling or multi-file catalog assembly. Ordinary install never
 * evicts: pinned hashes remain readable after activation of newer bytes. Optional explicit retention limits
 * reject new artifacts without eviction. Without those limits, retained storage remains unbounded.
 * The owner must choose and integrate a reviewed retention policy before production refresh.
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
    private val retention: CacheRetentionLimits?,
    private val checkpoint: (CacheWritePoint) -> Unit,
) {
    constructor(directory: Path, maxBytes: Int = 16 * 1024 * 1024) : this(directory, maxBytes, null, {})
    constructor(directory: Path, maxBytes: Int, retention: CacheRetentionLimits) : this(directory, maxBytes, retention, {})
    internal constructor(directory: Path, maxBytes: Int, checkpoint: (CacheWritePoint) -> Unit) :
        this(directory, maxBytes, null, checkpoint)

    private val root: Path
    init {
        require(maxBytes > 0)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "An existing durable cache directory is required" }
        root = directory.toRealPath()
    }

    fun active(): CacheRead = readSafely {
        val hash = activeHash() ?: return@readSafely CacheRead.Missing
        readVerified(hash, missingIsCorrupt = true)
    }

    fun pinned(hash: ArtifactHash): CacheRead = readSafely { readVerified(hash, missingIsCorrupt = false) }

    fun install(expected: ArtifactHash, input: InputStream): CacheInstall = install(expected, input, currentOnly = false)

    /**
     * Explicit private current-value storage: inactive hashes must have no retained/pending consumers.
     * Only the last-opened selection store opts in. Ordinary install/pinned never reclaim old pins.
     * One bounded scan before publication protects the verified current and incoming hashes. After a
     * distinct switch, the former current remains through all post-activation uncertainty; a later
     * installation can reclaim it. Reads never reclaim. Legacy cleanup makes incremental progress.
     */
    internal fun installCurrent(expected: ArtifactHash, input: InputStream): CacheInstall =
        install(expected, input, currentOnly = true)

    private fun install(expected: ArtifactHash, input: InputStream, currentOnly: Boolean): CacheInstall {
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
                    val retained = Files.exists(destination, NOFOLLOW_LINKS)
                    if (retained) {
                        // Never rewrite an immutable pin, even if its existing contents are damaged.
                        readVerified(expected, missingIsCorrupt = true)
                        Files.delete(stage)
                    }
                    if (currentOnly) {
                        // An unreadable pointer must not grant authority to discard its payload.
                        // Explicit selection can still repair it by publishing a new verified value.
                        val previous = try {
                            CurrentPointer(activeHash()?.also { readVerified(it, missingIsCorrupt = true) })
                        } catch (failure: Failure) {
                            if (failure.reason == CacheFailure.CANCELLED) throw failure
                            null
                        } catch (_: IOException) { null }
                        if (previous != null) {
                            checkpoint(CacheWritePoint.CURRENT_POINTER_VERIFIED)
                            // A prior uncertain activation may be visible but not durable yet.
                            // Confirm its pointer before discarding any possible predecessor.
                            syncDirectory()
                            val progress = reclaimCurrent(setOfNotNull(previous.hash, expected))
                            if (progress.removed > 0) syncDirectory()
                            // Do not grow a damaged/foreign-filled directory when bounded cleanup
                            // cannot make progress. Ordinary legacy payloads are reclaimed in batches.
                            if (!retained && !progress.complete && progress.removed == 0)
                                fail(CacheFailure.RETENTION_LIMIT)
                        }
                    }
                    if (!retained) {
                        enforceRetention(Files.size(stage))
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

    private fun activeHash(): ArtifactHash? {
        val pointer = root.resolve("active")
        if (!Files.exists(pointer, NOFOLLOW_LINKS)) return null
        val text = readFile(pointer, 64)
        if (text.size != 64) fail(CacheFailure.CORRUPT)
        return try { ArtifactHash(String(text, Charsets.US_ASCII)) } catch (_: IllegalArgumentException) {
            fail(CacheFailure.CORRUPT)
        }
    }

    private data class CurrentPointer(val hash: ArtifactHash?)
    private data class CleanupProgress(val complete: Boolean, val removed: Int)

    /** The existing exclusive cache lock protects pointer authority through scan, removal and sync. */
    private fun reclaimCurrent(keep: Set<ArtifactHash>): CleanupProgress {
        val names = keep.map { "${it.hex}.bin" }.toSet()
        try {
            Files.newDirectoryStream(root).use { entries ->
                val iterator = entries.iterator()
                val obsolete = ArrayList<Path>()
                var scanned = 0
                while (scanned < CURRENT_CLEANUP_ENTRIES && iterator.hasNext()) {
                    cancelled()
                    val entry = iterator.next()
                    scanned++ // Foreign and wrong-type entries consume the same bounded work budget.
                    val name = entry.fileName.toString()
                    if (name in names || !artifactName.matches(name)) continue
                    if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) fail(CacheFailure.CORRUPT)
                    obsolete.add(entry)
                }
                val complete = !iterator.hasNext()
                for (entry in obsolete) {
                    cancelled()
                    Files.delete(entry)
                    checkpoint(CacheWritePoint.CURRENT_PAYLOAD_REMOVED)
                }
                cancelled()
                return CleanupProgress(complete, obsolete.size)
            }
        } catch (_: DirectoryIteratorException) { fail(CacheFailure.IO_FAILURE) }
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
        val artifactName = Regex("[0-9a-f]{64}\\.bin")
        const val CURRENT_CLEANUP_ENTRIES = 256
    }

    /** Under the same exclusive lock as publication; failed-publication payloads also consume quota. */
    private fun enforceRetention(incomingBytes: Long) {
        val limits = retention ?: return
        if (incomingBytes > limits.maxRetainedBytes) fail(CacheFailure.RETENTION_LIMIT)
        var artifacts = 1 // Reserve the new identity before counting retained artifacts.
        var retainedBytes = incomingBytes
        try {
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    cancelled()
                    if (!artifactName.matches(entry.fileName.toString())) continue
                    val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (!attributes.isRegularFile) fail(CacheFailure.CORRUPT)
                    // Subtract before adding, so even Long.MAX_VALUE budgets cannot wrap.
                    if (artifacts >= limits.maxArtifacts || attributes.size() > limits.maxRetainedBytes - retainedBytes)
                        fail(CacheFailure.RETENTION_LIMIT)
                    artifacts++
                    retainedBytes += attributes.size()
                }
            }
        } catch (_: DirectoryIteratorException) {
            fail(CacheFailure.IO_FAILURE)
        }
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
