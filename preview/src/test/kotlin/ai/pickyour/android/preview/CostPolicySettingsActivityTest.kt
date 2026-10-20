package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
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

/** Real platform controls and canonical local storage in simulation, without installed-device claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CostPolicySettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val captions = listOf(R.string.cost_policy_monthly, R.string.cost_policy_per_request, R.string.cost_policy_notify)

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
    private fun control(form: AlertDialog, label: Int): View {
        val caption = views(form.window!!.decorView).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(label)
        }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return requireNotNull(form.findViewById(caption.labelFor))
    }
    private fun text(form: AlertDialog, label: Int) = control(form, label) as EditText
    private fun cap(form: AlertDialog) = control(form, R.string.cost_policy_hard_cap) as Button
    private fun supply(form: AlertDialog, label: Int) = views(form.window!!.decorView).filterIsInstance<CheckBox>().single {
        it.text.toString() == activity.getString(R.string.cost_policy_supply, activity.getString(label))
    }
    private fun enter(form: AlertDialog, label: Int, value: String) {
        if (!supply(form, label).isChecked) supply(form, label).performClick()
        assertTrue(text(form, label).isEnabled)
        text(form, label).setText(value)
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
        fail("Timed out waiting for explicit cost-policy editor state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == PreviewCostPolicySettings.KIND }
    private fun settings() = PreviewCostPolicySettings.initial(selection())
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
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
        button(R.string.component_choose_catalog).performClick()
        val actions = select(dialog()) { it.startsWith(componentTokenForLookup(activity, "cost_policy") + " ·") }
        return select(actions) { it == activity.getString(R.string.component_add) }
    }
    private fun configure(): AlertDialog {
        val target = selection()
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · " + componentTokenForLookup(activity, target.id)) }
        return select(actions) { it == activity.getString(R.string.component_configure) }
    }
    private fun commit(form: AlertDialog, before: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
        return loaded()
    }
    private fun seed(raw: String = "{}"): PreviewStoredDraft {
        saveInitial()
        val form = addForm()
        text(form, R.string.component_role).setText("primary")
        text(form, R.string.component_settings).setText(raw)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        return commit(form)
    }
    private fun chooseCap(form: AlertDialog, value: Boolean?) {
        cap(form).performClick()
        val picker = dialog()
        val expected = listOf(R.string.cost_policy_not_supplied, R.string.cost_policy_no, R.string.cost_policy_yes)
            .map(activity::getString)
        assertEquals(expected, (0 until picker.listView.adapter.count).map { picker.listView.adapter.getItem(it).toString() })
        assertEquals(expected.indexOf(cap(form).text.toString()), picker.listView.checkedItemPosition)
        val index = listOf<Boolean?>(null, false, true).indexOf(value)
        shadowOf(picker).clickOnItem(index)
        await { !picker.isShowing }
        assertEquals(expected[index], cap(form).text.toString())
    }
    private fun dismiss(form: AlertDialog) {
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing }
        finishWork()
    }
    private fun capture() = Bundle().also { controller.saveInstanceState(it) }
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
    private fun recreate(afterCheckpoint: (Bundle) -> Unit = {}) {
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        afterCheckpoint(state)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(parcel(state)).start().resume().visible()
        finishWork()
    }
    private fun reviewPending(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }
    private fun readOnly(form: AlertDialog, vararg raw: String) {
        assertTrue(views(form.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(form.window!!.decorView).filterIsInstance<CheckBox>().isEmpty())
        assertNotEquals(View.VISIBLE, form.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        for (value in raw) assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == value && it.isTextSelectable
        })
    }
    private fun fill(form: AlertDialog) {
        enter(form, R.string.cost_policy_monthly, "900719925474099312345678.000000000001")
        enter(form, R.string.cost_policy_per_request, "0.100000000001")
        enter(form, R.string.cost_policy_notify, "99.123456789123456789")
        chooseCap(form, false)
    }
    private fun assertUnchanged(before: PreviewStoredDraft, bytes: Map<String, List<Byte>>) {
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        assertEquals(bytes, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun freshEditorKeepsEveryFieldAbsentAndUnchangedConfirmationCreatesNoRevision() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        assertEquals(3, views(form.window!!.decorView).filterIsInstance<EditText>().size)
        for (caption in captions) {
            assertFalse(supply(form, caption).isChecked)
            assertFalse(text(form, caption).isEnabled)
            assertEquals("", text(form, caption).text.toString())
            assertFalse(text(form, caption).isSaveEnabled)
            assertFalse(supply(form, caption).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, text(form, caption).importantForAutofill)
            assertTrue(text(form, caption).imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        assertEquals(activity.getString(R.string.cost_policy_not_supplied), cap(form).text.toString())
        chooseCap(form, null)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_noop)
        } }
        assertUnchanged(before, bytes)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun wideExactMoneyStringsAndPercentageSurviveSaveReopenWithoutChangingLineage() {
        val before = seed()
        val selected = selection()
        val form = configure()
        val monthly = "9".repeat(160) + ".123456789012\n"
        enter(form, R.string.cost_policy_monthly, monthly)
        enter(form, R.string.cost_policy_per_request, "0.000000000001")
        enter(form, R.string.cost_policy_notify, "99.123456789123456789")
        chooseCap(form, false)
        val saved = commit(form, before)
        val expected = PreviewCostPolicyInput(monthly, "0.000000000001", false, "99.123456789123456789")
        assertEquals(expected, settings())
        assertEquals(selected.id, selection().id)
        assertEquals(selected.catalogItemId, selection().catalogItemId)
        assertEquals(selected.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(selected.role, selection().role)
        assertEquals(selected.enabled, selection().enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.contextInput, saved.contextInput)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        assertEquals(before.components.links, saved.components.links)
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(before.history.size + 1, saved.history.size)
        val bytes = disk()
        recreate()
        val reopened = configure()
        assertEquals(monthly, text(reopened, R.string.cost_policy_monthly).text.toString())
        assertEquals(expected.perRequestBudgetAmount, text(reopened, R.string.cost_policy_per_request).text.toString())
        assertEquals(expected.notifyAtPct, text(reopened, R.string.cost_policy_notify).text.toString())
        assertEquals(activity.getString(R.string.cost_policy_no), cap(reopened).text.toString())
        dismiss(reopened)
        assertUnchanged(saved, bytes)
        assertEquals(expected, settings())
    }

    @Test fun omittedFalseTrueAndZeroStayDistinctAndOmissionRemovesOnlyChosenFields() {
        var before = seed()
        var form = configure()
        enter(form, R.string.cost_policy_monthly, "0")
        enter(form, R.string.cost_policy_per_request, "0.00")
        enter(form, R.string.cost_policy_notify, "0")
        chooseCap(form, false)
        before = commit(form, before)
        assertEquals(PreviewCostPolicyInput("0", "0.00", false, "0"), settings())
        form = configure()
        chooseCap(form, true)
        before = commit(form, before)
        assertEquals(PreviewCostPolicyInput("0", "0.00", true, "0"), settings())
        form = configure()
        supply(form, R.string.cost_policy_per_request).performClick()
        assertEquals("", text(form, R.string.cost_policy_per_request).text.toString())
        assertFalse(text(form, R.string.cost_policy_per_request).isEnabled)
        before = commit(form, before)
        assertEquals(PreviewCostPolicyInput("0", null, true, "0"), settings())
        form = configure()
        chooseCap(form, null)
        supply(form, R.string.cost_policy_monthly).performClick()
        supply(form, R.string.cost_policy_notify).performClick()
        commit(form, before)
        assertEquals(PreviewCostPolicyInput(), settings())
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun independentInvalidFieldsRetainExactInputAndDiskUntilCorrectionOrExplicitOmission() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        enter(form, R.string.cost_policy_monthly, "01.00")
        enter(form, R.string.cost_policy_per_request, "€1,25")
        enter(form, R.string.cost_policy_notify, "100.000000000000000001")
        chooseCap(form, true)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && captions.all { text(form, it).error != null } }
        assertEquals("01.00", text(form, R.string.cost_policy_monthly).text.toString())
        assertEquals("€1,25", text(form, R.string.cost_policy_per_request).text.toString())
        assertEquals("100.000000000000000001", text(form, R.string.cost_policy_notify).text.toString())
        assertUnchanged(before, bytes)
        enter(form, R.string.cost_policy_monthly, "1.00")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && text(form, R.string.cost_policy_per_request).error != null }
        assertNull(text(form, R.string.cost_policy_monthly).error)
        assertUnchanged(before, bytes)
        supply(form, R.string.cost_policy_per_request).performClick()
        enter(form, R.string.cost_policy_notify, "100")
        val saved = commit(form, before)
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(PreviewCostPolicyInput("1.00", null, true, "100"), settings())
    }

    @Test fun suppliedEmptyTextRequiresCorrectionOrOmissionWithoutSavingSiblingValues() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        for (caption in captions) enter(form, caption, "")
        chooseCap(form, false)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && captions.all { text(form, it).error != null } }
        assertUnchanged(before, bytes)
        for (caption in captions) assertEquals(activity.getString(R.string.cost_policy_value_required), text(form, caption).error.toString())
        for (caption in captions) supply(form, caption).performClick()
        commit(form, before)
        assertEquals(PreviewCostPolicyInput(enforceHardCap = false), settings())
    }

    @Test fun canceledEditorAndRetainedChoiceCallbacksCannotReopenOrWrite() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        fill(form)
        val retiredCommit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val retiredCap = cap(form)
        retiredCap.performClick()
        val picker = dialog()
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !picker.isShowing }
        dismiss(form)
        val latest = dialog()
        retiredCommit.performClick()
        retiredCap.performClick()
        shadowOf(picker).clickOnItem(2)
        supply(form, R.string.cost_policy_monthly).performClick()
        assertUnchanged(before, bytes)
        assertSame(latest, dialog())
        assertSame(ComponentFormRecovery.None, ComponentFormRecovery.read(capture()))
        val reopened = configure()
        captions.forEach { assertFalse(supply(reopened, it).isChecked) }
        assertEquals(activity.getString(R.string.cost_policy_not_supplied), cap(reopened).text.toString())
        dismiss(reopened)
    }

    @Test fun invalidTypedInputSurvivesActualParcelButRequiresANewExplicitCommit() {
        val before = seed()
        val form = configure()
        enter(form, R.string.cost_policy_monthly, "1,5")
        enter(form, R.string.cost_policy_per_request, "")
        enter(form, R.string.cost_policy_notify, "unfinished\n")
        chooseCap(form, false)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val selected = selection()
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedCostPolicyPending).form
            assertEquals(PreviewCostPolicyInput("1,5", "", false, "unfinished\n"), pending.input)
            assertEquals(before.baselineIdentity, pending.baseline)
            assertEquals(currentId().value, pending.draftId)
            assertEquals(selected.id, pending.selectionId)
            assertEquals(selected.catalogItemId, pending.catalogItemId)
            assertEquals(selected.catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        captions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals("1,5", text(restored, R.string.cost_policy_monthly).text.toString())
        assertEquals("", text(restored, R.string.cost_policy_per_request).text.toString())
        assertEquals("unfinished\n", text(restored, R.string.cost_policy_notify).text.toString())
        assertEquals(activity.getString(R.string.cost_policy_no), cap(restored).text.toString())
        retired.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        enter(restored, R.string.cost_policy_monthly, "1.00")
        enter(restored, R.string.cost_policy_per_request, "0.000000000001")
        enter(restored, R.string.cost_policy_notify, "100")
        val saved = commit(restored, before)
        assertEquals(PreviewCostPolicyInput("1.00", "0.000000000001", false, "100"), settings())
        retired.performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun newerSavedHeadRejectsManagedCommitAndKeepsAllRecoveredInputReadOnly() {
        val before = seed()
        val form = configure()
        fill(form)
        finishWork()
        val newer = (adapter().save(before, before.fields.copy(title = "another writer")) as PreviewDraftSave.Saved).record
        val bytes = disk()
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        retired.performClick(); finishWork()
        assertTrue(form.isShowing)
        captions.forEach {
            assertFalse(text(form, it).isEnabled)
            assertFalse(supply(form, it).isEnabled)
        }
        assertFalse(cap(form).isEnabled)
        assertFalse(retired.isEnabled)
        retired.performClick()
        assertUnchanged(newer, bytes)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        readOnly(reviewPending(), "900719925474099312345678.000000000001",
            "0.100000000001", "99.123456789123456789", activity.getString(R.string.cost_policy_no))
        retired.performClick()
        assertUnchanged(newer, bytes)
    }

    @Test fun differentDraftSelectionAndCatalogPinsNeverAcquireCurrentSaveAuthority() {
        val before = seed()
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, R.string.cost_policy_monthly, "exact pending text")
            chooseCap(form, true)
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "exact pending text", activity.getString(R.string.cost_policy_yes),
                activity.getString(R.string.cost_policy_not_supplied))
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, R.string.cost_policy_notify, "pending")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightTypedCostWriteFreezesEveryControlAndCannotReplayAfterAcknowledgment() {
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
            captions.forEach {
                assertFalse(text(form, it).isEnabled)
                assertFalse(supply(form, it).isEnabled)
            }
            assertFalse(cap(form).isEnabled)
            assertFalse(retired.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            val latest = dialog()
            cap(form).performClick()
            supply(form, R.string.cost_policy_monthly).performClick()
            assertSame(latest, dialog())
            assertTrue(supply(form, R.string.cost_policy_monthly).isChecked)
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedCostPolicyPending).form
                assertTrue(pending.wasSaving)
                assertEquals(false, pending.input.enforceHardCap)
                assertEquals("900719925474099312345678.000000000001", pending.input.monthlyBudgetAmount)
                release.countDown()
                // Pause retires the form before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        readOnly(reviewPending(), "900719925474099312345678.000000000001", "0.100000000001",
            "99.123456789123456789", activity.getString(R.string.cost_policy_no))
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(PreviewCostPolicyInput("900719925474099312345678.000000000001",
            "0.100000000001", false, "99.123456789123456789"), settings())
        assertEquals(before.optionalTiers, saved.optionalTiers)
        val bytes = disk()
        retired.performClick()
        cap(form).performClick()
        assertUnchanged(saved, bytes)
    }

    @Test fun actualPauseRetiresChoiceAndEditControlsUntilFreshReviewedConfirmation() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        fill(form)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        cap(form).performClick()
        val picker = dialog()
        controller.pause()
        assertFalse(form.isShowing)
        assertFalse(picker.isShowing)
        retired.performClick()
        cap(form).performClick()
        shadowOf(picker).clickOnItem(2)
        supply(form, R.string.cost_policy_monthly).performClick()
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertUnchanged(before, bytes)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedCostPolicyPending).form
        assertEquals(false, pending.input.enforceHardCap)
        assertEquals("900719925474099312345678.000000000001", pending.input.monthlyBudgetAmount)
        assertFalse(pending.wasSaving)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        assertSame(picker, dialog())
        controller.resume().visible()
        finishWork()
        assertSame(picker, dialog())
        val reviewed = reviewPending()
        assertNotSame(form, reviewed)
        assertEquals(pending.input.monthlyBudgetAmount, text(reviewed, R.string.cost_policy_monthly).text.toString())
        assertEquals(pending.input.perRequestBudgetAmount, text(reviewed, R.string.cost_policy_per_request).text.toString())
        assertEquals(pending.input.notifyAtPct, text(reviewed, R.string.cost_policy_notify).text.toString())
        assertEquals(activity.getString(R.string.cost_policy_no), cap(reviewed).text.toString())
        retired.performClick()
        assertUnchanged(before, bytes)
        val saved = commit(reviewed, before)
        assertEquals(before.revision + 1, saved.revision)
        retired.performClick()
        reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun oversizedTypedRecoveryKeepsOnlyTheMarkerWithoutAutomaticViewStateFallback() {
        val before = seed()
        val form = configure()
        val raw = "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1)
        enter(form, R.string.cost_policy_monthly, raw)
        captions.forEach {
            assertFalse(text(form, it).isSaveEnabled)
            assertFalse(supply(form, it).isSaveEnabled)
        }
        assertFalse(cap(form).isSaveEnabled)
        assertFalse(views(form.window!!.decorView).filterIsInstance<CostPolicySettingsEditor>().single().isSaveEnabled)
        recreate { state ->
            val marker = requireNotNull(parcel(state).getBundle(ComponentFormRecovery.KEY))
            assertEquals(setOf("version", "unavailable"), marker.keySet())
            assertTrue(marker.getBoolean("unavailable"))
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(parcel(state)))
        }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        val fresh = configure()
        assertEquals("", text(fresh, R.string.cost_policy_monthly).text.toString())
        assertFalse(supply(fresh, R.string.cost_policy_monthly).isChecked)
        dismiss(fresh)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawConfigureRecoveryRemainsExplicitAndFreshConfigureUsesTypedControls() {
        seed()
        for (version in 1..2) {
            val before = loaded()
            val target = selection()
            val form = configure()
            enter(form, R.string.cost_policy_monthly, "typed input replaced by retained legacy fixture")
            val raw = """{"monthly_budget_amount":"unfinished"""
            val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, currentId().value,
                before.baselineIdentity, target.id, null, null, null, null, "", raw, false, false)
            recreate { state ->
                state.putBundle(ComponentFormRecovery.KEY, requireNotNull(legacy.bundle()).apply { putInt("version", version) })
            }
            val restored = reviewPending()
            assertEquals(raw, text(restored, R.string.component_settings).text.toString())
            assertTrue(views(restored.window!!.decorView).filterIsInstance<CostPolicySettingsEditor>().isEmpty())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            val exact = if (version == 1) "1.000000000001" else "2.000000000002"
            text(restored, R.string.component_settings).setText("{\"monthly_budget_amount\":\"" + exact + "\"}")
            commit(restored, before)
            assertEquals(PreviewCostPolicyInput(monthlyBudgetAmount = exact), settings())
            val typed = configure()
            assertEquals(exact, text(typed, R.string.cost_policy_monthly).text.toString())
            assertEquals(1, views(typed.window!!.decorView).filterIsInstance<CostPolicySettingsEditor>().size)
            dismiss(typed)
        }
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsTypedFieldsGuidanceAndConfirmationReachable() {
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
        for (caption in captions) {
            val input = text(form, caption)
            val layout = requireNotNull(input.layout)
            for (line in 0 until layout.lineCount) reachable(input, root,
                Rect(0, input.totalPaddingTop + layout.getLineTop(line), input.width,
                    input.totalPaddingTop + layout.getLineBottom(line)))
        }
        for (label in listOf(R.string.cost_policy_editor_guidance, R.string.cost_policy_recovery_guidance,
            R.string.component_cost_policy_guidance, R.string.component_core_input_guidance,
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
            for (line in 0 until layout.lineCount) assertEquals(
                "Visible cost-policy text must not be ellipsized: " + view.javaClass.simpleName +
                    " id=" + view.id + " text=" + view.text,
                0, layout.getEllipsisCount(line))
        }
        assertEquals(before.revision + 1, commit(form, before).revision)
        assertEquals(PreviewCostPolicyInput("900719925474099312345678.000000000001",
            "0.100000000001", false, "99.123456789123456789"), settings())
    }

    private fun reachable(control: View, root: View, target: Rect = Rect(0, 0, control.width, control.height)) {
        control.requestRectangleOnScreen(Rect(target), true)
        val bounds = Rect(target)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            val viewport = Rect().also(parent::getDrawingRect)
            assertTrue("Cost-policy control must remain reachable: " + control.javaClass.simpleName +
                " bounds=" + bounds + " viewport=" + viewport, viewport.contains(bounds))
            child = parent
        }
    }
}
