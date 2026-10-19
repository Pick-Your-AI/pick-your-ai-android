package ai.pickyour.core.evaluation

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Optional frozen-lock regression; original public synthetic fixtures remain unchanged. */
class CompatibleOptionsAbsentLockCausalReplayTest {
    private val mapper = ObjectMapper()
    private val validator = FrozenSchemaValidator()
    private val slot = CompatibleOptionSlot("model", "primary", "sel_model", true)
    private fun fixture(path: String): ObjectNode = requireNotNull(javaClass.getResourceAsStream(path)).use {
        mapper.readTree(it) as ObjectNode
    }
    private data class Input(val build: ObjectNode, val catalog: ObjectNode, val rules: ObjectNode, val candidate: ObjectNode) {
        fun bytes() = listOf(build, catalog, rules, candidate).map { it.toString().toByteArray(Charsets.UTF_8) }
    }
    private fun input(): Input {
        val build = fixture("/contracts/v1/examples/build.json")
        val catalog = fixture("/contracts/v1/examples/catalog.json")
        val rules = fixture("/contracts/v1/baseline/core-ruleset.json")
        val retainedRules = rules["rules"].filter { it["id"].asText() in setOf(
            "rule.required.foundation", "rule.model.provider.match", "rule.api.model.access") }
        rules.putArray("rules").addAll(retainedRules)
        (build["locks"] as ObjectNode).put("ruleset_id", rules["id"].asText()).put("ruleset_version", rules["version"].asText())
        val selections = build["selections"].filter { it["kind"].asText() in setOf(
            "workload", "deployment_mode", "model_provider", "model", "context_policy") }
        build.putArray("selections").addAll(selections)
        val ids = selections.map { it["id"].asText() }.toSet()
        val links = build["links"].filter { it["from_selection_id"].asText() in ids && it["to_selection_id"].asText() in ids }
        build.putArray("links").addAll(links)
        val mode = catalog["items"].single { it["id"].asText() == "cat.deployment_mode.fixture" }
        (mode["spec"] as ObjectNode).put("mode", "api")
        val model = catalog["items"].single { it["id"].asText() == "cat.model.fixture" }
        (model["spec"] as ObjectNode).putArray("access_modes").add("api")
        val candidate = (build["selections"].single { it["id"].asText() == slot.selectionId } as ObjectNode).deepCopy()
        return Input(build, catalog, rules, candidate)
    }
    private fun prepare(input: Input, validBuild: Boolean): CompatibleOptionsPreparation {
        val bytes = input.bytes()
        val before = bytes.map { it.copyOf() }
        val documents = listOf(FrozenDocument.BUILD, FrozenDocument.CATALOG, FrozenDocument.RULESET, FrozenDocument.SELECTION)
        for (index in documents.indices) {
            val validation = validator.validate(documents[index], bytes[index].inputStream())
            if (index == 0 && !validBuild) assertNotEquals(FrozenValidation.Valid, validation)
            else assertEquals(FrozenValidation.Valid, validation, "Input " + documents[index])
        }
        return try {
            CompatibleOptionsRequest.prepare(bytes[0], bytes[1], bytes[2], slot, listOf(bytes[3]))
        } finally {
            bytes.indices.forEach { assertArrayEquals(before[it], bytes[it], "Caller input " + documents[it]) }
            input.bytes().indices.forEach { assertArrayEquals(before[it], input.bytes()[it], "Original synthetic document " + documents[it]) }
        }
    }
    private fun completed(input: Input): CompatibleOptionsRequest {
        val preparation = prepare(input, true)
        assertTrue(preparation is CompatibleOptionsPreparation.Ready, preparation.toString())
        val request = (preparation as CompatibleOptionsPreparation.Ready).request
        val outcome = CompatibleOptionsEvaluator().evaluate(request)
        assertTrue(outcome is CompatibleOptionsOutcome.Completed, outcome.toString())
        assertEquals(CompatibleOptionVerdict.COMPATIBLE, (outcome as CompatibleOptionsOutcome.Completed).options.single().verdict)
        val gate = CompatibleOptionsCoordinator()
        gate.bind(request)
        val attempt = requireNotNull(gate.start(request))
        assertTrue(gate.complete(attempt, outcome))
        assertTrue(gate.isCurrent(outcome))
        gate.clear()
        assertFalse(gate.isCurrent(outcome))
        return request
    }

    @Test fun presentOptionalLockRetainsItsExactIdentityThroughEvaluationAndDelivery() {
        val source = input()
        val expected = source.build["locks"]["pricebook_id"].textValue()
        assertNotNull(expected)
        assertEquals(expected, completed(source).binding.pricebookId)
    }

    @Test fun omittedOptionalLockRemainsAbsentThroughAdmittedEvaluationAndDelivery() {
        val present = input()
        val source = Input(present.build.deepCopy(), present.catalog.deepCopy(), present.rules.deepCopy(), present.candidate.deepCopy())
        val removed = (source.build["locks"] as ObjectNode).remove("pricebook_id")
        assertTrue(removed.isTextual)
        val restored = source.build.deepCopy()
        (restored["locks"] as ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>("pricebook_id", removed)
        assertEquals(present.build, restored)
        assertEquals(present.catalog, source.catalog)
        assertEquals(present.rules, source.rules)
        assertEquals(present.candidate, source.candidate)
        assertNull(completed(source).binding.pricebookId)
        assertFalse(source.build["locks"].has("pricebook_id"))
    }

    @Test fun explicitNullLockRemainsRejectedByTheUnchangedFrozenBoundary() {
        val source = input()
        (source.build["locks"] as ObjectNode).putNull("pricebook_id")
        assertEquals(CompatibleOptionsPreparation.Unavailable(CompatibleOptionsUnavailable.INVALID_INPUT), prepare(source, false))
    }
}
