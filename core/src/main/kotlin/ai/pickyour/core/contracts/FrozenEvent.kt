package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenEventType(val encoded: String) {
    BUILD_CREATED("build.created"), BUILD_SELECTION_UPSERTED("build.selection.upserted"),
    BUILD_SELECTION_REMOVED("build.selection.removed"), BUILD_LINK_UPSERTED("build.link.upserted"),
    BUILD_OBJECTIVES_UPDATED("build.objectives.updated"), BUILD_VALIDATION_COMPLETED("build.validation.completed"),
    BUILD_COST_ESTIMATED("build.cost.estimated"), BUILD_DEPLOYMENT_REQUESTED("build.deployment.requested"),
    BUILD_DEPLOYED("build.deployed"), CATALOG_ITEM_PUBLISHED("catalog.item.published"),
    CATALOG_SNAPSHOT_PUBLISHED("catalog.snapshot.published"), RULESET_PUBLISHED("ruleset.published"),
    PRICEBOOK_PUBLISHED("pricebook.published"),
}

enum class FrozenEventSubjectType(val encoded: String) {
    BUILD("build"), CATALOG_ITEM("catalog_item"), CATALOG_SNAPSHOT("catalog_snapshot"),
    RULESET("ruleset"), PRICEBOOK("pricebook"),
}

enum class FrozenEventAffectedKind(val encoded: String) {
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

/** Optional fields retain absence; no field supplies authority to look up or mutate a referenced object. */
class FrozenEventPayload internal constructor(
    val selectionId: String?,
    val catalogItemId: String?,
    affectedKinds: List<FrozenEventAffectedKind>?,
    val evaluationReportId: String?,
    val costEstimateId: String?,
    val note: String?,
) {
    val affectedKinds: List<FrozenEventAffectedKind>? = affectedKinds?.let { Collections.unmodifiableList(it.toList()) }
    override fun toString(): String = "FrozenEventPayload"
}

/**
 * Immutable recorded event fields, not authenticated occurrence or permission to execute an event.
 * [json] preserves JSON values, not the original byte layout, number spelling, hash or signature.
 */
class FrozenEvent internal constructor(
    val schemaVersion: String,
    val id: String,
    val type: FrozenEventType,
    val occurredAt: String,
    val subjectType: FrozenEventSubjectType,
    val subjectId: String,
    val subjectRevision: BigInteger,
    val subjectVersion: String?,
    val actorRef: String,
    val correlationId: String,
    val causationId: String?,
    val idempotencyKey: String,
    val payload: FrozenEventPayload,
    val json: String,
) {
    override fun toString(): String = "FrozenEvent"
}

sealed interface FrozenEventDecoding {
    data class Decoded(val event: FrozenEvent) : FrozenEventDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenEventDecoding
    data object UnsupportedVersion : FrozenEventDecoding
    data object SchemaMismatch : FrozenEventDecoding
}

/**
 * Fixed-schema, bounded UTF-8 decoding with caller-owned streams and sanitized failures.
 * No execution, publication, replay, deduplication, authorization, telemetry or network access.
 * Perform stream work off the UI thread; the caller supplies transport timeout/cancellation.
 */
class FrozenEventDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenEventDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenEventDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenEventDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.EVENT, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenEventDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenEventDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        val revision = try {
            parsed["subject_revision"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return FrozenEventDecoding.SchemaMismatch
        }
        val payload = parsed["payload"]
        return FrozenEventDecoding.Decoded(FrozenEvent(
            parsed["schema_version"].textValue(), parsed["id"].textValue(),
            FrozenEventType.entries.first { it.encoded == parsed["type"].textValue() },
            parsed["occurred_at"].textValue(),
            FrozenEventSubjectType.entries.first { it.encoded == parsed["subject_type"].textValue() },
            parsed["subject_id"].textValue(), revision, parsed.get("subject_version")?.textValue(),
            parsed["actor_ref"].textValue(), parsed["correlation_id"].textValue(), parsed.get("causation_id")?.textValue(),
            parsed["idempotency_key"].textValue(), FrozenEventPayload(
                payload.get("selection_id")?.textValue(), payload.get("catalog_item_id")?.textValue(),
                payload.get("affected_kinds")?.map { kind ->
                    FrozenEventAffectedKind.entries.first { it.encoded == kind.textValue() }
                },
                payload.get("evaluation_report_id")?.textValue(), payload.get("cost_estimate_id")?.textValue(),
                payload.get("note")?.textValue(),
            ), json,
        ))
    }
}
