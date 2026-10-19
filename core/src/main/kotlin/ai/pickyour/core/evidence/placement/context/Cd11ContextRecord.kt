package ai.pickyour.core.evidence.placement.context

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * A retained CD11-0.1 binding and its original CD1-0.1 answer bytes.
 *
 * Typed admission supplies no token count, route selection, source authentication or physical evidence.
 * This reader is opt-in and is not registered with frozen or general answer-profile readers.
 */
class Cd11ContextRecord private constructor(
    private val retainedBinding: ByteArray,
    private val retainedAnswers: ByteArray,
    val scope: Cd11Scope,
    answers: Map<Cd11ContextField, Cd11AnswerObservation>,
) {
    val bindingSha256: String = sha256(retainedBinding)
    val answersSha256: String = sha256(retainedAnswers)
    val answers: Map<Cd11ContextField, Cd11AnswerObservation> = immutableMap(answers)

    fun originalBindingBytes(): ByteArray = retainedBinding.copyOf()
    fun originalAnswerBytes(): ByteArray = retainedAnswers.copyOf()

    /**
     * The typed selected scope is validated on construction, before an applicability shortcut.
     * An ineligible or mismatched record contributes no arithmetic failure from its own values.
     */
    fun evaluate(
        selectedScope: Cd11Scope?,
        enabled: Boolean?,
        evidenceEligible: Boolean?,
    ): Cd11ContextResult {
        fun unresolved(reason: Cd11ContextReason, outcome: ContextOutcome = ContextOutcome.UNKNOWN) =
            Cd11ContextResult(outcome, false, reason, emptyList(), answers)
        if (enabled == false) return unresolved(Cd11ContextReason.SELECTION_NOT_ENABLED, ContextOutcome.SKIPPED)
        if (enabled == null) return unresolved(Cd11ContextReason.SELECTION_ACTIVATION_UNKNOWN)
        if (selectedScope == null) return unresolved(Cd11ContextReason.SELECTION_SCOPE_MISSING)
        if (selectedScope != scope) return unresolved(Cd11ContextReason.ROUTE_SCOPE_MISMATCH)
        if (evidenceEligible != true) return unresolved(
            if (evidenceEligible == null) Cd11ContextReason.SOURCE_EVIDENCE_UNRESOLVED
            else Cd11ContextReason.SOURCE_EVIDENCE_INELIGIBLE,
        )
        fun count(field: Cd11ContextField) = (answers.getValue(field).value as? Cd11ContextValue.Count)?.value
        val budget = ContextBudget(
            semantics = (answers.getValue(Cd11ContextField.SEMANTICS).value as? Cd11ContextValue.Semantics)?.value
                ?: ContextSemantics.UNKNOWN,
            inputTokens = (answers.getValue(Cd11ContextField.INPUT_TOKENS).value as? Cd11ContextValue.Range)?.value,
            reservedOutput = count(Cd11ContextField.RESERVED_OUTPUT_TOKENS),
            outputCap = count(Cd11ContextField.OUTPUT_CAP_TOKENS),
            sharedCap = count(Cd11ContextField.SHARED_CAP_TOKENS),
            inputCap = count(Cd11ContextField.INPUT_CAP_TOKENS),
            policyTotalCap = count(Cd11ContextField.POLICY_TOTAL_CAP_TOKENS),
        )
        val checks = ContextBudgetMath.evaluate(budget).checks.toMutableList()
        for ((field, name) in listOf(
            Cd11ContextField.INPUT_CAP_TOKENS to ContextCheckName.INPUT,
            Cd11ContextField.POLICY_TOTAL_CAP_TOKENS to ContextCheckName.POLICY_TOTAL,
        )) {
            val answer = answers.getValue(field)
            if (!answer.provided && answer.state != Cd11AnswerState.NOT_APPLICABLE && checks.none { it.name == name }) {
                checks.add(ContextCheck(name, ContextOutcome.UNKNOWN, ContextOutcome.UNKNOWN))
            }
        }
        val result = ContextBudgetMath.summarize(checks)
        return Cd11ContextResult(
            result.outcome, result.mayNotFit, Cd11ContextReason.NORMALIZED_CONTEXT_SCREEN, result.checks, answers,
        )
    }

    override fun toString() = "Cd11ContextRecord(profile=cd11-0.1)"

    companion object {
        const val PROFILE = "cd11-0.1"
        const val ANSWER_PROFILE = "cd1-0.1"

        /** Per original input, a native consumer budget; this is not a research wire maximum. */
        const val MAX_INPUT_BYTES = 65_536
        private val limits = JsonInputLimits(maxBytes = MAX_INPUT_BYTES)

        fun read(bindingBytes: ByteArray, answerBytes: ByteArray): Cd11ContextAdmission {
            if (bindingBytes.size > MAX_INPUT_BYTES || answerBytes.size > MAX_INPUT_BYTES) {
                return Cd11ContextAdmission.Rejected(Cd11ContextRejection.NATIVE_INPUT_LIMIT)
            }
            // Snapshot before either parsing or hashing. No caller-owned mutable JSON is retained.
            val binding = bindingBytes.copyOf()
            val answers = answerBytes.copyOf()
            return try {
                val root = parse(binding, Cd11ContextRejection.BINDING_JSON)
                exactKeys(root, setOf("x_t0_context_budget"), Cd11ContextRejection.BINDING_SHAPE)
                val body = exactKeys(
                    root["x_t0_context_budget"], setOf("proposal_version", "scope", "answer_states_sha256"),
                    Cd11ContextRejection.BINDING_SHAPE,
                )
                admit(body["proposal_version"]?.isTextual == true &&
                    body["proposal_version"].textValue() == PROFILE, Cd11ContextRejection.BINDING_SHAPE)
                val scope = readScope(body["scope"])
                val expected = string(body["answer_states_sha256"], Cd11ContextRejection.BINDING_SHAPE)
                admit(validCd11Digest(expected), Cd11ContextRejection.BINDING_SHAPE)
                admit(sha256(answers) == expected, Cd11ContextRejection.ANSWER_HASH_MISMATCH)
                val index = OriginalContextAnswers(parse(answers, Cd11ContextRejection.ANSWER_JSON))
                val observations = Cd11ContextField.entries.associateWith { index.read(it) }
                val semantics = observations.getValue(Cd11ContextField.SEMANTICS).value as? Cd11ContextValue.Semantics
                admit(
                    semantics?.value != ContextSemantics.SEPARATE ||
                        !observations.getValue(Cd11ContextField.SHARED_CAP_TOKENS).provided,
                    Cd11ContextRejection.CONTEXT_VALUE,
                )
                Cd11ContextAdmission.Accepted(Cd11ContextRecord(binding, answers, scope, observations))
            } catch (failure: AdmissionFailure) {
                Cd11ContextAdmission.Rejected(failure.reason)
            }
        }

        private fun parse(raw: ByteArray, reason: Cd11ContextRejection): ObjectNode =
            when (val parsed = BoundedJsonReader(limits).read(raw.inputStream())) {
                is JsonInputResult.Parsed -> parsed.value
                is JsonInputResult.Rejected -> reject(
                    if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) Cd11ContextRejection.NATIVE_INPUT_LIMIT else reason,
                )
            }

        private fun readScope(node: JsonNode?): Cd11Scope {
            val reason = Cd11ContextRejection.BINDING_SHAPE
            val scope = exactKeys(
                node, setOf("selection_id", "model", "route", "operation", "tokenizer", "settings_sha256"), reason,
            )
            fun reference(node: JsonNode?): Cd11Reference {
                val value = exactKeys(node, setOf("id", "version"), reason)
                val id = string(value["id"], reason)
                val version = string(value["version"], reason)
                admit(validCd11Id(id) && validCd11Version(version), reason)
                return Cd11Reference(id, version)
            }
            val selection = string(scope["selection_id"], reason)
            val operation = string(scope["operation"], reason)
            val settings = string(scope["settings_sha256"], reason)
            admit(validCd11Id(selection) && validCd11Id(operation) && validCd11Digest(settings), reason)
            return Cd11Scope(
                selection, reference(scope["model"]), reference(scope["route"]), operation,
                reference(scope["tokenizer"]), settings,
            )
        }
    }
}

