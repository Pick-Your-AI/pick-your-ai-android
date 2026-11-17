package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewDataSourceInput
import ai.pickyour.core.preview.PreviewDataSourceSettings

/** Private typed input and captured identities only; this checkpoint grants no write or retry authority. */
internal class DataSourceFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewDataSourceInput,
    val wasSaving: Boolean,
) {
    private fun bounded() = draftId.length in 1..128 && baseline.length in 1..128 &&
        selectionId.length in 1..128 && catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
        (input.connectorSecretRef?.length?.toLong() ?: 0L) + (input.refreshMinutes?.length ?: 0) <=
            ComponentFormCheckpoint.MAX_RAW_CHARACTERS && PreviewDataSourceSettings.canRetainConnectorReference(input.connectorSecretRef)

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedDataSourcePending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("kind", PreviewDataSourceSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("connectorSecretRef", input.connectorSecretRef)
        putString("refreshMinutes", input.refreshMinutes)
        putBoolean("enabledSupplied", input.enabled != null)
        putBoolean("enabled", input.enabled ?: false)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "DataSourceFormCheckpoint"

    companion object {
        const val VERSION = "typed-data-source/1"
        private val KEYS = setOf("version", "kind", "draftId", "baseline", "selectionId", "catalogItemId",
            "catalogItemVersion", "connectorSecretRef", "refreshMinutes", "enabledSupplied", "enabled", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): DataSourceFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION ||
                value.get("kind") != PreviewDataSourceSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val reference = value.get("connectorSecretRef")
            val minutes = value.get("refreshMinutes")
            if (reference != null && reference !is String || minutes != null && minutes !is String) return null
            val supplied = value.get("enabledSupplied") as? Boolean ?: return null
            val enabled = value.get("enabled") as? Boolean ?: return null
            if (!supplied && enabled) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return DataSourceFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewDataSourceInput(reference as String?, minutes as String?, enabled.takeIf { supplied }), saving)
                .takeIf { it.bounded() }
        }
    }
}
