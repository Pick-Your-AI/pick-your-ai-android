package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewModelInput
import ai.pickyour.core.preview.PreviewModelSettings

/** Version 4 is private typed MODEL input, never a Selection envelope or a replacement save token. */
internal class ModelFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewModelInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.temperature, input.topP, input.maxOutputTokens, input.precision,
            input.servingMode, input.credentialRef, input.weightsUri).sumOf { it?.length?.toLong() ?: 0L }
        fun allowed(key: String, value: String?) = value == null ||
            value in PreviewModelSettings.fields.single { it.key == key }.enumValues
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS &&
            allowed(PreviewModelSettings.PRECISION, input.precision) &&
            allowed(PreviewModelSettings.SERVING_MODE, input.servingMode)
    }

    fun recovery(): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        PreviewModelSettings.hasRecognizedWeightsUserinfo(input.weightsUri) -> ComponentFormRecovery.UriUserinfoExcluded
        !PreviewModelSettings.canRetainCredentialReference(input.credentialRef) -> ComponentFormRecovery.Unavailable
        else -> ComponentFormRecovery.TypedModelPending(this)
    }

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedModelPending) return null
        return Bundle().apply {
            putInt("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewModelSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("temperature", input.temperature)
            putString("topP", input.topP)
            putString("maxOutputTokens", input.maxOutputTokens)
            putString("precision", input.precision)
            putString("servingMode", input.servingMode)
            putString("credentialRef", input.credentialRef)
            putString("weightsUri", input.weightsUri)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "ModelFormCheckpoint"

    companion object {
        const val VERSION = 4
        private const val FORM = "configure_model"
        private val INPUT_KEYS = listOf("temperature", "topP", "maxOutputTokens", "precision", "servingMode", "credentialRef", "weightsUri")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): ModelFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewModelSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return ModelFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewModelInput(raw[0] as String?, raw[1] as String?, raw[2] as String?, raw[3] as String?,
                    raw[4] as String?, raw[5] as String?, raw[6] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedModelPending }
        }
    }
}
