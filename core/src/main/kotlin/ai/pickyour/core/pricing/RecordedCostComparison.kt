package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.contracts.ContractMoney
import ai.pickyour.core.contracts.ContractRejected
import java.math.BigDecimal
import java.util.Collections

/** Facts about the supplied records only; a difference is not a price-refresh or savings verdict. */
enum class CostRecordDifference {
    ESTIMATE_ID, BUILD_ID, BUILD_REVISION, BUILD_RECORD, PRICEBOOK_ID, PERIOD, CURRENCY,
    ESTIMATED_AT, DECLARED_COVERAGE, REPORTED_UNPRICED_SELECTIONS, UNCLASSIFIED_SELECTIONS,
    REPORTED_SUBTOTAL, REPORTED_TAX, REPORTED_TOTAL, WARNINGS, LINE_IDS_OR_ORDER,
}
enum class CostRecordIntegrity { SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS, SAME_BUILD_IDENTITY_WITH_DIFFERENT_RECORDS }
enum class CostLineField { SELECTION, RATE, METER, QUANTITY, QUANTITY_LITERAL, UNIT_PRICE, AMOUNT, METHOD, CONFIDENCE, ASSUMPTIONS }
/** Numeric comparison only within an identical recorded association; never proof of equal usage. */
enum class RecordedQuantityComparison { EQUAL, DIFFERENT, UNAVAILABLE }
enum class CostLinePresence { BOTH, BEFORE_ONLY, AFTER_ONLY }
enum class CostComparisonCompleteness { COMPLETE, LINE_LIMIT_EXCEEDED }
enum class CostComparisonGap {
    USAGE_MODEL_IDENTITY_NOT_ESTABLISHED, PRICEBOOK_REVISION_AND_HASH_NOT_ESTABLISHED,
    RATE_ELIGIBILITY_NOT_ESTABLISHED, FRESHNESS_NOT_ESTABLISHED, COVERAGE_NOT_ACCEPTED,
    UNSUPPORTED_QUANTITY, MULTIPLE_LINES_FOR_SAME_METER_AND_RATE,
}

/**
 * A display join on a recorded line ID, not an assertion of stable cross-estimate correspondence.
 * Both full lines remain inspectable, including a changed selection/rate/meter association.
 */
class RecordedCostLineComparison internal constructor(
    val lineId: String,
    val before: InspectedCostLine?,
    val after: InspectedCostLine?,
    differences: Set<CostLineField>,
    val quantityComparison: RecordedQuantityComparison,
) {
    val presence: CostLinePresence = when {
        before == null -> CostLinePresence.AFTER_ONLY
        after == null -> CostLinePresence.BEFORE_ONLY
        else -> CostLinePresence.BOTH
    }
    val differences: Set<CostLineField> = immutableCostSet(differences)
    override fun toString(): String = "RecordedCostLineComparison"
}

/** Immutable historical inputs and recorded differences. This result never establishes comparability. */
class RecordedCostComparison internal constructor(
    val before: InspectedCostEstimate,
    val after: InspectedCostEstimate,
    differences: Set<CostRecordDifference>,
    integrity: Set<CostRecordIntegrity>,
    gaps: Set<CostComparisonGap>,
    lines: List<RecordedCostLineComparison>,
    val completeness: CostComparisonCompleteness,
) {
    val differences: Set<CostRecordDifference> = immutableCostSet(differences)
    val integrity: Set<CostRecordIntegrity> = immutableCostSet(integrity)
    val gaps: Set<CostComparisonGap> = immutableCostSet(gaps)
    /** Empty on a line-limit failure; the full before/after inputs are still retained. */
    val lines: List<RecordedCostLineComparison> = Collections.unmodifiableList(ArrayList(lines))
    val comparability: CostEvidence = CostEvidence.NOT_ESTABLISHED
    val savings: CostEvidence = CostEvidence.NOT_ESTABLISHED
    val budgetPass: CostEvidence = CostEvidence.NOT_ESTABLISHED
    override fun toString(): String = "RecordedCostComparison"
}

/**
 * Compares records already bound to their supplied BuildSnapshots by CostEstimateAdapter.
 * No rate lookup, clock, conversion, period normalization, inferred usage model, mutation or I/O.
 */
