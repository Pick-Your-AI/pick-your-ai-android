package ai.pickyour.core.evidence.placement.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger

class ContextBudgetMathTest {
    private fun n(value: Long) = BigInteger.valueOf(value)
    private fun range(lower: Long, upper: Long = lower) = ContextTokenRange(n(lower), n(upper))
    private fun shared() = ContextBudget(
        ContextSemantics.SHARED, range(7000), n(2000), n(4000), sharedCap = n(8000),
    )

    @Test fun sharedWindowRequiresInputPlusReservedOutput() {
        val result = ContextBudgetMath.evaluate(shared())
        assertEquals(ContextOutcome.FAIL, result.outcome)
        assertFalse(result.mayNotFit)
        assertEquals(
            listOf(
                ContextCheck(ContextCheckName.OUTPUT, ContextOutcome.PASS, ContextOutcome.PASS),
                ContextCheck(ContextCheckName.COMBINED, ContextOutcome.FAIL, ContextOutcome.FAIL),
            ),
            result.checks,
        )
    }

    @Test fun separateBudgetsDoNotInventASharedWindow() {
        // The pure normalized reference ignores an unused shared cap. The wire reader separately rejects its declaration.
        val result = ContextBudgetMath.evaluate(shared().copy(semantics = ContextSemantics.SEPARATE, inputCap = n(8000)))
        assertEquals(ContextOutcome.PASS, result.outcome)
        assertEquals(
            ContextCheck(ContextCheckName.COMBINED, ContextOutcome.SKIPPED, ContextOutcome.SKIPPED),
            result.checks.single { it.name == ContextCheckName.COMBINED },
        )
        assertEquals(ContextOutcome.PASS, result.checks.single { it.name == ContextCheckName.INPUT }.upper)
    }

    @Test fun inclusiveUpperBoundaryAndStraddleHaveDifferentResults() {
        val atLimit = ContextBudgetMath.evaluate(shared().copy(inputTokens = range(5000, 6000)))
        assertEquals(ContextOutcome.PASS, atLimit.outcome)
        assertFalse(atLimit.mayNotFit)
        val straddle = ContextBudgetMath.evaluate(shared().copy(inputTokens = range(5500, 6500)))
        assertEquals(ContextOutcome.FAIL, straddle.outcome)
        assertTrue(straddle.mayNotFit)
        assertEquals(
            ContextCheck(ContextCheckName.COMBINED, ContextOutcome.PASS, ContextOutcome.FAIL),
            straddle.checks.single { it.name == ContextCheckName.COMBINED },
        )
    }

    @Test fun unresolvedOtherOperandDoesNotHideAQualifiedStraddle() {
        for (budget in listOf(
            shared().copy(inputTokens = range(5500, 6500), outputCap = null),
            shared().copy(
                semantics = ContextSemantics.SEPARATE, inputTokens = range(7500, 8500),
                inputCap = n(8000), outputCap = null,
            ),
            shared().copy(semantics = ContextSemantics.UNKNOWN, inputTokens = range(5500, 6500), inputCap = n(6000)),
        )) {
            val result = ContextBudgetMath.evaluate(budget)
            assertEquals(ContextOutcome.FAIL, result.outcome)
            assertTrue(result.mayNotFit)
        }
    }

    @Test fun definiteOutputFailureRemovesAnUncertaintyOnlyLabel() {
        val result = ContextBudgetMath.evaluate(shared().copy(inputTokens = range(5500, 6500), outputCap = n(1000)))
        assertEquals(ContextOutcome.FAIL, result.outcome)
        assertFalse(result.mayNotFit)
        assertEquals(ContextOutcome.FAIL, result.checks.single { it.name == ContextCheckName.OUTPUT }.lower)
    }

    @Test fun qualifiedFailuresSurviveOtherUnknownFacts() {
        assertEquals(
            ContextOutcome.FAIL,
            ContextBudgetMath.evaluate(shared().copy(inputTokens = null, reservedOutput = n(4001))).outcome,
        )
        val result = ContextBudgetMath.evaluate(shared().copy(semantics = ContextSemantics.UNKNOWN, inputCap = n(6000)))
        assertEquals(ContextOutcome.FAIL, result.outcome)
        assertFalse(result.mayNotFit)
        assertEquals(
            ContextOutcome.UNKNOWN,
            ContextBudgetMath.evaluate(shared().copy(semantics = ContextSemantics.UNKNOWN, inputCap = n(8000))).outcome,
        )
    }

    @TestFactory fun eachMissingRequiredSharedOperandStaysUnknown(): List<DynamicTest> {
        val fitting = shared().copy(inputTokens = range(5000))
        return listOf(
            "input" to fitting.copy(inputTokens = null),
            "reserved_output" to fitting.copy(reservedOutput = null),
            "output_cap" to fitting.copy(outputCap = null),
            "shared_cap" to fitting.copy(sharedCap = null),
            "semantics" to fitting.copy(semantics = ContextSemantics.UNKNOWN),
        ).map { (name, budget) -> DynamicTest.dynamicTest("missing_required_" + name) {
            val result = ContextBudgetMath.evaluate(budget)
            assertEquals(ContextOutcome.UNKNOWN, result.outcome)
            assertFalse(result.mayNotFit)
        } }
    }

    @Test fun separateInputAndOutputFailIndependentlyAndMissingInputCapIsUnknown() {
        val base = shared().copy(semantics = ContextSemantics.SEPARATE, inputCap = n(8000))
        for (budget in listOf(base.copy(inputTokens = range(8001)), base.copy(reservedOutput = n(4001)))) {
            assertEquals(ContextOutcome.FAIL, ContextBudgetMath.evaluate(budget).outcome)
        }
        assertEquals(ContextOutcome.UNKNOWN, ContextBudgetMath.evaluate(base.copy(inputCap = null)).outcome)
    }

