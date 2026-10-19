package ai.pickyour.core.evidence.placement

import java.math.BigInteger

class PlacementSequenceClass(
    override val id: String,
    val requests: PlacementAnswer,
    val sequencesPerRequest: PlacementAnswer,
    val sameScopeAllocation: PlacementAnswer,
) : PlacementRecord {
    init {
        placementIdentity(id)
        requirePlacementField(requests, PlacementField.POSITIVE_INTEGER)
        requirePlacementField(sequencesPerRequest, PlacementField.POSITIVE_INTEGER)
        requirePlacementField(sameScopeAllocation, PlacementField.BOOLEAN)
    }
    val observations = immutablePlacementList(listOf(requests, sequencesPerRequest, sameScopeAllocation))
    override fun toString() = "PlacementSequenceClass"
}

class PlacementSequenceAdmission(
    val binding: PlacementRuntimeBinding,
    val limit: PlacementAnswer,
    val units: PlacementAnswer,
    val coversAllResidentKv: PlacementAnswer,
) {
    init {
        requirePlacementField(limit, PlacementField.POSITIVE_INTEGER)
        requirePlacementField(units, PlacementField.ADMISSION_UNIT)
        requirePlacementField(coversAllResidentKv, PlacementField.BOOLEAN)
    }
    val observations = immutablePlacementList(listOf(binding.answer, limit, units, coversAllResidentKv))
    override fun toString() = "PlacementSequenceAdmission"
}

class PlacementSequenceResult internal constructor(
    val result: PlacementResult,
    val expandedDemand: BigInteger?,
    val residentSequences: BigInteger?,
    val admissionKnown: Boolean,
    val conservativeFallback: Boolean,
) {
    override fun toString() = "PlacementSequenceResult"
}

/**
 * Qualified R*E demand in one actual engine/build/instance/replica/admission/stage domain.
 * Values are normalized inputs; no token geometry, KV bytes, stage maxima, or runtime support
 * are inferred. Required class completeness and simultaneous scope are independently assessed.
 */
