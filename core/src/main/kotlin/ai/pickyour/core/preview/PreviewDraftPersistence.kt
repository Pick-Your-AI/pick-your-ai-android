package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*

/** One bounded guest-preview form, not a BuildSnapshot or a validated model selection. */
data class PreviewDraftFields(val title: String = "", val selectedId: String? = null,
    val selectedVersion: String? = null, val catalogHash: String? = null) {
    init {
        require(title.length <= 120)
        require(selectedId == null || (selectedId.isNotBlank() && selectedId.length <= 256))
        require(selectedVersion == null || (selectedVersion.isNotBlank() && selectedVersion.length <= 256))
        require(catalogHash == null || Regex("[0-9a-f]{64}").matches(catalogHash))
        require(selectedId != null || (selectedVersion == null && catalogHash == null))
    }
    override fun toString() = "PreviewDraftFields"
}
/** Null is unanswered; an entered empty string remains a distinct local edit. */
data class PreviewDraftContext(val objective: String? = null, val context: String? = null) {
    init { require(objective == null || objective.length <= 16_384); require(context == null || context.length <= 16_384) }
    override fun toString() = "PreviewDraftContext"
}
/** Presence is separate from nullable input values, including older saved-state bundles. */
class PreviewContextCheckpoint(val hasInputs: Boolean, val inputs: PreviewDraftContext) {
    fun forLoad(preservePending: Boolean, baseline: PreviewDraftContext): PreviewDraftContext =
        if (preservePending && hasInputs) inputs else baseline
}
class PreviewStoredDraft internal constructor(internal val draft: StoredDraft, val fields: PreviewDraftFields) {
    val components: DraftSelections get() = draft.content.components
    val workloadInput: DraftWorkload get() = draft.content.workload
    val contextInput: PreviewDraftContext get() = PreviewDraftContext(draft.content.objective, draft.content.context)
    val optionalTiers: Map<OptionalTier, TierDraft> get() = draft.content.optional
    val revision: Long get() = draft.version.number
    val baselineIdentity: String get() = "${draft.version.number}:${draft.version.hash.hex}"
    /** Retained guest forms only; unavailable metadata is never reconstructed or replaced. */
    val history: List<PreviewDraftRevision> get() = java.util.Collections.unmodifiableList(draft.history.map {
        PreviewDraftRevision(it.number, decodePreviewFields(it.metadata), it.content, it.change, it.restoredFrom, it.selectionChange)
    })
    val earlierHistoryUnavailable: Boolean get() = draft.earlierHistoryUnavailable
    override fun toString() = "PreviewStoredDraft"
}
/** Retained local content and guest metadata, never an evaluation or price result. */
class PreviewDraftRevision internal constructor(val revision: Long, val fields: PreviewDraftFields?,
    val content: DraftContent, val change: StoredRevisionChange, val restoredFrom: Long?, val selectionChange: SelectionChange? = null) {
    override fun toString() = "PreviewDraftRevision"
}

private fun decodePreviewFields(metadata: Map<String, String>): PreviewDraftFields? {
    if (metadata["preview.format"] != "1") return null
    val title = metadata["preview.title"] ?: return null
    return try { PreviewDraftFields(title, metadata["preview.selectedId"], metadata["preview.selectedVersion"], metadata["preview.catalogHash"]) }
    catch (_: IllegalArgumentException) { null }
}

/** Immutable, validated pending inputs; raw invalid text cannot enter a recovery transaction. */
class PreviewPendingDraft(val fields: PreviewDraftFields, val context: PreviewDraftContext, val workload: DraftWorkload) {
    override fun toString() = "PreviewPendingDraft"
}

sealed interface PreviewDraftLoad {
    data object Missing : PreviewDraftLoad
    class Available(val record: PreviewStoredDraft) : PreviewDraftLoad
    data class Failed(val reason: DraftStorageFailure) : PreviewDraftLoad
}
sealed interface PreviewDraftSave {
    class Saved(val record: PreviewStoredDraft) : PreviewDraftSave
    data object Conflict : PreviewDraftSave
    data class Failed(val reason: DraftStorageFailure) : PreviewDraftSave
    /** Must reopen before retrying; never show an acknowledged saved state from this outcome. */
    data object Uncertain : PreviewDraftSave
}

/** A command result acknowledges only local persistence, never compatibility or pricing. */
sealed interface PreviewSelectionSave {
    class Saved(val record: PreviewStoredDraft, val change: SelectionChange?) : PreviewSelectionSave
    data object Conflict : PreviewSelectionSave
    data class Rejected(val reason: SelectionRejection) : PreviewSelectionSave
    data class Failed(val reason: DraftStorageFailure) : PreviewSelectionSave
    /** Reopen before another command; a dispatched write may already be durable. */
    data object Uncertain : PreviewSelectionSave
}

/**
 * Synchronous adapter over the canonical durable store. Call off the UI thread.
 * Private preview metadata retains opaque catalog selection IDs; no catalog facts are invented.
 * Existing unknown metadata and all domain content are preserved. Unknown preview format blocks edits.
 * The caller must keep pending fields on conflict/failure and never silently retry with a newer token.
 */
