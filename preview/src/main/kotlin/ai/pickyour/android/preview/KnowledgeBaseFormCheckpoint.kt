package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewKnowledgeBaseInput
import ai.pickyour.core.preview.PreviewKnowledgeBaseSettings

/** A private, independently versioned input record; it contains neither a command nor save authority. */
internal class KnowledgeBaseFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewKnowledgeBaseInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean = draftId.length in 1..128 && baseline.length in 1..128 &&
        selectionId.length in 1..128 && catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
        (input.chunkSizeTokens?.length ?: 0).toLong() + (input.chunkOverlapTokens?.length ?: 0) +
            (input.refreshMinutes?.length ?: 0) <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedKnowledgeBasePending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("form", FORM)
        putString("kind", PreviewKnowledgeBaseSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("chunkSizeTokens", input.chunkSizeTokens)
        putString("chunkOverlapTokens", input.chunkOverlapTokens)
        putString("refreshMinutes", input.refreshMinutes)
        putBoolean("aclSupplied", input.aclEnforced != null)
        putBoolean("aclEnforced", input.aclEnforced ?: false)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "KnowledgeBaseFormCheckpoint"

    companion object {
        const val VERSION = "typed-knowledge-base/1"
        private const val FORM = "configure_knowledge_base"
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "chunkSizeTokens", "chunkOverlapTokens", "refreshMinutes",
            "aclSupplied", "aclEnforced", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads also work at the API 26 minimum.
        fun read(value: Bundle): KnowledgeBaseFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewKnowledgeBaseSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val size = value.get("chunkSizeTokens")
            val overlap = value.get("chunkOverlapTokens")
            val refresh = value.get("refreshMinutes")
            if (size != null && size !is String || overlap != null && overlap !is String ||
                refresh != null && refresh !is String) return null
            val aclSupplied = value.get("aclSupplied") as? Boolean ?: return null
            val acl = value.get("aclEnforced") as? Boolean ?: return null
            if (!aclSupplied && acl) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return KnowledgeBaseFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewKnowledgeBaseInput(size as String?, overlap as String?, refresh as String?,
                    if (aclSupplied) acl else null), saving).takeIf { it.bounded() }
        }
    }
}
