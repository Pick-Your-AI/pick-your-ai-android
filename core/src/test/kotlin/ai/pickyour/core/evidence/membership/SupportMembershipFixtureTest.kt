package ai.pickyour.core.evidence.membership

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class SupportMembershipFixtureTest {
    private val mapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun nullableFact(value: JsonNode): Boolean? {
        assertTrue(value.isNull || value.isBoolean)
        return if (value.isNull) null else value.booleanValue()
    }

    private fun strings(value: JsonNode): Set<String>? {
        if (value.isNull) return null
        assertTrue(value.isArray)
        assertTrue(value.all { it.isTextual })
        val items = value.map { it.textValue() }
        assertEquals(items.size, items.toSet().size)
        return items.toCollection(LinkedHashSet())
    }

    private fun assertDecision(expected: JsonNode, actual: MembershipDecision) {
        assertEquals(expected["outcome"].textValue(), actual.outcome.encoded)
        assertEquals(expected["reason"].textValue(), actual.reason.encoded)
        assertEquals(expected["source_id"].textValue(), actual.sourceId)
        assertEquals(expected["disputed"].booleanValue(), actual.disputed)
        assertEquals(expected["evidence_label"].textValue(), actual.evidenceLabel?.encoded)
        assertEquals(expected["findings"].size(), actual.findings.size)
        actual.findings.forEachIndexed { index, finding ->
            val reference = expected["findings"][index]
            assertEquals(reference["value"].textValue(), finding.value)
            assertEquals(reference["outcome"].textValue(), finding.outcome.encoded)
            assertEquals(reference["reason"].textValue(), finding.reason.encoded)
            assertEquals(reference["disputed"].booleanValue(), finding.disputed)
        }
    }

    @TestFactory
    fun pinnedResearchCases(): List<DynamicTest> {
        val compressed = requireNotNull(javaClass.getResourceAsStream("/evidence/support-membership-cases.json.gz"))
            .use { it.readBytes() }
        val raw = GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
        assertEquals("a2c97fcb631bf7214a789973a6176d9b6e4f41b6abd44747a3865deb0f6128da", digest(raw))
        val fixture = mapper.readTree(raw)
        assertEquals("android-normalized-support-membership/v1", fixture["profile"].textValue())
        assertEquals("ec29b802ff1a3c8a2f1e89384a0c4dc9b89c8a60", fixture["research_commit"].textValue())
        assertEquals(setOf(
            "tests/support_membership_prototype.py", "tests/test_support_membership.py",
            "tests/test_support_evidence_labels.py",
            "research/T0-configuration/component-schema-compatibility.md",
            "research/T0-configuration/catalog-data-sources.md",
        ), fixture["sources"].fieldNames().asSequence().toSet())
        val reference = fixture["sources"]["tests/support_membership_prototype.py"]
        assertEquals("4406ccc9de150aa2955abce6173025c350e17da2", reference["blob"].textValue())
        assertEquals("a2120d738889253b5bf558827abc22c4de0be99dc6caa3ebee89551e4817a185",
            reference["sha256"].textValue())
        assertEquals(6146, reference["bytes"].intValue())
        val cases = fixture["cases"]
        assertEquals(1530, cases.size())
        assertEquals(1530, cases.map { it["id"].textValue() }.toSet().size)
        assertEquals(90, cases.count { it["expected"].has("error") })
        return cases.map { case ->
            DynamicTest.dynamicTest(case["id"].textValue()) {
                val inputJson = case["input_json"].textValue()
                assertTrue(inputJson.all { it.code < 128 })
                assertEquals(case["input_sha256"].textValue(), digest(inputJson.toByteArray(Charsets.US_ASCII)))
                val input = mapper.readTree(inputJson)
                val cohort = input["evidence"]
                val evidence = SupportEvidence(
                    sourceId = cohort["source_id"].textValue(),
                    eligible = nullableFact(cohort["eligible"]),
                    supported = strings(cohort["supported"]),
                    exhaustive = nullableFact(cohort["exhaustive"]),
                    unsupported = requireNotNull(strings(cohort["unsupported"])),
                    checkedOnly = nullableFact(cohort["checked_only"]),
                )
                val requested = strings(input["requested"])
                val requirement = MembershipRequirement.entries.single { it.encoded == input["requirement"].textValue() }
                val applicable = nullableFact(input["applicable"])
                val complete = nullableFact(input["requested_complete"])
                val expected = case["expected"]
                if (expected.has("error")) {
                    assertEquals("invalid_requested_values", expected["error"].textValue())
                    assertThrows(IllegalArgumentException::class.java) {
                        SupportMembership.evaluate(requested, evidence, requirement, applicable, complete)
                    }
                } else {
                    assertDecision(expected, SupportMembership.evaluate(requested, evidence, requirement, applicable, complete))
                }
            }
        }
    }
}
