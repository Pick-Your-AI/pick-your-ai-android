package ai.pickyour.core.conformance.typedcomparison

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.security.MessageDigest
import java.util.Collections

internal object TypedComparisonFixtures {
    const val CORPUS_SHA256 = "a8a1e4cc8e80d5b45424e38b023cee46f394a8d55fd776b4ff267df2386b1554"
    const val PROTOTYPE_SHA256 = "5fc27536a5e97a9a76ad250a90015c4082269b54c6d56bbefd2615fc99639b82"
    const val FROZEN_REFERENCE_SHA256 = "092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a"
    const val CORPUS_BYTES = 17_859

    data class Case(val name: String, val operator: String, val left: String?, val right: String?, val expected: String)

    fun digest(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    fun original(): ByteArray = TypedComparisonFixtures::class.java
        .getResourceAsStream("/conformance/typed-comparison-0.1.json")!!
        .use { it.readNBytes(CORPUS_BYTES + 1) }

    fun load(raw: ByteArray = original()): List<Case> {
        require(raw.size == CORPUS_BYTES && digest(raw) == CORPUS_SHA256) { "typed_fixture_identity" }
        val root = ObjectMapper().readTree(raw)
        require(root.isObject && root.fieldNames().asSequence().toSet() ==
            setOf("fixture_version", "profile", "classification", "frozen_reference_sha256", "cases"))
        require(root["fixture_version"].textValue() == "0.1" &&
            root["profile"].textValue() == TypedComparison01.PROFILE &&
            root["classification"].textValue() == "synthetic_test_only" &&
            root["frozen_reference_sha256"].textValue() == FROZEN_REFERENCE_SHA256)
        require(root["cases"].isArray && root["cases"].size() == 104)
        fun operand(node: JsonNode): String? {
            require(node.isNull || node.isTextual)
            return if (node.isNull) null else node.textValue()
        }
        val cases = root["cases"].map { row ->
            require(row.isObject && row.fieldNames().asSequence().toSet() ==
                setOf("name", "operator", "left_json", "right_json", "expected"))
            require(row["name"].isTextual && row["name"].textValue().isNotEmpty())
            require(row["operator"].isTextual && row["expected"].isTextual)
            Case(row["name"].textValue(), row["operator"].textValue(),
                operand(row["left_json"]), operand(row["right_json"]), row["expected"].textValue())
        }
        require(cases.map { it.name }.toSet().size == 104)
        require(cases.map { it.operator }.toSet() ==
            setOf("eq", "ne", "lt", "lte", "gt", "gte", "in", "contains", "intersects", "subset_of"))
        require(cases.map { it.expected }.toSet() == setOf("true", "false", "unknown"))
        return Collections.unmodifiableList(cases)
    }
}

class TypedComparisonFixtureTest {
    @TestFactory
    fun exactTypedComparisonCases(): List<DynamicTest> {
        val mapper = ObjectMapper()
        return TypedComparisonFixtures.load().map { row ->
            DynamicTest.dynamicTest("typed-comparison:" + row.name) {
                val left = row.left?.toByteArray(Charsets.UTF_8)
                val right = row.right?.toByteArray(Charsets.UTF_8)
                val result = TypedComparison01.compare(TypedComparison01.PROFILE, row.operator, left, right)
                assertTrue(result is TypedComparison01.Result.Compared, row.name)
                val actual = (result as TypedComparison01.Result.Compared).outcome.wire
                assertEquals(row.expected, actual, row.name)
                println("ANDROID_TYPED_COMPARISON_CASE " + mapper.writeValueAsString(linkedMapOf(
                    "profile" to TypedComparison01.PROFILE,
                    "case" to row.name,
                    "operator" to row.operator,
                    "outcome" to actual,
                    "leftSHA256" to left?.let(TypedComparisonFixtures::digest),
                    "rightSHA256" to right?.let(TypedComparisonFixtures::digest),
                    "corpusSHA256" to TypedComparisonFixtures.CORPUS_SHA256,
                    "prototypeSHA256" to TypedComparisonFixtures.PROTOTYPE_SHA256,
                )))
            }
        }
    }

    @Test
    fun changedTruncatedOrExtendedCorpusCannotSupplyAnExpectedOutcome() {
        val raw = TypedComparisonFixtures.original()
        val changed = raw.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        for (candidate in listOf(changed, raw.copyOf(raw.size - 1), raw + byteArrayOf(10))) {
            val error = assertThrows(IllegalArgumentException::class.java) { TypedComparisonFixtures.load(candidate) }
            assertEquals("typed_fixture_identity", error.message)
        }
        assertEquals(104, TypedComparisonFixtures.load(raw).size)
    }

    @Test
    fun reorderedCorpusCannotReuseTheReviewedCaseIdentity() {
        val mapper = ObjectMapper()
        val root = mapper.readTree(TypedComparisonFixtures.original())
        val originalCases = root["cases"].toList()
        val changed = root.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        changed.putArray("cases").addAll(originalCases.reversed())
        assertThrows(IllegalArgumentException::class.java) {
            TypedComparisonFixtures.load(mapper.writeValueAsBytes(changed))
        }
        assertEquals("residual-bool-equality", TypedComparisonFixtures.load().first().name)
    }
}
