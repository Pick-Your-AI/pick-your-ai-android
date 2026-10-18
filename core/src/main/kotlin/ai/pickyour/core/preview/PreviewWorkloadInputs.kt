package ai.pickyour.core.preview

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.DraftDataClassification
import ai.pickyour.core.drafts.DraftWorkload
import java.math.BigInteger

/** Raw pending edits, including invalid text; never replace them with defaults or parsed zeroes. */
data class PreviewWorkloadInputs(
    val region: String = "", val currency: String = "", val budget: String = "",
    val requests: String = "", val inputTokens: String = "", val outputTokens: String = "",
    val concurrency: String = "", val latency: String = "",
    val classification: DraftDataClassification? = null,
    val rejectedEdit: Boolean = false,
) {
    /** Null means invalid entered input. An entirely empty form is a valid unanswered workload. */
    fun parsed(): DraftWorkload? = try {
        require(!rejectedEdit)
        require(region.length <= 16_384 && budget.length <= 16_384)
        fun integer(value: String): BigInteger? {
            if (value.isEmpty()) return null
            require(value.length <= 128 && Regex("0|[1-9][0-9]*").matches(value))
            return BigInteger(value)
        }
        DraftWorkload(region.ifEmpty { null }, currency.ifEmpty { null },
            budget.ifEmpty { null }?.let(ContractDecimal::parse), integer(requests), integer(inputTokens),
            integer(outputTokens), integer(concurrency), integer(latency), classification)
    } catch (_: IllegalArgumentException) { null }

    override fun toString() = "PreviewWorkloadInputs"

    companion object {
        fun from(value: DraftWorkload) = PreviewWorkloadInputs(value.region.orEmpty(), value.currency.orEmpty(),
            value.monthlyBudgetAmount?.encoded.orEmpty(), value.requestsPerDay?.toString().orEmpty(),
            value.avgInputTokens?.toString().orEmpty(), value.avgOutputTokens?.toString().orEmpty(),
            value.maxConcurrency?.toString().orEmpty(), value.maxLatencyMs?.toString().orEmpty(), value.dataClassification)
    }
}

/** Older bundles hydrate from storage; a present raw checkpoint preserves even invalid or empty edits. */
class PreviewWorkloadCheckpoint(val hasInputs: Boolean, val inputs: PreviewWorkloadInputs) {
    fun forLoad(preservePending: Boolean, baseline: DraftWorkload): PreviewWorkloadInputs =
        if (preservePending && hasInputs) inputs else PreviewWorkloadInputs.from(baseline)
}
