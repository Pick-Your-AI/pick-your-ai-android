package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonInputLimits
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.Collections
import java.util.UUID

/** Local identity only. It confers no remote ownership or shared-build authority. */
data class LocalDraftId(val value: String) {
    init { require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)) }
}
enum class LibraryEntryState { AVAILABLE, UNAVAILABLE, RECOVERABLE, DELETION_PENDING }
class PreviewLibraryEntry internal constructor(
    val id: LocalDraftId, val state: LibraryEntryState, val record: PreviewStoredDraft?,
    val failure: DraftStorageFailure? = null,
) { override fun toString() = "PreviewLibraryEntry" }
class PreviewLibrarySnapshot internal constructor(
    entries: List<PreviewLibraryEntry>, val currentId: LocalDraftId?,
) {
    val entries: List<PreviewLibraryEntry> = Collections.unmodifiableList(ArrayList(entries))
    val current: PreviewLibraryEntry? get() = entries.find { it.id == currentId }
    override fun toString() = "PreviewLibrarySnapshot"
}
sealed interface PreviewLibraryResult {
    class Available(val snapshot: PreviewLibrarySnapshot) : PreviewLibraryResult
    data class Failed(val reason: DraftStorageFailure) : PreviewLibraryResult
    data object Conflict : PreviewLibraryResult
    /** The operation may have committed. Reopen the library before taking further save authority. */
    data object Uncertain : PreviewLibraryResult
}
internal enum class LibraryWritePoint { RECORD_SAVED, INDEX_STAGED, INDEX_ACTIVATED, DELETION_MARKED, LEGACY_MOVED }

/**
 * Bounded app-private guest library over LocalDraftStore, not a shared Draft wire format.
 * All cooperating writers must use this boundary (including edits) so deletion cannot race a save.
 * The existing durable root/ancestors and atomic rename/directory sync requirements still apply.
 * Unregistered directories remain visible for explicit recovery. Deletion first commits a tombstone;
 * failed cleanup remains visible and retryable. No readable draft is silently reset or pruned.
 */
