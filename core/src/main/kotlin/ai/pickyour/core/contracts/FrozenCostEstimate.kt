package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenCostMeter(val encoded: String) {
    INPUT_TOKENS_1M("input_tokens_1m"), OUTPUT_TOKENS_1M("output_tokens_1m"),
    CACHED_INPUT_TOKENS_1M("cached_input_tokens_1m"), EMBEDDING_TOKENS_1M("embedding_tokens_1m"),
    REQUEST("request"), GPU_HOUR("gpu_hour"), CPU_HOUR("cpu_hour"), STORAGE_GB_MONTH("storage_gb_month"),
    VECTOR_GB_MONTH("vector_gb_month"), AGENT_RUN("agent_run"), SEAT_MONTH("seat_month"),
    LICENSE_MONTH("license_month"), ONE_TIME("one_time"),
}

enum class FrozenCostMethod(val encoded: String) {
    MEASURED("measured"), WORKLOAD_PROJECTION("workload_projection"),
    VENDOR_QUOTE("vendor_quote"), MANUAL_OVERRIDE("manual_override"),
}

enum class FrozenCostConfidence(val encoded: String) { HIGH("high"), MEDIUM("medium"), LOW("low") }
enum class FrozenCostCoverage(val encoded: String) { COMPLETE("complete"), PARTIAL("partial"), UNKNOWN("unknown") }

/**
 * Inert frozen money fields, preserving the schema-admitted strings including a terminal LF.
 * This is deliberately not ContractMoney's stricter whole-string arithmetic profile. No trimming,
 * numeric conversion, currency lookup or relationship between recorded amounts is performed here.
 */
class FrozenCostMoney internal constructor(val amount: String, val currency: String) {
    override fun toString(): String = "FrozenCostMoney"
}

/** Declared line fields only; method/confidence and an amount of zero establish no pricing evidence. */
class FrozenCostLineItem internal constructor(
    val id: String,
    val selectionId: String,
    val priceRateId: String,
    val meter: FrozenCostMeter,
    val quantity: String,
    val unitPrice: FrozenCostMoney,
    val amount: FrozenCostMoney,
    val method: FrozenCostMethod,
    val confidence: FrozenCostConfidence,
    assumptions: List<String>,
) {
    val assumptions: List<String> = Collections.unmodifiableList(assumptions.toList())
    override fun toString(): String = "FrozenCostLineItem"
}

/**
 * Immutable structural projection, without Build binding, amount reconciliation or evidence grading.
 * Ordered duplicate line IDs and contradictory declared coverage/totals remain recorded input.
 * [json] preserves JSON values, not original layout, numeric spelling, source hash or signature.
 */
class FrozenCostEstimate internal constructor(
    val schemaVersion: String,
    val id: String,
    val buildId: String,
    val buildRevision: BigInteger,
    val pricebookId: String,
    val estimatedAt: String,
    val periodDays: BigInteger,
    val currency: String,
    lineItems: List<FrozenCostLineItem>,
    val subtotal: FrozenCostMoney,
    val tax: FrozenCostMoney,
    val total: FrozenCostMoney,
    val coverage: FrozenCostCoverage,
    unpricedSelectionIds: List<String>,
    warnings: List<String>,
    val json: String,
) {
    val lineItems: List<FrozenCostLineItem> = Collections.unmodifiableList(lineItems.toList())
    val unpricedSelectionIds: List<String> = Collections.unmodifiableList(unpricedSelectionIds.toList())
    val warnings: List<String> = Collections.unmodifiableList(warnings.toList())
    override fun toString(): String = "FrozenCostEstimate"
}

sealed interface FrozenCostEstimateDecoding {
    data class Decoded(val estimate: FrozenCostEstimate) : FrozenCostEstimateDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenCostEstimateDecoding
    data object UnsupportedVersion : FrozenCostEstimateDecoding
    data object SchemaMismatch : FrozenCostEstimateDecoding
}

/**
 * Fixed-schema, bounded UTF-8 decoding with caller-owned streams and sanitized failures.
 * Perform stream work off the UI thread; the caller supplies transport timeout/cancellation.
 * No amount arithmetic, Build/rate lookup, current binding, price/freshness/coverage acceptance,
 * publication, storage, UI mutation or network access follows from structural decoding.
 */
class FrozenCostEstimateDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenCostEstimateDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenCostEstimateDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenCostEstimateDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.COST_ESTIMATE, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenCostEstimateDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenCostEstimateDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        val buildRevision: BigInteger
        val periodDays: BigInteger
        try {
            buildRevision = parsed["build_revision"].decimalValue().toBigIntegerExact()
            periodDays = parsed["period_days"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return FrozenCostEstimateDecoding.SchemaMismatch
        }
        // The unchanged schema has validated every field before this typed extraction. Retain
        // wire strings and array order; the separate CostEstimateAdapter keeps its stronger checks.
        val lines = parsed["line_items"].map { line ->
            FrozenCostLineItem(
                line["id"].textValue(), line["selection_id"].textValue(), line["price_rate_id"].textValue(),
                FrozenCostMeter.entries.first { it.encoded == line["meter"].textValue() },
                line["quantity"].textValue(), money(line["unit_price"]), money(line["amount"]),
                FrozenCostMethod.entries.first { it.encoded == line["method"].textValue() },
                FrozenCostConfidence.entries.first { it.encoded == line["confidence"].textValue() },
                line["assumptions"].map { it.textValue() },
            )
        }
        return FrozenCostEstimateDecoding.Decoded(FrozenCostEstimate(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), parsed["build_id"].textValue(),
            buildRevision, parsed["pricebook_id"].textValue(), parsed["estimated_at"].textValue(), periodDays,
            parsed["currency"].textValue(), lines, money(parsed["subtotal"]), money(parsed["tax"]),
            money(parsed["total"]), FrozenCostCoverage.entries.first { it.encoded == parsed["coverage"].textValue() },
            parsed["unpriced_selection_ids"].map { it.textValue() }, parsed["warnings"].map { it.textValue() }, json,
        ))
    }

    private fun money(node: JsonNode): FrozenCostMoney =
        FrozenCostMoney(node["amount"].textValue(), node["currency"].textValue())
}
