package ai.pickyour.core.evidence.placement

import ai.pickyour.core.evidence.answers.Cd1Answer
import ai.pickyour.core.evidence.answers.Cd1AnswerIndex
import ai.pickyour.core.evidence.answers.Cd1Rejected
import ai.pickyour.core.evidence.answers.Cd1State
import ai.pickyour.core.evidence.answers.KnownAnswerSchema
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger

/** Closed field schemas are selected by this consumer, never by answer data. */
enum class PlacementField(schema: String) {
    BOOLEAN("""{"type":"boolean"}"""),
    TEXT("""{"type":"string","minLength":1}"""),
    NULLABLE_TEXT("""{"type":["string","null"]}"""),
    TEXT_SET("""{"type":"array","items":{"type":"string","minLength":1},"uniqueItems":true}"""),
    // The pinned CD1 schema dialect has no alternation; canonical zero/leading-zero checks follow admission.
    BYTE_COUNT("""{"type":"string","pattern":"^[0-9]+$"}"""),
    POSITIVE_INTEGER("""{"type":"integer","minimum":1}"""),
    PLACEMENT("""{"enum":["always","on_demand"]}"""),
    RESIDENCY("""{"enum":["resident","excluded"]}"""),
    RESERVE_BASIS("""{"enum":["before_reserve","after_reserve"]}"""),
    ADMISSION_UNIT("""{"enum":["sequences","requests","step_batch"]}"""),
    RUNTIME_BINDING("""{"type":"object","additionalProperties":false,"properties":{"kind":{"enum":["model_engine","service_runtime"]},"id":{"type":"string","minLength":1},"build":{"type":"string","minLength":1},"source_input":{"type":"string","minLength":1},"instance":{"type":"string","minLength":1},"replica":{"type":["string","null"],"minLength":1},"rank":{"type":["string","null"],"minLength":1},"admission":{"type":["string","null"],"minLength":1},"stage":{"type":["string","null"],"minLength":1}},"required":["kind","id","build","source_input","instance","replica","rank","admission","stage"]}"""),
    OPERATOR("""{"enum":["self","provider"]}"""),
    CAPACITY("""{"enum":["shared","reserved_throughput","dedicated_instances"]}"""),
    ARTIFACT_KIND("""{"enum":["adapter","full"]}"""),
    ROLE("""{"enum":["host","device","serving_engine","base_artifact","speculative_draft","other"]}"""),
    QUANTITY_TRACE("""{"type":"object","additionalProperties":false,"properties":{"source_quantity":{"type":"string","minLength":1},"source_unit":{"type":"string","minLength":1},"method":{"type":"string","minLength":1},"rounding":{"enum":["exact","outward"]}},"required":["source_quantity","source_unit","method","rounding"]}""");

    internal val knownSchema by lazy { KnownAnswerSchema.read(schema.toByteArray(Charsets.UTF_8)) }
}

enum class PlacementCause { NONE, DIRECT, ANCESTOR, WITHHELD_DESCENDANT }
enum class PlacementFact {
    OPERATOR, CAPACITY, LOCAL_VISIBILITY, ROLE, OPERATION, COMPATIBILITY, APPLICABILITY,
    NORMALIZATION, BYTE_BOUND, PHYSICAL_IDENTITY, ALLOCATION_IDENTITY, RESIDENCY, ACCESS,
    INVENTORY, COVERAGE, CEILING, WORKLOAD,
}
enum class PlacementCoverageKind {
    SERVING_COMPONENTS, ROLE_BINDINGS, SUPPORT_CONDITIONS, HARDWARE_INSTANCES, PHYSICAL_POOL_MAP,
    CONSUMERS, CHARGE_TERMS, INDEPENDENT_CEILINGS, SCENARIOS, TRANSITIONS, WORKLOAD_STAGES,
}
enum class PlacementRuntimeKind { MODEL_ENGINE, SERVICE_RUNTIME }

/** Tagged runtime alternatives do not add another runs_on graph. */
data class PlacementRuntime(val kind: PlacementRuntimeKind, val id: String) {
    init { placementIdentity(id) }
    override fun toString() = "PlacementRuntime"
}

