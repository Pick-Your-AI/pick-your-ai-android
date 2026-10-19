package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecordedCostArithmeticTest {
    private val mapper = ObjectMapper()
    private fun fixture(name: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/$name.json").use {
        mapper.readTree(it) as ObjectNode
    }
    private fun estimate(quantity: String, price: String, amount: String, method: String = "workload_projection",
        meter: String = "gpu_hour"): InspectedCostEstimate {
        val document = fixture("cost-estimate")
        val line = document["line_items"][0].deepCopy<ObjectNode>()
        line.put("quantity", quantity).put("method", method).put("meter", meter)
        (line["unit_price"] as ObjectNode).put("amount", price)
        (line["amount"] as ObjectNode).put("amount", amount)
        document.putArray("line_items").add(line)
        for (key in listOf("subtotal", "total")) (document[key] as ObjectNode).put("amount", amount)
        (document["tax"] as ObjectNode).put("amount", "0.00")
        assertEquals(FrozenValidation.Valid,
            FrozenSchemaValidator().validate(FrozenDocument.COST_ESTIMATE, document.toString().byteInputStream()))
        return (CostEstimateAdapter().inspect(fixture("build").toString().byteInputStream(),
            document.toString().byteInputStream()) as CostReception.Inspected).estimate
    }
    private fun observe(quantity: String, price: String, amount: String, expected: RecordedProductObservation,
        product: String?, method: String = "workload_projection", meter: String = "gpu_hour"): RecordedProductInspection {
        val value = estimate(quantity, price, amount, method, meter)
        val recorded = value.recordedJson
        val build = value.recordedBuildJson
        val line = value.lines.single()
        val result = RecordedCostArithmetic.inspect(line)
        assertEquals(expected, result.observation)
        assertEquals(product, result.exactProduct)
        assertEquals("android-recorded-exact-product/1", result.profileId)
        assertEquals(recorded, value.recordedJson)
        assertEquals(build, value.recordedBuildJson)
        assertEquals(quantity, line.quantity)
        assertEquals(price, line.unitPrice.amount.encoded)
        assertEquals(amount, line.amount.amount.encoded)
        assertEquals(amount, value.reportedTotal.amount.encoded)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.budgetPass)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.freeOrIncludedTreatment)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.freshness)
        return result
    }

    @Test fun `decimal multiplication and comparison are exact without binary float drift`() {
        observe("0.1", "0.2", "0.0200", RecordedProductObservation.MATCHES_EXACT_PRODUCT, "0.02")
        observe("0.1", "0.2", "0.03", RecordedProductObservation.DIFFERS_FROM_EXACT_PRODUCT, "0.02")
        observe("1.000000000001", "1", "1", RecordedProductObservation.DIFFERS_FROM_EXACT_PRODUCT, "1.000000000001")
        observe("12345678901234567890.123456789012", "10", "123456789012345678901.234567890120",
            RecordedProductObservation.MATCHES_EXACT_PRODUCT, "123456789012345678901.23456789012")
    }

    @Test fun `computed trailing zeros do not invent a precision failure or alter input lexemes`() {
        observe("0.100000000000", "0.200000000000", "0.020000000000",
            RecordedProductObservation.MATCHES_EXACT_PRODUCT, "0.02")
        observe("100.000000000000", "10.000000000000", "1000.00",
            RecordedProductObservation.MATCHES_EXACT_PRODUCT, "1000")
    }

    @Test fun `unrepresentable finite products remain exact and explicitly outside the profile`() {
        observe("0.000000000001", "0.1", "0", RecordedProductObservation.EXACT_PRODUCT_OUTSIDE_PROFILE,
            "0.0000000000001")
        observe("0.000000000001", "0.000000000001", "0.000000000001",
            RecordedProductObservation.EXACT_PRODUCT_OUTSIDE_PROFILE, "0.000000000000000000000001")
    }

    @Test fun `zero quantity or price does not prove free treatment or accepted coverage`() {
        observe("0.000000000000", "999.25", "0.00", RecordedProductObservation.MATCHES_EXACT_PRODUCT, "0")
        observe("999.25", "0.000000000000", "0", RecordedProductObservation.MATCHES_EXACT_PRODUCT, "0")
        observe("0", "7", "1", RecordedProductObservation.DIFFERS_FROM_EXACT_PRODUCT, "0")
    }

    @Test fun `recorded quantities stay in their meter units without new usage or discount inference`() {
        observe("1.5", "2", "3", RecordedProductObservation.MATCHES_EXACT_PRODUCT, "3", meter = "input_tokens_1m")
        observe("720", "0.80", "576.00", RecordedProductObservation.MATCHES_EXACT_PRODUCT, "576", meter = "gpu_hour")
        observe("8", "2", "16", RecordedProductObservation.MATCHES_EXACT_PRODUCT, "16", meter = "request")
    }

    @Test fun `vendor quotes and manual overrides retain their declarations when products differ`() {
        for (method in listOf("vendor_quote", "manual_override", "measured", "workload_projection")) {
            observe("1", "2.345", "2.35", RecordedProductObservation.DIFFERS_FROM_EXACT_PRODUCT, "2.345", method)
        }
    }

    @Test fun `schema admitted literal quantity stays unsupported without trimming or rejection`() {
        observe("720\n", "0.80", "576.00", RecordedProductObservation.UNSUPPORTED_QUANTITY, null)
    }

    @Test fun `both operand boundaries permit a complete maximal product without truncation`() {
        val operand = "9".repeat(256)
        val product = "9".repeat(255) + "8" + "0".repeat(255) + "1"
        assertEquals(512, product.length)
        observe(operand, operand, product, RecordedProductObservation.MATCHES_EXACT_PRODUCT, product)
    }

    @Test fun `oversized quantity price and declared amount are explicitly uninspected`() {
        observe("1".repeat(257), "1", "1", RecordedProductObservation.DECIMAL_LIMIT_EXCEEDED, null)
        observe("1", "1".repeat(257), "1", RecordedProductObservation.DECIMAL_LIMIT_EXCEEDED, null)
        observe("0", "1", "1".repeat(513), RecordedProductObservation.DECIMAL_LIMIT_EXCEEDED, null)
    }

    @Test fun `repeated observations are stable and diagnostics do not expose recorded values`() {
        val value = estimate("37", "13", "481.00", "manual_override")
        val first = RecordedCostArithmetic.inspect(value.lines.single())
        repeat(8) {
            val next = RecordedCostArithmetic.inspect(value.lines.single())
            assertEquals(first.observation, next.observation)
            assertEquals(first.exactProduct, next.exactProduct)
            assertEquals(first.profileId, next.profileId)
        }
        assertEquals("RecordedProductInspection(observation=MATCHES_EXACT_PRODUCT)", first.toString())
        assertFalse(first.toString().contains("481"))
    }
}
