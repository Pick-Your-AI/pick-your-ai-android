package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class ResourceMetric(val encoded: String) {
    REQUIRED_VRAM("inference.estimated_required_vram_gb"),
    PEAK_PER_DEVICE_VRAM("inference.estimated_peak_per_device_vram_gb"),
    TOKENS_PER_SECOND("inference.estimated_tokens_per_second"),
    GPU_POWER("compute.estimated_gpu_power_w"),
    GPU_HOURS("training.estimated_gpu_hours"),
}

enum class ResourceUnit(val encoded: String) {
    GB("gb"), TOKENS_PER_SECOND("tokens_per_second"), WATT("watt"), GPU_HOUR("gpu_hour"),
}

enum class ResourceConfidence(val encoded: String) {
    VERIFIED("verified"), CALIBRATED("calibrated"), ESTIMATED("estimated"), UNVERIFIED("unverified"),
}

/**
 * Recorded structural fields, not approved capacity evidence. [value] preserves the exact frozen
 * decimal string, including its schema's terminal-LF edge case; decoding performs no arithmetic.
 * Neither the metric/unit combination nor the declared confidence is independently verified here.
 */
class ResourceEstimateEntry internal constructor(
    val metric: ResourceMetric,
    val value: String,
    val unit: ResourceUnit,
    val estimatorId: String,
    val estimatorVersion: String,
    val confidence: ResourceConfidence,
    val sourceUri: String?,
    assumptions: List<String>,
    selectionIds: List<String>,
) {
    val assumptions: List<String> = Collections.unmodifiableList(assumptions.toList())
    val selectionIds: List<String> = Collections.unmodifiableList(selectionIds.toList())
    override fun toString(): String = "ResourceEstimateEntry"
}

/**
 * Immutable projection of one frozen ResourceEstimate. No mutable JSON tree escapes this boundary.
 * [json] retains JSON values, not original byte layout, numeric spelling or an artifact signature.
 * Source URIs remain inert strings, and selection IDs do not imply current-build applicability.
 */
class ResourceEstimate internal constructor(
    val schemaVersion: String,
    val id: String,
    val buildId: String,
    val buildRevision: BigInteger,
    val generatedAt: String,
    estimates: List<ResourceEstimateEntry>,
    val json: String,
) {
    val estimates: List<ResourceEstimateEntry> = Collections.unmodifiableList(estimates.toList())
    override fun toString(): String = "ResourceEstimate"
}

sealed interface ResourceEstimateDecoding {
    data class Decoded(val estimate: ResourceEstimate) : ResourceEstimateDecoding
    /** No input values, schema diagnostics, source URIs or provider error messages escape. */
    data class InvalidInput(val reason: JsonRejection) : ResourceEstimateDecoding
    data object UnsupportedVersion : ResourceEstimateDecoding
    data object SchemaMismatch : ResourceEstimateDecoding
}

/**
 * Fixed-schema, bounded UTF-8 wire decoding. Streams stay caller-owned on success and failure.
 * This neither estimates resources nor certifies a memory-fit verdict, measured result or device.
 * Build/runtime/context/topology binding, freshness and evidence policy belong to the receiver.
 * Perform stream work off the UI thread; transport timeout/cancellation remains the caller's job.
 */
class ResourceEstimateDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): ResourceEstimateDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return ResourceEstimateDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return ResourceEstimateDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.RESOURCE_ESTIMATE, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return ResourceEstimateDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return ResourceEstimateDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        val revision = try {
            parsed["build_revision"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return ResourceEstimateDecoding.SchemaMismatch
        }
        val estimates = parsed["estimates"].map { entry ->
            ResourceEstimateEntry(
                ResourceMetric.entries.first { it.encoded == entry["metric"].textValue() },
                entry["value"].textValue(),
                ResourceUnit.entries.first { it.encoded == entry["unit"].textValue() },
                entry["estimator_id"].textValue(), entry["estimator_version"].textValue(),
                ResourceConfidence.entries.first { it.encoded == entry["confidence"].textValue() },
                entry.get("source_uri")?.textValue(), entry["assumptions"].map { it.textValue() },
                entry["selection_ids"].map { it.textValue() },
            )
        }
        return ResourceEstimateDecoding.Decoded(ResourceEstimate(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), parsed["build_id"].textValue(),
            revision, parsed["generated_at"].textValue(), estimates, json,
        ))
    }
}