class PreviewDraftPersistence internal constructor(private val store: LocalDraftStore,
    private val access: PreviewDraftAccess?) {
    constructor(store: LocalDraftStore) : this(store, null)

    fun load(): PreviewDraftLoad = guarded({ PreviewDraftLoad.Failed(it) }) { when (val result = store.load()) {
        DraftLoad.Missing -> PreviewDraftLoad.Missing
        is DraftLoad.Failed -> PreviewDraftLoad.Failed(result.reason)
        is DraftLoad.Available -> decode(result.draft)
    } }

    /** Local candidate review only. Library liveness and the original captured store token apply. */
    fun prepareSnapshotCandidate(expected: PreviewStoredDraft, inputs: DraftSnapshotInputs): DraftProjectionPreparation =
        guarded({ DraftProjectionPreparation.Failed(it) }) { store.prepareSnapshotCandidate(expected.draft.version, inputs) }

    /** No export or saved feedback: Created is a reviewed in-memory candidate, with exact inputs. */
    fun createSnapshotCandidate(review: DraftProjectionPreparation.Review, acknowledgment: DraftProjectionAcknowledgment?): DraftProjectionCreation =
        guarded({ DraftProjectionCreation.Failed(it) }) { store.createSnapshotCandidate(review, acknowledgment) }

    fun save(expected: PreviewStoredDraft?, fields: PreviewDraftFields, contextInput: PreviewDraftContext? = null,
        workloadInput: DraftWorkload? = null): PreviewDraftSave = write {
        val previous = expected?.draft?.content ?: DraftContent()
        val content = DraftContent(
            if (contextInput == null) previous.objective else contextInput.objective,
            if (contextInput == null) previous.context else contextInput.context,
            previous.primaryModel, previous.optional, workloadInput ?: previous.workload, previous.components)
        val metadata = previewMetadata(expected?.draft?.metadata.orEmpty(), fields)
        when (val result = store.save(expected?.draft?.version, content, metadata)) {
            is DraftSave.Saved -> PreviewDraftSave.Saved(PreviewStoredDraft(result.draft, fields))
            DraftSave.Conflict -> PreviewDraftSave.Conflict
            is DraftSave.Failed -> PreviewDraftSave.Failed(result.reason)
            is DraftSave.Uncertain -> PreviewDraftSave.Uncertain
            DraftSave.UnknownRevision -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
        }
    }

    /** Original baseline authority and library liveness stay locked across the canonical command. */
    fun applySelection(expected: PreviewStoredDraft, command: SelectionCommand,
        catalog: SelectionCatalogSnapshot? = null): PreviewSelectionSave {
        var started = false
        return guarded({ if (started) PreviewSelectionSave.Uncertain else PreviewSelectionSave.Failed(it) }) {
            started = true
            when (val result = store.applySelection(DraftSelectionIntent(expected.draft.version, command), catalog)) {
                is DraftSelectionSave.Saved -> when (val decoded = decode(result.draft)) {
                    is PreviewDraftLoad.Available -> PreviewSelectionSave.Saved(decoded.record, result.change)
                    is PreviewDraftLoad.Failed -> PreviewSelectionSave.Failed(decoded.reason)
                    PreviewDraftLoad.Missing -> PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT)
                }
                DraftSelectionSave.Conflict -> PreviewSelectionSave.Conflict
                is DraftSelectionSave.Rejected -> PreviewSelectionSave.Rejected(result.reason)
                is DraftSelectionSave.Failed -> PreviewSelectionSave.Failed(result.reason)
                is DraftSelectionSave.Uncertain -> PreviewSelectionSave.Uncertain
            }
        }
    }

    /** Change only one existing optional activation; retain all selections/settings and metadata. */
    fun setActivation(expected: PreviewStoredDraft, tier: OptionalTier, activation: Activation): PreviewDraftSave = write {
        val current = expected.draft.content
        val content = current.withTier(tier, current.optional.getValue(tier).withActivation(activation))
        when (val result = store.save(expected.draft.version, content, expected.draft.metadata)) {
            is DraftSave.Saved -> PreviewDraftSave.Saved(PreviewStoredDraft(result.draft, expected.fields))
            DraftSave.Conflict -> PreviewDraftSave.Conflict
            is DraftSave.Failed -> PreviewDraftSave.Failed(result.reason)
            is DraftSave.Uncertain -> PreviewDraftSave.Uncertain
            DraftSave.UnknownRevision -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
        }
    }

    /** Explicit domain restore; current guest metadata is retained by the canonical store. */
    fun restore(expected: PreviewStoredDraft, revision: Long): PreviewDraftSave = write {
        when (val result = store.restore(expected.draft.version, revision)) {
            is DraftSave.Saved -> when (val decoded = decode(result.draft)) {
                is PreviewDraftLoad.Available -> PreviewDraftSave.Saved(decoded.record)
                is PreviewDraftLoad.Failed -> PreviewDraftSave.Failed(decoded.reason)
                PreviewDraftLoad.Missing -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
            }
            DraftSave.Conflict -> PreviewDraftSave.Conflict
            is DraftSave.Failed -> PreviewDraftSave.Failed(result.reason)
            is DraftSave.Uncertain -> PreviewDraftSave.Uncertain
            DraftSave.UnknownRevision -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
        }
    }

    /** New library identity; copy content/metadata, never source history or its save authority. */
    internal fun copySavedSnapshot(source: PreviewStoredDraft): PreviewDraftSave = write {
        when (val result = store.save(null, source.draft.content, source.draft.metadata + mapOf(
            "preview.duplicateSourceId" to source.draft.version.store.fileName.toString(),
            "preview.duplicateSourceRevision" to source.revision.toString(),
            "preview.duplicateSourceHash" to source.draft.version.hash.hex,
        ))) {
            is DraftSave.Saved -> PreviewDraftSave.Saved(PreviewStoredDraft(result.draft, source.fields))
            DraftSave.Conflict -> PreviewDraftSave.Conflict
            is DraftSave.Failed -> PreviewDraftSave.Failed(result.reason)
            is DraftSave.Uncertain -> PreviewDraftSave.Uncertain
            DraftSave.UnknownRevision -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
        }
    }

    /** Explicit recovery copy: new identity and save authority, never a retry against the source. */
    internal fun copyPendingSnapshot(source: PreviewStoredDraft, pending: PreviewPendingDraft): PreviewDraftSave = write {
        val previous = source.draft.content
        val content = DraftContent(pending.context.objective, pending.context.context, previous.primaryModel,
            previous.optional, pending.workload, previous.components)
        val metadata = previewMetadata(source.draft.metadata, pending.fields) + mapOf(
            "preview.recoverySourceId" to source.draft.version.store.fileName.toString(),
            "preview.recoverySourceRevision" to source.revision.toString(),
            "preview.recoverySourceHash" to source.draft.version.hash.hex,
        )
        when (val result = store.save(null, content, metadata)) {
            is DraftSave.Saved -> PreviewDraftSave.Saved(PreviewStoredDraft(result.draft, pending.fields))
            DraftSave.Conflict -> PreviewDraftSave.Conflict
            is DraftSave.Failed -> PreviewDraftSave.Failed(result.reason)
            is DraftSave.Uncertain -> PreviewDraftSave.Uncertain
            DraftSave.UnknownRevision -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
        }
    }

    private fun previewMetadata(previous: Map<String, String>, fields: PreviewDraftFields): Map<String, String> {
        val metadata = previous.toMutableMap()
        metadata[FORMAT] = "1"
        metadata[TITLE] = fields.title
        if (fields.selectedId == null) metadata.remove(SELECTION) else metadata[SELECTION] = fields.selectedId
        if (fields.selectedVersion == null) metadata.remove(VERSION) else metadata[VERSION] = fields.selectedVersion
        if (fields.catalogHash == null) metadata.remove(CATALOG) else metadata[CATALOG] = fields.catalogHash
        return metadata
    }

    private fun write(action: () -> PreviewDraftSave): PreviewDraftSave {
        var started = false
        return guarded({ if (started) PreviewDraftSave.Uncertain else PreviewDraftSave.Failed(it) }) {
            // The outer library lock may fail to close after the store has acknowledged a save.
            started = true
            action()
        }
    }

    private fun <T> guarded(failed: (DraftStorageFailure) -> T, action: () -> T): T =
        if (access == null) action() else access.run(failed, action)

    private fun decode(draft: StoredDraft): PreviewDraftLoad {
        if (draft.metadata[FORMAT] != "1") return PreviewDraftLoad.Failed(DraftStorageFailure.UNSUPPORTED_VERSION)
        val title = draft.metadata[TITLE] ?: return PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT)
        return try { PreviewDraftLoad.Available(PreviewStoredDraft(draft, PreviewDraftFields(title, draft.metadata[SELECTION], draft.metadata[VERSION], draft.metadata[CATALOG]))) }
        catch (_: IllegalArgumentException) { PreviewDraftLoad.Failed(DraftStorageFailure.CORRUPT) }
    }
    private companion object {
        const val FORMAT = "preview.format"
        const val TITLE = "preview.title"
        const val SELECTION = "preview.selectedId"
        const val VERSION = "preview.selectedVersion"
        const val CATALOG = "preview.catalogHash"
    }
}

/** Library-bound adapters recheck identity liveness while holding the library transaction lock. */
internal interface PreviewDraftAccess {
    fun <T> run(failed: (DraftStorageFailure) -> T, action: () -> T): T
}

/** Lifecycle state is not a save acknowledgement. A changed baseline always needs explicit reopen. */
data class PreviewDraftRestoration(val baselineIdentity: String?, val requiresReopen: Boolean) {
    fun canResume(loaded: PreviewStoredDraft?): Boolean =
        !requiresReopen && baselineIdentity == loaded?.baselineIdentity

    companion object {
        fun capture(record: PreviewStoredDraft?, storageReady: Boolean, storageBusy: Boolean) =
            PreviewDraftRestoration(record?.baselineIdentity, !storageReady || storageBusy)
    }
}
