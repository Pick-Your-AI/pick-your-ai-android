package ai.pickyour.core.evidence.placement

import ai.pickyour.core.evidence.answers.Cd1State
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger

class PlacementMemoryTest {
    @Test
    fun completeQualifiedPhysicalScopePassesConditionally() {
        val case = PlacementMemoryDraft().freeze()
        val result = case.result()
        assertEquals(PlacementOutcome.PASS, result.outcome)
        assertTrue(result.conditionalCapacity)
        assertEquals(listOf(PlacementCoverageMode.CONSERVATIVE_ALL_RESIDENT.name), result.assumptions)
        val pool = result.poolChecks().single()
        assertEquals(big(18), pool.demandUpper)
        assertEquals(big(24), pool.capacityLower)
        assertEquals(BigInteger.ZERO, pool.reserveUpper)
        assertEquals(listOf("allocation.1"), pool.chargeIds)
        assertFalse(pool.mayNotFit)
        assertEquals("error", pool.severity)
        assertEquals("review", pool.onUnknown)
    }

    @Test
    fun unifiedCpuAndGpuAllocationsChargeOnePhysicalPool() {
        val d = PlacementMemoryDraft()
        d.addDevice(TestDevice("device.cpu", "sel_cpu", "pool.1"), "compute_profile")
        d.pools[0] = TestPool(devices = listOf("device.1", "device.cpu"))
        d.consumers[0] = TestConsumer(devices = listOf("device.1", "device.cpu"))
        d.allocations += TestAllocation("allocation.cpu", bytes = "8")
        val result = d.freeze().result()
        val pool = result.poolChecks().single()
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        assertEquals(big(26), pool.demandUpper)
        assertEquals(big(24), pool.capacityLower)
        assertEquals(2, pool.chargeIds.size)
        assertTrue(pool.mayNotFit)
    }

    @Test
    fun separateDevicesNeverBorrowTheOtherDevicesCapacity() {
        val d = twoPools()
        d.allocations[0] = TestAllocation(bytes = "30")
        d.allocations += TestAllocation("allocation.2", "pool.2", bytes = "6")
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        val pools = result.poolChecks()
        assertEquals(listOf(PlacementOutcome.FAIL, PlacementOutcome.PASS), pools.map { it.outcome })
        assertEquals(listOf(big(30), big(6)), pools.map { it.demandUpper })
        assertEquals(listOf(big(24), big(24)), pools.map { it.capacityLower })
    }

