package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
import ai.pickyour.core.navigation.ExternalLinkPolicy
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
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
    private fun comparisonSection() = views().filterIsInstance<CatalogComparisonSection>().single()
    private fun comparisonAdd(detail: AlertDialog = dialog()) = views(detail.window!!.decorView).filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(R.string.catalog_compare_add) }
    private fun addComparison(kind: String) {
        field(R.string.catalog_search).setText("cat.$kind.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        val opened = dialog()
        comparisonAdd(opened).performClick()
        await { !opened.isShowing }
    }
    private fun comparisonRemovals() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Remove component ") }

    private fun historyRevision(index: Int): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        button(R.string.draft_history).performClick()
        await { dialog() !== previous && dialog().isShowing }
        val picker = dialog()
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }

    private fun restoreConfirmation(index: Int): AlertDialog {
        val detail = historyRevision(index)
        detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        await { dialog() !== detail && dialog().isShowing }
        return dialog()
    }

    /** Settle the actual serial worker before asserting that a retired callback did no work. */
    private fun finishActivityWork() {
        shadowOf(Looper.getMainLooper()).idle()
        val field = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = field.get(activity) as java.util.concurrent.ExecutorService
        worker.submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sourceDetails(vararg sources: String?): PreviewCatalogDetails {
        val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
        val items = root.getJSONArray("items")
        val model = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "model" }
        val provenance = org.json.JSONArray()
        sources.forEach { uri ->
            val source = org.json.JSONObject().put("checked_at", "2026-10-16T09:00:00Z")
                .put("verification_status", "user_supplied")
            if (uri != null) source.put("source_uri", uri)
            provenance.put(source)
        }
        model.put("provenance", provenance)
        val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        return requireNotNull(browser.details(browser.items.single { it.kind == "model" }, browser.hash))
    }

    private fun priceJson() = activity.assets.open("pricebook.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
    private fun recordedPrices(json: org.json.JSONObject) =
        (RecordedPricebook.read(json.toString().byteInputStream()) as RecordedPricebookResult.Parsed).pricebook

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

    @Test fun domainRestoreDisclosesTypedComponentsAndPreservesHistoryAndMetadata() {
        save()
        val store = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
        val initial = (store.load() as DraftLoad.Available).draft
        val fixture = activity.assets.open("build.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
        val selections = fixture.getJSONArray("selections")
        fun selection(kind: String) = (0 until selections.length()).map { selections.getJSONObject(it) }
            .single { it.getString("kind") == kind }
        fun parsed(value: org.json.JSONObject) = LocalSelection.fromJson(bytes = value.toString().toByteArray(Charsets.UTF_8))
        val model = selection("model")
        val provider = selection("model_provider")
        val context = selection("context_policy")
        val links = fixture.getJSONArray("links")
        val providedBy = (0 until links.length()).map { links.getJSONObject(it) }
            .single { it.getString("id") == "link.01" }
        val usesContext = (0 until links.length()).map { links.getJSONObject(it) }
            .single { it.getString("id") == "link.02" }
        fun parsedLink(value: org.json.JSONObject) = LocalSelectionLink.fromJson(bytes = value.toString().toByteArray(Charsets.UTF_8))
        val retainedComponents = DraftSelections(listOf(parsed(model), parsed(provider)), listOf(parsedLink(providedBy)))
        val retained = (store.save(initial.version,
            DraftContent(objective = "retained objective", components = retainedComponents),
            (initial.metadata - "preview.format") + mapOf("preview.title" to "retained title", "test.lock" to "retained-lock")) as DraftSave.Saved).draft
        val changedModel = org.json.JSONObject(model.toString()).put("role", "fallback").put("enabled", false).also {
            it.getJSONObject("settings").put("max_output_tokens", 256)
        }
        val currentComponents = DraftSelections(listOf(parsed(changedModel), parsed(provider), parsed(context)),
            listOf(parsedLink(org.json.JSONObject(providedBy.toString()).put("required", true)), parsedLink(usesContext)))
        val current = (store.save(retained.version,
            DraftContent(objective = "current objective", context = "current context", components = currentComponents),
            initial.metadata + mapOf("preview.title" to "current title", "test.lock" to "current-lock")) as DraftSave.Saved).draft
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { hasText(R.string.draft_saved) && field(R.string.draft_title).text.toString() == "current title" }

        assertNull(loaded().history.single { it.revision == retained.version.number }.fields)
        val unavailable = historyRevision(1)
        assertEquals(activity.getString(R.string.history_unavailable_detail), shadowOf(unavailable).message.toString())
        assertEquals(activity.getString(R.string.close), unavailable.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        val review = unavailable.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertEquals(activity.getString(R.string.domain_restore_preview), review.text.toString())
        unavailable.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !unavailable.isShowing }
        review.performClick()
        finishActivityWork()
        assertSame(unavailable, dialog())
        assertFalse(unavailable.isShowing)
        assertEquals(current.version.number, loaded().revision)

        val confirmation = restoreConfirmation(1)
        val message = shadowOf(confirmation).message.toString()
        assertTrue(message.contains("Typed selections: 3 saved now, 2 in the retained revision."))
        assertTrue(message.contains("Relationships: 2 saved now, 1 in the retained revision."))
        assertTrue(message.contains("catalog versions, roles, enabled states, settings and recorded extra fields"))
        assertTrue(message.contains("Matching counts do not mean matching content."))
        assertTrue(message.contains("typed components and their relationships"))
        assertEquals(current.version.number, loaded().revision)
        val commit = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        commit.performClick()
        await { hasText(R.string.draft_saved) && loaded().revision == current.version.number + 1 }
        val restored = (store.load() as DraftLoad.Available).draft
        assertEquals(retained.content, restored.content)
        assertEquals(current.metadata, restored.metadata)
        assertEquals(current.history.size + 1, restored.history.size)
        assertEquals(StoredRevisionChange.RESTORE, restored.history.last().change)
        assertEquals(retained.version.number, restored.history.last().restoredFrom)
        for (old in listOf(initial, retained, current)) {
            val unchanged = (store.revision(old.version.hash) as DraftLoad.Available).draft
            assertEquals(old.version.hash, unchanged.version.hash)
            assertEquals(old.content, unchanged.content)
            assertEquals(old.metadata, unchanged.metadata)
            assertEquals(old.history.map { it.number }, unchanged.history.map { it.number })
        }
        assertEquals("current title", field(R.string.draft_title).text.toString())
        assertEquals("retained objective", field(R.string.context_objective).text.toString())
        commit.performClick()
        finishActivityWork()
        assertEquals(restored.version.number, loaded().revision)
        recreate()
        await { hasText(R.string.draft_saved) }
        assertEquals(restored.version.number, loaded().revision)
        assertEquals(retainedComponents, loaded().components)
        field(R.string.draft_title).setText("edit after restore")
        save()
        assertEquals(restored.version.number + 1, loaded().revision)
        assertEquals(retainedComponents, loaded().components)
        assertEquals(current.metadata, (store.revision(restored.version.hash) as DraftLoad.Available).draft.metadata)
    }

    @Test fun cancelledHistoryDialogsCannotReplaceFieldsOrReopenChildren() {
        field(R.string.draft_title).setText("retained title")
        save()
        field(R.string.draft_title).setText("current title")
        save()
        val baseline = loaded().baselineIdentity
        button(R.string.draft_history).performClick()
        val canceledHistory = dialog()
        val compare = canceledHistory.getButton(AlertDialog.BUTTON_POSITIVE)
        canceledHistory.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !canceledHistory.isShowing }
        shadowOf(canceledHistory).clickOnItem(1)
        compare.performClick()
        finishActivityWork()
        assertSame(canceledHistory, dialog())
        assertFalse(canceledHistory.isShowing)

        val canceledDetail = historyRevision(1)
        val copy = canceledDetail.getButton(AlertDialog.BUTTON_POSITIVE)
        val review = canceledDetail.getButton(AlertDialog.BUTTON_NEUTRAL)
        canceledDetail.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !canceledDetail.isShowing }
        field(R.string.draft_title).setText("pending title")
        copy.performClick()
        review.performClick()
        finishActivityWork()
        assertSame(canceledDetail, dialog())
        assertFalse(canceledDetail.isShowing)
        assertEquals("pending title", field(R.string.draft_title).text.toString())

        button(R.string.draft_history).performClick()
        val history = dialog()
        history.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { dialog() !== history && dialog().isShowing }
        val canceledPicker = dialog()
        canceledPicker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !canceledPicker.isShowing }
        shadowOf(canceledPicker).clickOnItem(1)
        finishActivityWork()
        assertSame(canceledPicker, dialog())
        assertFalse(canceledPicker.isShowing)
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(hasText(R.string.draft_unsaved))
    }

    @Test fun cancelledDomainRestoreCannotCommitOrReplacePendingInputs() {
        field(R.string.context_objective).setText("retained objective")
        save()
        field(R.string.context_objective).setText("current objective")
        save()
        val baseline = loaded().baselineIdentity
        val confirmation = restoreConfirmation(1)
        val commit = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !confirmation.isShowing }
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_requests).setText("17")
        commit.performClick()
        finishActivityWork()
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        assertEquals("17", field(R.string.workload_requests).text.toString())
        assertTrue(hasText(R.string.draft_unsaved))
        assertTrue(button(R.string.save_draft).isEnabled)
        recreate()
        await { hasText(R.string.draft_unsaved) && button(R.string.save_draft).isEnabled }
        assertEquals(baseline, loaded().baselineIdentity)
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        assertEquals("17", field(R.string.workload_requests).text.toString())
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

    @Test fun catalogComparisonTrayRetainsExactPinsAcrossDetailsBackAndRecreationWithoutSaving() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending comparison title")
        field(R.string.workload_budget).setText("12.00")
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        val dismissedDetail = dialog()
        val dismissedAdd = comparisonAdd(dismissedDetail)
        dismissedDetail.onBackPressed()
        await { !dismissedDetail.isShowing }
        dismissedAdd.performClick()
        assertTrue(comparisonRemovals().isEmpty())
        addComparison("model")
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        addComparison("accelerator")
        assertTrue(button(R.string.catalog_compare_open).isEnabled)
        val captured = Bundle().also { comparisonSection().save(it) }
        assertEquals(listOf("cat.model.fixture", "cat.accelerator.fixture"), captured.getStringArrayList("comparisonIds"))
        addComparison("workload")
        assertEquals(2, comparisonRemovals().size)
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains(activity.getString(R.string.catalog_compare_full)) })
        samples().single().performClick()
        val detail = dialog()
        detail.onBackPressed()
        await { !detail.isShowing }
        recreate()
        await { button(R.string.catalog_compare_open).isEnabled }
        val restored = Bundle().also { comparisonSection().save(it) }
        assertEquals(captured.getStringArrayList("comparisonIds"), restored.getStringArrayList("comparisonIds"))
        assertEquals(captured.getStringArrayList("comparisonVersions"), restored.getStringArrayList("comparisonVersions"))
        assertEquals(captured.getStringArrayList("comparisonHashes"), restored.getStringArrayList("comparisonHashes"))
        assertEquals("cat.workload.fixture", field(R.string.catalog_search).text.toString())
        assertEquals("pending comparison title", field(R.string.draft_title).text.toString())
        assertEquals("12.00", field(R.string.workload_budget).text.toString())
        assertTrue(hasText(R.string.no_selection))
        assertEquals(baseline, loaded().baselineIdentity)
        val removedControl = comparisonRemovals().first()
        removedControl.performClick()
        assertEquals(1, comparisonRemovals().size)
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        assertEquals(listOf("cat.accelerator.fixture"), Bundle().also { comparisonSection().save(it) }.getStringArrayList("comparisonIds"))
        addComparison("model")
        removedControl.performClick()
        assertEquals(listOf("cat.accelerator.fixture", "cat.model.fixture"),
            Bundle().also { comparisonSection().save(it) }.getStringArrayList("comparisonIds"))
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun catalogComparisonDifferencesKeepVersionsConditionsAndUnknownFieldsVisible() {
        val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
        val items = root.getJSONArray("items")
        val model = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "model" }
        model.getJSONObject("spec").remove("supports_fine_tuning")
        model.getJSONObject("spec").put("min_weights_vram_gb", 0)
        val newer = org.json.JSONObject(model.toString()).put("version", "successor")
        newer.getJSONObject("spec").put("supported_precisions", org.json.JSONArray().put("int8"))
        items.put(newer)
        val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        views().filterIsInstance<CatalogBrowseSection>().single().show(browser)
        comparisonSection().show(browser)
        field(R.string.catalog_search).setText("cat.model.fixture")
        assertEquals(2, samples().size)
        samples().first().performClick()
        val firstDetail = dialog()
        comparisonAdd(firstDetail).performClick()
        await { !firstDetail.isShowing }
        samples().last().performClick()
        val secondDetail = dialog()
        comparisonAdd(secondDetail).performClick()
        await { !secondDetail.isShowing }
        button(R.string.catalog_compare_open).performClick()
        val comparison = dialog()
        fun texts() = views(comparison.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        val sameField = activity.getString(R.string.catalog_compare_field, activity.getString(R.string.catalog_compare_spec), "min_weights_vram_gb")
        val unknownField = activity.getString(R.string.catalog_compare_field, activity.getString(R.string.catalog_compare_spec), "supports_fine_tuning")
        assertTrue(sameField in texts())
        assertTrue(unknownField in texts())
        assertTrue(texts().any { it.contains("Version: \"2026.10-demo\"") })
        assertTrue(texts().any { it.contains("Version: \"successor\"") })
        val toggle = views(comparison.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single()
        toggle.performClick()
        assertTrue(toggle.isChecked)
        assertFalse(sameField in texts())
        assertTrue(unknownField in texts())
        assertTrue(texts().any { it.contains("provider_ref [string]: \"cat.model_provider.fixture\"") })
        assertTrue(texts().any { it.contains("supported_precisions [array]: [\"int8\"]") })
        assertTrue(texts().any { it.contains(activity.getString(R.string.catalog_detail_price)) })
        assertTrue(texts().any { it.contains(activity.getString(R.string.catalog_compare_unknown)) })
        comparison.onBackPressed()
        assertTrue(Bundle().also { comparisonSection().save(it) }.getBoolean("comparisonDifferencesOnly"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogComparisonPreservesRecordedPriceSnapshotsAndUnknownRecoveryWithoutSaving() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending combined price comparison")
        addComparison("compute_profile")
        addComparison("storage")
        val pinned = Bundle().also { comparisonSection().save(it) }
        val catalogBytes = activity.assets.open("catalog.json").use { it.readBytes() }
        val browser = requireNotNull(PreviewCatalogBrowser.read(catalogBytes))
        val book = activity.assets.open("pricebook.json").use {
            (RecordedPricebook.read(it) as RecordedPricebookResult.Parsed).pricebook
        }
        button(R.string.catalog_compare_open).performClick()
        val opened = dialog()
        fun contexts(value: AlertDialog) = views(value.window!!.decorView).filterIsInstance<TextView>()
            .map { it.text.toString() }.filter { it.contains(activity.getString(R.string.catalog_detail_price)) }
        val original = contexts(opened)
        assertEquals(2, original.size)
        original.forEach { text ->
            assertTrue(text.contains("Pricebook: \"prices.fixture.1\""))
            assertTrue(text.contains("SHA-256: ${book.hash}"))
            assertTrue(text.contains("Catalog item version: \"2026.10-demo\""))
            assertTrue(text.contains(activity.getString(R.string.catalog_price_notice)))
            assertTrue(text.contains(activity.getString(R.string.catalog_price_conditions_missing)))
        }
        assertTrue(original[0].contains("Meter: gpu_hour"))
        assertTrue(original[0].contains("Recorded unit_price.amount: \"0.80\""))
        assertFalse(original[0].contains("rate.storage.fixture"))
        assertTrue(original[1].contains("Meter: storage_gb_month"))
        assertTrue(original[1].contains("Recorded unit_price.amount: \"0.05\""))
        assertFalse(original[1].contains("rate.compute_profile.fixture"))
        views(opened.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single().performClick()
        assertEquals(original, contexts(opened))
        opened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !opened.isShowing }

        // A failed fresh price read replaces the old book; matching catalog pins remain available.
        comparisonSection().show(browser, null)
        button(R.string.catalog_compare_open).performClick()
        val unavailable = dialog()
        val unknown = contexts(unavailable)
        assertEquals(2, unknown.size)
        unknown.forEach { text ->
            assertTrue(text.contains(activity.getString(R.string.catalog_price_unavailable)))
            assertFalse(text.contains("Recorded unit_price.amount:"))
            assertFalse(text.contains(book.hash))
        }
        unavailable.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !unavailable.isShowing }
        comparisonSection().show(browser, book)
        button(R.string.catalog_compare_open).performClick()
        assertEquals(original, contexts(dialog()))
        recreate()
        await { button(R.string.catalog_compare_open).isEnabled }
        button(R.string.catalog_compare_open).performClick()
        assertEquals(original, contexts(dialog()))
        val restored = Bundle().also { comparisonSection().save(it) }
        for (key in listOf("comparisonIds", "comparisonVersions", "comparisonHashes")) {
            assertEquals(pinned.getStringArrayList(key), restored.getStringArrayList(key))
        }
        assertEquals("pending combined price comparison", field(R.string.context_objective).text.toString())
        assertTrue(hasText(R.string.no_selection))
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun catalogComparisonKeepsUnavailablePinsAndRejectsAnOldDetailAfterSnapshotReplacement() {
        addComparison("model")
        addComparison("accelerator")
        samples().single().performClick()
        val oldDetail = dialog()
        val pinState = Bundle().also { comparisonSection().save(it) }
        val bytes = activity.assets.open("catalog.json").use { it.readBytes() }
        val original = requireNotNull(PreviewCatalogBrowser.read(bytes))
        val root = org.json.JSONObject(bytes.toString(Charsets.UTF_8)).put("revision", 2)
        val replacement = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        views().filterIsInstance<CatalogBrowseSection>().single().show(replacement)
        comparisonSection().show(replacement)
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        comparisonAdd(oldDetail).performClick()
        await { !oldDetail.isShowing }
        assertEquals(2, comparisonRemovals().size)
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.catalog_compare_unavailable) })
        val after = Bundle().also { comparisonSection().save(it) }
        assertEquals(pinState.getStringArrayList("comparisonHashes"), after.getStringArrayList("comparisonHashes"))
        comparisonSection().show(original)
        assertTrue(button(R.string.catalog_compare_open).isEnabled)
        comparisonSection().show(null)
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        comparisonRemovals().last().performClick()
        assertEquals(listOf("cat.model.fixture"), Bundle().also { comparisonSection().save(it) }.getStringArrayList("comparisonIds"))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun retiredCatalogComparisonControlsCannotMutateStateOrOpenDialogs() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending comparison objective")
        addComparison("model")
        addComparison("accelerator")
        val retiredSection = comparisonSection()
        val retiredRemove = comparisonRemovals().first()
        val retiredOpen = button(R.string.catalog_compare_open)
        retiredOpen.performClick()
        val comparison = dialog()
        val retiredToggle = views(comparison.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single()
        comparison.onBackPressed()
        await { !comparison.isShowing }
        retiredToggle.performClick()
        assertFalse(Bundle().also { retiredSection.save(it) }.getBoolean("comparisonDifferencesOnly"))
        field(R.string.catalog_search).setText("cat.workload.fixture")
        samples().single().performClick()
        val retiredAdd = comparisonAdd()
        val before = Bundle().also { retiredSection.save(it) }
        recreate()
        await { button(R.string.catalog_compare_open).isEnabled }
        val previousDialog = ShadowAlertDialog.getLatestAlertDialog()
        retiredRemove.performClick()
        retiredAdd.performClick()
        retiredToggle.performClick()
        retiredOpen.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(previousDialog, ShadowAlertDialog.getLatestAlertDialog())
        val retiredAfter = Bundle().also { retiredSection.save(it) }
        val current = Bundle().also { comparisonSection().save(it) }
        for (state in listOf(retiredAfter, current)) {
            assertEquals(before.getStringArrayList("comparisonIds"), state.getStringArrayList("comparisonIds"))
            assertEquals(before.getStringArrayList("comparisonVersions"), state.getStringArrayList("comparisonVersions"))
            assertFalse(state.getBoolean("comparisonDifferencesOnly"))
        }
        assertEquals("pending comparison objective", field(R.string.context_objective).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun invalidCatalogComparisonRestorationIsExplicitAndPreservesPendingEditorInputs() {
        field(R.string.draft_title).setText("pending title survives invalid tray")
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        state.putStringArrayList("comparisonIds", arrayListOf("cat.model.fixture"))
        state.putStringArrayList("comparisonVersions", arrayListOf())
        state.putStringArrayList("comparisonHashes", arrayListOf("a".repeat(64)))
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains(activity.getString(R.string.catalog_compare_reset)) })
        assertTrue(comparisonRemovals().isEmpty())
        assertFalse(button(R.string.catalog_compare_open).isEnabled)
        assertEquals("pending title survives invalid tray", field(R.string.draft_title).text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun nonStringCatalogComparisonPinsResetWithoutLosingPendingDraftInputs() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending typed tray recovery")
        field(R.string.context_objective).setText("pending objective")
        field(R.string.workload_budget).setText("12.00")
        val bytes = activity.assets.open("catalog.json").use { it.readBytes() }
        val hash = requireNotNull(PreviewCatalogBrowser.read(bytes)).hash
        for (malformedKey in listOf("comparisonIds", "comparisonVersions", "comparisonHashes")) {
            val state = Bundle()
            controller.pause().saveInstanceState(state).stop().destroy()
            state.putStringArrayList("comparisonIds", arrayListOf("cat.model.fixture"))
            state.putStringArrayList("comparisonVersions", arrayListOf("2026.10-demo"))
            state.putStringArrayList("comparisonHashes", arrayListOf(hash))
            // Bundle's generic getter checks the outer ArrayList, not its elements.
            state.putIntegerArrayList(malformedKey, arrayListOf(1))
            controller = Robolectric.buildActivity(PreviewActivity::class.java)
            controller.create(state).start().resume().visible()
            await { button(R.string.save_draft).isEnabled }
            assertTrue(views().filterIsInstance<TextView>().any {
                it.text.toString().contains(activity.getString(R.string.catalog_compare_reset))
            })
            assertTrue(comparisonRemovals().isEmpty())
            assertFalse(button(R.string.catalog_compare_open).isEnabled)
            assertEquals("pending typed tray recovery", field(R.string.draft_title).text.toString())
            assertEquals("pending objective", field(R.string.context_objective).text.toString())
            assertEquals("12.00", field(R.string.workload_budget).text.toString())
            assertEquals(baseline, loaded().baselineIdentity)
            val restored = Bundle().also { comparisonSection().save(it) }
            for (key in listOf("comparisonIds", "comparisonVersions", "comparisonHashes")) {
                assertTrue(requireNotNull(restored.getStringArrayList(key)).isEmpty())
            }
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun catalogDetailsAndComparisonEscapeUnicodeSeparatorsWithoutChangingRecordedValues() {
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending literal comparison")
        val root = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { org.json.JSONObject(it.readText()) }
        val items = root.getJSONArray("items")
        val provider = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "model_provider" }
        provider.getJSONObject("spec").put("supported_regions", org.json.JSONArray()
            .put("region\u2028line").put("region\u2029paragraph").put("unknown"))
        provider.put("license", org.json.JSONObject().put("spdx_id", "TEST\u2028ONLY\u2029LITERAL"))
        val newer = org.json.JSONObject(provider.toString()).put("version", "literal-successor")
        newer.getJSONObject("spec").put("supported_regions", org.json.JSONArray()
            .put("region\u2028line").put("region\u2029paragraph"))
        items.put(newer)
        val browser = requireNotNull(PreviewCatalogBrowser.read(root.toString().toByteArray(Charsets.UTF_8)))
        val details = browser.items.filter { it.kind == "model_provider" }.map {
            requireNotNull(browser.details(it, browser.hash))
        }
        val recorded = details.map { detail -> (detail.specs + detail.license).map { it.valueJson } }
        assertEquals(2, recorded.size)
        assertTrue(recorded.flatten().filterNotNull().any { it.contains('\u2028') && it.contains('\u2029') })
        fun assertEscaped(text: String) {
            assertTrue(text.contains("\\u2028"))
            assertTrue(text.contains("\\u2029"))
            assertFalse(text.contains('\u2028'))
            assertFalse(text.contains('\u2029'))
        }
        views().filterIsInstance<CatalogBrowseSection>().single().show(browser)
        comparisonSection().show(browser)
        field(R.string.catalog_search).setText("cat.model_provider.fixture")
        assertEquals(2, samples().size)
        samples().toList().forEach { sample ->
            sample.performClick()
            val detail = dialog()
            assertEscaped(shadowOf(detail).message.toString())
            comparisonAdd(detail).performClick()
            await { !detail.isShowing }
        }
        assertTrue(button(R.string.catalog_compare_open).isEnabled)
        button(R.string.catalog_compare_open).performClick()
        val comparison = dialog()
        fun text() = views(comparison.window!!.decorView).filterIsInstance<TextView>()
            .joinToString("\n") { it.text.toString() }
        assertEscaped(text())
        views(comparison.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single().performClick()
        assertEscaped(text())
        assertEquals(recorded, details.map { detail -> (detail.specs + detail.license).map { it.valueJson } })
        val pins = Bundle().also { comparisonSection().save(it) }
        assertEquals(listOf(browser.hash, browser.hash), pins.getStringArrayList("comparisonHashes"))
        assertEquals("pending literal comparison", field(R.string.draft_title).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun catalogComparisonStackedHeadingsAndToggleRemainMeasuredAtNarrowRtlLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        await { button(R.string.save_draft).isEnabled }
        addComparison("model")
        addComparison("accelerator")
        button(R.string.catalog_compare_open).performClick()
        val root = dialog().window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val headings = views(root).filterIsInstance<TextView>().filter { it.isAccessibilityHeading }
        assertTrue(headings.size > 3)
        // Component numbers follow the configured locale; exact pins remain visible in either direction.
        assertTrue(headings.any { it.text.toString().contains("cat.model.fixture") && it.text.toString().contains("2026.10-demo") })
        assertTrue(headings.any { it.text.toString().contains("cat.accelerator.fixture") && it.text.toString().contains("2026.10-demo") })
        headings.forEach { heading ->
            assertTrue(heading.width in 1..width)
            assertNotNull(heading.layout)
            assertTrue(heading.layout.lineCount > 0)
            assertTrue(heading.layout.height <= heading.height - heading.compoundPaddingTop - heading.compoundPaddingBottom)
            repeat(heading.layout.lineCount) { assertEquals(0, heading.layout.getEllipsisCount(it)) }
        }
        val toggle = views(root).filterIsInstance<android.widget.CheckBox>().single()
        assertTrue(toggle.height >= (48 * density).toInt())
        assertTrue(toggle.width >= (48 * density).toInt())
        val node = toggle.createAccessibilityNodeInfo()
        assertTrue(node.isCheckable)
        assertFalse(node.isChecked)
        assertEquals(activity.getString(R.string.catalog_compare_differences), node.text.toString())
        assertTrue(toggle.isFocusable)
        assertNull(shadowOf(activity).nextStartedActivity)
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

    @Test fun catalogSourceChooserIsExplicitAndMissingUriCannotDispatch() {
        field(R.string.draft_title).setText("pending source review")
        field(R.string.context_objective).setText("pending source objective")
        field(R.string.context_context).setText("pending source context")
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val details = dialog()
        assertNull(shadowOf(activity).nextStartedActivity)
        details.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val sources = dialog()
        assertNotSame(details, sources)
        // The old detail dialog no longer authorizes another source flow.
        details.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(sources, dialog())
        val before = Bundle().also { controller.saveInstanceState(it) }
        details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val after = Bundle().also { controller.saveInstanceState(it) }
        for (key in listOf("title", "objective", "contextInput", "selectedId", "selectedVersion",
            "selectedCatalogHash", "draftId", "draftBaseline", "catalogSnapshot")) {
            assertEquals(key, before.getString(key), after.getString(key))
        }
        assertSame(sources, dialog())
        assertTrue(hasText(R.string.no_selection))
        assertEquals(1, sources.listView.adapter.count)
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(sources).clickOnItem(0)
        assertEquals(activity.getString(R.string.catalog_source_missing), shadowOf(dialog()).message.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("pending source review", field(R.string.draft_title).text.toString())
        assertEquals("cat.model.fixture", field(R.string.catalog_search).text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogSourceConfirmationDispatchesOnlyExactUriAndNoDraftData() {
        field(R.string.draft_title).setText("PRIVATE_DRAFT_SENTINEL")
        field(R.string.context_objective).setText("PRIVATE_OBJECTIVE_SENTINEL")
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_context).setText("PRIVATE_PENDING_SENTINEL")
        val uri = "https://docs.example.com/path?q=one%20two#source"
        val navigation = CatalogSourceNavigation(activity, sourceDetails(uri),
            ExternalLinkPolicy(setOf("docs.example.com")), isCurrent = { true })
        navigation.show()
        shadowOf(dialog()).clickOnItem(0)
        val confirmation = dialog()
        assertTrue(shadowOf(confirmation).message.toString().contains("Host: docs.example.com"))
        assertTrue(shadowOf(confirmation).message.toString().contains(uri))
        assertNull(shadowOf(activity).nextStartedActivity)
        val open = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        open.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertNull(intent.component)
        assertEquals(0, intent.flags)
        assertFalse(intent.toUri(0).contains("PRIVATE_"))
        open.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("PRIVATE_PENDING_SENTINEL", field(R.string.context_context).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    @Test fun cancelledSourceConfirmationAndRetiredChooserCannotDispatch() {
        val navigation = CatalogSourceNavigation(activity, sourceDetails("https://docs.example.com/source"),
            ExternalLinkPolicy(setOf("docs.example.com")), isCurrent = { true })
        navigation.show()
        val retiredChooser = dialog()
        shadowOf(retiredChooser).clickOnItem(0)
        val confirmation = dialog()
        val open = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        open.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(retiredChooser).clickOnItem(0)
        assertSame(confirmation, dialog())
        assertFalse(confirmation.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogSourcePolicyRejectsHostileAndUnconfiguredUris() {
        val policy = ExternalLinkPolicy(setOf("docs.example.com"))
        for (uri in listOf("https://docs.example.com.attacker.test/", "https://user:secret@docs.example.com/",
            "https://docs.example.com:444/", "file:///private/draft", "content://private/draft",
            "javascript:alert(1)", "intent://docs.example.com/", "https://docs.example.com/" + "a".repeat(4096))) {
            CatalogSourceNavigation(activity, sourceDetails(uri), policy, isCurrent = { true }).show()
            shadowOf(dialog()).clickOnItem(0)
            assertEquals(activity.getString(R.string.catalog_source_blocked), shadowOf(dialog()).message.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            dialog().dismiss()
        }
        CatalogSourceNavigation(activity, sourceDetails("https://docs.example.com/source"), isCurrent = { true }).show()
        shadowOf(dialog()).clickOnItem(0)
        assertEquals(activity.getString(R.string.catalog_source_blocked), shadowOf(dialog()).message.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogSourcePagingRetainsEveryExactProvenanceIndex() {
        val sources = (1..10).map { "https://docs.example.com/source-$it" }.toTypedArray()
        val navigation = CatalogSourceNavigation(activity, sourceDetails(*sources),
            ExternalLinkPolicy(setOf("docs.example.com")), isCurrent = { true })
        navigation.show()
        assertEquals(8, dialog().listView.adapter.count)
        assertEquals(activity.getString(R.string.catalog_source_page, 1, 2, 10), shadowOf(dialog()).title.toString())
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, dialog().listView.adapter.count)
        assertEquals(activity.getString(R.string.catalog_source_page, 2, 2, 10), shadowOf(dialog()).title.toString())
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(8, dialog().listView.adapter.count)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(dialog()).clickOnItem(1)
        assertTrue(shadowOf(dialog()).message.toString().startsWith("Source 10\n"))
        assertTrue(shadowOf(dialog()).message.toString().contains(sources.last()))
        assertNull(shadowOf(activity).nextStartedActivity)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun unavailableSourceHandlerPreservesDraftAndRequiresNewConfirmation() {
        field(R.string.draft_title).setText("saved source draft")
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending source draft")
        var calls = 0
        val navigation = CatalogSourceNavigation(activity, sourceDetails("https://docs.example.com/source"),
            ExternalLinkPolicy(setOf("docs.example.com")), isCurrent = { true }, launch = { intent ->
                calls++
                when (calls) {
                    1 -> throw ActivityNotFoundException("private platform detail")
                    2 -> throw SecurityException("private restriction detail")
                    else -> activity.startActivity(intent)
                }
            })
        navigation.show()
        repeat(2) {
            shadowOf(dialog()).clickOnItem(0)
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val unavailable = dialog()
            assertEquals(activity.getString(R.string.catalog_source_unavailable), shadowOf(unavailable).message.toString())
            assertFalse(shadowOf(unavailable).message.toString().contains("private"))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals("pending source draft", field(R.string.draft_title).text.toString())
            assertEquals(baseline, loaded().baselineIdentity)
            unavailable.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(dialog()).clickOnItem(0)
        assertEquals(2, calls)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, calls)
        assertEquals("https://docs.example.com/source", shadowOf(activity).nextStartedActivity?.dataString)
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun catalogSourceCallbackCannotDispatchAfterAuthorityChangeOrRecreation() {
        var authority = true
        val detail = sourceDetails("https://docs.example.com/source")
        val navigation = CatalogSourceNavigation(activity, detail,
            ExternalLinkPolicy(setOf("docs.example.com")), isCurrent = { authority })
        navigation.show()
        shadowOf(dialog()).clickOnItem(0)
        authority = false
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        authority = true
        navigation.show()
        shadowOf(dialog()).clickOnItem(0)
        val retiredOpen = dialog().getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredActivity = activity
        recreate()
        await { button(R.string.save_draft).isEnabled }
        retiredOpen.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(retiredActivity).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogSourceConfirmationShowsCompleteBoundedUri() {
        val uri = "HTTPS://docs.example.com/source?q=" + "a".repeat(3000) + "#retained-end"
        val detail = sourceDetails(uri)
        CatalogSourceNavigation(activity, detail, ExternalLinkPolicy(setOf("docs.example.com")),
            isCurrent = { true }).show()
        assertTrue(dialog().listView.adapter.getItem(0).toString().contains("[display truncated]"))
        shadowOf(dialog()).clickOnItem(0)
        val dispatchedUri = "https" + uri.removePrefix("HTTPS")
        assertTrue(shadowOf(dialog()).message.toString().contains(dispatchedUri))
        assertFalse(shadowOf(dialog()).message.toString().contains("[display truncated]"))
        assertNull(shadowOf(activity).nextStartedActivity)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(dispatchedUri, shadowOf(activity).nextStartedActivity?.dataString)
        assertEquals(uri, detail.provenance.single().sourceUri)
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

    @Test fun catalogRecordedPriceShowsExactSnapshotUnitsAndDatesWithoutSaving() {
        field(R.string.draft_title).setText("saved price review")
        save()
        val baseline = loaded().baselineIdentity
        field(R.string.context_objective).setText("pending price objective")
        field(R.string.context_context).setText("pending price context")
        field(R.string.catalog_search).setText("cat.compute_profile.fixture")
        await { samples().size == 1 }
        val before = Bundle().also { controller.saveInstanceState(it) }
        samples().single().performClick()
        val opened = dialog()
        val message = shadowOf(opened).message.toString()
        val book = activity.assets.open("pricebook.json").use { (RecordedPricebook.read(it) as RecordedPricebookResult.Parsed).pricebook }
        assertTrue(message.contains("Pricebook: \"prices.fixture.1\""))
        assertTrue(message.contains("SHA-256: ${book.hash}"))
        assertTrue(message.contains("Exact item/version price records displayed: 1 / 1"))
        assertTrue(message.contains("Catalog item ID: \"cat.compute_profile.fixture\""))
        assertTrue(message.contains("Catalog item version: \"2026.10-demo\""))
        assertTrue(message.contains("Meter: gpu_hour"))
        assertTrue(message.contains("Recorded unit_price.amount: \"0.80\""))
        assertTrue(message.contains("Currency: USD"))
        assertTrue(message.contains("Region: \"us-east-fixture\""))
        assertTrue(message.contains("Recorded valid_from: \"2026-10-16T09:00:00Z\""))
        assertTrue(message.contains("Recorded valid_until: " + activity.getString(R.string.catalog_detail_missing)))
        assertTrue(message.contains("Recorded price_type: estimate"))
        assertTrue(message.contains(activity.getString(R.string.catalog_price_conditions_missing)))
        assertTrue(message.contains(activity.getString(R.string.catalog_price_notice)))
        assertTrue(message.contains(activity.getString(R.string.catalog_price_limits)))
        assertFalse(message.contains("rate.storage.fixture"))
        opened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !opened.isShowing }
        val after = Bundle().also { controller.saveInstanceState(it) }
        for (key in listOf("title", "objective", "contextInput", "selectedId", "selectedVersion",
            "selectedCatalogHash", "draftId", "draftBaseline", "catalogSnapshot")) {
            assertEquals(key, before.getString(key), after.getString(key))
        }
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun catalogRecordedPriceNeverSubstitutesMissingItemOrVersionWithFreePricing() {
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        val message = shadowOf(dialog()).message.toString()
        assertTrue(message.contains("Pricebook: \"prices.fixture.1\""))
        assertTrue(message.contains("Exact item/version price records displayed: 0 / 0"))
        assertTrue(message.contains(activity.getString(R.string.catalog_price_no_record)))
        assertFalse(message.contains("Recorded unit_price.amount:"))
        assertFalse(message.contains("rate.compute_profile.fixture"))
        val detail = sourceDetails(null)
        val input = priceJson()
        input.getJSONArray("rates").getJSONObject(0).put("catalog_item_id", detail.item.id)
            .put("catalog_item_version", "different-version")
        val wrongVersion = CatalogPriceText.describe(activity, detail.item, recordedPrices(input))
        assertTrue(wrongVersion.contains(activity.getString(R.string.catalog_price_no_record)))
        assertFalse(wrongVersion.contains("Recorded unit_price.amount:"))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun catalogRecordedPriceKeepsZeroConditionsAndUnknownLiteralsUnassessed() {
        val detail = sourceDetails(null)
        val input = priceJson()
        val rate = input.getJSONArray("rates").getJSONObject(0)
        rate.put("catalog_item_id", detail.item.id).put("catalog_item_version", detail.item.version)
            .put("price_type", "list").put("region", "unknown")
            .put("valid_from", "2026-10-16T10:00:00+01:00").put("valid_until", "2026-10-17T10:00:00+01:00")
        rate.getJSONObject("unit_price").put("amount", "0.0000").put("currency", "JPY")
        rate.getJSONObject("provenance").put("verification_status", "verified")
            .put("source_uri", "https://example.invalid/rate").put("notes", "unknown")
        rate.put("conditions", org.json.JSONArray().put("unknown").put(""))
        val book = recordedPrices(input)
        val message = CatalogPriceText.describe(activity, detail.item, book)
        assertTrue(message.contains("Recorded unit_price.amount: \"0.0000\""))
        assertTrue(message.contains("Currency: JPY"))
        assertTrue(message.contains("Region: \"unknown\""))
        assertTrue(message.contains("Recorded valid_from: \"2026-10-16T10:00:00+01:00\""))
        assertTrue(message.contains("Recorded valid_until: \"2026-10-17T10:00:00+01:00\""))
        assertTrue(message.contains("1: \"unknown\"\n2: \"\""))
        assertTrue(message.contains("Rate notes: \"unknown\""))
        assertTrue(message.contains("Rate source URI: \"https://example.invalid/rate\""))
        assertTrue(message.contains(activity.getString(R.string.label_verification_unestablished)))
        assertFalse(message.contains(activity.getString(R.string.label_independently_verified)))
        assertTrue(message.contains(activity.getString(R.string.catalog_price_limits)))
        rate.put("conditions", org.json.JSONArray()).put("region", "")
        val empty = CatalogPriceText.describe(activity, detail.item, recordedPrices(input))
        assertTrue(empty.contains(activity.getString(R.string.catalog_price_conditions_empty)))
        assertTrue(empty.contains("Region: \"\""))
        rate.remove("conditions"); rate.remove("region")
        val missing = CatalogPriceText.describe(activity, detail.item, recordedPrices(input))
        assertTrue(missing.contains(activity.getString(R.string.catalog_price_conditions_missing)))
        assertTrue(missing.contains("Region: " + activity.getString(R.string.catalog_detail_missing)))
        assertEquals(listOf("unknown", ""), book.ratesFor(detail.item.id, detail.item.version).single().conditions)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun catalogRecordedPriceBoundsLiteralAlternativesWithoutSelectingOrCombining() {
        val detail = sourceDetails(null)
        val input = priceJson()
        val prototype = input.getJSONArray("rates").getJSONObject(0)
            .put("catalog_item_id", detail.item.id).put("catalog_item_version", detail.item.version)
        val rates = org.json.JSONArray()
        repeat(10) { index ->
            val rate = org.json.JSONObject(prototype.toString()).put("id", "rate.alternative.$index")
            val conditions = org.json.JSONArray()
            repeat(10) { conditions.put("\u202e" + "x".repeat(200)) }
            rate.put("conditions", conditions)
            rate.getJSONObject("provenance").put("notes", "\n\u202e" + "x".repeat(200))
            rates.put(rate)
        }
        input.put("rates", rates)
        val book = recordedPrices(input)
        val message = CatalogPriceText.describe(activity, detail.item, book)
        assertTrue(message.contains("Exact item/version price records displayed: 8 / 10"))
        assertTrue(message.contains("Conditions displayed: 8 / 10"))
        assertTrue(message.contains("rate.alternative.7"))
        assertFalse(message.contains("rate.alternative.8"))
        assertTrue(message.contains("[display truncated]"))
        assertTrue(message.contains("\\u202e"))
        assertFalse(message.contains('\u202e'))
        assertTrue(message.length < 100_000)
        assertEquals(10, book.ratesFor(detail.item.id, detail.item.version).size)
        assertEquals(10, book.rates.last().conditions!!.size)
        assertTrue(message.contains(activity.getString(R.string.catalog_price_limits)))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun catalogRecordedPriceReadFailureKeepsUnknownContextAndRetryUsesFreshSnapshot() {
        val catalogBytes = activity.assets.open("catalog.json").use { it.readBytes() }
        val first = CatalogFixtureData.read({ catalogBytes }) { activity.assets.open("pricebook.json") }
        assertNotNull(first.catalog)
        assertNotNull(first.pricebook)
        for (priceInput in listOf<() -> java.io.InputStream>(
            { throw java.io.IOException("private asset path") },
            { "{\"invalid\":true}".byteInputStream() },
        )) {
            val failed = CatalogFixtureData.read({ catalogBytes }, priceInput)
            val browser = requireNotNull(failed.catalog)
            assertEquals(first.catalog!!.hash, browser.hash)
            assertNull(failed.pricebook)
            val item = browser.items.single { it.kind == "compute_profile" }
            val message = CatalogDetailText.describe(activity, requireNotNull(browser.details(item, browser.hash)), failed.pricebook)
            assertTrue(message.contains(activity.getString(R.string.catalog_price_unavailable)))
            assertFalse(message.contains("rate.compute_profile.fixture"))
        }
        var attemptedPriceRead = false
        val invalidCatalog = CatalogFixtureData.read({ "{}".toByteArray() }) {
            attemptedPriceRead = true; activity.assets.open("pricebook.json")
        }
        assertNull(invalidCatalog.catalog)
        assertNull(invalidCatalog.pricebook)
        assertFalse(attemptedPriceRead)
        val retry = CatalogFixtureData.read({ catalogBytes }) { activity.assets.open("pricebook.json") }
        assertNotSame(first.pricebook, retry.pricebook)
        assertEquals(first.pricebook!!.hash, retry.pricebook!!.hash)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun catalogRecordedPriceReloadAndRetiredDialogCannotChangePendingDraft() {
        field(R.string.draft_title).setText("pending price reload")
        field(R.string.catalog_search).setText("cat.storage.fixture")
        await { samples().size == 1 }
        samples().single().performClick()
        val retired = dialog()
        val original = shadowOf(retired).message.toString()
        assertTrue(original.contains("Meter: storage_gb_month"))
        assertTrue(original.contains("Recorded unit_price.amount: \"0.05\""))
        retired.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !retired.isShowing }
        button(R.string.retry_catalog).performClick()
        await { button(R.string.retry_catalog).isEnabled && samples().size == 1 }
        samples().single().performClick()
        assertEquals(original, shadowOf(dialog()).message.toString())
        recreate()
        await { button(R.string.save_draft).isEnabled && samples().size == 1 }
        val before = Bundle().also { controller.saveInstanceState(it) }
        retired.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        retired.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val after = Bundle().also { controller.saveInstanceState(it) }
        for (key in listOf("title", "objective", "contextInput", "selectedId", "selectedVersion",
            "selectedCatalogHash", "draftId", "draftBaseline", "catalogSnapshot")) {
            assertEquals(key, before.getString(key), after.getString(key))
        }
        samples().single().performClick()
        assertEquals(original, shadowOf(dialog()).message.toString())
        assertEquals("pending price reload", field(R.string.draft_title).text.toString())
        assertTrue(hasText(R.string.no_selection))
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
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


    private fun dialogField(dialog: AlertDialog, label: Int): EditText {
        val caption = views(dialog.window!!.decorView).filterIsInstance<TextView>()
            .single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return dialog.findViewById(caption.labelFor)
    }
    private fun componentChoice(): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().startsWith("model ·")
        }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun componentAddForm(): AlertDialog {
        val choices = componentChoice()
        shadowOf(choices).clickOnItem(0)
        await { dialog() !== choices && dialog().isShowing }
        return dialog()
    }
    private fun addModel(): PreviewStoredDraft {
        val form = componentAddForm()
        dialogField(form, R.string.component_role).setText("primary")
        dialogField(form, R.string.component_settings).setText("{}")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && hasText(R.string.component_saved) }
        return loaded()
    }
    private fun componentAction(index: Int): AlertDialog {
        button(R.string.component_manage).performClick()
        val picker = dialog()
        shadowOf(picker).clickOnItem(0)
        await { dialog() !== picker && dialog().isShowing }
        val actions = dialog()
        shadowOf(actions).clickOnItem(index)
        await { dialog() !== actions && dialog().isShowing }
        return dialog()
    }

    @Test fun explicitComponentsAddConfigureToggleAndRemovePreserveGuestInputs() {
        field(R.string.draft_title).setText("saved form")
        field(R.string.context_objective).setText("objective")
        save()
        val before = loaded()
        var current = addModel()
        val selection = current.components.selections.single()
        assertEquals("primary", selection.role)
        assertFalse(selection.enabled)
        assertEquals(before.revision + 1, current.revision)
        val settings = componentAction(0)
        dialogField(settings, R.string.component_settings).setText("{\"temperature\":0.25}")
        settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !settings.isShowing && loaded().revision == current.revision + 1 }
        current = loaded()
        assertTrue(PreviewSelectionForms.settingsText(current.components.selections.single()).contains("0.25"))
        val toggle = componentAction(1)
        toggle.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !toggle.isShowing && loaded().components.selections.single().enabled }
        val remove = componentAction(2)
        remove.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !remove.isShowing && loaded().components.selections.isEmpty() }
        assertEquals(before.fields, loaded().fields)
        assertEquals(before.contextInput, loaded().contextInput)
        assertEquals(before.workloadInput, loaded().workloadInput)
        assertEquals(before.revision + 4, loaded().revision)
        assertFalse(button(R.string.component_manage).isEnabled)
    }

    @Test fun invalidComponentInputRemainsVisibleAndCannotCommitTruncatedPrefix() {
        save()
        val before = loaded().baselineIdentity
        val form = componentAddForm()
        val role = dialogField(form, R.string.component_role)
        val settings = dialogField(form, R.string.component_settings)
        role.setText("primary")
        val invalid = "{}" + " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT)
        settings.setText(invalid)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.component_invalid) && form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertTrue(form.isShowing)
        val feedback = views(form.window!!.decorView).filterIsInstance<TextView>()
            .single { it.text.toString() == activity.getString(R.string.component_invalid) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, feedback.accessibilityLiveRegion)
        assertTrue(feedback.isShown)
        assertEquals(invalid, settings.text.toString())
        assertEquals(before, loaded().baselineIdentity)
        role.setText("Primary")
        settings.setText("{}")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("", feedback.text.toString()) // The prior error clears synchronously on retry.
        await { hasText(R.string.component_invalid) && form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertEquals("Primary", role.text.toString())
        assertEquals(before, loaded().baselineIdentity)
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing && button(R.string.component_choose_catalog).isEnabled }
        assertEquals(before, loaded().baselineIdentity)
    }

    @Test fun staleComponentFormCannotOverwriteConcurrentSavedHead() {
        save()
        val before = loaded()
        val form = componentAddForm()
        dialogField(form, R.string.component_role).setText("primary")
        val newer = (adapter().save(before, PreviewDraftFields("other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_conflict) && !form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertTrue(form.isShowing)
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_conflict) && it.isShown
        })
        assertEquals("primary", dialogField(form, R.string.component_role).text.toString())
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertFalse(button(R.string.component_choose_catalog).isEnabled)
    }

    @Test fun repeatedAndRetiredComponentConfirmationsCannotCreateAnotherRevision() {
        save()
        val form = componentAddForm()
        dialogField(form, R.string.component_role).setText("primary")
        val positive = form.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.performClick()
        positive.performClick()
        await { !form.isShowing && hasText(R.string.component_saved) }
        val baseline = loaded().baselineIdentity
        positive.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(baseline, loaded().baselineIdentity)
        val configure = componentAction(0)
        val retired = configure.getButton(AlertDialog.BUTTON_POSITIVE)
        dialogField(configure, R.string.component_settings).setText("{\"temperature\":0.75}")
        recreate()
        await { hasText(R.string.draft_saved) }
        retired.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(configure.isShowing)
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun exactComponentReplacementNoopRetainsIdentityAndDirtyFormBlocksEntry() {
        save()
        val current = addModel()
        val choices = componentChoice()
        shadowOf(choices).clickOnItem(1)
        await { dialog() !== choices && dialog().isShowing }
        val replace = dialog()
        replace.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !replace.isShowing && hasText(R.string.component_noop) }
        assertEquals(current.baselineIdentity, loaded().baselineIdentity)
        assertEquals(current.components, loaded().components)
        field(R.string.draft_title).setText("pending")
        assertFalse(button(R.string.component_manage).isEnabled)
        assertFalse(button(R.string.component_choose_catalog).isEnabled)
        val last = dialog()
        button(R.string.component_choose_catalog).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(last, dialog())
        assertEquals(current.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun canceledComponentDialogsAndPickersRevokeTheirRetainedCallbacks() {
        save()
        val initial = loaded().baselineIdentity
        val add = componentAddForm()
        dialogField(add, R.string.component_role).setText("primary")
        val retainedAdd = add.getButton(AlertDialog.BUTTON_POSITIVE)
        add.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !add.isShowing && button(R.string.component_choose_catalog).isEnabled }
        retainedAdd.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initial, loaded().baselineIdentity)
        addModel()
        val baseline = loaded().baselineIdentity
        for (index in listOf(0, 1, 2)) {
            val canceled = componentAction(index)
            val positive = canceled.getButton(AlertDialog.BUTTON_POSITIVE)
            canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            await { !canceled.isShowing && button(R.string.component_manage).isEnabled }
            positive.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(baseline, loaded().baselineIdentity)
        }
        val choices = componentChoice()
        choices.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !choices.isShowing }
        val latest = dialog()
        shadowOf(choices).clickOnItem(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(latest, dialog())
        button(R.string.component_manage).performClick()
        val selectionPicker = dialog()
        selectionPicker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !selectionPicker.isShowing }
        shadowOf(selectionPicker).clickOnItem(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(selectionPicker, dialog())
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun inFlightComponentCommitFreezesFormUntilInvalidOutcomeRestoresInputs() {
        save()
        val form = componentAddForm()
        val role = dialogField(form, R.string.component_role)
        val settings = dialogField(form, R.string.component_settings)
        val enabled = views(form.window!!.decorView).filterIsInstance<android.widget.CheckBox>().single()
        role.setText("primary")
        settings.setText("invalid")
        val workerField = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = workerField.get(activity) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        worker.execute { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(role.isEnabled)
            assertFalse(settings.isEnabled)
            assertFalse(views(form.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.component_invalid)
            })
            assertFalse(enabled.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        } finally { release.countDown() }
        await { hasText(R.string.component_invalid) && form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertTrue(role.isEnabled)
        assertTrue(settings.isEnabled)
        assertTrue(enabled.isEnabled)
        assertTrue(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
        assertEquals("invalid", settings.text.toString())
        assertTrue(loaded().components.selections.isEmpty())
    }

}
