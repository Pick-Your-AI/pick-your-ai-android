package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections

/** Explicit caller facts. A local draft counter, title or clock is never substituted for these. */
class DraftSnapshotIdentity(
    val id: String,
    val revision: BigInteger,
    val name: String,
    val ownerRef: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString() = "DraftSnapshotIdentity"
}

/**
 * Exact caller-selected bytes and identity. Version is a ruleset semver or an integer revision's
 * decimal spelling, according to its position in DraftSnapshotInputs. No latest-version lookup.
 * Hash agreement proves byte identity, not publication authority, source rights or freshness.
 */
class DraftProjectionArtifact(
    val expectedId: String,
    val expectedVersion: String,
    val expectedHash: ArtifactHash,
    bytes: ByteArray,
) {
    private val payload: ByteArray
    init {
        require(bytes.size <= LocalDraftStore.MAX_BYTES && expectedId.length <= 16_384 && expectedVersion.length <= 16_384) {
            "PROJECTION_INPUT_LIMIT"
        }
        payload = bytes.copyOf()
    }
    internal fun bytes(): ByteArray = payload.copyOf()
    override fun toString() = "DraftProjectionArtifact"
}

/** Local profile version, separate from frozen schema_version and the stored draft envelope version. */
class DraftSnapshotInputs(
    val profileVersion: Int,
    val identity: DraftSnapshotIdentity? = null,
    val catalog: DraftProjectionArtifact? = null,
    val ruleset: DraftProjectionArtifact? = null,
    val graph: DraftProjectionArtifact? = null,
    val pricebook: DraftProjectionArtifact? = null,
) {
    override fun toString() = "DraftSnapshotInputs"
}

enum class ProjectionArtifactKind(val document: FrozenDocument, internal val counter: String) {
    CATALOG(FrozenDocument.CATALOG, "revision"), RULESET(FrozenDocument.RULESET, "version"),
    GRAPH(FrozenDocument.DEPENDENCY_GRAPH, "revision"), PRICEBOOK(FrozenDocument.PRICEBOOK, "revision"),
}

/** Local descriptor; not a new shared sidecar or a publication/eligibility envelope. */
class ProjectionArtifactPin internal constructor(
    val kind: ProjectionArtifactKind,
    val id: String,
    val version: String,
    val hash: ArtifactHash,
) {
    override fun toString() = "ProjectionArtifactPin"
}

enum class DraftProjectionProblem {
    UNSUPPORTED_PROFILE, MISSING_IDENTITY, INVALID_IDENTITY_TIME, MISSING_ARTIFACT,
    ARTIFACT_HASH_MISMATCH, ARTIFACT_SCHEMA_MISMATCH, ARTIFACT_ID_MISMATCH, ARTIFACT_VERSION_MISMATCH,
    ARTIFACT_COUNTER_UNSUPPORTED, REVIEW_LIMIT_EXCEEDED,
    DUPLICATE_ARTIFACT_ENTRY, MISSING_OBJECTIVE, MISSING_FOUNDATION,
    SELECTION_CATALOG_MISMATCH, MISSING_CATALOG_REFERENCE, AMBIGUOUS_CATALOG_REFERENCE,
    CATALOG_REFERENCE_KIND_MISMATCH, MISSING_MODEL_PROVIDER_LINK, PRICEBOOK_CATALOG_MISMATCH,
    UNKNOWN_OPTIONAL_INTENT, CONTRADICTORY_OPTIONAL_INTENT, INCOMPLETE_OPTIONAL_SELECTIONS,
    CONFLICTING_WORKLOAD_INPUT, BUILD_SCHEMA_MISMATCH, INPUT_LIMIT,
    ARTIFACT_UNSUPPORTED_SCHEMA_VERSION,
}

/** Bounded review data; toString never emits user content, IDs or artifact contents. */
class DraftProjectionFinding internal constructor(
    val problem: DraftProjectionProblem,
    val path: String,
    relatedValues: List<String> = emptyList(),
) {
    val relatedValues: List<String> = immutableList(relatedValues)
    override fun toString() = problem.name
}

