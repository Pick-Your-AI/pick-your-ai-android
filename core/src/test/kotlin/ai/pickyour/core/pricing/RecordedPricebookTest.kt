package ai.pickyour.core.pricing

import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.evidence.FrozenVerificationStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant

class RecordedPricebookTest {
    private val mapper = ObjectMapper()
    private fun bytes(): ByteArray = javaClass.getResourceAsStream("/contracts/v1/examples/pricebook.json").use {
        requireNotNull(it).readBytes()
    }
    private fun json() = mapper.readTree(bytes()) as ObjectNode
    private fun read(bytes: ByteArray) = (RecordedPricebook.read(bytes.inputStream()) as RecordedPricebookResult.Parsed).pricebook
    private fun read(json: ObjectNode) = read(json.toString().toByteArray(Charsets.UTF_8))
    private fun first(book: ObjectNode) = book["rates"][0] as ObjectNode
    private fun query(time: String = "2026-10-16T09:00:00Z", region: String = "us-east-fixture", currency: String = "USD") =
        RateQuery("cat.compute_profile.fixture", "2026.10-demo", "gpu_hour", region, currency, Instant.parse(time))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `exact fixture metadata retains pin decimal spelling and absent fields`() {
        val input = bytes()
        val book = read(input)
        assertEquals("prices.fixture.1", book.id)
        assertEquals(BigInteger.ONE, book.revision)
        assertEquals("2026-10-16T09:00:00Z", book.capturedAt)
        assertEquals(hash(input), book.hash)
        assertEquals(2, book.rates.size)
        val rate = book.ratesFor("cat.compute_profile.fixture", "2026.10-demo").single()
        assertSame(book.rates[0], rate)
        assertEquals("rate.compute_profile.fixture", rate.id)
        assertEquals("gpu_hour", rate.meter)
        assertEquals("0.80", rate.unitPrice.amount.encoded)
        assertEquals("USD", rate.unitPrice.currency)
        assertEquals("us-east-fixture", rate.region)
        assertEquals("estimate", rate.priceType)
        assertEquals("2026-10-16T09:00:00Z", rate.validFrom)
        assertNull(rate.validUntil)
        assertNull(rate.conditions)
        assertNull(rate.provenance.sourceUri)
        assertEquals("2026-10-16T09:00:00Z", rate.provenance.checkedAt)
        assertEquals(EvidenceLabel.ESTIMATED, rate.provenance.label)
        assertEquals("0.05", book.ratesFor("cat.storage.fixture", "2026.10-demo").single().unitPrice.amount.encoded)
    }

