package ai.pickyour.core.evidence.placement

enum class PlacementRole(val encoded: String, val relation: String, val single: Boolean,
                         internal val targetKinds: Set<String>) {
    HOST("host", "runs_on", true, setOf("hosting_platform")),
    DEVICE("device", "runs_on", false, setOf("accelerator", "compute_profile")),
    ENGINE("serving_engine", "served_by", true, setOf("inference_engine")),
    BASE("base_artifact", "derived_from", true, setOf("model", "fine_tuned_model")),
    DRAFT_TARGET("speculative_draft", "feeds", true, setOf("model", "fine_tuned_model")),
}

/** Only metadata for an existing frozen edge; no duplicated endpoints or alternate graph. */
class PlacementLinkMeaning(val linkId: String, val role: PlacementAnswer, val operation: PlacementAnswer) {
    init {
        placementIdentity(linkId)
        requirePlacementField(role, PlacementField.ROLE)
        requirePlacementField(operation, PlacementField.TEXT)
    }
    override fun toString() = "PlacementLinkMeaning"
}

class PlacementBinding internal constructor(
    val check: PlacementCheck,
    candidates: List<PlacementSelection>,
    resolved: List<PlacementSelection>,
) {
    /** Original graph order is retained; it never chooses a winner. */
    val candidates = immutablePlacementList(candidates)
    val resolved = immutablePlacementList(resolved)
    override fun toString() = "PlacementBinding(" + check.outcome.name + ")"
}

