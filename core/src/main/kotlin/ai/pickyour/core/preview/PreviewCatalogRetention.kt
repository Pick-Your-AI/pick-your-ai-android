package ai.pickyour.core.preview

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheFailure
import ai.pickyour.core.cache.CacheInstall
import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.CacheRetentionLimits
import ai.pickyour.core.cache.VerifiedArtifactCache
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

sealed interface PreviewCatalogRetentionResult {
    data class Available(val hash: ArtifactHash) : PreviewCatalogRetentionResult
    data object InvalidSnapshot : PreviewCatalogRetentionResult
    data class Failed(val reason: CacheFailure) : PreviewCatalogRetentionResult
    data class Uncertain(val hash: ArtifactHash) : PreviewCatalogRetentionResult
}

/**
 * Retains bounded, already permitted local preview input for exact historical inspection.
 * Validation and a byte digest establish structure/integrity, never publication or source-use rights.
 * Only the bundled synthetic loader calls this adapter; it acquires no remote input or pricebook.
 */
class PreviewCatalogRetention internal constructor(
    private val privateRoot: Path,
    private val limits: CacheRetentionLimits,
    private val cacheFactory: (Path, Int, CacheRetentionLimits) -> VerifiedArtifactCache,
    private val syncDirectory: (Path) -> Unit,
) {
    constructor(privateRoot: Path) : this(
        privateRoot,
        CacheRetentionLimits(MAX_SNAPSHOTS, MAX_RETAINED_BYTES),
        { path, maximum, retention -> VerifiedArtifactCache(path, maximum, retention) },
        { path -> FileChannel.open(path, READ).use { it.force(true) } },
    )

    fun retain(input: ByteArray): PreviewCatalogRetentionResult {
        if (Thread.currentThread().isInterrupted) return failed(CacheFailure.CANCELLED)
        if (input.size > PreviewCatalogPinResolver.MAX_BYTES) return failed(CacheFailure.TOO_LARGE)
        val bytes = input.copyOf()
        val catalog = try {
            PreviewCatalogBrowser.read(bytes)
        } catch (_: Exception) {
            return failed(if (Thread.currentThread().isInterrupted) CacheFailure.CANCELLED else CacheFailure.IO_FAILURE)
        } ?: return PreviewCatalogRetentionResult.InvalidSnapshot
        if (Thread.currentThread().isInterrupted) return failed(CacheFailure.CANCELLED)
        val expected = ArtifactHash(catalog.hash)

        return try {
            // Never create a missing application root, follow a child symlink, or repair foreign bytes.
            if (!Files.isDirectory(privateRoot, NOFOLLOW_LINKS)) return failed(CacheFailure.CORRUPT)
            val root = privateRoot.toRealPath()
            val directory = root.resolve(DIRECTORY)
            if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS))
                return failed(CacheFailure.CORRUPT)
            Files.createDirectories(directory)
            syncDirectory(root) // Also confirms a child left by interrupted earlier provisioning.
            if (Thread.currentThread().isInterrupted) return failed(CacheFailure.CANCELLED)
            val cache = cacheFactory(directory, PreviewCatalogPinResolver.MAX_BYTES, limits)

            // Reopen and verify first. Reusing a known immutable pin does not move another cache head,
            // allocate a duplicate, or reinterpret this read as a new draft-save acknowledgement.
            when (val retained = cache.pinned(expected)) {
                is CacheRead.Available -> {
                    // A prior install may have renamed a forced artifact before directory fsync failed.
                    // Confirm that entry without reactivating it or replacing the current pointer.
                    syncDirectory(directory)
                    if (Thread.currentThread().isInterrupted) failed(CacheFailure.CANCELLED)
                    else PreviewCatalogRetentionResult.Available(retained.hash)
                }
                is CacheRead.Failed -> failed(retained.reason)
                CacheRead.Missing -> when (val installed = cache.install(expected, bytes.inputStream())) {
                    is CacheInstall.Activated -> PreviewCatalogRetentionResult.Available(installed.hash)
                    is CacheInstall.Failed -> failed(installed.reason)
                    is CacheInstall.ActivationUncertain -> PreviewCatalogRetentionResult.Uncertain(installed.hash)
                }
            }
        } catch (_: IOException) {
            failed(if (Thread.currentThread().isInterrupted) CacheFailure.CANCELLED else CacheFailure.IO_FAILURE)
        } catch (_: IllegalArgumentException) {
            failed(CacheFailure.CORRUPT)
        }
    }

    private fun failed(reason: CacheFailure) = PreviewCatalogRetentionResult.Failed(reason)

    companion object {
        const val DIRECTORY = "catalog-snapshots-v1"
        const val MAX_SNAPSHOTS = 16
        const val MAX_RETAINED_BYTES = MAX_SNAPSHOTS * 1_048_576L
    }
}
