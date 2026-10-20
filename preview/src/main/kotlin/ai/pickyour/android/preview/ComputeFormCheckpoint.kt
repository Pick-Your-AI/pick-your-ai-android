package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewComputeInput
import ai.pickyour.core.preview.PreviewComputeSettings

/** Version 5 retains private compute input and its original identity, never a command or save token. */
internal class ComputeFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewComputeInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean = draftId.length in 1..128 && baseline.length in 1..128 &&
        selectionId.length in 1..128 && catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
        input.instances.length.toLong() + (input.reservedHoursPerMonth?.length ?: 0) + (input.region?.length ?: 0) <=
            ComponentFormCheckpoint.MAX_RAW_CHARACTERS

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedComputePending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putInt("version", VERSION)
        putString("form", FORM)
        putString("kind", PreviewComputeSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("instances", input.instances)
        putString("reservedHoursPerMonth", input.reservedHoursPerMonth)
        putString("region", input.region)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "ComputeFormCheckpoint"

    companion object {
        const val VERSION = 5
        private const val FORM = "configure_compute_profile"
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "instances", "reservedHoursPerMonth", "region", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads also work at the API 26 minimum.
        fun read(value: Bundle): ComputeFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewComputeSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val instances = value.get("instances") as? String ?: return null
            val hours = value.get("reservedHoursPerMonth")
            val region = value.get("region")
            if (hours != null && hours !is String || region != null && region !is String) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return ComputeFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewComputeInput(instances, hours as String?, region as String?), saving).takeIf { it.bounded() }
        }
    }
}
