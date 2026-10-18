package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.preview.*
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Simulated Android lifecycle/resources on the JVM, never installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue("Each test must start in an isolated empty Robolectric sandbox", adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int): Button = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun hasText(resource: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(resource) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun save() {
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()!!
    private fun samples() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL") }

    @Test fun acknowledgedFieldsAndExplicitEmptySurviveRecreation() {
        field(R.string.draft_title).setText("saved title")
        field(R.string.context_objective).setText("objective")
        field(R.string.context_context).setText("")
        save()
        val baseline = loaded().baselineIdentity
        recreate()
        await { hasText(R.string.draft_saved) }
        assertEquals("saved title", field(R.string.draft_title).text.toString())
        assertEquals("objective", field(R.string.context_objective).text.toString())
        assertEquals(PreviewDraftContext("objective", ""), loaded().contextInput)
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains("explicitly empty") })
    }

    @Test fun newerDiskHeadCannotAdoptStalePendingFormAfterRecreation() {
        field(R.string.draft_title).setText("baseline")
        save()
        val original = loaded()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        assertTrue(adapter().save(original, PreviewDraftFields("other writer"), PreviewDraftContext("new disk", null)) is PreviewDraftSave.Saved)
        recreate()
        await { hasText(R.string.draft_reconcile) }
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        button(R.string.reopen_draft).performClick()
        val reopenConfirmation = dialog()
        reopenConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !reopenConfirmation.isShowing && button(R.string.reopen_draft).isEnabled }
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) }
        assertEquals("other writer", field(R.string.draft_title).text.toString())
        assertEquals("new disk", field(R.string.context_objective).text.toString())
    }

    @Test fun tierActivationRequiresCleanFormAndExplicitConfirmation() {
        save()
        fun tier() = views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3:") }
        val original = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending")
        assertFalse(tier().isEnabled)
        save()
        tier().performClick()
        shadowOf(dialog()).clickOnItem(Activation.ENABLED.ordinal)
        val activationConfirmation = dialog()
        activationConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !activationConfirmation.isShowing && tier().isEnabled }
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
        tier().performClick()
        shadowOf(dialog()).clickOnItem(Activation.ENABLED.ordinal)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { tier().isEnabled && tier().text.toString().startsWith("T3: enabled") }
        assertEquals(Activation.ENABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
        assertNotEquals(original, loaded().baselineIdentity)
    }

    @Test fun comparisonDistinguishesLiteralSentinelWithoutWriting() {
        field(R.string.context_objective).setText("")
        save()
        field(R.string.context_objective).setText("explicitly empty")
        save()
        val baseline = loaded().baselineIdentity
        button(R.string.draft_history).performClick()
        val historyDialog = dialog()
        historyDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { dialog() !== historyDialog && dialog().isShowing }
        shadowOf(dialog()).clickOnItem(1)
        await { shadowOf(dialog()).message.toString().contains("Before:") }
        val message = shadowOf(dialog()).message.toString()
        assertTrue(message.contains("Before: explicitly empty"))
        assertTrue(message.contains("After: text: \"explicitly empty\""))
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun editorsExposeLabelsAndSavedStatusUsesTextAndLiveRegion() {
        val editors = views().filterIsInstance<EditText>()
        assertEquals(12, editors.size)
        editors.forEach { editor ->
            val labels = views().filterIsInstance<TextView>().filter { it.labelFor == editor.id }
            assertEquals("Each editor needs one explicit visible label", 1, labels.size)
            assertTrue(labels.single().text.isNotBlank())
            assertTrue(editor.isFocusable)
            assertTrue(editor.isEnabled)
        }
        assertTrue(views().filterIsInstance<TextView>().any { it.isAccessibilityHeading })
        field(R.string.draft_title).setText("semantics check")
        save()
        val status = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.draft_saved) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
        assertTrue(status.text.isNotBlank())
        val tier = views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3:") }
        assertTrue(tier.text.toString().contains(activity.getString(R.string.tier_disabled)))
        assertTrue(tier.isFocusable)
        assertTrue(tier.isEnabled)
    }

    @Test fun confirmedCurrentDraftDeletionRetainsPendingFormAndRevokesSaveAuthority() {
        field(R.string.draft_title).setText("saved before deletion")
        save()
        field(R.string.draft_title).setText("pending title stays visible")
        field(R.string.context_objective).setText("pending objective stays visible")
        button(R.string.library_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        shadowOf(dialog()).clickOnItem(0)
        val entryDialog = dialog()
        entryDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        await { dialog() !== entryDialog && dialog().isShowing }
        val confirmation = dialog()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.library_deleted_editor) && button(R.string.library_open).isEnabled }
        assertEquals("pending title stays visible", field(R.string.draft_title).text.toString())
        assertEquals("pending objective stays visible", field(R.string.context_objective).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertFalse(button(R.string.draft_history).isEnabled)
        val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
        assertTrue((library.list() as PreviewLibraryRead.Available).entries.isEmpty())
        assertEquals(activity.getString(R.string.library_deleted), shadowOf(dialog()).message.toString())
    }

    @Test fun catalogQuerySurvivesDetailsAndRecreationWithoutChangingPinnedSelection() {
        val search = field(R.string.catalog_search)
        search.setText("cat.model.fixture")
        assertEquals(1, samples().size)
        samples().single().performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.selected_sample, "FICTIONAL Model")
        } }
        save()
        val saved = loaded()
        assertEquals("cat.model.fixture", saved.fields.selectedId)

        search.setText("cat.storage.fixture")
        assertEquals(1, samples().size)
        assertTrue(hasText(R.string.draft_saved))
        assertFalse(button(R.string.save_draft).isEnabled)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.selected_sample, "FICTIONAL Model")
        })
        samples().single().performClick()
        val details = dialog()
        details.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !details.isShowing }
        assertEquals("cat.storage.fixture", search.text.toString())
        recreate()
        await { hasText(R.string.draft_saved) && samples().size == 1 }
        assertEquals("cat.storage.fixture", field(R.string.catalog_search).text.toString())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.fields, loaded().fields)
    }

    @Test fun emptyCatalogSearchCanRecoverAndReloadUsesTheCurrentQuery() {
        assertEquals(PreviewCatalogBrowser.PAGE_SIZE, samples().size)
        assertTrue(button(R.string.catalog_next).isEnabled)
        val search = field(R.string.catalog_search)
        search.setText(".* definitely absent")
        assertTrue(samples().isEmpty())
        assertTrue(hasText(R.string.catalog_search_empty))
        search.setText("")
        assertEquals(PreviewCatalogBrowser.PAGE_SIZE, samples().size)
        assertTrue(button(R.string.catalog_next).isEnabled)

        button(R.string.retry_catalog).performClick()
        search.setText("cat.accelerator.fixture")
        await { button(R.string.retry_catalog).isEnabled }
        assertEquals(1, samples().size)
        assertEquals(activity.getString(R.string.item_button, "FICTIONAL Accelerator", "accelerator"),
            samples().single().text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeTextRtlButtonsRetainMeasuredTargetsAndUntruncatedLabels() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        await { button(R.string.save_draft).isEnabled }
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val buttons = views().filterIsInstance<Button>().filter { it.isShown }
        assertTrue(buttons.isNotEmpty())
        buttons.forEach { control ->
            assertTrue("Action label is nonempty", control.text.isNotBlank())
            assertTrue("Visible controls retain a 48dp measured height", control.height >= (48 * density).toInt())
            assertTrue("Visible controls retain a 48dp measured width", control.width >= (48 * density).toInt())
            assertTrue(control.width > control.compoundPaddingLeft + control.compoundPaddingRight)
            val label = control.layout
            assertNotNull(label)
            assertTrue(label.lineCount > 0)
            assertTrue("Native text layout has positive height", label.height > 0)
            assertTrue("Label fits its measured control", label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            repeat(label.lineCount) { line ->
                assertEquals("No ellipsized action label", 0, label.getEllipsisCount(line))
            }
        }
    }

    @Test fun forwardFocusOrderKeepsEditorsAndClearActionReachable() {
        val title = field(R.string.draft_title)
        val objective = field(R.string.context_objective)
        val context = field(R.string.context_context)
        assertTrue(title.requestFocus())
        assertSame(objective, title.focusSearch(View.FOCUS_FORWARD))
        assertTrue(objective.requestFocus())
        assertSame(context, objective.focusSearch(View.FOCUS_FORWARD))
        assertTrue(context.requestFocus())
        assertSame(button(R.string.clear_draft), context.focusSearch(View.FOCUS_FORWARD))
    }
    @Test fun catalogQuerySortAndPageSurviveDetailsAndRecreationWithoutChangingDraft() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending objective stays separate")
        await { button(R.string.catalog_next).isEnabled }
        field(R.string.catalog_search).setText("FICTIONAL")
        fun sort() = views().filterIsInstance<Button>().single { it.text.toString().startsWith("Sort:") }
        sort().performClick()
        shadowOf(dialog()).clickOnItem(PreviewCatalogSort.KIND.ordinal)
        button(R.string.catalog_next).performClick()
        fun itemButtons() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL ") }
        val secondPage = itemButtons().map { it.text.toString() }
        assertEquals(10, secondPage.size)
        itemButtons().first().performClick()
        val details = dialog()
        details.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !details.isShowing }
        assertEquals(baseline, loaded().baselineIdentity)
        recreate()
        await { button(R.string.catalog_previous).isEnabled && !hasText(R.string.draft_loading) }
        assertEquals("FICTIONAL", field(R.string.catalog_search).text.toString())
        assertTrue(sort().text.toString().endsWith(activity.getString(R.string.catalog_sort_kind)))
        assertEquals(secondPage, itemButtons().map { it.text.toString() })
        assertEquals("pending objective stays separate", field(R.string.context_objective).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun catalogFacetsExposeEveryKindAndEmptyMatchesWithoutReplacingSavedSelection() {
        save()
        await { button(R.string.catalog_next).isEnabled }
        fun facet(prefix: String) = views().filterIsInstance<Button>().single { it.text.toString().startsWith(prefix) }
        facet("Kind:").performClick()
        assertEquals(30, dialog().listView.adapter.count)
        val model = PreviewCatalogKinds.all.indexOfFirst { it.id == "model" } + 1
        shadowOf(dialog()).clickOnItem(model)
        facet("Tier:").performClick()
        assertEquals(8, dialog().listView.adapter.count)
        shadowOf(dialog()).clickOnItem(1) // T0 and model are intentionally disjoint.
        assertTrue(hasText(R.string.catalog_no_matches))
        assertFalse(button(R.string.catalog_next).isEnabled)
        facet("Tier:").performClick()
        shadowOf(dialog()).clickOnItem(2) // T1
        views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.item_button, "FICTIONAL Model", "model") }.performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.selected_sample, "FICTIONAL Model") } }
        assertNull(loaded().fields.selectedId) // Selecting a sample edits the form, not disk.
        save()
        val selected = loaded()
        assertEquals("cat.model.fixture", selected.fields.selectedId)
        facet("Tier:").performClick()
        shadowOf(dialog()).clickOnItem(7) // T6
        assertTrue(hasText(R.string.catalog_no_matches))
        assertEquals(selected.baselineIdentity, loaded().baselineIdentity)
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.selected_sample, "FICTIONAL Model") })
        assertFalse(button(R.string.save_draft).isEnabled)
    }

    @Test fun catalogDetailsCannotApplySelectionAfterEditorBaselineChanges() {
        save()
        await { button(R.string.catalog_next).isEnabled }
        views().filterIsInstance<Button>().first { it.text.toString().startsWith("Open FICTIONAL ") }.performClick()
        val detail = dialog()
        // Exercise a superseded callback directly; ordinary modal navigation does not permit these taps.
        field(R.string.draft_title).setText("new acknowledged baseline")
        save()
        val baseline = loaded().baselineIdentity
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !detail.isShowing }
        assertTrue(hasText(R.string.no_selection))
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(loaded().fields.selectedId)
    }

    @Test fun findingsInspectionKeepsOutcomeSeveritySeparateAndNeverWritesDraft() {
        field(R.string.draft_title).setText("pending only")
        button(R.string.findings_inspect).performClick()
        await { button(R.string.findings_browse).isEnabled }
        assertEquals("pending only", field(R.string.draft_title).text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString().contains(activity.getString(R.string.findings_notice)) &&
                it.text.toString().contains("Reported result: valid") && it.text.toString().contains("skipped:")
        })
        button(R.string.findings_browse).performClick()
        val picker = dialog()
        shadowOf(picker).clickOnItem(0)
        await { dialog() !== picker && dialog().isShowing }
        val details = shadowOf(dialog()).message.toString()
        assertTrue(details.contains("Outcome: pass · Severity: error"))
        assertTrue(details.contains("rule.required.foundation"))
        assertTrue(details.contains("sel_model"))
        assertTrue(details.contains(activity.getString(R.string.findings_no_evidence)))
        assertTrue(details.contains(activity.getString(R.string.findings_limits)))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun destroyedCatalogIgnoresRetainedEditorsDialogsAndRows() {
        val retiredCatalog = views().filterIsInstance<CatalogBrowseSection>().single()
        val retiredSearch = field(R.string.catalog_search)
        val retiredRow = samples().first()
        val retiredTier = views().filterIsInstance<Button>().single { it.text.toString().startsWith("Tier:") }
        retiredTier.performClick()
        val pendingDialog = dialog()
        val before = Bundle().also(retiredCatalog::saveState)
        recreate()
        await { button(R.string.save_draft).isEnabled && samples().isNotEmpty() }

        // Deliver callbacks retained from the destroyed Activity, including a pending dialog choice.
        retiredSearch.setText("model")
        shadowOf(pendingDialog).clickOnItem(1)
        pendingDialog.dismiss()
        retiredTier.performClick()
        retiredRow.performClick()

        assertSame("A retired catalog cannot open a new dialog", pendingDialog, dialog())
        assertFalse(pendingDialog.isShowing)
        val after = Bundle().also(retiredCatalog::saveState)
        for (key in listOf("catalogQuery", "catalogTier", "catalogKind", "catalogSort", "catalogSnapshot")) {
            assertEquals(key, before.getString(key), after.getString(key))
        }
        assertEquals(before.getInt("catalogPage"), after.getInt("catalogPage"))
        assertEquals("", field(R.string.catalog_search).text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun destroyedFindingsIgnoreRetainedInspectBrowseAndPickerCallbacks() {
        val retiredInspect = button(R.string.findings_inspect)
        retiredInspect.performClick()
        await { button(R.string.findings_browse).isEnabled }
        val retiredBrowse = button(R.string.findings_browse)
        val retiredOutput = views().filterIsInstance<TextView>().single {
            it.text.toString().startsWith(activity.getString(R.string.findings_notice))
        }
        val before = retiredOutput.text.toString()
        retiredBrowse.performClick()
        val pendingPicker = dialog()
        recreate()
        await { button(R.string.save_draft).isEnabled }

        // None of these callbacks may submit work or open a window for the destroyed owner.
        retiredInspect.performClick()
        shadowOf(pendingPicker).clickOnItem(0)
        pendingPicker.dismiss()
        retiredBrowse.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(pendingPicker, dialog())
        assertFalse(pendingPicker.isShowing)
        assertTrue(retiredInspect.isEnabled)
        assertEquals(before, retiredOutput.text.toString())
        assertFalse(button(R.string.findings_browse).isEnabled)
        assertTrue(hasText(R.string.findings_notice))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogDetailShowsPinnedSpecsEvidenceAndLicenseWithoutSaving() {
        field(R.string.draft_title).setText("pending detail review")
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        val opened = dialog()
        val message = shadowOf(opened).message.toString()
        assertTrue(message.contains("cat.model.fixture"))
        assertTrue(message.contains("2026.10-demo"))
        assertTrue(message.contains("catalog.fixture.1"))
        assertTrue(message.contains("2026-10-16T09:00:00Z"))
        assertTrue(message.contains("provider_ref [string]: \"cat.model_provider.fixture\""))
        assertTrue(message.contains("context_window_tokens [integer]: 8192"))
        assertTrue(message.contains("min_weights_vram_gb [number]: 12"))
        assertTrue(message.contains("spdx_id [string]: \"TEST-ONLY\""))
        assertTrue(message.contains("commercial_use_allowed [boolean]: true"))
        assertTrue(message.contains(activity.getString(R.string.label_user_supplied)))
        assertTrue(message.contains(activity.getString(R.string.catalog_detail_price)))
        assertTrue(message.contains(activity.getString(R.string.catalog_detail_source_limits)))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        opened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !opened.isShowing }
        assertEquals("pending detail review", field(R.string.draft_title).text.toString())
        assertEquals("cat.model.fixture", field(R.string.catalog_search).text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogDetailKeepsLiteralUnknownIdentitySeparateFromUnknownSpec() {
        val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
        root.put("id", "unknown")
        val items = root.getJSONArray("items")
        val model = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "model" }
        model.put("id", "unknown").put("version", "unknown").put("description", "unknown")
        model.getJSONObject("spec").put("license_ref", "unknown")
        model.getJSONArray("provenance").getJSONObject(0).put("notes", "unknown")
        val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        val item = browser.items.single { it.kind == "model" }
        val detail = requireNotNull(browser.details(item, browser.hash))
        val message = CatalogDetailText.describe(activity, detail)
        assertTrue(message.contains(activity.getString(R.string.item_details, "\"unknown\"", "model", "\"unknown\"")))
        assertTrue(message.contains(activity.getString(R.string.catalog_detail_snapshot,
            "\"unknown\"", detail.snapshotRevision.toString(), browser.hash)))
        assertTrue(message.contains(activity.getString(R.string.catalog_detail_description, "\"unknown\"")))
        assertTrue(message.contains("Notes: \"unknown\""))
        assertTrue(message.contains("license_ref [string]: " + activity.getString(R.string.catalog_detail_explicit_unknown)))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogDetailDialogsPreserveLiteralCatalogReferenceIds() {
        await { hasText(R.string.catalog_ready) }
        field(R.string.draft_title).setText("pending reference review")
        data class ReferenceCase(val kind: String, val target: String, val field: String, val array: Boolean)
        for (case in listOf(ReferenceCase("model", "model_provider", "provider_ref", false),
                            ReferenceCase("mcp_server", "tool", "tool_refs", true))) {
            val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
            val items = root.getJSONArray("items")
            fun item(kind: String) = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == kind }
            item(case.target).put("id", "unknown")
            val selected = item(case.kind)
            selected.getJSONObject("spec").put(case.field,
                if (case.array) org.json.JSONArray().put("unknown") else "unknown")
            selected.put("billing_class", "unknown")
            val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
            views().filterIsInstance<CatalogBrowseSection>().single().show(browser)
            field(R.string.catalog_search).setText(selected.getString("id"))
            await { samples().size == 1 }
            samples().single().performClick()
            val opened = dialog()
            val message = shadowOf(opened).message.toString()
            val expected = if (case.array) "${case.field} [array]: [\"unknown\"]" else "${case.field} [string]: \"unknown\""
            assertEquals(expected, message.lineSequence().single { it.startsWith("${case.field} [") })
            assertTrue(message.contains("Recorded billing class: unknown"))
            assertTrue(message.contains(activity.getString(R.string.catalog_detail_price)))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(adapter().load() is PreviewDraftLoad.Missing)
            opened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            await { !opened.isShowing }
            assertEquals("pending reference review", field(R.string.draft_title).text.toString())
            assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        }
    }

    @Test fun catalogDetailRenderingKeepsUnknownAndBoundedLiteralSourceValues() {
        val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
        val items = root.getJSONArray("items")
        val provider = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "model_provider" }
        provider.getJSONObject("spec").put("supported_regions", org.json.JSONArray().put("region-fixture").put("unknown"))
        val sources = org.json.JSONArray()
        provider.put("provenance", sources)
        repeat(10) { index ->
            sources.put(org.json.JSONObject().put("checked_at", "2026-10-16T09:00:00Z").put("verification_status", "verified")
                .put("source_uri", "https://example.invalid/fixture-$index").put("notes", "\u202e" + "x".repeat(200)))
        }
        val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        val item = browser.items.single { it.kind == "model_provider" }
        val message = CatalogDetailText.describe(activity, requireNotNull(browser.details(item, browser.hash)))
        assertTrue(message.contains("contains explicit unknown entries"))
        assertTrue(message.contains(activity.getString(R.string.catalog_detail_missing)))
        assertTrue(message.contains("https://example.invalid/fixture-0"))
        assertTrue(message.contains("Provenance records displayed: 8 / 10"))
        assertTrue(message.contains("[display truncated]"))
        assertTrue(message.contains("\\u202e"))
        assertFalse(message.contains('\u202e'))
        assertTrue(message.contains(activity.getString(R.string.label_verification_unestablished)))
        assertFalse(message.contains(activity.getString(R.string.label_independently_verified)))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun destroyedCostIgnoresRetainedInspectControl() {
        val retiredInspect = button(R.string.cost_inspect)
        val retiredOutput = views().filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.cost_fixture_notice)
        }
        retiredInspect.performClick()
        await { retiredInspect.isEnabled && retiredOutput.text.toString() != activity.getString(R.string.cost_fixture_notice) }
        val before = retiredOutput.text.toString()
        recreate()
        await { button(R.string.save_draft).isEnabled }

        retiredInspect.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(retiredInspect.isEnabled)
        assertEquals(before, retiredOutput.text.toString())
        assertTrue(hasText(R.string.cost_fixture_notice))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @Test fun workloadInputsRetainExactValuesAndInvalidPendingTextAcrossRecreation() {
        field(R.string.workload_budget).setText("12.3400")
        field(R.string.workload_requests).setText("0")
        field(R.string.workload_input_tokens).setText("123456789012345678901234567890")
        save()
        val baseline = loaded().baselineIdentity
        assertEquals("12.3400", loaded().workloadInput.monthlyBudgetAmount!!.encoded)
        assertNull(loaded().workloadInput.currency)
        recreate()
        await { hasText(R.string.draft_saved) }
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertEquals("0", field(R.string.workload_requests).text.toString())
        assertEquals("", field(R.string.workload_region).text.toString())
        field(R.string.workload_budget).setText("invalid amount")
        assertFalse(button(R.string.save_draft).isEnabled)
        assertTrue(hasText(R.string.workload_invalid))
        recreate()
        await { hasText(R.string.draft_unsaved) && button(R.string.reopen_draft).isEnabled }
        assertEquals("invalid amount", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) }
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
    }

    @Test fun workloadRecreationRetainsStalePendingInputUntilExplicitReopen() {
        field(R.string.workload_region).setText("entered baseline")
        save()
        val baseline = loaded()
        field(R.string.workload_region).setText("pending region")
        assertTrue(adapter().save(baseline, baseline.fields,
            workloadInput = baseline.workloadInput.copy(region = "other writer")) is PreviewDraftSave.Saved)
        recreate()
        await { hasText(R.string.draft_reconcile) }
        assertEquals("pending region", field(R.string.workload_region).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals("other writer", loaded().workloadInput.region)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) }
        assertEquals("other writer", field(R.string.workload_region).text.toString())
        field(R.string.workload_region).setText("")
        save()
        assertNull(loaded().workloadInput.region)
    }

    @Test fun classificationIsExplicitAndClearRemainsAnUnsavedWorkloadEdit() {
        save()
        fun classification() = views().filterIsInstance<Button>().single {
            it.text.toString().startsWith(activity.getString(R.string.workload_classification) + ":")
        }
        classification().performClick()
        shadowOf(dialog()).clickOnItem(3) // unanswered, public, internal, confidential
        await { button(R.string.save_draft).isEnabled }
        assertNull(loaded().workloadInput.dataClassification)
        assertFalse(views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3:") }.isEnabled)
        save()
        assertEquals(ai.pickyour.core.drafts.DraftDataClassification.CONFIDENTIAL, loaded().workloadInput.dataClassification)
        recreate()
        await { hasText(R.string.draft_saved) }
        assertTrue(classification().text.toString().endsWith("confidential"))
        button(R.string.clear_draft).performClick()
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue(classification().text.toString().endsWith(activity.getString(R.string.workload_unanswered)))
        assertEquals(ai.pickyour.core.drafts.DraftDataClassification.CONFIDENTIAL, loaded().workloadInput.dataClassification)
        save()
        assertNull(loaded().workloadInput.dataClassification)
    }

    @Test fun oversizedWorkloadPastesNeverSaveAValidPrefix() {
        save()
        val baseline = loaded().baselineIdentity
        fun paste(label: Int, text: String) {
            val editable = field(label).text
            editable.replace(0, editable.length, text)
        }
        paste(R.string.workload_currency, "USDD")
        assertEquals("USDD", field(R.string.workload_currency).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        paste(R.string.workload_currency, "")
        val count = "9".repeat(129)
        paste(R.string.workload_requests, count)
        assertEquals(count, field(R.string.workload_requests).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        paste(R.string.workload_requests, "")
        val budget = "9".repeat(16_385)
        paste(R.string.workload_budget, budget)
        assertEquals(budget, field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        paste(R.string.workload_budget, "12.00")
        paste(R.string.workload_budget, "9".repeat(16_386))
        await { hasText(R.string.workload_rejected) }
        assertEquals("12.00", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        recreate()
        await { hasText(R.string.workload_rejected) && button(R.string.reopen_draft).isEnabled }
        assertEquals("12.00", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
        paste(R.string.workload_budget, "13.00")
        save()
        assertEquals("13.00", loaded().workloadInput.monthlyBudgetAmount!!.encoded)
    }

    @Test fun retiredWorkloadClassificationCallbackCannotOpenAnotherDialog() {
        save()
        val original = loaded().baselineIdentity
        val retired = views().filterIsInstance<Button>().single {
            it.text.toString().startsWith(activity.getString(R.string.workload_classification) + ":")
        }
        recreate()
        await { hasText(R.string.draft_saved) }
        val previousDialog = ShadowAlertDialog.getLatestAlertDialog()
        retired.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(previousDialog, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(original, loaded().baselineIdentity)
    }

    @Test fun retiredWorkloadTextEditsCannotMutateItsModelOrCurrentDraft() {
        field(R.string.workload_region).setText("saved region")
        field(R.string.workload_budget).setText("12.00")
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.workload_region).setText("pending region")
        val retiredRegion = field(R.string.workload_region)
        val retiredBudget = field(R.string.workload_budget)
        val retiredWatchers = shadowOf(retiredBudget).watchers.toList()
        assertTrue(retiredWatchers.isNotEmpty())
        val retiredSection = views().filterIsInstance<WorkloadSection>().single()
        val retiredInputs = retiredSection.inputs
        recreate()
        await { hasText(R.string.draft_unsaved) && button(R.string.save_draft).isEnabled }
        retiredRegion.setText("retired region")
        retiredBudget.setText("invalid retired amount")
        // Deliver an already-retained callback independently of input filtering.
        retiredWatchers.forEach { it.onTextChanged("invalid retired amount", 0, 5, 22) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(retiredInputs, retiredSection.inputs)
        assertEquals("pending region", field(R.string.workload_region).text.toString())
        assertEquals("12.00", field(R.string.workload_budget).text.toString())
        assertFalse(hasText(R.string.workload_invalid))
        assertTrue(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun retiredWorkloadOversizedPasteCannotRejectItsModelOrCurrentDraft() {
        field(R.string.workload_budget).setText("12.00")
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.workload_region).setText("pending region")
        val retiredBudget = field(R.string.workload_budget)
        val retiredSection = views().filterIsInstance<WorkloadSection>().single()
        val retiredInputs = retiredSection.inputs
        recreate()
        await { hasText(R.string.draft_unsaved) && button(R.string.save_draft).isEnabled }
        val retiredText = retiredBudget.text
        retiredText.replace(0, retiredText.length, "9".repeat(16_386))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(retiredInputs, retiredSection.inputs)
        assertFalse(retiredSection.inputs.rejectedEdit)
        assertEquals("pending region", field(R.string.workload_region).text.toString())
        assertEquals("12.00", field(R.string.workload_budget).text.toString())
        assertFalse(hasText(R.string.workload_rejected))
        assertTrue(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun retiredEditorButtonsCannotDispatchWorkOrOpenDialogs() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending survives")
        val retired = listOf(R.string.save_draft, R.string.reopen_draft, R.string.clear_draft,
            R.string.draft_history, R.string.library_open, R.string.library_new, R.string.library_duplicate, R.string.recovery_copy,
            R.string.retry_catalog, R.string.run).map(::button) +
            views().filterIsInstance<Button>().filter { it.text.toString().startsWith("T3:") }
        recreate()
        await { hasText(R.string.draft_unsaved) && button(R.string.save_draft).isEnabled }
        val lastDialog = ShadowAlertDialog.getLatestAlertDialog()
        retired.forEach { it.performClick() }
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(lastDialog, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("pending survives", field(R.string.draft_title).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @Test fun retiredComparisonPickerCannotSubmitToClosedWorker() {
        save()
        val baseline = loaded().baselineIdentity
        button(R.string.draft_history).performClick()
        val history = dialog()
        history.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { dialog() !== history && dialog().isShowing }
        val retiredPicker = dialog()
        recreate()
        await { hasText(R.string.draft_saved) }
        val lastDialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(retiredPicker).clickOnItem(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(lastDialog, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun retiredTierConfirmationCannotCommitAfterRecreation() {
        save()
        val baseline = loaded().baselineIdentity
        views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3:") }.performClick()
        shadowOf(dialog()).clickOnItem(Activation.ENABLED.ordinal)
        val retiredConfirmation = dialog()
        recreate()
        await { hasText(R.string.draft_saved) }
        retiredConfirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
    }

    @Test fun conflictingPendingInputsCanBeCopiedWithoutChangingNewerOriginal() {
        save()
        val baseline = loaded()
        field(R.string.draft_title).setText("pending recovery")
        field(R.string.workload_budget).setText("12.3400")
        field(R.string.workload_requests).setText("0")
        val newer = (adapter().save(baseline, PreviewDraftFields("other writer")) as PreviewDraftSave.Saved).record
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_conflict) && button(R.string.recovery_copy).isEnabled }
        recreate()
        await { hasText(R.string.draft_reconcile) && button(R.string.recovery_copy).isEnabled }
        assertFalse(button(R.string.save_draft).isEnabled)
        button(R.string.recovery_copy).performClick()
        val canceled = dialog()
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !canceled.isShowing && button(R.string.recovery_copy).isEnabled }
        val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
        assertEquals(1, (library.list() as PreviewLibraryRead.Available).entries.size)
        assertEquals("pending recovery", field(R.string.draft_title).text.toString())
        button(R.string.recovery_copy).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled &&
            (PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read() as? PreviewActiveRead.Selected)?.id?.let { it != PreviewDraftId("guest-draft") } == true }
        val selected = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read() as PreviewActiveRead.Selected
        val recovered = (library.open(selected.id).load() as PreviewDraftLoad.Available).record
        assertEquals("pending recovery", recovered.fields.title)
        assertEquals("12.3400", recovered.workloadInput.monthlyBudgetAmount!!.encoded)
        assertEquals(java.math.BigInteger.ZERO, recovered.workloadInput.requestsPerDay)
        assertEquals(0L, recovered.revision)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        recreate()
        await { hasText(R.string.draft_saved) }
        assertEquals("pending recovery", field(R.string.draft_title).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
    }

    @Test fun invalidOrChangedPendingInputsAndRetiredConfirmationCannotCreateRecovery() {
        save()
        field(R.string.draft_title).setText("pending")
        field(R.string.workload_currency).setText("USDD")
        assertFalse(button(R.string.recovery_copy).isEnabled)
        field(R.string.workload_currency).setText("USD")
        button(R.string.recovery_copy).performClick()
        val stale = dialog()
        field(R.string.workload_requests).setText("1".repeat(129))
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !stale.isShowing && button(R.string.library_open).isEnabled }
        assertFalse(button(R.string.recovery_copy).isEnabled)
        assertEquals("1".repeat(129), field(R.string.workload_requests).text.toString())
        field(R.string.workload_requests).setText("0")
        button(R.string.recovery_copy).performClick()
        val retired = dialog()
        recreate()
        await { hasText(R.string.draft_unsaved) }
        retired.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
        assertEquals(1, (library.list() as PreviewLibraryRead.Available).entries.size)
        assertEquals("pending", field(R.string.draft_title).text.toString())
        assertEquals("", loaded().fields.title)
    }

    @Test fun requiredObjectiveGuidanceNavigatesMissingInputsWithoutClaimingReadiness() {
        field(R.string.draft_title).setText("pending title")
        val next = button(R.string.objective_next_input)
        assertTrue(next.isEnabled)
        next.performClick()
        assertTrue(field(R.string.workload_region).hasFocus())
        field(R.string.workload_region).setText("entered region")
        next.performClick()
        assertTrue(field(R.string.workload_currency).hasFocus())
        field(R.string.workload_currency).setText("USD")
        next.performClick()
        shadowOf(dialog()).clickOnItem(1) // explicit public classification
        await { hasText(R.string.objective_inputs_answered) }
        assertFalse(next.isEnabled)
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        field(R.string.workload_budget).setText("invalid")
        assertTrue(hasText(R.string.objective_inputs_invalid))
        assertFalse(next.isEnabled)
        field(R.string.workload_budget).setText("0")
        assertTrue(hasText(R.string.objective_inputs_answered))
        save()
        recreate()
        await { hasText(R.string.draft_saved) && hasText(R.string.objective_inputs_answered) }
        assertFalse(button(R.string.objective_next_input).isEnabled)
    }

    @Test fun retiredObjectiveGuidanceCannotOpenClassificationOrChangeCurrentInputs() {
        field(R.string.workload_region).setText("entered region")
        field(R.string.workload_currency).setText("USD")
        val retired = button(R.string.objective_next_input)
        recreate()
        await { button(R.string.save_draft).isEnabled }
        val before = ShadowAlertDialog.getLatestAlertDialog()
        retired.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(before, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("entered region", field(R.string.workload_region).text.toString())
        assertEquals("USD", field(R.string.workload_currency).text.toString())
        assertTrue(button(R.string.objective_next_input).isEnabled)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun retiredContextEditorsCannotChangeCapturedDomainState() {
        field(R.string.context_objective).setText("saved objective")
        field(R.string.context_context).setText("saved context")
        save()
        val baseline = loaded().baselineIdentity
        val retiredActivity = activity
        val retiredObjective = field(R.string.context_objective)
        val retiredContext = field(R.string.context_context)
        val domain = PreviewActivity::class.java.getDeclaredField("domainContext").apply { isAccessible = true }
        val captured = domain.get(retiredActivity)
        recreate()
        await { hasText(R.string.draft_saved) }
        retiredObjective.setText("late objective")
        retiredContext.setText("late context")
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(captured, domain.get(retiredActivity))
        assertEquals("saved objective", field(R.string.context_objective).text.toString())
        assertEquals("saved context", field(R.string.context_context).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
    }


}