    @Test fun `identity lookup retains every alternative without item or latest version fallback`() {
        val input = json()
        val rates = input["rates"] as ArrayNode
        repeat(10) { index -> rates.add(first(input).deepCopy().put("id", "rate.alternative.$index")) }
        rates.add(first(input).deepCopy().put("id", "rate.newer").put("catalog_item_version", "newer"))
        val book = read(input)
        val exact = book.ratesFor("cat.compute_profile.fixture", "2026.10-demo")
        assertEquals(11, exact.size)
        assertEquals("rate.alternative.9", exact.last().id)
        assertEquals(listOf("rate.newer"), book.ratesFor("cat.compute_profile.fixture", "newer").map { it.id })
        assertTrue(book.ratesFor("cat.compute_profile.fixture", "missing").isEmpty())
        assertTrue(book.ratesFor("cat.missing", "2026.10-demo").isEmpty())
        assertTrue(book.ratesFor("CAT.COMPUTE_PROFILE.FIXTURE", "2026.10-demo").isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (book.rates as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (exact as MutableList).clear() }
    }

    @Test fun `wide revisions exact decimal lexemes and raw date offsets survive private parsing`() {
        val input = json().put("revision", BigInteger("123456789012345678901234567890"))
        val rate = first(input)
        val amount = "123456789012345678901234567890.000000000001"
        (rate["unit_price"] as ObjectNode).put("amount", amount).put("currency", "JPY")
        rate.put("valid_from", "2026-10-16T10:00:00+01:00").put("valid_until", "2026-10-17T10:00:00+01:00")
        val raw = input.toString().toByteArray(Charsets.UTF_8)
        val book = read(raw)
        raw.fill(0)
        rate.put("region", "changed"); (rate["unit_price"] as ObjectNode).put("amount", "0")
        assertEquals(BigInteger("123456789012345678901234567890"), book.revision)
        assertEquals(amount, book.rates[0].unitPrice.amount.encoded)
        assertEquals("JPY", book.rates[0].unitPrice.currency)
        assertEquals("us-east-fixture", book.rates[0].region)
        assertEquals("2026-10-16T10:00:00+01:00", book.rates[0].validFrom)
        assertEquals("2026-10-17T10:00:00+01:00", book.rates[0].validUntil)
    }

    @Test fun `conditions absence empty and literal unknown do not create eligibility`() {
        val input = json()
        assertNull(read(input).rates[0].conditions)
        first(input).putArray("conditions")
        assertEquals(emptyList<String>(), read(input).rates[0].conditions)
        first(input).putArray("conditions").add("unknown").add("").add("Region: anywhere\n\u202e")
        first(input).put("region", "unknown")
        val book = read(input)
        assertEquals(listOf("unknown", "", "Region: anywhere\n\u202e"), book.rates[0].conditions)
        assertEquals("unknown", book.rates[0].region)
        assertThrows(UnsupportedOperationException::class.java) { (book.rates[0].conditions as MutableList).clear() }
        assertTrue(RateGap.CONDITIONS_UNRESOLVED in RateScreen().screen(book, query(region = "unknown")).candidates.gaps)
    }

    @Test fun `recorded provenance never supplies independent verification or a source check`() {
        val labels = mapOf(FrozenVerificationStatus.VERIFIED to EvidenceLabel.VERIFICATION_UNESTABLISHED,
            FrozenVerificationStatus.UNVERIFIED to EvidenceLabel.UNVERIFIED,
            FrozenVerificationStatus.USER_SUPPLIED to EvidenceLabel.USER_SUPPLIED,
            FrozenVerificationStatus.ESTIMATED to EvidenceLabel.ESTIMATED)
        for ((raw, label) in labels) {
            val input = json()
            (first(input)["provenance"] as ObjectNode).put("verification_status", raw.encoded)
                .put("checked_at", "2026-11-01T10:00:00+01:00").put("source_uri", "https://example.invalid/rate")
                .put("notes", "unknown\nprivate sentinel")
            val provenance = read(input).rates[0].provenance
            assertEquals(raw, provenance.rawStatus)
            assertEquals(label, provenance.label)
            assertNotEquals(EvidenceLabel.INDEPENDENTLY_VERIFIED, provenance.label)
            assertEquals("2026-11-01T10:00:00+01:00", provenance.checkedAt)
            assertEquals("https://example.invalid/rate", provenance.sourceUri)
            assertEquals("unknown\nprivate sentinel", provenance.notes)
            assertEquals("RecordedRateProvenance", provenance.toString())
        }
    }

    @Test fun `whole document rejects duplicate IDs and unusable intervals even on unrelated items`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("captured_at", "2026-10-16T09:00:00.1234567891Z") },
            { (it["rates"][1] as ObjectNode).put("id", first(it)["id"].textValue()) },
            { (it["rates"][1] as ObjectNode).put("valid_until", "2026-10-16T09:00:00Z") },
            { (it["rates"][1] as ObjectNode).put("valid_until", "2026-10-16T08:00:00Z") },
            { (it["rates"][1] as ObjectNode).put("valid_from", "2026-10-16T09:00:00.1234567891Z") },
            { (it["rates"][1] as ObjectNode).put("valid_until", "2026-10-17T09:00:00.1234567891Z") },
            { (first(it)["unit_price"] as ObjectNode).put("amount", "1e3") },
            { (first(it)["unit_price"] as ObjectNode).put("currency", "usd") },
        )
        for (mutate in mutations) {
            val input = json().also(mutate).toString().toByteArray(Charsets.UTF_8)
            assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(input.inputStream()))
            assertEquals(RateScreenResult.InvalidDocument, RateScreen().screen(input.inputStream(), query()))
        }
    }

    @Test fun `received byte hash covers whitespace and stream ownership survives success and rejection`() {
        val original = bytes()
        val spaced = original + " \n\t".toByteArray(Charsets.UTF_8)
        for (input in listOf(original, spaced, "{\"private\":1,\"private\":2}".toByteArray(Charsets.UTF_8),
            byteArrayOf(0xc3.toByte(), 0x28), original + "{}".toByteArray(Charsets.UTF_8))) {
            var closed = false
            val stream = object : ByteArrayInputStream(input) { override fun close() { closed = true; super.close() } }
            val result = RecordedPricebook.read(stream)
            assertFalse(closed)
            if (input === original || input === spaced) {
                assertEquals(hash(input), (result as RecordedPricebookResult.Parsed).pricebook.hash)
                assertEquals(-1, stream.read())
            } else assertEquals(RecordedPricebookResult.InvalidDocument, result)
        }
        assertNotEquals(read(original).hash, read(spaced).hash)
        assertEquals(read(original).rates[0].unitPrice.amount, read(spaced).rates[0].unitPrice.amount)
    }

    @Test fun `oversized input and IO failure remain bounded and sanitized`() {
        var consumed = 0
        var closed = false
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return ' '.code }
            override fun close() { closed = true }
        }
        assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(endless))
        assertEquals(1_048_577, consumed)
        assertFalse(closed)
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("private source and path")
            override fun close() { closed = true }
        }
        assertEquals(RecordedPricebookResult.InvalidDocument, RecordedPricebook.read(broken))
        assertFalse(closed)
        val book = read(bytes())
        assertEquals("RecordedPricebook", book.toString())
        assertEquals("RecordedPriceRate", book.rates[0].toString())
        assertEquals("RecordedPricebookResult.Parsed", RecordedPricebook.read(bytes().inputStream()).toString())
    }

    @Test fun `stream and pinned object screening preserve all boundaries gaps and exact source hash`() {
        val input = json()
        first(input).put("price_type", "list").put("valid_from", "2026-10-16T10:00:00+01:00")
            .put("valid_until", "2026-10-16T10:00:00Z")
        val rates = input["rates"] as ArrayNode
        rates.add(first(input).deepCopy().put("id", "rate.alternative"))
        rates.add(first(input).deepCopy().put("id", "rate.missing_region").also { it.remove("region") })
        rates.add(first(input).deepCopy().put("id", "rate.conditional").also { it.putArray("conditions").add("unknown") })
        rates.add(first(input).deepCopy().put("id", "rate.contract").put("price_type", "contract"))
        val raw = input.toString().toByteArray(Charsets.UTF_8)
        val book = read(raw)
        val screen = RateScreen()
        for (query in listOf(query(), query("2026-10-16T08:59:59Z"), query("2026-10-16T09:59:59Z"),
            query("2026-10-16T10:00:00Z"), query(region = "other"), query(currency = "EUR"))) {
            val direct = (screen.screen(raw.inputStream(), query) as RateScreenResult.Screened).candidates
            val pinned = screen.screen(book, query).candidates
            assertEquals(direct.rateIds, pinned.rateIds)
            assertEquals(direct.gaps, pinned.gaps)
            assertEquals(hash(raw), direct.pricebookHash)
            assertEquals(book.hash, pinned.pricebookHash)
            assertEquals(book.id, pinned.pricebookId)
            assertEquals(book.revision.toString(), pinned.pricebookRevision)
            assertEquals(query.asOf, pinned.asOf)
        }
        assertEquals(setOf(RateGap.AMBIGUOUS, RateGap.EVIDENCE_REVIEW_REQUIRED, RateGap.REGION_UNSPECIFIED,
            RateGap.CONDITIONS_UNRESOLVED, RateGap.NON_LIST_PRICE), screen.screen(book, query()).candidates.gaps)
        assertEquals(setOf(RateGap.NO_MATCH, RateGap.EVIDENCE_REVIEW_REQUIRED),
            screen.screen(book, query("2026-10-16T10:00:00Z")).candidates.gaps)
    }
}
