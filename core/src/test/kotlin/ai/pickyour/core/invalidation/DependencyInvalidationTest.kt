package ai.pickyour.core.invalidation

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DependencyInvalidationTest {
    private fun context(vararg kinds: String, mode: DeploymentMode? = DeploymentMode.API, production: Boolean? = false) =
        InvalidationContext(kinds.map { InvalidationSelection("selection.$it", it) }, mode, production)
    private fun edge(from: String, to: String, activation: EdgeActivation = EdgeActivation.ALWAYS) =
        InvalidationEdge("$from.$to", from, to, activation)
    private fun result(edges: List<InvalidationEdge>, changed: Set<String>, before: InvalidationContext, after: InvalidationContext = before) =
        DependencyInvalidation.affectedByChange(edges, changed, before, after)

    @Test
    fun `both directions follow direct adjacency without transitive policy expansion`() {
        val edges = listOf(edge("model", "accelerator"), edge("context_policy", "model"), edge("storage", "context_policy"))
        val ctx = context("model", "accelerator", "context_policy", "storage")
        assertEquals(listOf("accelerator", "context_policy"), result(edges, setOf("model"), ctx).affectedKinds)
        assertEquals(listOf("model"), result(edges, setOf("accelerator"), ctx).affectedKinds)
        assertEquals(listOf("selection.accelerator", "selection.context_policy", "selection.model"), result(edges, setOf("model"), ctx).selectionIdsToRecheck)
    }

    @Test
    fun `mode changes include edges active before or after and unknown is conservative`() {
        val edges = listOf(edge("accelerator", "model", EdgeActivation.SELF_HOSTED))
        val api = context(mode = DeploymentMode.API)
        val local = context(mode = DeploymentMode.SELF_HOSTED)
        assertEquals(listOf("accelerator"), result(edges, setOf("model"), local, api).affectedKinds)
        assertEquals(listOf("accelerator"), result(edges, setOf("model"), api, local).affectedKinds)
        assertEquals(listOf("accelerator"), result(edges, setOf("model"), api, context(mode = null)).affectedKinds)
        assertTrue(result(edges, setOf("model"), api).affectedKinds.isEmpty())
    }

    @Test
    fun `every activation follows the frozen website truth table`() {
        val cases = listOf(
            Triple(EdgeActivation.ALWAYS, context(), true),
            Triple(EdgeActivation.SELF_HOSTED, context(mode = DeploymentMode.HYBRID), true),
            Triple(EdgeActivation.SELF_HOSTED, context(mode = DeploymentMode.MANAGED), false),
            Triple(EdgeActivation.NON_API, context(mode = DeploymentMode.MANAGED), true),
            Triple(EdgeActivation.NON_API, context(mode = DeploymentMode.API), false),
            Triple(EdgeActivation.NON_API, context(mode = null), true),
            Triple(EdgeActivation.RAG_ENABLED, context("rag_pipeline"), true),
            Triple(EdgeActivation.RAG_ENABLED, context(), false),
            Triple(EdgeActivation.AGENTS_ENABLED, context("agent_framework"), true),
            Triple(EdgeActivation.AGENTS_ENABLED, context(), false),
            Triple(EdgeActivation.FINE_TUNING_ENABLED, context("training_dataset"), true),
            Triple(EdgeActivation.FINE_TUNING_ENABLED, context(), false),
            Triple(EdgeActivation.IF_SELECTED, context("model", "accelerator"), true),
            Triple(EdgeActivation.IF_SELECTED, context("model"), false),
            Triple(EdgeActivation.PRODUCTION_ONLY, context(production = null), true),
            Triple(EdgeActivation.PRODUCTION_ONLY, context(production = true), true),
            Triple(EdgeActivation.PRODUCTION_ONLY, context(production = false), false),
        )
        for ((activation, ctx, shouldRecheck) in cases) {
            val actual = result(listOf(edge("accelerator", "model", activation)), setOf("model"), ctx)
            assertEquals(shouldRecheck, actual.affectedKinds.isNotEmpty(), activation.name)
        }
    }

    @Test
    fun `disabled tier and removed identities remain rechecked without editing input`() {
        val old = mutableListOf(InvalidationSelection("rag.old", "rag_pipeline"), InvalidationSelection("vector", "vector_database"))
        val before = InvalidationContext(old)
        val afterSelections = listOf(InvalidationSelection("rag.new", "rag_pipeline", false), InvalidationSelection("vector", "vector_database"))
        val after = InvalidationContext(afterSelections)
        val edges = listOf(edge("rag_pipeline", "vector_database", EdgeActivation.RAG_ENABLED))
        old.clear() // Context must retain its captured revision.
        val actual = result(edges, setOf("rag_pipeline"), before, after)
        assertEquals(listOf("vector_database"), actual.affectedKinds)
        assertEquals(listOf("rag.new", "rag.old", "vector"), actual.selectionIdsToRecheck)
        assertFalse(afterSelections[0].enabled)
        assertTrue(result(edges, setOf("rag_pipeline"), after).affectedKinds.isEmpty())
    }

    @Test
    fun `cycles self loops duplicates and input order produce finite deterministic output`() {
        val edges = listOf(edge("model", "accelerator"), edge("accelerator", "model"), edge("model", "model"))
        val ctx = context("model", "accelerator")
        val actual = result(edges, setOf("model"), ctx)
        assertEquals(actual, result((edges + edges).reversed(), setOf("model"), ctx))
        assertEquals(listOf("accelerator"), actual.affectedKinds)
        assertEquals(3, actual.viaEdgeIds.size)
        assertTrue(result(edges, emptySet(), ctx).selectionIdsToRecheck.isEmpty())
    }

    @Test
    fun `frozen graph has stable model neighbours and all changed kinds stay distinct`() {
        val graph = ObjectMapper().readTree(javaClass.getResourceAsStream("/contracts/v1/examples/dependency-graph.json"))
        val edges = graph["edges"].map {
            InvalidationEdge(it["id"].asText(), it["dependent_kind"].asText(), it["depends_on_kind"].asText(), EdgeActivation.valueOf(it["activation"].asText().uppercase()))
        }
        val ctx = context(mode = DeploymentMode.API)
        val actual = result(edges, setOf("model"), ctx)
        assertEquals(listOf("context_policy", "cost_policy", "deployment_mode", "deployment_target", "evaluation_suite", "guardrail", "model_provider", "monitoring_stack"), actual.affectedKinds)
        assertEquals(listOf("model"), actual.changedKinds)
        assertEquals(actual, result(edges.reversed(), setOf("model"), ctx))
    }
}
