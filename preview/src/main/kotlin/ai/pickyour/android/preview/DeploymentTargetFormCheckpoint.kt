package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewDeploymentTargetInput
import ai.pickyour.core.preview.PreviewDeploymentTargetSettings

/** Private pending input and original identity only; no approval, command or replacement save token. */
internal class DeploymentTargetFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewDeploymentTargetInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = listOf(input.environment, input.replicasMin, input.replicasMax, input.region, input.approvalTicket)
            .sumOf { it?.length?.toLong() ?: 0L }
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS &&
            (input.environment == null || input.environment in PreviewDeploymentTargetSettings.fields
                .single { it.key == PreviewDeploymentTargetSettings.ENVIRONMENT }.enumValues)
    }

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedDeploymentTargetPending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("form", FORM)
        putString("kind", PreviewDeploymentTargetSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("environment", input.environment)
        putString("replicasMin", input.replicasMin)
        putString("replicasMax", input.replicasMax)
        putString("region", input.region)
        putString("approvalTicket", input.approvalTicket)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "DeploymentTargetFormCheckpoint"

    companion object {
        const val VERSION = "typed-deployment-target/1"
        private const val FORM = "deploymentTarget"
        private val INPUT_KEYS = listOf("environment", "replicasMin", "replicasMax", "region", "approvalTicket")
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "wasSaving") + INPUT_KEYS

        @Suppress("DEPRECATION") // Strict primitive reads also preserve the API 26 minimum.
        fun read(value: Bundle): DeploymentTargetFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM ||
                value.get("kind") != PreviewDeploymentTargetSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            val raw = INPUT_KEYS.map { value.get(it) }
            if (raw.any { it != null && it !is String }) return null
            return DeploymentTargetFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewDeploymentTargetInput(raw[0] as String?, raw[1] as String?, raw[2] as String?,
                    raw[3] as String?, raw[4] as String?), saving).takeIf { it.bounded() }
        }
    }
}
