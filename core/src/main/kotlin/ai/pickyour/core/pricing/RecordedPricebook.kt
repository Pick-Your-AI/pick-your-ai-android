package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.ContractMoney
import ai.pickyour.core.contracts.ContractRejected
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.evidence.EvidencePresentationPolicy
import ai.pickyour.core.evidence.FrozenVerificationStatus
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.localSnapshotRevision
import java.io.InputStream
import java.math.BigInteger
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Collections

/** Recorded fields only. A declared status supplies no independent assessment or source-use right. */
class RecordedRateProvenance internal constructor(
    val sourceUri: String?,
    val checkedAt: String,
    val rawStatus: FrozenVerificationStatus,
    val notes: String?,
) {
    val label: EvidenceLabel get() = EvidencePresentationPolicy.unassessedLabel(rawStatus)
    override fun toString() = "RecordedRateProvenance"
}

/** Exact immutable metadata; absent conditions and an explicitly empty array remain distinct. */
class RecordedPriceRate internal constructor(
    val id: String,
    val itemId: String,
    val itemVersion: String,
    val meter: String,
    val region: String?,
    val unitPrice: ContractMoney,
    val validFrom: String,
    val validUntil: String?,
    val priceType: String,
    conditions: List<String>?,
    val provenance: RecordedRateProvenance,
    internal val startsAt: Instant,
    internal val endsAt: Instant?,
) {
    val conditions: List<String>? = conditions?.let { Collections.unmodifiableList(ArrayList(it)) }
    override fun toString() = "RecordedPriceRate"
}

sealed interface RecordedPricebookResult {
    data object InvalidDocument : RecordedPricebookResult
    data object UnsupportedSchemaVersion : RecordedPricebookResult
    class Parsed(val pricebook: RecordedPricebook) : RecordedPricebookResult {
        override fun toString() = "RecordedPricebookResult.Parsed"
    }
}

/**
 * One bounded frozen-v1 snapshot shared by metadata inspection and conservative candidate screening.
 * Hash covers the received bytes, including whitespace, and proves no publisher identity or freshness.
 * Exact identity lookup preserves every alternative; it does not evaluate applicability or return a quote.
 */
class RecordedPricebook private constructor(
    val id: String,
    val revision: BigInteger,
    val capturedAt: String,
    val hash: String,
    rates: List<RecordedPriceRate>,
) {
    val rates: List<RecordedPriceRate> = Collections.unmodifiableList(ArrayList(rates))
    private val byItem = this.rates.groupBy { it.itemId to it.itemVersion }.mapValues {
        Collections.unmodifiableList(ArrayList(it.value))
    }

    fun ratesFor(itemId: String, itemVersion: String): List<RecordedPriceRate> =
        byItem[itemId to itemVersion] ?: emptyList()

    override fun toString() = "RecordedPricebook"

    companion object {
        /** No stream closure, network, clock, draft mutation or interpretation of free-text conditions. */
        fun read(input: InputStream): RecordedPricebookResult {
            val digest = MessageDigest.getInstance("SHA-256")
            val parsed = BoundedJsonReader().read(DigestInputStream(input, digest))
            if (parsed !is JsonInputResult.Parsed) return RecordedPricebookResult.InvalidDocument
            val book = parsed.value
            val version = book["schema_version"]
            if (version?.isTextual == true && version.textValue() != "1.0.0")
                return RecordedPricebookResult.UnsupportedSchemaVersion
            if (FrozenSchemaValidator().validate(FrozenDocument.PRICEBOOK, book.toString().byteInputStream()) != FrozenValidation.Valid) {
                return RecordedPricebookResult.InvalidDocument
            }
            val revision = localSnapshotRevision(book["revision"]) ?: return RecordedPricebookResult.InvalidDocument
            val seen = HashSet<String>()
            val rates = ArrayList<RecordedPriceRate>()
            try {
                // Retain RateScreen's supported-date and whole-document interval checks.
                timestamp(book["captured_at"].textValue())
                for (rate in book["rates"]) {
                    val id = rate["id"].textValue()
                    if (!seen.add(id)) return RecordedPricebookResult.InvalidDocument
                    val from = rate["valid_from"].textValue()
                    val until = rate["valid_until"]?.textValue()
                    val start = timestamp(from)
                    val end = until?.let(::timestamp)
                    if (end != null && end <= start) return RecordedPricebookResult.InvalidDocument
                    val source = rate["provenance"]
                    rates.add(RecordedPriceRate(
                        id, rate["catalog_item_id"].textValue(), rate["catalog_item_version"].textValue(),
                        rate["meter"].textValue(), rate["region"]?.textValue(), ContractMoney.fromJson(rate["unit_price"]),
                        from, until, rate["price_type"].textValue(), rate["conditions"]?.map { it.textValue() },
                        RecordedRateProvenance(source["source_uri"]?.textValue(), source["checked_at"].textValue(),
                            FrozenVerificationStatus.entries.single { it.encoded == source["verification_status"].textValue() },
                            source["notes"]?.textValue()),
                        start, end,
                    ))
                }
            } catch (_: DateTimeParseException) {
                return RecordedPricebookResult.InvalidDocument
            } catch (_: ContractRejected) {
                return RecordedPricebookResult.InvalidDocument
            }
            val hash = digest.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            return RecordedPricebookResult.Parsed(RecordedPricebook(
                book["id"].textValue(), revision, book["captured_at"].textValue(), hash, rates,
            ))
        }

        private fun timestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()
    }
}
