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
import java.math.BigDecimal
import java.security.MessageDigest

class FrozenRulesetTest {
    private val decoder = FrozenRulesetDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun parse(text: String): ObjectNode = parse(text.toByteArray())
    private fun fixture(): ObjectNode = parse(resource("/contracts/ruleset-cases.json"))
    private fun minimal(): ObjectNode = fixture()["base_document"].deepCopy<ObjectNode>()
    private fun decode(bytes: ByteArray): FrozenRuleset =
        (decoder.decode(bytes.inputStream()) as FrozenRulesetDecoding.Decoded).ruleset
    private fun decode(node: ObjectNode): FrozenRuleset = decode(node.toString().toByteArray())
    private fun rule(node: ObjectNode): ObjectNode = node["rules"][0] as ObjectNode
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun assertOperand(expected: JsonNode, actual: FrozenRuleOperand) {
        when (actual) {
            is FrozenRuleOperand.Literal -> {
                assertEquals(setOf("literal"), expected.fieldNames().asSequence().toSet())
                assertEquals(expected["literal"], parse("{\"value\":${actual.json}} ")["value"])
            }
            is FrozenRuleOperand.Metric -> {
                assertEquals(setOf("metric"), expected.fieldNames().asSequence().toSet())
                assertEquals(expected["metric"].textValue(), actual.metric.encoded)
            }
            is FrozenRuleOperand.Reference -> {
                assertEquals(setOf("ref"), expected.fieldNames().asSequence().toSet())
                val ref = expected["ref"]
                assertEquals(ref["scope"].textValue(), actual.reference.scope.encoded)
                assertEquals(ref["pointer"].textValue(), actual.reference.pointer)
                assertEquals(ref.get("kind")?.textValue(), actual.reference.kind?.encoded)
                assertEquals(ref.get("role")?.textValue(), actual.reference.role)
                assertEquals(ref.get("selection_id")?.textValue(), actual.reference.selectionId)
                assertEquals("FrozenRuleReference", actual.reference.toString())
            }
        }
        assertFalse(actual.toString().contains("synthetic-private-ruleset"))
    }

    private fun assertExpression(expected: JsonNode, actual: FrozenRuleExpression) {
        when (actual) {
            is FrozenRuleExpression.Comparison -> {
                assertEquals(expected["op"].textValue(), actual.op.encoded)
                assertOperand(expected["left"], actual.left)
                assertOperand(expected["right"], actual.right)
            }
            is FrozenRuleExpression.Exists -> {
                assertEquals("exists", expected["op"].textValue())
                assertOperand(expected["value"], actual.value)
            }
            is FrozenRuleExpression.Junction -> {
                assertEquals(expected["op"].textValue(), actual.op.encoded)
                assertEquals(expected["args"].size(), actual.args.size)
                actual.args.forEachIndexed { index, arg -> assertExpression(expected["args"][index], arg) }
            }
            is FrozenRuleExpression.Not -> {
                assertEquals("not", expected["op"].textValue())
                assertExpression(expected["arg"], actual.arg)
            }
            is FrozenRuleExpression.Implies -> {
                assertEquals("implies", expected["op"].textValue())
                assertExpression(expected["if"], actual.condition)
                assertExpression(expected["then"], actual.consequence)
            }
        }
        assertFalse(actual.toString().contains("synthetic-private-ruleset"))
    }

    private fun assertFields(expected: ObjectNode, actual: FrozenRuleset) {
        assertEquals(expected["schema_version"].textValue(), actual.schemaVersion)
        assertEquals(expected["id"].textValue(), actual.id)
        assertEquals(expected["version"].textValue(), actual.version)
        assertEquals(expected["published_at"].textValue(), actual.publishedAt)
        assertEquals(expected["rules"].size(), actual.rules.size)
        actual.rules.forEachIndexed { index, actualRule ->
            val expectedRule = expected["rules"][index]
            assertEquals(expectedRule["id"].textValue(), actualRule.id)
            assertEquals(expectedRule["severity"].textValue(), actualRule.severity.encoded)
            assertExpression(expectedRule["applies_when"], actualRule.appliesWhen)
            assertExpression(expectedRule["assert"], actualRule.assertion)
            assertEquals(expectedRule["on_unknown"].textValue(), actualRule.onUnknown.encoded)
            assertEquals(expectedRule["message"].textValue(), actualRule.message)
            assertEquals(expectedRule["remediation"].textValue(), actualRule.remediation)
            assertEquals(expectedRule["affected_kinds"].map { it.textValue() }, actualRule.affectedKinds.map { it.encoded })
            assertEquals(expectedRule.get("owner")?.textValue(), actualRule.owner)
            assertEquals("FrozenRule", actualRule.toString())
        }
        assertEquals(expected, parse(actual.json))
        assertEquals("FrozenRuleset", actual.toString())
        assertEquals("Decoded(ruleset=FrozenRuleset)", FrozenRulesetDecoding.Decoded(actual).toString())
    }

