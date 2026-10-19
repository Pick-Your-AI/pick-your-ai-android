package ai.pickyour.core.evidence.placement

import ai.pickyour.core.evidence.answers.Cd1State
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigInteger

/** Synthetic original inputs and explicit caller assessments; none is a device or source attestation. */
internal class PlacementTestDraft {
    val mapper = ObjectMapper()
    private val exampleBuild = example("build")
    private val exampleCatalog = example("catalog")
    val build = exampleBuild.deepCopy().apply {
        put("name", "SYNTHETIC placement receiving regression")
        putArray("selections")
        putArray("links")
    }
    val catalog = exampleCatalog.deepCopy()
    val entries = linkedMapOf<String, ObjectNode>()
    val meanings = linkedMapOf<String, PlacementRole>()
    val requirements = linkedMapOf<String, String>()
    val rows = linkedSetOf<String>()
    val conditionKinds = linkedMapOf<Pair<String, String>, PlacementConditionKind>()
    val artifactIds = linkedSetOf<String>()
    val extraInputs = mutableListOf<PlacementInput>()
    var selectedCatalog: String? = "catalog"
    var operation = "inference"

    fun node(value: Any?): JsonNode = mapper.valueToTree(value)
    fun put(path: String, value: Any?, assumption: Boolean = true, source: String? = "preset:placement-test") {
        entries[path] = mapper.createObjectNode().put("state", "provided").put("assumption", assumption).apply {
            set<JsonNode>("value", if (value is JsonNode) value.deepCopy() else node(value))
            if (source != null) put("source", source)
        }
    }
    fun state(path: String, state: Cd1State) {
        require(state != Cd1State.PROVIDED)
        entries[path] = mapper.createObjectNode().put("state", state.encoded)
    }
    fun selection(id: String, kind: String, enabled: Boolean = true, catalogKind: String = kind): ObjectNode {
        val selections = build.withArray("selections")
        selections.firstOrNull { it["id"].textValue() == id }?.let { return it as ObjectNode }
        val selected = (exampleBuild["selections"].single { it["kind"].textValue() == kind } as ObjectNode).deepCopy()
        selected.put("id", id).put("enabled", enabled)
        val item = catalog["items"].single { it["kind"].textValue() == catalogKind }
        selected.put("catalog_item_id", item["id"].textValue()).put("catalog_item_version", item["version"].textValue())
        selections.add(selected)
        return selected
    }
    fun selected(id: String) = build["selections"].single { it["id"].textValue() == id } as ObjectNode
    fun item(kind: String) = catalog["items"].single { it["kind"].textValue() == kind } as ObjectNode
    fun link(id: String, from: String, to: String, role: PlacementRole) {
        build.withArray("links").add(mapper.createObjectNode().put("id", id)
            .put("from_selection_id", from).put("to_selection_id", to).put("relation", role.relation))
        meanings[id] = role
        put("/links/" + id + "/role", role.encoded)
        put("/links/" + id + "/operation", operation)
    }
    fun serving(
        id: String = "sel_model",
        kind: String = "model",
        operator: String = "self",
        capacity: String = "dedicated_instances",
        exposed: Boolean = true,
        engine: String? = "sel_engine",
        host: String? = "sel_host",
        devices: List<String> = listOf("sel_gpu"),
    ) {
        selection(id, kind)
        if (engine != null) { selection(engine, "inference_engine"); link("link." + id + ".engine", id, engine, PlacementRole.ENGINE) }
        if (host != null) { selection(host, "hosting_platform"); link("link." + id + ".host", id, host, PlacementRole.HOST) }
        for (device in devices) {
            selection(device, if (device.contains("cpu")) "compute_profile" else "accelerator")
            link("link." + id + "." + device, id, device, PlacementRole.DEVICE)
        }
        requirements[id] = id
        put("/requires/" + id + "/engine", engine != null)
        put("/requires/" + id + "/host", host != null)
        put("/requires/" + id + "/devices", devices.isNotEmpty())
        rows += id
        put("/deployment/" + id + "/operator", operator)
        put("/deployment/" + id + "/capacity", capacity)
        put("/deployment/" + id + "/exposed", exposed)
        put("/components/" + id + "/applies", operator == "self" || exposed)
        put("/components/" + id + "/placement", "always")
    }
    fun condition(selectionId: String, id: String, kind: PlacementConditionKind, satisfied: Boolean,
                  applicable: Boolean = true) {
        conditionKinds[selectionId to id] = kind
        put("/conditions/" + selectionId + "/" + id + "/applies", applicable)
        put("/conditions/" + selectionId + "/" + id + "/satisfied", satisfied)
    }
    fun artifact(id: String, kind: String, baseVersion: String = "2026.10-demo") {
        artifactIds += id
        put("/artifacts/" + id + "/kind", kind)
        put("/artifacts/" + id + "/version", baseVersion)
        put("/artifacts/" + id + "/required", kind == "adapter")
    }
    fun runtime(id: String = "sel_engine", kind: String = "model_engine", instance: String = "instance.1",
                replica: String? = "replica.1", rank: String? = "rank.0", admission: String? = "admission.1",
                stage: String? = "stage.1", build: String = "engine-build.1", source: String = "engines") =
        linkedMapOf("kind" to kind, "id" to id, "build" to build, "source_input" to source,
            "instance" to instance, "replica" to replica, "rank" to rank, "admission" to admission, "stage" to stage)
    fun trace(path: String, quantity: String = "0", unit: String = "bytes", rounding: String = "exact") {
        put(path, linkedMapOf("source_quantity" to quantity, "source_unit" to unit,
            "method" to "synthetic-normalized-source", "rounding" to rounding))
    }
    fun range(prefix: String, lower: String? = null, upper: String? = null) {
        lower?.let { put(prefix + "/lower", it) }
        upper?.let { put(prefix + "/upper", it) }
        trace(prefix + "/trace", upper ?: lower ?: "unresolved")
    }
    fun budget(prefix: String, capacity: String, reserve: String = "0") {
        range(prefix + "/capacity", lower = capacity)
        range(prefix + "/reserve", upper = reserve)
        put(prefix + "/reservation", "synthetic-explicit-reserve")
        put(prefix + "/basis", "before_reserve")
    }
    fun inventory(prefix: String, ids: Collection<String>, complete: Boolean = true) {
        put(prefix + "/ids", ids.toList())
        put(prefix + "/complete", complete)
    }

