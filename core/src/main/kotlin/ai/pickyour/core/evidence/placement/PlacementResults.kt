package ai.pickyour.core.evidence.placement

import java.math.BigInteger

enum class PlacementOutcome { PASS, FAIL, UNKNOWN, SKIPPED }
enum class PlacementRule {
    DEPLOYMENT, LOCAL_HARDWARE, REMOTE_CAPACITY, BINDING, ADAPTER_ENGINE,
    PHYSICAL_MAP, MEMORY_POOL, INDEPENDENT_CEILING, RUNTIME_ACCESS, COVERAGE, WORKLOAD,
}
enum class PlacementReason {
    QUALIFIED, INAPPLICABLE, MISSING_OR_INELIGIBLE, CHANGED_BASIS, INCOMPLETE_COVERAGE,
    REQUIRED_BINDING_ABSENT, AMBIGUOUS_BINDING, WRONG_KIND_OR_DISABLED, MISSING_CATALOG_VERSION,
    BASE_ARTIFACT_MISMATCH, BINDING_CYCLE, UNKNOWN_RESPONSIBILITY, UNKNOWN_CAPACITY,
    OVER_UPPER_BOUND_BUDGET, UNKNOWN_PHYSICAL_IDENTITY, UNKNOWN_ACCESS,
    UNKNOWN_RESIDENCY, MISSING_ALLOCATION, UNKNOWN_ADMISSION,
}

/** Every check is confined to its actual domain, used evidence and retained unresolved observations. */
class PlacementCheck internal constructor(
    val rule: PlacementRule,
    val domain: PlacementDomain,
    val outcome: PlacementOutcome,
    val reason: PlacementReason,
    observations: Collection<PlacementAnswer> = emptyList(),
    evidence: Collection<PlacementEvidenceUse> = emptyList(),
    chargeIds: Collection<String> = emptyList(),
    val demandUpper: BigInteger? = null,
    val capacityLower: BigInteger? = null,
    val reserveUpper: BigInteger? = null,
    val mayNotFit: Boolean = false,
) {
    val observations = immutablePlacementList(observations)
    val evidence = immutablePlacementList(evidence)
    val chargeIds = immutablePlacementList(chargeIds)
    val checkedOnly = this.evidence.any { it.checkedOnly }
    val severity = "error"
    val onUnknown = "review"
    override fun toString() = "PlacementCheck(" + outcome.name + ")"
}

class PlacementResult internal constructor(
    basis: PlacementBasis,
    qualifications: PlacementQualifications,
    checks: Collection<PlacementCheck>,
    assumptions: Collection<String> = emptyList(),
) {
    val checks = immutablePlacementList(checks)
    val assumptions = immutablePlacementList(assumptions)
    val basisSha256 = basis.sha256
    val qualificationsSha256 = qualifications.sha256
    val outcome = aggregatePlacement(this.checks.filter {
        it.rule != PlacementRule.COVERAGE || it.outcome != PlacementOutcome.PASS
    }.map { it.outcome })
    /** Capacity arithmetic is conditional; no support, runtime, quality or measured-fit conclusion. */
    val conditionalCapacity = this.checks.any {
        it.rule in setOf(PlacementRule.MEMORY_POOL, PlacementRule.INDEPENDENT_CEILING)
    }
    val severity = "error"
    val onUnknown = "review"
    override fun toString() = "PlacementResult(" + outcome.name + ")"
}

internal fun aggregatePlacement(outcomes: Collection<PlacementOutcome>): PlacementOutcome = when {
    PlacementOutcome.FAIL in outcomes -> PlacementOutcome.FAIL
    PlacementOutcome.UNKNOWN in outcomes || outcomes.isEmpty() -> PlacementOutcome.UNKNOWN
    PlacementOutcome.PASS in outcomes -> PlacementOutcome.PASS
    else -> PlacementOutcome.SKIPPED
}

/** Completeness is a separately qualified, scoped claim; nonempty inputs do not supply it. */
class PlacementCoverage(
    val kind: PlacementCoverageKind,
    val ownerId: String,
    val scenarioId: String?,
    val claim: PlacementAnswer,
) {
    val domain = PlacementDomain.inventory(kind, ownerId, scenarioId)
    init { requirePlacementField(claim, PlacementField.BOOLEAN) }
    internal fun use(basis: PlacementBasis, qualifications: PlacementQualifications): PlacementEvidenceUse? =
        if (claim.bool() == true) qualifications.use(basis, claim, PlacementFact.COVERAGE, domain) else null
    internal fun check(basis: PlacementBasis, qualifications: PlacementQualifications): PlacementCheck {
        val use = use(basis, qualifications)
        return PlacementCheck(PlacementRule.COVERAGE, domain,
            if (use == null) PlacementOutcome.UNKNOWN else PlacementOutcome.PASS,
            if (use == null) PlacementReason.INCOMPLETE_COVERAGE else PlacementReason.QUALIFIED,
            listOf(claim), listOfNotNull(use))
    }
    override fun toString() = "PlacementCoverage"
}

