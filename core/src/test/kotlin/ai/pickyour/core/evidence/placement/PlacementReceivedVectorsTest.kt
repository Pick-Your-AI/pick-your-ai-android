package ai.pickyour.core.evidence.placement

import ai.pickyour.core.evidence.answers.Cd1State
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger

/** Exact synthetic D3/B3/M1/M3 receiving examples; these quantities are not measured capacity. */
class PlacementReceivedVectorsTest {
    @Test
    fun b3DefensiveNormalizedAdapterCycleRemainsUnresolved() {
        // The selected CD-6 reference traverses synthetic adapter cycles. This carrier is
        // structurally admitted only: frozen-v1 base_model_ref still describes a model,
        // so these adapter-to-adapter references do not prove valid frozen-v1 lineage.
        val draft = PlacementTestDraft().apply {
            selection("sel_adapter_a", "fine_tuned_model")
            selection("sel_adapter_b", "fine_tuned_model")
            val first = item("fine_tuned_model")
            val second = first.deepCopy().put("id", "synthetic.adapter-b")
            first.put("id", "synthetic.adapter-a")
            catalog.withArray("items").add(second)
            (first["spec"] as ObjectNode).put("adapter_only", true)
                .put("base_model_ref", second["id"].textValue())
            (second["spec"] as ObjectNode).put("adapter_only", true)
                .put("base_model_ref", first["id"].textValue())
            selected("sel_adapter_a").put("catalog_item_id", first["id"].textValue())
            selected("sel_adapter_b").put("catalog_item_id", second["id"].textValue())
            link("link.a.base", "sel_adapter_a", "sel_adapter_b", PlacementRole.BASE)
            link("link.b.base", "sel_adapter_b", "sel_adapter_a", PlacementRole.BASE)
            artifact("sel_adapter_a", "adapter", second["version"].textValue())
            artifact("sel_adapter_b", "adapter", first["version"].textValue())
        }
        val originalBuild = draft.mapper.writeValueAsBytes(draft.build)
        val originalCatalog = draft.mapper.writeValueAsBytes(draft.catalog)
        val fixture = draft.freeze()
        val graph = fixture.graph()
        val artifacts = fixture.artifacts()
        val result = graph.lineage(fixture.basis, fixture.qs(), "sel_adapter_a", artifacts)
        assertEquals(listOf("sel_adapter_a", "sel_adapter_b"), result.artifacts.map { it.id })
        assertNull(result.servingArtifact)
        val cycle = result.checks.single { it.reason == PlacementReason.BINDING_CYCLE }
        assertEquals(PlacementOutcome.UNKNOWN, cycle.outcome)
        assertEquals(PlacementOutcome.UNKNOWN, result.outcome)
        assertTrue(cycle.evidence.isNotEmpty())
        assertTrue(cycle.observations.any { it.requestedPath == "/artifacts/sel_adapter_a/kind" })
        assertTrue(cycle.observations.any { it.requestedPath == "/artifacts/sel_adapter_b/version" })
        assertArrayEquals(originalBuild, fixture.basis.originalBuildBytes())
        assertArrayEquals(originalCatalog, checkNotNull(fixture.basis.input("catalog")).originalBytes())
    }

    @TestFactory
    fun unknownProviderFactsNeverAuthorizeALocalOnlySkip() = listOf(
        "capacity" to PlacementMode.UNKNOWN,
        "exposed" to PlacementMode.MANAGED,
    ).map { (field, expectedMode) ->
        DynamicTest.dynamicTest("D3 unknown provider " + field) {
            val draft = PlacementTestDraft().apply {
                serving(operator = "provider", capacity = "dedicated_instances", exposed = true)
                condition("sel_model", "local-hardware", PlacementConditionKind.LOCAL_HARDWARE, false)
                condition("sel_model", "independent-service", PlacementConditionKind.SERVICE_SUPPORT, false)
                state("/deployment/sel_model/" + field, Cd1State.UNKNOWN)
            }
            val fixture = draft.freeze()
            val deployment = fixture.deployment()
            val result = deployment.evaluate(fixture.basis, fixture.qs())
            val component = result.components.single()
            assertEquals(expectedMode, result.mode)
            assertEquals(PlacementApplicability.UNKNOWN, component.localApplicability)
            assertEquals(listOf(PlacementOutcome.UNKNOWN), component.checks.filter {
                it.rule == PlacementRule.LOCAL_HARDWARE
            }.map { it.outcome })
            assertTrue(component.checks.any {
                it.rule == PlacementRule.RUNTIME_ACCESS && it.outcome == PlacementOutcome.FAIL
            })
            assertEquals(PlacementOutcome.FAIL, result.result.outcome)
        }
    }

