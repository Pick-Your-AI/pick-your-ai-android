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
class PreviewStoredDraft internal constructor(internal val draft: StoredDraft, val fields: PreviewDraftFields) {
    val revision: Long get() = draft.version.number
    val baselineIdentity: String get() = "${draft.version.number}:${draft.version.hash.hex}"
    /** Retained guest forms only; unavailable metadata is never reconstructed or replaced. */
    val history: List<PreviewDraftRevision> get() = java.util.Collections.unmodifiableList(draft.history.map {
        PreviewDraftRevision(it.number, decodePreviewFields(it.metadata))
    })
    val earlierHistoryUnavailable: Boolean get() = draft.earlierHistoryUnavailable
    override fun toString() = "PreviewStoredDraft"
}
/** This is guest form history, not a domain snapshot or an evaluation result. */
class PreviewDraftRevision internal constructor(val revision: Long, val fields: PreviewDraftFields?) {
    override fun toString() = "PreviewDraftRevision"
}

private fun decodePreviewFields(metadata: Map<String, String>): PreviewDraftFields? {
    if (metadata["preview.format"] != "1") return null
    val title = metadata["preview.title"] ?: return null
    return try { PreviewDraftFields(title, metadata["preview.selectedId"], metadata["preview.selectedVersion"], metadata["preview.catalogHash"]) }
    catch (_: IllegalArgumentException) { null }
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

/**
 * Synchronous adapter over the canonical durable store. Call off the UI thread.
 * Private preview metadata retains opaque catalog selection IDs; no catalog facts are invented.
 * Existing unknown metadata and all domain content are preserved. Unknown preview format blocks edits.
 * The caller must keep pending fields on conflict/failure and never silently retry with a newer token.
 */
class PreviewDraftPersistence(private val store: LocalDraftStore) {
    fun load(): PreviewDraftLoad = when (val result = store.load()) {
        DraftLoad.Missing -> PreviewDraftLoad.Missing
        is DraftLoad.Failed -> PreviewDraftLoad.Failed(result.reason)
        is DraftLoad.Available -> decode(result.draft)
    }

    fun save(expected: PreviewStoredDraft?, fields: PreviewDraftFields): PreviewDraftSave {
        val metadata = (expected?.draft?.metadata ?: emptyMap()).toMutableMap()
        metadata[FORMAT] = "1"
        metadata[TITLE] = fields.title
        if (fields.selectedId == null) metadata.remove(SELECTION) else metadata[SELECTION] = fields.selectedId
        if (fields.selectedVersion == null) metadata.remove(VERSION) else metadata[VERSION] = fields.selectedVersion
        if (fields.catalogHash == null) metadata.remove(CATALOG) else metadata[CATALOG] = fields.catalogHash
        return when (val result = store.save(expected?.draft?.version, expected?.draft?.content ?: DraftContent(), metadata)) {
            is DraftSave.Saved -> PreviewDraftSave.Saved(PreviewStoredDraft(result.draft, fields))
            DraftSave.Conflict -> PreviewDraftSave.Conflict
            is DraftSave.Failed -> PreviewDraftSave.Failed(result.reason)
            is DraftSave.Uncertain -> PreviewDraftSave.Uncertain
            DraftSave.UnknownRevision -> PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT)
        }
    }

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

/** Lifecycle state is not a save acknowledgement. A changed baseline always needs explicit reopen. */
data class PreviewDraftRestoration(val baselineIdentity: String?, val requiresReopen: Boolean) {
    fun canResume(loaded: PreviewStoredDraft?): Boolean =
        !requiresReopen && baselineIdentity == loaded?.baselineIdentity

    companion object {
        fun capture(record: PreviewStoredDraft?, storageReady: Boolean, storageBusy: Boolean) =
            PreviewDraftRestoration(record?.baselineIdentity, !storageReady || storageBusy)
    }
}
