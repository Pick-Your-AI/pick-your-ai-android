package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class ReferenceSelectionContextTest {
    private val mapper = ObjectMapper()
    private fun fixture(name: String) = mapper.readTree(
        javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode

    private fun receive(build: ObjectNode, report: ObjectNode = fixture("evaluation-report")): ReportReception.Accepted {
        val rules = fixture("ruleset")
        for ((finding, rule) in report["findings"].zip(rules["rules"])) {
            val kinds = rule["affected_kinds"].map { it.asText() }.toSet()
            val ids = build["selections"].filter { it["enabled"].asBoolean() && it["kind"].asText() in kinds }
                .map { it["id"].asText() }.toSortedSet()
            (finding as ObjectNode).putArray("selection_ids").also { array -> ids.forEach(array::add) }
        }
        return ReferenceReportAdapter().receive(build.toString().byteInputStream(),
            rules.toString().byteInputStream(), report.toString().byteInputStream()) as ReportReception.Accepted
    }

    @Test fun `exact affected IDs retain multiple roles versions and report binding`() {
        val build = fixture("build")
        val selections = build["selections"] as ArrayNode
        val model = selections.single { it["id"].asText() == "sel_model" }.deepCopy<ObjectNode>()
        selections.add(model.put("id", "sel_model_fallback").put("role", "fallback")
            .put("catalog_item_version", "recorded-fallback.17"))
        val report = fixture("evaluation-report")
        val revision = BigInteger("18446744073709551617")
        build.put("revision", revision); report.put("build_revision", revision)
        val view = receive(build, report).inspect()
        assertEquals(revision, view.buildRevision)
        assertEquals("catalog.fixture.1", view.catalogSnapshotId)
        for (finding in view.findings) assertEquals(finding.selectionIds, finding.selectionContexts.map { it.selectionId })
        val contexts = view.findings.first().selectionContexts.filterIsInstance<ReferenceSelectionContext.Recorded>()
        val primary = contexts.single { it.selectionId == "sel_model" }
        val fallback = contexts.single { it.selectionId == "sel_model_fallback" }
        assertEquals("primary", primary.role)
        assertEquals("2026.10-demo", primary.catalogItemVersion)
        assertEquals("fallback", fallback.role)
        assertEquals("recorded-fallback.17", fallback.catalogItemVersion)
        assertEquals("cat.model.fixture", fallback.catalogItemId)
        assertEquals("model", fallback.kind)
        assertTrue(fallback.enabled)
        assertTrue(contexts.any { it.kind == "workload" })
    }

    @Test fun `duplicate identities including disabled rows stay unavailable rather than choosing a role`() {
        for (enabled in listOf(true, false)) {
            val build = fixture("build")
            val selections = build["selections"] as ArrayNode
            val duplicate = selections.single { it["id"].asText() == "sel_model" }.deepCopy<ObjectNode>()
            selections.add(duplicate.put("role", "fallback").put("enabled", enabled))
            val accepted = receive(build)
            val context = accepted.inspect().findings.first().selectionContexts.single { it.selectionId == "sel_model" }
            assertEquals(ReferenceSelectionUnavailable.AMBIGUOUS, (context as ReferenceSelectionContext.Unavailable).reason)
            val missing = accepted.selectionContext("sel_absent") as ReferenceSelectionContext.Unavailable
            assertEquals("sel_absent", missing.selectionId)
            assertEquals(ReferenceSelectionUnavailable.MISSING, missing.reason)
        }
    }

    @Test fun `projection is immutable and excludes private build and selection payloads`() {
        val build = fixture("build").put("owner_ref", "private-owner-marker")
        build.putObject("extensions").put("x_private", "private-build-extension")
        val model = build["selections"].single { it["id"].asText() == "sel_model" } as ObjectNode
        model.put("notes", "private-selection-notes")
        (model["settings"] as ObjectNode).put("credential_ref", "secret://private-test-reference")
        model.putObject("extensions").put("x_private", "private-selection-extension")
        val accepted = receive(build)
        val first = accepted.inspect().findings.first()
        val context = first.selectionContexts.single { it.selectionId == "sel_model" } as ReferenceSelectionContext.Recorded
        model.put("role", "changed_after_acceptance").put("catalog_item_version", "changed")
        assertEquals("primary", context.role)
        assertEquals("2026.10-demo", context.catalogItemVersion)
        assertEquals("primary", (accepted.inspect().findings.first().selectionContexts
            .single { it.selectionId == "sel_model" } as ReferenceSelectionContext.Recorded).role)
        assertThrows(UnsupportedOperationException::class.java) { (first.selectionContexts as MutableList).clear() }
        val exported = mapper.valueToTree<JsonNode>(context).toString()
        for (marker in listOf("private-owner-marker", "private-build-extension", "private-selection-notes",
            "private-test-reference", "private-selection-extension")) assertFalse(exported.contains(marker))
        assertEquals(setOf("selectionId", "kind", "role", "catalogItemId", "catalogItemVersion", "enabled"),
            mapper.valueToTree<JsonNode>(context).fieldNames().asSequence().toSet())
        assertEquals("ReferenceSelectionContext.Recorded", context.toString())
    }
}
