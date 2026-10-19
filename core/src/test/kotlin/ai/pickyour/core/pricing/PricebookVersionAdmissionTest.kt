package ai.pickyour.core.pricing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

class PricebookVersionAdmissionTest {
    private val mapper = ObjectMapper()
    private fun fixture(): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/pricebook.json").use {
        mapper.readTree(requireNotNull(it)) as ObjectNode
    }
    private fun query() = RateQuery("cat.compute_profile.fixture", "2026.10-demo", "gpu_hour",
        "us-east-fixture", "USD", Instant.parse("2026-10-16T09:00:00Z"))
    private fun version(value: String) = fixture().put("schema_version", value).toString().toByteArray(Charsets.UTF_8)

    private class OwnedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var wasClosed = false
        override fun close() { wasClosed = true; super.close() }
    }

    private fun assertRejection(bytes: ByteArray, unsupported: Boolean) {
        val original = bytes.copyOf()
        val recordedInput = OwnedInput(bytes)
        val screenInput = OwnedInput(bytes)
        val recorded = RecordedPricebook.read(recordedInput)
        val screened = RateScreen().screen(screenInput, query())
        assertEquals(if (unsupported) RecordedPricebookResult.UnsupportedSchemaVersion else RecordedPricebookResult.InvalidDocument, recorded)
        assertEquals(if (unsupported) RateScreenResult.UnsupportedSchemaVersion else RateScreenResult.InvalidDocument, screened)
        assertFalse(recordedInput.wasClosed)
        assertFalse(screenInput.wasClosed)
        assertArrayEquals(original, bytes)
        if (unsupported) {
            assertEquals(-1, recordedInput.read(), "version classification must follow complete input consumption")
            assertEquals(-1, screenInput.read(), "screening must preserve the same complete parsing boundary")
            assertEquals("UnsupportedSchemaVersion", recorded.toString())
            assertEquals("UnsupportedSchemaVersion", screened.toString())
        }
    }

    @TestFactory fun `unsupported textual versions have an explicit sanitized result`(): List<DynamicTest> =
        listOf("2.0.0", "1.0", "1.0.0 ", "1.0.0\n", "private version sentinel", "").mapIndexed { index, value ->
            DynamicTest.dynamicTest("unsupported textual version $index") { assertRejection(version(value), true) }
        }

    @TestFactory fun `missing and nontext versions retain invalid document`(): List<DynamicTest> =
        listOf<String?>(null, "null", "1", "true", "[]", "{}").mapIndexed { index, token ->
            DynamicTest.dynamicTest("missing or nontext version $index") {
                val input = fixture()
                if (token == null) input.remove("schema_version") else input.set<JsonNode>("schema_version", mapper.readTree(token))
                assertRejection(input.toString().toByteArray(Charsets.UTF_8), false)
            }
        }

    @TestFactory fun `strict syntax and byte failures precede version classification`(): List<DynamicTest> {
        val unsupported = version("2.0.0")
        val prefix = "{\"schema_version\":\"2.0.0\",\"later\":"
        val invalid = listOf(
            "duplicate version" to "{\"schema_version\":\"2.0.0\",\"schema_version\":\"1.0.0\"}".toByteArray(),
            "duplicate later key" to "{\"schema_version\":\"2.0.0\",\"later\":0,\"later\":1}".toByteArray(),
            "malformed later value" to "$prefix]}".toByteArray(),
            "invalid surrogate" to "$prefix\"\\uD800\"}".toByteArray(),
            "trailing object" to (unsupported + "{}".toByteArray()),
            "trailing malformed UTF-8" to (unsupported + byteArrayOf(0xc3.toByte(), 0x28)),
            "array root" to ("[".toByteArray() + unsupported + "]".toByteArray()),
        )
        return invalid.map { (name, input) -> DynamicTest.dynamicTest(name) { assertRejection(input, false) } }
    }

    @TestFactory fun `every parser budget remains authoritative before unsupported version`(): List<DynamicTest> {
        val prefix = "{\"schema_version\":\"2.0.0\",\"later\":"
        val bounded = listOf(
            "byte budget" to (version("2.0.0") + " ".repeat(1_048_577).toByteArray()),
            "nesting budget" to (prefix + "[".repeat(33) + "0" + "]".repeat(33) + "}").toByteArray(),
            "token budget" to (prefix + List(100_001) { "0" }.joinToString(",", "[", "]}")).toByteArray(),
            "string budget" to version("v".repeat(16_385)),
            "field name budget" to ("{\"schema_version\":\"2.0.0\",\"" + "n".repeat(257) + "\":0}").toByteArray(),
            "number budget" to (prefix + "1".repeat(129) + "}").toByteArray(),
        )
        return bounded.map { (name, input) -> DynamicTest.dynamicTest(name) { assertRejection(input, false) } }
    }

    @Test fun `complete unknown schema is distinguished before interpreting its fields`() {
        assertRejection("{\"schema_version\":\"2.0.0\",\"future_field\":true}".toByteArray(), true)
        assertRejection("{\"schema_version\":\"1.0.0\",\"future_field\":true}".toByteArray(), false)
    }

    @Test fun `read failure after a complete unknown object is still invalid and leaves caller streams open`() {
        fun input() = object : InputStream() {
            private val bytes = version("2.0.0")
            private var offset = 0
            var wasClosed = false
            override fun read(): Int = if (offset < bytes.size) bytes[offset++].toInt() and 0xff
                else throw IOException("private source sentinel")
            override fun close() { wasClosed = true }
        }
        val recordedInput = input()
        val screenInput = input()
        assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(recordedInput))
        assertEquals(RateScreenResult.InvalidDocument, RateScreen().screen(screenInput, query()))
        assertFalse(recordedInput.wasClosed)
        assertFalse(screenInput.wasClosed)
    }

    @Test fun `exact supported version keeps recorded metadata and candidate screening`() {
        val bytes = version("1.0.0")
        val input = OwnedInput(bytes)
        val parsed = RecordedPricebook.read(input)
        assertInstanceOf(RecordedPricebookResult.Parsed::class.java, parsed)
        val book = (parsed as RecordedPricebookResult.Parsed).pricebook
        assertEquals("prices.fixture.1", book.id)
        assertEquals("0.80", book.rates.first().unitPrice.amount.encoded)
        val screened = RateScreen().screen(bytes.inputStream(), query())
        assertInstanceOf(RateScreenResult.Screened::class.java, screened)
        val candidates = (screened as RateScreenResult.Screened).candidates
        assertEquals(book.hash, candidates.pricebookHash)
        assertEquals(emptyList<String>(), candidates.rateIds)
        assertTrue(RateGap.NON_LIST_PRICE in candidates.gaps)
        assertTrue(RateGap.EVIDENCE_REVIEW_REQUIRED in candidates.gaps)
        assertFalse(input.wasClosed)
    }
}