    @Test
    fun actualSharedExtentIsChargedOnceForTwoQualifiedRuntimeConsumers() {
        val d = PlacementMemoryDraft()
        d.consumers += TestConsumer("consumer.2", runtime = d.input.runtime(instance = "instance.2", replica = "replica.2"))
        d.allocations[0] = TestAllocation(consumers = listOf("consumer.1", "consumer.2"))
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.PASS, result.outcome)
        assertEquals(big(18), result.poolChecks().single().demandUpper)
        assertEquals(listOf("allocation.1"), result.poolChecks().single().chargeIds)
        assertEquals(2, result.checks.count { it.rule == PlacementRule.RUNTIME_ACCESS })
    }

    @Test
    fun equalSizesAndArtifactTermsDoNotDeduplicateDistinctExtents() {
        val d = PlacementMemoryDraft()
        d.allocations += TestAllocation("allocation.2", bytes = "18")
        val result = d.freeze().result()
        assertEquals(big(36), result.poolChecks().single().demandUpper)
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        assertEquals(listOf("allocation.1", "allocation.2"), result.poolChecks().single().chargeIds)
    }

    @Test
    fun hostOffloadAndTransferCopiesKeepTheirOwnPoolAndLifetimeCharge() {
        val d = twoPools(secondDevice = "sel_cpu", secondKind = "compute_profile")
        d.allocations[0] = TestAllocation(bytes = "18", term = "device-weights")
        d.allocations += TestAllocation("allocation.host", "pool.2", bytes = "18", term = "host-offload-copy")
        d.allocations += TestAllocation("allocation.transfer", "pool.2", bytes = "8", term = "transfer-buffer")
        val result = d.freeze().result()
        assertEquals(listOf(big(18), big(26)), result.poolChecks().map { it.demandUpper })
        assertEquals(listOf(PlacementOutcome.PASS, PlacementOutcome.FAIL), result.poolChecks().map { it.outcome })
    }

    @Test
    fun anIndependentRankCeilingRemainsAnAdditionalComparison() {
        val d = PlacementMemoryDraft()
        d.limits += TestLimit(capacity = "16")
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.PASS, result.poolChecks().single().outcome)
        val ceiling = result.checks.single { it.rule == PlacementRule.INDEPENDENT_CEILING }
        assertEquals(PlacementOutcome.FAIL, ceiling.outcome)
        assertEquals(big(18), ceiling.demandUpper)
        assertEquals(big(16), ceiling.capacityLower)
        assertTrue(ceiling.mayNotFit)
        assertEquals(PlacementOutcome.FAIL, result.outcome)
    }

    @Test
    fun explicitPerRankDemandIsNotAnEqualSplitOfThePoolBudget() {
        val d = PlacementMemoryDraft()
        d.consumers += TestConsumer("consumer.2", runtime = d.input.runtime(rank = "rank.1"))
        d.allocations[0] = TestAllocation(bytes = "9")
        d.allocations += TestAllocation("allocation.2", consumers = listOf("consumer.2"), bytes = "9")
        d.limits += TestLimit(demand = "9", capacity = "8")
        d.limits += TestLimit("limit.2", consumer = "consumer.2", demand = "9", capacity = "8",
            runtime = d.input.runtime(rank = "rank.1"))
        val result = d.freeze().result()
        assertEquals(big(18), result.poolChecks().single().demandUpper)
        assertEquals(PlacementOutcome.PASS, result.poolChecks().single().outcome)
        assertEquals(2, result.checks.count { it.rule == PlacementRule.INDEPENDENT_CEILING && it.outcome == PlacementOutcome.FAIL })
    }

    @TestFactory
    fun reserveBasisAndExactZeroAreExplicit() = listOf(
        Triple(TestPool(capacity = "24", reserve = "8"), "18", PlacementOutcome.FAIL),
        Triple(TestPool(capacity = "24", reserve = "8", reserveBasis = PlacementReserveBasis.AFTER_RESERVE), "18", PlacementOutcome.PASS),
        Triple(TestPool(capacity = "0", reserve = "0"), "0", PlacementOutcome.PASS),
        Triple(TestPool(capacity = "0", reserve = "1"), "0", PlacementOutcome.FAIL),
    ).mapIndexed { index, (pool, demand, expected) ->
        DynamicTest.dynamicTest("reserve policy case " + index) {
            val d = PlacementMemoryDraft()
            d.pools[0] = pool
            d.allocations[0] = TestAllocation(bytes = demand)
            val result = d.freeze().result()
            assertEquals(expected, result.poolChecks().single().outcome)
            assertEquals(BigInteger(pool.reserve), result.poolChecks().single().reserveUpper)
            assertEquals(BigInteger(pool.capacity), result.poolChecks().single().capacityLower)
        }
    }

    @TestFactory
    fun requiredBudgetOperandsCannotDefaultToZero() = listOf(
        "/pools/pool.1/budget/capacity/lower",
        "/pools/pool.1/budget/reserve/upper",
        "/pools/pool.1/budget/reservation",
        "/pools/pool.1/budget/basis",
    ).map { path ->
        DynamicTest.dynamicTest("unresolved budget operand " + path) {
            val case = PlacementMemoryDraft().freeze { state(path, Cd1State.UNKNOWN) }
            assertEquals(PlacementOutcome.UNKNOWN, case.result().poolChecks().single().outcome)
        }
    }

    @TestFactory
    fun oneByteBoundariesRemainExactBeyondBinary64AndLong() = listOf(53, 80).map { exponent ->
        DynamicTest.dynamicTest("exact byte boundary 2^" + exponent) {
            val capacity = BigInteger.ONE.shiftLeft(exponent)
            val d = PlacementMemoryDraft()
            d.pools[0] = TestPool(capacity = capacity.toString())
            d.allocations[0] = TestAllocation(bytes = capacity.add(BigInteger.ONE).toString())
            val fail = d.freeze().result().poolChecks().single()
            assertEquals(PlacementOutcome.FAIL, fail.outcome)
            assertEquals(capacity.add(BigInteger.ONE), fail.demandUpper)
            assertEquals(capacity, fail.capacityLower)
        }
    }

    @Test
    fun qualifiedScenariosCanExcludeAllocationsWithoutRemovingAnAlwaysComponent() {
        val d = PlacementMemoryDraft()
        d.mode = PlacementCoverageMode.QUALIFIED_SCENARIOS
        d.allocations[0] = TestAllocation(bytes = "20")
        d.allocations += TestAllocation("allocation.2", bytes = "20")
        d.scenarios[0] = TestScenario(excluded = setOf("allocation.2"))
        d.scenarios += TestScenario("scenario.2", excluded = setOf("allocation.1"))
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.PASS, result.outcome)
        assertEquals(listOf(big(20), big(20)), result.poolChecks().map { it.demandUpper })
        assertEquals(2, result.checks.count { it.rule == PlacementRule.MEMORY_POOL && it.outcome == PlacementOutcome.SKIPPED })
    }

    @Test
    fun conservativeModeDoesNotReuseAQualifiedScenarioExclusionAsZero() {
        val d = PlacementMemoryDraft()
        d.allocations += TestAllocation("allocation.2", bytes = "20")
        d.scenarios[0] = TestScenario(excluded = setOf("allocation.2"))
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        assertFalse(result.checks.any { it.rule == PlacementRule.MEMORY_POOL && it.outcome == PlacementOutcome.SKIPPED })
    }

    @Test
    fun conservativeModeIncludesOnDemandComponentCharges() {
        val d = PlacementMemoryDraft()
        d.allocations[0] = TestAllocation(bytes = "30")
        val result = d.freeze { put("/components/sel_model/placement", "on_demand") }.result()
        assertEquals(PlacementOutcome.FAIL, result.poolChecks().single().outcome)
        assertEquals(big(30), result.poolChecks().single().demandUpper)
    }

    @Test
    fun omittedAlwaysComponentMakesThatScenarioIneligibleForACeilingWitness() {
        val d = PlacementMemoryDraft()
        d.mode = PlacementCoverageMode.QUALIFIED_SCENARIOS
        d.scenarios[0] = TestScenario(components = emptyList())
        d.limits += TestLimit(demand = "30", capacity = "1")
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        assertEquals(PlacementOutcome.UNKNOWN, result.checks.single { it.rule == PlacementRule.INDEPENDENT_CEILING }.outcome)
    }

    @Test
    fun missingTransitionCoverageCannotAuthorizeAnExclusion() {
        val d = PlacementMemoryDraft()
        d.mode = PlacementCoverageMode.QUALIFIED_SCENARIOS
        d.scenarios[0] = TestScenario(excluded = setOf("allocation.1"))
        val result = d.freeze { state("/memory/transitions", Cd1State.WITHHELD) }.result()
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        assertFalse(result.checks.any { it.rule == PlacementRule.MEMORY_POOL && it.outcome == PlacementOutcome.SKIPPED })
    }

    @Test
    fun knownDisjointFailureSurvivesAnotherMissingNonnegativeCharge() {
        val d = PlacementMemoryDraft()
        d.allocations[0] = TestAllocation(bytes = "30")
        d.allocations += TestAllocation("allocation.missing", bytes = "8")
        d.scenarios[0] = TestScenario(omittedRows = setOf("allocation.missing"))
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        assertEquals(big(30), result.poolChecks().single().demandUpper)
        assertTrue(result.checks.any { it.rule == PlacementRule.MEMORY_POOL && it.reason == PlacementReason.MISSING_ALLOCATION })
    }

    @TestFactory
    fun unresolvedCompletenessNeverTurnsSuppliedPassingRowsIntoACompletePass() =
        listOf("/memory/components/complete", "/memory/devices/complete", "/memory/pools/complete",
            "/memory/consumers/complete", "/memory/allocations/complete", "/memory/scenarios/complete",
            "/memory/limits/complete", "/memory/transitions", "/scenarios/scenario.1/complete").map { path ->
            DynamicTest.dynamicTest("missing independent completeness " + path) {
                val result = PlacementMemoryDraft().freeze { state(path, Cd1State.UNKNOWN) }.result()
                assertEquals(PlacementOutcome.UNKNOWN, result.outcome)
            }
        }

    @TestFactory
    fun ineligibleWitnessOperandsCannotProduceAnArithmeticFailure() =
        listOf("/pools/pool.1/space", "/allocations/allocation.1/extent",
            "/scenarios/scenario.1/rows/allocation.1/residency",
            "/scenarios/scenario.1/rows/allocation.1/bytes/upper").map { path ->
            DynamicTest.dynamicTest("ineligible witness " + path) {
                val d = PlacementMemoryDraft()
                d.allocations[0] = TestAllocation(bytes = "30")
                val case = d.freeze()
                val result = case.result { it.key.requestedPath == path }
                assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
                assertFalse(result.poolChecks().single().mayNotFit)
            }
        }

    @Test
    fun uncertainAliasingCannotBeTurnedIntoAQualifiedFailingSubset() {
        val d = PlacementMemoryDraft()
        d.allocations[0] = TestAllocation(bytes = "30", nonOverlapping = false)
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        assertNull(result.poolChecks().single().demandUpper?.takeIf { it == big(30) })
    }

    @Test
    fun runtimeAccessFailureSurvivesMissingCapacityEvidence() {
        val case = PlacementMemoryDraft().freeze {
            state("/scenarios/scenario.1/rows/allocation.1/bytes/upper", Cd1State.UNKNOWN)
            put("/scenarios/scenario.1/rows/allocation.1/access/consumer.1", false)
        }
        val result = case.result()
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        assertEquals(PlacementOutcome.FAIL, result.checks.single { it.rule == PlacementRule.RUNTIME_ACCESS }.outcome)
        assertEquals(PlacementOutcome.FAIL, result.outcome)
    }

    @Test
    fun duplicateExtentsAndContradictoryPoolMappingsRejectBeforeOpaqueRemoteSkip() {
        for (defect in listOf("extent", "reciprocal", "physical")) {
            val d = PlacementMemoryDraft()
            when (defect) {
                "extent" -> d.allocations += TestAllocation("allocation.2", extent = "extent-allocation.1")
                "reciprocal" -> d.pools[0] = TestPool(devices = emptyList())
                else -> d.pools += TestPool("pool.2", emptyList(), space = "address-space-pool.1")
            }
            assertEquals(PlacementProblem.CONTRADICTORY_INPUT, assertThrows(PlacementRejected::class.java) {
                d.freeze {
                    put("/deployment/sel_model/operator", "provider")
                    put("/deployment/sel_model/capacity", "shared")
                    put("/deployment/sel_model/exposed", false)
                    put("/components/sel_model/applies", false)
                }
            }.problem, defect)
        }
    }

    @Test
    fun danglingProvidedInventoryReferencesRejectButAnUnknownCounterpartRemainsUnknown() {
        val d = PlacementMemoryDraft()
        d.devices[0] = TestDevice(pool = "pool.absent")
        assertThrows(PlacementRejected::class.java) { d.freeze() }
        val unresolvedInventory = PlacementMemoryDraft()
        unresolvedInventory.devices[0] = TestDevice(pool = "pool.absent")
        assertThrows(PlacementRejected::class.java) {
            unresolvedInventory.freeze { state("/memory/pools/ids", Cd1State.UNKNOWN) }
        }
        val case = PlacementMemoryDraft().freeze {
            state("/memory/pools/ids", Cd1State.UNKNOWN)
            state("/pools/pool.1/devices", Cd1State.UNKNOWN)
        }
        assertEquals(PlacementOutcome.UNKNOWN, case.result().outcome)
    }

    @Test
    fun physicalMappingChecksTheExactCatalogKindOfTheSelectedDevice() {
        val d = PlacementMemoryDraft()
        val model = d.input.item("model")
        d.input.selected("sel_gpu").put("catalog_item_id", model["id"].textValue())
            .put("catalog_item_version", model["version"].textValue())
        val result = d.freeze().result()
        val device = result.checks.single { it.rule == PlacementRule.PHYSICAL_MAP && it.domain == PlacementDomain.device("device.1") }
        assertEquals(PlacementOutcome.FAIL, device.outcome)
    }

    @Test
    fun unusedPoolCannotManufactureAnOverReserveFailure() {
        val d = PlacementMemoryDraft()
        d.pools += TestPool("pool.unused", emptyList(), capacity = "0", reserve = "8")
        val result = d.freeze().result()
        assertEquals(listOf(PlacementOutcome.PASS, PlacementOutcome.SKIPPED), result.poolChecks().map { it.outcome })
        assertEquals(PlacementOutcome.PASS, result.outcome)
    }

    @Test
    fun originalQualificationChangesAndDetachedOutputsKeepTheirBoundaries() {
        val case = PlacementMemoryDraft().freeze()
        val f = case.fixture
        val before = case.result()
        f.draft.build.put("name", "Changed actual full Build, same revision")
        val changed = f.draft.freeze()
        val result = case.memory.evaluate(changed.basis, f.qs())
        assertEquals(PlacementOutcome.UNKNOWN, result.outcome)
        assertTrue(result.checks.any { it.reason == PlacementReason.CHANGED_BASIS })
        assertThrows(UnsupportedOperationException::class.java) { (before.checks as MutableList<PlacementCheck>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (case.memory.pools.records as MutableList<PlacementPool>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (before.poolChecks().single().chargeIds as MutableList<String>).clear() }
    }

    @Test
    fun checkedOnlyDeploymentAndRoleEvidencePropagatesToMemoryDecisions() {
        for (path in listOf("/deployment/sel_model/operator", "/links/link.sel_model.engine/role")) {
            val case = PlacementMemoryDraft().freeze()
            val f = case.fixture
            val original = f.records.values.single { it.key.requestedPath == path }
            f.records[original.key] = PlacementQualification(original.key, original.eligibility,
                original.evidenceInputName, original.evidenceSha256, original.conflictRankResolved, true)
            assertTrue(case.result().poolChecks().single().checkedOnly, path)
        }
    }


    @Test
    fun knownRequiredBindingFailureSurvivesUnknownMemoryQuantity() {
        val d = PlacementMemoryDraft()
        d.input.selected("sel_engine").put("enabled", false)
        val result = d.freeze().result()
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        assertTrue(result.checks.any {
            it.rule == PlacementRule.BINDING && it.outcome == PlacementOutcome.FAIL &&
                it.reason == PlacementReason.WRONG_KIND_OR_DISABLED
        })
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        assertNull(result.poolChecks().single().demandUpper)
    }

    @Test
    fun anUnknownConsumerCannotDeclareThePoolUnused() {
        val case = PlacementMemoryDraft().freeze {
            state("/consumers/consumer.1/runtime", Cd1State.UNKNOWN)
            state("/scenarios/scenario.1/rows/allocation.1/residency", Cd1State.UNKNOWN)
        }
        assertEquals(PlacementOutcome.UNKNOWN, case.result().poolChecks().single().outcome)
    }

    @Test
    fun excludedZeroDemandAndUnusedPoolChecksRetainTheirActualPrerequisiteEvidence() {
        for (path in listOf("/memory/transitions", "/scenarios/scenario.1/envelope",
                "/memory/consumers/complete", "/devices/device.1/selection")) {
            val d = PlacementMemoryDraft()
            d.mode = PlacementCoverageMode.QUALIFIED_SCENARIOS
            d.scenarios[0] = TestScenario(excluded = setOf("allocation.1"))
            d.pools += TestPool("pool.unused", emptyList(), capacity = "0", reserve = "8")
            val case = d.freeze()
            val f = case.fixture
            val original = f.records.values.single { it.key.requestedPath == path }
            f.records[original.key] = PlacementQualification(original.key, original.eligibility,
                original.evidenceInputName, original.evidenceSha256, original.conflictRankResolved, true)
            val result = case.result()
            val unused = result.poolChecks().single { it.outcome == PlacementOutcome.SKIPPED }
            assertTrue(unused.checkedOnly, path)
            if (path in setOf("/memory/transitions", "/scenarios/scenario.1/envelope")) {
                assertTrue(result.checks.single {
                    it.rule == PlacementRule.MEMORY_POOL && it.domain.tag == "allocation"
                }.checkedOnly, path)
                assertTrue(result.poolChecks().single { it.outcome == PlacementOutcome.PASS }.checkedOnly, path)
            }
        }
    }

    @Test
    fun independentAccessFailureRetainsCheckedOnlyResidencyWithUnknownBytes() {
        val residencyPath = "/scenarios/scenario.1/rows/allocation.1/residency"
        val accessPath = "/scenarios/scenario.1/rows/allocation.1/access/consumer.1"
        val case = PlacementMemoryDraft().freeze {
            state("/scenarios/scenario.1/rows/allocation.1/bytes/upper", Cd1State.UNKNOWN)
            put(accessPath, false)
        }
        val f = case.fixture
        val original = f.records.values.single { it.key.requestedPath == residencyPath }
        f.records[original.key] = PlacementQualification(original.key, original.eligibility,
            original.evidenceInputName, original.evidenceSha256, original.conflictRankResolved, true)
        val result = case.result()
        assertEquals(PlacementOutcome.UNKNOWN, result.poolChecks().single().outcome)
        val access = result.checks.single { it.rule == PlacementRule.RUNTIME_ACCESS }
        assertEquals(PlacementOutcome.FAIL, access.outcome)
        assertTrue(access.checkedOnly)
        assertEquals(listOf(residencyPath, accessPath), access.observations.map { it.requestedPath })
        assertEquals(listOf(residencyPath, accessPath), access.evidence.map { it.answer.requestedPath })
        assertTrue(access.evidence.single { it.answer.requestedPath == residencyPath }.checkedOnly)
        assertFalse(access.evidence.single { it.answer.requestedPath == accessPath }.checkedOnly)
        assertEquals(PlacementOutcome.FAIL, result.outcome)
    }

    private fun twoPools(secondDevice: String = "sel_gpu2", secondKind: String = "accelerator"): PlacementMemoryDraft {
        val d = PlacementMemoryDraft()
        d.addDevice(TestDevice("device.2", secondDevice, "pool.2"), secondKind)
        d.pools += TestPool("pool.2", listOf("device.2"))
        d.consumers[0] = TestConsumer(devices = listOf("device.1", "device.2"))
        return d
    }
}
