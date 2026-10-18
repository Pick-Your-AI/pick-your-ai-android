package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.util.Collections

enum class FrozenRuleSeverity(val encoded: String) { ERROR("error"), WARNING("warning"), INFO("info") }
enum class FrozenRuleUnknownPolicy(val encoded: String) { BLOCK("block"), REVIEW("review"), DEFER("defer") }
enum class FrozenRuleReferenceScope(val encoded: String) { BUILD("build"), CATALOG("catalog"), SELECTION("selection") }
enum class FrozenRuleComparison(val encoded: String) {
    EQ("eq"), NE("ne"), LT("lt"), LTE("lte"), GT("gt"), GTE("gte"),
    IN("in"), CONTAINS("contains"), SUBSET_OF("subset_of"), INTERSECTS("intersects"),
}
enum class FrozenRuleJunction(val encoded: String) { ALL("all"), ANY("any") }
enum class FrozenRuleMetric(val encoded: String) {
    CONTEXT_TOTAL_REQUESTED_TOKENS("context.total_requested_tokens"),
    COMPUTE_TOTAL_SELECTED_VRAM_GB("compute.total_selected_vram_gb"),
    COMPUTE_MIN_SELECTED_DEVICE_VRAM_GB("compute.min_selected_device_vram_gb"),
    INFERENCE_ESTIMATED_REQUIRED_VRAM_GB("inference.estimated_required_vram_gb"),
    INFERENCE_ESTIMATED_PEAK_PER_DEVICE_VRAM_GB("inference.estimated_peak_per_device_vram_gb"),
    TOOLS_MUTATING_COUNT("tools.mutating_count"), COST_MONTHLY_TOTAL_AMOUNT("cost.monthly_total_amount"),
}
enum class FrozenRuleKind(val encoded: String) {
    WORKLOAD("workload"), DEPLOYMENT_MODE("deployment_mode"), MODEL_PROVIDER("model_provider"), MODEL("model"),
    CONTEXT_POLICY("context_policy"), HOSTING_PLATFORM("hosting_platform"), ACCELERATOR("accelerator"),
    COMPUTE_PROFILE("compute_profile"), INFERENCE_ENGINE("inference_engine"), STORAGE("storage"),
    DATA_SOURCE("data_source"), KNOWLEDGE_BASE("knowledge_base"), EMBEDDING_MODEL("embedding_model"),
    VECTOR_DATABASE("vector_database"), RAG_PIPELINE("rag_pipeline"), AGENT_FRAMEWORK("agent_framework"),
    TOOL("tool"), MCP_SERVER("mcp_server"), MEMORY_STORE("memory_store"), INTEGRATION("integration"),
    TRAINING_FRAMEWORK("training_framework"), TRAINING_COMPUTE("training_compute"), TRAINING_DATASET("training_dataset"),
    FINE_TUNED_MODEL("fine_tuned_model"), GUARDRAIL("guardrail"), EVALUATION_SUITE("evaluation_suite"),
    DEPLOYMENT_TARGET("deployment_target"), MONITORING_STACK("monitoring_stack"), COST_POLICY("cost_policy"),
}

/** Inert recorded fields. The frozen pointer grammar checks only a leading slash. */
class FrozenRuleReference internal constructor(
    val scope: FrozenRuleReferenceScope,
    val pointer: String,
    val kind: FrozenRuleKind?,
    val role: String?,
    val selectionId: String?,
) {
    override fun toString(): String = "FrozenRuleReference"
}

/** No operand resolves a reference, computes a metric, or interprets a literal's content. */
sealed class FrozenRuleOperand {
    /** Arbitrary JSON values, including null, retained without a mutable tree or binary-float conversion. */
    class Literal internal constructor(val json: String) : FrozenRuleOperand() {
        override fun toString(): String = "FrozenRuleOperand.Literal"
    }
    class Metric internal constructor(val metric: FrozenRuleMetric) : FrozenRuleOperand() {
        override fun toString(): String = "FrozenRuleOperand.Metric"
    }
    class Reference internal constructor(val reference: FrozenRuleReference) : FrozenRuleOperand() {
        override fun toString(): String = "FrozenRuleOperand.Reference"
    }
}

/** The five frozen expression shapes retain their supplied order and structure without evaluation. */
sealed class FrozenRuleExpression {
    class Comparison internal constructor(
        val op: FrozenRuleComparison, val left: FrozenRuleOperand, val right: FrozenRuleOperand,
    ) : FrozenRuleExpression() {
        override fun toString(): String = "FrozenRuleExpression.Comparison"
    }
    class Exists internal constructor(val value: FrozenRuleOperand) : FrozenRuleExpression() {
        override fun toString(): String = "FrozenRuleExpression.Exists"
    }
    class Junction internal constructor(val op: FrozenRuleJunction, args: List<FrozenRuleExpression>) : FrozenRuleExpression() {
        val args: List<FrozenRuleExpression> = Collections.unmodifiableList(args.toList())
        override fun toString(): String = "FrozenRuleExpression.Junction"
    }
    class Not internal constructor(val arg: FrozenRuleExpression) : FrozenRuleExpression() {
        override fun toString(): String = "FrozenRuleExpression.Not"
    }
    class Implies internal constructor(
        val condition: FrozenRuleExpression, val consequence: FrozenRuleExpression,
    ) : FrozenRuleExpression() {
        override fun toString(): String = "FrozenRuleExpression.Implies"
    }
}

