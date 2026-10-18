package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

enum class QualityRunStatus(val encoded: String) {
    PASSED("passed"), FAILED("failed"), INCOMPLETE("incomplete"),
}

enum class QualityComparator(val encoded: String) {
    GTE("gte"), GT("gt"), LTE("lte"), LT("lt"), EQ("eq"),
}

/** Recorded values only. Neither comparator nor passed is recalculated by this wire decoder. */
class QualityMeasurement internal constructor(
    val metric: String,
    val observed: BigDecimal,
    val threshold: BigDecimal,
    val comparator: QualityComparator,
    val unit: String?,
    val passed: Boolean,
) {
    override fun toString(): String = "QualityMeasurement"
}

/**
 * Immutable structural projection of one frozen QualityEvaluationRun, not trusted measured evidence.
 * Optional notes/unit retain absence separately from empty values. Artifact URIs are inert strings.
 * [json] preserves JSON values, not the original byte layout, numeric spelling or a source signature.
 */
class QualityEvaluationRun internal constructor(
    val schemaVersion: String,
    val id: String,
    val buildId: String,
    val buildRevision: BigInteger,
    val evaluationSuiteSelectionId: String,
    val startedAt: String,
    val finishedAt: String,
    val status: QualityRunStatus,
    val evaluatorVersion: String,
    val datasetRevision: String,
    val sampleSize: BigInteger,
    measurements: List<QualityMeasurement>,
    artifacts: List<String>,
    notes: List<String>?,
    val json: String,
) {
    val measurements: List<QualityMeasurement> = Collections.unmodifiableList(measurements.toList())
    val artifacts: List<String> = Collections.unmodifiableList(artifacts.toList())
    val notes: List<String>? = notes?.let { Collections.unmodifiableList(it.toList()) }
    override fun toString(): String = "QualityEvaluationRun"
}

sealed interface QualityRunDecoding {
    data class Decoded(val run: QualityEvaluationRun) : QualityRunDecoding
    /** No input fragments, schema-library diagnostics, URLs or provider error messages escape. */
    data class InvalidInput(val reason: JsonRejection) : QualityRunDecoding
    data object UnsupportedVersion : QualityRunDecoding
    data object SchemaMismatch : QualityRunDecoding
}

/**
 * Fixed-schema, bounded UTF-8 decoding only; streams remain caller-owned on every outcome.
 * No artifact is opened, evaluation is run, build binding is verified, or publication authorized.
 * Perform stream work off the UI thread; transport timeout/cancellation remains the caller's job.
 */
class QualityEvaluationRunDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): QualityRunDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return QualityRunDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return QualityRunDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.QUALITY_EVALUATION_RUN, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return QualityRunDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return QualityRunDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }

        // Never narrow revisions or sample counts through Long/Double or truncate a fractional value.
        val buildRevision: BigInteger
        val sampleSize: BigInteger
        try {
            buildRevision = parsed["build_revision"].decimalValue().toBigIntegerExact()
            sampleSize = parsed["sample_size"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return QualityRunDecoding.SchemaMismatch
        }
        val measurements = parsed["measurements"].map { measurement ->
            QualityMeasurement(
                measurement["metric"].textValue(), measurement["observed"].decimalValue(),
                measurement["threshold"].decimalValue(),
                QualityComparator.entries.first { it.encoded == measurement["comparator"].textValue() },
                measurement.get("unit")?.textValue(), measurement["passed"].booleanValue(),
            )
        }
        return QualityRunDecoding.Decoded(QualityEvaluationRun(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), parsed["build_id"].textValue(),
            buildRevision, parsed["evaluation_suite_selection_id"].textValue(), parsed["started_at"].textValue(),
            parsed["finished_at"].textValue(), QualityRunStatus.entries.first { it.encoded == parsed["status"].textValue() },
            parsed["evaluator_version"].textValue(), parsed["dataset_revision"].textValue(), sampleSize,
            measurements, parsed["artifacts"].map { it.textValue() }, parsed.get("notes")?.map { it.textValue() }, json,
        ))
    }
}
