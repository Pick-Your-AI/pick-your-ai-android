package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.*
import ai.pickyour.core.drafts.LocalSelection
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Existing Activity/worker/storage paths in simulation; no installed-process evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RagPipelineSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }

    private fun button(label: Int) = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }

    private fun captionControl(form: AlertDialog, text: String): View {
        val caption = views(form.window!!.decorView).filterIsInstance<TextView>()
            .singleOrNull { it.text.toString() == text }
        assertNotNull("Missing typed setting caption: $text", caption)
        assertNotEquals("Typed caption must label its native control", View.NO_ID, caption!!.labelFor)
        return requireNotNull(form.findViewById(caption.labelFor))
    }

    private fun number(form: AlertDialog, text: String): EditText {
        val control = captionControl(form, text)
        assertTrue("Expected whole-number input for $text", control is EditText)
        return control as EditText
    }

    private fun rawField(form: AlertDialog, label: Int): EditText {
        val caption = views(form.window!!.decorView).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(label)
        }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return requireNotNull(form.findViewById(caption.labelFor))
    }

    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for RAG-pipeline editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "rag_pipeline" }

    private fun saveInitial() {
        button(R.string.save_draft).performClick()
        await {
            !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.draft_saved)
            }
        }
        finishWork()
    }

    private fun select(picker: AlertDialog, match: (String) -> Boolean): AlertDialog {
        val index = (0 until picker.listView.adapter.count).single {
            match(picker.listView.adapter.getItem(it).toString())
        }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }

    private fun addForm(): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        val actions = select(dialog()) { it.startsWith("${componentTokenForLookup(activity, "rag_pipeline")} ·") }
        return select(actions) { it == activity.getString(R.string.component_add) }
    }

    private fun configure(): AlertDialog {
        val target = selection()
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · ${componentTokenForLookup(activity, target.id)}") }
        return select(actions) { it == activity.getString(R.string.component_configure) }
    }

    private fun seedRag(): PreviewStoredDraft {
        saveInitial()
        val before = loaded()
        val form = addForm()
        rawField(form, R.string.component_role).setText("primary")
        rawField(form, R.string.component_settings).setText("{}")
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        return loaded()
    }

    @Test fun configureShowsTypedOptionalRagPipelineFields() {
        val before = seedRag()
        val selected = selection()
        assertEquals("primary", selected.role)
        assertFalse(selected.enabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selected))
        val form = configure()
        assertTrue(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(number(form, "Top K").isEnabled)
        assertFalse(number(form, "Maximum chunks").isEnabled)
        val rerank = captionControl(form, "Reranking enabled")
        assertTrue("Expected explicit Boolean control", rerank is CheckBox)
        assertFalse(rerank.isEnabled)
        assertFalse((rerank as CheckBox).isChecked)
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        finishWork()
        assertFalse(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    private fun settings(value: LocalSelection = selection()) = JSONObject(PreviewSelectionForms.settingsText(value))

    private fun commit(form: AlertDialog, before: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        return loaded()
    }

    private fun dismiss(form: AlertDialog) {
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing }
        finishWork()
    }

    private fun parcel(state: Bundle): Bundle {
        val writer = Parcel.obtain()
        val reader = Parcel.obtain()
        return try {
            writer.writeBundle(state)
            val bytes = writer.marshall()
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            requireNotNull(reader.readBundle(javaClass.classLoader))
        } finally { writer.recycle(); reader.recycle() }
    }

    private fun recreate(editCheckpoint: (Bundle) -> Unit = {}) {
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        editCheckpoint(state)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java)
            .create(parcel(state)).start().resume().visible()
        finishWork()
    }

    private fun reviewPending(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }

    private val numericCaptions = listOf("Top K", "Maximum chunks")
    private val captions = numericCaptions + "Reranking enabled"
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun rerank(form: AlertDialog): CheckBox {
        val control = captionControl(form, "Reranking enabled")
        assertTrue(control is CheckBox)
        return control as CheckBox
    }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        if (!supply(form, caption).isChecked) supply(form, caption).performClick()
        assertTrue(number(form, caption).isEnabled)
        number(form, caption).setText(raw)
    }
    private fun enterRerank(form: AlertDialog, value: Boolean) {
        if (!supply(form, "Reranking enabled").isChecked) supply(form, "Reranking enabled").performClick()
        val control = rerank(form)
        assertTrue(control.isEnabled)
        if (control.isChecked != value) control.performClick()
        assertEquals(value, control.isChecked)
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Top K", "9007199254740993")
        enter(form, "Maximum chunks", "1e3")
        enterRerank(form, false)
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(form.window!!.decorView).filterIsInstance<CheckBox>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only RAG value", it in shown) }
    }

    @Test fun labelledControlsAndSupplyHintsKeepEveryOptionalStatePrivateAndExplicit() {
        val before = seedRag()
        val form = configure()
        assertEquals(4, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        for (caption in numericCaptions) {
            val control = number(form, caption)
            assertFalse(control.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, control.importantForAutofill)
            assertTrue(control.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertEquals(activity.getString(R.string.rag_pipeline_not_supplied), control.hint.toString())
            assertFalse(supply(form, caption).isChecked)
            assertFalse(supply(form, caption).isSaveEnabled)
            supply(form, caption).performClick()
            assertTrue(control.isEnabled)
            assertNull(control.hint)
            assertEquals("", control.text.toString())
            supply(form, caption).performClick()
            assertFalse(control.isEnabled)
            assertEquals(activity.getString(R.string.rag_pipeline_not_supplied), control.hint.toString())
        }
        assertFalse(rerank(form).isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, rerank(form).importantForAutofill)
        assertFalse(rerank(form).isEnabled)
        assertFalse(supply(form, "Reranking enabled").isChecked)
        enterRerank(form, false)
        assertTrue(supply(form, "Reranking enabled").isChecked)
        assertFalse(rerank(form).isChecked)
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_rag_pipeline_guidance)
        })
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_retrieval_intent_guidance)
        })
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun ragAddKeepsTheExplicitRawSettingsRoleAndEnabledPath() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun exactOptionalIntegersAndFalseSaveAndReopenWithoutChangingSelectionOrOtherDraftState() {
        val before = seedRag()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        val values = PreviewRagPipelineSettings.initial(selection())
        assertEquals("9007199254740993", values.topK)
        assertEquals("1E+3", values.maxChunks)
        assertEquals(false, values.rerankEnabled)
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertTrue(settings().get("rerank_enabled") is Boolean)
        assertFalse(settings().getBoolean("rerank_enabled"))
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val reopened = configure()
        assertEquals(values.topK, number(reopened, "Top K").text.toString())
        assertEquals(values.maxChunks, number(reopened, "Maximum chunks").text.toString())
        captions.forEach { assertTrue(supply(reopened, it).isChecked) }
        assertFalse(rerank(reopened).isChecked)
        dismiss(reopened)
    }

    @Test fun explicitFalseTrueAndAbsenceRemainThreeDifferentSavedStates() {
        val before = seedRag()
        val falseForm = configure()
        enterRerank(falseForm, false)
        val suppliedFalse = commit(falseForm, before)
        assertEquals(1, settings().length())
        assertTrue(settings().has("rerank_enabled"))
        assertFalse(settings().getBoolean("rerank_enabled"))
        val trueForm = configure()
        assertTrue(supply(trueForm, "Reranking enabled").isChecked)
        assertTrue(rerank(trueForm).isEnabled)
        assertFalse(rerank(trueForm).isChecked)
        rerank(trueForm).performClick()
        val suppliedTrue = commit(trueForm, suppliedFalse)
        assertTrue(settings().getBoolean("rerank_enabled"))
        val clear = configure()
        supply(clear, "Reranking enabled").performClick()
        assertFalse(rerank(clear).isEnabled)
        assertFalse(rerank(clear).isChecked)
        commit(clear, suppliedTrue)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        val absent = configure()
        assertFalse(supply(absent, "Reranking enabled").isChecked)
        assertFalse(rerank(absent).isEnabled)
        dismiss(absent)
    }

    @Test fun everyOptionalValueCanBeRemovedWithoutCreatingDefaults() {
        val before = seedRag()
        val form = configure()
        fillAll(form)
        val supplied = commit(form, before)
        val clear = configure()
        captions.forEach { assertTrue(supply(clear, it).isChecked); supply(clear, it).performClick() }
        numericCaptions.forEach {
            assertFalse(number(clear, it).isEnabled)
            assertEquals("", number(clear, it).text.toString())
        }
        assertFalse(rerank(clear).isEnabled)
        assertFalse(rerank(clear).isChecked)
        commit(clear, supplied)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun independentNumericErrorsRetainLiteralInputAndCannotReplaceAcknowledgedSettings() {
        val before = seedRag()
        val form = configure()
        enter(form, "Top K", "")
        enter(form, "Maximum chunks", "")
        enterRerank(form, false)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        numericCaptions.forEach {
            assertEquals(activity.getString(R.string.rag_pipeline_value_required), number(form, it).error.toString())
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Top K", "1,5")
        assertNull(number(form, "Top K").error)
        assertNotNull(number(form, "Maximum chunks").error)
        enter(form, "Maximum chunks", "0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.rag_pipeline_integer_invalid), number(form, "Top K").error.toString())
        assertEquals(activity.getString(R.string.rag_pipeline_integer_rule), number(form, "Maximum chunks").error.toString())
        assertEquals("1,5", number(form, "Top K").text.toString())
        assertFalse(number(form, "Top K").error.toString().contains("1,5"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Top K", "1e3")
        supply(form, "Maximum chunks").performClick()
        commit(form, before)
        assertEquals("1E+3", PreviewRagPipelineSettings.initial(selection()).topK)
        assertFalse(settings().has("max_chunks"))
        assertTrue(settings().has("rerank_enabled"))
        assertFalse(settings().getBoolean("rerank_enabled"))
    }

    @Test fun cancelAndUnchangedCommitDoNotCreateARevisionOrOptionalDefaults() {
        val before = seedRag()
        val form = configure()
        fillAll(form)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val noop = configure()
        noop.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { !noop.isShowing }
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun invalidInputAndExplicitFalseRecoverOnlyAfterReviewWithRetiredControls() {
        val before = seedRag()
        val target = selection()
        val form = configure()
        enter(form, "Top K", "unfinished")
        enter(form, "Maximum chunks", "")
        enterRerank(form, false)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, "Maximum chunks")
        val retiredValue = rerank(form)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedRagPipelinePending).form
            assertEquals(PreviewRagPipelineInput("unfinished", "", false), pending.input)
            assertEquals(target.catalogItemId, pending.catalogItemId)
            assertEquals(target.catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, "Top K").text.toString())
        assertEquals("", number(restored, "Maximum chunks").text.toString())
        numericCaptions.forEach { assertTrue(supply(restored, it).isChecked); assertNull(number(restored, it).hint) }
        assertTrue(supply(restored, "Reranking enabled").isChecked)
        assertFalse(rerank(restored).isChecked)
        retiredCommit.performClick(); retiredSupply.performClick(); retiredValue.performClick(); finishWork()
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertFalse(retiredValue.isChecked)
        assertFalse(retiredValue.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, "Top K", "2")
        enter(restored, "Maximum chunks", "3")
        commit(restored, before)
        assertEquals("2", PreviewRagPipelineSettings.initial(selection()).topK)
        assertEquals("3", PreviewRagPipelineSettings.initial(selection()).maxChunks)
        assertFalse(settings().getBoolean("rerank_enabled"))
    }

    @Test fun changedSavedHeadKeepsPendingRagInputReadOnlyUntilDiscardAndReopen() {
        val before = seedRag()
        val form = configure()
        enter(form, "Top K", "unfinished")
        enterRerank(form, false)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", activity.getString(R.string.rag_pipeline_supplied_value),
            activity.getString(R.string.rag_pipeline_not_supplied), activity.getString(R.string.rag_pipeline_supplied_false))
        recreate()
        val retained = reviewPending()
        readOnly(retained, "unfinished", activity.getString(R.string.rag_pipeline_supplied_false))
        dismiss(retained)
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        dismiss(reviewPending())
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun everyOriginalIdentityAndTheRagKindRemainNecessaryForWritableRecovery() {
        val before = seedRag()
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Top K", "pending value")
            enterRerank(form, false)
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", activity.getString(R.string.rag_pipeline_supplied_false))
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Top K", "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightRagSaveFreezesAllControlsAndRecoveryCannotReplayTheAcknowledgedWrite() {
        val before = seedRag()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            numericCaptions.forEach { assertFalse(number(form, it).isEnabled) }
            captions.forEach { assertFalse(supply(form, it).isEnabled) }
            assertFalse(rerank(form).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, "Reranking enabled").performClick()
            rerank(form).performClick()
            assertTrue(supply(form, "Reranking enabled").isChecked)
            assertFalse(rerank(form).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedRagPipelinePending).form
                assertTrue(pending.wasSaving)
                assertEquals(PreviewRagPipelineInput("9007199254740993", "1e3", false), pending.input)
                release.countDown()
                // Let the owned writer finish before the first durable observation.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "9007199254740993", "1e3", activity.getString(R.string.rag_pipeline_supplied_false))
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun actualPauseRetiresRagControlsAndRequiresExplicitReviewBeforeEditing() {
        val before = seedRag()
        val form = configure()
        enter(form, "Top K", "unfinished")
        enterRerank(form, true)
        val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val oldSupply = supply(form, "Top K")
        val oldBoolean = rerank(form)
        controller.pause()
        assertFalse(form.isShowing)
        commit.performClick(); oldSupply.performClick(); oldBoolean.performClick()
        controller.resume().visible()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, "Top K").text.toString())
        assertTrue(supply(restored, "Reranking enabled").isChecked)
        assertTrue(rerank(restored).isChecked)
        assertTrue(oldSupply.isChecked)
        assertTrue(oldBoolean.isChecked)
        assertFalse(oldSupply.isEnabled)
        assertFalse(oldBoolean.isEnabled)
        dismiss(restored)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun staleManagedRagCommitCannotOverwriteAnotherWriterOrRestoreSaveAuthority() {
        val before = seedRag()
        val form = configure()
        enter(form, "Top K", "2")
        enterRerank(form, false)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        numericCaptions.forEach { assertFalse(number(form, it).isEnabled) }
        captions.forEach { assertFalse(supply(form, it).isEnabled) }
        assertFalse(rerank(form).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "2", activity.getString(R.string.rag_pipeline_supplied_false))
    }

    @Test fun combinedOversizedInputRetainsNoRecoveryPrefixOrAutomaticViewState() {
        val before = seedRag()
        val form = configure()
        val first = "a".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS / 2)
        val second = "b".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS / 2 + 1)
        enter(form, "Top K", first)
        enter(form, "Maximum chunks", second)
        enterRerank(form, true)
        assertEquals(first, number(form, "Top K").text.toString())
        assertEquals(second, number(form, "Maximum chunks").text.toString())
        recreate { state ->
            val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(views().filterIsInstance<TextView>().any { it.text.toString() == first || it.text.toString() == second })
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun readOnlyLiteralStateWordsAndSuppliedEmptyRemainDifferentFromAbsence() {
        val before = seedRag()
        val form = configure()
        enter(form, "Top K", "Not supplied")
        enter(form, "Maximum chunks", "Supplied: false")
        enterRerank(form, false)
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("draftId", "other_fixture_identity") }
        val retained = reviewPending()
        readOnly(retained, "Not supplied", "Supplied: false")
        val shown = views(retained.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(2, shown.count { it == activity.getString(R.string.rag_pipeline_supplied_value) })
        assertEquals(2, shown.count { it == activity.getString(R.string.rag_pipeline_supplied_false) })
        dismiss(retained)
        button(R.string.component_recovery_discard).performClick()
        val empty = configure()
        enter(empty, "Top K", "")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("draftId", "other_fixture_identity") }
        val recoveredEmpty = reviewPending()
        readOnly(recoveredEmpty, activity.getString(R.string.rag_pipeline_empty_value),
            activity.getString(R.string.rag_pipeline_not_supplied))
        val states = views(recoveredEmpty.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(1, states.count { it == activity.getString(R.string.rag_pipeline_empty_value) })
        assertEquals(2, states.count { it == activity.getString(R.string.rag_pipeline_not_supplied) })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun legacyRawRagRecoveryStillUsesItsOriginalCorrectionReader() {
        val before = seedRag()
        val target = selection()
        val raw = """{"top_k":"""
        for (version in 1..2) {
            recreate { state ->
                val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, currentId().value,
                    before.baselineIdentity, target.id, null, null, null, null, "", raw, false, false)
                state.putBundle(ComponentFormRecovery.KEY, requireNotNull(legacy.bundle()).apply { putInt("version", version) })
            }
            val restored = reviewPending()
            assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
            assertTrue(restored.isShowing)
            assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(restored)
            button(R.string.component_recovery_discard).performClick()
        }
    }
}