    @Test
    fun m1CentralUmaExampleUsesExactBillionByteUnits() {
        val draft = PlacementMemoryDraft()
        draft.addDevice(TestDevice("device.cpu", "sel_cpu", "pool.1"), "compute_profile")
        draft.pools[0] = TestPool(devices = listOf("device.1", "device.cpu"), capacity = units(24).toString())
        draft.consumers[0] = TestConsumer(devices = listOf("device.1", "device.cpu"))
        draft.allocations[0] = TestAllocation(bytes = units(18).toString())
        draft.allocations += TestAllocation("allocation.cpu", bytes = units(8).toString())
        val result = draft.freeze().result()
        val pool = result.poolChecks().single()
        assertEquals(units(26), pool.demandUpper)
        assertEquals(units(24), pool.capacityLower)
        assertEquals(BigInteger.ZERO, pool.reserveUpper)
        assertEquals(listOf("allocation.1", "allocation.cpu"), pool.chargeIds)
        assertEquals(PlacementOutcome.FAIL, pool.outcome)
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        assertTrue(result.conditionalCapacity)
    }

    @Test
    fun m1CentralDiscreteExampleNeverPoolsTwoDeviceBudgets() {
        val draft = PlacementMemoryDraft()
        draft.addDevice(TestDevice("device.2", "sel_gpu2", "pool.2"), "accelerator")
        draft.pools[0] = TestPool(capacity = units(24).toString())
        draft.pools += TestPool("pool.2", listOf("device.2"), capacity = units(24).toString())
        draft.consumers[0] = TestConsumer(devices = listOf("device.1", "device.2"))
        draft.allocations[0] = TestAllocation(bytes = units(30).toString())
        draft.allocations += TestAllocation("allocation.2", "pool.2", bytes = units(6).toString())
        val result = draft.freeze().result()
        val pools = result.poolChecks()
        assertEquals(listOf(units(30), units(6)), pools.map { it.demandUpper })
        assertEquals(listOf(units(24), units(24)), pools.map { it.capacityLower })
        assertEquals(listOf(PlacementOutcome.FAIL, PlacementOutcome.PASS), pools.map { it.outcome })
        assertEquals(PlacementOutcome.FAIL, result.outcome)
    }

    @TestFactory
    fun m3EquivalentReserveBasesPreserveEqualityAndOneByteFailure() = listOf(
        BigInteger.ZERO to PlacementOutcome.PASS,
        BigInteger.ONE to PlacementOutcome.FAIL,
    ).map { (extraByte, expected) ->
        DynamicTest.dynamicTest("M3 equivalent reserve bases plus " + extraByte + " byte") {
            val demand = units(16).add(extraByte)
            val results = listOf(PlacementReserveBasis.BEFORE_RESERVE, PlacementReserveBasis.AFTER_RESERVE).map { basis ->
                val draft = PlacementMemoryDraft()
                val capacity = if (basis == PlacementReserveBasis.BEFORE_RESERVE) units(24) else units(16)
                draft.pools[0] = TestPool(capacity = capacity.toString(), reserve = units(8).toString(), reserveBasis = basis)
                draft.allocations[0] = TestAllocation(bytes = demand.toString())
                val result = draft.freeze().result()
                val pool = result.poolChecks().single()
                assertEquals(demand, pool.demandUpper)
                assertEquals(capacity, pool.capacityLower)
                assertEquals(units(8), pool.reserveUpper)
                assertEquals(expected, pool.outcome)
                assertEquals(expected, result.outcome)
                pool.outcome
            }
            assertEquals(results[0], results[1])
        }
    }

    private fun units(value: Long): BigInteger = BigInteger.valueOf(value).multiply(BigInteger("1000000000"))
}
