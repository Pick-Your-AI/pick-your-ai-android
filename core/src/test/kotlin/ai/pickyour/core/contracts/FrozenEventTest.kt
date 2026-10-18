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

class FrozenEventTest {
    private val decoder = FrozenEventDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/event.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): FrozenEvent =
        (decoder.decode(bytes.inputStream()) as FrozenEventDecoding.Decoded).event
    private fun decode(node: ObjectNode): FrozenEvent = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @TestFactory
    fun `shared frozen Python and Kotlin event cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/event-cases.json"))
        assertEquals("android-frozen-event-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(fixture["schema_sha256"].textValue(), digest(resource("/contracts/v1/schemas/event.schema.json")))
        val names = fixture["cases"].map { it["name"].textValue() }
        assertTrue(names.isNotEmpty())
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isEmpty() })
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertTrue(case.fieldNames().asSequence().all {
                it in setOf("name", "expected", "set", "remove", "payload_set", "payload_remove")
            })
            val input = parse(example())
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("payload_set")?.properties()?.forEach { (name, value) ->
                (input["payload"] as ObjectNode).set<JsonNode>(name, value.deepCopy())
            }
            case.get("payload_remove")?.forEach { (input["payload"] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is FrozenEventDecoding.Decoded -> {
                    assertEquals(input, parse(result.event.json.toByteArray()))
                    assertEquals(input, parse(decode(result.event.json.toByteArray()).json.toByteArray()))
                    "decoded"
                }
                FrozenEventDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenEventDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenEventDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
            assertFalse(result.toString().contains("synthetic-private-event"))
        } }
    }

    @Test fun `typed enum inventories exactly match the frozen schema and decode every member`() {
        val schema = parse(resource("/contracts/v1/schemas/event.schema.json"))["properties"]
        fun values(node: JsonNode) = node["enum"].map { it.textValue() }
        assertEquals(values(schema["type"]), FrozenEventType.entries.map { it.encoded })
        assertEquals(values(schema["subject_type"]), FrozenEventSubjectType.entries.map { it.encoded })
        assertEquals(values(schema["payload"]["properties"]["affected_kinds"]["items"]),
            FrozenEventAffectedKind.entries.map { it.encoded })
        FrozenEventType.entries.forEach { type ->
            assertEquals(type, decode(parse(example()).put("type", type.encoded)).type)
        }
        FrozenEventSubjectType.entries.forEach { type ->
            assertEquals(type, decode(parse(example()).put("subject_type", type.encoded)).subjectType)
        }
        val input = parse(example())
        (input["payload"] as ObjectNode).set<ArrayNode>("affected_kinds",
            input.arrayNode().also { array -> FrozenEventAffectedKind.entries.reversed().forEach { array.add(it.encoded) } })
        assertEquals(FrozenEventAffectedKind.entries.reversed(), decode(input).payload.affectedKinds)
    }

    @Test fun `canonical synthetic event exposes each required field without treating references as authority`() {
        val event = decode(example())
        assertEquals("1.0.0", event.schemaVersion)
        assertEquals("evt.fixture.1", event.id)
        assertEquals(FrozenEventType.BUILD_SELECTION_UPSERTED, event.type)
        assertEquals("2026-10-16T09:00:00Z", event.occurredAt)
        assertEquals(FrozenEventSubjectType.BUILD, event.subjectType)
        assertEquals("build.fixture.1", event.subjectId)
        assertEquals(BigInteger.ONE, event.subjectRevision)
        assertEquals("agent.model-agent", event.actorRef)
        assertEquals("correlation.fixture.1", event.correlationId)
        assertEquals("build.fixture.1.rev1.sel_model", event.idempotencyKey)
        assertEquals("sel_model", event.payload.selectionId)
        assertEquals(listOf("model", "context_policy", "compute_profile", "inference_engine", "rag_pipeline",
            "agent_framework", "cost_policy"), event.payload.affectedKinds!!.map { it.encoded })
        assertNull(event.subjectVersion)
        assertNull(event.causationId)
        assertNull(event.payload.catalogItemId)
        assertNull(event.payload.evaluationReportId)
        assertNull(event.payload.costEstimateId)
        assertNull(event.payload.note)
    }

    @Test fun `every optional field retains presence and original string content`() {
        val input = parse(example()).put("subject_version", "version is opaque\n").put("causation_id", "")
            .put("occurred_at", "2026-10-16T10:30:00+01:30")
        (input["payload"] as ObjectNode).put("catalog_item_id", "model.fixture")
            .put("evaluation_report_id", "report.fixture").put("cost_estimate_id", "cost.fixture")
            .put("note", "synthetic-private-event\n\u05d0\u2603")
        val event = decode(input)
        assertEquals("version is opaque\n", event.subjectVersion)
        assertEquals("", event.causationId)
        assertEquals("2026-10-16T10:30:00+01:30", event.occurredAt)
        assertEquals("model.fixture", event.payload.catalogItemId)
        assertEquals("report.fixture", event.payload.evaluationReportId)
        assertEquals("cost.fixture", event.payload.costEstimateId)
        assertEquals("synthetic-private-event\n\u05d0\u2603", event.payload.note)
        assertEquals(input, parse(event.json.toByteArray()))
        assertEquals("FrozenEvent", event.toString())
        assertEquals("FrozenEventPayload", event.payload.toString())
        assertEquals("Decoded(event=FrozenEvent)", FrozenEventDecoding.Decoded(event).toString())
    }

    @Test fun `empty payload and optional empty values remain distinct from absent values`() {
        val input = parse(example()).also { it.set<ObjectNode>("payload", it.objectNode()) }
        val absent = decode(input)
        assertNull(absent.payload.selectionId)
        assertNull(absent.payload.affectedKinds)
        assertNull(absent.payload.note)
        assertFalse(parse(absent.json.toByteArray())["payload"].has("affected_kinds"))
        input.put("subject_version", "").put("causation_id", "")
        (input["payload"] as ObjectNode).put("note", "").set<ArrayNode>("affected_kinds", input.arrayNode())
        val empty = decode(input)
        assertEquals("", empty.subjectVersion)
        assertEquals("", empty.causationId)
        assertEquals("", empty.payload.note)
        assertEquals(emptyList<FrozenEventAffectedKind>(), empty.payload.affectedKinds)
        assertTrue(parse(empty.json.toByteArray())["payload"].has("affected_kinds"))
    }

    @Test fun `subject revision is exact beyond machine width with integral decimal and exponent spellings`() {
        val template = example().toString(Charsets.UTF_8)
        for (number in listOf("9007199254740993", "9223372036854775808", "123456789012345678901234567890",
            "1.00", "1e19", "1.2500e2", "1e1000")) {
            val event = decode(template.replace("\"subject_revision\": 1", "\"subject_revision\": $number").toByteArray())
            val expected = java.math.BigDecimal(number).toBigIntegerExact()
            assertEquals(expected, event.subjectRevision)
            assertEquals(expected, decode(event.json.toByteArray()).subjectRevision)
        }
        for (number in listOf("-1e19", "0e19", "1.5", "9007199254740992.1", "1e-1")) {
            assertEquals(FrozenEventDecoding.SchemaMismatch,
                decoder.decode(template.replace("\"subject_revision\": 1", "\"subject_revision\": $number").byteInputStream()))
        }
    }

    @Test fun `decoded event is immutable through source mutation and collection casts`() {
        val input = parse(example())
        val event = decode(input)
        val retained = event.json
        input.put("actor_ref", "changed").remove("payload")
        assertEquals("agent.model-agent", event.actorRef)
        assertEquals(retained, event.json)
        assertThrows(UnsupportedOperationException::class.java) {
            (event.payload.affectedKinds as MutableList).clear()
        }
    }

    @Test fun `type subject payload and repeated idempotency key remain recorded independent values`() {
        val input = parse(example()).put("type", "pricebook.published").put("subject_type", "catalog_item")
            .put("actor_ref", " ").put("correlation_id", "\t").put("idempotency_key", "\n")
        val first = decode(input)
        val second = decode(input)
        assertEquals(FrozenEventType.PRICEBOOK_PUBLISHED, first.type)
        assertEquals(FrozenEventSubjectType.CATALOG_ITEM, first.subjectType)
        assertEquals("sel_model", first.payload.selectionId)
        assertEquals(" ", first.actorRef)
        assertEquals("\t", first.correlationId)
        assertEquals("\n", first.idempotencyKey)
        assertEquals(first.json, second.json)
        assertNotSame(first, second)
    }

    @Test fun `malformed input and normalization limits keep sanitized parser outcomes`() {
        val template = example().toString(Charsets.UTF_8)
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            template + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            template.replace("\"subject_revision\": 1", "\"subject_revision\": NaN") to JsonRejection.INVALID_JSON,
            template.replace("\"subject_revision\": 1", "\"subject_revision\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"subject_revision\": 1", "\"subject_revision\": 1e10001") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("agent.model-agent", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"subject_revision\": 1", "\"subject_revision\": ${"9".repeat(126)}e2") to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (input, reason) ->
            assertEquals(FrozenEventDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(FrozenEventDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `caller owns streams on all outcomes and provider errors never disclose their messages`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        listOf(example(), "{}".toByteArray(), "{".toByteArray(),
            parse(example()).put("type", "synthetic-private-event").toString().toByteArray()).forEach { bytes ->
            val stream = Tracked(bytes)
            decoder.decode(stream)
            assertFalse(stream.closed)
        }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-event")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(FrozenEventDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-event"))
    }
}
