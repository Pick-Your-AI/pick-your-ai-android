package ai.pickyour.core.preview

import ai.pickyour.core.drafts.DraftContent
import java.util.Collections

/** Frozen T6 kinds remain planning concerns independently of the optional T3–T5 switches. */
enum class PreviewT6Kind(val catalogKind: String) {
    GUARDRAIL("guardrail"), EVALUATION("evaluation_suite"), DEPLOYMENT("deployment_target"),
    MONITORING("monitoring_stack"), COST_POLICY("cost_policy"),
}

/** Counts describe recorded configuration only; enabled never means effective or operational. */
data class PreviewT6Choices(val kind: PreviewT6Kind, val enabled: Int, val disabled: Int)

object PreviewT6Guidance {
    fun recorded(record: PreviewStoredDraft): List<PreviewT6Choices> = recorded(record.draft.content)

    /** Pure inspection of one captured draft. No required choice, measured result or verdict is inferred. */
    fun recorded(content: DraftContent): List<PreviewT6Choices> = Collections.unmodifiableList(
        PreviewT6Kind.entries.map { kind ->
            val choices = content.components.selections.filter { it.kind == kind.catalogKind }
            PreviewT6Choices(kind, choices.count { it.enabled }, choices.count { !it.enabled })
        },
    )
}
