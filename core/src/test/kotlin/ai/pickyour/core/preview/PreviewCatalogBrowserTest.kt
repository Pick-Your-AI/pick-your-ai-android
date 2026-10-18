package ai.pickyour.core.preview

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale

class PreviewCatalogBrowserTest {
    private val hash = "a".repeat(64)
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json")).use { it.readBytes() }
    private fun browser() = requireNotNull(PreviewCatalogBrowser.read(fixture()))

    @Test fun `bundled registry exposes all canonical kinds and seven tiers without changing frozen input`() {
        val catalog = browser()
        assertEquals(29, PreviewCatalogKinds.all.size)
        assertEquals((0..6).map { "T$it" }, PreviewCatalogKinds.tiers)
        PreviewCatalogKinds.all.forEach { kind ->
            val result = catalog.browse(PreviewCatalogBrowseState(tier = kind.tier, kind = kind.id))
            assertEquals(1, result.total, kind.id)
            assertEquals(kind.id, result.items.single().kind)
        }
    }

    @Test fun `pagination has stable unique identities and a bounded visible window`() {
        val catalog = browser()
        val first = catalog.browse(PreviewCatalogBrowseState())
        assertEquals(29, first.total)
        assertEquals(3, first.pages)
        val pages = (0 until first.pages).map { catalog.browse(PreviewCatalogBrowseState(page = it)) }
        assertEquals(listOf(10, 10, 9), pages.map { it.items.size })
        assertEquals(catalog.items.map { it.id }.toSet(), pages.flatMap { it.items }.map { it.id }.toSet())
        assertEquals(29, pages.flatMap { it.items }.map { it.id }.distinct().size)
        assertEquals(2, catalog.browse(PreviewCatalogBrowseState(page = 25)).page)
    }

    @Test fun `canonical literal query semantics and locale independence are retained`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val result = browser().browse(PreviewCatalogBrowseState(query = "  FICTIONAL INFERENCE "))
            assertEquals("inference_engine", result.items.single().kind)
            assertEquals("model", browser().browse(PreviewCatalogBrowseState(query = "CAT.MODEL.FIXTURE")).items.single().kind)
            assertEquals(0, browser().browse(PreviewCatalogBrowseState(query = "MODEL 2026.10-DEMO")).total)
            assertEquals(29, browser().browse(PreviewCatalogBrowseState(query = " \t ")).total)
            assertEquals(0, browser().browse(PreviewCatalogBrowseState(query = ".*")).total)
        } finally { Locale.setDefault(original) }
    }

    @Test fun `combined facets preserve empty categories and query filtering clamps pages`() {
        val catalog = browser()
        val empty = catalog.browse(PreviewCatalogBrowseState(tier = "T0", kind = "model", page = 2))
        assertEquals(0, empty.total)
        assertEquals(0, empty.page)
        assertTrue(empty.items.isEmpty())
        assertEquals(1, empty.pages)
        assertEquals(0, catalog.browse(PreviewCatalogBrowseState(query = "workload", page = 2)).page)
        val missingKind = PreviewCatalogBrowser(hash, catalog.items.filter { it.kind != "model" })
        assertTrue(missingKind.browse(PreviewCatalogBrowseState(kind = "model")).items.isEmpty())
        assertTrue(PreviewCatalogKinds.all.any { it.id == "model" })
    }

    @Test fun `duplicate labels sort deterministically by identity and kind ordering respects tiers`() {
        val entries = (1..21).map { PreviewCatalogItem("id${it.toString().padStart(2, '0')}", "Same label", "model", "v1", "T1") }
        val forward = PreviewCatalogBrowser(hash, entries)
        val reverse = PreviewCatalogBrowser(hash, entries.reversed())
        for (page in 0..2) assertEquals(forward.browse(PreviewCatalogBrowseState(sort = PreviewCatalogSort.NAME, page = page)),
            reverse.browse(PreviewCatalogBrowseState(sort = PreviewCatalogSort.NAME, page = page)))
        assertEquals(entries.take(10), forward.browse(PreviewCatalogBrowseState()).items)
        val ordered = (0..2).flatMap { browser().browse(PreviewCatalogBrowseState(sort = PreviewCatalogSort.KIND, page = it)).items }
        assertEquals(ordered.map { it.tier }.sorted(), ordered.map { it.tier })
    }

    @Test fun `snapshot membership requires exact hash identity and version`() {
        val catalog = browser()
        val item = catalog.items.first()
        assertTrue(catalog.contains(item, catalog.hash))
        assertFalse(catalog.contains(item, hash))
        assertFalse(catalog.contains(item.copy(version = "unrequested successor"), catalog.hash))
        assertFalse(catalog.contains(item.copy(id = "missing"), catalog.hash))
    }

    @Test fun `result and input collections cannot mutate the cached snapshot`() {
        val entries = browser().items.toMutableList()
        val catalog = PreviewCatalogBrowser(hash, entries)
        entries.clear()
        assertEquals(29, catalog.items.size)
        assertThrows(UnsupportedOperationException::class.java) { (catalog.items as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.browse(PreviewCatalogBrowseState()).items as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (PreviewCatalogKinds.all as MutableList).clear() }
    }

    @Test fun `same ID at distinct versions stays reachable while repeated exact pairs reject`() {
        val old = browser().items.first()
        val newer = old.copy(version = "next-version")
        val catalog = PreviewCatalogBrowser(hash, listOf(old, newer))
        assertEquals(listOf(old, newer), catalog.browse(PreviewCatalogBrowseState(query = old.id)).items)
        assertTrue(catalog.contains(old, hash))
        assertTrue(catalog.contains(newer, hash))
        assertEquals(listOf(newer), catalog.browse(PreviewCatalogBrowseState(query = "next-version")).items)
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowser(hash, listOf(old, old.copy(name = "conflicting label"))) }
    }

    @Test fun `ambiguous pairs unsupported facets and excessive input fail closed`() {
        val item = browser().items.first()
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowser(hash, listOf(item, item.copy(name = "other"))) }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowser(hash, listOf(item.copy(tier = "T6"))) }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowseState(kind = "invented") }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowseState(tier = "T7") }
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowseState(query = "x".repeat(121)) }
        assertEquals("PreviewCatalogBrowseState", PreviewCatalogBrowseState(query = "private words").toString())
        assertThrows(IllegalArgumentException::class.java) { PreviewCatalogBrowseState(page = -1) }
        assertNull(PreviewCatalogBrowser.read(ByteArray(1_048_577)))
        assertNull(PreviewCatalogBrowser.read("{}".toByteArray()))
        assertNull(PreviewCatalogBrowser.read(fixture().toString(Charsets.UTF_8)
            .replace("cat.deployment_mode.fixture", "cat.workload.fixture").toByteArray()))
    }
}
