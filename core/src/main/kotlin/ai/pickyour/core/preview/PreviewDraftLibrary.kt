package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.cache.CacheFailure
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID

/** A directory identity, not a title, account identity or shared BuildSnapshot ID. */
data class PreviewDraftId(val value: String) {
    init { require(value == "guest-draft" || Regex("draft-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)) }
    override fun toString() = "PreviewDraftId"
}
class PreviewLibraryEntry(val id: PreviewDraftId, val state: PreviewDraftLoad, val deletionPending: Boolean = false)
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
sealed interface PreviewLibraryDelete {
    data object Deleted : PreviewLibraryDelete
    data object Conflict : PreviewLibraryDelete
    data class Failed(val reason: DraftStorageFailure) : PreviewLibraryDelete
    /** Deletion may have started. Refresh the library and retry cleanup by this same local ID. */
    data class Unconfirmed(val reason: DraftStorageFailure) : PreviewLibraryDelete
}

/**
 * Bounded directory catalog over canonical LocalDraftStore instances, never another revision engine.
 * Caller durably provisions root. The legacy guest-draft directory keeps its identity and bytes.
 * Synchronous: all methods belong on a worker. Missing/corrupt entries remain visible, not discarded.
 */
class PreviewDraftLibrary internal constructor(root: Path, private val checkpoint: (LibraryCheckpoint) -> Unit,
    private val deleteCheckpoint: (LibraryDeleteCheckpoint) -> Unit) {
    constructor(root: Path) : this(root, {}, {})
    internal constructor(root: Path, checkpoint: (LibraryCheckpoint) -> Unit) : this(root, checkpoint, {})
    private val root: Path
    init {
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        this.root = root.toRealPath()
    }

    fun list(): PreviewLibraryRead = locked({ PreviewLibraryRead.Failed(it) }) {
        PreviewLibraryRead.Available(ids().map { id ->
            val deleting = hasTombstone(id)
            val state = if (deleting) PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT) else try { unbound(id).load() }
                catch (_: IllegalArgumentException) { PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT) }
                catch (_: Exception) { PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE) }
            PreviewLibraryEntry(id, state, deleting)
        })
    }

    fun open(id: PreviewDraftId): PreviewDraftPersistence = locked({ throw IllegalArgumentException("Draft unavailable: $it") }) {
        require(!hasTombstone(id))
        bound(id)
    }

