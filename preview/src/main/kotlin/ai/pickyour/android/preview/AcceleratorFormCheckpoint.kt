package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewAcceleratorInput
import ai.pickyour.core.preview.PreviewAcceleratorSettings

/** Private bounded input and original identities, never a command or replacement save authority. */
internal class AcceleratorFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewAcceleratorInput,
    val wasSaving: Boolean,
) {
    private fun bounded() = draftId.length in 1..128 && baseline.length in 1..128 &&
        selectionId.length in 1..128 && catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
        input.deviceCount.length.toLong() + (input.tensorParallelGroups?.length ?: 0) <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedAcceleratorPending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? {
        if (!bounded()) return null
        return Bundle().apply {
            putString("version", VERSION)
            putString("form", FORM)
            putString("kind", PreviewAcceleratorSettings.KIND)
            putString("draftId", draftId)
            putString("baseline", baseline)
            putString("selectionId", selectionId)
            putString("catalogItemId", catalogItemId)
            putString("catalogItemVersion", catalogItemVersion)
            putString("deviceCount", input.deviceCount)
            putString("tensorParallelGroups", input.tensorParallelGroups)
            putBoolean("wasSaving", wasSaving)
        }
    }

    override fun toString() = "AcceleratorFormCheckpoint"

    companion object {
        // Exact primitive comparison composes with existing integer versions and other string namespaces.
        const val VERSION = "typed-accelerator/1"
        private const val FORM = "accelerator"
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "deviceCount", "tensorParallelGroups", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads retain API 26 compatibility.
        fun read(value: Bundle): AcceleratorFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewAcceleratorSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val devices = value.get("deviceCount") as? String ?: return null
            val groups = value.get("tensorParallelGroups")
            if (groups != null && groups !is String) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return AcceleratorFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewAcceleratorInput(devices, groups as String?), saving).takeIf { it.bounded() }
        }
    }
}