    fun freeze(): PlacementTestContext {
        for (selection in build["selections"]) {
            val id = selection["id"].textValue()
            val prefix = "/conditions/" + id
            if (prefix + "/ids" !in entries) put(prefix + "/ids", conditionKinds.keys.filter { it.first == id }.map { it.second })
            if (prefix + "/complete" !in entries) put(prefix + "/complete", true)
        }
        if ("/graph/complete" !in entries) put("/graph/complete", true)
        if ("/serving/complete" !in entries) put("/serving/complete", true)
        val answers = mapper.createObjectNode().apply {
            val index = putObject("x_t0_answer_states").put("proposal_version", "cd1-0.2")
            val supplied = index.putObject("answers")
            entries.forEach { (path, record) -> supplied.set<JsonNode>(path, record.deepCopy()) }
        }
        val inputs = mutableListOf(
            PlacementInput("answers", PlacementInputKind.ANSWERS, mapper.writeValueAsBytes(answers)),
            PlacementInput("catalog", PlacementInputKind.CATALOG, mapper.writeValueAsBytes(catalog)),
            PlacementInput("normalization", PlacementInputKind.NORMALIZATION, """{"synthetic_policy":"normalization-v1"}""".toByteArray()),
            PlacementInput("source-policy", PlacementInputKind.SOURCE_POLICY, """{"synthetic_policy":"explicit-test-assessments"}""".toByteArray()),
            PlacementInput("qualification", PlacementInputKind.QUALIFICATION, """{"synthetic_evidence":"no-device-or-provider-run"}""".toByteArray()),
            PlacementInput("engines", PlacementInputKind.ENGINE_BUILDS, """{"synthetic_build":"engine-build.1"}""".toByteArray()),
            PlacementInput("hardware", PlacementInputKind.HARDWARE, """{"synthetic_hardware":"test-only"}""".toByteArray()),
            PlacementInput("deployment", PlacementInputKind.DEPLOYMENT, """{"synthetic_deployment":"test-only"}""".toByteArray()),
            PlacementInput("workload", PlacementInputKind.WORKLOAD_STAGES, """{"synthetic_stages":"test-only"}""".toByteArray()),
        )
        inputs += extraInputs
        val basis = PlacementBasis.read(mapper.writeValueAsBytes(build), operation, "normalization", "source-policy",
            inputs, selectedCatalog)
        return PlacementTestContext(this, basis, inputs)
    }

