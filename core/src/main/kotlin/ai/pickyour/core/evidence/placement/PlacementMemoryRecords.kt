package ai.pickyour.core.evidence.placement

/** The accepted logical accounting profile, explicitly selected by this normalized consumer. */
const val CD2_PHYSICAL_POOL_PROFILE = "cd2-physical-pools/0.1.0"
enum class PlacementCoverageMode { CONSERVATIVE_ALL_RESIDENT, QUALIFIED_SCENARIOS }

interface PlacementRecord { val id: String }

/**
 * Original answer state for the declared inventory, independently assessed completeness, and
 * any supplied records. Partial records with an unresolved inventory remain useful witnesses.
 * A provided inventory names exactly its records; an unresolved inventory is never an empty set.
 */
class PlacementRecords<T : PlacementRecord>(
    val ids: PlacementAnswer,
    records: List<T>,
    val coverage: PlacementCoverage,
) {
    val records = immutablePlacementList(records)
    private val byId = immutablePlacementMap(records.associateBy { it.id })
    init {
        requirePlacementField(ids, PlacementField.TEXT_SET)
        if (records.size > 1024) placementReject(PlacementProblem.INPUT_LIMIT)
        records.forEach { placementIdentity(it.id) }
        if (byId.size != records.size) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
        if (ids.provided && ids.strings() != byId.keys) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }
    fun get(id: String): T? = byId[id]
    internal fun reference(id: String) {
        placementIdentity(id)
        if (id !in byId) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }
    internal fun check(basis: PlacementBasis, q: PlacementQualifications): PlacementCheck {
        val inventory = q.use(basis, ids, PlacementFact.INVENTORY, coverage.domain)
        val complete = coverage.use(basis, q)
        val known = inventory != null && complete != null
        return PlacementCheck(PlacementRule.COVERAGE, coverage.domain,
            if (known) PlacementOutcome.PASS else PlacementOutcome.UNKNOWN,
            if (known) PlacementReason.QUALIFIED else PlacementReason.INCOMPLETE_COVERAGE,
            listOf(ids, coverage.claim), listOfNotNull(inventory, complete))
    }
    override fun toString() = "PlacementRecords"
}

class PlacementMemoryComponent(
    override val id: String,
    val applicable: PlacementAnswer,
    val placement: PlacementAnswer,
) : PlacementRecord {
    val domain = PlacementDomain.component(id)
    init {
        requirePlacementField(applicable, PlacementField.BOOLEAN)
        requirePlacementField(placement, PlacementField.PLACEMENT)
    }
    internal fun applicability(basis: PlacementBasis, q: PlacementQualifications): Boolean? =
        if (q.qualified(basis, applicable, PlacementFact.APPLICABILITY, domain)) applicable.bool() else null
    override fun toString() = "PlacementMemoryComponent"
}

class PlacementDevice(
    override val id: String,
    val selectionId: PlacementAnswer,
    val poolId: PlacementAnswer,
) : PlacementRecord {
    init {
        placementIdentity(id)
        requirePlacementField(selectionId, PlacementField.TEXT)
        requirePlacementField(poolId, PlacementField.TEXT)
    }
    override fun toString() = "PlacementDevice"
}

class PlacementPool(
    override val id: String,
    val addressSpace: PlacementAnswer,
    val partition: PlacementAnswer,
    val tenancy: PlacementAnswer,
    val deviceIds: PlacementAnswer,
    val budget: PlacementBudget,
) : PlacementRecord {
    val domain = PlacementDomain.physical(id, addressSpace.text(), partition.text(), tenancy.text())
    val observations = immutablePlacementList(listOf(addressSpace, partition, tenancy, deviceIds) + budget.observations)
    init {
        placementIdentity(id)
        listOf(addressSpace, partition, tenancy).forEach { requirePlacementField(it, PlacementField.TEXT) }
        requirePlacementField(deviceIds, PlacementField.TEXT_SET)
    }
    internal fun identity(basis: PlacementBasis, q: PlacementQualifications): List<PlacementEvidenceUse>? =
        memoryUses(basis, q, listOf(addressSpace, partition, tenancy), PlacementFact.PHYSICAL_IDENTITY, domain)
    internal fun physicalTuple(): List<String>? =
        listOf(addressSpace.text(), partition.text(), tenancy.text()).let {
            if (it.any { value -> value == null }) null else it.filterNotNull()
        }
    override fun toString() = "PlacementPool"
}

