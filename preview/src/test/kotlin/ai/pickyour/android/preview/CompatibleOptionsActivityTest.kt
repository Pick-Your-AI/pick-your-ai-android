package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
import org.json.JSONObject
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
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Activity → native form/picker → typed evaluator → canonical store, in simulation only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CompatibleOptionsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && text().contains(activity.getString(R.string.draft_saved)) }
        finishWork()
        assertTrue(loaded().components.selections.isEmpty())
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun text(root: View = activity.window.decorView) = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(root: View, label: Int): EditText {
        val caption = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return root.findViewById(caption.labelFor)
    }
    private fun field(form: AlertDialog, label: Int) = field(form.window!!.decorView, label)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            idle(); if (condition()) return; Thread.sleep(10)
        }
        fail("Timed out waiting for actual compatible-picker state")
    }
    private fun worker() = get<ExecutorService>("worker")
    @Suppress("UNCHECKED_CAST")
    private fun <T> get(name: String): T = PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun set(name: String, value: Any) { PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun finishWork() { repeat(4) { idle(); worker().submit {}.get(20, TimeUnit.SECONDS); idle() } }
    private fun awaitSavedRevision(revision: Long) {
        await { !get<Boolean>("storageBusy") && get<PreviewStoredDraft?>("savedRecord")?.revision == revision }
        finishWork()
        assertEquals(revision, loaded().revision)
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
    } }
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun fixtureSelections(): List<LocalSelection> {
        val a = document("build").getJSONArray("selections")
        return (0 until a.length()).map { LocalSelection.fromJson(a.getJSONObject(it).toString().toByteArray()) }
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { button(R.string.component_choose_catalog).isEnabled }
    }
    private fun components(rows: List<LocalSelection>, links: List<LocalSelectionLink> = emptyList()) {
        val store = store(); val old = (store.load() as DraftLoad.Available).draft
        assertTrue(store.save(old.version, old.content.withComponents(DraftSelections(rows, links))) is DraftSave.Saved)
        reopen()
    }
    /** Schema-admitted synthetic replacement, through the same private reader and UI update entry points. */
    private fun installCatalog(value: JSONObject): PreviewCatalogBrowser {
        val bytes = value.toString().toByteArray()
        val input = CatalogFixtureData.read({ bytes }) { activity.assets.open("pricebook.json") }
        val browser = requireNotNull(input.catalog)
        set("catalogFixture", input)
        set("componentCatalog", SelectionCatalogSnapshot.fromJson(bytes))
        get<SelectionEditSection>("componentSection").catalog(browser.items, browser.hash)
        get<CatalogBrowseSection>("catalog").show(browser)
        return browser
    }
    private fun choose(picker: AlertDialog, match: (String) -> Boolean): AlertDialog {
        val index = (0 until picker.listView.adapter.count).first { match(picker.listView.adapter.getItem(it).toString()) }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun addForm(kind: String = "workload", role: String = "primary", settings: String = "{}"): AlertDialog {
        button(R.string.component_choose_catalog).performClick()
        val choices = choose(dialog()) { it.startsWith("$kind ·") }
        val form = choose(choices) { it == activity.getString(R.string.component_add) }
        field(form, R.string.component_role).setText(role)
        field(form, R.string.component_settings).setText(settings)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        return form
    }
    private fun compare(form: AlertDialog, awaitResult: Boolean = true): AlertDialog {
        form.findViewById<Button>(R.id.options_form_compare).performClick()
        val picker = dialog()
        assertNotSame(form, picker)
        if (awaitResult) await { status(picker) != activity.getString(R.string.options_evaluating) }
        return picker
    }
    private fun status(picker: AlertDialog) = picker.findViewById<TextView>(R.id.options_status).text.toString()
    private fun optionButtons(picker: AlertDialog) = views(picker.findViewById(R.id.options_rows)).filterIsInstance<Button>()
        .filter { it.text.toString().startsWith("Use ") }
    private fun confirmChoice(row: Button): AlertDialog {
        row.performClick()
        return dialog().also { assertEquals(activity.getString(R.string.options_confirm), shadowOf(it).title.toString()) }
    }
    private fun blocked(action: (CountDownLatch) -> Unit) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try { action(release) } finally { release.countDown(); finishWork() }
    }

    @Test fun blankSavedDraftKeepsInheritedFoundationFailureAndUsesActualCanonicalAdd() {
        val before = disk(); val previous = loaded().revision
        val form = addForm(); val picker = compare(form)
        assertTrue(status(picker), status(picker).contains("Compatible: 1 · Incompatible: 0 · Unknown: 0"))
        assertFalse(picker.findViewById<CheckBox>(R.id.options_include_incompatible).isChecked)
        assertFalse(picker.findViewById<CheckBox>(R.id.options_include_unknown).isChecked)
        assertEquals(before, disk())
        picker.findViewById<Button>(R.id.options_inherited).performClick()
        val inherited = dialog()
        assertTrue(text(inherited.window!!.decorView).contains("rule.required.foundation\nOutcome: fail"))
        inherited.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val row = optionButtons(picker).single()
        val confirm = confirmChoice(row)
        assertEquals(before, disk())
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitSavedRevision(previous + 1)
        assertFalse(form.isShowing)
        val selected = loaded().components.selections.single()
        assertEquals("workload", selected.kind); assertEquals("primary", selected.role); assertTrue(selected.enabled)
        assertEquals("cat.workload.fixture", selected.catalogItemId)
        val accepted = disk()
        row.callOnClick(); confirm.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick(); finishWork()
        assertEquals("Consumed result cannot add twice", accepted, disk())
    }

    @Test fun actualFrozenRulesExposeUnknownAndConflictingChoicesWithSeparateReasons() {
        components(fixtureSelections().filter { it.kind in setOf("deployment_mode", "model_provider") })
        val c = document("catalog"); val items = c.getJSONArray("items")
        val model = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "model" }
        val mode = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "deployment_mode" }
        mode.getJSONObject("spec").put("mode", "api")
        model.getJSONObject("spec").put("access_modes", org.json.JSONArray().put("api"))
        val bad = JSONObject(model.toString()).put("id", "cat.model.conflict").put("name", "FICTIONAL conflicting model")
        bad.getJSONObject("spec").put("provider_ref", "cat.provider.other").put("access_modes", org.json.JSONArray().put("weights"))
        items.put(bad); installCatalog(c)
        val before = disk(); val form = addForm("model", settings = "{\"serving_mode\":\"api\"}")
        val picker = compare(form)
        assertTrue(status(picker), status(picker).contains("Compatible: 0 · Incompatible: 1 · Unknown: 1"))
        assertTrue(optionButtons(picker).isEmpty())
        picker.findViewById<CheckBox>(R.id.options_include_unknown).isChecked = true
        assertEquals(1, optionButtons(picker).size)
        val unknownRule = views(picker.findViewById(R.id.options_rows)).filterIsInstance<Button>()
            .first { it.text.toString().contains("rule.model.context.fit") }
        unknownRule.performClick()
        val reason = dialog(); val shown = text(reason.window!!.decorView)
        assertTrue(shown.contains("Outcome: unknown")); assertTrue(shown.contains("slot empty, this rule was: skipped"))
        assertTrue(shown.contains("Suggested action:")); reason.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        picker.findViewById<CheckBox>(R.id.options_include_incompatible).isChecked = true
        assertEquals(2, optionButtons(picker).size)
        assertTrue(text(picker.window!!.decorView).contains("rule.model.provider.match"))
        assertTrue(text(picker.window!!.decorView).contains("rule.api.model.access"))
        assertEquals(before, disk())
        val confirmation = confirmChoice(optionButtons(picker).single { it.text.toString().contains("conflicting model") })
        assertTrue(shadowOf(confirmation).message.toString().contains("Incompatible"))
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("Inspection or declining a choice never changes the draft", before, disk())
    }

    @Test fun secondaryReplacementKeepsPrimaryExactAndRetainsRoleIdentitySettingsAndLinks() {
        val selections = fixtureSelections()
        val primary = selections.single { it.kind == "model" }
        val secondary = LocalSelection.fromJson(document("build").getJSONArray("selections").let { values ->
            (0 until values.length()).map(values::getJSONObject).single { it.getString("kind") == "model" }
                .put("id", "sel_model_secondary").put("role", "secondary").toString().toByteArray()
        })
        val link = LocalSelectionLink.fromJson("""{"id":"lnk_primary_secondary","from_selection_id":"sel_model","to_selection_id":"sel_model_secondary","relation":"uses","required":false}""".toByteArray())
        components(listOf(primary, secondary), listOf(link))
        val c = document("catalog"); val items = c.getJSONArray("items")
        val model = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "model" }
        items.put(JSONObject(model.toString()).put("id", "cat.model.alternative").put("name", "FICTIONAL alternative model"))
        installCatalog(c)
        val before = loaded(); button(R.string.component_manage).performClick()
        val actions = choose(dialog()) { it.endsWith(" · ${secondary.id}") }
        val picker = choose(actions) { it == activity.getString(R.string.options_open) }
        await { status(picker) != activity.getString(R.string.options_evaluating) }
        assertTrue(status(picker), status(picker).contains("Compatible: 2 · Incompatible: 0 · Unknown: 0"))
        val first = confirmChoice(optionButtons(picker).single { it.text.toString().contains("alternative model") })
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { dialog() !== first && dialog().isShowing }
        val canonical = dialog()
        assertNotSame(first, canonical)
        canonical.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitSavedRevision(before.revision + 1)
        val after = loaded().components
        assertEquals(primary, after.selections.single { it.id == primary.id })
        val changed = after.selections.single { it.id == secondary.id }
        assertEquals("secondary", changed.role); assertEquals("cat.model.alternative", changed.catalogItemId)
        assertEquals(PreviewSelectionForms.settingsText(secondary), PreviewSelectionForms.settingsText(changed))
        assertEquals(listOf(link), after.links)
    }

    @Test fun roleAndSettingsAbaEditsRetireQueuedPreparationAndOldRetryCannotReviveIt() {
        val form = addForm(); val before = disk()
        blocked { release ->
            val picker = compare(form, false)
            assertEquals(activity.getString(R.string.options_evaluating), status(picker))
            field(form, R.string.component_role).setText("secondary")
            field(form, R.string.component_role).setText("primary")
            field(form, R.string.component_settings).setText("{\"concurrency\":2}")
            field(form, R.string.component_settings).setText("{}")
            assertFalse(picker.isShowing)
            picker.getButton(AlertDialog.BUTTON_NEUTRAL).callOnClick()
            release.countDown(); finishWork()
            assertFalse(picker.isShowing); assertTrue(optionButtons(picker).isEmpty())
        }
        assertEquals(before, disk())
        val fresh = compare(form)
        assertEquals(1, optionButtons(fresh).size)
    }

    @Test fun pendingDraftEditAndRevertDiscardVisibleResultsAndRetainedConfirmation() {
        val form = addForm(); val picker = compare(form)
        val row = optionButtons(picker).single(); val confirmation = confirmChoice(row)
        val before = disk(); val title = field(activity.window.decorView, R.string.draft_title)
        val old = title.text.toString(); title.setText("pending edit"); title.setText(old)
        assertFalse(picker.isShowing); assertFalse(confirmation.isShowing)
        row.callOnClick(); confirmation.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick(); finishWork()
        assertEquals(before, disk()); assertTrue(loaded().components.selections.isEmpty())
        assertEquals(1, optionButtons(compare(form)).size)
    }

    @Test fun actualStoreRevisionConflictCannotBeOverwrittenByAStillVisibleOlderChoice() {
        val form = addForm(); val picker = compare(form)
        val store = store(); val old = (store.load() as DraftLoad.Available).draft
        val content = old.content
        val saved = store.save(old.version, DraftContent(content.objective, "different durable source", content.primaryModel,
            content.optional, content.workload, content.components)) as DraftSave.Saved
        val before = disk()
        confirmChoice(optionButtons(picker).single()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { text().contains(activity.getString(R.string.draft_conflict)) }
        finishWork()
        assertEquals(before, disk())
        assertEquals(saved.draft.version.hash, (store.load() as DraftLoad.Available).draft.version.hash)
        assertTrue(loaded().components.selections.isEmpty()); assertFalse(button(R.string.save_draft).isEnabled)
    }

    @Test fun catalogReloadPauseAndRecreationRetireOldNativeChoiceAuthority() {
        val form = addForm(); var picker = compare(form); val before = disk()
        val oldRow = optionButtons(picker).single()
        button(R.string.retry_catalog).performClick()
        assertFalse(picker.isShowing); oldRow.callOnClick(); finishWork(); assertEquals(before, disk())
        picker = compare(form)
        val confirmation = confirmChoice(optionButtons(picker).single())
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        assertFalse(picker.isShowing); assertFalse(confirmation.isShowing)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).callOnClick()
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        finishWork()
        assertEquals(before, disk())
        assertTrue(loaded().components.selections.isEmpty())
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun pagedRecalculationClearsOldRowsAndPreservesCatalogBrowseContext() {
        val c = document("catalog"); val items = c.getJSONArray("items")
        val workload = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "workload" }
        repeat(11) { n -> items.put(JSONObject(workload.toString()).put("id", "cat.workload.choice$n").put("name", "FICTIONAL workload choice $n")) }
        installCatalog(c)
        val catalog = get<CatalogBrowseSection>("catalog"); catalog.browseKind("workload"); catalog.search("FICTIONAL")
        val original = Bundle().also(catalog::saveState); val before = disk()
        val form = addForm(); val picker = compare(form)
        assertEquals(10, optionButtons(picker).size)
        val stale = optionButtons(picker).first()
        blocked { release ->
            picker.findViewById<Button>(R.id.options_next).performClick()
            assertEquals(activity.getString(R.string.options_evaluating), status(picker))
            assertTrue(optionButtons(picker).isEmpty())
            stale.callOnClick(); assertSame(picker, dialog())
            release.countDown(); finishWork()
        }
        assertEquals(2, optionButtons(picker).size)
        assertTrue(status(picker), status(picker).contains("Page 2 of 2 · 12 choices"))
        val after = Bundle().also(catalog::saveState)
        for (key in listOf("catalogQuery", "catalogTier", "catalogKind", "catalogSort", "catalogSnapshot"))
            assertEquals(key, original.getString(key), after.getString(key))
        assertEquals(original.getInt("catalogPage"), after.getInt("catalogPage"))
        assertEquals(before, disk())
    }

    @Test fun unavailableRoleAndDisabledProposalNeverBecomeApparentlyCompatible() {
        val form = addForm(role = "")
        val picker = compare(form)
        assertEquals(activity.getString(R.string.options_unavailable), status(picker))
        assertTrue(optionButtons(picker).isEmpty())
        field(form, R.string.component_role).setText("primary")
        assertFalse(picker.isShowing)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        form.findViewById<Button>(R.id.options_form_compare).performClick()
        assertTrue(text(form.window!!.decorView).contains(activity.getString(R.string.options_enabled_required)))
        assertTrue(loaded().components.selections.isEmpty())
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        assertEquals(1, optionButtons(compare(form)).size)
    }

    @Test fun disabledOptionalTierIsWithheldUntilAnExplicitSavedActivation() {
        val before = disk()
        val form = addForm("data_source", settings = "{\"enabled\":true}")
        val picker = compare(form)
        assertEquals(activity.getString(R.string.options_optional_unresolved), status(picker))
        assertTrue(optionButtons(picker).isEmpty())
        picker.findViewById<CheckBox>(R.id.options_include_unknown).isChecked = true
        picker.findViewById<CheckBox>(R.id.options_include_incompatible).isChecked = true
        assertTrue(optionButtons(picker).isEmpty())
        assertEquals(before, disk())
        form.dismiss(); finishWork()
        val store = store(); val old = (store.load() as DraftLoad.Available).draft
        val enabled = old.content.withTier(OptionalTier.T3, TierDraft(Activation.ENABLED))
        assertTrue(store.save(old.version, enabled) is DraftSave.Saved)
        reopen()
        val activated = disk()
        val current = compare(addForm("data_source", settings = "{\"enabled\":true}"))
        assertNotEquals(activity.getString(R.string.options_optional_unresolved), status(current))
        assertNotEquals(activity.getString(R.string.options_unavailable), status(current))
        current.findViewById<CheckBox>(R.id.options_include_unknown).isChecked = true
        current.findViewById<CheckBox>(R.id.options_include_incompatible).isChecked = true
        assertEquals(1, optionButtons(current).size)
        assertEquals(activated, disk())
        assertTrue(loaded().components.selections.isEmpty())
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowLargeTextPickerExposesScrollableFiltersStatusAndChoiceTargets() {
        RuntimeEnvironment.setFontScale(1.6f)
        val picker = compare(addForm())
        val root = picker.window!!.decorView
        val width = (320 * activity.resources.displayMetrics.density).toInt()
        val height = (580 * activity.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height); idle()
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, picker.findViewById<TextView>(R.id.options_status).accessibilityLiveRegion)
        val targets = listOf<View>(picker.findViewById<CheckBox>(R.id.options_include_unknown),
            picker.findViewById<Button>(R.id.options_inherited), optionButtons(picker).single())
        targets.forEach { target ->
            assertTrue(target.width > 0 && target.height > 0)
            assertTrue(target.height >= (48 * activity.resources.displayMetrics.density).toInt())
            target.requestFocus(); target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true); idle()
            val visible = Rect()
            assertTrue("Current picker target must intersect its real scrolling viewport", target.getGlobalVisibleRect(visible))
            assertTrue(visible.width() > 0 && visible.height() > 0)
        }
        assertTrue(loaded().components.selections.isEmpty())
    }
}