/**
 * Already normalized bounds and their original quantity/unit/method/rounding record.
 * This adapter parses exact ByteCount strings; it does not perform or invent unit conversion.
 */
class PlacementByteRange(
    val lower: PlacementAnswer?,
    val upper: PlacementAnswer?,
    val trace: PlacementAnswer,
) {
    val observations = immutablePlacementList(listOfNotNull(lower, upper, trace))
    init {
        lower?.let { requirePlacementField(it, PlacementField.BYTE_COUNT) }
        upper?.let { requirePlacementField(it, PlacementField.BYTE_COUNT) }
        requirePlacementField(trace, PlacementField.QUANTITY_TRACE)
        val lo = lower?.bytes()
        val hi = upper?.bytes()
        if (lo != null && hi != null && lo > hi) placementReject(PlacementProblem.CONTRADICTORY_INPUT)
    }
    internal fun qualifiedLower(basis: PlacementBasis, q: PlacementQualifications,
                                domain: PlacementDomain) = qualifiedBound(lower, basis, q, domain)
    internal fun qualifiedUpper(basis: PlacementBasis, q: PlacementQualifications,
                                domain: PlacementDomain) = qualifiedBound(upper, basis, q, domain)
    private fun qualifiedBound(answer: PlacementAnswer?, basis: PlacementBasis, q: PlacementQualifications,
                               domain: PlacementDomain): PlacementQualifiedBound? {
        if (answer == null) return null
        val bound = q.use(basis, answer, PlacementFact.BYTE_BOUND, domain) ?: return null
        val normalization = q.use(basis, trace, PlacementFact.NORMALIZATION, domain) ?: return null
        return PlacementQualifiedBound(checkNotNull(answer.bytes()), listOf(bound, normalization))
    }
    override fun toString() = "PlacementByteRange"
}

internal class PlacementQualifiedBound(val value: BigInteger, uses: List<PlacementEvidenceUse>) {
    val uses = immutablePlacementList(uses)
}

/** Explicit reserve basis applies once to one physical pool or independent quota. */
enum class PlacementReserveBasis { BEFORE_RESERVE, AFTER_RESERVE }

class PlacementBudget(
    val capacity: PlacementByteRange,
    val reserve: PlacementByteRange,
    /** Original qualified capacity interpretation, not an independently mutable arithmetic switch. */
    val basis: PlacementAnswer,
    /** Actual reduced-budget/reservation identity, independently scoped even when R is explicit zero. */
    val reservationTrace: PlacementAnswer,
) {
    val observations = immutablePlacementList(capacity.observations + reserve.observations + basis + reservationTrace)
    init {
        requirePlacementField(basis, PlacementField.RESERVE_BASIS)
        requirePlacementField(reservationTrace, PlacementField.TEXT)
    }
    internal fun qualified(current: PlacementBasis, qualifications: PlacementQualifications,
                           domain: PlacementDomain): PlacementQualifiedBudget? {
        val cap = capacity.qualifiedLower(current, qualifications, domain) ?: return null
        val res = reserve.qualifiedUpper(current, qualifications, domain) ?: return null
        val trace = qualifications.use(current, reservationTrace, PlacementFact.NORMALIZATION, domain) ?: return null
        val interpretation = qualifications.use(current, basis, PlacementFact.NORMALIZATION, domain) ?: return null
        val selected = if (basis.text() == "before_reserve") PlacementReserveBasis.BEFORE_RESERVE
            else PlacementReserveBasis.AFTER_RESERVE
        return PlacementQualifiedBudget(cap.value, res.value, selected, cap.uses + res.uses + trace + interpretation)
    }
    override fun toString() = "PlacementBudget"
}

internal class PlacementQualifiedBudget(
    val capacityLower: BigInteger,
    val reserveUpper: BigInteger,
    val basis: PlacementReserveBasis,
    uses: List<PlacementEvidenceUse>,
) {
    val uses = immutablePlacementList(uses)
    fun fits(demandUpper: BigInteger) =
        demandUpper + (if (basis == PlacementReserveBasis.BEFORE_RESERVE) reserveUpper else BigInteger.ZERO) <= capacityLower
}
