package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.contracts.ContractMoney
import ai.pickyour.core.contracts.ContractRejected
import ai.pickyour.core.preview.PreviewComparedValue
import ai.pickyour.core.preview.PreviewComparedValueKind
import ai.pickyour.core.pricing.InspectedCostEstimate
import ai.pickyour.core.pricing.InspectedCostLine
import ai.pickyour.core.pricing.RecordedCostArithmetic
import ai.pickyour.core.pricing.RecordedProductObservation
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat

/** Bounded declared values and separate unrounded-product observations; no billing or rate selection. */
internal object CostInspectionText {
    private const val MAX_LINES = 8
    private const val MAX_LIST_ITEMS = 8
    private const val MAX_LOCALIZED_DECIMAL_LENGTH = 160

    fun describe(context: Context, value: InspectedCostEstimate): String {
        fun literal(raw: String) = literal(context, raw)
        fun money(value: ContractMoney): String = context.getString(R.string.cost_display_money,
            decimal(context, value.amount), ltr(context, value.currency))
        fun entries(values: Collection<String>): String {
            if (values.isEmpty()) return context.getString(R.string.cost_none_declared)
            val shown = values.take(MAX_LIST_ITEMS)
            return context.resources.getQuantityString(R.plurals.cost_display_entries, values.size,
                shown.size, values.size, shown.joinToString("\n", transform = ::literal))
        }
        val summary = listOf(
            context.getString(R.string.cost_fixture_notice),
            context.getString(R.string.cost_display_policy),
            context.getString(R.string.cost_identity, literal(value.buildId), literal(value.buildRevision.toString()),
                literal(value.estimateId), literal(value.pricebookId)),
            context.getString(R.string.cost_period, literal(value.periodDays.toString()),
                ltr(context, value.currency), literal(value.estimatedAt)),
            context.getString(R.string.cost_coverage, value.declaredCoverage.name),
            context.getString(R.string.cost_amounts, money(value.reportedSubtotal), money(value.reportedTax), money(value.reportedTotal)),
            context.getString(R.string.cost_unpriced, entries(value.reportedUnpricedSelectionIds)),
            context.getString(R.string.cost_unclassified, entries(value.unclassifiedActiveSelectionIds)),
            context.getString(R.string.cost_evidence_limits),
            context.getString(R.string.cost_arithmetic_notice),
        )
        val shownLines = value.lines.take(MAX_LINES)
        val lines = shownLines.map { line ->
            context.getString(R.string.cost_line, literal(line.id), literal(line.selectionId), line.meter,
                quantity(context, line.quantity), literal(line.rateId),
                money(line.unitPrice), money(line.amount), line.method, line.confidence, entries(line.assumptions)) +
                "\n" + arithmetic(context, line)
        }
        return (summary + context.resources.getQuantityString(R.plurals.cost_display_lines, value.lines.size,
            shownLines.size, value.lines.size) + lines +
            context.getString(R.string.cost_warnings, entries(value.warnings))).joinToString("\n\n")
    }

    private fun arithmetic(context: Context, line: InspectedCostLine): String {
        val result = RecordedCostArithmetic.inspect(line)
        val resource = when (result.observation) {
            RecordedProductObservation.MATCHES_EXACT_PRODUCT -> R.string.cost_arithmetic_matches
            RecordedProductObservation.DIFFERS_FROM_EXACT_PRODUCT -> R.string.cost_arithmetic_differs
            RecordedProductObservation.EXACT_PRODUCT_OUTSIDE_PROFILE -> R.string.cost_arithmetic_precision
            RecordedProductObservation.UNSUPPORTED_QUANTITY -> R.string.cost_arithmetic_quantity
            RecordedProductObservation.DECIMAL_LIMIT_EXCEEDED -> R.string.cost_arithmetic_limit
        }
        return result.exactProduct?.let { product ->
            context.getString(resource, literal(context, product), ltr(context, line.amount.currency))
        } ?: context.getString(resource)
    }

    /** Frozen quantity strings can be retained even when they are not whole-string decimal values. */
    private fun quantity(context: Context, recorded: String): String {
        val value = try {
            ContractDecimal.parse(recorded)
        } catch (_: ContractRejected) {
            return context.getString(R.string.cost_display_literal_quantity, literal(context, recorded))
        }
        return decimal(context, value)
    }

    /** The bounded canonical lexeme is always visible, even when localization cannot be offered. */
    internal fun decimal(context: Context, value: ContractDecimal): String {
        val encoded = value.encoded
        if (encoded.length > MAX_LOCALIZED_DECIMAL_LENGTH)
            return context.getString(R.string.cost_display_large_decimal, literal(context, encoded))
        val locale = context.resources.configuration.locales[0]
        val format = NumberFormat.getNumberInstance(locale) as? DecimalFormat
            ?: return context.getString(R.string.cost_display_canonical_decimal, literal(context, encoded))
        val number = value.exactValue()
        format.minimumIntegerDigits = 1
        format.maximumIntegerDigits = encoded.substringBefore('.').length
        // Retain every supplied fractional digit, including zeroes; no currency defaults or binary float.
        format.maximumFractionDigits = number.scale()
        format.minimumFractionDigits = number.scale()
        format.roundingMode = RoundingMode.UNNECESSARY
        return context.getString(R.string.cost_display_decimal,
            ltr(context, format.format(number)), literal(context, encoded))
    }

    private fun literal(context: Context, raw: String): String {
        val value = PreviewComparedValue.from(raw)
        val escaped = when (value.kind) {
            PreviewComparedValueKind.EMPTY -> "\"\""
            PreviewComparedValueKind.TEXT -> requireNotNull(value.quotedLiteral)
            PreviewComparedValueKind.ABSENT -> error("A recorded cost field cannot be absent here")
        }
        val bounded = if (value.truncated) context.getString(R.string.cost_display_truncated, escaped) else escaped
        return ltr(context, bounded)
    }

    // Source-provided control/format characters have already been escaped. Only our formatter adds bidi marks.
    private fun ltr(context: Context, value: String): String =
        BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            .unicodeWrap(value, TextDirectionHeuristics.LTR)
}
