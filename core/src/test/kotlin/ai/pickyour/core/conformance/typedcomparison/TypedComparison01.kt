package ai.pickyour.core.conformance.typedcomparison

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque

/**
 * Internal fixture consumer of Research's separately selected typed-comparison/0.1.
 *
 * This file belongs to the test source set. It supplies no application evaluator,
 * Answer lowering, evidence qualification, report result or default dispatch.
 */
internal object TypedComparison01 {
    const val PROFILE = "typed-comparison/0.1"

    enum class Outcome(val wire: String) { TRUE("true"), FALSE("false"), UNKNOWN("unknown") }

    enum class Reason(val wire: String) {
        UNSUPPORTED_PROFILE("unsupported_profile"), UNSUPPORTED_OPERATOR("unsupported_operator"),
        INVALID_LIMITS("invalid_limits"), BYTE_LIMIT("byte_limit"), INVALID_UTF8("invalid_utf8"),
        DEPTH_LIMIT("depth_limit"), INVALID_JSON("invalid_json"), DUPLICATE_KEY("duplicate_key"),
        NON_FINITE("non_finite"), INVALID_UNICODE("invalid_unicode"), NODE_LIMIT("node_limit"),
        STRING_LIMIT("string_limit"), NUMBER_LIMIT("number_limit"), WORK_LIMIT("work_limit"),
    }

    sealed interface Result {
        data class Compared(val outcome: Outcome) : Result
        data class Rejected(val reason: Reason) : Result
    }

    data class Limits(
        val maxBytes: Int = 65_536,
        val maxDepth: Int = 32,
        val maxNodes: Int = 10_000,
        val maxStringChars: Int = 4_096,
        val maxNumberChars: Int = 128,
        val maxAbsExponent: Int = 1_000,
        val maxWork: Int = 200_000,
    ) {
        init { validate() }

        internal fun validate() {
            if (maxBytes !in 1..65_536 || maxDepth !in 1..32 || maxNodes !in 1..10_000 ||
                maxStringChars !in 1..4_096 || maxNumberChars !in 1..128 ||
                maxAbsExponent !in 0..1_000 || maxWork !in 1..200_000
            ) reject(Reason.INVALID_LIMITS)
        }
    }

    private class Rejection(val reason: Reason) : IllegalArgumentException(reason.wire)
    private fun reject(reason: Reason): Nothing = throw Rejection(reason)

    private val operators = setOf("eq", "ne", "lt", "lte", "gt", "gte", "in", "contains", "intersects", "subset_of")
    private val decimalString = Regex("(0|[1-9][0-9]*)(\\.[0-9]{1,12})?")
    private val exponent = Regex("[eE]([+-]?[0-9]+)$")
    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private sealed interface Value {
        data object Unresolved : Value
        data object Null : Value
        data class Bool(val value: Boolean) : Value
        data class Text(val value: String) : Value
        data class Integer(val value: BigInteger) : Value
        data class Decimal(val value: BigDecimal) : Value
        data class Items(val values: List<Value>) : Value
        data class Fields(val values: Map<String, Value>) : Value
    }

    private class Work(maximum: Int) {
        private var remaining = maximum.toLong()
        fun charge(amount: Long = 1) {
            check(amount >= 0) // An internal accounting error cannot become UNKNOWN.
            remaining -= amount
            if (remaining < 0) reject(Reason.WORK_LIMIT)
        }
    }

    fun compare(
        profile: String?,
        operator: String?,
        leftJson: ByteArray?,
        rightJson: ByteArray?,
        limits: Limits = Limits(),
    ): Result = try {
        if (profile != PROFILE) reject(Reason.UNSUPPORTED_PROFILE)
        if (operator !in operators) reject(Reason.UNSUPPORTED_OPERATOR)
        limits.validate()
        val work = Work(limits.maxWork)
        // Each mutable caller array is bounded and copied before it is decoded.
        // The result never retains either array, parsed values, or parser errors.
        val left = read(leftJson, limits, work)
        val right = read(rightJson, limits, work)
        Result.Compared(Comparison(work).compare(operator!!, left, right))
    } catch (error: Rejection) {
        Result.Rejected(error.reason)
    }

    private fun scalarCount(text: String): Int = text.codePointCount(0, text.length)

