package ai.pickyour.core.evaluation

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.drafts.DraftProjectionArtifact
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.DraftSelections
import ai.pickyour.core.drafts.DraftWorkload
import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.drafts.StoredDraft
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Local rule observations. These values are not an admitted frozen EvaluationReport. */
class DraftRuleFinding internal constructor(
    val ruleId: String, val outcome: String, val severity: String, val code: String,
    val message: String, val remediation: String, val selectionIds: List<String>,
)

internal sealed interface DraftRuleEvaluation {
    class Evaluated(val findings: List<DraftRuleFinding>) : DraftRuleEvaluation
    data class Rejected(val reason: DiagnosticRejection) : DraftRuleEvaluation
}

internal sealed interface DraftCurrentContextCapture {
    class Ready(val context: DraftDiagnosticContext, val selection: LocalSelection?) : DraftCurrentContextCapture
    data class Unavailable(val reason: CompatibleOptionsUnavailable) : DraftCurrentContextCapture
}

/**
 * Only known typed editing fields. Empty selections/links are known empty; unanswered objectives
 * stay absent. No shared Build ID, revision, owner, lifecycle, policy, locks or timestamp is invented.
 * The original private draft and its unrepresented free text/optional intent remain with its owner.
 */
internal class DraftDiagnosticContext private constructor(private val json: String) {
    internal fun node(): ObjectNode = (draftReader.read(json.byteInputStream()) as JsonInputResult.Parsed).value
    internal fun bytes(): ByteArray = json.toByteArray()

    companion object {
        fun create(selections: DraftSelections, workload: DraftWorkload): DraftDiagnosticContext {
            val value = JsonNodeFactory.instance.objectNode()
            val objectives = JsonNodeFactory.instance.objectNode()
            workload.region?.let { objectives.put("region", it) }
            workload.currency?.let { objectives.put("currency", it) }
            workload.dataClassification?.let { objectives.put("data_classification", it.encoded) }
            workload.monthlyBudgetAmount?.let { objectives.put("monthly_budget_amount", it.encoded) }
            listOf("requests_per_day" to workload.requestsPerDay, "avg_input_tokens" to workload.avgInputTokens,
                "avg_output_tokens" to workload.avgOutputTokens, "max_concurrency" to workload.maxConcurrency,
                "max_latency_ms" to workload.maxLatencyMs).forEach { (field, number) -> if (number != null) objectives.put(field, number) }
            if (!objectives.isEmpty) value.set<JsonNode>("objectives", objectives)
            value.putArray("selections").also { array -> selections.selections.forEach { array.add(it.toJson()) } }
            value.putArray("links").also { array -> selections.links.forEach { array.add(it.toJson()) } }
            val json = CompatibleOptionsRequest.encodeLocalContext(value) ?: draftReject(CompatibleOptionsUnavailable.WORK_LIMIT)
            if (draftReader.read(json.byteInputStream()) !is JsonInputResult.Parsed) draftReject(CompatibleOptionsUnavailable.INVALID_INPUT)
            return DraftDiagnosticContext(json)
        }
    }
}

/** A local store counter remains local; it is never substituted for a shared Build revision. */
data class DraftCompatibleOptionsBinding(
    val sourceRevision: Long, val sourceHash: ArtifactHash,
    val catalogSnapshotId: String, val catalogRevision: BigInteger, val catalogHash: ArtifactHash,
    val rulesetId: String, val rulesetVersion: String, val rulesetHash: ArtifactHash,
    val slot: CompatibleOptionSlot, val inputDigest: String,
)

sealed interface DraftCompatibleOptionsPreparation {
    class Ready(val request: DraftCompatibleOptionsRequest) : DraftCompatibleOptionsPreparation
    data class Unavailable(val reason: CompatibleOptionsUnavailable) : DraftCompatibleOptionsPreparation
}

/**
 * Bounded read-only capture from an actual immutable stored draft and explicit artifact pins.
 * All private source bytes are covered by its storage hash, including fields not used by rules.
 * Capture/evaluation does not recheck source liveness, acknowledge a save, or authorize a command.
 */
