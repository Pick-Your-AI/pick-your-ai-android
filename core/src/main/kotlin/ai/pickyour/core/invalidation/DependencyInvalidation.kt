package ai.pickyour.core.invalidation

import ai.pickyour.core.contracts.DependencyActivation
import ai.pickyour.core.contracts.DependencyGraph

/** Typed projection of an already validated frozen-v1 edge, not an alternate wire contract. */
data class InvalidationEdge(
    val id: String,
    val dependentKind: String,
    val dependsOnKind: String,
    val activation: EdgeActivation,
)

enum class EdgeActivation {
    ALWAYS, SELF_HOSTED, NON_API, RAG_ENABLED, AGENTS_ENABLED,
    FINE_TUNING_ENABLED, IF_SELECTED, PRODUCTION_ONLY,
}

enum class DeploymentMode { API, MANAGED, SELF_HOSTED, HYBRID }

/** Include disabled selections too: invalidation must retain the identity of a disabled/removed choice. */
data class InvalidationSelection(val id: String, val kind: String, val enabled: Boolean = true)

/**
 * Caller supplies a resolved mode and production-target state from pinned inputs; null means unknown.
 * This adapter neither resolves catalog references nor claims a draft was durably saved.
 */
class InvalidationContext(
    selections: List<InvalidationSelection>,
    val mode: DeploymentMode? = null,
    val production: Boolean? = null,
) {
    internal val selections = selections.toList()
    internal val selectedKinds = selections.filter { it.enabled }.map { it.kind }.toSet()
}

data class InvalidationResult(
    val changedKinds: List<String>,
    /** Direct neighbours only, excluding changedKinds, as in the website reference. */
    val affectedKinds: List<String>,
    /** Includes changed kinds and both old/new identities, even when removed or disabled. */
    val selectionIdsToRecheck: List<String>,
    val viaEdgeIds: List<String>,
)

/**
 * Pure direct-adjacency invalidation, never compatibility evaluation or selection mutation.
 * Every edge is inspected once; cycles and self-loops cannot schedule recursive work.
 * Full evaluation remains the safe fallback when callers cannot identify all changed kinds.
 * Input graph must be schema/version validated before projecting its edges into this API.
 */
object DependencyInvalidation {
    /**
     * Explicit projection of a decoded graph into the existing direct-adjacency policy below.
     * Structural decoding does not establish the graph's authority or its binding to a build.
     * Relationship/rationale stay in the immutable graph; this policy already treats all relationships
     * as recheck neighbours and does not reinterpret them as compatibility requirements.
     */
    fun affectedByChange(
        graph: DependencyGraph,
        changedKinds: Set<String>,
        before: InvalidationContext,
        after: InvalidationContext,
    ): InvalidationResult = affectedByChange(graph.edges.map { edge ->
        val activation = when (edge.activation) {
            DependencyActivation.ALWAYS -> EdgeActivation.ALWAYS
            DependencyActivation.SELF_HOSTED -> EdgeActivation.SELF_HOSTED
            DependencyActivation.NON_API -> EdgeActivation.NON_API
            DependencyActivation.RAG_ENABLED -> EdgeActivation.RAG_ENABLED
            DependencyActivation.AGENTS_ENABLED -> EdgeActivation.AGENTS_ENABLED
            DependencyActivation.FINE_TUNING_ENABLED -> EdgeActivation.FINE_TUNING_ENABLED
            DependencyActivation.IF_SELECTED -> EdgeActivation.IF_SELECTED
            DependencyActivation.PRODUCTION_ONLY -> EdgeActivation.PRODUCTION_ONLY
        }
        InvalidationEdge(edge.id, edge.dependentKind, edge.dependsOnKind, activation)
    }, changedKinds, before, after)

    fun affectedByChange(
        edges: List<InvalidationEdge>,
        changedKinds: Set<String>,
        before: InvalidationContext,
        after: InvalidationContext,
    ): InvalidationResult {
        val changed = changedKinds.toSet()
        val affected = mutableSetOf<String>()
        val via = mutableSetOf<String>()
        for (edge in edges) {
            if (edge.dependentKind !in changed && edge.dependsOnKind !in changed) continue
            if (active(edge, before) == false && active(edge, after) == false) continue
            affected.add(edge.dependentKind)
            affected.add(edge.dependsOnKind)
            via.add(edge.id)
        }
        affected.removeAll(changed)
        val recheckKinds = affected + changed
        val selections = (before.selections + after.selections)
            .filter { it.kind in recheckKinds }.map { it.id }.distinct().sorted()
        return InvalidationResult(changed.sorted(), affected.sorted(), selections, via.sorted())
    }

    private fun active(edge: InvalidationEdge, context: InvalidationContext): Boolean? = when (edge.activation) {
        EdgeActivation.ALWAYS -> true
        EdgeActivation.SELF_HOSTED -> context.mode?.let { it == DeploymentMode.SELF_HOSTED || it == DeploymentMode.HYBRID }
        EdgeActivation.NON_API -> context.mode?.let { it != DeploymentMode.API }
        EdgeActivation.RAG_ENABLED -> "rag_pipeline" in context.selectedKinds
        EdgeActivation.AGENTS_ENABLED -> "agent_framework" in context.selectedKinds
        EdgeActivation.FINE_TUNING_ENABLED -> context.selectedKinds.any { it in trainingKinds }
        EdgeActivation.IF_SELECTED -> edge.dependentKind in context.selectedKinds && edge.dependsOnKind in context.selectedKinds
        EdgeActivation.PRODUCTION_ONLY -> context.production
    }

    // Frozen registry T5 kinds; changing this requires a versioned contract/consumer review.
    private val trainingKinds = setOf("training_framework", "training_compute", "training_dataset", "fine_tuned_model")
}