    /** The only legacy provisioning path. A deleted legacy identity is never automatically reused. */
    fun openLegacy(): PreviewDraftPersistence = locked({ throw IllegalArgumentException("Draft unavailable: $it") }) {
        val id = PreviewDraftId("guest-draft")
        require(!hasTombstone(id))
        val path = root.resolve(id.value)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            if (ids().size >= MAX_DRAFTS) throw LimitExceeded()
            require(PreviewActiveDraftStore(root).read() == PreviewActiveRead.None)
            Files.createDirectory(path)
        }
        bound(id)
    }

    /** Pointer writes participate in the same liveness boundary as draft deletion and saves. */
    internal fun select(id: PreviewDraftId, write: () -> PreviewActiveWrite): PreviewActiveWrite {
        var started = false
        return locked({ reason -> if (started) PreviewActiveWrite.Uncertain else PreviewActiveWrite.Failed(when (reason) {
            DraftStorageFailure.BUSY -> CacheFailure.BUSY
            DraftStorageFailure.CANCELLED -> CacheFailure.CANCELLED
            DraftStorageFailure.CORRUPT -> CacheFailure.CORRUPT
            else -> CacheFailure.IO_FAILURE
        }) }) {
            if (hasTombstone(id) || !Files.isDirectory(root.resolve(id.value), NOFOLLOW_LINKS))
                return@locked PreviewActiveWrite.Failed(CacheFailure.CORRUPT)
            started = true
            write()
        }
    }

    private fun bound(id: PreviewDraftId): PreviewDraftPersistence = persistence(id, object : PreviewDraftAccess {
        override fun <T> run(failed: (DraftStorageFailure) -> T, action: () -> T): T = locked(failed) {
            if (hasTombstone(id) || !Files.isDirectory(root.resolve(id.value), NOFOLLOW_LINKS))
                failed(DraftStorageFailure.CORRUPT)
            else action()
        }
    })

    private fun unbound(id: PreviewDraftId): PreviewDraftPersistence = persistence(id, null)

    private fun persistence(id: PreviewDraftId, access: PreviewDraftAccess?): PreviewDraftPersistence {
        val path = root.resolve(id.value)
        require(Files.isDirectory(path, NOFOLLOW_LINKS))
        // Also retries parent synchronization for a directory left by interrupted creation.
        sync(root)
        return PreviewDraftPersistence(LocalDraftStore(path), access)
    }

    fun create(): PreviewLibraryCreate = createFrom(null)

    /** Copy the supplied saved snapshot, not pending form edits; source history is not duplicated. */
    fun duplicate(source: PreviewStoredDraft): PreviewLibraryCreate {
        require(source.draft.version.store.parent == root)
        PreviewDraftId(source.draft.version.store.fileName.toString())
        return createFrom(source)
    }

    private fun createFrom(source: PreviewStoredDraft?): PreviewLibraryCreate {
        var created: PreviewDraftId? = null
        return locked({ reason -> created?.let { PreviewLibraryCreate.Unconfirmed(it, reason) }
            ?: PreviewLibraryCreate.Failed(reason) }) {
            if (source != null) {
                val id = PreviewDraftId(source.draft.version.store.fileName.toString())
                if (hasTombstone(id) || !Files.isDirectory(root.resolve(id.value), NOFOLLOW_LINKS))
                    return@locked PreviewLibraryCreate.Failed(DraftStorageFailure.CORRUPT)
            }
            if (ids().size >= MAX_DRAFTS) return@locked PreviewLibraryCreate.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
            val id = PreviewDraftId("draft-${UUID.randomUUID()}")
            Files.createDirectory(root.resolve(id.value))
            created = id
            checkpoint(LibraryCheckpoint.AFTER_DIRECTORY_CREATE)
            sync(root)
            val persistence = unbound(id) // The library lock is already held, deliberately non-reentrant.
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

    /**
     * Delete only the selected identity, including its retained revision artifacts. A saved token
     * must still match; null permits only a still-missing or unreadable record, never a valid save.
     * Tombstoned records can only resume cleanup. Listing never grants an editor a new save token.
     */
    fun delete(id: PreviewDraftId, expected: PreviewStoredDraft?): PreviewLibraryDelete {
        var started = false
        return locked({ if (started) PreviewLibraryDelete.Unconfirmed(it) else PreviewLibraryDelete.Failed(it) }) {
            if (expected != null && expected.draft.version.store != root.resolve(id.value))
                return@locked PreviewLibraryDelete.Conflict
            val pending = pendingPath(id)
            if (!hasTombstone(id)) {
                val current = if (!Files.exists(root.resolve(id.value), NOFOLLOW_LINKS)) PreviewDraftLoad.Missing
                    else try { unbound(id).load() }
                    catch (_: IllegalArgumentException) { PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT) }
                when (current) {
                    is PreviewDraftLoad.Available -> if (expected == null ||
                        expected.baselineIdentity != current.record.baselineIdentity) return@locked PreviewLibraryDelete.Conflict
                    PreviewDraftLoad.Missing -> if (expected != null) return@locked PreviewLibraryDelete.Conflict
                    is PreviewDraftLoad.Failed -> {
                        if (current.reason in setOf(DraftStorageFailure.BUSY, DraftStorageFailure.IO_FAILURE, DraftStorageFailure.CANCELLED))
                            return@locked PreviewLibraryDelete.Failed(current.reason)
                        if (expected != null) return@locked PreviewLibraryDelete.Conflict
                    }
                }
            }
            started = true
            val directory = deletionDirectory()
            Files.createDirectories(directory)
            sync(root)
            // The legacy fallback is the sole reusable name. Its small marker survives cleanup.
            if (id.value == "guest-draft") durableMarker(root.resolve(LEGACY_DELETED))
            durableMarker(pending)
            deleteCheckpoint(LibraryDeleteCheckpoint.TOMBSTONE_DURABLE)
            removeDraft(root.resolve(id.value))
            deleteCheckpoint(LibraryDeleteCheckpoint.DIRECTORY_REMOVED)
            sync(root)
            // UUID identities are never reused. Once removal is durable, absence itself blocks
            // old adapters; pending markers need not accumulate after successful cleanup.
            Files.delete(pending)
            deleteCheckpoint(LibraryDeleteCheckpoint.TOMBSTONE_REMOVED)
            sync(directory)
            PreviewLibraryDelete.Deleted
        }
    }

    private fun removeDraft(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        var visited = 0
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            private fun bound() { if (++visited > MAX_DELETE_ENTRIES) throw LimitExceeded() }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                bound()
                if (path.relativize(dir).nameCount > MAX_DELETE_DEPTH) throw LimitExceeded()
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                bound()
                Files.delete(file) // walkFileTree does not follow links; only the link itself is removed.
                deleteCheckpoint(LibraryDeleteCheckpoint.CONTENT_REMOVED)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                sync(dir)
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun durableMarker(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Invalid deletion marker")
        } else {
            FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap("v1\n".toByteArray(Charsets.US_ASCII))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
        // Retry file and parent sync even after an interrupted earlier marker creation.
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        sync(path.parent)
    }

    private fun hasTombstone(id: PreviewDraftId): Boolean =
        Files.exists(pendingPath(id), NOFOLLOW_LINKS) ||
            id.value == "guest-draft" && Files.exists(root.resolve(LEGACY_DELETED), NOFOLLOW_LINKS)

    private fun pendingPath(id: PreviewDraftId) = deletionDirectory().resolve(id.value)

    private fun deletionDirectory(): Path = root.resolve(".preview-deletions").also {
        if (Files.exists(it, NOFOLLOW_LINKS) && !Files.isDirectory(it, NOFOLLOW_LINKS))
            throw IOException("Invalid deletion directory")
    }

    private fun ids(): List<PreviewDraftId> {
        val result = linkedSetOf<PreviewDraftId>()
        fun scan(directory: Path) = Files.newDirectoryStream(directory).use { stream ->
            var scanned = 0
            for (path in stream) {
                if (++scanned > MAX_DRAFTS * 4) throw LimitExceeded()
                val id = try { PreviewDraftId(path.fileName.toString()) } catch (_: IllegalArgumentException) { continue }
                result.add(id)
                if (result.size > MAX_DRAFTS) throw LimitExceeded()
            }
        }
        scan(root)
        val pending = deletionDirectory()
        if (Files.exists(pending, NOFOLLOW_LINKS)) scan(pending)
        return result.sortedBy { it.value }
    }

    private fun <T> locked(failed: (DraftStorageFailure) -> T, action: () -> T): T {
        if (Thread.currentThread().isInterrupted) return failed(DraftStorageFailure.CANCELLED)
        // A same-JVM contender must not open/close the OS lock file: POSIX close can release
        // another transaction's process-wide lock. Keep the canonical-root guard non-reentrant.
        if (!synchronized(activeRoots) { activeRoots.add(root) }) return failed(DraftStorageFailure.BUSY)
        try {
            val lockPath = root.resolve(".preview-library.lock")
            if (Files.exists(lockPath, NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, NOFOLLOW_LINKS))
                return failed(DraftStorageFailure.CORRUPT)
            return FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) return failed(DraftStorageFailure.BUSY)
                lock.use { action() }
            }
        } catch (_: LimitExceeded) { return failed(DraftStorageFailure.LIMIT_EXCEEDED) }
        catch (_: IOException) { return failed(DraftStorageFailure.IO_FAILURE) }
        finally { synchronized(activeRoots) { activeRoots.remove(root) } }
    }

    private fun sync(path: Path) = FileChannel.open(path, READ).use { it.force(true) }
    private class LimitExceeded : Exception()
    companion object {
        const val MAX_DRAFTS = 64
        internal const val MAX_DELETE_ENTRIES = 4096
        private const val MAX_DELETE_DEPTH = 16
        private const val LEGACY_DELETED = ".preview-guest-deleted"
        private val activeRoots = HashSet<Path>()
    }
}