    private fun nesting(text: String, maximum: Int) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '[', '{' -> {
                    depth++
                    if (depth > maximum) reject(Reason.DEPTH_LIMIT)
                }
                ']', '}' -> depth--
            }
        }
    }

    private fun number(token: String, limits: Limits): BigDecimal {
        if (token.length > limits.maxNumberChars) reject(Reason.NUMBER_LIMIT)
        val declared = exponent.find(token)?.groupValues?.get(1)
        if (declared != null &&
            BigInteger(declared).abs() > BigInteger.valueOf(limits.maxAbsExponent.toLong())
        ) reject(Reason.NUMBER_LIMIT)
        val result = try { BigDecimal(token) } catch (_: NumberFormatException) {
            reject(Reason.NUMBER_LIMIT)
        }
        if (kotlin.math.abs(result.scale().toLong()) > limits.maxAbsExponent) reject(Reason.NUMBER_LIMIT)
        return result
    }

    private fun text(value: String, limits: Limits, work: Work) {
        val count = scalarCount(value)
        if (count > limits.maxStringChars) reject(Reason.STRING_LIMIT)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))
                    reject(Reason.INVALID_UNICODE)
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) reject(Reason.INVALID_UNICODE)
                index++
            }
        }
        work.charge(count.toLong())
    }

    private fun decimalWork(value: BigDecimal): Long =
        value.precision().toLong() + (-value.scale().toLong()).toString().length

    private fun read(raw: ByteArray?, limits: Limits, work: Work): Value {
        if (raw == null) return Value.Unresolved
        if (raw.size > limits.maxBytes) reject(Reason.BYTE_LIMIT)
        val original = raw.copyOf()
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original)).toString()
        } catch (_: CharacterCodingException) {
            reject(Reason.INVALID_UTF8)
        }
        work.charge(scalarCount(decoded).toLong())
        nesting(decoded, limits.maxDepth)
        val value = JsonReader(decoded, limits).readDocument()

        // Reproduce the reference's complete LIFO preflight, including keys.
        val pending = ArrayDeque<Pair<Value, Int>>()
        pending.addLast(value to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (item, depth) = pending.removeLast()
            nodes++
            if (nodes > limits.maxNodes) reject(Reason.NODE_LIMIT)
            work.charge()
            when (item) {
                is Value.Items -> {
                    if (depth + 1 > limits.maxDepth) reject(Reason.DEPTH_LIMIT)
                    item.values.forEach { pending.addLast(it to depth + 1) }
                }
                is Value.Fields -> {
                    if (depth + 1 > limits.maxDepth) reject(Reason.DEPTH_LIMIT)
                    for ((key, child) in item.values) {
                        nodes++
                        if (nodes > limits.maxNodes) reject(Reason.NODE_LIMIT)
                        work.charge()
                        text(key, limits, work)
                        pending.addLast(child to depth + 1)
                    }
                }
                is Value.Text -> {
                    text(item.value, limits, work)
                    if (decimalString.matches(item.value)) number(item.value, limits)
                }
                is Value.Integer -> work.charge(item.value.toString().length.toLong())
                is Value.Decimal -> work.charge(decimalWork(item.value))
                Value.Null, is Value.Bool -> Unit
                Value.Unresolved -> error("Unresolved is not a parsed JSON value")
            }
        }
        return value
    }

    /**
     * Closed reference grammar over the original bounded UTF-8 text. In particular,
     * keys retain escaped surrogate code units until object-pair duplicate checks
     * and complete-document admission have finished, just as the selected Python
     * reader does. A library field-name decoder must not reorder those outcomes.
     */
    private class JsonReader(private val source: String, private val limits: Limits) {
        private var position = 0

        fun readDocument(): Value {
            val value = readValue()
            whitespace()
            if (position != source.length) reject(Reason.INVALID_JSON)
            return value
        }

        private fun whitespace() {
            while (position < source.length && source[position] in " \t\r\n") position++
        }

        private fun take(char: Char): Boolean {
            if (position >= source.length || source[position] != char) return false
            position++
            return true
        }

        private fun literal(spelling: String, value: Value): Value {
            if (!source.startsWith(spelling, position)) reject(Reason.INVALID_JSON)
            position += spelling.length
            return value
        }

        private fun readValue(): Value {
            whitespace()
            if (position >= source.length) reject(Reason.INVALID_JSON)
            return when (source[position]) {
                '[' -> readArray()
                '{' -> readObject()
                '"' -> Value.Text(readString())
                't' -> literal("true", Value.Bool(true))
                'f' -> literal("false", Value.Bool(false))
                'n' -> literal("null", Value.Null)
                'N' -> {
                    if (source.startsWith("NaN", position)) reject(Reason.NON_FINITE)
                    reject(Reason.INVALID_JSON)
                }
                'I' -> {
                    if (source.startsWith("Infinity", position)) reject(Reason.NON_FINITE)
                    reject(Reason.INVALID_JSON)
                }
                '-', in '0'..'9' -> {
                    if (source.startsWith("-Infinity", position)) reject(Reason.NON_FINITE)
                    // JSON's numeric callback consumes the valid prefix before
                    // the surrounding grammar rejects a later trailing token.
                    val token = jsonNumber.matchAt(source, position)?.value ?: reject(Reason.INVALID_JSON)
                    position += token.length
                    val decimal = number(token, limits)
                    if (token.indexOfAny(charArrayOf('.', 'e', 'E')) < 0)
                        Value.Integer(BigInteger(token))
                    else Value.Decimal(decimal)
                }
                else -> reject(Reason.INVALID_JSON)
            }
        }

        private fun readArray(): Value {
            position++
            whitespace()
            if (take(']')) return Value.Items(emptyList())
            val items = ArrayList<Value>()
            while (true) {
                items += readValue()
                whitespace()
                if (take(']')) return Value.Items(items.toList())
                if (!take(',')) reject(Reason.INVALID_JSON)
            }
        }

        private fun readObject(): Value {
            position++
            whitespace()
            if (take('}')) return Value.Fields(emptyMap())
            val pairs = ArrayList<Pair<String, Value>>()
            while (true) {
                whitespace()
                val key = readString()
                whitespace()
                if (!take(':')) reject(Reason.INVALID_JSON)
                pairs += key to readValue()
                whitespace()
                if (take('}')) break
                if (!take(',')) reject(Reason.INVALID_JSON)
            }
            // The original object_pairs_hook runs after every supplied child.
            val fields = LinkedHashMap<String, Value>()
            for ((key, child) in pairs) {
                if (fields.containsKey(key)) reject(Reason.DUPLICATE_KEY)
                fields[key] = child
            }
            return Value.Fields(fields.toMap())
        }

        private fun readString(): String {
            if (!take('"')) reject(Reason.INVALID_JSON)
            val value = StringBuilder()
            while (position < source.length) {
                val char = source[position++]
                when {
                    char == '"' -> return value.toString()
                    char < ' ' -> reject(Reason.INVALID_JSON)
                    char != '\\' -> value.append(char)
                    else -> {
                        if (position >= source.length) reject(Reason.INVALID_JSON)
                        when (val escape = source[position++]) {
                            '"', '\\', '/' -> value.append(escape)
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000c')
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'u' -> {
                                if (source.length - position < 4) reject(Reason.INVALID_JSON)
                                var code = 0
                                repeat(4) {
                                    val digit = source[position++]
                                    val nibble = when (digit) {
                                        in '0'..'9' -> digit - '0'
                                        in 'a'..'f' -> digit - 'a' + 10
                                        in 'A'..'F' -> digit - 'A' + 10
                                        else -> reject(Reason.INVALID_JSON)
                                    }
                                    code = code * 16 + nibble
                                }
                                value.append(code.toChar())
                            }
                            else -> reject(Reason.INVALID_JSON)
                        }
                    }
                }
            }
            reject(Reason.INVALID_JSON)
        }
    }

    private fun truth(value: Boolean): Outcome = if (value) Outcome.TRUE else Outcome.FALSE

    private fun all(values: Sequence<Outcome>): Outcome {
        var result = Outcome.TRUE
        for (value in values) {
            if (value == Outcome.FALSE) result = Outcome.FALSE
            else if (value == Outcome.UNKNOWN && result == Outcome.TRUE) result = Outcome.UNKNOWN
        }
        return result
    }

    private fun any(values: Sequence<Outcome>): Outcome {
        var result = Outcome.FALSE
        for (value in values) {
            if (value == Outcome.TRUE) result = Outcome.TRUE
            else if (value == Outcome.UNKNOWN && result == Outcome.FALSE) result = Outcome.UNKNOWN
        }
        return result
    }

    private val scalarOrder = Comparator<String> { left, right ->
        var a = 0
        var b = 0
        var result = 0
        while (a < left.length && b < right.length && result == 0) {
            val ac = left.codePointAt(a)
            val bc = right.codePointAt(b)
            result = ac.compareTo(bc)
            a += Character.charCount(ac)
            b += Character.charCount(bc)
        }
        if (result != 0) result else (left.length - a).compareTo(right.length - b)
    }

    private class Comparison(private val work: Work) {
        private fun numeric(value: Value): BigDecimal? = when (value) {
            is Value.Integer -> {
                work.charge(value.value.toString().length.toLong())
                BigDecimal(value.value)
            }
            is Value.Decimal -> {
                work.charge(decimalWork(value.value))
                value.value
            }
            is Value.Text -> {
                work.charge(scalarCount(value.value).toLong())
                if (decimalString.matches(value.value)) BigDecimal(value.value) else null
            }
            else -> null
        }

        private fun scalarEqual(left: Value, right: Value): Outcome {
            work.charge()
            val a = numeric(left)
            val b = numeric(right)
            if (a != null || b != null)
                return if (a != null && b != null) truth(a.compareTo(b) == 0) else Outcome.UNKNOWN
            return when {
                left === Value.Null && right === Value.Null -> Outcome.TRUE
                left is Value.Bool && right is Value.Bool -> truth(left.value == right.value)
                left is Value.Text && right is Value.Text -> truth(left.value == right.value)
                else -> Outcome.UNKNOWN
            }
        }

        private fun elementEqual(left: Value, right: Value): Outcome {
            work.charge()
            if (left is Value.Items && right is Value.Items) {
                if (left.values.size != right.values.size) return Outcome.FALSE
                return all(left.values.asSequence().zip(right.values.asSequence()) { a, b -> elementEqual(a, b) })
            }
            if (left is Value.Fields && right is Value.Fields) {
                work.charge(left.values.keys.sumOf { scalarCount(it).toLong() + 1 } +
                    right.values.keys.sumOf { scalarCount(it).toLong() + 1 })
                if (left.values.keys != right.values.keys) return Outcome.FALSE
                return all(left.values.keys.sortedWith(scalarOrder).asSequence().map {
                    elementEqual(left.values.getValue(it), right.values.getValue(it))
                })
            }
            return scalarEqual(left, right)
        }

        private fun contains(container: Value, item: Value): Outcome {
            work.charge()
            if (container is Value.Items)
                return any(container.values.asSequence().map { elementEqual(it, item) })
            if (container is Value.Text && item is Value.Text) {
                work.charge((scalarCount(container.value).toLong() + 1) * (scalarCount(item.value).toLong() + 1))
                return truth(container.value.contains(item.value))
            }
            return Outcome.UNKNOWN
        }

        fun compare(operator: String, left: Value, right: Value): Outcome {
            if (left === Value.Unresolved || right === Value.Unresolved) return Outcome.UNKNOWN
            if (operator == "eq" || operator == "ne") {
                val value = scalarEqual(left, right)
                return if (operator == "ne" && value != Outcome.UNKNOWN)
                    if (value == Outcome.TRUE) Outcome.FALSE else Outcome.TRUE
                else value
            }
            if (operator in setOf("lt", "lte", "gt", "gte")) {
                val a = numeric(left)
                val b = numeric(right)
                if (a == null || b == null) return Outcome.UNKNOWN
                val order = a.compareTo(b)
                return truth(when (operator) {
                    "lt" -> order < 0
                    "lte" -> order <= 0
                    "gt" -> order > 0
                    else -> order >= 0
                })
            }
            if (operator == "contains") return contains(left, right)
            if (operator == "in") return contains(right, left)
            if (left !is Value.Items || right !is Value.Items) return Outcome.UNKNOWN
            val comparisons = left.values.asSequence().map { contains(right, it) }
            return if (operator == "intersects") any(comparisons) else all(comparisons)
        }
    }
}
