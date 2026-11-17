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
import ai.pickyour.core.drafts.SelectionCommand
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
class HostingPlatformSettingsActivityTest {
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
        fail("Timed out waiting for hosting-platform editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "hosting_platform" }
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
        val actions = select(dialog()) { it.startsWith("hosting_platform ·") }
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

    private fun seedHostingPlatform(): PreviewStoredDraft {
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

    private val captions = listOf("Region", "Account reference")
    private val literalRegion = " \tSynthetic Region\n"
    private val reference = "secret://fixture/Case:Reference_Exact\n"
    private val network = "private"
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        val supplied = supply(form, caption)
        if (!supplied.isChecked) supplied.performClick()
        val control = textControl(form, caption)
        assertTrue(control.isEnabled)
        control.setText(raw)
    }
    private fun choiceControl(form: AlertDialog): Button {
        val control = captionControl(form, "Network isolation")
        assertTrue(control is Button)
        return control as Button
    }
    private fun chooseNetwork(form: AlertDialog, value: String?) {
        choiceControl(form).performClick()
        val picker = dialog()
        assertTrue(picker.isShowing)
        assertNotSame(form, picker)
        val expected = value ?: "Not supplied"
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString() == expected
        }
        shadowOf(picker).clickOnItem(index)
        assertFalse(picker.isShowing)
        assertEquals(expected, choiceControl(form).text.toString())
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Region", literalRegion)
        enter(form, "Account reference", reference)
        chooseNetwork(form, network)
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only hosting value", it in shown) }
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)
    private fun assertFixedMarker(state: Bundle) {
        val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
    }

