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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode
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
    private fun samples() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL") }

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

    @Test fun editorsExposeLabelsAndSavedStatusUsesTextAndLiveRegion() {
        val editors = views().filterIsInstance<EditText>()
        assertEquals(4, editors.size)
        editors.forEach { editor ->
            val labels = views().filterIsInstance<TextView>().filter { it.labelFor == editor.id }
            assertEquals("Each editor needs one explicit visible label", 1, labels.size)
            assertTrue(labels.single().text.isNotBlank())
            assertTrue(editor.isFocusable)
            assertTrue(editor.isEnabled)
        }
        assertTrue(views().filterIsInstance<TextView>().any { it.isAccessibilityHeading })
        field(R.string.draft_title).setText("semantics check")
        save()
        val status = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.draft_saved) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
        assertTrue(status.text.isNotBlank())
        val tier = views().filterIsInstance<Button>().single { it.text.toString().startsWith("T3:") }
        assertTrue(tier.text.toString().contains(activity.getString(R.string.tier_disabled)))
        assertTrue(tier.isFocusable)
        assertTrue(tier.isEnabled)
    }

    @Test fun confirmedCurrentDraftDeletionRetainsPendingFormAndRevokesSaveAuthority() {
        field(R.string.draft_title).setText("saved before deletion")
        save()
        field(R.string.draft_title).setText("pending title stays visible")
        field(R.string.context_objective).setText("pending objective stays visible")
        button(R.string.library_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        shadowOf(dialog()).clickOnItem(0)
        val entryDialog = dialog()
        entryDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        await { dialog() !== entryDialog && dialog().isShowing }
        val confirmation = dialog()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.library_deleted_editor) && button(R.string.library_open).isEnabled }
        assertEquals("pending title stays visible", field(R.string.draft_title).text.toString())
        assertEquals("pending objective stays visible", field(R.string.context_objective).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertFalse(button(R.string.draft_history).isEnabled)
        val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
        assertTrue((library.list() as PreviewLibraryRead.Available).entries.isEmpty())
        assertEquals(activity.getString(R.string.library_deleted), shadowOf(dialog()).message.toString())
    }

    @Test fun catalogQuerySurvivesDetailsAndRecreationWithoutChangingPinnedSelection() {
        val search = field(R.string.catalog_search)
        search.setText("cat.model.fixture")
        assertEquals(1, samples().size)
        samples().single().performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.selected_sample, "FICTIONAL Model")
        } }
        save()
        val saved = loaded()
        assertEquals("cat.model.fixture", saved.fields.selectedId)

        search.setText("cat.storage.fixture")
        assertEquals(1, samples().size)
        assertTrue(hasText(R.string.draft_saved))
        assertFalse(button(R.string.save_draft).isEnabled)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.selected_sample, "FICTIONAL Model")
        })
        samples().single().performClick()
        val details = dialog()
        details.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !details.isShowing }
        assertEquals("cat.storage.fixture", search.text.toString())
        recreate()
        await { hasText(R.string.draft_saved) && samples().size == 1 }
        assertEquals("cat.storage.fixture", field(R.string.catalog_search).text.toString())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.fields, loaded().fields)
    }

    @Test fun emptyCatalogSearchCanRecoverAndReloadUsesTheCurrentQuery() {
        assertEquals(29, samples().size)
        val search = field(R.string.catalog_search)
        search.setText(".* definitely absent")
        assertTrue(samples().isEmpty())
        assertTrue(hasText(R.string.catalog_search_empty))
        search.setText("")
        assertEquals(29, samples().size)

        button(R.string.retry_catalog).performClick()
        search.setText("cat.accelerator.fixture")
        await { button(R.string.retry_catalog).isEnabled }
        assertEquals(1, samples().size)
        assertEquals(activity.getString(R.string.item_button, "FICTIONAL Accelerator", "accelerator"),
            samples().single().text.toString())
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeTextRtlButtonsRetainMeasuredTargetsAndUntruncatedLabels() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        await { button(R.string.save_draft).isEnabled }
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        val buttons = views().filterIsInstance<Button>().filter { it.isShown }
        assertTrue(buttons.isNotEmpty())
        buttons.forEach { control ->
            assertTrue("Action label is nonempty", control.text.isNotBlank())
            assertTrue("Visible controls retain a 48dp measured height", control.height >= (48 * density).toInt())
            assertTrue("Visible controls retain a 48dp measured width", control.width >= (48 * density).toInt())
            assertTrue(control.width > control.compoundPaddingLeft + control.compoundPaddingRight)
            val label = control.layout
            assertNotNull(label)
            assertTrue(label.lineCount > 0)
            assertTrue("Native text layout has positive height", label.height > 0)
            assertTrue("Label fits its measured control", label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            repeat(label.lineCount) { line ->
                assertEquals("No ellipsized action label", 0, label.getEllipsisCount(line))
            }
        }
    }

    @Test fun forwardFocusOrderKeepsEditorsAndClearActionReachable() {
        val title = field(R.string.draft_title)
        val objective = field(R.string.context_objective)
        val context = field(R.string.context_context)
        assertTrue(title.requestFocus())
        assertSame(objective, title.focusSearch(View.FOCUS_FORWARD))
        assertTrue(objective.requestFocus())
        assertSame(context, objective.focusSearch(View.FOCUS_FORWARD))
        assertTrue(context.requestFocus())
        assertSame(button(R.string.clear_draft), context.focusSearch(View.FOCUS_FORWARD))
    }
}
