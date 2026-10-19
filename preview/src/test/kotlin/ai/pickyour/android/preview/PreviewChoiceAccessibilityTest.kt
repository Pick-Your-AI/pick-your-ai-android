package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import ai.pickyour.core.drafts.DraftDataClassification
import ai.pickyour.core.preview.PreviewCatalogKinds
import ai.pickyour.core.preview.PreviewCatalogSort
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
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

/** Actual native list/row semantics in a JVM simulation, not an installed TalkBack run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewChoiceAccessibilityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val catalog get() = views().filterIsInstance<CatalogBrowseSection>().single()
    private val workload get() = views().filterIsInstance<WorkloadSection>().single()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertNoSavedDraft()
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            AutosaveUiClock.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit UI state")
    }
    private fun assertNoSavedDraft() {
        assertTrue(PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load() is PreviewDraftLoad.Missing)
    }
    private fun catalogState(section: CatalogBrowseSection = catalog): List<Any?> {
        val out = Bundle()
        section.saveState(out)
        return listOf(out.getString("catalogQuery"), out.getString("catalogTier"), out.getString("catalogKind"),
            out.getString("catalogSort"), out.getInt("catalogPage"), out.getString("catalogSnapshot"))
    }
    private fun openCatalog(index: Int): AlertDialog = open(catalog.getChildAt(index) as Button)
    private fun openWorkload(): AlertDialog = open(views(workload).filterIsInstance<Button>().first())
    private fun open(control: Button): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(control.isEnabled)
        control.performClick()
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNotSame(previous, dialog)
        assertTrue(dialog.isShowing)
        return dialog
    }
    private fun cancel(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !dialog.isShowing }
    }
    private fun choose(dialog: AlertDialog, index: Int) {
        shadowOf(dialog).clickOnItem(index)
        await { !dialog.isShowing }
    }
    private fun retainedClick(dialog: AlertDialog): (Int) -> Unit {
        val list = dialog.listView
        val listener = requireNotNull(list.onItemClickListener)
        return { index ->
            listener.onItemClick(list, null, index, list.adapter.getItemId(index))
            AutosaveUiClock.idle()
        }
    }
    private fun assertChecked(dialog: AlertDialog, index: Int) {
        val list = dialog.listView
        assertEquals(ListView.CHOICE_MODE_SINGLE, list.choiceMode)
        assertEquals(index, list.checkedItemPosition)
        assertEquals(1, list.checkedItemCount)
        repeat(list.count) { assertEquals(it == index, list.isItemChecked(it)) }

        // Inspect a real laid-out row and its native accessibility node, not a
        // fabricated content description or an adapter-only checked flag.
        val decor = requireNotNull(dialog.window).decorView
        val density = activity.resources.displayMetrics.density
        fun layout() {
            decor.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
        layout()
        list.setSelection(index)
        AutosaveUiClock.idle()
        layout()
        val row = list.getChildAt(index - list.firstVisiblePosition) as CheckedTextView
        assertTrue(row.width > 0 && row.height > 0)
        assertTrue(row.isChecked)
        assertEquals(list.adapter.getItem(index).toString(), row.text.toString())
        val node = row.createAccessibilityNodeInfo()
        assertTrue(node.isCheckable)
        assertTrue(node.isChecked)
        assertEquals(row.text.toString(), node.text.toString())
        node.recycle()
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.save_draft).isEnabled }
    }

    @Test fun catalogPickersExposeCurrentCheckedChoicesWithoutSaving() {
        // Dialog frames must not advance this read-only case beyond the autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("Pending title remains local")
        val choices = listOf(PreviewCatalogKinds.tiers.indexOf("T1") + 1,
            PreviewCatalogKinds.all.indexOfFirst { it.id == "model" } + 1, PreviewCatalogSort.NAME.ordinal)
        choices.forEachIndexed { picker, selected ->
            val before = catalogState()
            val opened = openCatalog(picker)
            assertChecked(opened, 0)
            assertEquals(before, catalogState())
            choose(opened, selected)
            val current = catalogState()
            val reopened = openCatalog(picker)
            assertChecked(reopened, selected)
            cancel(reopened)
            assertEquals(current, catalogState())
        }
        assertEquals("Pending title remains local", field(R.string.draft_title).text.toString())
        assertNoSavedDraft()
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun workloadPickerExposesUnansweredAndCurrentClassificationWithoutSaving() {
        val inputs = workload.inputs
        val unanswered = openWorkload()
        assertChecked(unanswered, 0)
        assertSame(inputs, workload.inputs)
        cancel(unanswered)
        assertSame(inputs, workload.inputs)

        val selected = DraftDataClassification.CONFIDENTIAL.ordinal + 1
        choose(openWorkload(), selected)
        assertEquals(DraftDataClassification.CONFIDENTIAL, workload.inputs.classification)
        val current = workload.inputs
        val reopened = openWorkload()
        assertChecked(reopened, selected)
        cancel(reopened)
        assertSame(current, workload.inputs)
        choose(openWorkload(), 0)
        assertNull(workload.inputs.classification)
        assertNoSavedDraft()
    }

    @Test fun cancelledAndRepeatedPickerCallbacksCannotApplyAnotherChoice() {
        val before = catalogState()
        val cancelledCatalog = openCatalog(0)
        val lateCatalog = retainedClick(cancelledCatalog)
        cancel(cancelledCatalog)
        lateCatalog(2)
        assertEquals(before, catalogState())

        val appliedCatalog = openCatalog(2)
        val duplicateCatalog = retainedClick(appliedCatalog)
        choose(appliedCatalog, PreviewCatalogSort.NAME.ordinal)
        val applied = catalogState()
        duplicateCatalog(PreviewCatalogSort.KIND.ordinal)
        assertEquals(applied, catalogState())

        val inputs = workload.inputs
        val cancelledWorkload = openWorkload()
        val lateWorkload = retainedClick(cancelledWorkload)
        cancel(cancelledWorkload)
        lateWorkload(1)
        assertSame(inputs, workload.inputs)
        val appliedWorkload = openWorkload()
        val duplicateWorkload = retainedClick(appliedWorkload)
        choose(appliedWorkload, 1)
        val selected = workload.inputs
        duplicateWorkload(2)
        assertSame(selected, workload.inputs)
        assertEquals(DraftDataClassification.PUBLIC, workload.inputs.classification)
        assertNoSavedDraft()
    }

    @Test fun changedWorkloadInputsRetireAnOpenPickerWithoutApplyingItsCheckedRow() {
        val stale = openWorkload()
        field(R.string.workload_region).setText("pending-region")
        val current = workload.inputs
        choose(stale, 1)
        assertSame(current, workload.inputs)
        assertNull(workload.inputs.classification)
        val fresh = openWorkload()
        assertChecked(fresh, 0)
        choose(fresh, 1)
        assertEquals(DraftDataClassification.PUBLIC, workload.inputs.classification)
        assertEquals("pending-region", field(R.string.workload_region).text.toString())
        assertNoSavedDraft()
    }

    @Test fun recreationPreservesCheckedChoicesAndRetiresOldPickerCallbacks() {
        field(R.string.draft_title).setText("Pending title after recreation")
        field(R.string.catalog_search).setText("FICTIONAL")
        val tier = PreviewCatalogKinds.tiers.indexOf("T1") + 1
        val kind = PreviewCatalogKinds.all.indexOfFirst { it.id == "model" } + 1
        choose(openCatalog(0), tier)
        choose(openCatalog(1), kind)
        choose(openCatalog(2), PreviewCatalogSort.NAME.ordinal)
        choose(openWorkload(), DraftDataClassification.INTERNAL.ordinal + 1)
        val oldCatalog = catalog
        val oldWorkload = workload
        val before = catalogState()
        val inputs = workload.inputs
        val staleCatalog = retainedClick(openCatalog(0))
        val staleWorkload = retainedClick(openWorkload())
        recreate()
        staleCatalog(0)
        staleWorkload(0)
        assertEquals(before, catalogState(oldCatalog))
        assertSame(inputs, oldWorkload.inputs)
        assertEquals(before, catalogState())
        assertEquals(inputs, workload.inputs)
        assertEquals("Pending title after recreation", field(R.string.draft_title).text.toString())
        for ((picker, index) in listOf(0 to tier, 1 to kind, 2 to PreviewCatalogSort.NAME.ordinal)) {
            val opened = openCatalog(picker)
            assertChecked(opened, index)
            cancel(opened)
        }
        val classification = openWorkload()
        assertChecked(classification, DraftDataClassification.INTERNAL.ordinal + 1)
        cancel(classification)
        assertNoSavedDraft()
    }
}
