package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections

/** Storage token, separate from an in-memory DraftHistory token or a shared build revision. */
class DraftVersion internal constructor(internal val store: Path, val number: Long, val hash: ArtifactHash)
enum class StoredRevisionChange { INITIAL, LEGACY_ANCHOR, EDIT, RESTORE }
class StoredRevision internal constructor(
    val number: Long,
    val change: StoredRevisionChange,
    val restoredFrom: Long?,
    val content: DraftContent,
    metadata: Map<String, String>,
) {
    val metadata: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(metadata))
}
class StoredDraft internal constructor(
    val version: DraftVersion,
    history: List<StoredRevision>,
    val localFormatVersion: Int,
    val legacySourceHash: ArtifactHash?,
) {
    val history: List<StoredRevision> = Collections.unmodifiableList(ArrayList(history))
    val content: DraftContent get() = history.last().content
    val metadata: Map<String, String> get() = history.last().metadata
    /** No earlier timeline is inferred from a legacy storage counter. */
    val earlierHistoryUnavailable: Boolean get() = history.first().number > 0
}
enum class DraftStorageFailure { BUSY, IO_FAILURE, CORRUPT, UNSUPPORTED_VERSION, LIMIT_EXCEEDED, CANCELLED }
sealed interface DraftLoad {
    data object Missing : DraftLoad
    class Available(val draft: StoredDraft) : DraftLoad
    data class Failed(val reason: DraftStorageFailure) : DraftLoad
}
sealed interface DraftSave {
    class Saved(val draft: StoredDraft) : DraftSave
    data object Conflict : DraftSave
    data object UnknownRevision : DraftSave
    data class Failed(val reason: DraftStorageFailure) : DraftSave
    /** Reopen before retry: the new head may already be durable. */
    data class Uncertain(val hash: ArtifactHash) : DraftSave
}

/**
 * One local draft per dedicated app-private directory. All writers must use this adapter.
 * Preserves immutable content-addressed revisions using VerifiedArtifactCache; no eviction policy.
 * Directory and atomic-rename durability are inherited from that cache, not Android-qualified.
 * Unsupported/corrupt current data blocks saves without changing any original bytes.
 */
