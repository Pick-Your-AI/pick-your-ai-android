package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.LocalSelection
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Activity/worker/storage paths in simulation; never installed-process evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class KnowledgeBaseSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val minimum = "{}"

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
        assertTrue("Expected integer input for $text", control is EditText)
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
        fail("Timed out waiting for knowledge-base editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "knowledge_base" }
    private fun settings(value: LocalSelection = selection()) = JSONObject(PreviewSelectionForms.settingsText(value))

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
        val actions = select(dialog()) { it.startsWith("knowledge_base ·") }
        return select(actions) { it == activity.getString(R.string.component_add) }
    }

    private fun configure(): AlertDialog {
        val target = selection()
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · ${target.id}") }
        return select(actions) { it == activity.getString(R.string.component_configure) }
    }

    private fun commit(form: AlertDialog, before: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
        return loaded()
    }

    private fun seedKnowledgeBase(): PreviewStoredDraft {
        saveInitial()
        val form = addForm()
        rawField(form, R.string.component_role).setText("primary")
        rawField(form, R.string.component_settings).setText(minimum)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        return commit(form)
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

    private val numberCaptions = listOf("Chunk size in tokens", "Chunk overlap in tokens", "Refresh interval in minutes")
    private val aclCaption = "Declared ACL enforcement"
    private val captions = numberCaptions + aclCaption
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun acl(form: AlertDialog) = captionControl(form, aclCaption) as CheckBox
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        if (!supply(form, caption).isChecked) supply(form, caption).performClick()
        number(form, caption).setText(raw)
    }
    private fun declareAcl(form: AlertDialog, value: Boolean?) {
        val present = supply(form, aclCaption)
        if (present.isChecked != (value != null)) present.performClick()
        if (value != null && acl(form).isChecked != value) acl(form).performClick()
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, numberCaptions[0], "9007199254740993")
        enter(form, numberCaptions[1], "0")
        enter(form, numberCaptions[2], "9007199254740995")
        declareAcl(form, false)
    }
    private fun readOnly(form: AlertDialog, vararg expected: String) {
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
        val text = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        for (value in expected) assertTrue("Missing retained input", value in text)
    }

    @Test fun fourOptionalFieldsHaveLabeledPrivateControlsWithoutInferredValues() {
        seedKnowledgeBase()
        val form = configure()
        assertEquals(3, views(form.window!!.decorView).filterIsInstance<EditText>().size)
        assertEquals(5, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        for (caption in numberCaptions) {
            val field = number(form, caption)
            assertEquals("", field.text.toString())
            assertFalse(field.isEnabled)
            assertFalse(field.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, field.importantForAutofill)
            assertTrue(field.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        assertFalse(acl(form).isChecked)
        assertFalse(acl(form).isEnabled)
        assertFalse(acl(form).isSaveEnabled)
        captions.forEach { assertFalse(supply(form, it).isChecked); assertFalse(supply(form, it).isSaveEnabled) }
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.knowledge_base_acl_rule)
        })
        dismiss(form)
    }

    @Test fun knowledgeBaseAddRemainsRawAndEmptySettingsDoNotSupplyAnyField() {
        saveInitial()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().none {
            it.text.toString() == activity.getString(R.string.knowledge_base_editor_guidance)
        })
        rawField(form, R.string.component_role).setText("primary")
        val saved = commit(form)
        assertEquals(1, saved.components.selections.size)
        assertEquals(PreviewKnowledgeBaseInput(), PreviewKnowledgeBaseSettings.initial(selection()))
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun exactFourFieldSaveAndReopenPreserveSelectionAndUnrelatedDraftState() {
        val before = seedKnowledgeBase()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        val values = PreviewKnowledgeBaseSettings.initial(selection())
        assertEquals(PreviewKnowledgeBaseInput("9007199254740993", "0", "9007199254740995", false), values)
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val reopened = configure()
        assertEquals(values.chunkSizeTokens, number(reopened, numberCaptions[0]).text.toString())
        assertEquals(values.chunkOverlapTokens, number(reopened, numberCaptions[1]).text.toString())
        assertEquals(values.refreshMinutes, number(reopened, numberCaptions[2]).text.toString())
        assertFalse(acl(reopened).isChecked)
        assertTrue(acl(reopened).isEnabled)
        captions.forEach { assertTrue(supply(reopened, it).isChecked) }
        dismiss(reopened)
    }

    @Test fun zeroOverlapAndFalseRemainSuppliedUntilExplicitlyRemoved() {
        val before = seedKnowledgeBase()
        val form = configure()
        enter(form, numberCaptions[1], "0")
        declareAcl(form, false)
        val saved = commit(form, before)
        assertEquals(2, settings().length())
        assertEquals("0", PreviewKnowledgeBaseSettings.initial(selection()).chunkOverlapTokens)
        assertFalse(settings().getBoolean("acl_enforced"))
        val clear = configure()
        supply(clear, numberCaptions[1]).performClick()
        declareAcl(clear, null)
        captions.forEach { assertFalse(supply(clear, it).isChecked) }
        numberCaptions.forEach { assertFalse(number(clear, it).isEnabled); assertEquals("", number(clear, it).text.toString()) }
        assertFalse(acl(clear).isEnabled)
        commit(clear, saved)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun explicitTrueFalseAndAbsentBooleanCanEachBeReviewedAndSaved() {
        val before = seedKnowledgeBase()
        val form = configure()
        declareAcl(form, true)
        val trueSaved = commit(form, before)
        assertEquals(true, PreviewKnowledgeBaseSettings.initial(selection()).aclEnforced)
        val falseForm = configure()
        assertTrue(supply(falseForm, aclCaption).isChecked)
        assertTrue(acl(falseForm).isChecked)
        declareAcl(falseForm, false)
        val falseSaved = commit(falseForm, trueSaved)
        assertEquals(false, PreviewKnowledgeBaseSettings.initial(selection()).aclEnforced)
        val absentForm = configure()
        declareAcl(absentForm, null)
        commit(absentForm, falseSaved)
        assertNull(PreviewKnowledgeBaseSettings.initial(selection()).aclEnforced)
    }

    @Test fun invalidNumbersKeepIndependentValueFreeErrorsAndTheAcknowledgedDraft() {
        val before = seedKnowledgeBase()
        val form = configure()
        numberCaptions.forEach { enter(form, it, "") }
        declareAcl(form, false)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        numberCaptions.forEach {
            assertEquals(activity.getString(R.string.knowledge_base_number_required), number(form, it).error.toString())
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, numberCaptions[0], "1,5")
        assertNull(number(form, numberCaptions[0]).error)
        assertNotNull(number(form, numberCaptions[1]).error)
        enter(form, numberCaptions[1], "-1")
        enter(form, numberCaptions[2], "0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.knowledge_base_integer_invalid), number(form, numberCaptions[0]).error.toString())
        assertEquals(activity.getString(R.string.knowledge_base_overlap_rule), number(form, numberCaptions[1]).error.toString())
        assertEquals(activity.getString(R.string.knowledge_base_positive_rule), number(form, numberCaptions[2]).error.toString())
        assertEquals("1,5", number(form, numberCaptions[0]).text.toString())
        assertFalse(number(form, numberCaptions[0]).error.toString().contains("1,5"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, numberCaptions[0], "1")
        enter(form, numberCaptions[1], "9007199254740993")
        enter(form, numberCaptions[2], "1e3")
        commit(form, before)
        assertEquals("9007199254740993", PreviewKnowledgeBaseSettings.initial(selection()).chunkOverlapTokens)
        assertEquals("1E+3", PreviewKnowledgeBaseSettings.initial(selection()).refreshMinutes)
        assertFalse(settings().getBoolean("acl_enforced"))
    }

    @Test fun cancelAndUnchangedCommitDoNotCreateAnotherRevision() {
        val before = seedKnowledgeBase()
        val form = configure()
        fillAll(form)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val noop = configure()
        noop.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !noop.isShowing }; finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun invalidInputAndFalseRecoverOnlyAfterReviewAndOldControlsStayRetired() {
        val before = seedKnowledgeBase()
        val form = configure()
        enter(form, numberCaptions[0], "unfinished")
        enter(form, numberCaptions[1], "")
        declareAcl(form, false)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, aclCaption)
        val retiredAcl = acl(form)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedKnowledgeBasePending).form
            assertEquals(PreviewKnowledgeBaseInput("unfinished", "", null, false), pending.input)
            assertEquals(selection().catalogItemId, pending.catalogItemId)
            assertEquals(selection().catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, numberCaptions[0]).text.toString())
        assertTrue(supply(restored, numberCaptions[1]).isChecked)
        assertFalse(supply(restored, numberCaptions[2]).isChecked)
        assertTrue(supply(restored, aclCaption).isChecked)
        assertFalse(acl(restored).isChecked)
        retired.performClick(); retiredSupply.performClick(); retiredAcl.performClick(); finishWork()
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertFalse(retiredAcl.isChecked)
        assertFalse(retiredAcl.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, numberCaptions[0], "2")
        enter(restored, numberCaptions[1], "0")
        commit(restored, before)
        assertFalse(settings().getBoolean("acl_enforced"))
    }

    @Test fun changedSavedHeadKeepsPendingValuesReadOnlyUntilDiscardAndReopen() {
        val before = seedKnowledgeBase()
        val form = configure()
        enter(form, numberCaptions[0], "unfinished")
        declareAcl(form, false)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", "Not supplied", activity.getString(R.string.knowledge_base_acl_false))
        recreate()
        val retained = reviewPending()
        readOnly(retained, "unfinished")
        dismiss(retained)
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        dismiss(reviewPending())
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun wrongDraftSelectionAndCatalogIdentityCannotReceiveNewSaveAuthority() {
        val before = seedKnowledgeBase()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, numberCaptions[0], "pending value")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, numberCaptions[0], "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightSaveFreezesAllControlsAndRecoveryNeverReplaysTheCommit() {
        val before = seedKnowledgeBase()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            numberCaptions.forEach { assertFalse(number(form, it).isEnabled) }
            captions.forEach { assertFalse(supply(form, it).isEnabled) }
            assertFalse(acl(form).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, aclCaption).performClick(); acl(form).performClick()
            assertTrue(supply(form, aclCaption).isChecked)
            assertFalse(acl(form).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedKnowledgeBasePending).form
                assertTrue(pending.wasSaving)
                assertEquals(false, pending.input.aclEnforced)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish the admitted write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "9007199254740993", "0", "9007199254740995", activity.getString(R.string.knowledge_base_acl_false))
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableControls() {
        val before = seedKnowledgeBase()
        val form = configure()
        enter(form, numberCaptions[0], "2")
        declareAcl(form, true)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        numberCaptions.forEach { assertFalse(number(form, it).isEnabled) }
        captions.forEach { assertFalse(supply(form, it).isEnabled) }
        assertFalse(acl(form).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "2", "Not supplied", activity.getString(R.string.knowledge_base_acl_true))
    }

    @Test fun oversizedInputStaysLiveButCheckpointRetainsNoPrefixOrAutomaticViewState() {
        val before = seedKnowledgeBase()
        val form = configure()
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, numberCaptions[2], raw)
        declareAcl(form, false)
        assertEquals(raw, number(form, numberCaptions[2]).text.toString())
        recreate { state ->
            val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawKnowledgeBaseCheckpointsKeepTheirExistingCorrectionReader() {
        val before = seedKnowledgeBase()
        val target = selection()
        val raw = """{"chunk_size_tokens":"""
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
