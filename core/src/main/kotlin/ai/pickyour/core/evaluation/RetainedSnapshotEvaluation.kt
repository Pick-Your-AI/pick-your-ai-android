package ai.pickyour.core.evaluation

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.drafts.ProjectionArtifactKind
import ai.pickyour.core.drafts.RetainedDraftSnapshot
import ai.pickyour.core.drafts.SnapshotArchiveKey
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import ai.pickyour.core.importing.localSnapshotRevision
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Collections
import java.util.Locale

/** Private Android admission/observation profile. These fields do not extend frozen v1. */
object RetainedEvaluationProfile {
    const val ID = "local-retained-frozen-v1-no-external-evidence/1"
    const val REFERENCE_SHA256 = "092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a"
    const val SCHEMAS_SHA256 = "eaaf51a04c9bd0c7fce23e7ecc75724226e26b337cc1da04b88a59d2a912914f"
    const val EVALUATOR_SHA256 = "b0bc63de8d6d4fed412344c4cb58864248782edf74aa9fb0fa75df9f3e80667f"
    const val REPORT_RECEIVER_SHA256 = "d39e266490ffb7814b0bb2cc05a108d60206f036a3be7beb6c340b29723fa525"
    const val REFERENCE_TIME = "2026-10-16T09:00:00Z"
}

enum class SnapshotEvaluationSlot { BUILD, CATALOG, RULESET, GRAPH, PRICEBOOK, RESOURCE_ESTIMATE, COST_ESTIMATE }
enum class SnapshotInputStatus {
    BOUND, NOT_SUPPLIED, REQUESTED_UNAVAILABLE, MALFORMED_INPUT, LOCAL_INPUT_LIMIT,
    UNSUPPORTED_VERSION, SCHEMA_MISMATCH, HASH_MISMATCH, IDENTITY_MISMATCH,
    AMBIGUOUS_CATALOG, UNSUPPORTED_EVIDENCE, UNSUPPORTED_PROFILE,
}

/** Exact byte binding, separate from schema validity, evidence qualification and current UI authority. */
class SnapshotInputBinding internal constructor(
    val slot: SnapshotEvaluationSlot, val id: String, val version: String,
    val hash: ArtifactHash, val bytes: Int,
) { override fun toString() = "SnapshotInputBinding" }

class SnapshotInputDiagnostic internal constructor(
    val slot: SnapshotEvaluationSlot, val status: SnapshotInputStatus, val binding: SnapshotInputBinding? = null,
) { override fun toString() = "SnapshotInputDiagnostic($slot, $status)" }

sealed interface SnapshotEvidenceInput {
    data object NotSupplied : SnapshotEvidenceInput
    /** A requested missing artifact remains requested; no fallback to NotSupplied is allowed. */
    class Requested(val id: String, val hash: ArtifactHash, bytes: ByteArray?) : SnapshotEvidenceInput {
        init { require(id.isNotEmpty() && id.length <= 4096) }
        private val retained = bytes?.copyOf()
        internal fun bytes() = retained?.copyOf()
        override fun toString() = "RequestedSnapshotEvidence"
    }
}

sealed interface SnapshotEvaluationPreparation {
    class Ready(val request: SnapshotEvaluationRequest) : SnapshotEvaluationPreparation
    class Withheld(diagnostics: List<SnapshotInputDiagnostic>) : SnapshotEvaluationPreparation {
        val diagnostics: List<SnapshotInputDiagnostic> = fixedList(diagnostics)
    }
}

/**
 * Captured immutable archive input. Only the archive's fully decoded retained snapshot can enter
 * this factory: its semantic source/selection/reference/pin checks have already run on reopening.
 * No DraftVersion, archive-head CAS, caller metric override or executable metadata is carried.
 */
