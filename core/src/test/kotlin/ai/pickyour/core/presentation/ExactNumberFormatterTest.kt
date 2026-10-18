package ai.pickyour.core.presentation

import ai.pickyour.core.contracts.ContractDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.Locale

class ExactNumberFormatterTest {
    @Test fun `locale separators preserve all twelve decimal places beyond binary float precision`() {
        val original = "9007199254740993.123456789012"
        val value = ContractDecimal.parse(original)
        assertEquals(original, ExactNumberFormatter.format(value, Locale.US))
        assertEquals("9007199254740993,123456789012", ExactNumberFormatter.format(value, Locale.GERMANY))
        assertEquals("0,000000000000", ExactNumberFormatter.format(ContractDecimal.parse("0.000000000000"), Locale.FRANCE))
        assertEquals(original, value.encoded)
    }

    @Test fun `large integers and decimals are never narrowed truncated or grouped`() {
        val integer = "9".repeat(512)
        assertEquals(integer, ExactNumberFormatter.format(BigInteger(integer), Locale.US))
        val value = ContractDecimal.parse("$integer.000000000001")
        assertEquals("$integer,000000000001", ExactNumberFormatter.format(value, Locale.GERMANY))
        assertEquals("$integer.000000000001", value.encoded)
    }

    @Test fun `explicit locale supplies digits without retaining another call's formatting state`() {
        val value = ContractDecimal.parse("1234.50")
        val arabic = Locale.forLanguageTag("ar-EG")
        assertEquals("١٢٣٤٫٥٠", ExactNumberFormatter.format(value, arabic))
        assertEquals("1234.50", ExactNumberFormatter.format(value, Locale.US))
        assertEquals("٣٠", ExactNumberFormatter.format(BigInteger.valueOf(30), arabic))
        assertEquals("1234.50", value.encoded)
    }
}
