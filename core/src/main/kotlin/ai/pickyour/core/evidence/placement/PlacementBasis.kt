package ai.pickyour.core.evidence.placement

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections

/** Explicit synthetic receiving profile. It is never the frozen-v1 evaluator's dispatch key. */
const val ANDROID_PLACEMENT_PROFILE = "android-placement-receiving/0.1"

enum class PlacementProblem {
    INPUT_LIMIT, MALFORMED_IDENTITY, DECLARED_HASH_MISMATCH, INVALID_FROZEN_BUILD,
    INVALID_FROZEN_CATALOG, DUPLICATE_IDENTITY, DANGLING_LINK, INVALID_ANSWER_PROFILE,
    INVALID_FIELD, INVALID_DOMAIN, CONTRADICTORY_INPUT, UNSUPPORTED_PROFILE,
}
class PlacementRejected(val problem: PlacementProblem) : IllegalArgumentException(problem.name)

/** Categories describe retained inputs; their labels or hashes never qualify their evidence. */
enum class PlacementInputKind {
    CATALOG, ANSWERS, HARDWARE, DEPLOYMENT, WORKLOAD_STAGES, ENGINE_BUILDS,
    NORMALIZATION, SOURCE_POLICY, QUALIFICATION,
}

/** Caller-owned original bytes are copied before any identity is computed. */
class PlacementInput(
    val name: String,
    val kind: PlacementInputKind,
    bytes: ByteArray,
    declaredSha256: String? = null,
) {
    private val original: ByteArray
    val sha256: String
    init {
        placementIdentity(name)
        if (bytes.size > MAX_BYTES) placementReject(PlacementProblem.INPUT_LIMIT)
        original = bytes.copyOf()
        sha256 = placementDigest(original)
        declaredSha256?.let {
            if (!HASH.matches(it)) placementReject(PlacementProblem.MALFORMED_IDENTITY)
            if (it != sha256) placementReject(PlacementProblem.DECLARED_HASH_MISMATCH)
        }
    }
    fun originalBytes(): ByteArray = original.copyOf()
    override fun toString() = "PlacementInput"
    companion object {
        const val MAX_BYTES = 262_144
        internal val HASH = Regex("[0-9a-f]{64}")
    }
}

class PlacementSelection internal constructor(
    val id: String,
    val kind: String,
    val enabled: Boolean,
    val catalogItemId: String,
    val catalogItemVersion: String,
) {
    override fun toString() = "PlacementSelection"
}

class PlacementLink internal constructor(
    val id: String,
    val from: String,
    val to: String,
    val relation: String,
    /** Frozen optional data, distinct from the receiving request's required/optional assertion. */
    val recordedRequired: Boolean?,
) {
    override fun toString() = "PlacementLink"
}

/**
 * Immutable original Build/catalog and all named source inputs for one operation and policy pair.
 * A changed but valid basis is a different qualification key, not a malformed-input diagnosis.
 */
