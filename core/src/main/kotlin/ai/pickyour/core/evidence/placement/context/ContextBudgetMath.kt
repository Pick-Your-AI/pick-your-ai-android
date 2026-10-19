package ai.pickyour.core.evidence.placement.context

import java.math.BigInteger

/**
 * Pure normalized CTX-01/02 arithmetic from the pinned CD11 reference.
 * No counting, route selection, extension evidence qualification, memory estimate or model execution.
 */
object ContextBudgetMath {
    private fun compare(demand: BigInteger?, limit: BigInteger?): ContextOutcome = when {
        demand == null || limit == null -> ContextOutcome.UNKNOWN
        demand <= limit -> ContextOutcome.PASS
        else -> ContextOutcome.FAIL
    }

    fun combine(outcomes: Collection<ContextOutcome>): ContextOutcome = when {
        ContextOutcome.FAIL in outcomes -> ContextOutcome.FAIL
        ContextOutcome.UNKNOWN in outcomes -> ContextOutcome.UNKNOWN
        ContextOutcome.PASS in outcomes -> ContextOutcome.PASS
        else -> ContextOutcome.SKIPPED
    }

    fun evaluate(budget: ContextBudget): ContextMathResult {
        if (!budget.enabled) return ContextMathResult(ContextOutcome.SKIPPED, false, emptyList())
        if (!budget.tupleMatches) return ContextMathResult(ContextOutcome.UNKNOWN, false, emptyList())
        val lower = budget.inputTokens?.lower
        val upper = budget.inputTokens?.upper
        val output = budget.reservedOutput
        val totalLower = sum(lower, output)
        val totalUpper = sum(upper, output)
        val outputResult = compare(output, budget.outputCap)
        val checks = arrayListOf(ContextCheck(ContextCheckName.OUTPUT, outputResult, outputResult))
        checks.add(when (budget.semantics) {
            ContextSemantics.SHARED -> ContextCheck(
                ContextCheckName.COMBINED, compare(totalLower, budget.sharedCap), compare(totalUpper, budget.sharedCap),
            )
            ContextSemantics.SEPARATE -> ContextCheck(
                ContextCheckName.COMBINED, ContextOutcome.SKIPPED, ContextOutcome.SKIPPED,
            )
            ContextSemantics.UNKNOWN -> ContextCheck(
                ContextCheckName.COMBINED, ContextOutcome.UNKNOWN, ContextOutcome.UNKNOWN,
            )
        })
        if (budget.inputCap != null || budget.semantics == ContextSemantics.SEPARATE) {
            checks.add(ContextCheck(
                ContextCheckName.INPUT, compare(lower, budget.inputCap), compare(upper, budget.inputCap),
            ))
        }
        if (budget.policyTotalCap != null) {
            checks.add(ContextCheck(
                ContextCheckName.POLICY_TOTAL, compare(totalLower, budget.policyTotalCap),
                compare(totalUpper, budget.policyTotalCap),
            ))
        }
        return summarize(checks)
    }

    /**
     * The caller already established the extension and runtime facts. This function only normalizes them.
     * Missing native evidence stays missing even when an extension is supplied.
     */
    fun effectiveLimit(
        native: BigInteger?,
        extension: BigInteger? = null,
        extensionEvidenced: Boolean = false,
        runtimeCeiling: BigInteger? = null,
    ): BigInteger? {
        require(listOf(native, extension, runtimeCeiling).all { it == null || it.signum() >= 0 }) {
            "Invalid normalized context limit"
        }
        if (native == null) return null
        val limit = if (extensionEvidenced && extension != null) extension else native
        return if (runtimeCeiling == null) limit else limit.min(runtimeCeiling)
    }

    internal fun summarize(checks: List<ContextCheck>): ContextMathResult {
        val upper = combine(checks.map { it.upper })
        val lower = combine(checks.map { it.lower })
        return ContextMathResult(upper, upper == ContextOutcome.FAIL && lower != ContextOutcome.FAIL, checks)
    }

    private fun sum(first: BigInteger?, second: BigInteger?): BigInteger? =
        if (first == null || second == null) null else first + second
}
