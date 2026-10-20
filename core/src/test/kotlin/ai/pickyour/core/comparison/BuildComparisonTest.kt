package ai.pickyour.core.comparison

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest
import java.math.BigDecimal
import java.math.BigInteger

class BuildComparisonTest {
    private val mapper = ObjectMapper()
    private fun example(): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/build.json").use { mapper.readTree(it) as ObjectNode }
    private fun read(node: ObjectNode, expected: SnapshotIdentity = SnapshotIdentity(node["id"].textValue(), node["revision"].bigIntegerValue())) =
        ComparisonSnapshot.read(mapper.writeValueAsBytes(node).inputStream(), expected)
    private fun snapshot(node: ObjectNode) = (read(node) as SnapshotRead.Available).snapshot
    private fun settings(node: ObjectNode) = node["selections"][0]["settings"] as ObjectNode

    @Test fun `settings role objective and graph changes have exact categories with unchanged catalog item`() {
        val left = example()
        val right = left.deepCopy().put("revision", 2)
        settings(right).put("concurrency", 3)
        (right["selections"][0] as ObjectNode).put("role", "secondary")
        (right["objectives"] as ObjectNode).put("max_concurrency", 3)
        (right["locks"] as ObjectNode).put("dependency_graph_revision", 2)
        val leftBytes = mapper.writeValueAsBytes(left)
        val rightBytes = mapper.writeValueAsBytes(right)
        val result = BuildComparator.compare(snapshot(left), snapshot(right))
        assertEquals(listOf("/locks/dependency_graph_revision", "/objectives/max_concurrency", "/revision",
            "/selections/sel_workload/role", "/selections/sel_workload/settings/concurrency"), result.changes.map { it.path })
        assertEquals(listOf(ChangeCategory.LOCK, ChangeCategory.OBJECTIVE, ChangeCategory.METADATA, ChangeCategory.ROLE, ChangeCategory.SETTING), result.changes.map { it.category })
        assertTrue(result.changes.all { it.kind == ChangeKind.CHANGED })
        assertEquals(EvidenceCondition.RECORDED_LOCKS_DIFFER, result.evidenceCondition)
        assertArrayEquals(leftBytes, mapper.writeValueAsBytes(left))
        assertArrayEquals(rightBytes, mapper.writeValueAsBytes(right))
    }

    @Test fun `selection and relationship additions removals stable by id not array order`() {
        val left = example()
        val right = left.deepCopy()
        val selections = right["selections"] as ArrayNode
        val removed = selections.remove(0) as ObjectNode
        selections.add(removed.deepCopy().put("id", "sel_new"))
        val links = right["links"] as ArrayNode
        val link = links.remove(0) as ObjectNode
        links.add(link.deepCopy().put("id", "link.new"))
        val comparison = BuildComparator.compare(snapshot(left), snapshot(right))
        assertEquals(listOf("/links/link.01", "/links/link.new", "/selections/sel_new", "/selections/sel_workload"), comparison.changes.map { it.path })
        assertEquals(listOf(ChangeKind.REMOVED, ChangeKind.ADDED, ChangeKind.ADDED, ChangeKind.REMOVED), comparison.changes.map { it.kind })
        val reorder = left.deepCopy()
        val array = reorder["selections"] as ArrayNode
        val first = array.remove(0); array.add(first)
        assertTrue(BuildComparator.compare(snapshot(left), snapshot(reorder)).changes.isEmpty())
    }

