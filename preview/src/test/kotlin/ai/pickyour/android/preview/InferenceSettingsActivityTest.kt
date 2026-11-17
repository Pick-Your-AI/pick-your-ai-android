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
class InferenceSettingsActivityTest {
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

    private fun choice(form: AlertDialog, text: String): Button {
        val control = captionControl(form, text)
        assertTrue("Expected explicit enum control for $text", control is Button)
        return control as Button
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
        fail("Timed out waiting for inference editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "inference_engine" }
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
        val actions = select(dialog()) { it.startsWith("inference_engine ·") }
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

    private fun seedInference(): PreviewStoredDraft {
        saveInitial()
        val form = addForm()
        rawField(form, R.string.component_role).setText("primary")
        rawField(form, R.string.component_settings).setText(minimum)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        return commit(form)
    }

    private fun choose(control: Button, value: String) {
        control.performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString() == value
        }
        shadowOf(picker).clickOnItem(index)
        await { !picker.isShowing }
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

    @Test fun bothIntegersHaveExplicitOptionalControlsWithoutDefaults() {
        val before = seedInference()
        val form = configure()
        for (caption in listOf("Tensor parallel size", "Maximum batch tokens")) {
            val control = number(form, caption)
            assertEquals("", control.text.toString())
            assertFalse(control.isEnabled)
            assertEquals(activity.getString(R.string.inference_not_supplied), control.hint.toString())
            supply(form, caption).performClick()
            assertTrue(control.isEnabled)
            assertEquals("", control.text.toString())
            assertNull(control.hint)
            supply(form, caption).performClick()
            assertFalse(control.isEnabled)
            assertEquals(activity.getString(R.string.inference_not_supplied), control.hint.toString())
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun precisionAndQuantizationHaveExplicitOptionalEnumControls() {
        val before = seedInference()
        val form = configure()
        for (caption in listOf("Precision", "Quantization"))
            assertEquals("Not supplied", choice(form, caption).text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun inferenceAddRetainsRawJsonWithoutPrefillingOptionalSettings() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    private val textCaptions = listOf("Tensor parallel size", "Maximum batch tokens")
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        val supplied = supply(form, caption)
        if (!supplied.isChecked) supplied.performClick()
        val control = number(form, caption)
        assertTrue(control.isEnabled)
        control.setText(raw)
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only model value", it in shown) }
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)
    private fun assertFixedMarker(state: Bundle, userinfo: Boolean) {
        val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertEquals(if (userinfo) setOf("version", "unavailable", "reason") else setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
        if (userinfo) assertEquals("uri_userinfo", value.getString("reason"))
    }


    private fun fillAll(form: AlertDialog) {
        enter(form, "Tensor parallel size", "2")
        enter(form, "Maximum batch tokens", "9007199254740993")
        choose(choice(form, "Precision"), "tf32")
        choose(choice(form, "Quantization"), "awq")
    }

    @Test fun cancelConfigurePreservesTheAcknowledgedInference() {
        val before = seedInference()
        val original = PreviewSelectionForms.settingsText(selection())
        dismiss(configure())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals(original, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawInferenceCheckpointsKeepInvalidTextWithoutFreshAuthority() {
        val before = seedInference()
        val target = selection()
        val raw = """{"tensor_parallel_size":"""
        for (version in 1..2) {
            recreate { state ->
                val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE,
                    currentId().value, before.baselineIdentity, target.id,
                    null, null, null, null, "", raw, false, false)
                state.putBundle(ComponentFormRecovery.KEY, requireNotNull(legacy.bundle()).apply { putInt("version", version) })
            }
            val restored = reviewPending()
            assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            finishWork()
            assertTrue(restored.isShowing)
            assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(restored)
            button(R.string.component_recovery_discard).performClick()
        }
    }

    @Test fun absentTypedCommitClosesWithoutCreatingAnotherRevisionOrDefaults() {
        val before = seedInference()
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) })
    }

    @Test fun wrongDraftSelectionAndCatalogIdentityNeverReceiveFreshInferenceSaveAuthority() {
        val before = seedInference()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Tensor parallel size", "pending value")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Tensor parallel size", "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "context_policy") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightTypedInferenceFreezesAllControlsAndNeverReplaysAfterAcknowledgment() {
        val before = seedInference()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            textCaptions.forEach {
                assertFalse(supply(form, it).isEnabled)
                assertFalse(number(form, it).isEnabled)
            }
            for (caption in listOf("Precision", "Quantization")) assertFalse(choice(form, caption).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            val previousLatest = dialog()
            choice(form, "Precision").performClick()
            supply(form, "Tensor parallel size").performClick()
            assertSame(previousLatest, dialog())
            assertTrue(form.isShowing)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedInferencePending).form
                assertTrue(pending.wasSaving)
                assertEquals("tf32", pending.input.precision)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "2", "9007199254740993", "tf32", "awq")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun retiredPrecisionPickerAndSupplyControlCannotReviveAClosedForm() {
        val before = seedInference()
        val form = configure()
        val precision = choice(form, "Precision")
        choose(precision, "tf32")
        precision.performClick()
        val picker = dialog()
        assertEquals("tf32", picker.listView.adapter.getItem(picker.listView.checkedItemPosition))
        val oldSupply = supply(form, "Tensor parallel size")
        dismiss(form)
        assertFalse(picker.isShowing)
        shadowOf(picker).clickOnItem(1)
        precision.performClick()
        oldSupply.performClick()
        finishWork()
        assertEquals("tf32", precision.text.toString())
        assertFalse(oldSupply.isChecked)
        assertFalse(precision.isEnabled)
        assertFalse(oldSupply.isEnabled)
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableTypedControls() {
        val before = seedInference()
        val form = configure()
        enter(form, "Tensor parallel size", "1")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(number(form, it).isEnabled)
        }
        assertFalse(choice(form, "Precision").isEnabled)
        assertFalse(choice(form, "Quantization").isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "1", "Not supplied")
    }

    @Test fun oversizedTypedInferenceRecoveryHasNoPrefixAndNoAutomaticViewStateFallback() {
        val before = seedInference()
        val form = configure()
        textCaptions.forEach {
            assertFalse(number(form, it).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, number(form, it).importantForAutofill)
            assertTrue(number(form, it).imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertFalse(supply(form, it).isSaveEnabled)
        }
        assertFalse(choice(form, "Precision").isSaveEnabled)
        assertFalse(choice(form, "Quantization").isSaveEnabled)
        val raw = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, "Tensor parallel size", raw)
        assertEquals(raw, number(form, "Tensor parallel size").text.toString())
        recreate { assertFixedMarker(it, userinfo = false) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun fourValuesSaveExactlyAndQuantizationChangesPreserveOtherFields() {
        val before = seedInference()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        assertEquals(4, settings().length())
        assertEquals("2", PreviewInferenceSettings.initial(selection()).tensorParallelSize)
        assertEquals("9007199254740993", PreviewInferenceSettings.initial(selection()).maxBatchTokens)
        assertEquals("tf32", settings().getString("precision"))
        assertEquals("awq", settings().getString("quantization"))
        assertEquals(original.id, selection().id)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        val initial = PreviewSelectionForms.settingsText(selection())
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(initial, PreviewSelectionForms.settingsText(selection()))
        val reopened = configure()
        textCaptions.forEach { assertTrue(supply(reopened, it).isChecked) }
        val otherText = textCaptions.associateWith { number(reopened, it).text.toString() }
        choose(choice(reopened, "Quantization"), "none")
        assertEquals(otherText, textCaptions.associateWith { number(reopened, it).text.toString() })
        val none = commit(reopened, saved)
        assertEquals("none", settings().getString("quantization"))
        val remove = configure()
        choose(choice(remove, "Quantization"), "Not supplied")
        commit(remove, none)
        assertFalse(settings().has("quantization"))
        assertEquals("tf32", settings().getString("precision"))
        assertEquals(3, settings().length())
    }

    @Test fun actualPauseRetiresTypedInferenceControlsAndRequiresFreshReview() {
        val before = seedInference()
        val form = configure()
        fillAll(form)
        val entered = textCaptions.associateWith { number(form, it).text.toString() }
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredPrecision = choice(form, "Precision")
        val retiredSupply = supply(form, "Tensor parallel size")
        retiredPrecision.performClick()
        val picker = dialog()

        controller.pause()
        assertFalse(form.isShowing)
        assertFalse(picker.isShowing)
        retiredCommit.performClick()
        shadowOf(picker).clickOnItem(1)
        retiredPrecision.performClick()
        retiredSupply.performClick()
        finishWork()
        assertSame(picker, dialog())
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertFalse(retiredPrecision.isEnabled)
        assertEquals("tf32", retiredPrecision.text.toString())
        assertEquals(before.components, loaded().components)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val state = capture()
        assertEquals(6, state.getBundle(ComponentFormRecovery.KEY)!!.getInt("version"))
        val pending = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedInferencePending).form
        assertFalse(pending.wasSaving)
        assertEquals(before.baselineIdentity, pending.baseline)
        assertEquals("2", pending.input.tensorParallelSize)
        assertEquals("9007199254740993", pending.input.maxBatchTokens)
        assertEquals("tf32", pending.input.precision)
        assertEquals("awq", pending.input.quantization)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertSame(picker, dialog())
        assertTrue(ComponentFormRecovery.read(capture()) is ComponentFormRecovery.TypedInferencePending)

        controller.resume().visible()
        finishWork()
        assertSame(picker, dialog())
        retiredCommit.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val reviewed = reviewPending()
        assertNotSame(form, reviewed)
        textCaptions.forEach { assertTrue(supply(reviewed, it).isChecked) }
        assertEquals(entered, textCaptions.associateWith { number(reviewed, it).text.toString() })
        assertEquals("tf32", choice(reviewed, "Precision").text.toString())
        assertEquals("awq", choice(reviewed, "Quantization").text.toString())
        retiredCommit.performClick()
        shadowOf(picker).clickOnItem(1)
        retiredSupply.performClick()
        finishWork()
        assertSame(reviewed, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val saved = commit(reviewed, before)
        retiredCommit.performClick()
        reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(4, settings().length())
        assertEquals("2", PreviewInferenceSettings.initial(selection()).tensorParallelSize)
        assertEquals("9007199254740993", PreviewInferenceSettings.initial(selection()).maxBatchTokens)
        assertEquals("tf32", settings().getString("precision"))
        assertEquals("awq", settings().getString("quantization"))
    }

    @Test fun independentSuppliedErrorsPersistUntilCorrectionOrExplicitRemoval() {
        val before = seedInference()
        val form = configure()
        textCaptions.forEach { enter(form, it, ""); assertNull(number(form, it).hint) }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach {
            assertEquals(activity.getString(R.string.inference_value_required), number(form, it).error.toString())
            assertTrue(supply(form, it).isChecked)
        }
        enter(form, "Tensor parallel size", "0")
        assertNull(number(form, "Tensor parallel size").error)
        assertNotNull(number(form, "Maximum batch tokens").error)
        enter(form, "Maximum batch tokens", "1,5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.inference_tensor_parallel_rule), number(form, "Tensor parallel size").error.toString())
        assertEquals(activity.getString(R.string.inference_integer_invalid), number(form, "Maximum batch tokens").error.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Tensor parallel size", "1.000")
        enter(form, "Maximum batch tokens", "1e3")
        val saved = commit(form, before)
        val clear = configure()
        val retained = number(clear, "Maximum batch tokens").text.toString()
        supply(clear, "Tensor parallel size").performClick()
        assertFalse(number(clear, "Tensor parallel size").isEnabled)
        assertEquals("", number(clear, "Tensor parallel size").text.toString())
        assertEquals(retained, number(clear, "Maximum batch tokens").text.toString())
        commit(clear, saved)
        assertFalse(settings().has("tensor_parallel_size"))
        assertEquals(1, settings().length())
    }

    @Test fun privateInvalidTextAndExplicitPresenceRecoverOnlyAfterReview() {
        val before = seedInference()
        val form = configure()
        enter(form, "Tensor parallel size", "1,5")
        enter(form, "Maximum batch tokens", "")
        choose(choice(form, "Precision"), "tf32")
        choose(choice(form, "Quantization"), "none")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedInferencePending).form
            assertEquals("1,5", pending.input.tensorParallelSize)
            assertEquals("", pending.input.maxBatchTokens)
            assertEquals("none", pending.input.quantization)
            assertEquals(selection().catalogItemId, pending.catalogItemId)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        textCaptions.forEach { assertTrue(supply(restored, it).isChecked); assertNull(number(restored, it).hint) }
        assertEquals("1,5", number(restored, "Tensor parallel size").text.toString())
        assertEquals("", number(restored, "Maximum batch tokens").text.toString())
        assertEquals("tf32", choice(restored, "Precision").text.toString())
        assertEquals("none", choice(restored, "Quantization").text.toString())
        retired.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, "Tensor parallel size", "2")
        enter(restored, "Maximum batch tokens", "1e3")
        commit(restored, before)
        assertEquals("none", settings().getString("quantization"))
    }

    @Test fun changedSavedHeadRetainsAllInputsReadOnlyUntilExplicitDiscardAndReopen() {
        val before = seedInference()
        val form = configure()
        fillAll(form)
        enter(form, "Tensor parallel size", "unfinished")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", "9007199254740993", "tf32", "awq")
        recreate()
        val retained = reviewPending()
        readOnly(retained, "unfinished", "tf32")
        dismiss(retained)
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        dismiss(reviewPending())
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }
}