object RecordedCostComparator {
    fun compare(before: InspectedCostEstimate, after: InspectedCostEstimate, maxLines: Int = 10_000): RecordedCostComparison {
        require(maxLines in 1..10_000)
        val differences = linkedSetOf<CostRecordDifference>()
        fun changed(field: CostRecordDifference, same: Boolean) { if (!same) differences.add(field) }
        changed(CostRecordDifference.ESTIMATE_ID, before.estimateId == after.estimateId)
        changed(CostRecordDifference.BUILD_ID, before.buildId == after.buildId)
        changed(CostRecordDifference.BUILD_REVISION, before.buildRevision == after.buildRevision)
        changed(CostRecordDifference.BUILD_RECORD, before.hasSameRecordedBuild(after))
        changed(CostRecordDifference.PRICEBOOK_ID, before.pricebookId == after.pricebookId)
        changed(CostRecordDifference.PERIOD, before.periodDays == after.periodDays)
        changed(CostRecordDifference.CURRENCY, before.currency == after.currency)
        changed(CostRecordDifference.ESTIMATED_AT, before.estimatedAt == after.estimatedAt)
        changed(CostRecordDifference.DECLARED_COVERAGE, before.declaredCoverage == after.declaredCoverage)
        changed(CostRecordDifference.REPORTED_UNPRICED_SELECTIONS, before.reportedUnpricedSelectionIds == after.reportedUnpricedSelectionIds)
        changed(CostRecordDifference.UNCLASSIFIED_SELECTIONS, before.unclassifiedActiveSelectionIds == after.unclassifiedActiveSelectionIds)
        changed(CostRecordDifference.REPORTED_SUBTOTAL, sameMoney(before.reportedSubtotal, after.reportedSubtotal))
        changed(CostRecordDifference.REPORTED_TAX, sameMoney(before.reportedTax, after.reportedTax))
        changed(CostRecordDifference.REPORTED_TOTAL, sameMoney(before.reportedTotal, after.reportedTotal))
        changed(CostRecordDifference.WARNINGS, before.warnings == after.warnings)
        changed(CostRecordDifference.LINE_IDS_OR_ORDER, before.lines.map { it.id } == after.lines.map { it.id })

        val integrity = linkedSetOf<CostRecordIntegrity>()
        if (before.estimateId == after.estimateId && !before.hasSameRecordedEstimate(after))
            integrity.add(CostRecordIntegrity.SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS)
        if (before.buildId == after.buildId && before.buildRevision == after.buildRevision && !before.hasSameRecordedBuild(after))
            integrity.add(CostRecordIntegrity.SAME_BUILD_IDENTITY_WITH_DIFFERENT_RECORDS)

        val gaps = linkedSetOf(CostComparisonGap.USAGE_MODEL_IDENTITY_NOT_ESTABLISHED,
            CostComparisonGap.PRICEBOOK_REVISION_AND_HASH_NOT_ESTABLISHED,
            CostComparisonGap.RATE_ELIGIBILITY_NOT_ESTABLISHED, CostComparisonGap.FRESHNESS_NOT_ESTABLISHED,
            CostComparisonGap.COVERAGE_NOT_ACCEPTED)
        val left = before.lines.associateBy { it.id }
        val right = after.lines.associateBy { it.id }
        // The inspector rejects duplicate line IDs. Do not index by selection, meter or rate:
        // several distinct billable lines may share every one of those fields.
        for (estimate in listOf(before, after)) {
            if (estimate.lines.any { quantity(it.quantity) == null }) gaps.add(CostComparisonGap.UNSUPPORTED_QUANTITY)
            if (estimate.lines.groupingBy { Triple(it.selectionId, it.meter, it.rateId) }.eachCount().any { it.value > 1 })
                gaps.add(CostComparisonGap.MULTIPLE_LINES_FOR_SAME_METER_AND_RATE)
        }
        val ids = (left.keys + right.keys).sorted()
        if (ids.size > maxLines) return RecordedCostComparison(before, after, differences, integrity, gaps,
            emptyList(), CostComparisonCompleteness.LINE_LIMIT_EXCEEDED)

        val lines = ids.map { id ->
            val old = left[id]
            val new = right[id]
            val fields = linkedSetOf<CostLineField>()
            var quantities = RecordedQuantityComparison.UNAVAILABLE
            if (old != null && new != null) {
                fun field(name: CostLineField, same: Boolean) { if (!same) fields.add(name) }
                field(CostLineField.SELECTION, old.selectionId == new.selectionId)
                field(CostLineField.RATE, old.rateId == new.rateId)
                field(CostLineField.METER, old.meter == new.meter)
                val a = quantity(old.quantity)
                val b = quantity(new.quantity)
                val sameAssociation = old.selectionId == new.selectionId && old.meter == new.meter && old.rateId == new.rateId
                if (a != null && b != null && sameAssociation) {
                    quantities = if (a.compareTo(b) == 0) RecordedQuantityComparison.EQUAL else RecordedQuantityComparison.DIFFERENT
                    field(CostLineField.QUANTITY, quantities == RecordedQuantityComparison.EQUAL)
                } else field(CostLineField.QUANTITY_LITERAL, old.quantity == new.quantity)
                field(CostLineField.UNIT_PRICE, sameMoney(old.unitPrice, new.unitPrice))
                field(CostLineField.AMOUNT, sameMoney(old.amount, new.amount))
                field(CostLineField.METHOD, old.method == new.method)
                field(CostLineField.CONFIDENCE, old.confidence == new.confidence)
                field(CostLineField.ASSUMPTIONS, old.assumptions == new.assumptions)
            }
            RecordedCostLineComparison(id, old, new, fields, quantities)
        }
        return RecordedCostComparison(before, after, differences, integrity, gaps, lines, CostComparisonCompleteness.COMPLETE)
    }

    private fun quantity(raw: String): BigDecimal? = try {
        ContractDecimal.parse(raw).exactValue()
    } catch (_: ContractRejected) { null }

    private fun sameMoney(a: ContractMoney, b: ContractMoney): Boolean =
        a.currency == b.currency && a.amount.exactValue().compareTo(b.amount.exactValue()) == 0
}

private fun <T> immutableCostSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
