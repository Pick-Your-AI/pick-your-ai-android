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
class MonitoringStackSettingsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val captions = listOf(R.string.monitoring_stack_sample, R.string.monitoring_stack_retention,
        R.string.monitoring_stack_credential)
    private val reference = "secret://fixture/monitor\n"

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
    private fun supply(form: AlertDialog, label: Int) = views(form.window!!.decorView).filterIsInstance<CheckBox>().single {
        it.text.toString() == activity.getString(R.string.monitoring_stack_supply, activity.getString(label))
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
        fail("Timed out waiting for explicit monitoring-stack editor state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun selection() = loaded().components.selections.single { it.kind == PreviewMonitoringStackSettings.KIND }
    private fun settings() = PreviewMonitoringStackSettings.initial(selection())
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
        val actions = select(dialog()) { it.startsWith(componentTokenForLookup(activity, "monitoring_stack") + " ·") }
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
        enter(form, R.string.monitoring_stack_sample, "0.999999999999999999")
        enter(form, R.string.monitoring_stack_retention, "9223372036854775808")
        enter(form, R.string.monitoring_stack_credential, reference)
    }
    private fun filled() = PreviewMonitoringStackInput("0.999999999999999999", "9223372036854775808", reference)
    private fun fixedMarker(state: Bundle) {
        val read = parcel(state)
        val marker = requireNotNull(read.getBundle(ComponentFormRecovery.KEY))
        assertEquals(setOf("version", "unavailable"), marker.keySet())
        assertTrue(marker.getBoolean("unavailable"))
        assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(read))
    }
    private fun assertUnchanged(before: PreviewStoredDraft, bytes: Map<String, List<Byte>>) {
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        assertEquals(bytes, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun freshEditorKeepsThreeFieldsAbsentAndUnchangedConfirmationCreatesNoRevision() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        assertEquals(3, views(form.window!!.decorView).filterIsInstance<EditText>().size)
        for (caption in captions) {
            assertFalse(supply(form, caption).isChecked)
            assertFalse(text(form, caption).isEnabled)
            assertEquals("", text(form, caption).text.toString())
            assertEquals(activity.getString(R.string.monitoring_stack_not_supplied), text(form, caption).hint.toString())
            assertFalse(text(form, caption).isSaveEnabled)
            assertFalse(supply(form, caption).isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, text(form, caption).importantForAutofill)
            assertTrue(text(form, caption).imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_noop)
        } }
        assertUnchanged(before, bytes)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun exactFractionWideDaysAndReferenceSaveAndReopenWithoutChangingOtherInputs() {
        val requests = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.workload_requests) }
        activity.findViewById<EditText>(requests.labelFor).setText("17")
        val before = seed()
        val original = selection()
        val form = configure()
        fill(form)
        val saved = commit(form, before)
        assertEquals(filled(), settings())
        assertEquals("{\"sample_rate\":0.999999999999999999,\"retention_days\":9223372036854775808,\"credential_ref\":\"secret://fixture/monitor\\n\"}",
            PreviewSelectionForms.settingsText(selection()))
        assertEquals(original.id, selection().id)
        assertEquals(original.catalogItemId, selection().catalogItemId)
        assertEquals(original.catalogItemVersion, selection().catalogItemVersion)
        assertEquals(original.role, selection().role)
        assertEquals(original.enabled, selection().enabled)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.contextInput, saved.contextInput)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        assertEquals(before.components.links, saved.components.links)
        val bytes = disk()
        recreate()
        assertUnchanged(saved, bytes)
        val reopened = configure()
        assertEquals(listOf(filled().sampleRate, filled().retentionDays, reference),
            captions.map { text(reopened, it).text.toString() })
        captions.forEach { assertTrue(supply(reopened, it).isChecked); assertNull(text(reopened, it).hint) }
        assertEquals(0, text(reopened, R.string.monitoring_stack_credential).autoLinkMask)
        assertTrue(text(reopened, R.string.monitoring_stack_credential).urls.isEmpty())
        dismiss(reopened)
        assertUnchanged(saved, bytes)
    }

    @Test fun zeroAndUntouchedAdmittedNodesRemainDistinctFromIndependentOmission() {
        val before = seed("""{"sample_rate":0.00,"retention_days":0.000,"credential_ref":"secret://fixture/monitor\n"}""")
        val original = PreviewSelectionForms.settingsText(selection())
        val initial = settings()
        val unchanged = configure()
        unchanged.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !unchanged.isShowing }
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(original, PreviewSelectionForms.settingsText(selection()))
        val form = configure()
        supply(form, R.string.monitoring_stack_retention).performClick()
        assertEquals("", text(form, R.string.monitoring_stack_retention).text.toString())
        assertEquals(activity.getString(R.string.monitoring_stack_not_supplied), text(form, R.string.monitoring_stack_retention).hint.toString())
        val saved = commit(form, before)
        assertEquals(initial.copy(retentionDays = null), settings())
        val reopened = configure()
        assertFalse(supply(reopened, R.string.monitoring_stack_retention).isChecked)
        enter(reopened, R.string.monitoring_stack_retention, "0")
        assertNull(text(reopened, R.string.monitoring_stack_retention).hint)
        commit(reopened, saved)
        assertEquals(initial.copy(retentionDays = "0"), settings())
    }

    @Test fun independentInvalidFieldsKeepExactTextAndDiskUntilEveryValueIsCorrected() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        val invalid = listOf("1.000000000000000001", "1,5", "synthetic-private-password")
        captions.zip(invalid).forEach { (caption, value) -> enter(form, caption, value) }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && captions.all { text(form, it).error != null } }
        assertEquals(invalid, captions.map { text(form, it).text.toString() })
        for (caption in captions) assertFalse(text(form, caption).error.toString().contains("synthetic-private-password"))
        assertUnchanged(before, bytes)
        enter(form, R.string.monitoring_stack_sample, "1")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && text(form, R.string.monitoring_stack_retention).error != null }
        assertNull(text(form, R.string.monitoring_stack_sample).error)
        assertUnchanged(before, bytes)
        enter(form, R.string.monitoring_stack_retention, "0")
        supply(form, R.string.monitoring_stack_credential).performClick()
        commit(form, before)
        assertEquals(PreviewMonitoringStackInput("1", "0", null), settings())
    }

    @Test fun suppliedEmptyInputHasNoAbsentHintAndNeedsCorrectionOrExplicitOmission() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        captions.forEach { enter(form, it, ""); assertNull(text(form, it).hint) }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && captions.all { text(form, it).error != null } }
        for (caption in captions.take(2))
            assertEquals(activity.getString(R.string.monitoring_stack_required), text(form, caption).error.toString())
        assertEquals(activity.getString(R.string.monitoring_stack_reference_invalid), text(form, R.string.monitoring_stack_credential).error.toString())
        assertUnchanged(before, bytes)
        enter(form, R.string.monitoring_stack_sample, "0")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && text(form, R.string.monitoring_stack_retention).error != null }
        assertUnchanged(before, bytes)
        captions.drop(1).forEach {
            supply(form, it).performClick()
            assertEquals(activity.getString(R.string.monitoring_stack_not_supplied), text(form, it).hint.toString())
        }
        commit(form, before)
        assertEquals(PreviewMonitoringStackInput(sampleRate = "0"), settings())
    }

    @Test fun canceledAndRetainedControlsCannotReopenOrWriteAndFreshInputStartsAbsent() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        fill(form)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        dismiss(form)
        val latest = dialog()
        retired.performClick()
        captions.forEach { supply(form, it).performClick() }
        assertUnchanged(before, bytes)
        assertSame(latest, dialog())
        assertSame(ComponentFormRecovery.None, ComponentFormRecovery.read(capture()))
        val fresh = configure()
        captions.forEach { assertFalse(supply(fresh, it).isChecked); assertEquals("", text(fresh, it).text.toString()) }
        dismiss(fresh)
        assertUnchanged(before, bytes)
    }

    @Test fun invalidNumericInputAndAllowedReferenceRecoverWithOriginalPinsAndNewConsent() {
        val before = seed()
        val form = configure()
        val raw = listOf("unfinished\n", "1,5", reference)
        captions.zip(raw).forEach { (caption, value) -> enter(form, caption, value) }
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        val target = selection()
        recreate { state ->
            val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedMonitoringStackPending).form
            assertEquals(PreviewMonitoringStackInput(raw[0], raw[1], raw[2]), pending.input)
            assertEquals(before.baselineIdentity, pending.baseline)
            assertEquals(currentId().value, pending.draftId)
            assertEquals(target.id, pending.selectionId)
            assertEquals(target.catalogItemId, pending.catalogItemId)
            assertEquals(target.catalogItemVersion, pending.catalogItemVersion)
            assertFalse(pending.wasSaving)
        }
        assertFalse(form.isShowing)
        assertFalse(button(R.string.component_manage).isEnabled)
        val restored = reviewPending()
        captions.forEach { assertTrue(supply(restored, it).isChecked) }
        assertEquals(raw, captions.map { text(restored, it).text.toString() })
        retired.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        fill(restored)
        val saved = commit(restored, before)
        assertEquals(filled(), settings())
        retired.performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun pastedCredentialIsExcludedFromCaptureWhileTheLiveFieldCanBeCorrected() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        enter(form, R.string.monitoring_stack_sample, "0.25")
        enter(form, R.string.monitoring_stack_credential, "synthetic-private-password")
        fixedMarker(capture())
        assertEquals("synthetic-private-password", text(form, R.string.monitoring_stack_credential).text.toString())
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { text(form, R.string.monitoring_stack_credential).error != null && form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled }
        assertFalse(text(form, R.string.monitoring_stack_credential).error.toString().contains("synthetic-private-password"))
        assertUnchanged(before, bytes)
        enter(form, R.string.monitoring_stack_credential, reference)
        val recovered = (ComponentFormRecovery.read(parcel(capture())) as ComponentFormRecovery.TypedMonitoringStackPending).form
        assertEquals(reference, recovered.input.credentialRef)
        commit(form, before)
        assertEquals(PreviewMonitoringStackInput("0.25", null, reference), settings())
    }

    @Test fun invalidCredentialWithInvalidNumbersRecreatesOnlyAnUnavailableMarker() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        captions.zip(listOf("unknown", "1,5", "synthetic-private-password")).forEach { (caption, value) -> enter(form, caption, value) }
        captions.forEach { assertFalse(text(form, it).isSaveEnabled); assertFalse(supply(form, it).isSaveEnabled) }
        assertFalse(views(form.window!!.decorView).filterIsInstance<MonitoringStackSettingsEditor>().single().isSaveEnabled)
        recreate(::fixedMarker)
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertFalse(views().filterIsInstance<TextView>().any { it.text.toString().contains("synthetic-private-password") })
        assertUnchanged(before, bytes)
        button(R.string.component_recovery_discard).performClick()
        val fresh = configure()
        captions.forEach { assertFalse(supply(fresh, it).isChecked); assertEquals("", text(fresh, it).text.toString()) }
        dismiss(fresh)
        assertUnchanged(before, bytes)
    }

    @Test fun newerSavedHeadRejectsCommitAndKeepsAllRecoveredInputReadOnly() {
        val before = seed()
        val form = configure()
        fill(form)
        finishWork()
        val newer = (adapter().save(before, before.fields.copy(title = "another writer")) as PreviewDraftSave.Saved).record
        val bytes = disk()
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        retired.performClick(); finishWork()
        assertTrue(form.isShowing)
        captions.forEach { assertFalse(text(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
        assertFalse(retired.isEnabled)
        retired.performClick()
        assertUnchanged(newer, bytes)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
        recreate()
        val read = reviewPending()
        readOnly(read, "0.999999999999999999", "9223372036854775808", reference)
        val shown = views(read.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == reference }
        assertEquals(0, shown.autoLinkMask)
        assertTrue(shown.urls.isEmpty())
        retired.performClick()
        assertUnchanged(newer, bytes)
    }

    @Test fun differentDraftSelectionAndCatalogPinsCannotAcquireCurrentSaveAuthority() {
        val before = seed()
        for (key in listOf("draftId", "baseline", "selectionId", "catalogItemId", "catalogItemVersion")) {
            val form = configure()
            enter(form, R.string.monitoring_stack_sample, "exact pending text")
            recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString(key, "other_fixture_identity") }
            val retained = reviewPending()
            readOnly(retained, "exact pending text")
            assertTrue(views(retained.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.monitoring_stack_not_supplied)
            })
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            dismiss(retained)
            button(R.string.component_recovery_discard).performClick()
        }
        val form = configure()
        enter(form, R.string.monitoring_stack_retention, "pending")
        recreate { state -> state.getBundle(ComponentFormRecovery.KEY)!!.putString("kind", "model") }
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun inFlightWriteFreezesEveryControlAndCannotReplayAfterAcknowledgment() {
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
            captions.forEach { assertFalse(text(form, it).isEnabled); assertFalse(supply(form, it).isEnabled) }
            assertFalse(retired.isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            captions.forEach { supply(form, it).performClick(); assertTrue(supply(form, it).isChecked) }
            recreate { state ->
                val pending = (ComponentFormRecovery.read(parcel(state)) as ComponentFormRecovery.TypedMonitoringStackPending).form
                assertTrue(pending.wasSaving)
                assertEquals(filled(), pending.input)
                release.countDown()
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        readOnly(reviewPending(), "0.999999999999999999", "9223372036854775808", reference)
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(filled(), settings())
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        val bytes = disk()
        retired.performClick()
        captions.forEach { supply(form, it).performClick() }
        assertUnchanged(saved, bytes)
    }

    @Test fun actualPauseRetiresInputAndCommitUntilFreshReviewedConfirmation() {
        val before = seed()
        val bytes = disk()
        val form = configure()
        fill(form)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        controller.pause()
        assertFalse(form.isShowing)
        retired.performClick()
        captions.forEach { supply(form, it).performClick() }
        text(form, R.string.monitoring_stack_sample).setText("retired replacement")
        button(R.string.component_recovery_review).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertUnchanged(before, bytes)
        val pending = (ComponentFormRecovery.read(capture()) as ComponentFormRecovery.TypedMonitoringStackPending).form
        assertEquals(filled(), pending.input)
        assertFalse(pending.wasSaving)
        assertFalse(button(R.string.component_recovery_review).isEnabled)
        assertSame(form, dialog())
        controller.resume().visible()
        finishWork()
        assertSame(form, dialog())
        val reviewed = reviewPending()
        assertNotSame(form, reviewed)
        assertEquals(listOf(filled().sampleRate, filled().retentionDays, reference), captions.map { text(reviewed, it).text.toString() })
        retired.performClick()
        assertUnchanged(before, bytes)
        val saved = commit(reviewed, before)
        assertEquals(before.revision + 1, saved.revision)
        retired.performClick()
        reviewed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun oversizedNumericRecoveryHasOnlyTheMarkerAndNoAutomaticViewStateFallback() {
        val before = seed()
        val form = configure()
        enter(form, R.string.monitoring_stack_sample, "9".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS + 1))
        enter(form, R.string.monitoring_stack_credential, reference)
        captions.forEach { assertFalse(text(form, it).isSaveEnabled); assertFalse(supply(form, it).isSaveEnabled) }
        assertFalse(views(form.window!!.decorView).filterIsInstance<MonitoringStackSettingsEditor>().single().isSaveEnabled)
        recreate(::fixedMarker)
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        val fresh = configure()
        captions.forEach { assertEquals("", text(fresh, it).text.toString()); assertFalse(supply(fresh, it).isChecked) }
        dismiss(fresh)
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun legacyRawConfigureRecoveryStaysExplicitAndFreshConfigureUsesTypedControls() {
        seed()
        for (version in 1..2) {
            val before = loaded()
            val target = selection()
            val form = configure()
            enter(form, R.string.monitoring_stack_sample, "retained legacy fixture")
            val raw = """{"retention_days":"unfinished"""
            val legacy = ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, currentId().value,
                before.baselineIdentity, target.id, null, null, null, null, "", raw, false, false)
            recreate { state ->
                state.putBundle(ComponentFormRecovery.KEY, requireNotNull(legacy.bundle()).apply { putInt("version", version) })
            }
            val restored = reviewPending()
            assertEquals(raw, text(restored, R.string.component_settings).text.toString())
            assertTrue(views(restored.window!!.decorView).filterIsInstance<MonitoringStackSettingsEditor>().isEmpty())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            val exact = if (version == 1) "9007199254740993" else "9223372036854775808"
            text(restored, R.string.component_settings).setText("{\"retention_days\":" + exact + "}")
            commit(restored, before)
            assertEquals(PreviewMonitoringStackInput(retentionDays = exact), settings())
            val typed = configure()
            assertEquals(exact, text(typed, R.string.monitoring_stack_retention).text.toString())
            assertEquals(1, views(typed.window!!.decorView).filterIsInstance<MonitoringStackSettingsEditor>().size)
            dismiss(typed)
        }
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsIdentityFieldsGuidanceAndTouchTargetsReachable() {
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
            val detail = control.javaClass.simpleName + " id=" + control.id + " text=" + control.text +
                " measured=" + control.width + "x" + control.height
            assertTrue(detail, control.width >= (48 * density).toInt())
            assertTrue(detail, control.height >= (48 * density).toInt())
            reachable(control, root)
        }
        for (caption in captions) {
            val input = text(form, caption)
            val layout = requireNotNull(input.layout)
            for (line in 0 until layout.lineCount) reachable(input, root,
                Rect(0, input.totalPaddingTop + layout.getLineTop(line), input.width,
                    input.totalPaddingTop + layout.getLineBottom(line)))
        }
        for (label in listOf(R.string.monitoring_stack_editor_guidance, R.string.monitoring_stack_recovery_guidance,
            R.string.component_monitoring_stack_guidance)) {
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
                "Visible monitoring text must not be ellipsized: " + view.javaClass.simpleName +
                    " id=" + view.id + " text=" + view.text, 0, layout.getEllipsisCount(line))
        }
        assertEquals(before.revision + 1, commit(form, before).revision)
        assertEquals(filled(), settings())
    }

    private fun reachable(control: View, root: View, target: Rect = Rect(0, 0, control.width, control.height)) {
        control.requestRectangleOnScreen(Rect(target), true)
        val bounds = Rect(target)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            val viewport = Rect().also(parent::getDrawingRect)
            assertTrue("Monitoring control must remain reachable: " + control.javaClass.simpleName +
                " bounds=" + bounds + " viewport=" + viewport, viewport.contains(bounds))
            child = parent
        }
    }
}
