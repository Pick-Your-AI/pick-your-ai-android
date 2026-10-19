package ai.pickyour.core.evidence.answers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.security.MessageDigest
import java.util.Base64

/** Expected observations were executed by the pinned, unmodified Research modules. */
class Cd1ReferenceTest {
    @TestFactory
    fun acceptedReferenceObservations(): List<DynamicTest> = cases.map { row ->
        DynamicTest.dynamicTest(row["id"].textValue()) {
            when (row["operation"].textValue()) {
                "read" -> checkRead(row)
                "rule" -> checkRule(row)
                else -> fail<Unit>("Unexpected reference operation")
            }
        }
    }

    @Test
    fun fixtureAndEightOriginalSourcePinsRemainExact() {
        val lockBytes = resource("cd1-reference-lock.json")
        assertEquals("ff363c952ffdd595f845b8e0ad2ea03d84238a8b190a1afd4e378e2c21148e8b", sha(lockBytes))
        val lock = mapper.readTree(lockBytes)
        assertEquals("cebbf59345e01d34b8ad4c20d0b57782f6e05369", lock["researchCommit"].textValue())
        assertEquals("6e9f196d091fb0fb89443c3fc0e6adcf05607c55", lock["researchTree"].textValue())
        assertEquals(8, lock["sourceFiles"].size())
        assertEquals(449_598, raw.size)
        assertEquals("e41334d419c74d0a2d5c5e4dec3b4a3fc441430b2936f9cf99ed98754650ac97", sha(raw))
        assertEquals(623, cases.size)
        assertEquals(623, cases.map { it["id"].textValue() }.toSet().size)
        assertEquals(427, cases.count { it["operation"].textValue() == "read" })
        assertEquals(196, cases.count { it["operation"].textValue() == "rule" })
        assertEquals(167, cases.count { it.path("expected").path("status").asText() == "read" })
        assertEquals(260, cases.count { it.path("expected").path("status").asText() == "rejected" })
    }

    private fun checkRead(row: JsonNode) {
        val original = Base64.getDecoder().decode(row["inputBase64"].textValue())
        assertEquals(row["inputSHA256"].textValue(), sha(original))
        val expected = row["expected"]
        fun read(): Pair<Cd1AnswerIndex, Cd1Answer> {
            val index = Cd1AnswerIndex.read(original)
            val field = fields.getOrPut(row["schema"].textValue()) {
                KnownAnswerSchema.read(row["schema"].textValue().toByteArray())
            }
            return index to index.read(row["pointer"].textValue(), field)
        }
        if (expected["status"].textValue() == "rejected") {
            val rejection = assertThrows(Cd1Rejected::class.java) { read() }
            assertNull(rejection.cause)
            assertEquals(rejection.problem.name, rejection.message)
            return
        }
        val (index, answer) = read()
        assertArrayEquals(original, index.originalBytes)
        assertEquals(row["inputSHA256"].textValue(), index.originalSHA256)
        assertEquals(expected["state"].textValue(), answer.state.encoded)
        assertEquals(expected["sourcePath"].nullableText(), answer.sourcePath)
        assertEquals(expected["presetSource"].nullableText(), answer.presetSource)
        assertEquals(if (expected["assumption"].isNull) null else expected["assumption"].booleanValue(), answer.assumption)
        val value = answer.value
        assertEquals(expected["valueKind"].textValue(), kind(value))
        if (answer.provided) assertTypedJson(expected["value"], checkNotNull(value))
        else assertNull(value)
    }

    private fun checkRule(row: JsonNode) {
        var appliesCalls = 0
        var assertionCalls = 0
        fun predicate(description: JsonNode): Cd1Predicate {
            val state = Cd1State.entries.single { it.encoded == description["state"].textValue() }
            val result = if (state == Cd1State.PROVIDED) Cd1Predicate.provided(
                if (description["truth"].isNull) null else description["truth"].booleanValue(),
                description["assumption"].booleanValue(), description["sourcePath"].nullableText(),
                description["presetSource"].nullableText(),
            ) else Cd1Predicate.unresolved(state, description["sourcePath"].nullableText())
            return if (row["negate"].booleanValue()) result.negated() else result
        }
        val rule = Cd1Rule(row["ruleId"].textValue(),
            Cd1Severity.entries.single { it.encoded == row["severity"].textValue() },
            Cd1Policy.valueOf(row["policy"].textValue().uppercase()),
            { appliesCalls++; predicate(row["appliesWhen"]) },
            { assertionCalls++; predicate(row["assertion"]) })
        val actual = Cd1RulePolicy.evaluate(rule)
        val expected = row["expected"]
        assertEquals(expected["ruleId"].textValue(), actual.ruleId)
        assertEquals(expected["severity"].textValue(), actual.severity.encoded)
        assertEquals(expected["outcome"].textValue(), actual.outcome.encoded)
        assertEquals(expected["reason"].textValue(), actual.reason)
        assertEquals(expected["position"].textValue(), actual.position.encoded)
        assertEquals(expected["answerState"].textValue(), actual.answerState.encoded)
        assertEquals(expected["sourcePath"].nullableText(), actual.sourcePath)
        assertEquals(expected["presetSource"].nullableText(), actual.presetSource)
        assertEquals(if (expected["assumption"].isNull) null else expected["assumption"].booleanValue(), actual.assumption)
        assertEquals(expected["calls"]["appliesWhen"].intValue(), appliesCalls)
        assertEquals(expected["calls"]["assertion"].intValue(), assertionCalls)
    }

    companion object {
        private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        private fun resource(name: String) = checkNotNull(Cd1ReferenceTest::class.java
            .getResourceAsStream("/evidence/answers/$name")).use { it.readBytes() }
        private val raw = resource("cd1-reference.json")
        private val cases = mapper.readTree(raw)["cases"].toList()
        private val fields = mutableMapOf<String, KnownAnswerSchema>()
        private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        private fun JsonNode.nullableText(): String? = if (isNull) null else textValue()

        private fun kind(node: JsonNode?): String = when {
            node == null -> "absent"
            node.isNull -> "null"
            node.isBoolean -> "boolean"
            node.isIntegralNumber -> "integer"
            node.isFloatingPointNumber -> "number"
            node.isTextual -> "string"
            node.isArray -> "array"
            node.isObject -> "object"
            else -> error("Unexpected JSON kind")
        }

        /** Compare the reference's runtime values and their types, not a JVM
         * reserialization (which spells the minimum subnormal differently).
         */
        private fun assertTypedJson(expected: JsonNode, actual: JsonNode) {
            assertEquals(kind(expected), kind(actual))
            when {
                expected.isIntegralNumber -> assertEquals(expected.bigIntegerValue(), actual.bigIntegerValue())
                expected.isFloatingPointNumber -> assertEquals(expected.doubleValue().toRawBits(), actual.doubleValue().toRawBits())
                expected.isArray -> {
                    assertEquals(expected.size(), actual.size())
                    expected.forEachIndexed { index, node -> assertTypedJson(node, actual[index]) }
                }
                expected.isObject -> {
                    assertEquals(expected.fieldNames().asSequence().toSet(), actual.fieldNames().asSequence().toSet())
                    expected.properties().forEach { (name, value) -> assertTypedJson(value, actual[name]) }
                }
                else -> assertEquals(expected, actual)
            }
        }
    }
}
