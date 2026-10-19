package ai.pickyour.core.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.security.MessageDigest
import java.util.Collections

/** Local presentation only: neither a frozen-v1 category nor an independently verified verdict. */
enum class ProvisionalFindingGuidance { UNCLASSIFIED, INCOMPLETE, REPORTED_INCOMPATIBILITY }

/**
 * Finite android-local-finding-guidance/1 policy, after the existing receiver has admitted all inputs.
 * The two separately pinned profiles differ only in their top-level ID. Nothing normalizes a new
 * identity, interprets arbitrary rule syntax, or treats a reason/severity as classification metadata.
 */
internal fun recordedProvisionalGuidance(build: JsonNode, ruleset: JsonNode, report: JsonNode): Map<String, ProvisionalFindingGuidance> {
    if (!FindingGuidanceProfile.matches(ruleset)) return emptyMap()
    val selections = build["selections"].toList()
    // Even a disabled duplicate makes recorded identity ambiguous; do not select a convenient row.
    if (selections.map { it["id"].asText() }.distinct().size != selections.size) return emptyMap()
    val active = selections.filter { it["enabled"].asBoolean() }
        .groupBy { it["kind"].asText() to it["role"].asText() }
    val guidance = linkedMapOf<String, ProvisionalFindingGuidance>()
    for (finding in report["findings"]) {
        if (finding["outcome"].asText() != "fail") continue
        val id = finding["rule_id"].asText()
        val required = FindingGuidanceProfile.required[id].orEmpty()
        val fit = FindingGuidanceProfile.fit[id].orEmpty()
        fun count(kind: String) = active[kind to "primary"].orEmpty().size
        if ((required + fit).any { count(it) > 1 }) continue
        val value = when {
            required.any { count(it) == 0 } -> ProvisionalFindingGuidance.INCOMPLETE
            fit.isNotEmpty() && fit.all { count(it) == 1 } -> ProvisionalFindingGuidance.REPORTED_INCOMPATIBILITY
            else -> ProvisionalFindingGuidance.UNCLASSIFIED
        }
        if (value != ProvisionalFindingGuidance.UNCLASSIFIED) guidance[id] = value
    }
    return Collections.unmodifiableMap(guidance)
}

private object FindingGuidanceProfile {
    private val hashes = mapOf(
        "rules.core.1" to "1de9dc005b5012fdda5e0e5fc303a8ef2626a81098e314bfd8e68156ce86b0ff",
        "rules.fixture.1" to "f01bab08fb7fc62eeafe5883180cabaf5695c52a8c835c7f1d0f80957bd51d80",
    )

    fun matches(ruleset: JsonNode): Boolean {
        if (ruleset["schema_version"].asText() != "1.0.0" || ruleset["version"].asText() != "1.0.0") return false
        val expected = hashes[ruleset["id"].asText()] ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        fun emit(text: String) { digest.update(text.toByteArray(Charsets.UTF_8)) }
        // Object key order is irrelevant; array order and raw numeric token type remain significant.
        fun append(node: JsonNode) {
            when {
                node.isObject -> {
                    emit("{")
                    node.properties().sortedBy { it.key }.forEachIndexed { index, (key, child) ->
                        if (index > 0) emit(",")
                        emit(JsonNodeFactory.instance.textNode(key).toString()); emit(":"); append(child)
                    }
                    emit("}")
                }
                node.isArray -> {
                    emit("[")
                    node.forEachIndexed { index, child -> if (index > 0) emit(","); append(child) }
                    emit("]")
                }
                else -> {
                    val text = node.toString(); emit(text)
                    if (node.isFloatingPointNumber && text.none { it == '.' || it == 'e' || it == 'E' }) emit(".0")
                }
            }
        }
        append(ruleset)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected
    }

    // These 12 and 28 inventories overlap. All roles are primary; the remaining 10 rules have no facet.
    val required = mapOf(
        "rule.required.foundation" to listOf("workload", "deployment_mode", "model", "context_policy"),
        "rule.selfhost.requires.infrastructure" to listOf("hosting_platform", "accelerator", "compute_profile", "inference_engine"),
        "rule.rag.knowledge" to listOf("knowledge_base"),
        "rule.rag.dense.dependencies" to listOf("embedding_model", "vector_database"),
        "rule.memory.vector.storage" to listOf("vector_database"),
        "rule.production.gates" to listOf("evaluation_suite", "monitoring_stack", "guardrail"),
        "rule.agent.tools.framework" to listOf("agent_framework"),
        "rule.agent.mcp.framework" to listOf("agent_framework"),
        "rule.tool.mutation.guardrails" to listOf("guardrail"),
        "rule.training.model.capable" to listOf("training_framework", "training_compute", "training_dataset"),
        "rule.restricted.monitoring.redaction" to listOf("monitoring_stack"),
        "rule.memory.framework.compatibility" to listOf("agent_framework"),
    )
    val fit = mapOf(
        "rule.model.provider.match" to listOf("model", "model_provider"),
        "rule.model.output.limit" to listOf("model", "context_policy"),
        "rule.selfhost.requires.weights" to listOf("deployment_mode", "model"),
        "rule.engine.model.format" to listOf("model", "inference_engine"),
        "rule.engine.accelerator.backend" to listOf("accelerator", "inference_engine"),
        "rule.precision.engine" to listOf("inference_engine", "model"),
        "rule.precision.accelerator" to listOf("accelerator", "model"),
        "rule.api.model.access" to listOf("deployment_mode", "model"),
        "rule.embedding.dimension" to listOf("embedding_model", "vector_database"),
        "rule.vector.dimension.support" to listOf("vector_database"),
        "rule.vector.distance.support" to listOf("vector_database"),
        "rule.embedding.distance.support" to listOf("embedding_model", "vector_database"),
        "rule.agent.tools.framework" to listOf("tool", "agent_framework"),
        "rule.agent.mcp.framework" to listOf("mcp_server", "agent_framework"),
        "rule.tool.mutation.guardrails" to listOf("tool", "guardrail"),
        "rule.training.model.capable" to listOf("model", "training_framework", "training_compute", "training_dataset"),
        "rule.training.base.lineage" to listOf("fine_tuned_model", "model"),
        "rule.training.method.support" to listOf("training_framework"),
        "rule.training.backend.support" to listOf("training_framework", "accelerator"),
        "rule.compute.sku.accelerator" to listOf("compute_profile", "accelerator"),
        "rule.compute.sku.host" to listOf("compute_profile", "hosting_platform"),
        "rule.compute.accelerator.count" to listOf("compute_profile", "accelerator"),
        "rule.host.accelerator.catalog" to listOf("hosting_platform", "accelerator"),
        "rule.tensor.parallel.devices" to listOf("accelerator", "inference_engine"),
        "rule.model.serving.mode.selfhost" to listOf("model", "deployment_mode"),
        "rule.memory.framework.compatibility" to listOf("memory_store", "agent_framework"),
        "rule.training.artifact.engine" to listOf("fine_tuned_model", "inference_engine"),
        "rule.tensor.parallel.engine.support" to listOf("inference_engine"),
    )
}
