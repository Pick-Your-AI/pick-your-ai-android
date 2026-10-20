package ai.pickyour.core.evidence.membership

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SupportMembershipTest {
    @Test
    fun callerCollectionsAndReturnedCollectionsCannotChangeEvidenceOrDecisions() {
        val positive = linkedSetOf("a")
        val negative = linkedSetOf("b")
        val requested = linkedSetOf("b", "a")
        val evidence = SupportEvidence("synthetic-source", true, positive, unsupported = negative)
        positive.clear()
        negative.add("a")
        val decision = SupportMembership.evaluate(requested, evidence, MembershipRequirement.ANY)
        requested.clear()
        assertEquals(setOf("a"), evidence.supported)
        assertEquals(setOf("b"), evidence.unsupported)
        assertEquals(listOf("a", "b"), decision.findings.map { it.value })
        assertEquals(listOf(MembershipOutcome.PASS, MembershipOutcome.FAIL), decision.findings.map { it.outcome })
        assertFalse(decision.disputed)
        assertThrows(UnsupportedOperationException::class.java) { (evidence.supported as MutableSet<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (evidence.unsupported as MutableSet<String>).add("a") }
        assertThrows(UnsupportedOperationException::class.java) { (decision.findings as MutableList<MembershipFinding>).clear() }
    }

    @Test
    fun defaultsDoNotInventCompletenessOrCheckedProvenance() {
        val evidence = SupportEvidence("verified", true, setOf("gguf"))
        val any = SupportMembership.evaluate(setOf("gguf"), evidence)
        assertEquals(MembershipOutcome.PASS, any.outcome)
        assertNull(any.evidenceLabel)
        val all = SupportMembership.evaluate(setOf("gguf"), evidence, MembershipRequirement.ALL)
        assertEquals(MembershipOutcome.UNKNOWN, all.outcome)
        assertEquals(MembershipDecisionReason.REQUESTED_COMPLETENESS_UNKNOWN, all.reason)
        assertEquals(MembershipOutcome.PASS, all.findings.single().outcome)
        assertNull(evidence.exhaustive)
        assertNull(evidence.checkedOnly)
        assertEquals(emptySet<String>(), evidence.unsupported)
    }

    @Test
    fun scalarOrderingKeepsByteDistinctLiteralIdentities() {
        val values = linkedSetOf("\uD800\uDC00", "\ue000", "caf\u00e9", "cafe\u0301", "a ", "a", "A")
        val decision = SupportMembership.evaluate(values, SupportEvidence("source", true, values),
            MembershipRequirement.ALL, requestedComplete = true)
        assertEquals(MembershipOutcome.PASS, decision.outcome)
        assertEquals(listOf("A", "a", "a ", "cafe\u0301", "caf\u00e9", "\ue000", "\uD800\uDC00"),
            decision.findings.map { it.value })
        assertNotEquals(values.sorted(), decision.findings.map { it.value })
        assertEquals(7, decision.findings.size)
    }

    @Test
    fun checkedOnlyLabelIsRecomputedFromTheEvidenceActuallyUsed() {
        val evidence = SupportEvidence("synthetic-source", true, null, unsupported = setOf("negative"), checkedOnly = true)
        val negative = SupportMembership.evaluate(setOf("negative"), evidence, MembershipRequirement.ALL)
        assertEquals(MembershipEvidenceLabel.CHECKED_ONLY_UNVERIFIED, negative.evidenceLabel)
        val missing = SupportMembership.evaluate(setOf("not-recorded"), evidence)
        assertEquals(MembershipFindingReason.SUPPORT_LIST_MISSING, missing.findings.single().reason)
        assertNull(missing.evidenceLabel)
        assertNull(SupportMembership.evaluate(null, evidence).evidenceLabel)
        assertNull(SupportMembership.evaluate(setOf("negative"), evidence, applicable = false).evidenceLabel)
        assertEquals(MembershipEvidenceLabel.CHECKED_ONLY_UNVERIFIED,
            SupportMembership.evaluate(setOf("negative"), evidence, MembershipRequirement.ALL).evidenceLabel)
    }

    @Test
    fun nativeTextAdmissionRejectsUnpairedSurrogatesWithoutEchoingTheInput() {
        for (invalid in listOf("\uD800", "\uDC00", "synthetic-private-\uD800x", "\uDC00\uD800")) {
            val sourceError = assertThrows(IllegalArgumentException::class.java) {
                SupportEvidence(invalid, false, null)
            }
            assertFalse(sourceError.message.orEmpty().contains("synthetic-private"))
            assertThrows(IllegalArgumentException::class.java) { SupportEvidence("source", false, setOf(invalid)) }
            assertThrows(IllegalArgumentException::class.java) {
                SupportEvidence("source", null, null, unsupported = setOf(invalid))
            }
            for (applicable in listOf<Boolean?>(null, false, true)) {
                assertThrows(IllegalArgumentException::class.java) {
                    SupportMembership.evaluate(setOf(invalid), SupportEvidence("source", false, null),
                        applicable = applicable)
                }
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun foreignRawCollectionMembersAreRejectedBeforeShortcuts() {
        for (invalid in listOf<Any?>(null, 0, true, listOf("a"))) {
            val raw = setOf(invalid) as Set<String>
            assertThrows(IllegalArgumentException::class.java) { SupportEvidence("source", false, raw) }
            assertThrows(IllegalArgumentException::class.java) {
                SupportEvidence("source", null, null, unsupported = raw)
            }
            for (applicable in listOf<Boolean?>(null, false, true)) {
                assertThrows(IllegalArgumentException::class.java) {
                    SupportMembership.evaluate(raw, SupportEvidence("source", false, null), applicable = applicable)
                }
            }
        }
    }

    @Test
    fun nativeModelsDoNotExposeSourceOrMemberValuesInAutomaticText() {
        val evidence = SupportEvidence("synthetic-private-source", true, setOf("synthetic-private-member"))
        val decision = SupportMembership.evaluate(setOf("synthetic-private-member"), evidence)
        assertEquals("SupportEvidence", evidence.toString())
        assertEquals("MembershipDecision", decision.toString())
        assertEquals("MembershipFinding", decision.findings.single().toString())
    }
}
