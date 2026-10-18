package ai.pickyour.core.importing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.exc.StreamReadException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Local preview safety budgets, not approved production contract limits. Lengths use UTF-16 code units. */
data class JsonInputLimits(
    val maxBytes: Int = 1_048_576,
    val maxDepth: Int = 32,
    val maxTokens: Int = 100_000,
    val maxStringLength: Int = 16_384,
    val maxNameLength: Int = 256,
    val maxNumberLength: Int = 128,
) {
    init {
        require(maxBytes > 0 && maxTokens > 0)
        // Recursive assembly is bounded even when callers supply their own budgets.
        require(maxDepth in 1..128)
        require(maxStringLength > 0 && maxNameLength > 0 && maxNumberLength > 0)
    }
}

enum class JsonRejection { LIMIT_EXCEEDED, INVALID_JSON, INVALID_UTF8, IO_FAILURE, UNSUPPORTED_ROOT, TRAILING_CONTENT }

sealed interface JsonInputResult {
    /** Syntax acceptance only. The caller must still validate version, schema, URIs and disclosure scope. */
    data class Parsed(val value: ObjectNode) : JsonInputResult
    /** Deliberately contains no parser exception, input fragment, path or provider error message. */
    data class Rejected(val reason: JsonRejection) : JsonInputResult
}

/**
 * Parses exactly one UTF-8 JSON object. No URLs, files, application state or remote services are accessed.
 * The caller owns [InputStream]: this reader never closes it, on success or failure. At most maxBytes + 1
 * bytes are consumed (the extra byte detects overflow). available(), mark() and declared lengths are ignored.
 * Blocking/cancellation of a document provider is the transport caller's responsibility.
 */
class BoundedJsonReader(private val limits: JsonInputLimits = JsonInputLimits()) {
    private val nodes = JsonNodeFactory.instance
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(limits.maxDepth)
                .maxStringLength(limits.maxStringLength)
                .maxNameLength(limits.maxNameLength)
                .maxNumberLength(limits.maxNumberLength)
                .build(),
        ).build()

    fun read(input: InputStream): JsonInputResult = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(ByteBudget(input, limits.maxBytes), decoder)
        factory.createParser(reader).use { parser ->
            val cursor = Cursor(parser)
            val first = cursor.next() ?: reject(JsonRejection.INVALID_JSON)
            if (first != JsonToken.START_OBJECT) reject(JsonRejection.UNSUPPORTED_ROOT)
            val root = cursor.value(first, 1) as ObjectNode
            if (cursor.next() != null) reject(JsonRejection.TRAILING_CONTENT)
            JsonInputResult.Parsed(root)
        }
    } catch (failure: Rejection) {
        JsonInputResult.Rejected(failure.reason)
    } catch (_: StreamConstraintsException) {
        JsonInputResult.Rejected(JsonRejection.LIMIT_EXCEEDED)
    } catch (_: CharacterCodingException) {
        JsonInputResult.Rejected(JsonRejection.INVALID_UTF8)
    } catch (_: StreamReadException) {
        JsonInputResult.Rejected(JsonRejection.INVALID_JSON)
    } catch (_: IOException) {
        JsonInputResult.Rejected(JsonRejection.IO_FAILURE)
    } catch (_: NumberFormatException) {
        JsonInputResult.Rejected(JsonRejection.INVALID_JSON)
    }

    private inner class Cursor(val parser: JsonParser) {
        private var tokens = 0

        fun next(): JsonToken? {
            val token = parser.nextToken() ?: return null
            if (++tokens > limits.maxTokens) reject(JsonRejection.LIMIT_EXCEEDED)
            return token
        }

        fun value(token: JsonToken, depth: Int): JsonNode = when (token) {
            JsonToken.START_OBJECT -> {
                checkDepth(depth)
                val result = nodes.objectNode()
                var next = next() ?: reject(JsonRejection.INVALID_JSON)
                while (next != JsonToken.END_OBJECT) {
                    if (next != JsonToken.FIELD_NAME) reject(JsonRejection.INVALID_JSON)
                    val name = checkedText(parser.currentName(), limits.maxNameLength)
                    val child = next() ?: reject(JsonRejection.INVALID_JSON)
                    result.set<JsonNode>(name, value(child, depth + 1))
                    next = next() ?: reject(JsonRejection.INVALID_JSON)
                }
                result
            }
            JsonToken.START_ARRAY -> {
                checkDepth(depth)
                val result = nodes.arrayNode()
                var next = next() ?: reject(JsonRejection.INVALID_JSON)
                while (next != JsonToken.END_ARRAY) {
                    result.add(value(next, depth + 1))
                    next = next() ?: reject(JsonRejection.INVALID_JSON)
                }
                result
            }
            JsonToken.VALUE_STRING -> nodes.textNode(checkedText(parser.text, limits.maxStringLength))
            JsonToken.VALUE_NUMBER_INT -> {
                checkNumber()
                nodes.numberNode(parser.bigIntegerValue)
            }
            JsonToken.VALUE_NUMBER_FLOAT -> {
                checkNumber()
                val number = parser.decimalValue
                // Prevent tiny exponent strings from becoming enormous plain-decimal renderings downstream.
                if (number.scale().toLong() !in -10_000L..10_000L) reject(JsonRejection.LIMIT_EXCEEDED)
                DecimalNode(number)
            }
            JsonToken.VALUE_TRUE -> nodes.booleanNode(true)
            JsonToken.VALUE_FALSE -> nodes.booleanNode(false)
            JsonToken.VALUE_NULL -> nodes.nullNode()
            else -> reject(JsonRejection.INVALID_JSON)
        }

        private fun checkNumber() {
            if (parser.textLength > limits.maxNumberLength) reject(JsonRejection.LIMIT_EXCEEDED)
        }
    }

    private fun checkDepth(depth: Int) {
        if (depth > limits.maxDepth) reject(JsonRejection.LIMIT_EXCEEDED)
    }

    private fun checkedText(value: String, maxLength: Int): String {
        // Jackson's buffer constraints may allow a small overshoot; enforce the exact public budget too.
        if (value.length > maxLength) reject(JsonRejection.LIMIT_EXCEEDED)
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) reject(JsonRejection.INVALID_JSON)
            } else if (Character.isLowSurrogate(char)) reject(JsonRejection.INVALID_JSON)
        }
        return value
    }

    private class ByteBudget(private val source: InputStream, private val maximum: Int) : InputStream() {
        private var consumed = 0L
        override fun read(): Int {
            if (consumed > maximum) reject(JsonRejection.LIMIT_EXCEEDED)
            val byte = source.read()
            if (byte != -1 && ++consumed > maximum) reject(JsonRejection.LIMIT_EXCEEDED)
            return byte
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            if (consumed > maximum) reject(JsonRejection.LIMIT_EXCEEDED)
            val allowed = minOf(length.toLong(), maximum.toLong() - consumed + 1).toInt()
            val count = source.read(bytes, offset, allowed)
            if (count > 0) {
                consumed += count
                if (consumed > maximum) reject(JsonRejection.LIMIT_EXCEEDED)
            }
            return count
        }
    }

    private class Rejection(val reason: JsonRejection) : IOException()

    companion object {
        private fun reject(reason: JsonRejection): Nothing = throw Rejection(reason)
    }
}