class DraftCompatibleOptionsRequest private constructor(
    private val source: StoredDraft,
    val binding: DraftCompatibleOptionsBinding,
    private val catalog: ByteArray,
    private val ruleset: ByteArray,
    internal val baseline: DraftDiagnosticContext,
    options: List<Pair<LocalSelection, DraftDiagnosticContext>>,
    affectedRules: Set<String>,
) {
    val profile: String = PROFILE
    internal val options = fixedDraftList(options)
    internal val affectedRules: Set<String> = Collections.unmodifiableSet(LinkedHashSet(affectedRules))
    internal fun catalogBytes() = catalog.copyOf()
    internal fun rulesetBytes() = ruleset.copyOf()
    internal fun belongsTo(expected: StoredDraft): Boolean = source === expected
    /** Explicit inspection only: failure here does not change the accepted candidate verdicts. */
    internal fun captureCurrentContext(): DraftCurrentContextCapture = try {
        DraftCurrentContextCapture.Ready(DraftDiagnosticContext.create(source.content.components, source.content.workload),
            source.content.components.selections.singleOrNull { it.id == binding.slot.selectionId })
    } catch (failure: DraftPreparationFailure) { DraftCurrentContextCapture.Unavailable(failure.reason) }
    catch (_: IllegalArgumentException) { DraftCurrentContextCapture.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT) }
    /** Field groups retained only in the private source, never silently interpreted as v1 policy. */
    val unrepresentedFieldGroups: List<String> = UNREPRESENTED_FIELDS
    override fun toString() = "DraftCompatibleOptionsRequest"

    companion object {
        const val PROFILE = "local-stored-draft-slot-empty-diagnostics/1"
        private val UNREPRESENTED_FIELDS = fixedDraftList(listOf(
            "objective", "context", "primaryModel", "optionalTierIntent", "metadata", "history",
        ))
        private val validator = FrozenSchemaValidator()
        private val optionalTierByKind: Map<String, OptionalTier> by lazy {
            requireNotNull(DraftCompatibleOptionsRequest::class.java.getResourceAsStream("/contracts/v1/schemas/component-registry.json")).use {
                val kinds = (draftReader.read(it) as JsonInputResult.Parsed).value["kinds"]
                kinds.properties().mapNotNull { (kind, definition) ->
                    OptionalTier.entries.firstOrNull { tier -> tier.name == definition["tier"].asText() }?.let { tier -> kind to tier }
                }.toMap()
            }
        }

        /** Exact candidate Selection bytes supply all settings, identity, role and enabled state. */
        fun prepare(
            source: StoredDraft, catalog: DraftProjectionArtifact, ruleset: DraftProjectionArtifact,
            slot: CompatibleOptionSlot, candidates: List<ByteArray>,
        ): DraftCompatibleOptionsPreparation = try {
            if (!CompatibleOptionsRequest.validSlot(slot)) draftReject(CompatibleOptionsUnavailable.INVALID_SLOT)
            if (candidates.size > CompatibleOptionsRequest.MAX_CANDIDATES) draftReject(CompatibleOptionsUnavailable.WORK_LIMIT)
            fun capture(bytes: ByteArray): ByteArray {
                if (bytes.size > DRAFT_MAX_BYTES) draftReject(CompatibleOptionsUnavailable.WORK_LIMIT)
                return bytes.copyOf()
            }
            val catalogBytes = capture(catalog.bytes()); val rulesBytes = capture(ruleset.bytes())
            val candidateBytes = candidates.map(::capture)
            val c = readArtifact(FrozenDocument.CATALOG, catalog, catalogBytes)
            val r = readArtifact(FrozenDocument.RULESET, ruleset, rulesBytes)
            if (c["schema_version"] != r["schema_version"]) draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            val items = c["items"].associateBy { it["id"].asText() }
            if (items.size != c["items"].size() || r["rules"].map { it["id"].asText() }.distinct().size != r["rules"].size())
                draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            val content = source.content
            val selections = content.components.selections
            // Availability only: never silently activate disabled intent or turn unknown into pass.
            // ENABLED-but-empty stays inspectable so the caller can fill that exact optional slot.
            if (content.optional.values.any { it.activation == Activation.UNKNOWN })
                draftReject(CompatibleOptionsUnavailable.OPTIONAL_INTENT_UNRESOLVED)
            fun requireEnabledTier(selection: LocalSelection) {
                val tier = optionalTierByKind[selection.kind] ?: return
                if (selection.enabled && content.optional.getValue(tier).activation == Activation.DISABLED)
                    draftReject(CompatibleOptionsUnavailable.OPTIONAL_INTENT_UNRESOLVED)
            }
            for (selection in selections) {
                if (!selection.enabled) continue
                requireEnabledTier(selection)
                val item = items[selection.catalogItemId] ?: continue // Missing original evidence remains unknown where reached.
                if (item["kind"].asText() != selection.kind || item["version"].asText() != selection.catalogItemVersion)
                    draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            }
            val old = selections.singleOrNull { it.id == slot.selectionId }
            if (slot.replacing != (old != null) ||
                old != null && (old.kind != slot.kind || old.role != slot.role) ||
                selections.any { it !== old && it.kind == slot.kind && it.role == slot.role })
                draftReject(CompatibleOptionsUnavailable.INVALID_SLOT)
            val links = content.components.links
            val empty = DraftSelections(selections.filter { it !== old }, if (old == null) links else links.filter {
                it.fromSelectionId != old.id && it.toSelectionId != old.id
            })
            val baseline = DraftDiagnosticContext.create(empty, content.workload)
            val identities = mutableSetOf<CompatibleOptionIdentity>()
            val options = candidateBytes.map { bytes ->
                val candidate = LocalSelection.fromJson(bytes)
                if (candidate.id != slot.selectionId || candidate.kind != slot.kind || candidate.role != slot.role || !candidate.enabled)
                    draftReject(CompatibleOptionsUnavailable.INVALID_SLOT)
                requireEnabledTier(candidate)
                val item = items[candidate.catalogItemId] ?: draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
                if (item["kind"].asText() != candidate.kind || item["version"].asText() != candidate.catalogItemVersion)
                    draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
                if (!identities.add(CompatibleOptionIdentity(candidate.catalogItemId, candidate.catalogItemVersion)))
                    draftReject(CompatibleOptionsUnavailable.INVALID_INPUT)
                val replaced = if (old == null) selections + candidate else selections.map { if (it === old) candidate else it }
                candidate to DraftDiagnosticContext.create(DraftSelections(replaced, links), content.workload)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            fun frame(bytes: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
            listOf(PROFILE, source.version.store.toString(), source.version.number.toString(), source.version.hash.hex,
                source.localFormatVersion.toString(), catalog.expectedId, catalog.expectedVersion, catalog.expectedHash.hex,
                ruleset.expectedId, ruleset.expectedVersion, ruleset.expectedHash.hex,
                slot.kind, slot.role, slot.selectionId, slot.replacing.toString()).forEach { frame(it.toByteArray()) }
            frame(baseline.bytes()); frame(catalogBytes); frame(rulesBytes); candidateBytes.forEach(::frame)
            val binding = DraftCompatibleOptionsBinding(source.version.number, source.version.hash,
                c["id"].asText(), c["revision"].bigIntegerValue(), catalog.expectedHash,
                r["id"].asText(), r["version"].asText(), ruleset.expectedHash, slot,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            val affected = r["rules"].filter { rule -> rule["affected_kinds"].any { it.asText() == slot.kind } }
                .map { it["id"].asText() }.toSet()
            DraftCompatibleOptionsPreparation.Ready(DraftCompatibleOptionsRequest(source, binding, catalogBytes, rulesBytes, baseline, options, affected))
        } catch (failure: DraftPreparationFailure) { DraftCompatibleOptionsPreparation.Unavailable(failure.reason) }
        catch (_: IllegalArgumentException) { DraftCompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT) }

        private fun readArtifact(document: FrozenDocument, pin: DraftProjectionArtifact, bytes: ByteArray): ObjectNode {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (hash != pin.expectedHash.hex) draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            val value = (draftReader.read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value
                ?: draftReject(CompatibleOptionsUnavailable.INVALID_INPUT)
            if (validator.validate(document, bytes.inputStream()) != FrozenValidation.Valid) draftReject(CompatibleOptionsUnavailable.INVALID_INPUT)
            if (value["id"].asText() != pin.expectedId) draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            val version = if (document == FrozenDocument.CATALOG) {
                if (!value["revision"].isIntegralNumber) draftReject(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION)
                value["revision"].bigIntegerValue().toString()
            } else value["version"].asText()
            if (version != pin.expectedVersion) draftReject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            return value
        }
    }
}

data class DraftCompatibleOptionReason(val baselineOutcome: String, val finding: DraftRuleFinding)
class DraftCompatibleOption internal constructor(
    val selection: LocalSelection, val verdict: CompatibleOptionVerdict, reasons: List<DraftCompatibleOptionReason>,
) {
    val identity = CompatibleOptionIdentity(selection.catalogItemId, selection.catalogItemVersion)
    val reasons: List<DraftCompatibleOptionReason> = fixedDraftList(reasons)
    override fun toString() = "DraftCompatibleOption($verdict)"
}

sealed class DraftCompatibleOptionsOutcome protected constructor(private val request: DraftCompatibleOptionsRequest) {
    val binding: DraftCompatibleOptionsBinding get() = request.binding
    fun belongsTo(expected: DraftCompatibleOptionsRequest): Boolean = request === expected
    class Completed internal constructor(request: DraftCompatibleOptionsRequest, options: List<DraftCompatibleOption>,
        baselineFindings: List<DraftRuleFinding>) : DraftCompatibleOptionsOutcome(request) {
        val options: List<DraftCompatibleOption> = fixedDraftList(options)
        /** Inherited failures/unknowns remain inspectable; a compatible option never means a valid whole draft. */
        val baselineFindings: List<DraftRuleFinding> = fixedDraftList(baselineFindings)
        val compatibleCount get() = options.count { it.verdict == CompatibleOptionVerdict.COMPATIBLE }
        val incompatibleCount get() = options.count { it.verdict == CompatibleOptionVerdict.INCOMPATIBLE }
        val unknownCount get() = options.count { it.verdict == CompatibleOptionVerdict.UNKNOWN }
        fun visibleOptions(includeIncompatible: Boolean = false, includeUnknown: Boolean = false): List<DraftCompatibleOption> =
            fixedDraftList(options.filter { it.verdict == CompatibleOptionVerdict.COMPATIBLE ||
                (includeIncompatible && it.verdict == CompatibleOptionVerdict.INCOMPATIBLE) ||
                (includeUnknown && it.verdict == CompatibleOptionVerdict.UNKNOWN) })
    }
    class Unavailable internal constructor(request: DraftCompatibleOptionsRequest, val reason: CompatibleOptionsUnavailable) : DraftCompatibleOptionsOutcome(request)
}

/** Same reference rule operations, at most 33 bounded calls. No Build, archive, external metrics or write. */
class DraftCompatibleOptionsEvaluator(maxWorkPerEvaluation: Int = 250_000) {
    private val evaluator = FrozenDiagnosticEvaluator(maxWorkPerEvaluation)
    fun evaluate(request: DraftCompatibleOptionsRequest): DraftCompatibleOptionsOutcome {
        fun rejected(reason: CompatibleOptionsUnavailable) = DraftCompatibleOptionsOutcome.Unavailable(request, reason)
        fun run(context: DraftDiagnosticContext) = evaluator.evaluateDraft(context, request.catalogBytes(), request.rulesetBytes())
        if (Thread.currentThread().isInterrupted) return rejected(CompatibleOptionsUnavailable.CANCELLED)
        val baselineResult = run(request.baseline)
        if (baselineResult is DraftRuleEvaluation.Rejected) return rejected(CompatibleOptionsUnavailable.valueOf(baselineResult.reason.name))
        val baselineFindings = (baselineResult as DraftRuleEvaluation.Evaluated).findings
        val baseline = baselineFindings.associateBy { it.ruleId }
        fun rank(outcome: String) = when (outcome) { "pass", "skipped" -> 0; "unknown" -> 1; "fail" -> 2; else -> -1 }
        val options = mutableListOf<DraftCompatibleOption>()
        for ((selection, context) in request.options) {
            if (Thread.currentThread().isInterrupted) return rejected(CompatibleOptionsUnavailable.CANCELLED)
            val evaluated = run(context)
            if (evaluated is DraftRuleEvaluation.Rejected) return rejected(CompatibleOptionsUnavailable.valueOf(evaluated.reason.name))
            val reasons = (evaluated as DraftRuleEvaluation.Evaluated).findings.filter { it.ruleId in request.affectedRules }.mapNotNull { finding ->
                val before = baseline[finding.ruleId] ?: return rejected(CompatibleOptionsUnavailable.INVALID_OUTPUT)
                if (rank(finding.outcome) > rank(before.outcome)) DraftCompatibleOptionReason(before.outcome, finding) else null
            }
            val verdict = when {
                reasons.any { it.finding.outcome == "fail" } -> CompatibleOptionVerdict.INCOMPATIBLE
                reasons.any { it.finding.outcome == "unknown" } -> CompatibleOptionVerdict.UNKNOWN
                else -> CompatibleOptionVerdict.COMPATIBLE
            }
            options.add(DraftCompatibleOption(selection, verdict, reasons))
        }
        if (Thread.currentThread().isInterrupted) return rejected(CompatibleOptionsUnavailable.CANCELLED)
        return DraftCompatibleOptionsOutcome.Completed(request, options, baselineFindings)
    }
}

private const val DRAFT_MAX_BYTES = 262_144
private val draftReader = BoundedJsonReader(JsonInputLimits(maxBytes = DRAFT_MAX_BYTES, maxTokens = 20_000, maxStringLength = 4096))
private class DraftPreparationFailure(val reason: CompatibleOptionsUnavailable) : RuntimeException()
private fun draftReject(reason: CompatibleOptionsUnavailable): Nothing = throw DraftPreparationFailure(reason)
private fun <T> fixedDraftList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