/** Immutable operation-specific reading of the one accepted selection graph. */
class PlacementGraph(
    val basis: PlacementBasis,
    meanings: List<PlacementLinkMeaning>,
    val coverage: PlacementCoverage,
) {
    private val meanings: Map<String, PlacementLinkMeaning>
    init {
        if (meanings.size > PlacementBasis.MAX_LINKS) placementReject(PlacementProblem.INPUT_LIMIT)
        if (coverage.kind != PlacementCoverageKind.ROLE_BINDINGS || coverage.ownerId != basis.buildId ||
            coverage.scenarioId != basis.operation) placementReject(PlacementProblem.INVALID_DOMAIN)
        val links = basis.links.associateBy { it.id }
        val entries = linkedMapOf<String, PlacementLinkMeaning>()
        for (meaning in meanings) {
            if (meaning.linkId !in links) placementReject(PlacementProblem.DANGLING_LINK)
            if (entries.put(meaning.linkId, meaning) != null) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
        }
        this.meanings = immutablePlacementMap(entries)
    }

    fun resolve(current: PlacementBasis, qualifications: PlacementQualifications, fromSelectionId: String,
                role: PlacementRole, required: PlacementAnswer): PlacementBinding {
        placementIdentity(fromSelectionId)
        requirePlacementField(required, PlacementField.BOOLEAN)
        val from = basis.selection(fromSelectionId) ?: placementReject(PlacementProblem.DANGLING_LINK)
        val domain = PlacementDomain.role(fromSelectionId, role.encoded)
        val observations = mutableListOf(required, coverage.claim)
        val uses = mutableListOf<PlacementEvidenceUse>()
        val candidates = mutableListOf<PlacementSelection>()
        val selected = mutableListOf<PlacementSelection>()
        var incomplete = false
        var invalidTarget = false
        val fromCatalog = basis.catalogItem(from)
        var missingVersion = fromCatalog == null
        val requiredUse = qualifications.use(current, required, PlacementFact.APPLICABILITY, domain)
        val coverageUse = coverage.use(current, qualifications)
        uses.addAll(listOfNotNull(requiredUse, coverageUse))
        for (link in basis.links.filter { it.from == fromSelectionId && it.relation == role.relation }) {
            val target = checkNotNull(basis.selection(link.to)) // All endpoints were checked on admission.
            val meaning = meanings[link.id]
            if (meaning == null) {
                incomplete = true
                candidates += target
                continue
            }
            observations += listOf(meaning.role, meaning.operation)
            val linkDomain = PlacementDomain.link(link.id)
            val roleUse = qualifications.use(current, meaning.role, PlacementFact.ROLE, linkDomain)
            val operationUse = qualifications.use(current, meaning.operation, PlacementFact.OPERATION, linkDomain)
            if (roleUse == null || operationUse == null) {
                incomplete = true
                candidates += target
                continue
            }
            uses += listOf(roleUse, operationUse)
            if (meaning.role.text() != role.encoded || meaning.operation.text() != basis.operation) continue
            candidates += target
            selected += target
            if (!target.enabled || target.kind !in role.targetKinds) invalidTarget = true
            val catalog = basis.catalogItem(target)
            if (catalog == null) missingVersion = true
            else if (catalog["kind"].textValue() != target.kind) invalidTarget = true
        }
        fun result(outcome: PlacementOutcome, reason: PlacementReason) = PlacementBinding(
            PlacementCheck(PlacementRule.BINDING, domain, outcome, reason, observations, uses),
            candidates, if (outcome == PlacementOutcome.PASS) selected else emptyList())
        if (basis.sha256 != current.sha256) return result(PlacementOutcome.UNKNOWN, PlacementReason.CHANGED_BASIS)
        if (fromCatalog != null && fromCatalog["kind"].textValue() != from.kind)
            return result(PlacementOutcome.FAIL, PlacementReason.WRONG_KIND_OR_DISABLED)
        if (!from.enabled) return result(PlacementOutcome.SKIPPED, PlacementReason.INAPPLICABLE)
        if (requiredUse == null) return result(PlacementOutcome.UNKNOWN, PlacementReason.MISSING_OR_INELIGIBLE)
        if (invalidTarget) return result(
            if (required.bool() == true) PlacementOutcome.FAIL else PlacementOutcome.UNKNOWN,
            PlacementReason.WRONG_KIND_OR_DISABLED)
        if (role.single && candidates.size > 1)
            return result(PlacementOutcome.UNKNOWN, PlacementReason.AMBIGUOUS_BINDING)
        if (incomplete || coverageUse == null)
            return result(PlacementOutcome.UNKNOWN, PlacementReason.INCOMPLETE_COVERAGE)
        if (selected.isEmpty()) return result(
            if (required.bool() == true) PlacementOutcome.FAIL else PlacementOutcome.SKIPPED,
            if (required.bool() == true) PlacementReason.REQUIRED_BINDING_ABSENT else PlacementReason.INAPPLICABLE)
        if (missingVersion) return result(PlacementOutcome.UNKNOWN, PlacementReason.MISSING_CATALOG_VERSION)
        return result(PlacementOutcome.PASS, PlacementReason.QUALIFIED)
    }

    /** Exact lineage facts do not copy a full/merged artifact's base capabilities. */
    fun lineage(current: PlacementBasis, qualifications: PlacementQualifications, selectionId: String,
                facts: List<PlacementArtifactFacts>): PlacementLineage {
        val bySelection = admitPlacementArtifactFacts(basis, facts)
        val start = basis.selection(selectionId) ?: placementReject(PlacementProblem.DANGLING_LINK)
        val path = mutableListOf<PlacementSelection>()
        val checks = mutableListOf<PlacementCheck>()
        val visited = linkedSetOf<String>()
        // A dependent exit retains the exact qualified facts that selected this lineage.
        val usedObservations = mutableListOf<PlacementAnswer>()
        val usedEvidence = mutableListOf<PlacementEvidenceUse>()
        var selected = start
        fun lineageCheck(rule: PlacementRule, domain: PlacementDomain, outcome: PlacementOutcome,
                         reason: PlacementReason, observations: List<PlacementAnswer> = emptyList(),
                         evidence: List<PlacementEvidenceUse> = emptyList()) =
            PlacementCheck(rule, domain, outcome, reason,
                usedObservations + observations, usedEvidence + evidence)
        fun unknown(reason: PlacementReason): PlacementLineage {
            checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, PlacementDomain.component(selected.id),
                PlacementOutcome.UNKNOWN, reason)
            return PlacementLineage(path, checks, null)
        }
        if (current.sha256 != basis.sha256) return unknown(PlacementReason.CHANGED_BASIS)
        while (true) {
            if (!visited.add(selected.id)) return unknown(PlacementReason.BINDING_CYCLE)
            path += selected
            val catalog = basis.catalogItem(selected) ?: return unknown(PlacementReason.MISSING_CATALOG_VERSION)
            if (catalog["kind"].textValue() != selected.kind) {
                checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, PlacementDomain.component(selected.id),
                    PlacementOutcome.FAIL, PlacementReason.WRONG_KIND_OR_DISABLED)
                return PlacementLineage(path, checks, null)
            }
            if (selected.kind != "fine_tuned_model") return PlacementLineage(path, checks, selected)
            val fact = bySelection[selected.id] ?: return unknown(PlacementReason.MISSING_OR_INELIGIBLE)
            val domain = PlacementDomain.component(selected.id)
            usedObservations += fact.artifactKind
            val kindUse = qualifications.use(current, fact.artifactKind, PlacementFact.COMPATIBILITY, domain)
                ?: return unknown(PlacementReason.MISSING_OR_INELIGIBLE)
            usedEvidence += kindUse
            val adapter = fact.artifactKind.text() == "adapter"
            val recorded = catalog["spec"].get("adapter_only")
            if (recorded != null && recorded.booleanValue() != adapter) {
                checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, domain, PlacementOutcome.FAIL,
                    PlacementReason.BASE_ARTIFACT_MISMATCH)
                return PlacementLineage(path, checks, null)
            }
            if (!adapter) {
                checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, domain, PlacementOutcome.PASS,
                    PlacementReason.QUALIFIED)
                return PlacementLineage(path, checks, selected)
            }
            val base = resolve(current, qualifications, selected.id, PlacementRole.BASE, fact.baseRequired)
            checks += lineageCheck(base.check.rule, base.check.domain, base.check.outcome, base.check.reason,
                base.check.observations, base.check.evidence)
            if (base.check.outcome != PlacementOutcome.PASS) return PlacementLineage(path, checks, null)
            usedObservations += base.check.observations
            usedEvidence += base.check.evidence
            val target = base.resolved.single()
            if (catalog["spec"]["base_model_ref"].textValue() != target.catalogItemId) {
                checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, domain, PlacementOutcome.FAIL,
                    PlacementReason.BASE_ARTIFACT_MISMATCH)
                return PlacementLineage(path, checks, null)
            }
            usedObservations += fact.baseVersion
            val versionUse = qualifications.use(current, fact.baseVersion, PlacementFact.COMPATIBILITY, domain)
                ?: return unknown(PlacementReason.MISSING_CATALOG_VERSION)
            usedEvidence += versionUse
            if (fact.baseVersion.text() != target.catalogItemVersion) {
                checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, domain, PlacementOutcome.FAIL,
                    PlacementReason.BASE_ARTIFACT_MISMATCH)
                return PlacementLineage(path, checks, null)
            }
            checks += lineageCheck(PlacementRule.ADAPTER_ENGINE, domain, PlacementOutcome.PASS,
                PlacementReason.QUALIFIED)
            selected = target
        }
    }

    override fun toString() = "PlacementGraph"
}

