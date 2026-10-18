package ai.pickyour.core.presentation

import ai.pickyour.core.contracts.ContractDecimal
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Display only: keep every digit and decimal place, without grouping, rounding or unit conversion. */
object ExactNumberFormatter {
    fun format(value: ContractDecimal, locale: Locale): String = format(value.exactValue(), locale)
    fun format(value: BigInteger, locale: Locale): String = format(BigDecimal(value), locale)

    private fun format(value: BigDecimal, locale: Locale): String =
        DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            maximumIntegerDigits = maxOf(1, value.precision() - value.scale())
            minimumFractionDigits = value.scale()
            maximumFractionDigits = value.scale()
            roundingMode = RoundingMode.UNNECESSARY
        }.format(value)
}
