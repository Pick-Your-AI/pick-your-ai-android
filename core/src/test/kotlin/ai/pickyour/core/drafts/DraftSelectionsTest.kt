package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal object SelectionFixtures {
    val mapper = ObjectMapper()
    fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }
    fun node(kind: String = "model", id: String = "sel_model", role: String = "primary", enabled: Boolean = true): ObjectNode =
        (mapper.readTree(resource("/contracts/v1/examples/build.json"))["selections"].first { it["kind"].asText() == kind } as ObjectNode)
            .deepCopy().put("id", id).put("role", role).put("enabled", enabled)
    fun selection(kind: String = "model", id: String = "sel_model", role: String = "primary", enabled: Boolean = true) = LocalSelection.fromJson(node(kind, id, role, enabled))
    fun link(id: String, from: String, to: String, required: Boolean? = null): LocalSelectionLink = LocalSelectionLink.fromJson(
        mapper.createObjectNode().put("id", id).put("from_selection_id", from).put("to_selection_id", to).put("relation", "uses")
            .also { if (required != null) it.put("required", required) })
    fun catalogNode(): ObjectNode = mapper.readTree(resource("/contracts/v1/examples/catalog.json")) as ObjectNode
    fun catalog() = SelectionCatalogSnapshot.fromJson(resource("/contracts/v1/examples/catalog.json"))
    fun choice(catalog: SelectionCatalogSnapshot, selection: LocalSelection) = CatalogSelectionChoice(catalog.hash, selection.kind, selection.catalogItemId, selection.catalogItemVersion)
}

class DraftSelectionsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(kind: String = "model", id: String = "sel_model", role: String = "primary", enabled: Boolean = true) = SelectionFixtures.selection(kind, id, role, enabled)
    private fun add(selection: LocalSelection) = SelectionCommand.Add(selection, SelectionFixtures.choice(catalog, selection))
    private fun ready(content: DraftContent, command: SelectionCommand) = DraftSelectionCommands.apply(content, command, catalog) as SelectionEdit.Ready
    private fun saved(result: DraftSelectionSave) = (result as DraftSelectionSave.Saved).draft
    private fun initial(content: DraftContent = DraftContent()) = (LocalDraftStore(root).save(null, content, mapOf("opaque-pin" to "keep")) as DraftSave.Saved).draft

    @Test fun `all frozen kinds retain typed immutable payloads notes and nested extensions`() {
        val originals = SelectionFixtures.mapper.readTree(SelectionFixtures.resource("/contracts/v1/examples/build.json"))["selections"]
        assertEquals(29, originals.size())
        for (original in originals) {
            val node = (original as ObjectNode).deepCopy().put("notes", "")
            node.putObject("extensions").putObject("x_private").putArray("values").add(false).addNull().add(0)
            val parsed = LocalSelection.fromJson(node)
            val expected = parsed.toJson()
            node.put("role", "changed")
            (node["extensions"]["x_private"] as ObjectNode).put("changed", true)
            parsed.toJson().put("role", "changed-again")
            parsed.settings.removeAll()
            assertEquals(expected, parsed.toJson())
            assertEquals("", parsed.toJson()["notes"].textValue())
            assertEquals("LocalSelection", parsed.toString())
        }
    }

    @Test fun `schema requires explicit identity role enabled and typed kind settings`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.remove("id") }, { it.remove("role") }, { it.remove("enabled") }, { it.remove("settings") },
            { it.put("id", "x") }, { it.put("role", "Primary") }, { it.put("kind", "future_kind") },
            { it.put("catalog_item_version", "") }, { it.put("enabled", "true") }, { it.put("future", true) },
            { (it["settings"] as ObjectNode).put("temperature", 3) },
            { (it["settings"] as ObjectNode).put("temperature", "0.5") },
            { (it["settings"] as ObjectNode).put("credential_ref", "actual-password") },
            { it.putObject("extensions").put("unscoped", true) },
        )
        mutations.forEach { mutate -> assertThrows(IllegalArgumentException::class.java) { LocalSelection.fromJson(SelectionFixtures.node().also(mutate)) } }
        for (kind in listOf("accelerator", "compute_profile", "context_policy"))
            assertThrows(IllegalArgumentException::class.java) { LocalSelection.fromJson(SelectionFixtures.node(kind).also { it.putObject("settings") }) }
        val exact = SelectionFixtures.node().also { it.putObject("settings").put("credential_ref", "secret://private/reference").put("weights_uri", "https://never-resolve.invalid/model") }
        val bytes = SelectionFixtures.mapper.writeValueAsString(exact).replace("\"settings\":{", "\"settings\":{\"temperature\":0.100000000000000001,").toByteArray()
        val parsed = LocalSelection.fromJson(bytes)
        assertEquals("0.100000000000000001", parsed.settings["temperature"].toString())
        assertEquals("secret://private/reference", parsed.settings["credential_ref"].asText())
        assertThrows(IllegalArgumentException::class.java) { LocalSelection.fromJson("{\"id\":\"one\",\"id\":\"two\"}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { LocalSelection.fromJson(SelectionFixtures.node().put("notes", "x".repeat(16_385))) }
    }

    @Test fun `aggregate allows incomplete content but enforces role scope enabled cardinality and selection links`() {
        assertTrue(DraftSelections().selections.isEmpty())
        val model = selection()
        assertEquals(1, DraftSelections(listOf(model)).selections.size)
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(listOf(model, model)) }
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(listOf(model, selection(id = "other", enabled = false))) }
        assertEquals(2, DraftSelections(listOf(model, selection("hosting_platform", "hosting", "primary"))).selections.size)
        val primary = selection("context_policy", "context", "primary")
        val alternate = selection("context_policy", "alternate", "alternate", false)
        assertEquals(2, DraftSelections(listOf(primary, alternate)).selections.size)
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(listOf(primary, alternate.enabled(true))) }
        val link = SelectionFixtures.link("link.one", model.id, primary.id)
        val values = mutableListOf(model, primary)
        val links = mutableListOf(link)
        val aggregate = DraftSelections(values, links)
        values.clear(); links.clear()
        assertEquals(2, aggregate.selections.size)
        assertEquals(1, aggregate.links.size)
        assertThrows(UnsupportedOperationException::class.java) { (aggregate.selections as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(listOf(model), listOf(link)) }
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(listOf(model, primary), listOf(link, link)) }
        assertNull(link.required)
        assertEquals(false, SelectionFixtures.link("link.two", model.id, primary.id, false).required)
        assertThrows(IllegalArgumentException::class.java) { LocalSelectionLink.fromJson(link.toJson().put("role", "primary")) }
        assertThrows(IllegalArgumentException::class.java) { LocalSelectionLink.fromJson(link.toJson().put("from_selection_id", model.catalogItemId)) }
    }

    @Test fun `commands commit once preserve opaque content and never promote a secondary selection`() {
        val store = LocalDraftStore(root)
        val original = DraftContent("goal", "context", "legacy-model", mapOf(OptionalTier.T3 to TierDraft(Activation.UNKNOWN, setOf("opaque"))), DraftWorkload(region = "entered"))
        var current = initial(original)
        val values = listOf(selection(), selection(id = "fallback", role = "fallback"), selection("hosting_platform", "hosting"),
            selection("accelerator", "accelerator"), selection("accelerator", "accelerator_secondary", "secondary"),
            selection("compute_profile", "compute"), selection("inference_engine", "engine"))
        for (value in values) {
            val intent = DraftSelectionIntent(current.version, add(value))
            val previous = current
            current = saved(store.applySelection(intent, catalog))
            assertEquals(previous.version.number + 1, current.version.number)
            assertEquals(SelectionAction.ADD, current.history.last().selectionChange!!.action)
            assertEquals(DraftSelectionSave.Conflict, store.applySelection(intent, catalog))
        }
        assertEquals(original, current.content.withComponents(DraftSelections()))
        assertEquals(mapOf("opaque-pin" to "keep"), current.metadata)
        val disabled = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled("sel_model", false))))
        assertEquals("fallback", disabled.content.components.selections.first { it.id == "fallback" }.role)
        val enabled = saved(store.applySelection(DraftSelectionIntent(disabled.version, SelectionCommand.SetEnabled("sel_model", true))))
        val unchanged = store.applySelection(DraftSelectionIntent(enabled.version, SelectionCommand.SetEnabled("sel_model", true))) as DraftSelectionSave.Saved
        assertEquals(enabled.version.hash, unchanged.draft.version.hash)
        assertNull(unchanged.change)
        val removed = saved(store.applySelection(DraftSelectionIntent(enabled.version, SelectionCommand.Remove("sel_model"))))
        assertEquals("fallback", removed.content.components.selections.first { it.id == "fallback" }.role)
        assertFalse(removed.content.components.selections.any { it.kind == "model" && it.role == "primary" })
    }

    @Test fun `catalog actions require exact snapshot kind item version and never substitute latest`() {
        val base = DraftContent()
        val model = selection()
        fun rejected(choice: CatalogSelectionChoice, source: SelectionCatalogSnapshot? = catalog) =
            DraftSelectionCommands.apply(base, SelectionCommand.Add(model, choice), source) as SelectionEdit.Rejected
        val exact = SelectionFixtures.choice(catalog, model)
        assertEquals(SelectionRejection.CATALOG_PIN_MISMATCH, rejected(exact.copy(snapshotHash = ArtifactHash("a".repeat(64)))).reason)
        assertEquals(SelectionRejection.CATALOG_PIN_MISMATCH, rejected(exact, null).reason)
        assertEquals(SelectionRejection.CATALOG_PIN_MISMATCH, rejected(exact.copy(kind = "hosting_platform")).reason)
        assertEquals(SelectionRejection.CATALOG_PIN_MISMATCH, rejected(exact.copy(itemVersion = "latest")).reason)
        val missing = LocalSelection.fromJson(model.toJson().put("catalog_item_version", "unavailable-pinned-version"))
        val result = DraftSelectionCommands.apply(base, SelectionCommand.Add(missing, exact.copy(itemVersion = missing.catalogItemVersion)), catalog) as SelectionEdit.Rejected
        assertEquals(SelectionRejection.CATALOG_ITEM_UNAVAILABLE, result.reason)
        val unsupported = SelectionFixtures.catalogNode().put("schema_version", "2.0.0")
        assertThrows(IllegalArgumentException::class.java) { SelectionCatalogSnapshot.fromJson(SelectionFixtures.mapper.writeValueAsBytes(unsupported)) }
        val ambiguous = SelectionFixtures.catalogNode()
        (ambiguous["items"] as ArrayNode).add(ambiguous["items"][0].deepCopy<JsonNode>())
        assertThrows(IllegalArgumentException::class.java) { SelectionCatalogSnapshot.fromJson(SelectionFixtures.mapper.writeValueAsBytes(ambiguous)) }
    }

    @Test fun `replacement and configuration retain identities settings extras and disclose relationships for review`() {
        val first = LocalSelection.fromJson(SelectionFixtures.node(role = "fallback", enabled = false).put("notes", "retained").also { it.putObject("extensions").put("x_keep", true) })
        val target = selection("hosting_platform", "host")
        val link = SelectionFixtures.link("link.one", first.id, target.id)
        val before = DraftContent(components = DraftSelections(listOf(first, target), listOf(link)))
        val next = SelectionFixtures.catalogNode()
        val item = (next["items"].first { it["id"].asText() == first.catalogItemId } as ObjectNode).deepCopy().put("version", "explicit-next")
        (next["items"] as ArrayNode).add(item)
        val newCatalog = SelectionCatalogSnapshot.fromJson(SelectionFixtures.mapper.writeValueAsBytes(next))
        val choice = CatalogSelectionChoice(newCatalog.hash, first.kind, first.catalogItemId, "explicit-next")
        val result = DraftSelectionCommands.apply(before, SelectionCommand.Replace(first.id, choice), newCatalog) as SelectionEdit.Ready
        assertEquals(first.toJson().put("catalog_item_version", "explicit-next"), result.content.components.selections.first().toJson())
        assertEquals(listOf(link), result.content.components.links)
        assertEquals(listOf(link.id), result.change.affectedLinkIds)
        assertTrue(result.change.removedLinkIds.isEmpty())
        val settings = SelectionFixtures.mapper.createObjectNode().put("temperature", 0)
        val command = SelectionCommand.Configure(first.id, settings)
        settings.put("temperature", 9); command.settings.put("temperature", 9)
        val configured = ready(result.content, command)
        assertEquals(0, configured.content.components.selections.first().settings["temperature"].intValue())
        assertEquals("retained", configured.content.components.selections.first().toJson()["notes"].asText())
        assertEquals(listOf(link.id), configured.change.affectedLinkIds)
        val store = LocalDraftStore(root)
        val initial = initial(before)
        val replaced = saved(store.applySelection(DraftSelectionIntent(initial.version, SelectionCommand.Replace(first.id, choice)), newCatalog))
        val savedConfig = saved(store.applySelection(DraftSelectionIntent(replaced.version, command)))
        assertEquals(configured.content, savedConfig.content)
    }

    @Test fun `removal discloses incident links deterministically without promotion or unrelated loss`() {
        val primary = selection()
        val fallback = selection(id = "fallback", role = "fallback")
        val hosting = selection("hosting_platform", "hosting")
        val links = listOf(SelectionFixtures.link("z-link", primary.id, hosting.id), SelectionFixtures.link("keep-link", fallback.id, hosting.id),
            SelectionFixtures.link("a-link", fallback.id, primary.id), SelectionFixtures.link("self-link", primary.id, primary.id))
        val before = DraftContent(components = DraftSelections(listOf(primary, fallback, hosting), links))
        val store = LocalDraftStore(root)
        val initial = initial(before)
        val result = store.applySelection(DraftSelectionIntent(initial.version, SelectionCommand.Remove(primary.id))) as DraftSelectionSave.Saved
        val disclosure = requireNotNull(result.change)
        assertEquals(listOf("a-link", "self-link", "z-link"), disclosure.removedLinkIds)
        assertEquals(disclosure.removedLinkIds, disclosure.affectedLinkIds)
        assertEquals(disclosure, result.draft.history.last().selectionChange)
        assertEquals(listOf("keep-link"), result.draft.content.components.links.map { it.id })
        assertEquals("fallback", result.draft.content.components.selections.first().role)
        assertEquals(disclosure, (store.load() as DraftLoad.Available).draft.history.last().selectionChange)
        assertThrows(UnsupportedOperationException::class.java) { (disclosure.affectedLinkIds as MutableList).clear() }
    }

    @Test fun `caller nodes are bounded before recursion and invalid construction never mutates storage`() {
        val baseline = initial(DraftContent(components = DraftSelections(listOf(selection()))))
        val deep = SelectionFixtures.mapper.createObjectNode()
        var current = deep
        repeat(5_000) { current = current.putObject("nested") }
        val large = SelectionFixtures.mapper.createObjectNode().put("value", "x".repeat(1_100_000))
        val numeric = SelectionFixtures.mapper.createObjectNode().put("value", java.math.BigInteger("9".repeat(5_000)))
        val cycle = SelectionFixtures.mapper.createObjectNode().also { it.set<JsonNode>("cycle", it) }
        val nonfinite = SelectionFixtures.mapper.createObjectNode().put("value", Double.NaN)
        for (settings in listOf(deep, large, numeric, cycle, nonfinite)) {
            val error = assertThrows(IllegalArgumentException::class.java) { SelectionCommand.Configure("sel_model", settings) }
            assertEquals("INVALID_SELECTION_JSON", error.message)
            assertThrows(IllegalArgumentException::class.java) { SelectionCommand.Replace("sel_model", SelectionFixtures.choice(catalog, selection()), settings) }
            assertEquals(baseline.version.hash, (LocalDraftStore(root).load() as DraftLoad.Available).draft.version.hash)
        }
    }

    @Test fun `rejections and stale foreign tokens preserve all retained revisions`() {
        val store = LocalDraftStore(root)
        val context = selection("context_policy", "context")
        val alternate = selection("context_policy", "alternate", "alternate", false)
        val old = initial(DraftContent(components = DraftSelections(listOf(context, alternate))))
        val rejected = listOf(
            SelectionCommand.SetEnabled("alternate", true) to SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED,
            SelectionCommand.Configure("context", SelectionFixtures.mapper.createObjectNode()) to SelectionRejection.INVALID_SETTINGS,
            SelectionCommand.Remove("missing") to SelectionRejection.MISSING_SELECTION,
            add(context) to SelectionRejection.DUPLICATE_SELECTION_ID,
            add(selection("context_policy", "other", "primary", false)) to SelectionRejection.DUPLICATE_KIND_ROLE,
        )
        for ((command, reason) in rejected) {
            assertEquals(DraftSelectionSave.Rejected(reason), store.applySelection(DraftSelectionIntent(old.version, command), catalog))
            assertEquals(old.version.hash, (store.load() as DraftLoad.Available).draft.version.hash)
        }
        val changed = saved(store.applySelection(DraftSelectionIntent(old.version, SelectionCommand.SetEnabled("context", false))))
        assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(old.version, SelectionCommand.Configure("missing", SelectionFixtures.mapper.createObjectNode()))))
        val foreign = LocalDraftStore(Files.createDirectory(root.resolve("foreign")))
        val foreignVersion = (foreign.save(null, changed.content) as DraftSave.Saved).draft.version
        assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(foreignVersion, SelectionCommand.Remove("context"))))
        val retained = DraftContent(components = DraftSelections(listOf(selection("training_dataset", "training"), selection("deployment_target", "deployment"))))
        for (id in listOf("training", "deployment")) assertEquals(SelectionEdit.Rejected(SelectionRejection.UNSUPPORTED_KIND), DraftSelectionCommands.apply(retained, SelectionCommand.Remove(id)))
        assertEquals(2, retained.components.selections.size)
    }
}
