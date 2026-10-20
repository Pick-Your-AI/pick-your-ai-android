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
class ModelProviderSettingsActivityTest {
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

    private fun textControl(form: AlertDialog, text: String): EditText {
        val control = captionControl(form, text)
        assertTrue("Expected literal text input for $text", control is EditText)
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
        fail("Timed out waiting for model-provider editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "model_provider" }
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
        val actions = select(dialog()) { it.startsWith("model_provider ·") }
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

    private fun seedModelProvider(): PreviewStoredDraft {
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

    private val captions = listOf("Region", "Credential reference", "API base URL")
    private val literalRegion = " \tSynthetic Region\n"
    private val reference = "secret://fixture/Case:Reference_Exact\n"
    private val apiUri = "custom+fixture://Example.invalid/path@literal?q=@query#@fragment"
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        val supplied = supply(form, caption)
        if (!supplied.isChecked) supplied.performClick()
        val control = textControl(form, caption)
        assertTrue(control.isEnabled)
        control.setText(raw)
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Region", literalRegion)
        enter(form, "Credential reference", reference)
        enter(form, "API base URL", apiUri)
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only provider value", it in shown) }
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)
    private fun assertFixedMarker(state: Bundle, userinfo: Boolean) {
        val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertEquals(if (userinfo) setOf("version", "unavailable", "reason") else setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
        if (userinfo) assertEquals("uri_userinfo", value.getString("reason"))
    }

    @Test fun allThreeOptionalFieldsHaveLabelledPrivateLiteralControlsWithoutDefaults() {
        val before = seedModelProvider()
        val form = configure()
        for (caption in captions) {
            val control = textControl(form, caption)
            assertEquals("", control.text.toString())
            assertEquals("Not supplied", control.hint.toString())
            assertFalse(control.isEnabled)
            assertFalse(supply(form, caption).isChecked)
            assertFalse(control.isSaveEnabled)
            assertFalse(supply(form, caption).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, control.importantForAutofill)
            assertTrue(control.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertTrue(control.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        }
        assertEquals(3, views(form.window!!.decorView).filterIsInstance<EditText>().size)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun providerAddRetainsTheExistingRawSettingsAndRolePath() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun threeValuesRoundTripExactlyAndOneFieldEditPreservesTheOtherTwo() {
        val before = seedModelProvider()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        assertEquals(3, settings().length())
        assertEquals(literalRegion, settings().getString("region"))
        assertEquals(reference, settings().getString("credential_ref"))
        assertEquals(apiUri, settings().getString("api_base_url"))
        assertEquals(original.id, selection().id)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        val exact = PreviewSelectionForms.settingsText(selection())
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(exact, PreviewSelectionForms.settingsText(selection()))
        val reopened = configure()
        captions.forEach { assertTrue(supply(reopened, it).isChecked) }
        assertEquals(literalRegion, textControl(reopened, "Region").text.toString())
        enter(reopened, "Region", "other literal region")
        assertEquals(reference, textControl(reopened, "Credential reference").text.toString())
        assertEquals(apiUri, textControl(reopened, "API base URL").text.toString())
        commit(reopened, saved)
        assertEquals("other literal region", settings().getString("region"))
        assertEquals(reference, settings().getString("credential_ref"))
        assertEquals(apiUri, settings().getString("api_base_url"))
    }

    @Test fun suppliedEmptyWhitespaceAndUnknownRegionRemainLiteralAndDistinctFromAbsence() {
        var before = seedModelProvider()
        for (raw in listOf("", " \t\n", "unknown")) {
            val form = configure()
            enter(form, "Region", raw)
            assertNull(textControl(form, "Region").hint)
            before = commit(form, before)
            assertTrue(settings().has("region"))
            assertEquals(raw, settings().getString("region"))
            assertEquals(1, settings().length())
            recreate()
            val reopened = configure()
            assertTrue(supply(reopened, "Region").isChecked)
            assertNull(textControl(reopened, "Region").hint)
            assertEquals(raw, textControl(reopened, "Region").text.toString())
            dismiss(reopened)
        }
        val clear = configure()
        supply(clear, "Region").performClick()
        assertFalse(textControl(clear, "Region").isEnabled)
        assertEquals("Not supplied", textControl(clear, "Region").hint.toString())
        assertEquals("", textControl(clear, "Region").text.toString())
        commit(clear, before)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun invalidReferenceAndUriHaveIndependentValueFreeErrorsWithoutReplacingSavedValues() {
        var before = seedModelProvider()
        val initial = configure()
        fillAll(initial)
        before = commit(initial, before)
        val exact = PreviewSelectionForms.settingsText(selection())
        val form = configure()
        val pasted = "synthetic-pasted-password"
        val badUri = "https://bad authority"
        enter(form, "Region", "")
        enter(form, "Credential reference", pasted)
        enter(form, "API base URL", badUri)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertNull(textControl(form, "Region").error)
        assertEquals(activity.getString(R.string.model_provider_reference_invalid), textControl(form, "Credential reference").error.toString())
        assertEquals(activity.getString(R.string.model_provider_uri_invalid), textControl(form, "API base URL").error.toString())
        assertFalse(textControl(form, "Credential reference").error.toString().contains(pasted))
        assertFalse(textControl(form, "API base URL").error.toString().contains(badUri))
        assertEquals(exact, PreviewSelectionForms.settingsText(selection()))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Credential reference", reference)
        assertNull(textControl(form, "Credential reference").error)
        assertNotNull(textControl(form, "API base URL").error)
        supply(form, "API base URL").performClick()
        assertFalse(textControl(form, "API base URL").isEnabled)
        assertEquals("", textControl(form, "API base URL").text.toString())
        commit(form, before)
        assertEquals("", settings().getString("region"))
        assertEquals(reference, settings().getString("credential_ref"))
        assertFalse(settings().has("api_base_url"))
    }

    @Test fun cancelAfterEditingAllFieldsPreservesTheAcknowledgedProviderAndRevision() {
        val before = seedModelProvider()
        val exact = PreviewSelectionForms.settingsText(selection())
        val form = configure()
        fillAll(form)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals(exact, PreviewSelectionForms.settingsText(selection()))
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun absentCommitClosesAsNoopWithoutCreatingDefaultsOrAnotherRevision() {
        val before = seedModelProvider()
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) })
    }

    @Test fun suppliedEmptyRegionAndSafeInvalidUriRecoverOnlyAfterExplicitReview() {
        val before = seedModelProvider()
        val form = configure()
        enter(form, "Region", "")
        enter(form, "Credential reference", reference)
        enter(form, "API base URL", "https://bad authority")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val checkpoint = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedModelProviderPending).form
            assertEquals("", checkpoint.input.region)
            assertEquals(reference, checkpoint.input.credentialRef)
            assertEquals("https://bad authority", checkpoint.input.apiBaseUrl)
            assertEquals(selection().catalogItemId, checkpoint.catalogItemId)
            assertFalse(checkpoint.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        captions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals("", textControl(restored, "Region").text.toString())
        assertEquals(reference, textControl(restored, "Credential reference").text.toString())
        assertEquals("https://bad authority", textControl(restored, "API base URL").text.toString())
        retired.performClick()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.model_provider_uri_invalid), textControl(restored, "API base URL").error.toString())
        enter(restored, "API base URL", apiUri)
        commit(restored, before)
        assertEquals("", settings().getString("region"))
        assertEquals(reference, settings().getString("credential_ref"))
        assertEquals(apiUri, settings().getString("api_base_url"))
    }

    @Test fun invalidReferenceCannotHideUriUserinfoAndRecoveryRetainsOnlyTheFixedMarker() {
        val before = seedModelProvider()
        val form = configure()
        val uri = "custom+fixture://synthetic:private@host_name/resource"
        enter(form, "Region", literalRegion)
        enter(form, "Credential reference", "synthetic-pasted-password")
        enter(form, "API base URL", uri)
        assertFixedMarker(capture(), userinfo = true)
        assertEquals(uri, textControl(form, "API base URL").text.toString())
        assertTrue(textControl(form, "API base URL").isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.model_provider_uri_invalid), textControl(form, "API base URL").error.toString())
        assertEquals(activity.getString(R.string.model_provider_reference_invalid), textControl(form, "Credential reference").error.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        recreate { assertFixedMarker(it, userinfo = true) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_userinfo_excluded)
        })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun pastedCredentialIsExcludedWhileTheLiveFieldCanBeCorrected() {
        val before = seedModelProvider()
        val form = configure()
        val pasted = "synthetic-private-password"
        enter(form, "Credential reference", pasted)
        assertFixedMarker(capture(), userinfo = false)
        assertEquals(pasted, textControl(form, "Credential reference").text.toString())
        assertTrue(textControl(form, "Credential reference").isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.model_provider_reference_invalid), textControl(form, "Credential reference").error.toString())
        assertFalse(textControl(form, "Credential reference").error.toString().contains(pasted))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Credential reference", reference)
        val captured = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedModelProviderPending).form
        assertEquals(reference, captured.input.credentialRef)
        commit(form, before)
        assertEquals(reference, settings().getString("credential_ref"))
    }

