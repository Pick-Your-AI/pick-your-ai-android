package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import java.math.BigInteger
import java.nio.file.Path
import java.util.Collections

/** Exact Build ID and mathematical revision; raw spellings/bytes remain separately retained. */
data class SnapshotArchiveKey(val id: String, val revision: BigInteger) {
    init { require(id.isNotEmpty() && id.length <= 16_384 && revision.signum() > 0 && revision.bitLength() <= 426 && revision.toString().length <= 128) { "ARCHIVE_IDENTITY_LIMIT" } }
    override fun toString() = "SnapshotArchiveKey"
}

/** Opaque compare-and-set authority for one private archive index, never a live draft token. */
class SnapshotArchiveVersion internal constructor(internal val root: Path, internal val namespace: String, val generation: Int, val hash: ArtifactHash) {
    override fun toString() = "SnapshotArchiveVersion"
}

/** Index receipt only. Call read/readAt to verify the retained bundle before consuming it. */
class SnapshotArchiveEntry internal constructor(val key: SnapshotArchiveKey, val bundleHash: ArtifactHash, val bundleBytes: Int) {
    override fun toString() = "SnapshotArchiveEntry"
}
class SnapshotArchiveState internal constructor(val version: SnapshotArchiveVersion, entries: List<SnapshotArchiveEntry>) {
    val entries: List<SnapshotArchiveEntry> = archiveList(entries)
    override fun toString() = "SnapshotArchiveState"
}

enum class SnapshotArchiveFailure { BUSY, IO_FAILURE, CORRUPT, UNSUPPORTED_VERSION, LIMIT_EXCEEDED, CANCELLED }
sealed interface SnapshotArchiveLoad {
    class Available(val state: SnapshotArchiveState) : SnapshotArchiveLoad
    data class Failed(val reason: SnapshotArchiveFailure) : SnapshotArchiveLoad
}
sealed interface SnapshotArchiveRead {
    data object Missing : SnapshotArchiveRead
    class Available(val snapshot: RetainedDraftSnapshot) : SnapshotArchiveRead
    data class Failed(val reason: SnapshotArchiveFailure) : SnapshotArchiveRead
}

/**
 * Independently retained private inputs, without DraftVersion/store authority. Archived means bytes
 * were retained; no current/historical evaluation, compatibility, rights or execution is inferred.
 * Full source bytes/history include retained-only private data and are not an export/share format.
 */
class RetainedDraftSnapshot internal constructor(
    val entry: SnapshotArchiveEntry,
    val identity: DraftSnapshotIdentity,
    source: DecodedEnvelope,
    retainedPaths: List<String>,
    artifacts: List<CheckedProjectionArtifact>,
    buildBytes: ByteArray,
    sourceBytes: ByteArray,
) {
    val profileVersion: Int = 1
    val sourceFormatVersion: Int = source.format
    val sourceHistory: List<StoredRevision> = archiveList(source.history)
    val sourceContent: DraftContent get() = sourceHistory.last().content
    val sourceMetadata: Map<String, String> get() = sourceHistory.last().metadata
    /** Historical provenance only. Its predecessor serialization is not a retained bundle member. */
    val sourceLegacyHash: ArtifactHash? = source.legacySourceHash
    val retainedOnlyPaths: List<String> = archiveList(retainedPaths)
    val pins: List<ProjectionArtifactPin> = archiveList(artifacts.map { it.pin })
    private val build = buildBytes.copyOf()
    private val retainedSource = sourceBytes.copyOf()
    private val retainedArtifacts = artifacts.associate { it.pin.kind to it.bytes.copyOf() }
    fun bytes(): ByteArray = build.copyOf()
    fun sourceBytes(): ByteArray = retainedSource.copyOf()
    fun artifactBytes(kind: ProjectionArtifactKind): ByteArray? = retainedArtifacts[kind]?.copyOf()
    override fun toString() = "RetainedDraftSnapshot"
}

sealed interface DraftSnapshotArchival {
    /** Acknowledged only after archive, source and (when managed) library transactions finish. */
    class Archived(val entry: SnapshotArchiveEntry, val version: SnapshotArchiveVersion, val alreadyPresent: Boolean) : DraftSnapshotArchival
    data object SourceConflict : DraftSnapshotArchival
    data object ReviewAcknowledgmentMismatch : DraftSnapshotArchival
    data object ArchiveConflict : DraftSnapshotArchival
    data object IdentityConflict : DraftSnapshotArchival
    data class SourceUnavailable(val reason: DraftStorageFailure) : DraftSnapshotArchival
    data class ArchiveUnavailable(val reason: SnapshotArchiveFailure) : DraftSnapshotArchival
    /** No index activation was attempted. Inspect this exact hash for a retained complete orphan. */
    class NotPublished(val entry: SnapshotArchiveEntry, val reason: SnapshotArchiveFailure) : DraftSnapshotArchival
    /** Publication may have happened. Reopen the index and verify this exact bundle before retry. */
    class Uncertain(val entry: SnapshotArchiveEntry) : DraftSnapshotArchival
}

internal fun <T> archiveList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
