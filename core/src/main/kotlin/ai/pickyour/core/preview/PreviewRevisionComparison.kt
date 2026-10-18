package ai.pickyour.core.preview

import ai.pickyour.core.drafts.OptionalTier
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
fun PreviewStoredDraft.compareRevision(number: Long): PreviewRevisionComparison? {
    val old = draft.history.find { it.number == number } ?: return null
    val before = old.content
    val after = draft.content
    val changes = mutableListOf<PreviewRevisionChange>()
    fun add(field: String, left: String?, right: String?, key: String? = null) {
        if (left != right) changes += PreviewRevisionChange(field, key, left, right)
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
            add("components.selection.$field", left?.get(field)?.let(::stableSelectionJson), right?.get(field)?.let(::stableSelectionJson), id)
    }
    val oldLinks = before.components.links.associateBy { it.id }
    val newLinks = after.components.links.associateBy { it.id }
    for (id in (oldLinks.keys + newLinks.keys).sorted())
        add("components.link", oldLinks[id]?.toJson()?.let(::stableSelectionJson), newLinks[id]?.toJson()?.let(::stableSelectionJson), id)
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
    val oldFields = history.find { it.revision == number }?.fields
    if (oldFields != null) {
        add("guest.title", oldFields.title, fields.title)
        add("guest.sample_id", oldFields.selectedId, fields.selectedId)
        add("guest.sample_version", oldFields.selectedVersion, fields.selectedVersion)
        add("guest.catalog_hash", oldFields.catalogHash, fields.catalogHash)
    }
    return PreviewRevisionComparison(number, revision, changes, old.metadata != draft.metadata, oldFields != null)
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
