package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.*
import ai.pickyour.core.drafts.LocalSelection
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Existing Activity/worker/storage paths in simulation; no installed-process evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentFrameworkSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

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
        assertTrue("Expected whole-number input for $text", control is EditText)
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
        fail("Timed out waiting for agent-framework editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "agent_framework" }

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
        val actions = select(dialog()) { it.startsWith("${componentTokenForLookup(activity, "agent_framework")} ·") }
        return select(actions) { it == activity.getString(R.string.component_add) }
    }

    private fun configure(): AlertDialog {
        val target = selection()
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · ${componentTokenForLookup(activity, target.id)}") }
        return select(actions) { it == activity.getString(R.string.component_configure) }
    }

    private fun seedAgent(): PreviewStoredDraft {
        saveInitial()
        val before = loaded()
        val form = addForm()
        rawField(form, R.string.component_role).setText("primary")
        rawField(form, R.string.component_settings).setText("{}")
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        return loaded()
    }

    @Test fun configureShowsTypedOptionalAgentFrameworkFields() {
        val before = seedAgent()
        val selected = selection()
        assertEquals("primary", selected.role)
        assertFalse(selected.enabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selected))
        val form = configure()
        assertTrue(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(number(form, "Agent count").isEnabled)
        assertFalse(number(form, "Maximum iterations").isEnabled)
        assertFalse(number(form, "Timeout (milliseconds)").isEnabled)
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        finishWork()
        assertFalse(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    private fun settings(value: LocalSelection = selection()) = JSONObject(PreviewSelectionForms.settingsText(value))

    private fun commit(form: AlertDialog, before: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        return loaded()
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

    private val numericCaptions = listOf("Agent count", "Maximum iterations", "Timeout (milliseconds)")
    private val captions = numericCaptions
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        if (!supply(form, caption).isChecked) supply(form, caption).performClick()
        assertTrue(number(form, caption).isEnabled)
        number(form, caption).setText(raw)
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Agent count", "9007199254740993")
        enter(form, "Maximum iterations", "1e3")
        enter(form, "Timeout (milliseconds)", "1000")
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(form.window!!.decorView).filterIsInstance<CheckBox>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only agent-framework value", it in shown) }
    }

    @Test fun labelledControlsAndSupplyHintsKeepEveryOptionalStatePrivateAndExplicit() {
        val before = seedAgent()
        val form = configure()
        assertEquals(3, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        for (caption in numericCaptions) {
            val control = number(form, caption)
            assertFalse(control.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, control.importantForAutofill)
            assertTrue(control.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertEquals(activity.getString(R.string.agent_framework_not_supplied), control.hint.toString())
            assertFalse(supply(form, caption).isChecked)
            assertFalse(supply(form, caption).isSaveEnabled)
            supply(form, caption).performClick()
            assertTrue(control.isEnabled)
            assertNull(control.hint)
            assertEquals("", control.text.toString())
            supply(form, caption).performClick()
            assertFalse(control.isEnabled)
            assertEquals(activity.getString(R.string.agent_framework_not_supplied), control.hint.toString())
        }
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_agent_framework_guidance)
        })
        assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_agent_intent_guidance)
        })
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun agentAddKeepsTheExplicitRawSettingsRoleAndEnabledPath() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun exactOptionalIntegersSaveAndReopenWithoutChangingSelectionOrOtherDraftState() {
        val before = seedAgent()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        val values = PreviewAgentFrameworkSettings.initial(selection())
        assertEquals("9007199254740993", values.agentCount)
        assertEquals("1E+3", values.maxIterations)
        assertEquals("1000", values.timeoutMs)
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(3, settings().length())
        assertEquals(1000, settings().getInt("timeout_ms"))
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val reopened = configure()
        assertEquals(values.agentCount, number(reopened, "Agent count").text.toString())
        assertEquals(values.maxIterations, number(reopened, "Maximum iterations").text.toString())
        assertEquals(values.timeoutMs, number(reopened, "Timeout (milliseconds)").text.toString())
        captions.forEach { assertTrue(supply(reopened, it).isChecked) }
        dismiss(reopened)
    }

    @Test fun eachOptionalFieldCanSaveAloneWithoutFillingTheOtherTwo() {
        var before = seedAgent()
        for ((caption, key) in captions.zip(listOf("agent_count", "max_iterations", "timeout_ms"))) {
            val form = configure()
            captions.forEach { assertFalse(supply(form, it).isChecked) }
            enter(form, caption, "1")
            val saved = commit(form, before)
            assertEquals(1, settings().length())
            assertEquals(1, settings().getInt(key))
            val reopened = configure()
            for (other in captions) {
                assertEquals(other == caption, supply(reopened, other).isChecked)
                assertEquals(other == caption, number(reopened, other).isEnabled)
            }
            supply(reopened, caption).performClick()
            before = commit(reopened, saved)
            assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        }
    }

    @Test fun everyOptionalValueCanBeRemovedWithoutCreatingDefaults() {
        val before = seedAgent()
        val form = configure()
        fillAll(form)
        val supplied = commit(form, before)
        val clear = configure()
        captions.forEach { assertTrue(supply(clear, it).isChecked); supply(clear, it).performClick() }
        numericCaptions.forEach {
            assertFalse(number(clear, it).isEnabled)
            assertEquals("", number(clear, it).text.toString())
        }
        commit(clear, supplied)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun independentNumericErrorsRetainLiteralInputAndCannotReplaceAcknowledgedSettings() {
        val before = seedAgent()
        val form = configure()
        numericCaptions.forEach { enter(form, it, "") }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        numericCaptions.forEach {
            assertEquals(activity.getString(R.string.agent_framework_value_required), number(form, it).error.toString())
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Agent count", "1,5")
        assertNull(number(form, "Agent count").error)
        assertNotNull(number(form, "Maximum iterations").error)
        assertNotNull(number(form, "Timeout (milliseconds)").error)
        enter(form, "Maximum iterations", "0")
        enter(form, "Timeout (milliseconds)", "1.5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.agent_framework_integer_invalid), number(form, "Agent count").error.toString())
        assertEquals(activity.getString(R.string.agent_framework_integer_rule), number(form, "Maximum iterations").error.toString())
        assertEquals(activity.getString(R.string.agent_framework_integer_invalid), number(form, "Timeout (milliseconds)").error.toString())
        assertEquals("1,5", number(form, "Agent count").text.toString())
        assertEquals("0", number(form, "Maximum iterations").text.toString())
        assertEquals("1.5", number(form, "Timeout (milliseconds)").text.toString())
        assertFalse(number(form, "Agent count").error.toString().contains("1,5"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Agent count", "1e3")
        supply(form, "Maximum iterations").performClick()
        supply(form, "Timeout (milliseconds)").performClick()
        commit(form, before)
        assertEquals("1E+3", PreviewAgentFrameworkSettings.initial(selection()).agentCount)
        assertFalse(settings().has("max_iterations"))
        assertFalse(settings().has("timeout_ms"))
        assertEquals(1, settings().length())
    }

    @Test fun cancelAndUnchangedCommitDoNotCreateARevisionOrOptionalDefaults() {
        val before = seedAgent()
        val form = configure()
        fillAll(form)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val noop = configure()
        noop.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        await { !noop.isShowing }
        assertEquals(before.revision, loaded().revision)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun invalidAndEmptyInputRecoverOnlyAfterReviewWithRetiredControls() {
        val before = seedAgent()
        val target = selection()
        val form = configure()
        enter(form, "Agent count", "unfinished")
        enter(form, "Maximum iterations", "")
        enter(form, "Timeout (milliseconds)", "1e3")
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, "Maximum iterations")
        val retiredNumber = number(form, "Timeout (milliseconds)")
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedAgentFrameworkPending).form
            assertEquals(PreviewAgentFrameworkInput("unfinished", "", "1e3"), pending.input)
            assertEquals(target.catalogItemId, pending.catalogItemId)
            assertEquals(target.catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, "Agent count").text.toString())
        assertEquals("", number(restored, "Maximum iterations").text.toString())
        assertEquals("1e3", number(restored, "Timeout (milliseconds)").text.toString())
        numericCaptions.forEach { assertTrue(supply(restored, it).isChecked); assertNull(number(restored, it).hint) }
        retiredCommit.performClick(); retiredSupply.performClick(); retiredNumber.setText("changed after retirement"); finishWork()
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertFalse(retiredNumber.isEnabled)
        assertEquals("1e3", number(restored, "Timeout (milliseconds)").text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, "Agent count", "2")
        enter(restored, "Maximum iterations", "3")
        commit(restored, before)
        assertEquals("2", PreviewAgentFrameworkSettings.initial(selection()).agentCount)
        assertEquals("3", PreviewAgentFrameworkSettings.initial(selection()).maxIterations)
        assertEquals("1E+3", PreviewAgentFrameworkSettings.initial(selection()).timeoutMs)
    }

    @Test fun changedSavedHeadKeepsPendingAgentInputReadOnlyUntilDiscardAndReopen() {
        val before = seedAgent()
        val form = configure()
        enter(form, "Agent count", "unfinished")
        enter(form, "Maximum iterations", "")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", activity.getString(R.string.agent_framework_supplied_value),
            activity.getString(R.string.agent_framework_not_supplied), activity.getString(R.string.agent_framework_empty_value))
        recreate()
        val retained = reviewPending()
        readOnly(retained, "unfinished", activity.getString(R.string.agent_framework_empty_value))
        dismiss(retained)
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        dismiss(reviewPending())
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun everyOriginalIdentityAndTheAgentKindRemainNecessaryForWritableRecovery() {
        val before = seedAgent()
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Agent count", "pending value")
            enter(form, "Timeout (milliseconds)", "1000")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "1000")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Agent count", "pending value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightAgentSaveFreezesAllControlsAndRecoveryCannotReplayTheAcknowledgedWrite() {
        val before = seedAgent()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            numericCaptions.forEach { assertFalse(number(form, it).isEnabled) }
            captions.forEach { assertFalse(supply(form, it).isEnabled) }
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, "Timeout (milliseconds)").performClick()
            assertTrue(supply(form, "Timeout (milliseconds)").isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedAgentFrameworkPending).form
                assertTrue(pending.wasSaving)
                assertEquals(PreviewAgentFrameworkInput("9007199254740993", "1e3", "1000"), pending.input)
                release.countDown()
                // Let the owned writer finish before the first durable observation.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "9007199254740993", "1e3", "1000")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun actualPauseRetiresAgentControlsAndRequiresExplicitReviewBeforeEditing() {
        val before = seedAgent()
        val form = configure()
        enter(form, "Agent count", "unfinished")
        enter(form, "Timeout (milliseconds)", "1e3")
        val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val oldSupply = supply(form, "Agent count")
        val oldTimeoutSupply = supply(form, "Timeout (milliseconds)")
        val oldNumber = number(form, "Timeout (milliseconds)")
        controller.pause()
        assertFalse(form.isShowing)
        commit.performClick(); oldSupply.performClick(); oldTimeoutSupply.performClick()
        oldNumber.setText("changed after pause")
        controller.resume().visible()
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, "Agent count").text.toString())
        assertFalse(supply(restored, "Maximum iterations").isChecked)
        assertTrue(supply(restored, "Timeout (milliseconds)").isChecked)
        assertEquals("1e3", number(restored, "Timeout (milliseconds)").text.toString())
        assertTrue(oldSupply.isChecked)
        assertTrue(oldTimeoutSupply.isChecked)
        assertFalse(oldSupply.isEnabled)
        assertFalse(oldTimeoutSupply.isEnabled)
        assertFalse(oldNumber.isEnabled)
        dismiss(restored)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun staleManagedAgentCommitCannotOverwriteAnotherWriterOrRestoreSaveAuthority() {
        val before = seedAgent()
        val form = configure()
        enter(form, "Agent count", "2")
        enter(form, "Timeout (milliseconds)", "1000")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        numericCaptions.forEach { assertFalse(number(form, it).isEnabled) }
        captions.forEach { assertFalse(supply(form, it).isEnabled) }
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "2", "1000")
    }

    @Test fun combinedOversizedInputRetainsNoRecoveryPrefixOrAutomaticViewState() {
        val before = seedAgent()
        val form = configure()
        val first = "a".repeat(6_000)
        val second = "b".repeat(7_000)
        val third = "c".repeat(7_001)
        assertEquals(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1, first.length + second.length + third.length)
        enter(form, "Agent count", first)
        enter(form, "Maximum iterations", second)
        enter(form, "Timeout (milliseconds)", third)
        assertEquals(first, number(form, "Agent count").text.toString())
        assertEquals(second, number(form, "Maximum iterations").text.toString())
        assertEquals(third, number(form, "Timeout (milliseconds)").text.toString())
        recreate { state ->
            val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), value.keySet())
            assertTrue(value.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertFalse(views().filterIsInstance<TextView>().any { it.text.toString() in listOf(first, second, third) })
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun readOnlyLiteralStateWordsAndSuppliedEmptyRemainDifferentFromAbsence() {
        val before = seedAgent()
        val form = configure()
        enter(form, "Agent count", "Not supplied")
        enter(form, "Maximum iterations", "Supplied empty value")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("draftId", "other_fixture_identity") }
        val retained = reviewPending()
        readOnly(retained, "Not supplied", "Supplied empty value")
        val shown = views(retained.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(2, shown.count { it == activity.getString(R.string.agent_framework_supplied_value) })
        assertEquals(2, shown.count { it == activity.getString(R.string.agent_framework_not_supplied) })
        assertEquals(1, shown.count { it == activity.getString(R.string.agent_framework_empty_value) })
        dismiss(retained)
        button(R.string.component_recovery_discard).performClick()
        val empty = configure()
        enter(empty, "Agent count", "")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("draftId", "other_fixture_identity") }
        val recoveredEmpty = reviewPending()
        readOnly(recoveredEmpty, activity.getString(R.string.agent_framework_empty_value),
            activity.getString(R.string.agent_framework_not_supplied))
        val states = views(recoveredEmpty.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(1, states.count { it == activity.getString(R.string.agent_framework_empty_value) })
        assertEquals(2, states.count { it == activity.getString(R.string.agent_framework_not_supplied) })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun legacyRawAgentRecoveryStillUsesItsOriginalCorrectionReader() {
        val before = seedAgent()
        val target = selection()
        val raw = """{"agent_count":"""
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