    @Test fun threeOptionalFieldsHaveLabelledPrivateControlsAndNoImplicitSelection() {
        val before = seedHostingPlatform()
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
        val choice = choiceControl(form)
        assertEquals("Not supplied", choice.text.toString())
        assertTrue(choice.isEnabled)
        assertFalse(choice.isSaveEnabled)
        assertEquals(2, views(form.window!!.decorView).filterIsInstance<EditText>().size)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun hostingAddRetainsTheExistingRawSettingsAndRolePath() {
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
        val before = seedHostingPlatform()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        assertEquals(3, settings().length())
        assertEquals(literalRegion, settings().getString("region"))
        assertEquals(reference, settings().getString("account_ref"))
        assertEquals(network, settings().getString("network_isolation"))
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
        assertEquals(reference, textControl(reopened, "Account reference").text.toString())
        assertEquals(network, choiceControl(reopened).text.toString())
        commit(reopened, saved)
        assertEquals("other literal region", settings().getString("region"))
        assertEquals(reference, settings().getString("account_ref"))
        assertEquals(network, settings().getString("network_isolation"))
    }

    @Test fun suppliedEmptyWhitespaceAndUnknownRegionRemainLiteralAndDistinctFromAbsence() {
        var before = seedHostingPlatform()
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

    @Test fun invalidAccountHasAValueFreeErrorWithoutReplacingTheOtherSavedFields() {
        var before = seedHostingPlatform()
        val initial = configure()
        fillAll(initial)
        before = commit(initial, before)
        val exact = PreviewSelectionForms.settingsText(selection())
        val form = configure()
        val pasted = "synthetic-pasted-password"
        enter(form, "Region", "")
        enter(form, "Account reference", pasted)
        chooseNetwork(form, "air_gapped")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertNull(textControl(form, "Region").error)
        assertEquals(activity.getString(R.string.hosting_platform_reference_invalid),
            textControl(form, "Account reference").error.toString())
        assertFalse(textControl(form, "Account reference").error.toString().contains(pasted))
        assertEquals("air_gapped", choiceControl(form).text.toString())
        assertEquals(exact, PreviewSelectionForms.settingsText(selection()))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        supply(form, "Account reference").performClick()
        assertNull(textControl(form, "Account reference").error)
        assertFalse(textControl(form, "Account reference").isEnabled)
        assertEquals("", textControl(form, "Account reference").text.toString())
        commit(form, before)
        assertEquals("", settings().getString("region"))
        assertFalse(settings().has("account_ref"))
        assertEquals("air_gapped", settings().getString("network_isolation"))
    }

    @Test fun cancelAfterEditingAllFieldsPreservesTheAcknowledgedHostingAndRevision() {
        val before = seedHostingPlatform()
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
        val before = seedHostingPlatform()
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) })
    }

    @Test fun suppliedEmptyRegionReferenceAndChoiceRecoverOnlyAfterExplicitReview() {
        val before = seedHostingPlatform()
        val original = selection()
        val form = configure()
        enter(form, "Region", "")
        enter(form, "Account reference", reference)
        chooseNetwork(form, "air_gapped")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val checkpoint = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedHostingPlatformPending).form
            assertEquals("", checkpoint.input.region)
            assertEquals(reference, checkpoint.input.accountRef)
            assertEquals("air_gapped", checkpoint.input.networkIsolation)
            assertEquals(original.catalogItemId, checkpoint.catalogItemId)
            assertFalse(checkpoint.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        captions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals("", textControl(restored, "Region").text.toString())
        assertEquals(reference, textControl(restored, "Account reference").text.toString())
        assertEquals("air_gapped", choiceControl(restored).text.toString())
        retired.performClick()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        commit(restored, before)
        assertEquals("", settings().getString("region"))
        assertEquals(reference, settings().getString("account_ref"))
        assertEquals("air_gapped", settings().getString("network_isolation"))
    }

    @Test fun pastedAccountIsExcludedWhileTheLiveFieldCanBeCorrected() {
        val before = seedHostingPlatform()
        val form = configure()
        val pasted = "synthetic-private-password"
        enter(form, "Account reference", pasted)
        assertFixedMarker(capture())
        assertEquals(pasted, textControl(form, "Account reference").text.toString())
        assertTrue(textControl(form, "Account reference").isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.hosting_platform_reference_invalid), textControl(form, "Account reference").error.toString())
        assertFalse(textControl(form, "Account reference").error.toString().contains(pasted))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Account reference", reference)
        val captured = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedHostingPlatformPending).form
        assertEquals(reference, captured.input.accountRef)
        commit(form, before)
        assertEquals(reference, settings().getString("account_ref"))
    }

    @Test fun changedSavedHeadRetainsAllThreeInputsReadOnlyUntilExplicitDiscard() {
        val before = seedHostingPlatform()
        val form = configure()
        fillAll(form)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), literalRegion, reference, network, "Supplied value")
        recreate()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, network)
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
        val before = seedHostingPlatform()
        val form = configure()
        enter(form, "Region", "")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("catalogItemVersion", "other_version") }
        readOnly(reviewPending(), "Supplied empty string", "Not supplied")
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun readOnlyLiteralPlaceholderWordsRemainDistinctFromEmptyAndAbsentState() {
        val before = seedHostingPlatform()
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

    @Test fun wrongDraftSelectionOrCatalogIdentityCannotAcquireFreshHostingSaveAuthority() {
        val before = seedHostingPlatform()
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
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

    @Test fun actualPauseRetiresHostingControlsAndRequiresFreshReview() {
        val before = seedHostingPlatform()
        val original = selection()
        val form = configure()
        fillAll(form)
        val lastDialog = dialog() // The completed native choice picker is the latest owned dialog.
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, "Region")
        val retiredChoice = choiceControl(form)
        val retainedManage = button(R.string.component_manage)
        val retainedReview = button(R.string.component_recovery_review)
        val retainedDiscard = button(R.string.component_recovery_discard)

        controller.pause()
        assertFalse(form.isShowing)
        captions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(textControl(form, it).isEnabled)
        }
        assertFalse(retiredChoice.isEnabled)
        assertFalse(retainedManage.isEnabled)
        assertFalse(retainedReview.isEnabled)
        assertFalse(retainedDiscard.isEnabled)
        retiredCommit.performClick(); retiredSupply.performClick(); retiredChoice.performClick()
        retainedManage.performClick(); retainedReview.performClick(); retainedDiscard.performClick()
        finishWork()
        assertSame(lastDialog, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedHostingPlatformPending).form
        assertFalse(pending.wasSaving)
        assertEquals(currentId().value, pending.draftId)
        assertEquals(before.baselineIdentity, pending.baseline)
        assertEquals(original.id, pending.selectionId)
        assertEquals(original.catalogItemId, pending.catalogItemId)
        assertEquals(original.catalogItemVersion, pending.catalogItemVersion)
        assertEquals(literalRegion, pending.input.region)
        assertEquals(reference, pending.input.accountRef)
        assertEquals(network, pending.input.networkIsolation)

        controller.resume().visible()
        finishWork()
        assertSame(lastDialog, dialog())
        retiredCommit.performClick(); retiredSupply.performClick(); retiredChoice.performClick()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val reviewed = reviewPending()
        assertTrue(reviewed.isShowing)
        assertNotSame(form, reviewed)
        captions.forEach { assertTrue(supply(reviewed, it).isChecked) }
        assertEquals(literalRegion, textControl(reviewed, "Region").text.toString())
        assertEquals(reference, textControl(reviewed, "Account reference").text.toString())
        assertEquals(network, choiceControl(reviewed).text.toString())
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
        assertEquals(reference, settings().getString("account_ref"))
        assertEquals(network, settings().getString("network_isolation"))
    }

    @Test fun inFlightHostingSaveFreezesEveryControlAndNeverReplaysAfterAcknowledgment() {
        val before = seedHostingPlatform()
        val form = configure()
        fillAll(form)
        val lastDialog = dialog() // The completed native choice picker is the latest owned dialog.
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
            assertFalse(choiceControl(form).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            choiceControl(form).performClick()
            assertSame(lastDialog, dialog())
            supply(form, "Region").performClick()
            assertTrue(supply(form, "Region").isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedHostingPlatformPending).form
                assertTrue(pending.wasSaving)
                assertEquals(literalRegion, pending.input.region)
                assertEquals(reference, pending.input.accountRef)
                assertEquals(network, pending.input.networkIsolation)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, network)
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun retiredSupplyAndCommitControlsCannotReviveAClosedHostingForm() {
        val before = seedHostingPlatform()
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

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableHostingControls() {
        val before = seedHostingPlatform()
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
        assertFalse(choiceControl(form).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), literalRegion, "Not supplied")
    }

    @Test fun oversizedHostingRecoveryHasNoPrefixOrAutomaticViewStateFallback() {
        val before = seedHostingPlatform()
        val form = configure()
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, "Region", raw)
        assertEquals(raw, textControl(form, "Region").text.toString())
        recreate { assertFixedMarker(it) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawHostingRecoveryKeepsTheExistingRawEditorPath() {
        val before = seedHostingPlatform()
        val target = selection()
        val raw = """{"account_ref":"""
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
    @Test fun allNetworkChoicesCanBeSelectedRemovedAndCancelledWithoutChangingOtherValues() {
        var before = seedHostingPlatform()
        val form = configure()
        enter(form, "Region", literalRegion)
        enter(form, "Account reference", reference)
        before = commit(form, before)
        for (choice in listOf("public", "private", "air_gapped", null)) {
            val current = configure()
            chooseNetwork(current, choice)
            assertEquals(literalRegion, textControl(current, "Region").text.toString())
            assertEquals(reference, textControl(current, "Account reference").text.toString())
            before = commit(current, before)
            assertEquals(choice != null, settings().has("network_isolation"))
            if (choice != null) assertEquals(choice, settings().getString("network_isolation"))
            assertEquals(literalRegion, settings().getString("region"))
            assertEquals(reference, settings().getString("account_ref"))
        }
        val canceled = configure()
        chooseNetwork(canceled, "public")
        dismiss(canceled)
        assertFalse(settings().has("network_isolation"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun networkPickerShowsExactChoicesAndRetiredCallbacksCannotReviveClosedInput() {
        var before = seedHostingPlatform()
        val initial = configure()
        chooseNetwork(initial, "private")
        before = commit(initial, before)
        val form = configure()
        val control = choiceControl(form)
        control.performClick()
        val canceledPicker = dialog()
        val labels = (0 until canceledPicker.listView.adapter.count).map {
            canceledPicker.listView.adapter.getItem(it).toString()
        }
        assertEquals(listOf("Not supplied", "public", "private", "air_gapped"), labels)
        assertEquals(2, canceledPicker.listView.checkedItemPosition)
        canceledPicker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("private", control.text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        control.performClick()
        val retiredPicker = dialog()
        val retiredSupply = supply(form, "Region")
        dismiss(form)
        assertFalse(retiredPicker.isShowing)
        assertFalse(control.isEnabled)
        assertFalse(retiredSupply.isEnabled)
        shadowOf(retiredPicker).clickOnItem(1)
        control.performClick()
        retiredSupply.performClick()
        finishWork()
        assertFalse(retiredPicker.isShowing)
        assertEquals("private", control.text.toString())
        assertFalse(retiredSupply.isChecked)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("private", settings().getString("network_isolation"))
    }

    @Test fun removedSelectionRetainsOnlyReadOnlyRecoveryAndCannotBeRecreatedByOldControls() {
        val before = seedHostingPlatform()
        val target = selection()
        val form = configure()
        fillAll(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val removed = (adapter().applySelection(before, SelectionCommand.Remove(target.id)) as PreviewSelectionSave.Saved).record
        assertTrue(removed.components.selections.isEmpty())
        recreate()
        readOnly(reviewPending(), literalRegion, reference, network)
        retiredCommit.performClick()
        finishWork()
        assertEquals(removed.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        recreate()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, network)
        dismiss(retained)
        button(R.string.component_recovery_discard).performClick()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(removed.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
    }

}