class SnapshotEvaluationRequest internal constructor(
    val key: SnapshotArchiveKey, val bundleHash: ArtifactHash, val bundleBytes: Int,
    diagnostics: List<SnapshotInputDiagnostic>, inputs: Map<SnapshotEvaluationSlot, ByteArray>,
) {
    val profile: String = RetainedEvaluationProfile.ID
    val diagnostics: List<SnapshotInputDiagnostic> = fixedList(diagnostics)
    val bindings: List<SnapshotInputBinding> = fixedList(diagnostics.mapNotNull { it.binding })
    private val inputs = inputs.mapValues { it.value.copyOf() }
    /** Includes the full mathematical revision, all exact inputs and the local evaluator profile. */
    val digest: ArtifactHash = inputDigest(key, bundleHash, bundleBytes, diagnostics)
    internal fun bytes(slot: SnapshotEvaluationSlot) = inputs.getValue(slot).copyOf()
    override fun toString() = "SnapshotEvaluationRequest"
}

enum class SnapshotEvaluationFailure { INVALID_INPUT, BINDING_MISMATCH, WORK_LIMIT, UNSUPPORTED_OPERATION, INVALID_OUTPUT, ENGINE_FAILURE }
sealed class SnapshotEvaluationOutcome protected constructor(internal val request: SnapshotEvaluationRequest) {
    /** Trusted local computation plus complete report re-admission; not an arbitrary remote report. */
    class Evaluated internal constructor(request: SnapshotEvaluationRequest, val report: ReportReception.Accepted,
        val observedAt: Instant) : SnapshotEvaluationOutcome(request) {
        /** Immutable identity/profile descriptors for the actual computed report, without raw source. */
        val inputs: SnapshotEvaluationRequest get() = request
    }
    class Failed internal constructor(request: SnapshotEvaluationRequest, val reason: SnapshotEvaluationFailure) : SnapshotEvaluationOutcome(request)
    class Cancelled internal constructor(request: SnapshotEvaluationRequest) : SnapshotEvaluationOutcome(request)
    fun belongsTo(expected: SnapshotEvaluationRequest): Boolean = request === expected
    override fun toString() = "SnapshotEvaluationOutcome"
}

/** Stable synchronous local boundary. Its caller owns worker dispatch, request lifetime and delivery. */
fun interface SnapshotEvaluator {
    fun evaluate(request: SnapshotEvaluationRequest): SnapshotEvaluationOutcome
}

/**
 * Full reevaluation of exact retained inputs. Graph bytes/schema/identity are required and bound;
 * graph edges are not interpreted or used to prune rules. Retained Pricebook bytes do not create
 * CostEstimate metrics. This profile deliberately withholds every requested external estimate.
 */
class LocalSnapshotEvaluator(private val clock: Clock = Clock.systemUTC()) : SnapshotEvaluator {
    override fun evaluate(request: SnapshotEvaluationRequest): SnapshotEvaluationOutcome {
        if (Thread.currentThread().isInterrupted) return SnapshotEvaluationOutcome.Cancelled(request)
        val build = request.bytes(SnapshotEvaluationSlot.BUILD)
        val rules = request.bytes(SnapshotEvaluationSlot.RULESET)
        val computed = try {
            FrozenDiagnosticEvaluator().evaluate(build.inputStream(), request.bytes(SnapshotEvaluationSlot.CATALOG).inputStream(), rules.inputStream())
        } catch (_: RuntimeException) {
            return SnapshotEvaluationOutcome.Failed(request, SnapshotEvaluationFailure.ENGINE_FAILURE)
        }
        if (Thread.currentThread().isInterrupted) return SnapshotEvaluationOutcome.Cancelled(request)
        if (computed is DiagnosticEvaluation.Rejected) return SnapshotEvaluationOutcome.Failed(request, when (computed.reason) {
            DiagnosticRejection.INVALID_INPUT -> SnapshotEvaluationFailure.INVALID_INPUT
            DiagnosticRejection.BINDING_MISMATCH -> SnapshotEvaluationFailure.BINDING_MISMATCH
            DiagnosticRejection.WORK_LIMIT -> SnapshotEvaluationFailure.WORK_LIMIT
            DiagnosticRejection.UNSUPPORTED_OPERATION -> SnapshotEvaluationFailure.UNSUPPORTED_OPERATION
            DiagnosticRejection.INVALID_OUTPUT -> SnapshotEvaluationFailure.INVALID_OUTPUT
        })
        val original = (computed as DiagnosticEvaluation.Evaluated).report
        // This receiver validates structural/lock/result consistency. Predicate provenance remains
        // the actual fixed local computation immediately above, never a caller-supplied report.
        val received = ReferenceReportAdapter().receive(build.inputStream(), rules.inputStream(), original.json.byteInputStream())
        if (received !is ReportReception.Accepted || received.json != original.json || !completeReport(request, rules, received))
            return SnapshotEvaluationOutcome.Failed(request, SnapshotEvaluationFailure.INVALID_OUTPUT)
        val observedAt = try { clock.instant() } catch (_: RuntimeException) {
            return SnapshotEvaluationOutcome.Failed(request, SnapshotEvaluationFailure.ENGINE_FAILURE)
        }
        return if (Thread.currentThread().isInterrupted) SnapshotEvaluationOutcome.Cancelled(request)
            else SnapshotEvaluationOutcome.Evaluated(request, original, observedAt)
    }