/** Fixed-context adapter for the old envelope; intentionally no public generic CD1 index or rule API. */
private class OriginalContextAnswers(root: ObjectNode) {
    private class Row(
        val path: String,
        val tokens: List<String>,
        val state: Cd11AnswerState,
        val value: JsonNode?,
        val source: String?,
    )
    private val rows: Map<List<String>, Row>

    init {
        val reason = Cd11ContextRejection.ANSWER_ENCODING
        exactKeys(root, setOf("x_t0_answer_states"), reason)
        val body = exactKeys(root["x_t0_answer_states"], setOf("proposal_version", "answers"), reason)
        admit(body["proposal_version"]?.isTextual == true &&
            body["proposal_version"].textValue() == Cd11ContextRecord.ANSWER_PROFILE, reason)
        val values = body["answers"] as? ObjectNode ?: reject(reason)
        // The canonical old envelope validates unused records and numeric tokens too.
        requireOriginalNumbers(root)
        val collected = LinkedHashMap<List<String>, Row>()
        for ((path, raw) in values.fields().asSequence()) {
            val tokens = pointer(path)
            val row = raw as? ObjectNode ?: reject(reason)
            val stateText = string(row["state"], reason)
            val state = Cd11AnswerState.entries.firstOrNull { it.wireValue == stateText } ?: reject(reason)
            val provided = state == Cd11AnswerState.PROVIDED
            val fields = if (provided) {
                setOf("state", "value") + if (row.has("source")) setOf("source") else emptySet()
            } else setOf("state")
            exactKeys(row, fields, reason)
            val source = if (provided && row.has("source")) string(row["source"], reason).also {
                admit(it.isNotEmpty() && hasNonWhitespace(it), reason)
            } else null
            admit(tokens !in collected, reason)
            collected[tokens] = Row(path, tokens, state, if (provided) row["value"] else null, source)
        }
        for (tokens in collected.keys) {
            admit((1 until tokens.size).none { tokens.take(it) in collected }, reason)
        }
        rows = collected
    }