sealed interface DraftProjectionPreparation {
    class Withheld internal constructor(val source: StoredDraft, findings: List<DraftProjectionFinding>) : DraftProjectionPreparation {
        val findings: List<DraftProjectionFinding> = immutableList(findings)
        override fun toString() = "DraftProjectionPreparation.Withheld"
    }
    /** Captures one exact durable source and all candidate inputs; this is not a saved snapshot. */
    class Review internal constructor(
        val source: StoredDraft,
        val identity: DraftSnapshotIdentity,
        internal val sourceBytes: ByteArray,
        internal val candidateBytes: ByteArray,
        internal val artifacts: List<CheckedProjectionArtifact>,
    ) : DraftProjectionPreparation {
        val retainedOnlyPaths: List<String> = retainedOnlyPaths(source.metadata)
        val pins: List<ProjectionArtifactPin> = immutableList(artifacts.map { it.pin })
        /** Exact typed inputs that will be represented. Every other local value remains in source. */
        val projectedWorkload: DraftWorkload get() = source.content.workload
        val projectedComponents: DraftSelections get() = source.content.components
        /** Call only after reviewing this source, descriptors and complete retained-only checklist. */
        fun acknowledgeRetainedOnly(paths: Set<String>): DraftProjectionAcknowledgment? =
            if (paths == retainedOnlyPaths.toSet()) DraftProjectionAcknowledgment(this) else null
        override fun toString() = "DraftProjectionPreparation.Review"
    }
    data object Conflict : DraftProjectionPreparation
    data class Failed(val reason: DraftStorageFailure) : DraftProjectionPreparation
}

/** Opaque acknowledgment belongs to exactly one immutable review, including its chosen descriptors. */
class DraftProjectionAcknowledgment internal constructor(internal val review: DraftProjectionPreparation.Review) {
    override fun toString() = "DraftProjectionAcknowledgment"
}

sealed interface DraftProjectionCreation {
    class Created(val candidate: DraftSnapshotCandidate) : DraftProjectionCreation
    data object Conflict : DraftProjectionCreation
    data object ReviewAcknowledgmentMismatch : DraftProjectionCreation
    data class Failed(val reason: DraftStorageFailure) : DraftProjectionCreation
}

/**
 * Frozen-v1 draft-lifecycle candidate, never a saved/published revision or a compatibility verdict.
 * Original local history and exact artifact bytes accompany it in memory. A receiving archive must
 * enforce build-ID/revision immutability and atomically retain this complete input set before save.
 */
class DraftSnapshotCandidate internal constructor(review: DraftProjectionPreparation.Review) {
    val profileVersion: Int = 1
    val source: StoredDraft = review.source
    val identity: DraftSnapshotIdentity = review.identity
    val retainedOnlyPaths: List<String> = review.retainedOnlyPaths
    val pins: List<ProjectionArtifactPin> = review.pins
    private val payload = review.candidateBytes.copyOf()
    private val retainedSourceBytes = review.sourceBytes.copyOf()
    private val artifacts = review.artifacts.associate { it.pin.kind to it.bytes.copyOf() }
    val hash: ArtifactHash = projectionHash(payload)
    fun bytes(): ByteArray = payload.copyOf()
    /** The exact reviewed current local envelope, not a re-encoding or a wire Build extension. */
    fun sourceBytes(): ByteArray = retainedSourceBytes.copyOf()
    fun artifactBytes(kind: ProjectionArtifactKind): ByteArray? = artifacts[kind]?.copyOf()
    override fun toString() = "DraftSnapshotCandidate"
}

internal class CheckedProjectionArtifact(val pin: ProjectionArtifactPin, val bytes: ByteArray, val value: ObjectNode)

/** Shared pure projection result, also used to verify an archived retained source without a token. */
internal sealed interface ProjectionInspection {
    class Withheld(val findings: List<DraftProjectionFinding>) : ProjectionInspection
    class Ready(val identity: DraftSnapshotIdentity, val candidateBytes: ByteArray,
        val artifacts: List<CheckedProjectionArtifact>) : ProjectionInspection
}

