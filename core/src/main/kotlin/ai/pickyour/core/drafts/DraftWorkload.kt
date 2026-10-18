package ai.pickyour.core.drafts

import ai.pickyour.core.contracts.ContractDecimal
import java.math.BigInteger

/** Entered classification only; this is not a security, placement or compatibility decision. */
enum class DraftDataClassification(val encoded: String) {
    PUBLIC("public"), INTERNAL("internal"), CONFIDENTIAL("confidential"), RESTRICTED("restricted"),
}

/**
 * Immutable, incomplete user assumptions aligned with frozen Build.objectives field bounds.
 * Every null remains unanswered. Budget and currency are independent editing inputs; this is not
 * a complete BuildSnapshot, quote, catalog fact, measured capacity or deployment choice.
 * Integers have no machine-width cap; durable writes still obey the existing local input budgets.
 */
data class DraftWorkload(
    val region: String? = null,
    val currency: String? = null,
    val monthlyBudgetAmount: ContractDecimal? = null,
    val requestsPerDay: BigInteger? = null,
    val avgInputTokens: BigInteger? = null,
    val avgOutputTokens: BigInteger? = null,
    val maxConcurrency: BigInteger? = null,
    val maxLatencyMs: BigInteger? = null,
    val dataClassification: DraftDataClassification? = null,
) {
    init {
        require(region == null || region.isNotEmpty()) { "INVALID_REGION" }
        require(currency == null || Regex("^[A-Z]{3}$").matches(currency)) { "INVALID_CURRENCY" }
        require(requestsPerDay == null || requestsPerDay.signum() >= 0) { "INVALID_REQUESTS_PER_DAY" }
        require(avgInputTokens == null || avgInputTokens.signum() >= 0) { "INVALID_AVG_INPUT_TOKENS" }
        require(avgOutputTokens == null || avgOutputTokens.signum() >= 0) { "INVALID_AVG_OUTPUT_TOKENS" }
        require(maxConcurrency == null || maxConcurrency.signum() > 0) { "INVALID_MAX_CONCURRENCY" }
        require(maxLatencyMs == null || maxLatencyMs.signum() > 0) { "INVALID_MAX_LATENCY_MS" }
    }

    override fun toString(): String = "DraftWorkload"
}
