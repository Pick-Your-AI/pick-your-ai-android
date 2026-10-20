package ai.pickyour.core.evidence.answers

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.ExecutionContext
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonMetaSchema
import com.networknt.schema.Vocabulary
import com.networknt.schema.Vocabularies
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.regex.RegularExpression
import com.networknt.schema.resource.InputStreamSource
import com.networknt.schema.serialization.JsonNodeReader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.function.Consumer
import java.util.regex.Pattern

/**
 * A caller-supplied, document-local CD-1 field schema. This is not a field
 * registry or a way to change frozen contracts. JSON is bounded at creation;
 * the known-value vocabulary and schema are checked only when a provided value
 * is read, preserving the reference's lazy unresolved-answer behavior.
 */
class KnownAnswerSchema private constructor(private val original: ByteArray, private val root: JsonNode) {
    val originalBytes: ByteArray get() = original.copyOf()
    val originalSHA256: String = MessageDigest.getInstance("SHA-256").digest(original)
        .joinToString("") { "%02x".format(it) }

    private val compiled: JsonSchema by lazy { schemaBoundary { compile() } }

    internal fun requireValid(value: JsonNode) {
        val valid = schemaBoundary {
            compiled.validate(value, Consumer<ExecutionContext>(Cd1ReferenceBudget::begin)).isEmpty()
        }
        cd1Require(valid, Cd1Problem.VALUE_SCHEMA_MISMATCH)
    }

    override fun toString(): String = "KnownAnswerSchema($CD1_ANSWER_PROFILE)"

