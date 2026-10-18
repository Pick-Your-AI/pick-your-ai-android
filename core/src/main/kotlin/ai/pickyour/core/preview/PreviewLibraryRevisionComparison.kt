package ai.pickyour.core.preview

import ai.pickyour.core.drafts.StoredRevision
import java.nio.file.Path

/**
 * One explicit retained revision from a captured library entry. This holds immutable content and
 * metadata, never a DraftVersion, persistence adapter or authority to select or save a draft.
 */
class PreviewLibraryRevision private constructor(
    val draftId: PreviewDraftId,
    private val retained: StoredRevision,
    val fields: PreviewDraftFields?,
    val capturedBaselineIdentity: String,
    val earlierHistoryUnavailable: Boolean,
    private val libraryRoot: Path,
) {
    val revision: Long get() = retained.number

    /** Pure: neither source is reread, activated, substituted with its head or matched by title. */
    fun compareWith(after: PreviewLibraryRevision): PreviewLibraryComparison? {
        if (libraryRoot != after.libraryRoot) return null
        return PreviewLibraryComparison(this, after,
            compareRetainedRevisions(retained, after.retained, fields, after.fields))
    }

    companion object {
        /** Unavailable/deleting entries, a mismatched directory ID or a missing revision fail closed. */
        fun capture(entry: PreviewLibraryEntry, revision: Long): PreviewLibraryRevision? {
            if (entry.deletionPending) return null
            val record = (entry.state as? PreviewDraftLoad.Available)?.record ?: return null
            val store = record.draft.version.store
            if (store.fileName.toString() != entry.id.value) return null
            val retained = record.draft.history.find { it.number == revision } ?: return null
            val fields = record.history.find { it.revision == revision }?.fields
            return PreviewLibraryRevision(entry.id, retained, fields, record.baselineIdentity,
                record.earlierHistoryUnavailable, store.parent)
        }
    }

    override fun toString() = "PreviewLibraryRevision"
}

/** The two local directory identities remain explicit even when their revision counters are equal. */
class PreviewLibraryComparison internal constructor(
    val before: PreviewLibraryRevision,
    val after: PreviewLibraryRevision,
    val comparison: PreviewRevisionComparison,
) {
    override fun toString() = "PreviewLibraryComparison"
}
