package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class ReferenceReportInspectionTest {
    private val mapper = ObjectMapper()
    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun inspect(report: ObjectNode = fixture("evaluation-report"), build: ObjectNode = fixture("build"), rules: ObjectNode = fixture("ruleset")): ReferenceReportView =
        (ReferenceReportAdapter().receive(build.toString().byteInputStream(), rules.toString().byteInputStream(), report.toString().byteInputStream()) as ReportReception.Accepted).inspect()

    @Test fun `projection retains identity and independent outcome severity without reclassification`() {
        val view = inspect()
        assertEquals("build.fixture.1", view.buildId)
        assertEquals(BigInteger.ONE, view.buildRevision)
        assertEquals("catalog.fixture.1", view.catalogSnapshotId)
        assertEquals("needs_review", view.deploymentReadiness)
        assertEquals("valid", view.result)
        assertEquals("pass", view.findings.first().outcome)
        assertEquals("error", view.findings.first().severity)
        assertTrue(view.findings.any { it.outcome == "skipped" })
        val report = fixture("evaluation-report")
        val rules = fixture("ruleset")
        (report["findings"][0] as ObjectNode).put("outcome", "fail").put("severity", "warning")
        (rules["rules"][0] as ObjectNode).put("severity", "warning")
        (report["summary"] as ObjectNode).put("passed", report["summary"]["passed"].asInt() - 1).put("failed", 1)
        val warning = inspect(report, rules = rules).findings.first()
        assertEquals("fail", warning.outcome)
        assertEquals("warning", warning.severity)
    }
    @Test fun `all generated reference cases preserve unknown and skipped separately`() {
        val cases = mapper.readTree(javaClass.getResourceAsStream("/evaluation/reference-reports.json"))
        for (case in cases) {
            val build = fixture("build")
            (build["policy"] as ObjectNode).put("fail_on_unknown", case["strict"].asBoolean())
            val view = inspect(case["report"] as ObjectNode, build)
            assertEquals(case["report"]["findings"].map { it["outcome"].asText() }, view.findings.map { it.outcome })
            assertEquals(case["report"]["deployment_readiness"].asText(), view.deploymentReadiness)
        }
    }
    @Test fun `evidence preserves typed JSON absence null precision and arbitrary revisions`() {
        val report = fixture("evaluation-report")
        val build = fixture("build")
        val revision = BigInteger("18446744073709551617")
        report.put("build_revision", revision); build.put("revision", revision)
        val finding = report["findings"][0] as ObjectNode
        finding.remove("remediation")
        val evidence = finding.putArray("evidence").addObject().put("path", "/fixture/value")
        evidence.putNull("observed"); evidence.set<com.fasterxml.jackson.databind.JsonNode>("expected", mapper.readTree("{\"value\":18446744073709551617}"))
        val view = inspect(report, build)
        assertEquals(revision, view.buildRevision)
        assertNull(view.findings.first().remediation)
        val item = view.findings.first().evidence.single()
        assertEquals("null", item.observedJson)
        assertEquals("{\"value\":18446744073709551617}", item.expectedJson)
        assertNull(item.sourceVersion)
        assertTrue(view.findings[1].evidence.isEmpty())
    }
    @Test fun `projection collections are immutable copies`() {
        val source = fixture("evaluation-report")
        val view = inspect(source)
        (source["findings"][0] as ObjectNode).put("message", "changed later")
        assertNotEquals("changed later", view.findings.first().message)
        assertThrows(UnsupportedOperationException::class.java) { (view.findings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.findings.first().selectionIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.findings.first().evidence as MutableList).clear() }
    }
}