    fun read(field: Cd11ContextField): Cd11AnswerObservation {
        val tokens = listOf("context", field.fieldName)
        val match = (tokens.size downTo 1).firstNotNullOfOrNull { length ->
            rows[tokens.take(length)]?.let { length to it }
        }
        if (match == null) {
            val withheld = rows.values.filter {
                it.state == Cd11AnswerState.WITHHELD && it.tokens.size > tokens.size &&
                    it.tokens.take(tokens.size) == tokens
            }.minWithOrNull { first, second -> compareScalarText(first.path, second.path) }
            return if (withheld == null) observation(
                field, Cd11AnswerState.MISSING, null, null, null, Cd11AnswerOrigin.ABSENT,
            ) else observation(
                field, Cd11AnswerState.WITHHELD, null, null, withheld.path, Cd11AnswerOrigin.WITHHELD_DESCENDANT,
            )
        }
        val (length, row) = match
        if (row.state != Cd11AnswerState.PROVIDED) return observation(
            field, row.state, null, null, row.path,
            if (length == tokens.size) Cd11AnswerOrigin.EXACT else Cd11AnswerOrigin.UNRESOLVED_ANCESTOR,
        )
        var value = row.value ?: reject(Cd11ContextRejection.ANSWER_ENCODING)
        for (token in tokens.drop(length)) {
            value = when {
                value.isObject -> value[token] ?: return observation(
                    field, Cd11AnswerState.MISSING, null, null, row.path, Cd11AnswerOrigin.ANCESTOR_MEMBER_MISSING,
                )
                value.isArray -> {
                    admit(Regex("0|[1-9][0-9]*").matches(token), Cd11ContextRejection.CONTEXT_VALUE)
                    val index = token.toBigInteger()
                    if (index >= BigInteger.valueOf(value.size().toLong())) return observation(
                        field, Cd11AnswerState.MISSING, null, null, row.path, Cd11AnswerOrigin.ANCESTOR_MEMBER_MISSING,
                    )
                    value[index.toInt()]
                }
                else -> reject(Cd11ContextRejection.CONTEXT_VALUE)
            }
        }
        return observation(
            field, Cd11AnswerState.PROVIDED, typedValue(field, value), row.source, row.path,
            if (length == tokens.size) Cd11AnswerOrigin.EXACT else Cd11AnswerOrigin.PROVIDED_ANCESTOR,
        )
    }

    private fun observation(
        field: Cd11ContextField,
        state: Cd11AnswerState,
        value: Cd11ContextValue?,
        source: String?,
        path: String?,
        origin: Cd11AnswerOrigin,
    ) = Cd11AnswerObservation(field, state, value, source, path, origin)