class FrozenRule internal constructor(
    val id: String,
    val severity: FrozenRuleSeverity,
    val appliesWhen: FrozenRuleExpression,
    val assertion: FrozenRuleExpression,
    val onUnknown: FrozenRuleUnknownPolicy,
    val message: String,
    val remediation: String,
    affectedKinds: List<FrozenRuleKind>,
    val owner: String?,
) {
    val affectedKinds: List<FrozenRuleKind> = Collections.unmodifiableList(affectedKinds.toList())
    override fun toString(): String = "FrozenRule"
}

/** Immutable structural values, not an approved executable ruleset or authenticated publication. */
class FrozenRuleset internal constructor(
    val schemaVersion: String,
    val id: String,
    val version: String,
    val publishedAt: String,
    rules: List<FrozenRule>,
    /** Reparseable JSON values, not original layout, numeric spelling, content hash or signature. */
    val json: String,
) {
    val rules: List<FrozenRule> = Collections.unmodifiableList(rules.toList())
    override fun toString(): String = "FrozenRuleset"
}

sealed interface FrozenRulesetDecoding {
    data class Decoded(val ruleset: FrozenRuleset) : FrozenRulesetDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenRulesetDecoding
    data object UnsupportedVersion : FrozenRulesetDecoding
    data object SchemaMismatch : FrozenRulesetDecoding
}

/**
 * Fixed-schema, bounded UTF-8 decoding with caller-owned streams and sanitized failures.
 * Perform stream work off the UI thread; the caller supplies transport timeout/cancellation.
 * No evaluation, reference lookup, metric calculation, publication, storage or network access.
 */
class FrozenRulesetDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenRulesetDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenRulesetDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenRulesetDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.RULESET, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenRulesetDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenRulesetDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        // Every extraction below follows validation of the unchanged closed schema. Its existing
        // parser depth budget also bounds recursion through expressions and arbitrary literal JSON.
        val rules = parsed["rules"].map { rule ->
            FrozenRule(
                rule["id"].textValue(), FrozenRuleSeverity.entries.first { it.encoded == rule["severity"].textValue() },
                expression(rule["applies_when"]), expression(rule["assert"]),
                FrozenRuleUnknownPolicy.entries.first { it.encoded == rule["on_unknown"].textValue() },
                rule["message"].textValue(), rule["remediation"].textValue(),
                rule["affected_kinds"].map { kind -> FrozenRuleKind.entries.first { it.encoded == kind.textValue() } },
                rule.get("owner")?.textValue(),
            )
        }
        return FrozenRulesetDecoding.Decoded(FrozenRuleset(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), parsed["version"].textValue(),
            parsed["published_at"].textValue(), rules, json,
        ))
    }

    private fun expression(node: JsonNode): FrozenRuleExpression = when (val op = node["op"].textValue()) {
        "exists" -> FrozenRuleExpression.Exists(operand(node["value"]))
        "all", "any" -> FrozenRuleExpression.Junction(
            FrozenRuleJunction.entries.first { it.encoded == op }, node["args"].map(::expression),
        )
        "not" -> FrozenRuleExpression.Not(expression(node["arg"]))
        "implies" -> FrozenRuleExpression.Implies(expression(node["if"]), expression(node["then"]))
        else -> FrozenRuleExpression.Comparison(
            FrozenRuleComparison.entries.first { it.encoded == op }, operand(node["left"]), operand(node["right"]),
        )
    }

    private fun operand(node: JsonNode): FrozenRuleOperand = when {
        node.has("literal") -> FrozenRuleOperand.Literal(node["literal"].toString())
        node.has("metric") -> FrozenRuleOperand.Metric(
            FrozenRuleMetric.entries.first { it.encoded == node["metric"].textValue() },
        )
        else -> node["ref"].let { ref ->
            FrozenRuleOperand.Reference(FrozenRuleReference(
                FrozenRuleReferenceScope.entries.first { it.encoded == ref["scope"].textValue() },
                ref["pointer"].textValue(), ref.get("kind")?.textValue()?.let { kind ->
                    FrozenRuleKind.entries.first { it.encoded == kind }
                }, ref.get("role")?.textValue(), ref.get("selection_id")?.textValue(),
            ))
        }
    }
}
