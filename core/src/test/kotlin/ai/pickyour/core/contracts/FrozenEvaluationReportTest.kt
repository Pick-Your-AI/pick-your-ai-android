package ai.pickyour.core.contracts

import ai.pickyour.core.evaluation.ReferenceReportAdapter
import ai.pickyour.core.evaluation.ReportReception
import ai.pickyour.core.evaluation.ReportRejection
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

class FrozenEvaluationReportTest {
    private val decoder = FrozenEvaluationReportDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/evaluation-report.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun original(): ObjectNode = parse(example())
    private fun decode(bytes: ByteArray): FrozenEvaluationReport =
        (decoder.decode(bytes.inputStream()) as FrozenEvaluationReportDecoding.Decoded).report
    private fun decode(node: ObjectNode): FrozenEvaluationReport = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun first(input: ObjectNode): ObjectNode = input["findings"][0] as ObjectNode
    private fun evidence(input: ObjectNode, observed: JsonNode? = null): ObjectNode {
        val row = input.objectNode().put("path", "")
        if (observed != null) row.set<JsonNode>("observed", observed)
        first(input).set<ArrayNode>("evidence", input.arrayNode().add(row))
        return row
    }

    private fun assertProjection(input: ObjectNode, report: FrozenEvaluationReport) {
        for ((key, value) in listOf("schema_version" to report.schemaVersion, "id" to report.id,
            "build_id" to report.buildId, "catalog_snapshot_id" to report.catalogSnapshotId,
            "ruleset_id" to report.rulesetId, "ruleset_version" to report.rulesetVersion,
            "dependency_graph_id" to report.dependencyGraphId, "evaluated_at" to report.evaluatedAt,
            "result" to report.result.encoded, "deployment_readiness" to report.deploymentReadiness.encoded)) {
            assertEquals(input[key].textValue(), value, key)
        }
        assertEquals(input["build_revision"].decimalValue().toBigIntegerExact(), report.buildRevision)
        assertEquals(input["dependency_graph_revision"].decimalValue().toBigIntegerExact(), report.dependencyGraphRevision)
        for ((key, value) in listOf("passed" to report.summary.passed, "failed" to report.summary.failed,
            "unknown" to report.summary.unknown, "skipped" to report.summary.skipped)) {
            assertEquals(input["summary"][key].decimalValue().toBigIntegerExact(), value, key)
        }
        assertEquals(input["findings"].size(), report.findings.size)
        input["findings"].zip(report.findings).forEach { (source, finding) ->
            for ((key, value) in listOf("rule_id" to finding.ruleId, "outcome" to finding.outcome.encoded,
                "severity" to finding.severity.encoded, "code" to finding.code, "message" to finding.message)) {
                assertEquals(source[key].textValue(), value, key)
            }
            assertEquals(source.get("remediation")?.textValue(), finding.remediation)
            assertEquals(source["selection_ids"].map { it.textValue() }, finding.selectionIds)
            if (!source.has("evidence")) assertNull(finding.evidence) else {
                val rows = requireNotNull(finding.evidence)
                assertEquals(source["evidence"].size(), rows.size)
                source["evidence"].zip(rows).forEach { (raw, row) ->
                    assertEquals(raw["path"].textValue(), row.path)
                    assertEquals(raw.get("observed")?.toString(), row.observed?.json)
                    assertEquals(raw.get("expected")?.toString(), row.expected?.json)
                    assertEquals(raw.get("source_version")?.textValue(), row.sourceVersion)
                }
            }
        }
        assertEquals(input, parse(report.json.toByteArray()))
    }

