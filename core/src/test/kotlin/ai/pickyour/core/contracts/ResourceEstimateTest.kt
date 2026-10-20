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
import java.math.BigInteger
import java.security.MessageDigest

class ResourceEstimateTest {
    private val decoder = ResourceEstimateDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/resource-estimate.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): ResourceEstimate =
        (decoder.decode(bytes.inputStream()) as ResourceEstimateDecoding.Decoded).estimate
    private fun decode(node: ObjectNode): ResourceEstimate = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @TestFactory
    fun `shared Python and Kotlin frozen resource estimate cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/resource-estimate-cases.json"))
        assertEquals("android-resource-estimate-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(fixture["schema_sha256"].textValue(), digest(resource("/contracts/v1/schemas/resource-estimate.schema.json")))
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(names.size, names.toSet().size)
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            val input = parse(example())
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("estimate_set")?.properties()?.forEach { (name, value) ->
                (input["estimates"][0] as ObjectNode).set<JsonNode>(name, value.deepCopy())
            }
            case.get("estimate_remove")?.forEach { (input["estimates"][0] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val actual = when (result) {
                is ResourceEstimateDecoding.Decoded -> {
                    val expectedValues = parse(input.toString().toByteArray())
                    assertEquals(expectedValues, parse(result.estimate.json.toByteArray()))
                    assertEquals(expectedValues, parse(decode(result.estimate.json.toByteArray()).json.toByteArray()))
                    "decoded"
                }
                ResourceEstimateDecoding.UnsupportedVersion -> "unsupported_version"
                ResourceEstimateDecoding.SchemaMismatch -> "schema_mismatch"
                is ResourceEstimateDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), actual)
            assertFalse(result.toString().contains("synthetic-rejected-secret"))
        } }
    }

    @Test fun `canonical synthetic estimate exposes every field without inventing measured evidence`() {
        val result = decode(example())
        assertEquals("1.0.0", result.schemaVersion)
        assertEquals("resources.fixture.1", result.id)
        assertEquals("build.fixture.1", result.buildId)
        assertEquals(BigInteger.ONE, result.buildRevision)
        assertEquals("2026-10-16T09:00:00Z", result.generatedAt)
        assertEquals(2, result.estimates.size)
        assertEquals(listOf(ResourceMetric.REQUIRED_VRAM, ResourceMetric.PEAK_PER_DEVICE_VRAM), result.estimates.map { it.metric })
        val entry = result.estimates.first()
        assertEquals("20.0", entry.value)
        assertEquals(ResourceUnit.GB, entry.unit)
        assertEquals("fixture.estimator", entry.estimatorId)
        assertEquals("0.0-fixture", entry.estimatorVersion)
        assertEquals(ResourceConfidence.ESTIMATED, entry.confidence)
        assertNull(entry.sourceUri)
        assertEquals(listOf("sel_model", "sel_context_policy", "sel_inference_engine", "sel_accelerator"), entry.selectionIds)
        assertEquals(listOf("Synthetic figure only. Includes fictitious model weights, KV cache and serving overhead at the selected context/concurrency."), entry.assumptions)
        assertEquals("ResourceEstimate", result.toString())
        assertEquals("ResourceEstimateEntry", entry.toString())
    }

    @Test fun `decimal strings retain exact magnitude trailing zeroes and frozen LF without arithmetic`() {
        for (number in listOf("0", "0.000000000001", "9007199254740993.123456789012", "20.000000000000", "20.0\n")) {
            val input = parse(example())
            (input["estimates"][0] as ObjectNode).put("value", number)
            val result = decode(input)
            assertEquals(number, result.estimates.first().value)
            assertEquals(number, decode(result.json.toByteArray()).estimates.first().value)
            assertEquals(number, parse(result.json.toByteArray())["estimates"][0]["value"].textValue())
        }
    }

    @Test fun `revision is exact beyond machine range and fractional values cannot be truncated`() {
        val revision = BigInteger("123456789012345678901234567890123456789")
        val result = decode(parse(example()).put("build_revision", revision))
        assertEquals(revision, result.buildRevision)
        assertEquals(revision, decode(result.json.toByteArray()).buildRevision)
        val integral = example().toString(Charsets.UTF_8).replace("\"build_revision\": 1", "\"build_revision\": 1.00e2")
        assertEquals(BigInteger.valueOf(100), decode(integral.toByteArray()).buildRevision)
        for (fraction in listOf("1.1", "9007199254740992.1")) {
            val input = parse(example()).put("build_revision", BigDecimal(fraction))
            assertEquals(ResourceEstimateDecoding.SchemaMismatch, decoder.decode(input.toString().byteInputStream()))
        }
    }

    @Test fun `normalized revision growth retains the input representation limit`() {
        val token = "9".repeat(126) + "e2"
        assertEquals(128, token.toByteArray().size)
        val input = example().toString(Charsets.UTF_8).replace("\"build_revision\": 1", "\"build_revision\": $token")
        val parsed = BoundedJsonReader().read(input.byteInputStream()) as JsonInputResult.Parsed
        assertEquals(132, parsed.value["build_revision"].toString().toByteArray().size)
        assertEquals(ResourceEstimateDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(input.byteInputStream()))
    }

    @Test fun `optional source and recorded contradictions preserve values without adding receiver policy`() {
        val input = parse(example()).put("generated_at", "2000-01-01T00:00:00Z")
        val entries = input["estimates"] as ArrayNode
        val first = entries[0] as ObjectNode
        first.put("metric", "training.estimated_gpu_hours").put("unit", "tokens_per_second")
            .put("confidence", "verified").put("source_uri", "file:///synthetic/resource")
        first.set<ArrayNode>("selection_ids", input.arrayNode().add("sel_other_build"))
        first.set<ArrayNode>("assumptions", input.arrayNode().add("").add("duplicate").add("duplicate"))
        entries.add(first.deepCopy())
        val result = decode(input)
        assertEquals("2000-01-01T00:00:00Z", result.generatedAt)
        assertEquals(3, result.estimates.size)
        val entry = result.estimates.first()
        assertEquals(ResourceMetric.GPU_HOURS, entry.metric)
        assertEquals(ResourceUnit.TOKENS_PER_SECOND, entry.unit)
        assertEquals(ResourceConfidence.VERIFIED, entry.confidence)
        assertEquals("file:///synthetic/resource", entry.sourceUri)
        assertEquals(listOf("sel_other_build"), entry.selectionIds)
        assertEquals(listOf("", "duplicate", "duplicate"), entry.assumptions)
        assertEquals(entry.value, result.estimates.last().value)
        assertNull(result.estimates[1].sourceUri)
        assertFalse(parse(result.json.toByteArray())["estimates"][1].has("source_uri"))
    }

    @Test fun `decoded collections and nested values remain immutable after input mutation`() {
        val input = parse(example())
        val result = decode(input)
        val retained = result.json
        input.put("id", "resources.changed")
        val original = input["estimates"][0] as ObjectNode
        (original["selection_ids"] as ArrayNode).removeAll()
        (original["assumptions"] as ArrayNode).add("changed")
        original.put("value", "999")
        assertEquals("resources.fixture.1", result.id)
        assertEquals("20.0", result.estimates.first().value)
        assertEquals(4, result.estimates.first().selectionIds.size)
        assertEquals(1, result.estimates.first().assumptions.size)
        assertEquals(retained, result.json)
        assertThrows(UnsupportedOperationException::class.java) { (result.estimates as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.estimates.first().assumptions as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.estimates.first().selectionIds as MutableList).add("sel_changed") }
    }

    @Test fun `malformed bytes duplicate fields trailing data and local budgets retain typed rejection`() {
        val fixture = example().toString(Charsets.UTF_8)
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            fixture + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            fixture.replace("\"build_revision\": 1", "\"build_revision\": NaN") to JsonRejection.INVALID_JSON,
            fixture.replace("\"build_revision\": 1", "\"build_revision\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            fixture.replace("fixture.estimator", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (input, reason) ->
            assertEquals(ResourceEstimateDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(ResourceEstimateDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `caller owns streams on every result and IO diagnostics omit provider content`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val streams = listOf(Tracked(example()), Tracked("{}".toByteArray()), Tracked("{".toByteArray()),
            Tracked(parse(example()).put("id", "synthetic-rejected-secret value").toString().toByteArray()))
        streams.forEach { stream -> decoder.decode(stream); assertFalse(stream.closed) }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-provider-error")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(ResourceEstimateDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-provider-error"))
    }
}
