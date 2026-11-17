package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewEmbeddingModelInput
import ai.pickyour.core.preview.PreviewEmbeddingModelSettings

/** Private typed input and original identity; never a Selection envelope or new save authority. */
internal class EmbeddingModelFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewEmbeddingModelInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.credentialRef, input.outputDimensions, input.region).sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        !PreviewEmbeddingModelSettings.canRetainCredentialReference(input.credentialRef) -> ComponentFormRecovery.Unavailable
        else -> ComponentFormRecovery.TypedEmbeddingModelPending(this)
    }

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedEmbeddingModelPending) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewEmbeddingModelSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("credentialRef", input.credentialRef)
            putString("outputDimensions", input.outputDimensions)
            putString("region", input.region)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "EmbeddingModelFormCheckpoint"

    companion object {
        const val VERSION = "typed-embedding-model/1"
        private const val FORM = "configure_embedding_model"
        private val INPUT_KEYS = listOf("credentialRef", "outputDimensions", "region")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): EmbeddingModelFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewEmbeddingModelSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return EmbeddingModelFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewEmbeddingModelInput(raw[0] as String?, raw[1] as String?, raw[2] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedEmbeddingModelPending }
        }
    }
}
