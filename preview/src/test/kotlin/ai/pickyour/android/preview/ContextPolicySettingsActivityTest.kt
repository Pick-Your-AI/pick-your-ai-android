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
class ContextPolicySettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val minimum = """{"requested_context_tokens":1024,"reserved_output_tokens":0}"""

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
        fail("Timed out waiting for context-policy editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "context_policy" }
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
        val actions = select(dialog()) { it.startsWith("context_policy ·") }
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

    private fun seedPolicy(): PreviewStoredDraft {
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

    @Test fun configureShowsFourTypedControlsAndExactInitialValues() {
        val before = seedPolicy()
        val form = configure()
        assertEquals("1024", number(form, "Requested context tokens").text.toString())
        assertEquals("0", number(form, "Reserved output tokens").text.toString())
        assertEquals("Not supplied", choice(form, "Truncation strategy").text.toString())
        assertEquals("Not supplied", choice(form, "KV cache precision").text.toString())
        assertEquals(2, views(form.window!!.decorView).filterIsInstance<EditText>().size)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun reservedZeroStaysExplicitAndOptionalChoicesStayAbsentAfterCommit() {
        val before = seedPolicy()
        val form = configure()
        number(form, "Requested context tokens").setText("2048")
        number(form, "Reserved output tokens").setText("0")
        val saved = commit(form, before)
        val value = settings()
        assertEquals(2, value.length())
        assertEquals(2048, value.getInt("requested_context_tokens"))
        assertEquals(0, value.getInt("reserved_output_tokens"))
        assertFalse(value.has("truncation_strategy"))
        assertFalse(value.has("kv_cache_precision"))
        assertEquals(before.components.selections.single().role, saved.components.selections.single().role)
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val reopened = configure()
        assertEquals("0", number(reopened, "Reserved output tokens").text.toString())
        assertEquals("Not supplied", choice(reopened, "Truncation strategy").text.toString())
        dismiss(reopened)
    }

    @Test fun invalidRequiredRawTextSurvivesRecreationAndCannotCommit() {
        val before = seedPolicy()
        val form = configure()
        number(form, "Requested context tokens").setText("")
        number(form, "Reserved output tokens").setText("1,5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        recreate()
        val restored = reviewPending()
        assertEquals("", number(restored, "Requested context tokens").text.toString())
        assertEquals("1,5", number(restored, "Reserved output tokens").text.toString())
        restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(restored.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(restored)
    }

    @Test fun optionalChoicesCanBeSelectedRemovedAndCancelledWithoutChangingSavedState() {
        val before = seedPolicy()
        val form = configure()
        choose(choice(form, "Truncation strategy"), "sliding_window")
        choose(choice(form, "KV cache precision"), "fp16")
        val saved = commit(form, before)
        assertEquals("sliding_window", settings().getString("truncation_strategy"))
        assertEquals("fp16", settings().getString("kv_cache_precision"))
        val canceled = configure()
        choose(choice(canceled, "Truncation strategy"), "Not supplied")
        number(canceled, "Requested context tokens").setText("3072")
        dismiss(canceled)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals("sliding_window", settings().getString("truncation_strategy"))
        val cleared = configure()
        choose(choice(cleared, "Truncation strategy"), "Not supplied")
        choose(choice(cleared, "KV cache precision"), "Not supplied")
        commit(cleared, saved)
        assertEquals(2, settings().length())
        assertEquals(1024, settings().getInt("requested_context_tokens"))
        assertEquals(0, settings().getInt("reserved_output_tokens"))
    }

    @Test fun addStillStartsWithRawJsonAndNoImplicitSettings() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    @Test fun legacyRawContextCheckpointKeepsInvalidTextAndOriginalAuthority() {
        val before = seedPolicy()
        val target = selection()
        val raw = """{"requested_context_tokens":"""
        recreate { state ->
            val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE,
                currentId().value, before.baselineIdentity, target.id,
                null, null, null, null, "", raw, false, false)
            state.putBundle(ComponentFormRecovery.KEY, requireNotNull(legacy.bundle()))
        }
        val restored = reviewPending()
        assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
        restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(restored.isShowing)
        assertEquals(raw, rawField(restored, R.string.component_settings).text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(restored)
    }

    @Test fun cancelConfigureKeepsCurrentRevisionAndOriginalSettings() {
        val before = seedPolicy()
        val original = PreviewSelectionForms.settingsText(selection())
        dismiss(configure())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals(original, PreviewSelectionForms.settingsText(selection()))
    }

    private fun readOnly(form: AlertDialog, vararg raw: String) {
        assertTrue(form.isShowing)
        assertEquals(View.GONE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val values = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        raw.forEach { assertTrue("Missing retained read-only value", it in values) }
    }

    @Test fun invalidNumbersHaveIndependentValueFreeErrorsAndCanBeCorrected() {
        val before = seedPolicy()
        val form = configure()
        val requested = number(form, "Requested context tokens")
        val reserved = number(form, "Reserved output tokens")
        val rejected = "synthetic-private-invalid-number"
        requested.setText("0")
        reserved.setText(rejected)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(activity.getString(R.string.context_policy_minimum, "1"), requested.error.toString())
        assertEquals(activity.getString(R.string.context_policy_integer), reserved.error.toString())
        assertFalse(reserved.error.toString().contains(rejected))
        assertEquals("0", requested.text.toString())
        assertEquals(rejected, reserved.text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        requested.setText("1")
        assertNull(requested.error)
        assertNotNull(reserved.error)
        reserved.setText("0")
        assertNull(reserved.error)
        commit(form, before)
        assertEquals(1, settings().getInt("requested_context_tokens"))
        assertEquals(0, settings().getInt("reserved_output_tokens"))
    }

    @Test fun unchangedTypedCommitClosesWithoutCreatingAnotherRevision() {
        val before = seedPolicy()
        val raw = PreviewSelectionForms.settingsText(selection())
        val form = configure()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(raw, PreviewSelectionForms.settingsText(selection()))
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_noop)
        })
    }

    @Test fun choicePickerShowsCurrentSelectionAndRetiredCallbackCannotReviveInput() {
        val before = seedPolicy()
        val form = configure()
        val precision = choice(form, "KV cache precision")
        choose(precision, "fp16")
        precision.performClick()
        val picker = dialog()
        assertEquals("fp16", picker.listView.adapter.getItem(picker.listView.checkedItemPosition))
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !picker.isShowing }
        assertEquals("fp16", precision.text.toString())
        precision.performClick()
        val retired = dialog()
        dismiss(form)
        assertFalse(retired.isShowing)
        shadowOf(retired).clickOnItem(1)
        precision.performClick()
        finishWork()
        assertEquals("fp16", precision.text.toString())
        assertFalse(precision.isEnabled)
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(settings().has("kv_cache_precision"))
    }

    @Test fun changedSavedHeadKeepsAllFourTypedInputsReadOnlyAcrossRepeatedRecovery() {
        val before = seedPolicy()
        val form = configure()
        number(form, "Requested context tokens").setText("1,5")
        number(form, "Reserved output tokens").setText("")
        choose(choice(form, "Truncation strategy"), "reject")
        choose(choice(form, "KV cache precision"), "int4")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        readOnly(reviewPending(), "1,5", "", "reject", "int4")
        recreate()
        val retained = reviewPending()
        readOnly(retained, "1,5", "", "reject", "int4")
        dismiss(retained)
        // A changed saved head also keeps the existing whole-draft restoration gate closed.
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        val afterReopen = reviewPending()
        readOnly(afterReopen, "1,5", "", "reject", "int4")
        dismiss(afterReopen)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(1024, settings().getInt("requested_context_tokens"))
        assertFalse(settings().has("truncation_strategy"))
    }

    @Test fun mismatchedDraftOrSelectionCheckpointNeverAcquiresCurrentSaveAuthority() {
        val before = seedPolicy()
        for (key in listOf("draftId", "selectionId")) {
            val form = configure()
            number(form, "Requested context tokens").setText("pending value")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "0", "Not supplied")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(1024, settings().getInt("requested_context_tokens"))
    }

    @Test fun inFlightTypedCheckpointFreezesEveryControlAndNeverReplaysTheCompletedWrite() {
        val before = seedPolicy()
        val form = configure()
        number(form, "Requested context tokens").setText("4096")
        choose(choice(form, "Truncation strategy"), "summarize")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(number(form, "Requested context tokens").isEnabled)
            assertFalse(number(form, "Reserved output tokens").isEnabled)
            assertFalse(choice(form, "Truncation strategy").isEnabled)
            assertFalse(choice(form, "KV cache precision").isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            val latestBeforeBlockedClick = dialog() // The dismissed summarize picker is still the global latest.
            assertFalse(latestBeforeBlockedClick.isShowing)
            choice(form, "KV cache precision").performClick()
            assertTrue(form.isShowing)
            assertSame(latestBeforeBlockedClick, dialog())
            assertFalse(dialog().isShowing)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedContextPending).form
                assertTrue(pending.wasSaving)
                assertEquals("4096", pending.input.requestedContextTokens)
                assertEquals("summarize", pending.input.truncationStrategy)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val committed = loaded()
        assertEquals(before.revision + 1, committed.revision)
        val retained = reviewPending()
        readOnly(retained, "4096", "0", "summarize", "Not supplied")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        recreate()
        readOnly(reviewPending(), "4096", "summarize")
        assertEquals(committed.baselineIdentity, loaded().baselineIdentity)
        assertEquals(4096, settings().getInt("requested_context_tokens"))
    }

    @Test fun staleManagedCommitCannotReplaceAnotherWriterOrReenableItsRetiredControls() {
        val before = seedPolicy()
        val form = configure()
        number(form, "Requested context tokens").setText("4096")
        val precision = choice(form, "KV cache precision")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertFalse(number(form, "Requested context tokens").isEnabled)
        assertFalse(number(form, "Reserved output tokens").isEnabled)
        assertFalse(precision.isEnabled)
        assertFalse(choice(form, "Truncation strategy").isEnabled)
        precision.performClick()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertSame(form, dialog())
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(1024, settings().getInt("requested_context_tokens"))
        recreate()
        readOnly(reviewPending(), "4096", "0", "Not supplied")
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun oversizedTypedRecoveryIsUnavailableWithoutKeepingAValidPrefix() {
        val before = seedPolicy()
        val form = configure()
        val raw = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        number(form, "Requested context tokens").setText(raw)
        assertEquals(raw, number(form, "Requested context tokens").text.toString())
        recreate { state -> assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state))) }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_unavailable)
        })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }
}
