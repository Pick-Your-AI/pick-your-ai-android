package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import ai.pickyour.core.preview.*
import org.json.JSONObject
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

/** Actual tray and captured-value dialogs; no installed rendering or TalkBack claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogComparisonPresentationTest {
    private lateinit var controller: ActivityController<out Activity>
    private var section: CatalogComparisonSection? = null
    private var viewer: CatalogComparisonBrowser? = null
    private val activity get() = controller.get()

    class ForcedRtlActivity : Activity() {
        override fun attachBaseContext(base: Context) {
            val config = Configuration(base.resources.configuration).apply {
                setLayoutDirection(Locale.forLanguageTag("ar-EG"))
            }
            super.attachBaseContext(base.createConfigurationContext(config))
        }
    }

    @After fun close() {
        viewer?.retire()
        section?.close()
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun launch(forcedRtl: Boolean = false) {
        controller = if (forcedRtl) Robolectric.buildActivity(ForcedRtlActivity::class.java).setup()
            else Robolectric.buildActivity(Activity::class.java).setup()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun texts(root: View) = views(root).filterIsInstance<TextView>()
        .filterNot { it is Button }.map { it.text.toString() }
    private fun button(root: View, text: String) = views(root).filterIsInstance<Button>()
        .single { it.text.toString() == text }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertTrue(it.isShowing) }
    private fun root(window: AlertDialog = dialog()) = window.window!!.decorView
    private fun click(resource: Int) { button(root(), activity.getString(resource)).performClick(); idle() }
    private fun saved() = Bundle().also(requireNotNull(section)::save)
    // Literal API-28 output for these known-direction inputs, independent of the production formatter.
    private fun ltrInRtl(value: String) = "\u200f\u202a$value\u202c\u200f"
    private fun rtlInLtr(value: String) = "\u200e\u202b$value\u202c\u200e"
    private fun caption(index: Int, name: String, id: String, version: String) =
        activity.getString(R.string.catalog_compare_identity, index, name, id, version)

    private data class PairFixture(val catalog: PreviewCatalogBrowser, val first: PreviewCatalogDetails,
        val second: PreviewCatalogDetails)

    private fun fixture(firstName: String = "نموذج خيالي", secondName: String = "FICTIONAL second",
        firstVersion: String = "نسخة 2", secondVersion: String = "3.0"): PairFixture {
        val json = JSONObject(activity.assets.open("catalog.json").bufferedReader().use { it.readText() })
        val items = json.getJSONArray("items")
        val first = (0 until items.length()).map(items::getJSONObject).single { it.getString("kind") == "model" }
        val second = JSONObject(first.toString())
        first.put("name", firstName).put("version", firstVersion)
        second.put("name", secondName).put("version", secondVersion)
        first.getJSONObject("license").put("spdx_id", "MIT")
        second.getJSONObject("license").put("spdx_id", "Apache-2.0")
        items.put(second)
        val catalog = requireNotNull(PreviewCatalogBrowser.read(json.toString().toByteArray()))
        val pair = catalog.items.filter { it.id == first.getString("id") }
        assertEquals(listOf(firstVersion, secondVersion), pair.map { it.version })
        return PairFixture(catalog, requireNotNull(catalog.details(pair[0], catalog.hash)),
            requireNotNull(catalog.details(pair[1], catalog.hash)))
    }

    private fun tray(pair: PairFixture, saved: Bundle? = null,
        inspect: (PreviewCatalogPin, PreviewCatalogBrowser?, () -> Boolean) -> Unit = { _, _, _ -> }) {
        section = CatalogComparisonSection(activity, saved, inspect, dismissPinInspection = {})
        activity.setContentView(section)
        section!!.show(pair.catalog)
        if (saved == null) {
            section!!.add(pair.first.item, pair.catalog.hash)
            section!!.add(pair.second.item, pair.catalog.hash)
        }
    }
    private fun assertPins(bundle: Bundle, pair: PairFixture, versions: List<String>) {
        assertEquals(versions.map { pair.first.item.id }, bundle.getStringArrayList("comparisonIds"))
        assertEquals(versions, bundle.getStringArrayList("comparisonVersions"))
        assertEquals(versions.map { pair.catalog.hash }, bundle.getStringArrayList("comparisonHashes"))
    }

    @Test fun ltrTrayIsolatesRtlNameAndVersionWithoutChangingExactPinRemoval() {
        launch()
        val pair = fixture()
        tray(pair)
        val first = pair.first.item
        assertTrue(texts(section!!).contains(caption(1, rtlInLtr("\"${first.name}\""),
            "\"${first.id}\"", rtlInLtr("\"${first.version}\""))))
        val second = pair.second.item
        assertTrue(texts(section!!).contains(caption(2, "\"${second.name}\"", "\"${second.id}\"", "\"${second.version}\"")))
        assertPins(saved(), pair, listOf(first.version, second.version))
        val remove = button(section!!, activity.getString(R.string.catalog_compare_remove, 1,
            rtlInLtr("\"${first.version}\"")))
        remove.performClick(); idle()
        assertPins(saved(), pair, listOf(second.version))
        remove.performClick(); idle()
        assertPins(saved(), pair, listOf(second.version))
        assertTrue(texts(section!!).contains(caption(1, "\"${second.name}\"", "\"${second.id}\"", "\"${second.version}\"")))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun forcedRtlCaptionsUseScreenDirectionAndKeepLocalizedIndexAndRawState() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            launch(forcedRtl = true)
            assertEquals(Locale.US, activity.resources.configuration.locales[0])
            assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
            val pair = fixture("FICTIONAL first", "نموذج خيالي", "1.0", "نسخة 2")
            tray(pair)
            val first = pair.first.item
            val second = pair.second.item
            val firstCaption = caption(1, ltrInRtl("\"${first.name}\""), ltrInRtl("\"${first.id}\""),
                ltrInRtl("\"${first.version}\""))
            val secondCaption = caption(2, "\"${second.name}\"", ltrInRtl("\"${second.id}\""), "\"${second.version}\"")
            assertTrue(texts(section!!).contains(firstCaption))
            assertTrue(texts(section!!).contains(secondCaption))
            assertNotNull(button(section!!, activity.getString(R.string.catalog_compare_remove, 1,
                ltrInRtl("\"${first.version}\""))))
            assertNotNull(button(section!!, activity.getString(R.string.catalog_compare_remove, 2, "\"${second.version}\"")))
            button(section!!, activity.getString(R.string.catalog_compare_open)).performClick(); idle()
            assertTrue(texts(root()).contains(firstCaption)); assertTrue(texts(root()).contains(secondCaption))
            assertPins(saved(), pair, listOf(first.version, second.version))
            val state = saved()
            section!!.close()
            tray(pair, state)
            assertTrue(texts(section!!).contains(firstCaption)); assertTrue(texts(section!!).contains(secondCaption))
            assertPins(saved(), pair, listOf(first.version, second.version))
        } finally { Locale.setDefault(oldLocale) }
    }

    @Test fun valueNavigationKeepsCapturedSidesAndRetiresControlsWithIsolatedCaptions() {
        launch()
        val pair = fixture("מודל בדוי", "מודל בדוי", "גרסה 1", "גרסה 2")
        val comparison = PreviewCatalogComparison(pair.first, pair.second)
        val changes = mutableListOf<Boolean>()
        viewer = CatalogComparisonBrowser(activity, comparison, null, false, { true }, changes::add)
        viewer!!.show(); idle()
        fun expected(index: Int, detail: PreviewCatalogDetails) = caption(index,
            rtlInLtr("\"${detail.item.name}\""), "\"${detail.item.id}\"", rtlInLtr("\"${detail.item.version}\""))
        assertTrue(texts(root()).contains(expected(1, pair.first)))
        assertTrue(texts(root()).contains(expected(2, pair.second)))
        val toggle = views(root()).filterIsInstance<CheckBox>().single()
        toggle.isChecked = true; idle()
        assertEquals(listOf(true), changes)
        val row = comparison.rows.single { it.name == "spdx_id" }
        assertEquals(PreviewCatalogComparisonMatch.DIFFERENT_RECORDED_VALUE, row.match)
        val openSecond = button(root(), activity.getString(R.string.catalog_compare_inspect_value, 2, "spdx_id"))
        openSecond.performClick(); idle()
        assertTrue(texts(root()).contains(expected(2, pair.second)))
        assertEquals("\"\\\"Apache-2.0\\\"\"", root().findViewById<TextView>(R.id.catalog_compare_value_literal).text.toString())
        val other = button(root(), activity.getString(R.string.catalog_compare_other_value, 1))
        other.performClick(); idle()
        assertTrue(texts(root()).contains(expected(1, pair.first)))
        assertEquals("\"\\\"MIT\\\"\"", root().findViewById<TextView>(R.id.catalog_compare_value_literal).text.toString())
        val current = dialog()
        openSecond.performClick(); other.performClick(); idle()
        assertSame(current, dialog())
        click(R.string.catalog_compare_back_values)
        assertTrue(views(root()).filterIsInstance<CheckBox>().single().isChecked)
        assertTrue(texts(root()).contains(expected(2, pair.second)))
        val oldDialog = dialog()
        val stale = button(root(), activity.getString(R.string.catalog_compare_inspect_value, 2, "spdx_id"))
        viewer!!.retire(); stale.performClick(); idle()
        assertFalse(oldDialog.isShowing)
        assertSame(oldDialog, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("\"MIT\"", row.left!!.valueJson)
        assertEquals("\"Apache-2.0\"", row.right!!.valueJson)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun unavailableTrayCaptionsKeepExactRecoveryPinsAndCurrentness() {
        launch(forcedRtl = true)
        val pair = fixture("FICTIONAL first", "نموذج خيالي", "1.0", "نسخة 2")
        var captured: PreviewCatalogPin? = null
        var capturedBrowser: PreviewCatalogBrowser? = pair.catalog
        var current: (() -> Boolean)? = null
        tray(pair, inspect = { pin, catalog, check -> captured = pin; capturedBrowser = catalog; current = check })
        val state = saved()
        section!!.show(null)
        val first = pair.first.item
        val expected = caption(1, ltrInRtl(activity.getString(R.string.catalog_compare_missing_name)),
            ltrInRtl("\"${first.id}\""), ltrInRtl("\"${first.version}\""))
        assertTrue(texts(section!!).contains(expected))
        assertFalse(button(section!!, activity.getString(R.string.catalog_compare_open)).isEnabled)
        val inspect = button(section!!, activity.getString(R.string.catalog_pin_inspect, 1))
        inspect.performClick(); idle()
        assertEquals(PreviewCatalogPin(first.id, first.version, pair.catalog.hash), captured)
        assertNull(capturedBrowser); assertTrue(current!!.invoke())
        section!!.show(pair.catalog)
        assertFalse(current!!.invoke())
        assertTrue(button(section!!, activity.getString(R.string.catalog_compare_open)).isEnabled)
        assertPins(saved(), pair, listOf(first.version, pair.second.item.version))
        section!!.close()
        inspect.performClick(); idle()
        assertFalse(current!!.invoke())
        tray(pair, state)
        assertPins(saved(), pair, listOf(first.version, pair.second.item.version))
        assertFalse(saved().getBoolean("comparisonDifferencesOnly"))
    }
}
