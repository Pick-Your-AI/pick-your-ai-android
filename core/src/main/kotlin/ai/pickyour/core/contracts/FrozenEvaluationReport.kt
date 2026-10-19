package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenEvaluationResult(val encoded: String) { VALID("valid"), INVALID("invalid"), INDETERMINATE("indeterminate") }
enum class FrozenReportReadiness(val encoded: String) { READY("ready"), BLOCKED("blocked"), NEEDS_REVIEW("needs_review") }
enum class FrozenFindingOutcome(val encoded: String) { PASS("pass"), FAIL("fail"), UNKNOWN("unknown"), SKIPPED("skipped") }
enum class FrozenFindingSeverity(val encoded: String) { ERROR("error"), WARNING("warning"), INFO("info") }

/** Arbitrary inert JSON, including null. A missing optional value has no wrapper. */
class FrozenReportValue internal constructor(val json: String) {
    override fun toString(): String = "FrozenReportValue"
}

/** The frozen path is an arbitrary string, not a resolved pointer, URI or evidence authority. */
class FrozenReportEvidence internal constructor(
    val path: String,
    val observed: FrozenReportValue?,
    val expected: FrozenReportValue?,
    val sourceVersion: String?,
) {
    override fun toString(): String = "FrozenReportEvidence"
}

class FrozenReportFinding internal constructor(
    val ruleId: String,
    val outcome: FrozenFindingOutcome,
    val severity: FrozenFindingSeverity,
    val code: String,
    val message: String,
    val remediation: String?,
    selectionIds: List<String>,
    evidence: List<FrozenReportEvidence>?,
) {
    val selectionIds: List<String> = Collections.unmodifiableList(selectionIds.toList())
    /** Absence remains different from a supplied empty array; duplicate evidence stays ordered. */
    val evidence: List<FrozenReportEvidence>? = evidence?.let { Collections.unmodifiableList(it.toList()) }
    override fun toString(): String = "FrozenReportFinding"
}

/** Declared counters only; decoding does not count findings or verify a complete evaluation. */
class FrozenReportSummary internal constructor(
    val passed: BigInteger, val failed: BigInteger, val unknown: BigInteger, val skipped: BigInteger,
) {
    override fun toString(): String = "FrozenReportSummary"
}

/** Immutable frozen wire values, without Build binding, evaluator execution or readiness approval. */
class FrozenEvaluationReport internal constructor(
    val schemaVersion: String,
    val id: String,
    val buildId: String,
    val buildRevision: BigInteger,
    val catalogSnapshotId: String,
    val rulesetId: String,
    val rulesetVersion: String,
    val dependencyGraphId: String,
    val dependencyGraphRevision: BigInteger,
    val evaluatedAt: String,
    val result: FrozenEvaluationResult,
    val deploymentReadiness: FrozenReportReadiness,
    findings: List<FrozenReportFinding>,
    val summary: FrozenReportSummary,
    /** Reparseable JSON values, not original layout, numeric spelling, content hash or signature. */
    val json: String,
) {
    val findings: List<FrozenReportFinding> = Collections.unmodifiableList(findings.toList())
    override fun toString(): String = "FrozenEvaluationReport"
}

sealed interface FrozenEvaluationReportDecoding {
    data class Decoded(val report: FrozenEvaluationReport) : FrozenEvaluationReportDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenEvaluationReportDecoding
    data object UnsupportedVersion : FrozenEvaluationReportDecoding
    data object SchemaMismatch : FrozenEvaluationReportDecoding
}

/**
 * Fixed-schema, bounded UTF-8 decoding. Caller streams stay open on every outcome.
 * No rules, counters, evidence, references, current Build or declared readiness are evaluated here.
 * Perform stream work off the UI thread; transport timeout/cancellation remains the caller's job.
 */
class FrozenEvaluationReportDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenEvaluationReportDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenEvaluationReportDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenEvaluationReportDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.EVALUATION_REPORT, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenEvaluationReportDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenEvaluationReportDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        // Whole-document validation, including the existing normalized-input budget, precedes
        // every typed extraction. Integer schema values must never narrow through Long or Double.
        val numbers = try {
            listOf(parsed["build_revision"], parsed["dependency_graph_revision"],
                parsed["summary"]["passed"], parsed["summary"]["failed"],
                parsed["summary"]["unknown"], parsed["summary"]["skipped"])
                .map { it.decimalValue().toBigIntegerExact() }
        } catch (_: ArithmeticException) {
            return FrozenEvaluationReportDecoding.SchemaMismatch
        }
        val findings = parsed["findings"].map { finding ->
            FrozenReportFinding(
                finding["rule_id"].textValue(),
                FrozenFindingOutcome.entries.first { it.encoded == finding["outcome"].textValue() },
                FrozenFindingSeverity.entries.first { it.encoded == finding["severity"].textValue() },
                finding["code"].textValue(), finding["message"].textValue(), finding.get("remediation")?.textValue(),
                finding["selection_ids"].map { it.textValue() },
                finding.get("evidence")?.map { evidence ->
                    FrozenReportEvidence(
                        evidence["path"].textValue(),
                        evidence.get("observed")?.let { FrozenReportValue(it.toString()) },
                        evidence.get("expected")?.let { FrozenReportValue(it.toString()) },
                        evidence.get("source_version")?.textValue(),
                    )
                },
            )
        }
        return FrozenEvaluationReportDecoding.Decoded(FrozenEvaluationReport(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), parsed["build_id"].textValue(),
            numbers[0], parsed["catalog_snapshot_id"].textValue(), parsed["ruleset_id"].textValue(),
            parsed["ruleset_version"].textValue(), parsed["dependency_graph_id"].textValue(), numbers[1],
            parsed["evaluated_at"].textValue(),
            FrozenEvaluationResult.entries.first { it.encoded == parsed["result"].textValue() },
            FrozenReportReadiness.entries.first { it.encoded == parsed["deployment_readiness"].textValue() },
            findings, FrozenReportSummary(numbers[2], numbers[3], numbers[4], numbers[5]), json,
        ))
    }
}
