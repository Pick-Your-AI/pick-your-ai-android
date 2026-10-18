package ai.pickyour.core.preview

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PreviewCatalogComparisonTest {
    private val mapper = ObjectMapper()
    private fun root() = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json")).use {
        mapper.readTree(it) as ObjectNode
    }
    private fun item(root: ObjectNode, kind: String) = root["items"].single { it["kind"].textValue() == kind } as ObjectNode
    private fun read(root: ObjectNode) = requireNotNull(PreviewCatalogBrowser.read(mapper.writeValueAsBytes(root)))
    private fun nextVersion(root: ObjectNode, kind: String): ObjectNode = item(root, kind).deepCopy()
        .put("version", "successor").also { (root["items"] as ArrayNode).add(it) }
    private fun details(browser: PreviewCatalogBrowser, kind: String) = browser.items.filter { it.kind == kind }
        .map { requireNotNull(browser.details(it, browser.hash)) }
    private fun compare(browser: PreviewCatalogBrowser, kind: String) = details(browser, kind).let { PreviewCatalogComparison(it[0], it[1]) }

    @Test fun `tray keeps exact versions with identical names and explicit ordered limit and removal`() {
        val root = root()
        nextVersion(root, "model")
        val browser = read(root)
        val models = browser.items.filter { it.kind == "model" }
        assertEquals(models[0].name, models[1].name)
        val first = PreviewCatalogTray().add(browser, models[0], browser.hash)
        assertEquals(PreviewCatalogTrayResult.ADDED, first.result)
        val same = first.tray.add(browser, models[0], browser.hash)
        assertEquals(PreviewCatalogTrayResult.ALREADY_PRESENT, same.result)
        assertSame(first.tray, same.tray)
        val second = first.tray.add(browser, models[1], browser.hash)
        assertEquals(PreviewCatalogTrayResult.ADDED, second.result)
        assertEquals(listOf("2026.10-demo", "successor"), second.tray.pins.map { it.version })
        val full = second.tray.add(browser, browser.items.single { it.kind == "workload" }, browser.hash)
        assertEquals(PreviewCatalogTrayResult.FULL, full.result)
        assertSame(second.tray, full.tray)
        val remaining = full.tray.remove(full.tray.pins[0])
        assertEquals(listOf("successor"), remaining.pins.map { it.version })
        assertEquals(2, full.tray.pins.size)
    }

    @Test fun `add refuses stale snapshots forged rows and unsupported identity without changing tray`() {
        val root = root()
        val browser = read(root)
        val model = browser.items.single { it.kind == "model" }
        val tray = PreviewCatalogTray()
        assertEquals(PreviewCatalogTrayResult.UNAVAILABLE, tray.add(browser, model, "a".repeat(64)).result)
        assertEquals(PreviewCatalogTrayResult.UNAVAILABLE, tray.add(browser, model.copy(name = "forged"), browser.hash).result)
        assertEquals(PreviewCatalogTrayResult.UNAVAILABLE,
            tray.add(PreviewCatalogBrowser(browser.hash, browser.items), model, browser.hash).result)
        item(root, "model").put("version", "x".repeat(129))
        val large = read(root)
        val rejected = tray.add(large, large.items.single { it.kind == "model" }, large.hash)
        assertEquals(PreviewCatalogTrayResult.IDENTITY_TOO_LARGE, rejected.result)
        assertSame(tray, rejected.tray)
        assertTrue(rejected.tray.pins.isEmpty())
    }

    @Test fun `snapshot replacement cannot substitute a pin and exact recovery does not change its identity`() {
        val root = root()
        val original = read(root)
        val item = original.items.single { it.kind == "model" }
        val tray = PreviewCatalogTray().add(original, item, original.hash).tray
        val pin = tray.pins.single()
        assertEquals(item, tray.entries(original).single().detail?.item)
        root.put("revision", 2)
        val refreshed = read(root)
        assertEquals(item, refreshed.items.single { it.kind == "model" })
        assertNull(tray.entries(refreshed).single().detail)
        assertNull(tray.entries(null).single().detail)
        assertEquals(pin, tray.entries(refreshed).single().pin)
        assertEquals(item, tray.entries(original).single().detail?.item)
        assertTrue(tray.remove(pin).pins.isEmpty())
    }

    @Test fun `numeric spelling is compared without narrowing precision or converting units or conditions`() {
        val root = root()
        val older = item(root, "model")["spec"] as ObjectNode
        val newer = nextVersion(root, "model")["spec"] as ObjectNode
        older.put("min_weights_vram_gb", BigDecimal("123456789012345678901234567890.125"))
        newer.put("min_weights_vram_gb", BigDecimal("123456789012345678901234567890.1250"))
        older.putArray("supported_precisions").add("fp16")
        newer.putArray("supported_precisions").add("int8")
        older.put("context_window_tokens", 8192)
        newer.put("context_window_tokens", 16384)
        val comparison = compare(read(root), "model")
        val number = comparison.rows.single { it.name == "min_weights_vram_gb" }
        assertEquals(PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE, number.match)
        assertEquals("123456789012345678901234567890.125", number.left?.valueJson)
        assertEquals("123456789012345678901234567890.1250", number.right?.valueJson)
        for (name in listOf("supported_precisions", "context_window_tokens"))
            assertEquals(PreviewCatalogComparisonMatch.DIFFERENT_RECORDED_VALUE, comparison.rows.single { it.name == name }.match)
        assertTrue(comparison.visibleRows(true).any { it.name == "supported_precisions" })
        assertTrue(comparison.visibleRows(true).any { it.name == "context_window_tokens" })
        assertFalse(comparison.rows.any { it.name.contains("price") || it.name.contains("cheaper") })
    }

    @Test fun `missing unknown empty false and zero keep distinct states and unknown rows never disappear`() {
        val root = root()
        val original = item(root, "model_provider")["spec"] as ObjectNode
        original.put("supports_fine_tuning", false).putArray("supported_regions")
        nextVersion(root, "model_provider")
        val comparison = compare(read(root), "model_provider")
        val absent = comparison.rows.single { it.name == "api_base_url" }
        val empty = comparison.rows.single { it.name == "supported_regions" }
        val disabled = comparison.rows.single { it.name == "supports_fine_tuning" }
        assertEquals(PreviewCatalogValueState.MISSING, absent.left?.state)
        assertEquals(PreviewCatalogValueState.EMPTY_COLLECTION, empty.left?.state)
        assertEquals(PreviewCatalogComparisonMatch.UNKNOWN, absent.match)
        assertEquals(PreviewCatalogComparisonMatch.UNKNOWN, empty.match)
        assertEquals("false", disabled.left?.valueJson)
        assertEquals(PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE, disabled.match)
        assertTrue(comparison.visibleRows(true).containsAll(listOf(absent, empty)))
        assertFalse(disabled in comparison.visibleRows(true))

        val computeRoot = root()
        (item(computeRoot, "compute_profile")["spec"] as ObjectNode).put("region", "unknown").put("ram_gb", 0)
        nextVersion(computeRoot, "compute_profile")
        val compute = compare(read(computeRoot), "compute_profile")
        val region = compute.rows.single { it.name == "region" }
        assertEquals(PreviewCatalogValueState.EXPLICIT_UNKNOWN, region.left?.state)
        assertEquals(PreviewCatalogComparisonMatch.UNKNOWN, region.match)
        assertTrue(region in compute.visibleRows(true))
        val zero = compute.rows.single { it.name == "ram_gb" }
        assertEquals("0", zero.left?.valueJson)
        assertEquals(PreviewCatalogValueState.SUPPLIED, zero.left?.state)
        assertEquals(PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE, zero.match)
    }

    @Test fun `different kinds never normalize shared labels or license booleans into equivalence`() {
        val browser = read(root())
        val comparison = PreviewCatalogComparison(details(browser, "model").single(), details(browser, "accelerator").single())
        assertFalse(comparison.sameKind)
        assertTrue(comparison.rows.all { it.match == PreviewCatalogComparisonMatch.NOT_COMPARABLE })
        assertEquals(comparison.rows, comparison.visibleRows(true))
        assertTrue(comparison.rows.any { it.left == null })
        assertTrue(comparison.rows.any { it.right == null })
        assertEquals(comparison.left.item.version, comparison.right.item.version)
    }

    @Test fun `tray state and comparison rows are immutable and malformed restoration is rejected`() {
        val root = root()
        nextVersion(root, "model")
        val browser = read(root)
        val first = details(browser, "model")[0]
        val pin = PreviewCatalogPin(first.item.id, first.item.version, first.snapshotHash)
        val mutable = mutableListOf(pin)
        val tray = PreviewCatalogTray(mutable)
        mutable.clear()
        assertEquals(listOf(pin), tray.pins)
        assertThrows(UnsupportedOperationException::class.java) { (tray.pins as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (tray.entries(browser) as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogTray(listOf(pin, pin)) }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogTray(List(3) { pin.copy(version = "$it") }) }
        assertThrows(IllegalArgumentException::class.java) { pin.copy(snapshotHash = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { pin.copy(version = "x".repeat(129)) }
        val comparison = compare(browser, "model")
        assertThrows(UnsupportedOperationException::class.java) { (comparison.rows as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (comparison.visibleRows(true) as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogComparison(first, first) }
    }
}
