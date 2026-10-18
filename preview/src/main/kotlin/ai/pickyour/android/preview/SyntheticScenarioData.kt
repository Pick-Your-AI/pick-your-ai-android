package ai.pickyour.android.preview

import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.evaluation.DiagnosticEvaluation
import ai.pickyour.core.evaluation.FrozenDiagnosticEvaluator
import ai.pickyour.core.evaluation.ReportReception
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.InspectedCostEstimate
import java.security.MessageDigest

internal enum class SyntheticScenarioId(val wireName: String, val build: SyntheticScenarioAsset) {
    EMPTY_DRAFT("empty_draft", SyntheticScenarioAsset.EMPTY_BUILD),
    CORE_BUILD("core_build", SyntheticScenarioAsset.CORE_BUILD),
    UNKNOWN_EVIDENCE("unknown_evidence", SyntheticScenarioAsset.ORIGINAL_BUILD),
    FAILED_RULE("failed_rule", SyntheticScenarioAsset.FAILED_BUILD),
    PARTIAL_QUOTE("partial_quote", SyntheticScenarioAsset.CORE_BUILD),
}

/** Fixture identity only. It is neither a saved revision nor an asynchronous completion ticket. */
internal class SyntheticScenarioIdentity internal constructor(val scenario: SyntheticScenarioId) {
    val classification = SyntheticScenarioPins.CLASSIFICATION
    val fixtureSet = SyntheticScenarioPins.FIXTURE_SET
    val schemaVersion = SyntheticScenarioPins.SCHEMA_VERSION
    val manifestSha256 = SyntheticScenarioPins.MANIFEST_SHA256
    val descriptor = "$fixtureSet/${scenario.wireName}/$manifestSha256"
}

internal sealed interface SyntheticScenarioContent {
    /** A loaded negative sample, with no valid BuildSnapshot, evaluation or save acknowledgment. */
    data object ExpectedEmptyRejection : SyntheticScenarioContent
    class Diagnostic internal constructor(val report: ReportReception.Accepted) : SyntheticScenarioContent
    class PartialQuote internal constructor(val estimate: InspectedCostEstimate) : SyntheticScenarioContent
}

internal enum class SyntheticScenarioRejection {
    UNRECOGNIZED_MANIFEST, ASSET_INVENTORY, INPUT_LIMIT, ASSET_INTEGRITY,
    FROZEN_VALIDATION, REFERENCE_REJECTION, REFERENCE_UNAVAILABLE,
}

internal sealed interface SyntheticScenarioReception {
    class Accepted internal constructor(
        val identity: SyntheticScenarioIdentity,
        val content: SyntheticScenarioContent,
    ) : SyntheticScenarioReception
    data class Rejected(val reason: SyntheticScenarioRejection) : SyntheticScenarioReception
}

/**
 * Preview-only, synchronous admission of a closed, supplied byte set. No file/asset opener, repository,
 * worker, scheduler, Activity, current draft, URI, network, save or publication capability is accepted.
 * The #58 owner must open bounded assets and call this on its worker, map rejection to its existing
 * typed failure boundary and deliver only through current opaque AnalysisCoordinator authority.
 *
 * One reviewed manifest hash is the version allowlist: it binds every byte of its schema version,
 * provenance, asset inventory and expected case dispositions. No input-provided path/schema is used.
 * Hash acceptance is fixture identity, not publisher/evidence authentication or real-data admission.
 */
internal object SyntheticScenarioData {
    fun admit(
        scenario: SyntheticScenarioId,
        manifest: ByteArray,
        supplied: Map<SyntheticScenarioAsset, ByteArray>,
    ): SyntheticScenarioReception {
        if (manifest.size > SyntheticScenarioPins.MAX_MANIFEST_BYTES)
            return reject(SyntheticScenarioRejection.INPUT_LIMIT)
        val manifestSnapshot = manifest.copyOf()
        if (manifestSnapshot.size != SyntheticScenarioPins.MANIFEST_BYTES ||
            sha256(manifestSnapshot) != SyntheticScenarioPins.MANIFEST_SHA256)
            return reject(SyntheticScenarioRejection.UNRECOGNIZED_MANIFEST)
        val inputs = supplied.toMap()
        if (inputs.keys != SyntheticScenarioAsset.entries.toSet())
            return reject(SyntheticScenarioRejection.ASSET_INVENTORY)
        if (inputs.values.any { it.size > SyntheticScenarioPins.MAX_DOCUMENT_BYTES } ||
            inputs.values.sumOf { it.size.toLong() } > SyntheticScenarioPins.MAX_BUNDLE_BYTES)
            return reject(SyntheticScenarioRejection.INPUT_LIMIT)
        // Validate private copies, not mutable caller arrays that may change after their hash was read.
        val snapshots = inputs.mapValues { (_, bytes) -> bytes.copyOf() }
        if (snapshots.any { (asset, bytes) -> bytes.size != asset.byteCount || sha256(bytes) != asset.sha256 })
            return reject(SyntheticScenarioRejection.ASSET_INTEGRITY)

        return try {
            val validator = FrozenSchemaValidator()
            for ((asset, bytes) in snapshots) {
                val validation = validator.validate(asset.document, bytes.inputStream())
                val expected = if (asset == SyntheticScenarioAsset.EMPTY_BUILD) FrozenValidation.SchemaMismatch
                    else FrozenValidation.Valid
                if (validation != expected) return reject(SyntheticScenarioRejection.FROZEN_VALIDATION)
            }
            val content = when (scenario) {
                SyntheticScenarioId.EMPTY_DRAFT -> SyntheticScenarioContent.ExpectedEmptyRejection
                SyntheticScenarioId.PARTIAL_QUOTE -> {
                    val result = CostEstimateAdapter().inspect(
                        snapshots.getValue(scenario.build).inputStream(),
                        snapshots.getValue(SyntheticScenarioAsset.PARTIAL_ESTIMATE).inputStream(),
                    ) as? CostReception.Inspected ?: return reject(SyntheticScenarioRejection.REFERENCE_REJECTION)
                    SyntheticScenarioContent.PartialQuote(result.estimate)
                }
                else -> {
                    val result = FrozenDiagnosticEvaluator().evaluate(
                        snapshots.getValue(scenario.build).inputStream(),
                        snapshots.getValue(SyntheticScenarioAsset.CATALOG).inputStream(),
                        snapshots.getValue(SyntheticScenarioAsset.RULESET).inputStream(),
                    ) as? DiagnosticEvaluation.Evaluated ?: return reject(SyntheticScenarioRejection.REFERENCE_REJECTION)
                    SyntheticScenarioContent.Diagnostic(result.report)
                }
            }
            SyntheticScenarioReception.Accepted(SyntheticScenarioIdentity(scenario), content)
        } catch (_: Exception) {
            // For example, a missing packaged schema is never an accepted synthetic result.
            reject(SyntheticScenarioRejection.REFERENCE_UNAVAILABLE)
        }
    }

    private fun reject(reason: SyntheticScenarioRejection) = SyntheticScenarioReception.Rejected(reason)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
