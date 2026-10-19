package ai.pickyour.core.contracts

import ai.pickyour.core.evidence.FrozenVerificationStatus
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenCatalogKind(val encoded: String) {
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
enum class FrozenCatalogTier { T0, T1, T2, T3, T4, T5, T6 }
enum class FrozenCatalogStatus(val encoded: String) {
    ACTIVE("active"), PREVIEW("preview"), DEPRECATED("deprecated"), RETIRED("retired"),
}
enum class FrozenCatalogBillingClass(val encoded: String) {
    FREE("free"), INCLUDED("included"), METERED("metered"), UNKNOWN("unknown"),
}

/** Recorded source assertions. URI strings stay inert and declared verification is not reassessed. */
class FrozenCatalogProvenance internal constructor(
    val sourceUri: String?,
    val checkedAt: String,
    val verificationStatus: FrozenVerificationStatus,
    val notes: String?,
) {
    override fun toString(): String = "FrozenCatalogProvenance"
}

/** An absent license, an empty license object and absent/false/true flags remain distinct. */
class FrozenCatalogLicense internal constructor(
    val spdxId: String?,
    val commercialUseAllowed: Boolean?,
    val selfHostingAllowed: Boolean?,
) {
    override fun toString(): String = "FrozenCatalogLicense"
}

/**
 * Immutable structural projection of all frozen CatalogItem fields. Kind-specific [specJson] and
 * arbitrary [extensionsJson] retain JSON values with exact numbers, including nulls and literal
 * unknown text, after the unchanged kind-specific schema validates them. They are not interpreted.
 * JSON strings retain values, not original bytes, formatting, number spelling or a content signature.
 */
class FrozenCatalogItem internal constructor(
    val schemaVersion: String,
    val id: String,
    val kind: FrozenCatalogKind,
    val tier: FrozenCatalogTier,
    val name: String,
    val version: String,
    val status: FrozenCatalogStatus,
    val billingClass: FrozenCatalogBillingClass,
    val specJson: String,
    val description: String?,
    tags: List<String>?,
    provenance: List<FrozenCatalogProvenance>,
    val license: FrozenCatalogLicense?,
    val updatedAt: String,
    val extensionsJson: String?,
    val json: String,
) {
    val tags: List<String>? = tags?.let { Collections.unmodifiableList(it.toList()) }
    val provenance: List<FrozenCatalogProvenance> = Collections.unmodifiableList(provenance.toList())
    override fun toString(): String = "FrozenCatalogItem"
}

/** Ordered immutable records. Duplicate item identities remain as recorded; publication is separate. */
class FrozenCatalogSnapshot internal constructor(
    val schemaVersion: String,
    val id: String,
    val revision: BigInteger,
    val capturedAt: String,
    items: List<FrozenCatalogItem>,
    val json: String,
) {
    val items: List<FrozenCatalogItem> = Collections.unmodifiableList(items.toList())
    override fun toString(): String = "FrozenCatalogSnapshot"
}

sealed interface FrozenCatalogDecoding<out T> {
    data class Decoded<T>(val value: T) : FrozenCatalogDecoding<T>
    data class InvalidInput(val reason: JsonRejection) : FrozenCatalogDecoding<Nothing>
    data object UnsupportedVersion : FrozenCatalogDecoding<Nothing>
    data object SchemaMismatch : FrozenCatalogDecoding<Nothing>
}

/**
 * Bounded frozen-v1 wire reader with caller-owned streams and sanitized failure states.
 * Both entry points choose their own bundled schema; input cannot choose a schema or trigger I/O.
 * Dispatch stream work off the UI thread and supply transport timeout/cancellation at the caller.
 * No publication, cache activation, identity joins, source-rights, freshness or compatibility policy.
 */
class FrozenCatalogDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decodeItem(input: InputStream): FrozenCatalogDecoding<FrozenCatalogItem> =
        decode(FrozenDocument.CATALOG_ITEM, input) { node, json -> item(node, json) }

    fun decodeSnapshot(input: InputStream): FrozenCatalogDecoding<FrozenCatalogSnapshot> =
        decode(FrozenDocument.CATALOG, input) { node, json ->
            FrozenCatalogSnapshot(
                node["schema_version"].textValue(), node["id"].textValue(),
                node["revision"].decimalValue().toBigIntegerExact(), node["captured_at"].textValue(),
                node["items"].map { item(it, it.toString()) }, json,
            )
        }

    private fun <T> decode(
        document: FrozenDocument, input: InputStream, project: (JsonNode, String) -> T,
    ): FrozenCatalogDecoding<T> {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenCatalogDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenCatalogDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(document, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenCatalogDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenCatalogDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        // The unchanged closed schemas establish every type/enum before extraction. Exact integer
        // conversion is retained defensively; it never narrows a fractional revision or uses Long.
        return try {
            FrozenCatalogDecoding.Decoded(project(parsed, json))
        } catch (_: ArithmeticException) {
            FrozenCatalogDecoding.SchemaMismatch
        }
    }

    private fun item(node: JsonNode, json: String): FrozenCatalogItem = FrozenCatalogItem(
        node["schema_version"].textValue(), node["id"].textValue(),
        FrozenCatalogKind.entries.first { it.encoded == node["kind"].textValue() },
        FrozenCatalogTier.valueOf(node["tier"].textValue()), node["name"].textValue(), node["version"].textValue(),
        FrozenCatalogStatus.entries.first { it.encoded == node["status"].textValue() },
        FrozenCatalogBillingClass.entries.first { it.encoded == node["billing_class"].textValue() },
        node["spec"].toString(), node.get("description")?.textValue(), node.get("tags")?.map { it.textValue() },
        node["provenance"].map { source ->
            FrozenCatalogProvenance(
                source.get("source_uri")?.textValue(), source["checked_at"].textValue(),
                FrozenVerificationStatus.entries.first { it.encoded == source["verification_status"].textValue() },
                source.get("notes")?.textValue(),
            )
        },
        node.get("license")?.let { license ->
            FrozenCatalogLicense(
                license.get("spdx_id")?.textValue(), license.get("commercial_use_allowed")?.booleanValue(),
                license.get("self_hosting_allowed")?.booleanValue(),
            )
        },
        node["updated_at"].textValue(), node.get("extensions")?.toString(), json,
    )
}
