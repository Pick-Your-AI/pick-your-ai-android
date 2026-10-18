package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenDeploymentDecision(val encoded: String) {
    APPROVED("approved"), REJECTED("rejected"), PENDING("pending"),
}

enum class FrozenDeploymentApprovalScope(val encoded: String) {
    SECURITY("security"), OWNER("owner"), FINANCE("finance"), COMPLIANCE("compliance"),
}

enum class FrozenRolloutStrategy(val encoded: String) {
    CANARY("canary"), BLUE_GREEN("blue_green"), ROLLING("rolling"), MANUAL("manual"),
}

enum class FrozenDeploymentStatus(val encoded: String) {
    PROPOSED("proposed"), APPROVED("approved"), REJECTED("rejected"), DEPLOYED("deployed"), ROLLED_BACK("rolled_back"),
}

/** Recorded fields only. The approver reference and decision are not verified authority. */
class FrozenDeploymentApproval internal constructor(
    val approverRef: String,
    val decision: FrozenDeploymentDecision,
    val scope: FrozenDeploymentApprovalScope,
    val approvedAt: String?,
) {
    override fun toString(): String = "FrozenDeploymentApproval"
}

/**
 * Immutable recorded manifest, without report lookup, approval verification or deployment behavior.
 * Optional absence and array order are retained. [json] preserves JSON values, not original byte
 * layout, number spelling, hash or signature. URI and timestamp strings remain literal input.
 */
class FrozenDeploymentManifest internal constructor(
    val schemaVersion: String,
    val id: String,
    val buildId: String,
    val buildRevision: BigInteger,
    val deploymentTargetSelectionId: String,
    val compatibilityReportId: String,
    val qualityRunId: String,
    val costEstimateId: String,
    val resourceEstimateId: String,
    approvals: List<FrozenDeploymentApproval>,
    val rolloutStrategy: FrozenRolloutStrategy,
    val rollbackArtifactUri: String?,
    val requestedAt: String,
    val status: FrozenDeploymentStatus,
    val deploymentReference: String?,
    notes: List<String>?,
    val json: String,
) {
    val approvals: List<FrozenDeploymentApproval> = Collections.unmodifiableList(approvals.toList())
    val notes: List<String>? = notes?.let { Collections.unmodifiableList(it.toList()) }
    override fun toString(): String = "FrozenDeploymentManifest"
}

sealed interface FrozenDeploymentManifestDecoding {
    data class Decoded(val manifest: FrozenDeploymentManifest) : FrozenDeploymentManifestDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenDeploymentManifestDecoding
    data object UnsupportedVersion : FrozenDeploymentManifestDecoding
    data object SchemaMismatch : FrozenDeploymentManifestDecoding
}

/**
 * Fixed-schema bounded decoding with caller-owned streams and sanitized failures.
 * No URI is opened, reference resolved, approval authenticated or operation requested.
 * Perform stream work off the UI thread; the caller supplies transport timeout/cancellation.
 */
class FrozenDeploymentManifestDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenDeploymentManifestDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenDeploymentManifestDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenDeploymentManifestDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.DEPLOYMENT_MANIFEST, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenDeploymentManifestDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenDeploymentManifestDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        val revision = try {
            parsed["build_revision"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return FrozenDeploymentManifestDecoding.SchemaMismatch
        }
        return FrozenDeploymentManifestDecoding.Decoded(FrozenDeploymentManifest(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), parsed["build_id"].textValue(), revision,
            parsed["deployment_target_selection_id"].textValue(), parsed["compatibility_report_id"].textValue(),
            parsed["quality_run_id"].textValue(), parsed["cost_estimate_id"].textValue(), parsed["resource_estimate_id"].textValue(),
            parsed["approvals"].map { approval -> FrozenDeploymentApproval(
                approval["approver_ref"].textValue(),
                FrozenDeploymentDecision.entries.first { it.encoded == approval["decision"].textValue() },
                FrozenDeploymentApprovalScope.entries.first { it.encoded == approval["scope"].textValue() },
                approval.get("approved_at")?.textValue(),
            ) },
            FrozenRolloutStrategy.entries.first { it.encoded == parsed["rollout_strategy"].textValue() },
            parsed.get("rollback_artifact_uri")?.textValue(), parsed["requested_at"].textValue(),
            FrozenDeploymentStatus.entries.first { it.encoded == parsed["status"].textValue() },
            parsed.get("deployment_reference")?.textValue(), parsed.get("notes")?.map { it.textValue() }, json,
        ))
    }
}
