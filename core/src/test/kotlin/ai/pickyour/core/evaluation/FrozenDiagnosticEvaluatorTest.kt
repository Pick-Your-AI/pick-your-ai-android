package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream

class FrozenDiagnosticEvaluatorTest {
    private val mapper = ObjectMapper()
    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun run(build: JsonNode = fixture("build"), catalog: JsonNode = fixture("catalog"), rules: JsonNode = fixture("ruleset"), budget: Int = 250_000) =
        FrozenDiagnosticEvaluator(budget).evaluate(build.toString().byteInputStream(), catalog.toString().byteInputStream(), rules.toString().byteInputStream())
    private fun single(expression: String): ObjectNode {
        val r = fixture("ruleset")
        val rule = r["rules"][0].deepCopy<ObjectNode>()
        rule.set<JsonNode>("applies_when", mapper.readTree("""{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""))
        rule.set<JsonNode>("assert", mapper.readTree(expression))
        r.putArray("rules").add(rule)
        return r
    }
    @TestFactory fun `supported Python goldens match complete reports`() =
        mapper.readTree(javaClass.getResourceAsStream("/evaluation/diagnostic-cases.json")).map { case ->
            DynamicTest.dynamicTest(case["name"].asText()) {
                val result = run(build = case["build"], rules = case["ruleset"])
                assertTrue(result is DiagnosticEvaluation.Evaluated, result.toString())
                assertEquals(case["report"], mapper.readTree((result as DiagnosticEvaluation.Evaluated).report.json))
            }
        }
    @Test fun `foreign catalog and rules locks reject before report creation`() {
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.BINDING_MISMATCH), run(catalog = fixture("catalog").put("id", "catalog.other")))
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.BINDING_MISMATCH), run(rules = fixture("ruleset").put("version", "1.0.1")))
    }
    @Test fun `unsupported version and malformed field reject`() {
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.INVALID_INPUT), run(build = fixture("build").put("schema_version", "2.0.0")))
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.INVALID_INPUT), run(build = fixture("build").put("revision", "1")))
    }
    @Test fun `budget exhaustion never emits a partial compatible report`() {
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.WORK_LIMIT), run(budget = 1))
    }
    @Test fun `string scans charge their full work before execution`() {
        val expression = mapper.createObjectNode().put("op", "contains")
        expression.putObject("left").put("literal", "a".repeat(3000))
        expression.putObject("right").put("literal", "a".repeat(1500) + "b")
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.WORK_LIMIT), run(rules = single(expression.toString())))
    }
    @Test fun `recursive container containment charges nested comparisons`() {
        val expression = mapper.createObjectNode().put("op", "contains")
        val array = expression.putObject("left").putArray("literal")
        repeat(100) { array.addArray().apply { repeat(100) { add(1) }; add(0) } }
        expression.putObject("right").putArray("literal").apply { repeat(100) { add(1) }; add(2) }
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.WORK_LIMIT), run(rules = single(expression.toString()), budget = 5000))
    }
    @Test fun `nested string comparisons consume containment work`() {
        val expression = mapper.createObjectNode().put("op", "contains")
        val array = expression.putObject("left").putArray("literal")
        repeat(60) { array.addObject().put("v", "a".repeat(3000) + "b") }
        expression.putObject("right").putObject("literal").put("v", "a".repeat(3000) + "c")
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.WORK_LIMIT), run(rules = single(expression.toString()), budget = 10_000))
    }
    @Test fun `reference exceptions stay unsupported rather than invented verdicts`() {
        for (expression in listOf(
            """{"op":"subset_of","left":{"literal":[{}]},"right":{"literal":[]}}""",
            """{"op":"contains","left":{"literal":"abc"},"right":{"literal":1}}""")) {
            assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION), run(rules = single(expression)))
        }
    }
    @Test fun `raw floating and exponent operands reject even nested or opposite missing`() {
        for (raw in listOf("9007199254740993.0", "0.10000000000000001", "1e19", "[0.1]", "{\"a\":[1.0]}")) {
            val rules = single("""{"op":"eq","left":{"literal":$raw},"right":{"ref":{"scope":"build","pointer":"/missing"}}}""")
            assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION), run(rules = rules))
        }
    }
    @Test fun `set operands stay unsupported with opposite missing`() {
        val rules = single("""{"op":"subset_of","left":{"literal":[{}]},"right":{"ref":{"scope":"build","pointer":"/missing"}}}""")
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION), run(rules = rules))
    }
    @Test fun `selected item version mismatch is rejected before metrics`() {
        val catalog = fixture("catalog")
        (catalog["items"][0] as ObjectNode).put("version", "9.0.0")
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.BINDING_MISMATCH), run(catalog = catalog))
    }
    @Test fun `raw numeric spelling reaches the strict reader unchanged`() {
        val r = single("""{"op":"eq","left":{"literal":"RAW_NUMBER"},"right":{"literal":0}}""")
        for (raw in listOf("9007199254740993.0", "0.10000000000000001", "1e-999", "1e400", "[1e23]", "{\"value\":[1.0]}")) {
            val bytes = r.toString().replace("\"RAW_NUMBER\"", raw).byteInputStream()
            assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION),
                FrozenDiagnosticEvaluator().evaluate(fixture("build").toString().byteInputStream(), fixture("catalog").toString().byteInputStream(), bytes))
        }
    }
    @Test fun `float encoded identity revisions remain unsupported`() {
        for (key in listOf("revision", "dependency_graph_revision")) {
            val raw = fixture("build").toString().replace("\"$key\":1", "\"$key\":1.0")
            assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION),
                FrozenDiagnosticEvaluator().evaluate(raw.byteInputStream(), fixture("catalog").toString().byteInputStream(), fixture("ruleset").toString().byteInputStream()))
        }
    }
    @Test fun `duplicate identities reject across all three documents`() {
        val b = fixture("build"); (b["selections"] as com.fasterxml.jackson.databind.node.ArrayNode).add(b["selections"][0].deepCopy<JsonNode>())
        val c = fixture("catalog"); (c["items"] as com.fasterxml.jackson.databind.node.ArrayNode).add(c["items"][0].deepCopy<JsonNode>())
        val r = fixture("ruleset"); (r["rules"] as com.fasterxml.jackson.databind.node.ArrayNode).add(r["rules"][0].deepCopy<JsonNode>())
        for (result in listOf(run(build = b), run(catalog = c), run(rules = r)))
            assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.BINDING_MISMATCH), result)
    }
    @Test fun `schema valid swapped catalog identities reject kind mismatch`() {
        val c = fixture("catalog")
        val first = c["items"][0] as ObjectNode; val second = c["items"][1] as ObjectNode
        val firstId = first["id"].asText(); val secondId = second["id"].asText()
        first.put("id", secondId); second.put("id", firstId)
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.BINDING_MISMATCH), run(catalog = c))
    }
    @Test fun `ambiguous selections remain unknown without guessing first match`() {
        val b = fixture("build")
        val duplicate = b["selections"][0].deepCopy<ObjectNode>().put("id", "sel_duplicate")
        (b["selections"] as com.fasterxml.jackson.databind.node.ArrayNode).add(duplicate)
        val kind = duplicate["kind"].asText()
        val rules = single("""{"op":"eq","left":{"ref":{"scope":"selection","kind":"$kind","pointer":"/id"}},"right":{"literal":"none"}}""")
        val result = run(build = b, rules = rules) as DiagnosticEvaluation.Evaluated
        assertEquals("unknown", mapper.readTree(result.report.json)["findings"][0]["outcome"].asText())
    }
    @Test fun `ASCII array pointer preserves leading zeros and missing paths`() {
        val expected = fixture("build")["selections"][0]["id"].asText()
        val rules = single("""{"op":"eq","left":{"ref":{"scope":"build","pointer":"/selections/00/id"}},"right":{"literal":"$expected"}}""")
        val result = run(rules = rules) as DiagnosticEvaluation.Evaluated
        assertEquals("pass", mapper.readTree(result.report.json)["findings"][0]["outcome"].asText())
        val missing = single("""{"op":"eq","left":{"ref":{"scope":"build","pointer":"/missing~1key/~0"}},"right":{"literal":0}}""")
        assertEquals("unknown", mapper.readTree((run(rules = missing) as DiagnosticEvaluation.Evaluated).report.json)["findings"][0]["outcome"].asText())
        val unicode = single("""{"op":"eq","left":{"ref":{"scope":"build","pointer":"/selections/٠/id"}},"right":{"literal":0}}""")
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION), run(rules = unicode))
    }
    @Test fun `derived VRAM accepts exact 28 digits and rejects a rounded 29 digit product`() {
        val c = fixture("catalog")
        val accelerator = c["items"].first { it["kind"].asText() == "accelerator" }
        val amount = "9999999999999999999999999999"
        (accelerator["spec"] as ObjectNode).put("vram_gb", amount.toBigInteger())
        val rules = single("""{"op":"eq","left":{"metric":"compute.total_selected_vram_gb"},"right":{"literal":"$amount"}}""")
        assertEquals("pass", mapper.readTree((run(catalog = c, rules = rules) as DiagnosticEvaluation.Evaluated).report.json)["findings"][0]["outcome"].asText())
        val b = fixture("build")
        (b["selections"].first { it["kind"].asText() == "accelerator" }["settings"] as ObjectNode).put("device_count", 2)
        assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.UNSUPPORTED_OPERATION), run(build = b, catalog = c, rules = rules))
    }
    @Test fun `streams remain open and repeated runs have independent budgets`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) { var closed = false; override fun close() { closed = true } }
        val evaluator = FrozenDiagnosticEvaluator()
        repeat(2) {
            val streams = listOf("build", "catalog", "ruleset").map { Tracked(fixture(it).toString().toByteArray()) }
            assertTrue(evaluator.evaluate(streams[0], streams[1], streams[2]) is DiagnosticEvaluation.Evaluated)
            assertTrue(streams.none { it.closed })
        }
    }
}