    @Test fun changedSavedHeadRetainsAllThreeInputsReadOnlyUntilExplicitDiscard() {
        val before = seedModelProvider()
        val form = configure()
        fillAll(form)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), literalRegion, reference, apiUri, "Supplied value")
        recreate()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, apiUri)
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

    @Test fun suppliedEmptyRegionIsExplicitInReadOnlyRecoveryAndNeverBecomesAbsence() {
        val before = seedModelProvider()
        val form = configure()
        enter(form, "Region", "")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("catalogItemVersion", "other_version") }
        readOnly(reviewPending(), "Supplied empty string", "Not supplied")
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun readOnlyLiteralPlaceholderWordsRemainDistinctFromEmptyAndAbsentState() {
        val before = seedModelProvider()
        for (raw in listOf("Empty string", "Not supplied", "Supplied empty string")) {
            val form = configure()
            enter(form, "Region", raw)
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("catalogItemVersion", "other_version") }
            val retained = reviewPending()
            readOnly(retained, "Supplied value", raw)
            val shown = views(retained.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertEquals(1, shown.count { it == "Supplied value" })
            assertEquals(if (raw == "Supplied empty string") 1 else 0, shown.count { it == "Supplied empty string" })
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
    }

    @Test fun wrongDraftSelectionOrCatalogIdentityCannotAcquireFreshProviderSaveAuthority() {
        val before = seedModelProvider()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Region", literalRegion)
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, literalRegion, "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Region", literalRegion)
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun actualPauseRetiresProviderControlsAndRequiresFreshReview() {
        val before = seedModelProvider()
        val original = selection()
        val form = configure()
        fillAll(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, "Region")
        val retainedManage = button(R.string.component_manage)
        val retainedReview = button(R.string.component_recovery_review)
        val retainedDiscard = button(R.string.component_recovery_discard)

        controller.pause()
        assertFalse(form.isShowing)
        captions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(textControl(form, it).isEnabled)
        }
        assertFalse(retainedManage.isEnabled)
        assertFalse(retainedReview.isEnabled)
        assertFalse(retainedDiscard.isEnabled)
        retiredCommit.performClick(); retiredSupply.performClick()
        retainedManage.performClick(); retainedReview.performClick(); retainedDiscard.performClick()
        finishWork()
        assertSame(form, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedModelProviderPending).form
        assertFalse(pending.wasSaving)
        assertEquals(currentId().value, pending.draftId)
        assertEquals(before.baselineIdentity, pending.baseline)
        assertEquals(original.id, pending.selectionId)
        assertEquals(original.catalogItemId, pending.catalogItemId)
        assertEquals(original.catalogItemVersion, pending.catalogItemVersion)
        assertEquals(literalRegion, pending.input.region)
        assertEquals(reference, pending.input.credentialRef)
        assertEquals(apiUri, pending.input.apiBaseUrl)

        controller.resume().visible()
        finishWork()
        assertSame(form, dialog())
        retiredCommit.performClick(); retiredSupply.performClick()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val reviewed = reviewPending()
        assertTrue(reviewed.isShowing)
        assertNotSame(form, reviewed)
        captions.forEach { assertTrue(supply(reviewed, it).isChecked) }
        assertEquals(literalRegion, textControl(reviewed, "Region").text.toString())
        assertEquals(reference, textControl(reviewed, "Credential reference").text.toString())
        assertEquals(apiUri, textControl(reviewed, "API base URL").text.toString())
        retiredCommit.performClick(); finishWork()
        assertSame(reviewed, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val saved = commit(reviewed, before)
        retiredCommit.performClick()
        reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.components.links, saved.components.links)
        assertEquals(before.history.size + 1, saved.history.size)
        assertEquals(3, settings().length())
        assertEquals(literalRegion, settings().getString("region"))
        assertEquals(reference, settings().getString("credential_ref"))
        assertEquals(apiUri, settings().getString("api_base_url"))
    }

    @Test fun inFlightProviderSaveFreezesEveryControlAndNeverReplaysAfterAcknowledgment() {
        val before = seedModelProvider()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick()
            commit.performClick()
            captions.forEach {
                assertFalse(supply(form, it).isEnabled)
                assertFalse(textControl(form, it).isEnabled)
            }
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, "Region").performClick()
            assertTrue(supply(form, "Region").isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedModelProviderPending).form
                assertTrue(pending.wasSaving)
                assertEquals(literalRegion, pending.input.region)
                assertEquals(reference, pending.input.credentialRef)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, apiUri)
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun retiredSupplyAndCommitControlsCannotReviveAClosedProviderForm() {
        val before = seedModelProvider()
        val form = configure()
        val oldSupply = supply(form, "Region")
        val oldCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        dismiss(form)
        oldSupply.performClick()
        oldCommit.performClick()
        finishWork()
        assertFalse(oldSupply.isChecked)
        assertFalse(oldSupply.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableProviderControls() {
        val before = seedModelProvider()
        val form = configure()
        enter(form, "Region", literalRegion)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        captions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(textControl(form, it).isEnabled)
        }
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), literalRegion, "Not supplied")
    }

    @Test fun oversizedProviderRecoveryHasNoPrefixOrAutomaticViewStateFallback() {
        val before = seedModelProvider()
        val form = configure()
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, "Region", raw)
        assertEquals(raw, textControl(form, "Region").text.toString())
        recreate { assertFixedMarker(it, userinfo = false) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawProviderRecoveryKeepsTheExistingRawEditorPath() {
        val before = seedModelProvider()
        val target = selection()
        val raw = """{"credential_ref":"""
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
}
