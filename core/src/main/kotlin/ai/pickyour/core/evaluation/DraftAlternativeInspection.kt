package ai.pickyour.core.evaluation

import java.util.Collections

/** Limits of these local rule observations, not unavailable values silently treated as zero. */
enum class DraftAlternativeGap {
    OBJECTIVE_TEXT_NOT_INTERPRETED, EXTERNAL_EVIDENCE_APPLICABILITY_NOT_ESTABLISHED,
    COST_COMPARISON_UNAVAILABLE, RESOURCE_COMPARISON_UNAVAILABLE, MEASURED_QUALITY_UNAVAILABLE,
    RUNTIME_READINESS_NOT_ESTABLISHED,
}

/** Only these two contexts are freshly evaluated by the inspector; slot-empty stays generic. */
enum class DraftAlternativeSide { CURRENT, CANDIDATE }

sealed interface DraftAlternativePreparation {
    class Ready(val request: DraftAlternativeInspectionRequest) : DraftAlternativePreparation
    data class Unavailable(val reason: CompatibleOptionsUnavailable) : DraftAlternativePreparation
}

/**
 * One exact option from an already completed compatible-options request. The stored current choice,
 * candidate and slot-empty context use the same captured workload, catalog and ruleset. This capture
 * supplies no source-liveness, command, price, objective-policy or frozen-report authority.
 */
class DraftAlternativeInspectionRequest private constructor(
    private val parent: DraftCompatibleOptionsRequest,
    private val result: DraftCompatibleOptionsOutcome.Completed,
    private val option: DraftCompatibleOption,
    internal val current: DraftDiagnosticContext,
    internal val candidate: DraftDiagnosticContext,
    val currentSelectionJson: String?,
) {
    val profile: String = PROFILE
    val compatibleOptionsProfile: String get() = parent.profile
    val binding: DraftCompatibleOptionsBinding get() = parent.binding
    val verdict: CompatibleOptionVerdict get() = option.verdict
    val candidateIdentity: CompatibleOptionIdentity get() = option.identity
    val candidateSelectionJson: String = option.selection.toJson().toString()
    val currentContextJson: String = current.bytes().decodeToString()
    val candidateContextJson: String = candidate.bytes().decodeToString()
    val slotEmptyContextJson: String = parent.baseline.bytes().decodeToString()
    val unrepresentedFieldGroups: List<String> = alternativeList(parent.unrepresentedFieldGroups)
    val gaps: Set<DraftAlternativeGap> = Collections.unmodifiableSet(LinkedHashSet(DraftAlternativeGap.entries))
    internal val baselineFindings: List<DraftRuleFinding> = alternativeList(result.baselineFindings.map(::copyFinding))
    internal val reasons: List<DraftCompatibleOptionReason> = alternativeList(option.reasons.map {
        DraftCompatibleOptionReason(it.baselineOutcome, copyFinding(it.finding))
    })
    internal fun catalogBytes(): ByteArray = parent.catalogBytes()
    internal fun rulesetBytes(): ByteArray = parent.rulesetBytes()
    fun belongsTo(request: DraftCompatibleOptionsRequest, completed: DraftCompatibleOptionsOutcome.Completed,
        selected: DraftCompatibleOption): Boolean = parent === request && result === completed && option === selected
    override fun toString(): String = "DraftAlternativeInspectionRequest"

    companion object {
        const val PROFILE = "local-stored-draft-alternative-observations/1"

        /** Repeated same-byte captures and equal-looking options do not substitute for these objects. */
        fun prepare(parent: DraftCompatibleOptionsRequest, result: DraftCompatibleOptionsOutcome.Completed,
            option: DraftCompatibleOption): DraftAlternativePreparation {
            if (!result.belongsTo(parent) || result.options.none { it === option })
                return DraftAlternativePreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            val candidate = parent.options.singleOrNull { it.first === option.selection }?.second
                ?: return DraftAlternativePreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_OUTPUT)
            return when (val captured = parent.captureCurrentContext()) {
                is DraftCurrentContextCapture.Unavailable -> DraftAlternativePreparation.Unavailable(captured.reason)
                is DraftCurrentContextCapture.Ready -> DraftAlternativePreparation.Ready(DraftAlternativeInspectionRequest(
                    parent, result, option, captured.context, candidate, captured.selection?.toJson()?.toString()))
            }
        }
    }
}

