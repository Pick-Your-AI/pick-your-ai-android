package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import java.util.Collections

enum class ReferenceSelectionUnavailable { MISSING, AMBIGUOUS }

/** Only recorded identity fields; no settings, notes, extensions, owner or credential references. */
sealed interface ReferenceSelectionContext {
    val selectionId: String

    class Recorded internal constructor(
        override val selectionId: String,
        val kind: String,
        val role: String,
        val catalogItemId: String,
        val catalogItemVersion: String,
        val enabled: Boolean,
    ) : ReferenceSelectionContext {
        override fun toString() = "ReferenceSelectionContext.Recorded"
    }

    class Unavailable internal constructor(
        override val selectionId: String,
        val reason: ReferenceSelectionUnavailable,
    ) : ReferenceSelectionContext {
        override fun toString() = "ReferenceSelectionContext.Unavailable"
    }
}

/** Called only after the same build/report has passed the existing frozen receiving checks. */
internal fun recordedSelectionContexts(build: JsonNode): Map<String, ReferenceSelectionContext> {
    // Include disabled rows in ambiguity checks: an ID must identify exactly one recorded selection.
    val contexts = build["selections"].groupBy { it["id"].asText() }.mapValues { (id, matches) ->
        val selection = matches.singleOrNull()
        if (selection == null) ReferenceSelectionContext.Unavailable(id, ReferenceSelectionUnavailable.AMBIGUOUS)
        else ReferenceSelectionContext.Recorded(
            id, selection["kind"].asText(), selection["role"].asText(),
            selection["catalog_item_id"].asText(), selection["catalog_item_version"].asText(),
            selection["enabled"].asBoolean(),
        )
    }
    return Collections.unmodifiableMap(LinkedHashMap(contexts))
}
