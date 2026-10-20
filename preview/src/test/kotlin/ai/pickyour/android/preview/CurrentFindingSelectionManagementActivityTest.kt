package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.DraftCurrentInputs
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Current/save/management/lifecycle integration. SDK28 simulation, not installed/AT evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en +ldltr")
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentFindingSelectionManagementActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val ruleId = "rule.required.foundation"
    private val version = "V".repeat(159) + "\uD83D\uDE00\u202e\n\\\"" + "T".repeat(210) + " VERSION END"
    private val privateNote = "PRIVATE_CONTEXT_NOTES_MUST_NOT_BE_RENDERED"
    private lateinit var installedCatalog: JSONObject

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }
    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun setField(name: String, value: Any) { PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value) }
    private fun worker() = field<ExecutorService>("worker")
    private fun state(): PreviewCurrentDraftState {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return (CurrentDraftCheckSection::class.java.getDeclaredField("analysis").apply { isAccessible = true }
            .get(section) as PreviewCurrentDraftAnalysis).state
    }
    private fun current() = state() as PreviewCurrentDraftState.Current
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root); if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = AutosaveUiClock.idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) { idle(); if (condition()) return; Thread.sleep(10) }
        fail("Timed out waiting for actual current selection context")
    }
    private fun finishWork() { repeat(5) { idle(); worker().submit {}.get(25, TimeUnit.SECONDS); idle() } }
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun loaded() = (PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load() as PreviewDraftLoad.Available).record
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick(); idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        await { !field<Boolean>("storageBusy") && field<Boolean>("storageReady") }
        finishWork(); await { state() is PreviewCurrentDraftState.Current }
    }

    /** Same validated catalog bytes feed all actual Activity authorities; no Current/outcome is fabricated. */
    private fun installInputs(catalog: JSONObject, rules: JSONObject = document("ruleset")): DraftCurrentInputs {
        val bytes = catalog.toString().toByteArray(Charsets.UTF_8)
        val fixture = CatalogFixtureData.read({ bytes }) { activity.assets.open("pricebook.json") }
        val browser = requireNotNull(fixture.catalog)
        val inputs = requireNotNull(DraftCurrentInputs.read(bytes,
            document("dependency-graph").toString().toByteArray(Charsets.UTF_8), rules.toString().toByteArray(Charsets.UTF_8)))
        val catalogSnapshot = SelectionCatalogSnapshot.fromJson(bytes)
        assertEquals(inputs.catalogHash, catalogSnapshot.hash)
        setField("catalogFixture", fixture); setField("componentCatalog", catalogSnapshot); setField("currentCheckInputs", inputs)
        field<SelectionEditSection>("componentSection").catalog(browser.items, browser.hash)
        field<CatalogBrowseSection>("catalog").show(browser)
        finishWork()
        return inputs
    }

    /**
     * Model has frozen cardinality many. Each secondary has its own catalog ID and exact matching
     * version; the existing primary stays on its original item/version. Stored order intentionally
     * differs from the evaluator's sorted affected-ID order. Optional-tier intent is not involved.
     */
    private fun installModels(count: Int = 2, secondaryVersion: String = version,
        disableSecondary: Boolean = false): PreviewCurrentDraftState.Current {
        val prior = state()
        val selections = document("build").getJSONArray("selections")
        val template = (0 until selections.length()).map(selections::getJSONObject).single { it.getString("kind") == "model" }
        val catalog = document("catalog")
        val items = catalog.getJSONArray("items")
        val model = (0 until items.length()).map(items::getJSONObject).single { it.getString("id") == "cat.model.fixture" }
        val rows = (0 until count).map { index ->
            val selection = JSONObject(template.toString()).put("notes", privateNote)
            if (index == 0) selection.put("id", "sel_model_z_primary")
            else {
                val id = "cat.model.context_$index"
                val exactVersion = if (index == 1) secondaryVersion else "context-$index-v1"
                items.put(JSONObject(model.toString()).put("id", id).put("version", exactVersion))
                selection.put("id", "sel_model_a_" + index.toString().padStart(2, '0'))
                    .put("role", "secondary_$index").put("catalog_item_id", id).put("catalog_item_version", exactVersion)
                    .put("enabled", !(disableSecondary && index == 1))
            }
            LocalSelection.fromJson(selection.toString().toByteArray(Charsets.UTF_8))
        }
        installedCatalog = catalog
        val inputs = installInputs(catalog)
        val durable = (store().load() as DraftLoad.Available).draft
        assertTrue(store().save(durable.version, durable.content.withComponents(DraftSelections(rows)), durable.metadata) is DraftSave.Saved)
        reopen()
        val result = current()
        assertNotSame(prior, result)
        val saved = field<PreviewStoredDraft>("savedRecord")
        assertEquals(rows, saved.components.selections)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.revision, result.request.binding.sourceRevision)
        assertEquals(saved.baselineIdentity.substringAfter(':'), result.request.binding.sourceHash.hex)
        assertEquals(inputs.catalogHash, result.request.binding.catalogHash)
        assertEquals(inputs.rulesetHash, result.request.binding.rulesetHash)
        val finding = result.outcome.findings.single { it.ruleId == ruleId }
        assertEquals("Missing foundation choices remain an actual failure", "fail", finding.outcome)
        assertEquals("error", finding.severity)
        assertEquals(rows.filter { it.enabled }.map { it.id }.sorted(), finding.selectionIds)
        if (count > 1 && !disableSecondary) {
            assertEquals(secondaryVersion, saved.components.selections[1].catalogItemVersion)
            assertEquals("sel_model_a_01", finding.selectionIds.first())
        }
        return result
    }

    private fun body(window: AlertDialog) = window.findViewById<TextView>(android.R.id.message).text.toString()
    private fun detail(result: PreviewCurrentDraftState.Current): AlertDialog {
        assertSame(result, state())
        val index = result.outcome.findings.indexOfFirst { it.ruleId == ruleId }
        assertTrue(index >= 0)
        activity.findViewById<Button>(R.id.current_draft_check_inspect).also { assertTrue(it.isEnabled); it.performClick() }
        idle(); var page = dialog(); var offset = 0
        while (index >= offset + 10) {
            page.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); page = dialog(); offset += 10
        }
        assertTrue(page.listView.adapter.getItem(index - offset).toString().contains(ruleId))
        shadowOf(page).clickOnItem(index - offset); idle()
        return dialog().also { assertTrue(it.isShowing); assertTrue(body(it).contains(ruleId)) }
    }
    private fun entry(window: AlertDialog): Button {
        val found = views(window.window!!.decorView).filterIsInstance<Button>()
            .singleOrNull { it.text.toString() == "Inspect affected selections" }
        assertNotNull("An admitted current finding needs its read-only affected-selection destination", found)
        return requireNotNull(found).also { assertTrue(it.isShown); assertTrue(it.isEnabled) }
    }
    private fun context(window: AlertDialog): AlertDialog {
        entry(window).performClick(); idle()
        return dialog().also { assertNotSame(window, it); assertFalse(window.isShowing); assertTrue(it.isShowing) }
    }
    private fun select(window: AlertDialog, index: Int): AlertDialog {
        shadowOf(window).clickOnItem(index); idle()
        return dialog().also { assertNotSame(window, it); assertTrue(it.isShowing) }
    }
    private fun back(window: AlertDialog, which: Int = AlertDialog.BUTTON_NEGATIVE): AlertDialog {
        window.getButton(which).performClick(); idle()
        return dialog().also { assertNotSame(window, it); assertFalse(window.isShowing); assertTrue(it.isShowing) }
    }
    private fun fullVersion(window: AlertDialog): AlertDialog {
        val button = window.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertEquals("Complete version", button.text.toString())
        return back(window, AlertDialog.BUTTON_NEUTRAL)
    }
    private fun visibleButton(window: AlertDialog, which: Int) = window.getButton(which)?.takeIf { it.visibility == View.VISIBLE }
    private fun quoted(window: AlertDialog): String {
        val text = body(window); val first = text.indexOf('"'); val last = text.lastIndexOf('"')
        assertTrue(first >= 0 && last > first)
        assertTrue(text.substring(0, first).all { it == '\u200f' || it == '\u202a' })
        assertTrue(text.substring(last + 1).all { it == '\u202c' || it == '\u200f' })
        return text.substring(first, last + 1)
    }
    private fun readVersion(first: AlertDialog): Pair<String, AlertDialog> {
        var page = first; val text = StringBuilder(); var parts = 0
        while (true) {
            assertTrue(++parts <= 26) // Finite fixture guard, never a production page limit.
            val json = quoted(page)
            assertFalse(json.contains('\n')); assertFalse(json.contains('\u202e'))
            val decoded = JSONArray("[$json]").getString(0)
            assertTrue(decoded.length <= 160); text.append(decoded)
            val next = visibleButton(page, AlertDialog.BUTTON_POSITIVE) ?: break
            next.performClick(); idle(); val replacement = dialog()
            assertNotSame(page, replacement); assertFalse(page.isShowing); page = replacement
        }
        return text.toString() to page
    }

    private fun selectionContext(result: PreviewCurrentDraftState.Current, row: Int = 0): AlertDialog {
        val before = disk()
        val saved = field<PreviewStoredDraft>("savedRecord")
        return select(context(detail(result)), row).also {
            assertSame(result, state())
            assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
            assertEquals(saved.revision, result.request.binding.sourceRevision)
            assertEquals(saved.baselineIdentity.substringAfter(':'), result.request.binding.sourceHash.hex)
            assertEquals("Existing read-only context navigation preserves private draft/library bytes", before, disk())
        }
    }

    private fun manageEntry(window: AlertDialog): Button {
        val value = views(window.window!!.decorView).filterIsInstance<Button>()
            .singleOrNull { it.text.toString() == "Manage this saved selection" }
        assertNotNull("A real Current affected selection needs an explicit exact-selection management route", value)
        return requireNotNull(value).also { assertTrue(it.isShown); assertTrue(it.isEnabled) }
    }

    private fun management(window: AlertDialog): AlertDialog {
        manageEntry(window).performClick(); idle()
        return dialog().also { assertNotSame(window, it); assertFalse(window.isShowing); assertTrue(it.isShowing) }
    }

    private fun assertManagement(window: AlertDialog, id: String, role: String) {
        val title = shadowOf(window).title.toString()
        assertTrue(title, title.contains("model"))
        assertTrue(title, title.contains(id))
        assertTrue(title, title.contains(role))
        assertEquals(listOf("Edit settings", "Edit role", "Disable selection", "Remove selection", "Compare choices for this slot"),
            (0 until window.listView.adapter.count).map { window.listView.adapter.getItem(it).toString() })
    }

    private fun findingValues(result: PreviewCurrentDraftState.Current) = result.outcome.findings.map {
        listOf(it.ruleId, it.outcome, it.severity, it.message, it.remediation, it.selectionIds.toList())
    }

    private fun selectionInputsRemain(expected: PreviewStoredDraft, before: Map<String, List<Byte>>) {
        finishWork()
        assertEquals(expected.baselineIdentity, loaded().baselineIdentity)
        assertEquals(expected.components, loaded().components)
        assertEquals(before, disk())
    }

    private fun allText(window: AlertDialog) = views(window.window!!.decorView)
        .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

    @Test fun reportedSecondarySelectionOpensOnlyItsExistingManagementActions() {
        val result = installModels()
        val saved = field<PreviewStoredDraft>("savedRecord")
        val before = disk(); val findings = findingValues(result)
        val finding = detail(result)
        assertEquals("Close", finding.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Back to observations", finding.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Full text", finding.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        val selected = select(context(finding), 0)
        val original = body(selected)
        assertTrue(original.contains("Selection ID: \"sel_model_a_01\""))
        assertTrue(original.contains("Role: \"secondary_1\""))
        assertTrue(original.contains("Catalog item: \"cat.model.context_1\""))
        assertEquals("Close", selected.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertEquals("Back to affected selections", selected.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("Complete version", selected.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertEquals("Context navigation preserves private draft/library bytes before the new entry", before, disk())
        val entry = manageEntry(selected)
        val versionPages = readVersion(fullVersion(selected))
        assertEquals(version, versionPages.first)
        val returned = back(versionPages.second)
        assertEquals(original, body(returned))
        entry.callOnClick(); idle()
        assertSame("Version navigation retires the previous management control", returned, dialog())
        val actions = management(returned)
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        assertFalse(shadowOf(actions).title.toString().contains("sel_model_z_primary"))
        assertFalse(allText(actions).contains(privateNote))
        assertEquals(findings, findingValues(result))
        selectionInputsRemain(saved, before)
        actions.cancel(); idle()
    }

    @Test fun compareChoicesNeedsAnotherExplicitActionAndKeepsTheExactReportedSlot() {
        val result = installModels(secondaryVersion = "secondary-version-2")
        val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val selected = selectionContext(result)
        val entry = manageEntry(selected)
        val actions = management(selected)
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        assertNull("Management alone does not start a comparison",
            CompatibleOptionsPicker::class.java.getDeclaredField("session").apply { isAccessible = true }
                .get(field<CompatibleOptionsPicker>("compatibleOptions")))
        shadowOf(actions).clickOnItem(4); idle()
        val picker = dialog()
        assertEquals("Compare choices for this slot", shadowOf(picker).title.toString())
        await { picker.findViewById<TextView>(R.id.options_status).text.toString() != activity.getString(R.string.options_evaluating) }
        finishWork()
        val session = requireNotNull(CompatibleOptionsPicker::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(field<CompatibleOptionsPicker>("compatibleOptions")))
        val launch = session.javaClass.getDeclaredField("launch").apply { isAccessible = true }.get(session) as CompatiblePickerLaunch
        assertSame(saved, launch.source)
        assertEquals("model", launch.slot.kind); assertEquals("secondary_1", launch.slot.role)
        assertEquals("sel_model_a_01", launch.slot.selectionId)
        assertEquals("secondary-version-2", saved.components.selections[1].catalogItemVersion)
        assertEquals(result.request.binding.catalogHash.hex, launch.catalogHash)
        assertTrue(allText(picker).contains("selection: sel_model_a_01"))
        assertTrue(allText(picker).contains("role: secondary_1"))
        entry.callOnClick(); idle()
        assertSame("The one-use entry cannot replace an admitted picker", picker, dialog())
        assertTrue(picker.isShowing)
        selectionInputsRemain(saved, before)
        picker.cancel(); idle()
        entry.callOnClick(); idle(); assertFalse(dialog().isShowing)
        assertEquals(before, disk())
    }

    @Test fun noReportedSelectionOffersNoManagementDestination() {
        val result = current()
        val finding = result.outcome.findings.single { it.ruleId == ruleId }
        assertEquals("fail", finding.outcome); assertTrue(finding.selectionIds.isEmpty())
        assertTrue(field<PreviewStoredDraft>("savedRecord").components.selections.isEmpty())
        val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val empty = context(detail(result))
        assertTrue(body(empty).contains("This finding reports no affected selection IDs."))
        assertTrue(views(empty.window!!.decorView).filterIsInstance<Button>()
            .none { it.text.toString() == "Manage this saved selection" })
        empty.cancel(); idle()
        selectionInputsRemain(saved, before)
        assertSame(result, state())
    }

    @Test fun dismissedAndSupersededManagementControlsCannotReviveOrRepeat() {
        val result = installModels(); val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val oldWindow = selectionContext(result); val old = manageEntry(oldWindow)
        oldWindow.cancel(); idle(); old.callOnClick(); idle()
        assertFalse(dialog().isShowing)
        val freshWindow = selectionContext(result); val fresh = manageEntry(freshWindow)
        old.callOnClick(); idle(); assertSame(freshWindow, dialog()); assertTrue(freshWindow.isShowing)
        fresh.performClick(); idle(); val actions = dialog()
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        old.callOnClick(); fresh.callOnClick(); idle()
        assertSame(actions, dialog()); assertTrue(actions.isShowing)
        actions.cancel(); idle(); old.callOnClick(); fresh.callOnClick(); idle()
        assertFalse(dialog().isShowing)
        selectionInputsRemain(saved, before)
        await { state() is PreviewCurrentDraftState.Current }
        val freshActions = management(selectionContext(current()))
        assertManagement(freshActions, "sel_model_a_01", "secondary_1")
        freshActions.cancel(); idle(); assertEquals(before, disk())
    }

    @Test fun siblingInspectionRetiresTheManagementEntryUntilFreshInspection() {
        val result = installModels(); val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val selected = selectionContext(result); val old = manageEntry(selected)
        button(R.string.draft_history).performClick(); idle(); val history = dialog()
        assertTrue(history.isShowing); assertFalse(selected.isShowing)
        old.callOnClick(); idle(); assertSame(history, dialog())
        history.cancel(); idle(); old.callOnClick(); idle(); assertFalse(dialog().isShowing)
        val actions = management(selectionContext(result))
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        selectionInputsRemain(saved, before); actions.cancel(); idle()
    }

    @Test fun retryAndChangedInputsRetireOldManagementAuthorityBeforePublication() {
        val original = installModels(); val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val selected = selectionContext(original); val old = manageEntry(selected)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(25, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            activity.findViewById<Button>(R.id.current_draft_check_retry).performClick()
            assertTrue(state() is PreviewCurrentDraftState.Rechecking)
            old.callOnClick(); idle(); assertFalse(selected.isShowing); assertFalse(dialog().isShowing)
        } finally { release.countDown(); finishWork() }
        await { state() is PreviewCurrentDraftState.Current }
        val retried = current()
        assertNotSame(original.request, retried.request)
        assertEquals(original.request.binding, retried.request.binding)
        val retryWindow = selectionContext(retried); val retryEntry = manageEntry(retryWindow)
        val rules = document("ruleset")
        rules.getJSONArray("rules").getJSONObject(0).put("remediation", "Synthetic changed guidance for navigation admission")
        installInputs(installedCatalog, rules)
        PreviewActivity::class.java.getDeclaredMethod("updateCurrentChecks").apply { isAccessible = true }.invoke(activity)
        finishWork(); await { state() is PreviewCurrentDraftState.Current && state() !== retried }
        assertNotEquals(retried.request.binding.rulesetHash, current().request.binding.rulesetHash)
        old.callOnClick(); retryEntry.callOnClick(); idle(); assertFalse(dialog().isShowing)
        val actions = management(selectionContext(current()))
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        selectionInputsRemain(saved, before); actions.cancel(); idle()
    }

    @Test fun pendingFieldAbaCannotRestoreTheOldManagementControl() {
        val original = installModels(); val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val selected = selectionContext(original); val old = manageEntry(selected)
        val title = field<EditText>("title"); val initial = title.text.toString()
        title.setText("Pending navigation ownership edit")
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        old.callOnClick(); idle(); assertFalse(selected.isShowing); assertFalse(dialog().isShowing)
        assertEquals(before, disk())
        title.setText(initial); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        assertNotSame(original.request, current().request)
        assertEquals(original.request.binding, current().request.binding)
        old.callOnClick(); idle(); assertFalse(dialog().isShowing)
        val actions = management(selectionContext(current()))
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        selectionInputsRemain(saved, before); actions.cancel(); idle()
    }

    @Test fun replacementRemovalAndDisableNeverRedirectAnOldReportedId() {
        val original = installModels()
        val oldWindow = selectionContext(original); val old = manageEntry(oldWindow)
        val replacement = installModels(secondaryVersion = "replacement-exact-version")
        old.callOnClick(); idle(); assertFalse(dialog().isShowing)
        assertTrue(replacement.request.binding.sourceRevision > original.request.binding.sourceRevision)
        val replacedWindow = selectionContext(replacement); val replacedEntry = manageEntry(replacedWindow)
        assertTrue(body(replacedWindow).contains("Captured version: \"replacement-exact-version\""))
        val disabled = installModels(secondaryVersion = "replacement-exact-version", disableSecondary = true)
        assertEquals(listOf("sel_model_z_primary"), disabled.outcome.findings.single { it.ruleId == ruleId }.selectionIds)
        assertTrue(disabled.request.recheck.selectionIds.contains("sel_model_a_01"))
        old.callOnClick(); replacedEntry.callOnClick(); idle(); assertFalse(dialog().isShowing)
        val disabledWindow = selectionContext(disabled)
        assertTrue(body(disabledWindow).contains("Selection ID: \"sel_model_z_primary\""))
        val retainedPrimary = manageEntry(disabledWindow)
        val removed = installModels(count = 1)
        assertEquals(listOf("sel_model_z_primary"), removed.outcome.findings.single { it.ruleId == ruleId }.selectionIds)
        val before = disk(); val saved = field<PreviewStoredDraft>("savedRecord")
        old.callOnClick(); replacedEntry.callOnClick(); retainedPrimary.callOnClick(); idle()
        assertFalse("No old control may fall back to the surviving primary selection", dialog().isShowing)
        val actions = management(selectionContext(removed))
        assertManagement(actions, "sel_model_z_primary", "primary")
        assertFalse(shadowOf(actions).title.toString().contains("sel_model_a_01"))
        selectionInputsRemain(saved, before); actions.cancel(); idle()
    }

    @Test fun storeSwitchPauseAndRecreationNeverRestoreManagementAuthority() {
        val original = installModels(); val initialBytes = disk()
        val selected = selectionContext(original); val old = manageEntry(selected)
        controller.pause()
        old.callOnClick(); idle(); assertFalse(selected.isShowing); assertFalse(dialog().isShowing)
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        controller.resume(); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        old.callOnClick(); idle(); assertFalse(dialog().isShowing)
        assertEquals(initialBytes, disk())
        val resumed = current(); val resumedWindow = selectionContext(resumed); val resumedEntry = manageEntry(resumedWindow)
        val savedState = Bundle()
        controller.pause().stop().saveInstanceState(savedState).destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup(savedState)
        finishWork(); assertTrue(activity.window.decorView.isAttachedToWindow)
        // The recreated Activity reloads bundled inputs; install the same admitted catalog again.
        installInputs(installedCatalog)
        PreviewActivity::class.java.getDeclaredMethod("updateCurrentChecks").apply { isAccessible = true }.invoke(activity)
        finishWork(); await { state() is PreviewCurrentDraftState.Current }
        resumedEntry.callOnClick(); old.callOnClick(); idle(); assertFalse(dialog().isShowing)
        assertNotSame(resumed.request, current().request)
        val freshWindow = selectionContext(current()); val freshEntry = manageEntry(freshWindow)
        button(R.string.library_new).performClick(); idle(); val create = dialog()
        freshEntry.callOnClick(); idle(); assertSame(create, dialog())
        create.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); finishWork()
        await { state() is PreviewCurrentDraftState.Current }
        val switched = current()
        assertNotEquals(original.request.binding.inputDigest, switched.request.binding.inputDigest)
        assertTrue(switched.outcome.findings.single { it.ruleId == ruleId }.selectionIds.isEmpty())
        val afterSwitch = disk()
        old.callOnClick(); resumedEntry.callOnClick(); freshEntry.callOnClick(); idle(); assertFalse(dialog().isShowing)
        val empty = context(detail(switched))
        assertTrue(views(empty.window!!.decorView).filterIsInstance<Button>().none { it.text.toString() == "Manage this saved selection" })
        empty.cancel(); idle(); assertEquals(afterSwitch, disk())
    }

    @Test fun explicitManagementCommitStillRejectsAChangedDurableHead() {
        val result = installModels(secondaryVersion = "cas-version")
        val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val actions = management(selectionContext(result))
        assertManagement(actions, "sel_model_a_01", "secondary_1")
        shadowOf(actions).clickOnItem(2); idle(); val confirmation = dialog()
        assertTrue(body(confirmation).contains("Commit the enabled state for this exact selection?"))
        assertEquals("Commit local revision", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        selectionInputsRemain(saved, before)
        val durable = (store().load() as DraftLoad.Available).draft
        val content = durable.content
        val concurrent = store().save(durable.version, DraftContent(content.objective, "different durable navigation source",
            content.primaryModel, content.optional, content.workload, content.components), durable.metadata) as DraftSave.Saved
        val afterConcurrent = disk()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle(); finishWork()
        await { !field<Boolean>("storageBusy") }
        assertFalse(field<Boolean>("storageReady"))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_conflict) })
        val retained = (store().load() as DraftLoad.Available).draft
        assertEquals(concurrent.draft.version.number, retained.version.number)
        assertEquals(concurrent.draft.version.hash.hex, retained.version.hash.hex)
        assertEquals(saved.components, loaded().components)
        assertTrue(loaded().components.selections.single { it.id == "sel_model_a_01" }.enabled)
        assertEquals(afterConcurrent, disk())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick(); idle(); finishWork()
        assertEquals(afterConcurrent, disk())
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h480dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlManagementActionKeepsCapturedTextAndAllExistingActionsReachable() {
        RuntimeEnvironment.setFontScale(1.6f)
        assertEquals(1.6f, activity.resources.configuration.fontScale, 0.001f)
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val result = installModels(); val saved = field<PreviewStoredDraft>("savedRecord"); val before = disk()
        val selected = selectionContext(result); val original = body(selected)
        val route = manageEntry(selected)
        val root = selected.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (440 * density).toInt()
        val actions = listOf(route, selected.getButton(AlertDialog.BUTTON_NEGATIVE),
            selected.getButton(AlertDialog.BUTTON_POSITIVE), selected.getButton(AlertDialog.BUTTON_NEUTRAL))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height); idle()
        assertEquals(width, root.width); assertEquals(height, root.height)
        val visibleWindow = Rect(); assertTrue(root.getGlobalVisibleRect(visibleWindow))
        val heading = views(root).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.current_finding_selection_title)
        }
        assertTrue(heading.isAccessibilityHeading)
        fun completeLabel(target: TextView) {
            val visible = Rect(); assertTrue(target.getGlobalVisibleRect(visible))
            assertEquals(target.width, visible.width()); assertEquals(target.height, visible.height())
            assertTrue(visibleWindow.contains(visible))
            val layout = requireNotNull(target.layout)
            assertTrue(layout.lineCount > 0)
            assertEquals(target.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(layout.height > 0 && layout.height <= target.height - target.compoundPaddingTop - target.compoundPaddingBottom)
            val left = target.scrollX.toFloat()
            val right = left + target.width - target.compoundPaddingLeft - target.compoundPaddingRight
            repeat(layout.lineCount) { line ->
                assertEquals(0, layout.getEllipsisCount(line))
                assertTrue(layout.getLineLeft(line) >= left && layout.getLineRight(line) <= right &&
                    layout.getLineLeft(line) <= layout.getLineRight(line))
            }
        }
        completeLabel(heading)
        actions.forEach { target ->
            val beforeVisible = Rect()
            val wasFullyVisible = target.getGlobalVisibleRect(beforeVisible) &&
                beforeVisible.width() == target.width && beforeVisible.height() == target.height
            val actionScroll = generateSequence(target.parent as? View) { it.parent as? View }
                .filterIsInstance<ScrollView>().firstOrNull()
            val priorScroll = actionScroll?.scrollY
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true); idle()
            if (!wasFullyVisible) {
                assertNotNull("A clipped action is revealed through its existing native scrolling parent", actionScroll)
                assertNotEquals("Revealing a clipped action must move that native viewport", priorScroll, actionScroll?.scrollY)
            }
            assertEquals(width, root.width); assertEquals(height, root.height)
            assertTrue("Full 48dp target width: " + target.text, target.width >= (48 * density).toInt())
            assertTrue("Full 48dp target height: " + target.text, target.height >= (48 * density).toInt())
            completeLabel(target)
        }
        val message = selected.findViewById<TextView>(android.R.id.message)
        val scroll = generateSequence(message.parent as? View) { it.parent as? View }.filterIsInstance<ScrollView>().first()
        assertTrue(scroll.width > 0 && scroll.height > 0)
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_DOWN); idle()
        assertTrue("The long literal version exercises real message scrolling", scroll.scrollY > 0)
        val visibleMessage = Rect(); assertTrue(message.getLocalVisibleRect(visibleMessage))
        assertEquals(message.height, visibleMessage.bottom)
        assertEquals(original, body(selected))
        route.requestRectangleOnScreen(Rect(0, 0, route.width, route.height), true); idle()
        route.performClick(); idle(); val management = dialog()
        assertManagement(management, "sel_model_a_01", "secondary_1")
        selectionInputsRemain(saved, before)
        management.cancel(); idle()
    }
}
