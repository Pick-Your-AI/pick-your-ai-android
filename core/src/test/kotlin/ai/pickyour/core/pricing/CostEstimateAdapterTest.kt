package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
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
    @TestFactory
    fun `schema permitted terminal LF money returns a controlled rejection`(): List<DynamicTest> =
        listOf("currency", "/line_items/0/unit_price/amount", "/line_items/0/amount/amount",
            "/subtotal/amount", "/tax/amount", "/total/amount").map { path -> DynamicTest.dynamicTest(path) {
            val e = fixture("cost-estimate")
            if (path == "currency") {
                // Keep all currencies equal so this reaches the typed-money boundary, not MIXED_CURRENCY.
                e.findParents("currency").forEach { node ->
                    (node as ObjectNode).put("currency", node["currency"].textValue() + "\n")
                }
            } else {
                val money = e.at(path.substringBeforeLast('/')) as ObjectNode
                money.put("amount", money["amount"].textValue() + "\n")
            }
            val original = e.toString()
            assertEquals(FrozenValidation.Valid,
                FrozenSchemaValidator().validate(FrozenDocument.COST_ESTIMATE, original.byteInputStream()))
            class Tracked(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
                var closed = false
                override fun close() { closed = true; super.close() }
            }
            val build = Tracked(fixture("build").toString().toByteArray())
            val estimate = Tracked(original.toByteArray())
            assertEquals(CostReception.Rejected(CostRejection.INVALID_ESTIMATE), adapter.inspect(build, estimate))
            assertFalse(build.closed)
            assertFalse(estimate.closed)
            assertEquals(original, e.toString())
            // Rejection must not poison a later read on the same adapter or silently coerce the input.
            assertEquals("578.50", value().reportedTotal.amount.encoded)
        } }
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
    @Test fun `schema permitted terminal LF quantity remains recorded rather than treated as money`() {
        val e = fixture("cost-estimate")
        (e["line_items"][0] as ObjectNode).put("quantity", "720\n")
        val result = value(e)
        assertEquals("720\n", result.lines.first().quantity)
        assertEquals("720\n", mapper.readTree(result.recordedJson)["line_items"][0]["quantity"].textValue())
        assertEquals("578.50", result.reportedTotal.amount.encoded)
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
        assertEquals("UNSUPPORTED_ESTIMATE_SCHEMA_VERSION", (inspect(fixture("cost-estimate").put("schema_version", "2.0.0")) as CostReception.Rejected).reason.name)
        rejected(CostRejection.INVALID_ESTIMATE, fixture("cost-estimate").apply { putArray("warnings").add("x".repeat(20_000)) })
        assertEquals(CostReception.Rejected(CostRejection.INVALID_BUILD), adapter.inspect("{}".byteInputStream(), fixture("cost-estimate").toString().byteInputStream()))
    }

    private enum class VersionInput(val fixtureName: String, val invalid: CostRejection, val unsupported: String) {
        BUILD("build", CostRejection.INVALID_BUILD, "UNSUPPORTED_BUILD_SCHEMA_VERSION"),
        ESTIMATE("cost-estimate", CostRejection.INVALID_ESTIMATE, "UNSUPPORTED_ESTIMATE_SCHEMA_VERSION"),
    }

    private class TrackedInput(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
        var readCalls = 0
        var closed = false
        override fun read(): Int { readCalls++; return super.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            return super.read(bytes, offset, length)
        }
        override fun close() { closed = true; super.close() }
    }

    private fun inspectVersionBytes(input: VersionInput, bytes: ByteArray): CostReception {
        val buildBytes = if (input == VersionInput.BUILD) bytes else fixture("build").toString().toByteArray()
        val estimateBytes = if (input == VersionInput.ESTIMATE) bytes else fixture("cost-estimate").toString().toByteArray()
        val originalBuild = buildBytes.copyOf()
        val originalEstimate = estimateBytes.copyOf()
        val build = TrackedInput(buildBytes)
        val estimate = TrackedInput(estimateBytes)
        val result = adapter.inspect(build, estimate)
        assertFalse(build.closed)
        assertFalse(estimate.closed)
        assertArrayEquals(originalBuild, buildBytes)
        assertArrayEquals(originalEstimate, estimateBytes)
        if (input == VersionInput.BUILD && result is CostReception.Rejected) assertEquals(0, estimate.readCalls)
        return result
    }

    private fun rejectedVersionBytes(input: VersionInput, bytes: ByteArray, expected: String) {
        val result = assertInstanceOf(CostReception.Rejected::class.java, inspectVersionBytes(input, bytes))
        assertEquals(expected, result.reason.name)
        assertEquals("Rejected(reason=$expected)", result.toString())
    }

    @TestFactory fun `textual unsupported versions identify the supplied record without disclosing values`(): List<DynamicTest> =
        VersionInput.entries.flatMap { input ->
            listOf("old" to "0.9.0", "minor" to "1.0.1", "major" to "2.0.0",
                "empty" to "", "terminal LF" to "1.0.0\n", "opaque text" to "private-cost-version").map { (label, version) ->
                DynamicTest.dynamicTest("${input.name}: $label version") {
                    val bytes = fixture(input.fixtureName).put("schema_version", version).toString().toByteArray()
                    rejectedVersionBytes(input, bytes, input.unsupported)
                }
            }
        }

    @TestFactory fun `missing and nontext versions retain the original invalid record outcome`(): List<DynamicTest> =
        VersionInput.entries.flatMap { input ->
            listOf("missing" to null, "null" to "null", "boolean" to "false", "integer" to "1",
                "decimal" to "1.0", "array" to "[]", "object" to "{}").map { (label, json) ->
                DynamicTest.dynamicTest("${input.name}: $label version field") {
                    val node = fixture(input.fixtureName)
                    if (json == null) node.remove("schema_version")
                    else node.set<com.fasterxml.jackson.databind.JsonNode>("schema_version", mapper.readTree(json))
                    rejectedVersionBytes(input, node.toString().toByteArray(), input.invalid.name)
                }
            }
        }

    private fun strictVersionCases(): List<Pair<String, () -> ByteArray>> = listOf(
        "duplicate version" to { """{"schema_version":"2.0.0","schema_version":"1.0.0"}""".toByteArray() },
        "reversed duplicate version" to { """{"schema_version":"1.0.0","schema_version":"2.0.0"}""".toByteArray() },
        "nested duplicate" to { """{"schema_version":"2.0.0","x":{"a":1,"a":2}}""".toByteArray() },
        "trailing object" to { """{"schema_version":"2.0.0"} {}""".toByteArray() },
        "trailing private text" to { """{"schema_version":"2.0.0"} private-input""".toByteArray() },
        "truncated object" to { """{"schema_version":"2.0.0","x":""".toByteArray() },
        "invalid UTF-8" to { """{"schema_version":"2.0.0","x":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray() },
        "unpaired surrogate" to { """{"schema_version":"2.0.0","x":"\uD800"}""".toByteArray() },
        "string limit" to { ("""{"schema_version":"2.0.0","x":"""" + "x".repeat(16_385) + "\"}").toByteArray() },
        "member-name limit" to { ("""{"schema_version":"2.0.0","""" + "x".repeat(257) + "\":0}").toByteArray() },
        "number limit" to { ("""{"schema_version":"2.0.0","x":""" + "1".repeat(129) + "}").toByteArray() },
        "depth limit" to { ("""{"schema_version":"2.0.0","x":""" + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray() },
        "token limit" to { ("""{"schema_version":"2.0.0","x":[""" + "0,".repeat(100_000) + "0]}").toByteArray() },
        "byte limit after complete object" to {
            val prefix = """{"schema_version":"2.0.0"}""".toByteArray()
            prefix + ByteArray(1_048_577 - prefix.size) { 0x20 }
        },
        "array root" to { """[{"schema_version":"2.0.0"}]""".toByteArray() },
    )

    @TestFactory fun `complete strict parsing wins over an early recognized version`(): List<DynamicTest> =
        VersionInput.entries.flatMap { input -> strictVersionCases().map { (label, bytes) ->
            DynamicTest.dynamicTest("${input.name}: $label before version") {
                rejectedVersionBytes(input, bytes(), input.invalid.name)
            }
        } }

    @TestFactory fun `provider failure after a version prefix stays invalid and streams stay open`(): List<DynamicTest> =
        VersionInput.entries.map { input -> DynamicTest.dynamicTest("${input.name}: failure before EOF") {
            val failing = object : java.io.InputStream() {
                val bytes = """{"schema_version":"2.0.0"}""".byteInputStream()
                var closed = false
                override fun read(): Int = bytes.read().also { if (it == -1) throw java.io.IOException("private-provider-detail") }
                override fun close() { closed = true }
            }
            val other = TrackedInput(fixture(if (input == VersionInput.BUILD) "cost-estimate" else "build").toString().toByteArray())
            val result = if (input == VersionInput.BUILD) adapter.inspect(failing, other) else adapter.inspect(other, failing)
            assertEquals(CostReception.Rejected(input.invalid), result)
            assertFalse(result.toString().contains("private-provider-detail"))
            assertFalse(failing.closed)
            assertFalse(other.closed)
            if (input == VersionInput.BUILD) assertEquals(0, other.readCalls)
        } }

    @TestFactory fun `supported versions still require the unchanged full record schema`(): List<DynamicTest> =
        VersionInput.entries.flatMap { input -> listOf("unknown field", "missing ID", "invalid enum", "invalid revision").map { label ->
            DynamicTest.dynamicTest("${input.name}: $label at supported version") {
                val node = fixture(input.fixtureName)
                when (label) {
                    "unknown field" -> node.put("private-extra", true)
                    "missing ID" -> node.remove("id")
                    "invalid enum" -> if (input == VersionInput.BUILD) (node["selections"][0] as ObjectNode).put("kind", "unknown_kind")
                        else (node["line_items"][0] as ObjectNode).put("meter", "unknown_meter")
                    "invalid revision" -> node.put(if (input == VersionInput.BUILD) "revision" else "build_revision", 0)
                }
                rejectedVersionBytes(input, node.toString().toByteArray(), input.invalid.name)
            }
        } }

    @TestFactory fun `Build admission wins without reading a rejected estimate`(): List<DynamicTest> =
        listOf("both unsupported", "invalid Build schema", "malformed estimate").map { label -> DynamicTest.dynamicTest(label) {
            val node = fixture("build")
            if (label == "invalid Build schema") node.remove("id") else node.put("schema_version", "2.0.0")
            val build = TrackedInput(node.toString().toByteArray())
            val estimate = TrackedInput(if (label == "malformed estimate") "{private".toByteArray()
                else fixture("cost-estimate").put("schema_version", "2.0.0").toString().toByteArray())
            val result = assertInstanceOf(CostReception.Rejected::class.java, adapter.inspect(build, estimate))
            assertEquals(if (label == "invalid Build schema") "INVALID_BUILD" else "UNSUPPORTED_BUILD_SCHEMA_VERSION", result.reason.name)
            assertTrue(build.readCalls > 0)
            assertEquals(0, estimate.readCalls)
            assertFalse(build.closed)
            assertFalse(estimate.closed)
        } }

    @Test fun `both record admissions still precede the missing pricebook semantic check`() {
        val build = fixture("build")
        (build["locks"] as ObjectNode).remove("pricebook_id")
        val estimate = fixture("cost-estimate").put("schema_version", "2.0.0")
        assertEquals("UNSUPPORTED_ESTIMATE_SCHEMA_VERSION", (inspect(estimate, build) as CostReception.Rejected).reason.name)
        estimate.put("schema_version", "1.0.0")
        rejected(CostRejection.MISSING_PRICEBOOK_PIN, estimate, build)
        assertEquals("578.50", value().reportedTotal.amount.encoded)
    }

    @TestFactory fun `supported records at the original byte budget remain inspectable`(): List<DynamicTest> =
        VersionInput.entries.map { input -> DynamicTest.dynamicTest("${input.name}: exact byte budget") {
            val original = fixture(input.fixtureName).toString().toByteArray()
            val bytes = original + ByteArray(1_048_576 - original.size) { 0x20 }
            val result = assertInstanceOf(CostReception.Inspected::class.java, inspectVersionBytes(input, bytes)).estimate
            assertEquals("578.50", result.reportedTotal.amount.encoded)
            assertEquals(CostEvidence.NOT_ESTABLISHED, result.budgetPass)
            assertEquals(CostEvidence.NOT_ESTABLISHED, result.freshness)
            assertEquals(CostEvidence.NOT_ESTABLISHED, result.freeOrIncludedTreatment)
        } }

    @Test fun `all previous rejection names keep their enum positions`() {
        val original = listOf("INVALID_BUILD", "INVALID_ESTIMATE", "MISSING_PRICEBOOK_PIN", "BINDING_MISMATCH",
            "DUPLICATE_ID", "UNKNOWN_SELECTION", "MIXED_CURRENCY", "INCONSISTENT_AMOUNTS", "INCONSISTENT_COVERAGE")
        assertEquals(original, CostRejection.entries.take(original.size).map { it.name })
    }
}