class PlacementBasis private constructor(
    val operation: String,
    val normalizationPolicy: String,
    val sourcePolicy: String,
    buildBytes: ByteArray,
    val buildId: String,
    val buildRevision: BigInteger,
    selections: List<PlacementSelection>,
    links: List<PlacementLink>,
    val hasRecordedLinks: Boolean,
    val catalogMatchesBuild: Boolean,
    val catalogInputName: String?,
    private val catalogItems: Map<Pair<String, String>, JsonNode>,
    inputs: List<PlacementInput>,
) {
    private val build = buildBytes.copyOf()
    val originalBuildSha256 = placementDigest(build)
    val selections = immutablePlacementList(selections)
    val links = immutablePlacementList(links)
    private val bySelection = this.selections.associateBy { it.id }
    private val byInput = immutablePlacementMap(inputs.associateBy { it.name })
    val inputNames: Set<String> = Collections.unmodifiableSet(LinkedHashSet(byInput.keys))
    val sha256: String = placementKey(buildList {
        add(ANDROID_PLACEMENT_PROFILE.toByteArray())
        add(operation.toByteArray(Charsets.UTF_8))
        add(normalizationPolicy.toByteArray(Charsets.UTF_8))
        add(sourcePolicy.toByteArray(Charsets.UTF_8))
        add(build)
        add(byteArrayOf(if (catalogInputName == null) 0 else 1))
        add(catalogInputName.orEmpty().toByteArray(Charsets.UTF_8))
        for (input in inputs.sortedBy { it.name }) {
            add(input.name.toByteArray(Charsets.UTF_8))
            add(input.kind.name.toByteArray(Charsets.US_ASCII))
            add(input.originalBytes())
        }
    })

    fun originalBuildBytes(): ByteArray = build.copyOf()
    fun input(name: String): PlacementInput? = byInput[name]
    fun selection(id: String): PlacementSelection? = bySelection[id]
    /** An exact selected version only. Missing/mismatched catalog scope never falls back to an ID. */
    fun catalogItem(selection: PlacementSelection): JsonNode? =
        if (catalogMatchesBuild && bySelection[selection.id] === selection)
            catalogItems[selection.catalogItemId to selection.catalogItemVersion]?.deepCopy() else null

    override fun toString() = "PlacementBasis"

    companion object {
        private val validator by lazy { FrozenSchemaValidator() }
        private val reader = BoundedJsonReader()
        const val MAX_INPUTS = 32
        const val MAX_TOTAL_INPUT_BYTES = 2_097_152
        const val MAX_SELECTIONS = 512
        const val MAX_LINKS = 1024

        fun read(
            buildBytes: ByteArray,
            operation: String,
            normalizationPolicy: String,
            sourcePolicy: String,
            inputs: List<PlacementInput>,
            catalogInputName: String? = null,
            declaredBuildSha256: String? = null,
        ): PlacementBasis {
            listOf(operation, normalizationPolicy, sourcePolicy).forEach(::placementIdentity)
            if (buildBytes.size > PlacementInput.MAX_BYTES || inputs.size > MAX_INPUTS ||
                inputs.sumOf { it.originalBytes().size.toLong() } + buildBytes.size > MAX_TOTAL_INPUT_BYTES)
                placementReject(PlacementProblem.INPUT_LIMIT)
            if (inputs.map { it.name }.toSet().size != inputs.size)
                placementReject(PlacementProblem.DUPLICATE_IDENTITY)
            if (inputs.none { it.name == normalizationPolicy && it.kind == PlacementInputKind.NORMALIZATION } ||
                inputs.none { it.name == sourcePolicy && it.kind == PlacementInputKind.SOURCE_POLICY })
                placementReject(PlacementProblem.INVALID_FIELD)
            val build = buildBytes.copyOf()
            declaredBuildSha256?.let {
                if (!PlacementInput.HASH.matches(it)) placementReject(PlacementProblem.MALFORMED_IDENTITY)
                if (it != placementDigest(build)) placementReject(PlacementProblem.DECLARED_HASH_MISMATCH)
            }
            val value = parseFrozen(FrozenDocument.BUILD, build, PlacementProblem.INVALID_FROZEN_BUILD)
            val selections = value["selections"].map { node ->
                PlacementSelection(node["id"].textValue(), node["kind"].textValue(), node["enabled"].booleanValue(),
                    node["catalog_item_id"].textValue(), node["catalog_item_version"].textValue())
            }
            if (selections.size > MAX_SELECTIONS) placementReject(PlacementProblem.INPUT_LIMIT)
            val selectionIds = selections.map { it.id }.toSet()
            if (selectionIds.size != selections.size) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
            val links = value["links"]?.map { node ->
                PlacementLink(node["id"].textValue(), node["from_selection_id"].textValue(),
                    node["to_selection_id"].textValue(), node["relation"].textValue(),
                    node["required"]?.booleanValue())
            }.orEmpty()
            if (links.size > MAX_LINKS) placementReject(PlacementProblem.INPUT_LIMIT)
            if (links.map { it.id }.toSet().size != links.size) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
            if (links.any { it.from !in selectionIds || it.to !in selectionIds })
                placementReject(PlacementProblem.DANGLING_LINK)
            var catalogMatches = false
            val items = LinkedHashMap<Pair<String, String>, JsonNode>()
            if (catalogInputName != null) {
                placementIdentity(catalogInputName)
                val input = inputs.find { it.name == catalogInputName }
                    ?: placementReject(PlacementProblem.INVALID_FROZEN_CATALOG)
                if (input.kind != PlacementInputKind.CATALOG) placementReject(PlacementProblem.INVALID_FROZEN_CATALOG)
                val catalog = parseFrozen(FrozenDocument.CATALOG, input.originalBytes(), PlacementProblem.INVALID_FROZEN_CATALOG)
                catalogMatches = catalog["id"].textValue() == value["locks"]["catalog_snapshot_id"].textValue()
                for (item in catalog["items"]) {
                    val key = item["id"].textValue() to item["version"].textValue()
                    if (items.put(key, item.deepCopy()) != null) placementReject(PlacementProblem.DUPLICATE_IDENTITY)
                }
            }
            return PlacementBasis(operation, normalizationPolicy, sourcePolicy, build,
                value["id"].textValue(), value["revision"].decimalValue().toBigIntegerExact(),
                selections, links, value.has("links"), catalogMatches, catalogInputName, items, inputs.toList())
        }

        private fun parseFrozen(document: FrozenDocument, bytes: ByteArray, problem: PlacementProblem): JsonNode {
            if (validator.validate(document, bytes.inputStream()) != FrozenValidation.Valid) placementReject(problem)
            return when (val parsed = reader.read(bytes.inputStream())) {
                is JsonInputResult.Parsed -> parsed.value
                is JsonInputResult.Rejected -> placementReject(problem)
            }
        }
    }
}

internal fun placementReject(problem: PlacementProblem): Nothing = throw PlacementRejected(problem)

/** CD-2 IDs are nonempty text, without the separate CD-11 restricted scope-ID grammar. */
internal fun placementIdentity(value: String) {
    if (value.isEmpty()) placementReject(PlacementProblem.MALFORMED_IDENTITY)
    if (value.length > 4096) placementReject(PlacementProblem.INPUT_LIMIT)
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        if (Character.isHighSurrogate(ch)) {
            if (index == value.length || !Character.isLowSurrogate(value[index++]))
                placementReject(PlacementProblem.MALFORMED_IDENTITY)
        } else if (Character.isLowSurrogate(ch)) placementReject(PlacementProblem.MALFORMED_IDENTITY)
    }
}

internal fun placementDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Unambiguous length framing; array concatenation, labels and truncated hashes are not identities. */
internal fun placementKey(parts: List<ByteArray>): String {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { stream ->
        stream.writeInt(parts.size)
        for (part in parts) { stream.writeInt(part.size); stream.write(part) }
    }
    return placementDigest(buffer.toByteArray())
}

internal fun <T> immutablePlacementList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
internal fun <K, V> immutablePlacementMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
