package ai.pickyour.core.evidence.rules

import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.evidence.membership.requireMembershipText
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.regex.RegularExpression
import com.networknt.schema.serialization.JsonNodeReader
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Private finite-binary64 intake for the pinned experiment. Unlike CD1's stronger
 * number profile, floating tokens may round or underflow. Original bytes remain
 * separate from this read view. The fixed local budgets are not upstream limits.
 */
internal object FrozenRuleInput {
    const val MAX_BYTES = 1_048_576
    private const val MAX_DEPTH = 32
    private const val MAX_TOKENS = 100_000
    private const val MAX_STRING = 16_384
    private const val MAX_NAME = 256
    private const val MAX_NUMBER = 128
    private val nodes = JsonNodeFactory.instance
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
            .maxStringLength(MAX_STRING).maxNameLength(MAX_NAME).maxNumberLength(MAX_NUMBER).build()).build()

    private val schemaConfig = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true)
        .regularExpressionFactory { expression ->
            val pattern = Pattern.compile(expression, Pattern.UNIX_LINES)
            RegularExpression { value -> pattern.matcher(value).find() }
        }.build()
    private val schemaFactory = JsonSchemaFactory.getInstance(VersionFlag.V202012) { builder ->
        builder.jsonNodeReader(JsonNodeReader.builder().jsonMapper(ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)).build())
        builder.schemaLoaders { loaders ->
            loaders.add { iri -> FrozenSchemaValidator.bundledSchema(iri.toString()) }
        }
    }
    private val schemas = listOf("build", "catalog-snapshot").associateWith { name ->
        schemaFactory.getSchema(SchemaLocation.of("https://schemas.ai-builder.example/v1/$name.schema.json"), schemaConfig)
    }

    fun document(raw: ByteArray, schema: String): ObjectNode {
        val result = decode(raw)
        require(schemas.getValue(schema).validate(validationView(result)).isEmpty()) { "Invalid frozen rule input shape" }
        require(result["revision"].isIntegralNumber) { "Frozen rule revision must use a JSON integer" }
        return result
    }

    fun decode(raw: ByteArray): ObjectNode {
        require(raw.size <= MAX_BYTES) { "Frozen rule input exceeds local byte budget" }
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            factory.createParser(text).use { parser ->
                val cursor = Cursor(parser)
                require(cursor.next() == JsonToken.START_OBJECT) { "Frozen rule input must be a JSON object" }
                val result = cursor.value(JsonToken.START_OBJECT, 1) as ObjectNode
                require(cursor.next() == null) { "Frozen rule input has trailing content" }
                return result
            }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Frozen rule input is not UTF-8")
        } catch (_: IOException) {
            throw IllegalArgumentException("Invalid frozen rule JSON")
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid frozen rule number")
        }
    }

    // Python compares an int with the exact binary value of a float. Converting
    // via Double.toString/valueOf would instead create a rounded decimal bound.
    // Only this private validation view is decimal; the operand view stays Double.
    private fun validationView(value: JsonNode): JsonNode = when {
        value.isFloatingPointNumber -> DecimalNode(BigDecimal(value.doubleValue()))
        value.isObject -> nodes.objectNode().also { copy ->
            value.fields().forEachRemaining { (key, child) -> copy.set<JsonNode>(key, validationView(child)) }
        }
        value.isArray -> nodes.arrayNode().also { copy -> value.forEach { copy.add(validationView(it)) } }
        else -> value.deepCopy()
    }

    private class Cursor(private val parser: JsonParser) {
        private var count = 0
        fun next(): JsonToken? = parser.nextToken()?.also {
            require(++count <= MAX_TOKENS) { "Frozen rule input exceeds local token budget" }
        }

        fun value(token: JsonToken, depth: Int): JsonNode = when (token) {
            JsonToken.START_OBJECT -> {
                require(depth <= MAX_DEPTH) { "Frozen rule input exceeds local depth budget" }
                nodes.objectNode().also { result ->
                    var next = next()
                    while (next != JsonToken.END_OBJECT) {
                        require(next == JsonToken.FIELD_NAME) { "Invalid frozen rule object" }
                        val key = text(parser.currentName(), MAX_NAME)
                        val child = requireNotNull(next()) { "Invalid frozen rule object" }
                        result.set<JsonNode>(key, value(child, depth + 1))
                        next = next()
                    }
                }
            }
            JsonToken.START_ARRAY -> {
                require(depth <= MAX_DEPTH) { "Frozen rule input exceeds local depth budget" }
                nodes.arrayNode().also { result ->
                    var next = next()
                    while (next != JsonToken.END_ARRAY) {
                        result.add(value(requireNotNull(next) { "Invalid frozen rule array" }, depth + 1))
                        next = next()
                    }
                }
            }
            JsonToken.VALUE_STRING -> nodes.textNode(text(parser.text, MAX_STRING))
            JsonToken.VALUE_NUMBER_INT -> {
                require(parser.textLength <= MAX_NUMBER) { "Frozen rule input exceeds local numeric budget" }
                nodes.numberNode(parser.bigIntegerValue)
            }
            JsonToken.VALUE_NUMBER_FLOAT -> {
                require(parser.textLength <= MAX_NUMBER) { "Frozen rule input exceeds local numeric budget" }
                val number = parser.text.toDouble()
                require(number.isFinite()) { "Frozen rule number is not finite" }
                nodes.numberNode(number)
            }
            JsonToken.VALUE_TRUE -> nodes.booleanNode(true)
            JsonToken.VALUE_FALSE -> nodes.booleanNode(false)
            JsonToken.VALUE_NULL -> nodes.nullNode()
            else -> throw IllegalArgumentException("Invalid frozen rule value")
        }

        private fun text(value: String, limit: Int): String {
            require(value.length <= limit) { "Frozen rule input exceeds local text budget" }
            requireMembershipText(value)
            return value
        }
    }
}

internal fun ruleDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
