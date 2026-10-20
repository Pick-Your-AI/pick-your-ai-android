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
class ComputeSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val minimum = """{"instances":1}"""

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
        fail("Timed out waiting for compute editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "compute_profile" }
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
        val actions = select(dialog()) { it.startsWith("compute_profile ·") }
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

    private fun seedCompute(): PreviewStoredDraft {
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

    private val captions = listOf("Instances", "Reserved hours per month", "Region")
    private val optionalCaptions = captions.drop(1)
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        if (caption in optionalCaptions && !supply(form, caption).isChecked) supply(form, caption).performClick()
        assertTrue(number(form, caption).isEnabled)
        number(form, caption).setText(raw)
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Instances", "9007199254740993")
        enter(form, "Reserved hours per month", "0.100000000000000001")
        enter(form, "Region", "  fixture-region\n")
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only compute value", it in shown) }
    }

    @Test fun requiredCountAndTwoOptionalFieldsHaveLabeledPrivateNativeControls() {
        val before = seedCompute()
        val form = configure()
        assertEquals("1", number(form, "Instances").text.toString())
        assertTrue(number(form, "Instances").isEnabled)
        assertEquals(2, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        for (caption in captions) {
            val control = number(form, caption)
            assertFalse(control.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, control.importantForAutofill)
            assertTrue(control.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        for (caption in optionalCaptions) {
            assertFalse(number(form, caption).isEnabled)
            assertEquals("", number(form, caption).text.toString())
            assertFalse(supply(form, caption).isChecked)
            assertFalse(supply(form, caption).isSaveEnabled)
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun computeAddRemainsExplicitRawInputWithoutSupplyingTheRequiredCount() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun exactThreeFieldSaveAndReopenPreserveSelectionAndUnrelatedDraftState() {
        val before = seedCompute()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        val values = PreviewComputeSettings.initial(selection())
        assertEquals("9007199254740993", values.instances)
        assertEquals("0.100000000000000001", values.reservedHoursPerMonth)
        assertEquals("  fixture-region\n", values.region)
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
        assertEquals(values.instances, number(reopened, "Instances").text.toString())
        assertEquals(values.reservedHoursPerMonth, number(reopened, "Reserved hours per month").text.toString())
        assertEquals(values.region, number(reopened, "Region").text.toString())
        optionalCaptions.forEach { assertTrue(supply(reopened, it).isChecked) }
        dismiss(reopened)
    }

    @Test fun zeroHoursAndSuppliedEmptyRegionAreSavedUntilExplicitlyRemoved() {
        val before = seedCompute()
        val form = configure()
        enter(form, "Reserved hours per month", "0")
        enter(form, "Region", "")
        val saved = commit(form, before)
        assertEquals(3, settings().length())
        assertEquals("0", PreviewComputeSettings.initial(selection()).reservedHoursPerMonth)
        assertEquals("", settings().getString("region"))
        val clear = configure()
        optionalCaptions.forEach { assertTrue(supply(clear, it).isChecked); supply(clear, it).performClick() }
        assertEquals("1", number(clear, "Instances").text.toString())
        optionalCaptions.forEach {
            assertFalse(number(clear, it).isEnabled)
            assertEquals("", number(clear, it).text.toString())
        }
        commit(clear, saved)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun invalidNumbersKeepIndependentValueFreeErrorsAndTheAcknowledgedDraft() {
        val before = seedCompute()
        val form = configure()
        enter(form, "Instances", "")
        enter(form, "Reserved hours per month", "")
        enter(form, "Region", "")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        assertEquals(activity.getString(R.string.compute_instances_required), number(form, "Instances").error.toString())
        assertEquals(activity.getString(R.string.compute_number_required), number(form, "Reserved hours per month").error.toString())
        assertNull(number(form, "Region").error)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Instances", "1,5")
        assertNull(number(form, "Instances").error)
        assertNotNull(number(form, "Reserved hours per month").error)
        enter(form, "Reserved hours per month", "-0.000000000000000001")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.compute_integer_invalid), number(form, "Instances").error.toString())
        assertEquals(activity.getString(R.string.compute_reserved_hours_rule), number(form, "Reserved hours per month").error.toString())
        assertEquals("1,5", number(form, "Instances").text.toString())
        assertFalse(number(form, "Instances").error.toString().contains("1,5"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Instances", "1e3")
        supply(form, "Reserved hours per month").performClick()
        commit(form, before)
        assertEquals("1E+3", PreviewComputeSettings.initial(selection()).instances)
        assertFalse(settings().has("reserved_hours_per_month"))
        assertEquals("", settings().getString("region"))
    }

    @Test fun cancelAndUnchangedCommitDoNotCreateAnotherRevision() {
        val before = seedCompute()
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

    @Test fun invalidInputAndPresenceRecoverOnlyAfterReviewAndOldControlsStayRetired() {
        val before = seedCompute()
        val form = configure()
        enter(form, "Instances", "unfinished")
        enter(form, "Reserved hours per month", "")
        enter(form, "Region", "")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, "Region")
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedComputePending).form
            assertEquals(PreviewComputeInput("unfinished", "", ""), pending.input)
            assertEquals(selection().catalogItemId, pending.catalogItemId)
            assertEquals(selection().catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, "Instances").text.toString())
        optionalCaptions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals("", number(restored, "Region").text.toString())
        retired.performClick(); retiredSupply.performClick(); finishWork()
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, "Instances", "2")
        enter(restored, "Reserved hours per month", "0")
        commit(restored, before)
        assertEquals("", settings().getString("region"))
    }

    @Test fun changedSavedHeadKeepsPendingValuesReadOnlyUntilDiscardAndReopen() {
        val before = seedCompute()
        val form = configure()
        enter(form, "Instances", "unfinished")
        enter(form, "Region", "")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", "Not supplied", activity.getString(R.string.compute_region_supplied))
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
        val before = seedCompute()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Instances", "pending value")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Instances", "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightSaveFreezesAllControlsAndRecoveryNeverReplaysTheCommit() {
        val before = seedCompute()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            captions.forEach { assertFalse(number(form, it).isEnabled) }
            optionalCaptions.forEach { assertFalse(supply(form, it).isEnabled) }
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, "Region").performClick()
            assertTrue(supply(form, "Region").isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedComputePending).form
                assertTrue(pending.wasSaving)
                assertEquals("  fixture-region\n", pending.input.region)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "9007199254740993", "0.100000000000000001", "  fixture-region\n")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableControls() {
        val before = seedCompute()
        val form = configure()
        enter(form, "Instances", "2")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        captions.forEach { assertFalse(number(form, it).isEnabled) }
        optionalCaptions.forEach { assertFalse(supply(form, it).isEnabled) }
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "2", "Not supplied")
    }

    @Test fun oversizedInputIsKeptLiveButCheckpointRetainsNoPrefixOrViewState() {
        val before = seedCompute()
        val form = configure()
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, "Region", raw)
        assertEquals(raw, number(form, "Region").text.toString())
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

    @Test fun legacyRawComputeCheckpointsStillUseTheirOwnCorrectionReader() {
        val before = seedCompute()
        val target = selection()
        val raw = """{"instances":"""
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