    private fun completeReport(request: SnapshotEvaluationRequest, rulesBytes: ByteArray, report: ReportReception.Accepted): Boolean {
        val view = report.inspect()
        if (view.buildId != request.key.id || view.buildRevision != request.key.revision ||
            view.id != "evaluation.${request.key.id}.r${request.key.revision}" || view.evaluatedAt != RetainedEvaluationProfile.REFERENCE_TIME) return false
        val rules = (BoundedJsonReader().read(rulesBytes.inputStream()) as? JsonInputResult.Parsed)?.value ?: return false
        return view.findings.zip(rules["rules"]).all { (finding, rule) ->
            val needsDetail = finding.outcome in listOf("fail", "unknown")
            finding.code == finding.ruleId.replace('.', '_').uppercase(Locale.ROOT) && finding.evidence.isEmpty() &&
                finding.message == (if (needsDetail) rule["message"].textValue() else "${finding.ruleId}: ${finding.outcome}") &&
                finding.remediation == (if (needsDetail) rule["remediation"].textValue() else "")
        }
    }

    companion object {
        private val reader = BoundedJsonReader(JsonInputLimits(maxBytes = 262_144, maxTokens = 20_000, maxStringLength = 4096))
        private val validator = FrozenSchemaValidator()

        fun prepare(snapshot: RetainedDraftSnapshot, resource: SnapshotEvidenceInput = SnapshotEvidenceInput.NotSupplied,
            cost: SnapshotEvidenceInput = SnapshotEvidenceInput.NotSupplied,
            profile: String = RetainedEvaluationProfile.ID): SnapshotEvaluationPreparation {
            val diagnostics = mutableListOf<SnapshotInputDiagnostic>()
            val inputs = linkedMapOf<SnapshotEvaluationSlot, ByteArray>()
            val nodes = linkedMapOf<SnapshotEvaluationSlot, ObjectNode>()
            fun record(slot: SnapshotEvaluationSlot, raw: ByteArray?, type: FrozenDocument, id: String?, version: String?, hash: ArtifactHash?) {
                if (raw == null) {
                    diagnostics += diagnostic(slot, if (slot == SnapshotEvaluationSlot.PRICEBOOK && id == null)
                        SnapshotInputStatus.NOT_SUPPLIED else SnapshotInputStatus.REQUESTED_UNAVAILABLE)
                    return
                }
                val parsed = parse(raw, type)
                val node = parsed.first
                val digest = snapshotHash(raw)
                val actualVersion = node?.let { if (slot == SnapshotEvaluationSlot.RULESET) it["version"].textValue()
                    else localSnapshotRevision(it["revision"])?.toString() }
                val binding = node?.let { SnapshotInputBinding(slot, it["id"].textValue(), actualVersion ?: "", digest, raw.size) }
                val status = when {
                    hash != null && digest != hash -> SnapshotInputStatus.HASH_MISMATCH
                    parsed.second != null -> parsed.second!!
                    actualVersion == null -> SnapshotInputStatus.LOCAL_INPUT_LIMIT
                    node!!["id"].textValue() != id || actualVersion != version -> SnapshotInputStatus.IDENTITY_MISMATCH
                    else -> SnapshotInputStatus.BOUND
                }
                diagnostics += SnapshotInputDiagnostic(slot, status, binding)
                if (status == SnapshotInputStatus.BOUND) { inputs[slot] = raw.copyOf(); nodes[slot] = node!! }
            }
            record(SnapshotEvaluationSlot.BUILD, snapshot.bytes(), FrozenDocument.BUILD, snapshot.entry.key.id,
                snapshot.entry.key.revision.toString(), null)
            for (kind in ProjectionArtifactKind.entries) {
                val slot = SnapshotEvaluationSlot.valueOf(kind.name)
                val pin = snapshot.pins.singleOrNull { it.kind == kind }
                record(slot, snapshot.artifactBytes(kind), kind.document, pin?.id, pin?.version, pin?.hash)
            }
            val build = nodes[SnapshotEvaluationSlot.BUILD]
            if (build != null) {
                val locks = build["locks"]
                fun requireLock(slot: SnapshotEvaluationSlot, matches: Boolean) {
                    if (!matches) diagnostics.replaceAll { if (it.slot == slot && it.status == SnapshotInputStatus.BOUND)
                        SnapshotInputDiagnostic(slot, SnapshotInputStatus.IDENTITY_MISMATCH, it.binding) else it }
                }
                nodes[SnapshotEvaluationSlot.CATALOG]?.let { catalog ->
                    requireLock(SnapshotEvaluationSlot.CATALOG, catalog["id"] == locks["catalog_snapshot_id"])
                    if (catalog["items"].map { it["id"].textValue() }.distinct().size != catalog["items"].size())
                        diagnostics.replaceAll { if (it.slot == SnapshotEvaluationSlot.CATALOG && it.status == SnapshotInputStatus.BOUND)
                            SnapshotInputDiagnostic(it.slot, SnapshotInputStatus.AMBIGUOUS_CATALOG, it.binding) else it }
                }
                nodes[SnapshotEvaluationSlot.RULESET]?.let { requireLock(SnapshotEvaluationSlot.RULESET,
                    it["id"] == locks["ruleset_id"] && it["version"] == locks["ruleset_version"]) }
                nodes[SnapshotEvaluationSlot.GRAPH]?.let { requireLock(SnapshotEvaluationSlot.GRAPH,
                    it["id"] == locks["dependency_graph_id"] && localSnapshotRevision(it["revision"]) == localSnapshotRevision(locks["dependency_graph_revision"])) }
                val pricebook = nodes[SnapshotEvaluationSlot.PRICEBOOK]
                if ((pricebook == null) != !locks.has("pricebook_id") || (pricebook != null && pricebook["id"] != locks["pricebook_id"]))
                    diagnostics.replaceAll { if (it.slot == SnapshotEvaluationSlot.PRICEBOOK &&
                        it.status in listOf(SnapshotInputStatus.BOUND, SnapshotInputStatus.NOT_SUPPLIED))
                        SnapshotInputDiagnostic(it.slot, SnapshotInputStatus.IDENTITY_MISMATCH, it.binding) else it }
            }
            // Both optional inputs are independently inspected, even if a required input is bad.
            diagnostics += evidence(SnapshotEvaluationSlot.RESOURCE_ESTIMATE, resource, snapshot.entry.key, FrozenDocument.RESOURCE_ESTIMATE)
            diagnostics += evidence(SnapshotEvaluationSlot.COST_ESTIMATE, cost, snapshot.entry.key, FrozenDocument.COST_ESTIMATE)
            if (profile != RetainedEvaluationProfile.ID || snapshot.profileVersion != 1)
                diagnostics += diagnostic(SnapshotEvaluationSlot.BUILD, SnapshotInputStatus.UNSUPPORTED_PROFILE)
            return if (diagnostics.any { it.status !in listOf(SnapshotInputStatus.BOUND, SnapshotInputStatus.NOT_SUPPLIED) })
                SnapshotEvaluationPreparation.Withheld(diagnostics)
            else SnapshotEvaluationPreparation.Ready(SnapshotEvaluationRequest(snapshot.entry.key, snapshot.entry.bundleHash,
                snapshot.entry.bundleBytes, diagnostics, inputs))
        }

        private fun evidence(slot: SnapshotEvaluationSlot, input: SnapshotEvidenceInput, key: SnapshotArchiveKey, type: FrozenDocument): SnapshotInputDiagnostic {
            if (input is SnapshotEvidenceInput.NotSupplied) return diagnostic(slot, SnapshotInputStatus.NOT_SUPPLIED)
            input as SnapshotEvidenceInput.Requested
            val raw = input.bytes() ?: return diagnostic(slot, SnapshotInputStatus.REQUESTED_UNAVAILABLE)
            val hash = snapshotHash(raw)
            val (node, failure) = parse(raw, type)
            val binding = node?.let { SnapshotInputBinding(slot, it["id"].textValue(), it["schema_version"].textValue(), hash, raw.size) }
            val status = when {
                hash != input.hash -> SnapshotInputStatus.HASH_MISMATCH
                failure != null -> failure
                node!!["id"].textValue() != input.id || node["build_id"].textValue() != key.id ||
                    localSnapshotRevision(node["build_revision"]) != key.revision -> SnapshotInputStatus.IDENTITY_MISMATCH
                else -> SnapshotInputStatus.UNSUPPORTED_EVIDENCE
            }
            return SnapshotInputDiagnostic(slot, status, binding)
        }

        private fun parse(bytes: ByteArray, type: FrozenDocument): Pair<ObjectNode?, SnapshotInputStatus?> {
            val result = reader.read(bytes.inputStream())
            if (result is JsonInputResult.Rejected) return null to if (result.reason == JsonRejection.LIMIT_EXCEEDED)
                SnapshotInputStatus.LOCAL_INPUT_LIMIT else SnapshotInputStatus.MALFORMED_INPUT
            val node = (result as JsonInputResult.Parsed).value
            if (node.path("schema_version").textValue() != "1.0.0") return null to SnapshotInputStatus.UNSUPPORTED_VERSION
            if (validator.validate(type, bytes.inputStream()) != FrozenValidation.Valid) return null to SnapshotInputStatus.SCHEMA_MISMATCH
            return node to null
        }
        private fun diagnostic(slot: SnapshotEvaluationSlot, status: SnapshotInputStatus) = SnapshotInputDiagnostic(slot, status)
    }
}

private fun <T> fixedList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun snapshotHash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) })
private fun inputDigest(key: SnapshotArchiveKey, bundleHash: ArtifactHash, bundleBytes: Int,
    diagnostics: List<SnapshotInputDiagnostic>): ArtifactHash {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { stream ->
        fun part(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); stream.writeInt(bytes.size); stream.write(bytes) }
        part(RetainedEvaluationProfile.ID); part(RetainedEvaluationProfile.REFERENCE_SHA256); part(RetainedEvaluationProfile.SCHEMAS_SHA256)
        part(RetainedEvaluationProfile.EVALUATOR_SHA256); part(RetainedEvaluationProfile.REPORT_RECEIVER_SHA256)
        part(key.id); part(key.revision.toString()); part(bundleHash.hex); stream.writeInt(bundleBytes)
        for (diagnostic in diagnostics) {
            part(diagnostic.slot.name); part(diagnostic.status.name)
            diagnostic.binding?.let { part(it.id); part(it.version); part(it.hash.hex); stream.writeInt(it.bytes) }
        }
    }
    return snapshotHash(output.toByteArray())
}
