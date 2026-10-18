package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class PreviewRevisionNumberComparisonTest {
    @TempDir lateinit var root: Path

    private fun json(raw: String): JsonNode =
        (BoundedJsonReader().read("{\"value\":$raw}".byteInputStream()) as JsonInputResult.Parsed).value["value"]

    private fun components(temperature: String, extension: String): DraftSelections {
        val node = SelectionFixtures.node()
        (node["settings"] as ObjectNode).put("temperature", BigDecimal(temperature))
        node.putObject("extensions").set<JsonNode>("x_retained", json(extension))
        val link = SelectionFixtures.link("link_retained", "sel_model", "sel_host").toJson()
        return DraftSelections(listOf(LocalSelection.fromJson(node),
            SelectionFixtures.selection("hosting_platform", "sel_host")), listOf(LocalSelectionLink.fromJson(link)))
    }

    @Test fun `durable history ignores equivalent numeric spellings without rewriting retained selections or links`() {
        val store = LocalDraftStore(root)
        val before = components("1.0", """{"n":1,"nested":[0.10,{"large":9007199254740993}]}""")
        val after = components("1.00", """{"nested":[0.1000,{"large":9007199254740993.0}],"n":1.0}""")
        val first = (store.save(null, DraftContent(objective = "before", components = before),
            mapOf("preview.format" to "1", "preview.title" to "retained")) as DraftSave.Saved).draft
        val saved = (store.save(first.version, DraftContent(objective = "after", components = after)) as DraftSave.Saved).draft
        val current = (PreviewDraftPersistence(LocalDraftStore(root)).load() as PreviewDraftLoad.Available).record
        val comparison = current.compareRevision(0)!!
        assertEquals(listOf("objective"), comparison.changes.map { it.field })
        assertEquals("1.0", current.history.first().content.components.selections.first().settings["temperature"].toString())
        assertEquals("1.00", current.components.selections.first().settings["temperature"].toString())
        assertEquals(before.links.first().toJson(), current.history.first().content.components.links.first().toJson())
        assertEquals(after.links.first().toJson(), current.components.links.first().toJson())
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.version.hash, reopened.version.hash)
        assertEquals(2, reopened.history.size)
        assertEquals(first.history.first().content, reopened.history.first().content)
    }

    private fun compareExtension(before: String, after: String): PreviewRevisionChange {
        val directory = Files.createTempDirectory(root, "comparison-")
        val store = LocalDraftStore(directory)
        val old = components("1.0", before)
        val current = components("1.0", after)
        val first = (store.save(null, DraftContent(components = old),
            mapOf("preview.format" to "1", "preview.title" to "literal")) as DraftSave.Saved).draft
        val saved = (store.save(first.version, DraftContent(components = current)) as DraftSave.Saved).draft
        val loaded = (PreviewDraftPersistence(LocalDraftStore(directory)).load() as PreviewDraftLoad.Available).record
        val change = loaded.compareRevision(0)!!.changes.single()
        assertEquals("components.selection.extensions", change.field)
        assertEquals("sel_model", change.key)
        assertEquals(old.selections.first().toJson()["extensions"].toString(), change.before)
        assertEquals(current.selections.first().toJson()["extensions"].toString(), change.after)
        assertEquals(saved.version.hash, (LocalDraftStore(directory).load() as DraftLoad.Available).draft.version.hash)
        return change
    }

    @Test fun `real numeric differences remain visible beyond binary floating point precision`() {
        for ((before, after) in listOf(
            "9007199254740992" to "9007199254740993",
            "0.100000000000000001" to "0.100000000000000002",
            "1e-400" to "2e-400",
        )) compareExtension(before, after)
    }

    @Test fun `literal decimal strings booleans array order and missing versus null remain distinct`() {
        for ((before, after) in listOf(
            "\"1.0\"" to "\"1.00\"",
            "\"1\"" to "1",
            "true" to "1",
            "null" to "0",
            "[1,2]" to "[2.0,1.0]",
            "{\"value\":null}" to "{}",
        )) compareExtension(before, after)
    }
}
