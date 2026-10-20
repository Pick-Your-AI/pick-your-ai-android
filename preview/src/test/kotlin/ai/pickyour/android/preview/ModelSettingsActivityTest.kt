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
class ModelSettingsActivityTest {
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
        fail("Timed out waiting for model editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "model" }
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
        val actions = select(dialog()) { it.startsWith("model ·") }
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

    private fun seedModel(): PreviewStoredDraft {
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

    @Test fun temperatureHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        val control = number(form, "Temperature")
        assertEquals("", control.text.toString())
        assertFalse("An absent field must not acquire a supplied value", control.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun topPHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        val control = number(form, "Top p")
        assertEquals("", control.text.toString())
        assertFalse("An absent field must not acquire a supplied value", control.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun maxOutputTokensHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        val control = number(form, "Maximum output tokens")
        assertEquals("", control.text.toString())
        assertFalse("An absent field must not acquire a supplied value", control.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun precisionHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        assertEquals("Not supplied", choice(form, "Precision").text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun servingModeHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        assertEquals("Not supplied", choice(form, "Serving mode").text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun credentialReferenceHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        val control = number(form, "Credential reference")
        assertEquals("", control.text.toString())
        assertFalse("An absent field must not acquire a supplied value", control.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun weightsUriHasAnExplicitOptionalTypedControl() {
        val before = seedModel()
        val form = configure()
        val control = number(form, "Weights URI")
        assertEquals("", control.text.toString())
        assertFalse("An absent field must not acquire a supplied value", control.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun modelAddStillUsesRawJsonAndDoesNotPrefillOptionalSettings() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun cancelConfigurePreservesTheAcknowledgedModel() {
        val before = seedModel()
        val original = PreviewSelectionForms.settingsText(selection())
        dismiss(configure())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals(original, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawModelCheckpointsKeepInvalidTextWithoutFreshAuthority() {
        val before = seedModel()
        val target = selection()
        val raw = """{"temperature":"""
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

    private val textCaptions = listOf("Temperature", "Top p", "Maximum output tokens", "Credential reference", "Weights URI")
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        val supplied = supply(form, caption)
        if (!supplied.isChecked) supplied.performClick()
        val control = number(form, caption)
        assertTrue(control.isEnabled)
        control.setText(raw)
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Temperature", "0.100000000000000001")
        enter(form, "Top p", "1")
        enter(form, "Maximum output tokens", "9007199254740993")
        choose(choice(form, "Precision"), "tf32")
        choose(choice(form, "Serving mode"), "api")
        enter(form, "Credential reference", "secret://fixture/Case:Reference_Exact")
        enter(form, "Weights URI", "custom+fixture://Example.invalid/path@literal?q=@query#@fragment")
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

    @Test fun sevenValuesSaveExactlyAndModeSwitchDoesNotEraseAnotherField() {
        val before = seedModel()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        assertEquals(7, settings().length())
        assertEquals("0.100000000000000001", PreviewModelSettings.initial(selection()).temperature)
        assertEquals("9007199254740993", PreviewModelSettings.initial(selection()).maxOutputTokens)
        assertEquals("tf32", settings().getString("precision"))
        assertEquals(original.id, selection().id)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        val initial = PreviewSelectionForms.settingsText(selection())
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(initial, PreviewSelectionForms.settingsText(selection()))
        val reopened = configure()
        textCaptions.forEach { assertTrue(supply(reopened, it).isChecked) }
        val otherText = textCaptions.associateWith { number(reopened, it).text.toString() }
        choose(choice(reopened, "Serving mode"), "weights")
        assertEquals(otherText, textCaptions.associateWith { number(reopened, it).text.toString() })
        val switched = commit(reopened, saved)
        assertEquals("weights", settings().getString("serving_mode"))
        assertEquals(7, settings().length())
        assertEquals(saved.revision + 1, switched.revision)
    }

    @Test fun actualPauseRetiresTypedModelControlsAndRequiresFreshReview() {
        val before = seedModel()
        val form = configure()
        fillAll(form)
        val entered = textCaptions.associateWith { number(form, it).text.toString() }
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredPrecision = choice(form, "Precision")
        retiredPrecision.performClick()
        val picker = dialog()

        controller.pause()
        assertFalse(form.isShowing)
        assertFalse(picker.isShowing)
        retiredCommit.performClick()
        shadowOf(picker).clickOnItem(1)
        retiredPrecision.performClick()
        supply(form, "Temperature").performClick()
        finishWork()
        assertSame(picker, dialog())
        assertEquals(before.components, loaded().components)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedModelPending).form
        assertFalse(pending.wasSaving)
        assertEquals(before.baselineIdentity, pending.baseline)
        assertEquals("0.100000000000000001", pending.input.temperature)
        assertEquals("9007199254740993", pending.input.maxOutputTokens)
        assertEquals("tf32", pending.input.precision)
        assertEquals("api", pending.input.servingMode)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertSame(picker, dialog())
        assertTrue(ComponentFormRecovery.read(capture()) is ComponentFormRecovery.TypedModelPending)

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
        assertEquals("api", choice(reviewed, "Serving mode").text.toString())
        retiredCommit.performClick(); finishWork()
        assertSame(reviewed, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val saved = commit(reviewed, before)
        retiredCommit.performClick()
        reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(7, settings().length())
        assertEquals("0.100000000000000001", PreviewModelSettings.initial(selection()).temperature)
        assertEquals("9007199254740993", PreviewModelSettings.initial(selection()).maxOutputTokens)
    }

    @Test fun suppliedEmptyFieldsKeepIndependentErrorsUntilCorrectedOrExplicitlyRemoved() {
        val before = seedModel()
        val form = configure()
        textCaptions.forEach { enter(form, it, "") }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach {
            assertEquals(activity.getString(R.string.model_value_required), number(form, it).error.toString())
            assertTrue(supply(form, it).isChecked)
            assertEquals("", number(form, it).text.toString())
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Temperature", "0")
        assertNull(number(form, "Temperature").error)
        assertNotNull(number(form, "Top p").error)
        enter(form, "Top p", "1")
        enter(form, "Maximum output tokens", "1")
        enter(form, "Credential reference", "secret://fixture/ref")
        enter(form, "Weights URI", "urn:fixture:model")
        val saved = commit(form, before)
        val clear = configure()
        val beforeText = textCaptions.drop(1).associateWith { number(clear, it).text.toString() }
        supply(clear, "Temperature").performClick()
        assertFalse(number(clear, "Temperature").isEnabled)
        assertEquals("", number(clear, "Temperature").text.toString())
        assertEquals(beforeText, textCaptions.drop(1).associateWith { number(clear, it).text.toString() })
        commit(clear, saved)
        assertFalse(settings().has("temperature"))
        assertEquals(4, settings().length())
    }

    @Test fun absentTypedCommitClosesWithoutCreatingAnotherRevisionOrDefaults() {
        val before = seedModel()
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) })
    }

    @Test fun allPresenceAndInvalidOrdinaryTextRecoverOnlyAfterExplicitReview() {
        val before = seedModel()
        val form = configure()
        enter(form, "Temperature", "1,5")
        enter(form, "Top p", "")
        enter(form, "Maximum output tokens", "unfinished")
        choose(choice(form, "Precision"), "tf32")
        choose(choice(form, "Serving mode"), "weights")
        enter(form, "Credential reference", "secret://fixture/ref\n")
        enter(form, "Weights URI", "https://bad authority")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val checkpoint = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedModelPending).form
            assertEquals("1,5", checkpoint.input.temperature)
            assertEquals("", checkpoint.input.topP)
            assertEquals("unfinished", checkpoint.input.maxOutputTokens)
            assertEquals("secret://fixture/ref\n", checkpoint.input.credentialRef)
            assertEquals(selection().catalogItemId, checkpoint.catalogItemId)
            assertFalse(checkpoint.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        textCaptions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals("1,5", number(restored, "Temperature").text.toString())
        assertEquals("", number(restored, "Top p").text.toString())
        assertEquals("unfinished", number(restored, "Maximum output tokens").text.toString())
        assertEquals("secret://fixture/ref\n", number(restored, "Credential reference").text.toString())
        assertEquals("https://bad authority", number(restored, "Weights URI").text.toString())
        assertEquals("tf32", choice(restored, "Precision").text.toString())
        assertEquals("weights", choice(restored, "Serving mode").text.toString())
        retired.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, "Temperature", "0.5")
        enter(restored, "Top p", "1")
        enter(restored, "Maximum output tokens", "1e3")
        enter(restored, "Weights URI", "urn:fixture:model")
        commit(restored, before)
        assertEquals("secret://fixture/ref\n", settings().getString("credential_ref"))
    }

    @Test fun invalidNumericTextCannotHideWeightsUserinfoFromNewRecoveryCapture() {
        val before = seedModel()
        val form = configure()
        val uri = "custom+fixture://synthetic:private@host_name/resource"
        enter(form, "Temperature", "unfinished")
        enter(form, "Weights URI", uri)
        assertFixedMarker(capture(), userinfo = true)
        assertTrue(form.isShowing)
        assertTrue(number(form, "Weights URI").isEnabled)
        assertEquals(uri, number(form, "Weights URI").text.toString())
        assertEquals("unfinished", number(form, "Temperature").text.toString())
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.model_number_invalid), number(form, "Temperature").error.toString())
        assertEquals(activity.getString(R.string.model_uri_invalid), number(form, "Weights URI").error.toString())
        assertFalse(number(form, "Weights URI").error.toString().contains(uri))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        recreate { assertFixedMarker(it, userinfo = true) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_userinfo_excluded)
        })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun invalidCredentialCaptureIsUnavailableWithoutClearingLiveCorrectionText() {
        val before = seedModel()
        val form = configure()
        val password = "synthetic-pasted-password"
        enter(form, "Credential reference", password)
        enter(form, "Maximum output tokens", "unfinished")
        assertFixedMarker(capture(), userinfo = false)
        assertEquals(password, number(form, "Credential reference").text.toString())
        assertTrue(number(form, "Credential reference").isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.model_reference_invalid), number(form, "Credential reference").error.toString())
        assertFalse(number(form, "Credential reference").error.toString().contains(password))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Credential reference", "secret://fixture/ref\n")
        enter(form, "Maximum output tokens", "1.000")
        val captured = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedModelPending).form
        assertEquals("secret://fixture/ref\n", captured.input.credentialRef)
        commit(form, before)
        assertEquals("secret://fixture/ref\n", settings().getString("credential_ref"))
        assertEquals("1.000", PreviewModelSettings.initial(selection()).maxOutputTokens)
    }

    @Test fun changedSavedHeadRetainsSevenInputsReadOnlyUntilExplicitDiscardAndReopen() {
        val before = seedModel()
        val form = configure()
        fillAll(form)
        enter(form, "Temperature", "unfinished")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", "1", "9007199254740993", "tf32", "api",
            "secret://fixture/Case:Reference_Exact", "custom+fixture://Example.invalid/path@literal?q=@query#@fragment")
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

    @Test fun wrongDraftSelectionAndCatalogIdentityNeverReceiveFreshModelSaveAuthority() {
        val before = seedModel()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Temperature", "pending value")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Temperature", "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "context_policy") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightTypedModelFreezesAllControlsAndNeverReplaysAfterAcknowledgment() {
        val before = seedModel()
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
            for (caption in listOf("Precision", "Serving mode")) assertFalse(choice(form, caption).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            val previousLatest = dialog()
            choice(form, "Precision").performClick()
            supply(form, "Temperature").performClick()
            assertSame(previousLatest, dialog())
            assertTrue(form.isShowing)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedModelPending).form
                assertTrue(pending.wasSaving)
                assertEquals("tf32", pending.input.precision)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "0.100000000000000001", "tf32", "api", "secret://fixture/Case:Reference_Exact")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun retiredPrecisionPickerAndSupplyControlCannotReviveAClosedForm() {
        val before = seedModel()
        val form = configure()
        val precision = choice(form, "Precision")
        choose(precision, "tf32")
        precision.performClick()
        val picker = dialog()
        assertEquals("tf32", picker.listView.adapter.getItem(picker.listView.checkedItemPosition))
        val oldSupply = supply(form, "Temperature")
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
        val before = seedModel()
        val form = configure()
        enter(form, "Temperature", "1")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(number(form, it).isEnabled)
        }
        assertFalse(choice(form, "Precision").isEnabled)
        assertFalse(choice(form, "Serving mode").isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "1", "Not supplied")
    }

    @Test fun oversizedTypedModelRecoveryHasNoPrefixAndNoAutomaticViewStateFallback() {
        val before = seedModel()
        val form = configure()
        textCaptions.forEach {
            assertFalse(number(form, it).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, number(form, it).importantForAutofill)
            assertTrue(number(form, it).imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertFalse(supply(form, it).isSaveEnabled)
        }
        assertFalse(choice(form, "Precision").isSaveEnabled)
        assertFalse(choice(form, "Serving mode").isSaveEnabled)
        val raw = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, "Temperature", raw)
        assertEquals(raw, number(form, "Temperature").text.toString())
        recreate { assertFixedMarker(it, userinfo = false) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }
}