/** Pure preparation after the canonical store has checked the current captured revision. */
internal object DraftSnapshotProjection {
    private val mapper = ObjectMapper()
    private const val MAX_FINDINGS = 256
    private const val MAX_RELATED_VALUES = 128
    private val registry by lazy {
        requireNotNull(javaClass.getResourceAsStream("/contracts/v1/schemas/component-registry.json")).use {
            SelectionJson.parse(it.readBytes())["kinds"]
        }
    }
    // Frozen AGENT_PROTOCOL activation requirements and rule.required.foundation's explicit roles.
    private val foundationKinds = listOf("workload", "deployment_mode", "model", "context_policy")

    fun prepare(source: StoredDraft, inputs: DraftSnapshotInputs, sourceBytes: ByteArray): DraftProjectionPreparation =
        when (val result = inspect(source.content, inputs)) {
            is ProjectionInspection.Withheld -> DraftProjectionPreparation.Withheld(source, result.findings)
            is ProjectionInspection.Ready -> DraftProjectionPreparation.Review(source, result.identity,
                sourceBytes.copyOf(), result.candidateBytes, result.artifacts)
        }

    /** Reuse the same admission on retained content; this grants no live draft/store authority. */
    fun inspect(content: DraftContent, inputs: DraftSnapshotInputs): ProjectionInspection {
        val findings = mutableListOf<DraftProjectionFinding>()
        var limited = false
        fun finding(problem: DraftProjectionProblem, path: String, values: List<String> = emptyList()) {
            if (values.size > MAX_RELATED_VALUES || findings.size >= MAX_FINDINGS - 1) {
                if (!limited) {
                    findings += DraftProjectionFinding(DraftProjectionProblem.REVIEW_LIMIT_EXCEEDED, path)
                    limited = true
                }
                if (findings.size >= MAX_FINDINGS - 1) return
            }
            findings += DraftProjectionFinding(problem, path, values.take(MAX_RELATED_VALUES))
        }
        if (inputs.profileVersion != 1) {
            finding(DraftProjectionProblem.UNSUPPORTED_PROFILE, "/profile_version")
            return ProjectionInspection.Withheld(immutableList(findings))
        }
        val identity = inputs.identity
        if (identity == null) finding(DraftProjectionProblem.MISSING_IDENTITY, "/identity")
        else if (identity.updatedAt < identity.createdAt) finding(DraftProjectionProblem.INVALID_IDENTITY_TIME, "/identity/updated_at")

        val checked = mutableListOf<CheckedProjectionArtifact>()
        for ((kind, input) in listOf(ProjectionArtifactKind.CATALOG to inputs.catalog,
            ProjectionArtifactKind.RULESET to inputs.ruleset, ProjectionArtifactKind.GRAPH to inputs.graph,
            ProjectionArtifactKind.PRICEBOOK to inputs.pricebook)) {
            if (input == null) {
                if (kind != ProjectionArtifactKind.PRICEBOOK) finding(DraftProjectionProblem.MISSING_ARTIFACT, "/artifacts/${kind.name.lowercase()}")
                continue
            }
            readArtifact(kind, input, ::finding)?.let(checked::add)
        }

        val workload = content.workload
        if (workload.region == null) finding(DraftProjectionProblem.MISSING_OBJECTIVE, "/objectives/region")
        if (workload.currency == null) finding(DraftProjectionProblem.MISSING_OBJECTIVE, "/objectives/currency")
        if (workload.dataClassification == null) finding(DraftProjectionProblem.MISSING_OBJECTIVE, "/objectives/data_classification")
        val selections = content.components.selections
        val active = selections.filter { it.enabled }
        for (kind in foundationKinds) {
            if (active.count { it.kind == kind && it.role == "primary" } != 1)
                finding(DraftProjectionProblem.MISSING_FOUNDATION, "/selections", listOf(kind, "primary"))
        }
        // DraftSelections already enforces unique IDs/all roles, link endpoints and at-most-one
        // active registry cardinality. Preparation adds required presence, not a second reducer.
        for (tier in OptionalTier.entries) {
            val entered = content.optional.getValue(tier)
            val enabled = active.any { registry[it.kind]["tier"].textValue() == tier.name }
            val problem = when {
                entered.activation == Activation.UNKNOWN -> DraftProjectionProblem.UNKNOWN_OPTIONAL_INTENT
                entered.activation == Activation.DISABLED && enabled -> DraftProjectionProblem.CONTRADICTORY_OPTIONAL_INTENT
                entered.activation == Activation.ENABLED && !enabled -> DraftProjectionProblem.INCOMPLETE_OPTIONAL_SELECTIONS
                else -> null
            }
            if (problem != null) finding(problem, "/content/optional/${tier.name}/activation")
        }
        active.singleOrNull { it.kind == "workload" && it.role == "primary" }?.let { selected ->
            val settings = selected.settings
            for ((field, value) in listOf("requests_per_day" to workload.requestsPerDay,
                "avg_input_tokens" to workload.avgInputTokens, "avg_output_tokens" to workload.avgOutputTokens,
                "concurrency" to workload.maxConcurrency, "latency_budget_ms" to workload.maxLatencyMs)) {
                if (value != null && settings.has(field) && settings[field].bigIntegerValue() != value)
                    finding(DraftProjectionProblem.CONFLICTING_WORKLOAD_INPUT, "/selections/${selected.id}/settings/$field")
            }
        }

        val catalog = checked.find { it.pin.kind == ProjectionArtifactKind.CATALOG }
        if (catalog != null) inspectReferences(content.components, catalog, checked.find { it.pin.kind == ProjectionArtifactKind.PRICEBOOK }, ::finding)
        if (findings.isNotEmpty() || identity == null) return ProjectionInspection.Withheld(immutableList(findings))

        val byKind = checked.associateBy { it.pin.kind }
        val bytes = try {
            val build = mapper.createObjectNode().put("schema_version", "1.0.0")
                .put("id", identity.id).put("revision", identity.revision).put("name", identity.name)
                .put("owner_ref", identity.ownerRef).put("lifecycle", "draft")
                .put("created_at", identity.createdAt.toString()).put("updated_at", identity.updatedAt.toString())
            val objectives = build.putObject("objectives")
            objectives.put("region", workload.region).put("currency", workload.currency)
                .put("data_classification", workload.dataClassification!!.encoded)
            workload.monthlyBudgetAmount?.let { objectives.put("monthly_budget_amount", it.encoded) }
            listOf("requests_per_day" to workload.requestsPerDay, "avg_input_tokens" to workload.avgInputTokens,
                "avg_output_tokens" to workload.avgOutputTokens, "max_concurrency" to workload.maxConcurrency,
                "max_latency_ms" to workload.maxLatencyMs).forEach { (field, value) -> if (value != null) objectives.put(field, value) }
            val locks = build.putObject("locks")
            val catalogPin = byKind.getValue(ProjectionArtifactKind.CATALOG).pin
            val rulePin = byKind.getValue(ProjectionArtifactKind.RULESET).pin
            val graphPin = byKind.getValue(ProjectionArtifactKind.GRAPH).pin
            locks.put("catalog_snapshot_id", catalogPin.id).put("ruleset_id", rulePin.id).put("ruleset_version", rulePin.version)
                .put("dependency_graph_id", graphPin.id).put("dependency_graph_revision", BigInteger(graphPin.version))
            byKind[ProjectionArtifactKind.PRICEBOOK]?.pin?.let { locks.put("pricebook_id", it.id) }
            build.putArray("selections").also { target -> selections.forEach { target.add(it.toJson()) } }
            build.putArray("links").also { target -> content.components.links.forEach { target.add(it.toJson()) } }
            SelectionJson.encode(build)
        } catch (_: IllegalArgumentException) {
            finding(DraftProjectionProblem.INPUT_LIMIT, "/build")
            return ProjectionInspection.Withheld(immutableList(findings))
        }
        if (SelectionJson.validator.validate(FrozenDocument.BUILD, bytes.inputStream()) != FrozenValidation.Valid) {
            finding(DraftProjectionProblem.BUILD_SCHEMA_MISMATCH, "/build")
            return ProjectionInspection.Withheld(immutableList(findings))
        }
        return ProjectionInspection.Ready(identity, bytes, immutableList(checked))
    }

