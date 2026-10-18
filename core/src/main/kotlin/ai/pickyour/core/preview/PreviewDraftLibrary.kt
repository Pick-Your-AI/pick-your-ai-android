package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.Collections
import java.util.UUID

/** A directory identity, not a title, account identity or shared BuildSnapshot ID. */
data class PreviewDraftId(val value: String) {
    init { require(value == "guest-draft" || Regex("draft-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)) }
    override fun toString() = "PreviewDraftId"
}
class PreviewLibraryEntry(val id: PreviewDraftId, val state: PreviewDraftLoad)
sealed interface PreviewLibraryRead {
    class Available(entries: List<PreviewLibraryEntry>) : PreviewLibraryRead {
        val entries: List<PreviewLibraryEntry> = Collections.unmodifiableList(ArrayList(entries))
    }
    data class Failed(val reason: DraftStorageFailure) : PreviewLibraryRead
}
sealed interface PreviewLibraryCreate {
    class Saved(val id: PreviewDraftId, val record: PreviewStoredDraft) : PreviewLibraryCreate
    /** The directory may exist; refresh/reopen by this ID before deciding whether to retry. */
    class Unconfirmed(val id: PreviewDraftId, val reason: DraftStorageFailure?) : PreviewLibraryCreate
    data class Failed(val reason: DraftStorageFailure) : PreviewLibraryCreate
}

/**
 * Bounded directory catalog over canonical LocalDraftStore instances, never another revision engine.
 * Caller durably provisions root. The legacy guest-draft directory keeps its identity and bytes.
 * Synchronous: all methods belong on a worker. Missing/corrupt entries remain visible, not discarded.
 */
class PreviewDraftLibrary internal constructor(root: Path, private val checkpoint: (LibraryCheckpoint) -> Unit) {
    constructor(root: Path) : this(root, {})
    private val root: Path
    init {
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        this.root = root.toRealPath()
    }

    fun list(): PreviewLibraryRead = try {
        PreviewLibraryRead.Available(ids().map { id ->
            val state = try { open(id).load() }
                catch (_: IllegalArgumentException) { PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT) }
                catch (_: Exception) { PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE) }
            PreviewLibraryEntry(id, state)
        })
    } catch (_: LimitExceeded) { PreviewLibraryRead.Failed(DraftStorageFailure.LIMIT_EXCEEDED) }
    catch (_: Exception) { PreviewLibraryRead.Failed(DraftStorageFailure.IO_FAILURE) }

    fun open(id: PreviewDraftId): PreviewDraftPersistence {
        val path = root.resolve(id.value)
        require(Files.isDirectory(path, NOFOLLOW_LINKS))
        // Also retries parent synchronization for a directory left by interrupted creation.
        FileChannel.open(root, READ).use { it.force(true) }
        return PreviewDraftPersistence(LocalDraftStore(path))
    }

    fun create(): PreviewLibraryCreate = createFrom(null)

    /** Copy the supplied saved snapshot, not pending form edits; source history is not duplicated. */
    fun duplicate(source: PreviewStoredDraft): PreviewLibraryCreate {
        require(source.draft.version.store.parent == root)
        PreviewDraftId(source.draft.version.store.fileName.toString())
        return createFrom(source)
    }

    private fun createFrom(source: PreviewStoredDraft?): PreviewLibraryCreate {
        // On POSIX, closing a contending channel can release another operation's
        // process-wide lock. Claim the canonical root before opening that channel.
        // The set is deliberately non-reentrant, matching the canonical stores.
        if (!synchronized(activeRoots) { activeRoots.add(root) })
            return PreviewLibraryCreate.Failed(DraftStorageFailure.BUSY)
        var created: PreviewDraftId? = null
        return try {
            val lockPath = root.resolve(".preview-library.lock")
            require(!Files.isSymbolicLink(lockPath))
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = channel.tryLock() ?: return PreviewLibraryCreate.Failed(DraftStorageFailure.BUSY)
                lock.use {
                    if (ids().size >= MAX_DRAFTS) return PreviewLibraryCreate.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
                    val id = PreviewDraftId("draft-${UUID.randomUUID()}")
                    Files.createDirectory(root.resolve(id.value))
                    created = id
                    checkpoint(LibraryCheckpoint.AFTER_DIRECTORY_CREATE)
                    FileChannel.open(root, READ).use { it.force(true) }
                    val persistence = open(id)
                    val result = if (source == null) persistence.save(null, PreviewDraftFields())
                        else persistence.copySavedSnapshot(source)
                    checkpoint(LibraryCheckpoint.AFTER_SAVE)
                    when (result) {
                        is PreviewDraftSave.Saved -> PreviewLibraryCreate.Saved(id, result.record)
                        is PreviewDraftSave.Failed -> PreviewLibraryCreate.Unconfirmed(id, result.reason)
                        else -> PreviewLibraryCreate.Unconfirmed(id, null)
                    }
                }
            }
        } catch (_: OverlappingFileLockException) { created?.let { PreviewLibraryCreate.Unconfirmed(it, DraftStorageFailure.BUSY) } ?: PreviewLibraryCreate.Failed(DraftStorageFailure.BUSY) }
        catch (_: LimitExceeded) { PreviewLibraryCreate.Failed(DraftStorageFailure.LIMIT_EXCEEDED) }
        catch (_: Exception) {
            created?.let { PreviewLibraryCreate.Unconfirmed(it, DraftStorageFailure.IO_FAILURE) }
                ?: PreviewLibraryCreate.Failed(DraftStorageFailure.IO_FAILURE)
        } finally {
            // Release only after the OS lock and channel close, including failure paths.
            synchronized(activeRoots) { activeRoots.remove(root) }
        }
    }

    private fun ids(): List<PreviewDraftId> {
        val result = mutableListOf<PreviewDraftId>()
        Files.newDirectoryStream(root).use { stream ->
            var scanned = 0
            for (path in stream) {
                if (++scanned > MAX_DRAFTS * 4) throw LimitExceeded()
                val id = try { PreviewDraftId(path.fileName.toString()) } catch (_: IllegalArgumentException) { continue }
                result.add(id)
                if (result.size > MAX_DRAFTS) throw LimitExceeded()
            }
        }
        return result.sortedBy { it.value }
    }
    private class LimitExceeded : Exception()
    companion object {
        const val MAX_DRAFTS = 64
        private val activeRoots = HashSet<Path>()
    }
}

internal enum class LibraryCheckpoint { AFTER_DIRECTORY_CREATE, AFTER_SAVE }

/** Transactional editor switch: a failed target open cannot steal the current form's save authority. */
class PreviewDraftSession(val id: PreviewDraftId, val record: PreviewStoredDraft?,
    val fields: PreviewDraftFields, val ready: Boolean) {
    fun switchTo(target: PreviewDraftId, result: PreviewDraftLoad?): PreviewDraftSession = when (result) {
        is PreviewDraftLoad.Available -> PreviewDraftSession(target, result.record, result.record.fields, true)
        PreviewDraftLoad.Missing -> PreviewDraftSession(target, null, PreviewDraftFields(), true)
        is PreviewDraftLoad.Failed, null -> this
    }
}
