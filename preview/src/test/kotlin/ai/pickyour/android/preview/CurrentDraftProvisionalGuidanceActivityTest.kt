package ai.pickyour.android.preview

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.evaluation.DraftCurrentUnknownPhase
import ai.pickyour.core.evaluation.DraftRuleFinding
import ai.pickyour.core.evaluation.ProvisionalFindingGuidance
import ai.pickyour.core.evaluation.UnknownInputObservation
import ai.pickyour.core.preview.*
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

/** Actual stored selections and the real current evaluator; provisional text is not a new verdict. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en +ldltr")
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftProvisionalGuidanceActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val foundation = "rule.required.foundation"
    private val foundationKinds = listOf("workload", "deployment_mode", "model", "context_policy")
    private val provider = "rule.model.provider.match"
    private val incomplete = "Guidance: Incomplete (provisional)"
    private val incompatible = "Guidance: Reported incompatibility (provisional)"
    private val privateNote = "PRIVATE_PROVISIONAL_GUIDANCE_SELECTION_NOTE"
    private val unknownMeaning = "Outcome meaning: The local diagnostic did not determine a pass or fail for this rule. " +
        "This is not a pass or proof of readiness."
    private val assertionPhase = "Unknown phase: This rule applies, but its assertion did not determine a pass or fail. " +
        "This phase does not identify a cause or a feasible next action."
    private val noSelectionInputs = "Observed input limitations for this unknown result:\n" +
        "No enabled selection matched the local read.\nThese observations are local input facts, not a complete cause or a verified remedy."
    private val skippedMeaning = "Outcome meaning: This rule was not evaluated for these captured inputs. " +
        "A skipped result is not a pass or proof of readiness."

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
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun setField(name: String, value: Any) {
        PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
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
    private fun bytes(name: String) = activity.assets.open("$name.json").use { it.readBytes() }
    private fun document(name: String) = JSONObject(String(bytes(name), Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun loaded(): PreviewStoredDraft {
        val result = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()
        assertTrue("The actual saved draft must still load", result is PreviewDraftLoad.Available)
        return (result as PreviewDraftLoad.Available).record
    }
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to hash(Files.readAllBytes(it))
        }
    }
    private fun selection(kind: String): JSONObject {
        val selections = document("build").getJSONArray("selections")
        return (0 until selections.length()).map(selections::getJSONObject)
            .single { it.getString("kind") == kind && it.getString("role") == "primary" }
            .put("notes", privateNote)
    }
    private fun local(value: JSONObject) = LocalSelection.fromJson(value.toString().toByteArray(Charsets.UTF_8))
    private fun reopen() {
        button(R.string.reopen_draft).performClick(); idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        await { !field<Boolean>("storageBusy") && field<Boolean>("storageReady") }
        finishWork()
        await { state() is PreviewCurrentDraftState.Current }
    }

    /**
     * The copied catalog is admitted through every existing Activity authority. Only actual
     * LocalDraftStore.save plus reopen (or the real changed-input update for an empty draft)
     * can produce Current; no outcome, report or analysis state is injected.
     */
    private fun admitted(
        selections: List<LocalSelection> = emptyList(),
        catalog: JSONObject = document("catalog"),
        changeRules: (JSONObject) -> Unit = {},
    ): PreviewCurrentDraftState.Current {
        val prior = state()
        val rules = document("ruleset")
        assertEquals("05474568204fdbfd407cb4e4cfc9334e22fdf24e75111bed8990868926fab418", hash(bytes("ruleset")))
        changeRules(rules)
        val catalogBytes = catalog.toString().toByteArray(Charsets.UTF_8)
        val rulesBytes = rules.toString().toByteArray(Charsets.UTF_8)
        val graphBytes = document("dependency-graph").toString().toByteArray(Charsets.UTF_8)
        val fixture = CatalogFixtureData.read({ catalogBytes }) { activity.assets.open("pricebook.json") }
        val browser = requireNotNull(fixture.catalog)
        val inputs = requireNotNull(DraftCurrentInputs.read(catalogBytes, graphBytes, rulesBytes))
        val snapshot = SelectionCatalogSnapshot.fromJson(catalogBytes)
        assertEquals(inputs.catalogHash, snapshot.hash)
        assertEquals(hash(rulesBytes), inputs.rulesetHash.hex)
        setField("catalogFixture", fixture)
        setField("componentCatalog", snapshot)
        setField("currentCheckInputs", inputs)
        field<SelectionEditSection>("componentSection").catalog(browser.items, browser.hash)
        field<CatalogBrowseSection>("catalog").show(browser)
        finishWork()

        val durable = (store().load() as DraftLoad.Available).draft
        if (durable.content.components.selections != selections) {
            assertTrue(store().save(durable.version, durable.content.withComponents(DraftSelections(selections)),
                durable.metadata) is DraftSave.Saved)
            reopen()
        } else {
            PreviewActivity::class.java.getDeclaredMethod("updateCurrentChecks")
                .apply { isAccessible = true }.invoke(activity)
        }
        await { state() is PreviewCurrentDraftState.Current && state() !== prior }
        finishWork()
        val current = state() as PreviewCurrentDraftState.Current
        val saved = field<PreviewStoredDraft>("savedRecord")
        assertEquals(selections, saved.components.selections)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.revision, current.request.binding.sourceRevision)
        assertEquals(saved.baselineIdentity.substringAfter(':'), current.request.binding.sourceHash.hex)
        assertEquals(inputs.catalogHash, current.request.binding.catalogHash)
        assertEquals(inputs.rulesetHash, current.request.binding.rulesetHash)
        assertEquals(inputs.graphHash, current.request.binding.graphHash)
        assertTrue(current.outcome.belongsTo(current.request))
        assertEquals(45, current.outcome.findings.size)
        assertEquals(45, current.outcome.findings.map { it.ruleId }.toSet().size)
        return current
    }

    private fun finding(current: PreviewCurrentDraftState.Current, id: String, expected: String): DraftRuleFinding {
        val result = current.outcome.findings.single { it.ruleId == id }
        assertEquals("The real evaluator must establish the requested fixture outcome", expected, result.outcome)
        assertEquals("error", result.severity)
        val rules = document("ruleset").getJSONArray("rules")
        val rule = (0 until rules.length()).map(rules::getJSONObject).single { it.getString("id") == id }
        assertEquals(if (expected in listOf("fail", "unknown")) rule.getString("message") else "$id: $expected", result.message)
        assertEquals(if (expected in listOf("fail", "unknown")) rule.getString("remediation") else "", result.remediation)
        return result
    }
    private fun detail(current: PreviewCurrentDraftState.Current, index: Int): AlertDialog {
        assertSame(current, state())
        activity.findViewById<Button>(R.id.current_draft_check_inspect).also {
            assertTrue(it.isEnabled); it.performClick()
        }
        idle()
        var page = dialog()
        var offset = 0
        while (index >= offset + 10) {
            page.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            val next = dialog()
            assertNotSame(page, next)
            page = next; offset += 10
        }
        assertTrue(page.listView.adapter.getItem(index - offset).toString().contains(current.outcome.findings[index].ruleId))
        shadowOf(page).clickOnItem(index - offset); idle()
        return dialog().also { assertNotSame(page, it); assertTrue(it.isShowing) }
    }
    private fun body(window: AlertDialog) = window.findViewById<TextView>(android.R.id.message).text.toString()
    private fun quoted(raw: String): String {
        // These unchanged fixture fields fit the existing preview. Android JSON quoting optionally
        // escapes solidus; normalize only that spelling, independently of the production helper.
        assertTrue(raw.length <= 160)
        assertTrue(raw.all { it.code in 32..126 })
        val literal = JSONObject.quote(raw).replace("\\/", "/")
        assertEquals(raw, JSONArray("[$literal]").getString(0))
        return literal
    }
    private fun previousDetail(row: DraftRuleFinding) = activity.getString(R.string.current_check_finding,
        quoted(row.ruleId), quoted(row.outcome), quoted(row.severity), quoted(row.message),
        quoted(row.remediation), row.selectionIds.joinToString(", ", transform = ::quoted)) + when (row.outcome) {
            "unknown" -> "\n\n$unknownMeaning"
            "skipped" -> "\n\n$skippedMeaning"
            else -> ""
        }
    private fun decodedField(text: String, label: String): String {
        val value = text.lineSequence().single { it.startsWith("$label: ") }.substringAfter(": ")
            .trim('\u200f', '\u202a', '\u202c')
        return JSONArray("[$value]").getString(0)
    }

    private fun absentChoices(kinds: List<String>) = "Observed in this saved draft (provisional): no enabled primary choice for " +
        kinds.joinToString(", ", transform = ::quoted) + ". This is a selection observation; candidate feasibility is unverified."

    private fun verifyGuidance(current: PreviewCurrentDraftState.Current, id: String, guidance: String?,
        unknownPhase: String? = null, missingPrimaryKinds: List<String> = emptyList(), inputText: String? = null,
    ) {
        val before = disk()
        val saved = loaded().baselineIdentity
        val binding = current.request.binding.copy()
        assertEquals(missingPrimaryKinds, current.outcome.missingRequiredPrimaryKinds(id))
        val rows = current.outcome.findings
        val counts = listOf("pass", "fail", "unknown", "skipped").map { value -> rows.count { it.outcome == value } }
        assertEquals(rows.size, counts.sum())
        val summary = activity.findViewById<TextView>(R.id.current_draft_check_status)
        val expectedSummary = activity.getString(R.string.current_check_current, binding.sourceRevision.toString(),
            counts[0], counts[1], counts[2], counts[3])
        assertEquals(expectedSummary, summary.text.toString())
        val index = rows.indexOfFirst { it.ruleId == id }
        assertTrue(index >= 0)
        val row = rows[index]
        val window = detail(current, index)
        val text = body(window)
        val previous = previousDetail(row)
        assertTrue("The complete existing detail, including outcome meaning, remains the prefix", text.startsWith(previous))
        for ((label, value) in listOf("Rule" to row.ruleId, "Outcome" to row.outcome, "Severity" to row.severity,
            "Observation" to row.message, "Recorded guidance" to row.remediation)) assertEquals(value, decodedField(text, label))
        assertEquals("Close", window.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Back to observations", window.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Full text", window.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertTrue(views(window.window!!.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == "Inspect affected selections" }.isEnabled)
        assertFalse(text.contains(privateNote))
        assertSame(current, state())
        assertEquals(binding, current.request.binding)
        assertEquals(expectedSummary, summary.text.toString())
        assertEquals(saved, loaded().baselineIdentity)
        assertEquals(before, disk())

        val phased = if (unknownPhase == null) previous else "$previous\n\n$unknownPhase"
        val observed = if (inputText == null) phased else "$phased\n\n$inputText"
        val guided = if (guidance == null) observed else "$observed\n\n$guidance"
        val expected = if (missingPrimaryKinds.isEmpty()) guided else "$guided\n\n" + absentChoices(missingPrimaryKinds)
        assertEquals("Only expected phase/input observations, fail guidance and captured required-choice absences may be appended", expected, text)

        window.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val list = dialog()
        assertNotSame(window, list)
        shadowOf(list).clickOnItem(index % 10); idle()
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

    @Test fun emptyCurrentFailureShowsProvisionalIncompleteWithoutChangingItsVerdict() {
        val current = admitted()
        assertTrue(field<PreviewStoredDraft>("savedRecord").components.selections.isEmpty())
        assertTrue(finding(current, foundation, "fail").selectionIds.isEmpty())
        verifyGuidance(current, foundation, incomplete, missingPrimaryKinds = foundationKinds)
    }

    @Test fun mixedCurrentShowsReportedConflictAlongsideIndependentMissingChoices() {
        val catalog = document("catalog")
        val items = catalog.getJSONArray("items")
        val original = (0 until items.length()).map(items::getJSONObject)
            .single { it.getString("id") == "cat.model_provider.fixture" }
        items.put(JSONObject(original.toString()).put("id", "cat.model_provider.other_fixture"))
        val model = local(selection("model"))
        val other = local(selection("model_provider").put("id", "sel_provider_other")
            .put("catalog_item_id", "cat.model_provider.other_fixture"))
        val current = admitted(listOf(model, other), catalog)
        assertEquals(listOf(model.id), finding(current, foundation, "fail").selectionIds)
        assertEquals(listOf(model.id, other.id).sorted(), finding(current, provider, "fail").selectionIds)
        // The provider's ID differs, while both exact versions are admitted. This is not a
        // fabricated version mismatch or an external component-compatibility conclusion.
        assertEquals("2026.10-demo", other.catalogItemVersion)
        verifyGuidance(current, provider, incompatible)
        verifyGuidance(current, foundation, incomplete,
            missingPrimaryKinds = listOf("workload", "deployment_mode", "context_policy"))
    }

    @Test fun disabledPrimaryAndEnabledFallbackNeverSatisfyRequiredPresence() {
        val disabled = local(selection("model").put("enabled", false))
        val fallback = local(selection("model").put("id", "sel_model_fallback").put("role", "fallback"))
        val current = admitted(listOf(disabled, fallback))
        assertFalse(disabled.enabled)
        assertTrue(fallback.enabled)
        assertEquals(listOf(fallback.id), finding(current, foundation, "fail").selectionIds)
        verifyGuidance(current, foundation, incomplete, missingPrimaryKinds = foundationKinds)
    }

    @Test fun realUnknownKeepsItsRawOutcomeAndMeaningWithoutProvisionalGuidance() {
        val model = local(selection("model"))
        val current = admitted(listOf(model))
        assertEquals(listOf(model.id), finding(current, provider, "unknown").selectionIds)
        assertEquals(DraftCurrentUnknownPhase.ASSERTION, current.outcome.unknownPhase(provider))
        assertEquals(ProvisionalFindingGuidance.UNCLASSIFIED, current.outcome.provisionalGuidance(provider))
        assertEquals(listOf(UnknownInputObservation.NO_MATCHING_SELECTION), current.outcome.unknownInputObservations(provider))
        verifyGuidance(current, provider, null, unknownPhase = assertionPhase, inputText = noSelectionInputs)
    }

    @Test fun realSkippedKeepsItsRawOutcomeAndMeaningWithoutProvisionalGuidance() {
        val current = admitted()
        assertTrue(finding(current, provider, "skipped").selectionIds.isEmpty())
        verifyGuidance(current, provider, null)
    }

    @Test fun matchingProviderPassNeverAcquiresConflictOrIncompleteGuidance() {
        val model = local(selection("model"))
        val matching = local(selection("model_provider"))
        val current = admitted(listOf(model, matching))
        assertEquals(listOf(model.id, matching.id).sorted(), finding(current, provider, "pass").selectionIds)
        finding(current, foundation, "fail")
        verifyGuidance(current, provider, null)
    }

    @Test fun changedRegisteredRulesCannotBorrowProvisionalGuidance() {
        val current = admitted(changeRules = { it.put("published_at", "2026-10-17T09:00:00Z") })
        finding(current, foundation, "fail")
        assertNotEquals(hash(document("ruleset").toString().toByteArray(Charsets.UTF_8)), current.request.binding.rulesetHash.hex)
        verifyGuidance(current, foundation, null)
    }

    @Test fun requiredAbsencesStayWithTheirSavedRevisionAndRetiredDetailCannotReturn() {
        val all = foundationKinds.map { local(selection(it)) }
        val complete = admitted(all)
        finding(complete, foundation, "pass")
        verifyGuidance(complete, foundation, null)

        val missing = admitted(all.filter { it.kind != "model" })
        finding(missing, foundation, "fail")
        verifyGuidance(missing, foundation, incomplete, missingPrimaryKinds = listOf("model"))
        val old = detail(missing, missing.outcome.findings.indexOfFirst { it.ruleId == foundation })
        val capturedText = body(old)
        assertTrue(capturedText.endsWith(absentChoices(listOf("model"))))
        val oldContext = views(old.window!!.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == "Inspect affected selections" }

        val restored = admitted(all)
        finding(restored, foundation, "pass")
        assertNotEquals(missing.request.binding, restored.request.binding)
        assertEquals(listOf("model"), missing.outcome.missingRequiredPrimaryKinds(foundation))
        assertTrue(restored.outcome.missingRequiredPrimaryKinds(foundation).isEmpty())
        assertFalse(old.isShowing)
        val latest = dialog()
        val before = disk()
        val saved = loaded().baselineIdentity
        old.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        old.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        oldContext.performClick(); idle()
        assertSame(latest, dialog())
        assertFalse(old.isShowing)
        assertSame(restored, state())
        assertEquals(saved, loaded().baselineIdentity)
        assertEquals(before, disk())
        verifyGuidance(restored, foundation, null)
    }
}
