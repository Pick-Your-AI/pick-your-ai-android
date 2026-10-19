package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewContextPolicyInput
import ai.pickyour.core.preview.PreviewContextPolicySettings

/** Version 3 is private raw UI state. It is never a Selection payload or fresh write authority. */
internal class ContextPolicyFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val input: PreviewContextPolicyInput,
    val wasSaving: Boolean,
) {
    private fun bounded(): Boolean {
        val total = input.requestedContextTokens.length.toLong() + input.reservedOutputTokens.length +
            (input.truncationStrategy?.length ?: 0) + (input.kvCachePrecision?.length ?: 0)
        fun allowed(key: String, value: String?) = value == null ||
            value in PreviewContextPolicySettings.fields.single { it.key == key }.enumValues
        return draftId.length in 1..128 && baseline.length in 1..128 && selectionId.length in 1..128 &&
            total <= ComponentFormCheckpoint.MAX_RAW_CHARACTERS &&
            allowed(PreviewContextPolicySettings.TRUNCATION, input.truncationStrategy) &&
            allowed(PreviewContextPolicySettings.PRECISION, input.kvCachePrecision)
    }

    fun recovery(): ComponentFormRecovery =
        if (bounded()) ComponentFormRecovery.TypedContextPending(this) else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putInt("version", VERSION)
        putString("form", FORM)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("requested", input.requestedContextTokens)
        putString("reserved", input.reservedOutputTokens)
        putString("truncation", input.truncationStrategy)
        putString("precision", input.kvCachePrecision)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "ContextPolicyFormCheckpoint"

    companion object {
        const val VERSION = 3
        private const val FORM = "configure_context_policy"
        private val KEYS = setOf("version", "form", "draftId", "baseline", "selectionId",
            "requested", "reserved", "truncation", "precision", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads support the preview's API 26 minimum.
        fun read(value: Bundle): ContextPolicyFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION || value.get("form") != FORM) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val requested = value.get("requested") as? String ?: return null
            val reserved = value.get("reserved") as? String ?: return null
            val truncation = value.get("truncation")
            val precision = value.get("precision")
            if (truncation != null && truncation !is String || precision != null && precision !is String) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return ContextPolicyFormCheckpoint(draftId, baseline, selectionId,
                PreviewContextPolicyInput(requested, reserved, truncation as String?, precision as String?), saving)
                .takeIf { it.bounded() }
        }
    }
}