/** Concrete qualification domains; null physical facts cannot establish a known identity. */
class PlacementDomain private constructor(internal val tag: String, fields: Map<String, String?>) {
    private val fields = immutablePlacementMap(fields)
    internal val key = placementKey(buildList {
        add(tag.toByteArray(Charsets.US_ASCII))
        for ((name, value) in this@PlacementDomain.fields) {
            add(name.toByteArray(Charsets.US_ASCII))
            add(byteArrayOf(if (value == null) 0 else 1))
            add(value.orEmpty().toByteArray(Charsets.UTF_8))
        }
    })
    internal val completePhysical = tag != "physical" || fields.values.none { it == null }
    init { fields.values.filterNotNull().forEach(::placementIdentity) }
    override fun equals(other: Any?) = other is PlacementDomain && key == other.key
    override fun hashCode() = key.hashCode()
    override fun toString() = "PlacementDomain"

    companion object {
        fun device(deviceId: String) = PlacementDomain("device", linkedMapOf("device" to deviceId))
        fun poolScenario(pool: PlacementDomain, scenarioId: String) = PlacementDomain("pool_scenario",
            linkedMapOf("physical_domain" to pool.key, "scenario" to scenarioId))
        fun access(poolId: String, allocationId: String, scenarioId: String,
                   consumerId: String, runtime: PlacementDomain) = PlacementDomain("access",
            linkedMapOf("pool" to poolId, "allocation" to allocationId, "scenario" to scenarioId,
                "consumer" to consumerId, "runtime_domain" to runtime.key))
        fun ceiling(limitId: String, scenarioId: String, runtime: PlacementDomain) = PlacementDomain("ceiling",
            linkedMapOf("limit" to limitId, "scenario" to scenarioId, "runtime_domain" to runtime.key))
        fun workload(runtime: PlacementDomain, classId: String) = PlacementDomain("workload",
            linkedMapOf("runtime_domain" to runtime.key, "class" to classId))
        fun component(selectionId: String) = PlacementDomain("component", linkedMapOf("selection" to selectionId))
        fun link(linkId: String) = PlacementDomain("link", linkedMapOf("link" to linkId))
        fun condition(selectionId: String, conditionId: String, kind: String) = PlacementDomain("condition",
            linkedMapOf("selection" to selectionId, "condition" to conditionId, "kind" to kind))
        fun role(selectionId: String, role: String) = PlacementDomain("role",
            linkedMapOf("selection" to selectionId, "role" to role))
        fun physical(poolId: String, addressSpace: String?, partition: String?, tenancy: String?) =
            PlacementDomain("physical", linkedMapOf("pool" to poolId, "address_space" to addressSpace,
                "partition" to partition, "tenancy" to tenancy))
        fun runtime(runtime: PlacementRuntime, instanceId: String, replicaId: String? = null,
                    rankId: String? = null, admissionId: String? = null, stageId: String? = null, buildId: String? = null) =
            PlacementDomain("runtime", linkedMapOf("runtime_kind" to runtime.kind.name, "runtime" to runtime.id,
                "build" to buildId, "instance" to instanceId, "replica" to replicaId, "rank" to rankId,
                "admission" to admissionId, "stage" to stageId))
        fun inventory(kind: PlacementCoverageKind, ownerId: String, scenarioId: String? = null) =
            PlacementDomain("inventory", linkedMapOf("inventory_kind" to kind.name, "owner" to ownerId,
                "scenario" to scenarioId))
        fun allocation(poolId: String?, allocationId: String, scenarioId: String) =
            PlacementDomain("allocation", linkedMapOf("pool" to poolId, "allocation" to allocationId,
                "scenario" to scenarioId))
    }
}