class PlacementWorkload(
    val basis: PlacementBasis,
    private val graph: PlacementGraph,
    private val servingRequirements: PlacementServingRequirements,
    artifactFacts: List<PlacementArtifactFacts>,
    val binding: PlacementRuntimeBinding,
    val operation: PlacementAnswer,
    val applicable: PlacementAnswer,
    val classes: PlacementRecords<PlacementSequenceClass>,
    val admission: PlacementSequenceAdmission,
    /** Independent evidence that expanded demand itself bounds retained and queued KV state. */
    val expandedFallbackCoversAllResident: PlacementAnswer,
) {
    private val artifactFacts = immutablePlacementList(artifactFacts)
    init {
        admitPlacementArtifactFacts(basis, artifactFacts)
        if (graph.basis.sha256 != basis.sha256) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
        if (basis.selection(servingRequirements.selectionId) == null) placementReject(PlacementProblem.DANGLING_LINK)
        requirePlacementField(operation, PlacementField.TEXT)
        requirePlacementField(applicable, PlacementField.BOOLEAN)
        requirePlacementField(expandedFallbackCoversAllResident, PlacementField.BOOLEAN)
        requireMemoryCoverage(classes.coverage, PlacementCoverageKind.WORKLOAD_STAGES, basis)
        for (runtime in listOfNotNull(binding.runtime, admission.binding.runtime))
            if (runtime.kind == PlacementRuntimeKind.MODEL_ENGINE && basis.selection(runtime.id) == null)
                placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }

    fun evaluate(current: PlacementBasis, q: PlacementQualifications): PlacementSequenceResult {
        val domain = binding.domain ?: PlacementDomain.component(servingRequirements.selectionId)
        val observations = listOf(binding.answer, operation, applicable, expandedFallbackCoversAllResident,
            classes.ids, classes.coverage.claim) + classes.records.flatMap { it.observations } + admission.observations
        val uses = ArrayList<PlacementEvidenceUse>()
        val prerequisiteChecks = ArrayList<PlacementCheck>()
        val applicability = q.use(current, applicable, PlacementFact.APPLICABILITY, domain)
        fun result(outcome: PlacementOutcome, reason: PlacementReason, expanded: BigInteger? = null,
                   resident: BigInteger? = null, known: Boolean = false, fallback: Boolean = false): PlacementSequenceResult {
            val check = PlacementCheck(PlacementRule.WORKLOAD, domain, outcome, reason, observations, uses)
            return PlacementSequenceResult(PlacementResult(current, q, prerequisiteChecks + check,
                if (fallback) listOf("qualified_expanded_demand_fallback") else emptyList()),
                expanded, resident, known, fallback)
        }
        if (current.sha256 != basis.sha256)
            return result(PlacementOutcome.UNKNOWN, PlacementReason.CHANGED_BASIS)
        if (applicability == null) return result(PlacementOutcome.UNKNOWN, PlacementReason.MISSING_OR_INELIGIBLE)
        uses += applicability
        if (applicable.bool() == false) return result(PlacementOutcome.SKIPPED, PlacementReason.INAPPLICABLE)
        val scope = memoryUses(current, q, listOf(binding.answer, operation), PlacementFact.WORKLOAD, domain)
        val source = binding.sourceInput?.let(current::input)
        val serving = graph.serving(current, q, servingRequirements, artifactFacts)
        // Keep independent consistency findings while the dependent quantity remains unknown.
        prerequisiteChecks += serving.checks
        val selectedRuntime = binding.runtime?.takeIf { it.kind == PlacementRuntimeKind.MODEL_ENGINE }
            ?.id?.let(basis::selection)
        if (scope != null && operation.text() == basis.operation && selectedRuntime != null &&
            (selectedRuntime.kind != "inference_engine" || !selectedRuntime.enabled)) {
            prerequisiteChecks += PlacementCheck(PlacementRule.BINDING, domain, PlacementOutcome.FAIL,
                PlacementReason.WRONG_KIND_OR_DISABLED, listOf(binding.answer, operation), scope)
        }
        val exactEngine = binding.runtime?.kind == PlacementRuntimeKind.MODEL_ENGINE &&
            serving.engine?.check?.outcome == PlacementOutcome.PASS &&
            serving.engine.resolved.singleOrNull()?.id == binding.runtime?.id
        if (scope == null || binding.domain == null || operation.text() != basis.operation ||
            source?.kind != PlacementInputKind.ENGINE_BUILDS || !exactEngine ||
            serving.checks.any { it.outcome in setOf(PlacementOutcome.FAIL, PlacementOutcome.UNKNOWN) })
            return result(PlacementOutcome.UNKNOWN, PlacementReason.MISSING_OR_INELIGIBLE)
        uses += scope
        uses += serving.checks.flatMap { it.evidence }
        val inventory = classes.check(current, q)
        if (inventory.outcome != PlacementOutcome.PASS || classes.records.isEmpty())
            return result(PlacementOutcome.UNKNOWN, PlacementReason.INCOMPLETE_COVERAGE)
        uses += inventory.evidence
        var expanded = BigInteger.ZERO
        for (entry in classes.records) {
            val classDomain = PlacementDomain.workload(domain, entry.id)
            val classUses = memoryUses(current, q, entry.observations, PlacementFact.WORKLOAD, classDomain)
            if (classUses == null || entry.sameScopeAllocation.bool() != true)
                return result(PlacementOutcome.UNKNOWN, PlacementReason.MISSING_OR_INELIGIBLE)
            uses += classUses
            expanded += checkNotNull(entry.requests.integer()) * checkNotNull(entry.sequencesPerRequest.integer())
        }
        val admissionUses = memoryUses(current, q, admission.observations, PlacementFact.WORKLOAD, domain)
        val knownAdmission = admissionUses != null && admission.binding.domain == binding.domain &&
            admission.units.text() == "sequences" && admission.coversAllResidentKv.bool() == true
        if (knownAdmission) {
            uses += checkNotNull(admissionUses)
            val resident = expanded.min(checkNotNull(admission.limit.integer()))
            return result(PlacementOutcome.PASS, PlacementReason.QUALIFIED, expanded, resident, known = true)
        }
        // A wrong-domain, request-unit or per-step cap cannot reduce resident demand.
        val fallback = q.use(current, expandedFallbackCoversAllResident, PlacementFact.WORKLOAD, domain)
        if (fallback != null && expandedFallbackCoversAllResident.bool() == true) {
            uses += fallback
            return result(PlacementOutcome.UNKNOWN, PlacementReason.UNKNOWN_ADMISSION,
                expanded, expanded, fallback = true)
        }
        return result(PlacementOutcome.UNKNOWN, PlacementReason.UNKNOWN_ADMISSION, expanded)
    }

    override fun toString() = "PlacementWorkload"
}
