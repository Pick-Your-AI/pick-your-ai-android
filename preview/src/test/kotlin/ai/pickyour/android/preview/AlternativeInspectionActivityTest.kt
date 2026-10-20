package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.*
import ai.pickyour.core.preview.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Actual editor and immutable evaluator inputs. Queued harnesses exercise delivery order explicitly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AlternativeInspectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val browsers = mutableListOf<AlternativeInspectionBrowser>()

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && text().contains(activity.getString(R.string.draft_saved)) }
        finishWork()
        assertTrue(loaded().components.selections.isEmpty())
    }
    @After fun close() { browsers.forEach { it.retire() }; controller.pause().stop().destroy() }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun text(root: View = activity.window.decorView) =
        views(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun button(label: Int, root: View = activity.window.decorView) =
        views(root).filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(root: View, label: Int): EditText {
        val caption = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return root.findViewById(caption.labelFor)
    }
    private fun field(form: AlertDialog, label: Int) = field(form.window!!.decorView, label)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) { idle(); if (condition()) return; Thread.sleep(10) }
        fail("Expected current alternative inspection was not reached")
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> get(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun set(name: String, value: Any) {
        PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun worker() = get<ExecutorService>("worker")
    private fun finishWork() { repeat(4) { idle(); worker().submit {}.get(20, TimeUnit.SECONDS); idle() } }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    /** Positive disk observations follow the actual Activity worker barrier. */
    private fun loaded(): PreviewStoredDraft {
        finishWork()
        val result = adapter().load()
        assertTrue("Expected readable acknowledged private source, got $result", result is PreviewDraftLoad.Available)
        return (result as PreviewDraftLoad.Available).record
    }
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun selectionJson(kind: String): JSONObject {
        val values = document("build").getJSONArray("selections")
        return (0 until values.length()).map(values::getJSONObject).single { it.getString("kind") == kind }
    }
    private fun fixtureSelections(): List<LocalSelection> {
        val values = document("build").getJSONArray("selections")
        return (0 until values.length()).map { LocalSelection.fromJson(values.getJSONObject(it).toString().toByteArray()) }
    }
    private fun reopen() {
        finishWork(); button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        await { button(R.string.component_choose_catalog).isEnabled }
    }
    private fun saveContent(change: (DraftContent) -> DraftContent) {
        finishWork()
        val local = store(); val old = (local.load() as DraftLoad.Available).draft
        assertTrue(local.save(old.version, change(old.content)) is DraftSave.Saved); reopen()
    }
    private fun installCatalog(value: JSONObject): PreviewCatalogBrowser {
        finishWork()
        val bytes = value.toString().toByteArray()
        val input = CatalogFixtureData.read({ bytes }) { activity.assets.open("pricebook.json") }
        val browser = requireNotNull(input.catalog)
        set("catalogFixture", input); set("componentCatalog", SelectionCatalogSnapshot.fromJson(bytes))
        get<SelectionEditSection>("componentSection").catalog(browser.items, browser.hash)
        get<CatalogBrowseSection>("catalog").show(browser)
        return browser
    }
    private fun choose(window: AlertDialog = dialog(), matches: (String) -> Boolean): AlertDialog {
        val at = (0 until window.listView.adapter.count).first { matches(window.listView.adapter.getItem(it).toString()) }
        shadowOf(window).clickOnItem(at); return dialog()
    }
    private fun addForm(kind: String = "workload", settings: String = "{}"): AlertDialog {
        button(R.string.component_choose_catalog).performClick()
        val actions = choose { it.startsWith(componentTokenForLookup(activity, kind) + " ·") }
        val form = choose(actions) { it == activity.getString(R.string.component_add) }
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(settings)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        return form
    }
    private fun compare(form: AlertDialog): AlertDialog {
        form.findViewById<Button>(R.id.options_form_compare).performClick()
        return dialog().also { picker ->
            assertNotSame(form, picker)
            await { picker.findViewById<TextView>(R.id.options_status).text.toString() != activity.getString(R.string.options_evaluating) }
        }
    }
    private fun inspectionButtons(picker: AlertDialog) = views(picker.findViewById(R.id.options_rows))
        .filterIsInstance<Button>().filter { it.id == R.id.alternative_inspect }
    private fun choiceButtons(picker: AlertDialog) = views(picker.findViewById(R.id.options_rows))
        .filterIsInstance<Button>().filter { it.text.toString().startsWith("Use ") }
    private fun open(picker: AlertDialog, index: Int = 0): AlertDialog {
        inspectionButtons(picker)[index].performClick(); awaitMenu(); return dialog()
    }
    private fun click(which: Int) { dialog().getButton(which).performClick(); idle() }
    private fun listLabels(window: AlertDialog = dialog()) =
        (0 until window.listView.adapter.count).map { window.listView.adapter.getItem(it).toString() }
    private fun hasButton(which: Int, label: Int) = dialog().getButton(which)?.let {
        it.visibility == View.VISIBLE && it.text.toString() == activity.getString(label)
    } == true
    private fun noLinks(window: AlertDialog = dialog()) {
        val message = window.findViewById<TextView>(android.R.id.message) ?: return
        assertEquals(0, message.autoLinkMask); assertFalse(message.linksClickable); assertNull(message.movementMethod)
        (message.text as? Spanned)?.let { assertTrue(it.getSpans(0, it.length, URLSpan::class.java).isEmpty()) }
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    /** Independent JSON decoding reconstructs all displayed literal parts, including split surrogates. */
    private fun readValue(): String? {
        val output = StringBuilder(); var parts = 0
        while (true) {
            noLinks()
            val message = dialog().findViewById<TextView>(android.R.id.message).text.toString()
            val literal = message.substringAfter("\n\n").substringBefore("\n\n")
            if (literal == activity.getString(R.string.catalog_detail_missing)) {
                assertEquals(0, parts); assertFalse(hasButton(AlertDialog.BUTTON_POSITIVE, R.string.alternative_next_part))
                return null
            }
            val decoded = JSONTokener(literal).nextValue()
            assertTrue("Every displayed part must remain a complete quoted literal", decoded is String)
            output.append(decoded as String); parts++
            if (!hasButton(AlertDialog.BUTTON_POSITIVE, R.string.alternative_next_part)) return output.toString()
            click(AlertDialog.BUTTON_POSITIVE)
        }
    }
    /** Reads every field and value part through the actual pages, then returns to the parent. */
    private fun readFields(): Map<String, String?> {
        val output = linkedMapOf<String, String?>()
        while (true) {
            val names = listLabels()
            for (index in names.indices) {
                assertEquals(names, listLabels()); shadowOf(dialog()).clickOnItem(index)
                assertEquals(names[index], shadowOf(dialog()).title.toString())
                assertFalse("Field labels must not be duplicated", output.containsKey(names[index]))
                output[names[index]] = readValue(); click(AlertDialog.BUTTON_NEGATIVE)
            }
            if (!hasButton(AlertDialog.BUTTON_POSITIVE, R.string.alternative_next)) {
                click(AlertDialog.BUTTON_NEGATIVE); return output
            }
            click(AlertDialog.BUTTON_POSITIVE)
        }
    }
    private fun findField(name: String) {
        while (name !in listLabels()) {
            assertTrue("Missing complete field $name", hasButton(AlertDialog.BUTTON_POSITIVE, R.string.alternative_next))
            click(AlertDialog.BUTTON_POSITIVE)
        }
        choose { it == name }
    }
    private fun openRule(index: Int) {
        var page = 0
        while (index >= (page + 1) * 8) { click(AlertDialog.BUTTON_POSITIVE); page++ }
        shadowOf(dialog()).clickOnItem(index - page * 8)
    }
    private data class Capture(val parent: DraftCompatibleOptionsRequest, val result: DraftCompatibleOptionsOutcome.Completed,
        val option: DraftCompatibleOption) {
        fun request() = (DraftAlternativeInspectionRequest.prepare(parent, result, option) as DraftAlternativePreparation.Ready).request
    }
    private fun actualCapture(index: Int = 0): Capture {
        val picker = get<CompatibleOptionsPicker>("compatibleOptions")
        val session = CompatibleOptionsPicker::class.java.getDeclaredField("session").apply { isAccessible = true }.get(picker)
        fun value(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)
        val parent = value("request") as DraftCompatibleOptionsRequest
        val result = value("result") as DraftCompatibleOptionsOutcome.Completed
        return Capture(parent, result, result.options[index])
    }
    private fun captured(candidate: LocalSelection = fixtureSelections().single { it.kind == "workload" },
        slot: CompatibleOptionSlot = CompatibleOptionSlot(candidate.kind, candidate.role, candidate.id, false),
        rules: JSONObject = document("ruleset")): Capture {
        val prepared = loaded().prepareCompatibleSelections(
            requireNotNull(PreviewCompatibleOptionInputs.catalog(document("catalog").toString().toByteArray())),
            requireNotNull(PreviewCompatibleOptionInputs.ruleset(rules.toString().toByteArray())), slot, listOf(candidate))
        assertTrue(prepared is DraftCompatibleOptionsPreparation.Ready)
        val parent = (prepared as DraftCompatibleOptionsPreparation.Ready).request
        val result = DraftCompatibleOptionsEvaluator().evaluate(parent)
        assertTrue(result is DraftCompatibleOptionsOutcome.Completed)
        return Capture(parent, result as DraftCompatibleOptionsOutcome.Completed, result.options.single())
    }
    private fun browser(capture: Capture, executor: Executor = worker(), live: () -> Boolean = { true },
        inspect: (DraftAlternativeInspectionRequest, () -> Boolean) -> DraftAlternativeOutcome =
            DraftAlternativeInspector()::inspect): AlternativeInspectionBrowser =
        AlternativeInspectionBrowser(activity, executor, capture.parent, capture.result, capture.option, live, inspect)
            .also { browsers += it; it.show() }
    private class Queue : Executor {
        val tasks = mutableListOf<Runnable>(); var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("synthetic local queue")
            tasks += command
        }
        fun runFirst() { tasks.removeAt(0).run() }
        fun runFirstOffMain() {
            val action = tasks.removeAt(0); var failure: Throwable? = null
            val thread = Thread { try { action.run() } catch (caught: Throwable) { failure = caught } }
            thread.start(); thread.join(20_000)
            assertFalse("Bounded actual evaluator must return", thread.isAlive)
            failure?.let { throw AssertionError(it) }
        }
    }
    private fun awaitMenu() { await { dialog().findViewById<View>(R.id.alternative_rules) != null } }

    @Test fun actualAddBrowsesEveryRuleAndCompleteInheritedFindingWithoutWriting() {
        val before = disk(); val picker = compare(addForm())
        val capture = actualCapture()
        val expected = DraftAlternativeInspector().inspect(capture.request()) as DraftAlternativeOutcome.Completed
        val menu = open(picker)
        assertTrue(text(menu.window!!.decorView).contains(activity.getString(R.string.alternative_no_current)))
        assertTrue(text(menu.window!!.decorView).contains(activity.getString(R.string.alternative_limits)))
        menu.findViewById<Button>(R.id.alternative_rules).performClick()
        val ids = mutableListOf<String>(); var page = 0
        while (true) {
            val rows = listLabels()
            rows.indices.forEach { offset ->
                shadowOf(dialog()).clickOnItem(offset); choose { it == "rule_id" }
                ids += requireNotNull(readValue())
                click(AlertDialog.BUTTON_NEGATIVE); click(AlertDialog.BUTTON_NEGATIVE)
                assertEquals(rows, listLabels())
            }
            if (!hasButton(AlertDialog.BUTTON_POSITIVE, R.string.alternative_next)) break
            page++; click(AlertDialog.BUTTON_POSITIVE)
        }
        assertEquals(5, page); assertEquals(45, ids.size)
        assertEquals(expected.observations.map { it.ruleId }, ids)
        click(AlertDialog.BUTTON_NEGATIVE)
        dialog().findViewById<Button>(R.id.alternative_rules).performClick(); openRule(0)
        val fields = readFields(); val inherited = expected.observations.first()
        assertEquals("rule.required.foundation", fields["rule_id"])
        for ((prefix, finding) in listOf("current" to inherited.current, "candidate" to inherited.candidate, "slot_empty" to inherited.slotEmpty)) {
            assertEquals("fail", fields["$prefix.outcome"])
            assertEquals(finding.ruleId, fields["$prefix.rule_id"])
            assertEquals(finding.severity, fields["$prefix.severity"])
            assertEquals(finding.code, fields["$prefix.code"])
            assertEquals(finding.message, fields["$prefix.message"])
            assertEquals(finding.remediation, fields["$prefix.remediation"])
            assertEquals(finding.selectionIds.size.toString(), fields["$prefix.selection_ids.length"])
            finding.selectionIds.forEachIndexed { index, id -> assertEquals(id, fields["$prefix.selection_ids[$index]"]) }
        }
        assertEquals("false", fields["current_to_candidate.outcome_changed"])
        assertEquals("false", fields["candidate_reason.present"])
        assertEquals(before, disk()); assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun replacementShowsCurrentConflictAndCandidateRepairWithoutCallingItReadiness() {
        val catalog = document("catalog"); val items = catalog.getJSONArray("items")
        fun item(kind: String) = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == kind }
        item("deployment_mode").getJSONObject("spec").put("mode", "api")
        item("model").getJSONObject("spec").put("access_modes", JSONArray().put("api"))
        val bad = JSONObject(item("model").toString()).put("id", "cat.model.conflict").put("name", "FICTIONAL conflict")
        bad.getJSONObject("spec").put("provider_ref", "cat.provider.other").put("access_modes", JSONArray().put("weights"))
        items.put(bad)
        val rows = fixtureSelections().filter { it.kind in setOf("workload", "deployment_mode", "model_provider", "model", "context_policy") }
            .map { if (it.kind == "model") LocalSelection.fromJson(selectionJson("model")
                .put("catalog_item_id", "cat.model.conflict").toString().toByteArray()) else it }
        saveContent { it.withComponents(DraftSelections(rows)) }; installCatalog(catalog)
        val before = disk()
        button(R.string.component_manage).performClick()
        val actions = choose { it.endsWith(" · sel_model") }
        val picker = choose(actions) { it == activity.getString(R.string.options_open) }
        await { picker.findViewById<TextView>(R.id.options_status).text.toString() != activity.getString(R.string.options_evaluating) }
        val capture = actualCapture()
        val expected = DraftAlternativeInspector().inspect(capture.request()) as DraftAlternativeOutcome.Completed
        val index = expected.observations.indexOfFirst { it.ruleId == "rule.model.provider.match" }
        assertTrue(index >= 0); assertEquals("cat.model.fixture", capture.option.identity.catalogItemId)
        val menu = open(picker)
        assertTrue(text(menu.window!!.decorView).contains(activity.getString(R.string.alternative_has_current)))
        assertTrue(text(menu.window!!.decorView).contains(activity.getString(R.string.alternative_limits)))
        menu.findViewById<Button>(R.id.alternative_rules).performClick(); openRule(index)
        val fields = readFields()
        assertEquals("fail", fields["current.outcome"]); assertEquals("pass", fields["candidate.outcome"])
        assertEquals("true", fields["current_to_candidate.outcome_changed"])
        assertEquals("false", fields["candidate_reason.present"])
        assertEquals(before, disk()); assertEquals("cat.model.conflict", loaded().components.selections.single { it.kind == "model" }.catalogItemId)
    }

    @Test fun exactCapturedContextsAndLiteralTailsKeepNumbersControlsAndUrisInert() {
        val huge = "123456789012345678901234567890123456"
        val amount = "123456789012345678901.000000000001"
        val literal = "x".repeat(159) + "😀\u202e\nhttps://literal.invalid/private?raw=%2F&v=1\u0000" + "tail".repeat(60)
        saveContent { DraftContent("private objective remains uninterpreted", "private context remains unreported", it.primaryModel,
            it.optional, DraftWorkload(region = "https://literal.invalid/region", monthlyBudgetAmount = ContractDecimal.parse(amount),
                requestsPerDay = BigInteger(huge)), it.components) }
        val json = selectionJson("workload").put("settings", JSONObject().put("concurrency", BigInteger(huge)))
        val candidate = LocalSelection.fromJson(json.toString().toByteArray())
        val rules = document("ruleset")
        rules.getJSONArray("rules").getJSONObject(0).put("message", literal).put("remediation", " ")
        val capture = captured(candidate, rules = rules); val expected = capture.request(); val before = disk()
        browser(capture); awaitMenu()
        dialog().findViewById<Button>(R.id.alternative_records).performClick()
        val records = readFields()
        assertNull(records["current_selection"])
        assertEquals(expected.candidateSelectionJson, records["candidate_selection"])
        assertEquals(expected.currentContextJson, records["current_context"])
        assertEquals(expected.candidateContextJson, records["candidate_context"])
        assertEquals(expected.slotEmptyContextJson, records["slot_empty_context"])
        assertTrue(records.getValue("candidate_selection")!!.contains(huge))
        for (key in listOf("current_context", "candidate_context", "slot_empty_context")) {
            assertTrue(records.getValue(key)!!.contains(huge)); assertTrue(records.getValue(key)!!.contains(amount))
            assertFalse(records.getValue(key)!!.contains("private objective remains uninterpreted"))
        }
        dialog().findViewById<Button>(R.id.alternative_rules).performClick(); openRule(0)
        val findings = readFields()
        assertEquals(literal, findings["current.message"]); assertEquals(literal, findings["candidate.message"])
        assertEquals(literal, findings["slot_empty.message"]); assertEquals(" ", findings["current.remediation"])
        assertNull(shadowOf(activity).nextStartedActivity); assertEquals(before, disk())
    }

    @Test fun detachedFilterRowsAndRetainedConfirmationsCannotStartOrAcceptOldInspection() {
        val picker = compare(addForm()); val before = disk()
        val oldInspect = inspectionButtons(picker).single(); val oldChoose = choiceButtons(picker).single()
        oldChoose.performClick(); val confirmation = dialog()
        picker.findViewById<CheckBox>(R.id.options_include_unknown).isChecked = true
        assertFalse(confirmation.isShowing)
        val current = open(picker); val currentText = text(current.window!!.decorView)
        oldInspect.callOnClick(); oldChoose.callOnClick()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick(); finishWork()
        assertSame(current, dialog()); assertTrue(current.isShowing); assertEquals(currentText, text(current.window!!.decorView))
        assertEquals(before, disk()); assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun changingSavedDraftFieldsRetiresBrowserBeforeTheActualAutosaveDeadline() {
        val source = loaded(); val picker = compare(addForm()); val menu = open(picker)
        menu.findViewById<Button>(R.id.alternative_rules).performClick()
        val oldRules = dialog(); val before = disk()
        field(activity.window.decorView, R.string.draft_title).setText("pending alternative source")
        val started = SystemClock.uptimeMillis()
        assertFalse(picker.isShowing); assertFalse(oldRules.isShowing)
        shadowOf(oldRules).clickOnItem(0); oldRules.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick(); finishWork()
        assertEquals(started, SystemClock.uptimeMillis()); assertEquals(before, disk())
        shadowOf(Looper.getMainLooper()).idleFor(749, TimeUnit.MILLISECONDS); finishWork()
        assertEquals(before, disk()); assertEquals(source.revision, loaded().revision)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        await { !get<Boolean>("storageBusy") && get<PreviewStoredDraft?>("savedRecord")?.revision == source.revision + 1 }
        finishWork()
        val saved = loaded(); assertEquals("pending alternative source", saved.fields.title)
        assertTrue(saved.components.selections.isEmpty()); val accepted = disk()
        oldRules.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick()
        shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity); assertEquals(accepted, disk())
    }

    @Test fun formRoleSettingsAbaRetiresQueuedAlternativeAndPermitsFreshInspection() {
        val form = addForm(); val picker = compare(form); val before = disk()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            inspectionButtons(picker).single().performClick()
            val loading = dialog(); assertEquals(activity.getString(R.string.alternative_loading), shadowOf(loading).message.toString())
            field(form, R.string.component_role).setText("secondary"); field(form, R.string.component_role).setText("primary")
            field(form, R.string.component_settings).setText("""{"concurrency":2}"""); field(form, R.string.component_settings).setText("{}")
            assertFalse(loading.isShowing); assertFalse(picker.isShowing)
            release.countDown(); finishWork()
            loading.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick()
            assertFalse(loading.isShowing); assertEquals(before, disk())
        } finally { release.countDown(); finishWork() }
        val fresh = open(compare(form)); assertTrue(fresh.isShowing); assertEquals(before, disk())
    }

    @Test fun catalogReloadPauseAndRecreationRetireAllInspectionWindows() {
        val form = addForm(); var picker = compare(form); var menu = open(picker); val before = disk()
        val retained = menu.findViewById<Button>(R.id.alternative_records)
        button(R.string.retry_catalog).performClick()
        assertFalse(menu.isShowing); assertFalse(picker.isShowing)
        retained.callOnClick(); finishWork(); assertFalse(menu.isShowing); assertEquals(before, disk())
        picker = compare(form); menu = open(picker)
        menu.findViewById<Button>(R.id.alternative_rules).performClick()
        val oldRows = dialog(); val saved = Bundle(); controller.pause().saveInstanceState(saved)
        assertFalse(picker.isShowing); assertFalse(oldRows.isShowing)
        shadowOf(oldRows).clickOnItem(0); oldRows.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick()
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(saved).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && button(R.string.reopen_draft).isEnabled }; finishWork()
        assertEquals(before, disk()); assertTrue(loaded().components.selections.isEmpty())
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun onlySeparateCurrentChoiceConfirmationWritesOneCanonicalSelection() {
        val beforeRecord = loaded(); val original = disk()
        val form = addForm(settings = """{"concurrency":3}"""); val picker = compare(form)
        val inspection = open(picker); inspection.findViewById<Button>(R.id.alternative_overview).performClick()
        val basis = readFields()
        assertEquals(beforeRecord.revision.toString(), basis["saved_local_revision"])
        assertEquals("COMPATIBLE", basis["original_choice_verdict"])
        assertTrue(basis.containsKey("COST_COMPARISON_UNAVAILABLE"))
        assertTrue(basis.containsKey("EXTERNAL_EVIDENCE_APPLICABILITY_NOT_ESTABLISHED"))
        assertEquals(original, disk())
        val oldMenu = dialog(); click(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(picker.isShowing); assertFalse(oldMenu.isShowing); assertEquals(original, disk())
        val row = choiceButtons(picker).single(); row.performClick()
        val confirmation = dialog()
        assertEquals(activity.getString(R.string.options_confirm), shadowOf(confirmation).title.toString())
        assertEquals(original, disk()); confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !get<Boolean>("storageBusy") && get<PreviewStoredDraft?>("savedRecord")?.revision == beforeRecord.revision + 1 }
        val saved = loaded(); val selection = saved.components.selections.single()
        assertEquals("workload", selection.kind); assertEquals("primary", selection.role)
        assertEquals("""{"concurrency":3}""", PreviewSelectionForms.settingsText(selection))
        assertEquals(beforeRecord.fields, saved.fields); val committed = disk()
        row.callOnClick(); confirmation.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick()
        oldMenu.findViewById<Button>(R.id.alternative_overview).callOnClick(); finishWork()
        assertEquals(committed, disk()); assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun rejectedDispatchAndForeignResultsRemainUnavailableUntilFreshExplicitRetry() {
        val captured = captured(); val queue = Queue(); queue.reject = true
        val foreignRequest = captured.request(); var foreign = true; val before = disk()
        browser(captured, queue) { request, cancelled ->
            if (foreign) { foreign = false; DraftAlternativeInspector().inspect(foreignRequest, cancelled) }
            else DraftAlternativeInspector().inspect(request, cancelled)
        }
        val rejected = dialog()
        assertTrue(shadowOf(rejected).message.toString().contains(activity.getString(R.string.alternative_dispatch_failed)))
        assertNull(rejected.findViewById<View>(R.id.alternative_rules)); assertTrue(queue.tasks.isEmpty())
        queue.reject = false; click(AlertDialog.BUTTON_NEUTRAL); queue.runFirst(); idle()
        val mismatched = dialog()
        assertTrue(shadowOf(mismatched).message.toString().contains(activity.getString(R.string.alternative_invalid_output)))
        assertNull(mismatched.findViewById<View>(R.id.alternative_rules))
        click(AlertDialog.BUTTON_NEUTRAL); queue.runFirst(); idle(); awaitMenu(); val accepted = dialog()
        rejected.getButton(AlertDialog.BUTTON_NEUTRAL).callOnClick()
        mismatched.getButton(AlertDialog.BUTTON_NEUTRAL).callOnClick(); idle()
        assertSame(accepted, dialog()); assertTrue(queue.tasks.isEmpty()); assertEquals(before, disk())
    }

    @Test fun reversedQueuedSameBindingInspectionsCannotReplaceTheirNewerWindow() {
        val captured = captured(); val queue = Queue(); val before = disk(); var evaluations = 0
        val inspector: (DraftAlternativeInspectionRequest, () -> Boolean) -> DraftAlternativeOutcome = { request, cancelled ->
            evaluations++; DraftAlternativeInspector().inspect(request, cancelled)
        }
        val first = browser(captured, queue, inspect = inspector); val loading = dialog()
        queue.runFirstOffMain() // Real result exists, while its UI delivery is still queued.
        assertEquals(1, evaluations); assertTrue(loading.isShowing); first.retire()
        val second = browser(captured, queue, inspect = inspector)
        val currentLoading = dialog(); assertNotSame(loading, currentLoading)
        queue.runFirstOffMain(); idle(); awaitMenu(); val current = dialog()
        assertEquals(2, evaluations); assertFalse(loading.isShowing)
        loading.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick(); idle(); assertSame(current, dialog())
        current.findViewById<Button>(R.id.alternative_records).performClick(); findField("candidate_context")
        val value = dialog(); val back = value.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(hasButton(AlertDialog.BUTTON_POSITIVE, R.string.alternative_next_part))
        val next = value.getButton(AlertDialog.BUTTON_POSITIVE)
        second.retire(); browser(captured, queue, inspect = inspector); queue.runFirst(); idle(); awaitMenu()
        val fresh = dialog(); back.callOnClick(); next.callOnClick(); idle()
        assertSame(fresh, dialog()); assertFalse(value.isShowing); assertEquals(before, disk())
    }

    @Test fun inheritedFindingsAndChoiceConfirmationsMutuallyRetireAlternativeWindows() {
        val picker = compare(addForm()); val before = disk(); var menu = open(picker)
        menu.findViewById<Button>(R.id.alternative_rules).performClick(); val oldRules = dialog()
        picker.findViewById<Button>(R.id.options_inherited).performClick()
        val inherited = dialog(); assertFalse(oldRules.isShowing)
        shadowOf(oldRules).clickOnItem(0); oldRules.getButton(AlertDialog.BUTTON_NEGATIVE).callOnClick()
        assertSame(inherited, dialog())
        menu = open(picker); assertFalse(inherited.isShowing)
        choiceButtons(picker).single().performClick(); val confirmation = dialog(); assertFalse(menu.isShowing)
        menu.findViewById<Button>(R.id.alternative_records).callOnClick(); assertSame(confirmation, dialog())
        val fresh = open(picker); assertFalse(confirmation.isShowing)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick(); finishWork()
        assertSame(fresh, dialog()); assertEquals(before, disk()); assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun unsupportedCurrentContextNeverFallsBackToSuccessfulSlotEmptyRows() {
        val modelJson = selectionJson("model")
        val current = LocalSelection.fromJson(JSONObject(modelJson.toString())
            .put("settings", JSONObject("""{"temperature":0.4}""")).toString().toByteArray())
        val candidate = LocalSelection.fromJson(JSONObject(modelJson.toString())
            .put("settings", JSONObject("""{"temperature":0}""")).toString().toByteArray())
        saveContent { it.withComponents(DraftSelections(listOf(current))) }
        val rules = document("ruleset")
        val rule = JSONObject(rules.getJSONArray("rules").getJSONObject(0).toString())
            .put("id", "rule.fixture.temperature").put("affected_kinds", JSONArray().put("model"))
            .put("applies_when", JSONObject("""{"op":"exists","value":{"ref":{"scope":"selection","kind":"model","role":"primary","pointer":"/id"}}}"""))
            .put("assert", JSONObject("""{"op":"eq","left":{"ref":{"scope":"selection","kind":"model","role":"primary","pointer":"/settings/temperature"}},"right":{"literal":0}}"""))
        rules.put("rules", JSONArray().put(rule))
        val capture = captured(candidate, CompatibleOptionSlot("model", "primary", candidate.id, true), rules)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, capture.option.verdict)
        assertEquals("skipped", capture.result.baselineFindings.single().outcome)
        val before = disk(); browser(capture)
        await { shadowOf(dialog()).message?.toString()?.contains(activity.getString(R.string.alternative_unsupported)) == true }
        assertNull(dialog().findViewById<View>(R.id.alternative_rules)); assertEquals(before, disk())
        click(AlertDialog.BUTTON_NEUTRAL)
        await { shadowOf(dialog()).message?.toString()?.contains(activity.getString(R.string.alternative_unsupported)) == true }
        assertNull(dialog().findViewById<View>(R.id.alternative_rules)); assertEquals(before, disk())
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowLargeTextRtlBrowserKeepsEveryLineAndPartControlReachable() {
        RuntimeEnvironment.setFontScale(2f)
        val picker = compare(addForm()); val menu = open(picker); val before = disk()
        fun layout(window: AlertDialog) {
            val root = window.window!!.decorView
            val width = (320 * activity.resources.displayMetrics.density).toInt()
            val height = (580 * activity.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height); idle()
            assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        }
        fun lines(target: TextView) {
            assertNull(target.ellipsize)
            val textLayout = requireNotNull(target.layout)
            for (index in 0 until textLayout.lineCount) {
                assertEquals(0, textLayout.getEllipsisCount(index))
                val line = Rect(
                    textLayout.getLineLeft(index).toInt() + target.totalPaddingLeft,
                    textLayout.getLineTop(index) + target.totalPaddingTop,
                    kotlin.math.ceil(textLayout.getLineRight(index).toDouble()).toInt() + target.totalPaddingLeft,
                    textLayout.getLineBottom(index) + target.totalPaddingTop)
                if (line.width() == 0) continue
                target.requestRectangleOnScreen(Rect(line), true); idle()
                val position = IntArray(2); target.getLocationOnScreen(position)
                val actual = Rect(line).apply { offset(position[0], position[1]) }; val visible = Rect()
                assertTrue(target.getGlobalVisibleRect(visible))
                assertTrue("Each complete rendered line must be reachable: $actual within $visible", visible.contains(actual))
            }
        }
        layout(menu)
        val scope = menu.findViewById<TextView>(R.id.alternative_scope)
        assertEquals(activity.getString(R.string.alternative_scope), scope.text.toString()); lines(scope)
        for (id in listOf(R.id.alternative_overview, R.id.alternative_rules, R.id.alternative_records)) {
            val target = menu.findViewById<Button>(id)
            assertTrue(target.height >= (48 * activity.resources.displayMetrics.density).toInt())
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true); idle()
            val visible = Rect(); assertTrue(target.getGlobalVisibleRect(visible))
            assertEquals(target.width, visible.width()); assertEquals(target.height, visible.height()); lines(target)
        }
        menu.findViewById<Button>(R.id.alternative_records).performClick(); findField("candidate_context")
        val value = dialog(); layout(value); noLinks(value)
        val message = value.findViewById<TextView>(android.R.id.message); lines(message)
        val next = value.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(activity.getString(R.string.alternative_next_part), next.text.toString())
        assertTrue(next.height >= (48 * activity.resources.displayMetrics.density).toInt()); lines(next)
        next.performClick(); layout(dialog()); noLinks(); lines(dialog().findViewById(android.R.id.message))
        assertEquals(before, disk()); assertTrue(loaded().components.selections.isEmpty())
    }
}
