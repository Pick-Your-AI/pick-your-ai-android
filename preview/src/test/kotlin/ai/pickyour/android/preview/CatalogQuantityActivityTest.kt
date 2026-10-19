package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
import org.json.JSONArray
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

/** Actual simulated catalog controls and validated inputs; no installed or translation-acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogQuantityActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val pendingTitle = "Pending catalog quantity inspection"
    private var pendingSince = 0L

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.catalog_next).isEnabled }
        assertTrue(libraryState() is PreviewDraftLoad.Missing)
        // Keep these read-only inspections before the independent title autosave deadline.
        AutosaveUiClock.pause()
        pendingSince = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText(pendingTitle)
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun browse() = views().filterIsInstance<CatalogBrowseSection>().single()
    private fun browseStatus() = views(browse()).filterIsInstance<TextView>()
        .single { it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }.text.toString()
    private fun catalogJson() = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun catalog() = activity.assets.open("catalog.json").use { requireNotNull(PreviewCatalogBrowser.read(it.readBytes())) }
    private fun details(sourceCount: Int? = null): PreviewCatalogDetails {
        val input = catalogJson()
        val items = input.getJSONArray("items")
        val item = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "compute_profile" }
        if (sourceCount != null) item.put("provenance", JSONArray().also { sources ->
            repeat(sourceCount) { index ->
                sources.put(JSONObject().put("source_uri", "https://example.invalid/source/$index")
                    .put("checked_at", "2026-10-16T09:00:00Z").put("verification_status", "user_supplied"))
            }
        })
        val browser = requireNotNull(PreviewCatalogBrowser.read(input.toString().toByteArray()))
        return requireNotNull(browser.details(browser.items.single { it.kind == "compute_profile" }, browser.hash))
    }
    private fun alternatives(count: Int): JSONObject {
        val input = activity.assets.open("pricebook.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val prototype = input.getJSONArray("rates").getJSONObject(0)
        input.put("rates", JSONArray().also { rates ->
            repeat(count) { rates.put(JSONObject(prototype.toString()).put("id", "rate.quantity.$it")) }
        })
        return input
    }
    private fun prices(input: JSONObject) =
        (RecordedPricebook.read(input.toString().byteInputStream()) as RecordedPricebookResult.Parsed).pricebook
    private fun showPrices(input: JSONObject): RecordedPricebook {
        val book = prices(input)
        CatalogPriceBrowser(activity, details(), book) { true }.show()
        idle()
        return book
    }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun title() = shadowOf(dialog()).title.toString()
    private fun rows() = (0 until dialog().listView.adapter.count).map { dialog().listView.adapter.getItem(it).toString() }
    private fun idle() = AutosaveUiClock.idle()
    private fun click(which: Int) { dialog().getButton(which).performClick(); idle() }
    private fun choose(index: Int) { shadowOf(dialog()).clickOnItem(index); idle() }
    private fun inspect(name: String): String {
        // Reach the value through the same bounded field pages and current-window controls as a user.
        while (dialog().getButton(AlertDialog.BUTTON_NEUTRAL).visibility == View.VISIBLE) click(AlertDialog.BUTTON_NEUTRAL)
        repeat(20) {
            val index = rows().indexOfFirst { it.startsWith("$name\n") }
            if (index >= 0) {
                choose(index)
                assertEquals(name, title())
                return shadowOf(dialog()).message.toString().split("\n\n")[1]
            }
            assertEquals(View.VISIBLE, dialog().getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            click(AlertDialog.BUTTON_POSITIVE)
        }
        error("Field is unreachable through its controls: $name")
    }
    private fun libraryState() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()
    private fun assertReadOnly() {
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
        assertEquals(pendingTitle, field(R.string.draft_title).text.toString())
        assertTrue(libraryState() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit catalog state")
    }

    @Test fun browseCountsDistinguishUnavailableEmptySingleAndPagedTotals() {
        val fixture = catalog()
        browse().unavailable()
        assertEquals(activity.getString(R.string.catalog_browse_waiting), browseStatus())
        assertFalse(button(R.string.catalog_next).isEnabled)
        browse().show(fixture)
        field(R.string.catalog_search).setText("no.quantity.match")
        assertEquals(activity.getString(R.string.catalog_search_empty), browseStatus())
        assertFalse(button(R.string.catalog_next).isEnabled)
        field(R.string.catalog_search).setText("cat.compute_profile.fixture")
        assertEquals("Synthetic results: page 1 of 1 · 1 matching item", browseStatus())
        field(R.string.catalog_search).setText("")
        val total = fixture.items.size
        assertTrue(total > 8)
        val first = browseStatus()
        assertTrue(first.endsWith("· $total matching items"))
        button(R.string.catalog_next).performClick()
        assertNotEquals(first, browseStatus())
        assertTrue("Page changes retain the full matching count", browseStatus().endsWith("· $total matching items"))
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == "Choose up to 2 synthetic components from their details. Comparison does not change or save your draft."
        })
        assertReadOnly()
    }

    @Test fun sourceCountsUseTotalRecordsIncludingTheSingleRowOnTheLastPage() {
        CatalogSourceNavigation(activity, details(1), isCurrent = { true }).show()
        assertEquals("Sources · page 1 of 1 · 1 record", title())
        assertEquals(1, rows().size)
        click(AlertDialog.BUTTON_NEGATIVE)
        CatalogSourceNavigation(activity, details(9), isCurrent = { true }).show()
        assertEquals("Sources · page 1 of 2 · 9 records", title())
        assertEquals(8, rows().size)
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals("Sources · page 2 of 2 · 9 records", title())
        assertEquals(1, rows().size)
        click(AlertDialog.BUTTON_NEUTRAL)
        assertEquals("Sources · page 1 of 2 · 9 records", title())
        assertReadOnly()
    }

    @Test fun priceCountsKeepUnknownAndNoExactRateDistinctFromSingleAndPagedRecords() {
        CatalogPriceBrowser(activity, details(), null) { true }.show()
        assertEquals(activity.getString(R.string.catalog_price_unavailable), shadowOf(dialog()).message.toString())
        click(AlertDialog.BUTTON_NEGATIVE)
        val unmatched = alternatives(1)
        unmatched.getJSONArray("rates").getJSONObject(0).put("catalog_item_version", "different")
        showPrices(unmatched)
        assertEquals(activity.getString(R.string.catalog_price_no_record), shadowOf(dialog()).message.toString())
        click(AlertDialog.BUTTON_NEGATIVE)
        showPrices(alternatives(1))
        assertEquals("Fictional recorded rates · page 1 of 1 · 1 record", title())
        assertEquals(1, rows().size)
        click(AlertDialog.BUTTON_NEGATIVE)
        showPrices(alternatives(9))
        assertEquals("Fictional recorded rates · page 1 of 2 · 9 records", title())
        assertEquals(8, rows().size)
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals("Fictional recorded rates · page 2 of 2 · 9 records", title())
        assertEquals(1, rows().size)
        choose(0)
        assertEquals("\"0.80\"", inspect("rate.unit_price.amount"))
        click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals("\"USD\"", inspect("rate.unit_price.currency"))
        assertReadOnly()
    }

    @Test fun conditionCountsPreserveAbsentEmptyAndLiteralOrderWithoutEligibilityClaims() {
        val suffix = " retained in stored order. Each following condition can be inspected; eligibility is not established."
        for (count in listOf<Int?>(null, 0, 1, 2)) {
            val input = alternatives(1)
            val rate = input.getJSONArray("rates").getJSONObject(0)
            rate.remove("conditions")
            if (count != null) rate.put("conditions", JSONArray().also { conditions ->
                repeat(count) { conditions.put("literal condition $it") }
            })
            showPrices(input)
            choose(0)
            val expected = when (count) {
                null -> activity.getString(R.string.catalog_price_conditions_missing)
                0 -> activity.getString(R.string.catalog_price_conditions_empty)
                1 -> "1 literal condition$suffix"
                else -> "2 literal conditions$suffix"
            }
            assertEquals(expected, inspect(activity.getString(R.string.catalog_price_conditions_state)))
            click(AlertDialog.BUTTON_NEGATIVE)
            repeat(count ?: 0) { index ->
                assertEquals("\"literal condition $index\"", inspect("rate.conditions[$index]"))
                click(AlertDialog.BUTTON_NEGATIVE)
            }
            click(AlertDialog.BUTTON_NEGATIVE)
            click(AlertDialog.BUTTON_NEGATIVE)
        }
        assertReadOnly()
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    fun localizedCountsKeepRecordedMoneyAndCurrencyLiteral() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        field(R.string.catalog_search).setText("cat.compute_profile.fixture")
        assertEquals("Synthetic results: page ١ of ١ · ١ matching item", browseStatus())
        assertTrue(views().filterIsInstance<TextView>().any {
            it.text.toString() == "Choose up to ٢ synthetic components from their details. Comparison does not change or save your draft."
        })
        CatalogSourceNavigation(activity, details(1), isCurrent = { true }).show()
        assertEquals("Sources · page ١ of ١ · ١ record", title())
        click(AlertDialog.BUTTON_NEGATIVE)
        val book = showPrices(alternatives(9))
        assertEquals("Fictional recorded rates · page ١ of ٢ · ٩ records", title())
        click(AlertDialog.BUTTON_POSITIVE)
        assertEquals("Fictional recorded rates · page ٢ of ٢ · ٩ records", title())
        assertEquals(1, rows().size)
        choose(0)
        assertEquals("\"0.80\"", inspect("rate.unit_price.amount"))
        click(AlertDialog.BUTTON_NEGATIVE)
        assertEquals("\"USD\"", inspect("rate.unit_price.currency"))
        assertEquals("0.80", book.rates.last().unitPrice.amount.encoded)
        assertEquals("USD", book.rates.last().unitPrice.currency)
        assertReadOnly()
    }
}
