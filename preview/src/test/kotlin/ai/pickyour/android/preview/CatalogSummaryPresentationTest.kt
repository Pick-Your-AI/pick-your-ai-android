package ai.pickyour.android.preview

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Validated synthetic inputs and actual Android resources; no installed-layout or translation claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
class CatalogSummaryPresentationTest {
    private fun context(locale: Locale = Locale.US): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(locale))
        return app.createConfigurationContext(config)
    }

    private fun fixture(name: String) = context().assets.open("$name.json")
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun detail(count: Int = 1, description: String? = "Catalog summary"): PreviewCatalogDetails {
        val input = fixture("catalog")
        val items = input.getJSONArray("items")
        val item = (0 until items.length()).map { items.getJSONObject(it) }
            .single { it.getString("kind") == "compute_profile" }
        if (description == null) item.remove("description") else item.put("description", description)
        item.put("provenance", JSONArray().also { sources ->
            repeat(count) { index -> sources.put(JSONObject()
                .put("source_uri", "https://example.invalid/source/$index")
                .put("checked_at", "2026-10-16T09:00:00Z")
                .put("verification_status", "user_supplied")) }
        })
        val browser = requireNotNull(PreviewCatalogBrowser.read(input.toString().toByteArray()))
        return requireNotNull(browser.details(browser.items.single { it.kind == "compute_profile" }, browser.hash))
    }

    private fun book(count: Int = 1, conditions: Int? = 1, amount: String = "1.00", region: String? = "us-east"):
        RecordedPricebook {
        val input = fixture("pricebook")
        val prototype = input.getJSONArray("rates").getJSONObject(0)
        input.put("rates", JSONArray().also { rates ->
            repeat(maxOf(1, count)) { index ->
                val rate = JSONObject(prototype.toString()).put("id", "rate.summary.$index")
                // A frozen pricebook must contain a rate; zero exact matches uses a different item.
                if (count == 0) rate.put("catalog_item_id", "cat.summary.other")
                rate.getJSONObject("unit_price").put("amount", amount)
                if (region == null) rate.remove("region") else rate.put("region", region)
                if (conditions == null) rate.remove("conditions") else rate.put("conditions", JSONArray().also { values ->
                    repeat(conditions) { values.put("literal condition $it") }
                })
                rates.put(rate)
            }
        })
        val result = RecordedPricebook.read(input.toString().byteInputStream())
        assertTrue("Synthetic pricebook must pass the existing adapter", result is RecordedPricebookResult.Parsed)
        return (result as RecordedPricebookResult.Parsed).pricebook
    }

    @Test fun singleRetainedRecordsUseSingularSummaryResources() {
        val detail = detail()
        val prices = book()
        val text = CatalogDetailText.describe(context(), detail, prices)
        assertTrue(text.contains("Provenance record displayed: 1 / 1."))
        assertTrue(text.contains("Exact item/version price record displayed: 1 / 1."))
        assertTrue(text.contains("Condition displayed: 1 / 1."))
    }

    @Test fun noExactRatesAndBoundedSummaryCountsRetainTheFullPluralTotal() {
        val emptyText = CatalogDetailText.describe(context(), detail(), book(0))
        assertTrue(emptyText.contains("Exact item/version price records displayed: 0 / 0."))
        val detail = detail(10)
        val prices = book(10, 10)
        val text = CatalogDetailText.describe(context(), detail, prices)
        assertTrue(text.contains("Provenance records displayed: 8 / 10."))
        assertTrue(text.contains("Exact item/version price records displayed: 8 / 10."))
        assertTrue(text.contains("Conditions displayed: 8 / 10."))
        assertTrue(text.contains("\"rate.summary.7\""))
        assertFalse(text.contains("rate.summary.8"))
        assertFalse(text.contains("literal condition 8"))
        assertEquals(10, detail.provenance.size)
        assertEquals(10, prices.rates.size)
        assertEquals(10, prices.rates.last().conditions!!.size)
    }

    @Test fun missingEmptyAndZeroPriceMetadataKeepTheirDistinctMeaning() {
        val detail = detail()
        assertEquals(context().getString(R.string.catalog_price_unavailable),
            CatalogPriceText.describe(context(), detail.item, null))
        val empty = CatalogPriceText.describe(context(), detail.item, book(0))
        assertTrue(empty.contains(context().getString(R.string.catalog_price_no_record)))
        val absent = CatalogPriceText.describe(context(), detail.item, book(conditions = null, region = null))
        assertTrue(absent.contains(context().getString(R.string.catalog_price_conditions_missing)))
        assertTrue(absent.contains("Region: Not supplied; unknown"))
        val zero = book(conditions = 0, amount = "0.00")
        val text = CatalogPriceText.describe(context(), detail.item, zero)
        assertTrue(text.contains(context().getString(R.string.catalog_price_conditions_empty)))
        assertTrue(text.contains("Recorded unit_price.amount: \"0.00\""))
        assertTrue(text.contains("not an accepted free or complete quote"))
        assertEquals("0.00", zero.rates.single().unitPrice.amount.encoded)
    }

    @Test fun rtlSummariesIsolateEscapedValuesUsingTheScreenLocale() {
        val source = "مرحبا\u202e\nline\u2066x\u2069"
        val detail = detail(description = source)
        val prices = book(region = source)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            val rtl = context(Locale.forLanguageTag("ar-EG"))
            val text = CatalogDetailText.describe(rtl, detail, prices)
            assertTrue(text.contains("مرحبا\\u202e\\nline\\u2066x\\u2069"))
            assertFalse(text.contains('\u202e'))
            assertFalse(text.contains('\u2066'))
            assertFalse(text.contains('\u2069'))
            assertTrue("LTR record fragments must be bounded inside the RTL screen", text.contains('\u202a'))
            assertEquals(text.count { it == '\u202a' }, text.count { it == '\u202c' })
            assertTrue(text.contains("\u202a\"https://example.invalid/source/0\"\u202c"))
            assertTrue(text.contains("\u202a\"1.00\"\u202c"))
            assertEquals(source, detail.description)
            assertEquals(source, prices.rates.single().region)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun localeChangesPreserveCanonicalMoneySnapshotsAndStoredLiteralValues() {
        val detail = detail(description = "\"quoted\"" + "x".repeat(500))
        val prices = book(amount = "12345678901234567890.123456789012")
        val identity = listOf(detail.snapshotHash, prices.hash, prices.id, prices.revision.toString())
        val amount = prices.rates.single().unitPrice.amount.encoded
        val currency = prices.rates.single().unitPrice.currency
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
            val text = CatalogDetailText.describe(context(locale), detail, prices)
            assertTrue(text.contains("\"$amount\""))
            assertTrue(text.contains(currency))
            assertTrue(text.contains(detail.snapshotHash))
            assertTrue(text.contains(prices.hash))
            assertTrue(text.contains("[display truncated]"))
            assertFalse(text.contains("x".repeat(161)))
            assertEquals(identity, listOf(detail.snapshotHash, prices.hash, prices.id, prices.revision.toString()))
            assertEquals(amount, prices.rates.single().unitPrice.amount.encoded)
            assertEquals(currency, prices.rates.single().unitPrice.currency)
            assertEquals("\"quoted\"" + "x".repeat(500), detail.description)
        }
    }

    @Test fun forcedRtlLayoutKeepsEnglishCountsAndWrapsCanonicalValues() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.US))
        config.setLayoutDirection(Locale.forLanguageTag("ar-EG"))
        val screen = app.createConfigurationContext(config)
        assertEquals(Locale.US, screen.resources.configuration.locales[0])
        assertEquals(View.LAYOUT_DIRECTION_RTL, screen.resources.configuration.layoutDirection)
        val text = CatalogDetailText.describe(screen, detail(), book())
        assertTrue(text.contains("Provenance record displayed: 1 / 1."))
        assertTrue(text.contains("Exact item/version price record displayed: 1 / 1."))
        assertTrue(text.contains("\u202a\"1.00\"\u202c"))
        assertTrue(text.contains("\u202a\"https://example.invalid/source/0\"\u202c"))
    }

    @Test fun summaryWrappingDoesNotChangeCompleteValueBrowserLiterals() {
        val rtl = context(Locale.forLanguageTag("ar-EG"))
        assertEquals("\"\"", CatalogDetailText.literal(rtl, ""))
        assertEquals("\"مرحبا\\u202e\"", CatalogDetailText.literal(rtl, "مرحبا\u202e"))
        assertEquals(rtl.getString(R.string.catalog_detail_missing), CatalogDetailText.literal(rtl, null))
    }
}
