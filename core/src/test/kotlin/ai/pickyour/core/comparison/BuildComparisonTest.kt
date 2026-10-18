package ai.pickyour.core.comparison

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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
        assertEquals(SnapshotRead.Rejected(SnapshotProblem.SCHEMA_MISMATCH), read(node))
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

}
