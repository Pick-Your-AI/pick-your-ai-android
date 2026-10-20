package ai.pickyour.core.evaluation

import ai.pickyour.core.drafts.*
import ai.pickyour.core.invalidation.DeploymentMode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Real private stores and explicit synthetic frozen inputs; no fabricated Build/report authority. */
class DraftCurrentEvaluationTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun bytes(node: ObjectNode) = node.toString().toByteArray()
    private fun inputs(catalog: ObjectNode = fixture("catalog"), graph: ObjectNode = fixture("dependency-graph"), rules: ObjectNode = fixture("ruleset")) =
        requireNotNull(DraftCurrentInputs.read(bytes(catalog), bytes(graph), bytes(rules)))
    private fun rows(vararg kinds: String): List<LocalSelection> = fixture("build")["selections"].filter { it["kind"].asText() in kinds }
        .map { LocalSelection.fromJson(it as ObjectNode) }
    private fun content(rows: List<LocalSelection>, enabledTiers: Set<OptionalTier> = emptySet()) = DraftContent(
        optional = enabledTiers.associateWith { TierDraft(Activation.ENABLED) }, components = DraftSelections(rows))
    private fun saved(content: DraftContent): Pair<LocalDraftStore, StoredDraft> {
        val store = LocalDraftStore(Files.createDirectory(root.resolve("draft-${serial++}")))
        return store to (store.save(null, content, mapOf("note" to "private synthetic history")) as DraftSave.Saved).draft
    }
    private fun edit(store: LocalDraftStore, old: StoredDraft, content: DraftContent) =
        (store.save(old.version, content, old.metadata) as DraftSave.Saved).draft
    private fun request(source: StoredDraft, inputs: DraftCurrentInputs, old: StoredDraft? = null, oldInputs: DraftCurrentInputs? = inputs) =
        DraftCurrentEvaluation(source, inputs, DraftCurrentEvaluation.plan(old, oldInputs, source, inputs))
    private fun completed(request: DraftCurrentEvaluation): DraftCurrentOutcome.Completed {
        val result = DraftCurrentEvaluator().evaluate(request)
        assertTrue(result is DraftCurrentOutcome.Completed, (result as? DraftCurrentOutcome.Unavailable)?.reason.toString())
        return result as DraftCurrentOutcome.Completed
    }
    private fun files() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to Files.readAllBytes(it).toList()
    } }
    private fun item(catalog: ObjectNode, kind: String) = catalog["items"].single { it["kind"].asText() == kind } as ObjectNode
    private fun graphOnly(vararg ids: String) = fixture("dependency-graph").also { graph ->
        val selected = graph["edges"].filter { it["id"].asText() in ids }
        graph.putArray("edges").addAll(selected)
    }

    @Test fun wholeCurrentContextUsesExistingRulesAndKeepsUnrepresentedPrivateFields() {
        val pins = inputs()
        val (_, source) = saved(DraftContent(objective = "private objective", context = "private context",
            components = DraftSelections(rows("workload", "deployment_mode", "model_provider", "model", "context_policy"))))
        val disk = files(); val request = request(source, pins); val actual = completed(request)
        val expected = FrozenDiagnosticEvaluator().evaluateDraft(DraftDiagnosticContext.create(source.content.components, source.content.workload),
            pins.catalogBytes(), pins.rulesBytes()) as DraftRuleEvaluation.Evaluated
        assertEquals(expected.findings.map { listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds) },
            actual.findings.map { listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds) })
        assertEquals(setOf("pass", "fail", "unknown", "skipped"), actual.findings.map { it.outcome }.toSet())
        assertTrue(request.unrepresentedFieldGroups.containsAll(listOf("objective", "context", "optionalTierIntent")))
        assertFalse(actual.findings.any { it.message.contains("private objective") || it.message.contains("private context") })
        assertEquals(source.version.hash, actual.binding.sourceHash)
        assertEquals(disk, files())
    }

    @Test fun callerByteMutationCannotChangeCapturedPinsGraphOrLaterEvaluation() {
        val c = bytes(fixture("catalog")); val g = bytes(fixture("dependency-graph")); val r = bytes(fixture("ruleset"))
        val pins = requireNotNull(DraftCurrentInputs.read(c, g, r))
        val (_, source) = saved(DraftContent()); val request = request(source, pins)
        val before = completed(request); val edges = pins.graph.edges.map { it.id to it.dependentKind }
        c.fill(0); g.fill(0); r.fill(0)
        assertEquals(before.binding, completed(request).binding)
        assertEquals(before.findings.map { it.outcome }, completed(request).findings.map { it.outcome })
        assertEquals(edges, pins.graph.edges.map { it.id to it.dependentKind })
        assertThrows(UnsupportedOperationException::class.java) { (before.findings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (before.findings.first().selectionIds as MutableList).clear() }
    }

    @Test fun malformedOversizedDuplicateAndUnsupportedInputsNeverCreateAPin() {
        val c = bytes(fixture("catalog")); val g = bytes(fixture("dependency-graph")); val r = bytes(fixture("ruleset"))
        assertNull(DraftCurrentInputs.read(c, ByteArray(DraftCurrentInputs.MAX_BYTES + 1), r))
        assertNull(DraftCurrentInputs.read(c, byteArrayOf(0xc3.toByte(), 0x28), r))
        assertNull(DraftCurrentInputs.read(c, "{\"id\":1,\"id\":2}".toByteArray(), r))
        assertNull(DraftCurrentInputs.read(c, bytes(fixture("dependency-graph").put("schema_version", "99.0.0")), r))
        val duplicate = fixture("catalog"); (duplicate["items"] as ArrayNode).add(duplicate["items"][0].deepCopy<JsonNode>())
        assertNull(DraftCurrentInputs.read(bytes(duplicate), g, r))
        val rules = fixture("ruleset"); (rules["rules"] as ArrayNode).add(rules["rules"][0].deepCopy<JsonNode>())
        assertNull(DraftCurrentInputs.read(c, g, bytes(rules)))
    }

    @Test fun canonicalModelEditInvalidatesForwardAndInverseIdentitiesWithoutEditingHistory() {
        val pins = inputs(); val (store, old) = saved(content(rows("model", "accelerator", "context_policy", "deployment_mode", "compute_profile")))
        val model = old.content.components.selections.single { it.kind == "model" }
        val settings = model.settings.put("temperature", 0.8)
        val result = store.applySelection(DraftSelectionIntent(old.version, SelectionCommand.Configure(model.id, settings))) as DraftSelectionSave.Saved
        val next = request(result.draft, pins, old)
        assertEquals(DraftRecheckScope.DIRECT_DEPENDENCIES, next.recheck.scope)
        assertEquals(listOf("model"), next.recheck.changedKinds)
        assertTrue(next.recheck.selectionIds.containsAll(listOf("sel_model", "sel_accelerator", "sel_context_policy", "sel_compute_profile")))
        assertTrue(next.recheck.edgeIds.containsAll(listOf("dep.accelerator.model", "dep.context_policy.model")))
        assertEquals(0.4, old.content.components.selections.single { it.id == model.id }.settings["temperature"].doubleValue())
        val disk = files(); completed(next); assertEquals(disk, files())
    }

    @Test fun modeOnlyReplacementRechecksRemoteAcceleratorModelEdge() {
        val catalog = fixture("catalog")
        (item(catalog, "deployment_mode")["spec"] as ObjectNode).put("mode", "api")
        val self = item(catalog, "deployment_mode").deepCopy().put("id", "cat.mode.self")
        (self["spec"] as ObjectNode).put("mode", "self_hosted"); (catalog["items"] as ArrayNode).add(self)
        val pins = inputs(catalog, graphOnly("dep.accelerator.model"))
        val (store, old) = saved(content(rows("deployment_mode", "model", "accelerator", "workload")))
        val mode = old.content.components.selections.single { it.kind == "deployment_mode" }
        val snapshot = SelectionCatalogSnapshot.fromJson(bytes(catalog))
        val edit = store.applySelection(DraftSelectionIntent(old.version, SelectionCommand.Replace(mode.id,
            CatalogSelectionChoice(snapshot.hash, mode.kind, self["id"].asText(), self["version"].asText()))), snapshot) as DraftSelectionSave.Saved
        val next = request(edit.draft, pins, old)
        assertEquals(listOf("deployment_mode"), next.recheck.changedKinds)
        assertEquals(listOf("sel_accelerator", "sel_deployment_mode", "sel_model"), next.recheck.selectionIds)
        assertEquals(listOf("dep.accelerator.model"), next.recheck.edgeIds)
        assertEquals(DeploymentMode.API, pins.context(old).mode)
        assertEquals(DeploymentMode.SELF_HOSTED, pins.context(edit.draft).mode)
    }

    @Test fun optionalIntentDisableAndUnknownRetainPriorIdsAndWithholdContradictoryObservations() {
        val pins = inputs(); val (store, old) = saved(content(rows("rag_pipeline", "model", "vector_database"), setOf(OptionalTier.T3)))
        for (activation in listOf(Activation.DISABLED, Activation.UNKNOWN)) {
            val latest = (store.load() as DraftLoad.Available).draft
            val next = edit(store, latest, latest.content.withTier(OptionalTier.T3, latest.content.optional.getValue(OptionalTier.T3).withActivation(activation)))
            assertEquals(latest.content.components, next.content.components)
            val request = request(next, pins, latest)
            assertTrue(request.recheck.changedKinds.containsAll(listOf("rag_pipeline", "vector_database", "data_source")))
            assertTrue(request.recheck.selectionIds.containsAll(listOf("sel_model", "sel_rag_pipeline", "sel_vector_database")))
            assertEquals(DraftRecheckScope.CONSERVATIVE_FULL, request.recheck.scope)
            assertEquals(DraftCurrentUnavailable.OPTIONAL_INTENT_UNRESOLVED,
                (DraftCurrentEvaluator().evaluate(request) as DraftCurrentOutcome.Unavailable).reason)
        }
        assertEquals(Activation.ENABLED, old.content.optional.getValue(OptionalTier.T3).activation)
    }

    @Test fun removedAndDisabledSelectionsRemainInTheAcknowledgedTransition() {
        val pins = inputs(); val (store, old) = saved(content(rows("model", "accelerator", "deployment_mode")))
        val disabled = store.applySelection(DraftSelectionIntent(old.version, SelectionCommand.SetEnabled("sel_accelerator", false))) as DraftSelectionSave.Saved
        val removed = store.applySelection(DraftSelectionIntent(disabled.draft.version, SelectionCommand.Remove("sel_model"))) as DraftSelectionSave.Saved
        assertTrue(request(disabled.draft, pins, old).recheck.selectionIds.contains("sel_accelerator"))
        val plan = request(removed.draft, pins, disabled.draft).recheck
        assertTrue(plan.selectionIds.containsAll(listOf("sel_model", "sel_accelerator")))
        assertFalse(removed.draft.content.components.selections.any { it.id == "sel_model" })
        assertEquals(3, old.content.components.selections.size)
    }

    @Test fun exactCatalogContextUnknownAndProductionChangesStayConservative() {
        val pins = inputs(); val (store, old) = saved(content(rows("model", "accelerator", "deployment_mode", "deployment_target")))
        val target = old.content.components.selections.single { it.kind == "deployment_target" }
        val changed = store.applySelection(DraftSelectionIntent(old.version,
            SelectionCommand.Configure(target.id, target.settings.put("environment", "development")))) as DraftSelectionSave.Saved
        assertEquals(true, pins.context(old).production); assertEquals(false, pins.context(changed.draft).production)
        val withoutMode = store.applySelection(DraftSelectionIntent(changed.draft.version, SelectionCommand.Remove("sel_deployment_mode"))) as DraftSelectionSave.Saved
        assertNull(pins.context(withoutMode.draft).mode)
        assertTrue(request(withoutMode.draft, pins, changed.draft).recheck.selectionIds.contains("sel_accelerator"))
        val foreignCatalog = fixture("catalog"); item(foreignCatalog, "deployment_mode").put("version", "unmatched")
        val foreign = inputs(foreignCatalog)
        assertNull(foreign.context(old).mode)
        assertEquals(DraftCurrentUnavailable.BINDING_MISMATCH,
            (DraftCurrentEvaluator().evaluate(request(old, foreign)) as DraftCurrentOutcome.Unavailable).reason)
    }

    @Test fun replacementGraphUsesOldBindingAndIncludesRemovedEdges() {
        val oldInputs = inputs(graph = graphOnly("dep.accelerator.model"))
        val replacement = inputs(graph = graphOnly("dep.context_policy.model").put("revision", 2))
        val (_, source) = saved(content(rows("model", "accelerator", "context_policy")))
        val plan = request(source, replacement, source, oldInputs).recheck
        assertEquals(DraftRecheckScope.CONSERVATIVE_FULL, plan.scope)
        assertEquals(listOf("dep.accelerator.model", "dep.context_policy.model"), plan.edgeIds)
        assertEquals(oldInputs.graphHash, plan.before!!.graphHash)
        assertEquals(replacement.graphHash, plan.after!!.graphHash)
        assertEquals(listOf("sel_accelerator", "sel_context_policy", "sel_model"), plan.selectionIds)
    }

    @Test fun duplicateCycleAndSelfEdgesKeepFiniteDeterministicSelectionAttendance() {
        val graph = graphOnly("dep.accelerator.model")
        val edge = graph["edges"][0].deepCopy<ObjectNode>()
        (graph["edges"] as ArrayNode).add(edge.deepCopy()).add(edge.deepCopy().put("dependent_kind", "model").put("depends_on_kind", "accelerator"))
            .add(edge.deepCopy().put("dependent_kind", "model").put("depends_on_kind", "model"))
        val pins = inputs(graph = graph)
        assertEquals(4, pins.graph.edges.size)
        val (store, old) = saved(content(rows("model", "accelerator"))) // Unknown mode includes the incident edges.
        val next = edit(store, old, content(rows("model", "accelerator").map { if (it.kind == "model")
            LocalSelection.fromJson(it.toJson().also { node -> (node["settings"] as ObjectNode).put("temperature", 0.9) }) else it }))
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val request = request(next, pins, old)
            assertEquals(listOf("sel_accelerator", "sel_model"), request.recheck.selectionIds)
            assertEquals(listOf("dep.accelerator.model"), request.recheck.edgeIds)
            completed(request)
        }
    }

    @Test fun anotherStoreNeverBorrowsPriorDraftIdentitiesEvenAtTheSameRevision() {
        val pins = inputs(); val (_, a) = saved(content(rows("model")))
        val (_, b) = saved(content(rows("workload")))
        assertEquals(a.version.number, b.version.number)
        val plan = request(b, pins, a).recheck
        assertNull(plan.before)
        assertEquals(listOf("sel_workload"), plan.selectionIds)
        assertFalse(plan.changedKinds.contains("model"))
    }

    @Test fun unrepresentedAndTypedWorkloadChangesRetireTheCompleteRuleBasis() {
        val pins = inputs(); val (store, old) = saved(content(rows("workload", "model")))
        val typed = edit(store, old, DraftContent(workload = DraftWorkload(maxConcurrency = BigInteger.valueOf(7)), components = old.content.components))
        val text = edit(store, typed, DraftContent(objective = "unrepresented private objective", workload = typed.content.workload,
            components = typed.content.components))
        assertEquals(DraftRecheckScope.CONSERVATIVE_FULL, request(typed, pins, old).recheck.scope)
        val captured = request(text, pins, typed)
        assertEquals(DraftRecheckScope.CONSERVATIVE_FULL, captured.recheck.scope)
        assertNotEquals(request(typed, pins).binding.inputDigest, captured.binding.inputDigest)
        assertEquals(listOf("sel_model", "sel_workload"), captured.recheck.selectionIds)
        assertFalse(completed(captured).findings.any { "unrepresented private objective" in it.message })
    }
}
