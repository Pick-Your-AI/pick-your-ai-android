package ai.pickyour.core.evidence.rules

import ai.pickyour.core.evidence.membership.MembershipOutcome
import ai.pickyour.core.evidence.membership.MembershipRequirement
import ai.pickyour.core.evidence.membership.SupportEvidence
import ai.pickyour.core.evidence.membership.SupportMembership
import ai.pickyour.core.evidence.membership.membershipTextOrder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Exact operand reader for two frozen assertions, followed by caller-qualified
 * support mechanics. It does not invoke the diagnostic evaluator or a provider.
 * Full shape/identity preflight precedes every applicability or evidence shortcut.
 */
class FrozenSupportRuleAdapter(buildRaw: ByteArray, catalogRaw: ByteArray, rulesetRaw: ByteArray) {
    private val buildBytes = buildRaw.copyOf()
    private val catalogBytes = catalogRaw.copyOf()
    private val rulesetBytes = rulesetRaw.copyOf()
    private val build: ObjectNode
    private val catalog: ObjectNode
    private val ruleset: ObjectNode
    private val rules: Map<String, JsonNode>
    private val items: Map<String, IndexedValue<JsonNode>>

    init {
        require(ruleDigest(rulesetBytes) == RULESET_SHA256) { "Frozen support ruleset pin mismatch" }
        build = FrozenRuleInput.document(buildBytes, "build")
        catalog = FrozenRuleInput.document(catalogBytes, "catalog-snapshot")
        val identities = HashSet<String>()
        val roles = HashSet<Pair<String, String>>()
        for (selection in build["selections"]) {
            require(identities.add(selection["id"].textValue())) { "Duplicate frozen selection identity" }
            require(roles.add(selection["kind"].textValue() to selection["role"].textValue())) {
                "Ambiguous frozen selection role"
            }
        }
        val itemEntries = LinkedHashMap<String, IndexedValue<JsonNode>>()
        catalog["items"].withIndex().forEach { item ->
            require(itemEntries.put(item.value["id"].textValue(), item) == null) {
                "Duplicate frozen catalog item identity"
            }
        }
        items = itemEntries.toMap()
        ruleset = FrozenRuleInput.decode(rulesetBytes)
        rules = ruleset["rules"].filter { it["id"].textValue() in RULE_IDS }
            .associateBy { it["id"].textValue() }
        check(rules.keys == RULE_IDS) { "Pinned support declarations unavailable" }
    }

    /** Each access returns detached copies, including the exact original numeric tokens. */
    val originalBytes: List<ByteArray>
        get() = listOf(buildBytes.copyOf(), catalogBytes.copyOf(), rulesetBytes.copyOf())

    fun supportScope(ruleId: String): RuleSupportScope? {
        val rule = rule(ruleId)
        val side = if (rule["assert"]["op"].textValue() == "contains") "left" else "right"
        return scope(read(rule["assert"][side]))
    }

