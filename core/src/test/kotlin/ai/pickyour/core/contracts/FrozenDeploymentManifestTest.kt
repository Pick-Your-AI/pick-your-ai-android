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

class FrozenDeploymentManifestTest {
    private val decoder = FrozenDeploymentManifestDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/deployment-manifest.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): FrozenDeploymentManifest =
        (decoder.decode(bytes.inputStream()) as FrozenDeploymentManifestDecoding.Decoded).manifest
    private fun decode(node: ObjectNode): FrozenDeploymentManifest = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @TestFactory
    fun `shared frozen Python and Kotlin deployment manifest cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/deployment-manifest-cases.json"))
        assertEquals("android-frozen-deployment-manifest-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(fixture["schema_sha256"].textValue(), digest(resource("/contracts/v1/schemas/deployment-manifest.schema.json")))
        val names = fixture["cases"].map { it["name"].textValue() }
        assertTrue(names.isNotEmpty())
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isEmpty() })
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertTrue(case.fieldNames().asSequence().all {
                it in setOf("name", "expected", "set", "remove", "approval_set", "approval_remove")
            })
            val input = parse(example())
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("approval_set")?.properties()?.forEach { (name, value) ->
                (input["approvals"][0] as ObjectNode).set<JsonNode>(name, value.deepCopy())
            }
            case.get("approval_remove")?.forEach { (input["approvals"][0] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is FrozenDeploymentManifestDecoding.Decoded -> {
                    assertEquals(input, parse(result.manifest.json.toByteArray()))
                    assertEquals(input, parse(decode(result.manifest.json.toByteArray()).json.toByteArray()))
                    "decoded"
                }
                FrozenDeploymentManifestDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenDeploymentManifestDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenDeploymentManifestDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
            assertFalse(result.toString().contains("synthetic-private-manifest"))
        } }
    }

    @Test fun `typed inventories exactly match each frozen enum and every member decodes`() {
        val properties = parse(resource("/contracts/v1/schemas/deployment-manifest.schema.json"))["properties"]
        val approval = properties["approvals"]["items"]["properties"]
        fun values(node: JsonNode) = node["enum"].map { it.textValue() }
        assertEquals(values(approval["decision"]), FrozenDeploymentDecision.entries.map { it.encoded })
        assertEquals(values(approval["scope"]), FrozenDeploymentApprovalScope.entries.map { it.encoded })
        assertEquals(values(properties["rollout_strategy"]), FrozenRolloutStrategy.entries.map { it.encoded })
        assertEquals(values(properties["status"]), FrozenDeploymentStatus.entries.map { it.encoded })
        FrozenDeploymentDecision.entries.forEach { decision ->
            val input = parse(example())
            (input["approvals"][0] as ObjectNode).put("decision", decision.encoded)
            assertEquals(decision, decode(input).approvals.single().decision)
        }
        FrozenDeploymentApprovalScope.entries.forEach { scope ->
            val input = parse(example())
            (input["approvals"][0] as ObjectNode).put("scope", scope.encoded)
            assertEquals(scope, decode(input).approvals.single().scope)
        }
        FrozenRolloutStrategy.entries.forEach { strategy ->
            assertEquals(strategy, decode(parse(example()).put("rollout_strategy", strategy.encoded)).rolloutStrategy)
        }
        FrozenDeploymentStatus.entries.forEach { status ->
            assertEquals(status, decode(parse(example()).put("status", status.encoded)).status)
        }
    }

    @Test fun `canonical synthetic manifest exposes every required reference without resolving it`() {
        val manifest = decode(example())
        assertEquals("1.0.0", manifest.schemaVersion)
        assertEquals("manifest.fixture.1", manifest.id)
        assertEquals("build.fixture.1", manifest.buildId)
        assertEquals(BigInteger.ONE, manifest.buildRevision)
        assertEquals("sel_deployment_target", manifest.deploymentTargetSelectionId)
        assertEquals("evaluation.build.fixture.1.r1", manifest.compatibilityReportId)
        assertEquals("quality.fixture.1", manifest.qualityRunId)
        assertEquals("estimate.fixture.1", manifest.costEstimateId)
        assertEquals("resources.fixture.1", manifest.resourceEstimateId)
        assertEquals("user.fixture", manifest.approvals.single().approverRef)
        assertEquals(FrozenDeploymentDecision.PENDING, manifest.approvals.single().decision)
        assertEquals(FrozenDeploymentApprovalScope.OWNER, manifest.approvals.single().scope)
        assertNull(manifest.approvals.single().approvedAt)
        assertEquals(FrozenRolloutStrategy.CANARY, manifest.rolloutStrategy)
        assertEquals("2026-10-16T09:00:00Z", manifest.requestedAt)
        assertEquals(FrozenDeploymentStatus.PROPOSED, manifest.status)
        assertNull(manifest.rollbackArtifactUri)
        assertNull(manifest.deploymentReference)
        assertEquals(listOf("Not approved or deployed. All evidence is synthetic."), manifest.notes)
    }

    @Test fun `optional strings timestamps and ordered arrays preserve exact supplied values`() {
        val input = parse(example()).put("rollback_artifact_uri", "urn:synthetic:rollback:one")
            .put("requested_at", "2026-10-16T10:30:00.123456+01:30")
            .put("deployment_reference", "synthetic-private-manifest\n\u05d0\u2603")
        val approvals = input["approvals"] as ArrayNode
        (approvals[0] as ObjectNode).put("approved_at", "2026-10-16T09:00:00.000Z")
        approvals.add(approvals[0].deepCopy<JsonNode>())
        approvals.add(input.objectNode().put("approver_ref", " \t").put("decision", "rejected").put("scope", "security"))
        input.set<ArrayNode>("notes", input.arrayNode().add("second").add("").add("second").add("\n\u05d0"))
        val manifest = decode(input)
        assertEquals("urn:synthetic:rollback:one", manifest.rollbackArtifactUri)
        assertEquals("2026-10-16T10:30:00.123456+01:30", manifest.requestedAt)
        assertEquals("synthetic-private-manifest\n\u05d0\u2603", manifest.deploymentReference)
        assertEquals(listOf("user.fixture", "user.fixture", " \t"), manifest.approvals.map { it.approverRef })
        assertEquals(listOf("2026-10-16T09:00:00.000Z", "2026-10-16T09:00:00.000Z", null),
            manifest.approvals.map { it.approvedAt })
        assertEquals(listOf("second", "", "second", "\n\u05d0"), manifest.notes)
        assertEquals(input, parse(manifest.json.toByteArray()))
        assertEquals("FrozenDeploymentManifest", manifest.toString())
        assertEquals("FrozenDeploymentApproval", manifest.approvals[0].toString())
        assertEquals("Decoded(manifest=FrozenDeploymentManifest)", FrozenDeploymentManifestDecoding.Decoded(manifest).toString())
    }

    @Test fun `optional absent and empty collections or references stay distinct`() {
        val input = parse(example()).also { it.remove("notes") }
        val absent = decode(input)
        assertNull(absent.notes)
        assertNull(absent.deploymentReference)
        assertFalse(parse(absent.json.toByteArray()).has("notes"))
        input.put("deployment_reference", "").set<ArrayNode>("notes", input.arrayNode())
        val empty = decode(input)
        assertEquals("", empty.deploymentReference)
        assertEquals(emptyList<String>(), empty.notes)
        assertTrue(parse(empty.json.toByteArray()).has("notes"))
    }

    @Test fun `build revision retains exact wide integral decimal and exponent values`() {
        val template = example().toString(Charsets.UTF_8)
        for (number in listOf("9007199254740993", "9223372036854775808", "123456789012345678901234567890",
            "1.00", "1e19", "1.2500e2", "1e1000")) {
            val manifest = decode(template.replace("\"build_revision\": 1", "\"build_revision\": $number").toByteArray())
            val expected = BigDecimal(number).toBigIntegerExact()
            assertEquals(expected, manifest.buildRevision)
            assertEquals(expected, decode(manifest.json.toByteArray()).buildRevision)
        }
        for (number in listOf("-1e19", "0e19", "1.5", "9007199254740992.1", "1e-1")) {
            assertEquals(FrozenDeploymentManifestDecoding.SchemaMismatch,
                decoder.decode(template.replace("\"build_revision\": 1", "\"build_revision\": $number").byteInputStream()))
        }
    }

    @Test fun `source mutation and collection casts cannot change a decoded manifest`() {
        val input = parse(example())
        val manifest = decode(input)
        val retained = manifest.json
        input.put("id", "changed").remove("approvals")
        assertEquals("manifest.fixture.1", manifest.id)
        assertEquals("user.fixture", manifest.approvals.single().approverRef)
        assertEquals(retained, manifest.json)
        assertThrows(UnsupportedOperationException::class.java) { (manifest.approvals as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (manifest.notes as MutableList).clear() }
    }

    @Test fun `recorded status approval and URI do not gain stronger semantics from decoding`() {
        val input = parse(example()).put("status", "deployed").put("rollback_artifact_uri", "file:///synthetic/private")
            .put("quality_run_id", "unresolved.quality")
        (input["approvals"][0] as ObjectNode).put("decision", "rejected")
        val first = decode(input)
        val second = decode(input)
        assertEquals(FrozenDeploymentStatus.DEPLOYED, first.status)
        assertEquals(FrozenDeploymentDecision.REJECTED, first.approvals.single().decision)
        assertNull(first.approvals.single().approvedAt)
        assertEquals("file:///synthetic/private", first.rollbackArtifactUri)
        assertEquals("unresolved.quality", first.qualityRunId)
        assertEquals(first.json, second.json)
        assertNotSame(first, second)
    }

    @Test fun `malformed bytes and local budgets retain sanitized parser outcomes`() {
        val template = example().toString(Charsets.UTF_8)
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            template + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            template.replace("\"build_revision\": 1", "\"build_revision\": NaN") to JsonRejection.INVALID_JSON,
            template.replace("\"build_revision\": 1", "\"build_revision\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"build_revision\": 1", "\"build_revision\": 1e10001") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("user.fixture", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (input, reason) ->
            assertEquals(FrozenDeploymentManifestDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(FrozenDeploymentManifestDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `streams stay caller owned on success rejection and sanitized provider failure`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        listOf(example(), "{}".toByteArray(), "{".toByteArray(),
            parse(example()).put("status", "synthetic-private-manifest").toString().toByteArray()).forEach { bytes ->
            val stream = Tracked(bytes)
            decoder.decode(stream)
            assertFalse(stream.closed)
        }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-manifest")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(FrozenDeploymentManifestDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-manifest"))
    }
}
