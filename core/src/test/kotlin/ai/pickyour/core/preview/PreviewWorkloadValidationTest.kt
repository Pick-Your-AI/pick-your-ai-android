package ai.pickyour.core.preview

import ai.pickyour.core.drafts.DraftDataClassification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class PreviewWorkloadValidationTest {
    @Test fun emptyInputsStayUnansweredAndEveryTypedClassificationIsValid() {
        for (classification in listOf(null) + DraftDataClassification.entries) {
            val input = PreviewWorkloadInputs(classification = classification)
            assertTrue(PreviewWorkloadValidation.invalidFields(input).isEmpty())
            val value = input.parsed()!!
            assertNull(value.region)
            assertNull(value.currency)
            assertNull(value.monthlyBudgetAmount)
            assertNull(value.requestsPerDay)
            assertNull(value.maxConcurrency)
            assertEquals(classification, value.dataClassification)
        }
    }

    @Test fun independentInvalidFieldsDoNotBlameValidOrUnansweredValues() {
        val input = PreviewWorkloadInputs(region = "unknown", currency = "eur", budget = "1.2300",
            requests = "0", inputTokens = "01", outputTokens = "9", concurrency = "0", latency = "1")
        assertEquals(setOf(PreviewWorkloadField.CURRENCY, PreviewWorkloadField.INPUT_TOKENS,
            PreviewWorkloadField.CONCURRENCY), PreviewWorkloadValidation.invalidFields(input))
        assertNull(input.parsed())
        assertEquals("unknown", input.region)
        assertEquals("1.2300", input.budget)
        assertEquals("01", input.inputTokens)
    }

    @Test fun budgetFeedbackPreservesCanonicalDecimalPrecisionAndRejectsLocaleCoercion() {
        for (text in listOf("1,2", "1e3", "+1", "01", "1.", ".1", "-0", "0.1234567890123", "NaN", "Infinity", "unknown", " 1")) {
            val input = PreviewWorkloadInputs(budget = text)
            assertEquals(setOf(PreviewWorkloadField.BUDGET), PreviewWorkloadValidation.invalidFields(input))
            assertNull(input.parsed())
            assertEquals(text, input.budget)
        }
        for (text in listOf("0", "0.0000", "0.000000000001", "12345678901234567890.123456789012")) {
            val input = PreviewWorkloadInputs(budget = text)
            assertTrue(PreviewWorkloadValidation.invalidFields(input).isEmpty())
            assertEquals(text, input.parsed()!!.monthlyBudgetAmount!!.encoded)
            assertNull(input.parsed()!!.currency)
        }
    }

    @Test fun integerFeedbackUsesExactBoundsAndNeverTreatsUnknownAsAMeasurement() {
        val wide = "9".repeat(128)
        val valid = PreviewWorkloadInputs(requests = "0", inputTokens = wide, outputTokens = "0",
            concurrency = "1", latency = "1")
        assertTrue(PreviewWorkloadValidation.invalidFields(valid).isEmpty())
        assertEquals(BigInteger(wide), valid.parsed()!!.avgInputTokens)
        assertEquals(BigInteger.ZERO, valid.parsed()!!.requestsPerDay)
        for (text in listOf("unknown", "-1", "+1", "01", "1.0", "1e3", " 1", "١", "9".repeat(129))) {
            val input = PreviewWorkloadInputs(requests = text, inputTokens = text, outputTokens = text,
                concurrency = text, latency = text)
            assertEquals(setOf(PreviewWorkloadField.REQUESTS, PreviewWorkloadField.INPUT_TOKENS,
                PreviewWorkloadField.OUTPUT_TOKENS, PreviewWorkloadField.CONCURRENCY,
                PreviewWorkloadField.LATENCY), PreviewWorkloadValidation.invalidFields(input))
            assertNull(input.parsed())
        }
        val zeroes = PreviewWorkloadInputs(requests = "0", inputTokens = "0", outputTokens = "0",
            concurrency = "0", latency = "0")
        assertEquals(setOf(PreviewWorkloadField.CONCURRENCY, PreviewWorkloadField.LATENCY),
            PreviewWorkloadValidation.invalidFields(zeroes))
    }

    @Test fun textLimitsUseTheExistingStringBudgetWithoutTrimmingOrTruncation() {
        val exact = PreviewWorkloadInputs(region = "r".repeat(16_384), budget = "9".repeat(16_384))
        assertTrue(PreviewWorkloadValidation.invalidFields(exact).isEmpty())
        assertEquals(exact.region, exact.parsed()!!.region)
        assertEquals(exact.budget, exact.parsed()!!.monthlyBudgetAmount!!.encoded)
        val tooLong = PreviewWorkloadInputs(region = exact.region + "r", budget = exact.budget + "9")
        assertEquals(setOf(PreviewWorkloadField.REGION, PreviewWorkloadField.BUDGET),
            PreviewWorkloadValidation.invalidFields(tooLong))
        assertEquals(16_385, tooLong.region.length)
        assertEquals(16_385, tooLong.budget.length)
        val paired = PreviewWorkloadInputs(region = "\uD83D\uDE00".repeat(8_192))
        assertTrue(PreviewWorkloadValidation.invalidFields(paired).isEmpty())
        assertEquals(setOf(PreviewWorkloadField.REGION),
            PreviewWorkloadValidation.invalidFields(paired.copy(region = paired.region + "x")))
        assertTrue(PreviewWorkloadValidation.invalidFields(PreviewWorkloadInputs(region = " unknown ")).isEmpty())
    }

    @Test fun currencyFeedbackUsesTheExistingFullUppercasePattern() {
        for (text in listOf("usd", "USDD", "US", "US1", " USD", "USD\n", "unknown", "ＵＳＤ")) {
            val input = PreviewWorkloadInputs(currency = text)
            assertEquals(setOf(PreviewWorkloadField.CURRENCY), PreviewWorkloadValidation.invalidFields(input))
            assertNull(input.parsed())
        }
        assertTrue(PreviewWorkloadValidation.invalidFields(PreviewWorkloadInputs(currency = "EUR")).isEmpty())
    }

    @Test fun rejectedWholeEditRemainsAFormFailureWithoutInventingAFieldFailure() {
        val input = PreviewWorkloadInputs(budget = "12.3400", rejectedEdit = true)
        assertTrue(PreviewWorkloadValidation.invalidFields(input).isEmpty())
        assertNull(input.parsed())
        assertTrue(input.rejectedEdit)
        assertEquals("12.3400", input.budget)
        val alsoInvalid = input.copy(currency = "USDD")
        assertEquals(setOf(PreviewWorkloadField.CURRENCY), PreviewWorkloadValidation.invalidFields(alsoInvalid))
        assertNull(alsoInvalid.parsed())
    }

    @Test fun feedbackIsImmutableValueFreeAndIndependentOfLaterCorrections() {
        val input = PreviewWorkloadInputs(currency = "PRIVATE_CURRENCY_CANARY", budget = "PRIVATE_BUDGET_CANARY")
        val errors = PreviewWorkloadValidation.invalidFields(input)
        assertEquals(setOf(PreviewWorkloadField.CURRENCY, PreviewWorkloadField.BUDGET), errors)
        assertFalse(errors.toString().contains("PRIVATE_"))
        assertEquals("PreviewWorkloadInputs", input.toString())
        assertThrows(UnsupportedOperationException::class.java) { (errors as MutableSet<PreviewWorkloadField>).clear() }
        val corrected = input.copy(currency = "EUR", budget = "12.3400")
        assertTrue(PreviewWorkloadValidation.invalidFields(corrected).isEmpty())
        assertEquals(setOf(PreviewWorkloadField.CURRENCY, PreviewWorkloadField.BUDGET), errors)
        assertEquals("PRIVATE_CURRENCY_CANARY", input.currency)
        assertEquals("12.3400", corrected.parsed()!!.monthlyBudgetAmount!!.encoded)
    }
}
