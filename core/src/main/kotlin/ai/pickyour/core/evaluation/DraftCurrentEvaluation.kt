package ai.pickyour.core.evaluation

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.DependencyGraph
import ai.pickyour.core.contracts.DependencyGraphDecoder
import ai.pickyour.core.contracts.DependencyGraphDecoding
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.drafts.StoredDraft
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.invalidation.DependencyInvalidation
import ai.pickyour.core.invalidation.DeploymentMode
import ai.pickyour.core.invalidation.InvalidationContext
import ai.pickyour.core.invalidation.InvalidationSelection
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Immutable supplied inputs. Schema validity and byte identity do not assert publisher authority. */
class DraftCurrentInputs private constructor(
    private val catalogBytes: ByteArray,
    private val rulesBytes: ByteArray,
    private val catalog: ObjectNode,
    val graph: DependencyGraph,
    val catalogId: String, val catalogRevision: BigInteger, val catalogHash: ArtifactHash,
    val rulesetId: String, val rulesetVersion: String, val rulesetHash: ArtifactHash,
    val graphHash: ArtifactHash,
) {
    internal fun catalogBytes() = catalogBytes.copyOf()
    internal fun rulesBytes() = rulesBytes.copyOf()
    internal fun samePins(other: DraftCurrentInputs) = catalogHash == other.catalogHash &&
        graphHash == other.graphHash && rulesetHash == other.rulesetHash

    /** Each old/new context uses its own catalog. Missing/ambiguous values remain unknown. */
    internal fun context(source: StoredDraft): InvalidationContext {
        val selected = source.content.components.selections
        val modeSelection = selected.filter { it.enabled && it.kind == "deployment_mode" && it.role == "primary" }.singleOrNull()
        val item = modeSelection?.let { selection -> catalog["items"].singleOrNull {
            it["id"].asText() == selection.catalogItemId && it["version"].asText() == selection.catalogItemVersion &&
                it["kind"].asText() == selection.kind
        } }
        val mode = when (item?.path("spec")?.path("mode")?.asText()) {
            "api" -> DeploymentMode.API
            "managed" -> DeploymentMode.MANAGED
            "self_hosted" -> DeploymentMode.SELF_HOSTED
            "hybrid" -> DeploymentMode.HYBRID
            else -> null
        }
        val target = selected.filter { it.enabled && it.kind == "deployment_target" && it.role == "primary" }.singleOrNull()
        val production = when (target?.settings?.path("environment")?.asText()) {
            "production" -> true
            "development", "staging" -> false
            else -> null
        }
        return InvalidationContext(selected.map { InvalidationSelection(it.id, it.kind, it.enabled) }, mode, production)
    }

    override fun toString() = "DraftCurrentInputs"

    companion object {
        const val MAX_BYTES = 262_144
        /** Caller-owned bytes are copied before any parser or callback consumes them. */
        fun read(catalog: ByteArray, graph: ByteArray, ruleset: ByteArray): DraftCurrentInputs? {
            if (listOf(catalog, graph, ruleset).any { it.size > MAX_BYTES }) return null
            val cBytes = catalog.copyOf(); val gBytes = graph.copyOf(); val rBytes = ruleset.copyOf()
            val reader = currentReader()
            val validator = FrozenSchemaValidator()
            fun read(bytes: ByteArray, kind: FrozenDocument): ObjectNode? {
                val value = (reader.read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value ?: return null
                return value.takeIf { validator.validate(kind, bytes.inputStream()) == FrozenValidation.Valid }
            }
            val c = read(cBytes, FrozenDocument.CATALOG) ?: return null
            val r = read(rBytes, FrozenDocument.RULESET) ?: return null
            // Keep the same local evaluator revision profile; no frozen numeric policy is changed.
            if (!c["revision"].isIntegralNumber || c["schema_version"] != r["schema_version"] ||
                c["items"].map { it["id"].asText() }.distinct().size != c["items"].size() ||
                r["rules"].map { it["id"].asText() }.distinct().size != r["rules"].size()) return null
            if (reader.read(gBytes.inputStream()) !is JsonInputResult.Parsed) return null
            val g = (DependencyGraphDecoder().decode(gBytes.inputStream()) as? DependencyGraphDecoding.Decoded)?.graph ?: return null
            if (g.schemaVersion != c["schema_version"].asText()) return null
            return DraftCurrentInputs(cBytes, rBytes, c, g, c["id"].asText(), c["revision"].bigIntegerValue(),
                currentHash(cBytes), r["id"].asText(), r["version"].asText(), currentHash(rBytes), currentHash(gBytes))
        }
    }
}

data class DraftCurrentEvaluationBinding(
    val sourceRevision: Long, val sourceHash: ArtifactHash,
    val catalogId: String, val catalogRevision: BigInteger, val catalogHash: ArtifactHash,
    val graphId: String, val graphRevision: BigInteger, val graphHash: ArtifactHash,
    val rulesetId: String, val rulesetVersion: String, val rulesetHash: ArtifactHash,
    val inputDigest: String,
)

enum class DraftRecheckScope { DIRECT_DEPENDENCIES, CONSERVATIVE_FULL }

/** The helper's direct set is explanatory; every admitted attempt runs the complete bounded rule loop. */
class DraftCurrentRecheck internal constructor(
    val scope: DraftRecheckScope,
    changedKinds: List<String>, affectedKinds: List<String>, selectionIds: List<String>, edgeIds: List<String>,
    val before: DraftCurrentEvaluationBinding?, val after: DraftCurrentEvaluationBinding?,
) {
    val changedKinds = currentList(changedKinds)
    val affectedKinds = currentList(affectedKinds)
    val selectionIds = currentList(selectionIds)
    val edgeIds = currentList(edgeIds)
    override fun toString() = "DraftCurrentRecheck($scope)"
}

enum class DraftCurrentUnavailable { INPUT_UNAVAILABLE, OPTIONAL_INTENT_UNRESOLVED, INVALID_INPUT, BINDING_MISMATCH,
    WORK_LIMIT, UNSUPPORTED_OPERATION, INVALID_OUTPUT }

/** Opaque read-only request for an actual private stored source; never a frozen Build or report. */
class DraftCurrentEvaluation internal constructor(
    internal val source: StoredDraft, internal val inputs: DraftCurrentInputs,
    val recheck: DraftCurrentRecheck,
) {
    val binding = binding(source, inputs)
    val profile = "local-stored-draft-current-diagnostics/1"
    val unrepresentedFieldGroups = currentList(listOf("objective", "context", "primaryModel", "optionalTierIntent", "metadata", "history"))
    internal fun matches(other: StoredDraft) = source.version.store == other.version.store &&
        source.version.number == other.version.number && source.version.hash == other.version.hash &&
        source.localFormatVersion == other.localFormatVersion
    override fun toString() = "DraftCurrentEvaluation"

    companion object {
        internal fun binding(source: StoredDraft, inputs: DraftCurrentInputs): DraftCurrentEvaluationBinding {
            val digest = MessageDigest.getInstance("SHA-256")
            // Store identity participates privately; a path or private value is never rendered/logged.
            listOf("local-stored-draft-current-diagnostics/1", source.version.store.toString(),
                source.version.number.toString(), source.version.hash.hex, source.localFormatVersion.toString(),
                inputs.catalogHash.hex, inputs.graphHash.hex, inputs.rulesetHash.hex).forEach { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            return DraftCurrentEvaluationBinding(source.version.number, source.version.hash,
                inputs.catalogId, inputs.catalogRevision, inputs.catalogHash, inputs.graph.id, inputs.graph.revision, inputs.graphHash,
                inputs.rulesetId, inputs.rulesetVersion, inputs.rulesetHash, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }

        internal fun plan(previous: StoredDraft?, previousInputs: DraftCurrentInputs?, after: StoredDraft, inputs: DraftCurrentInputs?): DraftCurrentRecheck {
            // Library/new-draft switching starts a new basis, not an edit of the previous draft.
            val before = previous?.takeIf { it.version.store == after.version.store }
            val oldInputs = previousInputs.takeIf { before != null }
            val oldSelections = before?.content?.components?.selections.orEmpty()
            val newSelections = after.content.components.selections
            val oldById = oldSelections.associateBy { it.id }; val newById = newSelections.associateBy { it.id }
            val changed = mutableSetOf<String>()
            for (id in oldById.keys + newById.keys) if (oldById[id] != newById[id]) {
                oldById[id]?.kind?.let(changed::add); newById[id]?.kind?.let(changed::add)
            }
            if (before?.content?.components?.links != after.content.components.links) {
                val endpointIds = (before?.content?.components?.links.orEmpty() + after.content.components.links)
                    .flatMap { listOf(it.fromSelectionId, it.toSelectionId) }.toSet()
                (oldSelections + newSelections).filter { it.id in endpointIds }.forEach { changed += it.kind }
            }
            for (tier in OptionalTier.entries) if (before?.content?.optional?.get(tier) != after.content.optional[tier])
                changed += currentOptionalKinds.filterValues { it == tier }.keys
            if (before?.content?.workload != after.content.workload) changed += "workload"
            val full = before == null || before.version.store != after.version.store || oldInputs == null || inputs == null ||
                !oldInputs.samePins(inputs) || optionalUnresolved(before) || optionalUnresolved(after) ||
                before.content.workload != after.content.workload || before.content.objective != after.content.objective ||
                before.content.context != after.content.context || before.content.primaryModel != after.content.primaryModel ||
                before.metadata != after.metadata || changed.isEmpty()
            val priorBinding = if (before != null && oldInputs != null) binding(before, oldInputs) else null
            val nextBinding = inputs?.let { binding(after, it) }
            if (full) {
                // Graph replacement includes removed edges from its original bound predecessor.
                val edges = oldInputs?.graph?.edges.orEmpty() + inputs?.graph?.edges.orEmpty()
                val kinds = (oldSelections + newSelections).map { it.kind }.toSet() +
                    edges.flatMap { listOf(it.dependentKind, it.dependsOnKind) } + changed
                return DraftCurrentRecheck(DraftRecheckScope.CONSERVATIVE_FULL, changed.sorted(), (kinds - changed).sorted(),
                    (oldSelections + newSelections).map { it.id }.distinct().sorted(), edges.map { it.id }.distinct().sorted(), priorBinding, nextBinding)
            }
            val result = DependencyInvalidation.affectedByChange(requireNotNull(inputs).graph, changed,
                requireNotNull(oldInputs).context(requireNotNull(before)), inputs.context(after))
            return DraftCurrentRecheck(DraftRecheckScope.DIRECT_DEPENDENCIES, result.changedKinds, result.affectedKinds,
                result.selectionIdsToRecheck, result.viaEdgeIds, priorBinding, nextBinding)
        }
    }
}

sealed class DraftCurrentOutcome private constructor(private val request: DraftCurrentEvaluation) {
    val binding get() = request.binding
    fun belongsTo(expected: DraftCurrentEvaluation) = request === expected
    class Completed internal constructor(request: DraftCurrentEvaluation, findings: List<DraftRuleFinding>) : DraftCurrentOutcome(request) {
        val findings = currentList(findings.map { DraftRuleFinding(it.ruleId, it.outcome, it.severity, it.code,
            it.message, it.remediation, currentList(it.selectionIds)) })
    }
    class Unavailable internal constructor(request: DraftCurrentEvaluation, val reason: DraftCurrentUnavailable) : DraftCurrentOutcome(request)
}

/** Uses the existing private rule entry point exactly once. No graph-pruned verdict or external metric override. */
class DraftCurrentEvaluator {
    fun evaluate(request: DraftCurrentEvaluation): DraftCurrentOutcome {
        fun unavailable(reason: DraftCurrentUnavailable) = DraftCurrentOutcome.Unavailable(request, reason)
        if (optionalUnresolved(request.source)) return unavailable(DraftCurrentUnavailable.OPTIONAL_INTENT_UNRESOLVED)
        return try {
            val context = DraftDiagnosticContext.create(request.source.content.components, request.source.content.workload)
            when (val result = FrozenDiagnosticEvaluator().evaluateDraft(context, request.inputs.catalogBytes(), request.inputs.rulesBytes())) {
                is DraftRuleEvaluation.Evaluated -> DraftCurrentOutcome.Completed(request, result.findings)
                is DraftRuleEvaluation.Rejected -> unavailable(DraftCurrentUnavailable.valueOf(result.reason.name))
            }
        } catch (_: RuntimeException) { unavailable(DraftCurrentUnavailable.INVALID_INPUT) }
    }
}

private fun optionalUnresolved(source: StoredDraft) = source.content.optional.values.any { it.activation == Activation.UNKNOWN } ||
    source.content.components.selections.any { selection -> selection.enabled && currentOptionalKinds[selection.kind]?.let {
        source.content.optional.getValue(it).activation == Activation.DISABLED
    } == true }

private val currentOptionalKinds: Map<String, OptionalTier> by lazy {
    requireNotNull(DraftCurrentInputs::class.java.getResourceAsStream("/contracts/v1/schemas/component-registry.json")).use { stream ->
        val kinds = (BoundedJsonReader().read(stream) as JsonInputResult.Parsed).value["kinds"]
        kinds.properties().mapNotNull { (kind, definition) ->
            OptionalTier.entries.firstOrNull { it.name == definition["tier"].asText() }?.let { kind to it }
        }.toMap()
    }
}
private fun currentReader() = BoundedJsonReader(JsonInputLimits(maxBytes = DraftCurrentInputs.MAX_BYTES, maxTokens = 20_000, maxStringLength = 4096))
private fun currentHash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
private fun <T> currentList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
