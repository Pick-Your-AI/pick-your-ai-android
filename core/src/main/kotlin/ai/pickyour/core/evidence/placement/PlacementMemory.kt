package ai.pickyour.core.evidence.placement

import java.math.BigInteger

/**
 * Explicit normalized CD-2 receiving port. No saved Build fields, source conversion, default
 * evaluator dispatch, search for a placement, or claim of measured fit is performed here.
 */
class PlacementMemory(
    val basis: PlacementBasis,
    private val deployment: PlacementDeployment,
    profile: String,
    val mode: PlacementCoverageMode,
    val components: PlacementRecords<PlacementMemoryComponent>,
    val devices: PlacementRecords<PlacementDevice>,
    val pools: PlacementRecords<PlacementPool>,
    val consumers: PlacementRecords<PlacementConsumer>,
    val allocations: PlacementRecords<PlacementAllocation>,
    val scenarios: PlacementRecords<PlacementScenario>,
    val limits: PlacementRecords<PlacementIndependentLimit>,
    val transitions: PlacementCoverage,
) {
    init {
        if (deployment.basis.sha256 != basis.sha256) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        if (profile != CD2_PHYSICAL_POOL_PROFILE) placementReject(PlacementProblem.UNSUPPORTED_PROFILE)
        listOf(
            components.coverage to PlacementCoverageKind.SERVING_COMPONENTS,
            devices.coverage to PlacementCoverageKind.HARDWARE_INSTANCES,
            pools.coverage to PlacementCoverageKind.PHYSICAL_POOL_MAP,
            consumers.coverage to PlacementCoverageKind.CONSUMERS,
            allocations.coverage to PlacementCoverageKind.CHARGE_TERMS,
            scenarios.coverage to PlacementCoverageKind.SCENARIOS,
            limits.coverage to PlacementCoverageKind.INDEPENDENT_CEILINGS,
            transitions to PlacementCoverageKind.TRANSITIONS,
        ).forEach { (coverage, kind) -> requireMemoryCoverage(coverage, kind, basis) }
        if (scenarios.records.size > 64 || scenarios.records.sumOf { it.rows.size } > 4096 ||
            scenarios.records.sumOf { scenario -> scenario.rows.sumOf { it.access.size } } > 8192)
            placementReject(PlacementProblem.INPUT_LIMIT)
        if (mode == PlacementCoverageMode.CONSERVATIVE_ALL_RESIDENT && scenarios.records.size > 1)
            placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        preflight()
    }

    /** Admit all supplied shapes and references before disabled, inapplicable, or fail shortcuts. */
    private fun preflight() {
        val enabledServing = basis.selections.filter {
            it.enabled && it.kind in setOf("model", "embedding_model", "fine_tuned_model", "vector_database")
        }.map { it.id }.toSet()
        if (components.ids.provided && components.ids.strings()?.containsAll(enabledServing) != true)
            placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        for (component in components.records) {
            val selected = basis.selection(component.id) ?: placementReject(PlacementProblem.CONTRADICTORY_INPUT)
            if (selected.kind !in setOf("model", "embedding_model", "fine_tuned_model", "vector_database"))
                placementReject(PlacementProblem.INVALID_DOMAIN)
        }
        for (device in devices.records) {
            device.selectionId.text()?.let {
                if (basis.selection(it) == null) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
            }
            device.poolId.text()?.let(pools::reference)
        }
        val physical = HashSet<List<String>>()
        val memberships = HashMap<String, String>()
        for (pool in pools.records) {
            pool.physicalTuple()?.let {
                if (!physical.add(it)) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
            }
            pool.deviceIds.strings()?.forEach { deviceId ->
                devices.reference(deviceId)
                val previous = memberships.put(deviceId, pool.id)
                if (previous != null && previous != pool.id) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
                devices.get(deviceId)?.poolId?.text()?.let {
                    if (it != pool.id) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
                }
            }
        }
        for (device in devices.records) {
            device.poolId.text()?.let { poolId ->
                pools.get(poolId)?.deviceIds?.strings()?.let {
                    if (device.id !in it) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
                }
            }
        }
        for (consumer in consumers.records) {
            consumer.componentId.text()?.let(components::reference)
            consumer.deviceIds.strings()?.forEach(devices::reference)
            val runtime = consumer.binding.runtime
            if (runtime?.kind == PlacementRuntimeKind.MODEL_ENGINE && basis.selection(runtime.id) == null)
                placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        }
        val extents = HashSet<Pair<String, String>>()
        for (allocation in allocations.records) {
            allocation.poolId.text()?.let(pools::reference)
            allocation.consumerIds.strings()?.forEach(consumers::reference)
            val pool = allocation.poolId.text()
            val extent = allocation.extent.text()
            if (pool != null && extent != null && !extents.add(pool to extent))
                placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        }
        for (scenario in scenarios.records) {
            requireMemoryCoverage(scenario.coverage, PlacementCoverageKind.CHARGE_TERMS, basis, scenario.id)
            scenario.componentIds.strings()?.forEach(components::reference)
            for (row in scenario.rows) {
                allocations.reference(row.allocationId)
                val knownConsumers = allocations.get(row.allocationId)?.consumerIds?.strings()
                for (access in row.access) {
                    consumers.reference(access.consumerId)
                    if (knownConsumers != null && access.consumerId !in knownConsumers)
                        placementReject(PlacementProblem.CONTRADICTORY_INPUT)
                }
            }
        }
        for (limit in limits.records) {
            scenarios.reference(limit.scenarioId)
            limit.consumerId.text()?.let(consumers::reference)
        }
    }

    fun evaluate(current: PlacementBasis, q: PlacementQualifications): PlacementResult {
        val checks = ArrayList<PlacementCheck>()
        val componentInventory = components.check(current, q)
        checks += componentInventory
        if (current.sha256 != basis.sha256) {
            checks += unknown(PlacementRule.PHYSICAL_MAP, pools.coverage.domain,
                PlacementReason.CHANGED_BASIS, listOf(components.ids, pools.ids, allocations.ids, scenarios.ids))
            return PlacementResult(current, q, checks, listOf(mode.name))
        }
        val deploymentResult = deployment.evaluate(current, q)
        val derived = deploymentResult.components.associateBy { it.selectionId }
        // Independent source consistency/support failures survive an unknown dependent capacity.
        checks += deploymentResult.result.checks.filter { it.outcome == PlacementOutcome.FAIL }
        val applicabilityChecks = LinkedHashMap<String, PlacementCheck>()
        val applicability = components.records.associate { component ->
            val use = q.use(current, component.applicable, PlacementFact.APPLICABILITY, component.domain)
            val normalized = component.applicability(current, q)
            val selected = basis.selection(component.id)
            val actual = derived[component.id]?.localApplicability
            val applies = when {
                selected?.enabled == false && normalized == false -> false
                normalized == true && actual == PlacementApplicability.APPLICABLE -> true
                normalized == false && actual == PlacementApplicability.INAPPLICABLE -> false
                else -> null
            }
            val evidence = listOfNotNull(use) + derived[component.id]?.applicabilityEvidence.orEmpty()
            val check = PlacementCheck(PlacementRule.LOCAL_HARDWARE, component.domain,
                when (applies) { true -> PlacementOutcome.PASS; false -> PlacementOutcome.SKIPPED; null -> PlacementOutcome.UNKNOWN },
                when (applies) { true -> PlacementReason.QUALIFIED; false -> PlacementReason.INAPPLICABLE; null -> PlacementReason.MISSING_OR_INELIGIBLE },
                listOf(component.applicable) + evidence.map { it.answer }, evidence)
            checks += check
            applicabilityChecks[component.id] = check
            component.id to applies
        }
        if (componentInventory.outcome == PlacementOutcome.PASS && applicability.values.all { it == false }) {
            checks += PlacementCheck(PlacementRule.MEMORY_POOL, pools.coverage.domain,
                PlacementOutcome.SKIPPED, PlacementReason.INAPPLICABLE,
                evidence = componentInventory.evidence + applicabilityChecks.values.flatMap { it.evidence })
            return PlacementResult(current, q, checks, listOf(mode.name))
        }

        val inventoryChecks = listOf(devices.check(current, q), pools.check(current, q),
            consumers.check(current, q), allocations.check(current, q))
        val allocationInventory = inventoryChecks.last()
        checks += inventoryChecks
        checks += scenarios.check(current, q)
        checks += limits.check(current, q)
        val transitionCheck = transitions.check(current, q)
        val transitionUse = transitions.use(current, q)
        checks += transitionCheck
        if (scenarios.records.isEmpty() || pools.records.isEmpty())
            checks += unknown(PlacementRule.MEMORY_POOL, pools.coverage.domain,
                PlacementReason.INCOMPLETE_COVERAGE, listOf(pools.ids, scenarios.ids))

        val mappedDevices = devices.records.associate { device ->
            device.id to deviceCheck(device, current, q).also { checks += it }
        }
        val poolMapChecks = LinkedHashMap<String, PlacementCheck>()
        val poolIdentities = pools.records.associate { pool ->
            val identity = pool.identity(current, q)
            val memberships = q.use(current, pool.deviceIds, PlacementFact.INVENTORY, pool.domain)
            val deviceSet = pool.deviceIds.strings()
            val mapKnown = identity != null && memberships != null && deviceSet != null &&
                deviceSet.all { mappedDevices[it]?.outcome == PlacementOutcome.PASS }
            val check = PlacementCheck(PlacementRule.PHYSICAL_MAP, pool.domain,
                if (mapKnown) PlacementOutcome.PASS else PlacementOutcome.UNKNOWN,
                if (mapKnown) PlacementReason.QUALIFIED else PlacementReason.UNKNOWN_PHYSICAL_IDENTITY,
                listOf(pool.addressSpace, pool.partition, pool.tenancy, pool.deviceIds),
                identity.orEmpty() + listOfNotNull(memberships) +
                    deviceSet.orEmpty().flatMap { mappedDevices[it]?.evidence.orEmpty() })
            checks += check
            poolMapChecks[pool.id] = check
            pool.id to identity
        }
        val consumerIdentities = consumers.records.associate { consumer ->
            val identity = consumerIdentity(consumer, applicability, applicabilityChecks, mappedDevices, derived, current, q)
            checks += identity.check
            consumer.id to identity
        }

        for (component in components.records) {
            if (applicability[component.id] == true && consumers.records.none {
                    it.componentId.text() == component.id && consumerIdentities[it.id]?.uses != null
                })
                checks += unknown(PlacementRule.COVERAGE, component.domain,
                    PlacementReason.INCOMPLETE_COVERAGE, listOf(component.applicable, consumers.ids))
        }

        val scenarioIdentities = LinkedHashMap<String, ScenarioIdentity>()
        for (scenario in scenarios.records) {
            val scenarioDomain = PlacementDomain.inventory(PlacementCoverageKind.SCENARIOS, basis.buildId, scenario.id)
            val envelope = q.use(current, scenario.envelope, PlacementFact.WORKLOAD, scenarioDomain)
            val placed = q.use(current, scenario.componentIds, PlacementFact.INVENTORY, scenarioDomain)
            val placedIds = scenario.componentIds.strings()
            var scenarioKnown = envelope != null && placed != null && placedIds != null
            val scenarioUses = ArrayList<PlacementEvidenceUse>()
            scenarioUses += listOfNotNull(envelope, placed)
            for (component in components.records) {
                if (applicability[component.id] != true) continue
                scenarioUses += applicabilityChecks[component.id]?.evidence.orEmpty()
                val placement = q.use(current, component.placement, PlacementFact.RESIDENCY, component.domain)
                if (placement == null) scenarioKnown = false else scenarioUses += placement
                val required = mode == PlacementCoverageMode.CONSERVATIVE_ALL_RESIDENT ||
                    component.placement.text() == "always"
                if (required && placedIds?.contains(component.id) != true) scenarioKnown = false
            }
            val scenarioCoverage = scenario.coverage.check(current, q)
            checks += scenarioCoverage
            scenarioIdentities[scenario.id] = ScenarioIdentity(scenarioKnown, placedIds.orEmpty(), scenarioUses)
            if (!scenarioKnown) checks += PlacementCheck(PlacementRule.COVERAGE, scenarioDomain,
                PlacementOutcome.UNKNOWN, PlacementReason.INCOMPLETE_COVERAGE,
                listOf(scenario.envelope, scenario.componentIds), scenarioUses)
            val charges = LinkedHashMap<String, MutableList<QualifiedCharge>>()
            val excludedUses = LinkedHashMap<String?, MutableList<PlacementEvidenceUse>>()
            val requiredPoolUses = LinkedHashMap<String, MutableList<PlacementEvidenceUse>>()
            val requiredPools = HashSet<String>()
            for (consumer in consumers.records) {
                if (consumerIdentities[consumer.id]?.uses == null ||
                    placedIds?.contains(consumer.componentId.text()) != true) continue
                for (device in consumer.deviceIds.strings().orEmpty())
                    devices.get(device)?.poolId?.text()?.let { poolId ->
                        requiredPools += poolId
                        requiredPoolUses.getOrPut(poolId) { ArrayList() } +=
                            consumerIdentities[consumer.id]?.uses.orEmpty() + mappedDevices[device]?.evidence.orEmpty()
                    }
            }
            val incompletePools = HashSet<String>()
            var unscopedCharge = !allocations.ids.provided
            for (allocation in allocations.records) {
                val poolId = allocation.poolId.text()
                val domain = PlacementDomain.allocation(poolId, allocation.id, scenario.id)
                val row = scenario.row(allocation.id)
                if (row == null) {
                    if (poolId == null) unscopedCharge = true else incompletePools += poolId
                    checks += unknown(PlacementRule.MEMORY_POOL, domain,
                        PlacementReason.MISSING_ALLOCATION, allocation.observations)
                    continue
                }
                val observations = allocation.observations + row.observations
                val residency = q.use(current, row.residency, PlacementFact.RESIDENCY, domain)
                if (row.residency.text() == "excluded" && residency != null && scenarioKnown &&
                    mode == PlacementCoverageMode.QUALIFIED_SCENARIOS && transitionUse != null) {
                    val evidence = listOf(residency, transitionUse) + scenarioUses
                    excludedUses.getOrPut(poolId) { ArrayList() } += evidence
                    checks += PlacementCheck(PlacementRule.MEMORY_POOL, domain,
                        PlacementOutcome.SKIPPED, PlacementReason.INAPPLICABLE, observations, evidence)
                    continue
                }
                val consumersForCharge = allocation.consumerIds.strings()
                val identities = consumersForCharge?.map { consumerIdentities[it] }
                val active = consumersForCharge?.any {
                    val component = consumers.get(it)?.componentId?.text()
                    component != null && applicability[component] == true && placedIds?.contains(component) == true
                } == true
                val physical = if (allocation.nonOverlapping.bool() == true)
                    memoryUses(current, q, allocation.observations, PlacementFact.ALLOCATION_IDENTITY, domain) else null
                val upper = row.liveBytes.qualifiedUpper(current, q, domain)
                val chargeIdentityKnown = poolId != null && pools.get(poolId) != null && physical != null &&
                    residency != null && row.residency.text() == "resident" && scenarioKnown && active &&
                    identities != null && identities.all { it?.uses != null }
                if (chargeIdentityKnown) requiredPools += checkNotNull(poolId)
                val valid = chargeIdentityKnown && upper != null
                // Access remains independent of byte arithmetic and never acquires support from it.
                if (row.residency.text() == "resident" && residency != null && poolId != null) {
                    for (consumerId in consumersForCharge.orEmpty()) {
                        val consumer = consumers.get(consumerId)
                        val runtime = consumer?.binding?.domain
                        val access = row.access.find { it.consumerId == consumerId }
                        val accessDomain = runtime?.let {
                            PlacementDomain.access(poolId, allocation.id, scenario.id, consumerId, it)
                        }
                        val use = if (access != null && accessDomain != null)
                            q.use(current, access.allowed, PlacementFact.ACCESS, accessDomain) else null
                        val known = if (use != null) access?.allowed?.bool() else null
                        checks += PlacementCheck(PlacementRule.RUNTIME_ACCESS, accessDomain ?: domain,
                            when (known) { true -> PlacementOutcome.PASS; false -> PlacementOutcome.FAIL; null -> PlacementOutcome.UNKNOWN },
                            if (known == null) PlacementReason.UNKNOWN_ACCESS else PlacementReason.QUALIFIED,
                            listOfNotNull(row.residency, access?.allowed), listOfNotNull(residency, use))
                    }
                }
                if (valid) {
                    val uses = checkNotNull(physical) + checkNotNull(upper).uses + checkNotNull(residency) +
                        identities.orEmpty().flatMap { it?.uses.orEmpty() } + scenarioUses
                    charges.getOrPut(checkNotNull(poolId)) { ArrayList() } +=
                        QualifiedCharge(allocation.id, upper.value, uses)
                } else {
                    if (poolId == null) unscopedCharge = true else incompletePools += poolId
                    checks += unknown(PlacementRule.MEMORY_POOL, domain,
                        PlacementReason.UNKNOWN_RESIDENCY, observations)
                }
            }
            // A pool is unused only after every supplied consumer is assigned or qualified out of
            // this scenario. An unknown inventory or unresolved consumer cannot prove absence.
            val consumerScopeUses = consumers.records.map { consumer ->
                consumerIdentities[consumer.id]?.uses ?: run {
                    val runtime = consumer.binding.domain
                    val component = consumer.componentId.text()
                    val componentUse = runtime?.let {
                        q.use(current, consumer.componentId, PlacementFact.INVENTORY, it)
                    }
                    if (componentUse != null && component != null &&
                        (applicability[component] == false ||
                            (scenarioKnown && applicability[component] == true && component !in placedIds.orEmpty())))
                        listOf(componentUse) + applicabilityChecks[component]?.evidence.orEmpty() + scenarioUses
                    else null
                }
            }
            val activeComponentsCovered = components.records.all { component ->
                applicability[component.id] != true || component.id !in placedIds.orEmpty() ||
                    consumers.records.any {
                        it.componentId.text() == component.id && consumerIdentities[it.id]?.uses != null
                    }
            }
            for (pool in pools.records) {
                val qualified = charges[pool.id].orEmpty()
                val sum = qualified.fold(BigInteger.ZERO) { total, charge -> total + charge.upper }
                val budget = pool.budget.qualified(current, q, pool.domain)
                val identity = poolIdentities[pool.id]
                val requiredPool = pool.id in requiredPools
                val witness = requiredPool && budget != null && identity != null && scenarioKnown
                val qualifiedUnused = !requiredPool && scenarioKnown && !unscopedCharge &&
                    componentInventory.outcome == PlacementOutcome.PASS &&
                    applicability.values.none { it == null } && consumerScopeUses.all { it != null } &&
                    activeComponentsCovered && poolMapChecks.values.all { it.outcome == PlacementOutcome.PASS } &&
                    inventoryChecks.all { it.outcome == PlacementOutcome.PASS } &&
                    scenarioCoverage.outcome == PlacementOutcome.PASS && incompletePools.isEmpty()
                val incomplete = unscopedCharge || pool.id in incompletePools ||
                    scenarioCoverage.outcome != PlacementOutcome.PASS || allocationInventory.outcome != PlacementOutcome.PASS
                val result = when {
                    qualifiedUnused -> PlacementOutcome.SKIPPED
                    witness && !checkNotNull(budget).fits(sum) -> PlacementOutcome.FAIL
                    !witness || incomplete -> PlacementOutcome.UNKNOWN
                    else -> PlacementOutcome.PASS
                }
                val used = if (qualifiedUnused) {
                    scenarioUses + componentInventory.evidence + applicabilityChecks.values.flatMap { it.evidence } +
                        inventoryChecks.flatMap { it.evidence } + scenarioCoverage.evidence +
                        poolMapChecks.values.flatMap { it.evidence } + consumerScopeUses.flatMap { it.orEmpty() } +
                        charges.values.flatten().flatMap { it.uses } + excludedUses.values.flatten()
                } else {
                    identity.orEmpty() + budget?.uses.orEmpty() + requiredPoolUses[pool.id].orEmpty() +
                        qualified.flatMap { it.uses } + scenarioUses +
                        if (result == PlacementOutcome.FAIL) emptyList() else
                            allocationInventory.evidence + scenarioCoverage.evidence +
                                excludedUses[pool.id].orEmpty() + excludedUses[null].orEmpty()
                }
                checks += PlacementCheck(PlacementRule.MEMORY_POOL,
                    PlacementDomain.poolScenario(pool.domain, scenario.id), result,
                    when (result) {
                        PlacementOutcome.FAIL -> PlacementReason.OVER_UPPER_BOUND_BUDGET
                        PlacementOutcome.PASS -> PlacementReason.QUALIFIED
                        PlacementOutcome.SKIPPED -> PlacementReason.INAPPLICABLE
                        else -> PlacementReason.MISSING_OR_INELIGIBLE
                    },
                    pool.observations, used, qualified.map { it.id },
                    if (witness) sum else null, budget?.capacityLower, budget?.reserveUpper,
                    mayNotFit = result == PlacementOutcome.FAIL)
            }
        }
        for (limit in limits.records)
            checks += limitCheck(limit, consumerIdentities, scenarioIdentities, current, q)
        return PlacementResult(current, q, checks, listOf(mode.name))
    }

    private fun deviceCheck(device: PlacementDevice, current: PlacementBasis, q: PlacementQualifications): PlacementCheck {
        val domain = PlacementDomain.device(device.id)
        val selected = device.selectionId.text()?.let(basis::selection)
        val pool = device.poolId.text()?.let(pools::get)
        val catalog = selected?.let(basis::catalogItem)
        val use = memoryUses(current, q, listOf(device.selectionId, device.poolId), PlacementFact.INVENTORY, domain)
        val reciprocal = pool?.let { q.use(current, it.deviceIds, PlacementFact.INVENTORY, it.domain) }
        val result = when {
            use == null -> PlacementOutcome.UNKNOWN
            selected != null && (!selected.enabled || selected.kind !in setOf("accelerator", "compute_profile") ||
                (catalog != null && catalog["kind"]?.textValue() != selected.kind)) -> PlacementOutcome.FAIL
            selected == null || catalog == null || reciprocal == null -> PlacementOutcome.UNKNOWN
            else -> PlacementOutcome.PASS
        }
        return PlacementCheck(PlacementRule.PHYSICAL_MAP, domain, result,
            when (result) {
                PlacementOutcome.FAIL -> PlacementReason.WRONG_KIND_OR_DISABLED
                PlacementOutcome.PASS -> PlacementReason.QUALIFIED
                else -> PlacementReason.UNKNOWN_PHYSICAL_IDENTITY
            }, listOf(device.selectionId, device.poolId) + listOfNotNull(pool?.deviceIds),
            use.orEmpty() + listOfNotNull(reciprocal))
    }

    private class ConsumerIdentity(val check: PlacementCheck, val uses: List<PlacementEvidenceUse>?)
    private class QualifiedCharge(val id: String, val upper: BigInteger, val uses: List<PlacementEvidenceUse>)
    private class ScenarioIdentity(val known: Boolean, val placed: Set<String>, uses: List<PlacementEvidenceUse>) {
        val uses = immutablePlacementList(uses)
    }

    private fun consumerIdentity(
        consumer: PlacementConsumer, applicability: Map<String, Boolean?>,
        applicabilityChecks: Map<String, PlacementCheck>,
        devices: Map<String, PlacementCheck>, derived: Map<String, PlacementComponentMode>,
        current: PlacementBasis, q: PlacementQualifications,
    ): ConsumerIdentity {
        val domain = consumer.binding.domain ?: PlacementDomain.component(consumer.id)
        val runtime = consumer.binding.runtime
        val component = consumer.componentId.text()?.let(basis::selection)
        val source = consumer.binding.sourceInput?.let(current::input)
        val uses = memoryUses(current, q, consumer.observations, PlacementFact.INVENTORY, domain)
        val selected = if (runtime?.kind == PlacementRuntimeKind.MODEL_ENGINE) basis.selection(runtime.id) else null
        val serving = component?.id?.let { derived[it]?.serving }
        val bindingsKnown = serving != null && serving.lineage.outcome == PlacementOutcome.PASS &&
            serving.checks.none { it.outcome in setOf(PlacementOutcome.FAIL, PlacementOutcome.UNKNOWN) }
        val engineMatches = runtime != null && (
            (runtime.kind == PlacementRuntimeKind.MODEL_ENGINE &&
                serving?.engine?.resolved?.singleOrNull()?.id == runtime.id) ||
            (runtime.kind == PlacementRuntimeKind.SERVICE_RUNTIME &&
                serving?.engine?.check?.outcome == PlacementOutcome.SKIPPED))
        val selectedDeviceIds = serving?.devices?.resolved.orEmpty().map { it.id }.toSet()
        val deviceBindingsMatch = consumer.deviceIds.strings()?.all { id ->
            this.devices.get(id)?.selectionId?.text() in selectedDeviceIds
        } == true
        val wrongRuntime = runtime != null && component != null && (
            (runtime.kind == PlacementRuntimeKind.SERVICE_RUNTIME && component.kind != "vector_database") ||
            (runtime.kind == PlacementRuntimeKind.MODEL_ENGINE && component.kind == "vector_database") ||
            (selected != null && (selected.kind != "inference_engine" || !selected.enabled)))
        val known = uses != null && bindingsKnown && engineMatches && deviceBindingsMatch &&
            consumer.operation.text() == basis.operation && component != null &&
            applicability[component.id] == true && consumer.deviceIds.strings()?.all {
                devices[it]?.outcome == PlacementOutcome.PASS
            } == true && source?.kind == PlacementInputKind.ENGINE_BUILDS &&
            runtime != null && (runtime.kind == PlacementRuntimeKind.SERVICE_RUNTIME ||
                (selected != null && basis.catalogItem(selected) != null))
        val result = when {
            uses != null && wrongRuntime -> PlacementOutcome.FAIL
            !known -> PlacementOutcome.UNKNOWN
            else -> PlacementOutcome.PASS
        }
        val evidence = uses.orEmpty() + serving?.checks.orEmpty().flatMap { it.evidence } +
            consumer.deviceIds.strings().orEmpty().flatMap { devices[it]?.evidence.orEmpty() } +
            component?.id?.let { applicabilityChecks[it]?.evidence }.orEmpty()
        return ConsumerIdentity(PlacementCheck(PlacementRule.BINDING, domain, result,
            when (result) {
                PlacementOutcome.FAIL -> PlacementReason.WRONG_KIND_OR_DISABLED
                PlacementOutcome.PASS -> PlacementReason.QUALIFIED
                else -> PlacementReason.MISSING_OR_INELIGIBLE
            }, consumer.observations, evidence), if (result == PlacementOutcome.PASS) evidence else null)
    }

    private fun limitCheck(
        limit: PlacementIndependentLimit, consumers: Map<String, ConsumerIdentity>,
        scenarios: Map<String, ScenarioIdentity>, current: PlacementBasis, q: PlacementQualifications,
    ): PlacementCheck {
        val runtime = limit.binding.domain
        val domain = if (runtime == null) PlacementDomain.inventory(
            PlacementCoverageKind.INDEPENDENT_CEILINGS, basis.buildId, limit.scenarioId)
            else PlacementDomain.ceiling(limit.id, limit.scenarioId, runtime)
        val applicability = q.use(current, limit.applicable, PlacementFact.APPLICABILITY, domain)
        if (applicability != null && limit.applicable.bool() == false)
            return PlacementCheck(PlacementRule.INDEPENDENT_CEILING, domain,
                PlacementOutcome.SKIPPED, PlacementReason.INAPPLICABLE,
                limit.observations, listOf(applicability))
        val consumerId = limit.consumerId.text()
        val consumer = consumerId?.let(this.consumers::get)
        val identity = memoryUses(current, q, listOf(limit.consumerId, limit.binding.answer,
            limit.accountingDefinition), PlacementFact.CEILING, domain)
        val coverage = if (limit.accountingComplete.bool() == true)
            q.use(current, limit.accountingComplete, PlacementFact.COVERAGE, domain) else null
        val demand = limit.demand.qualifiedUpper(current, q, domain)
        val budget = limit.budget.qualified(current, q, domain)
        val scenario = scenarios[limit.scenarioId]
        val consumerUses = consumers[consumerId]?.uses
        val known = applicability != null && limit.applicable.bool() == true && identity != null &&
            coverage != null && runtime != null && consumer?.binding?.domain == runtime &&
            consumerUses != null && scenario?.known == true &&
            consumer.componentId.text() in scenario.placed && demand != null && budget != null
        val result = if (!known) PlacementOutcome.UNKNOWN
            else if (checkNotNull(budget).fits(checkNotNull(demand).value)) PlacementOutcome.PASS else PlacementOutcome.FAIL
        return PlacementCheck(PlacementRule.INDEPENDENT_CEILING, domain, result,
            when (result) {
                PlacementOutcome.FAIL -> PlacementReason.OVER_UPPER_BOUND_BUDGET
                PlacementOutcome.PASS -> PlacementReason.QUALIFIED
                else -> PlacementReason.MISSING_OR_INELIGIBLE
            }, limit.observations,
            listOfNotNull(applicability, coverage) + identity.orEmpty() + demand?.uses.orEmpty() + budget?.uses.orEmpty() +
                consumerUses.orEmpty() + scenario?.uses.orEmpty(),
            demandUpper = if (known) demand?.value else null, capacityLower = if (known) budget?.capacityLower else null,
            reserveUpper = if (known) budget?.reserveUpper else null, mayNotFit = result == PlacementOutcome.FAIL)
    }


    private fun unknown(rule: PlacementRule, domain: PlacementDomain, reason: PlacementReason,
                        observations: List<PlacementAnswer>) =
        PlacementCheck(rule, domain, PlacementOutcome.UNKNOWN, reason, observations)
}
