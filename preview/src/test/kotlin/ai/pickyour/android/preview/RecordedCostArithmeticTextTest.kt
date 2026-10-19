package ai.pickyour.android.preview

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostEvidence
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.InspectedCostEstimate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Actual cost formatter/section on simulated Android resources; no installed or billing acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordedCostArithmeticTextTest {
    private fun context(locale: Locale = Locale.US): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return application.createConfigurationContext(configuration)
    }
    private fun fixture(name: String): JSONObject = context().assets.open("$name.json").bufferedReader(Charsets.UTF_8).use {
        JSONObject(it.readText())
    }
    private fun line(quantity: String, price: String, amount: String, method: String = "workload_projection",
        meter: String = "gpu_hour"): JSONObject {
        val value = fixture("cost-estimate").getJSONArray("line_items").getJSONObject(0)
        value.put("quantity", quantity).put("method", method).put("meter", meter)
        value.getJSONObject("unit_price").put("amount", price)
        value.getJSONObject("amount").put("amount", amount)
        return value
    }
    private fun inspect(vararg lines: JSONObject): InspectedCostEstimate {
        val document = fixture("cost-estimate")
        val entries = org.json.JSONArray()
        var total = BigDecimal.ZERO
        lines.forEachIndexed { index, value ->
            entries.put(value.put("id", "arithmetic-line-$index"))
            total += BigDecimal(value.getJSONObject("amount").getString("amount"))
        }
        document.put("line_items", entries)
        for (key in listOf("subtotal", "total")) document.getJSONObject(key).put("amount", total.toPlainString())
        document.getJSONObject("tax").put("amount", "0.00")
        val result = CostEstimateAdapter().inspect(fixture("build").toString().byteInputStream(),
            document.toString().byteInputStream())
        assertTrue("The unchanged declared-cost receiver must admit the setup", result is CostReception.Inspected)
        return (result as CostReception.Inspected).estimate
    }
    private fun render(value: InspectedCostEstimate, locale: Locale = Locale.US): String {
        val before = value.recordedJson
        val build = value.recordedBuildJson
        val text = CostInspectionText.describe(context(locale), value)
        assertEquals(before, value.recordedJson)
        assertEquals(build, value.recordedBuildJson)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.budgetPass)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.freeOrIncludedTreatment)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.freshness)
        return text
    }

    @Test fun exactProductKeepsReportedLexemesAndDoesNotPromoteBudgetEvidence() {
        val value = inspect(line("0.100000000000", "0.200000000000", "0.020000000000"))
        val text = render(value, Locale.GERMANY)
        assertTrue(text.contains("Exact quantity × unit price: \"0.02\"; currency USD"))
        assertTrue(text.contains("Matches the declared amount under this no-rounding inspection"))
        assertTrue(text.contains("0,020000000000 (canonical \"0.020000000000\")"))
        assertTrue(text.contains("Budget pass: not established"))
        assertEquals("0.020000000000", value.lines.single().amount.amount.encoded)
    }

    @Test fun differingQuotesAndOverridesStayDeclaredAndRequireBillingReview() {
        val value = inspect(line("1", "2.345", "2.35", "vendor_quote"),
            line("1", "2.345", "2.35", "manual_override"))
        val text = render(value)
        assertEquals(2, Regex("Differs from the declared amount under this no-rounding inspection").findAll(text).count())
        assertTrue(text.contains("vendor_quote"))
        assertTrue(text.contains("manual_override"))
        assertTrue(text.contains("2.35 (canonical \"2.35\")"))
        assertTrue(text.contains("a difference does not by itself invalidate a quote or manual override"))
        assertEquals("4.70", value.reportedTotal.amount.encoded)
    }

    @Test fun precisionOutsideMoneyVocabularyRemainsExactWithoutRounding() {
        val value = inspect(line("0.000000000001", "0.1", "0"))
        val text = render(value)
        assertTrue(text.contains("\"0.0000000000001\""))
        assertTrue(text.contains("needs more than 12 fractional digits"))
        assertTrue(text.contains("No rounding policy is chosen; the declared amount is preserved"))
        assertFalse(text.contains("Matches the declared amount under"))
        assertEquals("0", value.lines.single().amount.amount.encoded)
    }

    @Test fun schemaAdmittedLiteralQuantityStaysVisibleAndUninspected() {
        val value = inspect(line("720\n", "0.80", "576.00"))
        val text = render(value)
        assertTrue(text.contains("\"720\\n\""))
        assertTrue(text.contains("Multiplication unavailable: the recorded quantity is outside the strict decimal subset"))
        assertFalse(text.contains("Matches the declared amount under"))
        assertEquals("720\n", value.lines.single().quantity)
    }

    @Test fun inputAndProductDisplayBoundsAreExplicitWithoutLosingTheRetainedRecord() {
        val product = "9".repeat(255) + "8" + "0".repeat(255) + "1"
        val value = inspect(line("1".repeat(257), "1", "1"),
            line("9".repeat(256), "9".repeat(256), product))
        val text = render(value)
        assertTrue(text.contains("Multiplication not performed: a decimal input or result exceeds this bounded inspection"))
        assertTrue(text.contains("[display truncated; full original retained]"))
        assertFalse(text.contains("9".repeat(161)))
        assertTrue(text.contains("Matches the declared amount under this no-rounding inspection"))
        assertEquals(product, value.lines[1].amount.amount.encoded)
        assertTrue(text.length < 10_000)
    }

    @Test fun millionTokenQuantityAndDeclaredZeroDoNotAcquireNewBillingMeaning() {
        val value = inspect(line("1.5", "2", "3", meter = "input_tokens_1m"),
            line("0", "999", "0"))
        val text = render(value)
        assertTrue(text.contains("input_tokens_1m"))
        assertTrue(text.contains("Exact quantity × unit price: \"3\"; currency USD"))
        assertTrue(text.contains("Exact quantity × unit price: \"0\"; currency USD"))
        assertTrue(text.contains("A match does not validate rates, usage, coverage or a budget pass"))
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.freeOrIncludedTreatment)
    }

    @Test fun mixedObservationsRemainPerLineAndHiddenLinesDoNotCreateAnAggregatePass() {
        val lines = (0 until 10).map { index -> when (index) {
            1 -> line("1", "2.345", "2.35")
            2 -> line("1\n", "1", "1")
            3 -> line("0.000000000001", "0.1", "0")
            else -> line("1", "1", "1")
        } }
        val value = inspect(*lines.toTypedArray())
        val text = render(value)
        assertTrue(text.contains("Line items displayed: 8 / 10 retained lines"))
        assertTrue(text.contains("Matches the declared amount under"))
        assertTrue(text.contains("Differs from the declared amount under"))
        assertTrue(text.contains("Multiplication unavailable"))
        assertTrue(text.contains("needs more than 12 fractional digits"))
        assertFalse(text.contains("arithmetic-line-8"))
        assertEquals(10, value.lines.size)
        assertEquals("10.35", value.reportedTotal.amount.encoded)
        assertTrue(text.contains("Budget pass: not established"))
    }

    @Test fun actualInspectorButtonDisplaysTheSeparateArithmeticObservation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val worker = object : AbstractExecutorService() {
            private var stopped = false
            override fun shutdown() { stopped = true }
            override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
            override fun isShutdown() = stopped
            override fun isTerminated() = stopped
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
            override fun execute(command: Runnable) = command.run()
        }
        try {
            val container = LinearLayout(activity)
            CostInspectionSection(activity, worker) { true }.attach(container)
            val button = container.getChildAt(0) as Button
            val output = container.getChildAt(1) as TextView
            assertTrue(button.performClick())
            assertTrue(button.isEnabled)
            assertTrue(output.text.contains("Additional arithmetic observation"))
            assertTrue(output.text.contains("Exact quantity × unit price: \"576\"; currency USD"))
            assertTrue(output.text.contains("Budget pass: not established"))
        } finally {
            worker.shutdown()
            controller.pause().stop().destroy()
        }
    }
}
