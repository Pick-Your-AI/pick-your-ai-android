package ai.pickyour.core.drafts

import java.util.Collections

private fun <K, V> frozenMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
private fun <T> frozenSet(source: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))

/** Only T3–T5 can be toggled. T6 readiness remains a core concern. */
enum class OptionalTier { T3, T4, T5 }
enum class Activation { ENABLED, DISABLED, UNKNOWN }

/** Opaque local editing inputs, not validated catalog references or contract extensions. */
class TierDraft(
    val activation: Activation = Activation.DISABLED,
    selections: Set<String> = emptySet(),
    settings: Map<String, String> = emptyMap(),
) {
    val selections: Set<String> = frozenSet(selections)
    val settings: Map<String, String> = frozenMap(settings)
    fun withActivation(value: Activation): TierDraft = TierDraft(value, selections, settings)
    override fun equals(other: Any?): Boolean = other is TierDraft &&
        activation == other.activation && selections == other.selections && settings == other.settings
    override fun hashCode(): Int = 31 * (31 * activation.hashCode() + selections.hashCode()) + settings.hashCode()
    override fun toString(): String = "TierDraft"
}

/**
 * Immutable incomplete editing content. Null means unanswered; empty strings are retained as edits.
 * No catalog IDs, default prices or validation verdicts are synthesized. This is not BuildSnapshot.
 */
class DraftContent(
    val objective: String? = null,
    val context: String? = null,
    val primaryModel: String? = null,
    optional: Map<OptionalTier, TierDraft> = emptyMap(),
) {
    val optional: Map<OptionalTier, TierDraft> = frozenMap(
        OptionalTier.entries.associateWith { optional[it] ?: TierDraft() },
    )

    fun withTier(tier: OptionalTier, value: TierDraft): DraftContent =
        DraftContent(objective, context, primaryModel, optional + (tier to value))

    /** Disabled and unknown inputs never enter the active cost/rule input projection. */
    fun activeOptionalSelections(): Map<OptionalTier, Set<String>> = frozenMap(
        optional.filterValues { it.activation == Activation.ENABLED }.mapValues { it.value.selections },
    )

    /** Consumers must surface these unknowns; an empty active projection is not a zero cost/pass. */
    fun unknownActivations(): Set<OptionalTier> = frozenSet(
        optional.filterValues { it.activation == Activation.UNKNOWN }.keys,
    )

    override fun equals(other: Any?): Boolean = other is DraftContent && objective == other.objective &&
        context == other.context && primaryModel == other.primaryModel && optional == other.optional
    override fun hashCode(): Int = 31 * (31 * (31 * (objective?.hashCode() ?: 0) +
        (context?.hashCode() ?: 0)) + (primaryModel?.hashCode() ?: 0)) + optional.hashCode()
    override fun toString(): String = "DraftContent"
}

/** Descriptions are deterministic codes, never interpolated user content. */
enum class RevisionChange { INITIAL, EDIT, RESTORE }

/** Opaque history-owned token; a same-number revision from another history is never accepted. */
class DraftRevision internal constructor(
    val number: Long,
    val content: DraftContent,
    metadata: Map<String, String>,
    val change: RevisionChange,
    val restoredFrom: Long? = null,
) {
    /** Local lock/version metadata; restoring content retains the current metadata. */
    val metadata: Map<String, String> = frozenMap(metadata)
}

sealed interface DraftEditResult {
    class Applied(val revision: DraftRevision, val previous: DraftRevision) : DraftEditResult
    class Unchanged(val revision: DraftRevision) : DraftEditResult
    class Conflict(val current: DraftRevision) : DraftEditResult
    data object UnknownRevision : DraftEditResult
}

/**
 * In-memory ordering only: Applied does NOT mean saved. No IO, wire format, migration, trimming,
 * remote synchronization or process-death recovery. A durable adapter must atomically persist and
 * acknowledge revisions before presenting saved feedback. Callers retain conflicted pending edits.
 */
class DraftHistory(initial: DraftContent = DraftContent(), metadata: Map<String, String> = emptyMap()) {
    private val revisions = mutableListOf(DraftRevision(0, initial, metadata, RevisionChange.INITIAL))

    @Synchronized
    fun head(): DraftRevision = revisions.last()

    @Synchronized
    fun history(): List<DraftRevision> = Collections.unmodifiableList(ArrayList(revisions))

    /** Same content and metadata are a no-op; stale tokens still conflict before deduplication. */
    @Synchronized
    fun edit(
        expectedHead: DraftRevision,
        content: DraftContent,
        metadata: Map<String, String> = expectedHead.metadata,
    ): DraftEditResult {
        val current = head()
        if (current !== expectedHead) return DraftEditResult.Conflict(current)
        if (current.content == content && current.metadata == metadata) return DraftEditResult.Unchanged(current)
        return append(current, content, metadata, RevisionChange.EDIT)
    }

    /** Always creates a new revision, even when the restored content already matches the head. */
    @Synchronized
    fun restore(expectedHead: DraftRevision, target: DraftRevision): DraftEditResult {
        val current = head()
        if (current !== expectedHead) return DraftEditResult.Conflict(current)
        if (revisions.none { it === target }) return DraftEditResult.UnknownRevision
        return append(current, target.content, current.metadata, RevisionChange.RESTORE, target.number)
    }

    private fun append(
        previous: DraftRevision,
        content: DraftContent,
        metadata: Map<String, String>,
        change: RevisionChange,
        restoredFrom: Long? = null,
    ): DraftEditResult.Applied {
        val revision = DraftRevision(Math.incrementExact(previous.number), content, metadata, change, restoredFrom)
        revisions.add(revision)
        return DraftEditResult.Applied(revision, previous)
    }
}
