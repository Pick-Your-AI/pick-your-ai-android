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

class QualityEvaluationRunTest {
    private val decoder = QualityEvaluationRunDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/quality-evaluation-run.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): QualityEvaluationRun =
        (decoder.decode(bytes.inputStream()) as QualityRunDecoding.Decoded).run
    private fun decode(node: ObjectNode): QualityEvaluationRun = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @TestFactory
    fun `shared Python and Kotlin frozen schema cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/quality-run-cases.json"))
        assertEquals("android-quality-run-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(fixture["schema_sha256"].textValue(), digest(resource("/contracts/v1/schemas/quality-evaluation-run.schema.json")))
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(names.size, names.toSet().size)
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            val input = parse(example())
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("measurement_set")?.properties()?.forEach { (name, value) ->
                (input["measurements"][0] as ObjectNode).set<JsonNode>(name, value.deepCopy())
            }
            case.get("measurement_remove")?.forEach { (input["measurements"][0] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val actual = when (result) {
                is QualityRunDecoding.Decoded -> {
                    assertEquals(input, parse(result.run.json.toByteArray()))
                    assertEquals(input, parse(decode(result.run.json.toByteArray()).json.toByteArray()))
                    "decoded"
                }
                QualityRunDecoding.UnsupportedVersion -> "unsupported_version"
                QualityRunDecoding.SchemaMismatch -> "schema_mismatch"
                is QualityRunDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), actual)
            assertFalse(result.toString().contains("synthetic-rejected-secret"))
        } }
    }

    @Test fun `canonical synthetic run exposes every supported field without inventing evidence`() {
        val run = decode(example())
        assertEquals("1.0.0", run.schemaVersion)
        assertEquals("quality.fixture.1", run.id)
        assertEquals("build.fixture.1", run.buildId)
        assertEquals(BigInteger.ONE, run.buildRevision)
        assertEquals("sel_evaluation_suite", run.evaluationSuiteSelectionId)
        assertEquals("2026-10-16T09:00:00Z", run.startedAt)
        assertEquals("2026-10-16T09:00:00Z", run.finishedAt)
        assertEquals(QualityRunStatus.PASSED, run.status)
        assertEquals("0.0-fixture", run.evaluatorVersion)
        assertEquals("fixture-synthetic-dataset-v1", run.datasetRevision)
        assertEquals(BigInteger.valueOf(100), run.sampleSize)
        val measurement = run.measurements.single()
        assertEquals("answer_quality", measurement.metric)
        assertEquals(BigDecimal("0.91"), measurement.observed)
        assertEquals(BigDecimal("0.85"), measurement.threshold)
        assertEquals(QualityComparator.GTE, measurement.comparator)
        assertEquals("score", measurement.unit)
        assertTrue(measurement.passed)
        assertTrue(run.artifacts.isEmpty())
        assertEquals(listOf("TEST FIXTURE: synthetic score, not an actual evaluation."), run.notes)
        assertEquals("QualityEvaluationRun", run.toString())
        assertEquals("QualityMeasurement", measurement.toString())
    }

    @Test fun `measurement values retain arbitrary precision without binary float conversion`() {
        val numbers = listOf(
            "123456789012345678901234567890.123456789012345678901234567890",
            "-0.000000000000000000000000000123456789",
            "1.234567890123456789e1000", "-1.234567890123456789e-1000", "0.000",
        )
        for (number in numbers) {
            val text = example().toString(Charsets.UTF_8).replace("\"observed\": 0.91", "\"observed\": $number")
                .replace("\"threshold\": 0.85", "\"threshold\": -9007199254740993.0000000000000000001")
            val run = decode(text.toByteArray())
            val replay = decode(run.json.toByteArray())
            for (value in listOf(run, replay)) {
                assertEquals(0, BigDecimal(number).compareTo(value.measurements.single().observed))
                assertEquals(BigDecimal("-9007199254740993.0000000000000000001"), value.measurements.single().threshold)
            }
        }
    }

    @Test fun `integer quantities stay exact beyond machine ranges and integral decimal representations`() {
        val input = parse(example())
        val revision = BigInteger("123456789012345678901234567890123456789")
        val samples = BigInteger("987654321098765432109876543210987654321")
        input.put("build_revision", revision).put("sample_size", samples)
        val run = decode(input)
        assertEquals(revision, run.buildRevision)
        assertEquals(samples, run.sampleSize)
        val decimal = example().toString(Charsets.UTF_8).replace("\"build_revision\": 1", "\"build_revision\": 1.00e2")
            .replace("\"sample_size\": 100", "\"sample_size\": 100.000")
        val integral = decode(decimal.toByteArray())
        assertEquals(BigInteger.valueOf(100), integral.buildRevision)
        assertEquals(BigInteger.valueOf(100), integral.sampleSize)
        for (field in listOf("build_revision", "sample_size")) {
            val fractional = parse(example()).put(field, BigDecimal("9007199254740992.1"))
            assertEquals(QualityRunDecoding.SchemaMismatch, decoder.decode(fractional.toString().byteInputStream()))
        }
    }

    @Test fun `normalized numeric growth retains the bounded input rejection`() {
        val token = "9".repeat(126) + "e2"
        assertEquals(128, token.toByteArray().size)
        val input = example().toString(Charsets.UTF_8)
            .replace("\"observed\": 0.91", "\"observed\": $token")
        val parsed = BoundedJsonReader().read(input.byteInputStream()) as JsonInputResult.Parsed
        assertEquals(132, parsed.value["measurements"][0]["observed"].toString().toByteArray().size)
        assertEquals(FrozenValidation.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            FrozenSchemaValidator().validate(FrozenDocument.QUALITY_EVALUATION_RUN, parsed.value.toString().byteInputStream()))
        assertEquals(QualityRunDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(input.byteInputStream()))
    }

    @Test fun `optional absence empty strings and empty arrays remain distinct`() {
        val absent = parse(example()).also {
            it.remove("notes")
            (it["measurements"][0] as ObjectNode).remove("unit")
        }
        val missing = decode(absent)
        assertNull(missing.notes)
        assertNull(missing.measurements.single().unit)
        assertFalse(parse(missing.json.toByteArray()).has("notes"))
        assertFalse(parse(missing.json.toByteArray())["measurements"][0].has("unit"))
        val empty = parse(example()).also {
            it.set<ArrayNode>("notes", it.arrayNode())
            (it["measurements"][0] as ObjectNode).put("unit", "")
        }
        val preserved = decode(empty)
        assertEquals(emptyList<String>(), preserved.notes)
        assertEquals("", preserved.measurements.single().unit)
        assertTrue(parse(preserved.json.toByteArray()).has("notes"))
    }

    @Test fun `recorded contradictions duplicate measurements and inert artifacts are not reinterpreted`() {
        val input = parse(example())
        input.put("finished_at", "2020-01-01T00:00:00Z")
        val measurements = input["measurements"] as ArrayNode
        (measurements[0] as ObjectNode).put("passed", false)
        measurements.add(measurements[0].deepCopy<JsonNode>())
        input.set<ArrayNode>("artifacts", input.arrayNode().add("file:///synthetic/quality")
            .add("https://example.invalid/report").add("file:///synthetic/quality"))
        val run = decode(input)
        assertEquals(QualityRunStatus.PASSED, run.status)
        assertEquals(listOf(false, false), run.measurements.map { it.passed })
        assertEquals(listOf("answer_quality", "answer_quality"), run.measurements.map { it.metric })
        assertEquals("2020-01-01T00:00:00Z", run.finishedAt)
        assertEquals(listOf("file:///synthetic/quality", "https://example.invalid/report", "file:///synthetic/quality"), run.artifacts)
    }

    @Test fun `decoded values cannot be changed through collection casts or source mutation`() {
        val input = parse(example())
        val run = decode(input)
        val retained = run.json
        input.put("id", "quality.changed").remove("measurements")
        assertEquals("quality.fixture.1", run.id)
        assertEquals(retained, run.json)
        assertThrows(UnsupportedOperationException::class.java) { (run.measurements as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (run.artifacts as MutableList).add("file:///changed") }
        assertThrows(UnsupportedOperationException::class.java) { (run.notes as MutableList).clear() }
    }

    @Test fun `invalid bytes duplicate keys trailing content and local limits fail before schema acceptance`() {
        val fixture = example().toString(Charsets.UTF_8)
        val malformed = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            fixture + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            fixture.replace("\"observed\": 0.91", "\"observed\": NaN") to JsonRejection.INVALID_JSON,
            fixture.replace("\"observed\": 0.91", "\"observed\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            fixture.replace("\"observed\": 0.91", "\"observed\": 1e10001") to JsonRejection.LIMIT_EXCEEDED,
            fixture.replace("answer_quality", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
        )
        malformed.forEach { (input, reason) ->
            assertEquals(QualityRunDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(QualityRunDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `streams remain caller owned and provider errors contain no rejected content`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val streams = listOf(Tracked(example()), Tracked("{}".toByteArray()), Tracked("{".toByteArray()),
            Tracked(parse(example()).put("status", "synthetic-rejected-secret").toString().toByteArray()))
        streams.forEach { stream -> decoder.decode(stream); assertFalse(stream.closed) }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-provider-error")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(QualityRunDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-provider-error"))
    }
}
