package ai.pickyour.android.preview

import android.app.AlertDialog
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.evaluation.DraftRuleFinding
import ai.pickyour.core.evaluation.UnknownInputObservation
import ai.pickyour.core.preview.PreviewCurrentDraftAnalysis
import ai.pickyour.core.preview.PreviewCurrentDraftState
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewStoredDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real saved-input admission and evaluator results; synthetic rules are not product evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en +ldltr")
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftFindingOutcomeActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val unknownMeaning = "Outcome meaning: The local diagnostic did not determine a pass or fail for this rule. " +
        "This is not a pass or proof of readiness."
    private val skippedMeaning = "Outcome meaning: This rule was not evaluated for these captured inputs. " +
        "A skipped result is not a pass or proof of readiness."
    private val applicabilityPhase = "Unknown phase: Whether this rule applies could not be determined. Its assertion was not evaluated. " +
        "This phase does not identify a cause or a feasible next action."
    private val assertionPhase = "Unknown phase: This rule applies, but its assertion did not determine a pass or fail. " +
        "This phase does not identify a cause or a feasible next action."
    private val noSelectionInputs = "Observed input limitations for this unknown result:\n" +
        "No enabled selection matched the local read.\nThese observations are local input facts, not a complete cause or a verified remedy."
    private val combinedInputs = "Observed input limitations for this unknown result:\n" +
        "A referenced value was absent from the captured inputs.\n" +
        "A supplied value was outside the supported operand domain.\n" +
        "The local profile does not supply an evaluated metric.\nThese observations are local input facts, not a complete cause or a verified remedy."
    private val suppliedMessage = "pass Ready unknown skipped\n\u202equoted \\\" observation"
    private val suppliedGuidance = "Recorded only: fail pass\t\u2066no promised fix\\"

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }

    @After fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun worker() = field<ExecutorService>("worker")
    private fun state(): PreviewCurrentDraftState {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return (CurrentDraftCheckSection::class.java.getDeclaredField("analysis").apply { isAccessible = true }
            .get(section) as PreviewCurrentDraftAnalysis).state
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the actual current diagnostic")
    }
    private fun finishWork() {
        repeat(5) { idle(); worker().submit {}.get(25, TimeUnit.SECONDS); idle() }
    }
    private fun loaded(): PreviewStoredDraft {
        val result = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()
        assertTrue("The actual saved draft must still load", result is PreviewDraftLoad.Available)
        return (result as PreviewDraftLoad.Available).record
    }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun fixture(name: String) = activity.assets.open("$name.json").use { it.readBytes() }
    private fun trueExpression() = JSONObject()
        .put("op", "eq").put("left", JSONObject().put("literal", true))
        .put("right", JSONObject().put("literal", true))

    /**
     * Change one explicitly synthetic rule through the existing validated input seam. The real
     * worker and canonical before/after stored reads produce Current; no result/state is injected.
     */
    private fun admitted(ruleId: String, expectedOutcome: String, severity: String,
        change: (JSONObject) -> Unit = {}): PreviewCurrentDraftState.Current {
        val saved = field<PreviewStoredDraft>("savedRecord")
        assertTrue("The fixture has no selected deployment/model/foundation components", saved.components.selections.isEmpty())
        val before = disk()
        val prior = state()
        val rules = JSONObject(String(fixture("ruleset"), Charsets.UTF_8))
        val rows = rules.getJSONArray("rules")
        val target = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == ruleId }
        target.put("severity", severity).put("message", suppliedMessage).put("remediation", suppliedGuidance)
        change(target)
        val bytes = rules.toString().toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= DraftCurrentInputs.MAX_BYTES)
        val inputs = requireNotNull(DraftCurrentInputs.read(fixture("catalog"), fixture("dependency-graph"), bytes))
        val rulesHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(rulesHash, inputs.rulesetHash.hex)
        PreviewActivity::class.java.getDeclaredField("currentCheckInputs").apply { isAccessible = true }.set(activity, inputs)
        PreviewActivity::class.java.getDeclaredMethod("updateCurrentChecks").apply { isAccessible = true }.invoke(activity)
        await { state() is PreviewCurrentDraftState.Current && state() !== prior }
        finishWork()
        val current = state() as PreviewCurrentDraftState.Current
        val finding = current.outcome.findings.single { it.ruleId == ruleId }
        assertEquals("The real evaluator must establish the requested fixture outcome", expectedOutcome, finding.outcome)
        assertEquals(severity, finding.severity)
        assertEquals(if (expectedOutcome in listOf("unknown", "fail")) suppliedMessage else "$ruleId: $expectedOutcome", finding.message)
        assertEquals(if (expectedOutcome in listOf("unknown", "fail")) suppliedGuidance else "", finding.remediation)
        assertTrue(finding.selectionIds.isEmpty())
        assertEquals(45, current.outcome.findings.size)
        assertEquals(inputs.rulesetHash, current.request.binding.rulesetHash)
        assertEquals(inputs.catalogHash, current.request.binding.catalogHash)
        assertEquals(inputs.graphHash, current.request.binding.graphHash)
        assertEquals(saved.revision, current.request.binding.sourceRevision)
        assertEquals(saved.baselineIdentity.substringAfter(':'), current.request.binding.sourceHash.hex)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.baselineIdentity, field<PreviewStoredDraft>("savedRecord").baselineIdentity)
        assertEquals(before, disk())
        return current
    }

    private fun detail(current: PreviewCurrentDraftState.Current, index: Int): AlertDialog {
        assertSame(current, state())
        assertTrue(index in current.outcome.findings.indices)
        val inspect = activity.findViewById<Button>(R.id.current_draft_check_inspect)
        assertTrue(inspect.isEnabled)
        inspect.performClick(); idle()
        var page = dialog()
        var offset = 0
        while (index >= offset + 10) {
            page.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            val next = dialog()
            assertNotSame(page, next)
            page = next; offset += 10
        }
        assertTrue(page.isShowing)
        assertTrue(page.listView.adapter.getItem(index - offset).toString().contains(current.outcome.findings[index].ruleId))
        shadowOf(page).clickOnItem(index - offset); idle()
        return dialog().also { assertNotSame(page, it); assertTrue(it.isShowing) }
    }
    private fun body(window: AlertDialog) = window.findViewById<TextView>(android.R.id.message).text.toString()
    private fun literal(raw: String) = BidiFormatter.getInstance(false)
        .unicodeWrap(CatalogDetailText.literal(activity, raw), TextDirectionHeuristics.LTR)
    private fun previousDetail(row: DraftRuleFinding) = activity.getString(R.string.current_check_finding,
        literal(row.ruleId), literal(row.outcome), literal(row.severity), literal(row.message),
        literal(row.remediation), row.selectionIds.joinToString(", ", transform = ::literal))
    private fun decodedField(text: String, label: String): String {
        val value = text.lineSequence().single { it.startsWith("$label: ") }.substringAfter(": ")
        // Only the renderer's directional wrappers may surround the quoted JSON literal.
        val quoted = value.trim('\u200f', '\u202a', '\u202c')
        assertTrue(quoted.startsWith('"') && quoted.endsWith('"'))
        return JSONArray("[$quoted]").getString(0)
    }

    private fun verifyMeaning(current: PreviewCurrentDraftState.Current, ruleId: String, explanation: String?,
        phase: String? = null, inputText: String? = null, inputs: List<UnknownInputObservation> = emptyList(),
    ) {
        val saved = loaded().baselineIdentity
        val before = disk()
        val binding = current.request.binding.copy()
        val rows = current.outcome.findings
        val counts = listOf("pass", "fail", "unknown", "skipped").map { outcome -> rows.count { it.outcome == outcome } }
        assertEquals(rows.size, counts.sum())
        val summary = activity.findViewById<TextView>(R.id.current_draft_check_status)
        val expectedSummary = activity.getString(R.string.current_check_current, binding.sourceRevision.toString(),
            counts[0], counts[1], counts[2], counts[3])
        assertEquals(expectedSummary, summary.text.toString())
        val index = rows.indexOfFirst { it.ruleId == ruleId }
        val row = rows[index]
        if (row.outcome != "unknown") assertNull(current.outcome.unknownPhase(ruleId))
        assertEquals(inputs, current.outcome.unknownInputObservations(ruleId))
        val window = detail(current, index)
        val previous = previousDetail(row)
        val text = body(window)
        assertTrue("Every byte of the original recorded detail must remain the prefix", text.startsWith(previous))
        assertEquals(row.ruleId, decodedField(text, "Rule"))
        assertEquals(row.outcome, decodedField(text, "Outcome"))
        assertEquals(row.severity, decodedField(text, "Severity"))
        assertEquals(row.message, decodedField(text, "Observation"))
        assertEquals(row.remediation, decodedField(text, "Recorded guidance"))
        assertEquals("Close", window.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Back to observations", window.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Full text", window.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertTrue(views(window.window!!.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == "Inspect affected selections" }.isEnabled)
        assertSame(current, state())
        assertEquals(binding, current.request.binding)
        assertEquals(expectedSummary, summary.text.toString())
        assertEquals(saved, loaded().baselineIdentity)
        assertEquals(before, disk())

        val meaning = if (explanation == null) previous else "$previous\n\n$explanation"
        val phased = if (phase == null) meaning else "$meaning\n\n$phase"
        val expected = if (inputText == null) phased else "$phased\n\n$inputText"
        assertEquals("Meaning, phase and exact captured input observations must preserve the recorded prefix", expected, text)

        window.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val page = dialog()
        assertNotSame(window, page)
        shadowOf(page).clickOnItem(index % 10); idle()
        val reopened = dialog()
        assertEquals(expected, body(reopened))
        reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertFalse(reopened.isShowing)
        val fresh = detail(current, index)
        assertEquals(expected, body(fresh))
        fresh.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertSame(current, state())
        assertEquals(binding, current.request.binding)
        assertEquals(expectedSummary, summary.text.toString())
        assertEquals(saved, loaded().baselineIdentity)
        assertEquals(before, disk())
    }

    @Test fun unresolvedApplicabilityExplainsUnknownWithoutRewritingWarningOrRecordedText() {
        val id = "rule.model.serving.mode.selfhost"
        // The unchanged applicability compares a missing primary deployment mode with self_hosted.
        val current = admitted(id, "unknown", "warning")
        assertEquals(ai.pickyour.core.evaluation.DraftCurrentUnknownPhase.APPLICABILITY, current.outcome.unknownPhase(id))
        verifyMeaning(current, id, unknownMeaning, applicabilityPhase, noSelectionInputs,
            listOf(UnknownInputObservation.NO_MATCHING_SELECTION))

        val before = disk()
        val oldWindow = detail(current, current.outcome.findings.indexOfFirst { it.ruleId == id })
        val oldBack = oldWindow.getButton(AlertDialog.BUTTON_POSITIVE)
        val oldFullText = oldWindow.getButton(AlertDialog.BUTTON_NEUTRAL)
        val replacement = admitted(id, "unknown", "warning") { it.put("applies_when", trueExpression()) }
        assertNotSame(current, replacement)
        assertNotEquals(current.request.binding.rulesetHash, replacement.request.binding.rulesetHash)
        assertFalse(oldWindow.isShowing)
        assertEquals(ai.pickyour.core.evaluation.DraftCurrentUnknownPhase.ASSERTION, replacement.outcome.unknownPhase(id))
        val fresh = detail(replacement, replacement.outcome.findings.indexOfFirst { it.ruleId == id })
        val row = replacement.outcome.findings.single { it.ruleId == id }
        val expected = previousDetail(row) + "\n\n" + unknownMeaning + "\n\n" + assertionPhase + "\n\n" + noSelectionInputs
        assertEquals(listOf(UnknownInputObservation.NO_MATCHING_SELECTION), replacement.outcome.unknownInputObservations(id))
        assertEquals(expected, body(fresh))
        assertFalse(body(fresh).contains(applicabilityPhase))
        oldBack.performClick(); oldFullText.performClick(); idle()
        assertSame(fresh, dialog())
        assertTrue(fresh.isShowing)
        assertEquals(expected, body(fresh))
        assertSame(replacement, state())
        assertEquals(before, disk())
        fresh.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
    }

    @Test fun unresolvedEligibleAssertionHasTheSameUnknownMeaningWithoutInferringACause() {
        val id = "rule.model.serving.mode.selfhost"
        // Explicit synthetic applicability is true; the unchanged assertion has no selected model.
        val current = admitted(id, "unknown", "info") { it.put("applies_when", trueExpression()) }
        assertEquals(ai.pickyour.core.evaluation.DraftCurrentUnknownPhase.ASSERTION, current.outcome.unknownPhase(id))
        verifyMeaning(current, id, unknownMeaning, assertionPhase, noSelectionInputs,
            listOf(UnknownInputObservation.NO_MATCHING_SELECTION))
    }

    @Test fun contributingInputsStayWithTheirActualUnknownAndRetiredDetailCannotReappear() {
        val id = "rule.model.serving.mode.selfhost"
        val assertion = JSONObject("""{"op":"all","args":[{"op":"eq","left":{"ref":{"scope":"build","pointer":"/private_missing_path"}},"right":{"literal":1}},{"op":"lt","left":{"literal":"private_non_numeric_value"},"right":{"literal":1}},{"op":"gte","left":{"metric":"cost.monthly_total_amount"},"right":{"literal":0}}]}""")
        val current = admitted(id, "unknown", "warning") {
            it.put("applies_when", trueExpression()).put("assert", assertion)
        }
        val expectedInputs = listOf(UnknownInputObservation.MISSING_CAPTURED_VALUE,
            UnknownInputObservation.OPERAND_DOMAIN_UNAVAILABLE, UnknownInputObservation.METRIC_NOT_SUPPLIED)
        verifyMeaning(current, id, unknownMeaning, assertionPhase, combinedInputs, expectedInputs)
        val old = detail(current, current.outcome.findings.indexOfFirst { it.ruleId == id })
        val text = body(old)
        assertTrue(text.endsWith(combinedInputs))
        assertFalse(text.contains("private_missing_path"))
        assertFalse(text.contains("private_non_numeric_value"))
        val oldBack = old.getButton(AlertDialog.BUTTON_POSITIVE)
        val oldFull = old.getButton(AlertDialog.BUTTON_NEUTRAL)
        val replacement = admitted(id, "pass", "warning") {
            it.put("applies_when", trueExpression()).put("assert", trueExpression())
        }
        assertNotEquals(current.request.binding.rulesetHash, replacement.request.binding.rulesetHash)
        assertEquals(expectedInputs, current.outcome.unknownInputObservations(id))
        assertTrue(replacement.outcome.unknownInputObservations(id).isEmpty())
        assertFalse(old.isShowing)
        verifyMeaning(replacement, id, null)
        val fresh = detail(replacement, replacement.outcome.findings.indexOfFirst { it.ruleId == id })
        val before = disk()
        val expected = body(fresh)
        oldBack.performClick(); oldFull.performClick(); idle()
        assertSame(fresh, dialog()); assertTrue(fresh.isShowing)
        assertEquals(expected, body(fresh)); assertFalse(expected.contains("Observed input limitations for this unknown result:"))
        assertSame(replacement, state()); assertEquals(before, disk())
        fresh.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
    }

    @Test fun falseApplicabilityExplainsSkippedWithoutPromotingErrorSeverityToFailureOrPass() {
        val id = "rule.model.provider.match"
        // Original exists(primary model) is false; the real evaluator synthesizes the skipped text.
        val current = admitted(id, "skipped", "error")
        verifyMeaning(current, id, skippedMeaning)
    }

    @Test fun naturalFailKeepsItsExactOriginalDetailDespiteMisleadingRecordedStatusWords() {
        val id = "rule.required.foundation"
        // Original all-of required-selection assertion is false for this genuinely empty draft.
        val current = admitted(id, "fail", "info")
        verifyMeaning(current, id, null)
    }

    @Test fun admittedSyntheticPassKeepsItsExactOriginalDetailDespiteErrorSeverity() {
        val id = "rule.required.foundation"
        // Explicit test-only literal comparison; actual evaluator/immutable Current still required.
        val current = admitted(id, "pass", "error") { it.put("assert", trueExpression()) }
        verifyMeaning(current, id, null)
    }
}
