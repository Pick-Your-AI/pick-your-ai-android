package ai.pickyour.core.comparison

import ai.pickyour.core.pricing.CostComparisonCompleteness
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.CostRejection
import ai.pickyour.core.pricing.InspectedCostEstimate
import ai.pickyour.core.pricing.RecordedCostComparator
import ai.pickyour.core.pricing.RecordedCostComparison
import java.io.InputStream

/** Dispositions of explicitly supplied inputs; no storage or current-price lookup is implied. */
enum class HistoricalCostProblem {
    ESTIMATE_NOT_SUPPLIED, ESTIMATE_IDENTITY_MISMATCH, BUILD_RECORD_MISMATCH,
    INVALID_BUILD, INVALID_ESTIMATE, MISSING_PRICEBOOK_PIN, BINDING_MISMATCH,
    DUPLICATE_ID, UNKNOWN_SELECTION, MIXED_CURRENCY, INCONSISTENT_AMOUNTS, INCONSISTENT_COVERAGE,
    UNSUPPORTED_BUILD_SCHEMA_VERSION, UNSUPPORTED_ESTIMATE_SCHEMA_VERSION,
}

sealed interface HistoricalCostInput {
    /** No attempted historical estimate admission was supplied. This never means zero cost. */
    data object NotLoaded : HistoricalCostInput
    data class Unavailable(val reason: HistoricalCostProblem) : HistoricalCostInput
    class Available internal constructor(val record: SelectedCostEstimate) : HistoricalCostInput {
        override fun toString(): String = "HistoricalCostInput.Available"
    }
}

/**
 * An immutable estimate admitted against the complete selected Build and requested estimate ID.
 * The existing inspector's accepted structure and arithmetic do not establish pricing eligibility,
 * coverage, freshness, savings, an authentic historical artifact or a complete Pricebook identity.
 */
class SelectedCostEstimate private constructor(
    val build: ComparisonSnapshot,
    val estimate: InspectedCostEstimate,
) {
    internal fun matchesBuild(selected: ComparisonSnapshot): Boolean =
        sameJsonValues(build.copyDocument(), selected.copyDocument())

    override fun toString(): String = "SelectedCostEstimate"

    companion object {
        /**
         * Explicit attempted admission. The nullable stream records only what the caller supplied.
         * The stream remains caller-owned on every outcome. Run synchronous parsing off the UI thread.
         */
        fun read(
            selectedBuild: ComparisonSnapshot,
            expectedEstimateId: String,
            estimate: InputStream?,
        ): HistoricalCostInput {
            require(expectedEstimateId.isNotBlank())
            if (estimate == null) return unavailable(HistoricalCostProblem.ESTIMATE_NOT_SUPPLIED)

            val reception = CostEstimateAdapter().inspect(
                selectedBuild.copyDocument().toString().byteInputStream(), estimate,
            )
            if (reception is CostReception.Rejected) return unavailable(when (reception.reason) {
                CostRejection.INVALID_BUILD -> HistoricalCostProblem.INVALID_BUILD
                CostRejection.INVALID_ESTIMATE -> HistoricalCostProblem.INVALID_ESTIMATE
                CostRejection.MISSING_PRICEBOOK_PIN -> HistoricalCostProblem.MISSING_PRICEBOOK_PIN
                CostRejection.BINDING_MISMATCH -> HistoricalCostProblem.BINDING_MISMATCH
                CostRejection.DUPLICATE_ID -> HistoricalCostProblem.DUPLICATE_ID
                CostRejection.UNKNOWN_SELECTION -> HistoricalCostProblem.UNKNOWN_SELECTION
                CostRejection.MIXED_CURRENCY -> HistoricalCostProblem.MIXED_CURRENCY
                CostRejection.INCONSISTENT_AMOUNTS -> HistoricalCostProblem.INCONSISTENT_AMOUNTS
                CostRejection.INCONSISTENT_COVERAGE -> HistoricalCostProblem.INCONSISTENT_COVERAGE
                CostRejection.UNSUPPORTED_BUILD_SCHEMA_VERSION -> HistoricalCostProblem.UNSUPPORTED_BUILD_SCHEMA_VERSION
                CostRejection.UNSUPPORTED_ESTIMATE_SCHEMA_VERSION -> HistoricalCostProblem.UNSUPPORTED_ESTIMATE_SCHEMA_VERSION
            })
            val inspected = (reception as CostReception.Inspected).estimate
            if (inspected.estimateId != expectedEstimateId)
                return unavailable(HistoricalCostProblem.ESTIMATE_IDENTITY_MISMATCH)
            return HistoricalCostInput.Available(SelectedCostEstimate(selectedBuild, inspected))
        }

        private fun unavailable(reason: HistoricalCostProblem) = HistoricalCostInput.Unavailable(reason)
    }
}

enum class SelectedCostComparisonState { NOT_COMPARED, COMPLETE, LINE_LIMIT_EXCEEDED }

/** Selected records and their independent input dispositions, including partial or unknown coverage. */
class SelectedCostComparison internal constructor(
    val beforeBuild: ComparisonSnapshot,
    val afterBuild: ComparisonSnapshot,
    val beforeInput: HistoricalCostInput,
    val afterInput: HistoricalCostInput,
    /** Null when either selected input is unavailable. Missing amounts are never synthesized. */
    val recorded: RecordedCostComparison?,
) {
    val state: SelectedCostComparisonState = when (recorded?.completeness) {
        null -> SelectedCostComparisonState.NOT_COMPARED
        CostComparisonCompleteness.COMPLETE -> SelectedCostComparisonState.COMPLETE
        CostComparisonCompleteness.LINE_LIMIT_EXCEEDED -> SelectedCostComparisonState.LINE_LIMIT_EXCEEDED
    }

    override fun toString(): String = "SelectedCostComparison"
}

/**
 * Rechecks complete selected Build records, then delegates unchanged recorded-cost comparison.
 * No estimate, current rate, usage model, clock, storage authority or currency conversion is supplied.
 */
object SelectedCostComparator {
    fun compare(
        before: ComparisonSnapshot,
        after: ComparisonSnapshot,
        beforeCost: HistoricalCostInput = HistoricalCostInput.NotLoaded,
        afterCost: HistoricalCostInput = HistoricalCostInput.NotLoaded,
        maxLines: Int = 10_000,
    ): SelectedCostComparison {
        require(maxLines in 1..10_000)
        fun boundInput(selected: ComparisonSnapshot, input: HistoricalCostInput): HistoricalCostInput =
            if (input is HistoricalCostInput.Available && !input.record.matchesBuild(selected))
                HistoricalCostInput.Unavailable(HistoricalCostProblem.BUILD_RECORD_MISMATCH) else input

        val left = boundInput(before, beforeCost)
        val right = boundInput(after, afterCost)
        val old = (left as? HistoricalCostInput.Available)?.record?.estimate
        val new = (right as? HistoricalCostInput.Available)?.record?.estimate
        val recorded = if (old != null && new != null) RecordedCostComparator.compare(old, new, maxLines) else null
        return SelectedCostComparison(before, after, left, right, recorded)
    }
}