internal enum class LibraryCheckpoint { AFTER_DIRECTORY_CREATE, AFTER_SAVE }
internal enum class LibraryDeleteCheckpoint { TOMBSTONE_DURABLE, CONTENT_REMOVED, DIRECTORY_REMOVED, TOMBSTONE_REMOVED }

/** Transactional editor switch: a failed target open cannot steal the current form's save authority. */
class PreviewDraftSession(val id: PreviewDraftId, val record: PreviewStoredDraft?,
    val fields: PreviewDraftFields, val ready: Boolean) {
    fun afterDeletion(target: PreviewDraftId, result: PreviewLibraryDelete): PreviewDraftSession =
        if (id == target && (result == PreviewLibraryDelete.Deleted || result is PreviewLibraryDelete.Unconfirmed))
            PreviewDraftSession(id, null, fields, false)
        else this

    fun switchTo(target: PreviewDraftId, result: PreviewDraftLoad?,
        selection: PreviewActiveWrite = PreviewActiveWrite.Saved): PreviewDraftSession {
        if (selection != PreviewActiveWrite.Saved) return this
        return when (result) {
        is PreviewDraftLoad.Available -> PreviewDraftSession(target, result.record, result.record.fields, true)
        PreviewDraftLoad.Missing -> PreviewDraftSession(target, null, PreviewDraftFields(), true)
        is PreviewDraftLoad.Failed, null -> this
        }
    }
}