    @TestFactory
    fun `shared Python and Kotlin frozen evaluation report cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/frozen-evaluation-report-cases.json"))
        assertEquals("android-frozen-evaluation-report-wire/v1", fixture["profile"].textValue())
        for ((key, path) in listOf("schema_sha256" to "/contracts/v1/schemas/evaluation-report.schema.json",
            "common_sha256" to "/contracts/v1/schemas/common.schema.json",
            "example_sha256" to "/contracts/v1/examples/evaluation-report.json")) {
            assertEquals(fixture[key].textValue(), digest(resource(path)))
        }
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(348, names.size)
        assertEquals(names.size, names.toSet().size)
        val allowed = setOf("name", "expected", "set", "remove", "finding_set", "finding_remove", "summary_set", "summary_remove")
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertTrue(case.properties().all { it.key in allowed })
            val input = original()
            case.get("set")?.properties()?.forEach { (key, value) -> input.set<JsonNode>(key, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("finding_set")?.properties()?.forEach { (key, value) -> first(input).set<JsonNode>(key, value.deepCopy()) }
            case.get("finding_remove")?.forEach { first(input).remove(it.textValue()) }
            case.get("summary_set")?.properties()?.forEach { (key, value) ->
                (input["summary"] as ObjectNode).set<JsonNode>(key, value.deepCopy())
            }
            case.get("summary_remove")?.forEach { (input["summary"] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val actual = when (result) {
                is FrozenEvaluationReportDecoding.Decoded -> {
                    assertProjection(input, result.report)
                    assertProjection(input, decode(result.report.json.toByteArray()))
                    "decoded"
                }
                FrozenEvaluationReportDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenEvaluationReportDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenEvaluationReportDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), actual)
            assertFalse(result.toString().contains("synthetic-private-report-value"))
        } }
    }

    @Test fun `canonical report exposes every recorded field without certifying evaluation`() {
        val report = decode(example())
        assertProjection(original(), report)
        assertEquals("evaluation.build.fixture.1.r1", report.id)
        assertEquals(BigInteger.ONE, report.buildRevision)
        assertEquals(BigInteger.ONE, report.dependencyGraphRevision)
        assertEquals(45, report.findings.size)
        assertEquals(FrozenEvaluationResult.VALID, report.result)
        assertEquals(FrozenReportReadiness.NEEDS_REVIEW, report.deploymentReadiness)
        assertEquals(BigInteger.valueOf(41), report.summary.passed)
        assertEquals(BigInteger.valueOf(4), report.summary.skipped)
        assertEquals(BigInteger.ZERO, report.summary.failed)
        assertEquals(BigInteger.ZERO, report.summary.unknown)
        assertEquals(FrozenFindingOutcome.PASS, report.findings.first().outcome)
        assertEquals(FrozenFindingSeverity.ERROR, report.findings.first().severity)
        assertEquals("", report.findings.first().remediation)
        assertNull(report.findings.first().evidence)
        assertTrue(ReferenceReportAdapter().receive(
            resource("/contracts/v1/examples/build.json").inputStream(),
            resource("/contracts/v1/examples/ruleset.json").inputStream(), example().inputStream()) is ReportReception.Accepted)
    }

    @Test fun `opaque evidence preserves absent null and arbitrary exact JSON without reference resolution`() {
        val input = original()
        val row = evidence(input)
        row.putNull("observed")
        row.set<ObjectNode>("expected", input.objectNode().put("amount", "9007199254740993.123456789012")
            .put("count", BigInteger("123456789012345678901234567890123456789"))
        row.put("source_version", "")
        val extra = input.objectNode().put("path", "file:///synthetic/inert")
        extra.set<ArrayNode>("observed", input.arrayNode().addNull().add(false).add(0).add("")
            .add(input.objectNode().put("$" + "ref", "https://fixture.invalid/inert").put("op", "eq")))
        (first(input)["evidence"] as ArrayNode).add(extra)
        val report = decode(input)
        assertProjection(input, report)
        val rows = requireNotNull(report.findings.first().evidence)
        assertEquals("null", requireNotNull(rows[0].observed).json)
        assertEquals("", rows[0].sourceVersion)
        assertNull(rows[1].expected)
        assertNull(rows[1].sourceVersion)
        assertEquals("file:///synthetic/inert", rows[1].path)
        val rawNumber = input.toString().replace("\"count\":123456789012345678901234567890123456789",
            "\"count\":9007199254740992.12345678901234567890")
        val exact = decode(rawNumber.toByteArray())
        assertEquals(BigDecimal("9007199254740992.12345678901234567890"),
            parse("{\"v\":${requireNotNull(exact.findings.first().evidence!![0].expected).json}}".toByteArray())["v"]["count"].decimalValue())
    }

    @Test fun `optional arrays text and frozen lexical values retain distinct supplied states`() {
        val input = original().put("evaluated_at", "2026-10-16T10:30:00.1200+01:30")
            .put("id", "report.synthetic\n").put("ruleset_version", "00.01.0002\n")
        first(input).remove("remediation")
        first(input).put("code", "ABC\n").put("message", " \n\t")
        first(input).putArray("evidence")
        val report = decode(input)
        assertProjection(input, report)
        assertNull(report.findings.first().remediation)
        assertNotNull(report.findings.first().evidence)
        assertTrue(report.findings.first().evidence!!.isEmpty())
        assertNull(report.findings[1].evidence)
        assertEquals("", report.findings[1].remediation)
        assertEquals("report.synthetic\n", report.id)
        assertEquals("00.01.0002\n", report.rulesetVersion)
        assertEquals("2026-10-16T10:30:00.1200+01:30", report.evaluatedAt)
    }

    @Test fun `structural duplicates declarations and foreign identities cannot bypass the strict receiver`() {
        val adapter = ReferenceReportAdapter()
        fun rejected(input: ObjectNode, reason: ReportRejection) {
            assertProjection(input, decode(input))
            assertEquals(ReportReception.Rejected(reason), adapter.receive(
                resource("/contracts/v1/examples/build.json").inputStream(),
                resource("/contracts/v1/examples/ruleset.json").inputStream(), input.toString().byteInputStream()))
        }
        rejected(original().put("build_id", "build.unrelated"), ReportRejection.BINDING_MISMATCH)
        rejected(original().put("deployment_readiness", "ready"), ReportRejection.INCONSISTENT_FINDINGS)
        rejected(original().put("result", "invalid"), ReportRejection.INCONSISTENT_FINDINGS)
        val counts = original()
        (counts["summary"] as ObjectNode).put("passed", BigInteger("18446744073709551617"))
        rejected(counts, ReportRejection.INCONSISTENT_FINDINGS)
        val duplicates = original()
        val findings = duplicates["findings"] as ArrayNode
        findings.add(findings[0].deepCopy<JsonNode>())
        rejected(duplicates, ReportRejection.INCONSISTENT_FINDINGS)
        val order = original()
        val ordered = order["findings"] as ArrayNode
        ordered.add(ordered.remove(0))
        rejected(order, ReportRejection.INCONSISTENT_FINDINGS)
        val withEvidence = original()
        val row = evidence(withEvidence).put("path", "synthetic-private-report-value")
        (first(withEvidence)["evidence"] as ArrayNode).add(row.deepCopy())
        val recorded = decode(withEvidence).findings.first().evidence!!
        assertEquals(listOf("synthetic-private-report-value", "synthetic-private-report-value"), recorded.map { it.path })
    }

    @Test fun `all revisions and counters retain exact integers and reject nonintegral or negative values`() {
        val input = original()
        val huge = BigInteger("9".repeat(128))
        for (key in listOf("build_revision", "dependency_graph_revision")) input.put(key, huge)
        val summary = input["summary"] as ObjectNode
        for (key in listOf("passed", "failed", "unknown", "skipped")) summary.put(key, huge)
        assertProjection(input, decode(input))
        val raw = original().toString()
        for (representation in listOf("1.0", "1e0", "1.00e2", "18446744073709551617")) {
            val changed = raw.replace("\"build_revision\":1,", "\"build_revision\":$representation,")
                .replace("\"dependency_graph_revision\":1,", "\"dependency_graph_revision\":$representation,")
                .replace("\"passed\":41,", "\"passed\":$representation,")
            val report = decode(changed.toByteArray())
            val expected = BigDecimal(representation).toBigIntegerExact()
            assertEquals(expected, report.buildRevision)
            assertEquals(expected, report.dependencyGraphRevision)
            assertEquals(expected, report.summary.passed)
        }
        for (key in listOf("build_revision", "dependency_graph_revision")) {
            for (number in listOf("9007199254740992.1", "-1e19")) {
                val changed = raw.replace("\"$key\":1,", "\"$key\":$number,")
                assertEquals(FrozenEvaluationReportDecoding.SchemaMismatch, decoder.decode(changed.byteInputStream()))
            }
        }
        for (key in listOf("passed", "failed", "unknown", "skipped")) {
            for (number in listOf(BigDecimal("9007199254740992.1"), BigDecimal("-1e19"))) {
                val changed = original()
                (changed["summary"] as ObjectNode).put(key, number)
                assertEquals(FrozenEvaluationReportDecoding.SchemaMismatch, decoder.decode(changed.toString().byteInputStream()))
            }
        }
    }

    @Test fun `normalized numeric growth still obeys the shared representation budget`() {
        val token = "9".repeat(126) + "e2"
        assertEquals(128, token.length)
        val input = original().toString().replace("\"build_revision\":1,", "\"build_revision\":$token,")
        val parsed = BoundedJsonReader().read(input.byteInputStream()) as JsonInputResult.Parsed
        assertEquals(132, parsed.value["build_revision"].toString().length)
        assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(input.byteInputStream()))
        val opaque = original()
        evidence(opaque).put("observed", 0)
        val rawOpaque = opaque.toString().replace("\"observed\":0", "\"observed\":$token")
        assertTrue(BoundedJsonReader().read(rawOpaque.byteInputStream()) is JsonInputResult.Parsed)
        assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            decoder.decode(rawOpaque.byteInputStream()))
    }

    @Test fun `all returned collections and opaque JSON survive input and attempted output mutation`() {
        val input = original()
        evidence(input, input.objectNode().put("synthetic-private-report-value", "retained"))
        val report = decode(input)
        val saved = report.json
        val finding = report.findings.first()
        val row = requireNotNull(finding.evidence).first()
        input.put("id", "report.changed")
        first(input).put("message", "changed")
        (first(input)["selection_ids"] as ArrayNode).removeAll()
        (first(input)["evidence"] as ArrayNode).removeAll()
        assertEquals("evaluation.build.fixture.1.r1", report.id)
        assertEquals(4, finding.selectionIds.size)
        assertEquals("rule.required.foundation: pass", finding.message)
        assertEquals("{\"synthetic-private-report-value\":\"retained\"}", row.observed!!.json)
        assertEquals(saved, report.json)
        assertThrows(UnsupportedOperationException::class.java) { (report.findings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (finding.selectionIds as MutableList).add("sel_other") }
        assertThrows(UnsupportedOperationException::class.java) { (finding.evidence as MutableList).clear() }
        for (value in listOf(report, report.summary, finding, row, row.observed,
            FrozenEvaluationReportDecoding.Decoded(report))) {
            assertFalse(value.toString().contains("synthetic-private-report-value"))
            assertFalse(value.toString().contains("rule.required.foundation"))
        }
    }

    @Test fun `exact parser limits apply to both the report and arbitrary evidence`() {
        val text = original().toString()
        val atBytes = text + " ".repeat(1_048_576 - text.toByteArray().size)
        assertEquals(1_048_576, atBytes.toByteArray().size)
        assertTrue(decoder.decode(atBytes.byteInputStream()) is FrozenEvaluationReportDecoding.Decoded)
        assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            decoder.decode((atBytes + " ").byteInputStream()))
        for ((length, accepted) in listOf(16_384 to true, 16_385 to false)) {
            val input = original()
            evidence(input).put("observed", "x".repeat(length))
            val result = decoder.decode(input.toString().byteInputStream())
            if (accepted) assertTrue(result is FrozenEvaluationReportDecoding.Decoded)
            else assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), result)
        }
        for ((depth, accepted) in listOf(27 to true, 28 to false)) {
            val input = original()
            var nested: JsonNode = input.nullNode()
            repeat(depth) { nested = input.arrayNode().add(nested) }
            evidence(input, nested)
            val result = decoder.decode(input.toString().byteInputStream())
            if (accepted) assertTrue(result is FrozenEvaluationReportDecoding.Decoded)
            else assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), result)
        }
        val tooLong = text.replace("\"build_revision\":1,", "\"build_revision\":${"9".repeat(129)},")
        assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(tooLong.byteInputStream()))
    }

    @Test fun `malformed whole documents retain explicit sanitized input outcomes`() {
        val text = original().toString()
        val malformed = listOf(
            "" to JsonRejection.INVALID_JSON,
            "{" to JsonRejection.INVALID_JSON,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            "null" to JsonRejection.UNSUPPORTED_ROOT,
            text + "{}" to JsonRejection.TRAILING_CONTENT,
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            text.replace("\"build_revision\":1,", "\"build_revision\":NaN,") to JsonRejection.INVALID_JSON,
            text.replace("rule.required.foundation: pass", "\\uD800") to JsonRejection.INVALID_JSON,
            "\uFEFF$text" to JsonRejection.INVALID_JSON,
        )
        malformed.forEach { (input, reason) ->
            assertEquals(FrozenEvaluationReportDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
    }

    @Test fun `caller owns every stream and input diagnostics never expose provider text`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val cases = listOf(example(), "{}".toByteArray(), "{".toByteArray(),
            original().put("id", "synthetic-private-report-value invalid").toString().toByteArray())
        for (bytes in cases) {
            val stream = Tracked(bytes)
            assertFalse(decoder.decode(stream).toString().contains("synthetic-private-report-value"))
            assertFalse(stream.closed)
        }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-provider-error")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(FrozenEvaluationReportDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-provider-error"))
    }
}
