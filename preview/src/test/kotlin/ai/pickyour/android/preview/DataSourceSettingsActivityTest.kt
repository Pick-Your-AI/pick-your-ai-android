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
class DataSourceSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val minimum = """{}"""

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
        fail("Timed out waiting for data-source editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "data_source" }
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
        val actions = select(dialog()) { it.startsWith("data_source ·") }
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

    private fun seedDataSource(): PreviewStoredDraft {
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

    @Test fun optionalDataSourceFieldsHaveLabeledPrivateNativeControls() {
        val before = seedDataSource()
        val form = configure()
        for (caption in listOf("Connector secret reference", "Refresh interval (minutes)")) {
            val control = number(form, caption)
            assertFalse(control.isEnabled)
            assertEquals("", control.text.toString())
            assertFalse(control.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, control.importantForAutofill)
            assertTrue(control.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        val enabled = captionControl(form, "Data-source setting: enabled")
        assertTrue(enabled is Button)
        assertEquals("Not supplied", (enabled as Button).text.toString())
        assertEquals(2, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    private val textCaptions = listOf("Connector secret reference", "Refresh interval (minutes)")
    private fun enabled(form: AlertDialog) = captionControl(form, "Data-source setting: enabled") as Button
    private fun supply(form: AlertDialog, caption: String) = views(form.window!!.decorView).filterIsInstance<CheckBox>()
        .single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, value: String) {
        if (!supply(form, caption).isChecked) supply(form, caption).performClick()
        assertTrue(number(form, caption).isEnabled)
        number(form, caption).setText(value)
    }
    private fun choose(control: Button, value: String) {
        control.performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single { picker.listView.adapter.getItem(it).toString() == value }
        shadowOf(picker).clickOnItem(index)
        await { !picker.isShowing }
    }
    private fun fill(form: AlertDialog) {
        enter(form, textCaptions[0], "secret://fixture/key")
        enter(form, textCaptions[1], "9007199254740993")
        choose(enabled(form), "True")
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only data-source value", it in shown) }
    }

    @Test fun genericDataSourceAddRetainsExplicitRawSettingsAndOuterEnabled() {
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
        val before = seedDataSource()
        val original = selection()
        val form = configure()
        fill(form)
        val saved = commit(form, before)
        assertEquals(PreviewDataSourceInput("secret://fixture/key", "9007199254740993", true),
            PreviewDataSourceSettings.initial(selection()))
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertFalse(selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val reopened = configure()
        assertEquals("secret://fixture/key", number(reopened, textCaptions[0]).text.toString())
        assertEquals("9007199254740993", number(reopened, textCaptions[1]).text.toString())
        assertEquals("True", enabled(reopened).text.toString())
        textCaptions.forEach { assertTrue(supply(reopened, it).isChecked) }
        dismiss(reopened)
    }

    @Test fun falseTrueAndNotSuppliedRemainDistinctWithoutChangingTheComponentFlag() {
        var before = seedDataSource()
        for ((caption, expected) in listOf("False" to false, "True" to true, "Not supplied" to null)) {
            val form = configure()
            choose(enabled(form), caption)
            before = commit(form, before)
            assertEquals(expected, PreviewDataSourceSettings.initial(selection()).enabled)
            assertEquals(expected != null, settings().has("enabled"))
            assertFalse(selection().enabled)
            assertEquals(if (expected == null) 0 else 1, settings().length())
        }
    }

    @Test fun independentFieldErrorsPreserveRawInputAndAcknowledgedStorage() {
        val before = seedDataSource()
        val form = configure()
        enter(form, textCaptions[0], "synthetic-private-value")
        enter(form, textCaptions[1], "")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.data_source_reference_rule), number(form, textCaptions[0]).error.toString())
        assertEquals(activity.getString(R.string.data_source_value_required), number(form, textCaptions[1]).error.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, textCaptions[0], "secret://fixture/key")
        assertNull(number(form, textCaptions[0]).error)
        assertNotNull(number(form, textCaptions[1]).error)
        enter(form, textCaptions[1], "1,5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertNull(number(form, textCaptions[0]).error)
        assertEquals(activity.getString(R.string.data_source_integer_invalid), number(form, textCaptions[1]).error.toString())
        assertEquals("1,5", number(form, textCaptions[1]).text.toString())
        assertFalse(number(form, textCaptions[1]).error.toString().contains("1,5"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, textCaptions[1], "0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.data_source_minutes_rule), number(form, textCaptions[1]).error.toString())
        supply(form, textCaptions[1]).performClick()
        choose(enabled(form), "False")
        commit(form, before)
        assertFalse(settings().has("refresh_minutes"))
        assertEquals("secret://fixture/key", settings().getString("connector_secret_ref"))
        assertFalse(settings().getBoolean("enabled"))
    }

    @Test fun cancelAndUntouchedCommitDoNotSaveDefaultsOrAnotherRevision() {
        val before = seedDataSource()
        val form = configure(); fill(form); dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val noop = configure()
        noop.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !noop.isShowing }; finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun rawMinutesAndFalseRecoverOnlyAfterReviewWhileOldControlsRemainRetired() {
        val before = seedDataSource()
        val form = configure()
        enter(form, textCaptions[0], "secret://fixture/key")
        enter(form, textCaptions[1], "unfinished")
        choose(enabled(form), "False")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, textCaptions[1])
        val retiredChoice = enabled(form)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedDataSourcePending).form
            assertEquals(PreviewDataSourceInput("secret://fixture/key", "unfinished", false), pending.input)
            assertEquals(selection().catalogItemId, pending.catalogItemId)
            assertEquals(selection().catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, textCaptions[1]).text.toString())
        assertEquals("False", enabled(restored).text.toString())
        retired.performClick(); retiredSupply.performClick(); retiredChoice.performClick(); finishWork()
        assertSame(restored, dialog())
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, textCaptions[1], "1")
        commit(restored, before)
        assertFalse(settings().getBoolean("enabled"))
    }

    @Test fun wrongDraftSelectionAndCatalogIdentitiesNeverReceiveFreshSaveAuthority() {
        val before = seedDataSource()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure(); enter(form, textCaptions[1], "pending value")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending(); readOnly(retained, "pending value", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained); button(R.string.component_recovery_discard).performClick()
        }
        val form = configure(); enter(form, textCaptions[1], "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun changedSavedHeadKeepsPendingValuesReadOnlyUntilExplicitDiscardAndReopen() {
        val before = seedDataSource()
        val form = configure(); enter(form, textCaptions[1], "unfinished"); choose(enabled(form), "False")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        val retained = reviewPending(); readOnly(retained, "unfinished", "False", "Not supplied")
        dismiss(retained)
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick(); dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        dismiss(reviewPending()); button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun inFlightSaveFreezesAllControlsAndRecoveryNeverReplaysTheCommit() {
        val before = seedDataSource()
        val form = configure(); fill(form)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            textCaptions.forEach { assertFalse(number(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
            assertFalse(enabled(form).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            enabled(form).performClick(); supply(form, textCaptions[0]).performClick()
            assertTrue(supply(form, textCaptions[0]).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedDataSourcePending).form
                assertTrue(pending.wasSaving)
                assertEquals(true, pending.input.enabled)
                release.countDown()
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending(); readOnly(retained, "secret://fixture/key", "9007199254740993", "True")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun actualPauseRetiresOpenBooleanPickerAndRequiresFreshInputReview() {
        val before = seedDataSource()
        val form = configure(); fill(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredChoice = enabled(form)
        retiredChoice.performClick(); val picker = dialog()
        controller.pause()
        assertFalse(form.isShowing); assertFalse(picker.isShowing)
        retiredCommit.performClick(); shadowOf(picker).clickOnItem(1); retiredChoice.performClick(); finishWork()
        assertSame(picker, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val state = Bundle(); controller.saveInstanceState(state)
        val pending = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedDataSourcePending).form
        assertEquals(true, pending.input.enabled)
        assertFalse(pending.wasSaving)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        button(R.string.component_recovery_review).performClick(); button(R.string.component_recovery_discard).performClick()
        controller.resume().visible(); finishWork()
        val reviewed = reviewPending()
        assertEquals("True", enabled(reviewed).text.toString())
        assertEquals("secret://fixture/key", number(reviewed, textCaptions[0]).text.toString())
        val saved = commit(reviewed, before)
        retiredCommit.performClick(); reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun canceledAndConsumedBooleanChoicesCannotReplaceTheCurrentChoice() {
        val before = seedDataSource()
        val form = configure(); val control = enabled(form)
        control.performClick(); val canceled = dialog(); dismiss(canceled)
        shadowOf(canceled).clickOnItem(2); idle()
        assertEquals("Not supplied", control.text.toString())
        control.performClick(); val current = dialog()
        shadowOf(current).clickOnItem(1); await { !current.isShowing }
        shadowOf(current).clickOnItem(2); idle()
        assertEquals("False", control.text.toString())
        commit(form, before)
        assertFalse(settings().getBoolean("enabled"))
        assertFalse(selection().enabled)
    }

    @Test fun invalidConnectorReferenceRemainsLiveButIsExcludedFromRecovery() {
        val before = seedDataSource()
        val form = configure()
        val raw = "synthetic-private-value"
        enter(form, textCaptions[0], raw); enter(form, textCaptions[1], "unfinished")
        assertEquals(raw, number(form, textCaptions[0]).text.toString())
        recreate { state ->
            val value = state.getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        val fresh = configure()
        assertEquals("", number(fresh, textCaptions[0]).text.toString())
        dismiss(fresh)
    }

    @Test fun oversizedInputRetainsNoPrefixOrAutomaticViewState() {
        val before = seedDataSource()
        val form = configure(); val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, textCaptions[1], raw)
        assertEquals(raw, number(form, textCaptions[1]).text.toString())
        recreate { state ->
            val value = state.getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableControls() {
        val before = seedDataSource()
        val form = configure(); fill(form)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach { assertFalse(number(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
        assertFalse(enabled(form).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate(); readOnly(reviewPending(), "secret://fixture/key", "9007199254740993", "True")
    }

    @Test fun legacyRawDataSourceCheckpointsKeepTheirOriginalCorrectionReader() {
        val before = seedDataSource(); val target = selection(); val raw = """{"connector_secret_ref":"""
        for (version in 1..2) {
            recreate { state ->
                val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, currentId().value,
                    before.baselineIdentity, target.id, null, null, null, null, "", raw, false, false)
                state.putBundle(ComponentFormRecovery.KEY, legacy.bundle()!!.apply { putInt("version", version) })
            }
            val restored = reviewPending()
            assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
            assertTrue(restored.isShowing)
            assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(restored); button(R.string.component_recovery_discard).performClick()
        }
    }
}
