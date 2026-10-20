package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

class DependencyGraphTest {
    private val decoder = DependencyGraphDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/dependency-graph.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): DependencyGraph =
        (decoder.decode(bytes.inputStream()) as DependencyGraphDecoding.Decoded).graph
    private fun decode(node: JsonNode): DependencyGraph = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun mutate(case: JsonNode): JsonNode {
        val root = parse(example())
        if (case["operation"].textValue() == "none") return root
        val path = case["path"].map { it.textValue() }
        // A wrapper permits exact JSON scalars/arrays without converting numbers through Double.
        fun value(): JsonNode = parse(("{\"value\":" + case["json"].textValue() + "}").toByteArray())["value"]
        if (path.isEmpty()) return value()
        var parent: JsonNode = root
        for (part in path.dropLast(1)) parent = if (parent.isArray) parent[part.toInt()] else parent[part]
        val key = path.last()
        when (parent) {
            is ArrayNode -> if (case["operation"].textValue() == "remove") parent.remove(key.toInt()) else parent.set(key.toInt(), value())
            is ObjectNode -> if (case["operation"].textValue() == "remove") parent.remove(key) else parent.set<JsonNode>(key, value())
            else -> error("Invalid synthetic mutation path")
        }
        return root
    }

    private fun assertFields(input: JsonNode, graph: DependencyGraph) {
        assertEquals(input["schema_version"].textValue(), graph.schemaVersion)
        assertEquals(input["id"].textValue(), graph.id)
        assertEquals(input["revision"].decimalValue().toBigIntegerExact(), graph.revision)
        assertEquals(input["edges"].size(), graph.edges.size)
        graph.edges.zip(input["edges"].toList()).forEach { (edge, node) ->
            assertEquals(node["id"].textValue(), edge.id)
            assertEquals(node["dependent_kind"].textValue(), edge.dependentKind)
            assertEquals(node["depends_on_kind"].textValue(), edge.dependsOnKind)
            assertEquals(node["relationship"].textValue(), edge.relationship.encoded)
            assertEquals(node["activation"].textValue(), edge.activation.encoded)
            assertEquals(node["rationale"].textValue(), edge.rationale)
        }
    }

    private fun assertJsonValues(input: JsonNode, retained: String) {
        val expected = input.deepCopy<ObjectNode>()
        val actual = parse(retained.toByteArray())
        // Integral decimal spelling may normalize to an integer token; JSON value remains exact.
        assertEquals(0, expected["revision"].decimalValue().compareTo(actual["revision"].decimalValue()))
        expected.remove("revision")
        actual.remove("revision")
        assertEquals(expected, actual)
    }

    @TestFactory fun `shared frozen graph reference mutations`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/dependency-graph-cases.json"))
        assertEquals("android-dependency-graph-wire/v1", fixture["profile"].textValue())
        assertEquals(setOf("schemas/common.schema.json", "schemas/dependency-graph.schema.json", "examples/dependency-graph.json"),
            fixture["input_sha256"].fieldNames().asSequence().toSet())
        fixture["input_sha256"].properties().forEach { (path, sha) ->
            assertEquals(sha.textValue(), digest(resource("/contracts/v1/$path")))
        }
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(172, names.size)
        assertEquals(names.size, names.toSet().size)
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            val input = mutate(case)
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is DependencyGraphDecoding.Decoded -> {
                    assertFields(input, result.graph)
                    assertJsonValues(input, result.graph.json)
                    val reopened = decode(result.graph.json.toByteArray())
                    assertFields(input, reopened)
                    assertJsonValues(input, reopened.json)
                    "decoded"
                }
                DependencyGraphDecoding.UnsupportedVersion -> "unsupported_version"
                DependencyGraphDecoding.SchemaMismatch -> "schema_mismatch"
                is DependencyGraphDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
        } }
    }

    @Test fun `complete canonical graph retains every field and direction`() {
        val original = parse(example())
        val graph = decode(original)
        assertFields(original, graph)
        assertEquals("dependencies.core.1", graph.id)
        assertEquals(BigInteger.ONE, graph.revision)
        val edge = graph.edges.first()
        assertEquals("dep.model.deployment_mode", edge.id)
        assertEquals("model", edge.dependentKind)
        assertEquals("deployment_mode", edge.dependsOnKind)
        assertEquals(DependencyRelationship.CONSTRAINS, edge.relationship)
        assertEquals(DependencyActivation.ALWAYS, edge.activation)
        assertEquals("model selection must be reevaluated when deployment_mode changes.", edge.rationale)
        assertEquals("DependencyGraph", graph.toString())
        assertEquals("DependencyEdge", edge.toString())
    }

    @Test fun `repeated identities self edges and cycles preserve supplied order`() {
        val original = parse(example())
        val first = original["edges"][0].deepCopy<ObjectNode>()
        val reverse = first.deepCopy().put("dependent_kind", "deployment_mode").put("depends_on_kind", "model")
        val self = first.deepCopy().put("depends_on_kind", "model").put("relationship", "informs")
        original.set<ArrayNode>("edges", original.arrayNode().add(first).add(reverse).add(first.deepCopy()).add(self))
        val graph = decode(original)
        assertEquals(List(4) { "dep.model.deployment_mode" }, graph.edges.map { it.id })
        assertEquals(listOf("model", "deployment_mode", "model", "model"), graph.edges.map { it.dependentKind })
        assertEquals(listOf("deployment_mode", "model", "deployment_mode", "model"), graph.edges.map { it.dependsOnKind })
        assertEquals(DependencyRelationship.INFORMS, graph.edges.last().relationship)
        assertEquals(original, parse(graph.json.toByteArray()))
        assertFields(original, decode(graph.json.toByteArray()))
    }

    @Test fun `source mutation and collection casts cannot change a decoded graph`() {
        val source = example()
        val graph = decode(source)
        val before = graph.json
        source.fill(0)
        val reopenedTree = parse(graph.json.toByteArray())
        (reopenedTree["edges"] as ArrayNode).removeAll()
        assertThrows(UnsupportedOperationException::class.java) { (graph.edges as MutableList<DependencyEdge>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (graph.edges as MutableList<DependencyEdge>)[0] = graph.edges.last() }
        assertEquals(before, graph.json)
        assertFields(parse(example()), graph)
        assertFields(parse(example()), decode(graph.json.toByteArray()))
    }

    @Test fun `revision preserves exact integers without narrowing or fractional truncation`() {
        val values = listOf("9007199254740993", "9223372036854775808", "123456789012345678901234567890123456789", "1.00e2", "1e19")
        val original = example().toString(Charsets.UTF_8)
        for (value in values) {
            val graph = decode(original.replace("\"revision\": 1", "\"revision\": $value").toByteArray())
            assertEquals(value.toBigDecimal().toBigIntegerExact(), graph.revision)
            assertEquals(graph.revision, decode(graph.json.toByteArray()).revision)
        }
        for (value in listOf("-1e19", "0", "9007199254740992.1")) {
            assertEquals(DependencyGraphDecoding.SchemaMismatch,
                decoder.decode(original.replace("\"revision\": 1", "\"revision\": $value").byteInputStream()))
        }
    }

    @Test fun `normalized numeric growth remains an input limit failure`() {
        val token = "9".repeat(126) + "e2"
        val text = example().toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": $token")
        val parsed = BoundedJsonReader().read(text.byteInputStream()) as JsonInputResult.Parsed
        assertEquals(128, token.length)
        assertEquals(132, parsed.value["revision"].toString().length)
        assertEquals(DependencyGraphDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(text.byteInputStream()))
    }

    @Test fun `bounded malformed input rejects the whole document without exposing fragments`() {
        val text = example().toString(Charsets.UTF_8)
        val malformed = listOf(
            "{" to JsonRejection.INVALID_JSON,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            "$text {}" to JsonRejection.TRAILING_CONTENT,
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"1.0.0\"}" to JsonRejection.INVALID_JSON,
            "{\"id\":\"synthetic-private\",\"\\u0069d\":\"duplicate\"}" to JsonRejection.INVALID_JSON,
            text.replace("dependencies.core.1", "\\uD800") to JsonRejection.INVALID_JSON,
            text.replace("\"revision\": 1", "\"revision\": " + "9".repeat(129)) to JsonRejection.LIMIT_EXCEEDED,
            text.replace("model selection must be reevaluated when deployment_mode changes.", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
            "{\"value\":".repeat(33) + "0" + "}".repeat(33) to JsonRejection.LIMIT_EXCEEDED,
            "{\"edges\":[" + List(100_001) { "0" }.joinToString(",") + "]}" to JsonRejection.LIMIT_EXCEEDED,
            " ".repeat(1_048_577) to JsonRejection.LIMIT_EXCEEDED,
        )
        malformed.forEach { (input, reason) ->
            val result = decoder.decode(input.byteInputStream())
            assertEquals(DependencyGraphDecoding.InvalidInput(reason), result)
            assertFalse(result.toString().contains("synthetic-private"))
        }
        assertEquals(DependencyGraphDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `streams remain caller owned for success rejection and provider failure`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val streams = listOf(Tracked(example()), Tracked("{}".toByteArray()), Tracked("{".toByteArray()),
            Tracked(parse(example()).put("revision", 0).toString().toByteArray()))
        streams.forEach { stream -> decoder.decode(stream); assertFalse(stream.closed) }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-provider-error")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(DependencyGraphDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-provider-error"))
    }
}
