package ai.pickyour.core.evaluation

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
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

/** Exact local editing intent. Replacing a missing selection is not an implicit add. */
data class CompatibleOptionSlot(val kind: String, val role: String, val selectionId: String, val replacing: Boolean)
enum class CompatibleOptionVerdict { COMPATIBLE, INCOMPATIBLE, UNKNOWN }
enum class CompatibleOptionsUnavailable {
    INVALID_INPUT, BINDING_MISMATCH, INVALID_SLOT, BASELINE_UNAVAILABLE,
    WORK_LIMIT, UNSUPPORTED_OPERATION, INVALID_OUTPUT, CANCELLED, OPTIONAL_INTENT_UNRESOLVED,
}
data class CompatibleOptionsBinding(
    val buildId: String, val buildRevision: BigInteger, val catalogSnapshotId: String, val catalogRevision: BigInteger,
    val rulesetId: String, val rulesetVersion: String, val dependencyGraphId: String,
    val dependencyGraphRevision: BigInteger, val pricebookId: String?,
    val slot: CompatibleOptionSlot, val inputDigest: String,
)
data class CompatibleOptionIdentity(val catalogItemId: String, val catalogItemVersion: String)
data class CompatibleOptionReason(val baselineOutcome: String, val finding: ReferenceFindingView)
class CompatibleOption internal constructor(
    val identity: CompatibleOptionIdentity,
    val verdict: CompatibleOptionVerdict,
    reasons: List<CompatibleOptionReason>,
) {
    val reasons: List<CompatibleOptionReason> = Collections.unmodifiableList(reasons.toList())
    override fun toString(): String = "CompatibleOption($verdict)"
}

sealed interface CompatibleOptionsPreparation {
    data class Ready(val request: CompatibleOptionsRequest) : CompatibleOptionsPreparation
    data class Unavailable(val reason: CompatibleOptionsUnavailable) : CompatibleOptionsPreparation
}

/**
 * Private client API, not a new v1 wire document or an archived report. The digest binds original
 * bytes, candidate settings/order and explicit slot intent. No caller-owned mutable values escape.
 */
