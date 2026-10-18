package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class FrozenBuildLifecycle(val encoded: String) {
    DRAFT("draft"), VALIDATED("validated"), READY("ready"), DEPLOYED("deployed"), ARCHIVED("archived"),
}

enum class FrozenBuildDataClassification(val encoded: String) {
    PUBLIC("public"), INTERNAL("internal"), CONFIDENTIAL("confidential"), RESTRICTED("restricted"),
}

enum class FrozenBuildResourceConfidence(val encoded: String) {
    ESTIMATED("estimated"), CALIBRATED("calibrated"), VERIFIED("verified"),
}

enum class FrozenBuildRelation(val encoded: String) {
    USES("uses"), PROVIDED_BY("provided_by"), RUNS_ON("runs_on"), SERVED_BY("served_by"),
    STORED_IN("stored_in"), INDEXES_WITH("indexes_with"), READS_FROM("reads_from"),
    DERIVED_FROM("derived_from"), SECURED_BY("secured_by"), EVALUATED_BY("evaluated_by"),
    OBSERVED_BY("observed_by"), DEPLOYS_TO("deploys_to"), TRAINED_ON("trained_on"),
    CALLS("calls"), EXPOSES("exposes"), FEEDS("feeds"), OTHER("other"),
}

/** Recorded objectives. Decimal strings retain their exact spelling; absent values are never zero. */
class FrozenBuildObjectives internal constructor(
    val region: String,
    val currency: String,
    val dataClassification: FrozenBuildDataClassification,
    val monthlyBudgetAmount: String?,
    val requestsPerDay: BigInteger?,
    val averageInputTokens: BigInteger?,
    val averageOutputTokens: BigInteger?,
    val maximumConcurrency: BigInteger?,
    val maximumLatencyMs: BigInteger?,
) {
    override fun toString() = "FrozenBuildObjectives"
}

/** Declared pins only; decoding neither fetches nor authenticates these artifacts. */
class FrozenBuildLocks internal constructor(
    val catalogSnapshotId: String,
    val rulesetId: String,
    val rulesetVersion: String,
    val dependencyGraphId: String,
    val dependencyGraphRevision: BigInteger,
    val pricebookId: String?,
) {
    override fun toString() = "FrozenBuildLocks"
}

/** Absence, false and an explicitly empty policy object remain distinct. No policy is executed. */
class FrozenBuildPolicy internal constructor(
    val failOnUnknown: Boolean?,
    val requireEvaluationsForProduction: Boolean?,
    val allowUnverifiedCatalogItems: Boolean?,
    val minimumResourceConfidence: FrozenBuildResourceConfidence?,
) {
    override fun toString() = "FrozenBuildPolicy"
}

/**
 * A structurally validated frozen selection. Kind and kind-specific settings use the unchanged
 * selection schema. Returned objects are defensive copies; URI/secret references remain inert.
 */
class FrozenBuildSelection internal constructor(value: ObjectNode) {
    private val payload: ObjectNode = value.deepCopy()
    val id: String get() = payload["id"].textValue()
    val kind: String get() = payload["kind"].textValue()
    val catalogItemId: String get() = payload["catalog_item_id"].textValue()
    val catalogItemVersion: String get() = payload["catalog_item_version"].textValue()
    val role: String get() = payload["role"].textValue()
    val enabled: Boolean get() = payload["enabled"].booleanValue()
    val settings: ObjectNode get() = payload["settings"].deepCopy()
    val notes: String? get() = payload.get("notes")?.textValue()
    val extensions: ObjectNode? get() = payload.get("extensions")?.deepCopy<ObjectNode>()
    fun toJson(): ObjectNode = payload.deepCopy()
    override fun toString() = "FrozenBuildSelection"
}

/** Relationship text is recorded, not resolved against selections or interpreted as a command. */
class FrozenBuildLink internal constructor(
    val id: String,
    val fromSelectionId: String,
    val toSelectionId: String,
    val relation: FrozenBuildRelation,
    val required: Boolean?,
) {
    override fun toString() = "FrozenBuildLink"
}

/**
 * Immutable structural view of a frozen Build document, not an authorized or saved local build.
 * JSON preserves values rather than original byte layout, numeric spelling, hash or signature.
 * Lifecycle, owner and locks are unverified declarations. This is not a public export projection.
 */
