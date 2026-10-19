package ai.pickyour.core.evidence.answers

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest

/** Explicit opt-in research profile; this is not a frozen-v1 Build or report reader. */
const val CD1_ANSWER_PROFILE = "cd1-0.2"

enum class Cd1State(val encoded: String) {
    PROVIDED("provided"), UNKNOWN("unknown"), MISSING("missing"),
    WITHHELD("withheld"), NOT_APPLICABLE("not_applicable"),
}

enum class Cd1Problem {
    INVALID_INPUT, UNSUPPORTED_PROFILE, INVALID_RECORD, INVALID_POINTER,
    OVERLAPPING_ANSWERS, UNSUPPORTED_NUMBER, INVALID_FIELD_SCHEMA, VALUE_SCHEMA_MISMATCH,
}

/** Sanitized rejection: no answer value, schema, source fragment or library exception escapes. */
class Cd1Rejected(val problem: Cd1Problem, val inputReason: JsonRejection? = null) :
    IllegalArgumentException(problem.name)

internal fun cd1Require(condition: Boolean, problem: Cd1Problem) {
    if (!condition) throw Cd1Rejected(problem)
}

/** The value is detached on every read. JSON null is distinct from an absent value. */
class Cd1Answer internal constructor(
    val state: Cd1State,
    private val supplied: JsonNode? = null,
    val sourcePath: String? = null,
    val presetSource: String? = null,
    val assumption: Boolean? = null,
) {
    val value: JsonNode? get() = supplied?.deepCopy()
    val provided: Boolean get() = state == Cd1State.PROVIDED

    init {
        cd1Require((provided && supplied != null && assumption != null) ||
            (!provided && supplied == null && presetSource == null && assumption == null), Cd1Problem.INVALID_RECORD)
    }

    override fun toString(): String = "Cd1Answer(${state.encoded})"
}

/**
 * Reads one original, explicitly versioned answer index. It does not read an old
 * profile as 0.2, rewrite a domain document, choose selections or qualify evidence.
 * Existing bounded-JSON limits remain local client budgets, not new wire limits.
 */
class Cd1AnswerIndex private constructor(private val original: ByteArray, private val records: Map<List<String>, Record>) {
    private data class Record(val path: String, val state: Cd1State, val value: JsonNode?,
                              val source: String?, val assumption: Boolean?)

    val originalBytes: ByteArray get() = original.copyOf()
    val originalSHA256: String = MessageDigest.getInstance("SHA-256").digest(original)
        .joinToString("") { "%02x".format(it) }

    private val withheldParents: Map<List<String>, String> = buildMap {
        // Python's canonical witness ordering is by Unicode code point, not UTF-16 unit.
        records.entries.sortedWith { a, b -> compareCodePoints(a.value.path, b.value.path) }
            .filter { it.value.state == Cd1State.WITHHELD }.forEach { (tokens, record) ->
                for (size in 1 until tokens.size) putIfAbsent(tokens.take(size), record.path)
            }
    }

    fun read(pointer: String, fieldSchema: KnownAnswerSchema): Cd1Answer {
        val tokens = pointerTokens(pointer)
        var matched: Record? = null
        var used = 0
        for (size in tokens.size downTo 1) {
            records[tokens.take(size)]?.let { matched = it; used = size }
            if (matched != null) break
        }
        val record = matched ?: return withheldParents[tokens]?.let {
            Cd1Answer(Cd1State.WITHHELD, sourcePath = it)
        } ?: Cd1Answer(Cd1State.MISSING)
        if (record.state != Cd1State.PROVIDED) return Cd1Answer(record.state, sourcePath = record.path)
        var value = checkNotNull(record.value)
        for (token in tokens.drop(used)) {
            value = when {
                value.isObject -> value.get(token) ?: return Cd1Answer(Cd1State.MISSING, sourcePath = record.path)
                value.isArray -> {
                    cd1Require(ARRAY_INDEX.matches(token), Cd1Problem.INVALID_POINTER)
                    // A syntactically valid large index is missing, never wrapped or clamped.
                    val index = token.toIntOrNull()
                    if (index == null || index >= value.size()) return Cd1Answer(Cd1State.MISSING, sourcePath = record.path)
                    value[index]
                }
                else -> throw Cd1Rejected(Cd1Problem.INVALID_POINTER)
            }
        }
        fieldSchema.requireValid(value)
        return Cd1Answer(record.state, value.deepCopy(), record.path, record.source, record.assumption)
    }

    /** Predicate callbacks receive only provided, field-admitted, detached data. */
    fun predicate(pointer: String, fieldSchema: KnownAnswerSchema, predicate: (JsonNode) -> Boolean?): Cd1Predicate {
        val answer = read(pointer, fieldSchema)
        return if (answer.provided) Cd1Predicate.provided(
            predicate(checkNotNull(answer.value)), checkNotNull(answer.assumption), answer.sourcePath, answer.presetSource,
        ) else Cd1Predicate.unresolved(answer.state, answer.sourcePath)
    }

