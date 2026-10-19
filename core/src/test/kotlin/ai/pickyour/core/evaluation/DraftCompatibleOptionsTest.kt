package ai.pickyour.core.evaluation

import ai.pickyour.core.analysis.AnalysisState
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
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

/** Actual private stores and unchanged frozen fixtures with explicit synthetic counterexamples. */
class DraftCompatibleOptionsTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private class Inputs(val build: ObjectNode, val catalog: ObjectNode, val rules: ObjectNode)
    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun inputs(allRules: Boolean = false): Inputs {
        val b = fixture("build"); val c = fixture("catalog")
        val r = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/baseline/core-ruleset.json")) as ObjectNode
        if (!allRules) {
            val selectedRules = r["rules"].filter { it["id"].asText() in
                setOf("rule.required.foundation", "rule.model.provider.match", "rule.api.model.access") }
            r.putArray("rules").addAll(selectedRules)
        }
        (b["locks"] as ObjectNode).put("ruleset_id", r["id"].asText()).put("ruleset_version", r["version"].asText())
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
        val directory = root.resolve("source-${serial++}").also(Files::createDirectories)
        val store = LocalDraftStore(directory)
        return store to (store.save(null, content, mapOf("retained.note" to "private fixture")) as DraftSave.Saved).draft
    }
    private fun content(i: Inputs, kinds: Set<String> = setOf("workload", "deployment_mode", "model_provider", "model", "context_policy")): DraftContent {
        val selections = i.build["selections"].filter { it["kind"].asText() in kinds }.map { LocalSelection.fromJson(it as ObjectNode) }
        val ids = selections.map { it.id }.toSet()
        val links = i.build["links"].filter { it["from_selection_id"].asText() in ids && it["to_selection_id"].asText() in ids }
            .map { LocalSelectionLink.fromJson(it as ObjectNode) }
        return DraftContent(components = DraftSelections(selections, links))
    }
    private val workloadSlot = CompatibleOptionSlot("workload", "primary", "sel_workload", false)
    private fun prepare(i: Inputs, source: StoredDraft, slot: CompatibleOptionSlot = workloadSlot,
        candidates: List<ByteArray> = listOf(bytes(selection(i)))) = DraftCompatibleOptionsRequest.prepare(source,
        pin(i.catalog), pin(i.rules, true), slot, candidates)
    private fun ready(i: Inputs, source: StoredDraft, slot: CompatibleOptionSlot = workloadSlot,
        candidates: List<ObjectNode> = listOf(selection(i))): DraftCompatibleOptionsRequest {
        val result = prepare(i, source, slot, candidates.map(::bytes))
        assertTrue(result is DraftCompatibleOptionsPreparation.Ready, result.toString())
        return (result as DraftCompatibleOptionsPreparation.Ready).request
    }
    private fun completed(request: DraftCompatibleOptionsRequest): DraftCompatibleOptionsOutcome.Completed {
        val result = DraftCompatibleOptionsEvaluator().evaluate(request)
        assertTrue(result is DraftCompatibleOptionsOutcome.Completed, (result as? DraftCompatibleOptionsOutcome.Unavailable)?.reason.toString())
        return result as DraftCompatibleOptionsOutcome.Completed
    }
    private fun workloadRule(i: Inputs, assertion: String = """{"op":"eq","left":{"ref":{"scope":"catalog","kind":"workload","role":"primary","pointer":"/spec/concurrency"}},"right":{"literal":2}}""") {
        val rule = i.rules["rules"][0].deepCopy<ObjectNode>().put("id", "rule.fixture.workload.concurrency")
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
    private fun modelVariant(i: Inputs, id: String, conflict: Boolean): ObjectNode {
        val v = item(i.catalog, "cat.model.fixture").deepCopy().put("id", id)
        if (conflict) {
            (v["spec"] as ObjectNode).put("provider_ref", "cat.provider.other")
            (v["spec"] as ObjectNode).putArray("access_modes").add("weights")
        }
        (i.catalog["items"] as ArrayNode).add(v)
        return selection(i, "model").deepCopy().put("catalog_item_id", id)
    }
    private fun storeFiles(): Map<String, String> = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile)
        .toList().associate { root.relativize(it).toString() to hash(Files.readAllBytes(it)).hex } }

    @Test fun emptyStoredDraftKeepsFoundationFailureAndSeparatesNewFailureAndUnknown() {
        val i = inputs(); workloadRule(i)
        val bad = workloadVariant(i, "cat.workload.bad", 3); val unknown = workloadVariant(i, "cat.workload.unknown", null)
        val (_, source) = saved(); val before = storeFiles()
        val request = ready(i, source, candidates = listOf(selection(i), bad, unknown))
        val result = completed(request)
        assertEquals("fail", result.baselineFindings.single { it.ruleId == "rule.required.foundation" }.outcome)
        assertEquals(CompatibleOptionVerdict.entries, result.options.map { it.verdict })
        assertTrue(result.options.all { option -> option.reasons.none { it.finding.ruleId == "rule.required.foundation" } })
        assertEquals("skipped", result.options[1].reasons.single().baselineOutcome)
        assertEquals("fail", result.options[1].reasons.single().finding.outcome)
        assertEquals("unknown", result.options[2].reasons.single().finding.outcome)
        assertTrue(result.options[2].reasons.single().finding.message.isNotBlank())
        assertEquals(before, storeFiles())
        assertEquals(listOf(1, 1, 1), listOf(result.compatibleCount, result.incompatibleCount, result.unknownCount))
        assertEquals(listOf(result.options[0]), result.visibleOptions())
        assertEquals(listOf(result.options[0], result.options[2]), result.visibleOptions(includeUnknown = true))
        assertEquals(result.options, result.visibleOptions(true, true))
    }

    @Test fun oneSelectionReplacementAndTwoSelectionBaselineUsePrivateAdmissionOnly() {
        val i = inputs(); workloadRule(i)
        for (kinds in listOf(setOf("workload"), setOf("workload", "deployment_mode"))) {
            val (_, source) = saved(content(i, kinds))
            val request = ready(i, source, workloadSlot.copy(replacing = true))
            assertEquals(kinds.size - 1, request.baseline.node()["selections"].size())
            assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
            val localBytes = request.baseline.bytes()
            assertNotEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, localBytes.inputStream()))
            assertEquals(DiagnosticEvaluation.Rejected(DiagnosticRejection.INVALID_INPUT), FrozenDiagnosticEvaluator()
                .evaluate(localBytes.inputStream(), bytes(i.catalog).inputStream(), bytes(i.rules).inputStream()))
            assertEquals(ReportReception.Rejected(ReportRejection.INVALID_BUILD), ReferenceReportAdapter()
                .receive(localBytes.inputStream(), bytes(i.rules).inputStream(), "{}".byteInputStream()))
        }
    }

    @Test fun storedSecondaryNeverPromotesIntoAnEmptiedPrimaryAndRealConflictsRemain() {
        val i = inputs(); val bad = modelVariant(i, "cat.model.conflict", true)
        val secondary = LocalSelection.fromJson(bad.deepCopy().put("id", "sel_model_extra").put("role", "secondary"))
        val original = content(i)
        val (_, source) = saved(original.withComponents(DraftSelections(original.components.selections + secondary, original.components.links)))
        val request = ready(i, source, CompatibleOptionSlot("model", "primary", "sel_model", true), listOf(bad))
        val b = request.baseline.node()
        assertEquals(secondary.toJson(), b["selections"].single { it["id"].asText() == secondary.id })
        assertFalse(b["selections"].any { it["id"].asText() == "sel_model" })
        assertFalse(b["links"].any { it["from_selection_id"].asText() == "sel_model" || it["to_selection_id"].asText() == "sel_model" })
        val option = completed(request).options.single()
        assertEquals(CompatibleOptionVerdict.INCOMPATIBLE, option.verdict)
        assertEquals(setOf("rule.model.provider.match", "rule.api.model.access"), option.reasons.map { it.finding.ruleId }.toSet())
        assertEquals("primary", source.content.components.selections.single { it.id == "sel_model" }.role)
    }

    @Test fun secondaryReplacementPreservesTheOriginalPrimaryAndExactIncidentLinks() {
        val i = inputs(); val bad = modelVariant(i, "cat.model.extra", true).put("id", "sel_model_extra").put("role", "secondary")
        val original = content(i); val secondary = LocalSelection.fromJson(bad)
        val components = DraftSelections(original.components.selections + secondary, original.components.links)
        val (_, source) = saved(original.withComponents(components))
        val request = ready(i, source, CompatibleOptionSlot("model", "secondary", secondary.id, true), listOf(bad))
        val baseline = request.baseline.node(); val hypothetical = request.options.single().second.node()
        val primary = components.selections.single { it.id == "sel_model" }.toJson()
        assertEquals(primary, baseline["selections"].single { it["id"].asText() == "sel_model" })
        assertEquals(primary, hypothetical["selections"].single { it["id"].asText() == "sel_model" })
        assertEquals(components.links.map { it.toJson() }, hypothetical["links"].toList())
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
    }

    @Test fun absentSharedIdentityPolicyAndObjectiveValuesAreNeverSynthesized() {
        val i = inputs(); val (_, source) = saved(DraftContent(objective = "private goal", context = "private context", primaryModel = "opaque model"))
        val request = ready(i, source)
        val context = request.baseline.node()
        assertEquals(setOf("selections", "links"), context.fieldNames().asSequence().toSet())
        assertFalse(context.toString().contains("private")); assertFalse(context.toString().contains("opaque"))
        assertEquals(setOf("objective", "context", "primaryModel", "optionalTierIntent", "metadata", "history"), request.unrepresentedFieldGroups.toSet())
        assertEquals(source.version.number, request.binding.sourceRevision)
        assertEquals(source.version.hash, request.binding.sourceHash)
    }

    @Test fun enteredWorkloadValuesPreserveExactDecimalsAndMissingValuesRemainUnknown() {
        val i = inputs()
        workloadRule(i, """{"op":"eq","left":{"ref":{"scope":"build","pointer":"/objectives/monthly_budget_amount"}},"right":{"literal":"100000000000000000000.000000000001"}}""")
        val (_, entered) = saved(DraftContent(workload = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("100000000000000000000.000000000001"))))
        val request = ready(i, entered)
        assertEquals("100000000000000000000.000000000001", request.baseline.node()["objectives"]["monthly_budget_amount"].asText())
        assertFalse(request.baseline.node()["objectives"].has("currency"))
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
        val (_, absent) = saved()
        assertEquals(CompatibleOptionVerdict.UNKNOWN, completed(ready(i, absent)).options.single().verdict)
    }

    @Test fun currentCompleteTypedFindingsEqualTheExistingFrozenInterpreter() {
        val i = inputs(allRules = true)
        val b = i.build; val o = b["objectives"]
        val workload = DraftWorkload(region = o["region"].asText(), currency = o["currency"].asText(),
            monthlyBudgetAmount = o["monthly_budget_amount"]?.asText()?.let(ContractDecimal::parse),
            requestsPerDay = o["requests_per_day"]?.bigIntegerValue(), avgInputTokens = o["avg_input_tokens"]?.bigIntegerValue(),
            avgOutputTokens = o["avg_output_tokens"]?.bigIntegerValue(), maxConcurrency = o["max_concurrency"]?.bigIntegerValue(),
            maxLatencyMs = o["max_latency_ms"]?.bigIntegerValue(),
            dataClassification = DraftDataClassification.entries.single { it.encoded == o["data_classification"].asText() })
        val components = DraftSelections(b["selections"].map { LocalSelection.fromJson(it as ObjectNode) },
            b["links"].map { LocalSelectionLink.fromJson(it as ObjectNode) })
        val evaluator = FrozenDiagnosticEvaluator()
        val frozen = evaluator.evaluate(bytes(b).inputStream(), bytes(i.catalog).inputStream(), bytes(i.rules).inputStream()) as DiagnosticEvaluation.Evaluated
        val local = evaluator.evaluateDraft(DraftDiagnosticContext.create(components, workload), bytes(i.catalog), bytes(i.rules)) as DraftRuleEvaluation.Evaluated
        assertEquals(45, local.findings.size)
        assertEquals(frozen.report.inspect().findings.map { listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds) },
            local.findings.map { listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds) })
    }

    @Test fun rawFloatingCandidateOperandRejectsTheWholeBatchWithoutPartialOptions() {
        val i = inputs()
        workloadRule(i, """{"op":"eq","left":{"ref":{"scope":"selection","kind":"workload","role":"primary","pointer":"/settings/concurrency"}},"right":{"literal":2}}""")
        val other = workloadVariant(i, "cat.workload.float", 2)
        val raw = bytes(other).decodeToString().replace("\"concurrency\":2", "\"concurrency\":1e0").toByteArray()
        assertTrue(raw.decodeToString().contains("1e0"))
        val (_, source) = saved()
        val request = (prepare(i, source, candidates = listOf(bytes(selection(i)), raw)) as DraftCompatibleOptionsPreparation.Ready).request
        assertTrue(request.options[1].second.node()["selections"][0]["settings"]["concurrency"].isFloatingPointNumber)
        assertEquals(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION,
            (DraftCompatibleOptionsEvaluator().evaluate(request) as DraftCompatibleOptionsOutcome.Unavailable).reason)
    }

    @Test fun artifactHashIdentityVersionAndExistingSelectionKindMismatchRejectBeforeEvaluation() {
        val i = inputs(); val (_, source) = saved()
        val c = bytes(i.catalog); val r = pin(i.rules, true)
        for (wrong in listOf(
            DraftProjectionArtifact(i.catalog["id"].asText(), i.catalog["revision"].asText(), ArtifactHash("0".repeat(64)), c),
            DraftProjectionArtifact("catalog.other", i.catalog["revision"].asText(), hash(c), c),
            DraftProjectionArtifact(i.catalog["id"].asText(), "999", hash(c), c))) {
            assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH),
                DraftCompatibleOptionsRequest.prepare(source, wrong, r, workloadSlot, listOf(bytes(selection(i)))))
        }
        val wrongSelection = selection(i, "model").deepCopy().put("catalog_item_id", "cat.workload.fixture")
        val (_, mismatched) = saved(DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(wrongSelection)))))
        assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.BINDING_MISMATCH), prepare(i, mismatched))
    }

    @Test fun invalidSlotsDuplicateCandidatesAndRegistryCardinalityCannotBecomeImplicitAdds() {
        val i = inputs(); val (_, empty) = saved()
        for (bad in listOf(workloadSlot.copy(kind = "made_up"), workloadSlot.copy(role = "Primary"), workloadSlot.copy(selectionId = "x"),
            workloadSlot.copy(replacing = true), workloadSlot.copy(role = "x".repeat(1000))))
            assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_SLOT), prepare(i, empty, bad, emptyList()))
        assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT),
            prepare(i, empty, candidates = listOf(bytes(selection(i)), bytes(selection(i)))))
        val (_, stored) = saved(content(i, setOf("workload")))
        val other = selection(i).deepCopy().put("id", "sel_workload_extra").put("role", "secondary")
        assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT),
            prepare(i, stored, workloadSlot.copy(role = "secondary", selectionId = "sel_workload_extra"), listOf(bytes(other))))
    }

    @Test fun candidateCountWorkBudgetAndCancellationWithholdTheEntireResult() {
        val i = inputs(); val (_, source) = saved()
        assertEquals(DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.WORK_LIMIT),
            prepare(i, source, candidates = List(33) { bytes(selection(i)) }))
        val request = ready(i, source)
        assertEquals(CompatibleOptionsUnavailable.WORK_LIMIT, (DraftCompatibleOptionsEvaluator(1).evaluate(request) as DraftCompatibleOptionsOutcome.Unavailable).reason)
        Thread.currentThread().interrupt()
        try { assertEquals(CompatibleOptionsUnavailable.CANCELLED,
            (DraftCompatibleOptionsEvaluator().evaluate(request) as DraftCompatibleOptionsOutcome.Unavailable).reason) }
        finally { Thread.interrupted() }
    }

    @Test fun capturedInputsAndReturnedCollectionsCannotBeMutatedThroughCallerObjects() {
        val i = inputs(); workloadRule(i); val (_, source) = saved()
        val candidate = bytes(selection(i)); val request = (prepare(i, source, candidates = listOf(candidate)) as DraftCompatibleOptionsPreparation.Ready).request
        candidate.fill(0); (item(i.catalog, "cat.workload.fixture")["spec"] as ObjectNode).put("concurrency", 999)
        val result = completed(request)
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, result.options.single().verdict)
        assertThrows(UnsupportedOperationException::class.java) { (result.options as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.baselineFindings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.baselineFindings.first().selectionIds as MutableList).clear() }
    }

    @Test fun sameStoredHashInDifferentStoresAndChangedRuleBytesHaveDifferentBindings() {
        val i = inputs(); val (_, first) = saved(); val (_, second) = saved()
        assertEquals(first.version.hash, second.version.hash)
        val one = ready(i, first); val two = ready(i, second)
        assertNotEquals(one.binding.inputDigest, two.binding.inputDigest)
        (i.rules["rules"][0] as ObjectNode).put("message", "A separate pinned synthetic wording")
        assertNotEquals(one.binding.inputDigest, ready(i, first).binding.inputDigest)
        assertFalse(one.belongsTo(second))
    }

    @Test fun realStoredEditAndRoleChangeRetireEarlierResultsBeforeQueuedStartOrDelivery() {
        val i = inputs(); val (store, first) = saved(); val initial = ready(i, first)
        val gate = DraftCompatibleOptionsCoordinator(); gate.bind(initial); val old = gate.start(initial)!!
        val changed = (store.save(first.version, DraftContent(context = "new pending source now saved")) as DraftSave.Saved).draft
        val newer = ready(i, changed); gate.bind(newer)
        assertNull(gate.start(initial)); assertFalse(gate.complete(old, completed(initial)))
        val attempt = gate.start(newer)!!; val outcome = completed(newer)
        assertTrue(gate.complete(attempt, outcome)); assertTrue(gate.isCurrent(outcome))
        val secondary = selection(i).deepCopy().put("role", "secondary")
        val role = ready(i, changed, workloadSlot.copy(role = "secondary"), listOf(secondary))
        gate.bind(role); assertFalse(gate.isCurrent(outcome)); assertNull(gate.start(newer))
        assertTrue(gate.snapshot() is AnalysisState.Idle)
    }

    @Test fun repeatedSameByteCaptureRetryAndCancelRejectForeignAttemptAuthority() {
        val i = inputs(); val (_, source) = saved(); val first = ready(i, source); val copy = ready(i, source)
        assertEquals(first.binding, copy.binding)
        val gate = DraftCompatibleOptionsCoordinator(); gate.bind(first); val old = gate.start(first)!!
        gate.bind(copy); val second = gate.start(copy)!!
        assertFalse(gate.complete(second, completed(first))); assertFalse(gate.complete(old, completed(first)))
        val retry = gate.start(copy)!!; assertFalse(gate.complete(second, completed(copy)))
        assertTrue(gate.cancel(retry)); assertFalse(gate.complete(retry, completed(copy)))
        gate.bind(first); assertFalse(gate.complete(old, completed(first)))
        val current = gate.start(first)!!; val value = completed(first); assertTrue(gate.complete(current, value))
        gate.clear(); assertFalse(gate.isCurrent(value)); assertFalse(gate.complete(current, value))
    }

    @Test fun staleCandidateDoesNotBypassTheActualStoreCommandRevisionGuard() {
        val i = inputs(); val (store, first) = saved(); val selected = completed(ready(i, first)).options.single().selection
        val current = (store.save(first.version, DraftContent(context = "new acknowledged edit")) as DraftSave.Saved).draft
        val catalog = SelectionCatalogSnapshot.fromJson(bytes(i.catalog))
        val command = SelectionCommand.Add(selected, CatalogSelectionChoice(catalog.hash, selected.kind, selected.catalogItemId, selected.catalogItemVersion))
        val before = storeFiles()
        assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(first.version, command), catalog))
        assertEquals(before, storeFiles()); assertEquals(current.version.hash, (store.load() as DraftLoad.Available).draft.version.hash)
    }

    @Test fun nativeAdapterCapturesOnlyTheAcknowledgedRecordAndRejectsOtherRecordMembership() {
        val i = inputs(); val path = root.resolve("native-store").also(Files::createDirectories)
        val persistence = PreviewDraftPersistence(LocalDraftStore(path))
        val saved = (persistence.save(null, PreviewDraftFields(title = "acknowledged")) as PreviewDraftSave.Saved).record
        val request = (saved.prepareCompatibleOptions(pin(i.catalog), pin(i.rules, true), workloadSlot,
            listOf(bytes(selection(i)))) as DraftCompatibleOptionsPreparation.Ready).request
        assertTrue(request.belongsTo(saved)); assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(request).options.single().verdict)
        val next = (persistence.save(saved, PreviewDraftFields(title = "new acknowledged title")) as PreviewDraftSave.Saved).record
        assertFalse(request.belongsTo(next))
    }

    @Test fun realOptionalIntentWithholdsContradictionsButAllowsFillingEnabledEmptySlots() {
        val i = inputs()
        for ((tier, kind) in listOf(OptionalTier.T3 to "data_source", OptionalTier.T4 to "tool", OptionalTier.T5 to "training_framework")) {
            val proposed = selection(i, kind)
            val slot = CompatibleOptionSlot(kind, proposed["role"].asText(), proposed["id"].asText(), false)
            val (_, unknown) = saved(DraftContent().withTier(tier, TierDraft(Activation.UNKNOWN)))
            val (_, disabled) = saved()
            val (_, contradictory) = saved(DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(proposed)))))
            val (_, inactive) = saved(DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(proposed.deepCopy().put("enabled", false))))))
            val (_, enabledEmpty) = saved(DraftContent().withTier(tier, TierDraft(Activation.ENABLED)))
            val before = storeFiles()
            for (result in listOf(prepare(i, unknown), prepare(i, disabled, slot, listOf(bytes(proposed))), prepare(i, contradictory))) {
                assertEquals(CompatibleOptionsUnavailable.OPTIONAL_INTENT_UNRESOLVED,
                    (result as DraftCompatibleOptionsPreparation.Unavailable).reason)
            }
            assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(ready(i, inactive)).options.single().verdict)
            val allowed = ready(i, enabledEmpty, slot, listOf(proposed))
            assertEquals(0, allowed.baseline.node()["selections"].size())
            assertEquals(CompatibleOptionVerdict.COMPATIBLE, completed(allowed).options.single().verdict)
            assertEquals(Activation.ENABLED, enabledEmpty.content.optional.getValue(tier).activation)
            assertTrue(enabledEmpty.content.components.selections.isEmpty())
            assertEquals(before, storeFiles(), "Availability checks neither activate tiers nor acknowledge a selection")
        }
    }

    @Test fun typedNativeBridgeKeepsExactArtifactPinsAndRawFloatingSelectionSemantics() {
        val i = inputs()
        workloadRule(i, """{"op":"eq","left":{"ref":{"scope":"selection","kind":"workload","role":"primary","pointer":"/settings/concurrency"}},"right":{"literal":2}}""")
        val catalogBytes = bytes(i.catalog); val rulesBytes = bytes(i.rules)
        val c = requireNotNull(PreviewCompatibleOptionInputs.catalog(catalogBytes))
        val r = requireNotNull(PreviewCompatibleOptionInputs.ruleset(rulesBytes))
        catalogBytes.fill(0); rulesBytes.fill(0)
        assertEquals(pin(i.catalog).expectedHash, c.expectedHash)
        assertEquals(pin(i.rules, true).expectedHash, r.expectedHash)
        assertNull(PreviewCompatibleOptionInputs.catalog(bytes(i.rules)))
        assertNull(PreviewCompatibleOptionInputs.ruleset(bytes(i.catalog)))
        val path = root.resolve("native-float").also(Files::createDirectories)
        val native = PreviewDraftPersistence(LocalDraftStore(path))
        val source = (native.save(null, PreviewDraftFields(title = "typed capture")) as PreviewDraftSave.Saved).record
        val raw = bytes(selection(i)).decodeToString().replace("\"concurrency\":2", "\"concurrency\":1e0").toByteArray()
        val captured = LocalSelection.fromJson(raw)
        assertTrue(captured.settings["concurrency"].isFloatingPointNumber)
        val request = (source.prepareCompatibleSelections(c, r, workloadSlot, listOf(captured)) as DraftCompatibleOptionsPreparation.Ready).request
        assertTrue(request.options.single().second.node()["selections"][0]["settings"]["concurrency"].isFloatingPointNumber)
        assertEquals(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION,
            (DraftCompatibleOptionsEvaluator().evaluate(request) as DraftCompatibleOptionsOutcome.Unavailable).reason)
        val item = PreviewCatalogItem(captured.catalogItemId, "typed fixture", captured.kind, captured.catalogItemVersion, "T0")
        assertEquals(captured.toJson(), requireNotNull(prepareCompatibleReplacement(captured, item)).toJson())
        assertNull(prepareCompatibleReplacement(captured, item.copy(kind = "model")))
    }
}
