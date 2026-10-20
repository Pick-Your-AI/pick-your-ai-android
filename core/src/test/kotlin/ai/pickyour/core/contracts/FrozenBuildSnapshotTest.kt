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

class FrozenBuildSnapshotTest {
    private val decoder = FrozenBuildSnapshotDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/build.json")
    private fun parse(bytes: ByteArray): ObjectNode = (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): FrozenBuildSnapshot =
        (decoder.decode(bytes.inputStream()) as FrozenBuildDecoding.Decoded).build
    private fun decode(node: ObjectNode): FrozenBuildSnapshot = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @TestFactory fun `shared frozen Python and Kotlin Build cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/build-cases.json"))
        assertEquals("android-frozen-build-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(setOf("build.schema.json", "selection.schema.json", "common.schema.json"),
            fixture["schemas"].fieldNames().asSequence().toSet())
        fixture["schemas"].properties().forEach { (name, hash) ->
            assertEquals(hash.textValue(), digest(resource("/contracts/v1/schemas/" + name)))
        }
        val names = fixture["cases"].map { it["name"].textValue() }
        assertTrue(names.isNotEmpty())
        assertEquals(names.size, names.toSet().size)
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertEquals(setOf("name", "expected", "operations"), case.fieldNames().asSequence().toSet())
            val input = parse(example())
            case["operations"].forEach { operation ->
                val path = operation["path"].map { it.textValue() }
                assertTrue(path.isNotEmpty())
                var parent: JsonNode = input
                path.dropLast(1).forEach { segment -> parent = if (parent.isArray) parent[segment.toInt()] else parent[segment] }
                val key = path.last()
                when (operation["op"].textValue()) {
                    "set" -> {
                        assertEquals(setOf("op", "path", "value"), operation.fieldNames().asSequence().toSet())
                        if (parent.isArray) (parent as ArrayNode).set(key.toInt(), operation["value"].deepCopy<JsonNode>())
                        else (parent as ObjectNode).set<JsonNode>(key, operation["value"].deepCopy())
                    }
                    "remove" -> {
                        assertEquals(setOf("op", "path"), operation.fieldNames().asSequence().toSet())
                        if (parent.isArray) (parent as ArrayNode).remove(key.toInt()) else (parent as ObjectNode).remove(key)
                    }
                    else -> fail<Unit>("Unknown fixture operation")
                }
            }
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is FrozenBuildDecoding.Decoded -> {
                    assertEquals(input, parse(result.build.json.toByteArray()))
                    assertEquals(input, parse(decode(result.build.json.toByteArray()).json.toByteArray()))
                    "decoded"
                }
                FrozenBuildDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenBuildDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenBuildDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
            assertFalse(result.toString().contains("synthetic-private-build"))
        } }
    }

    @Test fun `canonical fixture exposes every Build section and all twenty nine selection kinds`() {
        val build = decode(example())
        assertEquals("1.0.0", build.schemaVersion)
        assertEquals("build.fixture.1", build.id)
        assertEquals(BigInteger.ONE, build.revision)
        assertEquals("FICTIONAL Full-Stack AI Build", build.name)
        assertEquals("team.fixture", build.ownerRef)
        assertEquals(FrozenBuildLifecycle.DRAFT, build.lifecycle)
        assertEquals("2026-10-16T09:00:00Z", build.createdAt)
        assertEquals(build.createdAt, build.updatedAt)
        val objectives = build.objectives
        assertEquals("us-east-fixture", objectives.region)
        assertEquals("USD", objectives.currency)
        assertEquals("1000.00", objectives.monthlyBudgetAmount)
        assertEquals(FrozenBuildDataClassification.INTERNAL, objectives.dataClassification)
        assertEquals(BigInteger("200"), objectives.requestsPerDay)
        assertEquals(BigInteger("700"), objectives.averageInputTokens)
        assertEquals(BigInteger("400"), objectives.averageOutputTokens)
        assertEquals(BigInteger("2"), objectives.maximumConcurrency)
        assertEquals(BigInteger("6000"), objectives.maximumLatencyMs)
        val locks = build.locks
        assertEquals("catalog.fixture.1", locks.catalogSnapshotId)
        assertEquals("rules.fixture.1", locks.rulesetId)
        assertEquals("1.0.0", locks.rulesetVersion)
        assertEquals("dependencies.core.1", locks.dependencyGraphId)
        assertEquals(BigInteger.ONE, locks.dependencyGraphRevision)
        assertEquals("prices.fixture.1", locks.pricebookId)
        assertEquals(true, build.policy!!.failOnUnknown)
        assertEquals(true, build.policy.requireEvaluationsForProduction)
        assertEquals(true, build.policy.allowUnverifiedCatalogItems)
        assertEquals(FrozenBuildResourceConfidence.ESTIMATED, build.policy.minimumResourceConfidence)
        val selectionSchema = parse(resource("/contracts/v1/schemas/selection.schema.json"))
        assertEquals(selectionSchema["properties"]["kind"]["enum"].map { it.textValue() }, build.selections.map { it.kind })
        val original = parse(example())
        assertEquals(original["selections"].toList(), build.selections.map { it.toJson() })
        assertEquals(original["links"].map { it["id"].textValue() }, build.links.map { it.id })
        assertEquals("link.01", build.links.first().id)
        assertEquals("sel_model", build.links.first().fromSelectionId)
        assertEquals("sel_model_provider", build.links.first().toSelectionId)
        assertEquals(FrozenBuildRelation.PROVIDED_BY, build.links.first().relation)
        assertNull(build.links.first().required)
        assertNull(build.extensions)
    }

    @Test fun `recorded enum inventories match frozen schema values exactly`() {
        val schema = parse(resource("/contracts/v1/schemas/build.schema.json"))["properties"]
        fun values(node: JsonNode) = node["enum"].map { it.textValue() }
        assertEquals(values(schema["lifecycle"]), FrozenBuildLifecycle.entries.map { it.encoded })
        assertEquals(values(schema["objectives"]["properties"]["data_classification"]), FrozenBuildDataClassification.entries.map { it.encoded })
        assertEquals(values(schema["policy"]["properties"]["min_resource_confidence"]), FrozenBuildResourceConfidence.entries.map { it.encoded })
        assertEquals(values(schema["links"]["items"]["properties"]["relation"]), FrozenBuildRelation.entries.map { it.encoded })
    }

    @Test fun `absent optional fields remain absent while empty objects false and zero remain present`() {
        val input = parse(example())
        val objectives = input["objectives"] as ObjectNode
        listOf("monthly_budget_amount", "requests_per_day", "avg_input_tokens", "avg_output_tokens", "max_concurrency", "max_latency_ms").forEach(objectives::remove)
        (input["locks"] as ObjectNode).remove("pricebook_id")
        input.remove("policy")
        val absent = decode(input)
        assertNull(absent.objectives.monthlyBudgetAmount)
        assertNull(absent.objectives.requestsPerDay)
        assertNull(absent.objectives.averageInputTokens)
        assertNull(absent.objectives.averageOutputTokens)
        assertNull(absent.objectives.maximumConcurrency)
        assertNull(absent.objectives.maximumLatencyMs)
        assertNull(absent.locks.pricebookId)
        assertNull(absent.policy)
        input.set<ObjectNode>("policy", input.objectNode())
        input.set<ObjectNode>("extensions", input.objectNode())
        val selection = input["selections"][0] as ObjectNode
        selection.put("notes", "").set<ObjectNode>("extensions", input.objectNode())
        val empty = decode(input)
        assertNotNull(empty.policy)
        assertNull(empty.policy!!.failOnUnknown)
        assertNull(empty.policy.requireEvaluationsForProduction)
        assertNull(empty.policy.allowUnverifiedCatalogItems)
        assertNull(empty.policy.minimumResourceConfidence)
        assertEquals(0, empty.extensions!!.size())
        assertEquals("", empty.selections.first().notes)
        assertEquals(0, empty.selections.first().extensions!!.size())
        objectives.put("requests_per_day", 0).put("avg_input_tokens", 0).put("avg_output_tokens", 0)
        (input["policy"] as ObjectNode).put("fail_on_unknown", false).put("require_evaluations_for_production", false).put("allow_unverified_catalog_items", false)
        (input["links"][0] as ObjectNode).put("required", false)
        val explicit = decode(input)
        assertEquals(BigInteger.ZERO, explicit.objectives.requestsPerDay)
        assertEquals(BigInteger.ZERO, explicit.objectives.averageInputTokens)
        assertEquals(BigInteger.ZERO, explicit.objectives.averageOutputTokens)
        assertEquals(false, explicit.policy!!.failOnUnknown)
        assertEquals(false, explicit.policy.requireEvaluationsForProduction)
        assertEquals(false, explicit.policy.allowUnverifiedCatalogItems)
        assertEquals(false, explicit.links.first().required)
    }

    @Test fun `wide integral numbers stay exact and fractional boolean or string revisions reject`() {
        val template = example().toString(Charsets.UTF_8)
        for (number in listOf("9007199254740993", "9223372036854775808", "123456789012345678901234567890", "1.00", "1e19", "1.2500e2", "1e1000")) {
            val bytes = template.replace("\"revision\": 1", "\"revision\": $number")
                .replace("\"dependency_graph_revision\": 1", "\"dependency_graph_revision\": $number")
                .replace("\"requests_per_day\": 200", "\"requests_per_day\": $number").toByteArray()
            val build = decode(bytes)
            val expected = BigDecimal(number).toBigIntegerExact()
            assertEquals(expected, build.revision)
            assertEquals(expected, build.locks.dependencyGraphRevision)
            assertEquals(expected, build.objectives.requestsPerDay)
            assertEquals(expected, decode(build.json.toByteArray()).revision)
        }
        for (number in listOf("-1e19", "0e19", "1.5", "9007199254740992.1", "1e-1", "true", "\"1\"")) {
            assertEquals(FrozenBuildDecoding.SchemaMismatch,
                decoder.decode(template.replace("\"revision\": 1", "\"revision\": $number").byteInputStream()))
        }
    }

    @Test fun `private strings opaque versions decimals and URI references are preserved without disclosure or execution`() {
        val input = parse(example()).put("name", "synthetic-private-build").put("owner_ref", "synthetic-private-build/owner")
            .put("created_at", "2026-10-16T10:30:00+01:30").put("updated_at", "2026-10-15T09:00:00Z")
            .put("lifecycle", "deployed")
        (input["objectives"] as ObjectNode).put("monthly_budget_amount", "123456789012345678901234567890.123456789012\n")
        val model = input["selections"][3] as ObjectNode
        model.put("catalog_item_version", "opaque not a semver\n").put("notes", "synthetic-private-build\n<script>execute()</script>")
        (model["settings"] as ObjectNode).put("credential_ref", "secret://synthetic/private-build").put("weights_uri", "file:///synthetic/private-build")
        val build = decode(input)
        assertEquals("synthetic-private-build/owner", build.ownerRef)
        assertEquals(FrozenBuildLifecycle.DEPLOYED, build.lifecycle)
        assertEquals("2026-10-16T10:30:00+01:30", build.createdAt)
        assertEquals("2026-10-15T09:00:00Z", build.updatedAt)
        assertEquals("123456789012345678901234567890.123456789012\n", build.objectives.monthlyBudgetAmount)
        assertEquals("opaque not a semver\n", build.selections[3].catalogItemVersion)
        assertEquals("secret://synthetic/private-build", build.selections[3].settings["credential_ref"].textValue())
        assertEquals("file:///synthetic/private-build", build.selections[3].settings["weights_uri"].textValue())
        assertEquals(input, parse(build.json.toByteArray()))
        listOf(build, build.objectives, build.locks, build.policy, build.selections[3], build.links.first(), FrozenBuildDecoding.Decoded(build)).forEach {
            assertFalse(it.toString().contains("synthetic-private-build"))
        }
    }

    @Test fun `nested extension and settings values cannot mutate the retained snapshot`() {
        val input = parse(example())
        input.set<ObjectNode>("extensions", parse("""{"x_private":{"array":[null,true,{"n":9007199254740993,"s":"synthetic-private-build"}]}}""".toByteArray()))
        (input["selections"][3] as ObjectNode).set<ObjectNode>("extensions", input["extensions"].deepCopy())
        val build = decode(input)
        val json = build.json
        input.removeAll()
        build.extensions!!.removeAll()
        build.selections[3].extensions!!.removeAll()
        build.selections[3].settings.removeAll()
        build.selections[3].toJson().removeAll()
        assertEquals(json, build.json)
        assertEquals("synthetic-private-build", build.extensions!!["x_private"]["array"][2]["s"].textValue())
        assertEquals(BigInteger("9007199254740993"), build.selections[3].extensions!!["x_private"]["array"][2]["n"].bigIntegerValue())
        assertEquals("fp16", build.selections[3].settings["precision"].textValue())
        assertEquals("sel_model", build.selections[3].id)
        assertThrows(UnsupportedOperationException::class.java) { (build.selections as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (build.links as MutableList).clear() }
    }

    @Test fun `structural decoding preserves duplicate identities conflicting roles and unresolved links for domain review`() {
        val input = parse(example())
        val selections = input["selections"] as ArrayNode
        selections.add(selections[3].deepCopy<JsonNode>())
        val links = input["links"] as ArrayNode
        links.add(links[0].deepCopy<JsonNode>())
        (links[0] as ObjectNode).put("to_selection_id", "sel_absent")
        val build = decode(input)
        assertEquals(2, build.selections.count { it.id == "sel_model" && it.role == "primary" })
        assertEquals(2, build.links.count { it.id == "link.01" })
        assertEquals("sel_absent", build.links.first().toSelectionId)
        assertEquals(input, parse(build.json.toByteArray()))
    }

    @Test fun `malformed and oversized input retains sanitized parser outcomes and bounded stream reads`() {
        val template = example().toString(Charsets.UTF_8)
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            template + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            template.replace("\"revision\": 1", "\"revision\": NaN") to JsonRejection.INVALID_JSON,
            template.replace("\"revision\": 1", "\"revision\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"revision\": 1", "\"revision\": 1e10001") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("team.fixture", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
            "{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}" to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (text, reason) -> assertEquals(FrozenBuildDecoding.InvalidInput(reason), decoder.decode(text.byteInputStream())) }
        assertEquals(FrozenBuildDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
        val source = object : InputStream() {
            var bytes = 0
            var closed = false
            override fun available() = Int.MAX_VALUE
            override fun read(): Int { bytes++; return 32 }
            override fun close() { closed = true }
        }
        assertEquals(FrozenBuildDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(source))
        assertEquals(1_048_577, source.bytes)
        assertFalse(source.closed)
    }

    @Test fun `caller owns every stream and provider diagnostics never escape the result`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        listOf(example(), "{}".toByteArray(), "{".toByteArray(), parse(example()).put("lifecycle", "synthetic-private-build").toString().toByteArray()).forEach { bytes ->
            val stream = Tracked(bytes)
            val result = decoder.decode(stream)
            assertFalse(stream.closed)
            assertFalse(result.toString().contains("synthetic-private-build"))
        }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-build/path")
            override fun close() { closed = true }
        }
        assertEquals(FrozenBuildDecoding.InvalidInput(JsonRejection.IO_FAILURE), decoder.decode(broken))
        assertFalse(broken.closed)
        assertEquals("build.fixture.1", decode(example()).id)
    }
}
