package ai.pickyour.android.preview

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.CostEvidence
import ai.pickyour.core.pricing.DeclaredCoverage
import ai.pickyour.core.pricing.InspectedCostEstimate
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Resource/locale presentation on the JVM; this does not establish installed layout or TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CostInspectionTextTest {
    private fun context(locale: Locale = Locale.US): Context {
        val application = RuntimeEnvironment.getApplication()
        val config = Configuration(application.resources.configuration)
        config.setLocales(LocaleList(locale))
        return application.createConfigurationContext(config)
    }
    private fun fixture(name: String): JSONObject = context().assets.open("$name.json").bufferedReader(Charsets.UTF_8).use {
        JSONObject(it.readText())
    }
    private fun JSONObject.putArray(name: String) = JSONArray().also { put(name, it) }
    private fun inspect(estimate: JSONObject = fixture("cost-estimate")): InspectedCostEstimate {
        val result = CostEstimateAdapter().inspect(fixture("build").toString().byteInputStream(), estimate.toString().byteInputStream())
        assertTrue("Synthetic setup must pass the existing adapter before presentation", result is CostReception.Inspected)
        return (result as CostReception.Inspected).estimate
    }

    @Test fun localizesWideDecimalsWithoutRoundingAndKeepsCanonicalLexeme() {
        val value = ContractDecimal.parse("12345678901234567890.123456789012")
        val english = CostInspectionText.decimal(context(), value)
        val german = CostInspectionText.decimal(context(Locale.GERMANY), value)
        assertTrue(english.contains("12,345,678,901,234,567,890.123456789012"))
        assertTrue(german.contains("12.345.678.901.234.567.890,123456789012"))
        assertTrue(english.contains("\"12345678901234567890.123456789012\""))
        assertTrue(german.contains("\"12345678901234567890.123456789012\""))
        assertEquals("12345678901234567890.123456789012", value.encoded)
    }

    @Test fun preservesTrailingZerosAndSmallNonzeroAmountsAcrossCurrencyConventions() {
        val estimate = fixture("cost-estimate").put("currency", "JPY")
        for (key in listOf("subtotal", "tax", "total")) estimate.getJSONObject(key).put("currency", "JPY")
        val currencyLines = estimate.getJSONArray("line_items")
        for (index in 0 until currencyLines.length()) {
            val line = currencyLines.getJSONObject(index)
            line.getJSONObject("unit_price").put("currency", "JPY")
            line.getJSONObject("amount").put("currency", "JPY")
        }
        val value = inspect(estimate)
        val recorded = value.recordedJson
        val text = CostInspectionText.describe(context(Locale.JAPAN), value)
        assertTrue(text.contains("578.50 (canonical \"578.50\"); currency JPY"))
        assertTrue(text.contains("0.00 (canonical \"0.00\"); currency JPY"))
        assertFalse(text.contains("currency USD"))
        assertFalse(text.contains("¥"))
        assertTrue(CostInspectionText.decimal(context(Locale.GERMANY), ContractDecimal.parse("0.000000000001"))
            .contains("0,000000000001"))
        assertEquals(recorded, value.recordedJson)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.budgetPass)
    }

    @Test fun screenLocaleControlsDisplayIndependentlyOfProcessDefault() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            val value = ContractDecimal.parse("1234.50")
            assertTrue(CostInspectionText.decimal(context(Locale.GERMANY), value).contains("1.234,50"))
            val arabic = CostInspectionText.decimal(context(Locale.forLanguageTag("ar-EG")), value)
            assertTrue(arabic.contains("١٬٢٣٤٫٥٠"))
            assertTrue(arabic.contains("\"1234.50\""))
            assertEquals("1234.50", value.encoded)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun hostileLiteralTextCannotReplaceLabelsAndLongTextIsExplicitlyTruncated() {
        val estimate = fixture("cost-estimate")
        estimate.put("id", "unknown")
        estimate.putArray("warnings").put("None declared (not proof of completeness)")
            .put("\u202eTOTAL: FREE\nUSD").put("x".repeat(500))
            .put("first\u2028Injected heading\u2029last")
        val first = estimate.getJSONArray("line_items").getJSONObject(0)
        first.put("price_rate_id", "unknown")
        first.putArray("assumptions").put("line\\\"\nnot a heading").put("rate.\u2066x\u2069")
            .put("source\u2028assumption\u2029still literal")
        val value = inspect(estimate)
        val recorded = value.recordedJson
        val text = CostInspectionText.describe(context(), value)
        assertTrue(text.contains("\\u202eTOTAL: FREE\\nUSD"))
        assertTrue(text.contains("Estimate: \"unknown\""))
        assertTrue(text.contains("Rate ID: \"unknown\""))
        assertTrue(text.contains("\\u2066x\\u2069"))
        assertTrue(text.contains("first\\u2028Injected heading\\u2029last"))
        assertTrue(text.contains("source\\u2028assumption\\u2029still literal"))
        assertTrue(text.contains("\"None declared (not proof of completeness)\""))
        assertTrue(text.contains("[display truncated; full original retained]"))
        assertFalse(text.contains('\u202e'))
        assertFalse(text.contains('\u2066'))
        assertFalse(text.contains('\u2028'))
        assertFalse(text.contains('\u2029'))
        assertFalse(text.contains("x".repeat(161)))
        assertEquals(recorded, value.recordedJson)
        assertTrue(value.warnings.contains("first\u2028Injected heading\u2029last"))
        assertTrue(value.lines.first().assumptions.contains("source\u2028assumption\u2029still literal"))
    }

    @Test fun boundedLinesAndListsDiscloseCountsWhileTotalsRetainEveryLine() {
        val estimate = fixture("cost-estimate")
        val template = estimate.getJSONArray("line_items").getJSONObject(0)
        val lines = estimate.putArray("line_items")
        repeat(10) { index ->
            val line = JSONObject(template.toString()).put("id", "bounded-line-$index")
            line.getJSONObject("amount").put("amount", "1.00")
            line.putArray("assumptions").apply { repeat(10) { put("assumption-$it") } }
            lines.put(line)
        }
        estimate.putArray("warnings").apply { repeat(10) { put("warning-$it") } }
        estimate.getJSONObject("subtotal").put("amount", "10.00")
        estimate.getJSONObject("tax").put("amount", "0.00")
        estimate.getJSONObject("total").put("amount", "10.00")
        val value = inspect(estimate)
        val text = CostInspectionText.describe(context(), value)
        assertTrue(text.contains("Line items displayed: 8 / 10 retained lines"))
        assertTrue(text.contains("Entries displayed: 8 / 10 retained entries"))
        assertTrue(text.contains("\"bounded-line-7\""))
        assertFalse(text.contains("bounded-line-8"))
        assertFalse(text.contains("assumption-8"))
        assertFalse(text.contains("warning-8"))
        assertTrue(text.contains("10.00 (canonical \"10.00\")"))
        assertEquals(10, value.lines.size)
        assertEquals(10, value.warnings.size)
        assertEquals(10, value.lines.last().assumptions.size)
        assertTrue(value.recordedJson.contains("bounded-line-9"))
    }

    @Test fun oversizedDecimalUsesBoundedCanonicalDisclosureWithoutAFalseRoundedAmount() {
        val encoded = "9".repeat(500) + ".000000000001"
        val value = ContractDecimal.parse(encoded)
        val text = CostInspectionText.decimal(context(Locale.GERMANY), value)
        assertTrue(text.contains("too long for localized display"))
        assertTrue(text.contains("[display truncated; full original retained]"))
        assertFalse(text.contains("9".repeat(161)))
        assertTrue(text.length < 300)
        assertEquals(encoded, value.encoded)
    }

    @Test fun schemaPermittedTerminalNewlineQuantityRemainsLiteralWithoutCrashing() {
        val estimate = fixture("cost-estimate")
        estimate.getJSONArray("line_items").getJSONObject(0).put("quantity", "720\n")
        val value = inspect(estimate)
        val recorded = value.recordedJson
        val text = CostInspectionText.describe(context(Locale.GERMANY), value)
        assertTrue(text.contains("\"720\\n\""))
        assertFalse(text.contains("\"720\n\""))
        assertEquals("720\n", value.lines.first().quantity)
        assertEquals(recorded, value.recordedJson)
        assertEquals("578.50", value.reportedTotal.amount.encoded)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.budgetPass)
    }

    @Test fun absentLinesAndUnknownCoverageStayUnpricedWithoutManufacturedCompleteness() {
        val estimate = fixture("cost-estimate").put("coverage", "unknown")
        estimate.putArray("line_items")
        estimate.putArray("warnings")
        for (key in listOf("subtotal", "tax", "total")) estimate.getJSONObject(key).put("amount", "0.00")
        val value = inspect(estimate)
        val text = CostInspectionText.describe(context(), value)
        assertTrue(text.contains("Line items displayed: 0 / 0 retained lines"))
        assertTrue(text.contains("None declared (not proof of completeness)"))
        assertTrue(text.contains("Producer-declared coverage: UNKNOWN"))
        assertTrue(text.contains("Missing classification never means zero cost"))
        assertTrue(text.contains("Budget pass: not established"))
        assertEquals(DeclaredCoverage.UNKNOWN, value.declaredCoverage)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.freeOrIncludedTreatment)
        assertTrue(value.unclassifiedActiveSelectionIds.isNotEmpty())
    }
}
