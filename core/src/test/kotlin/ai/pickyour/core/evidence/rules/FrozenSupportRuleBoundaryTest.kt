package ai.pickyour.core.evidence.rules

import ai.pickyour.core.evidence.membership.MembershipOutcome
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrozenSupportRuleBoundaryTest {
    private val format = FrozenSupportRule.MODEL_FORMAT.id
    private fun adapter(build: ByteArray = FrozenRuleFixtures.build,
        catalog: ByteArray = FrozenRuleFixtures.catalog) =
        FrozenSupportRuleAdapter(build, catalog, FrozenRuleFixtures.ruleset)
    private fun evidence(adapter: FrozenSupportRuleAdapter, unsupported: Set<String> = emptySet()) =
        RuleEvidence("cohort.synthetic.support", "0.1.0", adapter.supportScope(format), unsupported)

    @Test
    fun callerBytesAndEveryReturnedObservationAreDetached() {
        val build = FrozenRuleFixtures.build
        val catalog = FrozenRuleFixtures.catalog
        val ruleset = FrozenRuleFixtures.ruleset
        val subject = FrozenSupportRuleAdapter(build, catalog, ruleset)
        val expected = subject.evaluate(format, evidence(subject), FrozenRuleFixtures.qualified).details()
        build.fill(0); catalog.fill(0); ruleset.fill(0)
        subject.originalBytes.forEach { it.fill(0) }
        val result = subject.evaluate(format, evidence(subject), FrozenRuleFixtures.qualified)
        result.details().removeAll()
        (result.details()["rule"] as ObjectNode).put("severity", "invented")
        assertEquals(expected, result.details())
        assertEquals(expected, subject.evaluate(format, evidence(subject), FrozenRuleFixtures.qualified).details())
        assertEquals("error", result.details()["rule"]["severity"].textValue())
        assertEquals(FrozenRuleFixtures.digest(FrozenRuleFixtures.build), result.details()["build_sha256"].textValue())
    }

    @Test
    fun negativeCollectionsAndDecisionFindingsCannotAlterLaterCalls() {
        val subject = adapter()
        val negative = linkedSetOf("gguf")
        val cohort = evidence(subject, negative)
        negative.add("safetensors")
        val result = subject.evaluate(format, cohort, FrozenRuleFixtures.qualified, true)
        assertEquals(MembershipOutcome.PASS, result.decision.outcome)
        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> { (cohort.unsupported as MutableSet<String>).add("safetensors") }
        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> { (result.decision.findings as MutableList<Any>).clear() }
        val unqualified = subject.evaluate(format, cohort)
        assertEquals(MembershipOutcome.UNKNOWN, unqualified.decision.outcome)
        assertNull(unqualified.decision.evidenceLabel)
        assertTrue(unqualified.decision.findings.isEmpty())
        assertEquals("RuleEvidence", cohort.toString())
        assertEquals("FrozenSupportResult", result.toString())
    }

    @Test
    fun exactRulesetBytesAndOnlyTwoDeclaredRulesAreAccepted() {
        assertThrows<IllegalArgumentException> {
            FrozenSupportRuleAdapter(FrozenRuleFixtures.build, FrozenRuleFixtures.catalog, FrozenRuleFixtures.ruleset + 10)
        }
        val subject = adapter()
        for (id in listOf("", "rule.precision.engine\n", "rule.memory.agent", "rule.other")) {
            assertThrows<IllegalArgumentException> { subject.supportScope(id) }
            assertThrows<IllegalArgumentException> { subject.evaluate(id, evidence(subject)) }
        }
        assertEquals(FrozenSupportRuleAdapter.RULESET_SHA256, FrozenRuleFixtures.digest(subject.originalBytes[2]))
    }

    @Test
    fun observedLegacyIdentityIsNotSilentlyPromotedToEvidenceScope() {
        val build = FrozenRuleFixtures.mapper.readTree(FrozenRuleFixtures.build) as ObjectNode
        val selection = build["selections"].first { it["kind"].textValue() == "inference_engine" } as ObjectNode
        selection.put("id", selection["id"].textValue() + "\n")
        val subject = adapter(FrozenRuleFixtures.mapper.writeValueAsBytes(build))
        val observed = requireNotNull(subject.supportScope(format))
        assertTrue(observed.selectionId.endsWith("\n"))
        val missing = RuleEvidence("cohort.synthetic.support", "0.1.0", null, emptySet())
        val result = subject.evaluate(format, missing, FrozenRuleFixtures.qualified)
        assertEquals("support_scope_mismatch", result.decision.reason)
        assertEquals(observed.selectionId, result.details()["support_scope"]["selection_id"].textValue())
        assertThrows<IllegalArgumentException> { RuleEvidence("cohort.synthetic.support", "0.1.0", observed, emptySet()) }
    }

    @Test
    fun localRepresentationBudgetsRejectBeforeSchemaOrRuleShortcuts() {
        assertThrows<IllegalArgumentException> { FrozenRuleInput.decode(ByteArray(1_048_577) { 32 }) }
        val base = String(FrozenRuleFixtures.build, Charsets.UTF_8)
        // Exercise the actual private intake before schema validation so an
        // unknown test property cannot make a missing budget guard appear safe.
        assertEquals(1, FrozenRuleInput.decode("{\"local_budget_probe\":1}".toByteArray())
            ["local_budget_probe"].intValue())
        for (fragment in listOf("1".repeat(129), "[".repeat(33) + "0" + "]".repeat(33),
                "\"" + "x".repeat(16_385) + "\"")) {
            assertThrows<IllegalArgumentException> {
                FrozenRuleInput.decode(("{\"local_budget_probe\":" + fragment + "}").toByteArray())
            }
        }
        assertThrows<IllegalArgumentException> {
            FrozenRuleInput.decode(("{\"values\":[" + List(99_999) { "0" }.joinToString(",") + "]}").toByteArray())
        }
        assertThrows<IllegalArgumentException> { adapter(("\uFEFF" + base).toByteArray()) }
        assertThrows<IllegalArgumentException> { adapter(FrozenRuleFixtures.build + byteArrayOf(0xff.toByte())) }
    }

    @Test
    fun androidFormatAdmissionRemainsStricterThanMissingOptionalReferenceChecker() {
        // The pinned minimal Research environment has no optional date-time
        // checker. Native schema format assertions remain an explicit subset,
        // not a claimed reference rejection or shared contract change.
        val build = FrozenRuleFixtures.mapper.readTree(FrozenRuleFixtures.build) as ObjectNode
        build.put("updated_at", "2026-02-30T00:00:00Z")
        assertThrows<IllegalArgumentException> { adapter(FrozenRuleFixtures.mapper.writeValueAsBytes(build)) }
    }

    @Test
    fun finiteBinary64RoundingAndUnderflowDoNotUseCd1ExactTokenAdmission() {
        val build = String(FrozenRuleFixtures.build, Charsets.UTF_8)
        val original = FrozenRuleFixtures.mapper.readTree(build) as ObjectNode
        val model = original["selections"].first { it["kind"].textValue() == "model" } as ObjectNode
        val settings = model["settings"] as ObjectNode
        for (token in listOf("4.9e-324", "1e-999999", "-0.0", "2.0000000000000001")) {
            settings.put("temperature", "SYNTHETIC_NUMBER")
            val raw = FrozenRuleFixtures.mapper.writeValueAsString(original)
                .replace("\"SYNTHETIC_NUMBER\"", token).toByteArray()
            val subject = adapter(raw)
            assertArrayEquals(raw, subject.originalBytes[0])
            assertEquals(MembershipOutcome.PASS,
                subject.evaluate(format, evidence(subject), FrozenRuleFixtures.qualified).decision.outcome)
        }
        for (token in listOf("1e309", "2.0000000000000004")) {
            val raw = FrozenRuleFixtures.mapper.writeValueAsString(original)
                .replace("\"SYNTHETIC_NUMBER\"", token).toByteArray()
            assertThrows<IllegalArgumentException> { adapter(raw) }
        }
    }
}
