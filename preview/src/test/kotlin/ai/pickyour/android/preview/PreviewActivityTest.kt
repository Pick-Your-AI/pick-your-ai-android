package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.preview.*
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

/** Simulated Android lifecycle/resources on the JVM, never installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
        assertTrue("Each test must start in an isolated empty Robolectric sandbox", adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int): Button = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun hasText(resource: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(resource) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun save() {
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
    }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()!!

    @Test fun acknowledgedFieldsAndExplicitEmptySurviveRecreation() {
        field(R.string.draft_title).setText("saved title")
        field(R.string.context_objective).setText("objective")
        field(R.string.context_context).setText("")
        save()
        val baseline = loaded().baselineIdentity
        recreate()
        await { hasText(R.string.draft_saved) }
        assertEquals("saved title", field(R.string.draft_title).text.toString())
        assertEquals("objective", field(R.string.context_objective).text.toString())
        assertEquals(PreviewDraftContext("objective", ""), loaded().contextInput)
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(views().filterIsInstance<TextView>().any { it.text.toString().contains("explicitly empty") })
    }

    @Test fun newerDiskHeadCannotAdoptStalePendingFormAfterRecreation() {
        field(R.string.draft_title).setText("baseline")
        save()
        val original = loaded()
        field(R.string.draft_title).setText("pending title")
        field(R.string.context_objective).setText("pending objective")
        assertTrue(adapter().save(original, PreviewDraftFields("other writer"), PreviewDraftContext("new disk", null)) is PreviewDraftSave.Saved)
        recreate()
        await { hasText(R.string.draft_reconcile) }
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        assertEquals("pending objective", field(R.string.context_objective).text.toString())
        button(R.string.reopen_draft).performClick()
        val reopenConfirmation = dialog()
        reopenConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !reopenConfirmation.isShowing && button(R.string.reopen_draft).isEnabled }
        assertEquals("pending title", field(R.string.draft_title).text.toString())
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_saved) }
        assertEquals("other writer", field(R.string.draft_title).text.toString())
        assertEquals("new disk", field(R.string.context_objective).text.toString())
    }

    @Test fun tierActivationRequiresCleanFormAndExplicitConfirmation() {
        save()
        fun tier() = views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3:") }
        val original = loaded().baselineIdentity
        field(R.string.draft_title).setText("pending")
        assertFalse(tier().isEnabled)
        save()
        tier().performClick()
        shadowOf(dialog()).clickOnItem(Activation.ENABLED.ordinal)
        val activationConfirmation = dialog()
        activationConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !activationConfirmation.isShowing && tier().isEnabled }
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
        tier().performClick()
        shadowOf(dialog()).clickOnItem(Activation.ENABLED.ordinal)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { tier().isEnabled && tier().text.toString().startsWith("T3: enabled") }
        assertEquals(Activation.ENABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
        assertNotEquals(original, loaded().baselineIdentity)
    }

    @Test fun comparisonDistinguishesLiteralSentinelWithoutWriting() {
        field(R.string.context_objective).setText("")
        save()
        field(R.string.context_objective).setText("explicitly empty")
        save()
        val baseline = loaded().baselineIdentity
        button(R.string.draft_history).performClick()
        val historyDialog = dialog()
        historyDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { dialog() !== historyDialog && dialog().isShowing }
        shadowOf(dialog()).clickOnItem(1)
        await { shadowOf(dialog()).message.toString().contains("Before:") }
        val message = shadowOf(dialog()).message.toString()
        assertTrue(message.contains("Before: explicitly empty"))
        assertTrue(message.contains("After: text: \"explicitly empty\""))
        assertEquals(baseline, loaded().baselineIdentity)
    }
}
