package ai.pickyour.core.evaluation

import ai.pickyour.core.analysis.AnalysisState
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** Synthetic mutations of unchanged bundled fixtures; no production catalog or shared-policy claim. */
class CompatibleOptionsTest {
    private val mapper = ObjectMapper()
    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private val primary = CompatibleOptionSlot("model", "primary", "sel_model", true)
    private class Inputs(val build: ObjectNode, val catalog: ObjectNode, val rules: ObjectNode)
    private fun inputs(): Inputs {
        val b = fixture("build"); val c = fixture("catalog")
        val r = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/baseline/core-ruleset.json")) as ObjectNode
        val rules = r["rules"].filter { it["id"].asText() in setOf("rule.required.foundation", "rule.model.provider.match", "rule.api.model.access") }
        r.putArray("rules").addAll(rules)
        (b["locks"] as ObjectNode).put("ruleset_id", r["id"].asText()).put("ruleset_version", r["version"].asText())
        val selections = b["selections"].filter { it["kind"].asText() in setOf("workload", "deployment_mode", "model_provider", "model", "context_policy") }
        b.putArray("selections").addAll(selections)
        val ids = selections.map { it["id"].asText() }.toSet()
        val links = b["links"].filter { it["from_selection_id"].asText() in ids && it["to_selection_id"].asText() in ids }
        b.putArray("links").addAll(links)
        (item(c, "cat.deployment_mode.fixture")["spec"] as ObjectNode).put("mode", "api")
        (item(c, "cat.model.fixture")["spec"] as ObjectNode).putArray("access_modes").add("api")
        return Inputs(b, c, r)
    }
    private fun item(catalog: ObjectNode, id: String) = catalog["items"].single { it["id"].asText() == id } as ObjectNode
    private fun model(b: ObjectNode) = b["selections"].single { it["id"].asText() == "sel_model" } as ObjectNode
    private fun variant(i: Inputs, id: String, conflict: Boolean = false): ObjectNode {
        val v = item(i.catalog, "cat.model.fixture").deepCopy().put("id", id)
        if (conflict) {
            (v["spec"] as ObjectNode).put("provider_ref", "cat.provider.other")
            (v["spec"] as ObjectNode).putArray("access_modes").add("weights")
        }
        (i.catalog["items"] as com.fasterxml.jackson.databind.node.ArrayNode).add(v)
        return model(i.build).deepCopy().put("catalog_item_id", id)
    }
    private fun prepare(i: Inputs, slot: CompatibleOptionSlot = primary, candidates: List<ByteArray> = listOf(model(i.build).toString().toByteArray())) =
        CompatibleOptionsRequest.prepare(i.build.toString().toByteArray(), i.catalog.toString().toByteArray(), i.rules.toString().toByteArray(), slot, candidates)
    private fun ready(i: Inputs, slot: CompatibleOptionSlot = primary, candidates: List<ObjectNode> = listOf(model(i.build))): CompatibleOptionsRequest {
        val result = prepare(i, slot, candidates.map { it.toString().toByteArray() })
        assertTrue(result is CompatibleOptionsPreparation.Ready, result.toString())
        return (result as CompatibleOptionsPreparation.Ready).request
    }
    private fun completed(request: CompatibleOptionsRequest): CompatibleOptionsOutcome.Completed {
        val result = CompatibleOptionsEvaluator().evaluate(request)
        assertTrue(result is CompatibleOptionsOutcome.Completed, (result as? CompatibleOptionsOutcome.Unavailable)?.reason.toString())
        return result as CompatibleOptionsOutcome.Completed
    }
    private fun evaluate(i: Inputs, build: String): ReferenceReportView {
        val result = FrozenDiagnosticEvaluator().evaluate(build.byteInputStream(), i.catalog.toString().byteInputStream(), i.rules.toString().byteInputStream())
        assertTrue(result is DiagnosticEvaluation.Evaluated, result.toString())
        return (result as DiagnosticEvaluation.Evaluated).report.inspect()
    }
    private fun extraSelection(i: Inputs, source: ObjectNode, role: String = "secondary"): ObjectNode {
        val s = source.deepCopy().put("id", "sel_model_extra").put("role", role)
        (i.build["selections"] as com.fasterxml.jackson.databind.node.ArrayNode).add(s)
        return s
    }
    private fun temperatureRule(i: Inputs) {
        val rule = i.rules["rules"][1].deepCopy<ObjectNode>().put("id", "rule.fixture.temperature")
        rule.set<JsonNode>("assert", mapper.readTree("""{"op":"eq","left":{"ref":{"scope":"selection","kind":"model","role":"primary","pointer":"/settings/temperature"}},"right":{"literal":0}}"""))
        (i.rules["rules"] as com.fasterxml.jackson.databind.node.ArrayNode).add(rule)
    }

