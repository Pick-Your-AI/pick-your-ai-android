package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ReferenceReportAdapterTest {
    private val mapper = ObjectMapper()
    private val adapter = ReferenceReportAdapter()
    private fun fixture(name: String): ObjectNode = mapper.readTree(
        javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun receive(report: JsonNode = fixture("evaluation-report"), build: JsonNode = fixture("build"),
                        rules: JsonNode = fixture("ruleset")) = adapter.receive(
        build.toString().byteInputStream(), rules.toString().byteInputStream(), report.toString().byteInputStream())
    private fun rejected(reason: ReportRejection, report: JsonNode = fixture("evaluation-report"),
                         build: JsonNode = fixture("build"), rules: JsonNode = fixture("ruleset")) {
        assertEquals(ReportReception.Rejected(reason), receive(report, build, rules))
    }

    @Test fun `original frozen golden is retained as an immutable snapshot`() {
        val report = fixture("evaluation-report")
        val result = receive(report) as ReportReception.Accepted
        report.put("result", "invalid")
        assertEquals("valid", mapper.readTree(result.json)["result"].asText())
        assertEquals("needs_review", mapper.readTree(result.json)["deployment_readiness"].asText())
    }

    @Test fun `Python generated unknown policy and decimal cases are accepted without loss`() {
        val cases = mapper.readTree(javaClass.getResourceAsStream("/evaluation/reference-reports.json"))
        for (case in cases) {
            val build = fixture("build")
            (build["policy"] as ObjectNode).put("fail_on_unknown", case["strict"].asBoolean())
            val result = receive(case["report"], build)
            assertTrue(result is ReportReception.Accepted, case["name"].asText())
            assertEquals(case["report"], mapper.readTree((result as ReportReception.Accepted).json))
        }
    }

    @Test fun `every report identity must match build locks`() {
        for (key in listOf("build_id", "catalog_snapshot_id", "ruleset_id", "dependency_graph_id")) {
            rejected(ReportRejection.BINDING_MISMATCH, fixture("evaluation-report").put(key, "different.fixture"))
        }
        rejected(ReportRejection.BINDING_MISMATCH, fixture("evaluation-report").put("ruleset_version", "1.0.1"))
        for (key in listOf("build_revision", "dependency_graph_revision")) {
            rejected(ReportRejection.BINDING_MISMATCH, fixture("evaluation-report").put(key, 2))
        }
        rejected(ReportRejection.BINDING_MISMATCH, rules = fixture("ruleset").put("id", "different.fixture"))
        rejected(ReportRejection.BINDING_MISMATCH, rules = fixture("ruleset").put("version", "1.0.1"))
    }

    @Test fun `unsupported versions malformed fields and huge revisions fail closed`() {
        assertEquals("UNSUPPORTED_BUILD_SCHEMA_VERSION", (receive(build = fixture("build").put("schema_version", "2.0.0")) as ReportReception.Rejected).reason.name)
        assertEquals("UNSUPPORTED_RULESET_SCHEMA_VERSION", (receive(rules = fixture("ruleset").put("schema_version", "2.0.0")) as ReportReception.Rejected).reason.name)
        assertEquals("UNSUPPORTED_REPORT_SCHEMA_VERSION", (receive(fixture("evaluation-report").put("schema_version", "2.0.0")) as ReportReception.Rejected).reason.name)
        rejected(ReportRejection.INVALID_REPORT, fixture("evaluation-report").put("build_revision", "1"))
        rejected(ReportRejection.INVALID_REPORT, fixture("evaluation-report").put("unexpected", true))
        rejected(ReportRejection.BINDING_MISMATCH, fixture("evaluation-report").put("build_revision", java.math.BigInteger("18446744073709551617")))
    }

    @Test fun `summary result readiness severity and affected selections cannot contradict findings`() {
        val wrongSummary = fixture("evaluation-report")
        (wrongSummary["summary"] as ObjectNode).put("passed", 0)
        rejected(ReportRejection.INCONSISTENT_FINDINGS, wrongSummary)
        rejected(ReportRejection.INCONSISTENT_FINDINGS, fixture("evaluation-report").put("result", "invalid"))
        rejected(ReportRejection.INCONSISTENT_FINDINGS, fixture("evaluation-report").put("deployment_readiness", "ready"))
        for (key in listOf("severity", "rule_id")) {
            val report = fixture("evaluation-report")
            (report["findings"][0] as ObjectNode).put(key, if (key == "severity") "warning" else "rule.other")
            rejected(ReportRejection.INCONSISTENT_FINDINGS, report)
        }
        val report = fixture("evaluation-report")
        (report["findings"][0] as ObjectNode).putArray("selection_ids").add("sel_other")
        rejected(ReportRejection.INCONSISTENT_FINDINGS, report)
    }

    @Test fun `missing duplicate and reordered findings do not masquerade as complete evaluation`() {
        for (operation in listOf("missing", "duplicate", "reordered")) {
            val report = fixture("evaluation-report")
            val findings = report["findings"] as com.fasterxml.jackson.databind.node.ArrayNode
            when (operation) {
                "missing" -> findings.remove(0)
                "duplicate" -> findings.add(findings[0].deepCopy<JsonNode>())
                else -> { val first = findings.remove(0); findings.add(first) }
            }
            rejected(ReportRejection.INCONSISTENT_FINDINGS, report)
        }
    }

    @Test fun `warning failure remains independent of error severity`() {
        val report = fixture("evaluation-report")
        val rules = fixture("ruleset")
        (report["findings"][0] as ObjectNode).put("outcome", "fail").put("severity", "warning")
        (rules["rules"][0] as ObjectNode).put("severity", "warning")
        val summary = report["summary"] as ObjectNode
        summary.put("passed", summary["passed"].asInt() - 1).put("failed", 1)
        assertTrue(receive(report, rules = rules) is ReportReception.Accepted)
        report.put("result", "invalid")
        rejected(ReportRejection.INCONSISTENT_FINDINGS, report, rules = rules)
    }

    @Test fun `integer revision representations compare numerically without narrowing`() {
        val exponentReport = fixture("evaluation-report").toString()
            .replace("\"build_revision\":1,", "\"build_revision\":1e0,")
            .replace("\"dependency_graph_revision\":1,", "\"dependency_graph_revision\":1e0,")
        assertTrue(adapter.receive(fixture("build").toString().byteInputStream(),
            fixture("ruleset").toString().byteInputStream(), exponentReport.byteInputStream()) is ReportReception.Accepted)
        for (representation in listOf("1.0", "1e0", "18446744073709551617")) {
            val build = fixture("build")
            val report = fixture("evaluation-report")
            val number = java.math.BigDecimal(representation)
            build.put("revision", number.toBigIntegerExact())
            (build["locks"] as ObjectNode).put("dependency_graph_revision", number)
            report.put("build_revision", number)
            report.put("dependency_graph_revision", number.toBigIntegerExact())
            assertTrue(receive(report, build) is ReportReception.Accepted, representation)
            report.put("build_revision", number.toBigIntegerExact() + java.math.BigInteger.ONE)
            rejected(ReportRejection.BINDING_MISMATCH, report, build)
            report.put("build_revision", number)
            report.put("dependency_graph_revision", number.toBigIntegerExact() + java.math.BigInteger.ONE)
            rejected(ReportRejection.BINDING_MISMATCH, report, build)
        }
    }

    @Test fun `review unknown follows build policy and error failure takes precedence`() {
        val report = fixture("evaluation-report")
        val rules = fixture("ruleset")
        val build = fixture("build")
        (rules["rules"][0] as ObjectNode).put("on_unknown", "review")
        (report["findings"][0] as ObjectNode).put("outcome", "unknown")
        val summary = report["summary"] as ObjectNode
        summary.put("passed", summary["passed"].asInt() - 1).put("unknown", 1)
        report.put("result", "indeterminate").put("deployment_readiness", "needs_review")
        (build["policy"] as ObjectNode).put("fail_on_unknown", false)
        assertTrue(receive(report, build, rules) is ReportReception.Accepted)
        (build["policy"] as ObjectNode).put("fail_on_unknown", true)
        rejected(ReportRejection.INCONSISTENT_FINDINGS, report, build, rules)
        report.put("deployment_readiness", "blocked")
        assertTrue(receive(report, build, rules) is ReportReception.Accepted)
        (report["findings"][1] as ObjectNode).put("outcome", "fail")
        summary.put("passed", summary["passed"].asInt() - 1).put("failed", 1)
        rejected(ReportRejection.INCONSISTENT_FINDINGS, report, build, rules)
        report.put("result", "invalid")
        assertTrue(receive(report, build, rules) is ReportReception.Accepted)
    }

    @Test fun `malformed stream does not disclose input or close caller stream`() {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream("{secret".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(ReportReception.Rejected(ReportRejection.INVALID_REPORT), adapter.receive(
            fixture("build").toString().byteInputStream(), fixture("ruleset").toString().byteInputStream(), stream))
        assertFalse(closed)
    }

    private enum class VersionInput(val fixtureName: String, val invalid: String, val unsupported: String) {
        BUILD("build", "INVALID_BUILD", "UNSUPPORTED_BUILD_SCHEMA_VERSION"),
        RULESET("ruleset", "INVALID_RULESET", "UNSUPPORTED_RULESET_SCHEMA_VERSION"),
        REPORT("evaluation-report", "INVALID_REPORT", "UNSUPPORTED_REPORT_SCHEMA_VERSION"),
    }

    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var calls = 0
        var consumed = 0
        var closed = false
        var eof = false
        override fun read(): Int {
            calls++
            return super.read().also { if (it < 0) eof = true else consumed++ }
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            calls++
            return super.read(bytes, offset, length).also { if (it < 0) eof = true else consumed += it }
        }
        override fun close() { closed = true; super.close() }
    }

    private fun versionBytes(input: VersionInput, version: String = "opaque.private.version"): ByteArray =
        fixture(input.fixtureName).put("schema_version", version).toString().toByteArray()

    private fun streams(input: VersionInput, bytes: ByteArray): List<TrackedInput> =
        VersionInput.entries.map { TrackedInput(if (it == input) bytes else fixture(it.fixtureName).toString().toByteArray()) }

    private fun receiveStreams(inputs: List<InputStream>): ReportReception = adapter.receive(inputs[0], inputs[1], inputs[2])

    private fun assertInputRejection(input: VersionInput, bytes: ByteArray, expected: String) {
        val inputs = streams(input, bytes)
        val result = assertInstanceOf(ReportReception.Rejected::class.java, receiveStreams(inputs))
        assertEquals(expected, result.reason.name)
        inputs.drop(input.ordinal + 1).forEach { assertEquals(0, it.calls, "later input stays unread") }
        assertTrue(inputs.none { it.closed })
        assertTrue(inputs.all { it.consumed <= 1_048_577 })
        assertFalse(result.toString().contains("opaque.private"))
        assertFalse(result.reason.toString().contains("opaque.private"))
    }

    @TestFactory fun `unsupported textual versions identify each supplied record without disclosure`() =
        VersionInput.entries.flatMap { input ->
            listOf("old" to "0.9.0", "minor" to "1.1.0", "major" to "2.0.0", "empty" to "",
                "terminal LF" to "1.0.0\n", "opaque" to "opaque.private.version").map { (label, version) ->
                DynamicTest.dynamicTest("${input.name}: $label version") {
                    assertInputRejection(input, versionBytes(input, version), input.unsupported)
                }
            }
        }

    @TestFactory fun `missing and nontext versions remain invalid for each input`() =
        VersionInput.entries.flatMap { input ->
            listOf("missing", "null", "true", "1", "1.0", "[]", "{}").map { value ->
                DynamicTest.dynamicTest("${input.name}: $value version field") {
                    val node = fixture(input.fixtureName)
                    if (value == "missing") node.remove("schema_version")
                    else node.set<JsonNode>("schema_version", mapper.readTree(value))
                    assertInputRejection(input, node.toString().toByteArray(), input.invalid)
                }
            }
        }

    @TestFactory fun `complete strict parsing takes precedence over a recognized unsupported version`() =
        VersionInput.entries.flatMap { input ->
            val unsupported = versionBytes(input).toString(Charsets.UTF_8)
            val duplicate = unsupported.replaceFirst("{", "{\"schema_version\":\"1.0.0\",")
            val reversed = unsupported.dropLast(1) + ",\"schema_version\":\"1.0.0\"}"
            val controls = listOf(
                "duplicate version" to duplicate.toByteArray(),
                "reversed duplicate" to reversed.toByteArray(),
                "nested duplicate" to "{\"schema_version\":\"2.0.0\",\"x\":{\"a\":1,\"a\":2}}".toByteArray(),
                "trailing object" to (unsupported + "{}").toByteArray(),
                "trailing private text" to (unsupported + "opaque.private.trailing").toByteArray(),
                "truncated object" to unsupported.dropLast(1).toByteArray(),
                "invalid UTF-8" to (unsupported.toByteArray() + byteArrayOf(0xc3.toByte())),
                "unpaired surrogate" to "{\"schema_version\":\"2.0.0\",\"x\":\"\\ud800\"}".toByteArray(),
                "string budget" to ("{\"schema_version\":\"2.0.0\",\"x\":\"" + "x".repeat(16_385) + "\"}").toByteArray(),
                "name budget" to ("{\"schema_version\":\"2.0.0\",\"" + "x".repeat(257) + "\":0}").toByteArray(),
                "number budget" to ("{\"schema_version\":\"2.0.0\",\"x\":" + "1".repeat(129) + "}").toByteArray(),
                "depth budget" to ("{\"schema_version\":\"2.0.0\",\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray(),
                "token budget" to ("{\"schema_version\":\"2.0.0\",\"x\":[" + List(100_001) { "0" }.joinToString(",") + "]}").toByteArray(),
                "byte budget after complete object" to (unsupported + " ".repeat(1_048_577 - unsupported.toByteArray().size)).toByteArray(),
                "array root" to ("[" + unsupported + "]").toByteArray(),
            )
            controls.map { (label, bytes) -> DynamicTest.dynamicTest("${input.name}: $label before version") {
                assertInputRejection(input, bytes, input.invalid)
            } }
        }

    @TestFactory fun `provider failure at EOF remains invalid and caller streams stay open`() =
        VersionInput.entries.map { input -> DynamicTest.dynamicTest("${input.name}: provider failure before EOF") {
            val originals = streams(input, versionBytes(input))
            val bytes = ByteArrayInputStream(versionBytes(input))
            var closed = false
            val failing = object : InputStream() {
                override fun read(): Int = bytes.read().also { if (it < 0) throw IOException("opaque.private.provider") }
                override fun read(target: ByteArray, offset: Int, length: Int): Int =
                    bytes.read(target, offset, length).also { if (it < 0) throw IOException("opaque.private.provider") }
                override fun close() { closed = true }
            }
            val result = assertInstanceOf(ReportReception.Rejected::class.java,
                receiveStreams(originals.mapIndexed { index, stream -> if (index == input.ordinal) failing else stream }))
            assertEquals(input.invalid, result.reason.name)
            assertFalse(result.toString().contains("opaque.private"))
            assertFalse(closed)
            assertTrue(originals.none { it.closed })
            originals.drop(input.ordinal + 1).forEach { assertEquals(0, it.calls) }
        } }

    @TestFactory fun `supported versions still require the full fixed schema`() =
        VersionInput.entries.flatMap { input ->
            listOf("extra field", "missing ID", "wrong ID type", "wrong schema field").map { label ->
                DynamicTest.dynamicTest("${input.name}: $label at supported version") {
                    val node = fixture(input.fixtureName)
                    when (label) {
                        "extra field" -> node.put("opaque.private.extra", true)
                        "missing ID" -> node.remove("id")
                        "wrong ID type" -> node.put("id", 1)
                        else -> when (input) {
                            VersionInput.BUILD -> node.put("revision", "1")
                            VersionInput.RULESET -> node.put("version", 1)
                            VersionInput.REPORT -> node.put("result", "opaque.private.result")
                        }
                    }
                    assertInputRejection(input, node.toString().toByteArray(), input.invalid)
                }
            }
        }

    @TestFactory fun `an exactly full byte budget remains admissible for each supported record`() =
        VersionInput.entries.map { input -> DynamicTest.dynamicTest("${input.name}: exact positive byte budget") {
            val bytes = fixture(input.fixtureName).toString().toByteArray()
            val full = bytes + ByteArray(1_048_576 - bytes.size) { ' '.code.toByte() }
            val inputs = streams(input, full)
            assertTrue(receiveStreams(inputs) is ReportReception.Accepted)
            assertTrue(inputs.all { it.eof && !it.closed })
            assertEquals(1_048_576, inputs[input.ordinal].consumed)
        } }

    @Test fun `admission order preserves every later stream and precedes semantic binding`() {
        val allUnsupported = VersionInput.entries.map { TrackedInput(versionBytes(it)) }
        assertEquals("UNSUPPORTED_BUILD_SCHEMA_VERSION", (receiveStreams(allUnsupported) as ReportReception.Rejected).reason.name)
        assertEquals(0, allUnsupported[1].calls)
        assertEquals(0, allUnsupported[2].calls)
        val badRules = listOf(TrackedInput(fixture("build").toString().toByteArray()),
            TrackedInput(versionBytes(VersionInput.RULESET)), TrackedInput("{opaque.private".toByteArray()))
        assertEquals("UNSUPPORTED_RULESET_SCHEMA_VERSION", (receiveStreams(badRules) as ReportReception.Rejected).reason.name)
        assertEquals(0, badRules[2].calls)
        val wrongBinding = fixture("build")
        (wrongBinding["locks"] as ObjectNode).put("ruleset_id", "different.fixture")
        val result = receive(build = wrongBinding, report = fixture("evaluation-report").put("schema_version", "2.0.0"))
        assertEquals("UNSUPPORTED_REPORT_SCHEMA_VERSION", (result as ReportReception.Rejected).reason.name)
        assertTrue((allUnsupported + badRules).none { it.closed })
    }

    @Test fun `the five existing rejection names keep their enum positions`() {
        assertEquals(listOf("INVALID_BUILD", "INVALID_RULESET", "INVALID_REPORT", "BINDING_MISMATCH", "INCONSISTENT_FINDINGS"),
            ReportRejection.entries.take(5).map { it.name })
    }
}
