package ai.pickyour.core.evidence.answers

enum class Cd1Policy { COMPATIBILITY, RECOMMENDATION }
enum class Cd1Severity(val encoded: String) { INFO("info"), WARNING("warning"), ERROR("error") }
enum class Cd1Outcome(val encoded: String) { PASS("pass"), FAIL("fail"), UNKNOWN("unknown"), SKIPPED("skipped") }
enum class Cd1Position(val encoded: String) { APPLIES_WHEN("applies_when"), ASSERTION("assertion") }

/** No implicit Boolean conversion exists. Compose negation before terminal lowering. */
class Cd1Predicate private constructor(
    val state: Cd1State,
    val truth: Boolean?,
    val sourcePath: String?,
    val presetSource: String?,
    val assumption: Boolean?,
) {
    fun negated(): Cd1Predicate = Cd1Predicate(state, truth?.not(), sourcePath, presetSource, assumption)

    override fun toString(): String = "Cd1Predicate(${state.encoded})"

    companion object {
        /** Explicit caller-qualified Boolean/unknown fact; attribution does not establish evidence eligibility. */
        fun provided(truth: Boolean?, assumption: Boolean, sourcePath: String? = null,
                     presetSource: String? = null): Cd1Predicate {
            sourcePath?.let { pointerTokens(it) }
            cd1Require(presetSource == null || Cd1AnswerIndex.validPreset(presetSource), Cd1Problem.INVALID_RECORD)
            return Cd1Predicate(Cd1State.PROVIDED, truth, sourcePath, presetSource, assumption)
        }

        fun unresolved(state: Cd1State, sourcePath: String? = null): Cd1Predicate {
            cd1Require(state != Cd1State.PROVIDED, Cd1Problem.INVALID_RECORD)
            sourcePath?.let { pointerTokens(it) }
            return Cd1Predicate(state, null, sourcePath, null, null)
        }
    }
}

/** A normalized rule, not a released Ruleset, selection quantifier or Build validator. */
class Cd1Rule(
    val id: String,
    val severity: Cd1Severity,
    val policy: Cd1Policy,
    val appliesWhen: () -> Cd1Predicate,
    val assertion: () -> Cd1Predicate,
) {
    init { require(id.isNotBlank()) { "Rule identity is required" } }
}

/** A causal terminal finding. Candidate verdict and evidence grade are not derived here. */
data class Cd1Finding(
    val ruleId: String,
    val severity: Cd1Severity,
    val outcome: Cd1Outcome,
    val reason: String,
    val position: Cd1Position,
    val answerState: Cd1State,
    val sourcePath: String?,
    val presetSource: String?,
    val assumption: Boolean?,
)

object Cd1RulePolicy {
    fun evaluate(rule: Cd1Rule): Cd1Finding {
        fun finish(value: Cd1Predicate, position: Cd1Position, outcome: Cd1Outcome, reason: String) =
            Cd1Finding(rule.id, rule.severity, outcome, reason, position, value.state,
                value.sourcePath, value.presetSource, value.assumption)

        val applicable = rule.appliesWhen()
        if (applicable.state == Cd1State.NOT_APPLICABLE) return finish(
            applicable, Cd1Position.APPLIES_WHEN, Cd1Outcome.SKIPPED, "answer_not_applicable")
        if (applicable.state != Cd1State.PROVIDED) return finish(
            applicable, Cd1Position.APPLIES_WHEN,
            if (rule.policy == Cd1Policy.RECOMMENDATION) Cd1Outcome.SKIPPED else Cd1Outcome.UNKNOWN,
            unresolvedReason(applicable.state),
        )
        if (applicable.truth == null) return finish(applicable, Cd1Position.APPLIES_WHEN, Cd1Outcome.UNKNOWN, "predicate_unknown")
        if (!applicable.truth) return finish(applicable, Cd1Position.APPLIES_WHEN, Cd1Outcome.SKIPPED, "applicability_false")

        val assertion = rule.assertion()
        if (assertion.state == Cd1State.NOT_APPLICABLE) return finish(
            assertion, Cd1Position.ASSERTION, Cd1Outcome.SKIPPED, "answer_not_applicable")
        if (assertion.state != Cd1State.PROVIDED) return finish(
            assertion, Cd1Position.ASSERTION, Cd1Outcome.UNKNOWN, unresolvedReason(assertion.state))
        if (assertion.truth == null) return finish(assertion, Cd1Position.ASSERTION, Cd1Outcome.UNKNOWN, "predicate_unknown")
        return finish(assertion, Cd1Position.ASSERTION,
            if (assertion.truth) Cd1Outcome.PASS else Cd1Outcome.FAIL,
            if (assertion.truth) "assertion_true" else "assertion_false")
    }

    private fun unresolvedReason(state: Cd1State): String = when (state) {
        Cd1State.UNKNOWN -> "answer_unknown"
        Cd1State.MISSING -> "answer_missing"
        Cd1State.WITHHELD -> "answer_withheld"
        Cd1State.NOT_APPLICABLE -> "answer_not_applicable"
        Cd1State.PROVIDED -> error("Provided values are evaluated separately")
    }
}