class FrozenBuildSnapshot internal constructor(
    val schemaVersion: String,
    val id: String,
    val revision: BigInteger,
    val name: String,
    val ownerRef: String,
    val lifecycle: FrozenBuildLifecycle,
    val createdAt: String,
    val updatedAt: String,
    val objectives: FrozenBuildObjectives,
    val locks: FrozenBuildLocks,
    selections: List<FrozenBuildSelection>,
    links: List<FrozenBuildLink>,
    val policy: FrozenBuildPolicy?,
    extensions: ObjectNode?,
    val json: String,
) {
    val selections: List<FrozenBuildSelection> = Collections.unmodifiableList(ArrayList(selections))
    val links: List<FrozenBuildLink> = Collections.unmodifiableList(ArrayList(links))
    private val retainedExtensions: ObjectNode? = extensions?.deepCopy()
    val extensions: ObjectNode? get() = retainedExtensions?.deepCopy()
    override fun toString() = "FrozenBuildSnapshot"
}

sealed interface FrozenBuildDecoding {
    data class Decoded(val build: FrozenBuildSnapshot) : FrozenBuildDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenBuildDecoding
    data object UnsupportedVersion : FrozenBuildDecoding
    data object SchemaMismatch : FrozenBuildDecoding
}

/**
 * Bounded fixed-schema decoding only. No file import, persistence, identity allocation, URI access,
 * credential resolution, compatibility evaluation, policy execution or release action occurs.
 * Duplicate IDs, dangling links and conflicting roles are retained if the frozen schema admits
 * them; a domain receiver must check those semantics before using a build. Streams remain owned
 * by callers, who supply transport timeout/cancellation and keep stream work off the UI thread.
 */
class FrozenBuildSnapshotDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): FrozenBuildDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenBuildDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return FrozenBuildDecoding.UnsupportedVersion
        val json = parsed.toString()
        when (val result = validator.validate(FrozenDocument.BUILD, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenBuildDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenBuildDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        return try {
            val objectives = parsed["objectives"]
            val locks = parsed["locks"]
            val policy = parsed.get("policy")
            FrozenBuildDecoding.Decoded(FrozenBuildSnapshot(
                parsed["schema_version"].textValue(), parsed["id"].textValue(), integer(parsed["revision"]),
                parsed["name"].textValue(), parsed["owner_ref"].textValue(),
                FrozenBuildLifecycle.entries.first { it.encoded == parsed["lifecycle"].textValue() },
                parsed["created_at"].textValue(), parsed["updated_at"].textValue(),
                FrozenBuildObjectives(
                    objectives["region"].textValue(), objectives["currency"].textValue(),
                    FrozenBuildDataClassification.entries.first { it.encoded == objectives["data_classification"].textValue() },
                    objectives.get("monthly_budget_amount")?.textValue(),
                    objectives.get("requests_per_day")?.let(::integer),
                    objectives.get("avg_input_tokens")?.let(::integer),
                    objectives.get("avg_output_tokens")?.let(::integer),
                    objectives.get("max_concurrency")?.let(::integer),
                    objectives.get("max_latency_ms")?.let(::integer),
                ),
                FrozenBuildLocks(
                    locks["catalog_snapshot_id"].textValue(), locks["ruleset_id"].textValue(),
                    locks["ruleset_version"].textValue(), locks["dependency_graph_id"].textValue(),
                    integer(locks["dependency_graph_revision"]), locks.get("pricebook_id")?.textValue(),
                ),
                parsed["selections"].map { FrozenBuildSelection(it as ObjectNode) },
                parsed["links"].map { link -> FrozenBuildLink(
                    link["id"].textValue(), link["from_selection_id"].textValue(), link["to_selection_id"].textValue(),
                    FrozenBuildRelation.entries.first { it.encoded == link["relation"].textValue() },
                    link.get("required")?.booleanValue(),
                ) },
                policy?.let { FrozenBuildPolicy(
                    it.get("fail_on_unknown")?.booleanValue(), it.get("require_evaluations_for_production")?.booleanValue(),
                    it.get("allow_unverified_catalog_items")?.booleanValue(),
                    it.get("min_resource_confidence")?.let { confidence ->
                        FrozenBuildResourceConfidence.entries.first { it.encoded == confidence.textValue() }
                    },
                ) },
                parsed.get("extensions") as ObjectNode?, json,
            ))
        } catch (_: ArithmeticException) {
            // Never narrow or truncate a revision or a count if an input is not an exact integer.
            FrozenBuildDecoding.SchemaMismatch
        }
    }

    private fun integer(value: JsonNode): BigInteger = value.decimalValue().toBigIntegerExact()
}