/** Closed Answer<runtime binding>; all null optional roles are explicitly supplied as such. */
class PlacementRuntimeBinding(val answer: PlacementAnswer) {
    init {
        requirePlacementField(answer, PlacementField.RUNTIME_BINDING)
        if (answer.provided) {
            val value = checkNotNull(answer.value)
            // Resolve supplied original references against the answer's own retained basis before any skip.
            val input = answer.basis.input(value["source_input"].textValue())
                ?: placementReject(PlacementProblem.INVALID_FIELD)
            if (input.kind != PlacementInputKind.ENGINE_BUILDS) placementReject(PlacementProblem.INVALID_DOMAIN)
            if (value["kind"].textValue() == "model_engine" &&
                answer.basis.selection(value["id"].textValue()) == null)
                placementReject(PlacementProblem.DANGLING_LINK)
        }
    }
    val runtime: PlacementRuntime? = answer.value?.takeIf { answer.provided }?.let {
        PlacementRuntime(
            if (it["kind"].textValue() == "model_engine") PlacementRuntimeKind.MODEL_ENGINE
            else PlacementRuntimeKind.SERVICE_RUNTIME,
            it["id"].textValue(),
        )
    }
    val buildId: String? = answer.value?.takeIf { answer.provided }?.get("build")?.textValue()
    val sourceInput: String? = answer.value?.takeIf { answer.provided }?.get("source_input")?.textValue()
    val domain: PlacementDomain? = answer.value?.takeIf { answer.provided }?.let { value ->
        PlacementDomain.runtime(checkNotNull(runtime), value["instance"].textValue(),
            value["replica"].textValue(), value["rank"].textValue(),
            value["admission"].textValue(), value["stage"].textValue(), checkNotNull(buildId))
    }
    override fun toString() = "PlacementRuntimeBinding"
}

class PlacementConsumer(
    override val id: String,
    val componentId: PlacementAnswer,
    val operation: PlacementAnswer,
    val deviceIds: PlacementAnswer,
    val binding: PlacementRuntimeBinding,
) : PlacementRecord {
    init {
        placementIdentity(id)
        requirePlacementField(componentId, PlacementField.TEXT)
        requirePlacementField(operation, PlacementField.TEXT)
        requirePlacementField(deviceIds, PlacementField.TEXT_SET)
        if (deviceIds.provided && deviceIds.strings().isNullOrEmpty())
            placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }
    val observations = immutablePlacementList(listOf(componentId, operation, deviceIds, binding.answer))
    override fun toString() = "PlacementConsumer"
}

/** One actual storage extent; the non-overlap claim needs an independent scope qualification. */
class PlacementAllocation(
    override val id: String,
    val poolId: PlacementAnswer,
    val consumerIds: PlacementAnswer,
    val extent: PlacementAnswer,
    val nonOverlapping: PlacementAnswer,
    val term: PlacementAnswer,
    val method: PlacementAnswer,
) : PlacementRecord {
    init {
        placementIdentity(id)
        listOf(poolId, extent, term, method).forEach { requirePlacementField(it, PlacementField.TEXT) }
        requirePlacementField(consumerIds, PlacementField.TEXT_SET)
        requirePlacementField(nonOverlapping, PlacementField.BOOLEAN)
        if (consumerIds.provided && consumerIds.strings().isNullOrEmpty())
            placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }
    val observations = immutablePlacementList(listOf(poolId, consumerIds, extent, nonOverlapping, term, method))
    override fun toString() = "PlacementAllocation"
}

