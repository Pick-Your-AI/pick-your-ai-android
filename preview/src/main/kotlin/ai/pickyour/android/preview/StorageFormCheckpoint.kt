package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewStorageInput
import ai.pickyour.core.preview.PreviewStorageSettings

/** Private bounded pending input with original identities; it never supplies current save authority. */
internal class StorageFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewStorageInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.provisionedGb, input.region, input.encryptionKeyRef)
            .sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery = when {
        !bounded() -> ComponentFormRecovery.Unavailable
        !PreviewStorageSettings.canRetainEncryptionKeyReference(input.encryptionKeyRef) -> ComponentFormRecovery.Unavailable
        else -> ComponentFormRecovery.TypedStoragePending(this)
    }

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedStoragePending) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewStorageSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("provisionedGb", input.provisionedGb)
            putString("region", input.region)
            putString("encryptionKeyRef", input.encryptionKeyRef)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "StorageFormCheckpoint"

    companion object {
        const val VERSION = "typed-storage/1"
        private const val FORM = "storage"
        private val INPUT_KEYS = listOf("provisionedGb", "region", "encryptionKeyRef")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads retain the API 26 minimum.
        fun read(value: Bundle): StorageFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewStorageSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return StorageFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewStorageInput(raw[0] as String?, raw[1] as String?, raw[2] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedStoragePending }
        }
    }
}
