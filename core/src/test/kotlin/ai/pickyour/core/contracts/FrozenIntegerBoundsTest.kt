package ai.pickyour.core.contracts

import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.drafts.SelectionCatalogSnapshot
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Exact raw-number admission at every integer minimum in the unchanged frozen package. */
class FrozenIntegerBoundsTest {
    private val mapper = ObjectMapper()
    private val validator = FrozenSchemaValidator()
    private fun resource(path: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/$path").use {
        mapper.readTree(requireNotNull(it)) as ObjectNode
    }
    private fun example(name: String) = resource("examples/$name.json")

    private class Location(val document: FrozenDocument, val schemaPath: String, val fixture: ObjectNode,
        val pointer: String, val minimum: Int)

    private fun locations(): List<Location> = buildList {
        fun collect(document: FrozenDocument, schema: JsonNode, schemaPath: String, fixture: ObjectNode, pointer: String) {
            schema["properties"]?.properties()?.forEach { (field, definition) ->
                val nextSchemaPath = "$schemaPath/properties/$field"
                val nextPointer = "$pointer/$field"
                if (definition["type"]?.textValue() == "integer" && definition.has("minimum")) {
                    add(Location(document, nextSchemaPath, fixture, nextPointer, definition["minimum"].intValue()))
                } else if (definition["type"]?.textValue() == "object") {
                    collect(document, definition, nextSchemaPath, fixture, nextPointer)
                }
            }
        }
        val documents = mapOf(FrozenDocument.BUILD to "build", FrozenDocument.CATALOG to "catalog",
            FrozenDocument.COST_ESTIMATE to "cost-estimate", FrozenDocument.DEPENDENCY_GRAPH to "dependency-graph",
            FrozenDocument.DEPLOYMENT_MANIFEST to "deployment-manifest", FrozenDocument.EVALUATION_REPORT to "evaluation-report",
            FrozenDocument.EVENT to "event", FrozenDocument.PRICEBOOK to "pricebook",
            FrozenDocument.QUALITY_EVALUATION_RUN to "quality-evaluation-run", FrozenDocument.RESOURCE_ESTIMATE to "resource-estimate",
            FrozenDocument.RULESET to "ruleset")
        documents.forEach { (document, fixture) ->
            collect(document, resource("schemas/${document.schemaName}.schema.json"), "", example(fixture), "")
        }
        for ((document, name, member) in listOf(Triple(FrozenDocument.CATALOG_ITEM, "catalog", "items"),
            Triple(FrozenDocument.SELECTION, "build", "selections"))) {
            val schema = resource("schemas/${document.schemaName}.schema.json")
            val prefix = if (document == FrozenDocument.CATALOG_ITEM) "spec" else "settings"
            example(name)[member].forEach { fixture ->
                val definition = "${prefix}_${fixture["kind"].textValue()}"
                collect(document, schema["\$defs"][definition], "/\$defs/$definition", fixture as ObjectNode, "/$prefix")
            }
        }
    }

    private fun raw(location: Location, number: String): ByteArray {
        val value = location.fixture.deepCopy()
        val parent = value.at(location.pointer.substringBeforeLast('/')) as ObjectNode
        // Replace one unique synthetic string token after ordinary serialization. This preserves
        // the exact supplied numeric spelling instead of letting the test mapper round/re-encode it.
        parent.put(location.pointer.substringAfterLast('/'), "__exact_numeric_probe__")
        return mapper.writeValueAsString(value).replace("\"__exact_numeric_probe__\"", number).toByteArray()
    }

    @TestFactory fun `every frozen integer minimum compares raw values without narrowing`(): List<DynamicTest> =
        locations().flatMap { location ->
            listOf("1", "1.0", "1e0", "1e18", "1e19", "1e30", "1e127", "1e129", "1e10000",
                "10000000000000000000", "-1", "-1.0", "-1e19", "-1e30", "-1e127", "-10000000000000000000",
                "0", "0.0", "0e9999", "0.5", "1.00000000000000000001", "true", "\"1\"").map { spelling ->
                DynamicTest.dynamicTest("${location.document}${location.schemaPath}: $spelling") {
                    val expected = when {
                        spelling in setOf("0", "0.0", "0e9999") && location.minimum == 0 -> FrozenValidation.Valid
                        spelling in setOf("1", "1.0", "1e0", "1e18", "1e19", "1e30", "1e127", "1e129", "1e10000",
                            "10000000000000000000") -> FrozenValidation.Valid
                        else -> FrozenValidation.SchemaMismatch
                    }
                    assertEquals(expected, validator.validate(location.document, raw(location, spelling).inputStream()))
                }
            }
        }

