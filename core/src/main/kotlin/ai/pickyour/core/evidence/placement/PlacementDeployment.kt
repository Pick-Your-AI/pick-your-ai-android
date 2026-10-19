package ai.pickyour.core.evidence.placement

enum class PlacementMode { SELF_HOSTED, MANAGED, API, HYBRID, UNKNOWN }
enum class PlacementApplicability { APPLICABLE, INAPPLICABLE, UNKNOWN }
enum class PlacementConditionKind { LOCAL_HARDWARE, SERVICE_SUPPORT, ARTIFACT_SUPPORT }

class PlacementDeploymentRow(
    val selectionId: String,
    val operator: PlacementAnswer,
    val capacity: PlacementAnswer,
    val exposedHardware: PlacementAnswer,
) {
    init {
        placementIdentity(selectionId)
        requirePlacementField(operator, PlacementField.OPERATOR)
        requirePlacementField(capacity, PlacementField.CAPACITY)
        requirePlacementField(exposedHardware, PlacementField.BOOLEAN)
    }
    override fun toString() = "PlacementDeploymentRow"
}

/** One already normalized, operation-specific technical condition. No rights assessment is performed. */
class PlacementSupportCondition(
    val selectionId: String,
    val conditionId: String,
    val kind: PlacementConditionKind,
    val applicable: PlacementAnswer,
    val satisfied: PlacementAnswer,
) {
    val domain = PlacementDomain.condition(selectionId, conditionId, kind.name)
    init {
        placementIdentity(selectionId)
        placementIdentity(conditionId)
        requirePlacementField(applicable, PlacementField.BOOLEAN)
        requirePlacementField(satisfied, PlacementField.BOOLEAN)
    }
    internal fun check(basis: PlacementBasis, qualifications: PlacementQualifications): PlacementCheck {
        val applies = qualifications.use(basis, applicable, PlacementFact.APPLICABILITY, domain)
        val result = qualifications.use(basis, satisfied, PlacementFact.COMPATIBILITY, domain)
        val outcome = when {
            applies == null -> PlacementOutcome.UNKNOWN
            applicable.bool() == false -> PlacementOutcome.SKIPPED
            result == null -> PlacementOutcome.UNKNOWN
            satisfied.bool() == true -> PlacementOutcome.PASS
            else -> PlacementOutcome.FAIL
        }
        return PlacementCheck(if (kind == PlacementConditionKind.LOCAL_HARDWARE) PlacementRule.LOCAL_HARDWARE
            else PlacementRule.RUNTIME_ACCESS, domain, outcome,
            if (outcome == PlacementOutcome.UNKNOWN) PlacementReason.MISSING_OR_INELIGIBLE
            else if (outcome == PlacementOutcome.SKIPPED) PlacementReason.INAPPLICABLE else PlacementReason.QUALIFIED,
            listOf(applicable, satisfied), listOfNotNull(applies) +
                if (applies != null && applicable.bool() == true) listOfNotNull(result) else emptyList())
    }
    override fun toString() = "PlacementSupportCondition"
}

/** Declared condition IDs and their independent completeness assessment for one selected subject. */
class PlacementConditionInventory(
    val ids: PlacementAnswer,
    val coverage: PlacementCoverage,
) {
    init { requirePlacementField(ids, PlacementField.TEXT_SET) }
    internal fun check(basis: PlacementBasis, q: PlacementQualifications): PlacementCheck {
        val inventory = q.use(basis, ids, PlacementFact.INVENTORY, coverage.domain)
        val complete = coverage.use(basis, q)
        val known = inventory != null && complete != null
        return PlacementCheck(PlacementRule.COVERAGE, coverage.domain,
            if (known) PlacementOutcome.PASS else PlacementOutcome.UNKNOWN,
            if (known) PlacementReason.QUALIFIED else PlacementReason.INCOMPLETE_COVERAGE,
            listOf(ids, coverage.claim), listOfNotNull(inventory, complete))
    }
    override fun toString() = "PlacementConditionInventory"
}

class PlacementComponentMode internal constructor(
    val selectionId: String,
    val effectiveSelectionId: String?,
    val mode: PlacementMode,
    val localApplicability: PlacementApplicability,
    val serving: PlacementServingBinding?,
    checks: Collection<PlacementCheck>,
    applicabilityEvidence: Collection<PlacementEvidenceUse> = emptyList(),
) {
    val checks = immutablePlacementList(checks)
    val applicabilityEvidence = immutablePlacementList(applicabilityEvidence)
    override fun toString() = "PlacementComponentMode"
}

class PlacementDeploymentResult internal constructor(
    val result: PlacementResult,
    components: Collection<PlacementComponentMode>,
    servingMembershipKnown: Boolean,
) {
    val components = immutablePlacementList(components)
    val mode: PlacementMode = when {
        this.components.isEmpty() || this.components.any { it.mode == PlacementMode.UNKNOWN } ||
            !servingMembershipKnown -> PlacementMode.UNKNOWN
        this.components.map { it.mode }.toSet().size == 1 -> this.components.first().mode
        else -> PlacementMode.HYBRID
    }
    override fun toString() = "PlacementDeploymentResult(" + mode.name + ")"
}

