package ai.pickyour.core.contracts

import ai.pickyour.core.evidence.FrozenVerificationStatus
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenPriceMeter(val encoded: String) {
    INPUT_TOKENS_1M("input_tokens_1m"), OUTPUT_TOKENS_1M("output_tokens_1m"),
    CACHED_INPUT_TOKENS_1M("cached_input_tokens_1m"), EMBEDDING_TOKENS_1M("embedding_tokens_1m"),
    REQUEST("request"), GPU_HOUR("gpu_hour"), CPU_HOUR("cpu_hour"), STORAGE_GB_MONTH("storage_gb_month"),
    VECTOR_GB_MONTH("vector_gb_month"), AGENT_RUN("agent_run"), SEAT_MONTH("seat_month"),
    LICENSE_MONTH("license_month"), ONE_TIME("one_time"),
}

enum class FrozenPriceType(val encoded: String) { LIST("list"), CONTRACT("contract"), ESTIMATE("estimate") }

/** Inert frozen strings, including the admitted terminal LF; no amount conversion or ISO lookup. */
class FrozenPricebookMoney internal constructor(val amount: String, val currency: String) {
    override fun toString(): String = "FrozenPricebookMoney"
}

/** Recorded provenance only. Neither a declared verified status nor a URI supplies an assessment. */
class FrozenPricebookProvenance internal constructor(
    val sourceUri: String?,
    val checkedAt: String,
    val verificationStatus: FrozenVerificationStatus,
    val notes: String?,
) {
    override fun toString(): String = "FrozenPricebookProvenance"
}

/** No chronology, condition interpretation, region default or catalog/rate identity lookup. */
class FrozenPriceRate internal constructor(
    val id: String,
    val catalogItemId: String,
    val catalogItemVersion: String,
    val meter: FrozenPriceMeter,
    val region: String?,
    val unitPrice: FrozenPricebookMoney,
    val validFrom: String,
    val validUntil: String?,
    val provenance: FrozenPricebookProvenance,
    conditions: List<String>?,
    val priceType: FrozenPriceType,
) {
    val conditions: List<String>? = conditions?.let { Collections.unmodifiableList(it.toList()) }
    override fun toString(): String = "FrozenPriceRate"
}

/**
 * Immutable structural projection preserving ordered duplicate rates and exact scalar values.
 * [json] preserves JSON values, not original layout, numeric spelling, source hash or signature.
 * The existing RecordedPricebook and RateScreen retain their separate admission/screening rules.
 */
class FrozenPricebook internal constructor(
    val schemaVersion: String,
    val id: String,
    val revision: BigInteger,
    val capturedAt: String,
    rates: List<FrozenPriceRate>,
    val json: String,
) {
    val rates: List<FrozenPriceRate> = Collections.unmodifiableList(rates.toList())
    override fun toString(): String = "FrozenPricebook"
}

sealed interface FrozenPricebookDecoding {
    data class Decoded(val pricebook: FrozenPricebook) : FrozenPricebookDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenPricebookDecoding
    data object UnsupportedVersion : FrozenPricebookDecoding
    data object SchemaMismatch : FrozenPricebookDecoding
}

/**
 * Bounded UTF-8 and fixed-schema decoding with caller-owned streams and sanitized failures.
 * Callers provide background execution and transport timeout/cancellation. Decoding grants no
 * screening, price calculation, evidence approval, URI fetch, clock, storage or UI authority.
 */
class FrozenPricebookDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenPricebookDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenPricebookDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenPricebookDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.PRICEBOOK, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenPricebookDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenPricebookDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        val revision = try {
            parsed["revision"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return FrozenPricebookDecoding.SchemaMismatch
        }
        // The unchanged schema has validated every field. Preserve its weaker structural profile
        // without altering RecordedPricebook's existing strict money, interval and revision rules.
        val rates = parsed["rates"].map { rate ->
            val source = rate["provenance"]
            val money = rate["unit_price"]
            FrozenPriceRate(
                rate["id"].textValue(), rate["catalog_item_id"].textValue(), rate["catalog_item_version"].textValue(),
                FrozenPriceMeter.entries.first { it.encoded == rate["meter"].textValue() },
                rate["region"]?.textValue(), FrozenPricebookMoney(money["amount"].textValue(), money["currency"].textValue()),
                rate["valid_from"].textValue(), rate["valid_until"]?.textValue(),
                FrozenPricebookProvenance(source["source_uri"]?.textValue(), source["checked_at"].textValue(),
                    FrozenVerificationStatus.entries.first { it.encoded == source["verification_status"].textValue() },
                    source["notes"]?.textValue()),
                rate["conditions"]?.map { it.textValue() },
                FrozenPriceType.entries.first { it.encoded == rate["price_type"].textValue() },
            )
        }
        return FrozenPricebookDecoding.Decoded(FrozenPricebook(parsed["schema_version"].textValue(),
            parsed["id"].textValue(), revision, parsed["captured_at"].textValue(), rates, json))
    }
}