    fun evaluate(
        ruleId: String,
        evidence: RuleEvidence,
        eligibleInputs: Map<String, Boolean?>? = null,
        cohortCheckedOnly: Boolean? = null,
    ): FrozenSupportResult {
        val rule = rule(ruleId)
        // Check Java/erased callers too; never coerce markers or ignore extra keys.
        val supplied: Map<*, *> = eligibleInputs ?: emptyMap<String, Boolean?>()
        require(supplied.keys.all { it is String && it in ELIGIBILITY } &&
            supplied.values.all { it == null || it is Boolean }) { "Invalid rule input qualification" }
        val eligible = ELIGIBILITY.associateWith { supplied[it] as Boolean? }
        val applicabilityArgs = rule["applies_when"]["args"]
        val operands = nodes.objectNode().also {
            it.set<JsonNode>("deployment_mode", read(applicabilityArgs[0]["left"]))
            it.set<JsonNode>("engine_presence", read(applicabilityArgs[1]["value"]))
            it.set<JsonNode>("left", read(rule["assert"]["left"]))
            it.set<JsonNode>("right", read(rule["assert"]["right"]))
        }
        val scalar = rule["assert"]["op"].textValue() == "contains"
        val requested = operands[if (scalar) "right" else "left"].get("value")?.takeUnless { it.isNull }
        val supported = operands[if (scalar) "left" else "right"] as ObjectNode
        val supportScope = scope(supported)
        val mode = operands["deployment_mode"]
        val engine = operands["engine_presence"]
        val modeMatches: Boolean? = if (mode["state"].textValue() != "provided") null
            else mode["value"] == applicabilityArgs[0]["right"]["literal"]
        val enginePresent = engine["state"].textValue() == "provided" && !engine["value"].isNull
        // A known-false operand dominates an unresolved mode, as in boolean_and.
        val applicability = when {
            modeMatches == false || !enginePresent -> false
            modeMatches == null -> null
            else -> true
        }
        val complete = when {
            scalar && requested != null && eligible["requested"] == true -> true
            !scalar && eligible["requested_complete"] == true -> evidence.requestedComplete
            else -> null
        }
        val completenessBasis = when {
            scalar && complete == true -> "admitted_selected_scalar"
            !scalar && eligible["requested_complete"] == true && evidence.requestedComplete != null ->
                "caller_qualified_catalog_inventory"
            else -> "unresolved"
        }
        fun uncertain(reason: String, outcome: MembershipOutcome = MembershipOutcome.UNKNOWN) =
            FrozenSupportDecision(outcome, reason, evidence.cohortId)

        val decision = when {
            build["locks"]["catalog_snapshot_id"] != catalog["id"] -> uncertain("catalog_snapshot_mismatch")
            eligible["applies_when"] != true -> uncertain("applicability_evidence_unqualified")
            applicability == false -> uncertain("not_applicable", MembershipOutcome.SKIPPED)
            applicability == null -> uncertain("applicability_unknown")
            supportScope == null -> uncertain("support_operand_unresolved")
            evidence.scope != supportScope -> uncertain("support_scope_mismatch")
            eligible["requested"] != true -> uncertain("requested_evidence_unqualified")
            requested == null -> uncertain("requirement_unknown")
            eligible["cohort"] != true -> uncertain("cohort_evidence_unqualified")
            evidence.unsupported == null -> uncertain("unsupported_claims_unresolved")
            else -> {
                val requestedValues = if (scalar) setOf(requested.textValue())
                    else requested.mapTo(LinkedHashSet()) { it.textValue() }
                val reduced = SupportMembership.evaluate(
                    requestedValues,
                    SupportEvidence(
                        evidence.cohortId, true, supported["value"].mapTo(LinkedHashSet()) { it.textValue() },
                        evidence.exhaustive, evidence.unsupported, cohortCheckedOnly,
                    ),
                    MembershipRequirement.ANY,
                    requestedComplete = complete,
                )
                FrozenSupportDecision(reduced.outcome, reduced.reason.encoded, reduced.sourceId,
                    reduced.findings, reduced.disputed, reduced.evidenceLabel)
            }
        }
        val observation = nodes.objectNode().also { result ->
            result.put("profile", PROFILE)
            result.set<JsonNode>("ruleset", nodes.objectNode().also {
                it.set<JsonNode>("id", ruleset["id"].deepCopy())
                it.set<JsonNode>("version", ruleset["version"].deepCopy())
                it.put("sha256", RULESET_SHA256)
                it.put("reference_sha256", REFERENCE_SHA256)
            })
            result.set<JsonNode>("rule", rule.deepCopy())
            result.put("build_sha256", ruleDigest(buildBytes))
            result.put("catalog_sha256", ruleDigest(catalogBytes))
            result.set<JsonNode>("operands", operands)
            result.set<JsonNode>("support_scope", scopeNode(supportScope))
            result.set<JsonNode>("applicability", booleanNode(applicability))
            result.set<JsonNode>("evidence", nodes.objectNode().also {
                it.set<JsonNode>("cohort", nodes.objectNode().put("id", evidence.cohortId)
                    .put("version", evidence.cohortVersion))
                it.set<JsonNode>("scope", scopeNode(evidence.scope))
                it.set<JsonNode>("unsupported", evidence.unsupported?.let { values ->
                    nodes.arrayNode().also { array -> values.sortedWith(membershipTextOrder).forEach(array::add) }
                } ?: nodes.nullNode())
                it.set<JsonNode>("exhaustive", booleanNode(evidence.exhaustive))
                it.set<JsonNode>("requested_complete", booleanNode(evidence.requestedComplete))
            })
            result.set<JsonNode>("evaluation", nodes.objectNode().also {
                it.set<JsonNode>("eligible_inputs", nodes.objectNode().also { facts ->
                    eligible.forEach { (key, fact) -> facts.set<JsonNode>(key, booleanNode(fact)) }
                })
                it.set<JsonNode>("cohort_checked_only", booleanNode(cohortCheckedOnly))
            })
            result.set<JsonNode>("requested_completeness", nodes.objectNode()
                .set<ObjectNode>("effective", booleanNode(complete)).put("basis", completenessBasis))
            result.set<JsonNode>("decision", decisionNode(decision))
        }
        return FrozenSupportResult(decision, supportScope, observation)
    }

