package ai.pickyour.core.contracts

import ai.pickyour.core.drafts.SelectionCatalogSnapshot
import ai.pickyour.core.drafts.SelectionFixtures
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.security.MessageDigest

/** The two catalog percentage ranges, including fractions too precise for a Double boundary check. */
class FrozenCatalogPercentageBoundsTest {
    private val mapper = SelectionFixtures.mapper
    private val validator = FrozenSchemaValidator()
    private val frozenCatalog = SelectionFixtures.resource("/contracts/v1/examples/catalog.json")
    private val frozenSchema = mapper.readTree(SelectionFixtures.resource("/contracts/v1/schemas/catalog-item.schema.json"))
    private val marker = "\"__exact_percentage_probe__\""

    private data class Location(val kind: String, val field: String)
    private data class Spelling(val raw: String, val valid: Boolean)

    private fun raw(node: ObjectNode, spelling: String): ByteArray {
        val serialized = mapper.writeValueAsString(node)
        val first = serialized.indexOf(marker)
        assertTrue(first >= 0)
        assertEquals(first, serialized.lastIndexOf(marker), "Replace exactly one synthetic JSON token")
        return serialized.replace(marker, spelling).toByteArray(Charsets.UTF_8)
    }

    @TestFactory fun `catalog percentages enforce exact inclusive bounds and types in standalone and nested receivers`(): List<DynamicTest> =
        listOf(Location("workload", "uptime_target_pct"), Location("cost_policy", "alert_threshold_pct")).flatMap { location ->
            listOf(
                Spelling("0", true), Spelling("0.0", true), Spelling("0e20", true),
                Spelling("100", true), Spelling("100.000000000000000000", true), Spelling("1e2", true),
                Spelling("0.125000000000000001", true),
                Spelling("-0.000000000000000001", false), Spelling("100.000000000000000001", false),
                Spelling("-1e19", false), Spelling("1e19", false),
                Spelling("\"0\"", false), Spelling("true", false), Spelling("null", false),
            ).map { spelling ->
                DynamicTest.dynamicTest("${location.kind}.${location.field}: ${spelling.raw}") {
                    val definition = frozenSchema["\$defs"]["spec_${location.kind}"]["properties"][location.field]
                    assertEquals("number", definition["type"].textValue())
                    assertEquals(0, BigDecimal.ZERO.compareTo(definition["minimum"].decimalValue()))
                    assertEquals(0, BigDecimal("100").compareTo(definition["maximum"].decimalValue()))
                    val original = frozenCatalog.copyOf()
                    val catalog = mapper.readTree(frozenCatalog) as ObjectNode
                    val item = catalog["items"].single { it["kind"].textValue() == location.kind } as ObjectNode
                    (item["spec"] as ObjectNode).put(location.field, "__exact_percentage_probe__")
                    val itemBytes = raw(item, spelling.raw)
                    val catalogBytes = raw(catalog, spelling.raw)
                    val itemCopy = itemBytes.copyOf()
                    val catalogCopy = catalogBytes.copyOf()
                    val expected = if (spelling.valid) FrozenValidation.Valid else FrozenValidation.SchemaMismatch
                    assertEquals(expected, validator.validate(FrozenDocument.CATALOG_ITEM, itemBytes.inputStream()))
                    assertEquals(expected, validator.validate(FrozenDocument.CATALOG, catalogBytes.inputStream()))
                    if (spelling.valid) {
                        val accepted = SelectionCatalogSnapshot.fromJson(catalogBytes)
                        val hash = MessageDigest.getInstance("SHA-256").digest(catalogBytes)
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        assertEquals(hash, accepted.hash.hex)
                    } else {
                        assertThrows(IllegalArgumentException::class.java) { SelectionCatalogSnapshot.fromJson(catalogBytes) }
                    }
                    assertArrayEquals(itemCopy, itemBytes)
                    assertArrayEquals(catalogCopy, catalogBytes)
                    assertArrayEquals(original, frozenCatalog)
                }
            }
        }
}
