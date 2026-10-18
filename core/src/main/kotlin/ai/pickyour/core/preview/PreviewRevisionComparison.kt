package ai.pickyour.core.preview

import ai.pickyour.core.comparison.sameJsonValues
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.drafts.StoredRevision
import com.fasterxml.jackson.databind.JsonNode
import java.util.Collections

/** Exact local editing values. Null is absent/unanswered, not an empty string or numeric zero. */
class PreviewRevisionChange internal constructor(val field: String, val key: String?, val before: String?, val after: String?) {
    override fun toString() = "PreviewRevisionChange"
}
class PreviewRevisionComparison internal constructor(val from: Long, val to: Long,
    changes: List<PreviewRevisionChange>, val metadataChanged: Boolean, val guestFieldsComparable: Boolean) {
    val changes: List<PreviewRevisionChange> = Collections.unmodifiableList(ArrayList(changes))
    override fun toString() = "PreviewRevisionComparison"
}

/** Pure comparison within one loaded durable history; never adopts another revision's save token. */
fun PreviewStoredDraft.compareRevision(number: Long): PreviewRevisionComparison? = compareRevisions(number, revision)

/**
 * Compare exactly two retained revision numbers in the caller's captured immutable history.
 * Either side may precede the saved head. No latest lookup, storage read or save token is created.
 * A missing side is unavailable, never replaced with the head or another retained revision.
 */
fun PreviewStoredDraft.compareRevisions(from: Long, to: Long): PreviewRevisionComparison? {
    val old = draft.history.find { it.number == from } ?: return null
    val selected = draft.history.find { it.number == to } ?: return null
    val retainedForms = history
    return compareRetainedRevisions(old, selected, retainedForms.find { it.revision == from }?.fields,
        retainedForms.find { it.revision == to }?.fields)
}

/** Shared exact value comparison; callers must supply each selected revision's own guest fields. */
internal fun compareRetainedRevisions(old: StoredRevision, selected: StoredRevision,
    oldFields: PreviewDraftFields?, selectedFields: PreviewDraftFields?): PreviewRevisionComparison {
    val before = old.content
    val after = selected.content
    val changes = mutableListOf<PreviewRevisionChange>()
    fun add(field: String, left: String?, right: String?, key: String? = null) {
        if (left != right) changes += PreviewRevisionChange(field, key, left, right)
    }
    fun addJson(field: String, left: JsonNode?, right: JsonNode?, key: String) {
        if (!sameJsonValues(left, right))
            add(field, left?.let(::stableSelectionJson), right?.let(::stableSelectionJson), key)
    }
    add("objective", before.objective, after.objective)
    add("context", before.context, after.context)
    add("primary_model", before.primaryModel, after.primaryModel)
    add("workload.region", before.workload.region, after.workload.region)
    add("workload.currency", before.workload.currency, after.workload.currency)
    add("workload.monthly_budget_amount", before.workload.monthlyBudgetAmount?.encoded, after.workload.monthlyBudgetAmount?.encoded)
    add("workload.requests_per_day", before.workload.requestsPerDay?.toString(), after.workload.requestsPerDay?.toString())
    add("workload.avg_input_tokens", before.workload.avgInputTokens?.toString(), after.workload.avgInputTokens?.toString())
    add("workload.avg_output_tokens", before.workload.avgOutputTokens?.toString(), after.workload.avgOutputTokens?.toString())
    add("workload.max_concurrency", before.workload.maxConcurrency?.toString(), after.workload.maxConcurrency?.toString())
    add("workload.max_latency_ms", before.workload.maxLatencyMs?.toString(), after.workload.maxLatencyMs?.toString())
    add("workload.data_classification", before.workload.dataClassification?.encoded, after.workload.dataClassification?.encoded)
    val oldSelections = before.components.selections.associateBy { it.id }
    val newSelections = after.components.selections.associateBy { it.id }
    for (id in (oldSelections.keys + newSelections.keys).sorted()) {
        val left = oldSelections[id]?.toJson()
        val right = newSelections[id]?.toJson()
        for (field in listOf("kind", "catalog_item_id", "catalog_item_version", "role", "enabled", "settings", "notes", "extensions"))
            addJson("components.selection.$field", left?.get(field), right?.get(field), id)
    }
    val oldLinks = before.components.links.associateBy { it.id }
    val newLinks = after.components.links.associateBy { it.id }
    for (id in (oldLinks.keys + newLinks.keys).sorted())
        addJson("components.link", oldLinks[id]?.toJson(), newLinks[id]?.toJson(), id)
    for (tier in OptionalTier.entries) {
        val left = before.optional.getValue(tier)
        val right = after.optional.getValue(tier)
        add("${tier.name}.activation", left.activation.name, right.activation.name)
        for (id in (left.selections + right.selections).sorted())
            add("${tier.name}.selection", if (id in left.selections) "selected" else null,
                if (id in right.selections) "selected" else null, id)
        for (key in (left.settings.keys + right.settings.keys).sorted())
            add("${tier.name}.setting", left.settings[key], right.settings[key], key)
    }
    // Guest form metadata can be unavailable in older formats. Never synthesize it from content.
    if (oldFields != null && selectedFields != null) {
        add("guest.title", oldFields.title, selectedFields.title)
        add("guest.sample_id", oldFields.selectedId, selectedFields.selectedId)
        add("guest.sample_version", oldFields.selectedVersion, selectedFields.selectedVersion)
        add("guest.catalog_hash", oldFields.catalogHash, selectedFields.catalogHash)
    }
    return PreviewRevisionComparison(old.number, selected.number, changes, old.metadata != selected.metadata,
        oldFields != null && selectedFields != null)
}

/** Typed bounded display: a user literal can never impersonate an absent/empty sentinel. */
enum class PreviewComparedValueKind { ABSENT, EMPTY, TEXT }
class PreviewComparedValue private constructor(val kind: PreviewComparedValueKind,
    val quotedLiteral: String?, val truncated: Boolean) {
    companion object {
        fun from(raw: String?): PreviewComparedValue {
            if (raw == null) return PreviewComparedValue(PreviewComparedValueKind.ABSENT, null, false)
            if (raw.isEmpty()) return PreviewComparedValue(PreviewComparedValueKind.EMPTY, null, false)
            val escaped = buildString {
                append('"')
                for (char in raw.take(160)) when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (Character.isISOControl(char) || Character.isSurrogate(char) ||
                        char == '\u2028' || char == '\u2029' ||
                        Character.getType(char) == Character.FORMAT.toInt())
                        append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
                }
                append('"')
            }
            return PreviewComparedValue(PreviewComparedValueKind.TEXT, escaped, raw.length > 160)
        }
    }
    override fun toString() = "PreviewComparedValue"
}

/** Stable object field order, retaining JSON types, nulls and array order. No resource resolution. */
private fun stableSelectionJson(value: com.fasterxml.jackson.databind.JsonNode): String = when {
    value.isObject -> value.properties().sortedBy { it.key }.joinToString(",", "{", "}") {
        com.fasterxml.jackson.databind.node.TextNode(it.key).toString() + ":" + stableSelectionJson(it.value)
    }
    value.isArray -> value.joinToString(",", "[", "]") { stableSelectionJson(it) }
    else -> value.toString()
}
