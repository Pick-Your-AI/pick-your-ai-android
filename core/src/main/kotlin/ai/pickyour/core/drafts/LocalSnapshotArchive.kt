package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** Explicit local profile limits. Failed/unreferenced complete objects consume quota; no eviction. */
internal data class SnapshotArchiveLimits(
    val maxSnapshots: Int = 32,
    val bundles: CacheRetentionLimits = CacheRetentionLimits(64, 64L * 1024 * 1024),
    val indices: CacheRetentionLimits = CacheRetentionLimits(64, 4L * 1024 * 1024),
) {
    init { require(maxSnapshots in 1..32 && bundles.maxArtifacts <= 64 && indices.maxArtifacts <= 64) }
}

/**
 * A dedicated, durably provisioned app-private directory, separate from every source draft.
 * Complete immutable bundle files precede an atomic immutable-index activation. A durable local
 * namespace seal distinguishes first initialization from lost index state; tokens bind its nonce.
 * Source/liveness locks are held by the caller across append. Lock order is library, draft, archive,
 * then one cache at a time. All locks are non-reentrant/nonblocking and all writers must cooperate.
 * No networking, expiry, source-edit/deletion cascade, import/export, evaluation or UI is supplied.
 * Atomic rename and directory fsync are mandatory; Android power-loss behavior remains unqualified.
 */
