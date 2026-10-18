package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
        rejected(ReportRejection.INVALID_BUILD, build = fixture("build").put("schema_version", "2.0.0"))
        rejected(ReportRejection.INVALID_RULESET, rules = fixture("ruleset").put("schema_version", "2.0.0"))
        rejected(ReportRejection.INVALID_REPORT, fixture("evaluation-report").put("schema_version", "2.0.0"))
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
}
