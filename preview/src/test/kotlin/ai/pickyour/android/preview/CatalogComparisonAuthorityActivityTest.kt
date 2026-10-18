package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

/** Real preview controls in a JVM simulation; no installed-device or external-source claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogComparisonAuthorityActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && samples().isNotEmpty() }
        field(R.string.draft_title).setText("acknowledged comparison baseline")
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.draft_saved) } }
        field(R.string.context_objective).setText("pending comparison objective")
        field(R.string.workload_budget).setText("12.00")
        for (kind in listOf("model", "compute_profile")) {
            val parent = openDetail(kind)
            button(R.string.catalog_compare_add, parent.window!!.decorView).performClick(); idle()
            assertFalse(parent.isShowing)
        }
        assertTrue(button(R.string.catalog_compare_open).isEnabled)
        assertEquals(listOf("cat.model.fixture", "cat.compute_profile.fixture"), state().getStringArrayList("comparisonIds"))
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun currentPriceAndSourceControlsWorkBeforeComparisonWithoutSaving() {
        val parent = openDetail("compute_profile")
        button(R.string.catalog_price_browse, parent.window!!.decorView).performClick(); idle()
        val prices = dialog()
        assertTrue(prices.listView.adapter.count > 0)
        val before = state()
        val baseline = loaded().baselineIdentity
        shadowOf(prices).clickOnItem(0); idle()
        val fields = dialog()
        assertNotSame(prices, fields)
        assertTrue(fields.isShowing)
        assertTrue((0 until fields.listView.adapter.count).any {
            fields.listView.adapter.getItem(it).toString().contains("pricebook.id")
        })
        assertStable(before, baseline)
        fields.cancel(); idle()

        val source = sourceMissingMessage()
        val sourceState = state()
        source.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertNotSame(source, dialog())
        assertTrue(dialog().isShowing)
        assertEquals(1, dialog().listView.adapter.count)
        assertStable(sourceState, baseline)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun comparisonRetiresPriceControlsBeforeTheyCanReplaceItsDialog() {
        val parent = openDetail("compute_profile")
        button(R.string.catalog_price_browse, parent.window!!.decorView).performClick(); idle()
        val prices = dialog()
        assertTrue(prices.listView.adapter.count > 0)
        val before = state()
        val baseline = loaded().baselineIdentity
        button(R.string.catalog_compare_open).performClick(); idle()
        val comparison = dialog()

        shadowOf(prices).clickOnItem(0); idle()

        assertSame("A retired price row cannot replace the current comparison", comparison, dialog())
        assertTrue(comparison.isShowing)
        assertFalse("Comparison ends the previous price inspection", prices.isShowing)
        assertStable(before, baseline)
    }

    @Test fun comparisonRetiresDetailSelectionBeforeItCanChangePendingFields() {
        val parent = openDetail("compute_profile")
        val oldSelect = parent.getButton(AlertDialog.BUTTON_POSITIVE)
        val before = state()
        val baseline = loaded().baselineIdentity
        button(R.string.catalog_compare_open).performClick(); idle()
        val comparison = dialog()

        oldSelect.performClick(); idle()

        assertSame(comparison, dialog())
        assertTrue(comparison.isShowing)
        assertStable(before, baseline)
        assertFalse("Comparison ends the previous detail parent", parent.isShowing)
    }

    @Test fun comparisonRetiresSourceRecoveryBeforeItCanReopenTheChooser() {
        val source = sourceMissingMessage()
        val retainedBack = source.getButton(AlertDialog.BUTTON_POSITIVE)
        val before = state()
        val baseline = loaded().baselineIdentity
        button(R.string.catalog_compare_open).performClick(); idle()
        val comparison = dialog()

        retainedBack.performClick(); idle()

        assertSame("Retired source recovery cannot reopen a chooser over comparison", comparison, dialog())
        assertTrue(comparison.isShowing)
        assertStable(before, baseline)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun sourceMissingMessage(): AlertDialog {
        val parent = openDetail("compute_profile")
        parent.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        val sources = dialog()
        assertEquals(1, sources.listView.adapter.count)
        shadowOf(sources).clickOnItem(0); idle()
        val missing = dialog()
        assertEquals(activity.getString(R.string.catalog_source_missing), shadowOf(missing).message.toString())
        assertEquals(activity.getString(R.string.catalog_source_back), missing.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        return missing
    }

    private fun openDetail(kind: String): AlertDialog {
        field(R.string.catalog_search).setText("cat.$kind.fixture")
        await { samples().size == 1 }
        samples().single().performClick(); idle()
        return dialog().also { assertTrue(it.isShowing) }
    }

    private fun assertStable(before: Bundle, baseline: String) {
        val after = state()
        val strings = listOf("draftId", "draftBaseline", "title", "objective", "contextInput", "selectedId",
            "selectedVersion", "selectedCatalogHash", "pendingDeleteId", "catalogQuery", "catalogTier",
            "catalogKind", "catalogSort", "catalogSnapshot", "workload.classification") + (0..7).map { "workload.$it" }
        for (key in strings) assertEquals(key, before.getString(key), after.getString(key))
        for (key in listOf("entryBlocked", "unsupportedEntry", "selectionResolved", "draftRequiresReopen",
                "contextInputsPresent", "workloadInputsPresent", "workload.rejectedEdit", "comparisonDifferencesOnly")) {
            assertEquals(key, before.getBoolean(key), after.getBoolean(key))
        }
        for (key in listOf("comparisonIds", "comparisonVersions", "comparisonHashes")) {
            assertEquals(key, before.getStringArrayList(key), after.getStringArrayList(key))
        }
        assertEquals(before.getInt("catalogPage"), after.getInt("catalogPage"))
        assertEquals(baseline, loaded().baselineIdentity)
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    private fun state() = Bundle().also { controller.saveInstanceState(it) }
    private fun loaded() = (PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load() as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun samples() = views().filterIsInstance<CatalogBrowseSection>().single().let(::views)
        .filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL ") }
    private fun button(label: Int, root: View = activity.window.decorView) = views(root).filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the current preview state")
    }
}
