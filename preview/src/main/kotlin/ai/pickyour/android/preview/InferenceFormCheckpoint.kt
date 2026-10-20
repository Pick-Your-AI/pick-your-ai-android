package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewInferenceInput
import ai.pickyour.core.preview.PreviewInferenceSettings

/** Version 6 is private typed INFERENCE_ENGINE input, never a Selection envelope or a replacement save token. */
internal class InferenceFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewInferenceInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.tensorParallelSize, input.maxBatchTokens, input.precision, input.quantization)
            .sumOf { it?.length?.toLong() ?: 0L }
        fun allowed(key: String, value: String?) = value == null ||
            value in PreviewInferenceSettings.fields.single { it.key == key }.enumValues
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS &&
            allowed(PreviewInferenceSettings.PRECISION, input.precision) &&
            allowed(PreviewInferenceSettings.QUANTIZATION, input.quantization)
    }

    fun recovery(): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        else -> ComponentFormRecovery.TypedInferencePending(this)
    }

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedInferencePending) return null
        return Bundle().apply {
            putInt("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewInferenceSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("tensorParallelSize", input.tensorParallelSize)
            putString("maxBatchTokens", input.maxBatchTokens)
            putString("precision", input.precision)
            putString("quantization", input.quantization)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "InferenceFormCheckpoint"

    companion object {
        const val VERSION = 6
        private const val FORM = "configure_inference_engine"
        private val INPUT_KEYS = listOf("tensorParallelSize", "maxBatchTokens", "precision", "quantization")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): InferenceFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewInferenceSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return InferenceFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewInferenceInput(raw[0] as String?, raw[1] as String?, raw[2] as String?, raw[3] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedInferencePending }
        }
    }
}
