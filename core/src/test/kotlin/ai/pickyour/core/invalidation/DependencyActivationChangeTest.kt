package ai.pickyour.core.invalidation

import ai.pickyour.core.contracts.DependencyGraph
import ai.pickyour.core.contracts.DependencyGraphDecoding
import ai.pickyour.core.contracts.DependencyGraphDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DependencyActivationChangeTest {
    private fun context(
        vararg kinds: String,
        mode: DeploymentMode? = DeploymentMode.API,
        production: Boolean? = false,
    ) = InvalidationContext(kinds.map { InvalidationSelection("selection.$it", it) }, mode, production)

    private fun frozenGraph(): DependencyGraph =
        requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/dependency-graph.json")).use {
            (DependencyGraphDecoder().decode(it) as DependencyGraphDecoding.Decoded).graph
        }

    private fun decodedEdge(activation: String): DependencyGraph {
        val json = """{"schema_version":"1.0.0","id":"graph.synthetic","revision":1,"edges":[
            {"id":"edge.remote","dependent_kind":"accelerator","depends_on_kind":"model",
             "relationship":"informs","activation":"$activation","rationale":"Synthetic context transition"}]}"""
        return (DependencyGraphDecoder().decode(json.byteInputStream()) as DependencyGraphDecoding.Decoded).graph
    }

    private fun assertRemoteTransition(
        activation: String,
        changedKind: String,
        before: InvalidationContext,
        after: InvalidationContext,
        expectedIds: List<String> = listOf("selection.accelerator", "selection.model"),
    ) {
        val result = DependencyInvalidation.affectedByChange(decodedEdge(activation), setOf(changedKind), before, after)
        assertEquals(listOf(changedKind), result.changedKinds)
        assertEquals(listOf("accelerator", "model"), result.affectedKinds)
        assertEquals(expectedIds, result.selectionIdsToRecheck)
        assertEquals(listOf("edge.remote"), result.viaEdgeIds)
    }

    private fun assertFrozenModeTransition(beforeMode: DeploymentMode, afterMode: DeploymentMode) {
        val graph = frozenGraph()
        val retained = graph.json
        val kinds = arrayOf("accelerator", "compute_profile", "context_policy", "cost_policy",
            "deployment_mode", "deployment_target", "hosting_platform", "inference_engine", "model", "storage")
        val result = DependencyInvalidation.affectedByChange(
            graph, setOf("deployment_mode"), context(*kinds, mode = beforeMode), context(*kinds, mode = afterMode),
        )
        assertEquals(listOf("deployment_mode"), result.changedKinds)
        assertEquals(listOf("accelerator", "compute_profile", "context_policy", "cost_policy",
            "deployment_target", "hosting_platform", "inference_engine", "model"), result.affectedKinds)
        assertEquals(listOf("selection.accelerator", "selection.compute_profile", "selection.context_policy",
            "selection.cost_policy", "selection.deployment_mode", "selection.deployment_target",
            "selection.hosting_platform", "selection.inference_engine", "selection.model"), result.selectionIdsToRecheck)
        assertEquals(listOf("dep.accelerator.context_policy", "dep.accelerator.model",
            "dep.compute_profile.accelerator", "dep.compute_profile.hosting_platform", "dep.compute_profile.model",
            "dep.cost_policy.compute_profile", "dep.deployment_target.hosting_platform",
            "dep.hosting_platform.deployment_mode", "dep.inference_engine.accelerator",
            "dep.inference_engine.model", "dep.model.deployment_mode"), result.viaEdgeIds)
        assertEquals(retained, graph.json)
    }

    @Test
    fun `mode-only API to self-hosted edit includes actual frozen accelerator-model endpoints`() {
        assertFrozenModeTransition(DeploymentMode.API, DeploymentMode.SELF_HOSTED)
    }

    @Test
    fun `mode-only self-hosted to API edit includes previously active frozen endpoints`() {
        assertFrozenModeTransition(DeploymentMode.SELF_HOSTED, DeploymentMode.API)
    }

    @Test
    fun `enabling RAG includes the frozen vector-embedding edge remote from the edited tier`() {
        val before = context("embedding_model", "knowledge_base", "model", "storage", "vector_database")
        val after = context("embedding_model", "knowledge_base", "model", "rag_pipeline", "storage", "vector_database")
        val result = DependencyInvalidation.affectedByChange(frozenGraph(), setOf("rag_pipeline"), before, after)
        assertEquals(listOf("embedding_model", "knowledge_base", "model", "vector_database"), result.affectedKinds)
        assertEquals(listOf("selection.embedding_model", "selection.knowledge_base", "selection.model",
            "selection.rag_pipeline", "selection.vector_database"), result.selectionIdsToRecheck)
        assertEquals(listOf("dep.rag_pipeline.embedding_model", "dep.rag_pipeline.knowledge_base",
            "dep.rag_pipeline.model", "dep.rag_pipeline.vector_database", "dep.vector_database.embedding_model"),
            result.viaEdgeIds)
    }

    @Test
    fun `remote optional disable retains removed and disabled identities and captured history`() {
        val old = mutableListOf(InvalidationSelection("rag.old", "rag_pipeline"),
            InvalidationSelection("model.old", "model"), InvalidationSelection("accelerator", "accelerator"))
        val next = mutableListOf(InvalidationSelection("rag.new", "rag_pipeline", false),
            InvalidationSelection("model.new", "model", false), InvalidationSelection("accelerator", "accelerator"))
        val before = InvalidationContext(old)
        val after = InvalidationContext(next)
        val graph = decodedEdge("rag_enabled")
        val historicalGraph = graph.json
        val capturedBefore = old.toList()
        val capturedAfter = next.toList()
        old.clear()
        next.clear()
        val result = DependencyInvalidation.affectedByChange(graph, setOf("rag_pipeline"), before, after)
        assertEquals(listOf("accelerator", "model"), result.affectedKinds)
        assertEquals(listOf("accelerator", "model.new", "model.old", "rag.new", "rag.old"), result.selectionIdsToRecheck)
        assertEquals(listOf("edge.remote"), result.viaEdgeIds)
        assertEquals(capturedBefore, before.selections)
        assertEquals(capturedAfter, after.selections)
        assertFalse(after.selections.first { it.id == "model.new" }.enabled)
        assertEquals(historicalGraph, graph.json)
        assertEquals(result, DependencyInvalidation.affectedByChange(graph, setOf("rag_pipeline"), before, after))
    }

    @Test
    fun `remote agents transition includes both endpoints without a changed endpoint kind`() {
        assertRemoteTransition("agents_enabled", "agent_framework",
            context("accelerator", "model"), context("accelerator", "model", "agent_framework"),
            listOf("selection.accelerator", "selection.agent_framework", "selection.model"))
    }

    @Test
    fun `each frozen training kind can activate a remote fine-tuning edge`() {
        for (kind in listOf("training_framework", "training_compute", "training_dataset", "fine_tuned_model")) {
            val before = context("accelerator", "model")
            val after = InvalidationContext(listOf(InvalidationSelection("selection.accelerator", "accelerator"),
                InvalidationSelection("selection.model", "model"), InvalidationSelection("training", kind)))
            val result = DependencyInvalidation.affectedByChange(decodedEdge("fine_tuning_enabled"), setOf(kind), before, after)
            assertEquals(listOf("accelerator", "model"), result.affectedKinds, kind)
            assertEquals(listOf("selection.accelerator", "selection.model", "training"), result.selectionIdsToRecheck, kind)
            assertEquals(listOf("edge.remote"), result.viaEdgeIds, kind)
        }
    }

    @Test
    fun `removing one training kind while another remains does not expand an unchanged remote edge`() {
        val before = context("accelerator", "model", "training_dataset", "training_framework")
        val after = context("accelerator", "model", "training_framework")
        val result = DependencyInvalidation.affectedByChange(decodedEdge("fine_tuning_enabled"),
            setOf("training_dataset"), before, after)
        assertEquals(emptyList<String>(), result.affectedKinds)
        assertEquals(listOf("selection.training_dataset"), result.selectionIdsToRecheck)
        assertEquals(emptyList<String>(), result.viaEdgeIds)
    }

    @Test
    fun `if-selected context change rechecks endpoints even with an empty explicit edit set`() {
        val before = InvalidationContext(listOf(InvalidationSelection("selection.model", "model"),
            InvalidationSelection("selection.accelerator", "accelerator", false)))
        val after = context("accelerator", "model")
        val result = DependencyInvalidation.affectedByChange(decodedEdge("if_selected"), emptySet(), before, after)
        assertEquals(emptyList<String>(), result.changedKinds)
        assertEquals(listOf("accelerator", "model"), result.affectedKinds)
        assertEquals(listOf("selection.accelerator", "selection.model"), result.selectionIdsToRecheck)
        assertEquals(listOf("edge.remote"), result.viaEdgeIds)
    }

    @Test
    fun `API to managed activates a remote non-API edge`() {
        assertRemoteTransition("non_api", "deployment_mode",
            context("accelerator", "model", mode = DeploymentMode.API),
            context("accelerator", "model", mode = DeploymentMode.MANAGED))
    }

    private fun productionTransition(before: Boolean?, after: Boolean?) =
        assertRemoteTransition("production_only", "deployment_target",
            context("accelerator", "model", production = before), context("accelerator", "model", production = after))

    @Test fun `remote production false to true rechecks both endpoints`() = productionTransition(false, true)
    @Test fun `remote production true to false rechecks both endpoints`() = productionTransition(true, false)
    @Test fun `remote production false to unknown remains conservative`() = productionTransition(false, null)
    @Test fun `remote production unknown to false retires the earlier uncertainty`() = productionTransition(null, false)
    @Test fun `remote production true to unknown remains conservative`() = productionTransition(true, null)
    @Test fun `remote production unknown to true rechecks the resolved context`() = productionTransition(null, true)

    @Test
    fun `unchanged unknown remote activation does not expand unrelated work`() {
        val before = context("accelerator", "model", production = null)
        val after = context("accelerator", "model", production = null)
        val result = DependencyInvalidation.affectedByChange(decodedEdge("production_only"),
            setOf("deployment_target"), before, after)
        assertEquals(listOf("deployment_target"), result.changedKinds)
        assertEquals(emptyList<String>(), result.affectedKinds)
        assertEquals(emptyList<String>(), result.selectionIdsToRecheck)
        assertEquals(emptyList<String>(), result.viaEdgeIds)
    }

    @Test
    fun `unchanged resolved remote activation does not expand unrelated work`() {
        for (production in listOf(false, true)) {
            val ctx = context("accelerator", "model", "deployment_target", production = production)
            val result = DependencyInvalidation.affectedByChange(decodedEdge("production_only"),
                setOf("deployment_target"), ctx, ctx)
            assertEquals(emptyList<String>(), result.affectedKinds, production.toString())
            assertEquals(listOf("selection.deployment_target"), result.selectionIdsToRecheck, production.toString())
            assertEquals(emptyList<String>(), result.viaEdgeIds, production.toString())
        }
    }

    @Test
    fun `different raw modes with equal activation do not expand a remote self-hosted edge`() {
        val result = DependencyInvalidation.affectedByChange(decodedEdge("self_hosted"), setOf("deployment_mode"),
            context("accelerator", "model", mode = DeploymentMode.SELF_HOSTED),
            context("accelerator", "model", mode = DeploymentMode.HYBRID))
        assertEquals(emptyList<String>(), result.affectedKinds)
        assertEquals(emptyList<String>(), result.viaEdgeIds)
    }

    @Test
    fun `unchanged unknown incident activation still rechecks the neighbour`() {
        val ctx = context("accelerator", "model", production = null)
        val result = DependencyInvalidation.affectedByChange(decodedEdge("production_only"), setOf("model"), ctx, ctx)
        assertEquals(listOf("accelerator"), result.affectedKinds)
        assertEquals(listOf("selection.accelerator", "selection.model"), result.selectionIdsToRecheck)
        assertEquals(listOf("edge.remote"), result.viaEdgeIds)
    }

    @Test
    fun `remote mode transitions into and out of unknown recheck endpoints`() {
        for ((before, after) in listOf(DeploymentMode.API to null, null to DeploymentMode.API,
            DeploymentMode.SELF_HOSTED to null, null to DeploymentMode.SELF_HOSTED)) {
            assertRemoteTransition("self_hosted", "deployment_mode",
                context("accelerator", "model", mode = before), context("accelerator", "model", mode = after))
        }
    }

    @Test
    fun `activation transition cycles remain finite unique direct and order-independent`() {
        val edges = listOf(
            InvalidationEdge("cycle.a", "accelerator", "model", EdgeActivation.SELF_HOSTED),
            InvalidationEdge("cycle.b", "model", "accelerator", EdgeActivation.SELF_HOSTED),
            InvalidationEdge("self", "model", "model", EdgeActivation.SELF_HOSTED),
            InvalidationEdge("unrelated", "storage", "accelerator", EdgeActivation.ALWAYS),
        )
        val before = context("accelerator", "model", "storage", "deployment_mode", mode = DeploymentMode.API)
        val after = context("accelerator", "model", "storage", "deployment_mode", mode = DeploymentMode.SELF_HOSTED)
        val result = DependencyInvalidation.affectedByChange(edges, setOf("deployment_mode"), before, after)
        assertEquals(listOf("deployment_mode"), result.changedKinds)
        assertEquals(listOf("accelerator", "model"), result.affectedKinds)
        assertEquals(listOf("selection.accelerator", "selection.deployment_mode", "selection.model"), result.selectionIdsToRecheck)
        assertEquals(listOf("cycle.a", "cycle.b", "self"), result.viaEdgeIds)
        assertEquals(result, DependencyInvalidation.affectedByChange((edges + edges).reversed(),
            setOf("deployment_mode"), before, after))
        assertEquals(4, edges.size)
    }
}
