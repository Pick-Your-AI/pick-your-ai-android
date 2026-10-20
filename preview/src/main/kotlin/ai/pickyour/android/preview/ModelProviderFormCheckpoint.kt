package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewModelProviderInput
import ai.pickyour.core.preview.PreviewModelProviderSettings

/** Private typed provider input and original identity, never a Selection payload or fresh save token. */
internal class ModelProviderFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewModelProviderInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.region, input.credentialRef, input.apiBaseUrl)
            .sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(input.apiBaseUrl) -> ComponentFormRecovery.UriUserinfoExcluded
        !PreviewModelProviderSettings.canRetainCredentialReference(input.credentialRef) -> ComponentFormRecovery.Unavailable
        else -> ComponentFormRecovery.TypedModelProviderPending(this)
    }

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedModelProviderPending) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewModelProviderSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("region", input.region)
            putString("credentialRef", input.credentialRef)
            putString("apiBaseUrl", input.apiBaseUrl)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "ModelProviderFormCheckpoint"

    companion object {
        // A namespaced string avoids collisions with independently added integer checkpoint versions.
        const val VERSION = "typed-model-provider/1"
        private const val FORM = "modelProvider"
        private val INPUT_KEYS = listOf("region", "credentialRef", "apiBaseUrl")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): ModelProviderFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewModelProviderSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return ModelProviderFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewModelProviderInput(raw[0] as String?, raw[1] as String?, raw[2] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedModelProviderPending }
        }
    }
}