/** Immutable observation from the actual, unchanged CD1-0.2 API. */
class PlacementAnswer internal constructor(
    internal val basis: PlacementBasis,
    val inputName: String,
    val inputSha256: String,
    val requestedPath: String,
    val field: PlacementField,
    answer: Cd1Answer,
) {
    val state = answer.state
    private val supplied = answer.value
    val sourcePath = answer.sourcePath
    val presetSource = answer.presetSource
    val assumption = answer.assumption
    val value: JsonNode? get() = supplied?.deepCopy()
    val provided get() = state == Cd1State.PROVIDED
    val cause: PlacementCause = sourcePath?.let {
        val requested = placementPointer(requestedPath)
        val actual = placementPointer(it)
        when {
            requested == actual -> PlacementCause.DIRECT
            actual.size < requested.size && requested.take(actual.size) == actual -> PlacementCause.ANCESTOR
            state == Cd1State.WITHHELD && requested.size < actual.size &&
                actual.take(requested.size) == requested -> PlacementCause.WITHHELD_DESCENDANT
            else -> placementReject(PlacementProblem.INVALID_ANSWER_PROFILE)
        }
    } ?: PlacementCause.NONE

    internal fun text(): String? = if (provided && supplied?.isTextual == true) supplied.textValue() else null
    internal fun bool(): Boolean? = if (provided && supplied?.isBoolean == true) supplied.booleanValue() else null
    internal fun strings(): Set<String>? = if (provided && supplied?.isArray == true)
        supplied.map { it.textValue() }.toSet() else null
    internal fun integer(): BigInteger? = if (provided && supplied?.isNumber == true)
        supplied.decimalValue().toBigIntegerExact() else null
    internal fun bytes(): BigInteger? {
        if (!provided) return null
        val text = supplied?.takeIf { it.isTextual }?.textValue()
            ?: placementReject(PlacementProblem.INVALID_FIELD)
        if (!BYTE_COUNT.matches(text)) placementReject(PlacementProblem.INVALID_FIELD)
        return BigInteger(text)
    }
    fun qualificationKey(fact: PlacementFact, domain: PlacementDomain) =
        PlacementEvidenceKey(basis, inputName, inputSha256, requestedPath, field, fact, domain)

    override fun toString() = "PlacementAnswer(" + state.encoded + ")"
    companion object { private val BYTE_COUNT = Regex("0|[1-9][0-9]*") }
}

/** Keys bind the full immutable basis, actual answer bytes, field, requested path and concrete domain. */
class PlacementEvidenceKey internal constructor(
    internal val boundBasis: PlacementBasis,
    val inputName: String,
    val inputSha256: String,
    val requestedPath: String,
    val field: PlacementField,
    val fact: PlacementFact,
    val domain: PlacementDomain,
) {
    val basisSha256 = boundBasis.sha256
    init {
        val input = boundBasis.input(inputName) ?: placementReject(PlacementProblem.INVALID_FIELD)
        if (input.kind != PlacementInputKind.ANSWERS) placementReject(PlacementProblem.INVALID_FIELD)
        if (input.sha256 != inputSha256) placementReject(PlacementProblem.DECLARED_HASH_MISMATCH)
        placementPointer(requestedPath)
    }
    val profile = ANDROID_PLACEMENT_PROFILE
    internal val key = placementKey(listOf(profile, basisSha256, inputName, inputSha256,
        requestedPath, field.name, fact.name, domain.key).map { it.toByteArray(Charsets.UTF_8) })
    override fun equals(other: Any?) = other is PlacementEvidenceKey && key == other.key
    override fun hashCode() = key.hashCode()
    override fun toString() = "PlacementEvidenceKey"
}

enum class PlacementEligibility { QUALIFIED, INELIGIBLE, UNKNOWN }
class PlacementQualification(
    val key: PlacementEvidenceKey,
    val eligibility: PlacementEligibility,
    val evidenceInputName: String,
    val evidenceSha256: String,
    val conflictRankResolved: Boolean?,
    val checkedOnly: Boolean,
) {
    init {
        placementIdentity(evidenceInputName)
        if (!PlacementInput.HASH.matches(evidenceSha256)) placementReject(PlacementProblem.MALFORMED_IDENTITY)
        // Validate every supplied original binding, including records no later predicate consumes.
        val input = key.boundBasis.input(evidenceInputName) ?: placementReject(PlacementProblem.INVALID_FIELD)
        if (input.sha256 != evidenceSha256) placementReject(PlacementProblem.DECLARED_HASH_MISMATCH)
        if (input.kind !in setOf(PlacementInputKind.QUALIFICATION, PlacementInputKind.SOURCE_POLICY))
            placementReject(PlacementProblem.INVALID_DOMAIN)
    }
    override fun toString() = "PlacementQualification"
}

class PlacementEvidenceUse internal constructor(
    val answer: PlacementAnswer,
    val qualification: PlacementQualification,
) {
    val key = qualification.key
    val checkedOnly = qualification.checkedOnly
    override fun toString() = "PlacementEvidenceUse"
}

/**
 * Explicit caller/source-policy assessments; this library does not authenticate their sources.
 * Changed claims, conflicts or checked-only attribution also change the dependent result key.
 */
