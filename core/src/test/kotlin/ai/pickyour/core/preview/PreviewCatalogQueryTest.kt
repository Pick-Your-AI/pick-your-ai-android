package ai.pickyour.core.preview

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale

class PreviewCatalogQueryTest {
    private fun query(text: String) = PreviewCatalogQuery(text)
    private fun matches(text: String) = query(text).matches("fixture.model.A", "Example Model", "model", "1.2.0")

    @Test fun `empty and whitespace queries retain every sample`() {
        assertTrue(matches(""))
        assertTrue(matches(" \t\n "))
    }

    @Test fun `literal search matches each displayed identity field case insensitively`() {
        assertTrue(matches("FIXTURE.MODEL.A"))
        assertTrue(matches(" example model "))
        assertTrue(query("accelerator").matches("opaque", "GPU", "accelerator", "1"))
        assertTrue(matches("1.2.0"))
        assertFalse(matches("missing"))
        assertFalse(matches("model 1.2.0"))
    }

    @Test fun `query punctuation is literal and never a regex or wildcard`() {
        for (text in listOf(".*", "[model]", "*", "model|gpu", "\\p{L}")) assertFalse(matches(text))
        assertTrue(query("[GPU]").matches("id", "Example [gpu]", "accelerator", "1"))
    }

    @Test fun `matching is independent of device locale and retains non-ASCII text`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(query("fixture").matches("FIXTURE", "", "", ""))
            assertTrue(query("énergie").matches("id", "ÉNERGIE", "model", "1"))
        } finally { Locale.setDefault(before) }
    }

    @Test fun `query length is bounded and diagnostics do not disclose the query`() {
        val text = "private".repeat(17) + "x"
        assertEquals(PreviewCatalogQuery.MAX_LENGTH, text.length)
        assertEquals(text, query(text).text)
        assertEquals("PreviewCatalogQuery", query(text).toString())
        assertThrows(IllegalArgumentException::class.java) { query(text + "x") }
    }

    @Test fun `each frozen canonical kind remains reachable by its exact sample ID`() {
        val catalog = javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json")!!.use {
            ObjectMapper().readTree(it)["items"].toList()
        }
        assertEquals(29, catalog.size)
        assertEquals(29, catalog.map { it["kind"].textValue() }.toSet().size)
        for (item in catalog) {
            val q = query(item["id"].textValue())
            val found = catalog.filter { q.matches(it["id"].textValue(), it["name"].textValue(),
                it["kind"].textValue(), it["version"].textValue()) }
            assertEquals(listOf(item), found)
        }
    }
}
