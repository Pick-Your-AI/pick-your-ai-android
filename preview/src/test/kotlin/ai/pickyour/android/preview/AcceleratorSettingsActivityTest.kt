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
class AcceleratorSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val minimum = """{"device_count":1}"""

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
        fail("Timed out waiting for accelerator editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "accelerator" }
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
        val actions = select(dialog()) { it.startsWith("accelerator ·") }
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

    private fun seedAccelerator(): PreviewStoredDraft {
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

    private fun supply(form: AlertDialog): CheckBox = views(form.window!!.decorView)
        .filterIsInstance<CheckBox>().single { it.text.toString() == "Supply Tensor parallel groups" }
    private fun groups(form: AlertDialog, raw: String) {
        if (!supply(form).isChecked) supply(form).performClick()
        assertTrue(number(form, "Tensor parallel groups").isEnabled)
        number(form, "Tensor parallel groups").setText(raw)
    }
    private fun fill(form: AlertDialog) {
        number(form, "Device count").setText("2")
        groups(form, "9007199254740993")
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only accelerator value", it in shown) }
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)

    @Test fun deviceCountIsRequiredAndOnlyGroupsHaveAnOptionalSupplyControl() {
        val before = seedAccelerator()
        val form = configure()
        assertEquals("1", number(form, "Device count").text.toString())
        assertTrue(number(form, "Device count").isEnabled)
        assertEquals("", number(form, "Tensor parallel groups").text.toString())
        assertFalse(number(form, "Tensor parallel groups").isEnabled)
        assertFalse(supply(form).isChecked)
        assertEquals(listOf("Supply Tensor parallel groups"), views(form.window!!.decorView)
            .filterIsInstance<CheckBox>().map { it.text.toString() })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun acceleratorAddRemainsRawAndMissingRequiredCountCannotBeSaved() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        rawField(form, R.string.component_role).setText("primary")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        dismiss(form)
    }

    @Test fun unchangedRequiredCountAndAbsentGroupsCloseWithoutAnotherRevision() {
        val before = seedAccelerator()
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }; finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) })
    }

    @Test fun cancelRetiresCommitAndOptionalControlWithoutSavingOrRecoveringInput() {
        val before = seedAccelerator()
        val form = configure()
        fill(form)
        val retiredSupply = supply(form)
        dismiss(form)
        retiredSupply.performClick()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertFalse(number(form, "Device count").isEnabled)
        assertFalse(number(form, "Tensor parallel groups").isEnabled)
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun exactCountsSaveReopenAndRemoveOnlyOptionalGroups() {
        val before = seedAccelerator()
        val original = selection()
        val form = configure()
        fill(form)
        val saved = commit(form, before)
        assertEquals(PreviewAcceleratorInput("2", "9007199254740993"), PreviewAcceleratorSettings.initial(selection()))
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
        assertTrue(supply(reopened).isChecked)
        assertEquals("9007199254740993", number(reopened, "Tensor parallel groups").text.toString())
        supply(reopened).performClick()
        assertFalse(number(reopened, "Tensor parallel groups").isEnabled)
        assertEquals("", number(reopened, "Tensor parallel groups").text.toString())
        assertEquals("2", number(reopened, "Device count").text.toString())
        commit(reopened, saved)
        assertEquals(PreviewAcceleratorInput("2"), PreviewAcceleratorSettings.initial(selection()))
        assertEquals(1, settings().length())
    }

    @Test fun independentErrorsRequireDeviceCorrectionAndAllowExplicitGroupRemoval() {
        val before = seedAccelerator()
        val form = configure()
        number(form, "Device count").setText("")
        groups(form, "")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        for (label in listOf("Device count", "Tensor parallel groups"))
            assertEquals(activity.getString(R.string.accelerator_value_required), number(form, label).error.toString())
        number(form, "Device count").setText("0")
        assertNull(number(form, "Device count").error)
        assertNotNull(number(form, "Tensor parallel groups").error)
        groups(form, "1,5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.accelerator_device_count_rule), number(form, "Device count").error.toString())
        assertEquals(activity.getString(R.string.accelerator_integer_invalid), number(form, "Tensor parallel groups").error.toString())
        supply(form).performClick()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        number(form, "Device count").setText("2.000")
        commit(form, before)
        assertEquals(1, settings().length())
        assertEquals(0, java.math.BigDecimal(PreviewAcceleratorSettings.initial(selection()).deviceCount).compareTo(java.math.BigDecimal("2")))
    }

    @Test fun privateInvalidRequiredTextAndSuppliedEmptyGroupsRecoverOnlyAfterReview() {
        val before = seedAccelerator()
        val form = configure()
        number(form, "Device count").setText("1,5")
        groups(form, "")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedAcceleratorPending).form
            assertEquals(PreviewAcceleratorInput("1,5", ""), pending.input)
            assertEquals(selection().catalogItemId, pending.catalogItemId)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertTrue(supply(restored).isChecked)
        assertEquals("1,5", number(restored, "Device count").text.toString())
        assertEquals("", number(restored, "Tensor parallel groups").text.toString())
        retired.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        number(restored, "Device count").setText("2")
        groups(restored, "1e3")
        commit(restored, before)
    }

    @Test fun wrongOriginalDraftSelectionAndCatalogIdentityNeverReceiveNewSaveAuthority() {
        val before = seedAccelerator()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            number(form, "Device count").setText("pending count")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending count", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
    }

    @Test fun changedSavedHeadKeepsPrivateCountsReadOnlyUntilDiscardAndReopen() {
        val before = seedAccelerator()
        val form = configure()
        fill(form)
        number(form, "Device count").setText("unfinished")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", "9007199254740993")
        recreate()
        dismiss(reviewPending())
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        dismiss(reviewPending())
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableControls() {
        val before = seedAccelerator()
        val form = configure()
        fill(form)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        assertFalse(supply(form).isEnabled)
        for (label in listOf("Device count", "Tensor parallel groups")) assertFalse(number(form, label).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "2", "9007199254740993")
    }

    @Test fun inFlightAcceleratorFreezesControlsAndNeverReplaysAfterAcknowledgment() {
        val before = seedAccelerator()
        val form = configure()
        fill(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            assertFalse(supply(form).isEnabled)
            for (label in listOf("Device count", "Tensor parallel groups")) assertFalse(number(form, label).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form).performClick()
            assertTrue(supply(form).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedAcceleratorPending).form
                assertTrue(pending.wasSaving)
                assertEquals(PreviewAcceleratorInput("2", "9007199254740993"), pending.input)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "2", "9007199254740993")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun actualPauseRetiresControlsAndRequiresExplicitFreshAcceleratorReview() {
        val before = seedAccelerator()
        val form = configure()
        fill(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form)
        controller.pause()
        assertFalse(form.isShowing)
        retiredCommit.performClick(); retiredSupply.performClick(); finishWork()
        assertSame(form, dialog())
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertFalse(number(form, "Device count").isEnabled)
        assertFalse(number(form, "Tensor parallel groups").isEnabled)
        assertEquals(before.components, loaded().components)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val state = capture()
        assertEquals("typed-accelerator/1", state.getBundle(ComponentFormRecovery.KEY)!!.getString("version"))
        val pending = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedAcceleratorPending).form
        assertFalse(pending.wasSaving)
        assertEquals(before.baselineIdentity, pending.baseline)
        assertEquals(PreviewAcceleratorInput("2", "9007199254740993"), pending.input)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertSame(form, dialog())
        assertTrue(ComponentFormRecovery.read(capture()) is ComponentFormRecovery.TypedAcceleratorPending)
        controller.resume().visible(); finishWork()
        assertSame(form, dialog())
        retiredCommit.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val reviewed = reviewPending()
        assertNotSame(form, reviewed)
        assertTrue(supply(reviewed).isChecked)
        assertEquals("2", number(reviewed, "Device count").text.toString())
        assertEquals("9007199254740993", number(reviewed, "Tensor parallel groups").text.toString())
        retiredCommit.performClick(); retiredSupply.performClick(); finishWork()
        assertSame(reviewed, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val saved = commit(reviewed, before)
        retiredCommit.performClick(); reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun oversizedRecoveryHasNoPrefixAndCannotUseAutomaticViewStateFallback() {
        val before = seedAccelerator()
        val form = configure()
        for (caption in listOf("Device count", "Tensor parallel groups")) {
            assertFalse(number(form, caption).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, number(form, caption).importantForAutofill)
            assertTrue(number(form, caption).imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        assertFalse(supply(form).isSaveEnabled)
        val raw = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        number(form, "Device count").setText(raw)
        assertEquals(raw, number(form, "Device count").text.toString())
        recreate { state ->
            val marker = state.getBundle(ComponentFormRecovery.KEY)!!
            assertEquals(setOf("version", "unavailable"), marker.keySet())
            assertTrue(marker.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawV1V2AcceleratorRecoveryRemainsRawWithoutTypedPromotion() {
        val before = seedAccelerator()
        val target = selection()
        for (version in 1..2) {
            val raw = """{"device_count":"""
            recreate { state ->
                val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE,
                    currentId().value, before.baselineIdentity, target.id,
                    null, null, null, null, "", raw, false, false)
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
