package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewWorkloadSelectionInput
import ai.pickyour.core.preview.PreviewWorkloadSelectionSettings

/** Private per-selection input. It cannot supply a new baseline, selected item or save authority. */
internal class WorkloadSelectionFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewWorkloadSelectionInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.concurrency, input.requestsPerDay, input.averageInputTokens,
            input.averageOutputTokens, input.latencyBudgetMs).sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery =
        if (bounded()) ComponentFormRecovery.TypedWorkloadSelectionPending(this) else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? {
        if (!bounded()) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewWorkloadSelectionSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("concurrency", input.concurrency)
            putString("requestsPerDay", input.requestsPerDay)
            putString("averageInputTokens", input.averageInputTokens)
            putString("averageOutputTokens", input.averageOutputTokens)
            putString("latencyBudgetMs", input.latencyBudgetMs)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "WorkloadSelectionFormCheckpoint"

    companion object {
        const val VERSION = "typed-workload/1"
        private const val FORM = "configure_workload"
        private val TEXT_KEYS = listOf("concurrency", "requestsPerDay", "averageInputTokens", "averageOutputTokens", "latencyBudgetMs")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + TEXT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): WorkloadSelectionFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewWorkloadSelectionSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = TEXT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return WorkloadSelectionFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewWorkloadSelectionInput(raw[0] as String?, raw[1] as String?, raw[2] as String?, raw[3] as String?, raw[4] as String?), saving)
                .takeIf { it.bounded() }
        }
    }
}
