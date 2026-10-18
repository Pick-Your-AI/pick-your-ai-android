package ai.pickyour.core.comparison

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

/** Explicit selected build/revision identity; no lookup of a latest revision. */
data class SnapshotIdentity(val buildId: String, val revision: BigInteger) {
    init { require(buildId.isNotBlank() && revision.signum() > 0) }
    override fun toString(): String = "SnapshotIdentity"
}
enum class SnapshotProblem { INVALID_INPUT, SCHEMA_MISMATCH, IDENTITY_MISMATCH, DUPLICATE_SELECTION_ID, DUPLICATE_LINK_ID }
sealed interface SnapshotRead {
    class Available(val snapshot: ComparisonSnapshot) : SnapshotRead
    data class Rejected(val reason: SnapshotProblem) : SnapshotRead
}

/** Owns a private parsed copy. No writable source node or mutable collection is exposed. */
class ComparisonSnapshot private constructor(private val document: ObjectNode, val identity: SnapshotIdentity) {
    val locks: Map<String, String> = Collections.unmodifiableMap(
        document["locks"].properties().associate { it.key to if (it.value.isTextual) it.value.textValue() else it.value.toString() },
    )
    internal fun copyDocument(): ObjectNode = document.deepCopy()
    internal fun hasSameRecordedLocks(other: ComparisonSnapshot): Boolean =
        sameJsonValues(document["locks"], other.document["locks"])
    override fun toString(): String = "ComparisonSnapshot"

    companion object {
        private val validator = FrozenSchemaValidator()
        private val mapper = ObjectMapper()

        /** Caller owns the stream. Uses the shared byte/depth/token limits and unchanged frozen schema. */
        fun read(input: InputStream, expected: SnapshotIdentity): SnapshotRead {
            val parsed = BoundedJsonReader().read(input) as? JsonInputResult.Parsed
                ?: return SnapshotRead.Rejected(SnapshotProblem.INVALID_INPUT)
            val node = parsed.value
            if (validator.validate(FrozenDocument.BUILD, mapper.writeValueAsBytes(node).inputStream()) != FrozenValidation.Valid) {
                return SnapshotRead.Rejected(SnapshotProblem.SCHEMA_MISMATCH)
            }
            val actual = SnapshotIdentity(node["id"].textValue(), node["revision"].bigIntegerValue())
            if (actual != expected) return SnapshotRead.Rejected(SnapshotProblem.IDENTITY_MISMATCH)
            fun duplicate(field: String): Boolean {
                val ids = node[field].map { it["id"].textValue() }
                return ids.toSet().size != ids.size
            }
            if (duplicate("selections")) return SnapshotRead.Rejected(SnapshotProblem.DUPLICATE_SELECTION_ID)
            if (duplicate("links")) return SnapshotRead.Rejected(SnapshotProblem.DUPLICATE_LINK_ID)
            return SnapshotRead.Available(ComparisonSnapshot(node, actual))
        }
    }
}

enum class ChangeKind { ADDED, REMOVED, CHANGED }
enum class ChangeCategory { SELECTION, ROLE, SETTING, RELATIONSHIP, OBJECTIVE, LOCK, POLICY, METADATA }
/** Values are JSON encodings; null means absent, while the string "null" means an explicit JSON null. */
class SnapshotChange internal constructor(
    val path: String, val category: ChangeCategory, val kind: ChangeKind,
    val beforeJson: String?, val afterJson: String?,
) {
    override fun toString(): String = "SnapshotChange"
}
enum class SnapshotIdentityCondition { DISTINCT_IDENTITIES, SAME_IDENTITY, SAME_IDENTITY_WITH_DIFFERENCES }
enum class ComparisonCompleteness { COMPLETE, LIMIT_EXCEEDED }
enum class HistoricalOutputState { NOT_LOADED }
enum class EvidenceCondition { RECORDED_LOCKS_DIFFER, SAME_RECORDED_LOCKS_NOT_PROOF_OF_COMPARABILITY }

