package ai.pickyour.core.evidence.placement.context

import java.math.BigInteger
import java.util.Collections

/** This package is an opt-in Android consumer of the original CD11-0.1 research profile. */
enum class ContextOutcome(val wireValue: String) {
    PASS("pass"), FAIL("fail"), UNKNOWN("unknown"), SKIPPED("skipped"),
}

enum class ContextSemantics(val wireValue: String) {
    SHARED("shared"), SEPARATE("separate"), UNKNOWN("unknown"),
}

enum class ContextCheckName(val wireValue: String) {
    OUTPUT("output"), COMBINED("combined"), INPUT("input"), POLICY_TOTAL("policy_total"),
}

data class ContextCheck(val name: ContextCheckName, val lower: ContextOutcome, val upper: ContextOutcome)

/** Positive input counts, without machine-width narrowing or an inferred point estimate. */
data class ContextTokenRange(val lower: BigInteger, val upper: BigInteger) {
    init { require(lower.signum() > 0 && lower <= upper) { "Invalid normalized context range" } }
    override fun toString() = "ContextTokenRange"
}

/** Already normalized facts. A nullable operand is unresolved, never an implicit zero. */
data class ContextBudget(
    val semantics: ContextSemantics,
    val inputTokens: ContextTokenRange?,
    val reservedOutput: BigInteger?,
    val outputCap: BigInteger?,
    val sharedCap: BigInteger? = null,
    val inputCap: BigInteger? = null,
    val policyTotalCap: BigInteger? = null,
    val enabled: Boolean = true,
    val tupleMatches: Boolean = true,
) {
    init {
        require(listOf(reservedOutput, outputCap, policyTotalCap).all { it == null || it.signum() >= 0 }) {
            "Invalid normalized context count"
        }
        require(listOf(sharedCap, inputCap).all { it == null || it.signum() > 0 }) {
            "Invalid normalized context capacity"
        }
    }
    override fun toString() = "ContextBudget"
}

class ContextMathResult internal constructor(
    val outcome: ContextOutcome,
    val mayNotFit: Boolean,
    checks: List<ContextCheck>,
) {
    val checks: List<ContextCheck> = immutableList(checks)
    override fun toString() = "ContextMathResult(outcome=" + outcome + ", mayNotFit=" + mayNotFit + ")"
}

/** Exact labels are retained; equality is scope matching, not source authentication. */
data class Cd11Reference(val id: String, val version: String) {
    init {
        require(validCd11Id(id) && validCd11Version(version)) { "Invalid context reference" }
    }
    override fun toString() = "Cd11Reference"
}

data class Cd11Scope(
    val selectionId: String,
    val model: Cd11Reference,
    val route: Cd11Reference,
    val operation: String,
    val tokenizer: Cd11Reference,
    val settingsSha256: String,
) {
    init {
        require(validCd11Id(selectionId) && validCd11Id(operation) && validCd11Digest(settingsSha256)) {
            "Invalid context scope"
        }
    }
    override fun toString() = "Cd11Scope"
}

enum class Cd11AnswerState(val wireValue: String) {
    PROVIDED("provided"), UNKNOWN("unknown"), MISSING("missing"),
    WITHHELD("withheld"), NOT_APPLICABLE("not_applicable"),
}

enum class Cd11ContextField(val fieldName: String) {
    SEMANTICS("semantics"), INPUT_TOKENS("input_tokens"),
    RESERVED_OUTPUT_TOKENS("reserved_output_tokens"), OUTPUT_CAP_TOKENS("output_cap_tokens"),
    SHARED_CAP_TOKENS("shared_cap_tokens"), INPUT_CAP_TOKENS("input_cap_tokens"),
    POLICY_TOTAL_CAP_TOKENS("policy_total_cap_tokens");

    val requestedPath: String get() = "/context/" + fieldName
}

/** Additional causal labels describe this adapter's lookup; they do not rewrite CD1 metadata. */
enum class Cd11AnswerOrigin {
    EXACT, PROVIDED_ANCESTOR, UNRESOLVED_ANCESTOR,
    WITHHELD_DESCENDANT, ABSENT, ANCESTOR_MEMBER_MISSING,
}

