package ai.pickyour.core.comparison

import ai.pickyour.core.pricing.CostComparisonGap
import ai.pickyour.core.pricing.CostEvidence
import ai.pickyour.core.pricing.CostLineField
import ai.pickyour.core.pricing.CostRecordDifference
import ai.pickyour.core.pricing.CostRecordIntegrity
import ai.pickyour.core.pricing.DeclaredCoverage
import ai.pickyour.core.pricing.RecordedQuantityComparison
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger

class SelectedCostComparisonTest {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    private fun fixture(name: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/$name.json").use {
        mapper.readTree(it) as ObjectNode
    }
    private fun snapshot(build: ObjectNode): ComparisonSnapshot = assertInstanceOf(SnapshotRead.Available::class.java,
        ComparisonSnapshot.read(build.toString().byteInputStream(),
            SnapshotIdentity(build["id"].textValue(), build["revision"].bigIntegerValue()))).snapshot
    private fun load(selected: ComparisonSnapshot, estimate: ObjectNode = fixture("cost-estimate"),
                     expected: String = estimate["id"].textValue()): HistoricalCostInput =
        SelectedCostEstimate.read(selected, expected, estimate.toString().byteInputStream())
    private fun admitted(selected: ComparisonSnapshot, estimate: ObjectNode = fixture("cost-estimate")) =
        assertInstanceOf(HistoricalCostInput.Available::class.java, load(selected, estimate))
    private fun rejected(reason: HistoricalCostProblem, result: HistoricalCostInput) {
        assertEquals(HistoricalCostInput.Unavailable(reason), result)
        assertFalse(result.toString().contains("private.fixture"))
    }
    private fun noPricingVerdict(value: SelectedCostComparison) {
        assertNotNull(value.recorded)
        val record = value.recorded!!
        assertEquals(CostEvidence.NOT_ESTABLISHED, record.comparability)
        assertEquals(CostEvidence.NOT_ESTABLISHED, record.savings)
        assertEquals(CostEvidence.NOT_ESTABLISHED, record.budgetPass)
        assertTrue(record.gaps.containsAll(setOf(CostComparisonGap.USAGE_MODEL_IDENTITY_NOT_ESTABLISHED,
            CostComparisonGap.PRICEBOOK_REVISION_AND_HASH_NOT_ESTABLISHED,
            CostComparisonGap.RATE_ELIGIBILITY_NOT_ESTABLISHED, CostComparisonGap.FRESHNESS_NOT_ESTABLISHED,
            CostComparisonGap.COVERAGE_NOT_ACCEPTED)))
    }
    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun `default costs stay not loaded and structural comparison remains unchanged`() {
        val build = snapshot(fixture("build"))
        val result = SelectedCostComparator.compare(build, build)
        assertEquals(SelectedCostComparisonState.NOT_COMPARED, result.state)
        assertSame(HistoricalCostInput.NotLoaded, result.beforeInput)
        assertSame(HistoricalCostInput.NotLoaded, result.afterInput)
        assertNull(result.recorded)
        val structural = BuildComparator.compare(build, build)
        assertEquals(HistoricalOutputState.NOT_LOADED, structural.historicalCost)
        assertEquals(HistoricalOutputState.NOT_LOADED, structural.historicalFindings)
        assertTrue(structural.changes.isEmpty())
    }

    @Test fun `explicit missing input differs from no attempted admission on either side`() {
        val build = snapshot(fixture("build"))
        val missing = SelectedCostEstimate.read(build, "estimate.requested", null)
        rejected(HistoricalCostProblem.ESTIMATE_NOT_SUPPLIED, missing)
        val result = SelectedCostComparator.compare(build, build, afterCost = missing)
        assertSame(HistoricalCostInput.NotLoaded, result.beforeInput)
        assertSame(missing, result.afterInput)
        assertNull(result.recorded)
        val reversed = SelectedCostComparator.compare(build, build, beforeCost = missing)
        assertSame(missing, reversed.beforeInput)
        assertSame(HistoricalCostInput.NotLoaded, reversed.afterInput)
        assertEquals(SelectedCostComparisonState.NOT_COMPARED, reversed.state)
    }

