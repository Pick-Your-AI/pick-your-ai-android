package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewAgentFrameworkInput
import ai.pickyour.core.preview.PreviewAgentFrameworkSettings

/** Private typed agent-framework input and original identity, never a command or replacement save token. */
internal class AgentFrameworkFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewAgentFrameworkInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = (input.agentCount?.length?.toLong() ?: 0L) +
            (input.maxIterations?.length?.toLong() ?: 0L) + (input.timeoutMs?.length?.toLong() ?: 0L)
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS
    }

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedAgentFrameworkPending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("form", FORM)
        putString("kind", PreviewAgentFrameworkSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("agentCount", input.agentCount)
        putString("maxIterations", input.maxIterations)
        putString("timeoutMs", input.timeoutMs)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "AgentFrameworkFormCheckpoint"

    companion object {
        const val VERSION = "typed-agent-framework/1"
        private const val FORM = "configure_agent_framework"
        private val KEYS = setOf("version", "form", "kind", "draftId", "baseline", "selectionId",
            "catalogItemId", "catalogItemVersion", "agentCount", "maxIterations", "timeoutMs", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): AgentFrameworkFormCheckpoint? {
            if (value.keySet() != KEYS || (value.get("version") as? String) != VERSION ||
                (value.get("form") as? String) != FORM || (value.get("kind") as? String) != PreviewAgentFrameworkSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val agentCount = value.get("agentCount")
            val maxIterations = value.get("maxIterations")
            val timeoutMs = value.get("timeoutMs")
            if (agentCount != null && agentCount !is String || maxIterations != null && maxIterations !is String ||
                timeoutMs != null && timeoutMs !is String) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return AgentFrameworkFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewAgentFrameworkInput(agentCount as String?, maxIterations as String?, timeoutMs as String?), saving)
                .takeIf { it.bounded() }
        }
    }
}
