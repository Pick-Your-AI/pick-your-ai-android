package ai.pickyour.core.pricing

import ai.pickyour.core.contracts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

enum class CostRejection { INVALID_BUILD, INVALID_ESTIMATE, MISSING_PRICEBOOK_PIN, BINDING_MISMATCH,
    DUPLICATE_ID, UNKNOWN_SELECTION, MIXED_CURRENCY, INCONSISTENT_AMOUNTS, INCONSISTENT_COVERAGE }
enum class DeclaredCoverage { COMPLETE, PARTIAL, UNKNOWN }
enum class CostEvidence { NOT_ESTABLISHED }
sealed interface CostReception {
    data class Rejected(val reason: CostRejection) : CostReception
    class Inspected(val estimate: InspectedCostEstimate) : CostReception
}

/** Recorded amounts only. Inspection is not accepted pricing, freshness, free/included proof or a budget pass. */
class InspectedCostEstimate internal constructor(node: JsonNode, unclassified: Set<String>) {
    val estimateId: String = node["id"].textValue()
    val buildId: String = node["build_id"].textValue()
    val buildRevision: BigInteger = node["build_revision"].bigIntegerValue()
    val pricebookId: String = node["pricebook_id"].textValue()
    val periodDays: BigInteger = node["period_days"].bigIntegerValue()
    val estimatedAt: String = node["estimated_at"].textValue()
    val currency: String = node["currency"].textValue()
    val declaredCoverage = DeclaredCoverage.valueOf(node["coverage"].textValue().uppercase(java.util.Locale.ROOT))
    val reportedSubtotal: ContractMoney = ContractMoney.fromJson(node["subtotal"])
    val reportedTax: ContractMoney = ContractMoney.fromJson(node["tax"])
    val reportedTotal: ContractMoney = ContractMoney.fromJson(node["total"])
    val reportedUnpricedSelectionIds: Set<String> = immutableSet(node["unpriced_selection_ids"].map { it.textValue() }.toSet())
    /** A missing line is not proof that a component is free, included, or even priced. */
    val unclassifiedActiveSelectionIds: Set<String> = immutableSet(unclassified)
    val warnings: List<String> = Collections.unmodifiableList(node["warnings"].map { it.textValue() })
    /** Full immutable input preserves every line's quantity, rate ID, assumptions and original decimal strings. */
    val recordedJson: String = node.toString()
    val freshness = CostEvidence.NOT_ESTABLISHED
    val freeOrIncludedTreatment = CostEvidence.NOT_ESTABLISHED
    val budgetPass = CostEvidence.NOT_ESTABLISHED
    override fun toString(): String = "InspectedCostEstimate"
    private fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
}

/**
 * Bounded read-only inspection of frozen CostEstimate and its explicitly supplied BuildSnapshot.
 * Reconciles already-declared decimal amounts exactly; never derives quantities or rounds prices.
 * No pricebook revision, expiry threshold, rights, rate eligibility or complete meter coverage is inferred.
 * Even declared COMPLETE remains unreviewed. Partial/unknown totals are not complete stack prices or
 * proven lower bounds. UI adapters must use current AnalysisCoordinator tickets for asynchronous results.
 */
class CostEstimateAdapter {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun inspect(build: InputStream, estimate: InputStream): CostReception {
        val b = parse(FrozenDocument.BUILD, build) ?: return reject(CostRejection.INVALID_BUILD)
        val e = parse(FrozenDocument.COST_ESTIMATE, estimate) ?: return reject(CostRejection.INVALID_ESTIMATE)
        val pricebook = b["locks"]["pricebook_id"] ?: return reject(CostRejection.MISSING_PRICEBOOK_PIN)
        if (e["build_id"] != b["id"] || e["build_revision"].bigIntegerValue() != b["revision"].bigIntegerValue() ||
            e["pricebook_id"] != pricebook) return reject(CostRejection.BINDING_MISMATCH)
        val selections = b["selections"].toList()
        val selectionIds = selections.map { it["id"].textValue() }
        val lines = e["line_items"].toList()
        val lineIds = lines.map { it["id"].textValue() }
        if (selectionIds.distinct().size != selectionIds.size || lineIds.distinct().size != lineIds.size)
            return reject(CostRejection.DUPLICATE_ID)
        val active = selections.filter { it["enabled"].asBoolean() }.map { it["id"].textValue() }.toSet()
        val priced = lines.map { it["selection_id"].textValue() }.toSet()
        val unpriced = e["unpriced_selection_ids"].map { it.textValue() }.toSet()
        // A selection can have both a priced meter and an uncovered meter; overlap is retained.
        if (!(priced + unpriced).all { it in active }) return reject(CostRejection.UNKNOWN_SELECTION)
        if (e["coverage"].textValue() == "complete" && unpriced.isNotEmpty())
            return reject(CostRejection.INCONSISTENT_COVERAGE)
        val currency = e["currency"].textValue()
        val totals = listOf("subtotal", "tax", "total").map { e[it] }
        if ((totals + lines.flatMap { listOf(it["unit_price"], it["amount"]) }).any { it["currency"].textValue() != currency })
            return reject(CostRejection.MIXED_CURRENCY)
        fun amount(money: JsonNode): BigDecimal = ContractMoney.fromJson(money).amount.exactValue()
        val sum = lines.fold(BigDecimal.ZERO) { acc, line -> acc + amount(line["amount"]) }
        if (sum.compareTo(amount(e["subtotal"])) != 0 ||
            (amount(e["subtotal"]) + amount(e["tax"])).compareTo(amount(e["total"])) != 0)
            return reject(CostRejection.INCONSISTENT_AMOUNTS)
        return CostReception.Inspected(InspectedCostEstimate(e, active - priced - unpriced))
    }
    private fun parse(type: FrozenDocument, input: InputStream): JsonNode? {
        val node = (reader.read(input) as? JsonInputResult.Parsed)?.value ?: return null
        return if (validator.validate(type, node.toString().byteInputStream()) == FrozenValidation.Valid) node else null
    }
    private fun reject(reason: CostRejection) = CostReception.Rejected(reason)
}
