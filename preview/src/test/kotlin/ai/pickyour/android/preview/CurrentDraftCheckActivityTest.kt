package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.evaluation.DraftCurrentUnavailable
import ai.pickyour.core.preview.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Activity/save/picker/reader/evaluator integration. Simulated Android, not installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftCheckActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }
    @After fun close() { controller.pause().stop().destroy() }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun setField(name: String, value: Any) { PreviewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun worker() = field<ExecutorService>("worker")
    private fun session(): PreviewCurrentDraftAnalysis {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return CurrentDraftCheckSection::class.java.getDeclaredField("analysis").apply { isAccessible = true }.get(section) as PreviewCurrentDraftAnalysis
    }
    private fun state() = session().state
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root); if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun status() = activity.findViewById<TextView>(R.id.current_draft_check_status).text.toString()
    private fun affected() = activity.findViewById<TextView>(R.id.current_draft_check_affected).text.toString()
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) { idle(); if (condition()) return; Thread.sleep(10) }
        fail("Timed out waiting for actual saved-draft check state")
    }
    private fun finishWork() { repeat(5) { idle(); worker().submit {}.get(25, TimeUnit.SECONDS); idle() } }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun rows(vararg kinds: String): List<LocalSelection> {
        val rows = document("build").getJSONArray("selections")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("kind") in kinds }
            .map { LocalSelection.fromJson(it.toString().toByteArray()) }
    }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
    } }
    private fun reopen() {
        button(R.string.reopen_draft).performClick(); dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !field<Boolean>("storageBusy") && field<Boolean>("storageReady") }
        finishWork()
    }
    private fun components(kinds: List<String>, tiers: Set<OptionalTier> = emptySet()) {
        val store = store(); val old = (store.load() as DraftLoad.Available).draft
        val next = DraftContent(optional = tiers.associateWith { TierDraft(Activation.ENABLED) }, components = DraftSelections(rows(*kinds.toTypedArray())))
        assertTrue(store.save(old.version, next, old.metadata) is DraftSave.Saved)
        reopen(); await { state() is PreviewCurrentDraftState.Current }
    }
    /** Explicit synthetic artifact replacement through the existing validated catalog/selection seam. */
    private fun installInputs(catalog: JSONObject, graph: JSONObject) {
        val bytes = catalog.toString().toByteArray()
        val fixture = CatalogFixtureData.read({ bytes }) { activity.assets.open("pricebook.json") }
        val browser = requireNotNull(fixture.catalog)
        setField("catalogFixture", fixture)
        setField("componentCatalog", SelectionCatalogSnapshot.fromJson(bytes))
        setField("currentCheckInputs", requireNotNull(DraftCurrentInputs.read(bytes, graph.toString().toByteArray(), document("ruleset").toString().toByteArray())))
        field<SelectionEditSection>("componentSection").catalog(browser.items, browser.hash)
        field<CatalogBrowseSection>("catalog").show(browser)
        finishWork()
    }
    private fun choose(picker: AlertDialog, predicate: (String) -> Boolean): AlertDialog {
        val index = (0 until picker.listView.adapter.count).first { predicate(picker.listView.adapter.getItem(it).toString()) }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun modelActions(): AlertDialog {
        button(R.string.component_manage).performClick()
        return choose(dialog()) { it.startsWith("model ·") }
    }
    private fun input(dialog: AlertDialog, label: Int): EditText {
        val caption = views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return dialog.findViewById(caption.labelFor)
    }
    /** The actual save runs; a following worker barrier keeps its new evaluation queued for UI observation. */
    private fun observeAcknowledgement(command: () -> Unit, observe: () -> Unit) {
        val firstEntered = CountDownLatch(1); val firstRelease = CountDownLatch(1)
        val savedEntered = CountDownLatch(1); val evaluateRelease = CountDownLatch(1)
        worker().execute { firstEntered.countDown(); check(firstRelease.await(25, TimeUnit.SECONDS)) }
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
        try {
            command()
            worker().execute { savedEntered.countDown(); check(evaluateRelease.await(25, TimeUnit.SECONDS)) }
            firstRelease.countDown(); assertTrue(savedEntered.await(15, TimeUnit.SECONDS))
            await { !field<Boolean>("storageBusy") }
            observe()
        } finally { firstRelease.countDown(); evaluateRelease.countDown(); finishWork() }
    }

    @Test fun acknowledgedDraftRunsRealRulesWithUnavailableMetricsAndReadOnlyHistory() {
        val current = state() as PreviewCurrentDraftState.Current
        assertEquals(loaded().revision, current.request.binding.sourceRevision)
        assertTrue(current.outcome.findings.any { it.outcome == "fail" })
        assertEquals(status(), activity.findViewById<TextView>(R.id.draft_summary_evaluation).text.toString())
        val allText = views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(allText.contains("Current cost and resource estimates are unavailable"))
        assertTrue(allText.contains(activity.getString(R.string.draft_summary_cost_unavailable)))
        val before = disk()
        activity.findViewById<Button>(R.id.current_draft_check_inspect).performClick()
        val page = dialog(); assertTrue(page.listView.adapter.count in 1..10)
        shadowOf(page).clickOnItem(0); assertTrue(dialog().isShowing)
        val body = dialog().findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(body.startsWith("Rule: "))
        for (separator in listOf("\nOutcome: ", "\nSeverity: ", "\nObservation: ", "\nRecorded guidance: ",
            "\nAffected selections: ", "\n\nNo external evidence or cost/resource estimate is supplied by this observation.")) {
            assertTrue("Rendered finding must preserve the field separator: $separator", body.contains(separator))
        }
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        finishWork(); assertEquals(before, disk())
    }

    @Test fun realModelSettingsSavePublishesAffectedRecheckingBeforeTheNewResult() {
        components(listOf("model", "accelerator", "context_policy", "deployment_mode", "compute_profile"))
        val prior = state() as PreviewCurrentDraftState.Current
        val previousRows = prior.outcome.findings.map { it.ruleId to it.outcome }
        val form = choose(modelActions()) { it == activity.getString(R.string.component_configure) }
        val settings = JSONObject(PreviewSelectionForms.settingsText(loaded().components.selections.single { it.kind == "model" })).put("temperature", 0.7)
        input(form, R.string.component_settings).setText(settings.toString())
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        observeAcknowledgement({ form.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }) {
            await { state() is PreviewCurrentDraftState.Rechecking }
            val checking = state() as PreviewCurrentDraftState.Rechecking
            assertEquals(listOf("model"), checking.request.recheck.changedKinds)
            assertTrue(checking.request.recheck.selectionIds.containsAll(listOf("sel_model", "sel_accelerator", "sel_context_policy", "sel_compute_profile")))
            assertTrue(status().contains("Rechecking saved revision"))
            assertFalse(activity.findViewById<Button>(R.id.current_draft_check_inspect).isEnabled)
            val retry = activity.findViewById<Button>(R.id.current_draft_check_retry)
            retry.performClick(); assertSame(checking, state())
        }
        await { state() is PreviewCurrentDraftState.Current }
        assertTrue((state() as PreviewCurrentDraftState.Current).request.binding.sourceRevision > prior.request.binding.sourceRevision)
        assertEquals(previousRows, prior.outcome.findings.map { it.ruleId to it.outcome })
    }

    @Test fun actualDeploymentModeReplacementRechecksUneditedRemoteEndpoints() {
        val catalog = document("catalog"); val items = catalog.getJSONArray("items")
        val mode = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "deployment_mode" }
        mode.getJSONObject("spec").put("mode", "api")
        val self = JSONObject(mode.toString()).put("id", "cat.mode.self")
        self.getJSONObject("spec").put("mode", "self_hosted"); items.put(self)
        val graph = document("dependency-graph"); val edges = graph.getJSONArray("edges")
        val remote = (0 until edges.length()).map(edges::getJSONObject).single { it.getString("id") == "dep.accelerator.model" }
        graph.put("edges", org.json.JSONArray().put(remote))
        installInputs(catalog, graph)
        components(listOf("model", "accelerator", "deployment_mode", "workload"))
        val old = loaded()
        button(R.string.component_choose_catalog).performClick()
        val choice = choose(dialog()) { it.startsWith("deployment_mode · cat.mode.self") }
        val confirmation = choose(choice) { it.startsWith("Replace ") }
        observeAcknowledgement({ confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }) {
            await { state() is PreviewCurrentDraftState.Rechecking }
            val checking = state() as PreviewCurrentDraftState.Rechecking
            assertEquals(listOf("deployment_mode"), checking.request.recheck.changedKinds)
            assertEquals(listOf("sel_accelerator", "sel_deployment_mode", "sel_model"), checking.request.recheck.selectionIds)
            assertEquals(listOf("dep.accelerator.model"), checking.request.recheck.edgeIds)
            assertEquals(old.components.selections.filter { it.kind != "deployment_mode" },
                loaded().components.selections.filter { it.kind != "deployment_mode" })
        }
        await { state() is PreviewCurrentDraftState.Current }
    }

    @Test fun actualTierDisableKeepsComponentBytesAndShowsPriorAffectedIdentitiesThenWithheld() {
        components(listOf("rag_pipeline", "vector_database", "model"), setOf(OptionalTier.T3))
        val prior = loaded()
        for (activation in listOf(Activation.DISABLED, Activation.UNKNOWN)) {
            val tier = views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3") }
            tier.performClick(); shadowOf(dialog()).clickOnItem(activation.ordinal)
            val confirm = dialog()
            observeAcknowledgement({ confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }) {
                await { state() is PreviewCurrentDraftState.Rechecking }
                val checking = state() as PreviewCurrentDraftState.Rechecking
                assertTrue(checking.request.recheck.selectionIds.containsAll(listOf("sel_model", "sel_rag_pipeline", "sel_vector_database")))
                assertEquals(prior.components, loaded().components)
                assertEquals(activation, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
            }
            await { state() is PreviewCurrentDraftState.Withheld }
            assertEquals(DraftCurrentUnavailable.OPTIONAL_INTENT_UNRESOLVED, (state() as PreviewCurrentDraftState.Withheld).outcome.reason)
            assertTrue(affected().contains("sel_rag_pipeline"))
            assertFalse(activity.findViewById<Button>(R.id.current_draft_check_inspect).isEnabled)
        }
    }

    @Test fun pendingMainEditRetiresActualCompatiblePickerAndCannotReviveItsControls() {
        components(listOf("model", "deployment_mode", "workload", "model_provider", "context_policy"))
        choose(modelActions()) { it == activity.getString(R.string.options_open) }
        val picker = dialog()
        await { picker.findViewById<TextView>(R.id.options_status)?.text?.toString() != activity.getString(R.string.options_evaluating) }
        val oldButtons = views(picker.window!!.decorView).filterIsInstance<Button>().toList()
        val before = disk(); val title = field<EditText>("title"); val saved = title.text.toString()
        title.setText("pending local edit")
        assertFalse(picker.isShowing)
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        assertTrue(status().contains("while editing"))
        oldButtons.forEach { it.performClick() }
        assertEquals(before, disk())
        title.setText(saved); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        assertEquals(before, disk())
    }

    @Test fun catalogReloadRevokesBeforeIoAndSameBytesReturnWithANewAttempt() {
        val prior = state() as PreviewCurrentDraftState.Current
        activity.findViewById<Button>(R.id.current_draft_check_inspect).performClick(); val oldDialog = dialog()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(25, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            button(R.string.retry_catalog).performClick()
            assertTrue(state() is PreviewCurrentDraftState.Unavailable)
            assertFalse(oldDialog.isShowing)
            assertTrue(status().contains("exact catalog, graph and rules"))
        } finally { release.countDown(); finishWork() }
        await { state() is PreviewCurrentDraftState.Current }
        val next = state() as PreviewCurrentDraftState.Current
        assertNotSame(prior.request, next.request)
        assertEquals(prior.request.binding.inputDigest, next.request.binding.inputDigest)
    }

    @Test fun actualNewDraftSwitchHidesPriorIdsAndNeverAdoptsPreviousSource() {
        components(listOf("model", "accelerator"))
        val prior = state() as PreviewCurrentDraftState.Current
        button(R.string.library_new).performClick(); dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        assertFalse(affected().contains("sel_model"))
        finishWork(); await { state() is PreviewCurrentDraftState.Current }
        val next = state() as PreviewCurrentDraftState.Current
        assertNotEquals(prior.request.binding.inputDigest, next.request.binding.inputDigest)
        assertNull(next.request.recheck.before)
        assertTrue(next.request.recheck.selectionIds.isEmpty())
        assertFalse(affected().contains("sel_accelerator"))
    }

    @Test fun realSaveConflictWithholdsUntilTheCanonicalReopenRecovers() {
        val previous = loaded(); val title = field<EditText>("title")
        title.setText("pending edit retained")
        val store = store(); val durable = (store.load() as DraftLoad.Available).draft
        assertTrue(store.save(durable.version, durable.content.withComponents(DraftSelections(rows("model"))), durable.metadata) is DraftSave.Saved)
        button(R.string.save_draft).performClick(); finishWork()
        assertFalse(field<Boolean>("storageReady"))
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        assertEquals(previous.revision, field<PreviewStoredDraft>("savedRecord").revision)
        assertEquals("pending edit retained", title.text.toString())
        reopen(); await { state() is PreviewCurrentDraftState.Current }
        assertEquals(loaded().revision, (state() as PreviewCurrentDraftState.Current).request.binding.sourceRevision)
    }

    @Test fun pauseAndRecreationRetireCurrentAuthorityAndDoNotRestoreAReportFromBundle() {
        val prior = state() as PreviewCurrentDraftState.Current
        val before = disk()
        controller.pause()
        assertTrue(state() is PreviewCurrentDraftState.Unavailable)
        assertFalse(activity.findViewById<Button>(R.id.current_draft_check_inspect).isEnabled)
        controller.resume(); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        assertNotSame(prior.request, (state() as PreviewCurrentDraftState.Current).request)
        assertEquals(before, disk())
        controller.recreate(); finishWork(); await { state() is PreviewCurrentDraftState.Current }
        val recreated = state() as PreviewCurrentDraftState.Current
        assertNotSame(prior.request, recreated.request)
        assertEquals(prior.request.binding.inputDigest, recreated.request.binding.inputDigest)
        assertNull(recreated.request.recheck.before)
        assertEquals(before, disk())
    }
}
