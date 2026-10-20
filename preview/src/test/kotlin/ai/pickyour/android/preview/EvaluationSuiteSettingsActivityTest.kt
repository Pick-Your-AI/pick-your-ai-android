package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Activity/worker/storage paths in simulation; never installed-process evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EvaluationSuiteSettingsActivityTest {
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
        assertTrue("Expected exact numeric input for $text", control is EditText)
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
        fail("Timed out waiting for evaluation-suite editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "evaluation_suite" }
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
        val actions = select(dialog()) { it.startsWith("${componentTokenForLookup(activity, PreviewEvaluationSuiteSettings.KIND)} ·") }
        return select(actions) { it == activity.getString(R.string.component_add) }
    }

    private fun configure(): AlertDialog {
        val target = selection()
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · ${componentTokenForLookup(activity, target.id)}") }
        return select(actions) { it == activity.getString(R.string.component_configure) }
    }

    private fun commit(form: AlertDialog, before: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
        return loaded()
    }

    private fun seedEvaluationSuite(): PreviewStoredDraft {
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

    @Test fun optionalEvaluationSuiteFieldsHaveLabeledPrivateNativeControls() {
        val before = seedEvaluationSuite()
        val form = configure()
        for (caption in listOf("Minimum score", "Dataset size")) {
            val control = number(form, caption)
            assertFalse(control.isEnabled)
            assertEquals("", control.text.toString())
            assertFalse(control.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, control.importantForAutofill)
            assertTrue(control.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        val enabled = captionControl(form, "Gate required")
        assertTrue(enabled is Button)
        assertEquals("Not supplied", (enabled as Button).text.toString())
        assertEquals(2, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        dismiss(form)
    }

    private val textCaptions = listOf("Minimum score", "Dataset size")
    private fun gate(form: AlertDialog) = captionControl(form, "Gate required") as Button
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
        enter(form, textCaptions[0], "-2.5")
        enter(form, textCaptions[1], "9007199254740993")
        choose(gate(form), "True")
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only evaluation-suite value", it in shown) }
    }

    @Test fun genericEvaluationSuiteAddRetainsExplicitRawSettingsAndOuterEnabled() {
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
        val before = seedEvaluationSuite()
        val original = selection()
        val form = configure()
        fill(form)
        val saved = commit(form, before)
        assertEquals(PreviewEvaluationSuiteInput("-2.5", "9007199254740993", true),
            PreviewEvaluationSuiteSettings.initial(selection()))
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
        assertEquals("-2.5", number(reopened, textCaptions[0]).text.toString())
        assertEquals("9007199254740993", number(reopened, textCaptions[1]).text.toString())
        assertEquals("True", gate(reopened).text.toString())
        textCaptions.forEach { assertTrue(supply(reopened, it).isChecked) }
        dismiss(reopened)
    }

    @Test fun falseTrueAndNotSuppliedRemainDistinctWithoutChangingTheComponentFlag() {
        var before = seedEvaluationSuite()
        for ((caption, expected) in listOf("False" to false, "True" to true, "Not supplied" to null)) {
            val form = configure()
            choose(gate(form), caption)
            before = commit(form, before)
            assertEquals(expected, PreviewEvaluationSuiteSettings.initial(selection()).gateRequired)
            assertEquals(expected != null, settings().has("gate_required"))
            assertFalse(selection().enabled)
            assertEquals(if (expected == null) 0 else 1, settings().length())
        }
    }

    @Test fun independentFieldErrorsPreserveRawInputAndAcknowledgedStorage() {
        val before = seedEvaluationSuite()
        val form = configure()
        enter(form, textCaptions[0], "synthetic-private-value")
        enter(form, textCaptions[1], "")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.evaluation_suite_number_invalid), number(form, textCaptions[0]).error.toString())
        assertEquals(activity.getString(R.string.evaluation_suite_value_required), number(form, textCaptions[1]).error.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, textCaptions[0], "-2.5")
        assertNull(number(form, textCaptions[0]).error)
        assertNotNull(number(form, textCaptions[1]).error)
        enter(form, textCaptions[1], "1,5")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertNull(number(form, textCaptions[0]).error)
        assertEquals(activity.getString(R.string.evaluation_suite_integer_invalid), number(form, textCaptions[1]).error.toString())
        assertEquals("1,5", number(form, textCaptions[1]).text.toString())
        assertFalse(number(form, textCaptions[1]).error.toString().contains("1,5"))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, textCaptions[1], "0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.evaluation_suite_dataset_rule), number(form, textCaptions[1]).error.toString())
        supply(form, textCaptions[1]).performClick()
        choose(gate(form), "False")
        commit(form, before)
        assertFalse(settings().has("dataset_size"))
        assertEquals(-2.5, settings().getDouble("minimum_score"), 0.0)
        assertFalse(settings().getBoolean("gate_required"))
    }

    @Test fun cancelAndUntouchedCommitDoNotSaveDefaultsOrAnotherRevision() {
        val before = seedEvaluationSuite()
        val form = configure(); fill(form); dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val noop = configure()
        noop.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !noop.isShowing }; finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun rawNumbersAndFalseRecoverOnlyAfterReviewWhileOldControlsRemainRetired() {
        val before = seedEvaluationSuite()
        val form = configure()
        enter(form, textCaptions[0], "-2.5")
        enter(form, textCaptions[1], "unfinished")
        choose(gate(form), "False")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, textCaptions[1])
        val retiredChoice = gate(form)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedEvaluationSuitePending).form
            assertEquals(PreviewEvaluationSuiteInput("-2.5", "unfinished", false), pending.input)
            assertEquals(selection().catalogItemId, pending.catalogItemId)
            assertEquals(selection().catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("unfinished", number(restored, textCaptions[1]).text.toString())
        assertEquals("False", gate(restored).text.toString())
        retired.performClick(); retiredSupply.performClick(); retiredChoice.performClick(); finishWork()
        assertSame(restored, dialog())
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, textCaptions[1], "1")
        commit(restored, before)
        assertFalse(settings().getBoolean("gate_required"))
    }

    @Test fun wrongDraftSelectionAndCatalogIdentitiesNeverReceiveFreshSaveAuthority() {
        val before = seedEvaluationSuite()
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
        val before = seedEvaluationSuite()
        val form = configure(); enter(form, textCaptions[1], "unfinished"); choose(gate(form), "False")
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
        val before = seedEvaluationSuite()
        val form = configure(); fill(form)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            textCaptions.forEach { assertFalse(number(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
            assertFalse(gate(form).isEnabled)
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            gate(form).performClick(); supply(form, textCaptions[0]).performClick()
            assertTrue(supply(form, textCaptions[0]).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedEvaluationSuitePending).form
                assertTrue(pending.wasSaving)
                assertEquals(true, pending.input.gateRequired)
                release.countDown()
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending(); readOnly(retained, "-2.5", "9007199254740993", "True")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun actualPauseRetiresOpenBooleanPickerAndRequiresFreshInputReview() {
        val before = seedEvaluationSuite()
        val form = configure(); fill(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredChoice = gate(form)
        retiredChoice.performClick(); val picker = dialog()
        controller.pause()
        assertFalse(form.isShowing); assertFalse(picker.isShowing)
        retiredCommit.performClick(); shadowOf(picker).clickOnItem(1); retiredChoice.performClick(); finishWork()
        assertSame(picker, dialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val state = Bundle(); controller.saveInstanceState(state)
        val pending = (ComponentFormRecovery.read(state) as ComponentFormRecovery.TypedEvaluationSuitePending).form
        assertEquals(true, pending.input.gateRequired)
        assertFalse(pending.wasSaving)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        button(R.string.component_recovery_review).performClick(); button(R.string.component_recovery_discard).performClick()
        controller.resume().visible(); finishWork()
        val reviewed = reviewPending()
        assertEquals("True", gate(reviewed).text.toString())
        assertEquals("-2.5", number(reviewed, textCaptions[0]).text.toString())
        val saved = commit(reviewed, before)
        retiredCommit.performClick(); reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun canceledAndConsumedBooleanChoicesCannotReplaceTheCurrentChoice() {
        val before = seedEvaluationSuite()
        val form = configure(); val control = gate(form)
        control.performClick(); val canceled = dialog(); dismiss(canceled)
        shadowOf(canceled).clickOnItem(2); idle()
        assertEquals("Not supplied", control.text.toString())
        control.performClick(); val current = dialog()
        shadowOf(current).clickOnItem(1); await { !current.isShowing }
        shadowOf(current).clickOnItem(2); idle()
        assertEquals("False", control.text.toString())
        commit(form, before)
        assertFalse(settings().getBoolean("gate_required"))
        assertFalse(selection().enabled)
    }

    @Test fun scoreHasNoInventedPercentageRangeAndInvalidTextRecoversForCorrection() {
        val before = seedEvaluationSuite()
        val form = configure()
        enter(form, textCaptions[0], "unfinished")
        enter(form, textCaptions[1], "1")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.evaluation_suite_number_invalid),
            number(form, textCaptions[0]).error.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedEvaluationSuitePending).form
            assertEquals("unfinished", pending.input.minimumScore)
            assertEquals("1", pending.input.datasetSize)
        }
        val recovered = reviewPending()
        assertEquals("unfinished", number(recovered, textCaptions[0]).text.toString())
        enter(recovered, textCaptions[0], "-2.5")
        val negative = commit(recovered, before)
        assertEquals(-2.5, settings().getDouble("minimum_score"), 0.0)
        val positive = configure()
        enter(positive, textCaptions[0], "1000.125")
        commit(positive, negative)
        assertEquals(1000.125, settings().getDouble("minimum_score"), 0.0)
    }

    @Test fun oversizedInputRetainsNoPrefixOrAutomaticViewState() {
        val before = seedEvaluationSuite()
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
        val before = seedEvaluationSuite()
        val form = configure(); fill(form)
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach { assertFalse(number(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
        assertFalse(gate(form).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate(); readOnly(reviewPending(), "-2.5", "9007199254740993", "True")
    }

    @Test fun legacyRawEvaluationSuiteCheckpointsKeepTheirOriginalCorrectionReader() {
        val before = seedEvaluationSuite(); val target = selection(); val raw = """{"minimum_score":"""
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

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsIdentityFieldsGuidanceAndConfirmationReachable() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        val before = seedEvaluationSuite()
        val target = selection()
        val form = configure()
        fill(form)
        val root = form.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val identity = views(root).filterIsInstance<TextView>().single {
            it.text.toString() == ComponentSelectionText.selection(activity, target)
        }
        val identityLayout = requireNotNull(identity.layout)
        for (line in 0 until identityLayout.lineCount) reachable(identity, root,
            Rect(0, identity.totalPaddingTop + identityLayout.getLineTop(line), identity.width,
                identity.totalPaddingTop + identityLayout.getLineBottom(line)))
        for (control in views(root).filterIsInstance<Button>().filter { it.isShown && it.text.isNotBlank() }) {
            val description = control.javaClass.simpleName + " id=" + control.id + " text=" + control.text +
                " measured=" + control.width + "x" + control.height + " minimum=" + (48 * density).toInt()
            assertTrue("Evaluation-suite button must meet minimum width: " + description,
                control.width >= (48 * density).toInt())
            assertTrue("Evaluation-suite button must meet minimum height: " + description,
                control.height >= (48 * density).toInt())
            reachable(control, root)
        }
        for (caption in textCaptions) {
            val input = number(form, caption)
            val layout = requireNotNull(input.layout)
            for (line in 0 until layout.lineCount) reachable(input, root,
                Rect(0, input.totalPaddingTop + layout.getLineTop(line), input.width,
                    input.totalPaddingTop + layout.getLineBottom(line)))
        }
        for (label in listOf(R.string.evaluation_suite_editor_guidance, R.string.evaluation_suite_recovery_guidance,
            R.string.component_evaluation_guidance, R.string.component_core_input_guidance,
            R.string.component_core_guidance_unavailable)) {
            val explanation = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
            val layout = requireNotNull(explanation.layout)
            for (line in listOf(0, layout.lineCount - 1)) reachable(explanation, root,
                Rect(0, explanation.totalPaddingTop + layout.getLineTop(line), explanation.width,
                    explanation.totalPaddingTop + layout.getLineBottom(line)))
        }
        for (view in views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }) {
            val layout = requireNotNull(view.layout)
            assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals("Visible evaluation-suite text must not be ellipsized: " +
                view.javaClass.simpleName + " id=" + view.id + " text=" + view.text, 0, layout.getEllipsisCount(line))
        }
        assertEquals(before.revision + 1, commit(form, before).revision)
        assertEquals(PreviewEvaluationSuiteInput("-2.5", "9007199254740993", true),
            PreviewEvaluationSuiteSettings.initial(selection()))
    }

    private fun reachable(control: View, root: View, target: Rect = Rect(0, 0, control.width, control.height)) {
        control.requestRectangleOnScreen(Rect(target), true)
        val bounds = Rect(target)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            val viewport = Rect().also(parent::getDrawingRect)
            assertTrue("Evaluation-suite control must remain reachable: " + control.javaClass.simpleName +
                " bounds=" + bounds + " viewport=" + viewport, viewport.contains(bounds))
            child = parent
        }
    }
}