class PlacementQualifications(qualifications: List<PlacementQualification>) {
    private val qualifications: Map<PlacementEvidenceKey, PlacementQualification>
    val sha256: String
    init {
        if (qualifications.size > 8192) placementReject(PlacementProblem.INPUT_LIMIT)
        val entries = linkedMapOf<PlacementEvidenceKey, PlacementQualification>()
        for (qualification in qualifications) {
            if (entries.put(qualification.key, qualification) != null)
                placementReject(PlacementProblem.DUPLICATE_IDENTITY)
        }
        this.qualifications = immutablePlacementMap(entries)
        sha256 = placementKey(entries.values.sortedBy { it.key.key }.flatMap {
            listOf(it.key.key, it.eligibility.name, it.evidenceInputName, it.evidenceSha256,
                it.conflictRankResolved?.toString() ?: "unresolved", it.checkedOnly.toString())
                .map { part -> part.toByteArray(Charsets.UTF_8) }
        })
    }
    fun eligibility(basis: PlacementBasis, answer: PlacementAnswer, fact: PlacementFact,
                    domain: PlacementDomain): PlacementEligibility {
        if (basis.sha256 != answer.basis.sha256) return PlacementEligibility.UNKNOWN
        if (fact == PlacementFact.PHYSICAL_IDENTITY && !domain.completePhysical) return PlacementEligibility.UNKNOWN
        val record = qualifications[answer.qualificationKey(fact, domain)] ?: return PlacementEligibility.UNKNOWN
        if (record.conflictRankResolved != true) return PlacementEligibility.UNKNOWN
        return record.eligibility
    }
    fun use(basis: PlacementBasis, answer: PlacementAnswer, fact: PlacementFact,
            domain: PlacementDomain): PlacementEvidenceUse? {
        if (!answer.provided || eligibility(basis, answer, fact, domain) != PlacementEligibility.QUALIFIED) return null
        return PlacementEvidenceUse(answer, qualifications.getValue(answer.qualificationKey(fact, domain)))
    }
    internal fun qualified(basis: PlacementBasis, answer: PlacementAnswer, fact: PlacementFact,
                           domain: PlacementDomain): Boolean = use(basis, answer, fact, domain) != null
    override fun toString() = "PlacementQualifications"
}

/** One envelope read per named original input. No second CD1 parser or 0.1-to-0.2 conversion. */
class PlacementAnswers(val basis: PlacementBasis, inputName: String) {
    val inputName: String
    private val index: Cd1AnswerIndex
    val originalSha256: String
    init {
        val input = basis.input(inputName)
        if (input == null || input.kind != PlacementInputKind.ANSWERS)
            placementReject(PlacementProblem.INVALID_ANSWER_PROFILE)
        this.inputName = inputName
        index = try { Cd1AnswerIndex.read(input.originalBytes()) } catch (_: Cd1Rejected) {
            placementReject(PlacementProblem.INVALID_ANSWER_PROFILE)
        }
        originalSha256 = index.originalSHA256
        if (originalSha256 != input.sha256) placementReject(PlacementProblem.DECLARED_HASH_MISMATCH)
    }
    fun originalBytes(): ByteArray = index.originalBytes
    fun read(pointer: String, field: PlacementField): PlacementAnswer {
        val observation = try { index.read(pointer, field.knownSchema) } catch (_: Cd1Rejected) {
            placementReject(PlacementProblem.INVALID_FIELD)
        }
        val result = PlacementAnswer(basis, inputName, originalSha256, pointer, field, observation)
        // Exact full-string matching excludes the final-newline alias of a schema dollar anchor.
        if (field == PlacementField.BYTE_COUNT && result.provided) result.bytes()
        if (field == PlacementField.POSITIVE_INTEGER && result.provided && result.value?.isIntegralNumber != true)
            placementReject(PlacementProblem.INVALID_FIELD)
        return result
    }
    override fun toString() = "PlacementAnswers"
}

internal fun placementPointer(pointer: String): List<String> {
    if (pointer.length > 16_384 || !pointer.startsWith('/')) placementReject(PlacementProblem.INVALID_FIELD)
    val parts = pointer.substring(1).split('/')
    if (parts.size > 256) placementReject(PlacementProblem.INPUT_LIMIT)
    return parts.map { encoded ->
        val result = StringBuilder()
        var at = 0
        while (at < encoded.length) {
            val char = encoded[at++]
            if (char != '~') result.append(char)
            else {
                if (at == encoded.length || encoded[at] !in "01") placementReject(PlacementProblem.INVALID_FIELD)
                result.append(if (encoded[at++] == '0') '~' else '/')
            }
        }
        result.toString()
    }
}

internal fun requirePlacementField(answer: PlacementAnswer, field: PlacementField) {
    if (answer.field != field) placementReject(PlacementProblem.INVALID_FIELD)
}