    @TestFactory
    fun `shared frozen Python and Kotlin ruleset cases`(): List<DynamicTest> {
        val fixture = fixture()
        assertEquals("android-frozen-ruleset-wire/v1", fixture["profile"].textValue())
        mapOf("schema_sha256" to "schemas/ruleset.schema.json", "common_sha256" to "schemas/common.schema.json",
            "example_sha256" to "examples/ruleset.json", "baseline_sha256" to "baseline/core-ruleset.json")
            .forEach { (key, path) -> assertEquals(fixture[key].textValue(), digest(resource("/contracts/v1/$path"))) }
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(145, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isEmpty() })
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertTrue(case.fieldNames().asSequence().all {
                it in setOf("name", "expected", "source", "set", "remove", "rule_set", "rule_remove")
            })
            val input = when (case.get("source")?.textValue() ?: "minimal") {
                "minimal" -> fixture["base_document"].deepCopy<ObjectNode>()
                "example" -> parse(resource("/contracts/v1/examples/ruleset.json"))
                "baseline" -> parse(resource("/contracts/v1/baseline/core-ruleset.json"))
                else -> error("Unknown Ruleset fixture source")
            }
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("rule_set")?.properties()?.forEach { (name, value) -> rule(input).set<JsonNode>(name, value.deepCopy()) }
            case.get("rule_remove")?.forEach { rule(input).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is FrozenRulesetDecoding.Decoded -> {
                    assertFields(input, result.ruleset)
                    assertFields(input, decode(result.ruleset.json.toByteArray()))
                    "decoded"
                }
                FrozenRulesetDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenRulesetDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenRulesetDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
            assertFalse(result.toString().contains("synthetic-private-ruleset"))
        } }
    }

    @Test fun `typed inventories match the exact frozen schema and retain every kind in supplied order`() {
        val schema = parse(resource("/contracts/v1/schemas/ruleset.schema.json"))
        val fields = schema["properties"]["rules"]["items"]["properties"]
        val operands = schema["\$defs"]["operand"]["oneOf"]
        val expressions = schema["\$defs"]["expression"]["oneOf"]
        fun values(node: JsonNode) = node["enum"].map { it.textValue() }
        assertEquals(values(fields["severity"]), FrozenRuleSeverity.entries.map { it.encoded })
        assertEquals(values(fields["on_unknown"]), FrozenRuleUnknownPolicy.entries.map { it.encoded })
        assertEquals(values(fields["affected_kinds"]["items"]), FrozenRuleKind.entries.map { it.encoded })
        assertEquals(values(operands[1]["properties"]["metric"]), FrozenRuleMetric.entries.map { it.encoded })
        val refs = operands[2]["properties"]["ref"]["properties"]
        assertEquals(values(refs["scope"]), FrozenRuleReferenceScope.entries.map { it.encoded })
        assertEquals(values(refs["kind"]), FrozenRuleKind.entries.map { it.encoded })
        assertEquals(values(expressions[0]["properties"]["op"]), FrozenRuleComparison.entries.map { it.encoded })
        assertEquals(values(expressions[2]["properties"]["op"]), FrozenRuleJunction.entries.map { it.encoded })
        val input = minimal()
        rule(input).set<ArrayNode>("affected_kinds", input.arrayNode().also { a ->
            FrozenRuleKind.entries.reversed().forEach { a.add(it.encoded) }
        })
        for (kind in FrozenRuleKind.entries) {
            rule(input).set<ObjectNode>("assert", parse("""{"op":"exists","value":{"ref":{"scope":"build","pointer":"/","kind":"${kind.encoded}"}}}"""))
            val decoded = decode(input)
            assertFields(input, decoded)
            assertEquals(FrozenRuleKind.entries.reversed(), decoded.rules.single().affectedKinds)
            val operand = (decoded.rules.single().assertion as FrozenRuleExpression.Exists).value as FrozenRuleOperand.Reference
            assertEquals(kind, operand.reference.kind)
        }
    }

    @Test fun `arbitrary literal numbers retain exact values without binary float narrowing`() {
        val template = minimal().toString()
        for (number in listOf("9007199254740993", "-9223372036854775809", "123456789012345678901234567890.0000000000000000001",
            "-0.000000000000000000000001", "1.2300", "1e1000", "1e-1000")) {
            val input = template.replace("\"literal\":null", "\"literal\":$number")
            val ruleset = decode(input.toByteArray())
            val literal = (ruleset.rules.single().assertion as FrozenRuleExpression.Exists).value as FrozenRuleOperand.Literal
            assertEquals(0, BigDecimal(number).compareTo(parse("{\"v\":${literal.json}}")["v"].decimalValue()))
            assertFields(parse(input), ruleset)
            assertFields(parse(input), decode(ruleset.json.toByteArray()))
        }
    }

    @Test fun `ordered duplicate rules and nested args remain immutable after source mutation`() {
        val input = minimal()
        val duplicate = rule(input).deepCopy().put("message", "second recorded rule")
        (input["rules"] as ArrayNode).add(duplicate)
        rule(input).set<ObjectNode>("assert", parse("""{"op":"all","args":[
            {"op":"exists","value":{"literal":false}},
            {"op":"exists","value":{"literal":"synthetic-private-ruleset"}}]}"""))
        val ruleset = decode(input)
        val originalJson = ruleset.json
        val junction = ruleset.rules.first().assertion as FrozenRuleExpression.Junction
        assertEquals(listOf("rule.synthetic.1", "rule.synthetic.1"), ruleset.rules.map { it.id })
        assertEquals(listOf("synthetic-private-ruleset", "second recorded rule"), ruleset.rules.map { it.message })
        val values = junction.args.map { ((it as FrozenRuleExpression.Exists).value as FrozenRuleOperand.Literal).json }
        assertEquals(listOf("false", "\"synthetic-private-ruleset\""), values)
        rule(input).put("id", "changed.rule").remove("assert")
        (input["rules"] as ArrayNode).removeAll()
        assertEquals(originalJson, ruleset.json)
        assertEquals("rule.synthetic.1", ruleset.rules.first().id)
        assertThrows(UnsupportedOperationException::class.java) { (ruleset.rules as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (junction.args as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (ruleset.rules.first().affectedKinds as MutableList).clear() }
        val sourceArgs = junction.args.toMutableList()
        val copied = FrozenRuleExpression.Junction(FrozenRuleJunction.ANY, sourceArgs)
        sourceArgs.clear()
        assertEquals(values, copied.args.map { ((it as FrozenRuleExpression.Exists).value as FrozenRuleOperand.Literal).json })
    }

    @Test fun `opaque literals never become expressions references or normalized unknown states`() {
        val input = minimal()
        rule(input).set<ObjectNode>("assert", parse("""{"op":"exists","value":{"literal":{
            "op":"implies","if":{"literal":"unknown"},"then":{"metric":"unknown"},
            "ref":{"scope":"outside-frozen-enums","pointer":"https://example.invalid/inert"},
            "private":"synthetic-private-ruleset","nullable":null,"empty":[]}}}"""))
        val first = decode(input)
        val second = decode(input)
        assertFields(input, first)
        assertFields(input, second)
        val literal = (first.rules.single().assertion as FrozenRuleExpression.Exists).value
        assertInstanceOf(FrozenRuleOperand.Literal::class.java, literal)
        assertFalse(literal.toString().contains("synthetic-private-ruleset"))
        assertEquals(first.json, second.json)
        assertNotSame(first, second)
        assertNotSame(first.rules.single(), second.rules.single())
        assertNull(first.rules.single().owner)
    }

    @Test fun `malformed input and original or normalized resource limits return sanitized reasons`() {
        val template = minimal().toString()
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            template + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            template.replace("\"literal\":null", "\"literal\":NaN") to JsonRejection.INVALID_JSON,
            template.replace("\"literal\":null", "\"literal\":\"\\ud800\"") to JsonRejection.INVALID_JSON,
            template.replace("\"literal\":null", "\"literal\":${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"literal\":null", "\"literal\":1e10001") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"literal\":null", "\"literal\":${"9".repeat(126)}e2") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("synthetic-private-ruleset", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
            template + " ".repeat(1_048_576) to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"literal\":null", "\"literal\":[${"0,".repeat(100_000)}0]") to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (text, reason) ->
            assertEquals(FrozenRulesetDecoding.InvalidInput(reason), decoder.decode(text.byteInputStream()))
        }
        val deep = minimal()
        var expression = rule(deep)["assert"].deepCopy<ObjectNode>()
        repeat(40) { expression = deep.objectNode().put("op", "not").set("arg", expression) }
        rule(deep).set<ObjectNode>("assert", expression)
        assertEquals(FrozenRulesetDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            decoder.decode(deep.toString().byteInputStream()))
        assertEquals(FrozenRulesetDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `caller owns streams on every outcome and provider errors stay sanitized`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        listOf(minimal().toString(), "{}", "{", minimal().put("id", "synthetic private invalid id").toString())
            .forEach { text ->
                val stream = Tracked(text.toByteArray())
                decoder.decode(stream)
                assertFalse(stream.closed)
            }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-ruleset")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(FrozenRulesetDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-ruleset"))
    }

    @Test fun `expression recursion respects the existing exact object-depth boundary`() {
        val input = minimal()
        var expression = rule(input)["assert"].deepCopy<ObjectNode>()
        repeat(27) { expression = input.objectNode().put("op", "not").set("arg", expression) }
        rule(input).set<ObjectNode>("assert", expression)
        val accepted = decode(input)
        assertFields(input, accepted)
        var retained = accepted.rules.single().assertion
        repeat(27) { retained = (retained as FrozenRuleExpression.Not).arg }
        assertEquals("null", ((retained as FrozenRuleExpression.Exists).value as FrozenRuleOperand.Literal).json)
        rule(input).set<ObjectNode>("assert", input.objectNode().put("op", "not").set("arg", expression))
        assertEquals(FrozenRulesetDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            decoder.decode(input.toString().byteInputStream()))
    }
}