class PlacementArtifactFacts(
    val selectionId: String,
    val artifactKind: PlacementAnswer,
    val baseVersion: PlacementAnswer,
    val baseRequired: PlacementAnswer,
) {
    init {
        placementIdentity(selectionId)
        requirePlacementField(artifactKind, PlacementField.ARTIFACT_KIND)
        requirePlacementField(baseVersion, PlacementField.TEXT)
        requirePlacementField(baseRequired, PlacementField.BOOLEAN)
        // An adapter requires its base; a caller cannot convert its required lineage to optional.
        if (artifactKind.text() == "adapter" && baseRequired.bool() == false)
            placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }
    override fun toString() = "PlacementArtifactFacts"
}

class PlacementLineage internal constructor(
    artifacts: List<PlacementSelection>, checks: List<PlacementCheck>, val servingArtifact: PlacementSelection?,
) {
    val artifacts = immutablePlacementList(artifacts)
    val checks = immutablePlacementList(checks)
    val outcome = when {
        this.checks.any { it.outcome == PlacementOutcome.FAIL } -> PlacementOutcome.FAIL
        servingArtifact == null -> PlacementOutcome.UNKNOWN
        else -> PlacementOutcome.PASS
    }
    override fun toString() = "PlacementLineage(" + outcome.name + ")"
}

/** Structural admission is shared by all callers and always precedes outcome shortcuts. */
internal fun admitPlacementArtifactFacts(
    basis: PlacementBasis, facts: List<PlacementArtifactFacts>,
): Map<String, PlacementArtifactFacts> {
    if (facts.size > PlacementBasis.MAX_SELECTIONS) placementReject(PlacementProblem.INPUT_LIMIT)
    val bySelection = linkedMapOf<String, PlacementArtifactFacts>()
    for (fact in facts) {
        if (basis.selection(fact.selectionId) == null) placementReject(PlacementProblem.DANGLING_LINK)
        if (bySelection.put(fact.selectionId, fact) != null) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
    }
    return immutablePlacementMap(bySelection)
}
