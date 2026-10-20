package ai.pickyour.android.preview

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import ai.pickyour.core.evaluation.ReferenceReportAdapter
import ai.pickyour.core.evaluation.ReportReception
import ai.pickyour.core.evaluation.inspect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/** Simulated native resources; no installed accessibility or evaluation-correctness claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FindingsInspectionTextTest {
    private fun context(locale: Locale = Locale.US): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(locale))
        return app.createConfigurationContext(config)
    }
    private fun fixture(name: String): JSONObject = context().assets.open("$name.json")
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun JSONObject.putArray(name: String) = JSONArray().also { put(name, it) }
    private fun receive(report: JSONObject = fixture("evaluation-report"), build: JSONObject = fixture("build"),
                        rules: JSONObject = fixture("ruleset")): ReportReception.Accepted {
        val result = ReferenceReportAdapter().receive(build.toString().byteInputStream(),
            rules.toString().byteInputStream(), report.toString().byteInputStream())
        assertTrue("Fixture must pass the unchanged receiver before presentation", result is ReportReception.Accepted)
        return result as ReportReception.Accepted
    }

    @Test fun recordedEmptyRemediationDoesNotBecomeAnAbsentValue() {
        for ((raw, display) in listOf(null to "Not supplied", "" to "\"\"", "Not supplied" to "\"Not supplied\"")) {
            val report = fixture("evaluation-report")
            report.getJSONArray("findings").getJSONObject(0).put("remediation", raw)
            val accepted = receive(report)
            val before = accepted.json
            val finding = accepted.inspect().findings.first()
            val text = FindingsInspectionText.describe(context(), finding)
            assertTrue(text.contains("Reported remediation (not executed): $display\n"))
            assertEquals(raw, finding.remediation)
            assertEquals(before, accepted.json)
        }
    }

    @Test fun absentValuesJsonNullStringsAndWideNumbersKeepDistinctMeaning() {
        val report = fixture("evaluation-report")
        val finding = report.getJSONArray("findings").getJSONObject(0)
        val evidence = finding.putArray("evidence")
        evidence.put(JSONObject().put("path", "/null").put("observed", JSONObject.NULL).put("expected", "null"))
        evidence.put(JSONObject().put("path", "").put("source_version", "")
            .put("observed", BigInteger("18446744073709551617"))
            .put("expected", BigDecimal("0.12345678901234567890123456789")))
        evidence.put(JSONObject().put("path", "/missing").put("expected", false).put("source_version", "Not supplied"))
        val accepted = receive(report)
        val before = accepted.json
        val view = accepted.inspect().findings.first()
        val text = FindingsInspectionText.describe(context(), view)
        assertTrue(text.contains("Observed JSON: \"null\"\nExpected JSON: \"\\\"null\\\"\""))
        assertTrue(text.contains("Reported evidence path: \"\""))
        assertTrue(text.contains("Observed JSON: \"18446744073709551617\""))
        assertTrue(text.contains("Expected JSON: \"0.12345678901234567890123456789\""))
        assertTrue(text.contains("Observed JSON: Not supplied\nExpected JSON: \"false\""))
        assertTrue(text.contains("Reported source version: \"\""))
        assertTrue(text.contains("Reported source version: \"Not supplied\""))
        assertEquals("null", view.evidence[0].observedJson)
        assertEquals("\"null\"", view.evidence[0].expectedJson)
        assertNull(view.evidence[0].sourceVersion)
        assertEquals("", view.evidence[1].sourceVersion)
        assertNull(view.evidence[2].observedJson)
        assertEquals(before, accepted.json)
    }

    @Test fun hostileSourceCannotInjectLabelsAndLongValuesDiscloseTruncation() {
        val report = fixture("evaluation-report")
        val finding = report.getJSONArray("findings").getJSONObject(0)
        val explanation = "source\u2028Outcome: unknown\u2029Severity: info\n\t\r\u202e"
        finding.put("message", explanation).put("remediation", "x".repeat(500))
        finding.putArray("evidence").put(JSONObject().put("path", "/source\\\"\u2066x\u2069")
            .put("source_version", "version\u2028Injected label\u2029last"))
        val accepted = receive(report)
        val before = accepted.json
        val view = accepted.inspect().findings.first()
        val text = FindingsInspectionText.describe(context(), view)
        assertTrue(text.contains("Outcome: pass · Severity: error"))
        assertTrue(text.contains("source\\u2028Outcome: unknown\\u2029Severity: info\\n\\t\\r\\u202e"))
        assertTrue(text.contains("\\u2066x\\u2069"))
        assertTrue(text.contains("version\\u2028Injected label\\u2029last"))
        for (control in listOf('\u2028', '\u2029', '\u202e', '\u2066', '\u2069')) assertFalse(text.contains(control))
        assertTrue(text.contains("[display truncated; full recorded value retained]"))
        assertFalse(text.contains("x".repeat(161)))
        assertEquals(explanation, view.message)
        assertEquals("x".repeat(500), view.remediation)
        assertEquals(before, accepted.json)
    }

    @Test fun nestedDisplayCapsDiscloseAllRetainedEvidenceAndSelectionCounts() {
        val report = fixture("evaluation-report")
        val build = fixture("build")
        val rules = fixture("ruleset")
        val selections = build.getJSONArray("selections")
        val workload = selections.getJSONObject(0)
        repeat(9) { selections.put(JSONObject(workload.toString()).put("id", "sel_workload_extra_$it")) }
        val findings = report.getJSONArray("findings")
        // Keep every reference binding internally consistent; this does not execute those rules.
        for (index in 0 until findings.length()) {
            val kinds = rules.getJSONArray("rules").getJSONObject(index).getJSONArray("affected_kinds")
            val affected = (0 until kinds.length()).map(kinds::getString).toSet()
            val ids = (0 until selections.length()).map(selections::getJSONObject)
                .filter { it.getBoolean("enabled") && it.getString("kind") in affected }
                .map { it.getString("id") }.sorted()
            findings.getJSONObject(index).put("selection_ids", JSONArray(ids))
        }
        findings.getJSONObject(0).putArray("evidence").apply {
            repeat(10) { put(JSONObject().put("path", "/entry-$it").put("observed", it)) }
        }
        val accepted = receive(report, build, rules)
        val before = accepted.json
        val view = accepted.inspect().findings.first()
        val text = FindingsInspectionText.describe(context(), view)
        assertEquals(13, view.selectionIds.size)
        assertEquals(10, view.evidence.size)
        assertTrue(text.contains("Selection IDs displayed: 8 / 13 retained IDs"))
        assertTrue(text.contains("Evidence entries displayed: 8 / 10 retained entries"))
        assertTrue(text.contains("\"${view.selectionIds[7]}\""))
        assertFalse(text.contains("\"${view.selectionIds[8]}\""))
        assertTrue(text.contains("\"/entry-7\""))
        assertFalse(text.contains("\"/entry-8\""))
        assertEquals(before, accepted.json)
    }

    @Test fun summaryCountsAllFindingsAndNeverConvertsWarningFailureOrUnknownToPass() {
        for (outcome in listOf("fail", "unknown")) {
            val report = fixture("evaluation-report")
            val rules = fixture("ruleset")
            val build = fixture("build")
            val totals = report.getJSONObject("summary")
            val expectedPass = totals.getInt("passed") - 1
            totals.put("passed", expectedPass).put(if (outcome == "fail") "failed" else "unknown", 1)
            report.getJSONArray("findings").getJSONObject(0).put("outcome", outcome).put("severity", "warning")
            rules.getJSONArray("rules").getJSONObject(0).put("severity", "warning").put("on_unknown", "review")
            build.getJSONObject("policy").put("fail_on_unknown", false)
            if (outcome == "unknown") report.put("result", "indeterminate")
            val accepted = receive(report, build, rules)
            val before = accepted.json
            val view = accepted.inspect()
            val summary = FindingsInspectionText.describe(context(), view)
            val expectedCounts = "pass: $expectedPass · fail: ${if (outcome == "fail") 1 else 0} · " +
                "unknown: ${if (outcome == "unknown") 1 else 0} · skipped: ${totals.getInt("skipped")}"
            assertTrue(summary.contains(expectedCounts))
            assertTrue(summary.contains("Reported readiness: needs_review"))
            assertTrue(FindingsInspectionText.row(context(), view.findings.first()).contains("Outcome: $outcome · Severity: warning"))
            assertTrue(summary.contains(context().getString(R.string.findings_limits)))
            assertEquals(45, view.findings.size)
            assertEquals(before, accepted.json)
        }
    }

    @Test fun literalUnknownIdentityAndWideRevisionRemainExactInSummaryAndTitle() {
        val report = fixture("evaluation-report").put("id", "unknown")
        val rules = fixture("ruleset")
        val build = fixture("build")
        val revision = BigInteger("1844674407370955161718446744073709551617")
        report.put("build_revision", revision)
        build.put("revision", revision)
        report.getJSONArray("findings").getJSONObject(0).put("rule_id", "unknown")
        rules.getJSONArray("rules").getJSONObject(0).put("id", "unknown")
        val view = receive(report, build, rules).inspect()
        val summary = FindingsInspectionText.describe(context(), view)
        assertTrue(summary.contains("Report: \"unknown\""))
        assertTrue(summary.contains("revision \"$revision\""))
        assertEquals("\"unknown\"", FindingsInspectionText.title(context(), view.findings.first()))
        assertEquals(revision, view.buildRevision)
    }

    @Test fun rtlScreenAddsOnlyTrustedDirectionControlsAroundEscapedSource() {
        val report = fixture("evaluation-report")
        val source = "مرحبا\u202e"
        report.getJSONArray("findings").getJSONObject(0).put("remediation", source)
        val accepted = receive(report)
        val before = accepted.json
        val view = accepted.inspect().findings.first()
        val text = FindingsInspectionText.describe(context(Locale.forLanguageTag("ar-EG")), view)
        assertTrue(text.contains("مرحبا\\u202e"))
        assertFalse(text.contains('\u202e'))
        assertTrue(text.contains('\u202a'))
        assertEquals(source, view.remediation)
        assertEquals(before, accepted.json)
    }
}