    override fun toString(): String = "Cd1AnswerIndex($CD1_ANSWER_PROFILE)"

    companion object {
        private val ARRAY_INDEX = Regex("0|[1-9][0-9]*")
        private val PRESET = Regex("preset:[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun read(originalBytes: ByteArray): Cd1AnswerIndex {
            val limits = JsonInputLimits()
            cd1Require(originalBytes.size <= limits.maxBytes, Cd1Problem.INVALID_INPUT)
            val original = originalBytes.copyOf()
            val parsed = BoundedJsonReader(limits).read(original.inputStream())
            if (parsed is JsonInputResult.Rejected) throw Cd1Rejected(Cd1Problem.INVALID_INPUT, parsed.reason)
            val document = (parsed as JsonInputResult.Parsed).value
            cd1Require(document.fieldNames().asSequence().toSet() == setOf("x_t0_answer_states"), Cd1Problem.INVALID_RECORD)
            val index = document["x_t0_answer_states"]
            cd1Require(index.isObject && index.fieldNames().asSequence().toSet() == setOf("proposal_version", "answers"),
                Cd1Problem.INVALID_RECORD)
            cd1Require(index["proposal_version"].isTextual && index["proposal_version"].textValue() == CD1_ANSWER_PROFILE,
                Cd1Problem.UNSUPPORTED_PROFILE)
            val answers = index["answers"]
            cd1Require(answers.isObject, Cd1Problem.INVALID_RECORD)
            // Apply the accepted profile to every original numeric token, including unread values.
            requireCd1Numbers(document)
            val records = linkedMapOf<List<String>, Record>()
            val runtimeAnswers = cd1RuntimeTree(original)["x_t0_answer_states"]["answers"]
            runtimeAnswers.properties().forEach { (path, node) ->
                val tokens = pointerTokens(path)
                cd1Require(node.isObject && node.path("state").isTextual, Cd1Problem.INVALID_RECORD)
                val state = Cd1State.entries.singleOrNull { it.encoded == node["state"].textValue() }
                    ?: throw Cd1Rejected(Cd1Problem.INVALID_RECORD)
                val fields = node.fieldNames().asSequence().toSet()
                val record = if (state == Cd1State.PROVIDED) {
                    cd1Require(fields == setOf("state", "value", "assumption") ||
                        fields == setOf("state", "value", "assumption", "source"), Cd1Problem.INVALID_RECORD)
                    cd1Require(node["assumption"].isBoolean, Cd1Problem.INVALID_RECORD)
                    val source = node.get("source")?.let {
                        cd1Require(it.isTextual && PRESET.matches(it.textValue()), Cd1Problem.INVALID_RECORD)
                        it.textValue()
                    }
                    Record(path, state, node["value"], source, node["assumption"].booleanValue())
                } else {
                    cd1Require(fields == setOf("state"), Cd1Problem.INVALID_RECORD)
                    Record(path, state, null, null, null)
                }
                cd1Require(records.put(tokens, record) == null, Cd1Problem.INVALID_POINTER)
            }
            for (tokens in records.keys) for (length in 1 until tokens.size) {
                cd1Require(!records.containsKey(tokens.take(length)), Cd1Problem.OVERLAPPING_ANSWERS)
            }
            return Cd1AnswerIndex(original, records)
        }

        internal fun validPreset(source: String) = PRESET.matches(source)
    }
}

/** Non-root RFC6901 string form; URI fragments and dotted paths are not answer pointers. */
internal fun pointerTokens(pointer: String): List<String> {
    cd1Require(pointer.length <= 16_384 && pointer.startsWith("/"), Cd1Problem.INVALID_POINTER)
    val parts = pointer.substring(1).split('/')
    cd1Require(parts.size <= 256, Cd1Problem.INVALID_POINTER)
    return parts.map { encoded ->
        val decoded = StringBuilder()
        var offset = 0
        while (offset < encoded.length) {
            val char = encoded[offset++]
            if (char == '~') {
                cd1Require(offset < encoded.length && encoded[offset] in "01", Cd1Problem.INVALID_POINTER)
                decoded.append(if (encoded[offset++] == '0') '~' else '/')
            } else if (Character.isHighSurrogate(char)) {
                cd1Require(offset < encoded.length && Character.isLowSurrogate(encoded[offset]), Cd1Problem.INVALID_POINTER)
                decoded.append(char).append(encoded[offset++])
            } else {
                cd1Require(!Character.isLowSurrogate(char), Cd1Problem.INVALID_POINTER)
                decoded.append(char)
            }
        }
        decoded.toString()
    }
}

private fun compareCodePoints(a: String, b: String): Int {
    var left = 0
    var right = 0
    while (left < a.length && right < b.length) {
        val ca = a.codePointAt(left)
        val cb = b.codePointAt(right)
        if (ca != cb) return ca.compareTo(cb)
        left += Character.charCount(ca)
        right += Character.charCount(cb)
    }
    return (a.length - left).compareTo(b.length - right)
}