    @Test fun policyTotalIsAnAdditionalConstraintUnderEitherSemantics() {
        for (semantics in listOf(ContextSemantics.SHARED, ContextSemantics.SEPARATE)) {
            val result = ContextBudgetMath.evaluate(shared().copy(
                semantics = semantics, sharedCap = n(10000), inputCap = n(8000), policyTotalCap = n(8500),
            ))
            assertEquals(ContextOutcome.FAIL, result.outcome)
            assertEquals(
                ContextCheck(ContextCheckName.POLICY_TOTAL, ContextOutcome.FAIL, ContextOutcome.FAIL),
                result.checks.single { it.name == ContextCheckName.POLICY_TOTAL },
            )
        }
    }

    @Test fun zeroOutputIsKnownAndZeroPolicyIsAnActualCeiling() {
        val budget = shared().copy(inputTokens = range(8000), reservedOutput = BigInteger.ZERO, outputCap = BigInteger.ZERO)
        assertEquals(ContextOutcome.PASS, ContextBudgetMath.evaluate(budget).outcome)
        assertEquals(ContextOutcome.FAIL, ContextBudgetMath.evaluate(budget.copy(policyTotalCap = BigInteger.ZERO)).outcome)
    }

    @Test fun arbitraryPrecisionKeepsAOneTokenBoundaryBeyondLong() {
        val input = BigInteger.ONE.shiftLeft(80)
        val budget = shared().copy(
            inputTokens = ContextTokenRange(input, input), sharedCap = input + n(1999),
        )
        assertEquals(ContextOutcome.FAIL, ContextBudgetMath.evaluate(budget).outcome)
        assertEquals(
            ContextOutcome.PASS, ContextBudgetMath.evaluate(budget.copy(sharedCap = input + n(2000))).outcome,
        )
    }

    @Test fun mismatchContributesNoArithmeticAndDisabledSelectionSkips() {
        val mismatch = ContextBudgetMath.evaluate(shared().copy(tupleMatches = false))
        assertEquals(ContextOutcome.UNKNOWN, mismatch.outcome)
        assertTrue(mismatch.checks.isEmpty())
        val disabled = ContextBudgetMath.evaluate(shared().copy(enabled = false, tupleMatches = false))
        assertEquals(ContextOutcome.SKIPPED, disabled.outcome)
        assertTrue(disabled.checks.isEmpty())
        assertFalse(disabled.mayNotFit)
    }

    @TestFactory fun exactFourOutcomeAggregationTable(): List<DynamicTest> {
        val states = listOf(ContextOutcome.PASS, ContextOutcome.FAIL, ContextOutcome.UNKNOWN, ContextOutcome.SKIPPED)
        val expected = listOf(
            listOf(ContextOutcome.PASS, ContextOutcome.FAIL, ContextOutcome.UNKNOWN, ContextOutcome.PASS),
            listOf(ContextOutcome.FAIL, ContextOutcome.FAIL, ContextOutcome.FAIL, ContextOutcome.FAIL),
            listOf(ContextOutcome.UNKNOWN, ContextOutcome.FAIL, ContextOutcome.UNKNOWN, ContextOutcome.UNKNOWN),
            listOf(ContextOutcome.PASS, ContextOutcome.FAIL, ContextOutcome.UNKNOWN, ContextOutcome.SKIPPED),
        )
        return states.flatMapIndexed { i, first ->
            states.mapIndexed { j, second -> DynamicTest.dynamicTest(first.wireValue + "_and_" + second.wireValue) {
                assertEquals(expected[i][j], ContextBudgetMath.combine(listOf(first, second)))
            } }
        }
    }

    @Test fun emptyAggregateSkipsAndAnIndependentFailureSurvivesUnknown() {
        assertEquals(ContextOutcome.SKIPPED, ContextBudgetMath.combine(emptyList()))
        assertEquals(ContextOutcome.FAIL, ContextBudgetMath.combine(listOf(ContextOutcome.UNKNOWN, ContextOutcome.FAIL)))
    }

    @Test fun extensionRequiresExplicitEvidenceAndObeysTheRuntimeCeiling() {
        assertEquals(n(32768), ContextBudgetMath.effectiveLimit(n(32768), extension = n(131072)))
        assertEquals(n(131072), ContextBudgetMath.effectiveLimit(n(32768), extension = n(131072), extensionEvidenced = true))
        assertEquals(
            n(65536), ContextBudgetMath.effectiveLimit(
                n(32768), extension = n(131072), extensionEvidenced = true, runtimeCeiling = n(65536),
            ),
        )
        assertEquals(n(16384), ContextBudgetMath.effectiveLimit(n(32768), runtimeCeiling = n(16384)))
        assertNull(ContextBudgetMath.effectiveLimit(null, extension = n(131072), extensionEvidenced = true))
        assertNull(ContextBudgetMath.effectiveLimit(null, runtimeCeiling = n(65536)))
    }

    @Test fun normalizedTypesRejectInvalidCountsAndKeepOutputsImmutable() {
        assertThrows(IllegalArgumentException::class.java) { range(0) }
        assertThrows(IllegalArgumentException::class.java) { range(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { shared().copy(reservedOutput = n(-1)) }
        assertThrows(IllegalArgumentException::class.java) { shared().copy(inputCap = BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { ContextBudgetMath.effectiveLimit(n(1), extension = n(-1)) }
        val result = ContextBudgetMath.evaluate(shared())
        assertThrows(UnsupportedOperationException::class.java) { (result.checks as MutableList<ContextCheck>).clear() }
        assertEquals(2, result.checks.size)
        assertFalse(shared().toString().contains("7000"))
        assertFalse(range(7000).toString().contains("7000"))
    }
}
