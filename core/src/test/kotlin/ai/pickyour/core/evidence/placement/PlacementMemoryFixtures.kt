package ai.pickyour.core.evidence.placement

internal data class TestPool(
    val id: String = "pool.1", val devices: List<String> = listOf("device.1"),
    val capacity: String = "24", val reserve: String = "0",
    val reserveBasis: PlacementReserveBasis = PlacementReserveBasis.BEFORE_RESERVE,
    val space: String = "address-space-" + id, val partition: String = "whole", val tenancy: String = "tenant.1",
)
internal data class TestDevice(val id: String = "device.1", val selection: String = "sel_gpu", val pool: String = "pool.1")
internal data class TestConsumer(
    val id: String = "consumer.1", val component: String = "sel_model", val devices: List<String> = listOf("device.1"),
    val runtime: Map<String, String?>? = null,
)
internal data class TestAllocation(
    val id: String = "allocation.1", val pool: String = "pool.1", val consumers: List<String> = listOf("consumer.1"),
    val bytes: String = "18", val extent: String = "extent-" + id, val nonOverlapping: Boolean = true,
    val term: String = "weights",
)
internal data class TestScenario(
    val id: String = "scenario.1", val components: List<String> = listOf("sel_model"),
    val excluded: Set<String> = emptySet(), val omittedRows: Set<String> = emptySet(),
    val amounts: Map<String, String> = emptyMap(),
)
internal data class TestLimit(
    val id: String = "limit.1", val scenario: String = "scenario.1", val consumer: String = "consumer.1",
    val demand: String = "18", val capacity: String = "24", val reserve: String = "0",
    val applicable: Boolean = true, val runtime: Map<String, String?>? = null,
)

/** Explicit normalized scenarios. The builder never derives expected results from implementation checks. */
internal class PlacementMemoryDraft {
    val input = PlacementTestDraft().apply { serving() }
    val pools = mutableListOf(TestPool())
    val devices = mutableListOf(TestDevice())
    val consumers = mutableListOf(TestConsumer())
    val allocations = mutableListOf(TestAllocation())
    val scenarios = mutableListOf(TestScenario())
    val limits = mutableListOf<TestLimit>()
    var mode = PlacementCoverageMode.CONSERVATIVE_ALL_RESIDENT

    fun addDevice(device: TestDevice, kind: String = "accelerator") {
        devices += device
        input.selection(device.selection, kind)
        input.link("link.memory." + device.id, "sel_model", device.selection, PlacementRole.DEVICE)
    }

