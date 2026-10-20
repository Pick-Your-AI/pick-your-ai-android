package ai.pickyour.core.evaluation

import ai.pickyour.core.drafts.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Real private stores and current evaluator results; no supplied report or outcome override. */
class DraftCurrentProvisionalGuidanceTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private val foundation = "rule.required.foundation"
    private val foundationKinds = listOf("workload", "deployment_mode", "model", "context_policy")
    private val provider = "rule.model.provider.match"
    private val incomplete = ProvisionalFindingGuidance.INCOMPLETE
    private val incompatible = ProvisionalFindingGuidance.REPORTED_INCOMPATIBILITY
    private val unclassified = ProvisionalFindingGuidance.UNCLASSIFIED
    private fun fixture(name: String) = mapper.readTree(
        javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun bytes(node: ObjectNode) = node.toString().toByteArray(Charsets.UTF_8)
    private fun inputs(catalog: ObjectNode = fixture("catalog"), rules: ObjectNode = fixture("ruleset")) =
        requireNotNull(DraftCurrentInputs.read(bytes(catalog), bytes(fixture("dependency-graph")), bytes(rules)))
    private fun rows(vararg kinds: String): List<LocalSelection> = fixture("build")["selections"]
        .filter { it["kind"].asText() in kinds }.map { LocalSelection.fromJson(it as ObjectNode) }
    private fun saved(content: DraftContent = DraftContent()): Pair<LocalDraftStore, StoredDraft> {
        val store = LocalDraftStore(Files.createDirectory(root.resolve("draft-${serial++}")))
        return store to (store.save(null, content, mapOf("note" to "private guidance history")) as DraftSave.Saved).draft
    }
    private fun saved(rows: List<LocalSelection>) = saved(DraftContent(components = DraftSelections(rows)))
    private fun request(source: StoredDraft, pins: DraftCurrentInputs, previous: StoredDraft? = null) =
        DraftCurrentEvaluation(source, pins, DraftCurrentEvaluation.plan(previous, pins, source, pins))
    private fun files() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to Files.readAllBytes(it).toList()
    } }
    private fun completed(request: DraftCurrentEvaluation): DraftCurrentOutcome.Completed {
        val before = files()
        val result = DraftCurrentEvaluator().evaluate(request)
        assertTrue(result is DraftCurrentOutcome.Completed, (result as? DraftCurrentOutcome.Unavailable)?.reason.toString())
        result as DraftCurrentOutcome.Completed
        assertTrue(result.belongsTo(request))
        assertEquals(request.binding, result.binding)
        assertEquals(request.source.version.hash, result.binding.sourceHash)
        assertEquals(request.inputs.rulesetHash, result.binding.rulesetHash)
        assertEquals(45, result.findings.size)
        assertEquals(45, result.findings.map { it.ruleId }.toSet().size)
        assertEquals(before, files())
        return result
    }
    private fun row(outcome: DraftCurrentOutcome.Completed, id: String) = outcome.findings.single { it.ruleId == id }
    private fun values(findings: List<DraftRuleFinding>) = findings.map {
        listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds)
    }
    private fun conflictingProvider(catalog: ObjectNode): LocalSelection {
        val original = catalog["items"].single { it["id"].asText() == "cat.model_provider.fixture" } as ObjectNode
        (catalog["items"] as ArrayNode).add(original.deepCopy().put("id", "cat.model_provider.other_fixture"))
        return LocalSelection.fromJson(rows("model_provider").single().toJson().put("id", "sel_provider_other")
            .put("catalog_item_id", "cat.model_provider.other_fixture"))
    }

    @Test fun bothRegisteredProfilesUseRealEmptyCurrentFailureWithoutChangingFindings() {
        val profiles = listOf(fixture("ruleset"), mapper.readTree(
            javaClass.getResourceAsStream("/contracts/v1/baseline/core-ruleset.json")) as ObjectNode)
        assertEquals(setOf("rules.fixture.1", "rules.core.1"), profiles.map { it["id"].asText() }.toSet())
        for (rules in profiles) {
            val pins = inputs(rules = rules)
            val (_, source) = saved()
            val request = request(source, pins)
            val actual = completed(request)
            val direct = FrozenDiagnosticEvaluator().evaluateDraft(
                DraftDiagnosticContext.create(source.content.components, source.content.workload),
                pins.catalogBytes(), pins.rulesBytes()) as DraftRuleEvaluation.Evaluated
            assertEquals(values(direct.findings), values(actual.findings))
            assertEquals("fail", row(actual, foundation).outcome)
            assertEquals("error", row(actual, foundation).severity)
            assertTrue(row(actual, foundation).selectionIds.isEmpty())
            assertEquals(incomplete, actual.provisionalGuidance(foundation))
            assertEquals(foundationKinds, actual.missingRequiredPrimaryKinds(foundation))
            assertEquals(unclassified, actual.provisionalGuidance("rule.unregistered"))
            assertTrue(actual.missingRequiredPrimaryKinds("rule.unregistered").isEmpty())
        }
    }

    @Test fun realProviderConflictAndMissingFoundationKeepIndependentGuidance() {
        val catalog = fixture("catalog")
        val other = conflictingProvider(catalog)
        val model = rows("model").single()
        val (_, source) = saved(listOf(model, other))
        val actual = completed(request(source, inputs(catalog)))
        assertEquals("2026.10-demo", other.catalogItemVersion)
        assertEquals("fail", row(actual, provider).outcome)
        assertEquals("fail", row(actual, foundation).outcome)
        assertEquals(listOf(model.id, other.id).sorted(), row(actual, provider).selectionIds)
        assertEquals(listOf(model.id), row(actual, foundation).selectionIds)
        assertEquals(incompatible, actual.provisionalGuidance(provider))
        assertTrue(actual.missingRequiredPrimaryKinds(provider).isEmpty())
        assertEquals(incomplete, actual.provisionalGuidance(foundation))
        assertEquals(listOf("workload", "deployment_mode", "context_policy"), actual.missingRequiredPrimaryKinds(foundation))
        assertEquals("Selected model belongs to a different provider.", row(actual, provider).message)
    }

    @Test fun realPassUnknownAndSkippedNeverAcquireProvisionalGuidance() {
        for ((selected, expected) in listOf(rows("model", "model_provider") to "pass",
            rows("model") to "unknown", emptyList<LocalSelection>() to "skipped")) {
            val (_, source) = saved(selected)
            val actual = completed(request(source, inputs()))
            assertEquals(expected, row(actual, provider).outcome)
            assertEquals("error", row(actual, provider).severity)
            assertEquals(unclassified, actual.provisionalGuidance(provider))
            if (expected == "unknown") assertEquals(DraftCurrentUnknownPhase.ASSERTION, actual.unknownPhase(provider))
            assertTrue(actual.missingRequiredPrimaryKinds(provider).isEmpty())
            for (finding in actual.findings.filter { it.outcome != "fail" }) {
                assertEquals(unclassified, actual.provisionalGuidance(finding.ruleId), finding.ruleId)
                assertTrue(actual.missingRequiredPrimaryKinds(finding.ruleId).isEmpty(), finding.ruleId)
            }
        }
    }

    @Test fun disabledPrimaryAndEnabledFallbackRemainMissingRequiredPresence() {
        val primary = rows("model").single()
        val disabled = LocalSelection.fromJson(primary.toJson().put("enabled", false))
        val fallback = LocalSelection.fromJson(primary.toJson().put("id", "sel_model_fallback").put("role", "fallback"))
        val (_, source) = saved(listOf(disabled, fallback))
        val actual = completed(request(source, inputs()))
        assertEquals("fail", row(actual, foundation).outcome)
        assertEquals(listOf(fallback.id), row(actual, foundation).selectionIds)
        assertEquals(incomplete, actual.provisionalGuidance(foundation))
        assertEquals(foundationKinds, actual.missingRequiredPrimaryKinds(foundation))
        assertFalse(source.content.components.selections.single { it.id == primary.id }.enabled)
        assertEquals("fallback", source.content.components.selections.single { it.id == fallback.id }.role)
    }

    @Test fun optionalRequiredGuidanceAppearsOnlyForAnActuallyFailedActiveRule() {
        val pins = inputs()
        val (_, empty) = saved()
        val inactive = completed(request(empty, pins))
        assertEquals(Activation.DISABLED, empty.content.optional.getValue(OptionalTier.T3).activation)
        assertEquals("skipped", row(inactive, "rule.rag.knowledge").outcome)
        assertEquals(unclassified, inactive.provisionalGuidance("rule.rag.knowledge"))
        assertTrue(inactive.missingRequiredPrimaryKinds("rule.rag.knowledge").isEmpty())
        val (_, active) = saved(DraftContent(optional = mapOf(OptionalTier.T3 to TierDraft(Activation.ENABLED)),
            components = DraftSelections(rows("rag_pipeline"))))
        val actual = completed(request(active, pins))
        assertEquals("fail", row(actual, "rule.rag.knowledge").outcome)
        assertEquals(listOf("sel_rag_pipeline"), row(actual, "rule.rag.knowledge").selectionIds)
        assertEquals("RAG pipeline lacks a knowledge base.", row(actual, "rule.rag.knowledge").message)
        assertEquals(incomplete, actual.provisionalGuidance("rule.rag.knowledge"))
        assertEquals(listOf("knowledge_base"), actual.missingRequiredPrimaryKinds("rule.rag.knowledge"))
        assertEquals(unclassified, inactive.provisionalGuidance("rule.rag.knowledge"))
        assertTrue(inactive.missingRequiredPrimaryKinds("rule.rag.knowledge").isEmpty())
    }

    @Test fun changedRulesetIdentityMetadataAndOrderRefuseAllCurrentFacets() {
        val mutations = listOf<(ObjectNode) -> Unit>(
            { it.put("id", "rules.unregistered.1") },
            { it.put("version", "1.0.1") },
            { it.put("published_at", "2026-10-17T09:00:00Z") },
            { (it["rules"][0] as ObjectNode).put("message", "Incomplete; incompatible; ready; pass") },
            { (it["rules"][20] as ObjectNode).put("remediation", "Changed unrelated guidance") },
            { val rules = it["rules"] as ArrayNode; rules.add(rules.remove(0)) },
        )
        for (change in mutations) {
            val rules = fixture("ruleset").also(change)
            val (_, source) = saved()
            val actual = completed(request(source, inputs(rules = rules)))
            assertEquals("fail", row(actual, foundation).outcome)
            assertEquals(rules["id"].asText(), actual.binding.rulesetId)
            for (finding in actual.findings) {
                assertEquals(unclassified, actual.provisionalGuidance(finding.ruleId))
                assertTrue(actual.missingRequiredPrimaryKinds(finding.ruleId).isEmpty())
            }
        }
    }

    @Test fun objectKeyOrderDoesNotChangeTheRegisteredCurrentProfile() {
        fun reverseKeys(node: JsonNode): JsonNode = when {
            node.isObject -> JsonNodeFactory.instance.objectNode().also { result ->
                node.properties().toList().reversed().forEach { (key, value) -> result.set<JsonNode>(key, reverseKeys(value)) }
            }
            node.isArray -> JsonNodeFactory.instance.arrayNode().also { result -> node.forEach { result.add(reverseKeys(it)) } }
            else -> node.deepCopy()
        }
        val original = inputs()
        val reordered = inputs(rules = reverseKeys(fixture("ruleset")) as ObjectNode)
        assertNotEquals(original.rulesetHash, reordered.rulesetHash)
        val (_, source) = saved()
        val before = completed(request(source, original))
        val after = completed(request(source, reordered))
        assertEquals(values(before.findings), values(after.findings))
        assertEquals(incomplete, after.provisionalGuidance(foundation))
        assertEquals(incomplete, before.provisionalGuidance(foundation))
        assertEquals(foundationKinds, after.missingRequiredPrimaryKinds(foundation))
        assertEquals(foundationKinds, before.missingRequiredPrimaryKinds(foundation))
    }

    @Test fun callerMutationCannotChangeCapturedRulesFindingsOrGuidance() {
        val rules = fixture("ruleset")
        val c = bytes(fixture("catalog")); val g = bytes(fixture("dependency-graph")); val r = bytes(rules)
        val pins = requireNotNull(DraftCurrentInputs.read(c, g, r))
        val (_, source) = saved()
        val request = request(source, pins)
        val captured = completed(request)
        val before = values(captured.findings)
        val binding = captured.binding.copy()
        c.fill(0); g.fill(0); r.fill(0)
        pins.catalogBytes().fill(0); pins.rulesBytes().fill(0)
        rules.put("id", "rules.unregistered.1")
        (rules["rules"][0] as ObjectNode).put("message", "private replacement")
        val again = completed(request)
        assertEquals(binding, again.binding)
        assertEquals(before, values(again.findings))
        assertEquals(before, values(captured.findings))
        assertEquals(incomplete, again.provisionalGuidance(foundation))
        assertEquals(incomplete, captured.provisionalGuidance(foundation))
        assertEquals(foundationKinds, again.missingRequiredPrimaryKinds(foundation))
        assertEquals(foundationKinds, captured.missingRequiredPrimaryKinds(foundation))
        assertThrows(UnsupportedOperationException::class.java) {
            (captured.missingRequiredPrimaryKinds(foundation) as MutableList<String>).clear()
        }
        assertEquals(foundationKinds, captured.missingRequiredPrimaryKinds(foundation))
        assertThrows(UnsupportedOperationException::class.java) { (captured.findings as MutableList).clear() }
        assertFalse(captured.findings.any { "private" in it.message })
    }

    @Test fun removedAndDisabledPredecessorIdsCannotSupplyCurrentPresence() {
        val pins = inputs()
        val (store, old) = saved(rows("workload", "deployment_mode", "model", "context_policy"))
        val prior = completed(request(old, pins))
        assertEquals("pass", row(prior, foundation).outcome)
        assertEquals(unclassified, prior.provisionalGuidance(foundation))
        assertTrue(prior.missingRequiredPrimaryKinds(foundation).isEmpty())
        val removed = (store.applySelection(DraftSelectionIntent(old.version,
            SelectionCommand.Remove("sel_model"))) as DraftSelectionSave.Saved).draft
        val removedRequest = request(removed, pins, old)
        assertTrue("sel_model" in removedRequest.recheck.selectionIds,
            "Transition IDs retain the predecessor only for recheck explanation")
        assertFalse(removed.content.components.selections.any { it.id == "sel_model" })
        val absent = completed(removedRequest)
        assertEquals("fail", row(absent, foundation).outcome)
        assertEquals(incomplete, absent.provisionalGuidance(foundation))
        assertEquals(listOf("model"), absent.missingRequiredPrimaryKinds(foundation))
        val disabledRows = old.content.components.selections.map { if (it.id == "sel_model")
            LocalSelection.fromJson(it.toJson().put("enabled", false)) else it }
        val disabled = (store.save(removed.version, removed.content.withComponents(DraftSelections(disabledRows)),
            removed.metadata) as DraftSave.Saved).draft
        val disabledRequest = request(disabled, pins, removed)
        assertTrue("sel_model" in disabledRequest.recheck.selectionIds)
        val inactive = completed(disabledRequest)
        assertEquals("fail", row(inactive, foundation).outcome)
        assertFalse("sel_model" in row(inactive, foundation).selectionIds)
        assertEquals(incomplete, inactive.provisionalGuidance(foundation))
        assertEquals(unclassified, prior.provisionalGuidance(foundation))
        assertEquals(incomplete, absent.provisionalGuidance(foundation))
        assertEquals(listOf("model"), inactive.missingRequiredPrimaryKinds(foundation))
        assertEquals(listOf("model"), absent.missingRequiredPrimaryKinds(foundation))
        assertTrue(prior.missingRequiredPrimaryKinds(foundation).isEmpty())
        assertNotEquals(prior.binding.sourceHash, inactive.binding.sourceHash)
    }

    @Test fun unavailableCurrentAttemptsDoNotProduceGuidanceBearingOutcomes() {
        val changed = fixture("catalog")
        (changed["items"].single { it["kind"].asText() == "model" } as ObjectNode).put("version", "unmatched")
        val (_, mismatched) = saved(rows("model"))
        val (_, unresolved) = saved(DraftContent(optional = mapOf(OptionalTier.T3 to TierDraft(Activation.UNKNOWN))))
        for ((request, reason) in listOf(request(mismatched, inputs(changed)) to DraftCurrentUnavailable.BINDING_MISMATCH,
            request(unresolved, inputs()) to DraftCurrentUnavailable.OPTIONAL_INTENT_UNRESOLVED)) {
            val before = files()
            val result = DraftCurrentEvaluator().evaluate(request)
            assertTrue(result is DraftCurrentOutcome.Unavailable)
            assertEquals(reason, (result as DraftCurrentOutcome.Unavailable).reason)
            assertTrue(result.belongsTo(request))
            assertEquals(request.binding, result.binding)
            assertEquals(before, files())
        }
    }
}