/**
 * Full observations of the same literal rule under three captured contexts. An outcome transition
 * is not a causal claim or a measured improvement; skipped and unknown keep their separate meaning.
 * The optional candidate reason is the existing slot-empty verdict explanation, without reranking.
 */
class DraftAlternativeRuleObservation internal constructor(
    val ruleId: String,
    val current: DraftRuleFinding,
    val candidate: DraftRuleFinding,
    val slotEmpty: DraftRuleFinding,
    val candidateReason: DraftCompatibleOptionReason?,
) {
    val outcomeChanged: Boolean get() = current.outcome != candidate.outcome
    val detailsChanged: Boolean get() = !sameFinding(current, candidate)
    override fun toString(): String = "DraftAlternativeRuleObservation"
}

sealed class DraftAlternativeOutcome protected constructor(private val request: DraftAlternativeInspectionRequest) {
    fun belongsTo(expected: DraftAlternativeInspectionRequest): Boolean = request === expected
    class Completed internal constructor(request: DraftAlternativeInspectionRequest,
        observations: List<DraftAlternativeRuleObservation>,
        currentTrace: FrozenDiagnosticEvaluator.AlternativeDraftTrace? = null,
        candidateTrace: FrozenDiagnosticEvaluator.AlternativeDraftTrace? = null) : DraftAlternativeOutcome(request) {
        val observations: List<DraftAlternativeRuleObservation> = alternativeList(observations)
        private val observationRequest = request
        private class Unknowns(phases: Map<String, DraftCurrentUnknownPhase>,
            inputs: Map<String, List<UnknownInputObservation>>) {
            val phases = Collections.unmodifiableMap(LinkedHashMap(phases))
            val inputs = Collections.unmodifiableMap(LinkedHashMap(inputs.mapValues { alternativeList(it.value) }))
        }
        private fun capture(side: DraftAlternativeSide, trace: FrozenDiagnosticEvaluator.AlternativeDraftTrace?): Unknowns {
            val producer = trace ?: return Unknowns(emptyMap(), emptyMap())
            val findings = (producer.evaluation as? DraftRuleEvaluation.Evaluated)?.findings
                ?: return Unknowns(emptyMap(), emptyMap())
            if (findings.size != this.observations.size || findings.indices.any { index ->
                val row = this.observations[index]
                row.ruleId != findings[index].ruleId || (when (side) {
                    DraftAlternativeSide.CURRENT -> row.current
                    DraftAlternativeSide.CANDIDATE -> row.candidate
                }) !== findings[index]
            }) return Unknowns(emptyMap(), emptyMap())
            return Unknowns(producer.phasesFor(observationRequest, side, findings),
                producer.observationsFor(observationRequest, side, findings))
        }
        private val currentUnknowns = capture(DraftAlternativeSide.CURRENT, currentTrace)
        private val candidateUnknowns = capture(DraftAlternativeSide.CANDIDATE, candidateTrace)

        fun unknownPhase(side: DraftAlternativeSide, row: DraftAlternativeRuleObservation): DraftCurrentUnknownPhase? {
            if (observations.none { it === row }) return null
            val finding = when (side) {
                DraftAlternativeSide.CURRENT -> row.current
                DraftAlternativeSide.CANDIDATE -> row.candidate
            }
            if (finding.outcome != "unknown") return null
            return when (side) {
                DraftAlternativeSide.CURRENT -> currentUnknowns
                DraftAlternativeSide.CANDIDATE -> candidateUnknowns
            }.phases[row.ruleId]
        }
        fun unknownInputObservations(side: DraftAlternativeSide, row: DraftAlternativeRuleObservation): List<UnknownInputObservation> =
            if (unknownPhase(side, row) == null) emptyList() else when (side) {
                DraftAlternativeSide.CURRENT -> currentUnknowns
                DraftAlternativeSide.CANDIDATE -> candidateUnknowns
            }.inputs[row.ruleId].orEmpty()
    }
    class Unavailable internal constructor(request: DraftAlternativeInspectionRequest,
        val reason: CompatibleOptionsUnavailable) : DraftAlternativeOutcome(request)
}

