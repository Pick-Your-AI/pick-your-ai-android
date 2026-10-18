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
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections

/** Storage token, separate from an in-memory DraftHistory token or a shared build revision. */
class DraftVersion internal constructor(internal val store: Path, val number: Long, val hash: ArtifactHash)
class StoredDraft internal constructor(val version: DraftVersion, val content: DraftContent, metadata: Map<String, String>) {
    val metadata: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(metadata))
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
    data class Failed(val reason: DraftStorageFailure) : DraftSave
    /** Reopen before retry: the new head may already be durable. */
    data class Uncertain(val hash: ArtifactHash) : DraftSave
}

/**
 * One local draft per existing, durably provisioned app-private directory.
 * The caller must make the root and its ancestor entries durable. All writers use this adapter.
 * Preserves immutable content-addressed revisions using VerifiedArtifactCache; no eviction policy.
 * Directory and atomic-rename durability are inherited from that cache, not Android-qualified.
 * Unsupported/corrupt current data blocks saves without changing any original bytes.
 */
class LocalDraftStore internal constructor(
    directory: Path,
    private val cacheFactory: (Path) -> VerifiedArtifactCache,
    private val afterTransaction: () -> Unit,
    syncRoot: (Path) -> Unit = { path -> FileChannel.open(path, READ).use { it.force(true) } },
) {
    internal constructor(directory: Path, cacheFactory: (Path) -> VerifiedArtifactCache) : this(directory, cacheFactory, {})
    constructor(directory: Path) : this(directory, { VerifiedArtifactCache(it, MAX_BYTES) }, {})
    private val root: Path
    private val cache: VerifiedArtifactCache
    init {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "An existing durable store directory is required" }
        root = directory.toRealPath()
        val revisions = root.resolve("revisions")
        require(!Files.isSymbolicLink(revisions)) { "Revision directory must not be a symbolic link" }
        Files.createDirectories(revisions)
        // Sync even when the child already exists: a prior construction may have failed after
        // mkdir but before its parent entry became durable. Do not construct a usable store
        // until this succeeds. Concurrent cooperating constructors may safely repeat the sync.
        syncRoot(root)
        cache = cacheFactory(revisions)
    }

    fun load(): DraftLoad = guarded({ DraftLoad.Failed(it) }) { decode(cache.active()) }

    /** Read immutable history without activating it; restoring is an explicit new save with current token. */
    fun revision(hash: ArtifactHash): DraftLoad = guarded({ DraftLoad.Failed(it) }) { decode(cache.pinned(hash)) }

    /** Omitted metadata preserves the current map; an explicit empty map clears it. */
    fun save(expected: DraftVersion?, content: DraftContent, metadata: Map<String, String>? = null): DraftSave {
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
            val bytes = try { DraftEnvelope.encode(number, content, metadata ?: current?.metadata ?: emptyMap()) } catch (_: EnvelopeFailure) {
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
                is CacheInstall.Activated -> DraftSave.Saved(StoredDraft(DraftVersion(root, number, hash), decoded.content, decoded.metadata))
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
            DraftLoad.Available(StoredDraft(DraftVersion(root, envelope.revision, read.hash), envelope.content, envelope.metadata))
        } catch (failure: EnvelopeFailure) { DraftLoad.Failed(failure.reason) }
    }

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
private data class DecodedEnvelope(val revision: Long, val content: DraftContent, val metadata: Map<String, String>)

/** Private local format. Never fed to FrozenSchemaValidator or emitted as a BuildSnapshot. */
private object DraftEnvelope {
    private val mapper = ObjectMapper()
    private fun bad(): Nothing = throw EnvelopeFailure(DraftStorageFailure.CORRUPT)
    fun decode(bytes: ByteArray): DecodedEnvelope {
        val parsed = BoundedJsonReader().read(bytes.inputStream()) as? JsonInputResult.Parsed ?: bad()
        val node = parsed.value
        val version = node["local_format_version"] ?: bad()
        if (!version.isIntegralNumber || !version.canConvertToInt()) bad()
        if (version.intValue() != 1) throw EnvelopeFailure(DraftStorageFailure.UNSUPPORTED_VERSION)
        fields(node, setOf("local_format_version", "revision", "content", "metadata"))
        val revision = node["revision"]
        if (!revision.isIntegralNumber || !revision.canConvertToLong() || revision.longValue() < 0) bad()
        val content = node["content"]
        fields(content, setOf("objective", "context", "primaryModel", "optional"))
        val optional = content["optional"]
        fields(optional, OptionalTier.entries.map { it.name }.toSet())
        val tiers = OptionalTier.entries.associateWith { tier ->
            val value = optional[tier.name]
            fields(value, setOf("activation", "selections", "settings"))
            val activation = value["activation"].takeIf { it.isTextual }?.textValue() ?: bad()
            val mode = Activation.entries.find { it.name == activation } ?: bad()
            val selections = value["selections"]
            if (!selections.isArray || selections.any { !it.isTextual }) bad()
            val inputs = selections.map { it.textValue() }
            if (inputs.toSet().size != inputs.size) bad()
            TierDraft(mode, inputs.toSet(), strings(value["settings"]))
        }
        fun nullable(name: String): String? = content[name].let { if (it.isNull) null else if (it.isTextual) it.textValue() else bad() }
        return DecodedEnvelope(revision.longValue(), DraftContent(nullable("objective"), nullable("context"), nullable("primaryModel"), tiers), strings(node["metadata"]))
    }
    private fun fields(node: JsonNode, expected: Set<String>) {
        if (!node.isObject || node.fieldNames().asSequence().toSet() != expected) bad()
    }
    private fun strings(node: JsonNode): Map<String, String> {
        if (!node.isObject || node.any { !it.isTextual }) bad()
        return node.properties().associate { it.key to it.value.textValue() }
    }
    fun encode(revision: Long, content: DraftContent, metadata: Map<String, String>): ByteArray {
        val node = mapper.createObjectNode().put("local_format_version", 1).put("revision", revision)
        val body = node.putObject("content")
        body.put("objective", content.objective).put("context", content.context).put("primaryModel", content.primaryModel)
        val optional = body.putObject("optional")
        content.optional.forEach { (tier, value) ->
            val target = optional.putObject(tier.name).put("activation", value.activation.name)
            val selections = target.putArray("selections")
            value.selections.forEach { selections.add(it) }
            stringMap(target.putObject("settings"), value.settings)
        }
        stringMap(node.putObject("metadata"), metadata)
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
    private fun stringMap(node: ObjectNode, values: Map<String, String>) { values.forEach { (key, value) -> node.put(key, value) } }
}
