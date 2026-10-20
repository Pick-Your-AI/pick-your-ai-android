package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewRagPipelineInput
import ai.pickyour.core.preview.PreviewRagPipelineSettings

/** Private typed RAG input and original identity, never a command or replacement save token. */
internal class RagPipelineFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewRagPipelineInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = (input.topK?.length?.toLong() ?: 0L) + (input.maxChunks?.length?.toLong() ?: 0L)
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedRagPipelinePending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("form", FORM)
        putString("kind", PreviewRagPipelineSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("topK", input.topK)
        putString("maxChunks", input.maxChunks)
        putBoolean("rerankSupplied", input.rerankEnabled != null)
        putBoolean("rerankEnabled", input.rerankEnabled ?: false)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "RagPipelineFormCheckpoint"

    companion object {
        const val VERSION = "typed-rag-pipeline/1"
        private const val FORM = "configure_rag_pipeline"
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "topK", "maxChunks", "rerankSupplied", "rerankEnabled", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): RagPipelineFormCheckpoint? {
            if (value.keySet() != KEYS || (value.get("version") as? String) != VERSION ||
                (value.get("form") as? String) != FORM || (value.get("kind") as? String) != PreviewRagPipelineSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val topK = value.get("topK")
            val maxChunks = value.get("maxChunks")
            if (topK != null && topK !is String || maxChunks != null && maxChunks !is String) return null
            val supplied = value.get("rerankSupplied") as? Boolean ?: return null
            val rerank = value.get("rerankEnabled") as? Boolean ?: return null
            if (!supplied && rerank) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return RagPipelineFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewRagPipelineInput(topK as String?, maxChunks as String?, if (supplied) rerank else null), saving)
                .takeIf { it.bounded() }
        }
    }
}