/**
 * At most two calls to the unchanged bounded evaluator. It reads no storage and produces no shared
 * Build, EvaluationReport, cost or readiness result. Any rejection/cancellation discards every row.
 */
class DraftAlternativeInspector(maxWorkPerEvaluation: Int = 250_000) {
    private val evaluator = FrozenDiagnosticEvaluator(maxWorkPerEvaluation)

    fun inspect(request: DraftAlternativeInspectionRequest, cancelled: () -> Boolean = { false }): DraftAlternativeOutcome {
        fun unavailable(reason: CompatibleOptionsUnavailable) = DraftAlternativeOutcome.Unavailable(request, reason)
        fun retired() = Thread.currentThread().isInterrupted || cancelled()
        fun run(side: DraftAlternativeSide) = evaluator.evaluateAlternativeDraft(request, side)
        if (retired()) return unavailable(CompatibleOptionsUnavailable.CANCELLED)
        val currentTrace = run(DraftAlternativeSide.CURRENT)
        val current = currentTrace.evaluation
        if (current is DraftRuleEvaluation.Rejected)
            return unavailable(CompatibleOptionsUnavailable.valueOf(current.reason.name))
        if (retired()) return unavailable(CompatibleOptionsUnavailable.CANCELLED)
        val candidateTrace = run(DraftAlternativeSide.CANDIDATE)
        val candidate = candidateTrace.evaluation
        if (candidate is DraftRuleEvaluation.Rejected)
            return unavailable(CompatibleOptionsUnavailable.valueOf(candidate.reason.name))
        if (retired()) return unavailable(CompatibleOptionsUnavailable.CANCELLED)
        val before = (current as DraftRuleEvaluation.Evaluated).findings
        val after = (candidate as DraftRuleEvaluation.Evaluated).findings
        val empty = request.baselineFindings
        val ids = empty.map { it.ruleId }
        val idSet = ids.toSet()
        if (ids.size != idSet.size || before.map { it.ruleId } != ids || after.map { it.ruleId } != ids ||
            (before + after + empty).any { it.outcome !in OUTCOMES })
            return unavailable(CompatibleOptionsUnavailable.INVALID_OUTPUT)
        val reasons = request.reasons.associateBy { it.finding.ruleId }
        if (reasons.size != request.reasons.size || reasons.keys.any { it !in idSet })
            return unavailable(CompatibleOptionsUnavailable.INVALID_OUTPUT)
        val rows = ids.indices.map { index ->
            val reason = reasons[ids[index]]
            if (reason != null && (reason.baselineOutcome != empty[index].outcome || !sameFinding(reason.finding, after[index])))
                return unavailable(CompatibleOptionsUnavailable.INVALID_OUTPUT)
            DraftAlternativeRuleObservation(ids[index], before[index], after[index], empty[index], reason)
        }
        if (retired()) return unavailable(CompatibleOptionsUnavailable.CANCELLED)
        return DraftAlternativeOutcome.Completed(request, rows, currentTrace, candidateTrace)
    }

    companion object { private val OUTCOMES = setOf("pass", "fail", "unknown", "skipped") }
}

private fun sameFinding(a: DraftRuleFinding, b: DraftRuleFinding): Boolean =
    a.ruleId == b.ruleId && a.outcome == b.outcome && a.severity == b.severity && a.code == b.code &&
        a.message == b.message && a.remediation == b.remediation && a.selectionIds == b.selectionIds
private fun copyFinding(f: DraftRuleFinding): DraftRuleFinding = DraftRuleFinding(
    f.ruleId, f.outcome, f.severity, f.code, f.message, f.remediation, alternativeList(f.selectionIds))
private fun <T> alternativeList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
