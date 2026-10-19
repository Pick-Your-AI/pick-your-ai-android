package ai.pickyour.core.resources

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.contracts.ResourceEstimate
import ai.pickyour.core.contracts.ResourceEstimateDecoder
import ai.pickyour.core.contracts.ResourceEstimateDecoding
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.math.BigInteger
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Collections

enum class ResourceInspectionRejection {
    INVALID_BUILD, UNSUPPORTED_BUILD_SCHEMA_VERSION,
    INVALID_RESOURCE_ESTIMATE, UNSUPPORTED_RESOURCE_SCHEMA_VERSION,
    BUILD_BINDING_MISMATCH, DUPLICATE_BUILD_SELECTION_ID,
    UNKNOWN_SELECTION_REFERENCE, DISABLED_SELECTION_REFERENCE,
}

enum class ResourceInspectionGap {
    CATALOG_ARTIFACT_CONDITIONS_NOT_ESTABLISHED,
    RUNTIME_CONTEXT_TOPOLOGY_APPLICABILITY_NOT_ESTABLISHED,
    ESTIMATOR_EVIDENCE_NOT_ESTABLISHED,
    FRESHNESS_NOT_ESTABLISHED,
    SOURCE_ACCESS_AND_RIGHTS_NOT_ESTABLISHED,
    MEASUREMENT_METHOD_AND_RANGE_NOT_RECORDED,
    METRIC_UNIT_RELATIONSHIP_NOT_ESTABLISHED,
}

/** Exact consumed bytes, including whitespace; these hashes are not signatures or access tokens. */
@ConsistentCopyVisibility
data class ResourceInputIdentity internal constructor(val buildSha256: String, val estimateSha256: String) {
    override fun toString(): String = "ResourceInputIdentity"
}

/** A literal selection from the supplied Build, with no catalog resolution or inferred target. */
class RecordedResourceSelection internal constructor(node: JsonNode) {
    val id: String = node["id"].textValue()
    val kind: String = node["kind"].textValue()
    val catalogItemId: String = node["catalog_item_id"].textValue()
    val catalogItemVersion: String = node["catalog_item_version"].textValue()
    val role: String? = node.get("role")?.textValue()
    val enabled: Boolean = node["enabled"].booleanValue()
    val settingsJson: String = node["settings"].toString()
    val recordedJson: String = node.toString()
    override fun toString(): String = "RecordedResourceSelection"
}

/**
 * An immutable inspection of two explicitly supplied records. Matching their declared Build ID,
 * revision and selection references does not establish that the estimate applies to the recorded
 * artifact/runtime/context/topology, is fresh, or meets the Build's declared confidence policy.
 * In particular, a declared `verified` value is not independently verified or a memory-fit pass.
 */
class InspectedResourceEstimate internal constructor(
    val identity: ResourceInputIdentity,
    val estimate: ResourceEstimate,
    build: JsonNode,
    selections: List<RecordedResourceSelection>,
    unreferencedActiveSelectionIds: Set<String>,
) {
    val buildId: String = build["id"].textValue()
    val buildRevision: BigInteger = build["revision"].decimalValue().toBigIntegerExact()
    val buildName: String = build["name"].textValue()
    val recordedBuildJson: String = build.toString()
    val declaredMinimumConfidence: String? = build.get("policy")?.get("min_resource_confidence")?.textValue()
    val selections: List<RecordedResourceSelection> = Collections.unmodifiableList(selections.toList())
    /** Unreferenced is a recorded association fact, not a claim that these selections need a metric. */
    val unreferencedActiveSelectionIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(unreferencedActiveSelectionIds))
    val gaps: Set<ResourceInspectionGap> = Collections.unmodifiableSet(LinkedHashSet(ResourceInspectionGap.entries))
    override fun toString(): String = "InspectedResourceEstimate"
}

sealed interface ResourceInspection {
    data class Rejected(val reason: ResourceInspectionRejection) : ResourceInspection
    class Inspected(val record: InspectedResourceEstimate) : ResourceInspection {
        override fun toString(): String = "ResourceInspection.Inspected"
    }
}

/**
 * Bounded, read-only receiving inspection over the existing frozen schemas. Build is read first;
 * rejected Build input prevents any estimate read. Both streams stay caller-owned on every result.
 * Complete successful reads produce exact input hashes; failures expose no input or provider text.
 * No arithmetic, clock, URI resolution, storage, policy approval or evaluation mutation is performed.
 * A caller needs its own current request/selection authority before presenting an async result.
 */
class RecordedResourceInspector {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()
    private val decoder = ResourceEstimateDecoder()

    fun inspect(build: InputStream, estimate: InputStream): ResourceInspection {
        val buildDigest = MessageDigest.getInstance("SHA-256")
        val input = when (val result = reader.read(DigestInputStream(build, buildDigest))) {
            is JsonInputResult.Parsed -> result.value
            is JsonInputResult.Rejected -> return reject(ResourceInspectionRejection.INVALID_BUILD)
        }
        if (input["schema_version"]?.let { it.isTextual && it.textValue() != "1.0.0" } == true)
            return reject(ResourceInspectionRejection.UNSUPPORTED_BUILD_SCHEMA_VERSION)
        if (validator.validate(FrozenDocument.BUILD, input.toString().byteInputStream()) != FrozenValidation.Valid)
            return reject(ResourceInspectionRejection.INVALID_BUILD)

        val selections = input["selections"].map(::RecordedResourceSelection)
        val byId = selections.associateBy { it.id }
        if (byId.size != selections.size) return reject(ResourceInspectionRejection.DUPLICATE_BUILD_SELECTION_ID)

        val estimateDigest = MessageDigest.getInstance("SHA-256")
        val recorded = when (val result = decoder.decode(DigestInputStream(estimate, estimateDigest))) {
            is ResourceEstimateDecoding.Decoded -> result.estimate
            ResourceEstimateDecoding.UnsupportedVersion -> return reject(ResourceInspectionRejection.UNSUPPORTED_RESOURCE_SCHEMA_VERSION)
            is ResourceEstimateDecoding.InvalidInput, ResourceEstimateDecoding.SchemaMismatch ->
                return reject(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE)
        }
        if (recorded.buildId != input["id"].textValue() ||
            recorded.buildRevision != input["revision"].decimalValue().toBigIntegerExact())
            return reject(ResourceInspectionRejection.BUILD_BINDING_MISMATCH)
        val referenced = recorded.estimates.flatMap { it.selectionIds }.toSet()
        if (referenced.any { it !in byId }) return reject(ResourceInspectionRejection.UNKNOWN_SELECTION_REFERENCE)
        if (referenced.any { byId.getValue(it).enabled.not() })
            return reject(ResourceInspectionRejection.DISABLED_SELECTION_REFERENCE)
        val active = selections.filter { it.enabled }.map { it.id }.toSet()
        return ResourceInspection.Inspected(InspectedResourceEstimate(
            ResourceInputIdentity(hex(buildDigest.digest()), hex(estimateDigest.digest())), recorded,
            input, selections, active - referenced,
        ))
    }

    private fun reject(reason: ResourceInspectionRejection) = ResourceInspection.Rejected(reason)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