class PlacementResidency(
    val allocationId: String,
    val residency: PlacementAnswer,
    /** The upper bound is required only for a resident row; its lower bound is optional. */
    val liveBytes: PlacementByteRange,
    access: List<PlacementAllocationAccess>,
) {
    val access = immutablePlacementList(access)
    init {
        placementIdentity(allocationId)
        requirePlacementField(residency, PlacementField.RESIDENCY)
        if (access.size > 1024) placementReject(PlacementProblem.INPUT_LIMIT)
        if (access.map { it.consumerId }.toSet().size != access.size)
            placementReject(PlacementProblem.DUPLICATE_IDENTITY)
    }
    val observations = immutablePlacementList(listOf(residency) + liveBytes.observations + access.map { it.allowed })
    override fun toString() = "PlacementResidency"
}

class PlacementAllocationAccess(val consumerId: String, val allowed: PlacementAnswer) {
    init { placementIdentity(consumerId); requirePlacementField(allowed, PlacementField.BOOLEAN) }
    override fun toString() = "PlacementAllocationAccess"
}

class PlacementScenario(
    override val id: String,
    val envelope: PlacementAnswer,
    val componentIds: PlacementAnswer,
    rows: List<PlacementResidency>,
    val coverage: PlacementCoverage,
) : PlacementRecord {
    val rows = immutablePlacementList(rows)
    private val byAllocation = immutablePlacementMap(rows.associateBy { it.allocationId })
    init {
        placementIdentity(id)
        requirePlacementField(envelope, PlacementField.TEXT)
        requirePlacementField(componentIds, PlacementField.TEXT_SET)
        if (rows.size > 1024) placementReject(PlacementProblem.INPUT_LIMIT)
        if (byAllocation.size != rows.size) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
        if (coverage.kind != PlacementCoverageKind.CHARGE_TERMS || coverage.scenarioId != id)
            placementReject(PlacementProblem.INVALID_DOMAIN)
    }
    fun row(allocationId: String): PlacementResidency? = byAllocation[allocationId]
    override fun toString() = "PlacementScenario"
}

/**
 * Already normalized independently qualified demand, rather than an inferred equal rank split.
 * A separate record is required per actual scenario/runtime accounting domain.
 */
class PlacementIndependentLimit(
    override val id: String,
    val scenarioId: String,
    val consumerId: PlacementAnswer,
    val binding: PlacementRuntimeBinding,
    val applicable: PlacementAnswer,
    val demand: PlacementByteRange,
    val budget: PlacementBudget,
    val accountingDefinition: PlacementAnswer,
    val accountingComplete: PlacementAnswer,
) : PlacementRecord {
    init {
        listOf(id, scenarioId).forEach(::placementIdentity)
        requirePlacementField(consumerId, PlacementField.TEXT)
        requirePlacementField(applicable, PlacementField.BOOLEAN)
        requirePlacementField(accountingDefinition, PlacementField.TEXT)
        requirePlacementField(accountingComplete, PlacementField.BOOLEAN)
    }
    val observations = immutablePlacementList(listOf(consumerId, binding.answer, applicable,
        accountingDefinition, accountingComplete) + demand.observations + budget.observations)
    override fun toString() = "PlacementIndependentLimit"
}

internal fun memoryUses(
    basis: PlacementBasis,
    q: PlacementQualifications,
    answers: List<PlacementAnswer>,
    fact: PlacementFact,
    domain: PlacementDomain,
): List<PlacementEvidenceUse>? {
    val uses = answers.map { q.use(basis, it, fact, domain) }
    return if (uses.any { it == null }) null else uses.filterNotNull()
}

internal fun requireMemoryCoverage(
    value: PlacementCoverage,
    kind: PlacementCoverageKind,
    basis: PlacementBasis,
    scenario: String? = null,
) {
    if (value.kind != kind || value.ownerId != basis.buildId || value.scenarioId != scenario)
        placementReject(PlacementProblem.INVALID_DOMAIN)
}
