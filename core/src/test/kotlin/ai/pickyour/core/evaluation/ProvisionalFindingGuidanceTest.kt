package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Consistent supplied reports test presentation admission; they do not attest that a rule ran. */
class ProvisionalFindingGuidanceTest {
    private val mapper = ObjectMapper()
    private val foundation = "rule.required.foundation"
    private val provider = "rule.model.provider.match"
    private val incomplete = ProvisionalFindingGuidance.INCOMPLETE
    private val incompatible = ProvisionalFindingGuidance.REPORTED_INCOMPATIBILITY
    private val unclassified = ProvisionalFindingGuidance.UNCLASSIFIED
    private fun fixture(name: String): ObjectNode = mapper.readTree(
        javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun primary(build: ObjectNode, kind: String) = build["selections"].single {
        it["kind"].asText() == kind && it["role"].asText() == "primary"
    } as ObjectNode
    private fun missing(vararg kinds: String) = fixture("build").also { build ->
        kinds.forEach { primary(build, it).put("enabled", false) }
    }
    private fun receive(
        build: ObjectNode = fixture("build"), rules: ObjectNode = fixture("ruleset"),
        outcomes: Map<String, String> = mapOf(foundation to "fail", provider to "fail"),
        rulesBytes: String = rules.toString(), message: String? = null,
    ): ReportReception.Accepted {
        val report = fixture("evaluation-report")
        val locks = build["locks"] as ObjectNode
        locks.put("ruleset_id", rules["id"].asText()).put("ruleset_version", rules["version"].asText())
        report.put("build_id", build["id"].asText()).put("build_revision", build["revision"].bigIntegerValue())
        report.put("ruleset_id", rules["id"].asText()).put("ruleset_version", rules["version"].asText())
        val findings = report["findings"] as ArrayNode
        val original = findings.map { it.deepCopy<ObjectNode>() }.associateBy { it["rule_id"].asText() }
        findings.removeAll()
        for (rule in rules["rules"]) {
            val id = rule["id"].asText()
            val finding = (original[id] ?: original.values.first()).deepCopy()
            finding.put("rule_id", id).put("outcome", outcomes[id] ?: "pass").put("severity", rule["severity"].asText())
            if (message != null) finding.put("message", message).put("code", "INCOMPLETE_COMPATIBILITY")
            val kinds = rule["affected_kinds"].map { it.asText() }.toSet()
            val selected = build["selections"].filter { it["enabled"].asBoolean() && it["kind"].asText() in kinds }
                .map { it["id"].asText() }.toSortedSet()
            finding.putArray("selection_ids").also { ids -> selected.forEach(ids::add) }
            findings.add(finding)
        }
        val summary = report["summary"] as ObjectNode
        for ((field, outcome) in mapOf("passed" to "pass", "failed" to "fail", "unknown" to "unknown", "skipped" to "skipped"))
            summary.put(field, findings.count { it["outcome"].asText() == outcome })
        val errors = findings.any { it["outcome"].asText() == "fail" && it["severity"].asText() == "error" }
        val unknown = findings.any { it["outcome"].asText() == "unknown" }
        val blocked = findings.zip(rules["rules"]).any { (finding, rule) ->
            finding["outcome"].asText() == "unknown" && rule["on_unknown"].asText() == "block"
        }
        report.put("result", if (errors) "invalid" else if (unknown) "indeterminate" else "valid")
        report.put("deployment_readiness", if (errors || blocked ||
            (unknown && build.path("policy").path("fail_on_unknown").asBoolean(false))) "blocked" else "needs_review")
        val beforeBuild = build.toString(); val beforeRules = rules.toString(); val beforeReport = report.toString()
        val received = ReferenceReportAdapter().receive(beforeBuild.byteInputStream(), rulesBytes.byteInputStream(), beforeReport.byteInputStream())
        assertTrue(received is ReportReception.Accepted, received.toString())
        val accepted = received as ReportReception.Accepted
        assertEquals(beforeBuild, build.toString()); assertEquals(beforeRules, rules.toString()); assertEquals(beforeReport, report.toString())
        assertEquals(beforeReport, accepted.json)
        return accepted
    }
    private fun ReportReception.Accepted.guidance(id: String) = inspect().findings.single { it.ruleId == id }.provisionalGuidance
    private fun allFail(rules: ObjectNode = fixture("ruleset")) = rules["rules"].associate { it["id"].asText() to "fail" }

    @Test fun `both exact profiles recognize the same failed presence and fit contexts`() {
        for (id in listOf("rules.fixture.1", "rules.core.1")) {
            val rules = fixture("ruleset").put("id", id)
            val received = receive(missing("workload"), rules)
            assertEquals(incomplete, received.guidance(foundation), id)
            assertEquals(incompatible, received.guidance(provider), id)
            assertEquals("invalid", received.inspect().result)
            assertEquals("blocked", received.inspect().deploymentReadiness)
        }
    }

    @Test fun `pass unknown and skipped never acquire either provisional label`() {
        for (outcome in listOf("pass", "unknown", "skipped")) {
            val received = receive(missing("workload"), outcomes = mapOf(foundation to outcome, provider to outcome))
            assertEquals(unclassified, received.guidance(foundation), outcome)
            assertEquals(unclassified, received.guidance(provider), outcome)
            assertEquals(outcome, received.inspect().findings.single { it.ruleId == foundation }.outcome)
            assertEquals("error", received.inspect().findings.single { it.ruleId == foundation }.severity)
        }
    }

    @Test fun `all required conjunction rows need an actual failure and a missing primary choice`() {
        // Independently read the fixed source's positive conjunctions; production never generalizes this traversal.
        fun required(node: JsonNode): Set<String> = when (node.path("op").asText()) {
            "all" -> node["args"].flatMap { required(it) }.toSet()
            "exists" -> node.path("value").path("ref").let { ref ->
                if (ref.path("scope").asText() == "selection" && ref.path("role").asText() == "primary" &&
                    ref.path("pointer").asText() == "/id") setOf(ref["kind"].asText()) else emptySet()
            }
            else -> emptySet()
        }
        val rows = fixture("ruleset")["rules"].mapNotNull { rule ->
            required(rule["assert"]).takeIf { it.isNotEmpty() }?.let { rule["id"].asText() to it }
        }
        assertEquals(12, rows.size)
        for ((id, choices) in rows) for (choice in choices) {
            assertEquals(incomplete, receive(missing(choice), outcomes = mapOf(id to "fail")).guidance(id), "$id/$choice")
            assertEquals(unclassified, receive(missing(choice), outcomes = mapOf(id to "pass")).guidance(id), "$id/$choice/pass")
        }
    }

    @Test fun `present choice failures use only the finite fit inventory and leave other rules unclassified`() {
        val received = receive(outcomes = allFail())
        val expected = setOf(
            "rule.model.provider.match", "rule.model.output.limit", "rule.selfhost.requires.weights", "rule.engine.model.format",
            "rule.engine.accelerator.backend", "rule.precision.engine", "rule.precision.accelerator", "rule.api.model.access",
            "rule.embedding.dimension", "rule.vector.dimension.support", "rule.vector.distance.support", "rule.embedding.distance.support",
            "rule.agent.tools.framework", "rule.agent.mcp.framework", "rule.tool.mutation.guardrails", "rule.training.model.capable",
            "rule.training.base.lineage", "rule.training.method.support", "rule.training.backend.support", "rule.compute.sku.accelerator",
            "rule.compute.sku.host", "rule.compute.accelerator.count", "rule.host.accelerator.catalog", "rule.tensor.parallel.devices",
            "rule.model.serving.mode.selfhost", "rule.memory.framework.compatibility", "rule.training.artifact.engine",
            "rule.tensor.parallel.engine.support",
        )
        assertEquals(28, expected.size)
        assertEquals(expected, received.inspect().findings.filter { it.provisionalGuidance == incompatible }.map { it.ruleId }.toSet())
        assertTrue(received.inspect().findings.all { it.outcome == "fail" && it.provisionalGuidance != incomplete })
    }

    @Test fun `overlapping required and fit rows distinguish absence from a reported conflict`() {
        val id = "rule.agent.tools.framework"
        assertEquals(incomplete, receive(missing("agent_framework"), outcomes = mapOf(id to "fail")).guidance(id))
        assertEquals(incompatible, receive(outcomes = mapOf(id to "fail")).guidance(id))
        assertEquals(unclassified, receive(missing("tool"), outcomes = mapOf(id to "fail")).guidance(id))
        assertEquals(unclassified, receive(missing("agent_framework"), outcomes = mapOf(id to "skipped")).guidance(id))
    }

    @Test fun `object key order is irrelevant while rules and expression array order stay pinned`() {
        fun reverseKeys(node: JsonNode): JsonNode = when {
            node.isObject -> JsonNodeFactory.instance.objectNode().also { out ->
                node.properties().toList().reversed().forEach { (key, child) -> out.set<JsonNode>(key, reverseKeys(child)) }
            }
            node.isArray -> JsonNodeFactory.instance.arrayNode().also { out -> node.forEach { out.add(reverseKeys(it)) } }
            else -> node.deepCopy()
        }
        val reversed = reverseKeys(fixture("ruleset")) as ObjectNode
        assertEquals(incomplete, receive(missing("workload"), reversed).guidance(foundation))
        val rules = fixture("ruleset")
        val args = rules["rules"][0]["assert"]["args"] as ArrayNode
        args.add(args.remove(0))
        assertEquals(unclassified, receive(missing("workload"), rules).guidance(foundation))
        val reordered = fixture("ruleset")
        val rows = reordered["rules"] as ArrayNode
        rows.add(rows.remove(0))
        assertEquals(unclassified, receive(missing("workload"), reordered).guidance(foundation))
    }

    @Test fun `unrecognized identity and changed metadata cannot borrow the registered rules`() {
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.put("id", "rules.unregistered.1") }, { it.put("version", "1.0.1") },
            { it.put("published_at", "2026-10-17T09:00:00Z") },
            { (it["rules"][0] as ObjectNode).put("message", "Required components are missing") },
            { (it["rules"][20] as ObjectNode).put("remediation", "Changed unrelated rule") },
            { (it["rules"][0] as ObjectNode).put("severity", "warning") },
        )) {
            val rules = fixture("ruleset").also(change)
            val received = receive(missing("workload"), rules, message = "Incomplete; incompatible; choose a provider")
            assertTrue(received.inspect().findings.all { it.provisionalGuidance == unclassified })
            assertEquals("fail", received.inspect().findings.single { it.ruleId == foundation }.outcome)
        }
    }

    @Test fun `raw floating types cannot impersonate integral pinned operands`() {
        val rules = fixture("ruleset")
        val original = rules.toString()
        assertTrue(original.contains("\"literal\":1}"))
        for (token in listOf("1.0", "1e0", "1.000")) {
            val changed = original.replaceFirst("\"literal\":1}", "\"literal\":$token}")
            assertNotEquals(original, changed)
            assertEquals(unclassified, receive(missing("workload"), rules, rulesBytes = changed).guidance(foundation), token)
        }
        assertEquals(incomplete, receive(missing("workload"), rules).guidance(foundation))
    }

    @Test fun `duplicate IDs including disabled rows withhold every guidance facet`() {
        for (enabled in listOf(true, false)) {
            val build = missing("workload")
            (build["selections"] as ArrayNode).add(primary(build, "storage").deepCopy().put("role", "fallback").put("enabled", enabled))
            assertTrue(receive(build).inspect().findings.all { it.provisionalGuidance == unclassified })
        }
    }

    @Test fun `ambiguous referenced roles are withheld while an independent missing choice remains visible`() {
        val build = missing("workload")
        (build["selections"] as ArrayNode).add(primary(build, "model_provider").deepCopy().put("id", "sel_provider_second"))
        val received = receive(build)
        assertEquals(unclassified, received.guidance(provider))
        assertEquals(incomplete, received.guidance(foundation))
        (build["selections"] as ArrayNode).last().let { (it as ObjectNode).put("enabled", false) }
        assertEquals(incompatible, receive(build).guidance(provider))
    }

    @Test fun `fallback and disabled selections never substitute for the required primary`() {
        val build = missing("workload")
        (build["selections"] as ArrayNode).add(primary(build, "workload").deepCopy()
            .put("id", "sel_workload_fallback").put("role", "fallback").put("enabled", true))
        assertEquals(incomplete, receive(build).guidance(foundation))
        assertEquals(unclassified, receive(missing("model_provider")).guidance(provider))
    }

    @Test fun `captured guidance stays with its admitted Build and exposes no private source fields`() {
        val build = missing("workload").put("owner_ref", "private-guidance-owner")
        primary(build, "model").put("notes", "private-guidance-notes")
        val accepted = receive(build)
        val before = accepted.json
        primary(build, "workload").put("enabled", true)
        assertEquals(incomplete, accepted.guidance(foundation))
        assertEquals(unclassified, receive(build).guidance(foundation))
        assertEquals(incomplete, accepted.guidance(foundation))
        assertEquals(before, accepted.json)
        val exported = mapper.valueToTree<JsonNode>(accepted.inspect()).toString()
        assertFalse(exported.contains("private-guidance-owner")); assertFalse(exported.contains("private-guidance-notes"))
        assertFalse(mapper.readTree(before)["findings"].any { it.has("provisionalGuidance") || it.has("guidance") })
    }
}
