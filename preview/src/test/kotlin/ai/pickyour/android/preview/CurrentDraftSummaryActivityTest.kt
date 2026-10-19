package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

/** Actual simulated Activity/resources/store; no installed-device or production-outcome claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftSummaryActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private var destroyed = false

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        finishWork()
    }
    @After fun close() { if (!destroyed) controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun summary() = activity.findViewById<CurrentDraftSummarySection>(R.id.draft_summary)
    private fun output(id: Int) = summary().findViewById<TextView>(id).text.toString()
    private fun action(id: Int) = summary().findViewById<Button>(id)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun dialogField(form: AlertDialog, label: Int): EditText {
        val caption = views(requireNotNull(form.window).decorView).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(label)
        }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return form.findViewById(caption.labelFor)
    }
    private fun addFormFromSummary(): AlertDialog {
        assertTrue(action(R.id.draft_summary_component_action).isEnabled)
        action(R.id.draft_summary_component_action).performClick()
        val picker = dialog()
        val model = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().startsWith("model ·")
        }
        shadowOf(picker).clickOnItem(model)
        await { dialog() !== picker && dialog().isShowing }
        val choice = dialog()
        shadowOf(choice).clickOnItem(0)
        await { dialog() !== choice && dialog().isShowing }
        return dialog()
    }
    private fun savedComponentFormFromSummary(actionIndex: Int): AlertDialog {
        assertTrue(action(R.id.draft_summary_component_action).isEnabled)
        action(R.id.draft_summary_component_action).performClick()
        val picker = dialog()
        assertEquals(1, picker.listView.adapter.count)
        shadowOf(picker).clickOnItem(0)
        await { dialog() !== picker && dialog().isShowing }
        val choices = dialog()
        shadowOf(choices).clickOnItem(actionIndex)
        await { dialog() !== choices && dialog().isShowing }
        return dialog()
    }
    private fun commitFormAndReviewCurrentComponents(form: AlertDialog, mode: String) {
        val before = loaded()
        val retired = action(R.id.draft_summary_component_action)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision + 1, loaded().revision)
        assertTrue(button(R.string.component_manage).isEnabled)
        val previous = dialog()
        retired.callOnClick()
        assertSame(previous, dialog())
        val current = action(R.id.draft_summary_component_action)
        assertNotSame(retired, current)
        assertTrue("Summary component action did not recover after the $mode form was saved", current.isEnabled)
        current.performClick()
        val review = dialog()
        assertEquals(activity.getString(R.string.component_manage), shadowOf(review).title.toString())
        assertEquals(1, review.listView.adapter.count)
        assertTrue(review.listView.adapter.getItem(0).toString().contains(loaded().components.selections.single().id))
        review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !review.isShowing }
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun save() {
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled &&
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } }
        finishWork()
        assertEquals(activity.getString(R.string.draft_summary_saved, loaded().revision.toString()), output(R.id.draft_summary_source))
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        finishWork()
    }
    /** Actual canonical-store update, then explicit Activity reopen; no UI authority is injected. */
    private fun installTwoRecordedComponents(): PreviewStoredDraft {
        save()
        val store = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
        val old = (store.load() as DraftLoad.Available).draft
        val entries = activity.assets.open("build.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("selections") }
        val rows = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getString("kind") == "model" || it.getString("kind") == "evaluation_suite" }
            .map { node -> LocalSelection.fromJson(node.put("enabled", node.getString("kind") == "model").toString().toByteArray()) }
        assertEquals(2, rows.size)
        val next = store.save(old.version, old.content.withComponents(DraftSelections(rows))) as DraftSave.Saved
        reopen()
        await { output(R.id.draft_summary_source) == activity.getString(R.string.draft_summary_saved, next.draft.version.number.toString()) }
        finishWork()
        return loaded()
    }
    private fun assertCurrentResultLimits() {
        val evaluation = output(R.id.draft_summary_evaluation)
        val id = PreviewActivity::class.java.getDeclaredField("currentDraftId")
            .apply { isAccessible = true }.get(activity) as PreviewDraftId
        val record = (readDraftAfterOwnedWork(activity) {
            val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
            if (id.value == "guest-draft") library.openLegacy() else library.open(id)
        } as? PreviewDraftLoad.Available)?.record
        val acknowledged = record != null && output(R.id.draft_summary_source) ==
            activity.getString(R.string.draft_summary_saved, record.revision.toString())
        val unavailable = setOf(
            "No evaluation is available for the current draft.",
            "Checks are unavailable while editing. Save the current inputs before reevaluation.",
            "Current check authority was retired while this screen was inactive.",
            "Checks are unavailable until the exact catalog, graph and rules are loaded.",
            "The stored draft changed. Reopen or reconcile the editor before checking it again.",
            "The current source or check could not be confirmed. Retry without treating an earlier result as current.",
        )
        val revision = record?.revision
        val observations = acknowledged && Regex("Saved revision $revision: [0-9]+ pass, [0-9]+ fail, [0-9]+ unknown, " +
            "[0-9]+ skipped rule observations\\. Severity is shown separately in each finding\\.").matches(evaluation)
        val withheld = acknowledged && evaluation.startsWith("Checks for saved revision $revision are unavailable: ") &&
            evaluation.endsWith("The affected set remains visible; unavailable is not pass or a zero estimate.")
        val rechecking = acknowledged && evaluation == "Rechecking saved revision $revision. " +
            "Results remain unavailable until its inputs and current revision are confirmed."
        assertTrue("Summary must expose only unavailable or exact saved-source local observations: $evaluation",
            evaluation in unavailable || observations || withheld || rechecking)
        assertTrue(views().filterIsInstance<TextView>().any {
            val value = it.text.toString()
            value.contains("loaded synthetic catalog, dependency graph and rules") &&
                value.contains("Free-text objective and context are not interpreted.") &&
                value.contains("Current cost and resource estimates are unavailable.")
        })
        assertEquals(activity.getString(R.string.draft_summary_cost_unavailable), output(R.id.draft_summary_cost))
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the current draft UI")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() {
        repeat(2) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() }
    }

    @Test fun emptyDraftHasASummaryWithExplicitCurrentResultLimits() {
        val text = views().filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("The loaded editable draft has no current-draft summary", "Current draft summary" in text)
        assertCurrentResultLimits()
        assertTrue("Unknown price coverage is not a zero or complete total", "No cost estimate or price coverage is available for the current draft." in text)
        assertEquals(activity.getString(R.string.draft_summary_unsaved), output(R.id.draft_summary_source))
        assertEquals(activity.getString(R.string.draft_summary_components_unavailable), output(R.id.draft_summary_components))
        assertEquals(activity.getString(R.string.draft_summary_sample_absent), output(R.id.draft_summary_sample))
        assertFalse(action(R.id.draft_summary_component_action).isEnabled)
        assertTrue(action(R.id.draft_summary_planning_action).isEnabled)
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test fun requiredInputsNavigateInOrderAndInvalidInputTargetsTheExistingError() {
        val retained = disk()
        val first = action(R.id.draft_summary_input_action)
        first.performClick()
        assertTrue(field(R.string.workload_region).hasFocus())
        field(R.string.workload_region).setText("user-entered region")
        field(R.string.draft_title).requestFocus()
        first.callOnClick()
        assertTrue("Retired required-input action did not acquire the replacement input", field(R.string.draft_title).hasFocus())
        action(R.id.draft_summary_input_action).performClick()
        assertTrue(field(R.string.workload_currency).hasFocus())
        field(R.string.workload_currency).setText("USD")
        action(R.id.draft_summary_input_action).performClick()
        assertTrue(dialog().isShowing)
        assertEquals(activity.getString(R.string.workload_classification), shadowOf(dialog()).title.toString())
        shadowOf(dialog()).clickOnItem(1)
        assertEquals(activity.getString(R.string.objective_inputs_answered), output(R.id.draft_summary_objectives))
        assertNull(action(R.id.draft_summary_input_action))
        field(R.string.workload_budget).setText("invalid")
        assertEquals(activity.getString(R.string.objective_inputs_invalid), output(R.id.draft_summary_objectives))
        action(R.id.draft_summary_input_action).performClick()
        requireFocused(R.string.workload_invalid)
        assertEquals("invalid", field(R.string.workload_budget).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(retained, disk())
        assertCurrentResultLimits()
    }

    @Test fun acknowledgedCountsAndPendingInputsStayDistinctAcrossRecreation() {
        val recorded = installTwoRecordedComponents()
        assertEquals(activity.getString(R.string.draft_summary_component_counts, 1, 1), output(R.id.draft_summary_components))
        val retained = disk()
        field(R.string.draft_title).setText("pending private title")
        field(R.string.workload_region).setText("pending region")
        assertEquals(activity.getString(R.string.draft_summary_pending, recorded.revision.toString()), output(R.id.draft_summary_source))
        assertEquals(activity.getString(R.string.draft_summary_component_counts, 1, 1), output(R.id.draft_summary_components))
        assertFalse(action(R.id.draft_summary_component_action).isEnabled)
        recreate()
        assertEquals(activity.getString(R.string.draft_summary_pending, recorded.revision.toString()), output(R.id.draft_summary_source))
        assertEquals("pending private title", field(R.string.draft_title).text.toString())
        assertEquals("pending region", field(R.string.workload_region).text.toString())
        assertEquals(recorded.baselineIdentity, loaded().baselineIdentity)
        assertEquals(retained, disk())
        assertCurrentResultLimits()
    }

    @Test fun aDelayedReadWithholdsCountsAndOldActionsUntilActualCompletion() {
        installTwoRecordedComponents()
        val old = action(R.id.draft_summary_component_action)
        val retained = disk()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { started.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(started.await(15, TimeUnit.SECONDS))
        try {
            reopen()
            idle()
            val previous = dialog()
            assertEquals(activity.getString(R.string.draft_summary_loading), output(R.id.draft_summary_source))
            assertEquals(activity.getString(R.string.draft_summary_components_unavailable), output(R.id.draft_summary_components))
            assertEquals(activity.getString(R.string.draft_summary_objectives_unavailable), output(R.id.draft_summary_objectives))
            old.callOnClick()
            assertSame(previous, dialog())
            assertTrue(views(summary()).filterIsInstance<Button>().all { !it.isEnabled })
        } finally { release.countDown() }
        finishWork()
        assertEquals(activity.getString(R.string.draft_summary_component_counts, 1, 1), output(R.id.draft_summary_components))
        assertNotSame(old, action(R.id.draft_summary_component_action))
        assertEquals(retained, disk())
        assertCurrentResultLimits()
    }

    @Test fun componentActionsUseCurrentEditorsAndNeverReplayIntoANewDraft() {
        save()
        val empty = loaded()
        val before = disk()
        action(R.id.draft_summary_component_action).performClick()
        assertEquals(activity.getString(R.string.component_choose_catalog), shadowOf(dialog()).title.toString())
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(empty.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before, disk())

        field(R.string.draft_title).setText("saved populated draft")
        val populated = installTwoRecordedComponents()
        val old = action(R.id.draft_summary_component_action)
        old.performClick()
        assertEquals(activity.getString(R.string.component_manage), shadowOf(dialog()).title.toString())
        assertEquals(2, dialog().listView.adapter.count)
        val entries = (0 until 2).map { dialog().listView.adapter.getItem(it).toString() }
        populated.components.selections.forEach { row -> assertTrue(entries.any { it.contains(row.id) }) }
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        field(R.string.draft_title).setText("preserve pending")
        assertFalse(action(R.id.draft_summary_component_action).isEnabled)
        old.callOnClick()
        assertFalse(dialog().isShowing)
        assertEquals("preserve pending", field(R.string.draft_title).text.toString())
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals("", field(R.string.draft_title).text.toString())
        val current = disk()
        val previous = dialog()
        old.callOnClick()
        assertSame(previous, dialog())
        assertEquals(current, disk())
        assertEquals(activity.getString(R.string.draft_summary_component_counts, 0, 0), output(R.id.draft_summary_components))
        assertCurrentResultLimits()
    }

    @Test fun successfulComponentFormsRefreshTheSummaryAfterEachAcknowledgedSave() {
        save()
        val add = addFormFromSummary()
        dialogField(add, R.string.component_role).setText("primary")
        commitFormAndReviewCurrentComponents(add, "add")
        assertEquals("primary", loaded().components.selections.single().role)

        val configure = savedComponentFormFromSummary(0)
        dialogField(configure, R.string.component_settings).setText("{\"temperature\":0.5}")
        commitFormAndReviewCurrentComponents(configure, "configure")
        assertEquals("{\"temperature\":0.5}", PreviewSelectionForms.settingsText(loaded().components.selections.single()))

        val role = savedComponentFormFromSummary(1)
        dialogField(role, R.string.component_role).setText("secondary")
        commitFormAndReviewCurrentComponents(role, "role")
        assertEquals("secondary", loaded().components.selections.single().role)
        assertCurrentResultLimits()
    }

    @Test fun discardingRecoveredComponentInputRefreshesTheSummaryWithoutOtherEdits() {
        save()
        val form = addFormFromSummary()
        dialogField(form, R.string.component_role).setText("pending role")
        dialogField(form, R.string.component_settings).setText("{\"unfinished\":")
        val retained = disk()
        val baseline = loaded().baselineIdentity
        recreate()
        val retired = action(R.id.draft_summary_component_action)
        assertFalse(retired.isEnabled)
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_choose_catalog).isEnabled)
        assertEquals(retained, disk())
        assertEquals(baseline, loaded().baselineIdentity)
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        retired.callOnClick()
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
        val current = action(R.id.draft_summary_component_action)
        assertTrue("Summary component action did not recover after pending input was discarded", current.isEnabled)
        assertNotSame(retired, current)
        current.performClick()
        assertEquals(activity.getString(R.string.component_choose_catalog), shadowOf(dialog()).title.toString())
        assertTrue(loaded().components.selections.isEmpty())
        assertCurrentResultLimits()
    }

    @Test fun pauseAndRecreationRetireSummaryActionsWithoutDroppingPendingText() {
        field(R.string.draft_title).setText("keep paused draft")
        val input = action(R.id.draft_summary_input_action)
        val planning = action(R.id.draft_summary_planning_action)
        val retained = disk()
        field(R.string.draft_title).requestFocus()
        controller.pause()
        input.callOnClick(); planning.callOnClick(); idle()
        assertTrue(field(R.string.draft_title).hasFocus())
        controller.resume()
        input.callOnClick(); planning.callOnClick(); idle()
        assertTrue("Old actions stay retired after resume", field(R.string.draft_title).hasFocus())
        action(R.id.draft_summary_planning_action).performClick()
        requireFocused(R.string.planning_heading)
        val oldAfterResume = action(R.id.draft_summary_input_action)
        recreate()
        field(R.string.draft_title).requestFocus()
        oldAfterResume.callOnClick(); input.callOnClick(); idle()
        assertTrue(field(R.string.draft_title).hasFocus())
        assertEquals("keep paused draft", field(R.string.draft_title).text.toString())
        assertEquals(retained, disk())
        assertCurrentResultLimits()
    }

    @Test fun unconfirmedRestorationCannotBorrowTheOldRevisionOrItsActions() {
        installTwoRecordedComponents()
        val old = action(R.id.draft_summary_component_action)
        field(R.string.draft_title).setText("pending over older baseline")
        val store = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
        val prior = (store.load() as DraftLoad.Available).draft
        assertTrue(store.save(prior.version, prior.content.withComponents(DraftSelections())) is DraftSave.Saved)
        val retained = disk()
        recreate()
        assertEquals(activity.getString(R.string.draft_summary_unavailable), output(R.id.draft_summary_source))
        assertEquals(activity.getString(R.string.draft_summary_components_unavailable), output(R.id.draft_summary_components))
        assertTrue(views(summary()).filterIsInstance<Button>().all { !it.isEnabled })
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        old.callOnClick(); idle()
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("pending over older baseline", field(R.string.draft_title).text.toString())
        assertEquals(retained, disk())
        assertCurrentResultLimits()
    }

    @Test fun fixedReportsCannotPopulateCurrentEvaluationOrCostAfterEditsOrSave() {
        save()
        val retained = disk()
        button(R.string.cost_inspect).performClick()
        button(R.string.findings_inspect).performClick()
        finishWork()
        assertTrue(button(R.string.findings_browse).isEnabled)
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains("Known declared line subtotal:") })
        assertCurrentResultLimits()
        assertEquals(retained, disk())
        field(R.string.workload_region).setText("another region")
        assertCurrentResultLimits()
        save()
        assertCurrentResultLimits()
        recreate()
        assertCurrentResultLimits()
    }

    @Test fun summaryOmitsPrivateValuesAndKeepsTheExistingDiagnosticReport() {
        field(R.string.draft_title).setText("private-summary-title")
        field(R.string.context_objective).setText("private-summary-objective")
        field(R.string.context_context).setText("private-summary-context")
        field(R.string.workload_region).setText("private-summary-region")
        save()
        val retained = disk()
        field(R.string.context_context).setText("private-summary-pending")
        assertFalse(views(summary()).filterIsInstance<TextView>().any { it.text.toString().contains("private-summary") })
        button(R.string.diagnostics_review).performClick()
        assertEquals(LocalDiagnosticReport(DiagnosticStorageState.READY, DiagnosticCatalogState.SYNTHETIC_LOADED).text,
            dialog().findViewById<TextView>(R.id.diagnostic_report).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(retained, disk())
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsSummaryActionsAndDestinationsReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        assertSummaryLayout(320, 640, View.LAYOUT_DIRECTION_RTL)
    }

    @Test
    @Config(qualifiers = "w840dp-h360dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wideReducedViewportKeepsSummaryNavigationAndInputsReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        // Deliberately reduced measured viewport; this is not an installed IME observation.
        assertSummaryLayout(840, 360, View.LAYOUT_DIRECTION_LTR)
    }

    private fun assertSummaryLayout(widthDp: Int, heightDp: Int, direction: Int) {
        save()
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(direction, root.layoutDirection)
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        assertTrue(summary().findViewById<TextView>(R.id.draft_summary_heading).isAccessibilityHeading)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, summary().findViewById<TextView>(R.id.draft_summary_source).accessibilityLiveRegion)
        for (text in views(summary()).filterIsInstance<TextView>()) {
            val layout = requireNotNull(text.layout)
            assertEquals(text.text.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
        }
        for (id in listOf(R.id.draft_summary_input_action, R.id.draft_summary_component_action, R.id.draft_summary_planning_action)) {
            val target = action(id)
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            idle()
            assertFullyVisible(target)
            assertTrue(target.width >= (48 * density).toInt())
            assertTrue(target.height >= (48 * density).toInt())
            assertTrue(target.isFocusable)
            assertTrue(target.isEnabled)
            assertTrue(target.layout.height <= target.height - target.compoundPaddingTop - target.compoundPaddingBottom)
        }
        val retained = disk()
        action(R.id.draft_summary_input_action).performClick()
        val region = field(R.string.workload_region)
        assertTrue(region.hasFocus())
        assertFullyVisible(region)
        action(R.id.draft_summary_planning_action).performClick()
        val heading = requireFocused(R.string.planning_heading)
        assertFullyVisible(heading)
        assertEquals(retained, disk())
    }

    private fun requireFocused(label: Int): TextView {
        val target = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertTrue("Destination focus: focusable=${target.isFocusable}, touch=${target.isFocusableInTouchMode}, " +
            "enabled=${target.isEnabled}, visible=${target.isShown}, size=${target.width}x${target.height}, " +
            "current=${activity.window.decorView.findFocus()?.javaClass?.simpleName}", target.hasFocus())
        // Inspect the real view hierarchy; ShadowActivity.getCurrentFocus is a separate manual field.
        assertSame(target, activity.window.decorView.findFocus())
        return target
    }

    private fun assertFullyVisible(target: View) {
        val visible = Rect()
        assertTrue("Destination must intersect the visible viewport", target.getGlobalVisibleRect(visible))
        assertEquals("The complete destination width must be visible", target.width, visible.width())
        assertEquals("The complete destination height must be visible", target.height, visible.height())
    }
}
