package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.contracts.ContractMoney
import ai.pickyour.core.presentation.ExactNumberFormatter
import ai.pickyour.core.pricing.InspectedCostEstimate
import java.math.BigInteger

/** Read-only presentation of recorded values; locale never changes their amount, unit or currency. */
internal object CostInspectionText {
    fun describe(context: Context, value: InspectedCostEstimate): String {
        val locale = context.resources.configuration.locales[0]
        val bidi = BidiFormatter.getInstance(locale)
        fun number(text: String) = bidi.unicodeWrap(text, TextDirectionHeuristics.LTR)
        fun decimal(value: ContractDecimal) = number(ExactNumberFormatter.format(value, locale))
        fun money(value: ContractMoney) = number(context.getString(R.string.cost_money,
            ExactNumberFormatter.format(value.amount, locale), value.currency))
        fun ids(values: Collection<String>) = values.joinToString(", ").ifEmpty { context.getString(R.string.cost_none_declared) }
        val days = number(ExactNumberFormatter.format(value.periodDays, locale))
        // Android's plural selector takes Int. Never narrow a larger frozen integer into another count.
        val period = if (value.periodDays >= BigInteger.ZERO && value.periodDays <= BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
            context.resources.getQuantityString(R.plurals.cost_period_days, value.periodDays.toInt(), days)
        } else context.getString(R.string.cost_period_days_exact, days)
        val summary = listOf(
            context.getString(R.string.cost_fixture_notice),
            context.getString(R.string.cost_identity, value.buildId, value.buildRevision.toString(), value.estimateId, value.pricebookId),
            context.getString(R.string.cost_period, period, value.currency, value.estimatedAt),
            context.getString(R.string.cost_coverage, value.declaredCoverage.name),
            context.getString(R.string.cost_amounts, money(value.reportedSubtotal), money(value.reportedTax), money(value.reportedTotal)),
            context.getString(R.string.cost_unpriced, ids(value.reportedUnpricedSelectionIds)),
            context.getString(R.string.cost_unclassified, ids(value.unclassifiedActiveSelectionIds)),
            context.getString(R.string.cost_evidence_limits),
        )
        val lines = value.lines.map { line ->
            context.getString(R.string.cost_line, line.id, line.selectionId, line.meter,
                decimal(ContractDecimal.parse(line.quantity)), line.rateId, money(line.unitPrice), money(line.amount),
                line.method, line.confidence, ids(line.assumptions))
        }
        return (summary + lines + context.getString(R.string.cost_warnings, ids(value.warnings))).joinToString("\n\n")
    }
}
