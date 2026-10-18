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