class LocalDraftStore internal constructor(
    directory: Path,
    private val cacheFactory: (Path) -> VerifiedArtifactCache,
    private val afterTransaction: () -> Unit,
) {
    internal constructor(directory: Path, cacheFactory: (Path) -> VerifiedArtifactCache) : this(directory, cacheFactory, {})
    constructor(directory: Path) : this(directory, { VerifiedArtifactCache(it, MAX_BYTES) }, {})
    private val root: Path
    private val cache: VerifiedArtifactCache
    init {
        require(!Files.isSymbolicLink(directory))
        Files.createDirectories(directory)
        root = directory.toRealPath()
        cache = cacheFactory(root.resolve("revisions"))
    }

    fun load(): DraftLoad = guarded({ DraftLoad.Failed(it) }) { decode(cache.active()) }

    /** Read immutable history without activating it; restoring is an explicit new save with current token. */
    fun revision(hash: ArtifactHash): DraftLoad = guarded({ DraftLoad.Failed(it) }) { decode(cache.pinned(hash)) }

    /** Omitted metadata preserves the current map; an explicit empty map clears it. */
    fun save(expected: DraftVersion?, content: DraftContent, metadata: Map<String, String>? = null): DraftSave =
        update(expected, content, metadata, null)

    /** Restore retained content as a new revision, preserving current metadata and all prior entries. */
    fun restore(expected: DraftVersion, revision: Long): DraftSave = update(expected, null, null, revision)

    private fun update(expected: DraftVersion?, content: DraftContent?, metadata: Map<String, String>?, restore: Long?): DraftSave {
        var attemptedHash: ArtifactHash? = null
        return guarded({ reason -> attemptedHash?.let { DraftSave.Uncertain(it) } ?: DraftSave.Failed(reason) }) {
            val current = when (val loaded = decode(cache.active())) {
                DraftLoad.Missing -> null
                is DraftLoad.Failed -> return@guarded DraftSave.Failed(loaded.reason)
                is DraftLoad.Available -> loaded.draft
            }
            if (current == null && expected != null || current != null &&
                (expected == null || expected.store != root || expected.hash != current.version.hash || expected.number != current.version.number)) {
                return@guarded DraftSave.Conflict
            }
            val number = try { Math.incrementExact(current?.version?.number ?: -1L) } catch (_: ArithmeticException) {
                return@guarded DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
            }
            val previous = current?.history ?: emptyList()
            if (previous.size >= MAX_HISTORY) return@guarded DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
            val restored = if (restore == null) null else previous.find { it.number == restore }
                ?: return@guarded DraftSave.UnknownRevision
            val next = StoredRevision(number, when {
                restore != null -> StoredRevisionChange.RESTORE
                current == null -> StoredRevisionChange.INITIAL
                else -> StoredRevisionChange.EDIT
            }, restore, restored?.content ?: requireNotNull(content), metadata ?: current?.metadata ?: emptyMap())
            val bytes = try { DraftEnvelope.encode(previous + next, current?.legacySourceHash) } catch (_: EnvelopeFailure) {
                return@guarded DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
            }
            // Apply the same parser limits to writes, so every acknowledged envelope can be reopened.
            val decoded = try { DraftEnvelope.decode(bytes) } catch (_: EnvelopeFailure) {
                return@guarded DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED)
            }
            val hash = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            // An outer lock-release/channel-close error after this point cannot prove no activation.
            attemptedHash = hash
            when (val result = cache.install(hash, bytes.inputStream())) {
                is CacheInstall.Activated -> DraftSave.Saved(stored(decoded, hash))
                is CacheInstall.ActivationUncertain -> DraftSave.Uncertain(hash)
                is CacheInstall.Failed -> DraftSave.Failed(mapFailure(result.reason))
            }
        }
    }

    private fun decode(read: CacheRead): DraftLoad = when (read) {
        CacheRead.Missing -> DraftLoad.Missing
        is CacheRead.Failed -> DraftLoad.Failed(mapFailure(read.reason))
        is CacheRead.Available -> try {
            val envelope = DraftEnvelope.decode(read.bytes)
            DraftLoad.Available(stored(envelope, read.hash))
        } catch (failure: EnvelopeFailure) { DraftLoad.Failed(failure.reason) }
    }

    private fun stored(envelope: DecodedEnvelope, hash: ArtifactHash) = StoredDraft(
        DraftVersion(root, envelope.history.last().number, hash), envelope.history, envelope.format,
        if (envelope.format == 1) hash else envelope.legacySourceHash,
    )

    private fun <T> guarded(failed: (DraftStorageFailure) -> T, action: () -> T): T {
        if (Thread.currentThread().isInterrupted) return failed(DraftStorageFailure.CANCELLED)
        if (!synchronized(activeRoots) { activeRoots.add(root) }) return failed(DraftStorageFailure.BUSY)
        try {
            val lockPath = root.resolve("transaction.lock")
            if (Files.exists(lockPath, NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, NOFOLLOW_LINKS)) return failed(DraftStorageFailure.CORRUPT)
            val result = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) return failed(DraftStorageFailure.BUSY)
                lock.use { action() }
            }
            afterTransaction() // Fault-injection seam for failures after a transaction has completed.
            return result
        } catch (_: IOException) { return failed(DraftStorageFailure.IO_FAILURE) }
        finally { synchronized(activeRoots) { activeRoots.remove(root) } }
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_HISTORY = 128
        private val activeRoots = HashSet<Path>()
        private fun mapFailure(reason: CacheFailure): DraftStorageFailure = when (reason) {
            CacheFailure.BUSY -> DraftStorageFailure.BUSY
            CacheFailure.CANCELLED -> DraftStorageFailure.CANCELLED
            CacheFailure.TOO_LARGE -> DraftStorageFailure.LIMIT_EXCEEDED
            CacheFailure.CORRUPT, CacheFailure.HASH_MISMATCH -> DraftStorageFailure.CORRUPT
            CacheFailure.IO_FAILURE -> DraftStorageFailure.IO_FAILURE
        }
    }
}

private class EnvelopeFailure(val reason: DraftStorageFailure) : RuntimeException()
private data class DecodedEnvelope(val format: Int, val history: List<StoredRevision>, val legacySourceHash: ArtifactHash?)

