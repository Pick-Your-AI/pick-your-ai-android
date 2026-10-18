package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

sealed interface PreviewActiveRead {
    data object None : PreviewActiveRead
    data class Selected(val id: PreviewDraftId) : PreviewActiveRead
    data class Failed(val reason: CacheFailure) : PreviewActiveRead
}
sealed interface PreviewActiveWrite {
    data object Saved : PreviewActiveWrite
    data class Failed(val reason: CacheFailure) : PreviewActiveWrite
    data object Uncertain : PreviewActiveWrite
}

/** Private local last-opened ID only, never a revision token or another draft storage engine. */
class PreviewActiveDraftStore internal constructor(root: Path, cacheFactory: (Path) -> VerifiedArtifactCache,
    syncRoot: (Path) -> Unit) {
    constructor(root: Path) : this(root, { VerifiedArtifactCache(it, 128) },
        { path -> FileChannel.open(path, READ).use { it.force(true) } })
    private val root: Path
    private val cache: VerifiedArtifactCache
    init {
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        this.root = root.toRealPath()
        val directory = this.root.resolve(".active-draft")
        require(!Files.isSymbolicLink(directory))
        Files.createDirectories(directory)
        syncRoot(this.root) // Repeat even after an interrupted prior initialization.
        cache = cacheFactory(directory)
    }
    fun read(): PreviewActiveRead = when (val value = cache.active()) {
        CacheRead.Missing -> PreviewActiveRead.None
        is CacheRead.Failed -> PreviewActiveRead.Failed(value.reason)
        is CacheRead.Available -> {
            val text = String(value.bytes, Charsets.US_ASCII)
            try {
                require(text.length >= 5 && text.startsWith("v1\n") && text.endsWith("\n"))
                PreviewActiveRead.Selected(PreviewDraftId(text.substring(3, text.length - 1)))
            } catch (_: IllegalArgumentException) { PreviewActiveRead.Failed(CacheFailure.CORRUPT) }
        }
    }
    fun select(id: PreviewDraftId): PreviewActiveWrite {
        val library = try { PreviewDraftLibrary(root) }
            catch (_: IllegalArgumentException) { return PreviewActiveWrite.Failed(CacheFailure.CORRUPT) }
            catch (_: IOException) { return PreviewActiveWrite.Failed(CacheFailure.IO_FAILURE) }
        return library.select(id) {
            val bytes = "v1\n${id.value}\n".toByteArray(Charsets.US_ASCII)
            val hash = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            // These hashes identify only superseded last-opened IDs, never retained draft inputs.
            when (val value = cache.installCurrent(hash, bytes.inputStream())) {
                is CacheInstall.Activated -> PreviewActiveWrite.Saved
                is CacheInstall.Failed -> PreviewActiveWrite.Failed(value.reason)
                is CacheInstall.ActivationUncertain -> PreviewActiveWrite.Uncertain
            }
        }
    }
}
