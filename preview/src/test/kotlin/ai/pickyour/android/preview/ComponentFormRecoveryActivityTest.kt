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

/** Simulated lifecycle recovery of pending input, never installed-process evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ComponentFormRecoveryActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun shownText(label: Int) = views().filterIsInstance<TextView>().any {
        it.text.toString() == activity.getString(label) && it.visibility == View.VISIBLE
    }
    private fun field(dialog: AlertDialog, label: Int): EditText {
        val caption = views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return dialog.findViewById(caption.labelFor)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit component recovery UI")
    }
    private fun finishWork() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker().submit {}.get(15, TimeUnit.SECONDS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun worker(): ExecutorService = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()!!
    private fun save() {
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
    }
    private fun addForm(): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().startsWith("model ·")
        }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        val choice = dialog()
        shadowOf(choice).clickOnItem(0)
        await { dialog() !== choice && dialog().isShowing }
        return dialog()
    }
    private fun roundTrip(state: Bundle): Bundle {
        val written = Parcel.obtain()
        val read = Parcel.obtain()
        return try {
            written.writeBundle(state)
            val bytes = written.marshall()
            read.unmarshall(bytes, 0, bytes.size); read.setDataPosition(0)
            requireNotNull(read.readBundle(javaClass.classLoader))
        } finally { written.recycle(); read.recycle() }
    }
    private fun recreate(afterCheckpoint: (Bundle) -> Unit = {}) {
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        afterCheckpoint(state)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(roundTrip(state)).start().resume().visible()
        finishWork()
    }
    private fun review(): AlertDialog {
        val review = button(R.string.component_recovery_review)
        assertEquals(View.VISIBLE, review.visibility)
        review.performClick()
        return dialog()
    }
    private fun assertReadOnly(review: AlertDialog, raw: String) {
        assertTrue(review.isShowing)
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == raw && it.isTextSelectable
        })
        assertNotEquals(View.VISIBLE, review.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
    }
    private fun addModel(): PreviewStoredDraft {
        val form = addForm()
        field(form, R.string.component_role).setText("primary")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && hasText(R.string.component_saved) }
        finishWork()
        return loaded()
    }
    private fun configure(): AlertDialog {
        button(R.string.component_manage).performClick()
        val picker = dialog()
        shadowOf(picker).clickOnItem(0)
        await { dialog() !== picker && dialog().isShowing }
        val actions = dialog()
        shadowOf(actions).clickOnItem(0)
        await { dialog() !== actions && dialog().isShowing }
        return dialog()
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
    }

    @Test fun recreatedAddFormRetainsInvalidRawInputWithoutWritingOrRevivingOldConsent() {
        save()
        val baseline = loaded().baselineIdentity
        val form = addForm()
        val raw = "{\"temperature\": \"pending\u2028literal\""
        field(form, R.string.component_role).setText("Primary pending")
        field(form, R.string.component_settings).setText(raw)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate()
        assertFalse(form.isShowing)
        assertEquals(baseline, loaded().baselineIdentity)
        val restored = review()
        assertNotSame(form, restored)
        assertEquals("Primary pending", field(restored, R.string.component_role).text.toString())
        assertEquals(raw, field(restored, R.string.component_settings).text.toString())
        assertTrue(views(restored.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        retired.performClick()
        finishWork()
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun recoveredAddRequiresFreshConfirmationAndCommitsCapturedSelectionOnlyOnce() {
        save()
        val before = loaded()
        val form = addForm()
        val invalid = "{" + " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT)
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(invalid)
        var capturedId: String? = null
        recreate { state ->
            val pending = ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending
            capturedId = pending.form.selectionId
            assertEquals(invalid, pending.form.settings)
        }
        val restored = review()
        val positive = restored.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(invalid, field(restored, R.string.component_settings).text.toString())
        positive.performClick()
        await { hasText(R.string.component_invalid) && positive.isEnabled }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(invalid, field(restored, R.string.component_settings).text.toString())
        field(restored, R.string.component_settings).setText("{\"temperature\":0.7500}")
        positive.performClick(); positive.performClick()
        await { !restored.isShowing && loaded().revision == before.revision + 1 }
        positive.performClick(); finishWork()
        val after = loaded()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(capturedId, after.components.selections.single().id)
        assertEquals(before.fields, after.fields)
        assertEquals(before.contextInput, after.contextInput)
        assertEquals(before.workloadInput, after.workloadInput)
        assertTrue(PreviewSelectionForms.settingsText(after.components.selections.single()).contains("0.75"))
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(after.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun recreatedConfigurationKeepsItsSelectionAcrossRepeatedReviewBeforeOneExplicitSave() {
        save()
        val before = addModel()
        val selection = before.components.selections.single()
        val form = configure()
        val raw = "{\"temperature\":0.5,\"unfinished\":"
        field(form, R.string.component_settings).setText(raw)
        recreate()
        val first = review()
        assertEquals(raw, field(first, R.string.component_settings).text.toString())
        recreate()
        val second = review()
        assertFalse(first.isShowing)
        assertEquals(raw, field(second, R.string.component_settings).text.toString())
        field(second, R.string.component_settings).setText("{\"temperature\":0.5}")
        second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !second.isShowing && loaded().revision == before.revision + 1 }
        val after = loaded()
        assertEquals(selection.id, after.components.selections.single().id)
        assertEquals(selection.role, after.components.selections.single().role)
        assertEquals(selection.enabled, after.components.selections.single().enabled)
        assertEquals(before.fields, after.fields)
        assertEquals("{\"temperature\":0.5}", PreviewSelectionForms.settingsText(after.components.selections.single()))
    }

    @Test fun changedSavedHeadKeepsPendingComponentInputReadOnlyAfterExplicitReopen() {
        save()
        val before = loaded()
        val form = addForm()
        val raw = "{\"temperature\":\"uncommitted\""
        field(form, R.string.component_role).setText("retained role")
        field(form, R.string.component_settings).setText(raw)
        finishWork()
        val newer = (adapter().save(before, PreviewDraftFields("other writer")) as PreviewDraftSave.Saved).record
        recreate()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        val readOnly = review()
        assertReadOnly(readOnly, raw)
        readOnly.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        reopen()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertReadOnly(review(), raw)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_choose_catalog).isEnabled)
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun mismatchedLocalDraftIdentityCannotAcquireCurrentBaselineAuthorityFromRestoredState() {
        save()
        val baseline = loaded().baselineIdentity
        val form = addForm()
        val raw = "{\"still\":\"pending\"}"
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(raw)
        recreate { state ->
            // Keep the real current baseline while supplying a different, well-formed local identity.
            state.getBundle(ComponentFormRecovery.KEY)!!.putString("draftId", "draft-00000000-0000-0000-0000-000000000001")
        }
        assertReadOnly(review(), raw)
        finishWork()
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun changedCatalogHashKeepsRecoveredAddReadOnlyWithoutUsingMatchingItemFallback() {
        save()
        val baseline = loaded().baselineIdentity
        val form = addForm()
        val raw = "{\"temperature\":0.5}"
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(raw)
        var oldHash: String? = null
        recreate { state -> oldHash = (ComponentFormRecovery.read(state) as ComponentFormRecovery.Pending).form.catalogHash }
        val document = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        document.put("revision", document.getInt("revision") + 1)
        val replacement = requireNotNull(PreviewCatalogBrowser.read(document.toString().toByteArray(Charsets.UTF_8)))
        assertNotEquals(oldHash, replacement.hash)
        views().filterIsInstance<SelectionEditSection>().single().catalog(replacement.items, replacement.hash)
        assertReadOnly(review(), raw)
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun savedInFlightCheckpointNeverReplaysAfterTheOriginalComponentWriteCompletes() {
        save()
        val before = loaded()
        val form = addForm()
        field(form, R.string.component_role).setText("primary")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            recreate { state ->
                assertTrue((ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form.wasSaving)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val committed = loaded()
        assertEquals(before.revision + 1, committed.revision)
        val readOnly = review()
        assertReadOnly(readOnly, "{}")
        assertTrue(views(readOnly.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_recovery_saving)
        })
        readOnly.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        reopen()
        assertReadOnly(review(), "{}")
        finishWork()
        assertEquals(committed.baselineIdentity, loaded().baselineIdentity)
        assertEquals(1, loaded().components.selections.size)
    }

    @Test fun canceledAndDiscardedRecoveryCannotBeRevivedOrClearLaterInputThroughRetiredButtons() {
        save()
        val baseline = loaded().baselineIdentity
        val canceled = addForm()
        field(canceled, R.string.component_settings).setText("canceled")
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !canceled.isShowing } // The platform posts its standard negative-button dismissal.
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        val discarded = addForm()
        field(discarded, R.string.component_settings).setText("discarded")
        recreate()
        val retiredReview = button(R.string.component_recovery_review)
        val retiredDiscard = button(R.string.component_recovery_discard)
        retiredDiscard.performClick()
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        val fresh = addForm()
        field(fresh, R.string.component_settings).setText("new pending input")
        recreate()
        val previousDialog = dialog()
        retiredReview.performClick(); retiredDiscard.performClick(); finishWork()
        assertSame(previousDialog, dialog())
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        assertEquals("new pending input", field(review(), R.string.component_settings).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun oversizedComponentCheckpointShowsUnavailableWithoutSavingOrRetainingAValidPrefix() {
        save()
        val baseline = loaded().baselineIdentity
        val form = addForm()
        field(form, R.string.component_role).setText("primary")
        val raw = "{}" + " ".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS)
        field(form, R.string.component_settings).setText(raw)
        assertEquals(raw, field(form, R.string.component_settings).text.toString())
        recreate { state -> assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(roundTrip(state))) }
        assertTrue(shownText(R.string.component_recovery_unavailable))
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_choose_catalog).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick()
        assertTrue(button(R.string.component_choose_catalog).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
    }

    @Test fun openComponentFormCannotBeReplacedByRetainedEntryControlsBeforeCheckpointing() {
        save()
        val baseline = loaded().baselineIdentity
        val retainedChoose = button(R.string.component_choose_catalog)
        val form = addForm()
        field(form, R.string.component_settings).setText("the first pending form")
        assertFalse(retainedChoose.isEnabled)
        retainedChoose.performClick(); finishWork()
        assertSame(form, dialog())
        assertTrue(form.isShowing)
        recreate()
        assertEquals("the first pending form", field(review(), R.string.component_settings).text.toString())
        assertEquals(baseline, loaded().baselineIdentity)
    }
}