    private fun readArtifact(kind: ProjectionArtifactKind, input: DraftProjectionArtifact,
        finding: (DraftProjectionProblem, String, List<String>) -> Unit): CheckedProjectionArtifact? {
        val path = "/artifacts/${kind.name.lowercase()}"
        val bytes = input.bytes()
        if (projectionHash(bytes) != input.expectedHash) {
            finding(DraftProjectionProblem.ARTIFACT_HASH_MISMATCH, path, emptyList())
            return null
        }
        val node = try { SelectionJson.parse(bytes) } catch (_: IllegalArgumentException) {
            finding(DraftProjectionProblem.ARTIFACT_SCHEMA_MISMATCH, path, emptyList())
            return null
        }
        val schemaVersion = node["schema_version"]
        val problem = when {
            schemaVersion?.isTextual == true && schemaVersion.textValue() != "1.0.0" ->
                DraftProjectionProblem.ARTIFACT_UNSUPPORTED_SCHEMA_VERSION
            SelectionJson.validator.validate(kind.document, bytes.inputStream()) != FrozenValidation.Valid -> DraftProjectionProblem.ARTIFACT_SCHEMA_MISMATCH
            else -> null
        }
        if (problem != null) { finding(problem, path, emptyList()); return null }
        val version = if (kind == ProjectionArtifactKind.RULESET) node[kind.counter].textValue() else {
            // JSON Schema integer includes exactly integral decimal/exponent forms. Preserve the
            // original bytes, while comparing the typed counter without truncation or machine width.
            val number = try { node[kind.counter].decimalValue().toBigIntegerExact() } catch (_: ArithmeticException) {
                finding(DraftProjectionProblem.ARTIFACT_COUNTER_UNSUPPORTED, path, emptyList())
                return null
            }
            if (number.bitLength() > 426 || number.toString().length > 128) {
                finding(DraftProjectionProblem.ARTIFACT_COUNTER_UNSUPPORTED, path, emptyList())
                return null
            }
            number.toString()
        }
        val pinProblem = when {
            node["id"].textValue() != input.expectedId -> DraftProjectionProblem.ARTIFACT_ID_MISMATCH
            version != input.expectedVersion -> DraftProjectionProblem.ARTIFACT_VERSION_MISMATCH
            else -> null
        }
        if (pinProblem != null) { finding(pinProblem, path, emptyList()); return null }
        val array = node[when (kind) {
            ProjectionArtifactKind.CATALOG -> "items"
            ProjectionArtifactKind.RULESET -> "rules"
            ProjectionArtifactKind.GRAPH -> "edges"
            ProjectionArtifactKind.PRICEBOOK -> "rates"
        }]
        val identities = array.map { entry -> entry["id"].textValue() to
            if (kind == ProjectionArtifactKind.CATALOG) entry["version"].textValue() else "" }
        if (identities.toSet().size != identities.size) {
            finding(DraftProjectionProblem.DUPLICATE_ARTIFACT_ENTRY, path, emptyList())
            return null
        }
        return CheckedProjectionArtifact(ProjectionArtifactPin(kind, input.expectedId, input.expectedVersion, input.expectedHash), bytes, node)
    }

