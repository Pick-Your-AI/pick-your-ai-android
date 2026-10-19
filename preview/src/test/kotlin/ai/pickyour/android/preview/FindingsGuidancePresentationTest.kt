package ai.pickyour.android.preview

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.widget.TextView
import ai.pickyour.core.evaluation.DiagnosticEvaluation
import ai.pickyour.core.evaluation.FrozenDiagnosticEvaluator
import ai.pickyour.core.evaluation.ReferenceFindingView
import ai.pickyour.core.evaluation.ReportReception
import ai.pickyour.core.evaluation.inspect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Controlled frozen diagnostic reports and native text; no current-draft or installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FindingsGuidancePresentationTest {
    private fun context(): Context {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList(Locale.US))
        return app.createConfigurationContext(configuration)
    }

    private fun fixture(name: String): JSONObject = context().assets.open("$name.json")
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun evaluate(missingWorkload: Boolean = false, providerConflict: Boolean = false): ReportReception.Accepted {
        val build = fixture("build")
        val catalog = fixture("catalog")
        val rules = fixture("ruleset")
        val selections = build.getJSONArray("selections")
        fun selection(kind: String): JSONObject = (0 until selections.length()).map(selections::getJSONObject)
            .single { it.getString("kind") == kind && it.getString("role") == "primary" }
        if (missingWorkload) selection("workload").put("enabled", false)
        if (providerConflict) {
            val provider = selection("model_provider")
            val items = catalog.getJSONArray("items")
            val original = (0 until items.length()).map(items::getJSONObject)
                .single { it.getString("id") == provider.getString("catalog_item_id") }
            val alternative = JSONObject(original.toString()).put("id", "provider.provisional-other")
            items.put(alternative)
            provider.put("catalog_item_id", alternative.getString("id"))
        }
        val inputs = listOf(build.toString(), catalog.toString(), rules.toString())
        val result = FrozenDiagnosticEvaluator().evaluate(inputs[0].byteInputStream(),
            inputs[1].byteInputStream(), inputs[2].byteInputStream())
        assertTrue("The existing bounded evaluator must produce an admitted report: $result",
            result is DiagnosticEvaluation.Evaluated)
        assertEquals(inputs, listOf(build.toString(), catalog.toString(), rules.toString()))
        val accepted = (result as DiagnosticEvaluation.Evaluated).report
        val view = accepted.inspect()
        assertEquals(45, view.findings.size)
        assertEquals(if (missingWorkload) "fail" else "pass",
            view.findings.single { it.ruleId == "rule.required.foundation" }.outcome)
        assertEquals(if (providerConflict) "fail" else "pass",
            view.findings.single { it.ruleId == "rule.model.provider.match" }.outcome)
        assertTrue(view.deploymentReadiness in setOf("blocked", "needs_review"))
        return accepted
    }

    private fun render(accepted: ReportReception.Accepted, id: String): Pair<ReferenceFindingView, String> {
        val before = accepted.json
        val finding = accepted.inspect().findings.single { it.ruleId == id }
        val text = TextView(context()).apply {
            text = FindingsInspectionText.describe(context(), finding)
        }.text.toString()
        assertTrue(text.contains("Outcome: ${finding.outcome} · Severity: ${finding.severity}"))
        assertEquals(before, accepted.json)
        return finding to text
    }

    @Test fun canonicalUnknownSkippedAndSeverityRemainIndependent() {
        val accepted = evaluate()
        val original = accepted.json
        for (outcome in listOf("pass", "unknown", "skipped")) {
            val finding = accepted.inspect().findings.first { it.outcome == outcome }
            val rendered = render(accepted, finding.ruleId).second
            assertFalse(rendered.contains("Guidance: Incomplete (provisional)"))
            assertFalse(rendered.contains("Guidance: Reported incompatibility (provisional)"))
        }
        assertEquals(original, accepted.json)
    }

    @Test fun missingRequiredChoiceHasIncompleteGuidanceWithoutChangingItsFailure() {
        val accepted = evaluate(missingWorkload = true)
        val (finding, rendered) = render(accepted, "rule.required.foundation")
        assertEquals("fail", finding.outcome)
        assertEquals("error", finding.severity)
        assertTrue(rendered.contains("Guidance: Incomplete (provisional)"))
        assertFalse(rendered.contains("Guidance: Reported incompatibility (provisional)"))
    }

    @Test fun presentConflictingComponentsHaveReportedIncompatibilityGuidance() {
        val accepted = evaluate(providerConflict = true)
        val (finding, rendered) = render(accepted, "rule.model.provider.match")
        assertEquals("fail", finding.outcome)
        assertEquals("error", finding.severity)
        assertTrue(rendered.contains("Guidance: Reported incompatibility (provisional)"))
        assertFalse(rendered.contains("Guidance: Incomplete (provisional)"))
    }

    @Test fun mixedMissingChoiceAndComponentConflictKeepSeparateGuidance() {
        val accepted = evaluate(missingWorkload = true, providerConflict = true)
        val incomplete = render(accepted, "rule.required.foundation")
        val incompatible = render(accepted, "rule.model.provider.match")
        assertEquals("fail", incomplete.first.outcome)
        assertEquals("fail", incompatible.first.outcome)
        assertTrue(incomplete.second.contains("Guidance: Incomplete (provisional)"))
        assertTrue(incompatible.second.contains("Guidance: Reported incompatibility (provisional)"))
    }
}