class BuildComparison internal constructor(
    val before: ComparisonSnapshot,
    val after: ComparisonSnapshot,
    changes: List<SnapshotChange>,
    limitExceeded: Boolean,
) {
    /** No partial diff is returned when the limit is exceeded. */
    val completeness = if (limitExceeded) ComparisonCompleteness.LIMIT_EXCEEDED else ComparisonCompleteness.COMPLETE
    val changes: List<SnapshotChange> = Collections.unmodifiableList(ArrayList(changes))
    /** Same recorded identity with differences is an integrity discrepancy, not a new revision. */
    val identityCondition: SnapshotIdentityCondition = when {
        before.identity != after.identity -> SnapshotIdentityCondition.DISTINCT_IDENTITIES
        changes.isEmpty() && !limitExceeded -> SnapshotIdentityCondition.SAME_IDENTITY
        else -> SnapshotIdentityCondition.SAME_IDENTITY_WITH_DIFFERENCES
    }
    val evidenceCondition: EvidenceCondition = if (before.hasSameRecordedLocks(after))
        EvidenceCondition.SAME_RECORDED_LOCKS_NOT_PROOF_OF_COMPARABILITY else EvidenceCondition.RECORDED_LOCKS_DIFFER
    /** No historical report was supplied or recomputed. This never means zero cost or no findings. */
    val historicalCost = HistoricalOutputState.NOT_LOADED
    val historicalFindings = HistoricalOutputState.NOT_LOADED
    override fun toString(): String = "BuildComparison"
}

/** Read-only structural comparison, not a semantic evaluator or permission to disclose private builds. */
object BuildComparator {
    fun compare(before: ComparisonSnapshot, after: ComparisonSnapshot, maxChanges: Int = 10_000): BuildComparison {
        require(maxChanges in 1..10_000)
        val left = before.copyDocument()
        val right = after.copyDocument()
        val changes = mutableListOf<SnapshotChange>()
        fun record(path: String, category: ChangeCategory, a: JsonNode?, b: JsonNode?) {
            if (changes.size >= maxChanges) throw ComparisonLimitReached()
            val kind = if (a == null) ChangeKind.ADDED else if (b == null) ChangeKind.REMOVED else ChangeKind.CHANGED
            changes += SnapshotChange(path, category, kind, a?.toString(), b?.toString())
        }
        fun walk(path: String, category: ChangeCategory, a: JsonNode?, b: JsonNode?) {
            if (sameJsonValues(a, b)) return
            if (a != null && b != null && a.isObject && b.isObject) {
                (a.fieldNames().asSequence().toSet() + b.fieldNames().asSequence().toSet()).sorted().forEach { field ->
                    walk("$path/${escape(field)}", category, a[field], b[field])
                }
            } else record(path, category, a, b) // Arrays are atomic; no invented element identity/order semantics.
        }
        fun indexed(field: String) {
            val a = left[field].associateBy { it["id"].textValue() }
            val b = right[field].associateBy { it["id"].textValue() }
            (a.keys + b.keys).sorted().forEach { id ->
                val path = "/$field/${escape(id)}"
                val old = a[id]
                val new = b[id]
                if (old == null || new == null) record(path, if (field == "links") ChangeCategory.RELATIONSHIP else ChangeCategory.SELECTION, old, new)
                else if (field == "links") walk(path, ChangeCategory.RELATIONSHIP, old, new)
                else (old.fieldNames().asSequence().toSet() + new.fieldNames().asSequence().toSet()).sorted().forEach { key ->
                    val category = when (key) { "settings" -> ChangeCategory.SETTING; "role" -> ChangeCategory.ROLE; else -> ChangeCategory.SELECTION }
                    walk("$path/${escape(key)}", category, old[key], new[key])
                }
            }
        }
        var limitExceeded = false
        try {
            indexed("selections")
            indexed("links")
            (left.fieldNames().asSequence().toSet() + right.fieldNames().asSequence().toSet()).sorted()
                .filter { it != "selections" && it != "links" }.forEach { key ->
                    val category = when (key) {
                        "objectives" -> ChangeCategory.OBJECTIVE
                        "locks" -> ChangeCategory.LOCK
                        "policy" -> ChangeCategory.POLICY
                        else -> ChangeCategory.METADATA
                    }
                    walk("/${escape(key)}", category, left[key], right[key])
                }
        } catch (_: ComparisonLimitReached) { limitExceeded = true }
        return BuildComparison(before, after, if (limitExceeded) emptyList() else changes.sortedBy { it.path }, limitExceeded)
    }
    private class ComparisonLimitReached : RuntimeException()
    private fun escape(value: String): String = value.replace("~", "~0").replace("/", "~1")
}

/** Exact value comparison for already bounded retained JSON; numeric spellings may differ, strings stay literal. */
internal fun sameJsonValues(a: JsonNode?, b: JsonNode?): Boolean = when {
    a === b -> true
    a == null || b == null -> false
    a.isNumber && b.isNumber -> a.decimalValue().compareTo(b.decimalValue()) == 0
    a.isObject && b.isObject -> a.size() == b.size() &&
        a.properties().all { (name, value) -> sameJsonValues(value, b[name]) }
    a.isArray && b.isArray -> a.size() == b.size() &&
        (0 until a.size()).all { index -> sameJsonValues(a[index], b[index]) }
    else -> a == b
}
