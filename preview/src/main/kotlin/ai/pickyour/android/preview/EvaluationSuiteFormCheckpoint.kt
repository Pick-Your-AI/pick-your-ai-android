package ai.pickyour.android.preview

import android.os.Bundle
import ai.pickyour.core.preview.PreviewEvaluationSuiteInput
import ai.pickyour.core.preview.PreviewEvaluationSuiteSettings

/** Private typed input and captured identities only; this checkpoint grants no write or retry authority. */
internal class EvaluationSuiteFormCheckpoint(
    val draftId: String,
    val baseline: String,
    val selectionId: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val input: PreviewEvaluationSuiteInput,
    val wasSaving: Boolean,
) {
    private fun bounded() = draftId.length in 1..128 && baseline.length in 1..128 &&
        selectionId.length in 1..128 && catalogItemId.length in 1..256 && catalogItemVersion.length in 1..128 &&
        (input.minimumScore?.length?.toLong() ?: 0L) + (input.datasetSize?.length ?: 0) <=
            ComponentFormCheckpoint.MAX_RAW_CHARACTERS

    fun recovery(): ComponentFormRecovery = if (bounded()) ComponentFormRecovery.TypedEvaluationSuitePending(this)
        else ComponentFormRecovery.Unavailable

    fun bundle(): Bundle? = if (!bounded()) null else Bundle().apply {
        putString("version", VERSION)
        putString("kind", PreviewEvaluationSuiteSettings.KIND)
        putString("draftId", draftId)
        putString("baseline", baseline)
        putString("selectionId", selectionId)
        putString("catalogItemId", catalogItemId)
        putString("catalogItemVersion", catalogItemVersion)
        putString("minimumScore", input.minimumScore)
        putString("datasetSize", input.datasetSize)
        putBoolean("gateRequiredSupplied", input.gateRequired != null)
        putBoolean("gateRequired", input.gateRequired ?: false)
        putBoolean("wasSaving", wasSaving)
    }

    override fun toString() = "EvaluationSuiteFormCheckpoint"

    companion object {
        const val VERSION = "typed-evaluation-suite/1"
        private val KEYS = setOf("version", "kind", "draftId", "baseline", "selectionId", "catalogItemId",
            "catalogItemVersion", "minimumScore", "datasetSize", "gateRequiredSupplied", "gateRequired", "wasSaving")

        @Suppress("DEPRECATION") // Exact primitive reads preserve the API 26 minimum.
        fun read(value: Bundle): EvaluationSuiteFormCheckpoint? {
            if (value.keySet() != KEYS || value.get("version") != VERSION ||
                value.get("kind") != PreviewEvaluationSuiteSettings.KIND) return null
            val draftId = value.get("draftId") as? String ?: return null
            val baseline = value.get("baseline") as? String ?: return null
            val selectionId = value.get("selectionId") as? String ?: return null
            val itemId = value.get("catalogItemId") as? String ?: return null
            val itemVersion = value.get("catalogItemVersion") as? String ?: return null
            val score = value.get("minimumScore")
            val size = value.get("datasetSize")
            if (score != null && score !is String || size != null && size !is String) return null
            val supplied = value.get("gateRequiredSupplied") as? Boolean ?: return null
            val gateRequired = value.get("gateRequired") as? Boolean ?: return null
            if (!supplied && gateRequired) return null
            val saving = value.get("wasSaving") as? Boolean ?: return null
            return EvaluationSuiteFormCheckpoint(draftId, baseline, selectionId, itemId, itemVersion,
                PreviewEvaluationSuiteInput(score as String?, size as String?, gateRequired.takeIf { supplied }), saving)
                .takeIf { it.bounded() }
        }
    }
}