    @Test fun `same locks never assert historical costs or findings comparable`() {
        val left = snapshot(example())
        val result = BuildComparator.compare(left, left)
        assertEquals(EvidenceCondition.SAME_RECORDED_LOCKS_NOT_PROOF_OF_COMPARABILITY, result.evidenceCondition)
        assertEquals(HistoricalOutputState.NOT_LOADED, result.historicalCost)
        assertEquals(HistoricalOutputState.NOT_LOADED, result.historicalFindings)
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY, result.identityCondition)
    }

    @Test fun `equivalent JSON numeric representations do not invent changes or different locks`() {
        val left = example()
        val right = left.deepCopy().put("revision", BigDecimal("1.00"))
        (right["locks"] as ObjectNode).put("dependency_graph_revision", BigDecimal("1.0"))
        settings(right).put("concurrency", BigDecimal("2.000"))
        left.putObject("extensions").putArray("x_numbers").add(1).addObject().put("nested", BigInteger("9007199254740993"))
        right.putObject("extensions").putArray("x_numbers").add(BigDecimal("1.0")).addObject()
            .put("nested", BigDecimal("9007199254740993.00"))

        val result = BuildComparator.compare(snapshot(left), snapshot(right))

        assertTrue(result.changes.isEmpty(), result.changes.joinToString { it.path })
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY, result.identityCondition)
        assertEquals(EvidenceCondition.SAME_RECORDED_LOCKS_NOT_PROOF_OF_COMPARABILITY, result.evidenceCondition)
    }

    @Test fun `equivalent graph revision spelling retains recorded text without claiming changed evidence`() {
        val left = snapshot(example())
        val node = example()
        (node["locks"] as ObjectNode).put("dependency_graph_revision", BigDecimal("1.0"))
        val right = snapshot(node)

        assertEquals("1", left.locks["dependency_graph_revision"])
        assertEquals("1.0", right.locks["dependency_graph_revision"])
        val result = BuildComparator.compare(left, right)
        assertEquals(EvidenceCondition.SAME_RECORDED_LOCKS_NOT_PROOF_OF_COMPARABILITY, result.evidenceCondition)
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY, result.identityCondition)
        assertTrue(result.changes.isEmpty())
    }

    @Test fun `actual numeric changes remain exact beyond binary float precision`() {
        val left = example()
        val right = left.deepCopy()
        left.putObject("extensions").put("x_value", BigInteger("9007199254740993"))
            .put("x_tiny", BigDecimal("1e-1000"))
        right.putObject("extensions").put("x_value", BigDecimal("9007199254740993.0000000000000000001"))
            .put("x_tiny", BigDecimal("2e-1000"))

        val result = BuildComparator.compare(snapshot(left), snapshot(right))

        assertEquals(listOf("/extensions/x_tiny", "/extensions/x_value"), result.changes.map { it.path })
        assertEquals("9007199254740993", result.changes[1].beforeJson)
        assertEquals("9007199254740993.0000000000000000001", result.changes[1].afterJson)
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY_WITH_DIFFERENCES, result.identityCondition)
    }

    @Test fun `numeric equality preserves string boolean and atomic array distinctions`() {
        val left = example()
        val right = left.deepCopy()
        (left["objectives"] as ObjectNode).put("monthly_budget_amount", "100")
        (right["objectives"] as ObjectNode).put("monthly_budget_amount", "100.00")
        left.putObject("extensions").put("x_type", 1).put("x_bool", 1).putArray("x_order").add(1).add(2)
        right.putObject("extensions").put("x_type", "1").put("x_bool", true)
            .putArray("x_order").add(BigDecimal("2.0")).add(BigDecimal("1.0"))

        val result = BuildComparator.compare(snapshot(left), snapshot(right))

        assertEquals(listOf("/extensions/x_bool", "/extensions/x_order", "/extensions/x_type",
            "/objectives/monthly_budget_amount"), result.changes.map { it.path })
        assertTrue(result.changes.all { it.kind == ChangeKind.CHANGED })
        assertEquals("[1,2]", result.changes[1].beforeJson)
        assertEquals("[2.0,1.0]", result.changes[1].afterJson)
        assertEquals("\"100\"", result.changes.last().beforeJson)
        assertEquals("\"100.00\"", result.changes.last().afterJson)
    }

    @Test fun `changed pricebook rules and missing optional pin disclosed without computing reports`() {
        val left = example()
        val right = left.deepCopy()
        (right["locks"] as ObjectNode).put("ruleset_version", "2.0.0").put("catalog_snapshot_id", "catalog.other")
        (right["locks"] as ObjectNode).remove("pricebook_id")
        val result = BuildComparator.compare(snapshot(left), snapshot(right))
        assertEquals(listOf("/locks/catalog_snapshot_id", "/locks/pricebook_id", "/locks/ruleset_version"), result.changes.map { it.path })
        assertEquals(ChangeKind.REMOVED, result.changes[1].kind)
        assertNull(result.changes[1].afterJson)
        assertEquals(HistoricalOutputState.NOT_LOADED, result.historicalCost)
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY_WITH_DIFFERENCES, result.identityCondition)
    }

    @Test fun `snapshot input and public outputs cannot mutate retained comparison`() {
        val node = example()
        val retained = snapshot(node)
        settings(node).put("concurrency", 99)
        val result = BuildComparator.compare(retained, snapshot(example()))
        assertTrue(result.changes.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (retained.locks as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.changes as MutableList).clear() }
        assertEquals("ComparisonSnapshot", retained.toString())
        assertEquals("BuildComparison", result.toString())
    }

    @Test fun `schema errors identity mismatches and duplicate IDs rejected before diff`() {
        val node = example()
        assertEquals(SnapshotRead.Rejected(SnapshotProblem.IDENTITY_MISMATCH), read(node, SnapshotIdentity("other.build", BigInteger.ONE)))
        val duplicate = node.deepCopy()
        (duplicate["selections"] as ArrayNode).add(duplicate["selections"][0].deepCopy<ObjectNode>())
        assertEquals(SnapshotRead.Rejected(SnapshotProblem.DUPLICATE_SELECTION_ID), read(duplicate))
        val links = node.deepCopy()
        (links["links"] as ArrayNode).add(links["links"][0].deepCopy<ObjectNode>())
        assertEquals(SnapshotRead.Rejected(SnapshotProblem.DUPLICATE_LINK_ID), read(links))
        node.put("schema_version", "2.0.0")
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", (read(node) as SnapshotRead.Rejected).reason.name)
    }

    @Test fun `malformed and oversized imports rejected without closing caller stream`() {
        val identity = SnapshotIdentity("build.fixture.1", BigInteger.ONE)
        assertEquals(SnapshotRead.Rejected(SnapshotProblem.INVALID_INPUT), ComparisonSnapshot.read("{".byteInputStream(), identity))
        val node = example().put("name", "x".repeat(20_000))
        assertEquals(SnapshotRead.Rejected(SnapshotProblem.INVALID_INPUT), read(node))
        var closed = false
        val input = object : java.io.ByteArrayInputStream(mapper.writeValueAsBytes(example())) {
            override fun close() { closed = true; super.close() }
        }
        assertTrue(ComparisonSnapshot.read(input, identity) is SnapshotRead.Available)
        assertFalse(closed)
    }

    @Test fun `added removed setting and changed relation retain exact encoded values`() {
        val left = example()
        val right = left.deepCopy()
        settings(right).remove("concurrency")
        settings(left).remove("avg_output_tokens")
        (right["links"][0] as ObjectNode).put("relation", "uses")
        val result = BuildComparator.compare(snapshot(left), snapshot(right))
        assertEquals(listOf("/links/link.01/relation", "/selections/sel_workload/settings/avg_output_tokens", "/selections/sel_workload/settings/concurrency"), result.changes.map { it.path })
        assertEquals(listOf(ChangeKind.CHANGED, ChangeKind.ADDED, ChangeKind.REMOVED), result.changes.map { it.kind })
        assertEquals("\"provided_by\"", result.changes[0].beforeJson)
        assertEquals("\"uses\"", result.changes[0].afterJson)
        assertEquals("SnapshotChange", result.changes[0].toString())
    }
    @Test fun `change budget fails explicitly without returning a partial empty success`() {
        val left = example()
        val right = left.deepCopy()
        settings(right).put("concurrency", 3).put("requests_per_day", 201)
        val result = BuildComparator.compare(snapshot(left), snapshot(right), maxChanges = 1)
        assertEquals(ComparisonCompleteness.LIMIT_EXCEEDED, result.completeness)
        assertTrue(result.changes.isEmpty())
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY_WITH_DIFFERENCES, result.identityCondition)
        assertEquals(ComparisonCompleteness.COMPLETE, BuildComparator.compare(snapshot(left), snapshot(right), 2).completeness)
    }

    @Test fun `nested extension paths escape slash and tilde and preserve null versus absence`() {
        val left = example()
        val right = left.deepCopy()
        left.putObject("extensions").putObject("x_test").put("a/b~c", "old")
        right.putObject("extensions").putObject("x_test").putNull("a/b~c").putNull("new")
        val result = BuildComparator.compare(snapshot(left), snapshot(right))
        assertEquals(listOf("/extensions/x_test/a~1b~0c", "/extensions/x_test/new"), result.changes.map { it.path })
        assertEquals("null", result.changes[0].afterJson)
        assertNull(result.changes[1].beforeJson)
        assertEquals("null", result.changes[1].afterJson)
    }

    private fun rejectedInput(bytes: ByteArray, expectedReason: String) {
        val original = bytes.copyOf()
        var closed = false
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val result = ComparisonSnapshot.read(input, SnapshotIdentity("build.fixture.1", BigInteger.ONE))
        assertTrue(result is SnapshotRead.Rejected)
        assertEquals(expectedReason, (result as SnapshotRead.Rejected).reason.name)
        assertEquals("Rejected(reason=$expectedReason)", result.toString())
        assertFalse(closed)
        assertArrayEquals(original, bytes)
    }

    @TestFactory fun `present textual unsupported versions have an explicit comparison diagnostic`() =
        listOf("0.9.0", "1.0.1", "2.0.0", "", "1.0.0 ", "1.0.0\n", "v1.0.0", "not-a-version")
            .mapIndexed { index, version -> dynamicTest("unsupported version spelling $index") {
                rejectedInput(mapper.writeValueAsBytes(example().put("schema_version", version)), "UNSUPPORTED_SCHEMA_VERSION")
            } }

    @TestFactory fun `missing and nontextual versions retain schema mismatch`() =
        listOf<Pair<String, (ObjectNode) -> Unit>>(
            "missing" to { it.remove("schema_version"); Unit },
            "null" to { it.putNull("schema_version"); Unit },
            "boolean" to { it.put("schema_version", false); Unit },
            "number" to { it.put("schema_version", 1); Unit },
            "array" to { it.putArray("schema_version"); Unit },
            "object" to { it.putObject("schema_version"); Unit },
        ).map { (name, change) -> dynamicTest("version field $name") {
            val node = example().also(change)
            rejectedInput(mapper.writeValueAsBytes(node), "SCHEMA_MISMATCH")
        } }

    @TestFactory fun `supported version does not bypass the unchanged build schema`() =
        listOf<Pair<String, (ObjectNode) -> Unit>>(
            "extra field" to { it.put("unrecognized", true); Unit },
            "invalid kind" to { (it["selections"][0] as ObjectNode).put("kind", "not_a_kind"); Unit },
            "missing identity" to { it.remove("id"); Unit },
        ).map { (name, change) -> dynamicTest("supported version $name") {
            rejectedInput(mapper.writeValueAsBytes(example().also(change)), "SCHEMA_MISMATCH")
        } }

    @TestFactory fun `complete strict parse precedes either version outcome`() =
        listOf("1.0.0", "2.0.0").flatMap { version ->
            val prefix = "{\"schema_version\":\"$version\","
            val inputs = listOf<Pair<String, () -> ByteArray>>(
                "truncated object" to { prefix.toByteArray() },
                "duplicate key" to { (prefix + "\"schema_version\":\"1.0.0\"}").toByteArray() },
                "second value" to { mapper.writeValueAsBytes(example().put("schema_version", version)) + " {}".toByteArray() },
                "invalid UTF8" to { (prefix + "\"name\":\"").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray() },
                "unpaired surrogate" to { (prefix + "\"name\":\"\\ud800\"}").toByteArray() },
                "string bound" to { (prefix + "\"name\":\"" + "x".repeat(16_385) + "\"}").toByteArray() },
                "name bound" to { (prefix + "\"" + "x".repeat(257) + "\":0}").toByteArray() },
                "number bound" to { (prefix + "\"revision\":" + "1".repeat(129) + "}").toByteArray() },
                "depth bound" to { (prefix + "\"extensions\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray() },
                "token bound" to { (prefix + "\"extensions\":[" + "0,".repeat(100_000) + "0]}").toByteArray() },
                "byte bound" to { mapper.writeValueAsBytes(example().put("schema_version", version)) + ByteArray(1_048_576) { 32 } },
            )
            inputs.map { (name, bytes) -> dynamicTest("version $version rejects $name before classification") {
                rejectedInput(bytes(), "INVALID_INPUT")
            } }
        }

    @Test fun `version rejection preserves retained snapshot and cannot return comparison authority`() {
        val original = example()
        val bytes = mapper.writeValueAsBytes(original)
        val retained = snapshot(original)
        val unsupported = original.deepCopy().put("schema_version", "2.0.0")
        rejectedInput(mapper.writeValueAsBytes(unsupported), "UNSUPPORTED_SCHEMA_VERSION")
        settings(unsupported).put("concurrency", 99)
        assertArrayEquals(bytes, mapper.writeValueAsBytes(original))
        val comparison = BuildComparator.compare(retained, snapshot(original))
        assertEquals(ComparisonCompleteness.COMPLETE, comparison.completeness)
        assertEquals(SnapshotIdentityCondition.SAME_IDENTITY, comparison.identityCondition)
        assertTrue(comparison.changes.isEmpty())
        assertEquals(HistoricalOutputState.NOT_LOADED, comparison.historicalCost)
        assertEquals(HistoricalOutputState.NOT_LOADED, comparison.historicalFindings)
    }

    @Test fun `the existing comparison problem order is retained`() {
        assertEquals(listOf("INVALID_INPUT", "SCHEMA_MISMATCH", "IDENTITY_MISMATCH", "DUPLICATE_SELECTION_ID", "DUPLICATE_LINK_ID"),
            SnapshotProblem.entries.take(5).map { it.name })
    }

}
