package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewCatalogItem
import ai.pickyour.core.preview.PreviewCatalogKinds
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Locale

/** Real resources and native controls with synthetic rows; no installed rendering or draft write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogBrowsePresentationTest {
    private lateinit var controller: ActivityController<out Activity>
    private lateinit var section: CatalogBrowseSection
    private val activity get() = controller.get()
    private val hash = "b".repeat(64)
    private val latin = PreviewCatalogItem("fictional-model", "FICTIONAL Latin model", "model", "1.0", "T1")
    private val arabic = PreviewCatalogItem("fictional-model", "نموذج خيالي", "model", "نسخة 2", "T1")
    private val catalog = PreviewCatalogBrowser(hash, listOf(latin, arabic))
    private val dispatches = mutableListOf<Pair<PreviewCatalogItem, String>>()

    class ForcedRtlActivity : Activity() {
        override fun attachBaseContext(base: Context) {
            val config = Configuration(base.resources.configuration).apply {
                setLayoutDirection(Locale.forLanguageTag("ar-EG"))
            }
            super.attachBaseContext(base.createConfigurationContext(config))
        }
    }

    @After fun close() {
        ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }?.dismiss()
        if (::section.isInitialized) section.close()
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun launch(saved: Bundle? = null, forcedRtl: Boolean = false) {
        controller = if (forcedRtl) Robolectric.buildActivity(ForcedRtlActivity::class.java).setup()
            else Robolectric.buildActivity(Activity::class.java).setup()
        section = CatalogBrowseSection(activity, saved) { item, snapshot -> dispatches += item to snapshot }
        activity.setContentView(section)
        section.show(catalog)
        section.selectionEnabled(true)
    }

    private fun facet(index: Int) = section.getChildAt(index) as Button
    private fun row(item: PreviewCatalogItem): Button {
        val results = section.getChildAt(4) as LinearLayout
        return (0 until results.childCount).map { results.getChildAt(it) as Button }
            .single { it.text.toString().contains(item.name) }
    }
    private fun openFacet(index: Int): AlertDialog {
        assertTrue(facet(index).performClick())
        return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertTrue(it.isShowing) }
    }
    private fun labels(dialog: AlertDialog) = (0 until dialog.listView.adapter.count)
        .map { dialog.listView.adapter.getItem(it).toString() }
    private fun cancel(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }
    private fun choose(dialog: AlertDialog, index: Int) {
        shadowOf(dialog).clickOnItem(index)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }
    private fun saved() = Bundle().also(section::saveState)
    private fun checkedBundle() = Bundle().apply {
        putString("catalogTier", "T1")
        putString("catalogKind", "model")
        putString("catalogSort", "NAME")
    }
    private fun assertRawState(value: Bundle, tier: String?, kind: String?, query: String = "") {
        assertEquals(tier, value.getString("catalogTier"))
        assertEquals(kind, value.getString("catalogKind"))
        assertEquals(query, value.getString("catalogQuery"))
        assertEquals(hash, value.getString("catalogSnapshot"))
        assertEquals(0, value.getInt("catalogPage"))
    }
    // Literal API-28 BidiFormatter output; expected text never calls the production formatter.
    private fun ltrInRtl(value: String) = "\u200f\u202a$value\u202c\u200f"
    private fun rtlInLtr(value: String) = "\u200e\u202b$value\u202c\u200e"

    @Test fun mixedDirectionRowsPreserveExactCatalogObjectsAndSnapshot() {
        launch()
        val chosen = row(arabic)
        assertEquals("Open ${rtlInLtr(arabic.name)} · model", chosen.text.toString())
        assertEquals("Open ${latin.name} · model", row(latin).text.toString())
        chosen.performClick()
        assertSame(arabic, dispatches.single().first)
        assertEquals(hash, dispatches.single().second)
        assertEquals("نسخة 2", dispatches.single().first.version)
        assertRawState(saved(), null, null)
        section.unavailable()
        chosen.performClick()
        assertEquals(1, dispatches.size)
        section.show(catalog)
        chosen.performClick()
        assertEquals(1, dispatches.size)
        row(arabic).performClick()
        assertSame(arabic, dispatches.last().first)
        assertEquals(2, dispatches.size)
    }

    @Test fun forcedRtlFacetsAndKindChoicesKeepCanonicalSavedState() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            launch(checkedBundle(), forcedRtl = true)
            assertEquals(Locale.US, activity.resources.configuration.locales[0])
            assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
            assertEquals("Tier: ${ltrInRtl("T1")}", facet(0).text.toString())
            assertEquals("Kind: ${ltrInRtl("model")}", facet(1).text.toString())
            val picker = openFacet(1)
            val expected = listOf(activity.getString(R.string.catalog_all_kinds)) + PreviewCatalogKinds.all.map {
                "${ltrInRtl(it.tier)} · ${ltrInRtl(it.id)}"
            }
            assertEquals(expected, labels(picker))
            assertEquals(1 + PreviewCatalogKinds.all.indexOfFirst { it.id == "model" }, picker.listView.checkedItemPosition)
            cancel(picker)
            assertRawState(saved(), "T1", "model")
            assertEquals("NAME", saved().getString("catalogSort"))
            val replacement = PreviewCatalogKinds.all.first { it.id != "model" }
            choose(openFacet(1), 1 + PreviewCatalogKinds.all.indexOf(replacement))
            assertRawState(saved(), "T1", replacement.id)
            choose(openFacet(1), 0)
            assertRawState(saved(), "T1", null)
            assertEquals(activity.getString(R.string.catalog_kind, activity.getString(R.string.catalog_all_kinds)),
                facet(1).text.toString())
            val tiers = openFacet(0)
            assertEquals(listOf(activity.getString(R.string.catalog_all_tiers)) + PreviewCatalogKinds.tiers, labels(tiers))
            choose(tiers, 0)
            assertRawState(saved(), null, null)
            assertTrue(dispatches.isEmpty())
        } finally { Locale.setDefault(previous) }
    }

    @Test @Config(qualifiers = "ar-rEG")
    fun rtlRowsAndRecreatedChoicesKeepNaturalNamesAndRetireOldControls() {
        launch(checkedBundle())
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        assertEquals("Open ${arabic.name} · ${ltrInRtl("model")}", row(arabic).text.toString())
        assertEquals("Open ${ltrInRtl(latin.name)} · ${ltrInRtl("model")}", row(latin).text.toString())
        val oldRow = row(latin)
        section.search("نموذج")
        oldRow.performClick()
        assertTrue(dispatches.isEmpty())
        val checkpoint = saved()
        assertRawState(checkpoint, "T1", "model", "نموذج")
        val oldPicker = openFacet(1)
        val oldList = oldPicker.listView
        val oldClick = requireNotNull(oldList.onItemClickListener)
        val oldSection = section
        oldSection.close()
        controller.pause().stop().destroy()
        launch(checkpoint)
        val currentPicker = openFacet(1)
        val before = saved()
        oldRow.performClick()
        oldClick.onItemClick(oldList, null, 0, oldList.adapter.getItemId(0))
        assertSame(currentPicker, ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(currentPicker.isShowing)
        assertTrue(dispatches.isEmpty())
        assertRawState(saved(), "T1", "model", "نموذج")
        assertEquals(before.getString("catalogSort"), saved().getString("catalogSort"))
        cancel(currentPicker)
        row(arabic).performClick()
        assertSame(arabic, dispatches.single().first)
        assertEquals(hash, dispatches.single().second)
        val oldState = Bundle().also(oldSection::saveState)
        assertRawState(oldState, "T1", "model", "نموذج")
    }

    @Test fun ltrRowsAndResourceChoicesPreserveAllLabelsAndClosedState() {
        launch()
        assertEquals("Open ${latin.name} · model", row(latin).text.toString())
        assertEquals(activity.getString(R.string.catalog_tier, activity.getString(R.string.catalog_all_tiers)), facet(0).text.toString())
        assertEquals(activity.getString(R.string.catalog_kind, activity.getString(R.string.catalog_all_kinds)), facet(1).text.toString())
        assertEquals(activity.getString(R.string.catalog_sort, activity.getString(R.string.catalog_sort_fixture)), facet(2).text.toString())
        val picker = openFacet(1)
        assertEquals(listOf(activity.getString(R.string.catalog_all_kinds)) + PreviewCatalogKinds.all.map {
            activity.getString(R.string.catalog_browse_kind_choice, it.tier, it.id)
        }, labels(picker))
        assertEquals(0, picker.listView.checkedItemPosition)
        val oldList = picker.listView
        val oldClick = requireNotNull(oldList.onItemClickListener)
        cancel(picker)
        val current = openFacet(1)
        oldClick.onItemClick(oldList, null, 1, oldList.adapter.getItemId(1))
        assertSame(current, ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(current.isShowing)
        assertRawState(saved(), null, null)
        val currentList = current.listView
        val currentClick = requireNotNull(currentList.onItemClickListener)
        val oldRow = row(latin)
        section.close()
        currentClick.onItemClick(currentList, null, 1, currentList.adapter.getItemId(1))
        oldRow.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(current.isShowing)
        assertRawState(saved(), null, null)
        assertTrue(dispatches.isEmpty())
    }
}
