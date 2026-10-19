package ai.pickyour.core.contracts

import ai.pickyour.core.evidence.FrozenVerificationStatus
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import ai.pickyour.core.pricing.RateGap
import ai.pickyour.core.pricing.RateQuery
import ai.pickyour.core.pricing.RateScreen
import ai.pickyour.core.pricing.RateScreenResult
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant

class FrozenPricebookTest {
    private val decoder = FrozenPricebookDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/pricebook.json")
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun decode(bytes: ByteArray): FrozenPricebook =
        (decoder.decode(bytes.inputStream()) as FrozenPricebookDecoding.Decoded).pricebook
    private fun decode(node: ObjectNode): FrozenPricebook = decode(node.toString().toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @TestFactory
    fun `shared frozen Python and Kotlin pricebook cases`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/pricebook-cases.json"))
        assertEquals("android-frozen-pricebook-wire/v1", fixture["profile"].textValue())
        assertEquals(fixture["example_sha256"].textValue(), digest(example()))
        assertEquals(fixture["schema_sha256"].textValue(), digest(resource("/contracts/v1/schemas/pricebook.schema.json")))
        assertEquals(fixture["common_sha256"].textValue(), digest(resource("/contracts/v1/schemas/common.schema.json")))
        val names = fixture["cases"].map { it["name"].textValue() }
        assertEquals(188, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isEmpty() })
        return fixture["cases"].map { case -> DynamicTest.dynamicTest(case["name"].textValue()) {
            assertTrue(case.fieldNames().asSequence().all {
                it in setOf("name", "expected", "set", "remove", "rate_set", "rate_remove")
            })
            val input = parse(example())
            case.get("set")?.properties()?.forEach { (name, value) -> input.set<JsonNode>(name, value.deepCopy()) }
            case.get("remove")?.forEach { input.remove(it.textValue()) }
            case.get("rate_set")?.properties()?.forEach { (name, value) ->
                (input["rates"][0] as ObjectNode).set<JsonNode>(name, value.deepCopy())
            }
            case.get("rate_remove")?.forEach { (input["rates"][0] as ObjectNode).remove(it.textValue()) }
            val result = decoder.decode(input.toString().byteInputStream())
            val outcome = when (result) {
                is FrozenPricebookDecoding.Decoded -> {
                    assertTypedFields(input, result.pricebook)
                    assertEquals(input, parse(result.pricebook.json.toByteArray()))
                    assertTypedFields(input, decode(result.pricebook.json.toByteArray()))
                    "decoded"
                }
                FrozenPricebookDecoding.UnsupportedVersion -> "unsupported_version"
                FrozenPricebookDecoding.SchemaMismatch -> "schema_mismatch"
                is FrozenPricebookDecoding.InvalidInput -> "invalid_input"
            }
            assertEquals(case["expected"].textValue(), outcome)
            assertFalse(result.toString().contains("synthetic-private-price"))
        } }
    }

    private fun assertTypedFields(input: JsonNode, book: FrozenPricebook) {
        assertEquals(input["schema_version"].textValue(), book.schemaVersion)
        assertEquals(input["id"].textValue(), book.id)
        assertEquals(input["revision"].decimalValue().toBigIntegerExact(), book.revision)
        assertEquals(input["captured_at"].textValue(), book.capturedAt)
        assertEquals(input["rates"].size(), book.rates.size)
        input["rates"].zip(book.rates).forEach { (rate, recorded) ->
            assertEquals(rate["id"].textValue(), recorded.id)
            assertEquals(rate["catalog_item_id"].textValue(), recorded.catalogItemId)
            assertEquals(rate["catalog_item_version"].textValue(), recorded.catalogItemVersion)
            assertEquals(rate["meter"].textValue(), recorded.meter.encoded)
            assertEquals(rate["region"]?.textValue(), recorded.region)
            assertEquals(rate["unit_price"]["amount"].textValue(), recorded.unitPrice.amount)
            assertEquals(rate["unit_price"]["currency"].textValue(), recorded.unitPrice.currency)
            assertEquals(rate["valid_from"].textValue(), recorded.validFrom)
            assertEquals(rate["valid_until"]?.textValue(), recorded.validUntil)
            assertEquals(rate["price_type"].textValue(), recorded.priceType.encoded)
            assertEquals(rate["conditions"]?.map { it.textValue() }, recorded.conditions)
            val source = rate["provenance"]
            assertEquals(source["source_uri"]?.textValue(), recorded.provenance.sourceUri)
            assertEquals(source["checked_at"].textValue(), recorded.provenance.checkedAt)
            assertEquals(source["verification_status"].textValue(), recorded.provenance.verificationStatus.encoded)
            assertEquals(source["notes"]?.textValue(), recorded.provenance.notes)
        }
    }

    @Test fun `typed enum inventories exactly equal all frozen closed members`() {
        val properties = parse(resource("/contracts/v1/schemas/pricebook.schema.json"))["properties"]["rates"]["items"]["properties"]
        fun values(node: JsonNode) = node["enum"].map { it.textValue() }
        assertEquals(values(properties["meter"]), FrozenPriceMeter.entries.map { it.encoded })
        assertEquals(values(properties["price_type"]), FrozenPriceType.entries.map { it.encoded })
        val provenance = parse(resource("/contracts/v1/schemas/common.schema.json"))["\$defs"]["provenance"]["properties"]
        assertEquals(values(provenance["verification_status"]), FrozenVerificationStatus.entries.map { it.encoded })
    }

    @Test fun `revision preserves exact integral decimal and exponent values beyond machine width`() {
        val template = example().toString(Charsets.UTF_8)
        val needle = "\"revision\": 1"
        assertTrue(template.contains(needle))
        for (number in listOf("1.00", "1e0", "1.2500e2", "9007199254740993", "9223372036854775808",
            "1e128", "1e1000", "9".repeat(128))) {
            val book = decode(template.replace(needle, "\"revision\": $number").toByteArray())
            val expected = BigDecimal(number).toBigIntegerExact()
            assertEquals(expected, book.revision)
            assertEquals(expected, decode(book.json.toByteArray()).revision)
        }
        for (number in listOf("-1e19", "0e19", "1.5", "9007199254740992.1", "1e-1")) {
            assertEquals(FrozenPricebookDecoding.SchemaMismatch,
                decoder.decode(template.replace(needle, "\"revision\": $number").byteInputStream()))
        }
    }

    @Test fun `structural decoding preserves inputs rejected by the unchanged screening snapshot reader`() {
        assertTrue(RecordedPricebook.read(example().inputStream()) is RecordedPricebookResult.Parsed)
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { (it["rates"] as ArrayNode).add(it["rates"][0].deepCopy<JsonNode>()) },
            { (it["rates"][0] as ObjectNode).put("valid_until", "2025-01-01T00:00:00Z") },
            { (it["rates"][0] as ObjectNode).put("valid_until", it["rates"][0]["valid_from"].textValue()) },
            { (it["rates"][0]["unit_price"] as ObjectNode).put("amount", "0.80\n") },
            { (it["rates"][0]["unit_price"] as ObjectNode).put("currency", "USD\n") },
        )
        mutations.forEach { mutate ->
            val input = parse(example()).also(mutate)
            assertTypedFields(input, decode(input))
            assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(input.toString().byteInputStream()))
        }
        val expanded = example().toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": 1e128")
        assertEquals(BigDecimal("1e128").toBigIntegerExact(), decode(expanded.toByteArray()).revision)
        assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(expanded.byteInputStream()))
    }

    @Test fun `recorded verified list zero price cannot remove existing screen evidence requirements`() {
        val input = parse(example())
        val rate = input["rates"][0] as ObjectNode
        (input["rates"] as ArrayNode).remove(1)
        rate.put("price_type", "list")
        rate.putArray("conditions")
        (rate["unit_price"] as ObjectNode).put("amount", "0.00")
        (rate["provenance"] as ObjectNode).put("verification_status", "verified")
            .put("source_uri", "https://example.invalid/synthetic-private-price")
        val book = decode(input)
        assertEquals(FrozenVerificationStatus.VERIFIED, book.rates[0].provenance.verificationStatus)
        assertEquals("0.00", book.rates[0].unitPrice.amount)
        val query = RateQuery(rate["catalog_item_id"].textValue(), rate["catalog_item_version"].textValue(),
            "gpu_hour", "us-east-fixture", "USD", Instant.parse("2026-10-17T09:00:00Z"))
        val screened = RateScreen().screen(input.toString().byteInputStream(), query) as RateScreenResult.Screened
        assertEquals(listOf(rate["id"].textValue()), screened.candidates.rateIds)
        assertEquals(setOf(RateGap.EVIDENCE_REVIEW_REQUIRED), screened.candidates.gaps)
    }

    @Test fun `rate and condition lists are copied and immutable without mutable JSON escape`() {
        val input = parse(example())
        (input["rates"][0] as ObjectNode).putArray("conditions").add("synthetic-private-price").add("")
        val book = decode(input)
        val originalJson = book.json
        input.removeAll()
        assertEquals(originalJson, book.json)
        assertEquals("prices.fixture.1", book.id)
        assertThrows(UnsupportedOperationException::class.java) { (book.rates as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (book.rates[0].conditions as MutableList).clear() }
        val conditions = mutableListOf("b", "", "a", "b")
        val source = book.rates[0]
        val rate = FrozenPriceRate(source.id, source.catalogItemId, source.catalogItemVersion, source.meter,
            source.region, source.unitPrice, source.validFrom, source.validUntil, source.provenance,
            conditions, source.priceType)
        val rates = mutableListOf(rate, rate)
        val copy = FrozenPricebook(book.schemaVersion, book.id, book.revision, book.capturedAt, rates, book.json)
        conditions.clear(); rates.clear()
        assertEquals(2, copy.rates.size)
        assertEquals(listOf("b", "", "a", "b"), copy.rates[0].conditions)
        assertNull(book.rates[1].conditions)
        val empty = parse(example())
        (empty["rates"][0] as ObjectNode).putArray("conditions")
        val explicitEmpty = decode(empty).rates[0].conditions
        assertEquals(emptyList<String>(), explicitEmpty)
        assertThrows(UnsupportedOperationException::class.java) { (explicitEmpty as MutableList).add("x") }
    }

    @Test fun `default formatting omits all recorded price and provenance data`() {
        val input = parse(example()).put("id", "synthetic-private-price")
        (input["rates"][0]["provenance"] as ObjectNode).put("notes", "synthetic-private-price")
            .put("source_uri", "https://example.invalid/synthetic-private-price")
        val book = decode(input)
        assertEquals("FrozenPricebook", book.toString())
        assertEquals("FrozenPriceRate", book.rates[0].toString())
        assertEquals("FrozenPricebookMoney", book.rates[0].unitPrice.toString())
        assertEquals("FrozenPricebookProvenance", book.rates[0].provenance.toString())
        assertEquals("Decoded(pricebook=FrozenPricebook)", FrozenPricebookDecoding.Decoded(book).toString())
    }

    @Test fun `strict syntax and original plus normalized budgets retain sanitized outcomes`() {
        val template = example().toString(Charsets.UTF_8)
        val cases = listOf(
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}" to JsonRejection.INVALID_JSON,
            template + "{}" to JsonRejection.TRAILING_CONTENT,
            "[]" to JsonRejection.UNSUPPORTED_ROOT,
            "{\"x\":\"\\uD800\"}" to JsonRejection.INVALID_JSON,
            template.replace("\"revision\": 1", "\"revision\": NaN") to JsonRejection.INVALID_JSON,
            template.replace("\"revision\": 1", "\"revision\": ${"9".repeat(129)}") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"revision\": 1", "\"revision\": 1e10001") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("\"revision\": 1", "\"revision\": ${"9".repeat(126)}e2") to JsonRejection.LIMIT_EXCEEDED,
            template.replace("prices.fixture.1", "x".repeat(16_385)) to JsonRejection.LIMIT_EXCEEDED,
            "{\"x\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}" to JsonRejection.LIMIT_EXCEEDED,
        )
        cases.forEach { (input, reason) ->
            assertEquals(FrozenPricebookDecoding.InvalidInput(reason), decoder.decode(input.byteInputStream()))
        }
        assertEquals(FrozenPricebookDecoding.InvalidInput(JsonRejection.INVALID_UTF8),
            decoder.decode(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
        val atLimit = example() + ByteArray(1_048_576 - example().size) { 0x20 }
        assertEquals("prices.fixture.1", decode(atLimit).id)
        assertEquals(FrozenPricebookDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED),
            decoder.decode((atLimit + byteArrayOf(0x20)).inputStream()))
    }

    @Test fun `caller retains stream ownership across short reads and every failure class`() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(1, length))
        }
        listOf(example(), "{}".toByteArray(), "{".toByteArray(), parse(example()).put("captured_at", "synthetic-private-price")
            .toString().toByteArray()).forEach { bytes ->
            val stream = Tracked(bytes)
            val result = decoder.decode(stream)
            if (bytes.contentEquals(example())) assertTrue(result is FrozenPricebookDecoding.Decoded)
            assertFalse(stream.closed)
        }
        val broken = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-price")
            override fun close() { closed = true }
        }
        val result = decoder.decode(broken)
        assertEquals(FrozenPricebookDecoding.InvalidInput(JsonRejection.IO_FAILURE), result)
        assertFalse(broken.closed)
        assertFalse(result.toString().contains("synthetic-private-price"))
    }
}
