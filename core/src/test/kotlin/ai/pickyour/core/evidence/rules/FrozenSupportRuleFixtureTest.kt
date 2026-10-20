package ai.pickyour.core.evidence.rules

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

class FrozenSupportRuleFixtureTest {
    @TestFactory
    fun pinnedReferenceCases(): List<DynamicTest> {
        val fixture = FrozenRuleFixtures.corpus
        assertEquals("android-frozen-support-rules/0.1", fixture["profile"].textValue())
        assertEquals(FrozenSupportRuleAdapter.PROFILE, fixture["prototype_profile"].textValue())
        assertEquals("05ec3efec6b29316eef9bc036c42678e4d225ce7", fixture["research_commit"].textValue())
        assertEquals(30, fixture["sources"].size())
        assertEquals(1577, fixture["counts"]["cases"].intValue())
        assertEquals(55, fixture["counts"]["construct_rejections"].intValue())
        assertEquals(52, fixture["counts"]["evaluation_rejections"].intValue())
        assertEquals(109, fixture["documents"].size())
        val documents = fixture["documents"].fields().asSequence().associate { (key, entry) ->
            val bytes = Base64.getDecoder().decode(entry["base64"].textValue())
            assertEquals(entry["bytes"].intValue(), bytes.size)
            assertEquals(key, FrozenRuleFixtures.digest(bytes))
            assertEquals(key, entry["sha256"].textValue())
            key to bytes
        }
        // Bind every frozen schema/registry and actual example/ruleset resource
        // used by these JVM checks to the exact canonical producer inputs.
        fixture["sources"].fields().forEachRemaining { (path, pin) ->
            if (path.startsWith("contracts/v1/") && !path.endsWith(".py")) {
                val resource = FrozenRuleFixtures.resource("/$path")
                assertEquals(pin["bytes"].intValue(), resource.size, path)
                assertEquals(pin["sha256"].textValue(), FrozenRuleFixtures.digest(resource), path)
            }
        }
        val cases = fixture["cases"].toList()
        assertEquals(1577, cases.size)
        assertEquals(cases.size, cases.map { it["id"].textValue() }.toSet().size)
        return cases.map { case ->
            DynamicTest.dynamicTest(case["id"].textValue()) {
                val inputText = case["input_json"].textValue()
                assertEquals(case["input_sha256"].textValue(),
                    FrozenRuleFixtures.digest(inputText.toByteArray(Charsets.US_ASCII)))
                val input = FrozenRuleFixtures.mapper.readTree(inputText)
                val build = documents.getValue(input["build"].textValue())
                val catalog = documents.getValue(input["catalog"].textValue())
                val expected = case["expected"]
                if (expected["error"]?.textValue() == "construct") {
                    assertThrows<IllegalArgumentException> {
                        FrozenSupportRuleAdapter(build, catalog, FrozenRuleFixtures.ruleset)
                    }
                } else {
                    val adapter = FrozenSupportRuleAdapter(build, catalog, FrozenRuleFixtures.ruleset)
                    val original = adapter.originalBytes
                    assertArrayEquals(build, original[0])
                    assertArrayEquals(catalog, original[1])
                    assertArrayEquals(FrozenRuleFixtures.ruleset, original[2])
                    if (expected["error"]?.textValue() == "evaluate") {
                        assertThrows<IllegalArgumentException> { FrozenRuleFixtures.evaluate(adapter, input) }
                    } else {
                        val actual = FrozenRuleFixtures.evaluate(adapter, input)
                        assertEquals(expected, actual.details())
                        assertEquals(expected["decision"]["outcome"].textValue(), actual.decision.outcome.encoded)
                        assertEquals(expected["decision"]["reason"].textValue(), actual.decision.reason)
                        assertEquals(expected["decision"]["disputed"].booleanValue(), actual.decision.disputed)
                        assertEquals(expected["decision"]["evidence_label"].takeUnless { it.isNull }?.textValue(),
                            actual.decision.evidenceLabel?.encoded)
                    }
                }
            }
        }
    }
}

/** Test-only conversion from the canonical synthetic fixture, not a product wire API. */
internal object FrozenRuleFixtures {
    val mapper: ObjectMapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    val corpus: JsonNode by lazy {
        val bytes = resource("/evidence/rules/frozen-support-cases.json.gz")
        val raw = GZIPInputStream(bytes.inputStream()).use { it.readNBytes(32 * 1024 * 1024 + 1) }
        require(raw.size <= 32 * 1024 * 1024)
        mapper.readTree(raw)
    }
    val ruleset: ByteArray get() = resource("/contracts/v1/baseline/core-ruleset.json")
    val build: ByteArray get() = resource("/contracts/v1/examples/build.json")
    val catalog: ByteArray get() = resource("/contracts/v1/examples/catalog.json")
    val qualified: Map<String, Boolean?> =
        listOf("applies_when", "requested", "requested_complete", "cohort").associateWith { true }

    fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "Missing fixture resource" }.use { it.readBytes() }

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun fact(value: JsonNode): Boolean? {
        require(value.isNull || value.isBoolean) { "Fixture marker is not a nullable Boolean" }
        return value.takeUnless { it.isNull }?.booleanValue()
    }

    private fun text(value: JsonNode): String {
        require(value.isTextual) { "Fixture value is not text" }
        return value.textValue()
    }

    private fun scope(value: JsonNode): RuleSupportScope? {
        if (value.isNull) return null
        require(value.isObject && value.fieldNames().asSequence().toSet() ==
            setOf("selection_id", "catalog_sha256", "item", "property", "conditions_sha256"))
        val item = value["item"]
        require(item.isObject && item.fieldNames().asSequence().toSet() == setOf("id", "version", "kind"))
        return RuleSupportScope(text(value["selection_id"]), text(value["catalog_sha256"]),
            text(item["id"]), text(item["version"]), text(item["kind"]),
            text(value["property"]), text(value["conditions_sha256"]))
    }

    fun evaluate(adapter: FrozenSupportRuleAdapter, input: JsonNode): FrozenSupportResult {
        val raw = input["evidence"]
        val unsupported = raw["unsupported"].takeUnless { it.isNull }?.let {
            require(it.isArray) { "Fixture unsupported values are not an array" }
            it.mapTo(linkedSetOf(), ::text)
        }
        val evidence = RuleEvidence(text(raw["cohort_id"]), text(raw["cohort_version"]),
            scope(raw["scope"]), unsupported, fact(raw["exhaustive"]), fact(raw["requested_complete"]))
        val supplied = input["eligible_inputs"].takeUnless { it.isNull }?.let { value ->
            require(value.isObject)
            value.fields().asSequence().associate { (key, marker) -> key to when {
                marker.isNull -> null
                marker.isBoolean -> marker.booleanValue()
                marker.isNumber -> marker.numberValue()
                marker.isTextual -> marker.textValue()
                else -> emptyList<Any>()
            } }
        }
        @Suppress("UNCHECKED_CAST")
        return adapter.evaluate(text(input["rule"]), evidence,
            supplied as Map<String, Boolean?>?, fact(input["cohort_checked_only"]))
    }
}