class PreviewDraftLibrary internal constructor(
    directory: Path, private val legacyDirectory: Path?,
    private val checkpoint: (LibraryWritePoint) -> Unit,
) {
    constructor(directory: Path, legacyDirectory: Path? = null) : this(directory, legacyDirectory, {})
    private val root: Path
    private val records: Path
    init {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS))
        root = directory.toRealPath()
        records = root.resolve("records")
        require(!Files.isSymbolicLink(records))
        Files.createDirectories(records)
        sync(root)
    }

    fun read(): PreviewLibraryResult = transaction { state -> available(state) }

    /** Create an empty or incomplete local record and select it in one index commit. */
    fun create(fields: PreviewDraftFields = PreviewDraftFields()): PreviewLibraryResult = transaction { state ->
        createRecord(state, DraftContent(), fields, emptyMap())
    }

    fun select(id: LocalDraftId): PreviewLibraryResult = transaction { state ->
        if (id !in state.active) return@transaction PreviewLibraryResult.Conflict
        if (readRecord(id) !is PreviewDraftLoad.Available) return@transaction PreviewLibraryResult.Failed(DraftStorageFailure.CORRUPT)
        if (state.current != id) writeIndex(state.copy(current = id))
        available(state.copy(current = id))
    }

    fun save(id: LocalDraftId, expected: PreviewStoredDraft, fields: PreviewDraftFields): PreviewLibraryResult = transaction { state ->
        if (id !in state.active || state.current != id) return@transaction PreviewLibraryResult.Conflict
        when (val saved = PreviewDraftPersistence(store(id)).save(expected, fields)) {
            is PreviewDraftSave.Saved -> { changed = true; available(state) }
            PreviewDraftSave.Conflict -> PreviewLibraryResult.Conflict
            is PreviewDraftSave.Failed -> PreviewLibraryResult.Failed(saved.reason)
            PreviewDraftSave.Uncertain -> PreviewLibraryResult.Uncertain
        }
    }

    /** Copy saved content and recognized guest metadata, with new local identity and exact lineage. */
    fun duplicate(id: LocalDraftId, expected: PreviewStoredDraft): PreviewLibraryResult = transaction { state ->
        if (id !in state.active) return@transaction PreviewLibraryResult.Conflict
        val source = (readRecord(id) as? PreviewDraftLoad.Available)?.record
            ?: return@transaction PreviewLibraryResult.Failed(DraftStorageFailure.CORRUPT)
        if (!sameVersion(source, expected)) return@transaction PreviewLibraryResult.Conflict
        createRecord(state, source.draft.content, source.fields, mapOf(
            "library.sourceId" to id.value,
            "library.sourceRevision" to source.revision.toString(),
            "library.sourceHash" to source.draft.version.hash.hex,
        ))
    }

    /** Recover an unregistered completed create; corrupt/incomplete records stay in the recovery view. */
    fun recover(id: LocalDraftId): PreviewLibraryResult = transaction { state ->
        if (id in state.active || id in state.deleting || id !in diskIds()) return@transaction PreviewLibraryResult.Conflict
        if (readRecord(id) !is PreviewDraftLoad.Available) return@transaction PreviewLibraryResult.Failed(DraftStorageFailure.CORRUPT)
        val next = state.copy(active = state.active + id, current = id)
        writeIndex(next)
        available(next)
    }

    /** Null expected is allowed only while the selected record is still unreadable. Confirmation is a UI concern. */
    fun delete(id: LocalDraftId, expected: PreviewStoredDraft?): PreviewLibraryResult = transaction { state ->
        if (id !in state.active && id !in state.deleting && id !in diskIds()) return@transaction PreviewLibraryResult.Conflict
        if (id !in state.deleting) {
            val actual = (readRecord(id) as? PreviewDraftLoad.Available)?.record
            if (actual != null && (expected == null || !sameVersion(actual, expected)) || actual == null && expected != null) {
                return@transaction PreviewLibraryResult.Conflict
            }
            val marked = state.copy(active = state.active - id, deleting = state.deleting + id,
                current = state.current.takeUnless { it == id })
            writeIndex(marked)
            checkpoint(LibraryWritePoint.DELETION_MARKED)
            finishDeletion(marked, id)
        } else finishDeletion(state, id)
    }

    private fun Transaction.createRecord(state: Index, content: DraftContent, fields: PreviewDraftFields,
        lineage: Map<String, String>): PreviewLibraryResult {
        if ((diskIds() + state.active + state.deleting).size >= MAX_RECORDS) return PreviewLibraryResult.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
        val id = LocalDraftId(UUID.randomUUID().toString())
        val path = records.resolve(id.value)
        Files.createDirectory(path)
        sync(records)
        // Only known local guest fields and local lineage are copied. Unknown metadata is not authority.
        val metadata = mutableMapOf("preview.format" to "1", "preview.title" to fields.title)
        fields.selectedId?.let { metadata["preview.selectedId"] = it }
        fields.selectedVersion?.let { metadata["preview.selectedVersion"] = it }
        fields.catalogHash?.let { metadata["preview.catalogHash"] = it }
        metadata.putAll(lineage)
        when (val saved = store(id).save(null, content, metadata)) {
            is DraftSave.Saved -> changed = true
            is DraftSave.Failed -> return PreviewLibraryResult.Failed(saved.reason)
            else -> return PreviewLibraryResult.Uncertain
        }
        checkpoint(LibraryWritePoint.RECORD_SAVED)
        val next = state.copy(active = state.active + id, current = id)
        writeIndex(next)
        return available(next)
    }

    private fun Transaction.finishDeletion(state: Index, id: LocalDraftId): PreviewLibraryResult {
        val path = records.resolve(id.value)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            // Walk does not follow symbolic links. Preflight the whole bounded private record before deletion.
            val paths = Files.walk(path, 4).use { stream -> stream.iterator().asSequence().take(MAX_RECORD_FILES + 1).toList() }
            if (paths.size > MAX_RECORD_FILES) throw Failure(DraftStorageFailure.LIMIT_EXCEEDED)
            changed = true
            paths.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
            sync(records)
        }
        val next = state.copy(deleting = state.deleting - id)
        writeIndex(next)
        return available(next)
    }

    private data class Index(val current: LocalDraftId? = null, val active: Set<LocalDraftId> = emptySet(),
        val deleting: Set<LocalDraftId> = emptySet(), val legacyChecked: Boolean = false)

    private inner class Transaction {
        var changed = false
        fun writeIndex(state: Index) {
            val mapper = ObjectMapper()
            val value = mapper.createObjectNode().put("local_library_version", 1).put("legacy_checked", state.legacyChecked)
            value.put("current_id", state.current?.value)
            state.active.sortedBy { it.value }.forEach { value.withArray("active_ids").add(it.value) }
            state.deleting.sortedBy { it.value }.forEach { value.withArray("deleting_ids").add(it.value) }
            if (!value.has("active_ids")) value.putArray("active_ids")
            if (!value.has("deleting_ids")) value.putArray("deleting_ids")
            val bytes = mapper.writeValueAsBytes(value)
            if (bytes.size > MAX_INDEX_BYTES) throw Failure(DraftStorageFailure.LIMIT_EXCEEDED)
            val stage = root.resolve("index.stage")
            Files.deleteIfExists(stage)
            FileChannel.open(stage, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    cancelled()
                    if (channel.write(buffer) <= 0) throw IOException()
                }
                channel.force(true)
            }
            checkpoint(LibraryWritePoint.INDEX_STAGED)
            cancelled()
            changed = true
            Files.move(stage, root.resolve("index.json"), ATOMIC_MOVE, REPLACE_EXISTING)
            checkpoint(LibraryWritePoint.INDEX_ACTIVATED)
            sync(root)
        }

        fun available(state: Index): PreviewLibraryResult.Available {
            val ids = diskIds() + state.active + state.deleting
            val entries = ids.sortedBy { it.value }.map { id ->
                if (id in state.deleting) PreviewLibraryEntry(id, LibraryEntryState.DELETION_PENDING, null)
                else {
                    val result = readRecord(id)
                    val record = (result as? PreviewDraftLoad.Available)?.record
                    PreviewLibraryEntry(id, when {
                        id !in state.active -> LibraryEntryState.RECOVERABLE
                        record != null -> LibraryEntryState.AVAILABLE
                        else -> LibraryEntryState.UNAVAILABLE
                    }, record, (result as? PreviewDraftLoad.Failed)?.reason)
                }
            }
            return PreviewLibraryResult.Available(PreviewLibrarySnapshot(entries, state.current))
        }

        fun migrate(state: Index): Index {
            if (state.legacyChecked) return state
            val target = records.resolve(LEGACY_ID.value)
            if (legacyDirectory != null && Files.exists(legacyDirectory, NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(legacyDirectory, NOFOLLOW_LINKS) || Files.exists(target, NOFOLLOW_LINKS)) throw Failure(DraftStorageFailure.CORRUPT)
                changed = true
                Files.move(legacyDirectory, target, ATOMIC_MOVE)
                sync(legacyDirectory.parent)
                sync(records)
                checkpoint(LibraryWritePoint.LEGACY_MOVED)
            }
            val hasLegacy = Files.exists(target, NOFOLLOW_LINKS)
            val next = state.copy(legacyChecked = true,
                active = if (hasLegacy) state.active + LEGACY_ID else state.active,
                current = state.current ?: LEGACY_ID.takeIf { hasLegacy })
            writeIndex(next)
            return next
        }
    }

    private fun transaction(action: Transaction.(Index) -> PreviewLibraryResult): PreviewLibraryResult {
        if (Thread.currentThread().isInterrupted) return PreviewLibraryResult.Failed(DraftStorageFailure.CANCELLED)
        if (!synchronized(activeRoots) { activeRoots.add(root) }) return PreviewLibraryResult.Failed(DraftStorageFailure.BUSY)
        val transaction = Transaction()
        try {
            val lockPath = root.resolve("library.lock")
            if (Files.exists(lockPath, NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, NOFOLLOW_LINKS)) throw Failure(DraftStorageFailure.CORRUPT)
            return FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) return PreviewLibraryResult.Failed(DraftStorageFailure.BUSY)
                lock.use { transaction.action(transaction.migrate(readIndex())) }
            }
        } catch (failure: Failure) {
            return if (transaction.changed) PreviewLibraryResult.Uncertain else PreviewLibraryResult.Failed(failure.reason)
        } catch (_: IOException) {
            return if (transaction.changed) PreviewLibraryResult.Uncertain else PreviewLibraryResult.Failed(DraftStorageFailure.IO_FAILURE)
        } catch (_: IllegalArgumentException) {
            return if (transaction.changed) PreviewLibraryResult.Uncertain else PreviewLibraryResult.Failed(DraftStorageFailure.CORRUPT)
        } finally { synchronized(activeRoots) { activeRoots.remove(root) } }
    }

    private fun readIndex(): Index {
        val path = root.resolve("index.json")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Index()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > MAX_INDEX_BYTES) throw Failure(DraftStorageFailure.CORRUPT)
        val parsed = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use {
            BoundedJsonReader(JsonInputLimits(maxBytes = MAX_INDEX_BYTES, maxDepth = 4, maxTokens = 256, maxStringLength = 64)).read(it)
        }
        val value = (parsed as? JsonInputResult.Parsed)?.value ?: throw Failure(DraftStorageFailure.CORRUPT)
        fun bad(): Nothing = throw Failure(DraftStorageFailure.CORRUPT)
        if (!value.isObject || value.fieldNames().asSequence().toSet() != setOf("local_library_version", "legacy_checked", "current_id", "active_ids", "deleting_ids")) bad()
        if (!value["local_library_version"].isIntegralNumber || !value["local_library_version"].canConvertToInt()) bad()
        if (value["local_library_version"].intValue() != 1) throw Failure(DraftStorageFailure.UNSUPPORTED_VERSION)
        if (!value["legacy_checked"].isBoolean) bad()
        fun ids(name: String): Set<LocalDraftId> {
            val array = value[name]
            if (!array.isArray || array.size() > MAX_RECORDS || array.any { !it.isTextual }) bad()
            val ids = array.map { LocalDraftId(it.textValue()) }
            if (ids.toSet().size != ids.size) bad()
            return ids.toSet()
        }
        val active = ids("active_ids")
        val deleting = ids("deleting_ids")
        if ((active + deleting).size > MAX_RECORDS || active.intersect(deleting).isNotEmpty()) bad()
        val current = value["current_id"].let { if (it.isNull) null else if (it.isTextual) LocalDraftId(it.textValue()) else bad() }
        if (current != null && current !in active) bad()
        return Index(current, active, deleting, value["legacy_checked"].booleanValue())
    }

    private fun diskIds(): Set<LocalDraftId> = Files.newDirectoryStream(records).use { stream ->
        val result = LinkedHashSet<LocalDraftId>()
        for (path in stream) {
            cancelled()
            if (result.size >= MAX_RECORDS) throw Failure(DraftStorageFailure.LIMIT_EXCEEDED)
            result.add(LocalDraftId(path.fileName.toString()))
        }
        result
    }

    private fun readRecord(id: LocalDraftId): PreviewDraftLoad = try {
        val path = records.resolve(id.value)
        if (!Files.exists(path, NOFOLLOW_LINKS)) PreviewDraftLoad.Missing
        else PreviewDraftPersistence(store(id)).load()
    } catch (_: IOException) { PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE) }
    catch (_: IllegalArgumentException) { PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT) }

    private fun store(id: LocalDraftId) = LocalDraftStore(records.resolve(id.value))
    private fun sameVersion(left: PreviewStoredDraft, right: PreviewStoredDraft): Boolean =
        left.draft.version.store == right.draft.version.store && left.draft.version.number == right.draft.version.number &&
            left.draft.version.hash == right.draft.version.hash

    private class Failure(val reason: DraftStorageFailure) : IOException()
    private fun cancelled() { if (Thread.currentThread().isInterrupted) throw Failure(DraftStorageFailure.CANCELLED) }
    private fun sync(path: Path) = FileChannel.open(path, READ).use { it.force(true) }
    companion object {
        const val MAX_RECORDS = 32
        private const val MAX_INDEX_BYTES = 16_384
        private const val MAX_RECORD_FILES = 512
        val LEGACY_ID = LocalDraftId("00000000-0000-4000-8000-000000000001")
        private val activeRoots = HashSet<Path>()
    }
}

/** Equal revision bytes in a different draft never authorize a recreated screen to save there. */
data class PreviewLibraryRestoration(val selectedId: String?, val draft: PreviewDraftRestoration) {
    fun canResume(snapshot: PreviewLibrarySnapshot): Boolean = selectedId == snapshot.currentId?.value &&
        draft.canResume(snapshot.current?.record)
}