class LocalSnapshotArchive internal constructor(
    directory: Path,
    private val limits: SnapshotArchiveLimits,
    private val cacheFactory: (Path, Int, CacheRetentionLimits) -> VerifiedArtifactCache,
    private val afterTransaction: () -> Unit = {},
    private val syncRoot: (Path) -> Unit = { path -> FileChannel.open(path, READ).use { it.force(true) } },
) {
    constructor(directory: Path) : this(directory, SnapshotArchiveLimits(), { path, max, retention -> VerifiedArtifactCache(path, max, retention) })
    private val root: Path
    private val bundleDirectory: Path
    private val indexDirectory: Path
    private val bundles: VerifiedArtifactCache
    private val indices: VerifiedArtifactCache
    init {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "An existing durable archive directory is required" }
        root = directory.toRealPath()
        bundleDirectory = child("bundles")
        indexDirectory = child("indices")
        syncRoot(root)
        bundles = cacheFactory(bundleDirectory, SnapshotBundleCodec.MAX_BYTES, limits.bundles)
        indices = cacheFactory(indexDirectory, LocalDraftStore.MAX_BYTES, limits.indices)
    }

    /** Initializes only an empty namespace. Available verifies the index, not every retained bundle. */
    fun load(): SnapshotArchiveLoad = guarded({ SnapshotArchiveLoad.Failed(it) }) {
        val namespace = initializeNamespace()
        SnapshotArchiveLoad.Available(current(namespace))
    }

    /** Read the current indexed identity; a missing/corrupt index never falls back to another head. */
    fun read(key: SnapshotArchiveKey): SnapshotArchiveRead = guarded({ SnapshotArchiveRead.Failed(it) }) {
        val state = current(readNamespace())
        readEntry(state.entries.find { it.key == key })
    }

    /** Captured historical index authority is read-only; it can never authorize a current append. */
    fun readAt(version: SnapshotArchiveVersion, key: SnapshotArchiveKey): SnapshotArchiveRead = guarded({ SnapshotArchiveRead.Failed(it) }) {
        val namespace = readNamespace()
        if (version.root != root || version.namespace != namespace) archiveBad()
        val state = index(readRequired(indices.pinned(version.hash)), namespace)
        if (state.version.generation != version.generation) archiveBad()
        readEntry(state.entries.find { it.key == key })
    }

    /**
     * Verify one exact retained receipt even when the current index is unavailable. This supplies no
     * index/publication or write authority; the object may be unreferenced after an interrupted save.
     */
    fun inspectRetained(hash: ArtifactHash): SnapshotArchiveRead = guarded({ SnapshotArchiveRead.Failed(it) }) {
        readNamespace()
        when (val read = bundles.pinned(hash)) {
            CacheRead.Missing -> SnapshotArchiveRead.Missing
            is CacheRead.Failed -> archiveBad(mapFailure(read.reason))
            is CacheRead.Available -> SnapshotArchiveRead.Available(SnapshotBundleCodec.decode(read.bytes))
        }
    }

    internal fun isDisjointFrom(directory: Path): Boolean = !root.startsWith(directory) && !directory.startsWith(root)

    /** Called only by the live source store after exact review/acknowledgment checks under its lock. */
    internal fun append(candidate: DraftSnapshotCandidate, expected: SnapshotArchiveVersion,
        publicationAttempted: (SnapshotArchiveEntry) -> Unit): DraftSnapshotArchival {
        var attempted: SnapshotArchiveEntry? = null
        return guarded({ failure -> attempted?.let { DraftSnapshotArchival.Uncertain(it) }
            ?: DraftSnapshotArchival.ArchiveUnavailable(failure) }) {
            val namespace = readNamespace()
            val state = current(namespace)
            if (expected.root != root || expected.namespace != namespace || expected.hash != state.version.hash ||
                expected.generation != state.version.generation) return@guarded DraftSnapshotArchival.ArchiveConflict
            val payload = SnapshotBundleCodec.encode(candidate)
            val snapshot = SnapshotBundleCodec.decode(payload)
            val entry = snapshot.entry
            // All complete retained objects reserve their identities, including failed-publication
            // orphans. Validate every indexed entry before append; do not hide earlier corruption.
            val retained = inventory(bundleDirectory, limits.bundles.maxArtifacts).map { hash ->
                SnapshotBundleCodec.decode(readRequired(bundles.pinned(hash)).bytes).entry
            }
            val byKey = retained.groupBy { it.key }
            if (byKey.values.any { values -> values.map { it.bundleHash }.toSet().size != 1 }) archiveBad()
            if (state.entries.any { saved -> retained.none { sameEntry(saved, it) } }) archiveBad()
            byKey[entry.key]?.singleOrNull()?.let { saved ->
                if (!sameEntry(saved, entry)) return@guarded DraftSnapshotArchival.IdentityConflict
            }
            state.entries.find { it.key == entry.key }?.let { saved ->
                if (!sameEntry(saved, entry)) return@guarded DraftSnapshotArchival.IdentityConflict
                return@guarded DraftSnapshotArchival.Archived(saved, state.version, true)
            }
            if (state.entries.size >= limits.maxSnapshots) return@guarded DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.LIMIT_EXCEEDED)
            val indexBytes = encodeIndex(state.entries + entry)
            val indexHash = archiveHash(indexBytes)
            when (val installed = bundles.install(entry.bundleHash, payload.inputStream())) {
                is CacheInstall.Activated -> Unit
                is CacheInstall.Failed -> return@guarded DraftSnapshotArchival.NotPublished(entry, mapFailure(installed.reason))
                is CacheInstall.ActivationUncertain -> return@guarded DraftSnapshotArchival.NotPublished(entry, SnapshotArchiveFailure.IO_FAILURE)
            }
            attempted = entry
            publicationAttempted(entry)
            when (val installed = indices.install(indexHash, indexBytes.inputStream())) {
                is CacheInstall.Activated -> DraftSnapshotArchival.Archived(entry, SnapshotArchiveVersion(root, namespace, state.version.generation + 1, indexHash), false)
                is CacheInstall.Failed -> {
                    // No logical activation attempted. The complete unreferenced bundle remains
                    // inspectable and reserves its identity; the previous index is unchanged.
                    attempted = null
                    DraftSnapshotArchival.NotPublished(entry, mapFailure(installed.reason))
                }
                is CacheInstall.ActivationUncertain -> DraftSnapshotArchival.Uncertain(entry)
            }
        }
    }

    private fun readEntry(entry: SnapshotArchiveEntry?): SnapshotArchiveRead {
        if (entry == null) return SnapshotArchiveRead.Missing
        val read = readRequired(bundles.pinned(entry.bundleHash))
        val snapshot = SnapshotBundleCodec.decode(read.bytes)
        if (!sameEntry(entry, snapshot.entry)) archiveBad()
        return SnapshotArchiveRead.Available(snapshot)
    }
    private fun current(namespace: String): SnapshotArchiveState = index(readRequired(indices.active()), namespace)
    private fun index(read: CacheRead.Available, namespace: String): SnapshotArchiveState {
        val node = try { SelectionJson.parse(read.bytes) } catch (_: IllegalArgumentException) { archiveBad() }
        SnapshotBundleCodec.fields(node, setOf("archive_index_version", "generation", "entries"))
        if (SnapshotBundleCodec.integer(node["archive_index_version"], 0, Int.MAX_VALUE) != 1) archiveBad(SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        val entries = node["entries"]
        if (entries == null || !entries.isArray || entries.size() > 32) archiveBad()
        val generation = SnapshotBundleCodec.integer(node["generation"], 0, 32)
        if (generation != entries.size()) archiveBad()
        val decoded = entries.map { entry ->
            SnapshotBundleCodec.fields(entry, setOf("identity", "sha256", "bytes"))
            SnapshotArchiveEntry(SnapshotBundleCodec.key(entry["identity"]), ArtifactHash(SnapshotBundleCodec.text(entry["sha256"])),
                SnapshotBundleCodec.integer(entry["bytes"], 1, SnapshotBundleCodec.MAX_BYTES))
        }
        if (decoded.map { it.key }.toSet().size != decoded.size || decoded.map { it.bundleHash }.toSet().size != decoded.size) archiveBad()
        return SnapshotArchiveState(SnapshotArchiveVersion(root, namespace, generation, read.hash), decoded)
    }
    private fun encodeIndex(entries: List<SnapshotArchiveEntry>): ByteArray {
        val node = ObjectMapper().createObjectNode().put("archive_index_version", 1).put("generation", entries.size)
        node.putArray("entries").also { array -> entries.forEach { entry ->
            array.addObject().put("sha256", entry.bundleHash.hex).put("bytes", entry.bundleBytes)
                .set<com.fasterxml.jackson.databind.node.ObjectNode>("identity", SnapshotBundleCodec.keyNode(entry.key))
        } }
        return try { SelectionJson.encode(node) } catch (_: IllegalArgumentException) { archiveBad(SnapshotArchiveFailure.LIMIT_EXCEEDED) }
    }

    private fun initializeNamespace(): String {
        val seal = root.resolve("namespace")
        if (Files.exists(seal, NOFOLLOW_LINKS)) {
            val namespace = readNamespace()
            // A prior initialization may have failed after writing the seal but before its force.
            // A reopened usable namespace must finish that same durability boundary before writes.
            FileChannel.open(seal, READ, NOFOLLOW_LINKS).use { it.force(true) }
            syncRoot(root)
            return namespace
        }
        // No snapshot can be written before this seal. Populated/unrecognized layouts are lost
        // metadata, never a new empty namespace. Only exact empty-index initialization can retry.
        if (inventory(bundleDirectory, limits.bundles.maxArtifacts).isNotEmpty()) archiveBad()
        if (Files.exists(bundleDirectory.resolve("active"), NOFOLLOW_LINKS) ||
            Files.exists(bundleDirectory.resolve("stage-artifact"), NOFOLLOW_LINKS) || Files.exists(bundleDirectory.resolve("stage-pointer"), NOFOLLOW_LINKS)) archiveBad()
        val empty = encodeIndex(emptyList())
        val emptyHash = archiveHash(empty)
        if (inventory(indexDirectory, limits.indices.maxArtifacts).any { it != emptyHash }) archiveBad()
        when (val active = indices.active()) {
            CacheRead.Missing -> when (val installed = indices.install(emptyHash, empty.inputStream())) {
                is CacheInstall.Activated -> Unit
                is CacheInstall.Failed -> archiveBad(mapFailure(installed.reason))
                is CacheInstall.ActivationUncertain -> archiveBad(SnapshotArchiveFailure.IO_FAILURE)
            }
            is CacheRead.Failed -> archiveBad(mapFailure(active.reason))
            is CacheRead.Available -> if (active.hash != emptyHash || !active.bytes.contentEquals(empty)) archiveBad()
        }
        val namespace = UUID.randomUUID().toString()
        val bytes = (SEAL_PREFIX + namespace + "\n").toByteArray(Charsets.US_ASCII)
        FileChannel.open(seal, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        syncRoot(root)
        return namespace
    }
    private fun readNamespace(): String {
        val seal = root.resolve("namespace")
        if (!Files.isRegularFile(seal, NOFOLLOW_LINKS) || Files.size(seal) != (SEAL_PREFIX.length + 37).toLong()) archiveBad()
        val bytes = ByteArray(SEAL_PREFIX.length + 37)
        Files.newInputStream(seal, READ, NOFOLLOW_LINKS).use { input ->
            DataInputStream(input).readFully(bytes)
            if (input.read() != -1) archiveBad()
        }
        val value = String(bytes, Charsets.US_ASCII)
        if (value.length != SEAL_PREFIX.length + 37 || !value.startsWith(SEAL_PREFIX) || !value.endsWith("\n")) archiveBad()
        val namespace = value.substring(SEAL_PREFIX.length, value.length - 1)
        if (!namespace.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) archiveBad()
        return namespace
    }

    private fun inventory(directory: Path, maximum: Int): List<ArtifactHash> {
        val hashes = mutableListOf<ArtifactHash>()
        Files.newDirectoryStream(directory).use { entries -> entries.forEach { entry ->
            if (Thread.currentThread().isInterrupted) archiveBad(SnapshotArchiveFailure.CANCELLED)
            if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) archiveBad()
            val name = entry.fileName.toString()
            when {
                name.matches(Regex("[0-9a-f]{64}\\.bin")) -> {
                    if (hashes.size >= maximum) archiveBad(SnapshotArchiveFailure.LIMIT_EXCEEDED)
                    hashes += ArtifactHash(name.removeSuffix(".bin"))
                }
                name in setOf("active", "lock", "stage-artifact", "stage-pointer") -> Unit
                else -> archiveBad()
            }
        } }
        return hashes
    }
    private fun child(name: String): Path = root.resolve(name).also {
        require(!Files.isSymbolicLink(it)) { "Archive child must not be a symbolic link" }
        Files.createDirectories(it)
    }
    private fun readRequired(read: CacheRead): CacheRead.Available = when (read) {
        CacheRead.Missing -> archiveBad()
        is CacheRead.Failed -> archiveBad(mapFailure(read.reason))
        is CacheRead.Available -> read
    }
    private fun <T> guarded(failed: (SnapshotArchiveFailure) -> T, action: () -> T): T {
        if (Thread.currentThread().isInterrupted) return failed(SnapshotArchiveFailure.CANCELLED)
        if (!synchronized(activeRoots) { activeRoots.add(root) }) return failed(SnapshotArchiveFailure.BUSY)
        try {
            val path = root.resolve("transaction.lock")
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) return failed(SnapshotArchiveFailure.CORRUPT)
            val result = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) return failed(SnapshotArchiveFailure.BUSY)
                lock.use { action() }
            }
            afterTransaction()
            return result
        } catch (failure: ArchiveFormatFailure) { return failed(failure.reason) }
        catch (_: IllegalArgumentException) { return failed(SnapshotArchiveFailure.CORRUPT) }
        catch (_: DirectoryIteratorException) { return failed(SnapshotArchiveFailure.IO_FAILURE) }
        catch (_: IOException) { return failed(SnapshotArchiveFailure.IO_FAILURE) }
        finally { synchronized(activeRoots) { activeRoots.remove(root) } }
    }
    private companion object {
        const val SEAL_PREFIX = "PYAI-SNAPSHOT-ARCHIVE 1 "
        val activeRoots = HashSet<Path>()
        fun sameEntry(a: SnapshotArchiveEntry, b: SnapshotArchiveEntry) = a.key == b.key && a.bundleHash == b.bundleHash && a.bundleBytes == b.bundleBytes
        fun mapFailure(reason: CacheFailure): SnapshotArchiveFailure = when (reason) {
            CacheFailure.BUSY -> SnapshotArchiveFailure.BUSY
            CacheFailure.IO_FAILURE -> SnapshotArchiveFailure.IO_FAILURE
            CacheFailure.TOO_LARGE, CacheFailure.RETENTION_LIMIT -> SnapshotArchiveFailure.LIMIT_EXCEEDED
            CacheFailure.HASH_MISMATCH, CacheFailure.CORRUPT -> SnapshotArchiveFailure.CORRUPT
            CacheFailure.CANCELLED -> SnapshotArchiveFailure.CANCELLED
        }
    }
}
