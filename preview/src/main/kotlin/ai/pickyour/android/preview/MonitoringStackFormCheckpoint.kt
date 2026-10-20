package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewMonitoringStackInput
import ai.pickyour.core.preview.PreviewMonitoringStackSettings

/** Private input plus original identity; never authority to save or resolve a reference. */
internal class MonitoringStackFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewMonitoringStackInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.sampleRate, input.retentionDays, input.credentialRef)
            .sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery =
        if (bounded() && PreviewMonitoringStackSettings.canRetainCredentialReference(input.credentialRef))
            ComponentFormRecovery.TypedMonitoringStackPending(this) else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? {
        if (recovery() !is ComponentFormRecovery.TypedMonitoringStackPending) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewMonitoringStackSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("sampleRate", input.sampleRate)
            putString("retentionDays", input.retentionDays)
            putString("credentialRef", input.credentialRef)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "MonitoringStackFormCheckpoint"

    companion object {
        const val VERSION = "typed-monitoring-stack/1"
        private const val FORM = "configure_monitoring_stack"
        private val TEXT_KEYS = listOf("sampleRate", "retentionDays", "credentialRef")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + TEXT_KEYS

        @Suppress("DEPRECATION")
        fun read(value: Bundle): MonitoringStackFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewMonitoringStackSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = TEXT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return MonitoringStackFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewMonitoringStackInput(raw[0] as String?, raw[1] as String?, raw[2] as String?), saving)
                .takeIf { it.recovery() is ComponentFormRecovery.TypedMonitoringStackPending }
        }
    }
}
