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
class EmbeddingModelSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val minimum = "{\"region\":\"\"}"

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
        fail("Timed out waiting for embedding editor state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "embedding_model" }
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
        val actions = select(dialog()) { it.startsWith("${componentTokenForLookup(activity, "embedding_model")} ·") }
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

    private fun seedEmbedding(raw: String = minimum): PreviewStoredDraft {
        saveInitial()
        val form = addForm()
        rawField(form, R.string.component_role).setText("primary")
        rawField(form, R.string.component_settings).setText(raw)
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

    @Test fun configureShowsTypedOptionalEmbeddingFields() {
        val before = seedEmbedding()
        val form = configure()
        for (caption in listOf("Credential reference", "Output dimensions", "Region")) {
            val control = number(form, caption)
            val supplied = views(form.window!!.decorView).filterIsInstance<CheckBox>()
                .single { it.text.toString() == "Supply $caption" }
            assertEquals(caption == "Region", supplied.isChecked)
            assertEquals(caption == "Region", control.isEnabled)
            assertEquals("", control.text.toString())
            if (caption == "Region") assertNull(control.hint)
            else assertEquals(activity.getString(R.string.embedding_model_not_supplied), control.hint.toString())
        }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("", settings().getString("region"))
        dismiss(form)
    }

    private val captions = listOf("Credential reference", "Output dimensions", "Region")
    private fun supply(form: AlertDialog, caption: String): CheckBox =
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single { it.text.toString() == "Supply $caption" }
    private fun enter(form: AlertDialog, caption: String, raw: String) {
        val supplied = supply(form, caption)
        if (!supplied.isChecked) supplied.performClick()
        assertTrue(number(form, caption).isEnabled)
        number(form, caption).setText(raw)
    }
    private fun fillAll(form: AlertDialog) {
        enter(form, "Credential reference", "secret://fixture/Case:Exact\n")
        enter(form, "Output dimensions", "9007199254740993")
        enter(form, "Region", " \t\n")
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only embedding value", it in shown) }
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)
    private fun disk() = java.nio.file.Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { java.nio.file.Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(it)).toList()
        }
    }
    private fun unavailable(state: Bundle) {
        val value = requireNotNull(state.getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), value.keySet())
        assertTrue(value.getBoolean("unavailable"))
    }

    @Test fun rawAddRemainsExplicitAndUnchangedWithoutOptionalDefaults() {
        saveInitial()
        val before = loaded()
        val form = addForm()
        assertEquals("{}", rawField(form, R.string.component_settings).text.toString())
        assertEquals("", rawField(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        dismiss(form)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun threeExactValuesSaveReopenAndRemoveOnlyTheExplicitlyClearedField() {
        val before = seedEmbedding()
        val original = selection()
        val form = configure()
        fillAll(form)
        val saved = commit(form, before)
        assertEquals(3, settings().length())
        assertEquals("9007199254740993", PreviewEmbeddingModelSettings.initial(selection()).outputDimensions)
        assertEquals("secret://fixture/Case:Exact\n", settings().getString("credential_ref"))
        assertEquals(" \t\n", settings().getString("region"))
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        assertEquals(before.components.links, saved.components.links)
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val reopened = configure()
        captions.forEach { assertTrue(supply(reopened, it).isChecked) }
        supply(reopened, "Region").performClick()
        assertFalse(number(reopened, "Region").isEnabled)
        assertEquals("secret://fixture/Case:Exact\n", number(reopened, "Credential reference").text.toString())
        assertEquals("9007199254740993", number(reopened, "Output dimensions").text.toString())
        commit(reopened, saved)
        assertFalse(settings().has("region"))
        assertEquals(2, settings().length())
    }

    @Test fun suppliedEmptyRegionAndIntegralDecimalSurviveARealSaveAndReopen() {
        val before = seedEmbedding("{}")
        val form = configure()
        assertEquals(activity.getString(R.string.embedding_model_not_supplied), number(form, "Region").hint.toString())
        enter(form, "Region", "")
        assertNull(number(form, "Region").hint)
        supply(form, "Region").performClick()
        assertFalse(number(form, "Region").isEnabled)
        assertEquals(activity.getString(R.string.embedding_model_not_supplied), number(form, "Region").hint.toString())
        supply(form, "Region").performClick()
        assertTrue(number(form, "Region").isEnabled)
        assertEquals("", number(form, "Region").text.toString())
        assertNull(number(form, "Region").hint)
        enter(form, "Output dimensions", "1.000")
        val saved = commit(form, before)
        recreate()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertTrue(settings().has("region"))
        assertEquals("", settings().getString("region"))
        assertEquals("1.000", PreviewEmbeddingModelSettings.initial(selection()).outputDimensions)
        val reopened = configure()
        assertTrue(supply(reopened, "Region").isChecked)
        assertEquals("", number(reopened, "Region").text.toString())
        assertNull(number(reopened, "Region").hint)
        enter(reopened, "Credential reference", "secret://fixture/ref")
        commit(reopened, saved)
        assertEquals("1.000", PreviewEmbeddingModelSettings.initial(selection()).outputDimensions)
        assertEquals("", settings().getString("region"))
    }

    @Test fun cancelRetiresSupplyAndCommitCallbacksWithoutChangingAnySavedBytes() {
        val before = seedEmbedding()
        val form = configure()
        fillAll(form)
        val oldSupply = supply(form, "Output dimensions")
        val oldCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val bytes = disk()
        dismiss(form)
        oldSupply.performClick()
        oldCommit.performClick()
        finishWork()
        assertTrue(oldSupply.isChecked)
        assertFalse(oldSupply.isEnabled)
        assertFalse(number(form, "Output dimensions").isEnabled)
        assertEquals("9007199254740993", number(form, "Output dimensions").text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(bytes, disk())
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun untouchedTypedCommitClosesWithoutNewRevisionOrInventedValues() {
        val before = seedEmbedding("{}")
        val bytes = disk()
        val form = configure()
        captions.forEach { assertFalse(supply(form, it).isChecked) }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing }
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision, loaded().revision)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        assertEquals(bytes, disk())
    }

    @Test fun independentInvalidFieldsKeepCorrectionTextAndCannotPartiallySave() {
        val before = seedEmbedding()
        val form = configure()
        enter(form, "Credential reference", "synthetic-pasted-password")
        enter(form, "Output dimensions", "1.5")
        enter(form, "Region", "")
        val bytes = disk()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        assertEquals(activity.getString(R.string.embedding_model_reference_invalid), number(form, "Credential reference").error.toString())
        assertEquals(activity.getString(R.string.embedding_model_integer_invalid), number(form, "Output dimensions").error.toString())
        assertNull(number(form, "Region").error)
        assertEquals("synthetic-pasted-password", number(form, "Credential reference").text.toString())
        assertEquals("1.5", number(form, "Output dimensions").text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(bytes, disk())
        supply(form, "Credential reference").performClick()
        enter(form, "Output dimensions", "1")
        commit(form, before)
        assertFalse(settings().has("credential_ref"))
        assertEquals("", settings().getString("region"))
    }

    @Test fun invalidCredentialCaptureIsUnavailableWhileLiveCorrectionAndFinalLfRemainExact() {
        val before = seedEmbedding()
        val form = configure()
        enter(form, "Credential reference", "synthetic-pasted-password")
        enter(form, "Output dimensions", "unfinished")
        unavailable(capture())
        assertEquals("synthetic-pasted-password", number(form, "Credential reference").text.toString())
        assertTrue(number(form, "Credential reference").isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(form, "Credential reference", "secret://fixture/ref\n")
        enter(form, "Output dimensions", "1")
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedEmbeddingModelPending).form
        assertEquals("secret://fixture/ref\n", pending.input.credentialRef)
        assertEquals("", pending.input.region)
        commit(form, before)
        assertEquals("secret://fixture/ref\n", settings().getString("credential_ref"))
    }

    @Test fun legacyRawEmbeddingCheckpointsKeepTheirExactReaderAndInvalidText() {
        val before = seedEmbedding()
        val target = selection()
        val raw = """{"output_dimensions":"""
        for (version in 1..2) {
            recreate { state ->
                val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, currentId().value,
                    before.baselineIdentity, target.id, null, null, null, null, "", raw, false, false)
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

    @Test fun recreationRetainsInvalidNumericAndSuppliedEmptyRegionOnlyAfterExplicitReview() {
        val before = seedEmbedding()
        val form = configure()
        enter(form, "Output dimensions", "unfinished")
        recreate()
        assertFalse(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val restored = reviewPending()
        assertFalse(supply(restored, "Credential reference").isChecked)
        assertEquals(activity.getString(R.string.embedding_model_not_supplied), number(restored, "Credential reference").hint.toString())
        assertTrue(supply(restored, "Output dimensions").isChecked)
        assertEquals("unfinished", number(restored, "Output dimensions").text.toString())
        assertNull(number(restored, "Output dimensions").hint)
        assertTrue(supply(restored, "Region").isChecked)
        assertEquals("", number(restored, "Region").text.toString())
        assertNull(number(restored, "Region").hint)
        enter(restored, "Output dimensions", "1")
        commit(restored, before)
    }

    @Test fun actualPauseRetiresControlsAndRequiresExplicitResumeReview() {
        val before = seedEmbedding()
        val form = configure()
        fillAll(form)
        val retiredSupply = supply(form, "Output dimensions")
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        controller.pause()
        assertFalse(form.isShowing)
        retiredSupply.performClick()
        retiredCommit.performClick()
        finishWork()
        assertTrue(retiredSupply.isChecked)
        assertFalse(retiredSupply.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedEmbeddingModelPending).form
        assertFalse(pending.wasSaving)
        assertEquals("9007199254740993", pending.input.outputDimensions)
        assertEquals(" \t\n", pending.input.region)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertTrue(ComponentFormRecovery.read(capture()) is ComponentFormRecovery.TypedEmbeddingModelPending)
        controller.resume().visible()
        finishWork()
        assertFalse(form.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val restored = reviewPending()
        assertEquals("secret://fixture/Case:Exact\n", number(restored, "Credential reference").text.toString())
        assertEquals("9007199254740993", number(restored, "Output dimensions").text.toString())
        commit(restored, before)
    }

    @Test fun changedSavedHeadRetainsExactInputReadOnlyUntilExplicitDiscardAndReopen() {
        val before = seedEmbedding()
        val form = configure()
        enter(form, "Output dimensions", "unfinished")
        enter(form, "Credential reference", "secret://fixture/ref\n")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "unfinished", "secret://fixture/ref\n", "Supplied empty text")
        recreate()
        val retained = reviewPending()
        readOnly(retained, "unfinished", "Supplied empty text")
        dismiss(retained)
        assertFalse(button(R.string.component_manage).isEnabled)
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        dismiss(reviewPending())
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun foreignDraftSelectionAndCatalogIdentityNeverGainFreshSaveAuthority() {
        val before = seedEmbedding()
        for (key in listOf("draftId", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, "Output dimensions", "pending value")
            recreate { it.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending value", "Not supplied", "Supplied empty text")
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        for (literal in listOf("Not supplied", "Supplied empty text")) {
            val form = configure()
            enter(form, "Region", literal)
            recreate { it.getBundle(ComponentFormRecovery.KEY)!!.putString("draftId", "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, literal, activity.getString(R.string.embedding_model_not_supplied))
            val label = views(retained.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Region" }
            val group = label.parent as ViewGroup
            val index = group.indexOfChild(label)
            assertEquals(activity.getString(R.string.embedding_model_supplied_value),
                (group.getChildAt(index + 1) as TextView).text.toString())
            assertEquals(literal, (group.getChildAt(index + 2) as TextView).text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, "Output dimensions", "pending value")
        recreate { it.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightSaveFreezesControlsAndCannotReplayAfterItsOriginalAcknowledgment() {
        val before = seedEmbedding()
        val form = configure()
        fillAll(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            commit.performClick(); commit.performClick()
            captions.forEach {
                assertFalse(supply(form, it).isEnabled)
                assertFalse(number(form, it).isEnabled)
            }
            assertFalse(commit.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            supply(form, "Output dimensions").performClick()
            assertTrue(supply(form, "Output dimensions").isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedEmbeddingModelPending).form
                assertTrue(pending.wasSaving)
                assertEquals("9007199254740993", pending.input.outputDimensions)
                release.countDown()
                // Pause already retired the dialog. Fence the admitted write before the first durable read.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val retained = reviewPending()
        readOnly(retained, "secret://fixture/Case:Exact\n", "9007199254740993", " \t\n")
        assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        supply(form, "Output dimensions").performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.revision + 1, saved.revision)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableControls() {
        val before = seedEmbedding()
        val form = configure()
        enter(form, "Output dimensions", "1")
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertTrue(form.isShowing)
        captions.forEach {
            assertFalse(supply(form, it).isEnabled)
            assertFalse(number(form, it).isEnabled)
        }
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "1", "Not supplied", "Supplied empty text")
    }

    @Test fun oversizedRecoveryHasNoPrefixAndControlsHaveNoAutomaticViewStateFallback() {
        val before = seedEmbedding()
        val form = configure()
        captions.forEach {
            assertFalse(number(form, it).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, number(form, it).importantForAutofill)
            assertTrue(number(form, it).imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertFalse(supply(form, it).isSaveEnabled)
        }
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, "Region", raw)
        assertEquals(raw, number(form, "Region").text.toString())
        recreate(::unavailable)
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(minimum, PreviewSelectionForms.settingsText(selection()))
    }
}
