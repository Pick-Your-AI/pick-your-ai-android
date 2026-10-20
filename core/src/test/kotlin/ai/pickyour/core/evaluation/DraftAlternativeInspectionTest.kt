package ai.pickyour.core.evaluation

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
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
import java.security.MessageDigest

/** Actual immutable private stores and the frozen rule loop; no price or operational evidence. */
class DraftAlternativeInspectionTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private class Inputs(val build: ObjectNode, val catalog: ObjectNode, val rules: ObjectNode)
    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun inputs(allRules: Boolean = false): Inputs {
        val b = fixture("build"); val c = fixture("catalog")
        val r = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/baseline/core-ruleset.json")) as ObjectNode
        if (!allRules) {
            val selected = r["rules"].filter { it["id"].asText() in
                setOf("rule.required.foundation", "rule.model.provider.match", "rule.api.model.access") }
            r.putArray("rules").addAll(selected)
        }
        (item(c, "cat.deployment_mode.fixture")["spec"] as ObjectNode).put("mode", "api")
        (item(c, "cat.model.fixture")["spec"] as ObjectNode).putArray("access_modes").add("api")
        return Inputs(b, c, r)
    }
    private fun item(catalog: ObjectNode, id: String) = catalog["items"].single { it["id"].asText() == id } as ObjectNode
    private fun selection(i: Inputs, kind: String = "workload") = i.build["selections"].single { it["kind"].asText() == kind } as ObjectNode
    private fun bytes(value: ObjectNode) = value.toString().toByteArray()
    private fun hash(value: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun pin(value: ObjectNode, rules: Boolean = false): DraftProjectionArtifact {
        val b = bytes(value)
        return DraftProjectionArtifact(value["id"].asText(), value[if (rules) "version" else "revision"].asText(), hash(b), b)
    }
    private fun saved(content: DraftContent = DraftContent()): Pair<LocalDraftStore, StoredDraft> {
        val store = LocalDraftStore(root.resolve("source-${serial++}").also(Files::createDirectories))
        return store to (store.save(null, content, mapOf("retained.note" to "private source")) as DraftSave.Saved).draft
    }
    private fun content(i: Inputs, kinds: Set<String> = setOf("workload", "deployment_mode", "model_provider", "model", "context_policy")): DraftContent {
        val selections = i.build["selections"].filter { it["kind"].asText() in kinds }.map { LocalSelection.fromJson(it as ObjectNode) }
        val ids = selections.map { it.id }.toSet()
        val links = i.build["links"].filter { it["from_selection_id"].asText() in ids && it["to_selection_id"].asText() in ids }
            .map { LocalSelectionLink.fromJson(it as ObjectNode) }
        return DraftContent(components = DraftSelections(selections, links))
    }
    private val workloadSlot = CompatibleOptionSlot("workload", "primary", "sel_workload", false)
    private val modelSlot = CompatibleOptionSlot("model", "primary", "sel_model", true)
    private fun parent(i: Inputs, source: StoredDraft, slot: CompatibleOptionSlot = workloadSlot,
        candidates: List<ObjectNode> = listOf(selection(i))): DraftCompatibleOptionsRequest =
        (DraftCompatibleOptionsRequest.prepare(source, pin(i.catalog), pin(i.rules, true), slot, candidates.map(::bytes))
            as DraftCompatibleOptionsPreparation.Ready).request
    private fun options(request: DraftCompatibleOptionsRequest) =
        DraftCompatibleOptionsEvaluator().evaluate(request) as DraftCompatibleOptionsOutcome.Completed
    private fun capture(parent: DraftCompatibleOptionsRequest, result: DraftCompatibleOptionsOutcome.Completed,
        option: DraftCompatibleOption = result.options.single()): DraftAlternativeInspectionRequest =
        (DraftAlternativeInspectionRequest.prepare(parent, result, option) as DraftAlternativePreparation.Ready).request
    private fun observations(request: DraftAlternativeInspectionRequest): DraftAlternativeOutcome.Completed =
        DraftAlternativeInspector().inspect(request) as DraftAlternativeOutcome.Completed
    private fun workloadRule(i: Inputs, assertion: String = """{"op":"eq","left":{"ref":{"scope":"catalog","kind":"workload","role":"primary","pointer":"/spec/concurrency"}},"right":{"literal":2}}""") {
        val rule = i.rules["rules"][0].deepCopy<ObjectNode>().put("id", "rule.fixture.workload")
        rule.putArray("affected_kinds").add("workload")
        rule.set<JsonNode>("applies_when", mapper.readTree("""{"op":"exists","value":{"ref":{"scope":"selection","kind":"workload","role":"primary","pointer":"/id"}}}"""))
        rule.set<JsonNode>("assert", mapper.readTree(assertion))
        (i.rules["rules"] as ArrayNode).add(rule)
    }
    private fun workloadVariant(i: Inputs, id: String, concurrency: Int?): ObjectNode {
        val v = item(i.catalog, "cat.workload.fixture").deepCopy().put("id", id)
        if (concurrency == null) (v["spec"] as ObjectNode).remove("concurrency") else (v["spec"] as ObjectNode).put("concurrency", concurrency)
        (i.catalog["items"] as ArrayNode).add(v)
        return selection(i).deepCopy().put("catalog_item_id", id)
    }
    private fun conflictingModel(i: Inputs): ObjectNode {
        val v = item(i.catalog, "cat.model.fixture").deepCopy().put("id", "cat.model.conflict")
        (v["spec"] as ObjectNode).put("provider_ref", "cat.provider.other").putArray("access_modes").add("weights")
        (i.catalog["items"] as ArrayNode).add(v)
        return selection(i, "model").deepCopy().put("catalog_item_id", v["id"].asText())
    }
    private fun files() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to hash(Files.readAllBytes(it)).hex
    } }
    private fun values(f: DraftRuleFinding): List<Any> = listOf(f.ruleId, f.outcome, f.severity, f.code, f.message, f.remediation, f.selectionIds)
    private fun independent(context: DraftDiagnosticContext, i: Inputs) =
        (FrozenDiagnosticEvaluator().evaluateDraft(context, bytes(i.catalog), bytes(i.rules)) as DraftRuleEvaluation.Evaluated).findings

    @Test fun replacementKeepsEveryCurrentCandidateAndSlotEmptyFindingInExactRuleOrder() {
        val i = inputs(allRules = true); val bad = conflictingModel(i)
        val (_, source) = saved(content(i)); val before = files()
        val p = parent(i, source, modelSlot, listOf(bad)); val result = options(p); val request = capture(p, result)
        val inspected = observations(request)
        assertTrue(inspected.belongsTo(request)); assertEquals(45, inspected.observations.size)
        val current = independent(DraftDiagnosticContext.create(source.content.components, source.content.workload), i)
        val candidate = independent(p.options.single().second, i)
        assertEquals(current.map(::values), inspected.observations.map { values(it.current) })
        assertEquals(candidate.map(::values), inspected.observations.map { values(it.candidate) })
        assertEquals(result.baselineFindings.map(::values), inspected.observations.map { values(it.slotEmpty) })
        assertEquals(current.map { it.ruleId }, inspected.observations.map { it.ruleId })
        for (id in listOf("rule.model.provider.match", "rule.api.model.access")) {
            val row = inspected.observations.single { it.ruleId == id }
            assertEquals("pass", row.current.outcome); assertEquals("fail", row.candidate.outcome)
            assertTrue(row.outcomeChanged); assertNotNull(row.candidateReason)
        }
        assertEquals(result.options.single().verdict, request.verdict)
        assertEquals(CompatibleOptionVerdict.INCOMPATIBLE, request.verdict)
        assertEquals(selection(i, "model"), mapper.readTree(request.currentSelectionJson))
        assertEquals(bad, mapper.readTree(request.candidateSelectionJson))
        assertEquals(before, files())
    }

    @Test fun replacingAnExistingConflictExposesFailToPassWithoutInventingAWholeDraftVerdict() {
        val i = inputs(); val bad = LocalSelection.fromJson(conflictingModel(i)); val original = content(i)
        val (_, source) = saved(original.withComponents(DraftSelections(original.components.selections.map {
            if (it.id == bad.id) bad else it
        }, original.components.links)))
        val before = files(); val p = parent(i, source, modelSlot, listOf(selection(i, "model")))
        val result = options(p); val request = capture(p, result); val inspected = observations(request)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, request.verdict)
        for (id in listOf("rule.model.provider.match", "rule.api.model.access")) {
            val row = inspected.observations.single { it.ruleId == id }
            assertEquals("fail", row.current.outcome); assertEquals("pass", row.candidate.outcome)
            assertTrue(row.outcomeChanged); assertNull(row.candidateReason)
        }
        assertTrue(DraftAlternativeGap.RUNTIME_READINESS_NOT_ESTABLISHED in request.gaps)
        assertEquals(before, files()); assertEquals(bad.toJson().toString(), request.currentSelectionJson)
    }

    @Test fun inheritedFoundationFailureAndCandidateUnknownStayDistinctFromTheCompatibleVerdict() {
        val i = inputs(); workloadRule(i)
        val bad = workloadVariant(i, "cat.workload.bad", 3); val unknown = workloadVariant(i, "cat.workload.unknown", null)
        val (_, source) = saved(); val before = files()
        val p = parent(i, source, candidates = listOf(selection(i), bad, unknown)); val result = options(p)
        assertEquals(CompatibleOptionVerdict.entries, result.options.map { it.verdict })
        for (option in result.options) {
            val request = capture(p, result, option); val inspected = observations(request)
            assertNull(request.currentSelectionJson)
            assertEquals(request.currentContextJson, request.slotEmptyContextJson)
            val inherited = inspected.observations.single { it.ruleId == "rule.required.foundation" }
            assertEquals(listOf("fail", "fail", "fail"), listOf(inherited.current, inherited.candidate, inherited.slotEmpty).map { it.outcome })
            assertFalse(inherited.outcomeChanged); assertNull(inherited.candidateReason)
            val workload = inspected.observations.single { it.ruleId == "rule.fixture.workload" }
            assertEquals("skipped", workload.current.outcome); assertEquals("skipped", workload.slotEmpty.outcome)
            assertEquals(when (option.verdict) { CompatibleOptionVerdict.COMPATIBLE -> "pass"; CompatibleOptionVerdict.INCOMPATIBLE -> "fail"; else -> "unknown" }, workload.candidate.outcome)
            assertEquals(option.verdict != CompatibleOptionVerdict.COMPATIBLE, workload.candidateReason != null)
            assertEquals(DraftAlternativeGap.entries.toSet(), request.gaps)
        }
        assertEquals(before, files())
    }

    @Test fun secondaryReplacementKeepsThePrimaryLinksAndExactTypedWorkloadAcrossEveryContext() {
        val i = inputs(); val secondary = conflictingModel(i).put("id", "sel_secondary").put("role", "secondary")
        val original = content(i); val proposed = selection(i, "model").deepCopy().put("id", "sel_secondary").put("role", "secondary")
        val workload = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("123456789012345678901.000000000001"),
            requestsPerDay = BigInteger("123456789012345678901234567890123456789"))
        val (_, source) = saved(DraftContent(objective = "uninterpreted objective", context = "private context", workload = workload,
            components = DraftSelections(original.components.selections + LocalSelection.fromJson(secondary), original.components.links)))
        val p = parent(i, source, CompatibleOptionSlot("model", "secondary", "sel_secondary", true), listOf(proposed))
        val request = capture(p, options(p)); val before = files(); observations(request)
        val contexts = listOf(request.currentContextJson, request.candidateContextJson, request.slotEmptyContextJson).map(mapper::readTree)
        val primary = selection(i, "model")
        contexts.forEach { context ->
            assertEquals(primary, context["selections"].single { it["id"].asText() == primary["id"].asText() })
            assertEquals(workload.monthlyBudgetAmount!!.encoded, context["objectives"]["monthly_budget_amount"].asText())
            assertEquals(workload.requestsPerDay, context["objectives"]["requests_per_day"].bigIntegerValue())
            assertFalse(context["objectives"].has("currency")); assertFalse(context.toString().contains("private context"))
        }
        assertEquals(source.content.components.links.map { it.toJson() }, contexts[0]["links"].toList())
        assertEquals(contexts[0]["links"], contexts[1]["links"])
        assertFalse(contexts[2]["selections"].any { it["id"].asText() == "sel_secondary" })
        assertEquals(secondary, mapper.readTree(request.currentSelectionJson)); assertEquals(proposed, mapper.readTree(request.candidateSelectionJson))
        assertEquals(before, files())
    }

    @Test fun unchangedChoiceKeepsAllObservationsAndUnrepresentedPrivateFieldsOutOfRuleContext() {
        val i = inputs(allRules = true); val original = content(i)
        val (_, source) = saved(DraftContent(objective = "private objective\n", context = "private context", primaryModel = "opaque legacy model", components = original.components))
        val p = parent(i, source, modelSlot, listOf(selection(i, "model"))); val request = capture(p, options(p))
        val inspected = observations(request)
        assertTrue(inspected.observations.all { !it.outcomeChanged && !it.detailsChanged })
        assertEquals(request.currentContextJson, request.candidateContextJson)
        for (field in listOf("objective", "context", "primaryModel", "optionalTierIntent", "metadata", "history"))
            assertTrue(field in request.unrepresentedFieldGroups)
        assertFalse(request.currentContextJson.contains("private")); assertFalse(request.currentContextJson.contains("opaque"))
        assertEquals(source.version.hash, request.binding.sourceHash)
        assertEquals(p.binding, request.binding); assertEquals(p.profile, request.compatibleOptionsProfile)
    }

    @Test fun equalLookingForeignRequestsResultsAndOptionsCannotReplaceCapturedMembership() {
        val i = inputs(); val (_, source) = saved(); val first = parent(i, source); val second = parent(i, source)
        val one = options(first); val two = options(second)
        assertEquals(first.binding, second.binding)
        for ((p, r, option) in listOf(Triple(first, two, two.options.single()), Triple(first, one, two.options.single()),
            Triple(second, one, one.options.single()))) {
            assertEquals(DraftAlternativePreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH),
                DraftAlternativeInspectionRequest.prepare(p, r, option))
        }
        val sameLooking = DraftCompatibleOption(one.options.single().selection, one.options.single().verdict, one.options.single().reasons)
        assertEquals(DraftAlternativePreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH),
            DraftAlternativeInspectionRequest.prepare(first, one, sameLooking))
        val current = capture(first, one); val another = capture(first, one); val inspected = observations(current)
        assertTrue(current.belongsTo(first, one, one.options.single())); assertFalse(current.belongsTo(second, two, two.options.single()))
        assertTrue(inspected.belongsTo(current)); assertFalse(inspected.belongsTo(another))
    }

    @Test fun capturedArtifactsAndReturnedFindingsCannotBeMutatedThroughCallerCollections() {
        val i = inputs(); workloadRule(i); val (_, source) = saved(); val p = parent(i, source); val result = options(p)
        val request = capture(p, result); val before = files()
        (item(i.catalog, "cat.workload.fixture")["spec"] as ObjectNode).put("concurrency", 99)
        (i.rules["rules"][0] as ObjectNode).put("message", "changed after capture")
        val inspected = observations(request); val workload = inspected.observations.single { it.ruleId == "rule.fixture.workload" }
        assertEquals("pass", workload.candidate.outcome)
        assertThrows(UnsupportedOperationException::class.java) { (inspected.observations as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (request.unrepresentedFieldGroups as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (request.gaps as MutableSet).clear() }
        for (finding in listOf(workload.current, workload.candidate, workload.slotEmpty))
            assertThrows(UnsupportedOperationException::class.java) { (finding.selectionIds as MutableList).clear() }
        assertFalse(inspected.observations.first().candidate.message.contains("changed after capture"))
        assertEquals(before, files())
    }

    @Test fun optionalIntentStaysWithItsOwnerAndDisabledCurrentSelectionsRemainExplicit() {
        val i = inputs(); val candidate = selection(i, "data_source")
        val slot = CompatibleOptionSlot("data_source", candidate["role"].asText(), candidate["id"].asText(), false)
        for (activation in listOf(Activation.UNKNOWN, Activation.DISABLED)) {
            val (_, source) = saved(DraftContent().withTier(OptionalTier.T3, TierDraft(activation)))
            assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.OPTIONAL_INTENT_UNRESOLVED),
                DraftCompatibleOptionsRequest.prepare(source, pin(i.catalog), pin(i.rules, true), slot, listOf(bytes(candidate))))
        }
        val disabled = LocalSelection.fromJson(candidate.deepCopy().put("enabled", false))
        val (_, source) = saved(DraftContent(components = DraftSelections(listOf(disabled)))
            .withTier(OptionalTier.T3, TierDraft(Activation.ENABLED)))
        val before = files(); val p = parent(i, source, slot.copy(replacing = true), listOf(candidate)); val request = capture(p, options(p))
        observations(request)
        assertFalse(mapper.readTree(request.currentSelectionJson)["enabled"].booleanValue())
        assertTrue(mapper.readTree(request.candidateSelectionJson)["enabled"].booleanValue())
        assertEquals(Activation.ENABLED, source.content.optional.getValue(OptionalTier.T3).activation)
        assertFalse(source.content.components.selections.single().enabled)
        assertEquals(before, files())
    }

    @Test fun unsupportedCurrentOperandCannotBeSubstitutedWithTheSuccessfulSlotEmptyBasis() {
        val i = inputs(); workloadRule(i, """{"op":"eq","left":{"ref":{"scope":"selection","kind":"workload","role":"primary","pointer":"/settings/concurrency"}},"right":{"literal":2}}""")
        val raw = selection(i).toString().replace("\"concurrency\":2", "\"concurrency\":1e0")
        assertTrue(raw.contains("1e0"))
        val (_, source) = saved(DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(raw.toByteArray())))))
        val p = parent(i, source, workloadSlot.copy(replacing = true)); val result = options(p); val request = capture(p, result)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, result.options.single().verdict)
        val before = files(); val inspected = DraftAlternativeInspector().inspect(request)
        // The canonical store admits an integral JSON spelling as integer 1. Inspect that actual
        // stored value, rather than attributing the pre-save token's node type to the saved source.
        val integral = (inspected as DraftAlternativeOutcome.Completed).observations.single { it.ruleId == "rule.fixture.workload" }
        assertEquals("fail", integral.current.outcome); assertEquals("pass", integral.candidate.outcome)
        assertEquals(BigInteger.ONE, mapper.readTree(request.currentContextJson)["selections"][0]["settings"]["concurrency"].bigIntegerValue())
        assertTrue(inspected.belongsTo(request)); assertEquals(before, files())
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, result.options.single().verdict)

        val fractional = inputs()
        val rule = fractional.rules["rules"][0].deepCopy<ObjectNode>().put("id", "rule.fixture.temperature")
        rule.putArray("affected_kinds").add("model")
        rule.set<JsonNode>("applies_when", mapper.readTree("""{"op":"exists","value":{"ref":{"scope":"selection","kind":"model","role":"primary","pointer":"/id"}}}"""))
        rule.set<JsonNode>("assert", mapper.readTree("""{"op":"eq","left":{"ref":{"scope":"selection","kind":"model","role":"primary","pointer":"/settings/temperature"}},"right":{"literal":0}}"""))
        (fractional.rules["rules"] as ArrayNode).add(rule)
        val (_, stored) = saved(content(fractional))
        val candidate = selection(fractional, "model").deepCopy()
        (candidate["settings"] as ObjectNode).put("temperature", 0)
        val replacement = parent(fractional, stored, modelSlot, listOf(candidate)); val allowed = options(replacement)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, allowed.options.single().verdict)
        val captured = capture(replacement, allowed)
        assertEquals("0.4", mapper.readTree(captured.currentSelectionJson)["settings"]["temperature"].asText())
        val unchanged = files(); val rejected = DraftAlternativeInspector().inspect(captured)
        assertTrue(rejected is DraftAlternativeOutcome.Unavailable)
        assertEquals(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION, (rejected as DraftAlternativeOutcome.Unavailable).reason)
        assertTrue(rejected.belongsTo(captured)); assertEquals(unchanged, files())
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, allowed.options.single().verdict)
    }

    @Test fun workLimitsCancellationAndInconsistentOriginalOutputNeverReturnPartialRows() {
        val i = inputs(); val (_, source) = saved(); val p = parent(i, source); val result = options(p); val request = capture(p, result)
        assertEquals(CompatibleOptionsUnavailable.WORK_LIMIT,
            (DraftAlternativeInspector(1).inspect(request) as DraftAlternativeOutcome.Unavailable).reason)
        var checks = 0
        assertEquals(CompatibleOptionsUnavailable.CANCELLED,
            (DraftAlternativeInspector().inspect(request) { ++checks == 2 } as DraftAlternativeOutcome.Unavailable).reason)
        assertEquals(2, checks)
        Thread.currentThread().interrupt()
        try { assertEquals(CompatibleOptionsUnavailable.CANCELLED,
            (DraftAlternativeInspector().inspect(request) as DraftAlternativeOutcome.Unavailable).reason) }
        finally { Thread.interrupted() }
        for (findings in listOf(result.baselineFindings.dropLast(1), result.baselineFindings + result.baselineFindings.first())) {
            val invalid = DraftCompatibleOptionsOutcome.Completed(p, result.options, findings)
            val captured = capture(p, invalid)
            assertEquals(CompatibleOptionsUnavailable.INVALID_OUTPUT,
                (DraftAlternativeInspector().inspect(captured) as DraftAlternativeOutcome.Unavailable).reason)
        }
    }

    @Test fun realSavedObjectiveChangeRetiresOldAuthorityAndOnlyAFreshExplicitCommandCanWrite() {
        val i = inputs(); val (store, first) = saved(); val p = parent(i, first); val result = options(p)
        val request = capture(p, result); val gate = DraftCompatibleOptionsCoordinator(); gate.bind(p)
        assertTrue(gate.complete(requireNotNull(gate.start(p)), result))
        val changed = (store.save(first.version, DraftContent(objective = "new saved objective", context = "retained context")) as DraftSave.Saved).draft
        val current = parent(i, changed); gate.bind(current); assertFalse(gate.isCurrent(result))
        assertNotEquals(p.binding.inputDigest, current.binding.inputDigest)
        val before = files(); assertTrue(observations(request).belongsTo(request)) // Historical observation confers no fresh authority.
        val selection = result.options.single().selection; val catalog = SelectionCatalogSnapshot.fromJson(bytes(i.catalog))
        val command = SelectionCommand.Add(selection, CatalogSelectionChoice(catalog.hash, selection.kind, selection.catalogItemId, selection.catalogItemVersion))
        assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(first.version, command), catalog))
        assertEquals(before, files())
        val freshResult = options(current); val freshInspection = capture(current, freshResult)
        assertFalse(request.belongsTo(current, freshResult, freshResult.options.single()))
        observations(freshInspection); assertEquals(before, files())
        val written = store.applySelection(DraftSelectionIntent(changed.version, command), catalog) as DraftSelectionSave.Saved
        assertEquals(changed.version.number + 1, written.draft.version.number)
        assertEquals(listOf(selection.toJson()), written.draft.content.components.selections.map { it.toJson() })
        assertEquals(changed.content.objective, written.draft.content.objective)
        assertEquals(changed.content.context, written.draft.content.context)
    }

    @Test fun unchangedFailOutcomesStillExposeChangedSelectionAssociationsAndLiteralDetails() {
        val i = inputs(); val rule = i.rules["rules"][0].deepCopy<ObjectNode>().put("id", "rule.fixture.always")
        rule.putArray("affected_kinds").add("workload")
        rule.set<JsonNode>("applies_when", mapper.readTree("""{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""))
        rule.set<JsonNode>("assert", mapper.readTree("""{"op":"eq","left":{"literal":1},"right":{"literal":2}}"""))
        rule.put("message", "Unverified \u202e literal https://example.invalid/claim\n")
        rule.put("remediation", "Review the exact synthetic condition\n")
        (i.rules["rules"] as ArrayNode).add(rule)
        val disabled = LocalSelection.fromJson(selection(i).deepCopy().put("enabled", false))
        val (_, source) = saved(DraftContent(components = DraftSelections(listOf(disabled))))
        val p = parent(i, source, workloadSlot.copy(replacing = true)); val request = capture(p, options(p))
        val row = observations(request).observations.single { it.ruleId == "rule.fixture.always" }
        assertEquals("fail", row.current.outcome); assertEquals("fail", row.candidate.outcome)
        assertFalse(row.outcomeChanged); assertTrue(row.detailsChanged)
        assertTrue(row.current.selectionIds.isEmpty()); assertEquals(listOf("sel_workload"), row.candidate.selectionIds)
        assertEquals(rule["message"].asText(), row.current.message); assertEquals(rule["message"].asText(), row.candidate.message)
        assertEquals(rule["remediation"].asText(), row.candidate.remediation); assertNull(row.candidateReason)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, request.verdict)
    }
}
