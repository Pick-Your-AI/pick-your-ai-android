package ai.pickyour.core.preview

import ai.pickyour.core.evidence.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class PreviewCatalogDetailsTest {
    private val mapper = ObjectMapper()
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json")).use { it.readBytes() }
    private fun root() = mapper.readTree(fixture()) as ObjectNode
    private fun item(root: ObjectNode, kind: String) = root["items"].single { it["kind"].textValue() == kind } as ObjectNode
    private fun read(root: ObjectNode) = requireNotNull(PreviewCatalogBrowser.read(mapper.writeValueAsBytes(root)))
    private fun detail(browser: PreviewCatalogBrowser, kind: String): PreviewCatalogDetails {
        val selected = browser.items.single { it.kind == kind }
        return requireNotNull(browser.details(selected, browser.hash))
    }
    private fun PreviewCatalogDetails.spec(name: String) = specs.single { it.name == name }

    @Test fun `all frozen kinds expose exact selected identity dates and typed fields`() {
        val browser = requireNotNull(PreviewCatalogBrowser.read(fixture()))
        assertEquals(29, browser.items.size)
        for (selected in browser.items) {
            val detail = requireNotNull(browser.details(selected, browser.hash))
            assertEquals(selected, detail.item)
            assertEquals(browser.hash, detail.snapshotHash)
            assertEquals("catalog.fixture.1", detail.snapshotId)
            assertEquals(BigInteger.ONE, detail.snapshotRevision)
            assertEquals("2026-10-16T09:00:00Z", detail.snapshotCapturedAt)
            assertEquals("2026-10-16T09:00:00Z", detail.updatedAt)
            assertTrue(detail.specs.isNotEmpty())
            assertTrue(detail.specs.all { it.type in setOf("string", "integer", "number", "boolean", "array") })
            assertEquals(3, detail.license.size)
            assertEquals(EvidenceLabel.USER_SUPPLIED, detail.provenance.single().label)
            assertNull(detail.provenance.single().sourceUri)
        }
        assertEquals("\"cat.model_provider.fixture\"", detail(browser, "model").spec("provider_ref").valueJson)
        assertFalse(detail(browser, "model_provider").specs.any { it.name.contains("offering") })
    }

    @Test fun `same ID different versions never substitute details or source dates`() {
        val root = root()
        val older = item(root, "model")
        older.put("status", "retired").put("description", "withdrawn fixture")
        val newer = older.deepCopy().put("version", "successor").put("name", "New fixture name")
            .put("status", "active").put("updated_at", "2026-10-17T09:00:00Z")
        (root["items"] as com.fasterxml.jackson.databind.node.ArrayNode).add(newer)
        val browser = read(root)
        val selected = browser.items.single { it.kind == "model" && it.version == "2026.10-demo" }
        val detail = requireNotNull(browser.details(selected, browser.hash))
        assertEquals("retired", detail.status)
        assertEquals("withdrawn fixture", detail.description)
        assertEquals("2026-10-16T09:00:00Z", detail.updatedAt)
        assertNull(browser.details(selected, "a".repeat(64)))
        assertNull(browser.details(selected.copy(version = "missing historical version"), browser.hash))
        assertNull(browser.details(selected.copy(name = "forged label"), browser.hash))
        assertNull(PreviewCatalogBrowser(browser.hash, browser.items).details(selected, browser.hash))
    }

    @Test fun `missing fields false zero unknown and incomplete collections stay distinct`() {
        val root = root()
        val provider = item(root, "model_provider")["spec"] as ObjectNode
        provider.put("supports_fine_tuning", false)
        provider.putArray("supported_regions")
        val workload = item(root, "workload")["spec"] as ObjectNode
        workload.put("requests_per_day", 0)
        val compute = item(root, "compute_profile")["spec"] as ObjectNode
        compute.put("region", "unknown")
        val browser = read(root)
        val inspected = detail(browser, "model_provider")
        assertEquals(PreviewCatalogValueState.MISSING, inspected.spec("api_base_url").state)
        assertNull(inspected.spec("api_base_url").valueJson)
        assertEquals("false", inspected.spec("supports_fine_tuning").valueJson)
        assertEquals(PreviewCatalogValueState.SUPPLIED, inspected.spec("supports_fine_tuning").state)
        assertEquals(PreviewCatalogValueState.EMPTY_COLLECTION, inspected.spec("supported_regions").state)
        assertEquals("[]", inspected.spec("supported_regions").valueJson)
        assertEquals("0", detail(browser, "workload").spec("requests_per_day").valueJson)
        assertEquals(PreviewCatalogValueState.EXPLICIT_UNKNOWN, detail(browser, "compute_profile").spec("region").state)
        provider.putArray("supported_regions").add("declared-region").add("unknown")
        val mixed = detail(read(root), "model_provider").spec("supported_regions")
        assertEquals(PreviewCatalogValueState.CONTAINS_UNKNOWN, mixed.state)
        assertEquals("[\"declared-region\",\"unknown\"]", mixed.valueJson)
    }

    @Test fun `restrictive and absent license conditions are preserved without a reuse verdict`() {
        val root = root()
        val model = item(root, "model")
        val license = model["license"] as ObjectNode
        license.put("spdx_id", "restricted-fixture").put("commercial_use_allowed", false)
        license.remove("self_hosting_allowed")
        model.put("billing_class", "unknown")
        val detail = detail(read(root), "model")
        assertEquals("unknown", detail.billingClass)
        assertEquals("\"restricted-fixture\"", detail.license.single { it.name == "spdx_id" }.valueJson)
        assertEquals("false", detail.license.single { it.name == "commercial_use_allowed" }.valueJson)
        assertEquals(PreviewCatalogValueState.MISSING, detail.license.single { it.name == "self_hosting_allowed" }.state)
        assertTrue(detail(read(root), "workload").license.all { it.state == PreviewCatalogValueState.MISSING })
    }

    @Test fun `every provenance entry keeps raw status source date and conservative label`() {
        val root = root()
        val provenance = item(root, "model").putArray("provenance")
        FrozenVerificationStatus.entries.forEachIndexed { index, status ->
            provenance.addObject().put("verification_status", status.encoded).put("checked_at", "2026-10-1${index + 1}T00:00:00+00:00")
                .put("source_uri", "https://example.invalid/fixture-$index").put("notes", "conflicting fixture $index")
        }
        val detail = detail(read(root), "model")
        assertEquals(4, detail.provenance.size)
        detail.provenance.forEachIndexed { index, source ->
            assertEquals(FrozenVerificationStatus.entries[index], source.rawStatus)
            assertEquals("https://example.invalid/fixture-$index", source.sourceUri)
            assertEquals("2026-10-1${index + 1}T00:00:00+00:00", source.checkedAt)
            val identity = EvidenceIdentity(detail.item.id, detail.item.version, detail.snapshotId, 1)
            val policy = EvidencePresentationPolicy.present(EvidenceClaim(identity, source.rawStatus, FactClass.VERSION_PINNED))
            assertEquals(policy.label, source.label)
            assertNotEquals(EvidenceLabel.INDEPENDENTLY_VERIFIED, source.label)
            assertEquals("conflicting fixture $index", source.notes)
        }
        assertEquals(EvidenceLabel.VERIFICATION_UNESTABLISHED, detail.provenance.first().label)
    }

    @Test fun `wide snapshot revisions and numeric specifications are never narrowed`() {
        val root = root()
        val huge = BigInteger("9".repeat(100))
        root.put("revision", huge)
        (item(root, "workload")["spec"] as ObjectNode).put("requests_per_day", huge)
        (item(root, "accelerator")["spec"] as ObjectNode).put("vram_gb", BigDecimal("123456789012345678901234567890.125"))
        val browser = read(root)
        assertEquals(huge, detail(browser, "workload").snapshotRevision)
        assertEquals(huge.toString(), detail(browser, "workload").spec("requests_per_day").valueJson)
        assertEquals("123456789012345678901234567890.125", detail(browser, "accelerator").spec("vram_gb").valueJson)
    }

    @Test fun `projected collections cannot mutate the accepted snapshot and diagnostic text is redacted`() {
        val detail = detail(read(root()), "model")
        assertThrows(UnsupportedOperationException::class.java) { (detail.specs as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (detail.license as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (detail.provenance as MutableList).clear() }
        assertEquals("PreviewCatalogDetails", detail.toString())
        assertEquals("PreviewCatalogField", detail.specs.first().toString())
        assertEquals("PreviewCatalogProvenance", detail.provenance.first().toString())
    }
}
