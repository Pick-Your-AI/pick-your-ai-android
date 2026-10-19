package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger

class FrozenSelectionTest {
    private val decoder = FrozenSelectionDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun parse(bytes: ByteArray): ObjectNode = (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun originals(): List<ObjectNode> = parse(resource("/contracts/v1/examples/build.json"))["selections"].map { it as ObjectNode }
    private fun original(kind: String = "model"): ObjectNode = originals().single { it["kind"].textValue() == kind }
    private fun decode(bytes: ByteArray): FrozenBuildSelection =
        (decoder.decode(bytes.inputStream(), "1.0.0") as FrozenSelectionDecoding.Decoded).selection
    private fun decode(node: ObjectNode): FrozenBuildSelection = decode(node.toString().toByteArray())

    @TestFactory fun `standalone round trip for every frozen selection kind`(): List<DynamicTest> {
        val inputs = originals()
        val kinds = parse(resource("/contracts/v1/schemas/selection.schema.json"))["properties"]["kind"]["enum"].map { it.textValue() }
        assertEquals(29, inputs.size)
        assertEquals(kinds, inputs.map { it["kind"].textValue() })
        return inputs.map { input -> DynamicTest.dynamicTest(input["kind"].textValue()) {
            val selection = decode(input)
            assertEquals(input, selection.toJson())
            assertEquals(input, decode(selection.toJson()).toJson())
            assertEquals(input["id"].textValue(), selection.id)
            assertEquals(input["kind"].textValue(), selection.kind)
            assertEquals(input["catalog_item_id"].textValue(), selection.catalogItemId)
            assertEquals(input["catalog_item_version"].textValue(), selection.catalogItemVersion)
            assertEquals(input["role"].textValue(), selection.role)
            assertEquals(input["enabled"].booleanValue(), selection.enabled)
            assertEquals(input["settings"], selection.settings)
            assertNull(selection.notes)
            assertNull(selection.extensions)
            assertFalse(selection.toJson().has("schema_version"))
        } }
    }

    @Test fun `version context is required separately and never inserted into Selection`() {
        val input = original()
        for (version in listOf("", "1", "1.0", "1.0.0 ", "2.0.0", "unknown"))
            assertEquals(FrozenSelectionDecoding.UnsupportedVersion, decoder.decode(input.toString().byteInputStream(), version))
        assertEquals(input, decode(input).toJson())
        for (version in listOf("1.0.0", "2.0.0")) {
            val withVersion = input.deepCopy().put("schema_version", version)
            assertEquals(FrozenSelectionDecoding.SchemaMismatch, decoder.decode(withVersion.toString().byteInputStream(), "1.0.0"))
        }
    }

    @TestFactory fun `missing required Selection fields reject without a fabricated envelope`(): List<DynamicTest> =
        listOf("id", "kind", "catalog_item_id", "catalog_item_version", "role", "enabled", "settings").map { field ->
            DynamicTest.dynamicTest(field) {
                val input = original().apply { remove(field) }
                assertEquals(FrozenSelectionDecoding.SchemaMismatch, decoder.decode(input.toString().byteInputStream(), "1.0.0"))
            }
        }

    @TestFactory fun `invalid fields kinds settings and extensions remain schema failures`(): List<DynamicTest> {
        val cases: List<Pair<String, (ObjectNode) -> Unit>> = listOf(
            "unknown kind" to { it.put("kind", "future_model") },
            "wrong role case" to { it.put("role", "Primary") },
            "invalid selection identity" to { it.put("id", "x") },
            "empty catalog version" to { it.put("catalog_item_version", "") },
            "string enabled" to { it.put("enabled", "false") },
            "additional top field" to { it.put("future", true) },
            "null notes" to { it.putNull("notes") },
            "null extensions" to { it.putNull("extensions") },
            "unscoped extension" to { it.putObject("extensions").put("unscoped", true) },
            "unknown setting" to { (it["settings"] as ObjectNode).put("future", true) },
            "wrong setting type" to { (it["settings"] as ObjectNode).put("temperature", "0.5") },
            "out of range setting" to { (it["settings"] as ObjectNode).put("temperature", 3) },
            "wrong enum" to { (it["settings"] as ObjectNode).put("precision", "future") },
            "credential value instead of reference" to { (it["settings"] as ObjectNode).put("credential_ref", "actual-password") },
        )
        return cases.map { (name, mutate) -> DynamicTest.dynamicTest(name) {
            val input = original().also(mutate)
            assertEquals(FrozenSelectionDecoding.SchemaMismatch, decoder.decode(input.toString().byteInputStream(), "1.0.0"))
        } }
    }

    @Test fun `kind-specific required settings do not become optional through standalone decoding`() {
        for (kind in listOf("accelerator", "compute_profile", "context_policy")) {
            val input = original(kind).apply { putObject("settings") }
            assertEquals(FrozenSelectionDecoding.SchemaMismatch, decoder.decode(input.toString().byteInputStream(), "1.0.0"))
        }
        val empty = original("deployment_mode")
        assertEquals(0, decode(empty).settings.size())
    }

    @Test fun `optional absence empty values false and zero remain distinct`() {
        val input = original().put("enabled", false)
        (input["settings"] as ObjectNode).put("temperature", BigInteger.ZERO)
        val absent = decode(input)
        assertNull(absent.notes)
        assertNull(absent.extensions)
        assertFalse(absent.enabled)
        assertEquals(BigInteger.ZERO, absent.settings["temperature"].bigIntegerValue())
        input.put("notes", "").putObject("extensions")
        val empty = decode(input)
        assertEquals("", empty.notes)
        assertEquals(0, empty.extensions!!.size())
        assertEquals(input, empty.toJson())
    }

    @Test fun `exact decimal strings and arbitrary precision numbers survive standalone round trips`() {
        val money = original("cost_policy")
        (money["settings"] as ObjectNode).put("monthly_budget_amount", "123456789012345678901234567890.000000000001")
        val cost = decode(money)
        assertEquals("123456789012345678901234567890.000000000001", cost.settings["monthly_budget_amount"].textValue())
        assertEquals(money, decode(cost.toJson()).toJson())
        val context = original("context_policy")
        (context["settings"] as ObjectNode).put("requested_context_tokens", BigInteger("9007199254740993"))
        val exactContext = decode(context)
        assertEquals(BigInteger("9007199254740993"), exactContext.settings["requested_context_tokens"].bigIntegerValue())
        val model = original()
        val settings = model["settings"] as ObjectNode
        settings.put("temperature", BigDecimal("0.100000000000000001"))
        val exactModel = decode(model)
        assertEquals(BigDecimal("0.100000000000000001"), exactModel.settings["temperature"].decimalValue())
        assertEquals(exactModel.toJson(), decode(exactModel.toJson()).toJson())
    }

    @Test fun `returned settings extensions and whole payload are defensive snapshots`() {
        val input = original().put("notes", "retained")
        input.putObject("extensions").putObject("x_private").putArray("values").addNull().add(false)
            .add(input.objectNode().put("n", BigInteger("9007199254740993")))
        val selected = decode(input)
        val expected = input.deepCopy()
        input.put("role", "changed")
        (input["settings"] as ObjectNode).removeAll()
        (input["extensions"]["x_private"] as ObjectNode).removeAll()
        selected.settings.removeAll()
        (selected.extensions!!["x_private"] as ObjectNode).removeAll()
        selected.toJson().put("notes", "changed-again")
        assertEquals(expected, selected.toJson())
        assertEquals("retained", selected.notes)
        assertEquals(BigInteger("9007199254740993"), selected.extensions!!["x_private"]["values"][2]["n"].bigIntegerValue())
    }

    @Test fun `structural selection preserves inert references and does not apply private draft policy`() {
        val input = original().put("notes", "synthetic-private-selection <script>run()</script>")
        (input["settings"] as ObjectNode).put("credential_ref", "secret://synthetic/private-selection")
            .put("weights_uri", "file:///synthetic/private-selection")
        input.putObject("extensions").put("x_literal", "unknown").putNull("x_absent_value")
        val result = decoder.decode(input.toString().byteInputStream(), "1.0.0") as FrozenSelectionDecoding.Decoded
        assertEquals(input, result.selection.toJson())
        assertEquals("secret://synthetic/private-selection", result.selection.settings["credential_ref"].textValue())
        assertEquals("file:///synthetic/private-selection", result.selection.settings["weights_uri"].textValue())
        assertEquals("unknown", result.selection.extensions!!["x_literal"].textValue())
        assertTrue(result.selection.extensions!!["x_absent_value"].isNull)
        assertFalse(result.toString().contains("synthetic-private-selection"))
        assertEquals("FrozenBuildSelection", result.selection.toString())
    }

    @Test fun `syntax encoding duplicate trailing and budget errors precede version admission`() {
        val badInputs = listOf(
            "{".toByteArray() to JsonRejection.INVALID_JSON,
            "[]".toByteArray() to JsonRejection.UNSUPPORTED_ROOT,
            "{} {}".toByteArray() to JsonRejection.TRAILING_CONTENT,
            "{\"id\":\"first\",\"id\":\"second\"}".toByteArray() to JsonRejection.INVALID_JSON,
            ("{\"notes\":\"".toByteArray() + byteArrayOf(0xc2.toByte()) + "\"}".toByteArray()) to JsonRejection.INVALID_UTF8,
            ("{\"notes\":\"" + "x".repeat(16_385) + "\"}").toByteArray() to JsonRejection.LIMIT_EXCEEDED,
            ("{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray() to JsonRejection.LIMIT_EXCEEDED,
        )
        for (version in listOf("1.0.0", "2.0.0")) for ((bytes, reason) in badInputs) {
            assertEquals(FrozenSelectionDecoding.InvalidInput(reason), decoder.decode(bytes.inputStream(), version))
        }
    }

    @Test fun `byte budget stops a provider before consuming unbounded whitespace`() {
        val maximum = 1_048_576
        val input = object : InputStream() {
            var count = 0
            var closed = false
            override fun read(): Int { count++; return ' '.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(' '.code.toByte(), offset, offset + length)
                count += length
                return length
            }
            override fun close() { closed = true }
        }
        assertEquals(FrozenSelectionDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decode(input, "2.0.0"))
        assertEquals(maximum + 1, input.count)
        assertFalse(input.closed)
    }

    @Test fun `late input failure cannot return a parsed selection or an unsupported version`() {
        for (version in listOf("1.0.0", "2.0.0")) {
            val payload = original().toString().toByteArray()
            val input = object : InputStream() {
                private val source = ByteArrayInputStream(payload)
                var closed = false
                override fun read(): Int = source.read().also { if (it == -1) throw IOException("synthetic-private-selection/provider") }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    source.read(bytes, offset, length).also { if (it == -1) throw IOException("synthetic-private-selection/provider") }
                override fun close() { closed = true }
            }
            val result = decoder.decode(input, version)
            assertEquals(FrozenSelectionDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
            assertFalse(input.closed)
            assertFalse(result.toString().contains("synthetic-private-selection"))
        }
    }

    @Test fun `caller streams stay open on success schema version and parser failures`() {
        for ((bytes, version) in listOf(
            original().toString().toByteArray() to "1.0.0",
            original().toString().toByteArray() to "2.0.0",
            "{}".toByteArray() to "1.0.0",
            "{".toByteArray() to "1.0.0",
        )) {
            val input = object : ByteArrayInputStream(bytes) {
                var closed = false
                override fun close() { closed = true; super.close() }
            }
            decoder.decode(input, version)
            assertFalse(input.closed)
        }
    }
}