    @Test fun exactEmptyPrimaryKeepsSecondaryRoleAndExposesIndependentConflicts() {
        val i = inputs(); val conflict = variant(i, "cat.model.conflict", true)
        val secondary = extraSelection(i, conflict)
        val original = i.build.toString()
        val request = ready(i, candidates = listOf(conflict))
        val baseline = mapper.readTree(request.baseline)
        assertEquals(secondary, baseline["selections"].single { it["id"].asText() == "sel_model_extra" })
        assertFalse(baseline["selections"].any { it["id"].asText() == "sel_model" })
        assertFalse(baseline["links"].any { it["from_selection_id"].asText() == "sel_model" || it["to_selection_id"].asText() == "sel_model" })
        val result = completed(request).options.single()
        assertEquals(CompatibleOptionVerdict.INCOMPATIBLE, result.verdict)
        assertEquals(setOf("rule.model.provider.match", "rule.api.model.access"), result.reasons.map { it.finding.ruleId }.toSet())
        assertEquals(setOf("skipped", "unknown"), result.reasons.map { it.baselineOutcome }.toSet())
        assertEquals(setOf("fail"), result.reasons.map { it.finding.outcome }.toSet())
        assertEquals(original, i.build.toString())
        // Causal control: role promotion makes these same two conflicts preexisting and masks them.
        val promoted = baseline.deepCopy<ObjectNode>()
        (promoted["selections"].single { it["id"].asText() == "sel_model_extra" } as ObjectNode).put("role", "primary")
        val findings = evaluate(i, promoted.toString()).findings.associateBy { it.ruleId }
        assertTrue(result.reasons.all { findings.getValue(it.finding.ruleId).outcome == "fail" })
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, request.baseline.byteInputStream()))
    }

    @Test fun missingFoundationIsInheritedWhileCandidateConflictRemainsExcluded() {
        val i = inputs(); val conflict = variant(i, "cat.model.conflict", true)
        val remaining = i.build["selections"].filter { it["kind"].asText() != "workload" }
        i.build.putArray("selections").addAll(remaining)
        val request = ready(i, candidates = listOf(model(i.build), conflict))
        assertEquals("fail", evaluate(i, request.baseline).findings.single { it.ruleId == "rule.required.foundation" }.outcome)
        val results = completed(request).options
        assertEquals(listOf(CompatibleOptionVerdict.COMPATIBLE, CompatibleOptionVerdict.INCOMPATIBLE), results.map { it.verdict })
        assertTrue(results.all { option -> option.reasons.none { it.finding.ruleId == "rule.required.foundation" } })
    }

    @Test fun compatibleIncompatibleAndUnknownRemainSeparateWithActualRuleReasons() {
        val i = inputs(); temperatureRule(i)
        val known = model(i.build).deepCopy(); (known["settings"] as ObjectNode).put("temperature", 0)
        val conflict = variant(i, "cat.model.conflict", true); (conflict["settings"] as ObjectNode).put("temperature", 0)
        val unknown = variant(i, "cat.model.unknown"); (unknown["settings"] as ObjectNode).remove("temperature")
        val complete = completed(ready(i, candidates = listOf(known, conflict, unknown)))
        val results = complete.options
        assertEquals(CompatibleOptionVerdict.entries, results.map { it.verdict })
        assertTrue(results[0].reasons.isEmpty())
        assertEquals(setOf("rule.model.provider.match", "rule.api.model.access"), results[1].reasons.map { it.finding.ruleId }.toSet())
        val missing = results[2].reasons.single()
        assertEquals("rule.fixture.temperature", missing.finding.ruleId)
        assertEquals("skipped", missing.baselineOutcome); assertEquals("unknown", missing.finding.outcome)
        assertTrue(missing.finding.message.isNotBlank())
        assertEquals(listOf(1, 1, 1), listOf(complete.compatibleCount, complete.incompatibleCount, complete.unknownCount))
        assertEquals(listOf(results[0]), complete.visibleOptions())
        assertEquals(listOf(results[0], results[1]), complete.visibleOptions(includeIncompatible = true))
        assertEquals(listOf(results[0], results[2]), complete.visibleOptions(includeUnknown = true))
        assertEquals(results, complete.visibleOptions(true, true))
        assertThrows(UnsupportedOperationException::class.java) { (complete.options as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (results[1].reasons as MutableList).clear() }
    }

    @Test fun emptyCandidatePageReturnsEmptyBucketsWithoutInventingAnOption() {
        val complete = completed(ready(inputs(), candidates = emptyList()))
        assertTrue(complete.options.isEmpty()); assertTrue(complete.visibleOptions(true, true).isEmpty())
        assertEquals(listOf(0, 0, 0), listOf(complete.compatibleCount, complete.incompatibleCount, complete.unknownCount))
    }

    @Test fun emptyPagesStillValidateFrozenSlotSpellingsAndBoundIntent() {
        val i = inputs(); val slot = CompatibleOptionSlot("model", "secondary", "sel_empty", false)
        for (invalid in listOf(slot.copy(kind = "made_up"), slot.copy(role = "Primary"), slot.copy(selectionId = "x"),
                slot.copy(kind = "m".repeat(1000)), slot.copy(role = "r".repeat(1000)), slot.copy(selectionId = "s".repeat(1000))))
            assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_SLOT), prepare(i, invalid, emptyList()))
        assertTrue(prepare(i, slot, emptyList()) is CompatibleOptionsPreparation.Ready)
    }

    @Test fun replacingSecondaryPreservesPrimaryIdentitySettingsOrderAndLinks() {
        val i = inputs(); val candidate = variant(i, "cat.model.secondary", true)
        val secondary = extraSelection(i, candidate)
        val slot = CompatibleOptionSlot("model", "secondary", secondary["id"].asText(), true)
        val request = ready(i, slot, listOf(secondary))
        val baseline = mapper.readTree(request.baseline); val hypothetical = mapper.readTree(request.options.single().second)
        assertEquals(model(i.build), baseline["selections"].single { it["id"].asText() == "sel_model" })
        assertEquals(i.build, hypothetical)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
        assertEquals(i.build["links"], hypothetical["links"])
    }

    @Test fun secondaryScopedRuleEvaluatesTheRequestedSelectionInsteadOfThePrimary() {
        val i = inputs(); val bad = variant(i, "cat.model.secondary.bad", true)
        val current = extraSelection(i, bad)
        val good = variant(i, "cat.model.secondary.good").put("id", "sel_model_extra").put("role", "secondary")
        val rule = i.rules["rules"][1].deepCopy<ObjectNode>().put("id", "rule.fixture.secondary.provider")
        fun secondary(node: JsonNode) {
            if (node.isObject && node.has("scope") && node.path("kind").asText() == "model") (node as ObjectNode).put("role", "secondary")
            if (node.isContainerNode) node.forEach(::secondary)
        }
        secondary(rule); (i.rules["rules"] as com.fasterxml.jackson.databind.node.ArrayNode).add(rule)
        val request = ready(i, CompatibleOptionSlot("model", "secondary", "sel_model_extra", true), listOf(good, current))
        val result = completed(request).options
        assertEquals(listOf(CompatibleOptionVerdict.COMPATIBLE, CompatibleOptionVerdict.INCOMPATIBLE), result.map { it.verdict })
        assertEquals("rule.fixture.secondary.provider", result[1].reasons.single().finding.ruleId)
        assertEquals("skipped", result[1].reasons.single().baselineOutcome)
        assertEquals(model(i.build), mapper.readTree(request.baseline)["selections"].single { it["id"].asText() == "sel_model" })
    }

    @Test fun explicitAddFillsOnlyTheRequestedEmptySlot() {
        val i = inputs(); val candidate = variant(i, "cat.model.secondary").put("id", "sel_new").put("role", "secondary")
        val request = ready(i, CompatibleOptionSlot("model", "secondary", "sel_new", false), listOf(candidate))
        assertEquals(i.build, mapper.readTree(request.baseline))
        val hypothetical = mapper.readTree(request.options.single().second)
        assertEquals(i.build["selections"].toList(), hypothetical["selections"].toList().dropLast(1))
        assertEquals(candidate, hypothetical["selections"].last())
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
    }

    @Test fun missingReplacementWrongRoleDuplicateSlotAndDisabledCandidatesCannotBecomeAdds() {
        val i = inputs()
        for (slot in listOf(primary.copy(selectionId = "missing"), primary.copy(role = "secondary"), primary.copy(replacing = false)))
            assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_SLOT), prepare(i, slot))
        val disabled = model(i.build).deepCopy().put("enabled", false)
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_SLOT), prepare(i, candidates = listOf(disabled.toString().toByteArray())))
        extraSelection(i, model(i.build), "primary")
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_SLOT), prepare(i))
    }

    @Test fun frozenMinimumSelectionsRemainUnavailableWithoutInventedBlankBuild() {
        val i = inputs(); val two = i.build["selections"].filter { it["kind"].asText() in setOf("model", "workload") }
        i.build.putArray("selections").addAll(two); i.build.putArray("links")
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, i.build.toString().byteInputStream()))
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.BASELINE_UNAVAILABLE), prepare(i))
        val candidate = model(i.build).toString().toByteArray(); i.build.putArray("selections")
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT), prepare(i, candidates = listOf(candidate)))
    }

    @Test fun strictInputAndExactCatalogLocksRejectBeforeAnyVerdict() {
        val i = inputs(); val b = i.build.toString().toByteArray(); val c = i.catalog.toString().toByteArray(); val r = i.rules.toString().toByteArray()
        val candidate = model(i.build).toString().toByteArray()
        for (bad in listOf("{}".toByteArray(), "[]".toByteArray(), (String(b) + "{}").toByteArray(), String(b).replaceFirst("\"revision\":1", "\"revision\":1,\"revision\":1").toByteArray()))
            assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT), CompatibleOptionsRequest.prepare(bad, c, r, primary, listOf(candidate)))
        i.catalog.put("id", "catalog.changed")
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH), prepare(i))
        i.catalog.put("id", "catalog.fixture.1"); item(i.catalog, "cat.model.fixture").put("version", "changed")
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH), prepare(i))
    }

    @Test fun immutableCaptureRetainsOriginalInputsAndLargeRevisionWithoutNarrowing() {
        val i = inputs(); val revision = BigInteger("922337203685477580812345")
        i.build.put("revision", revision)
        val b = i.build.toString().toByteArray(); val c = i.catalog.toString().toByteArray(); val r = i.rules.toString().toByteArray()
        val candidate = model(i.build).toString().toByteArray(); val candidates = mutableListOf(candidate)
        val request = (CompatibleOptionsRequest.prepare(b, c, r, primary, candidates) as CompatibleOptionsPreparation.Ready).request
        val digest = request.binding.inputDigest
        b.fill(0); c.fill(0); r.fill(0); candidate.fill(0); candidates.clear()
        assertEquals(revision, request.binding.buildRevision); assertEquals(digest, request.binding.inputDigest)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
        assertFalse(request.toString().contains(i.build["name"].asText()))
    }

    @Test fun floatingBuildAndCandidateOperandsKeepUnsupportedTypesAfterHypotheticalEncoding() {
        val i = inputs(); temperatureRule(i)
        val candidate = model(i.build).deepCopy(); (candidate["settings"] as ObjectNode).put("temperature", "RAW")
        val raw = candidate.toString().replace("\"RAW\"", "1e0").toByteArray()
        val request = (prepare(i, candidates = listOf(raw)) as CompatibleOptionsPreparation.Ready).request
        assertTrue(request.options.single().second.contains("\"temperature\":1.0"))
        assertEquals(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION, (CompatibleOptionsEvaluator().evaluate(request) as CompatibleOptionsOutcome.Unavailable).reason)
        val b = i.build.toString().replaceFirst("\"revision\":1", "\"revision\":1e0").toByteArray()
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION), CompatibleOptionsRequest.prepare(b, i.catalog.toString().toByteArray(), i.rules.toString().toByteArray(), primary, listOf(raw)))
    }

    @Test fun workAndInputBoundsRejectTheWholeAttemptAndRetryUsesFreshEvidence() {
        val i = inputs(); val request = ready(i)
        assertEquals(CompatibleOptionsUnavailable.WORK_LIMIT, (CompatibleOptionsEvaluator(1).evaluate(request) as CompatibleOptionsOutcome.Unavailable).reason)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.WORK_LIMIT), prepare(i, candidates = List(33) { model(i.build).toString().toByteArray() }))
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.WORK_LIMIT), prepare(i, candidates = listOf(ByteArray(262_145))))
        Thread.currentThread().interrupt()
        try { assertEquals(CompatibleOptionsUnavailable.CANCELLED, (CompatibleOptionsEvaluator().evaluate(request) as CompatibleOptionsOutcome.Unavailable).reason) }
        finally { Thread.interrupted() }
    }

    @Test fun laterCandidateFailureDoesNotPublishAnEarlierCompatiblePartialBatch() {
        val i = inputs(); temperatureRule(i)
        val known = model(i.build).deepCopy(); (known["settings"] as ObjectNode).put("temperature", 0)
        val unsupported = variant(i, "cat.model.unsupported"); (unsupported["settings"] as ObjectNode).put("temperature", 0.5)
        val request = ready(i, candidates = listOf(known, unsupported))
        assertEquals(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION, (CompatibleOptionsEvaluator().evaluate(request) as CompatibleOptionsOutcome.Unavailable).reason)
    }

    @Test fun sameValueForeignResultsRetryAndAbaCannotCompleteOrRemainCurrent() {
        val i = inputs(); val a = ready(i); val equal = ready(i)
        assertEquals(a.binding, equal.binding)
        val coordinator = CompatibleOptionsCoordinator().apply { bind(a) }; val first = coordinator.start(a)!!
        assertFalse(coordinator.complete(first, completed(equal)))
        val retry = coordinator.start(a)!!; val value = completed(a)
        assertFalse(coordinator.complete(first, value)); assertFalse(coordinator.cancel(first))
        assertTrue(coordinator.complete(retry, value)); assertTrue(coordinator.isCurrent(value))
        coordinator.bind(equal); assertFalse(coordinator.isCurrent(value)); assertTrue(coordinator.snapshot() is AnalysisState.Idle)
        coordinator.bind(a); assertFalse(coordinator.complete(retry, value))
        val next = coordinator.start(a)!!; assertTrue(coordinator.cancel(next)); assertFalse(coordinator.complete(next, value))
        coordinator.clear(); assertFalse(coordinator.isCurrent(value))
    }

    @Test fun editsCatalogRulesAndRoleChangesRetireInFlightResults() {
        val i = inputs(); val initial = ready(i); val old = completed(initial)
        val changes = mutableListOf<CompatibleOptionsRequest>()
        i.build.put("revision", 2); changes.add(ready(i))
        i.catalog.put("revision", 2); changes.add(ready(i))
        i.rules.put("version", "1.0.1"); (i.build["locks"] as ObjectNode).put("ruleset_version", "1.0.1"); changes.add(ready(i))
        val secondary = extraSelection(i, model(i.build)); changes.add(ready(i, CompatibleOptionSlot("model", "secondary", "sel_model_extra", true), listOf(secondary)))
        for (next in changes) {
            assertNotEquals(initial.binding.inputDigest, next.binding.inputDigest)
            val coordinator = CompatibleOptionsCoordinator().apply { bind(initial) }; val attempt = coordinator.start(initial)!!
            coordinator.bind(next)
            assertTrue(coordinator.snapshot() is AnalysisState.Idle)
            assertFalse(coordinator.complete(attempt, old)); assertFalse(coordinator.isCurrent(old))
            val current = coordinator.start(next)!!; assertTrue(coordinator.snapshot() is AnalysisState.Pending)
            val value = completed(next); assertTrue(coordinator.complete(current, value)); assertTrue(coordinator.isCurrent(value))
        }
    }

    @Test fun retiredQueuedStartCannotRebindTheCurrentEditorToAnOlderRequest() {
        val i = inputs(); val old = ready(i); i.build.put("revision", 2); val current = ready(i)
        val coordinator = CompatibleOptionsCoordinator(); coordinator.bind(old)
        coordinator.bind(current)
        assertNull(coordinator.start(old))
        assertTrue(coordinator.snapshot() is AnalysisState.Idle)
    }
}