/** Private local formats, never frozen contracts or BuildSnapshots. V1 reads never rewrite bytes. */
private object DraftEnvelope {
    private val mapper = ObjectMapper()
    private fun bad(): Nothing = throw EnvelopeFailure(DraftStorageFailure.CORRUPT)
    fun decode(bytes: ByteArray): DecodedEnvelope {
        val parsed = BoundedJsonReader().read(bytes.inputStream()) as? JsonInputResult.Parsed ?: bad()
        val node = parsed.value
        val version = node["local_format_version"] ?: bad()
        if (!version.isIntegralNumber || !version.canConvertToInt()) bad()
        if (version.intValue() == 1) {
            fields(node, setOf("local_format_version", "revision", "content", "metadata"))
            return DecodedEnvelope(1, listOf(StoredRevision(number(node["revision"]), StoredRevisionChange.LEGACY_ANCHOR,
                null, content(node["content"]), strings(node["metadata"]))), null)
        }
        if (version.intValue() != 2) throw EnvelopeFailure(DraftStorageFailure.UNSUPPORTED_VERSION)
        fields(node, setOf("local_format_version", "revision", "legacy_source_hash", "history"))
        val head = number(node["revision"])
        val legacy = node["legacy_source_hash"].let {
            if (it.isNull) null else try { ArtifactHash(text(it)) } catch (_: IllegalArgumentException) { bad() }
        }
        val entries = node["history"]
        if (!entries.isArray || entries.isEmpty || entries.size() > LocalDraftStore.MAX_HISTORY) bad()
        val history = entries.map { entry ->
            fields(entry, setOf("number", "change", "restored_from", "content", "metadata"))
            val change = StoredRevisionChange.entries.find { it.name == text(entry["change"]) } ?: bad()
            StoredRevision(number(entry["number"]), change,
                if (entry["restored_from"].isNull) null else number(entry["restored_from"]),
                content(entry["content"]), strings(entry["metadata"]))
        }
        val first = history.first()
        if (legacy == null) {
            if (first.change != StoredRevisionChange.INITIAL || first.number != 0L) bad()
        } else if (first.change != StoredRevisionChange.LEGACY_ANCHOR) bad()
        history.forEachIndexed { index, entry ->
            if (index > 0) {
                val prior = history[index - 1]
                if (prior.number == Long.MAX_VALUE || entry.number != prior.number + 1) bad()
                if (entry.change != StoredRevisionChange.EDIT && entry.change != StoredRevisionChange.RESTORE) bad()
            }
            if (entry.change == StoredRevisionChange.RESTORE) {
                val target = history.take(index).find { it.number == entry.restoredFrom } ?: bad()
                if (entry.content != target.content || entry.metadata != history[index - 1].metadata) bad()
            } else if (entry.restoredFrom != null) bad()
        }
        if (history.last().number != head) bad()
        return DecodedEnvelope(2, history, legacy)
    }
    private fun content(content: JsonNode): DraftContent {
        fields(content, setOf("objective", "context", "primaryModel", "optional"))
        val optional = content["optional"]
        fields(optional, OptionalTier.entries.map { it.name }.toSet())
        val tiers = OptionalTier.entries.associateWith { tier ->
            val value = optional[tier.name]
            fields(value, setOf("activation", "selections", "settings"))
            val mode = Activation.entries.find { it.name == text(value["activation"]) } ?: bad()
            val selections = value["selections"]
            if (!selections.isArray || selections.any { !it.isTextual }) bad()
            val inputs = selections.map { it.textValue() }
            if (inputs.toSet().size != inputs.size) bad()
            TierDraft(mode, inputs.toSet(), strings(value["settings"]))
        }
        fun nullable(name: String): String? = content[name].let { if (it.isNull) null else text(it) }
        return DraftContent(nullable("objective"), nullable("context"), nullable("primaryModel"), tiers)
    }
    private fun text(node: JsonNode): String = if (node.isTextual) node.textValue() else bad()
    private fun number(node: JsonNode): Long {
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.longValue() < 0) bad()
        return node.longValue()
    }
    private fun fields(node: JsonNode, expected: Set<String>) {
        if (!node.isObject || node.fieldNames().asSequence().toSet() != expected) bad()
    }
    private fun strings(node: JsonNode): Map<String, String> {
        if (!node.isObject || node.any { !it.isTextual }) bad()
        return node.properties().associate { it.key to it.value.textValue() }
    }
    fun encode(history: List<StoredRevision>, legacy: ArtifactHash?): ByteArray {
        val node = mapper.createObjectNode().put("local_format_version", 2).put("revision", history.last().number)
        node.put("legacy_source_hash", legacy?.hex)
        val entries = node.putArray("history")
        history.forEach { entry ->
            val target = entries.addObject().put("number", entry.number).put("change", entry.change.name)
            if (entry.restoredFrom == null) target.putNull("restored_from") else target.put("restored_from", entry.restoredFrom)
            encodeContent(target.putObject("content"), entry.content)
            stringMap(target.putObject("metadata"), entry.metadata)
        }
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                if (output.size() >= LocalDraftStore.MAX_BYTES) throw EnvelopeFailure(DraftStorageFailure.LIMIT_EXCEEDED)
                output.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > LocalDraftStore.MAX_BYTES - output.size()) throw EnvelopeFailure(DraftStorageFailure.LIMIT_EXCEEDED)
                output.write(bytes, offset, length)
            }
        }
        mapper.writeValue(bounded, node)
        return output.toByteArray()
    }
    private fun encodeContent(body: ObjectNode, content: DraftContent) {
        body.put("objective", content.objective).put("context", content.context).put("primaryModel", content.primaryModel)
        val optional = body.putObject("optional")
        content.optional.forEach { (tier, value) ->
            val target = optional.putObject(tier.name).put("activation", value.activation.name)
            val selections = target.putArray("selections")
            value.selections.forEach { selections.add(it) }
            stringMap(target.putObject("settings"), value.settings)
        }
    }
    private fun stringMap(node: ObjectNode, values: Map<String, String>) { values.forEach { (key, value) -> node.put(key, value) } }
}
