package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

/** Real preview controls on a simulated Activity; never installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class OptionalTierDialogTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && button(R.string.library_new).isEnabled }
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun tierButton(tier: OptionalTier = OptionalTier.T3) = views().filterIsInstance<Button>()
        .single { it.text.toString().startsWith("${tier.name}:") }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun confirmation(): AlertDialog {
        tierButton().performClick()
        shadowOf(dialog()).clickOnItem(Activation.ENABLED.ordinal)
        return dialog()
    }

    @Test fun canceledTierConfirmationCannotCommitRetainedPositiveControl() {
        val before = loaded()
        val canceled = confirmation()
        val retained = canceled.getButton(AlertDialog.BUTTON_POSITIVE)
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !canceled.isShowing }
        retained.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        await { button(R.string.library_new).isEnabled }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
    }

    @Test fun dismissedTierConfirmationCannotCommitAfterEditabilityReturns() {
        val before = loaded()
        val dismissed = confirmation()
        val retained = dismissed.getButton(AlertDialog.BUTTON_POSITIVE)
        val title = field(R.string.draft_title)
        val original = title.text.toString()
        title.setText("temporary pending title")
        assertFalse(tierButton().isEnabled)
        title.setText(original)
        assertTrue(tierButton().isEnabled)
        await { !dismissed.isShowing }
        retained.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        await { button(R.string.library_new).isEnabled }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
    }

    @Test fun canceledTierPickerCannotOpenConfirmationFromRetainedItemCallback() {
        val before = loaded()
        tierButton().performClick()
        val canceled = dialog()
        canceled.cancel()
        await { !canceled.isShowing }
        shadowOf(canceled).clickOnItem(Activation.ENABLED.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(canceled, dialog())
        assertFalse(dialog().isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun supersededTierPickerCannotReplaceCurrentChoiceAndCurrentConfirmationCommitsOnce() {
        val before = loaded()
        tierButton().performClick()
        val superseded = dialog()
        tierButton(OptionalTier.T4).performClick()
        val current = dialog()
        assertNotSame(superseded, current)
        shadowOf(superseded).clickOnItem(Activation.ENABLED.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(current, dialog())
        assertTrue(current.isShowing)
        assertFalse(superseded.isShowing)
        shadowOf(current).clickOnItem(Activation.ENABLED.ordinal)
        val accepted = dialog()
        val retained = accepted.getButton(AlertDialog.BUTTON_POSITIVE)
        retained.performClick()
        await { !accepted.isShowing && button(R.string.library_new).isEnabled }
        val saved = loaded()
        assertNotEquals(before.baselineIdentity, saved.baselineIdentity)
        assertEquals(Activation.DISABLED, saved.optionalTiers.getValue(OptionalTier.T3).activation)
        assertEquals(Activation.ENABLED, saved.optionalTiers.getValue(OptionalTier.T4).activation)
        assertEquals(before.history.size + 1, saved.history.size)
        retained.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        await { button(R.string.library_new).isEnabled }
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.history.size, loaded().history.size)
    }
}
