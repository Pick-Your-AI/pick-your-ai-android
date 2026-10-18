package ai.pickyour.core.pricing

import java.io.InputStream
import java.time.Instant
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
    val pricebookHash: String,
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
    fun screen(input: InputStream, query: RateQuery): RateScreenResult =
        when (val parsed = RecordedPricebook.read(input)) {
            RecordedPricebookResult.InvalidDocument -> RateScreenResult.InvalidDocument
            is RecordedPricebookResult.Parsed -> screen(parsed.pricebook, query)
        }

    /** Uses the same immutable snapshot the caller can inspect; still never accepts or prices a rate. */
    fun screen(book: RecordedPricebook, query: RateQuery): RateScreenResult.Screened {
        val ids = ArrayList<String>()
        val gaps = mutableSetOf(RateGap.EVIDENCE_REVIEW_REQUIRED)
        for (rate in book.ratesFor(query.itemId, query.itemVersion)) {
            if (rate.meter != query.meter || rate.unitPrice.currency != query.currency ||
                query.asOf < rate.startsAt || (rate.endsAt != null && query.asOf >= rate.endsAt)) continue
            val region = rate.region
            if (region.isNullOrBlank()) { gaps.add(RateGap.REGION_UNSPECIFIED); continue }
            if (region != query.region) continue
            if (rate.conditions?.isNotEmpty() == true) { gaps.add(RateGap.CONDITIONS_UNRESOLVED); continue }
            if (rate.priceType != "list") { gaps.add(RateGap.NON_LIST_PRICE); continue }
            ids.add(rate.id)
        }
        if (ids.isEmpty()) gaps.add(RateGap.NO_MATCH)
        if (ids.size > 1) gaps.add(RateGap.AMBIGUOUS)
        return RateScreenResult.Screened(RateCandidates(
            book.id, book.revision.toString(), book.hash, query.asOf, ids, gaps,
        ))
    }
}