    private fun inspectReferences(components: DraftSelections, catalog: CheckedProjectionArtifact,
        pricebook: CheckedProjectionArtifact?, finding: (DraftProjectionProblem, String, List<String>) -> Unit) {
        val entries = catalog.value["items"].toList()
        val byIdentity = entries.associateBy { it["id"].textValue() to it["version"].textValue() }
        val byId = entries.groupBy { it["id"].textValue() }
        // Reuse bounded descriptor lists across references. A large ambiguous target group must not
        // create quadratic retained diagnostics; the extra value explicitly marks a limited review.
        val referenceValues = byId.mapValues { (id, targets) -> listOf(id) + targets.asSequence()
            .map { it["version"].textValue() }.sorted().take(MAX_RELATED_VALUES).toList() }
        for ((index, selection) in components.selections.withIndex()) {
            val entry = byIdentity[selection.catalogItemId to selection.catalogItemVersion]
            if (entry == null || entry["kind"].textValue() != selection.kind)
                finding(DraftProjectionProblem.SELECTION_CATALOG_MISMATCH, "/selections/$index", listOf(selection.id))
        }
        for ((index, entry) in entries.withIndex()) {
            for ((field, expectedKind) in catalogReferences[entry["kind"].textValue()].orEmpty()) {
                val value = entry["spec"][field] ?: continue
                val refs = if (value.isArray) value.toList() else listOf(value)
                for (ref in refs) {
                    val targets = byId[ref.textValue()].orEmpty()
                    val problem = when {
                        targets.isEmpty() -> DraftProjectionProblem.MISSING_CATALOG_REFERENCE
                        targets.size > 1 -> DraftProjectionProblem.AMBIGUOUS_CATALOG_REFERENCE
                        targets.single()["kind"].textValue() != expectedKind -> DraftProjectionProblem.CATALOG_REFERENCE_KIND_MISMATCH
                        else -> null
                    }
                    if (problem != null) finding(problem, "/artifacts/catalog/items/$index/spec/$field",
                        referenceValues[ref.textValue()] ?: listOf(ref.textValue()))
                }
            }
        }
        // The frozen component documentation requires an active model to be linked to its provider.
        // Catalog references do not manufacture a selected provider or user wiring.
        for (model in components.selections.filter { it.enabled && it.kind == "model" }) {
            val entry = byIdentity[model.catalogItemId to model.catalogItemVersion] ?: continue
            val providerId = entry["spec"]["provider_ref"]?.textValue() ?: continue
            val providerIds = components.selections.filter {
                it.enabled && it.kind == "model_provider" && it.catalogItemId == providerId &&
                    byIdentity.containsKey(it.catalogItemId to it.catalogItemVersion)
            }.map { it.id }.toSet()
            if (components.links.none { it.fromSelectionId == model.id && it.toSelectionId in providerIds && it.relation == "provided_by" })
                finding(DraftProjectionProblem.MISSING_MODEL_PROVIDER_LINK, "/links", listOf(model.id))
        }
        pricebook?.value?.get("rates")?.forEachIndexed { index, rate ->
            if (!byIdentity.containsKey(rate["catalog_item_id"].textValue() to rate["catalog_item_version"].textValue()))
                finding(DraftProjectionProblem.PRICEBOOK_CATALOG_MISMATCH, "/artifacts/pricebook/rates/$index", listOf(rate["id"].textValue()))
        }
    }

