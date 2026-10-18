package ai.pickyour.core.evidence.answers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private val runtimeMapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)

/** Only after bounded UTF-8 syntax and original-token admission have succeeded.
 * This private runtime view retains plain BigInteger versus binary64 tokens,
 * including negative zero. Its rendered bytes are never an original input.
 */
internal fun cd1RuntimeTree(checkedRaw: ByteArray): JsonNode =
    runtimeMapper.readTree(checkedRaw.toString(Charsets.UTF_8))

/** Match the selected Python reference's decimal/float admission, retaining original bytes separately. */
internal fun requireCd1Numbers(root: JsonNode) {
    val pending = ArrayDeque<JsonNode>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val value = pending.removeLast()
        if (value.isContainerNode) value.elements().forEachRemaining { pending.add(it) }
        if (!value.isFloatingPointNumber) continue // Plain integer tokens keep arbitrary precision within the input budget.
        val original = value.decimalValue()
        val number = original.toDouble()
        cd1Require(number.isFinite(), Cd1Problem.UNSUPPORTED_NUMBER)
        cd1Require(original.compareTo(shortestDecimal(number)) == 0, Cd1Problem.UNSUPPORTED_NUMBER)
        if (number % 1.0 == 0.0) {
            cd1Require(original.compareTo(BigDecimal(number)) == 0, Cd1Problem.UNSUPPORTED_NUMBER)
        }
    }
}

/**
 * Python uses the nearest shortest round-tripping decimal, including one-digit
 * subnormals such as 5e-324. Java's rendering can require 4.9E-324 instead. Find
 * the bounded 1..17 significant-digit candidate using exact decimal distances;
 * this avoids treating either runtime's display spelling as a numeric fact.
 */
private fun shortestDecimal(number: Double): BigDecimal {
    if (number == 0.0) return BigDecimal.ZERO
    val exact = BigDecimal(number)
    for (precision in 1..17) {
        val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        val step = BigDecimal.ONE.scaleByPowerOfTen(rounded.precision() - rounded.scale() - precision)
        val candidates = listOf(rounded, rounded.subtract(step), rounded.add(step))
            .filter { it.toDouble().toRawBits() == number.toRawBits() }
        if (candidates.isNotEmpty()) return candidates.minWithOrNull { a, b ->
            val distance = a.subtract(exact).abs().compareTo(b.subtract(exact).abs())
            if (distance != 0) distance else {
                // Equal-distance shortest representations use an even last digit.
                val ae = a.stripTrailingZeros().unscaledValue().abs().testBit(0)
                val be = b.stripTrailingZeros().unscaledValue().abs().testBit(0)
                ae.compareTo(be)
            }
        }!!
    }
    throw Cd1Rejected(Cd1Problem.UNSUPPORTED_NUMBER)
}