    fun freeze(edit: PlacementTestDraft.() -> Unit = {}): PlacementMemoryCase {
        val d = input
        val componentIds = d.build["selections"].filter {
            it["kind"].textValue() in setOf("model", "embedding_model", "fine_tuned_model", "vector_database")
        }.map { it["id"].textValue() }
        d.inventory("/memory/components", componentIds)
        d.inventory("/memory/devices", devices.map { it.id })
        d.inventory("/memory/pools", pools.map { it.id })
        d.inventory("/memory/consumers", consumers.map { it.id })
        d.inventory("/memory/allocations", allocations.map { it.id })
        d.inventory("/memory/scenarios", scenarios.map { it.id })
        d.inventory("/memory/limits", limits.map { it.id })
        d.put("/memory/transitions", true)
        for (device in devices) {
            d.put("/devices/" + device.id + "/selection", device.selection)
            d.put("/devices/" + device.id + "/pool", device.pool)
        }
        for (pool in pools) {
            val p = "/pools/" + pool.id
            d.put(p + "/space", pool.space)
            d.put(p + "/partition", pool.partition)
            d.put(p + "/tenancy", pool.tenancy)
            d.put(p + "/devices", pool.devices)
            d.budget(p + "/budget", pool.capacity, pool.reserve)
            d.put(p + "/budget/basis", if (pool.reserveBasis == PlacementReserveBasis.BEFORE_RESERVE) "before_reserve" else "after_reserve")
        }
        for (consumer in consumers) {
            val p = "/consumers/" + consumer.id
            d.put(p + "/component", consumer.component)
            d.put(p + "/operation", d.operation)
            d.put(p + "/devices", consumer.devices)
            d.put(p + "/runtime", consumer.runtime ?: d.runtime())
        }
        for (allocation in allocations) {
            val p = "/allocations/" + allocation.id
            d.put(p + "/pool", allocation.pool)
            d.put(p + "/consumers", allocation.consumers)
            d.put(p + "/extent", allocation.extent)
            d.put(p + "/non-overlapping", allocation.nonOverlapping)
            d.put(p + "/term", allocation.term)
            d.put(p + "/method", "synthetic-physical-allocation")
        }
        for (scenario in scenarios) {
            val p = "/scenarios/" + scenario.id
            d.put(p + "/envelope", "synthetic-scoped-lifetime-" + scenario.id)
            d.put(p + "/components", scenario.components)
            d.put(p + "/complete", true)
            for (allocation in allocations.filter { it.id !in scenario.omittedRows }) {
                val row = p + "/rows/" + allocation.id
                d.put(row + "/residency", if (allocation.id in scenario.excluded) "excluded" else "resident")
                d.range(row + "/bytes", upper = scenario.amounts[allocation.id] ?: allocation.bytes)
                allocation.consumers.forEach { d.put(row + "/access/" + it, true) }
            }
        }
        for (limit in limits) {
            val p = "/limits/" + limit.id
            d.put(p + "/consumer", limit.consumer)
            d.put(p + "/runtime", limit.runtime ?: d.runtime())
            d.put(p + "/applies", limit.applicable)
            d.put(p + "/definition", "synthetic-independent-accounting")
            d.put(p + "/complete", true)
            d.range(p + "/demand", upper = limit.demand)
            d.budget(p + "/budget", limit.capacity, limit.reserve)
            d.put(p + "/budget/basis", "before_reserve")
        }
        d.edit()
        val f = d.freeze()
        val deployment = f.deployment()
        val componentRecords = componentIds.map { id ->
            val applies = f.a("/components/" + id + "/applies", PlacementField.BOOLEAN)
            val placement = f.a("/components/" + id + "/placement", PlacementField.PLACEMENT)
            f.qualify(applies, PlacementFact.APPLICABILITY, PlacementDomain.component(id))
            f.qualify(placement, PlacementFact.RESIDENCY, PlacementDomain.component(id))
            PlacementMemoryComponent(id, applies, placement)
        }
        val deviceRecords = devices.map { value ->
            val p = "/devices/" + value.id
            val selection = f.a(p + "/selection", PlacementField.TEXT)
            val pool = f.a(p + "/pool", PlacementField.TEXT)
            val domain = PlacementDomain.device(value.id)
            f.qualify(selection, PlacementFact.INVENTORY, domain)
            f.qualify(pool, PlacementFact.INVENTORY, domain)
            PlacementDevice(value.id, selection, pool)
        }
        val poolRecords = pools.map { value ->
            val p = "/pools/" + value.id
            val space = f.a(p + "/space", PlacementField.TEXT)
            val partition = f.a(p + "/partition", PlacementField.TEXT)
            val tenancy = f.a(p + "/tenancy", PlacementField.TEXT)
            val deviceIds = f.a(p + "/devices", PlacementField.TEXT_SET)
            val domain = PlacementDomain.physical(value.id, space.text(), partition.text(), tenancy.text())
            listOf(space, partition, tenancy).forEach { f.qualify(it, PlacementFact.PHYSICAL_IDENTITY, domain) }
            f.qualify(deviceIds, PlacementFact.INVENTORY, domain)
            PlacementPool(value.id, space, partition, tenancy, deviceIds, f.budget(p + "/budget", domain))
        }
        val consumerRecords = consumers.map { value ->
            val p = "/consumers/" + value.id
            val component = f.a(p + "/component", PlacementField.TEXT)
            val operation = f.a(p + "/operation", PlacementField.TEXT)
            val deviceIds = f.a(p + "/devices", PlacementField.TEXT_SET)
            val binding = PlacementRuntimeBinding(f.a(p + "/runtime", PlacementField.RUNTIME_BINDING))
            val domain = binding.domain ?: PlacementDomain.component(value.id)
            listOf(component, operation, deviceIds, binding.answer).forEach { f.qualify(it, PlacementFact.INVENTORY, domain) }
            PlacementConsumer(value.id, component, operation, deviceIds, binding)
        }
        val allocationRecords = allocations.map { value ->
            val p = "/allocations/" + value.id
            PlacementAllocation(value.id, f.a(p + "/pool", PlacementField.TEXT),
                f.a(p + "/consumers", PlacementField.TEXT_SET), f.a(p + "/extent", PlacementField.TEXT),
                f.a(p + "/non-overlapping", PlacementField.BOOLEAN), f.a(p + "/term", PlacementField.TEXT),
                f.a(p + "/method", PlacementField.TEXT))
        }
        val scenarioRecords = scenarios.map { value ->
            val p = "/scenarios/" + value.id
            val envelope = f.a(p + "/envelope", PlacementField.TEXT)
            val componentSet = f.a(p + "/components", PlacementField.TEXT_SET)
            val domain = PlacementDomain.inventory(PlacementCoverageKind.SCENARIOS, f.basis.buildId, value.id)
            f.qualify(envelope, PlacementFact.WORKLOAD, domain)
            f.qualify(componentSet, PlacementFact.INVENTORY, domain)
            val rows = allocationRecords.filter { it.id !in value.omittedRows }.map { allocation ->
                val prefix = p + "/rows/" + allocation.id
                val pool = allocation.poolId.text()
                val scope = PlacementDomain.allocation(pool, allocation.id, value.id)
                allocation.observations.forEach { f.qualify(it, PlacementFact.ALLOCATION_IDENTITY, scope) }
                val residency = f.a(prefix + "/residency", PlacementField.RESIDENCY)
                f.qualify(residency, PlacementFact.RESIDENCY, scope)
                val access = allocations.single { it.id == allocation.id }.consumers.map { id ->
                    val allowed = f.a(prefix + "/access/" + id, PlacementField.BOOLEAN)
                    val runtime = consumerRecords.singleOrNull { it.id == id }?.binding?.domain
                    if (pool != null && runtime != null)
                        f.qualify(allowed, PlacementFact.ACCESS, PlacementDomain.access(pool, allocation.id, value.id, id, runtime))
                    PlacementAllocationAccess(id, allowed)
                }
                PlacementResidency(allocation.id, residency, f.range(prefix + "/bytes", scope), access)
            }
            PlacementScenario(value.id, envelope, componentSet, rows,
                f.coverage(p + "/complete", PlacementCoverageKind.CHARGE_TERMS, scenario = value.id))
        }
        val limitRecords = limits.map { value ->
            val p = "/limits/" + value.id
            val consumer = f.a(p + "/consumer", PlacementField.TEXT)
            val binding = PlacementRuntimeBinding(f.a(p + "/runtime", PlacementField.RUNTIME_BINDING))
            val domain = binding.domain?.let { PlacementDomain.ceiling(value.id, value.scenario, it) }
                ?: PlacementDomain.inventory(PlacementCoverageKind.INDEPENDENT_CEILINGS, f.basis.buildId, value.scenario)
            val applies = f.a(p + "/applies", PlacementField.BOOLEAN)
            val definition = f.a(p + "/definition", PlacementField.TEXT)
            val complete = f.a(p + "/complete", PlacementField.BOOLEAN)
            f.qualify(applies, PlacementFact.APPLICABILITY, domain)
            f.qualify(complete, PlacementFact.COVERAGE, domain)
            listOf(consumer, binding.answer, definition).forEach { f.qualify(it, PlacementFact.CEILING, domain) }
            PlacementIndependentLimit(value.id, value.scenario, consumer, binding, applies,
                f.range(p + "/demand", domain), f.budget(p + "/budget", domain), definition, complete)
        }
        val memory = PlacementMemory(f.basis, deployment, CD2_PHYSICAL_POOL_PROFILE, mode,
            f.inventory("/memory/components", componentRecords, PlacementCoverageKind.SERVING_COMPONENTS),
            f.inventory("/memory/devices", deviceRecords, PlacementCoverageKind.HARDWARE_INSTANCES),
            f.inventory("/memory/pools", poolRecords, PlacementCoverageKind.PHYSICAL_POOL_MAP),
            f.inventory("/memory/consumers", consumerRecords, PlacementCoverageKind.CONSUMERS),
            f.inventory("/memory/allocations", allocationRecords, PlacementCoverageKind.CHARGE_TERMS),
            f.inventory("/memory/scenarios", scenarioRecords, PlacementCoverageKind.SCENARIOS),
            f.inventory("/memory/limits", limitRecords, PlacementCoverageKind.INDEPENDENT_CEILINGS),
            f.coverage("/memory/transitions", PlacementCoverageKind.TRANSITIONS))
        return PlacementMemoryCase(f, deployment, memory)
    }
}
internal class PlacementMemoryCase(val fixture: PlacementTestContext, val deployment: PlacementDeployment, val memory: PlacementMemory) {
    fun result(omit: (PlacementQualification) -> Boolean = { false }) = memory.evaluate(fixture.basis, fixture.qs(omit))
}