    @Test fun `requested estimate and arbitrary precision selected revision are admitted exactly`() {
        val revision = BigInteger("92233720368547758081234567890")
        val buildJson = fixture("build").put("revision", revision)
        val estimate = fixture("cost-estimate").put("build_revision", revision)
        val build = snapshot(buildJson)
        val input = admitted(build, estimate)
        assertSame(build, input.record.build)
        assertEquals(revision, input.record.estimate.buildRevision)
        assertEquals(buildJson, mapper.readTree(input.record.estimate.recordedBuildJson))
        assertEquals(estimate, mapper.readTree(input.record.estimate.recordedJson))
        rejected(HistoricalCostProblem.ESTIMATE_IDENTITY_MISMATCH, load(build, estimate, "estimate.other"))
        assertEquals(estimate["id"].textValue(), input.record.estimate.estimateId)
    }

    @Test fun `admitted cost cannot move to changed settings roles objectives or locks under reused identity`() {
        val original = fixture("build")
        val selected = snapshot(original)
        val input = admitted(selected)
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { (it["selections"][0]["settings"] as ObjectNode).put("concurrency", 3) },
            { (it["selections"][0] as ObjectNode).put("role", "secondary") },
            { (it["objectives"] as ObjectNode).put("max_concurrency", 3) },
            { (it["locks"] as ObjectNode).put("ruleset_version", "2.0.0") },
            { it.put("name", "Another retained name") },
        )
        for (change in mutations) {
            val different = original.deepCopy().also(change)
            val after = snapshot(different)
            assertEquals(selected.identity, after.identity)
            val result = SelectedCostComparator.compare(selected, after, input, input)
            assertSame(input, result.beforeInput)
            rejected(HistoricalCostProblem.BUILD_RECORD_MISMATCH, result.afterInput)
            assertSame(after, result.afterBuild)
            assertNull(result.recorded)
            assertEquals(SelectedCostComparisonState.NOT_COMPARED, result.state)
        }
        assertEquals(SelectedCostComparisonState.COMPLETE, SelectedCostComparator.compare(selected, selected, input, input).state)
    }

    @Test fun `explicit costs for both changed selected records retain their own Build contents`() {
        val left = snapshot(fixture("build"))
        val changed = fixture("build").also { (it["selections"][0]["settings"] as ObjectNode).put("concurrency", 3) }
        val right = snapshot(changed)
        val before = admitted(left)
        val after = admitted(right, fixture("cost-estimate").put("id", "estimate.after"))
        val result = SelectedCostComparator.compare(left, right, before, after)
        assertEquals(SelectedCostComparisonState.COMPLETE, result.state)
        assertSame(before.record.estimate, result.recorded!!.before)
        assertSame(after.record.estimate, result.recorded!!.after)
        assertTrue(CostRecordDifference.BUILD_RECORD in result.recorded!!.differences)
        assertTrue(CostRecordIntegrity.SAME_BUILD_IDENTITY_WITH_DIFFERENT_RECORDS in result.recorded!!.integrity)
        assertEquals(mapper.readTree(changed.toString()), mapper.readTree(result.recorded!!.after.recordedBuildJson))
        noPricingVerdict(result)
    }

    @Test fun `equivalent selected JSON values match without requiring property order or numeric spelling`() {
        val original = fixture("build")
        val selected = snapshot(original)
        val input = admitted(selected)
        val same = mapper.createObjectNode()
        original.properties().toList().reversed().forEach { same.set<JsonNode>(it.key, it.value.deepCopy()) }
        (same["selections"][0]["settings"] as ObjectNode).put("concurrency", BigDecimal("2.000"))
        val result = SelectedCostComparator.compare(selected, snapshot(same), input, input)
        assertEquals(SelectedCostComparisonState.COMPLETE, result.state)
        assertSame(input, result.afterInput)
        assertTrue(result.recorded!!.differences.isEmpty())
        noPricingVerdict(result)
    }

    @Test fun `stale revision different Build and different locked pricebook reject the supplied estimate`() {
        for (field in listOf("build_revision", "build_id", "pricebook_id")) {
            val estimate = fixture("cost-estimate")
            if (field == "build_revision") estimate.put(field, 2) else estimate.put(field, "different.identity")
            val selected = snapshot(fixture("build"))
            val unavailable = load(selected, estimate)
            rejected(HistoricalCostProblem.BINDING_MISMATCH, unavailable)
            val result = SelectedCostComparator.compare(selected, selected, admitted(selected), unavailable)
            assertNull(result.recorded)
            assertSame(unavailable, result.afterInput)
        }
    }

    @Test fun `matching Build fields do not override the explicitly requested estimate identity`() {
        val selected = snapshot(fixture("build"))
        val estimate = fixture("cost-estimate").put("id", "estimate.private.fixture.actual")
        val wrong = load(selected, estimate, "estimate.private.fixture.requested")
        rejected(HistoricalCostProblem.ESTIMATE_IDENTITY_MISMATCH, wrong)
        val result = SelectedCostComparator.compare(selected, selected, wrong, admitted(selected))
        assertEquals(SelectedCostComparisonState.NOT_COMPARED, result.state)
        assertNull(result.recorded)
        assertSame(wrong, result.beforeInput)
    }

    @Test fun `complete unsupported input differs from malformed or failed provider input`() {
        val selected = snapshot(fixture("build"))
        val future = fixture("cost-estimate").put("schema_version", "9.0.0")
        rejected(HistoricalCostProblem.UNSUPPORTED_ESTIMATE_SCHEMA_VERSION, load(selected, future))
        val malformed = listOf("{\"schema_version\":\"9.0.0\",", future.toString() + " {}",
            "{\"schema_version\":\"9.0.0\",\"schema_version\":\"1.0.0\"}")
        for (text in malformed) {
            val input = TrackedInput(text.toByteArray())
            rejected(HistoricalCostProblem.INVALID_ESTIMATE, SelectedCostEstimate.read(selected, "estimate.requested", input))
            assertFalse(input.closed)
        }
        val failed = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("private.fixture.provider")
            override fun close() { closed = true }
        }
        rejected(HistoricalCostProblem.INVALID_ESTIMATE, SelectedCostEstimate.read(selected, "estimate.requested", failed))
        assertFalse(failed.closed)
    }

    @Test fun `missing selected Pricebook pin stays unavailable without a current Pricebook fallback`() {
        val build = fixture("build").also { (it["locks"] as ObjectNode).remove("pricebook_id") }
        val selected = snapshot(build)
        val input = load(selected)
        rejected(HistoricalCostProblem.MISSING_PRICEBOOK_PIN, input)
        val result = SelectedCostComparator.compare(selected, selected, input, input)
        assertNull(result.recorded)
        assertEquals(SelectedCostComparisonState.NOT_COMPARED, result.state)
    }

    @Test fun `existing cost integrity rejections survive selected admission without partial records`() {
        val selected = snapshot(fixture("build"))
        val mutations: List<Pair<HistoricalCostProblem, (ObjectNode) -> Unit>> = listOf(
            HistoricalCostProblem.MIXED_CURRENCY to { (it["tax"] as ObjectNode).put("currency", "EUR") },
            HistoricalCostProblem.INCONSISTENT_AMOUNTS to { (it["total"] as ObjectNode).put("amount", "1") },
            HistoricalCostProblem.UNKNOWN_SELECTION to { (it["line_items"][0] as ObjectNode).put("selection_id", "sel_missing") },
            HistoricalCostProblem.INCONSISTENT_COVERAGE to { (it["unpriced_selection_ids"] as ArrayNode).add("sel_storage") },
        )
        for ((reason, change) in mutations) {
            val result = load(selected, fixture("cost-estimate").also(change))
            rejected(reason, result)
            assertNull(SelectedCostComparator.compare(selected, selected, result, admitted(selected)).recorded)
        }
    }

    @Test fun `changed currency period Pricebook and dated evidence stay literal and noncomparable`() {
        val beforeBuild = snapshot(fixture("build"))
        val changed = fixture("build").put("revision", 2).also {
            (it["locks"] as ObjectNode).put("pricebook_id", "prices.other")
        }
        val afterBuild = snapshot(changed)
        val estimate = fixture("cost-estimate").put("id", "estimate.after").put("build_revision", 2)
            .put("pricebook_id", "prices.other").put("period_days", 31).put("estimated_at", "2099-01-01T00:00:00Z")
        estimate.findParents("currency").forEach { (it as ObjectNode).put("currency", "EUR") }
        val result = SelectedCostComparator.compare(beforeBuild, afterBuild, admitted(beforeBuild), admitted(afterBuild, estimate))
        assertTrue(result.recorded!!.differences.containsAll(setOf(CostRecordDifference.CURRENCY,
            CostRecordDifference.PERIOD, CostRecordDifference.PRICEBOOK_ID, CostRecordDifference.ESTIMATED_AT)))
        assertEquals("USD", result.recorded!!.before.currency)
        assertEquals("EUR", result.recorded!!.after.currency)
        assertEquals("prices.other", result.afterBuild.locks["pricebook_id"])
        assertEquals(mapper.readTree(estimate.toString()), mapper.readTree(result.recorded!!.after.recordedJson))
        noPricingVerdict(result)
    }

    @Test fun `complete original line evidence and unsupported quantity survive the selection boundary`() {
        val selected = snapshot(fixture("build"))
        val estimate = fixture("cost-estimate").put("id", "estimate.after")
        val line = estimate["line_items"][0] as ObjectNode
        val literal = "context\\path\n\u2067literal\u2069".repeat(20)
        line.put("quantity", "720\n")
        line.putArray("assumptions").add(literal).add("")
        val result = SelectedCostComparator.compare(selected, selected, admitted(selected), admitted(selected, estimate))
        val compared = result.recorded!!.lines.single { it.lineId == line["id"].textValue() }
        assertEquals("720\n", compared.after!!.quantity)
        assertEquals(listOf(literal, ""), compared.after!!.assumptions)
        assertEquals(RecordedQuantityComparison.UNAVAILABLE, compared.quantityComparison)
        assertTrue(CostLineField.ASSUMPTIONS in compared.differences)
        assertTrue(CostComparisonGap.UNSUPPORTED_QUANTITY in result.recorded!!.gaps)
        noPricingVerdict(result)
    }

    @Test fun `empty unknown coverage is admitted evidence and never a missing or accepted total`() {
        val selected = snapshot(fixture("build"))
        val unknown = fixture("cost-estimate").put("coverage", "unknown")
        unknown.putArray("line_items")
        for (field in listOf("subtotal", "tax", "total")) (unknown[field] as ObjectNode).put("amount", "0")
        val input = admitted(selected, unknown)
        val result = SelectedCostComparator.compare(selected, selected, input, input)
        assertEquals(SelectedCostComparisonState.COMPLETE, result.state)
        assertTrue(result.recorded!!.lines.isEmpty())
        assertEquals(DeclaredCoverage.UNKNOWN, result.recorded!!.before.declaredCoverage)
        assertTrue(result.recorded!!.before.unclassifiedActiveSelectionIds.isNotEmpty())
        assertSame(input, result.beforeInput)
        noPricingVerdict(result)
    }

    @Test fun `reused estimate identities still expose differing complete records`() {
        val selected = snapshot(fixture("build"))
        val changed = fixture("cost-estimate").also { (it["warnings"] as ArrayNode).add("changed retained warning") }
        val result = SelectedCostComparator.compare(selected, selected, admitted(selected), admitted(selected, changed))
        assertTrue(CostRecordIntegrity.SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS in result.recorded!!.integrity)
        assertTrue(CostRecordDifference.WARNINGS in result.recorded!!.differences)
        assertEquals(changed, mapper.readTree(result.recorded!!.after.recordedJson))
        noPricingVerdict(result)
    }

    @Test fun `line limits return no partial comparison and a later bounded request starts cleanly`() {
        val selected = snapshot(fixture("build"))
        val input = admitted(selected)
        val limited = SelectedCostComparator.compare(selected, selected, input, input, 1)
        assertEquals(SelectedCostComparisonState.LINE_LIMIT_EXCEEDED, limited.state)
        assertTrue(limited.recorded!!.lines.isEmpty())
        assertSame(input.record.estimate, limited.recorded!!.before)
        noPricingVerdict(limited)
        val full = SelectedCostComparator.compare(selected, selected, input, input, 2)
        assertEquals(SelectedCostComparisonState.COMPLETE, full.state)
        assertEquals(2, full.recorded!!.lines.size)
        for (limit in listOf(0, 10_001)) assertThrows(IllegalArgumentException::class.java) {
            SelectedCostComparator.compare(selected, selected, maxLines = limit)
        }
    }

    @Test fun `reversing selected inputs preserves both records without reassigning one side`() {
        val firstBuild = snapshot(fixture("build"))
        val secondBuild = snapshot(fixture("build").put("revision", 2))
        val first = admitted(firstBuild)
        val second = admitted(secondBuild, fixture("cost-estimate").put("id", "estimate.second").put("build_revision", 2))
        val reversed = SelectedCostComparator.compare(secondBuild, firstBuild, second, first)
        assertSame(second.record.estimate, reversed.recorded!!.before)
        assertSame(first.record.estimate, reversed.recorded!!.after)
        val wrongOrder = SelectedCostComparator.compare(secondBuild, firstBuild, first, second)
        rejected(HistoricalCostProblem.BUILD_RECORD_MISMATCH, wrongOrder.beforeInput)
        rejected(HistoricalCostProblem.BUILD_RECORD_MISMATCH, wrongOrder.afterInput)
        assertNull(wrongOrder.recorded)
        noPricingVerdict(reversed)
    }

    @Test fun `caller mutation and stream closure cannot change admitted records or retained results`() {
        val buildJson = fixture("build")
        val estimateJson = fixture("cost-estimate")
        val selected = snapshot(buildJson)
        val input = TrackedInput(estimateJson.toString().toByteArray())
        val available = assertInstanceOf(HistoricalCostInput.Available::class.java,
            SelectedCostEstimate.read(selected, estimateJson["id"].textValue(), input))
        assertFalse(input.closed)
        val beforeBuild = available.record.estimate.recordedBuildJson
        val beforeEstimate = available.record.estimate.recordedJson
        buildJson.put("name", "private.fixture.replaced")
        estimateJson.put("id", "estimate.private.fixture.replaced")
        input.close()
        val result = SelectedCostComparator.compare(selected, selected, available, available)
        assertEquals(SelectedCostComparisonState.COMPLETE, result.state)
        assertEquals(beforeBuild, result.recorded!!.before.recordedBuildJson)
        assertEquals(beforeEstimate, result.recorded!!.after.recordedJson)
        assertThrows(UnsupportedOperationException::class.java) { (result.recorded!!.lines as MutableList<*>).clear() }
        assertEquals("HistoricalCostInput.Available", available.toString())
        assertEquals("SelectedCostEstimate", available.record.toString())
        assertEquals("SelectedCostComparison", result.toString())
        assertTrue(listOf(available, available.record, result).all { !it.toString().contains("private.fixture") })
    }
}