    private fun compile(): JsonSchema {
        // Each field has a private factory: an input-provided root $id cannot
        // alias another caller's schema or a schema retained in a shared cache.
        val factory = JsonSchemaFactory.getInstance(VersionFlag.V202012) { builder ->
            builder.metaSchema(JsonMetaSchema.builder(JsonMetaSchema.getV202012()).vocabularyFactory { iri ->
                val vocabulary = Vocabularies.getVocabulary(iri)
                if (iri == "https://json-schema.org/draft/2020-12/vocab/validation") {
                    // 1.5.6 assembles vocabulary keywords after ordinary keyword
                    // overrides; replace only these eight value comparisons and
                    // wrap the two contains-count keywords in the selected
                    // vocabulary, preserving all other validators.
                    Vocabulary(iri, *vocabulary.keywords.map { keyword ->
                        when (keyword.value) {
                            in Cd1ValueKeyword.names -> Cd1ValueKeyword(keyword.value)
                            in Cd1ContainsCountKeyword.names -> Cd1ContainsCountKeyword(keyword)
                            else -> keyword
                        }
                    }.toTypedArray())
                } else if (iri == "https://json-schema.org/draft/2020-12/vocab/core") {
                    Vocabulary(iri, *vocabulary.keywords.map { keyword ->
                        if (keyword.value == "\$ref") Cd1ReferenceBudget.wrap(keyword) else keyword
                    }.toTypedArray())
                } else vocabulary
            }.build())
            builder.jsonNodeReader(JsonNodeReader.builder().jsonMapper(ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)).build())
            builder.schemaLoaders { loaders ->
                loaders.values { it.clear() }
                loaders.add { iri -> localMetaSchema(iri.toString()) }
            }
        }
        val config = SchemaValidatorsConfig.builder()
            // The accepted reference does not install a format checker.
            .formatAssertionsEnabled(false).typeLoose(false).nullableKeywordEnabled(false)
            .preloadJsonSchema(false)
            .regularExpressionFactory(Cd1PortablePattern::compile).build()
        val metaConfig = SchemaValidatorsConfig.builder()
            // check_schema checks schema formats; instance formats remain annotations.
            .formatAssertionsEnabled(true).typeLoose(false).nullableKeywordEnabled(false)
            .preloadJsonSchema(false)
            .regularExpressionFactory { regex ->
                val pattern = Pattern.compile(regex, Pattern.UNIX_LINES)
                RegularExpression { value -> pattern.matcher(value).find() }
            }.build()
        requirePortableSyntax()
        val meta = factory.getSchema(SchemaLocation.of(DIALECT), metaConfig)
        cd1Require(meta.validate(root).isEmpty(), Cd1Problem.INVALID_FIELD_SCHEMA)
        requireProfile()
        return factory.getSchema(root, config)
    }

    private data class Visit(val schema: JsonNode, val insideNot: Boolean, val namesOnly: Boolean)

    /** Native resource/admission limits, distinct from the reference's marker policy.
     * Inspect schema locations including unused definitions, but never annotations.
     */
    private fun requirePortableSyntax() {
        val pending = ArrayDeque<JsonNode>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.isObject) continue // The meta-schema diagnoses malformed schema locations.
            node.get("pattern")?.let {
                cd1Require(it.isTextual, Cd1Problem.INVALID_FIELD_SCHEMA)
                Cd1PortablePattern.requireSupported(it.textValue())
            }
            node.get("patternProperties")?.fieldNames()?.forEachRemaining(Cd1PortablePattern::requireSupported)
            for (keyword in COUNT_KEYWORDS) node.get(keyword)?.let {
                // The bundled validators store these counts in signed Int. A
                // large schema count must not wrap into a permissive bound.
                cd1Require(it.isNumber && it.decimalValue() >= java.math.BigDecimal.ZERO &&
                    it.decimalValue() <= java.math.BigDecimal(Int.MAX_VALUE), Cd1Problem.INVALID_FIELD_SCHEMA)
            }
            for (keyword in SCHEMA_MAPS) node.get(keyword)?.elements()?.forEachRemaining(pending::add)
            for (keyword in SCHEMA_ARRAYS) node.get(keyword)?.elements()?.forEachRemaining(pending::add)
            for (keyword in ALL_SCHEMA_VALUES) node.get(keyword)?.let(pending::add)
        }
    }

    private fun requireProfile() {
        val pending = ArrayDeque<Visit>()
        val seen = IdentityHashMap<JsonNode, Int>()
        pending.add(Visit(root, false, false))
        while (pending.isNotEmpty()) {
            val (node, insideNot, namesOnly) = pending.removeLast()
            if (node.isBoolean) continue
            val flag = 1 shl ((if (insideNot) 1 else 0) + (if (namesOnly) 2 else 0))
            val previous = seen[node] ?: 0
            if (previous and flag != 0) continue
            seen[node] = previous or flag
            requireKeywords(node)
            if (!namesOnly) for (keyword in listOf("enum", "const")) {
                node.get(keyword)?.let { cd1Require(!containsUnknown(it), Cd1Problem.INVALID_FIELD_SCHEMA) }
            }
            node.get("\$ref")?.let { pending.add(Visit(localReference(it.textValue()), insideNot, namesOnly)) }
            for (keyword in VALUE_MAPS) node.get(keyword)?.elements()?.forEachRemaining {
                pending.add(Visit(it, insideNot, namesOnly))
            }
            for (keyword in SCHEMA_ARRAYS) node.get(keyword)?.elements()?.forEachRemaining {
                pending.add(Visit(it, insideNot, namesOnly))
            }
            for (keyword in VALUE_SCHEMAS) node.get(keyword)?.let { pending.add(Visit(it, insideNot, namesOnly)) }
            node.get("propertyNames")?.let { pending.add(Visit(it, insideNot, true)) }
            node.get("not")?.let { excluded ->
                val keys = excluded.fieldNames().asSequence().toSet()
                val directFinite = excluded.isObject && (keys == setOf("const") || keys == setOf("enum"))
                if (insideNot || !directFinite) pending.add(Visit(excluded, true, namesOnly))
            }
        }
    }

    private fun requireKeywords(node: JsonNode) {
        if (node.isBoolean) return
        cd1Require(node.isObject, Cd1Problem.INVALID_FIELD_SCHEMA)
        node.get("\$schema")?.let {
            cd1Require(it.isTextual && it.textValue() in setOf(DIALECT, "$DIALECT#"), Cd1Problem.INVALID_FIELD_SCHEMA)
        }
        cd1Require(!node.has("\$dynamicRef") && (node === root || !node.has("\$id")), Cd1Problem.INVALID_FIELD_SCHEMA)
    }

    private enum class Location { SCHEMA, MAP, ARRAY }

    private fun localReference(reference: String): JsonNode {
        cd1Require(reference.startsWith('#'), Cd1Problem.INVALID_FIELD_SCHEMA)
        val pointer = decodeFragment(reference.substring(1))
        if (pointer.isEmpty()) return root
        val tokens = try { pointerTokens(pointer) } catch (_: Cd1Rejected) {
            throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
        }
        var target = root
        var location = Location.SCHEMA
        for (token in tokens) {
            val next = if (location == Location.SCHEMA) {
                // A direct pointer jump still checks resource-bearing ancestors.
                // Map entries literally named "$id" are field names, not keywords.
                requireKeywords(target)
                when (token) {
                    in SCHEMA_MAPS -> Location.MAP
                    in SCHEMA_ARRAYS -> Location.ARRAY
                    in ALL_SCHEMA_VALUES -> Location.SCHEMA
                    else -> throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
                }
            } else Location.SCHEMA
            target = when {
                target.isObject -> target.get(token) ?: throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
                target.isArray && ARRAY_INDEX.matches(token) -> {
                    val index = token.toIntOrNull() ?: throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
                    target.get(index) ?: throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
                }
                else -> throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
            }
            location = next
        }
        cd1Require(location == Location.SCHEMA && (target.isObject || target.isBoolean), Cd1Problem.INVALID_FIELD_SCHEMA)
        return target
    }

    companion object {
        private const val DIALECT = "https://json-schema.org/draft/2020-12/schema"
        private val VALUE_MAPS = setOf("properties", "patternProperties", "dependentSchemas")
        private val SCHEMA_MAPS = VALUE_MAPS + "\$defs"
        private val SCHEMA_ARRAYS = setOf("allOf", "anyOf", "oneOf", "prefixItems")
        private val VALUE_SCHEMAS = setOf("items", "contains", "additionalProperties", "unevaluatedProperties",
            "unevaluatedItems", "if", "then", "else")
        private val ALL_SCHEMA_VALUES = VALUE_SCHEMAS + setOf("not", "propertyNames")
        private val COUNT_KEYWORDS = setOf("minLength", "maxLength", "minItems", "maxItems",
            "minProperties", "maxProperties", "minContains", "maxContains")
        private val ARRAY_INDEX = Regex("0|[1-9][0-9]*")
        private val META_NAMES = setOf("schema", "meta/core", "meta/applicator", "meta/unevaluated",
            "meta/validation", "meta/meta-data", "meta/format-annotation", "meta/content")

        fun read(originalBytes: ByteArray): KnownAnswerSchema {
            // A private wrapper allows a Boolean schema without widening the
            // shared object-only JSON reader. It is never exported or hashed
            // as though it were the caller's original document.
            cd1Require(originalBytes.size <= 65_536, Cd1Problem.INVALID_FIELD_SCHEMA)
            val original = originalBytes.copyOf()
            val wrapped = "{\"schema\":".toByteArray() + original + "}".toByteArray()
            val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_547)).read(wrapped.inputStream())
            if (parsed is JsonInputResult.Rejected) throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA, parsed.reason)
            val document = (parsed as JsonInputResult.Parsed).value
            cd1Require(document.fieldNames().asSequence().toSet() == setOf("schema"), Cd1Problem.INVALID_FIELD_SCHEMA)
            val root = document["schema"]
            cd1Require(root.isObject || root.isBoolean, Cd1Problem.INVALID_FIELD_SCHEMA)
            // A finite JSON schema is required even for annotation values.
            val pending = ArrayDeque<JsonNode>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val value = pending.removeLast()
                if (value.isContainerNode) value.elements().forEachRemaining { pending.add(it) }
                if (value.isFloatingPointNumber) cd1Require(value.doubleValue().isFinite(), Cd1Problem.INVALID_FIELD_SCHEMA)
            }
            return KnownAnswerSchema(original, cd1RuntimeTree(wrapped)["schema"])
        }

        private fun containsUnknown(value: JsonNode): Boolean =
            if (value.isTextual) value.textValue() == "unknown"
            else value.isContainerNode && value.elements().asSequence().any(::containsUnknown)

        private fun decodeFragment(fragment: String): String {
            val bytes = ByteArrayOutputStream()
            var start = 0
            while (start < fragment.length) {
                if (fragment[start] == '%') {
                    cd1Require(start + 2 < fragment.length, Cd1Problem.INVALID_FIELD_SCHEMA)
                    val high = fragment[start + 1].digitToIntOrNull(16)
                    val low = fragment[start + 2].digitToIntOrNull(16)
                    cd1Require(high != null && low != null, Cd1Problem.INVALID_FIELD_SCHEMA)
                    bytes.write(high!! * 16 + low!!)
                    start += 3
                } else {
                    val end = fragment.indexOf('%', start).let { if (it < 0) fragment.length else it }
                    bytes.write(fragment.substring(start, end).toByteArray(Charsets.UTF_8))
                    start = end
                }
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }

        private fun localMetaSchema(uri: String): InputStreamSource {
            val httpsPrefix = "https://json-schema.org/draft/2020-12/"
            val classpathPrefix = "classpath:draft/2020-12/"
            val name = when {
                uri.startsWith(httpsPrefix) -> uri.removePrefix(httpsPrefix)
                uri.startsWith(classpathPrefix) -> uri.removePrefix(classpathPrefix)
                else -> throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
            }
            cd1Require(name in META_NAMES, Cd1Problem.INVALID_FIELD_SCHEMA)
            return InputStreamSource {
                checkNotNull(KnownAnswerSchema::class.java.getResourceAsStream("/draft/2020-12/$name")) {
                    "Bundled 2020-12 meta-schema missing"
                }
            }
        }

        private fun <T> schemaBoundary(block: () -> T): T = try {
            block()
        } catch (failure: Cd1Rejected) {
            throw failure
        } catch (_: Exception) {
            throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
        } catch (_: StackOverflowError) {
            // The accepted reference rejects a recursive application which
            // cannot progress; it is never a known false predicate or success.
            throw Cd1Rejected(Cd1Problem.INVALID_FIELD_SCHEMA)
        }
    }
}
