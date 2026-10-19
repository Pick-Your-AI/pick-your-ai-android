package ai.pickyour.core.evidence.placement

import ai.pickyour.core.evidence.answers.Cd1State
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger

class PlacementWorkloadTest {
    @Test
    fun oneRequestWithFourResidentSequencesIsNotOneKvSequence() {
        val case = workload()
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(PlacementOutcome.PASS, result.result.outcome)
        assertEquals(big(4), result.expandedDemand)
        assertEquals(big(2), result.residentSequences)
        assertTrue(result.admissionKnown)
        assertFalse(result.conservativeFallback)
    }

    @TestFactory
    fun requestAndStepBatchCapsCannotReduceTheSequenceEnvelope() = listOf("requests", "step_batch").map { unit ->
        DynamicTest.dynamicTest("wrong admission unit " + unit) {
            val case = workload { put("/admission/units", unit); put("/admission/limit", 1) }
            val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
            assertEquals(big(4), result.expandedDemand)
            assertEquals(big(4), result.residentSequences)
            assertEquals(PlacementOutcome.UNKNOWN, result.result.outcome)
            assertTrue(result.conservativeFallback)
            assertFalse(result.admissionKnown)
        }
    }

    @Test
    fun aCapForAnotherEngineBuildOrReplicaRemainsIneligible() {
        for (changed in listOf("build", "replica", "admission", "stage")) {
            val case = workload {
                val other = runtime().toMutableMap()
                other[changed] = "different-" + changed
                put("/admission/runtime", other)
            }
            val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
            assertEquals(big(4), result.residentSequences, changed)
            assertFalse(result.admissionKnown, changed)
            assertEquals(PlacementOutcome.UNKNOWN, result.result.outcome, changed)
        }
    }

    @Test
    fun retainedOrQueuedKvNeedsItsOwnAdmissionCoverage() {
        val case = workload {
            put("/admission/covers", false)
            put("/workload/fallback-covers", false)
        }
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(big(4), result.expandedDemand)
        assertNull(result.residentSequences)
        assertFalse(result.admissionKnown)
        assertFalse(result.conservativeFallback)
        assertEquals(PlacementOutcome.UNKNOWN, result.result.outcome)
    }

    @TestFactory
    fun missingExpansionDoesNotBecomeAKnownDemandFromAnAdmissionCap() =
        listOf(Cd1State.MISSING, Cd1State.UNKNOWN, Cd1State.WITHHELD, Cd1State.NOT_APPLICABLE).map { state ->
            DynamicTest.dynamicTest("unresolved sequence expansion " + state.encoded) {
                val case = workload { state("/classes/class.1/expansion", state) }
                val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
                assertNull(result.expandedDemand)
                assertNull(result.residentSequences)
                assertFalse(result.admissionKnown)
                assertEquals(PlacementOutcome.UNKNOWN, result.result.outcome)
            }
        }

    @Test
    fun unknownAdmissionCanRetainOnlyAnExplicitlyQualifiedExpandedFallback() {
        val case = workload { state("/admission/limit", Cd1State.UNKNOWN) }
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(big(4), result.expandedDemand)
        assertEquals(big(4), result.residentSequences)
        assertEquals(PlacementOutcome.UNKNOWN, result.result.outcome)
        val noFallback = case.fixture.qs { it.key.requestedPath == "/workload/fallback-covers" }
        val unqualified = case.value.evaluate(case.fixture.basis, noFallback)
        assertEquals(big(4), unqualified.expandedDemand)
        assertNull(unqualified.residentSequences)
    }

    @Test
    fun onlyACompleteSimultaneousClassInventoryCanBeSummed() {
        val case = workload(listOf("class.1", "class.2")) {
            put("/classes/class.2/requests", 3)
            put("/classes/class.2/expansion", 2)
            put("/admission/limit", 8)
        }
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(big(10), result.expandedDemand)
        assertEquals(big(8), result.residentSequences)
        val missingInventory = case.fixture.qs { it.key.requestedPath == "/classes/complete" }
        assertNull(case.value.evaluate(case.fixture.basis, missingInventory).expandedDemand)
        val notCoResident = workload(listOf("class.1", "class.2")) { put("/classes/class.2/same-scope", false) }
        assertNull(notCoResident.value.evaluate(notCoResident.fixture.basis, notCoResident.fixture.qs()).expandedDemand)
    }

    @Test
    fun exactRequestExpansionKeepsArbitraryPrecisionWithinInputLimits() {
        val count = BigInteger.ONE.shiftLeft(80)
        val case = workload {
            put("/classes/class.1/requests", count)
            put("/admission/limit", count.shiftLeft(4))
        }
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(count.shiftLeft(2), result.expandedDemand)
        assertEquals(count.shiftLeft(2), result.residentSequences)
        assertTrue(result.admissionKnown)
    }

    @TestFactory
    fun applicabilityStateDoesNotTreatMissingAsFalse() =
        listOf(Cd1State.MISSING, Cd1State.UNKNOWN, Cd1State.WITHHELD, Cd1State.NOT_APPLICABLE).map { state ->
            DynamicTest.dynamicTest("unresolved workload applicability " + state.encoded) {
                val case = workload { state("/workload/applies", state) }
                val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
                assertEquals(PlacementOutcome.UNKNOWN, result.result.outcome)
                assertNull(result.residentSequences)
            }
        }

    @Test
    fun explicitQualifiedInapplicabilitySkipsAfterStructuralAdmission() {
        val case = workload { put("/workload/applies", false) }
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(PlacementOutcome.SKIPPED, result.result.outcome)
        assertNull(result.expandedDemand)
        assertNull(result.residentSequences)
    }

