package ai.pickyour.core.pricing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.math.BigInteger

class RecordedCostComparisonTest {
    private val mapper = ObjectMapper()
    private fun fixture(name: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/$name.json").use {
        mapper.readTree(it) as ObjectNode
    }
    private fun estimate(): ObjectNode = fixture("cost-estimate")
    private fun inspected(e: ObjectNode = estimate(), b: ObjectNode = fixture("build")): InspectedCostEstimate {
        val result = CostEstimateAdapter().inspect(b.toString().byteInputStream(), e.toString().byteInputStream())
        assertInstanceOf(CostReception.Inspected::class.java, result)
        return (result as CostReception.Inspected).estimate
    }
    private fun first(e: ObjectNode): ObjectNode = e["line_items"][0] as ObjectNode
    private fun money(node: ObjectNode, key: String, value: String) = (node[key] as ObjectNode).put("amount", value)
    private fun reconcile(e: ObjectNode) {
        val subtotal = e["line_items"].fold(BigDecimal.ZERO) { sum, line -> sum + BigDecimal(line["amount"]["amount"].textValue()) }
        money(e, "subtotal", subtotal.toPlainString())
        money(e, "total", (subtotal + BigDecimal(e["tax"]["amount"].textValue())).toPlainString())
    }
    private fun noVerdict(value: RecordedCostComparison) {
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.comparability)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.savings)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.budgetPass)
        assertTrue(value.gaps.containsAll(setOf(CostComparisonGap.USAGE_MODEL_IDENTITY_NOT_ESTABLISHED,
            CostComparisonGap.PRICEBOOK_REVISION_AND_HASH_NOT_ESTABLISHED, CostComparisonGap.RATE_ELIGIBILITY_NOT_ESTABLISHED,
            CostComparisonGap.FRESHNESS_NOT_ESTABLISHED, CostComparisonGap.COVERAGE_NOT_ACCEPTED)))
    }

    @Test fun `identical complete declared records remain unqualified historical evidence`() {
        val before = inspected()
        val after = inspected()
        val result = RecordedCostComparator.compare(before, after)
        assertSame(before, result.before)
        assertSame(after, result.after)
        assertEquals(CostComparisonCompleteness.COMPLETE, result.completeness)
        assertTrue(result.differences.isEmpty())
        assertTrue(result.integrity.isEmpty())
        assertEquals(2, result.lines.size)
        assertTrue(result.lines.all { it.presence == CostLinePresence.BOTH && it.differences.isEmpty() &&
            it.quantityComparison == RecordedQuantityComparison.EQUAL })
        assertEquals(DeclaredCoverage.COMPLETE, result.before.declaredCoverage)
        assertTrue(result.before.unclassifiedActiveSelectionIds.isNotEmpty())
        noVerdict(result)
    }

    @TestFactory fun `quantity comparison is exact while retained spellings remain unchanged`(): List<DynamicTest> = listOf(
        Triple("1", "1.000000000000", RecordedQuantityComparison.EQUAL),
        Triple("0.00", "0", RecordedQuantityComparison.EQUAL),
        Triple("9007199254740992", "9007199254740993", RecordedQuantityComparison.DIFFERENT),
        Triple("0.100000000001", "0.100000000002", RecordedQuantityComparison.DIFFERENT),
        Triple("123456789012345678901234567890.120000000000", "123456789012345678901234567890.12", RecordedQuantityComparison.EQUAL),
        Triple("720\n", "720", RecordedQuantityComparison.UNAVAILABLE),
        Triple("720", "720\n", RecordedQuantityComparison.UNAVAILABLE),
        Triple("720\n", "720\n", RecordedQuantityComparison.UNAVAILABLE),
    ).mapIndexed { index, (left, right, expected) -> DynamicTest.dynamicTest("quantity case $index") {
        val before = inspected(estimate().apply { first(this).put("quantity", left) })
        val after = inspected(estimate().put("id", "estimate.after").apply { first(this).put("quantity", right) })
        val result = RecordedCostComparator.compare(before, after)
        val line = result.lines.single { it.lineId == before.lines.first().id }
        assertEquals(expected, line.quantityComparison)
        assertEquals(left, line.before!!.quantity)
        assertEquals(right, line.after!!.quantity)
        assertEquals(expected == RecordedQuantityComparison.DIFFERENT, CostLineField.QUANTITY in line.differences)
        assertEquals(expected == RecordedQuantityComparison.UNAVAILABLE, CostComparisonGap.UNSUPPORTED_QUANTITY in result.gaps)
        if (expected == RecordedQuantityComparison.UNAVAILABLE)
            assertEquals(left != right, CostLineField.QUANTITY_LITERAL in line.differences)
        assertEquals(left, mapper.readTree(result.before.recordedJson)["line_items"][0]["quantity"].textValue())
        assertEquals(right, mapper.readTree(result.after.recordedJson)["line_items"][0]["quantity"].textValue())
        noVerdict(result)
    } }

    @Test fun `money uses exact numeric equivalence without replacing original scales`() {
        val left = estimate()
        val right = estimate().put("id", "estimate.after")
        money(first(left), "unit_price", "0.8000")
        money(first(right), "unit_price", "0.8")
        money(first(left), "amount", "0.100000000001")
        money(first(right), "amount", "0.100000000001")
        money(left, "tax", "0.000000000001")
        money(right, "tax", "0.000000000001")
        reconcile(left); reconcile(right)
        val equal = RecordedCostComparator.compare(inspected(left), inspected(right))
        assertFalse(CostLineField.UNIT_PRICE in equal.lines.first().differences)
        assertFalse(CostRecordDifference.REPORTED_TOTAL in equal.differences)
        assertEquals("0.8000", equal.before.lines.first().unitPrice.amount.encoded)
        assertEquals("0.8", equal.after.lines.first().unitPrice.amount.encoded)
        money(first(right), "amount", "0.100000000002")
        reconcile(right)
        val changed = RecordedCostComparator.compare(inspected(left), inspected(right))
        assertTrue(CostLineField.AMOUNT in changed.lines.first().differences)
        assertTrue(CostRecordDifference.REPORTED_SUBTOTAL in changed.differences)
        assertTrue(CostRecordDifference.REPORTED_TOTAL in changed.differences)
        noVerdict(changed)
    }

    @Test fun `currencies periods and future estimate dates stay distinct without conversion or freshness inference`() {
        val after = estimate().put("id", "estimate.after").put("period_days", 31)
            .put("estimated_at", "2099-01-01T00:00:00Z")
        after.findParents("currency").forEach { (it as ObjectNode).put("currency", "EUR") }
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        assertTrue(result.differences.containsAll(setOf(CostRecordDifference.CURRENCY, CostRecordDifference.PERIOD,
            CostRecordDifference.ESTIMATED_AT, CostRecordDifference.REPORTED_TOTAL)))
        assertEquals("USD", result.before.currency)
        assertEquals("EUR", result.after.currency)
        assertEquals(BigInteger.valueOf(30), result.before.periodDays)
        assertEquals(BigInteger.valueOf(31), result.after.periodDays)
        assertEquals("578.50", result.before.reportedTotal.amount.encoded)
        assertEquals("578.50", result.after.reportedTotal.amount.encoded)
        assertTrue(result.lines.all { CostLineField.AMOUNT in it.differences && CostLineField.UNIT_PRICE in it.differences })
        noVerdict(result)
    }

    @Test fun `wide build revisions and explicit pricebook changes remain exact`() {
        val oldRevision = BigInteger("18446744073709551617")
        val newRevision = oldRevision + BigInteger.ONE
        val beforeBuild = fixture("build").put("revision", oldRevision)
        val afterBuild = fixture("build").put("revision", newRevision).put("id", "build.after")
        (afterBuild["locks"] as ObjectNode).put("pricebook_id", "prices.after")
        val before = inspected(estimate().put("build_revision", oldRevision), beforeBuild)
        val after = inspected(estimate().put("id", "estimate.after").put("build_id", "build.after")
            .put("build_revision", newRevision).put("pricebook_id", "prices.after"), afterBuild)
        val result = RecordedCostComparator.compare(before, after)
        assertTrue(result.differences.containsAll(setOf(CostRecordDifference.BUILD_ID, CostRecordDifference.BUILD_REVISION,
            CostRecordDifference.BUILD_RECORD, CostRecordDifference.PRICEBOOK_ID)))
        assertTrue(result.integrity.isEmpty())
        assertEquals(oldRevision, result.before.buildRevision)
        assertEquals(newRevision, result.after.buildRevision)
        noVerdict(result)
    }

    @Test fun `changed workload or deployment data at the same build identity exposes integrity conflict`() {
        val beforeBuild = fixture("build")
        val afterBuild = beforeBuild.deepCopy()
        (afterBuild["objectives"] as ObjectNode).put("requests_per_day", 201)
        val selection = afterBuild["selections"].first { it["id"].textValue() == "sel_workload" }
        (selection["settings"] as ObjectNode).put("requests_per_day", 201)
        val before = inspected(estimate(), beforeBuild)
        val after = inspected(estimate().put("id", "estimate.after"), afterBuild)
        val result = RecordedCostComparator.compare(before, after)
        assertEquals(setOf(CostRecordIntegrity.SAME_BUILD_IDENTITY_WITH_DIFFERENT_RECORDS), result.integrity)
        assertTrue(CostRecordDifference.BUILD_RECORD in result.differences)
        assertFalse(CostRecordDifference.BUILD_REVISION in result.differences)
        assertEquals(200, mapper.readTree(before.recordedBuildJson)["objectives"]["requests_per_day"].intValue())
        assertEquals(201, mapper.readTree(after.recordedBuildJson)["objectives"]["requests_per_day"].intValue())
        noVerdict(result)
    }

    @Test fun `reusing an estimate id for different recorded values cannot masquerade as a new version`() {
        val after = estimate().put("estimated_at", "2026-10-17T09:00:00Z")
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        assertEquals(setOf(CostRecordIntegrity.SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS), result.integrity)
        assertFalse(CostRecordDifference.ESTIMATE_ID in result.differences)
        assertTrue(CostRecordDifference.ESTIMATED_AT in result.differences)
        noVerdict(result)
    }

    @Test fun `complete input identity checks retain reordered arrays and changed decimal literals`() {
        val after = estimate()
        val values = after["line_items"].map { it.deepCopy<ObjectNode>() }.reversed()
        after.putArray("line_items").addAll(values)
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        assertTrue(CostRecordIntegrity.SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS in result.integrity)
        assertTrue(CostRecordDifference.LINE_IDS_OR_ORDER in result.differences)
        assertTrue(result.lines.all { it.differences.isEmpty() })
        val scale = estimate()
        money(first(scale), "unit_price", "0.800")
        val numeric = RecordedCostComparator.compare(inspected(), inspected(scale))
        assertTrue(CostRecordIntegrity.SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS in numeric.integrity)
        assertTrue(numeric.lines.all { it.differences.isEmpty() })
    }

    @Test fun `distinct line ids sharing selection meter and rate are never collapsed`() {
        val before = estimate()
        (before["line_items"] as ArrayNode).add(first(before).deepCopy().put("id", "line.compute.second").put("quantity", "1"))
        reconcile(before)
        val after = before.deepCopy().put("id", "estimate.after")
        (after["line_items"][2] as ObjectNode).put("quantity", "2")
        (after["line_items"] as ArrayNode).add(first(after).deepCopy().put("id", "line.compute.third").put("quantity", "3"))
        reconcile(after)
        val result = RecordedCostComparator.compare(inspected(before), inspected(after))
        assertEquals(3, result.before.lines.size)
        assertEquals(4, result.after.lines.size)
        assertEquals(4, result.lines.size)
        assertTrue(CostComparisonGap.MULTIPLE_LINES_FOR_SAME_METER_AND_RATE in result.gaps)
        assertEquals(RecordedQuantityComparison.DIFFERENT, result.lines.single { it.lineId == "line.compute.second" }.quantityComparison)
        val added = result.lines.single { it.lineId == "line.compute.third" }
        assertEquals(CostLinePresence.AFTER_ONLY, added.presence)
        assertNull(added.before)
        assertEquals("3", added.after!!.quantity)
        assertEquals(RecordedQuantityComparison.UNAVAILABLE, added.quantityComparison)
        noVerdict(result)
    }

    @Test fun `changed line ids produce independent before and after records without guessed pairing`() {
        val after = estimate().put("id", "estimate.after")
        first(after).put("id", "line.replacement")
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        assertEquals(3, result.lines.size)
        assertEquals(CostLinePresence.BEFORE_ONLY, result.lines.single { it.lineId == "line.compute_profile.fixture" }.presence)
        assertEquals(CostLinePresence.AFTER_ONLY, result.lines.single { it.lineId == "line.replacement" }.presence)
        assertEquals(CostLinePresence.BOTH, result.lines.single { it.lineId == "line.storage.fixture" }.presence)
        noVerdict(result)
    }

    @Test fun `reused line id retains both changed associations and declared semantics`() {
        val after = estimate().put("id", "estimate.after")
        first(after).put("selection_id", "sel_storage").put("price_rate_id", "rate.other")
            .put("meter", "request").put("method", "manual_override").put("confidence", "high")
            .putArray("assumptions").add("A different recorded basis, not reviewed evidence")
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        val line = result.lines.single { it.lineId == "line.compute_profile.fixture" }
        assertTrue(line.differences.containsAll(setOf(CostLineField.SELECTION, CostLineField.RATE, CostLineField.METER,
            CostLineField.METHOD, CostLineField.CONFIDENCE, CostLineField.ASSUMPTIONS)))
        assertEquals("sel_compute_profile", line.before!!.selectionId)
        assertEquals("sel_storage", line.after!!.selectionId)
        assertEquals(RecordedQuantityComparison.UNAVAILABLE, line.quantityComparison)
        assertTrue(CostRecordDifference.UNCLASSIFIED_SELECTIONS in result.differences)
        noVerdict(result)
    }

    @Test fun `equal numeric tokens with different recorded meter units cannot report equal quantities`() {
        val after = estimate().put("id", "estimate.after")
        first(after).put("meter", "storage_gb_month")
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        val line = result.lines.single { it.lineId == "line.compute_profile.fixture" }
        assertEquals("720", line.before!!.quantity)
        assertEquals("720", line.after!!.quantity)
        assertEquals(RecordedQuantityComparison.UNAVAILABLE, line.quantityComparison)
        assertEquals(setOf(CostLineField.METER), line.differences)
        assertFalse(CostComparisonGap.UNSUPPORTED_QUANTITY in result.gaps)
        noVerdict(result)
    }

    @Test fun `newly unpriced and unclassified selections remain explicit including priced overlap`() {
        val after = estimate().put("id", "estimate.after").put("coverage", "partial")
        (after["line_items"] as ArrayNode).remove(1)
        (after["unpriced_selection_ids"] as ArrayNode).add("sel_compute_profile")
        (after["warnings"] as ArrayNode).add("Coverage remains partial")
        reconcile(after)
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        assertTrue(result.differences.containsAll(setOf(CostRecordDifference.DECLARED_COVERAGE,
            CostRecordDifference.REPORTED_UNPRICED_SELECTIONS, CostRecordDifference.UNCLASSIFIED_SELECTIONS,
            CostRecordDifference.WARNINGS)))
        assertEquals(setOf("sel_compute_profile"), result.after.reportedUnpricedSelectionIds)
        assertTrue("sel_storage" in result.after.unclassifiedActiveSelectionIds)
        assertTrue(result.after.lines.any { it.selectionId in result.after.reportedUnpricedSelectionIds })
        assertEquals("576.00", result.after.reportedSubtotal.amount.encoded)
        noVerdict(result)
    }

    @Test fun `empty unknown records do not manufacture removed-line zeroes or a complete quote`() {
        val after = estimate().put("id", "estimate.after").put("coverage", "unknown")
        after.putArray("line_items")
        reconcile(after)
        val result = RecordedCostComparator.compare(inspected(), inspected(after))
        assertEquals(2, result.lines.size)
        assertTrue(result.lines.all { it.presence == CostLinePresence.BEFORE_ONLY && it.after == null })
        assertTrue(result.after.lines.isEmpty())
        assertEquals("0.00", result.after.reportedTotal.amount.encoded)
        assertEquals(DeclaredCoverage.UNKNOWN, result.after.declaredCoverage)
        noVerdict(result)
    }

    @Test fun `limits never return a partial line comparison and recovery uses the full records`() {
        val before = inspected()
        val after = inspected(estimate().put("id", "estimate.after"))
        val limited = RecordedCostComparator.compare(before, after, 1)
        assertEquals(CostComparisonCompleteness.LINE_LIMIT_EXCEEDED, limited.completeness)
        assertTrue(limited.lines.isEmpty())
        assertEquals(2, limited.before.lines.size)
        assertEquals(2, limited.after.lines.size)
        noVerdict(limited)
        val complete = RecordedCostComparator.compare(before, after, 2)
        assertEquals(CostComparisonCompleteness.COMPLETE, complete.completeness)
        assertEquals(2, complete.lines.size)
        for (limit in listOf(0, -1, 10_001)) assertThrows(IllegalArgumentException::class.java) {
            RecordedCostComparator.compare(before, after, limit)
        }
    }

    @Test fun `retained values and result collections cannot be changed by source or consumer mutations`() {
        val b = fixture("build")
        val e = estimate()
        val before = inspected(e, b)
        val after = inspected(estimate().put("id", "estimate.after"))
        val buildRecord = before.recordedBuildJson
        val estimateRecord = before.recordedJson
        b.put("name", "Changed private source")
        first(e).put("quantity", "999")
        val result = RecordedCostComparator.compare(before, after)
        assertEquals(buildRecord, result.before.recordedBuildJson)
        assertEquals(estimateRecord, result.before.recordedJson)
        assertEquals("720", result.before.lines.first().quantity)
        assertTrue(result.integrity.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (result.lines as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.differences as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.integrity as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.gaps as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.lines.first().differences as MutableSet).clear() }
        assertEquals("RecordedCostComparison", result.toString())
        assertEquals("RecordedCostLineComparison", result.lines.first().toString())
        val reversed = RecordedCostComparator.compare(after, before)
        assertSame(after, reversed.before)
        assertSame(before, reversed.after)
        assertEquals(result.differences, reversed.differences)
    }
}
