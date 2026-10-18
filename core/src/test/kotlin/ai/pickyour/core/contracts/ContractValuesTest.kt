package ai.pickyour.core.contracts

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ContractValuesTest {
    private val mapper = ObjectMapper()

    @Test fun `money retains decimal lexeme and exact large magnitude`() {
        val text = "999999999999999999999999999999999999.123456789012"
        val money = ContractMoney.fromJson(mapper.readTree("""{"amount":"$text","currency":"USD"}"""))
        assertEquals(text, money.amount.encoded)
        assertEquals(BigDecimal(text), money.amount.exactValue())
        assertEquals("1.2300", ContractDecimal.parse("1.2300").encoded)
        assertEquals(BigDecimal("0.3"), ContractDecimal.parse("0.1").exactValue() + ContractDecimal.parse("0.2").exactValue())
    }

    @Test fun `invalid decimal shapes fail instead of coercing to zero`() {
        listOf("", "01", "-1", "+1", ".1", "1.", "1e3", "NaN", "Infinity", " 1", "1\n", "0.1234567890123").forEach {
            val failure = assertThrows(ContractRejected::class.java) { ContractDecimal.parse(it) }
            assertEquals(ContractProblem.INVALID_DECIMAL, failure.problem)
            assertEquals("INVALID_DECIMAL", failure.message)
        }
    }

    @Test fun `money rejects numeric amount missing and extra fields and unknown currency shape`() {
        listOf("""{"amount":0.1,"currency":"USD"}""", """{"amount":"1","currency":"usd"}""",
            """{"amount":"1"}""", """{"amount":"1","currency":"USD","secret":"hidden"}""", "null", "[]")
            .forEach { assertThrows(ContractRejected::class.java) { ContractMoney.fromJson(mapper.readTree(it)) } }
    }

    @Test fun `version check rejects future absent numeric and null versions`() {
        requireFrozenV1(mapper.readTree("""{"schema_version":"1.0.0"}"""))
        listOf("{}", "null", """{"schema_version":"2.0.0"}""", """{"schema_version":1}""")
            .forEach { assertEquals(ContractProblem.UNSUPPORTED_VERSION,
                assertThrows(ContractRejected::class.java) { requireFrozenV1(mapper.readTree(it)) }.problem) }
    }
}
