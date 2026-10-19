package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.ListView
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.OptionalTier
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

/** Native checked rows and actual local-store effects; not an installed TalkBack run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class OptionalTierChoiceAccessibilityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        button(R.string.save_draft).performClick()
        await { button(R.string.library_new).isEnabled && adapter().load() is PreviewDraftLoad.Available }
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun tierButton(tier: OptionalTier) = views().filterIsInstance<Button>()
        .single { it.text.toString().startsWith("${tier.name}:") }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            AutosaveUiClock.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit tier UI state")
    }
    private fun open(tier: OptionalTier): AlertDialog {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(tierButton(tier).isEnabled)
        tierButton(tier).performClick()
        return dialog().also { assertNotSame(previous, it); assertTrue(it.isShowing) }
    }
    private fun propose(picker: AlertDialog, value: Activation): AlertDialog {
        val before = loaded()
        shadowOf(picker).clickOnItem(value.ordinal)
        val confirmation = dialog()
        assertNotSame(picker, confirmation)
        assertTrue(confirmation.isShowing)
        assertFalse(picker.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.history.map { it.revision }, loaded().history.map { it.revision })
        return confirmation
    }
    private fun commit(tier: OptionalTier, value: Activation) {
        val before = loaded()
        assertNotEquals(value, before.optionalTiers.getValue(tier).activation)
        val confirmation = propose(open(tier), value)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !confirmation.isShowing && button(R.string.library_new).isEnabled &&
            loaded().optionalTiers.getValue(tier).activation == value }
        val after = loaded()
        assertNotEquals(before.baselineIdentity, after.baselineIdentity)
        assertEquals(before.history.size + 1, after.history.size)
        OptionalTier.entries.filter { it != tier }.forEach {
            assertEquals(before.optionalTiers.getValue(it), after.optionalTiers.getValue(it))
        }
    }
    private fun cancel(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !dialog.isShowing }
    }
    private fun retainedClick(picker: AlertDialog): (Activation) -> Unit {
        val list = picker.listView
        val listener = requireNotNull(list.onItemClickListener)
        return { value ->
            listener.onItemClick(list, null, value.ordinal, list.adapter.getItemId(value.ordinal))
            AutosaveUiClock.idle()
        }
    }
    private fun assertChecked(picker: AlertDialog, value: Activation) {
        val list = picker.listView
        assertEquals(ListView.CHOICE_MODE_SINGLE, list.choiceMode)
        assertEquals(Activation.entries.size, list.count)
        assertEquals(value.ordinal, list.checkedItemPosition)
        assertEquals(1, list.checkedItemCount)
        val labels = Activation.entries.map { activity.getString(when (it) {
            Activation.ENABLED -> R.string.tier_enabled
            Activation.DISABLED -> R.string.tier_disabled
            Activation.UNKNOWN -> R.string.tier_unknown
        }) }
        val decor = requireNotNull(picker.window).decorView
        val density = activity.resources.displayMetrics.density
        decor.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        repeat(list.count) { index ->
            assertEquals(index == value.ordinal, list.isItemChecked(index))
            assertEquals(labels[index], list.adapter.getItem(index).toString())
            val row = list.getChildAt(index - list.firstVisiblePosition) as CheckedTextView
            assertTrue(row.width > 0 && row.height > 0)
            assertEquals(index == value.ordinal, row.isChecked)
            val node = row.createAccessibilityNodeInfo()
            assertTrue(node.isCheckable)
            assertEquals(index == value.ordinal, node.isChecked)
            assertEquals(labels[index], node.text.toString())
            node.recycle()
        }
    }

    @Test fun eachTierPickerExposesItsSavedActivationWithoutWriting() {
        val initial = loaded()
        OptionalTier.entries.forEach { tier ->
            val picker = open(tier)
            assertChecked(picker, Activation.DISABLED)
            cancel(picker)
        }
        assertEquals(initial.baselineIdentity, loaded().baselineIdentity)
        commit(OptionalTier.T3, Activation.ENABLED)
        commit(OptionalTier.T4, Activation.UNKNOWN)
        val saved = loaded()
        OptionalTier.entries.forEach { tier ->
            val picker = open(tier)
            assertChecked(picker, saved.optionalTiers.getValue(tier).activation)
            cancel(picker)
            assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
            assertEquals(saved.history.map { it.revision }, loaded().history.map { it.revision })
        }
    }

    @Test fun canceledTierProposalReopensSavedCheckAndRetiresControls() {
        commit(OptionalTier.T3, Activation.ENABLED)
        val saved = loaded()
        val picker = open(OptionalTier.T3)
        assertChecked(picker, Activation.ENABLED)
        val oldChoice = retainedClick(picker)
        val confirmation = propose(picker, Activation.UNKNOWN)
        val oldCommit = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        cancel(confirmation)
        val reopened = open(OptionalTier.T3)
        assertChecked(reopened, Activation.ENABLED)
        oldChoice(Activation.DISABLED)
        oldCommit.performClick()
        AutosaveUiClock.idle()
        assertSame(reopened, dialog())
        assertTrue(reopened.isShowing)
        assertChecked(reopened, Activation.ENABLED)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.history.map { it.revision }, loaded().history.map { it.revision })
        cancel(reopened)
    }

    @Test fun supersededTierChoicePreservesCurrentCheckAndCommitsOnce() {
        commit(OptionalTier.T3, Activation.ENABLED)
        commit(OptionalTier.T4, Activation.UNKNOWN)
        val before = loaded()
        val superseded = open(OptionalTier.T3)
        assertChecked(superseded, Activation.ENABLED)
        val oldChoice = retainedClick(superseded)
        val current = open(OptionalTier.T4)
        assertChecked(current, Activation.UNKNOWN)
        oldChoice(Activation.DISABLED)
        assertFalse(superseded.isShowing)
        assertSame(current, dialog())
        assertChecked(current, Activation.UNKNOWN)
        val confirmation = propose(current, Activation.ENABLED)
        val positive = confirmation.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.performClick()
        await { !confirmation.isShowing && button(R.string.library_new).isEnabled &&
            loaded().optionalTiers.getValue(OptionalTier.T4).activation == Activation.ENABLED }
        val after = loaded()
        assertEquals(before.history.size + 1, after.history.size)
        assertEquals(Activation.ENABLED, after.optionalTiers.getValue(OptionalTier.T3).activation)
        positive.performClick()
        AutosaveUiClock.idle()
        val reopened = open(OptionalTier.T4)
        assertChecked(reopened, Activation.ENABLED)
        assertEquals(after.baselineIdentity, loaded().baselineIdentity)
        assertEquals(after.history.map { it.revision }, loaded().history.map { it.revision })
        cancel(reopened)
    }

    @Test fun recreationRestoresSavedTierCheckAndRetiresOldPicker() {
        commit(OptionalTier.T5, Activation.UNKNOWN)
        val saved = loaded()
        val obsolete = open(OptionalTier.T5)
        assertChecked(obsolete, Activation.UNKNOWN)
        val oldChoice = retainedClick(obsolete)
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.library_new).isEnabled }
        assertFalse(obsolete.isShowing)
        val current = open(OptionalTier.T5)
        oldChoice(Activation.ENABLED)
        assertSame(current, dialog())
        assertTrue(current.isShowing)
        assertChecked(current, Activation.UNKNOWN)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.history.map { it.revision }, loaded().history.map { it.revision })
        cancel(current)
    }
}
