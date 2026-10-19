package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream
import java.math.BigInteger
import java.util.Collections

enum class DependencyRelationship(val encoded: String) {
    REQUIRES("requires"), CONSTRAINS("constrains"), INFORMS("informs"), PRODUCES("produces"),
}

enum class DependencyActivation(val encoded: String) {
    ALWAYS("always"), SELF_HOSTED("self_hosted"), NON_API("non_api"), RAG_ENABLED("rag_enabled"),
    AGENTS_ENABLED("agents_enabled"), FINE_TUNING_ENABLED("fine_tuning_enabled"),
    IF_SELECTED("if_selected"), PRODUCTION_ONLY("production_only"),
}

/** Recorded direction and labels only: dependentKind depends on dependsOnKind. */
class DependencyEdge internal constructor(
    val id: String,
    val dependentKind: String,
    val dependsOnKind: String,
    val relationship: DependencyRelationship,
    val activation: DependencyActivation,
    val rationale: String,
) {
    override fun toString(): String = "DependencyEdge"
}

/**
 * Immutable structural projection of one complete frozen DependencyGraph.
 * Edge order, repeated identities, self edges and cycles remain supplied data.
 * [json] preserves JSON values, not original bytes, number spelling or a source signature.
 */
class DependencyGraph internal constructor(
    val schemaVersion: String,
    val id: String,
    val revision: BigInteger,
    edges: List<DependencyEdge>,
    val json: String,
) {
    val edges: List<DependencyEdge> = Collections.unmodifiableList(edges.toList())
    override fun toString(): String = "DependencyGraph"
}

sealed interface DependencyGraphDecoding {
    data class Decoded(val graph: DependencyGraph) : DependencyGraphDecoding
    /** No input fragment, provider exception or schema-library diagnostic escapes. */
    data class InvalidInput(val reason: JsonRejection) : DependencyGraphDecoding
    data object UnsupportedVersion : DependencyGraphDecoding
    data object SchemaMismatch : DependencyGraphDecoding
}

/**
 * Fixed-schema UTF-8 decoding only. Streams remain caller-owned on every outcome.
 * This neither activates edges nor binds a graph to a build, runs an evaluator or changes a draft.
 * Perform stream work off the UI thread; provider timeouts and cancellation remain caller-owned.
 */
class DependencyGraphDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream): DependencyGraphDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return DependencyGraphDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (!parsed.path("schema_version").isTextual || parsed.path("schema_version").textValue() != "1.0.0")
            return DependencyGraphDecoding.UnsupportedVersion
        val json = parsed.toString()
        // Reopening the exact retained representation must fit the same parser budgets.
        when (val result = validator.validate(FrozenDocument.DEPENDENCY_GRAPH, json.byteInputStream())) {
            is FrozenValidation.InvalidInput -> return DependencyGraphDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return DependencyGraphDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        val revision = try {
            parsed["revision"].decimalValue().toBigIntegerExact()
        } catch (_: ArithmeticException) {
            return DependencyGraphDecoding.SchemaMismatch
        }
        if (revision.signum() <= 0) return DependencyGraphDecoding.SchemaMismatch
        val edges = parsed["edges"].map { edge ->
            val relationship = DependencyRelationship.entries.firstOrNull {
                it.encoded == edge["relationship"].textValue()
            } ?: return DependencyGraphDecoding.SchemaMismatch
            val activation = DependencyActivation.entries.firstOrNull {
                it.encoded == edge["activation"].textValue()
            } ?: return DependencyGraphDecoding.SchemaMismatch
            DependencyEdge(
                edge["id"].textValue(), edge["dependent_kind"].textValue(), edge["depends_on_kind"].textValue(),
                relationship, activation, edge["rationale"].textValue(),
            )
        }
        return DependencyGraphDecoding.Decoded(DependencyGraph(
            parsed["schema_version"].textValue(), parsed["id"].textValue(), revision, edges, json,
        ))
    }
}