    private fun rule(ruleId: String): JsonNode =
        requireNotNull(rules[ruleId]) { "Unsupported frozen support rule" }

    private fun read(operand: JsonNode): ObjectNode {
        val ref = operand["ref"]
        val result = nodes.objectNode().set<ObjectNode>("ref", ref.deepCopy())
            .put("state", "unknown").put("reason", "selection_unresolved")
        val matches = build["selections"].withIndex().filter { (_, value) ->
            value["enabled"].booleanValue() &&
                listOf("kind", "role", "selection_id").all { key ->
                    ref[key] == null || ref[key].textValue().isEmpty() ||
                        value[if (key == "selection_id") "id" else key] == ref[key]
                }
        }
        if (matches.size != 1) return result
        val selection = matches.single()
        result.set<JsonNode>("selection", nodes.objectNode().also { identity ->
            listOf("id", "kind", "role", "enabled", "catalog_item_id", "catalog_item_version").forEach {
                identity.set<JsonNode>(it, selection.value[it].deepCopy())
            }
        })
        val document: JsonNode
        val prefix: String
        if (ref["scope"].textValue() == "selection") {
            document = selection.value
            prefix = "/selections/" + selection.index
        } else {
            val entry = items[selection.value["catalog_item_id"].textValue()]
                ?: return result.put("reason", "catalog_item_missing")
            document = entry.value
            result.set<JsonNode>("catalog_item", nodes.objectNode().also { identity ->
                listOf("id", "version", "kind").forEach { identity.set<JsonNode>(it, document[it].deepCopy()) }
            })
            if (document["version"] != selection.value["catalog_item_version"])
                return result.put("reason", "catalog_version_mismatch")
            if (document["kind"] != selection.value["kind"])
                return result.put("reason", "catalog_kind_mismatch")
            prefix = "/items/" + entry.index
        }
        val pointer = ref["pointer"].textValue()
        val value = document.at(pointer)
        if (value.isMissingNode) return result.put("reason", "property_missing")
        return result.put("state", "provided").put("reason", "resolved")
            .set<ObjectNode>("value", value.deepCopy()).put("source_path", prefix + pointer)
    }

    private fun scope(supported: ObjectNode): RuleSupportScope? {
        if (supported["state"].textValue() != "provided") return null
        val item = supported["catalog_item"]
        return RuleSupportScope(
            supported["selection"]["id"].textValue(), ruleDigest(catalogBytes),
            item["id"].textValue(), item["version"].textValue(), item["kind"].textValue(),
            supported["ref"]["pointer"].textValue(), ruleDigest(buildBytes),
        )
    }

    private fun scopeNode(value: RuleSupportScope?): JsonNode = if (value == null) nodes.nullNode()
        else nodes.objectNode().put("selection_id", value.selectionId).put("catalog_sha256", value.catalogSha256)
            .set<ObjectNode>("item", nodes.objectNode().put("id", value.itemId)
                .put("version", value.itemVersion).put("kind", value.itemKind))
            .put("property", value.property).put("conditions_sha256", value.conditionsSha256)

    private fun decisionNode(value: FrozenSupportDecision): ObjectNode =
        nodes.objectNode().put("outcome", value.outcome.encoded).put("reason", value.reason)
            .put("source_id", value.sourceId)
            .set<ObjectNode>("findings", nodes.arrayNode().also { array ->
                value.findings.forEach { finding ->
                    array.add(nodes.objectNode().put("value", finding.value).put("outcome", finding.outcome.encoded)
                        .put("reason", finding.reason.encoded).put("disputed", finding.disputed))
                }
            }).put("disputed", value.disputed)
            .set<ObjectNode>("evidence_label", value.evidenceLabel?.let { nodes.textNode(it.encoded) } ?: nodes.nullNode())

    private fun booleanNode(value: Boolean?): JsonNode = value?.let(nodes::booleanNode) ?: nodes.nullNode()

    override fun toString(): String = "FrozenSupportRuleAdapter"

    companion object {
        const val PROFILE = "cd3-frozen-support-rules-0.1"
        const val RULESET_SHA256 = "e60e43c0abc3c5d927e10c578a39fdedfa76e00fce6242e2db3a46e4b1da907e"
        const val REFERENCE_SHA256 = "092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a"
        private val RULE_IDS = FrozenSupportRule.entries.mapTo(linkedSetOf()) { it.id }
        private val ELIGIBILITY = listOf("applies_when", "requested", "requested_complete", "cohort")
        private val nodes = JsonNodeFactory.instance
    }
}