    @Test
    fun duplicateAndDanglingLineageFactsCannotHideBehindDisabledWorkload() {
        for (defect in listOf("duplicate", "dangling", "too-many")) {
            assertThrows(PlacementRejected::class.java) {
                workload(factsDefect = defect) { put("/workload/applies", false) }
            }
        }
    }

    @Test
    fun aKnownRequiredDisabledEngineRemainsFailedAlongsideUnknownSequenceQuantity() {
        val case = workload { selected("sel_engine").put("enabled", false) }
        val result = case.value.evaluate(case.fixture.basis, case.fixture.qs())
        assertEquals(PlacementOutcome.FAIL, result.result.outcome)
        assertNull(result.expandedDemand)
        assertNull(result.residentSequences)
        assertTrue(result.result.checks.any { it.rule == PlacementRule.BINDING && it.outcome == PlacementOutcome.FAIL })
        assertTrue(result.result.checks.any { it.rule == PlacementRule.WORKLOAD && it.outcome == PlacementOutcome.UNKNOWN })
    }

    @Test
    fun suppliedRuntimeOriginalReferenceIsValidatedBeforeInapplicability() {
        assertThrows(PlacementRejected::class.java) {
            workload {
                put("/workload/applies", false)
                put("/workload/runtime", runtime(source = "not-retained"))
            }
        }
    }

    @Test
    fun oldBasisAndAnUnqualifiedOperationNeverReuseAQuantity() {
        val case = workload()
        val old = case.fixture
        old.draft.build.put("name", "Changed configuration")
        val fresh = old.draft.freeze()
        assertEquals(PlacementOutcome.UNKNOWN, case.value.evaluate(fresh.basis, old.qs()).result.outcome)
        assertNull(case.value.evaluate(fresh.basis, old.qs()).residentSequences)
        val unqualified = old.qs { it.key.requestedPath == "/workload/operation" }
        assertNull(case.value.evaluate(old.basis, unqualified).expandedDemand)
    }

    private class Case(val fixture: PlacementTestContext, val value: PlacementWorkload)
    private fun workload(
        classIds: List<String> = listOf("class.1"),
        factsDefect: String? = null,
        edit: PlacementTestDraft.() -> Unit = {},
    ): Case {
        val d = PlacementTestDraft().apply {
            serving()
            put("/workload/runtime", runtime())
            put("/workload/operation", operation)
            put("/workload/applies", true)
            put("/workload/fallback-covers", true)
            inventory("/classes", classIds)
            classIds.forEach { id ->
                put("/classes/" + id + "/requests", 1)
                put("/classes/" + id + "/expansion", 4)
                put("/classes/" + id + "/same-scope", true)
            }
            put("/admission/runtime", runtime())
            put("/admission/limit", 2)
            put("/admission/units", "sequences")
            put("/admission/covers", true)
            put("/unused-fact/kind", "full")
            put("/unused-fact/version", "2026.10-demo")
            put("/unused-fact/required", false)
            edit()
        }
        val f = d.freeze()
        val binding = PlacementRuntimeBinding(f.a("/workload/runtime", PlacementField.RUNTIME_BINDING))
        val domain = binding.domain ?: PlacementDomain.component("sel_model")
        val operation = f.a("/workload/operation", PlacementField.TEXT)
        val applicable = f.a("/workload/applies", PlacementField.BOOLEAN)
        val fallback = f.a("/workload/fallback-covers", PlacementField.BOOLEAN)
        listOf(binding.answer, operation, fallback).forEach { f.qualify(it, PlacementFact.WORKLOAD, domain) }
        f.qualify(applicable, PlacementFact.APPLICABILITY, domain)
        val classes = classIds.map { id ->
            val requests = f.a("/classes/" + id + "/requests", PlacementField.POSITIVE_INTEGER)
            val expansion = f.a("/classes/" + id + "/expansion", PlacementField.POSITIVE_INTEGER)
            val sameScope = f.a("/classes/" + id + "/same-scope", PlacementField.BOOLEAN)
            listOf(requests, expansion, sameScope).forEach {
                f.qualify(it, PlacementFact.WORKLOAD, PlacementDomain.workload(domain, id))
            }
            PlacementSequenceClass(id, requests, expansion, sameScope)
        }
        val admission = PlacementSequenceAdmission(
            PlacementRuntimeBinding(f.a("/admission/runtime", PlacementField.RUNTIME_BINDING)),
            f.a("/admission/limit", PlacementField.POSITIVE_INTEGER),
            f.a("/admission/units", PlacementField.ADMISSION_UNIT),
            f.a("/admission/covers", PlacementField.BOOLEAN))
        admission.observations.forEach { f.qualify(it, PlacementFact.WORKLOAD, domain) }
        val graph = f.graph()
        val requirements = f.requirements().single()
        val ordinaryFact = PlacementArtifactFacts(if (factsDefect == "dangling") "sel_absent" else "sel_model",
            f.a("/unused-fact/kind", PlacementField.ARTIFACT_KIND),
            f.a("/unused-fact/version", PlacementField.TEXT),
            f.a("/unused-fact/required", PlacementField.BOOLEAN))
        val facts = when (factsDefect) {
            "duplicate" -> listOf(ordinaryFact, ordinaryFact)
            "dangling" -> listOf(ordinaryFact)
            "too-many" -> List(PlacementBasis.MAX_SELECTIONS + 1) { ordinaryFact }
            else -> emptyList()
        }
        val value = PlacementWorkload(f.basis, graph, requirements, facts, binding, operation, applicable,
            f.inventory("/classes", classes, PlacementCoverageKind.WORKLOAD_STAGES), admission, fallback)
        return Case(f, value)
    }
}
