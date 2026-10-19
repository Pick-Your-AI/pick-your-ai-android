package ai.pickyour.core.pricing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class RateScreenTest {
    private val mapper = ObjectMapper()
    private val screen = RateScreen()
    private fun book(): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/pricebook.json").use {
        (mapper.readTree(requireNotNull(it)) as ObjectNode).also { b ->
            (b["rates"][0] as ObjectNode).put("price_type", "list")
        }
    }
    private fun query(
        item: String = "cat.compute_profile.fixture", version: String = "2026.10-demo", meter: String = "gpu_hour", region: String = "us-east-fixture",
        currency: String = "USD", time: String = "2026-10-16T09:00:00Z",
    ) = RateQuery(item, version, meter, region, currency, Instant.parse(time))
    private fun result(b: ObjectNode, q: RateQuery = query()) = screen.screen(b.toString().byteInputStream(), q)
    private fun candidates(b: ObjectNode, q: RateQuery = query()) =
        (result(b, q) as RateScreenResult.Screened).candidates

    @Test fun `candidate preserves pricebook revision and always requires evidence review`() {
        val b = book().put("revision", 12)
        val result = candidates(b)
        assertEquals(listOf("rate.compute_profile.fixture"), result.rateIds)
        assertEquals("prices.fixture.1", result.pricebookId)
        assertEquals("12", result.pricebookRevision)
        assertEquals(query().asOf, result.asOf)
        assertEquals(setOf(RateGap.EVIDENCE_REVIEW_REQUIRED), result.gaps)
        assertThrows(UnsupportedOperationException::class.java) { (result.rateIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.gaps as MutableSet).clear() }
    }

    @Test fun `exact version meter region and currency cannot silently fall back`() {
        for (q in listOf(query(item="cat.other"), query(version="other"), query(meter="egress"), query(region="other"), query(currency="EUR"))) {
            val c = candidates(book(), q)
            assertTrue(c.rateIds.isEmpty())
            assertTrue(RateGap.NO_MATCH in c.gaps)
        }
    }

    @Test fun `explicit start inclusive and conservative end exclusive including offset equivalence`() {
        val b = book()
        (b["rates"][0] as ObjectNode).put("valid_from", "2026-10-16T10:00:00+01:00")
            .put("valid_until", "2026-10-16T10:00:00Z")
        for ((time, count) in listOf("2026-10-16T08:59:59Z" to 0, "2026-10-16T09:00:00Z" to 1,
            "2026-10-16T09:59:59Z" to 1, "2026-10-16T10:00:00Z" to 0)) {
            assertEquals(count, candidates(b, query(time=time)).rateIds.size)
        }
    }

    @Test fun `missing region conditions and estimate prices remain unresolved`() {
        val mutations: List<Pair<RateGap, (ObjectNode) -> Unit>> = listOf(
            RateGap.REGION_UNSPECIFIED to { it.remove("region"); Unit },
            RateGap.CONDITIONS_UNRESOLVED to { it.putArray("conditions").add("eligible customers only"); Unit },
            RateGap.NON_LIST_PRICE to { it.put("price_type", "estimate"); Unit },
        )
        for ((gap, mutate) in mutations) {
            val b = book(); mutate(b["rates"][0] as ObjectNode)
            val c = candidates(b)
            assertTrue(c.rateIds.isEmpty()); assertTrue(gap in c.gaps)
        }
    }

    @Test fun `alternatives remain ambiguous and duplicate IDs reject document`() {
        val b = book()
        val rate = (b["rates"][0] as ObjectNode).deepCopy().put("id", "rate.alternative")
        (b["rates"] as com.fasterxml.jackson.databind.node.ArrayNode).add(rate)
        assertEquals(2, candidates(b).rateIds.size)
        assertTrue(RateGap.AMBIGUOUS in candidates(b).gaps)
        rate.put("id", "rate.compute_profile.fixture")
        assertEquals(RateScreenResult.InvalidDocument, result(b))
    }

    @Test fun `query validation date precision and IO failure fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { query(item=" ") }
        assertThrows(IllegalArgumentException::class.java) { query(currency="usd") }
        val b = book()
        (b["rates"][0] as ObjectNode).put("valid_until", "2026-10-16T09:00:00Z")
        assertEquals(RateScreenResult.InvalidDocument, result(b))
        (b["rates"][0] as ObjectNode).remove("valid_until")
        (b["rates"][0] as ObjectNode).put("valid_from", "2026-10-16T09:00:00.1234567891Z")
        assertEquals(RateScreenResult.InvalidDocument, result(b))
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("private path")
        }
        assertEquals(RateScreenResult.InvalidDocument, screen.screen(broken, query()))
        assertEquals(RateScreenResult.InvalidDocument, screen.screen(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), query()))
    }

    @Test fun `invalid schema reversed interval and malformed bytes fail closed without disclosure`() {
        val b = book()
        (b["rates"][0] as ObjectNode).put("valid_until", "2026-10-16T08:00:00Z")
        assertEquals(RateScreenResult.InvalidDocument, result(b))
        assertEquals(RateScreenResult.UnsupportedSchemaVersion, result(book().put("schema_version", "2.0.0")))
        assertEquals(RateScreenResult.InvalidDocument, screen.screen("{\"secret\":1,\"secret\":2}".byteInputStream(), query()))
        assertEquals("RateQuery", query().toString())
        assertEquals("RateCandidates", candidates(book()).toString())
    }
}
