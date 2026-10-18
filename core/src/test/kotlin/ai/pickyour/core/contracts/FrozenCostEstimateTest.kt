package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.CostRejection
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

class FrozenCostEstimateTest {
    private val decoder = FrozenCostEstimateDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/cost-estimate.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): FrozenCostEstimate =
        (decoder.decode(bytes.inputStream()) as FrozenCostEstimateDecoding.Decoded).estimate
    private fun decode(node: ObjectNode): FrozenCostEstimate = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @TestFactory
    fun `shared frozen Python and Kotlin cost estimate cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/cost-estimate-cases.json"))
        assertEquals("android-frozen-cost-estimate-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(fixture["schema_sha256"].textValue(), digest(resource("/contracts/v1/schemas/cost-estimate.schema.json")))
        assertEquals(fixture["common_sha256"].textValue(), digest(resource("/contracts/v1/schemas/common.schema.json")))
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(224, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isEmpty() })
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertTrue(case.fieldNames().asSequence().all {
                it in setOf("name", "expected", "set", "remove", "line_set", "line_remove")
            })
            val input = parse(example())
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("line_set")?.properties()?.forEach { (name, value) ->
                (input["line_items"][0] as ObjectNode).set<JsonNode>(name, value.deepCopy())
            }
            case.get("line_remove")?.forEach { (input["line_items"][0] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is FrozenCostEstimateDecoding.Decoded -> {
                    assertTypedFields(input, result.estimate)
                    assertEquals(input, parse(result.estimate.json.toByteArray()))
                    assertTypedFields(input, decode(result.estimate.json.toByteArray()))
                    "decoded"
                }
                FrozenCostEstimateDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenCostEstimateDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenCostEstimateDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
            assertFalse(result.toString().contains("synthetic-private-cost"))
        } }
    }

    private fun assertTypedFields(input: JsonNode, estimate: FrozenCostEstimate) {
        assertEquals(input["schema_version"].textValue(), estimate.schemaVersion)
        assertEquals(input["id"].textValue(), estimate.id)
        assertEquals(input["build_id"].textValue(), estimate.buildId)
        assertEquals(input["build_revision"].decimalValue().toBigIntegerExact(), estimate.buildRevision)
        assertEquals(input["pricebook_id"].textValue(), estimate.pricebookId)
        assertEquals(input["estimated_at"].textValue(), estimate.estimatedAt)
        assertEquals(input["period_days"].decimalValue().toBigIntegerExact(), estimate.periodDays)
        assertEquals(input["currency"].textValue(), estimate.currency)
        assertMoney(input["subtotal"], estimate.subtotal)
        assertMoney(input["tax"], estimate.tax)
        assertMoney(input["total"], estimate.total)
        assertEquals(input["coverage"].textValue(), estimate.coverage.encoded)
        assertEquals(input["unpriced_selection_ids"].map { it.textValue() }, estimate.unpricedSelectionIds)
        assertEquals(input["warnings"].map { it.textValue() }, estimate.warnings)
        assertEquals(input["line_items"].size(), estimate.lineItems.size)
        input["line_items"].zip(estimate.lineItems).forEach { (line, recorded) ->
            assertEquals(line["id"].textValue(), recorded.id)
            assertEquals(line["selection_id"].textValue(), recorded.selectionId)
            assertEquals(line["price_rate_id"].textValue(), recorded.priceRateId)
            assertEquals(line["meter"].textValue(), recorded.meter.encoded)
            assertEquals(line["quantity"].textValue(), recorded.quantity)
            assertMoney(line["unit_price"], recorded.unitPrice)
            assertMoney(line["amount"], recorded.amount)
            assertEquals(line["method"].textValue(), recorded.method.encoded)
            assertEquals(line["confidence"].textValue(), recorded.confidence.encoded)
            assertEquals(line["assumptions"].map { it.textValue() }, recorded.assumptions)
        }
    }

    private fun assertMoney(input: JsonNode, money: FrozenCostMoney) {
        assertEquals(input["amount"].textValue(), money.amount)
        assertEquals(input["currency"].textValue(), money.currency)
    }

    @Test fun `typed enum inventories exactly equal the frozen closed members`() {
        val schema = parse(resource("/contracts/v1/schemas/cost-estimate.schema.json"))["properties"]
        val line = schema["line_items"]["items"]["properties"]
        fun values(node: JsonNode) = node["enum"].map { it.textValue() }
        assertEquals(values(line["meter"]), FrozenCostMeter.entries.map { it.encoded })
        assertEquals(values(line["method"]), FrozenCostMethod.entries.map { it.encoded })
        assertEquals(values(line["confidence"]), FrozenCostConfidence.entries.map { it.encoded })
        assertEquals(values(schema["coverage"]), FrozenCostCoverage.entries.map { it.encoded })
    }

    @Test fun `exact revisions and periods retain integral decimal and exponent values beyond machine width`() {
        val template = example().toString(Charsets.UTF_8)
        for ((field, original) in listOf("build_revision" to "1", "period_days" to "30")) {
            val needle = "\"$field\": $original"
            assertTrue(template.contains(needle))
            for (number in listOf("1.00", "1e0", "1.2500e2", "9007199254740993", "9223372036854775808",
                "1e1000", "9".repeat(128))) {
                val estimate = decode(template.replace(needle, "\"$field\": $number").toByteArray())
                val expected = BigDecimal(number).toBigIntegerExact()
                assertEquals(expected, if (field == "build_revision") estimate.buildRevision else estimate.periodDays)
                val roundTrip = decode(estimate.json.toByteArray())
                assertEquals(expected, if (field == "build_revision") roundTrip.buildRevision else roundTrip.periodDays)
            }
            for (number in listOf("-1e19", "0e19", "1.5", "9007199254740992.1", "1e-1")) {
                assertEquals(FrozenCostEstimateDecoding.SchemaMismatch,
                    decoder.decode(template.replace(needle, "\"$field\": $number").byteInputStream()))
            }
        }
    }

    @Test fun `structural decoding retains declarations rejected by the unchanged semantic adapter`() {
        val adapter = CostEstimateAdapter()
        val build = resource("/contracts/v1/examples/build.json")
        assertTrue(adapter.inspect(build.inputStream(), example().inputStream()) is CostReception.Inspected)
        val mutations: List<Pair<CostRejection, (ObjectNode) -> Unit>> = listOf(
            CostRejection.BINDING_MISMATCH to { it.put("build_revision", 2) },
            CostRejection.DUPLICATE_ID to { (it["line_items"] as ArrayNode).add(it["line_items"][0].deepCopy<JsonNode>()) },
            CostRejection.UNKNOWN_SELECTION to { (it["line_items"][0] as ObjectNode).put("selection_id", "sel_missing") },
            CostRejection.INCONSISTENT_COVERAGE to { (it["unpriced_selection_ids"] as ArrayNode).add("sel_compute_profile") },
            CostRejection.MIXED_CURRENCY to { (it["total"] as ObjectNode).put("currency", "EUR") },
            CostRejection.INCONSISTENT_AMOUNTS to { (it["total"] as ObjectNode).put("amount", "0.00") },
            CostRejection.INVALID_ESTIMATE to { (it["total"] as ObjectNode).put("amount", "578.50\n") },
        )
        mutations.forEach { (reason, mutate) ->
            val input = parse(example()).also(mutate)
            assertTypedFields(input, decode(input))
            assertEquals(CostReception.Rejected(reason), adapter.inspect(build.inputStream(), input.toString().byteInputStream()))
        }
    }

    @Test fun `all lists are copied and immutable while source JSON mutation cannot change recorded values`() {
        val input = parse(example())
        val estimate = decode(input)
        val originalJson = estimate.json
        input.removeAll()
        assertEquals(originalJson, estimate.json)
        assertEquals("estimate.fixture.1", estimate.id)
        assertThrows(UnsupportedOperationException::class.java) { (estimate.lineItems as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (estimate.warnings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (estimate.unpricedSelectionIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (estimate.lineItems[0].assumptions as MutableList).clear() }
        val assumptions = mutableListOf("synthetic-private-cost", "")
        val source = estimate.lineItems[0]
        val line = FrozenCostLineItem(source.id, source.selectionId, source.priceRateId, source.meter, source.quantity,
            source.unitPrice, source.amount, source.method, source.confidence, assumptions)
        val lines = mutableListOf(line, line)
        val unpriced = mutableListOf("sel_second", "sel_first")
        val warnings = mutableListOf("synthetic-private-cost", "")
        val copy = FrozenCostEstimate(estimate.schemaVersion, estimate.id, estimate.buildId, estimate.buildRevision,
            estimate.pricebookId, estimate.estimatedAt, estimate.periodDays, estimate.currency, lines, estimate.subtotal,
            estimate.tax, estimate.total, estimate.coverage, unpriced, warnings, estimate.json)
        assumptions.clear(); lines.clear(); unpriced.reverse(); warnings.clear()
        assertEquals(listOf("synthetic-private-cost", ""), copy.lineItems[0].assumptions)
        assertEquals(2, copy.lineItems.size)
        assertEquals(listOf("sel_second", "sel_first"), copy.unpricedSelectionIds)
        assertEquals(listOf("synthetic-private-cost", ""), copy.warnings)
    }

    @Test fun `default formatting exposes no recorded cost data or provider message`() {
        val input = parse(example()).put("id", "synthetic-private-cost")
        (input["warnings"] as ArrayNode).add("synthetic-private-cost")
        val estimate = decode(input)
        assertEquals("FrozenCostEstimate", estimate.toString())
        assertEquals("FrozenCostLineItem", estimate.lineItems[0].toString())
        assertEquals("FrozenCostMoney", estimate.total.toString())
        assertEquals("Decoded(estimate=FrozenCostEstimate)", FrozenCostEstimateDecoding.Decoded(estimate).toString())
        assertFalse(FrozenCostEstimateDecoding.SchemaMismatch.toString().contains("synthetic-private-cost"))
    }

    @Test fun `strict syntax and original plus normalized budgets retain sanitized parser outcomes`() {
        val template = example().toString(Charsets.UTF_8)
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            template + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            "{\"x\":\"\\uD800\"}" to JsonRejection.INVALID_JSON,
            template.replace("\"build_revision\": 1", "\"build_revision\": NaN") to JsonRejection.INVALID_JSON,
            template.replace("\"build_revision\": 1", "\"build_revision\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"build_revision\": 1", "\"build_revision\": 1e10001") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"build_revision\": 1", "\"build_revision\": ${"9".repeat(126)}e2") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("estimate.fixture.1", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
            "{\"x\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}" to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (input, reason) ->
            assertEquals(FrozenCostEstimateDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(FrozenCostEstimateDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
        val atLimit = example() + ByteArray(1_048_576 - example().size) { 0x20 }
        assertEquals("estimate.fixture.1", decode(atLimit).id)
        assertEquals(FrozenCostEstimateDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            decoder.decode((atLimit + byteArrayOf(0x20)).inputStream()))
    }

    @Test fun `caller owns streams on success and every failure including short reads and IO errors`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(1, length))
        }
        listOf(example(), "{}".toByteArray(), "{".toByteArray(), parse(example()).put("coverage", "synthetic-private-cost")
            .toString().toByteArray()).forEach { bytes ->
            val stream = Tracked(bytes)
            val result = decoder.decode(stream)
            if (bytes.contentEquals(example())) assertTrue(result is FrozenCostEstimateDecoding.Decoded)
            assertFalse(stream.closed)
        }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-cost")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(FrozenCostEstimateDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-cost"))
    }
}
