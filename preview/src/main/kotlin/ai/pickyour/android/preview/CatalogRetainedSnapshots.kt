package ai.pickyour.android.preview

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheFailure
import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.preview.PreviewCatalogPinResolver
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Exact reads of an already provisioned private cache. This adapter never creates or populates it. */
internal object CatalogRetainedSnapshots {
    const val DIRECTORY = "catalog-snapshots-v1"

    fun read(privateRoot: Path, hash: ArtifactHash): CacheRead = try {
        val directory = privateRoot.resolve(DIRECTORY)
        when {
            !Files.exists(directory, NOFOLLOW_LINKS) -> CacheRead.Missing
            !Files.isDirectory(directory, NOFOLLOW_LINKS) -> CacheRead.Failed(CacheFailure.CORRUPT)
            else -> VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES).pinned(hash)
        }
    } catch (_: Exception) { CacheRead.Failed(CacheFailure.IO_FAILURE) }
}
