package ai.pickyour.core.drafts

import ai.pickyour.core.contracts.ContractDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class DraftWorkloadTest {
    @Test fun `empty workload is entirely unanswered without default facts`() {
        val workload = DraftContent().workload
        assertEquals(DraftWorkload(), workload)
        assertNull(workload.region)
        assertNull(workload.currency)
        assertNull(workload.monthlyBudgetAmount)
        assertNull(workload.requestsPerDay)
        assertNull(workload.avgInputTokens)
        assertNull(workload.avgOutputTokens)
        assertNull(workload.maxConcurrency)
        assertNull(workload.maxLatencyMs)
        assertNull(workload.dataClassification)
        assertEquals("DraftWorkload", workload.copy(region = "private input").toString())
        assertEquals("DraftContent", DraftContent(workload = workload).toString())
    }

    @Test fun `schema bounds reject invalid entered values without narrowing valid regions or numbers`() {
        val invalid: List<() -> Unit> = listOf(
            { DraftWorkload(region = "") },
            { DraftWorkload(currency = "usd") }, { DraftWorkload(currency = "US") },
            { DraftWorkload(currency = " USD") }, { DraftWorkload(currency = "US1") },
            { DraftWorkload(currency = "USD\n") },
            { DraftWorkload(requestsPerDay = BigInteger.valueOf(-1)) },
            { DraftWorkload(avgInputTokens = BigInteger.valueOf(-1)) },
            { DraftWorkload(avgOutputTokens = BigInteger.valueOf(-1)) },
            { DraftWorkload(maxConcurrency = BigInteger.ZERO) },
            { DraftWorkload(maxConcurrency = BigInteger.valueOf(-1)) },
            { DraftWorkload(maxLatencyMs = BigInteger.ZERO) },
            { DraftWorkload(maxLatencyMs = BigInteger.valueOf(-1)) },
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
        // The schema says minLength 1, not isNotBlank, an enum of known regions or a machine-width cap.
        assertEquals(" ", DraftWorkload(region = " ").region)
        val huge = BigInteger("9".repeat(128))
        val workload = DraftWorkload(requestsPerDay = BigInteger.ZERO, avgInputTokens = BigInteger.ZERO,
            avgOutputTokens = huge, maxConcurrency = huge, maxLatencyMs = huge)
        assertEquals(huge, workload.maxConcurrency)
        assertEquals(huge, workload.maxLatencyMs)
    }

    @Test fun `every workload input participates in equality hashing and tier edits`() {
        val empty = DraftWorkload()
        val variants = listOf(
            empty.copy(region = "entered"), empty.copy(currency = "USD"),
            empty.copy(monthlyBudgetAmount = ContractDecimal.parse("0.00")),
            empty.copy(requestsPerDay = BigInteger.ZERO), empty.copy(avgInputTokens = BigInteger.ZERO),
            empty.copy(avgOutputTokens = BigInteger.ZERO), empty.copy(maxConcurrency = BigInteger.ONE),
            empty.copy(maxLatencyMs = BigInteger.ONE), empty.copy(dataClassification = DraftDataClassification.INTERNAL),
        )
        for (workload in variants) {
            val content = DraftContent(workload = workload)
            assertNotEquals(DraftContent(), content)
            assertEquals(content, DraftContent(workload = workload.copy()))
            assertEquals(content.hashCode(), DraftContent(workload = workload.copy()).hashCode())
            assertEquals(workload, content.withTier(OptionalTier.T3, TierDraft(Activation.UNKNOWN)).workload)
        }
        assertEquals(variants.size + 1, (variants.map { DraftContent(workload = it) } + DraftContent()).toSet().size)
        val budget = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("12.3400"))
        assertNull(budget.currency)
        assertEquals("12.3400", budget.monthlyBudgetAmount!!.encoded)
        assertNotEquals(budget, budget.copy(monthlyBudgetAmount = ContractDecimal.parse("12.34")))
    }

    @Test fun `history workload edits and restore preserve current metadata and original values`() {
        val original = DraftContent(workload = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("0.00")))
        val history = DraftHistory(original, mapOf("pin" to "old"))
        val first = history.head()
        val next = (history.edit(first, DraftContent(workload = DraftWorkload(requestsPerDay = BigInteger.ZERO)),
            mapOf("pin" to "current")) as DraftEditResult.Applied).revision
        assertTrue(history.edit(next, next.content) is DraftEditResult.Unchanged)
        assertTrue(history.edit(first, next.content) is DraftEditResult.Conflict)
        val restored = (history.restore(next, first) as DraftEditResult.Applied).revision
        assertEquals(original.workload, restored.content.workload)
        assertEquals(mapOf("pin" to "current"), restored.metadata)
        assertEquals(mapOf("pin" to "old"), first.metadata)
        assertEquals(original, first.content)
    }
}