class CompatibleOptionsRequest private constructor(
    val binding: CompatibleOptionsBinding,
    private val catalog: ByteArray,
    private val ruleset: ByteArray,
    internal val baseline: String,
    options: List<Pair<CompatibleOptionIdentity, String>>,
    rules: Set<String>,
) {
    internal val options = Collections.unmodifiableList(options.toList())
    internal val affectedRules: Set<String> = Collections.unmodifiableSet(rules.toSet())
    internal fun catalogBytes(): ByteArray = catalog.copyOf()
    internal fun rulesetBytes(): ByteArray = ruleset.copyOf()
    override fun toString(): String = "CompatibleOptionsRequest"

    companion object {
        const val PROFILE = "local-slot-empty-frozen-diagnostics/1"
        const val MAX_CANDIDATES = 32
        private const val MAX_BYTES = 262_144
        private val reader = BoundedJsonReader(JsonInputLimits(maxBytes = MAX_BYTES, maxTokens = 20_000, maxStringLength = 4096))
        private val validator = FrozenSchemaValidator()
        private fun bundled(name: String): ObjectNode = requireNotNull(
            CompatibleOptionsRequest::class.java.getResourceAsStream("/contracts/v1/schemas/$name"),
        ).use { (reader.read(it) as JsonInputResult.Parsed).value }
        private val slotSchema = bundled("selection.schema.json")["properties"]
        private val kinds = slotSchema["kind"]["enum"].map { it.asText() }.toSet()
        private val rolePattern = Regex(slotSchema["role"]["pattern"].asText())
        private val idPattern = Regex(bundled("common.schema.json")["\$defs"]["selection_id"]["pattern"].asText())

        internal fun validSlot(slot: CompatibleOptionSlot): Boolean =
            slot.kind.length <= 80 && slot.role.length <= 80 && slot.selectionId.length <= 80 &&
                slot.kind in kinds && rolePattern.matches(slot.role) && idPattern.matches(slot.selectionId)

        /** Same bounded numeric-type-preserving transport for the separate private draft caller. */
        internal fun encodeLocalContext(node: JsonNode): String? = try { encode(node) }
            catch (_: PreparationFailure) { null }

        /** Candidates are complete Selection documents: no guessed settings, defaults or roles. */
        fun prepare(
            build: ByteArray, catalog: ByteArray, ruleset: ByteArray,
            slot: CompatibleOptionSlot, candidates: List<ByteArray>,
        ): CompatibleOptionsPreparation = try {
            if (!validSlot(slot))
                reject(CompatibleOptionsUnavailable.INVALID_SLOT)
            if (candidates.size > MAX_CANDIDATES) reject(CompatibleOptionsUnavailable.WORK_LIMIT)
            fun capture(bytes: ByteArray): ByteArray {
                if (bytes.size > MAX_BYTES) reject(CompatibleOptionsUnavailable.WORK_LIMIT)
                return bytes.copyOf()
            }
            val bBytes = capture(build); val cBytes = capture(catalog); val rBytes = capture(ruleset)
            val candidateBytes = candidates.map(::capture)
            val b = parse(FrozenDocument.BUILD, bBytes)
            val c = parse(FrozenDocument.CATALOG, cBytes)
            val r = parse(FrozenDocument.RULESET, rBytes)
            val locks = b["locks"]
            if (!b["revision"].isIntegralNumber || !c["revision"].isIntegralNumber || !locks["dependency_graph_revision"].isIntegralNumber)
                reject(CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION)
            if (c["id"] != locks["catalog_snapshot_id"] || r["id"] != locks["ruleset_id"] ||
                r["version"] != locks["ruleset_version"] || c["schema_version"] != b["schema_version"] ||
                r["schema_version"] != b["schema_version"])
                reject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            val selections = b["selections"].toList()
            val items = c["items"].associateBy { it["id"].asText() }
            if (items.size != c["items"].size() ||
                r["rules"].map { it["id"].asText() }.distinct().size != r["rules"].size() ||
                selections.map { it["id"].asText() }.distinct().size != selections.size)
                reject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            for (selection in selections) {
                if (!selection["enabled"].asBoolean()) continue
                val item = items[selection["catalog_item_id"].asText()] ?: continue
                if (item["kind"] != selection["kind"] || item["version"] != selection["catalog_item_version"])
                    reject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
            }
            val old = selections.singleOrNull { it["id"].asText() == slot.selectionId }
            if (listOf(slot.kind, slot.role, slot.selectionId).any { it.isBlank() } ||
                slot.replacing != (old != null) ||
                (old != null && (old["kind"].asText() != slot.kind || old["role"].asText() != slot.role)) ||
                selections.any { it !== old && it["kind"].asText() == slot.kind && it["role"].asText() == slot.role })
                reject(CompatibleOptionsUnavailable.INVALID_SLOT)
            val empty = b.deepCopy()
            empty.set<JsonNode>("selections", JsonNodeFactory.instance.arrayNode().addAll(selections.filter { it !== old }))
            // Only the removed selection's incident links leave the baseline. Never promote a role.
            if (old != null && b.has("links")) empty.set<JsonNode>("links", JsonNodeFactory.instance.arrayNode().addAll(
                b["links"].filter { it["from_selection_id"].asText() != slot.selectionId && it["to_selection_id"].asText() != slot.selectionId },
            ))
            val baseline = encode(empty)
            if (validator.validate(FrozenDocument.BUILD, baseline.byteInputStream()) != FrozenValidation.Valid)
                reject(CompatibleOptionsUnavailable.BASELINE_UNAVAILABLE)
            val identities = mutableSetOf<CompatibleOptionIdentity>()
            val options = candidateBytes.map { bytes ->
                val candidate = parse(FrozenDocument.SELECTION, bytes)
                if (candidate["id"].asText() != slot.selectionId || candidate["kind"].asText() != slot.kind ||
                    candidate["role"].asText() != slot.role || !candidate["enabled"].asBoolean())
                    reject(CompatibleOptionsUnavailable.INVALID_SLOT)
                val item = items[candidate["catalog_item_id"].asText()] ?: reject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
                if (item["kind"] != candidate["kind"] || item["version"] != candidate["catalog_item_version"])
                    reject(CompatibleOptionsUnavailable.BINDING_MISMATCH)
                val identity = CompatibleOptionIdentity(candidate["catalog_item_id"].asText(), candidate["catalog_item_version"].asText())
                if (!identities.add(identity)) reject(CompatibleOptionsUnavailable.INVALID_INPUT)
                val hypothetical = b.deepCopy()
                val replaced = if (old == null) selections + listOf(candidate) else selections.map { if (it === old) candidate else it }
                hypothetical.set<JsonNode>("selections", JsonNodeFactory.instance.arrayNode().addAll(replaced))
                val json = encode(hypothetical)
                if (validator.validate(FrozenDocument.BUILD, json.byteInputStream()) != FrozenValidation.Valid)
                    reject(CompatibleOptionsUnavailable.INVALID_INPUT)
                identity to json
            }
            val digest = MessageDigest.getInstance("SHA-256")
            fun frame(bytes: ByteArray) { digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
            listOf(PROFILE, slot.kind, slot.role, slot.selectionId, slot.replacing.toString()).forEach { frame(it.toByteArray()) }
            listOf(bBytes, cBytes, rBytes).forEach(::frame); candidateBytes.forEach(::frame)
            val binding = CompatibleOptionsBinding(b["id"].asText(), b["revision"].bigIntegerValue(), c["id"].asText(), c["revision"].bigIntegerValue(),
                r["id"].asText(), r["version"].asText(), locks["dependency_graph_id"].asText(),
                locks["dependency_graph_revision"].bigIntegerValue(), locks["pricebook_id"]?.textValue(),
                slot, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            val affected = r["rules"].filter { rule -> rule["affected_kinds"].any { it.asText() == slot.kind } }
                .map { it["id"].asText() }.toSet()
            CompatibleOptionsPreparation.Ready(CompatibleOptionsRequest(binding, cBytes, rBytes, baseline, options, affected))
        } catch (failure: PreparationFailure) { CompatibleOptionsPreparation.Unavailable(failure.reason) }

        private fun parse(document: FrozenDocument, bytes: ByteArray): ObjectNode {
            val value = (reader.read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value
                ?: reject(CompatibleOptionsUnavailable.INVALID_INPUT)
            if (validator.validate(document, bytes.inputStream()) != FrozenValidation.Valid)
                reject(CompatibleOptionsUnavailable.INVALID_INPUT)
            return value
        }

        /** Preserve raw floating operand types (including 1e0); normalization must not widen the interpreter. */
        private fun encode(node: JsonNode): String {
            val out = StringBuilder()
            fun append(value: JsonNode) {
                when {
                    value.isObject -> {
                        out.append('{')
                        value.properties().forEachIndexed { i, (key, child) ->
                            if (i > 0) out.append(',')
                            out.append(JsonNodeFactory.instance.textNode(key).toString()).append(':'); append(child)
                        }
                        out.append('}')
                    }
                    value.isArray -> {
                        out.append('['); value.forEachIndexed { i, child -> if (i > 0) out.append(','); append(child) }; out.append(']')
                    }
                    else -> {
                        val text = value.toString(); out.append(text)
                        if (value.isFloatingPointNumber && text.none { it == '.' || it == 'e' || it == 'E' }) out.append(".0")
                    }
                }
                if (out.length > MAX_BYTES) reject(CompatibleOptionsUnavailable.WORK_LIMIT)
            }
            append(node)
            return out.toString().also { if (it.toByteArray().size > MAX_BYTES) reject(CompatibleOptionsUnavailable.WORK_LIMIT) }
        }
        private class PreparationFailure(val reason: CompatibleOptionsUnavailable) : RuntimeException()
        private fun reject(reason: CompatibleOptionsUnavailable): Nothing = throw PreparationFailure(reason)
    }
}

sealed class CompatibleOptionsOutcome protected constructor(private val request: CompatibleOptionsRequest) {
    val binding: CompatibleOptionsBinding get() = request.binding
    fun belongsTo(expected: CompatibleOptionsRequest): Boolean = request === expected
    class Completed internal constructor(request: CompatibleOptionsRequest, options: List<CompatibleOption>) : CompatibleOptionsOutcome(request) {
        val options: List<CompatibleOption> = Collections.unmodifiableList(options.toList())
        val compatibleCount: Int get() = options.count { it.verdict == CompatibleOptionVerdict.COMPATIBLE }
        val incompatibleCount: Int get() = options.count { it.verdict == CompatibleOptionVerdict.INCOMPATIBLE }
        val unknownCount: Int get() = options.count { it.verdict == CompatibleOptionVerdict.UNKNOWN }
        /** Conservative default; callers can explicitly inspect the other buckets with their reasons. */
        fun visibleOptions(includeIncompatible: Boolean = false, includeUnknown: Boolean = false): List<CompatibleOption> =
            Collections.unmodifiableList(options.filter { it.verdict == CompatibleOptionVerdict.COMPATIBLE ||
                (includeIncompatible && it.verdict == CompatibleOptionVerdict.INCOMPATIBLE) ||
                (includeUnknown && it.verdict == CompatibleOptionVerdict.UNKNOWN) })
        override fun toString(): String = "CompatibleOptionsOutcome.Completed"
    }
    class Unavailable internal constructor(request: CompatibleOptionsRequest, val reason: CompatibleOptionsUnavailable) : CompatibleOptionsOutcome(request)
}

/** At most 33 bounded interpreter calls, off the UI thread. No partial batch, cost/resource override or write authority. */
class CompatibleOptionsEvaluator(maxWorkPerEvaluation: Int = 250_000) {
    private val evaluator = FrozenDiagnosticEvaluator(maxWorkPerEvaluation)
    fun evaluate(request: CompatibleOptionsRequest): CompatibleOptionsOutcome {
        fun rejected(reason: CompatibleOptionsUnavailable) = CompatibleOptionsOutcome.Unavailable(request, reason)
        fun report(build: String): DiagnosticEvaluation = evaluator.evaluate(build.byteInputStream(),
            request.catalogBytes().inputStream(), request.rulesetBytes().inputStream())
        fun reason(failure: DiagnosticEvaluation.Rejected) = CompatibleOptionsUnavailable.valueOf(failure.reason.name)
        if (Thread.currentThread().isInterrupted) return rejected(CompatibleOptionsUnavailable.CANCELLED)
        val baseResult = report(request.baseline)
        if (baseResult is DiagnosticEvaluation.Rejected) return rejected(reason(baseResult))
        val baseline = (baseResult as DiagnosticEvaluation.Evaluated).report.inspect().findings.associateBy { it.ruleId }
        val options = mutableListOf<CompatibleOption>()
        fun rank(outcome: String): Int = when (outcome) { "pass", "skipped" -> 0; "unknown" -> 1; "fail" -> 2; else -> -1 }
        for ((identity, build) in request.options) {
            if (Thread.currentThread().isInterrupted) return rejected(CompatibleOptionsUnavailable.CANCELLED)
            val result = report(build)
            if (result is DiagnosticEvaluation.Rejected) return rejected(reason(result))
            val reasons = (result as DiagnosticEvaluation.Evaluated).report.inspect().findings
                .filter { it.ruleId in request.affectedRules }
                .mapNotNull { finding ->
                    val before = baseline[finding.ruleId] ?: return rejected(CompatibleOptionsUnavailable.INVALID_OUTPUT)
                    if (rank(finding.outcome) > rank(before.outcome)) CompatibleOptionReason(before.outcome, finding) else null
                }
            val verdict = when {
                reasons.any { it.finding.outcome == "fail" } -> CompatibleOptionVerdict.INCOMPATIBLE
                reasons.any { it.finding.outcome == "unknown" } -> CompatibleOptionVerdict.UNKNOWN
                else -> CompatibleOptionVerdict.COMPATIBLE
            }
            options.add(CompatibleOption(identity, verdict, reasons))
        }
        if (Thread.currentThread().isInterrupted) return rejected(CompatibleOptionsUnavailable.CANCELLED)
        return CompatibleOptionsOutcome.Completed(request, options)
    }
}
