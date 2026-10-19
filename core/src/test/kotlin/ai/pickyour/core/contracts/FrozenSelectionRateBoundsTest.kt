package ai.pickyour.core.contracts

import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.drafts.SelectionFixtures
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/** Raw input probes for the two optional frozen operational rate fields. */
class FrozenSelectionRateBoundsTest {
    private val mapper = SelectionFixtures.mapper
    private val validator = FrozenSchemaValidator()
    private val frozenBuild = SelectionFixtures.resource("/contracts/v1/examples/build.json")
    private val frozenSchema = mapper.readTree(
        SelectionFixtures.resource("/contracts/v1/schemas/selection.schema.json"),
    )
    private val markerText = "__exact_selection_rate_probe__"
    private val marker = "\"$markerText\""

    private data class Location(
        val kind: String,
        val field: String,
        val maximum: String,
        val upperExponent: String,
        val aboveUpperExponent: String,
    )
    private data class Spelling(val raw: String, val valid: Boolean)
    private val locations = listOf(
        Location("monitoring_stack", "sample_rate", "1", "1e0", "1000000000000000001e-18"),
        Location("cost_policy", "notify_at_pct", "100", "1e2", "100000000000000000001e-18"),
    )

    private fun spellings(location: Location): List<Spelling> {
        val upper = BigDecimal(location.maximum)
        val step = BigDecimal("0.000000000000000001")
        return listOf(
            Spelling("0", true),
            Spelling("0.0", true),
            Spelling("0e20", true),
            Spelling("-0e-20", true),
            Spelling(location.maximum, true),
            Spelling(location.maximum + ".000000000000000000", true),
            Spelling(location.upperExponent, true),
            Spelling(upper.subtract(step).toPlainString(), true),
            Spelling("0.125000000000000001", true),
            Spelling("-0.000000000000000001", false),
            Spelling(upper.add(step).toPlainString(), false),
            Spelling("-1e-18", false),
            Spelling(location.aboveUpperExponent, false),
            Spelling("\"0\"", false),
            Spelling("true", false),
            Spelling("null", false),
        )
    }

    /** Insert raw JSON without first rounding the probe through a floating-point tree node. */
    private fun raw(node: ObjectNode, spelling: Spelling): ByteArray {
        val text = mapper.writeValueAsString(node)
        val position = text.indexOf(marker)
        assertTrue(position >= 0)
        assertEquals(position, text.lastIndexOf(marker))
        return text.replace(marker, spelling.raw).toByteArray(Charsets.UTF_8)
    }

    @TestFactory
    fun rawSelectionRatesRetainExactInclusiveBounds(): List<DynamicTest> =
        locations.flatMap { location ->
            spellings(location).map { spelling ->
                DynamicTest.dynamicTest("${location.kind}.${location.field}: ${spelling.raw}") {
                    val definition = frozenSchema["\$defs"]["settings_" + location.kind]["properties"][location.field]
                    assertEquals("number", definition["type"].textValue())
                    assertEquals(BigDecimal.ZERO, definition["minimum"].decimalValue())
                    assertEquals(BigDecimal(location.maximum), definition["maximum"].decimalValue())

                    val pristineBuild = frozenBuild.copyOf()
                    val build = mapper.readTree(frozenBuild) as ObjectNode
                    val matches = build["selections"].filter { it["kind"].textValue() == location.kind }
                    assertEquals(1, matches.size)
                    val selection = matches.single() as ObjectNode
                    (selection["settings"] as ObjectNode).put(location.field, markerText)

                    val selectionBytes = raw(selection, spelling)
                    val buildBytes = raw(build, spelling)
                    val originalSelectionBytes = selectionBytes.copyOf()
                    val originalBuildBytes = buildBytes.copyOf()
                    val expected = if (spelling.valid) FrozenValidation.Valid else FrozenValidation.SchemaMismatch
                    assertEquals(expected, validator.validate(FrozenDocument.SELECTION, selectionBytes.inputStream()))
                    assertEquals(expected, validator.validate(FrozenDocument.BUILD, buildBytes.inputStream()))

                    if (spelling.valid) {
                        val received = LocalSelection.fromJson(selectionBytes)
                        val rate = received.settings[location.field]
                        assertTrue(rate.isNumber)
                        assertEquals(0, BigDecimal(spelling.raw).compareTo(rate.decimalValue()))
                    } else {
                        val error = assertThrows(IllegalArgumentException::class.java) {
                            LocalSelection.fromJson(selectionBytes)
                        }
                        assertEquals("INVALID_SELECTION", error.message)
                    }
                    assertArrayEquals(originalSelectionBytes, selectionBytes)
                    assertArrayEquals(originalBuildBytes, buildBytes)
                    assertArrayEquals(pristineBuild, frozenBuild)
                }
            }
        }
}
