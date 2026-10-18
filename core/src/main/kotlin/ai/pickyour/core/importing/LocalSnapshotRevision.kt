package ai.pickyour.core.importing

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger

/**
 * Existing local catalog/pricebook display budget, separate from frozen schema validity.
 * Check expanded decimal length before materializing a short exponent as a large integer.
 */
internal fun localSnapshotRevision(value: JsonNode): BigInteger? {
    if (!value.isNumber) return null
    val number = value.decimalValue()
    if (number.signum() <= 0 || number.precision().toLong() - number.scale().toLong() > 128L) return null
    return try { number.toBigIntegerExact() } catch (_: ArithmeticException) { null }
}