    @Test fun `coverage names every integer minimum and actual nested receivers enforce it`() {
        val covered = locations().map { it.document.schemaName + it.schemaPath }.toSet()
        val required = mutableSetOf<String>()
        fun inspect(document: FrozenDocument, node: JsonNode, pointer: String) {
            if (node.isObject) {
                if (node["type"]?.textValue() == "integer" && node.has("minimum")) required += document.schemaName + pointer
                node.properties().forEach { (key, value) -> inspect(document, value, "$pointer/$key") }
            } else if (node.isArray) node.forEachIndexed { index, value -> inspect(document, value, "$pointer/$index") }
        }
        FrozenDocument.entries.forEach { inspect(it, resource("schemas/${it.schemaName}.schema.json"), "") }
        assertEquals(required, covered)
        assertEquals(covered.size, locations().size)
        for (kind in listOf("workload", "model", "context_policy")) {
            val location = locations().first { it.document == FrozenDocument.SELECTION && it.fixture["kind"].textValue() == kind }
            val negative = raw(location, "-1e19")
            assertThrows(IllegalArgumentException::class.java) { LocalSelection.fromJson(negative) }
            val build = example("build")
            val position = build["selections"].indexOfFirst { it["kind"].textValue() == kind }
            (build["selections"] as com.fasterxml.jackson.databind.node.ArrayNode).set(position,
                (BoundedJsonReader().read(negative.inputStream()) as JsonInputResult.Parsed).value)
            assertEquals(FrozenValidation.SchemaMismatch, validator.validate(FrozenDocument.BUILD, mapper.writeValueAsBytes(build).inputStream()))
        }
        val catalog = locations().single { it.document == FrozenDocument.CATALOG && it.pointer == "/revision" }
        assertThrows(IllegalArgumentException::class.java) { SelectionCatalogSnapshot.fromJson(raw(catalog, "-1e19")) }
        assertNotNull(SelectionCatalogSnapshot.fromJson(raw(catalog, "1e19")))
    }

    @Test fun `ordinary numeric bounds remain exact and validation preserves input spelling and syntax gates`() {
        val selection = example("build")["selections"].first { it["kind"].textValue() == "model" } as ObjectNode
        val location = Location(FrozenDocument.SELECTION, "", selection, "/settings/temperature", 0)
        for ((spelling, expected) in listOf("0" to true, "0.1" to true, "2.0" to true, "2.000000000000000000001" to false,
            "-0.000000000000000000001" to false, "1e19" to false, "-1e19" to false)) {
            val bytes = raw(location, spelling)
            val copy = bytes.copyOf()
            val parsed = (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
            assertEquals(if (expected) FrozenValidation.Valid else FrozenValidation.SchemaMismatch,
                validator.validate(FrozenDocument.SELECTION, bytes.inputStream()))
            assertArrayEquals(copy, bytes)
            assertEquals(java.math.BigDecimal(spelling), parsed["settings"]["temperature"].decimalValue())
        }
        for (bad in listOf("{\"revision\":1,\"revision\":2}".toByteArray(), "{}{}".toByteArray(), byteArrayOf(0xff.toByte()))) {
            assertTrue(validator.validate(FrozenDocument.CATALOG, bad.inputStream()) is FrozenValidation.InvalidInput)
        }
    }

    @Test fun `local catalog and pricebook projections enforce expanded revision budget after valid schema intake`() {
        val catalog = locations().single { it.document == FrozenDocument.CATALOG && it.pointer == "/revision" }
        val pricebook = locations().single { it.document == FrozenDocument.PRICEBOOK && it.pointer == "/revision" }
        for (spelling in listOf("1.0", "1e19", "1e127", "9".repeat(128))) {
            val expected = java.math.BigDecimal(spelling).toBigIntegerExact()
            val browser = requireNotNull(PreviewCatalogBrowser.read(raw(catalog, spelling)))
            assertEquals(expected, requireNotNull(browser.details(browser.items.first(), browser.hash)).snapshotRevision)
            val bytes = raw(pricebook, spelling)
            val result = RecordedPricebook.read(bytes.inputStream()) as RecordedPricebookResult.Parsed
            assertEquals(expected, result.pricebook.revision)
            assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }, result.pricebook.hash)
        }
        for (spelling in listOf("1e128", "1e159", "1e308", "1e309", "1e1000", "9e999", "1e10000")) {
            assertEquals(FrozenValidation.Valid, validator.validate(FrozenDocument.CATALOG, raw(catalog, spelling).inputStream()))
            assertEquals(FrozenValidation.Valid, validator.validate(FrozenDocument.PRICEBOOK, raw(pricebook, spelling).inputStream()))
            assertNull(PreviewCatalogBrowser.read(raw(catalog, spelling)))
            assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(raw(pricebook, spelling).inputStream()))
        }
    }
}
