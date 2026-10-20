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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Activity, serial worker and local store in simulation; no deployed or installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DeploymentTargetSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val textCaptions = listOf(R.string.deployment_target_replicas_min, R.string.deployment_target_replicas_max,
        R.string.deployment_target_region, R.string.deployment_target_approval_ticket)
    private val enteredInput = PreviewDeploymentTargetInput("production", "9007199254740993", "9223372036854775808",
        "  declared-region\n", "unverified\nreference")

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        finishWork()
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun control(form: AlertDialog, label: Int): View {
        val caption = views(form.window!!.decorView).filterIsInstance<TextView>()
            .singleOrNull { it.text.toString() == activity.getString(label) }
        assertNotNull("Missing typed deployment caption: " + activity.getString(label), caption)
        assertNotEquals(View.NO_ID, caption!!.labelFor)
        return requireNotNull(form.findViewById(caption.labelFor))
    }
    private fun text(form: AlertDialog, label: Int) = control(form, label) as EditText
    private fun environment(form: AlertDialog) = control(form, R.string.deployment_target_environment) as Button
    private fun supply(form: AlertDialog, label: Int) = views(form.window!!.decorView).filterIsInstance<CheckBox>().single {
        it.text.toString() == activity.getString(R.string.deployment_target_supply, activity.getString(label))
    }
    private fun enter(form: AlertDialog, label: Int, raw: String) {
        if (!supply(form, label).isChecked) supply(form, label).performClick()
        assertTrue(text(form, label).isEnabled)
        text(form, label).setText(raw)
    }
    private fun chooseEnvironment(form: AlertDialog, value: String?) {
        environment(form).performClick()
        val picker = dialog()
        val shown = value ?: activity.getString(R.string.deployment_target_not_supplied)
        val index = (0 until picker.listView.adapter.count).single { picker.listView.adapter.getItem(it).toString() == shown }
        shadowOf(picker).clickOnItem(index)
        await { !picker.isShowing }
        assertTrue(form.isShowing)
        assertEquals(shown, environment(form).text.toString())
    }
    private fun fill(form: AlertDialog) {
        chooseEnvironment(form, enteredInput.environment)
        enter(form, R.string.deployment_target_replicas_min, enteredInput.replicasMin!!)
        enter(form, R.string.deployment_target_replicas_max, enteredInput.replicasMax!!)
        enter(form, R.string.deployment_target_region, enteredInput.region!!)
        enter(form, R.string.deployment_target_approval_ticket, enteredInput.approvalTicket!!)
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
        fail("Timed out waiting for deployment settings state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == "deployment_target" }
    private fun settings(value: LocalSelection = selection()) = PreviewDeploymentTargetSettings.initial(value)
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun assertUnchanged(before: PreviewStoredDraft, bytes: Map<String, List<Byte>>) {
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        assertEquals(bytes, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun saveInitial() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_saved)
        } }
        finishWork()
    }
    private fun select(picker: AlertDialog, match: (String) -> Boolean): AlertDialog {
        val index = (0 until picker.listView.adapter.count).single { match(picker.listView.adapter.getItem(it).toString()) }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun addForm(): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        val actions = select(dialog()) { it.startsWith("${componentTokenForLookup(activity, "deployment_target")} ·") }
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
    private fun seed(): PreviewStoredDraft {
        saveInitial()
        val form = addForm()
        text(form, R.string.component_role).setText("primary")
        text(form, R.string.component_settings).setText("{}")
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        return commit(form)
    }
    private fun dismiss(form: AlertDialog) {
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing }
        finishWork()
    }
    private fun parcel(value: Bundle): Bundle {
        val writer = Parcel.obtain()
        val reader = Parcel.obtain()
        return try {
            writer.writeBundle(value)
            val bytes = writer.marshall()
            reader.unmarshall(bytes, 0, bytes.size); reader.setDataPosition(0)
            requireNotNull(reader.readBundle(javaClass.classLoader))
        } finally { writer.recycle(); reader.recycle() }
    }
    private fun capture(): Bundle = Bundle().also { controller.saveInstanceState(it) }.let(::parcel)
    private fun recreate(editCheckpoint: (Bundle) -> Unit = {}) {
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        editCheckpoint(state)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(parcel(state)).start().resume().visible()
        finishWork()
    }
    private fun reviewPending(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }
    private fun readOnly(form: AlertDialog, vararg values: String) {
        assertTrue(form.isShowing)
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        val shown = views(form.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        values.forEach { assertTrue("Missing retained read-only deployment input", it in shown) }
    }

    @Test fun fiveOptionalFieldsHaveLabeledPrivateControlsWithoutSuppliedDefaults() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        assertEquals(activity.getString(R.string.deployment_target_not_supplied), environment(form).text.toString())
        assertFalse(environment(form).isSaveEnabled)
        assertEquals(4, views(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        for (caption in textCaptions) {
            val input = text(form, caption)
            assertEquals("", input.text.toString())
            assertFalse(input.isEnabled)
            assertFalse(input.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
            assertTrue(input.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertFalse(supply(form, caption).isChecked)
            assertFalse(supply(form, caption).isSaveEnabled)
        }
        assertUnchanged(before, bytes)
        dismiss(form)
    }

    @Test fun addStaysRawAndDoesNotChooseAnEnvironmentOrReplicaCount() {
        saveInitial()
        val before = loaded()
        val bytes = disk()
        val form = addForm()
        assertEquals("{}", text(form, R.string.component_settings).text.toString())
        assertEquals("", text(form, R.string.component_role).text.toString())
        assertFalse(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        assertTrue(views(form.window!!.decorView).filterIsInstance<DeploymentTargetSettingsEditor>().isEmpty())
        dismiss(form)
        assertUnchanged(before, bytes)
    }

    @Test fun explicitEnvironmentChoiceIsCheckedAndClosingThePickerDoesNotEdit() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        environment(form).performClick()
        val picker = dialog()
        assertEquals(0, picker.listView.checkedItemPosition)
        assertEquals(listOf(activity.getString(R.string.deployment_target_not_supplied), "development", "staging", "production"),
            (0 until picker.listView.adapter.count).map { picker.listView.adapter.getItem(it).toString() })
        dismiss(picker)
        assertEquals(activity.getString(R.string.deployment_target_not_supplied), environment(form).text.toString())
        assertUnchanged(before, bytes)
        chooseEnvironment(form, "staging")
        environment(form).performClick()
        val checked = dialog()
        assertEquals(2, checked.listView.checkedItemPosition)
        dismiss(checked)
        commit(form, before)
        assertEquals(PreviewDeploymentTargetInput(environment = "staging"), settings())
    }

    @Test fun fiveExactValuesSaveAndReopenWithIdentityAndUnrelatedDraftDataIntact() {
        val before = seed()
        val original = selection()
        val form = configure()
        fill(form)
        val saved = commit(form, before)
        assertEquals(enteredInput, settings())
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.contextInput, saved.contextInput)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        val bytes = disk()
        recreate()
        assertUnchanged(saved, bytes)
        val reopened = configure()
        assertEquals("production", environment(reopened).text.toString())
        val expected = listOf(enteredInput.replicasMin, enteredInput.replicasMax, enteredInput.region, enteredInput.approvalTicket)
        textCaptions.zip(expected).forEach { (caption, raw) ->
            assertEquals(raw, text(reopened, caption).text.toString())
            assertTrue(supply(reopened, caption).isChecked)
        }
        dismiss(reopened)
    }

    @Test fun explicitZeroAndEmptyReferenceRemainUntilEachSuppliedFieldIsRemoved() {
        val before = seed()
        val form = configure()
        textCaptions.forEach {
            assertEquals(activity.getString(R.string.deployment_target_not_supplied), text(form, it).hint.toString())
        }
        chooseEnvironment(form, "production")
        enter(form, R.string.deployment_target_replicas_min, "0")
        enter(form, R.string.deployment_target_region, "")
        enter(form, R.string.deployment_target_approval_ticket, "")
        for (caption in listOf(R.string.deployment_target_region, R.string.deployment_target_approval_ticket)) {
            assertTrue(supply(form, caption).isChecked)
            assertEquals("", text(form, caption).text.toString())
            assertNull(text(form, caption).hint)
        }
        val saved = commit(form, before)
        assertEquals(PreviewDeploymentTargetInput("production", "0", null, "", ""), settings())
        val clear = configure()
        chooseEnvironment(clear, null)
        for (caption in listOf(R.string.deployment_target_replicas_min, R.string.deployment_target_region,
            R.string.deployment_target_approval_ticket)) {
            assertTrue(supply(clear, caption).isChecked)
            assertNull(text(clear, caption).hint)
            supply(clear, caption).performClick()
            assertFalse(text(clear, caption).isEnabled)
            assertEquals("", text(clear, caption).text.toString())
            assertEquals(activity.getString(R.string.deployment_target_not_supplied), text(clear, caption).hint.toString())
        }
        commit(clear, saved)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        val omitted = configure()
        textCaptions.forEach {
            assertFalse(supply(omitted, it).isChecked)
            assertEquals("", text(omitted, it).text.toString())
            assertEquals(activity.getString(R.string.deployment_target_not_supplied), text(omitted, it).hint.toString())
        }
        dismiss(omitted)
    }

    @Test fun invalidNumbersKeepFieldErrorsIndependentAndCannotReplaceSavedValues() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        enter(form, R.string.deployment_target_replicas_min, "")
        enter(form, R.string.deployment_target_replicas_max, "")
        enter(form, R.string.deployment_target_approval_ticket, "unknown")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        for (caption in textCaptions.take(2)) {
            assertEquals(activity.getString(R.string.deployment_target_number_required), text(form, caption).error.toString())
        }
        assertNull(text(form, R.string.deployment_target_approval_ticket).error)
        assertUnchanged(before, bytes)
        enter(form, R.string.deployment_target_replicas_min, "1,5")
        assertNull(text(form, R.string.deployment_target_replicas_min).error)
        assertNotNull(text(form, R.string.deployment_target_replicas_max).error)
        enter(form, R.string.deployment_target_replicas_max, "0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(activity.getString(R.string.deployment_target_integer_invalid),
            text(form, R.string.deployment_target_replicas_min).error.toString())
        assertEquals(activity.getString(R.string.deployment_target_replicas_max_rule),
            text(form, R.string.deployment_target_replicas_max).error.toString())
        assertFalse(text(form, R.string.deployment_target_replicas_min).error.toString().contains("1,5"))
        assertEquals("1,5", text(form, R.string.deployment_target_replicas_min).text.toString())
        assertUnchanged(before, bytes)
        enter(form, R.string.deployment_target_replicas_min, "1e3")
        enter(form, R.string.deployment_target_replicas_max, "1")
        commit(form, before)
        assertEquals(PreviewDeploymentTargetInput(replicasMin = "1E+3", replicasMax = "1", approvalTicket = "unknown"), settings())
    }

    @Test fun cancelAndUnchangedCommitKeepTheAcknowledgedRevisionAndDisk() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        fill(form)
        dismiss(form)
        assertUnchanged(before, bytes)
        val noop = configure()
        noop.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !noop.isShowing }; finishWork()
        assertUnchanged(before, bytes)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun invalidPendingTextAndPresenceRecoverOnlyAfterReviewWithOldControlsRetired() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        chooseEnvironment(form, "production")
        enter(form, R.string.deployment_target_replicas_min, "unfinished")
        enter(form, R.string.deployment_target_replicas_max, "")
        enter(form, R.string.deployment_target_region, "")
        enter(form, R.string.deployment_target_approval_ticket, "unknown")
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredSupply = supply(form, R.string.deployment_target_region)
        val target = selection()
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedDeploymentTargetPending).form
            assertEquals(PreviewDeploymentTargetInput("production", "unfinished", "", "", "unknown"), pending.input)
            assertEquals(target.id, pending.selectionId)
            assertEquals(target.catalogItemId, pending.catalogItemId)
            assertEquals(target.catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        assertEquals("production", environment(restored).text.toString())
        assertEquals("unfinished", text(restored, R.string.deployment_target_replicas_min).text.toString())
        textCaptions.forEach { assertTrue(supply(restored, it).isChecked) }
        retired.performClick(); retiredSupply.performClick()
        assertFalse(retiredSupply.isEnabled)
        assertTrue(retiredSupply.isChecked)
        assertUnchanged(before, bytes)
        enter(restored, R.string.deployment_target_replicas_min, "0")
        enter(restored, R.string.deployment_target_replicas_max, "1")
        commit(restored, before)
        assertEquals(PreviewDeploymentTargetInput("production", "0", "1", "", "unknown"), settings())
    }

    @Test fun aChangedSavedHeadKeepsOriginalPendingValuesReadOnlyUntilExplicitDiscard() {
        val before = seed()
        val form = configure()
        fill(form)
        finishWork()
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        recreate()
        readOnly(reviewPending(), "production", enteredInput.replicasMin!!, enteredInput.replicasMax!!,
            enteredInput.region!!, enteredInput.approvalTicket!!)
        recreate()
        val retained = reviewPending()
        readOnly(retained, enteredInput.approvalTicket!!)
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

    @Test fun wrongDraftBaselineSelectionAndCatalogPinsCannotAcquireSaveAuthority() {
        val before = seed()
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, R.string.deployment_target_approval_ticket, "pending reference")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "pending reference", activity.getString(R.string.deployment_target_not_supplied))
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, R.string.deployment_target_approval_ticket, "pending reference")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "deployment_mode") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun anInFlightWriteFreezesEveryControlAndRecoveredInputCannotReplayIt() {
        val before = seed()
        val form = configure()
        fill(form)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            retired.performClick(); retired.performClick()
            textCaptions.forEach { assertFalse(text(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
            assertFalse(environment(form).isEnabled)
            assertFalse(retired.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            val latest = dialog()
            environment(form).performClick()
            supply(form, R.string.deployment_target_region).performClick()
            assertSame(latest, dialog())
            assertTrue(supply(form, R.string.deployment_target_region).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedDeploymentTargetPending).form
                assertTrue(pending.wasSaving)
                assertEquals(enteredInput, pending.input)
                release.countDown()
                // Pause retires the dialog before acknowledgment; finish owned work before any store read.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        readOnly(reviewPending(), "production", enteredInput.replicasMin!!, enteredInput.replicasMax!!,
            enteredInput.region!!, enteredInput.approvalTicket!!)
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(enteredInput, settings())
        assertEquals(before.optionalTiers, saved.optionalTiers)
        val bytes = disk()
        retired.performClick(); environment(form).performClick()
        assertUnchanged(saved, bytes)
    }

    @Test fun staleManagedCommitCannotOverwriteAnotherWriterOrReenableTheForm() {
        val before = seed()
        val form = configure()
        enter(form, R.string.deployment_target_replicas_min, "2")
        finishWork()
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        textCaptions.forEach { assertFalse(text(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
        assertFalse(environment(form).isEnabled)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "2", activity.getString(R.string.deployment_target_not_supplied))
    }

    @Test fun actualPauseRetiresTheChoiceAndFormUntilFreshReviewedConfirmation() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        fill(form)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        environment(form).performClick()
        val picker = dialog()
        controller.pause()
        assertFalse(form.isShowing)
        assertFalse(picker.isShowing)
        retired.performClick(); environment(form).performClick()
        shadowOf(picker).clickOnItem(1)
        supply(form, R.string.deployment_target_region).performClick()
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertUnchanged(before, bytes)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedDeploymentTargetPending).form
        assertEquals(enteredInput, pending.input)
        assertFalse(pending.wasSaving)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        assertSame(picker, dialog())
        controller.resume().visible(); finishWork()
        assertSame(picker, dialog())
        val reviewed = reviewPending()
        assertNotSame(form, reviewed)
        assertEquals("production", environment(reviewed).text.toString())
        assertEquals(enteredInput.approvalTicket, text(reviewed, R.string.deployment_target_approval_ticket).text.toString())
        retired.performClick()
        assertUnchanged(before, bytes)
        val saved = commit(reviewed, before)
        retired.performClick(); reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(enteredInput, settings())
    }

    @Test fun oversizedPendingInputKeepsOnlyAnUnavailableMarkerWithoutViewStateFallback() {
        val before = seed()
        val form = configure()
        val raw = "x".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, R.string.deployment_target_approval_ticket, raw)
        assertEquals(raw, text(form, R.string.deployment_target_approval_ticket).text.toString())
        textCaptions.forEach { assertFalse(text(form, it).isSaveEnabled); assertFalse(supply(form, it).isSaveEnabled) }
        assertFalse(environment(form).isSaveEnabled)
        assertFalse(views(form.window!!.decorView).filterIsInstance<DeploymentTargetSettingsEditor>().single().isSaveEnabled)
        recreate { state ->
            val marker = requireNotNull(parcel(state).getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), marker.keySet())
            assertTrue(marker.getBoolean("unavailable"))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        val fresh = configure()
        assertEquals("", text(fresh, R.string.deployment_target_approval_ticket).text.toString())
        assertFalse(supply(fresh, R.string.deployment_target_approval_ticket).isChecked)
        dismiss(fresh)
    }

    @Test fun legacyRawRecoveryKeepsItsReaderAndFreshConfigureReturnsToTypedControls() {
        seed()
        for (version in 1..2) {
            val before = loaded()
            val target = selection()
            val form = configure()
            enter(form, R.string.deployment_target_replicas_min, "typed input replaced by legacy fixture")
            val raw = """{"replicas_min":"""
            val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, currentId().value,
                before.baselineIdentity, target.id, null, null, null, null, "", raw, false, false)
            recreate { state -> state.putBundle(ComponentFormRecovery.KEY,
                requireNotNull(legacy.bundle()).apply { putInt("version", version) }) }
            val restored = reviewPending()
            assertEquals(raw, text(restored, R.string.component_settings).text.toString())
            assertTrue(views(restored.window!!.decorView).filterIsInstance<DeploymentTargetSettingsEditor>().isEmpty())
            val exact = if (version == 1) "9007199254740993" else "9007199254740994"
            text(restored, R.string.component_settings).setText("{\"replicas_min\":" + exact + "}")
            commit(restored, before)
            assertEquals(PreviewDeploymentTargetInput(replicasMin = exact), settings())
            val typed = configure()
            assertEquals(exact, text(typed, R.string.deployment_target_replicas_min).text.toString())
            assertEquals(1, views(typed.window!!.decorView).filterIsInstance<DeploymentTargetSettingsEditor>().size)
            dismiss(typed)
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
        val before = seed()
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
            assertTrue(control.width >= (48 * density).toInt())
            assertTrue(control.height >= (48 * density).toInt())
            reachable(control, root)
        }
        for (caption in textCaptions) {
            val input = text(form, caption)
            val layout = requireNotNull(input.layout)
            for (line in 0 until layout.lineCount) reachable(input, root,
                Rect(0, input.totalPaddingTop + layout.getLineTop(line), input.width,
                    input.totalPaddingTop + layout.getLineBottom(line)))
        }
        for (label in listOf(R.string.deployment_target_editor_guidance, R.string.deployment_target_recovery_guidance,
            R.string.component_deployment_target_guidance, R.string.component_core_input_guidance,
            R.string.component_operational_guidance_unavailable)) {
            val explanation = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
            val layout = requireNotNull(explanation.layout)
            for (line in listOf(0, layout.lineCount - 1)) reachable(explanation, root,
                Rect(0, explanation.totalPaddingTop + layout.getLineTop(line), explanation.width,
                    explanation.totalPaddingTop + layout.getLineBottom(line)))
        }
        for (view in views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }) {
            val layout = requireNotNull(view.layout)
            assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals("Visible deployment text must not be ellipsized: " +
                view.javaClass.simpleName + " id=" + view.id + " text=" + view.text, 0, layout.getEllipsisCount(line))
        }
        assertEquals(before.revision + 1, commit(form, before).revision)
        assertEquals(enteredInput, settings())
    }

    private fun reachable(control: View, root: View, target: Rect = Rect(0, 0, control.width, control.height)) {
        control.requestRectangleOnScreen(Rect(target), true)
        val bounds = Rect(target)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            val viewport = Rect().also(parent::getDrawingRect)
            assertTrue("Deployment control must remain reachable: " + control.javaClass.simpleName +
                " bounds=" + bounds + " viewport=" + viewport, viewport.contains(bounds))
            child = parent
        }
    }
}
