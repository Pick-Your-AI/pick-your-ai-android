package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.security.MessageDigest

class FrozenPatternConformanceTest {
    private val validator = FrozenSchemaValidator()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun patterns(node: JsonNode): Set<String> = when {
        node.isObject -> node.properties().flatMap { (name, value) ->
            (if (name == "pattern") setOf(value.textValue()) else emptySet()) + patterns(value)
        }.toSet()
        node.isArray -> node.flatMap { patterns(it) }.toSet()
        else -> emptySet()
    }

    @TestFactory
    fun `all frozen pattern families match the pinned Python reference`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/frozen-pattern-cases.json"))
        assertEquals("android-frozen-pattern-wire/v1", fixture["profile"].textValue())
        val inputs = fixture["inputs"]
        val requiredInputs = FrozenDocument.entries.map { "schemas/${it.schemaName}.schema.json" }.toSet() +
            "schemas/common.schema.json" + inputs.map { "examples/${it["example"].textValue()}.json" }
        assertEquals(20, requiredInputs.size)
        assertEquals(requiredInputs, fixture["input_sha256"].properties().map { it.key }.toSet())
        val actualPatterns = mutableSetOf<String>()
        fixture["input_sha256"].properties().forEach { (path, expected) ->
            val bytes = resource("/contracts/v1/$path")
            assertEquals(expected.textValue(), digest(bytes), path)
            if (path.startsWith("schemas/")) actualPatterns += patterns(parse(bytes))
        }
        val coveredPatterns = inputs.mapNotNull { it["pattern"].takeUnless(JsonNode::isNull)?.textValue() }.toSet()
        assertEquals(9, actualPatterns.size)
        assertEquals(actualPatterns, coveredPatterns)
        val cases = fixture["cases"]
        assertEquals(160, cases.size())
        assertEquals(cases.size(), cases.map { it["name"].textValue() }.toSet().size)
        return cases.map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            val entry = inputs[case["input"].textValue()]
            val input = parse(resource("/contracts/v1/examples/${entry["example"].textValue()}.json"))
            val pointer = entry["pointer"].textValue()
            val parent = input.at(pointer.substringBeforeLast('/')) as ObjectNode
            parent.set<JsonNode>(pointer.substringAfterLast('/'), case["value"].deepCopy())
            val result = validator.validate(FrozenDocument.valueOf(entry["document"].textValue()), input.toString().byteInputStream())
            val expected = when (case["expected"].textValue()) {
                "valid" -> FrozenValidation.Valid
                "schema_mismatch" -> FrozenValidation.SchemaMismatch
                else -> error("Unsupported frozen-pattern fixture outcome")
            }
            assertEquals(expected, result)
            // Successful pattern validation must not strip or rewrite the supplied characters.
            assertEquals(case["value"], input.at(pointer))
        } }
    }
}
