package ai.pickyour.core.pricing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class CostEstimateAdapterTest {
    private val mapper = ObjectMapper()
    private val adapter = CostEstimateAdapter()
    private fun fixture(name: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/$name.json").use { mapper.readTree(it) as ObjectNode }
    private fun inspect(e: ObjectNode = fixture("cost-estimate"), b: ObjectNode = fixture("build")) =
        adapter.inspect(b.toString().byteInputStream(), e.toString().byteInputStream())
    private fun value(e: ObjectNode = fixture("cost-estimate"), b: ObjectNode = fixture("build")) = (inspect(e, b) as CostReception.Inspected).estimate
    private fun rejected(reason: CostRejection, e: ObjectNode, b: ObjectNode = fixture("build")) =
        assertEquals(CostReception.Rejected(reason), inspect(e, b))

    @Test fun `line presentation preserves exact declared evidence in immutable projections`() {
        val result = value()
        val line = result.lines.first()
        assertEquals("line.compute_profile.fixture", line.id)
        assertEquals("sel_compute_profile", line.selectionId)
        assertEquals("rate.compute_profile.fixture", line.rateId)
        assertEquals("gpu_hour", line.meter)
        assertEquals("720", line.quantity)
        assertEquals("0.80", line.unitPrice.amount.encoded)
        assertEquals("576.00", line.amount.amount.encoded)
        assertEquals("USD", line.amount.currency)
        assertEquals("workload_projection", line.method)
        assertEquals("low", line.confidence)
        assertTrue(line.assumptions.single().contains("FIXTURE ONLY"))
        assertThrows(UnsupportedOperationException::class.java) { (result.lines as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (line.assumptions as MutableList).clear() }
        assertEquals("InspectedCostLine", line.toString())
    }
    @Test fun `zero declared line does not grant free included freshness or budget evidence`() {
        val e = fixture("cost-estimate")
        e["line_items"].forEach { (it["amount"] as ObjectNode).put("amount", "0.00") }
        for (key in listOf("subtotal", "tax", "total")) (e[key] as ObjectNode).put("amount", "0.00")
        val result = value(e)
        assertEquals("0.00", result.lines.first().amount.amount.encoded)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.freeOrIncludedTreatment)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.freshness)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.budgetPass)
    }
    @Test fun `frozen fixture retains declared decimals but never attests budget freshness or free treatment`() {
        val result = value()
        assertEquals("578.50", result.reportedSubtotal.amount.encoded)
        assertEquals("0.00", result.reportedTax.amount.encoded)
        assertEquals(BigInteger.valueOf(30), result.periodDays)
        assertEquals(DeclaredCoverage.COMPLETE, result.declaredCoverage)
        assertTrue(result.unclassifiedActiveSelectionIds.isNotEmpty())
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.budgetPass)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.freshness)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.freeOrIncludedTreatment)
        assertTrue(result.recordedJson.contains("FIXTURE ONLY"))
        assertEquals("InspectedCostEstimate", result.toString())
    }
    @Test fun `partial coverage keeps priced and uncovered meters on same selection without zero substitution`() {
        val e = fixture("cost-estimate").put("coverage", "partial")
        (e["unpriced_selection_ids"] as ArrayNode).add("sel_compute_profile")
        val result = value(e)
        assertEquals(DeclaredCoverage.PARTIAL, result.declaredCoverage)
        assertEquals(setOf("sel_compute_profile"), result.reportedUnpricedSelectionIds)
        assertEquals("578.50", result.reportedSubtotal.amount.encoded)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.budgetPass)
    }
    @Test fun `unknown empty estimate preserves explicit recorded zero without interpreting it as free`() {
        val e = fixture("cost-estimate").put("coverage", "unknown")
        e.putArray("line_items")
        for (key in listOf("subtotal", "tax", "total")) (e[key] as ObjectNode).put("amount", "0.00")
        val result = value(e)
        assertEquals(DeclaredCoverage.UNKNOWN, result.declaredCoverage)
        assertTrue(result.unclassifiedActiveSelectionIds.isNotEmpty())
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.freeOrIncludedTreatment)
        assertEquals(CostEvidence.NOT_ESTABLISHED, result.budgetPass)
    }
    @Test fun `complete with explicitly unpriced selections fails closed`() {
        val e = fixture("cost-estimate")
        (e["unpriced_selection_ids"] as ArrayNode).add("sel_compute_profile")
        rejected(CostRejection.INCONSISTENT_COVERAGE, e)
    }
    @Test fun `build revision and pricebook binding never silently accepts latest or missing pins`() {
        for (field in listOf("build_id", "pricebook_id")) rejected(CostRejection.BINDING_MISMATCH, fixture("cost-estimate").put(field, "other.id"))
        rejected(CostRejection.BINDING_MISMATCH, fixture("cost-estimate").put("build_revision", 2))
        val b = fixture("build")
        (b["locks"] as ObjectNode).remove("pricebook_id")
        rejected(CostRejection.MISSING_PRICEBOOK_PIN, fixture("cost-estimate"), b)
    }
    @Test fun `large and decimal integer revision encodings compare without narrowing`() {
        val b = fixture("build")
        val e = fixture("cost-estimate")
        val huge = BigInteger("18446744073709551617")
        b.put("revision", huge)
        e.put("build_revision", huge.toBigDecimal())
        assertEquals(huge, value(e, b).buildRevision)
        e.put("build_revision", huge + BigInteger.ONE)
        rejected(CostRejection.BINDING_MISMATCH, e, b)
    }
    @Test fun `every money currency must match without conversion or defaults`() {
        for (path in listOf("subtotal", "tax", "total", "unit_price", "amount")) {
            val e = fixture("cost-estimate")
            val money = if (path in listOf("unit_price", "amount")) e["line_items"][0][path] else e[path]
            (money as ObjectNode).put("currency", "EUR")
            rejected(CostRejection.MIXED_CURRENCY, e)
        }
    }
    @Test fun `declared amount reconciliation is exact and never rounds away a discrepancy`() {
        val e = fixture("cost-estimate")
        (e["line_items"][0]["amount"] as ObjectNode).put("amount", "0.100000000001")
        (e["line_items"][1]["amount"] as ObjectNode).put("amount", "0.200000000002")
        (e["subtotal"] as ObjectNode).put("amount", "0.300000000003")
        (e["tax"] as ObjectNode).put("amount", "0.000000000001")
        (e["total"] as ObjectNode).put("amount", "0.300000000004")
        assertEquals("0.300000000004", value(e).reportedTotal.amount.encoded)
        (e["total"] as ObjectNode).put("amount", "0.30")
        rejected(CostRejection.INCONSISTENT_AMOUNTS, e)
        rejected(CostRejection.INCONSISTENT_AMOUNTS, fixture("cost-estimate").apply { (this["subtotal"] as ObjectNode).put("amount", "1") })
    }
    @Test fun `unknown disabled and duplicate identities cannot disappear in indexing`() {
        val e = fixture("cost-estimate")
        (e["line_items"][0] as ObjectNode).put("selection_id", "sel_absent")
        rejected(CostRejection.UNKNOWN_SELECTION, e)
        val b = fixture("build")
        (b["selections"].first { it["id"].asText() == "sel_compute_profile" } as ObjectNode).put("enabled", false)
        rejected(CostRejection.UNKNOWN_SELECTION, fixture("cost-estimate"), b)
        val duplicate = fixture("cost-estimate")
        (duplicate["line_items"] as ArrayNode).add(duplicate["line_items"][0].deepCopy<ObjectNode>())
        rejected(CostRejection.DUPLICATE_ID, duplicate)
        val db = fixture("build")
        (db["selections"] as ArrayNode).add(db["selections"][0].deepCopy<ObjectNode>())
        rejected(CostRejection.DUPLICATE_ID, fixture("cost-estimate"), db)
    }
    @Test fun `immutable snapshots preserve assumptions and do not leak through diagnostics`() {
        val e = fixture("cost-estimate").put("coverage", "partial")
        (e["unpriced_selection_ids"] as ArrayNode).add("sel_compute_profile")
        val result = value(e)
        val recorded = result.recordedJson
        e.put("currency", "EUR")
        assertEquals(recorded, result.recordedJson)
        assertEquals("USD", result.currency)
        assertThrows(UnsupportedOperationException::class.java) { (result.warnings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.reportedUnpricedSelectionIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.unclassifiedActiveSelectionIds as MutableSet).clear() }
    }
    @Test fun `invalid bounded inputs and wrong schemas return sanitized outcomes and preserve streams`() {
        var closed = false
        val input = object : java.io.ByteArrayInputStream("{private".toByteArray()) {
            override fun close() { closed = true }
        }
        assertEquals(CostReception.Rejected(CostRejection.INVALID_ESTIMATE), adapter.inspect(fixture("build").toString().byteInputStream(), input))
        assertFalse(closed)
        rejected(CostRejection.INVALID_ESTIMATE, fixture("cost-estimate").put("schema_version", "2.0.0"))
        rejected(CostRejection.INVALID_ESTIMATE, fixture("cost-estimate").apply { putArray("warnings").add("x".repeat(20_000)) })
        assertEquals(CostReception.Rejected(CostRejection.INVALID_BUILD), adapter.inspect("{}".byteInputStream(), fixture("cost-estimate").toString().byteInputStream()))
    }
}