    // Exact mapping from the frozen reference test's REFERENCE_FIELDS. No guessed *_ref traversal.
    private val catalogReferences: Map<String, Map<String, String>> = mapOf(
        "model" to mapOf("provider_ref" to "model_provider"),
        "embedding_model" to mapOf("provider_ref" to "model_provider"),
        "compute_profile" to mapOf("hosting_ref" to "hosting_platform", "accelerator_ref" to "accelerator"),
        "hosting_platform" to mapOf("accelerator_refs" to "accelerator"),
        "storage" to mapOf("hosting_ref" to "hosting_platform"),
        "knowledge_base" to mapOf("data_source_refs" to "data_source"),
        "vector_database" to mapOf("hosting_ref" to "hosting_platform"),
        "mcp_server" to mapOf("tool_refs" to "tool"),
        "integration" to mapOf("associated_tool_refs" to "tool"),
        "training_compute" to mapOf("compute_profile_ref" to "compute_profile", "storage_ref" to "storage"),
        "training_dataset" to mapOf("data_source_refs" to "data_source"),
        "fine_tuned_model" to mapOf("base_model_ref" to "model", "training_framework_ref" to "training_framework", "training_dataset_ref" to "training_dataset"),
        "evaluation_suite" to mapOf("dataset_refs" to "training_dataset"),
        "deployment_target" to mapOf("hosting_ref" to "hosting_platform"),
    )
}

/** Exact local field groups omitted from the Build, even when a value is null, empty or a matching label. */
internal fun retainedOnlyPaths(metadata: Map<String, String>): List<String> = immutableList(buildList {
    addAll(listOf("/content/objective", "/content/context", "/content/primaryModel"))
    OptionalTier.entries.forEach { tier ->
        listOf("activation", "selections", "settings").forEach { field -> add("/content/optional/${tier.name}/$field") }
    }
    addAll(listOf("/local_format_version", "/revision", "/legacy_source_hash", "/metadata"))
    metadata.keys.sorted().forEach { key -> add("/metadata/" + key.replace("~", "~0").replace("/", "~1")) }
    add("/history")
})

private fun projectionHash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) })
private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