    private fun typedValue(field: Cd11ContextField, value: JsonNode): Cd11ContextValue {
        val reason = Cd11ContextRejection.CONTEXT_VALUE
        fun count(node: JsonNode?, positive: Boolean = false): BigInteger {
            admit(node?.isIntegralNumber == true, reason)
            val number = node!!.bigIntegerValue()
            admit(if (positive) number.signum() > 0 else number.signum() >= 0, reason)
            return number
        }
        return when (field) {
            Cd11ContextField.SEMANTICS -> Cd11ContextValue.Semantics(when (string(value, reason)) {
                "shared" -> ContextSemantics.SHARED
                "separate" -> ContextSemantics.SEPARATE
                else -> reject(reason)
            })
            Cd11ContextField.INPUT_TOKENS -> {
                val interval = exactKeys(value, setOf("lower", "upper"), reason)
                val lower = count(interval["lower"], true)
                val upper = count(interval["upper"], true)
                admit(lower <= upper, reason)
                Cd11ContextValue.Range(ContextTokenRange(lower, upper))
            }
            else -> Cd11ContextValue.Count(count(
                value, field == Cd11ContextField.SHARED_CAP_TOKENS || field == Cd11ContextField.INPUT_CAP_TOKENS,
            ))
        }
    }
}

private fun exactKeys(node: JsonNode?, expected: Set<String>, reason: Cd11ContextRejection): ObjectNode {
    val value = node as? ObjectNode ?: reject(reason)
    admit(value.fieldNames().asSequence().toSet() == expected, reason)
    return value
}

private fun string(node: JsonNode?, reason: Cd11ContextRejection): String {
    admit(node?.isTextual == true, reason)
    return node!!.textValue()
}

private fun pointer(value: String): List<String> {
    admit(Regex("(?:/(?:[^~/]|~[01])*)+").matches(value), Cd11ContextRejection.ANSWER_ENCODING)
    return value.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
}

private fun compareScalarText(first: String, second: String): Int {
    var a = 0
    var b = 0
    while (a < first.length && b < second.length) {
        val left = first.codePointAt(a)
        val right = second.codePointAt(b)
        if (left != right) return left.compareTo(right)
        a += Character.charCount(left)
        b += Character.charCount(right)
    }
    return (first.length - a).compareTo(second.length - b)
}

/**
 * The pinned CD1-0.1 decoder admits a float only when its shortest binary64 readback retains
 * the original decimal value, with an additional exact-integral check. Context counts still
 * require original JSON integer tokens. Unused provided values retain the original bytes.
 */
private fun requireOriginalNumbers(root: JsonNode) {
    val pending = ArrayDeque<JsonNode>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.isContainerNode) node.elements().forEachRemaining { pending.add(it) }
        if (!node.isFloatingPointNumber) continue
        val original = node.decimalValue()
        val number = original.toDouble()
        admit(number.isFinite(), Cd11ContextRejection.UNSUPPORTED_NUMBER)
        admit(original.compareTo(shortestDecimal(number)) == 0, Cd11ContextRejection.UNSUPPORTED_NUMBER)
        if (number % 1.0 == 0.0) {
            admit(original.compareTo(BigDecimal(number)) == 0, Cd11ContextRejection.UNSUPPORTED_NUMBER)
        }
    }
}

private fun shortestDecimal(number: Double): BigDecimal {
    if (number == 0.0) return BigDecimal.ZERO
    val exact = BigDecimal(number)
    for (precision in 1..17) {
        val rounded = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        val step = BigDecimal.ONE.scaleByPowerOfTen(rounded.precision() - rounded.scale() - precision)
        val candidates = listOf(rounded, rounded.subtract(step), rounded.add(step))
            .filter { it.toDouble().toRawBits() == number.toRawBits() }
        if (candidates.isNotEmpty()) return candidates.minWithOrNull { first, second ->
            val distance = first.subtract(exact).abs().compareTo(second.subtract(exact).abs())
            if (distance != 0) distance else first.stripTrailingZeros().unscaledValue().abs().testBit(0)
                .compareTo(second.stripTrailingZeros().unscaledValue().abs().testBit(0))
        }!!
    }
    reject(Cd11ContextRejection.UNSUPPORTED_NUMBER)
}

private fun sha256(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            val value = byte.toInt() and 255
            append(alphabet[value ushr 4])
            append(alphabet[value and 15])
        }
    }
}

private class AdmissionFailure(val reason: Cd11ContextRejection) : RuntimeException(null, null, false, false)
private fun reject(reason: Cd11ContextRejection): Nothing = throw AdmissionFailure(reason)
private fun admit(condition: Boolean, reason: Cd11ContextRejection) { if (!condition) reject(reason) }