sealed interface Cd11ContextValue {
    data class Semantics(val value: ContextSemantics) : Cd11ContextValue {
        init { require(value != ContextSemantics.UNKNOWN) { "Invalid provided context semantics" } }
        override fun toString() = "Cd11ContextValue.Semantics"
    }
    data class Range(val value: ContextTokenRange) : Cd11ContextValue {
        override fun toString() = "Cd11ContextValue.Range"
    }
    data class Count(val value: BigInteger) : Cd11ContextValue {
        init { require(value.signum() >= 0) { "Invalid context count" } }
        override fun toString() = "Cd11ContextValue.Count"
    }
}

/** Provided metadata exists only for a successfully typed provided value. */
class Cd11AnswerObservation internal constructor(
    val field: Cd11ContextField,
    val state: Cd11AnswerState,
    val value: Cd11ContextValue?,
    val source: String?,
    val sourcePath: String?,
    val origin: Cd11AnswerOrigin,
) {
    val requestedPath: String get() = this.field.requestedPath
    val provided: Boolean get() = state == Cd11AnswerState.PROVIDED
    init {
        require(provided == (value != null) && (provided || source == null)) {
            "Invalid context answer observation"
        }
    }
    override fun toString() = "Cd11AnswerObservation(field=" + field + ", state=" + state + ")"
}

enum class Cd11ContextReason(val wireValue: String) {
    SELECTION_NOT_ENABLED("selection_not_enabled"),
    SELECTION_ACTIVATION_UNKNOWN("selection_activation_unknown"),
    SELECTION_SCOPE_MISSING("selection_scope_missing"),
    ROUTE_SCOPE_MISMATCH("route_scope_mismatch"),
    SOURCE_EVIDENCE_UNRESOLVED("source_evidence_unresolved"),
    SOURCE_EVIDENCE_INELIGIBLE("source_evidence_ineligible"),
    NORMALIZED_CONTEXT_SCREEN("normalized_context_screen"),
}

class Cd11ContextResult internal constructor(
    val outcome: ContextOutcome,
    val mayNotFit: Boolean,
    val reason: Cd11ContextReason,
    checks: List<ContextCheck>,
    answers: Map<Cd11ContextField, Cd11AnswerObservation>,
) {
    val checks: List<ContextCheck> = immutableList(checks)
    val answers: Map<Cd11ContextField, Cd11AnswerObservation> = immutableMap(answers)
    override fun toString() = "Cd11ContextResult(outcome=" + outcome + ", reason=" + reason + ")"
}

enum class Cd11ContextRejection {
    NATIVE_INPUT_LIMIT, BINDING_JSON, BINDING_SHAPE, ANSWER_HASH_MISMATCH,
    ANSWER_JSON, ANSWER_ENCODING, UNSUPPORTED_NUMBER, CONTEXT_VALUE,
}

sealed interface Cd11ContextAdmission {
    class Accepted internal constructor(val record: Cd11ContextRecord) : Cd11ContextAdmission {
        override fun toString() = "Cd11ContextAdmission.Accepted"
    }
    data class Rejected(val reason: Cd11ContextRejection) : Cd11ContextAdmission
}

internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

internal fun validCd11Id(value: String): Boolean = Regex("[a-z0-9][a-z0-9._:-]{2,119}").matches(value)
internal fun validCd11Digest(value: String): Boolean = Regex("[a-f0-9]{64}").matches(value)

internal fun validCd11Version(value: String): Boolean =
    scalarText(value) && value.codePointCount(0, value.length) in 1..200 &&
        hasNonWhitespace(value) && value.none { it <= '\u001f' || it == '\u007f' }

/** Python 3.12's Unicode whitespace set, without trimming or normalizing the original string. */
internal fun hasNonWhitespace(value: String): Boolean = value.any {
    !(it in '\u0009'..'\u000d' || it in '\u001c'..'\u0020' || it == '\u0085' ||
        it == '\u00a0' || it == '\u1680' || it in '\u2000'..'\u200a' || it == '\u2028' ||
        it == '\u2029' || it == '\u202f' || it == '\u205f' || it == '\u3000')
}

private fun scalarText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (Character.isHighSurrogate(character)) {
            if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
        } else if (Character.isLowSurrogate(character)) return false
    }
    return true
}
