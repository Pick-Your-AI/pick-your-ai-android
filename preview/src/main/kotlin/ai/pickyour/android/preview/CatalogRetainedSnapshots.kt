package ai.pickyour.android.preview

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheFailure
import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.preview.PreviewCatalogPinResolver
import ai.pickyour.core.preview.PreviewCatalogRetention
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Exact historical reads, plus a separate bounded ingress for the bundled synthetic loader. */
internal object CatalogRetainedSnapshots {
    const val DIRECTORY = PreviewCatalogRetention.DIRECTORY

    /** Only the validated bundled loader retains bytes; historical inspection calls read alone. */
    fun retain(privateRoot: Path, bytes: ByteArray) = PreviewCatalogRetention(privateRoot).retain(bytes)

    fun read(privateRoot: Path, hash: ArtifactHash): CacheRead = try {
        val directory = privateRoot.resolve(DIRECTORY)
        when {
            !Files.exists(directory, NOFOLLOW_LINKS) -> CacheRead.Missing
            !Files.isDirectory(directory, NOFOLLOW_LINKS) -> CacheRead.Failed(CacheFailure.CORRUPT)
            else -> VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES).pinned(hash)
        }
    } catch (_: Exception) { CacheRead.Failed(CacheFailure.IO_FAILURE) }
}
