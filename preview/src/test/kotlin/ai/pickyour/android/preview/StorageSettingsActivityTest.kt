package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.util.JsonReader
import android.util.JsonToken
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
import java.io.StringReader
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Activity/worker/storage paths in simulation; never installed-process evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StorageSettingsActivityTest {
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
        fail("Timed out waiting for storage editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "storage" }
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
        val actions = select(dialog()) { it.startsWith("storage ·") }
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

    private fun seedStorage(): PreviewStoredDraft {
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

    private val captions = listOf("Provisioned storage (GB)", "Region", "Encryption key reference")
    private val literalRegion = " \tSynthetic Region\n"
    private val reference = "secret://fixture/Case:Reference_Exact\n"
    private val capacity = "9007199254740993.125"
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        val supplied = supply(form, caption)
        if (!supplied.isChecked) supplied.performClick()
        val control = textControl(form, caption)
        assertTrue(control.isEnabled)
        control.setText(raw)
    }
    private fun setCapacity(form: AlertDialog, raw: String?) {
        if (raw != null) enter(form, "Provisioned storage (GB)", raw)
        else if (supply(form, "Provisioned storage (GB)").isChecked) supply(form, "Provisioned storage (GB)").performClick()
    }
    private fun assertCapacity(raw: String) {
        val saved = selection()
        val exact = JsonReader(StringReader(PreviewSelectionForms.settingsText(saved))).use { reader ->
            reader.beginObject()
            var numeric: String? = null
            while (reader.hasNext()) {
                if (reader.nextName() == "provisioned_gb") {
                    assertEquals(JsonToken.NUMBER, reader.peek())
                    numeric = reader.nextString()
                } else reader.skipValue()
            }
            reader.endObject()
            requireNotNull(numeric)
        }
        assertEquals(0, BigDecimal(raw).compareTo(BigDecimal(exact)))
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Region", literalRegion)
        enter(form, "Encryption key reference", reference)
        setCapacity(form, capacity)
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only storage value", it in shown) }
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)
    private fun assertFixedMarker(state: Bundle) {
        val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
    }

    @Test fun threeOptionalFieldsHaveLabelledPrivateControlsAndNoImplicitSelection() {
        val before = seedStorage()
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

    @Test fun storageAddRetainsTheExistingRawSettingsAndRolePath() {
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
        val before = seedStorage()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        assertEquals(3, settings().length())
        assertEquals(literalRegion, settings().getString("region"))
        assertEquals(reference, settings().getString("encryption_key_ref"))
        assertCapacity(capacity)
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
        assertEquals(reference, textControl(reopened, "Encryption key reference").text.toString())
        assertEquals(capacity, textControl(reopened, "Provisioned storage (GB)").text.toString())
        commit(reopened, saved)
        assertEquals("other literal region", settings().getString("region"))
        assertEquals(reference, settings().getString("encryption_key_ref"))
        assertCapacity(capacity)
    }

    @Test fun suppliedEmptyWhitespaceAndUnknownRegionRemainLiteralAndDistinctFromAbsence() {
        var before = seedStorage()
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

    @Test fun invalidKeyReferenceHasAValueFreeErrorWithoutReplacingTheOtherSavedFields() {
        var before = seedStorage()
        val initial = configure()
        fillAll(initial)
        before = commit(initial, before)
        val exact = PreviewSelectionForms.settingsText(selection())
        val form = configure()
        val pasted = "synthetic-pasted-password"
        enter(form, "Region", "")
        enter(form, "Encryption key reference", pasted)
        setCapacity(form, "0.125")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertNull(textControl(form, "Region").error)
        assertEquals(activity.getString(R.string.storage_reference_invalid),
            textControl(form, "Encryption key reference").error.toString())
        assertFalse(textControl(form, "Encryption key reference").error.toString().contains(pasted))
        assertEquals("0.125", textControl(form, "Provisioned storage (GB)").text.toString())
        assertEquals(exact, PreviewSelectionForms.settingsText(selection()))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        supply(form, "Encryption key reference").performClick()
        assertNull(textControl(form, "Encryption key reference").error)
        assertFalse(textControl(form, "Encryption key reference").isEnabled)
        assertEquals("", textControl(form, "Encryption key reference").text.toString())
        commit(form, before)
        assertEquals("", settings().getString("region"))
        assertFalse(settings().has("encryption_key_ref"))
        assertCapacity("0.125")
    }

    @Test fun cancelAfterEditingAllFieldsPreservesTheAcknowledgedStorageAndRevision() {
        val before = seedStorage()
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
        val before = seedStorage()
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.component_noop) })
    }

    @Test fun suppliedEmptyRegionReferenceAndNumberRecoverOnlyAfterExplicitReview() {
        val before = seedStorage()
        val original = selection()
        val form = configure()
        enter(form, "Region", "")
        enter(form, "Encryption key reference", reference)
        setCapacity(form, "0.125")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val checkpoint = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedStoragePending).form
            assertEquals("", checkpoint.input.region)
            assertEquals(reference, checkpoint.input.encryptionKeyRef)
            assertEquals("0.125", checkpoint.input.provisionedGb)
            assertEquals(original.catalogItemId, checkpoint.catalogItemId)
            assertFalse(checkpoint.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        captions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals("", textControl(restored, "Region").text.toString())
        assertEquals(reference, textControl(restored, "Encryption key reference").text.toString())
        assertEquals("0.125", textControl(restored, "Provisioned storage (GB)").text.toString())
        retired.performClick()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        commit(restored, before)
        assertEquals("", settings().getString("region"))
        assertEquals(reference, settings().getString("encryption_key_ref"))
        assertCapacity("0.125")
    }

    @Test fun pastedKeyIsExcludedWhileTheLiveFieldCanBeCorrected() {
        val before = seedStorage()
        val form = configure()
        val pasted = "synthetic-private-password"
        enter(form, "Encryption key reference", pasted)
        assertFixedMarker(capture())
        assertEquals(pasted, textControl(form, "Encryption key reference").text.toString())
        assertTrue(textControl(form, "Encryption key reference").isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.storage_reference_invalid), textControl(form, "Encryption key reference").error.toString())
        assertFalse(textControl(form, "Encryption key reference").error.toString().contains(pasted))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Encryption key reference", reference)
        val captured = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedStoragePending).form
        assertEquals(reference, captured.input.encryptionKeyRef)
        commit(form, before)
        assertEquals(reference, settings().getString("encryption_key_ref"))
    }

    @Test fun changedSavedHeadRetainsAllThreeInputsReadOnlyUntilExplicitDiscard() {
        val before = seedStorage()
        val form = configure()
        fillAll(form)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), literalRegion, reference, capacity, "Supplied value")
        recreate()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, capacity)
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
        val before = seedStorage()
        val form = configure()
        enter(form, "Region", "")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("catalogItemVersion", "other_version") }
        readOnly(reviewPending(), "Supplied empty value", "Not supplied")
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun readOnlyLiteralPlaceholderWordsRemainDistinctFromEmptyAndAbsentState() {
        val before = seedStorage()
        for (raw in listOf("Empty value", "Not supplied", "Supplied empty value")) {
            val form = configure()
            enter(form, "Region", raw)
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("catalogItemVersion", "other_version") }
            val retained = reviewPending()
            readOnly(retained, "Supplied value", raw)
            val shown = views(retained.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertEquals(1, shown.count { it == "Supplied value" })
            assertEquals(if (raw == "Supplied empty value") 1 else 0, shown.count { it == "Supplied empty value" })
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
    }

    @Test fun wrongDraftSelectionOrCatalogIdentityCannotAcquireFreshStorageSaveAuthority() {
        val before = seedStorage()
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

    @Test fun actualPauseRetiresStorageControlsAndRequiresFreshReview() {
        val before = seedStorage()
        val original = selection()
        val form = configure()
        fillAll(form)
        val lastDialog = dialog()
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, "Region")
        val retiredCapacitySupply = supply(form, "Provisioned storage (GB)")
        val retainedManage = button(R.string.component_manage)
        val retainedReview = button(R.string.component_recovery_review)
        val retainedDiscard = button(R.string.component_recovery_discard)

        controller.pause()
        assertFalse(form.isShowing)
        captions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(textControl(form, it).isEnabled)
        }
        assertFalse(retiredCapacitySupply.isEnabled)
        assertFalse(retainedManage.isEnabled)
        assertFalse(retainedReview.isEnabled)
        assertFalse(retainedDiscard.isEnabled)
        retiredCommit.performClick(); retiredSupply.performClick(); retiredCapacitySupply.performClick()
        retainedManage.performClick(); retainedReview.performClick(); retainedDiscard.performClick()
        finishWork()
        assertSame(lastDialog, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedStoragePending).form
        assertFalse(pending.wasSaving)
        assertEquals(currentId().value, pending.draftId)
        assertEquals(before.baselineIdentity, pending.baseline)
        assertEquals(original.id, pending.selectionId)
        assertEquals(original.catalogItemId, pending.catalogItemId)
        assertEquals(original.catalogItemVersion, pending.catalogItemVersion)
        assertEquals(literalRegion, pending.input.region)
        assertEquals(reference, pending.input.encryptionKeyRef)
        assertEquals(capacity, pending.input.provisionedGb)

        controller.resume().visible()
        finishWork()
        assertSame(lastDialog, dialog())
        retiredCommit.performClick(); retiredSupply.performClick(); retiredCapacitySupply.performClick()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val reviewed = reviewPending()
        assertTrue(reviewed.isShowing)
        assertNotSame(form, reviewed)
        captions.forEach { assertTrue(supply(reviewed, it).isChecked) }
        assertEquals(literalRegion, textControl(reviewed, "Region").text.toString())
        assertEquals(reference, textControl(reviewed, "Encryption key reference").text.toString())
        assertEquals(capacity, textControl(reviewed, "Provisioned storage (GB)").text.toString())
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
        assertEquals(reference, settings().getString("encryption_key_ref"))
        assertCapacity(capacity)
    }

    @Test fun inFlightStorageSaveFreezesEveryControlAndNeverReplaysAfterAcknowledgment() {
        val before = seedStorage()
        val form = configure()
        fillAll(form)
        val lastDialog = dialog()
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
            assertFalse(supply(form, "Provisioned storage (GB)").isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, "Provisioned storage (GB)").performClick()
            assertSame(lastDialog, dialog())
            supply(form, "Region").performClick()
            assertTrue(supply(form, "Region").isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedStoragePending).form
                assertTrue(pending.wasSaving)
                assertEquals(literalRegion, pending.input.region)
                assertEquals(reference, pending.input.encryptionKeyRef)
                assertEquals(capacity, pending.input.provisionedGb)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, capacity)
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun retiredSupplyAndCommitControlsCannotReviveAClosedStorageForm() {
        val before = seedStorage()
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

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableStorageControls() {
        val before = seedStorage()
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
        assertFalse(supply(form, "Provisioned storage (GB)").isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), literalRegion, "Not supplied")
    }

    @Test fun oversizedStorageRecoveryHasNoPrefixOrAutomaticViewStateFallback() {
        val before = seedStorage()
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

    @Test fun legacyRawStorageRecoveryKeepsTheExistingRawEditorPath() {
        val before = seedStorage()
        val target = selection()
        val raw = """{"encryption_key_ref":"""
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
    @Test fun zeroExactDecimalsAndOmissionCanBeSavedRemovedAndCancelledWithoutChangingOtherValues() {
        var before = seedStorage()
        val form = configure()
        enter(form, "Region", literalRegion)
        enter(form, "Encryption key reference", reference)
        before = commit(form, before)
        for (raw in listOf("0", "0.125", "9007199254740993.125", "1e-400", null)) {
            val current = configure()
            setCapacity(current, raw)
            assertEquals(literalRegion, textControl(current, "Region").text.toString())
            assertEquals(reference, textControl(current, "Encryption key reference").text.toString())
            before = commit(current, before)
            assertEquals(raw != null, settings().has("provisioned_gb"))
            if (raw != null) assertCapacity(raw)
            assertEquals(literalRegion, settings().getString("region"))
            assertEquals(reference, settings().getString("encryption_key_ref"))
        }
        val canceled = configure()
        setCapacity(canceled, "0")
        dismiss(canceled)
        assertFalse(settings().has("provisioned_gb"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun invalidNumbersCannotReplaceSavedCapacityAndRetainLocalCorrectionUntilExplicitReview() {
        var before = seedStorage()
        val initial = configure()
        fillAll(initial)
        before = commit(initial, before)
        val exact = PreviewSelectionForms.settingsText(selection())
        for (raw in listOf("", " ", "-0.01", "1,25", "NaN", "\"1\"", "1e10001", "1".repeat(129))) {
            val form = configure()
            setCapacity(form, raw)
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            finishWork()
            assertTrue(form.isShowing)
            assertNotNull(textControl(form, "Provisioned storage (GB)").error)
            assertEquals(raw, textControl(form, "Provisioned storage (GB)").text.toString())
            assertNull(textControl(form, "Region").error)
            assertNull(textControl(form, "Encryption key reference").error)
            assertEquals(exact, PreviewSelectionForms.settingsText(selection()))
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(form)
        }
        val form = configure()
        setCapacity(form, "1,25")
        enter(form, "Encryption key reference", "synthetic-private-key")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.storage_number_invalid), textControl(form, "Provisioned storage (GB)").error.toString())
        assertEquals(activity.getString(R.string.storage_reference_invalid), textControl(form, "Encryption key reference").error.toString())
        assertFixedMarker(capture())
        enter(form, "Encryption key reference", reference)
        recreate()
        assertFalse(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val restored = reviewPending()
        assertEquals("1,25", textControl(restored, "Provisioned storage (GB)").text.toString())
        assertEquals(literalRegion, textControl(restored, "Region").text.toString())
        assertEquals(reference, textControl(restored, "Encryption key reference").text.toString())
        setCapacity(restored, "0.00")
        val saved = commit(restored, before)
        assertCapacity("0")
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(literalRegion, settings().getString("region"))
        assertEquals(reference, settings().getString("encryption_key_ref"))
    }

    @Test fun removedSelectionRetainsOnlyReadOnlyRecoveryAndCannotBeRecreatedByOldControls() {
        val before = seedStorage()
        val target = selection()
        val form = configure()
        fillAll(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val removed = (adapter().applySelection(before, SelectionCommand.Remove(target.id)) as PreviewSelectionSave.Saved).record
        assertTrue(removed.components.selections.isEmpty())
        recreate()
        readOnly(reviewPending(), literalRegion, reference, capacity)
        retiredCommit.performClick()
        finishWork()
        assertEquals(removed.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        recreate()
        val retained = reviewPending()
        readOnly(retained, literalRegion, reference, capacity)
        dismiss(retained)
        button(R.string.component_recovery_discard).performClick()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(removed.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
    }

}
