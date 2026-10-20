package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewTrainingComputeInput
import ai.pickyour.core.preview.PreviewTrainingComputeSettings

/** Private typed input and captured identities only; this checkpoint grants no write or retry authority. */
internal class TrainingComputeFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewTrainingComputeInput,
    val wasSaving: Boolean,
) {
    private fun bounded() = draftId.length in 1..128 && baseline.length in 1..128 &&
        selectionId.length in 1..128 && catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
        (input.gpuHoursBudget?.length?.toLong() ?: 0L) + (input.checkpointIntervalMinutes?.length ?: 0) <=
            ComponentFormCheckpoint.MAX_RAW_CHARACTERS

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedTrainingComputePending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("kind", PreviewTrainingComputeSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("gpuHoursBudget", input.gpuHoursBudget)
        putString("checkpointIntervalMinutes", input.checkpointIntervalMinutes)
        putBoolean("spotAllowedSupplied", input.spotAllowed != null)
        putBoolean("spotAllowed", input.spotAllowed ?: false)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "TrainingComputeFormCheckpoint"

    companion object {
        const val VERSION = "typed-training-compute/1"
        private val KEYS = setOf("version", "kind", "draftId", "baseline", "selectionId", "catalogItemId",
            "catalogItemVersion", "gpuHoursBudget", "checkpointIntervalMinutes", "spotAllowedSupplied", "spotAllowed", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): TrainingComputeFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION ||
                value.get("kind") != PreviewTrainingComputeSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val budget = value.get("gpuHoursBudget")
            val interval = value.get("checkpointIntervalMinutes")
            if (budget != null && budget !is String || interval != null && interval !is String) return null
            val supplied = value.get("spotAllowedSupplied") as? Boolean ?: return null
            val spotAllowed = value.get("spotAllowed") as? Boolean ?: return null
            if (!supplied && spotAllowed) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return TrainingComputeFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewTrainingComputeInput(budget as String?, interval as String?, spotAllowed.takeIf { supplied }), saving)
                .takeIf { it.bounded() }
        }
    }
}