/**
 * Serving membership and per-component predicates; no Build-wide mode suppresses a local check.
 * Operator, capacity and exposure are admitted answers. Protocol, billing and catalog access labels
 * are retained in the basis but never stand in for these facts.
 */
class PlacementDeployment(
    val basis: PlacementBasis,
    private val graph: PlacementGraph,
    rows: List<PlacementDeploymentRow>,
    artifactFacts: List<PlacementArtifactFacts>,
    conditions: List<PlacementSupportCondition>,
    conditionCoverage: List<PlacementConditionInventory>,
    private val servingCoverage: PlacementCoverage,
    requirements: List<PlacementServingRequirements>,
) {
    private val rows: Map<String, PlacementDeploymentRow>
    private val artifactFacts = immutablePlacementList(artifactFacts)
    private val conditions = immutablePlacementList(conditions)
    private val conditionCoverage: Map<String, PlacementConditionInventory>
    private val requirements: Map<String, PlacementServingRequirements>

    init {
        admitPlacementArtifactFacts(basis, artifactFacts)
        if (graph.basis.sha256 != basis.sha256) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        if (rows.size > PlacementBasis.MAX_SELECTIONS || conditions.size > 2048 ||
            conditionCoverage.size > PlacementBasis.MAX_SELECTIONS || requirements.size > PlacementBasis.MAX_SELECTIONS) placementReject(PlacementProblem.INPUT_LIMIT)
        if (servingCoverage.kind != PlacementCoverageKind.SERVING_COMPONENTS ||
            servingCoverage.ownerId != basis.buildId || servingCoverage.scenarioId != basis.operation)
            placementReject(PlacementProblem.INVALID_DOMAIN)
        if (rows.map { it.selectionId }.toSet().size != rows.size ||
            conditions.map { it.selectionId to it.conditionId }.toSet().size != conditions.size ||
            artifactFacts.map { it.selectionId }.toSet().size != artifactFacts.size ||
            conditionCoverage.map { it.coverage.ownerId }.toSet().size != conditionCoverage.size ||
            requirements.map { it.selectionId }.toSet().size != requirements.size)
            placementReject(PlacementProblem.DUPLICATE_IDENTITY)
        val references = rows.map { it.selectionId } + conditions.map { it.selectionId } +
            artifactFacts.map { it.selectionId } + conditionCoverage.map { it.coverage.ownerId } + requirements.map { it.selectionId }
        if (references.any { basis.selection(it) == null }) placementReject(PlacementProblem.DANGLING_LINK)
        for (inventory in conditionCoverage) {
            val coverage = inventory.coverage
            if (coverage.kind != PlacementCoverageKind.SUPPORT_CONDITIONS || coverage.scenarioId != basis.operation)
                placementReject(PlacementProblem.INVALID_DOMAIN)
            val suppliedIds = conditions.filter { it.selectionId == coverage.ownerId }.map { it.conditionId }.toSet()
            if (inventory.ids.provided && inventory.ids.strings() != suppliedIds)
                placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        }
        this.rows = immutablePlacementMap(rows.associateBy { it.selectionId })
        this.conditionCoverage = immutablePlacementMap(conditionCoverage.associateBy { it.coverage.ownerId })
        this.requirements = immutablePlacementMap(requirements.associateBy { it.selectionId })
    }

    fun evaluate(current: PlacementBasis, qualifications: PlacementQualifications): PlacementDeploymentResult {
        val components = mutableListOf<PlacementComponentMode>()
        val checks = mutableListOf(servingCoverage.check(current, qualifications))
        for (selection in basis.selections.filter { it.enabled && it.kind in SERVING_KINDS }) {
            val serving = requirements[selection.id]?.let { graph.serving(current, qualifications, it, artifactFacts) }
            val lineage = serving?.lineage ?: graph.lineage(current, qualifications, selection.id, artifactFacts)
            val componentChecks = (serving?.checks ?: lineage.checks).toMutableList()
            if (serving == null) componentChecks += PlacementCheck(PlacementRule.BINDING,
                PlacementDomain.component(selection.id), PlacementOutcome.UNKNOWN, PlacementReason.INCOMPLETE_COVERAGE)
            val conditionSubjects = (lineage.artifacts + serving?.engine?.resolved.orEmpty() +
                serving?.host?.resolved.orEmpty() + serving?.devices?.resolved.orEmpty()).distinctBy { it.id }
            for (artifact in conditionSubjects) {
                val coverage = conditionCoverage[artifact.id]
                componentChecks += coverage?.check(current, qualifications) ?: PlacementCheck(
                    PlacementRule.COVERAGE, PlacementDomain.component(artifact.id), PlacementOutcome.UNKNOWN,
                    PlacementReason.INCOMPLETE_COVERAGE)
                // Nonlocal support is independent of the local-device predicate and remains visible.
                componentChecks += conditions.filter {
                    it.selectionId == artifact.id && it.kind != PlacementConditionKind.LOCAL_HARDWARE
                }.map { it.check(current, qualifications) }
            }
            val effective = lineage.servingArtifact
            val row = effective?.let { rows[it.id] }
            val domain = PlacementDomain.component(selection.id)
            if (row == null || lineage.outcome != PlacementOutcome.PASS) {
                componentChecks += PlacementCheck(PlacementRule.DEPLOYMENT, domain, PlacementOutcome.UNKNOWN,
                    if (basis.sha256 != current.sha256) PlacementReason.CHANGED_BASIS
                    else PlacementReason.MISSING_OR_INELIGIBLE)
                components += PlacementComponentMode(selection.id, effective?.id, PlacementMode.UNKNOWN,
                    PlacementApplicability.UNKNOWN, serving, componentChecks)
                checks += componentChecks
                continue
            }
            val effectiveDomain = PlacementDomain.component(effective.id)
            val operatorUse = qualifications.use(current, row.operator, PlacementFact.OPERATOR, effectiveDomain)
            val capacityUse = qualifications.use(current, row.capacity, PlacementFact.CAPACITY, effectiveDomain)
            val exposureUse = qualifications.use(current, row.exposedHardware, PlacementFact.LOCAL_VISIBILITY, effectiveDomain)
            val operator = if (operatorUse != null) row.operator.text() else null
            val capacity = if (capacityUse != null) row.capacity.text() else null
            val exposure = if (exposureUse != null) row.exposedHardware.bool() else null
            val mode = when {
                operator == "self" -> PlacementMode.SELF_HOSTED
                operator == "provider" && capacity == "shared" -> PlacementMode.API
                operator == "provider" && capacity in setOf("reserved_throughput", "dedicated_instances") -> PlacementMode.MANAGED
                else -> PlacementMode.UNKNOWN
            }
            val applies = when {
                mode == PlacementMode.UNKNOWN -> PlacementApplicability.UNKNOWN
                mode == PlacementMode.SELF_HOSTED -> PlacementApplicability.APPLICABLE
                exposure == true -> PlacementApplicability.APPLICABLE
                exposure == false -> PlacementApplicability.INAPPLICABLE
                else -> PlacementApplicability.UNKNOWN
            }
            componentChecks += PlacementCheck(PlacementRule.DEPLOYMENT, domain,
                if (mode == PlacementMode.UNKNOWN) PlacementOutcome.UNKNOWN else PlacementOutcome.PASS,
                if (mode == PlacementMode.UNKNOWN) PlacementReason.UNKNOWN_RESPONSIBILITY else PlacementReason.QUALIFIED,
                listOf(row.operator, row.capacity, row.exposedHardware),
                listOfNotNull(operatorUse) + if (operator == "provider") listOfNotNull(capacityUse) else emptyList())
            when (applies) {
                PlacementApplicability.UNKNOWN -> componentChecks += PlacementCheck(PlacementRule.LOCAL_HARDWARE,
                    domain, PlacementOutcome.UNKNOWN, PlacementReason.UNKNOWN_RESPONSIBILITY,
                    listOf(row.exposedHardware), listOfNotNull(exposureUse))
                PlacementApplicability.INAPPLICABLE -> {
                    componentChecks += PlacementCheck(PlacementRule.LOCAL_HARDWARE, domain, PlacementOutcome.SKIPPED,
                        PlacementReason.INAPPLICABLE, listOf(row.exposedHardware), listOfNotNull(exposureUse))
                    componentChecks += PlacementCheck(PlacementRule.REMOTE_CAPACITY, domain, PlacementOutcome.UNKNOWN,
                        PlacementReason.UNKNOWN_CAPACITY)
                }
                PlacementApplicability.APPLICABLE -> for (artifact in conditionSubjects) {
                    componentChecks += conditions.filter {
                        it.selectionId == artifact.id && it.kind == PlacementConditionKind.LOCAL_HARDWARE
                    }.map { it.check(current, qualifications) }
                }
            }
            val applicabilityEvidence = listOfNotNull(operatorUse) +
                if (operator == "provider") listOfNotNull(capacityUse, exposureUse) else emptyList()
            components += PlacementComponentMode(selection.id, effective.id, mode, applies, serving, componentChecks,
                applicabilityEvidence)
            checks += componentChecks
        }
        if (components.isEmpty()) {
            val coverageUse = servingCoverage.use(current, qualifications)
            checks += PlacementCheck(PlacementRule.DEPLOYMENT, PlacementDomain.component(basis.buildId),
                if (coverageUse == null) PlacementOutcome.UNKNOWN else PlacementOutcome.SKIPPED,
                if (coverageUse == null) PlacementReason.INCOMPLETE_COVERAGE else PlacementReason.INAPPLICABLE,
                listOf(servingCoverage.claim), listOfNotNull(coverageUse))
        }
        return PlacementDeploymentResult(PlacementResult(current, qualifications, checks), components,
            servingCoverage.use(current, qualifications) != null)
    }

    override fun toString() = "PlacementDeployment"
    companion object {
        private val SERVING_KINDS = setOf("model", "embedding_model", "vector_database", "fine_tuned_model")
    }
}
