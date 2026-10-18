package ai.pickyour.core.contracts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrozenSchemaValidatorTest {
    private val validator = FrozenSchemaValidator()
    private val mapper = ObjectMapper()
    private fun example(name: String): ObjectNode =
        javaClass.getResourceAsStream("/contracts/v1/examples/$name.json").use {
            requireNotNull(it)
            mapper.readTree(it) as ObjectNode
        }
    private fun validate(type: FrozenDocument, value: com.fasterxml.jackson.databind.JsonNode) =
        validator.validate(type, mapper.writeValueAsBytes(value).inputStream())

    @Test
    fun `all canonical examples pass and embedded objects validate independently`() {
        val examples = mapOf(
            FrozenDocument.BUILD to "build", FrozenDocument.CATALOG to "catalog",
            FrozenDocument.COST_ESTIMATE to "cost-estimate", FrozenDocument.DEPENDENCY_GRAPH to "dependency-graph",
            FrozenDocument.DEPLOYMENT_MANIFEST to "deployment-manifest", FrozenDocument.EVALUATION_REPORT to "evaluation-report",
            FrozenDocument.EVENT to "event", FrozenDocument.PRICEBOOK to "pricebook",
            FrozenDocument.QUALITY_EVALUATION_RUN to "quality-evaluation-run", FrozenDocument.RESOURCE_ESTIMATE to "resource-estimate",
            FrozenDocument.RULESET to "ruleset",
        )
        examples.forEach { (type, name) -> assertEquals(FrozenValidation.Valid, validate(type, example(name)), name) }
        example("catalog")["items"].forEach { assertEquals(FrozenValidation.Valid, validate(FrozenDocument.CATALOG_ITEM, it)) }
        example("build")["selections"].forEach { assertEquals(FrozenValidation.Valid, validate(FrozenDocument.SELECTION, it)) }
    }

    @Test
    fun `future version extra fields and invalid enum rejected`() {
        val build = example("build")
        build.put("schema_version", "2.0.0")
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.BUILD, build))
        build.put("schema_version", "1.0.0").put("unexpected", true)
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.BUILD, build))
        build.remove("unexpected")
        build.put("lifecycle", "secret-backend-text")
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.BUILD, build))
    }

    @Test
    fun `unknown kinds and kind spec mismatch rejected`() {
        val catalog = example("catalog")
        val item = catalog["items"][0] as ObjectNode
        item.put("kind", "future_kind")
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.CATALOG, catalog))
        item.put("kind", "model")
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.CATALOG, catalog))
    }

    @Test
    fun `decimal numbers and malformed timestamps rejected`() {
        val prices = example("pricebook")
        (prices["rates"][0]["unit_price"] as ObjectNode).put("amount", 0.8)
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.PRICEBOOK, prices))
        val catalog = example("catalog").put("captured_at", "2026-02-30T09:00:00Z")
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.CATALOG, catalog))
    }

    @Test
    fun `hostile schema properties are data and unknown schema loads fail closed`() {
        val build = example("build").put("\$schema", "https://attacker.invalid/schema")
        assertEquals(FrozenValidation.SchemaMismatch, validate(FrozenDocument.BUILD, build))
        for (uri in listOf("https://attacker.invalid/schema", "file:///etc/passwd", "classpath:secret", "https://schemas.ai-builder.example/v1/../secret")) {
            assertThrows(IllegalStateException::class.java) { FrozenSchemaValidator.bundledSchema(uri) }
        }
    }

    @Test
    fun `malformed input fails before schema evaluation`() {
        val result = validator.validate(FrozenDocument.BUILD, "{\"id\":1,\"id\":2}".byteInputStream())
        assertTrue(result is FrozenValidation.InvalidInput)
    }
}
