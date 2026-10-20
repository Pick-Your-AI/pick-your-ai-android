package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewCostPolicyInput
import ai.pickyour.core.preview.PreviewCostPolicySettings

/** Private typed input only. It cannot provide a new baseline, selection or save authority. */
internal class CostPolicyFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewCostPolicyInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.monthlyBudgetAmount, input.perRequestBudgetAmount, input.notifyAtPct)
            .sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery =
        if (bounded()) ComponentFormRecovery.TypedCostPolicyPending(this) else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? {
        if (!bounded()) return null
        return Bundle().apply {
            putInt("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewCostPolicySettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("monthlyBudgetAmount", input.monthlyBudgetAmount)
            putString("perRequestBudgetAmount", input.perRequestBudgetAmount)
            putString("notifyAtPct", input.notifyAtPct)
            // An explicit null entry keeps omission distinct from the Boolean false value.
            val cap = input.enforceHardCap
            if (cap == null) putString("enforceHardCap", null)
            else putBoolean("enforceHardCap", cap)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "CostPolicyFormCheckpoint"

    companion object {
        const val VERSION = 8
        private const val FORM = "configure_cost_policy"
        private val TEXT_KEYS = listOf("monthlyBudgetAmount", "perRequestBudgetAmount", "notifyAtPct")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving", "enforceHardCap") + TEXT_KEYS

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): CostPolicyFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewCostPolicySettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val cap = value.get("enforceHardCap")
            if (cap != null && cap !is Boolean) return null
            val raw = TEXT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return CostPolicyFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewCostPolicyInput(raw[0] as String?, raw[1] as String?, cap as Boolean?, raw[2] as String?), saving)
                .takeIf { it.bounded() }
        }
    }
}
