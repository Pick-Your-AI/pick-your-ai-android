package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.contracts.ContractRejected

/** Observations under one local no-rounding profile, never an accepted billing verdict. */
enum class RecordedProductObservation {
    MATCHES_EXACT_PRODUCT,
    DIFFERS_FROM_EXACT_PRODUCT,
    EXACT_PRODUCT_OUTSIDE_PROFILE,
    UNSUPPORTED_QUANTITY,
    DECIMAL_LIMIT_EXCEEDED,
}

/** The computed string is private inspection evidence, not a replacement frozen Money value. */
class RecordedProductInspection internal constructor(
    val observation: RecordedProductObservation,
    val exactProduct: String? = null,
) {
    val profileId: String = RecordedCostArithmetic.PROFILE_ID
    override fun toString(): String = "RecordedProductInspection(observation=$observation)"
}

/**
 * Multiplies the recorded quantity, already in the recorded meter's units, by its recorded unit price.
 * Preserves the entire declaration and uses no rate lookup, usage inference, billing adjustment,
 * period conversion, floating-point arithmetic or rounding. A difference does not invalidate a
 * vendor quote/manual override; equality does not establish coverage, free treatment or a budget pass.
 */
object RecordedCostArithmetic {
    const val PROFILE_ID = "android-recorded-exact-product/1"
    const val MAX_OPERAND_CHARACTERS = 256
    const val MAX_AMOUNT_CHARACTERS = 512
    const val MAX_PRODUCT_CHARACTERS = 512

    fun inspect(line: InspectedCostLine): RecordedProductInspection {
        val quantityText = line.quantity
        val price = line.unitPrice.amount
        val amount = line.amount.amount
        // Bound all decimal conversion/comparison work before constructing any new BigDecimal.
        if (quantityText.length > MAX_OPERAND_CHARACTERS ||
            price.encoded.length > MAX_OPERAND_CHARACTERS ||
            amount.encoded.length > MAX_AMOUNT_CHARACTERS)
            return RecordedProductInspection(RecordedProductObservation.DECIMAL_LIMIT_EXCEEDED)
        val quantity = try {
            ContractDecimal.parse(quantityText)
        } catch (_: ContractRejected) {
            // The frozen schema permits some literal quantities outside this whole-string subset.
            return RecordedProductInspection(RecordedProductObservation.UNSUPPORTED_QUANTITY)
        }
        val product = quantity.exactValue().multiply(price.exactValue()).stripTrailingZeros()
        val integerPlaces = maxOf(1, product.precision() - product.scale())
        val outputCharacters = integerPlaces + if (product.scale() > 0) product.scale() + 1 else 0
        if (outputCharacters > MAX_PRODUCT_CHARACTERS)
            return RecordedProductInspection(RecordedProductObservation.DECIMAL_LIMIT_EXCEEDED)
        val exact = product.toPlainString()
        // A finite exact product can exceed the frozen money vocabulary without being uncomputable.
        if (product.scale() > 12)
            return RecordedProductInspection(RecordedProductObservation.EXACT_PRODUCT_OUTSIDE_PROFILE, exact)
        val observation = if (product.compareTo(amount.exactValue()) == 0)
            RecordedProductObservation.MATCHES_EXACT_PRODUCT
        else RecordedProductObservation.DIFFERS_FROM_EXACT_PRODUCT
        return RecordedProductInspection(observation, exact)
    }
}
