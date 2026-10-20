package ai.pickyour.core.contracts

import ai.pickyour.core.evidence.FrozenVerificationStatus
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
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
import java.math.BigInteger
import java.security.MessageDigest

class FrozenCatalogTest {
    private val decoder = FrozenCatalogDecoder()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    private fun example(): ByteArray = resource("/contracts/v1/examples/catalog.json")
    private fun parse(bytes: ByteArray): ObjectNode = (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun parse(json: String): ObjectNode = parse(json.toByteArray())
    private fun firstItem(): ObjectNode = parse(example())["items"][0] as ObjectNode
    private fun snapshot(bytes: ByteArray): FrozenCatalogSnapshot =
        (decoder.decodeSnapshot(bytes.inputStream()) as FrozenCatalogDecoding.Decoded).value
    private fun item(node: JsonNode): FrozenCatalogItem =
        (decoder.decodeItem(node.toString().byteInputStream()) as FrozenCatalogDecoding.Decoded).value

    @TestFactory fun `all pinned Python catalog mutations have their expected Kotlin outcome`(): List<DynamicTest> {
        val fixture = parse(resource("/contracts/frozen-catalog-cases.json"))
        assertEquals("android-frozen-catalog-wire/v1", fixture["profile"].textValue())
        fixture["inputs"].properties().forEach { (path, digest) ->
            assertEquals(digest.textValue(), MessageDigest.getInstance("SHA-256").digest(resource("/contracts/v1/$path"))
                .joinToString("") { "%02x".format(it) })
        }
        assertEquals(225, fixture["cases"].size())
        assertEquals(225, fixture["cases"].map { it["name"].textValue() }.toSet().size)
        return fixture["cases"].map { case ->
            DynamicTest.dynamicTest(case["name"].textValue()) {
                val value = mutation(case)
                val result = if (case["target"].textValue() == "item")
                    decoder.decodeItem(value.toString().byteInputStream())
                else decoder.decodeSnapshot(value.toString().byteInputStream())
                when (case["expected"].textValue()) {
                    "decoded" -> {
                        assertTrue(result is FrozenCatalogDecoding.Decoded)
                        val json = when (val decoded = (result as FrozenCatalogDecoding.Decoded).value) {
                            is FrozenCatalogItem -> decoded.json
                            is FrozenCatalogSnapshot -> decoded.json
                            else -> error("Unexpected decoded catalog value")
                        }
                        assertEquals(value, parse(json))
                    }
                    "unsupported_version" -> assertEquals(FrozenCatalogDecoding.UnsupportedVersion, result)
                    "schema_mismatch" -> assertEquals(FrozenCatalogDecoding.SchemaMismatch, result)
                    else -> error("Unexpected catalog case outcome")
                }
            }
        }
    }

    private fun mutation(case: JsonNode): ObjectNode {
        val original = parse(example())
        val selected = case.get("kind")?.textValue()?.let { kind -> original["items"].single { it["kind"].textValue() == kind } }
        val value = when (case["target"].textValue()) {
            "item" -> requireNotNull(selected) as ObjectNode
            "snapshot" -> original.apply { if (selected != null) set<ArrayNode>("items", arrayNode().add(selected)) }
            else -> error("Unexpected catalog target")
        }
        for (operation in case.path("operations")) {
            val path = operation["path"].toList()
            require(path.isNotEmpty())
            var parent: JsonNode = value
            for (part in path.dropLast(1)) parent = if (part.isIntegralNumber) parent[part.intValue()] else parent[part.textValue()]
            val last = path.last()
            when (operation["op"].textValue()) {
                "set" -> if (last.isIntegralNumber) (parent as ArrayNode).set(last.intValue(), operation["value"].deepCopy<JsonNode>())
                    else (parent as ObjectNode).set<JsonNode>(last.textValue(), operation["value"].deepCopy<JsonNode>())
                "remove" -> if (last.isIntegralNumber) (parent as ArrayNode).remove(last.intValue())
                    else (parent as ObjectNode).remove(last.textValue())
                else -> error("Unexpected catalog operation")
            }
        }
        return value
    }

    @Test fun `snapshot and standalone items expose every recorded field and all canonical kinds`() {
        val input = parse(example())
        val result = snapshot(example())
        assertEquals("1.0.0", result.schemaVersion)
        assertEquals("catalog.fixture.1", result.id)
        assertEquals(BigInteger.ONE, result.revision)
        assertEquals("2026-10-16T09:00:00Z", result.capturedAt)
        assertEquals(FrozenCatalogKind.entries.toList(), result.items.map { it.kind })
        assertEquals(FrozenCatalogTier.entries.toSet(), result.items.map { it.tier }.toSet())
        for ((position, value) in result.items.withIndex()) {
            val source = input["items"][position]
            assertEquals(source["schema_version"].textValue(), value.schemaVersion)
            assertEquals(source["id"].textValue(), value.id)
            assertEquals(source["kind"].textValue(), value.kind.encoded)
            assertEquals(source["tier"].textValue(), value.tier.name)
            assertEquals(source["name"].textValue(), value.name)
            assertEquals(source["version"].textValue(), value.version)
            assertEquals(source["status"].textValue(), value.status.encoded)
            assertEquals(source["billing_class"].textValue(), value.billingClass.encoded)
            assertEquals(source["spec"], parse(value.specJson))
            assertEquals(source["updated_at"].textValue(), value.updatedAt)
            assertEquals(source, parse(value.json))
            assertEquals(value.json, item(source).json)
            assertEquals(source.get("description")?.textValue(), value.description)
            assertEquals(source.get("tags")?.map { it.textValue() }, value.tags)
            val license = source.get("license")
            if (license == null) assertNull(value.license) else {
                assertNotNull(value.license)
                assertEquals(license.get("spdx_id")?.textValue(), value.license?.spdxId)
                assertEquals(license.get("commercial_use_allowed")?.booleanValue(), value.license?.commercialUseAllowed)
                assertEquals(license.get("self_hosting_allowed")?.booleanValue(), value.license?.selfHostingAllowed)
            }
            assertEquals(source.get("extensions")?.toString(), value.extensionsJson)
            assertEquals(1, value.provenance.size)
            assertEquals(FrozenVerificationStatus.USER_SUPPLIED, value.provenance[0].verificationStatus)
            assertEquals(source["provenance"][0]["checked_at"].textValue(), value.provenance[0].checkedAt)
            assertEquals(source["provenance"][0]["notes"].textValue(), value.provenance[0].notes)
            assertNull(value.provenance[0].sourceUri)
        }
    }

    @Test fun `optional absence empty values and false flags are preserved independently`() {
        val input = firstItem()
        input.put("description", "")
        input.set<ArrayNode>("tags", input.arrayNode())
        input.set<ObjectNode>("extensions", input.objectNode())
        input.set<ObjectNode>("license", input.objectNode())
        val empty = item(input)
        assertEquals("", empty.description)
        assertEquals(emptyList<String>(), empty.tags)
        assertEquals("{}", empty.extensionsJson)
        assertNotNull(empty.license)
        assertNull(empty.license?.spdxId)
        assertNull(empty.license?.commercialUseAllowed)
        assertNull(empty.license?.selfHostingAllowed)
        (input["license"] as ObjectNode).put("spdx_id", "").put("commercial_use_allowed", false).put("self_hosting_allowed", true)
        val flags = item(input).license!!
        assertEquals("", flags.spdxId)
        assertEquals(false, flags.commercialUseAllowed)
        assertEquals(true, flags.selfHostingAllowed)
        assertEquals("FrozenCatalogLicense", flags.toString())
        (input["provenance"][0] as ObjectNode).remove("notes")
        assertNull(item(input).provenance[0].notes)
        (input["provenance"][0] as ObjectNode).put("notes", "").put("source_uri", "file:///synthetic/catalog")
            .put("checked_at", "2026-10-18T01:02:03.1200+05:30")
        val source = item(input).provenance[0]
        assertEquals("", source.notes)
        assertEquals("file:///synthetic/catalog", source.sourceUri)
        assertEquals("2026-10-18T01:02:03.1200+05:30", source.checkedAt)
    }

    @Test fun `exact revision integers and exponents never narrow or truncate fractional values`() {
        val original = example().toString(Charsets.UTF_8)
        for (token in listOf("123456789012345678901234567890", "1.00e2", "1e19", "1e1000")) {
            val input = original.replace("\"revision\": 1", "\"revision\": $token")
            val result = snapshot(input.toByteArray())
            assertEquals(BigDecimal(token).toBigIntegerExact(), result.revision)
            assertEquals(result.revision, snapshot(result.json.toByteArray()).revision)
        }
        for (token in listOf("0", "-1e19", "1.1", "9007199254740992.1")) {
            assertEquals(FrozenCatalogDecoding.SchemaMismatch, decoder.decodeSnapshot(
                original.replace("\"revision\": 1", "\"revision\": $token").byteInputStream()))
        }
        val expanded = "9".repeat(126) + "e2"
        assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), decoder.decodeSnapshot(
            original.replace("\"revision\": 1", "\"revision\": $expanded").byteInputStream()))
    }

    @Test fun `spec and extension JSON retain exact numbers nested values null and literal unknown`() {
        val input = firstItem()
        (input["spec"] as ObjectNode).put("concurrency", BigInteger("9007199254740993"))
        val extra = input.objectNode().put("x_number", BigDecimal("9007199254740992.123456789012"))
            .put("x_decimal_text", "0.000000000001").put("x_literal", "unknown").putNull("x_null")
        extra.set<ArrayNode>("x_nested", input.arrayNode().add(false).add(input.objectNode().put("flag", true)).add(input.arrayNode()))
        input.set<ObjectNode>("extensions", extra)
        val result = item(input)
        assertEquals(BigInteger("9007199254740993"), parse(result.specJson)["concurrency"].bigIntegerValue())
        assertEquals(extra, parse(result.extensionsJson!!))
        assertEquals(input, parse(result.json))
        assertEquals(result.extensionsJson, item(parse(result.json)).extensionsJson)
        extra.removeAll()
        (input["spec"] as ObjectNode).put("concurrency", 1)
        assertEquals(BigInteger("9007199254740993"), parse(result.specJson)["concurrency"].bigIntegerValue())
        assertEquals("unknown", parse(result.extensionsJson)["x_literal"].textValue())
        assertTrue(parse(result.extensionsJson)["x_null"].isNull)
    }

    @Test fun `snapshot duplicates order and contradictory recorded metadata receive no identity policy`() {
        val input = parse(example())
        val first = (input["items"][0] as ObjectNode).deepCopy()
        val second = first.deepCopy().put("version", "other-recorded-version").put("status", "retired")
        input.set<ArrayNode>("items", input.arrayNode().add(second).add(first).add(second.deepCopy()))
        val result = snapshot(input.toString().toByteArray())
        assertEquals(listOf("other-recorded-version", first["version"].textValue(), "other-recorded-version"), result.items.map { it.version })
        assertEquals(1, result.items.map { it.id }.toSet().size)
        assertEquals(FrozenCatalogStatus.RETIRED, result.items[0].status)
        assertEquals(input, parse(result.json))
    }

    @Test fun `decoded nested collections resist mutation and diagnostic strings omit recorded content`() {
        val input = parse(example())
        (input["items"][0] as ObjectNode).set<ArrayNode>("tags", input.arrayNode().add("private-note"))
        val result = snapshot(input.toString().toByteArray())
        val retained = result.json
        (input["items"] as ArrayNode).removeAll()
        assertEquals(29, result.items.size)
        assertEquals(retained, result.json)
        assertThrows(UnsupportedOperationException::class.java) { (result.items as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.items[0].tags as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.items[0].provenance as MutableList).clear() }
        assertEquals("FrozenCatalogSnapshot", result.toString())
        assertEquals("FrozenCatalogItem", result.items[0].toString())
        assertEquals("FrozenCatalogProvenance", result.items[0].provenance[0].toString())
        assertFalse(FrozenCatalogDecoding.Decoded(result).toString().contains("private-note"))
    }

    @Test fun `both entry points retain bounded malformed input rejection and caller stream ownership`() {
        val readers: List<(InputStream) -> FrozenCatalogDecoding<*>> = listOf(decoder::decodeItem, decoder::decodeSnapshot)
        val valid = listOf(firstItem().toString().toByteArray(), example())
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        for ((index, read) in readers.withIndex()) {
            for (bytes in listOf(valid[index], "{}".toByteArray(), valid[index] + "{}".toByteArray())) {
                val stream = Tracked(bytes)
                read(stream)
                assertFalse(stream.closed)
            }
            assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.INVALID_JSON),
                read("{\"schema_version\":\"1.0.0\",\"schema_version\":\"1.0.0\"}".byteInputStream()))
            assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.TRAILING_CONTENT), read((valid[index] + "{}".toByteArray()).inputStream()))
            assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.UNSUPPORTED_ROOT), read("[]".byteInputStream()))
            assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.INVALID_UTF8), read(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d).inputStream()))
            assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.LIMIT_EXCEEDED), read(ByteArray(1_048_577) { 32 }.inputStream()))
            val failure = read(object : InputStream() { override fun read(): Int = throw IOException("private provider content") })
            assertEquals(FrozenCatalogDecoding.InvalidInput(JsonRejection.IO_FAILURE), failure)
            assertFalse(failure.toString().contains("private provider"))
        }
    }
}
