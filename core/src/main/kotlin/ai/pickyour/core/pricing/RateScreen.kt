package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Collections

/** Exact explicit query; no region, currency, clock or component-version defaults. */
class RateQuery(
    val itemId: String,
    val itemVersion: String,
    val meter: String,
    val region: String,
    val currency: String,
    val asOf: Instant,
) {
    init {
        require(listOf(itemId, itemVersion, meter, region).all { it.isNotBlank() }) { "Incomplete rate query" }
        require(Regex("[A-Z]{3}").matches(currency)) { "Invalid currency" }
    }
    override fun toString() = "RateQuery"
}

/** Candidate IDs point back to the exact caller-owned pricebook; no amounts or quotes are produced. */
class RateCandidates internal constructor(
    val pricebookId: String,
    val pricebookRevision: String,
    val asOf: Instant,
    candidates: List<String>,
    unresolved: Set<RateGap>,
) {
    val rateIds: List<String> = Collections.unmodifiableList(ArrayList(candidates))
    val gaps: Set<RateGap> = Collections.unmodifiableSet(HashSet(unresolved))
    override fun toString() = "RateCandidates"
}

enum class RateGap {
    NO_MATCH, REGION_UNSPECIFIED, CONDITIONS_UNRESOLVED, NON_LIST_PRICE,
    AMBIGUOUS, EVIDENCE_REVIEW_REQUIRED,
}
sealed interface RateScreenResult {
    data object InvalidDocument : RateScreenResult
    class Screened(val candidates: RateCandidates) : RateScreenResult {
        override fun toString() = "RateScreenResult.Screened"
    }
}

/**
 * Conservative frozen-v1 discovery only. A singleton is NOT an accepted rate or complete meter set.
 * Conditions are free text in v1: never interpret them as eligibility, discounts or additive meters.
 * End timestamps are conservatively excluded; this is not a new shared billing interval policy.
 * Schema validation and screening use one bounded private snapshot. No clock, network or mutation.
 */
class RateScreen {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun screen(input: InputStream, query: RateQuery): RateScreenResult {
        val parsed = reader.read(input)
        if (parsed !is JsonInputResult.Parsed) return RateScreenResult.InvalidDocument
        val book = parsed.value
        if (validator.validate(FrozenDocument.PRICEBOOK, book.toString().byteInputStream()) != FrozenValidation.Valid) {
            return RateScreenResult.InvalidDocument
        }
        val seen = HashSet<String>()
        val ids = ArrayList<String>()
        val gaps = mutableSetOf(RateGap.EVIDENCE_REVIEW_REQUIRED)
        try {
            // Schema-valid dates may exceed java.time's supported precision/range. Fail closed.
            timestamp(book["captured_at"].textValue())
            for (rate in book["rates"]) {
                val id = rate["id"].textValue()
                if (!seen.add(id)) return RateScreenResult.InvalidDocument
                val start = timestamp(rate["valid_from"].textValue())
                val end = rate["valid_until"]?.let { timestamp(it.textValue()) }
                if (end != null && end <= start) return RateScreenResult.InvalidDocument
                if (rate["catalog_item_id"].textValue() != query.itemId ||
                    rate["catalog_item_version"].textValue() != query.itemVersion ||
                    rate["meter"].textValue() != query.meter ||
                    rate["unit_price"]["currency"].textValue() != query.currency ||
                    query.asOf < start || (end != null && query.asOf >= end)) continue
                val region = rate["region"]?.textValue()
                if (region.isNullOrBlank()) { gaps.add(RateGap.REGION_UNSPECIFIED); continue }
                if (region != query.region) continue
                if (rate["conditions"]?.isEmpty == false) { gaps.add(RateGap.CONDITIONS_UNRESOLVED); continue }
                if (rate["price_type"].textValue() != "list") { gaps.add(RateGap.NON_LIST_PRICE); continue }
                ids.add(id)
            }
        } catch (_: DateTimeParseException) {
            return RateScreenResult.InvalidDocument
        }
        if (ids.isEmpty()) gaps.add(RateGap.NO_MATCH)
        if (ids.size > 1) gaps.add(RateGap.AMBIGUOUS)
        return RateScreenResult.Screened(RateCandidates(
            book["id"].textValue(), book["revision"].asText(), query.asOf, ids, gaps,
        ))
    }

    private fun timestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()
}