    private fun example(name: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/" + name + ".json").use {
        mapper.readTree(requireNotNull(it)) as ObjectNode
    }
}

internal class PlacementTestContext(
    val draft: PlacementTestDraft,
    val basis: PlacementBasis,
    val inputs: List<PlacementInput>,
) {
    val answers = PlacementAnswers(basis, "answers")
    val records = linkedMapOf<PlacementEvidenceKey, PlacementQualification>()
    fun a(path: String, field: PlacementField) = answers.read(path, field)
    fun qualify(answer: PlacementAnswer, fact: PlacementFact, domain: PlacementDomain,
                eligibility: PlacementEligibility = PlacementEligibility.QUALIFIED,
                resolved: Boolean? = true, checked: Boolean = false) {
        val key = answer.qualificationKey(fact, domain)
        val evidence = checkNotNull(basis.input("qualification"))
        records[key] = PlacementQualification(key, eligibility, evidence.name, evidence.sha256, resolved, checked)
    }
    fun qs(omit: (PlacementQualification) -> Boolean = { false }) = PlacementQualifications(records.values.filterNot(omit))
    fun coverage(path: String, kind: PlacementCoverageKind, owner: String = basis.buildId,
                 scenario: String? = null): PlacementCoverage {
        val value = PlacementCoverage(kind, owner, scenario, a(path, PlacementField.BOOLEAN))
        qualify(value.claim, PlacementFact.COVERAGE, value.domain)
        return value
    }
    fun graph(): PlacementGraph {
        val meanings = draft.meanings.keys.map { id ->
            val role = a("/links/" + id + "/role", PlacementField.ROLE)
            val operation = a("/links/" + id + "/operation", PlacementField.TEXT)
            qualify(role, PlacementFact.ROLE, PlacementDomain.link(id))
            qualify(operation, PlacementFact.OPERATION, PlacementDomain.link(id))
            PlacementLinkMeaning(id, role, operation)
        }
        return PlacementGraph(basis, meanings, coverage("/graph/complete", PlacementCoverageKind.ROLE_BINDINGS,
            scenario = basis.operation))
    }
    fun requirements(): List<PlacementServingRequirements> = draft.requirements.map { (id, effective) ->
        val engine = a("/requires/" + id + "/engine", PlacementField.BOOLEAN)
        val host = a("/requires/" + id + "/host", PlacementField.BOOLEAN)
        val devices = a("/requires/" + id + "/devices", PlacementField.BOOLEAN)
        qualify(engine, PlacementFact.APPLICABILITY, PlacementDomain.role(effective, PlacementRole.ENGINE.encoded))
        qualify(host, PlacementFact.APPLICABILITY, PlacementDomain.role(effective, PlacementRole.HOST.encoded))
        qualify(devices, PlacementFact.APPLICABILITY, PlacementDomain.role(effective, PlacementRole.DEVICE.encoded))
        PlacementServingRequirements(id, engine, host, devices)
    }
    fun artifacts(): List<PlacementArtifactFacts> = draft.artifactIds.map { id ->
        val kind = a("/artifacts/" + id + "/kind", PlacementField.ARTIFACT_KIND)
        val version = a("/artifacts/" + id + "/version", PlacementField.TEXT)
        val required = a("/artifacts/" + id + "/required", PlacementField.BOOLEAN)
        qualify(kind, PlacementFact.COMPATIBILITY, PlacementDomain.component(id))
        qualify(version, PlacementFact.COMPATIBILITY, PlacementDomain.component(id))
        qualify(required, PlacementFact.APPLICABILITY, PlacementDomain.role(id, PlacementRole.BASE.encoded))
        PlacementArtifactFacts(id, kind, version, required)
    }
    fun conditions(): List<PlacementSupportCondition> = draft.conditionKinds.map { (key, kind) ->
        val (selection, id) = key
        val applies = a("/conditions/" + selection + "/" + id + "/applies", PlacementField.BOOLEAN)
        val satisfied = a("/conditions/" + selection + "/" + id + "/satisfied", PlacementField.BOOLEAN)
        val condition = PlacementSupportCondition(selection, id, kind, applies, satisfied)
        qualify(applies, PlacementFact.APPLICABILITY, condition.domain)
        qualify(satisfied, PlacementFact.COMPATIBILITY, condition.domain)
        condition
    }
    fun conditionInventories(): List<PlacementConditionInventory> = basis.selections.map { selected ->
        val prefix = "/conditions/" + selected.id
        val complete = coverage(prefix + "/complete", PlacementCoverageKind.SUPPORT_CONDITIONS,
            selected.id, basis.operation)
        val ids = a(prefix + "/ids", PlacementField.TEXT_SET)
        qualify(ids, PlacementFact.INVENTORY, complete.domain)
        PlacementConditionInventory(ids, complete)
    }
    fun deployment(
        conditions: List<PlacementSupportCondition> = conditions(),
        inventories: List<PlacementConditionInventory> = conditionInventories(),
    ): PlacementDeployment {
        val rows = draft.rows.map { id ->
            val prefix = "/deployment/" + id
            val operator = a(prefix + "/operator", PlacementField.OPERATOR)
            val capacity = a(prefix + "/capacity", PlacementField.CAPACITY)
            val exposure = a(prefix + "/exposed", PlacementField.BOOLEAN)
            val domain = PlacementDomain.component(id)
            qualify(operator, PlacementFact.OPERATOR, domain)
            qualify(capacity, PlacementFact.CAPACITY, domain)
            qualify(exposure, PlacementFact.LOCAL_VISIBILITY, domain)
            PlacementDeploymentRow(id, operator, capacity, exposure)
        }
        return PlacementDeployment(basis, graph(), rows, artifacts(), conditions, inventories,
            coverage("/serving/complete", PlacementCoverageKind.SERVING_COMPONENTS, scenario = basis.operation),
            requirements())
    }
    fun range(prefix: String, domain: PlacementDomain): PlacementByteRange {
        val lower = if (prefix + "/lower" in draft.entries) a(prefix + "/lower", PlacementField.BYTE_COUNT) else null
        val upper = if (prefix + "/upper" in draft.entries) a(prefix + "/upper", PlacementField.BYTE_COUNT) else null
        val trace = a(prefix + "/trace", PlacementField.QUANTITY_TRACE)
        listOfNotNull(lower, upper).forEach { qualify(it, PlacementFact.BYTE_BOUND, domain) }
        qualify(trace, PlacementFact.NORMALIZATION, domain)
        return PlacementByteRange(lower, upper, trace)
    }
    fun budget(prefix: String, domain: PlacementDomain): PlacementBudget {
        val reservation = a(prefix + "/reservation", PlacementField.TEXT)
        val basis = a(prefix + "/basis", PlacementField.RESERVE_BASIS)
        qualify(reservation, PlacementFact.NORMALIZATION, domain)
        qualify(basis, PlacementFact.NORMALIZATION, domain)
        return PlacementBudget(range(prefix + "/capacity", domain), range(prefix + "/reserve", domain), basis, reservation)
    }
    fun <T : PlacementRecord> inventory(prefix: String, values: List<T>, kind: PlacementCoverageKind,
                                        scenario: String? = null): PlacementRecords<T> {
        val complete = coverage(prefix + "/complete", kind, scenario = scenario)
        val ids = a(prefix + "/ids", PlacementField.TEXT_SET)
        qualify(ids, PlacementFact.INVENTORY, complete.domain)
        return PlacementRecords(ids, values, complete)
    }
}

internal fun PlacementResult.poolChecks() = checks.filter {
    it.rule == PlacementRule.MEMORY_POOL && it.domain.tag == "pool_scenario"
}
internal fun big(value: Long): BigInteger = BigInteger.valueOf(value)
