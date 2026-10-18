package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.preview.*
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/** Explicit frozen T0 editing, not deployment applicability or compatibility evaluation. */
class DraftFoundationSelectionTest {
    @TempDir lateinit var root: Path
    private val kinds = listOf("workload", "deployment_mode")
    private val catalog = SelectionFixtures.catalog()
    private val mapper = SelectionFixtures.mapper
    private fun item(kind: String): PreviewCatalogItem {
        val selection = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(selection.catalogItemId, "synthetic test input", kind, selection.catalogItemVersion, "T0")
    }
    private fun prepared(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun add(kind: String, id: String = "selection_$kind", role: String = "primary", enabled: Boolean = false,
        settings: String = "{}") = prepared(PreviewSelectionForms.add(id, item(kind), catalog.hash.hex, PreviewSelectionForm(role, enabled, settings)))
    private fun ready(content: DraftContent, command: SelectionCommand, source: SelectionCatalogSnapshot? = catalog) =
        DraftSelectionCommands.apply(content, command, source) as SelectionEdit.Ready
    private fun raw(path: Path) = (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun saved(result: PreviewSelectionSave) = (result as PreviewSelectionSave.Saved).record

    @Test fun `empty foundation forms retain explicit absent settings roles and disabled state without inventing other inputs`() {
        for (kind in kinds) {
            assertEquals(PreviewSelectionPreparation.Invalid,
                PreviewSelectionForms.add("new_selection", item(kind), catalog.hash.hex, PreviewSelectionForm("", false, "{}")))
            val original = DraftContent()
            val result = ready(original, add(kind, role = "unassigned"))
            val value = result.content.components.selections.single()
            assertEquals(kind, value.kind)
            assertEquals("unassigned", value.role)
            assertFalse(value.enabled)
            assertTrue(value.settings.isEmpty)
            assertEquals(item(kind).id, value.catalogItemId)
            assertEquals(item(kind).version, value.catalogItemVersion)
            assertTrue(result.content.components.links.isEmpty())
            assertEquals(original, result.content.withComponents(DraftSelections()))
            assertTrue(original.components.selections.isEmpty())
        }
    }

    @Test fun `workload overrides retain exact large integers and reject invalid types bounds and unknown settings atomically`() {
        val exact = "{\"concurrency\":999999999999999999999999,\"requests_per_day\":0,\"avg_input_tokens\":0,\"avg_output_tokens\":9007199254740993,\"latency_budget_ms\":1}"
        val unrelated = DraftContent("entered goal", "entered context", "opaque model", workload = DraftWorkload(region = "entered-region"))
        val original = ready(unrelated, add("workload", settings = exact)).content
        val value = original.components.selections.single()
        assertEquals(BigInteger("999999999999999999999999"), value.settings["concurrency"].bigIntegerValue())
        assertEquals(BigInteger("9007199254740993"), value.settings["avg_output_tokens"].bigIntegerValue())
        assertEquals(0, value.settings["requests_per_day"].intValue())
        assertEquals(unrelated, original.withComponents(DraftSelections()))
        val invalid = listOf("{\"concurrency\":0}", "{\"latency_budget_ms\":0}", "{\"requests_per_day\":-1}",
            "{\"avg_input_tokens\":0.5}", "{\"avg_output_tokens\":\"0\"}", "{\"requests_per_day\":null}",
            "{\"mode\":\"api\"}", "{\"currency\":\"USD\"}", "{\"concurrency\":1,\"concurrency\":2}")
        for (text in invalid) {
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(value, text), text)
            assertEquals(exact, PreviewSelectionForms.settingsText(value))
        }
        val settings = mapper.createObjectNode().put("concurrency", 7)
        val command = SelectionCommand.Configure(value.id, settings)
        settings.put("concurrency", 0)
        command.settings.put("concurrency", 0)
        val changed = ready(original, command).content
        assertEquals("{\"concurrency\":7}", PreviewSelectionForms.settingsText(changed.components.selections.single()))
        assertFalse(changed.components.selections.single().settings.has("requests_per_day"))
        assertEquals(unrelated.workload, changed.workload)
        assertEquals(exact, PreviewSelectionForms.settingsText(value))
    }

    @Test fun `all four explicit deployment catalog modes add and replace without mode settings or adaptation`() {
        val modes = listOf("api", "managed", "self_hosted", "hybrid")
        val document = SelectionFixtures.catalogNode()
        val prototype = document["items"].single { it["kind"].asText() == "deployment_mode" } as ObjectNode
        val items = modes.map { mode ->
            prototype.deepCopy().put("id", "cat.deployment_mode.test_$mode").put("version", "test-$mode").also {
                (it["spec"] as ObjectNode).put("mode", mode)
                (document["items"] as ArrayNode).add(it)
            }
        }
        val source = SelectionCatalogSnapshot.fromJson(mapper.writeValueAsBytes(document))
        val model = SelectionFixtures.selection()
        val base = DraftContent("unchanged", workload = DraftWorkload(region = "entered-only"), components = DraftSelections(listOf(model)))
        var content = base
        var first: LocalSelection? = null
        for ((index, entry) in items.withIndex()) {
            val choice = PreviewCatalogItem(entry["id"].asText(), "test-only mode", "deployment_mode", entry["version"].asText(), "T0")
            val command = if (index == 0) prepared(PreviewSelectionForms.add("deployment_choice", choice, source.hash.hex,
                PreviewSelectionForm("secondary", false, "{}"))) else prepared(PreviewSelectionForms.replace(requireNotNull(first), choice, source.hash.hex))
            val result = ready(content, command, source)
            content = result.content
            val selected = content.components.selections.single { it.kind == "deployment_mode" }
            if (first == null) first = selected
            assertEquals("deployment_choice", selected.id)
            assertEquals("secondary", selected.role)
            assertFalse(selected.enabled)
            assertEquals(choice.id, selected.catalogItemId)
            assertEquals(choice.version, selected.catalogItemVersion)
            assertTrue(selected.settings.isEmpty)
            assertEquals(base, content.withComponents(DraftSelections(listOf(model))))
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(selected, "{\"mode\":\"${modes[index]}\"}"))
            assertTrue(result.change.affectedLinkIds.isEmpty())
        }
        assertEquals("cat.deployment_mode.test_api", requireNotNull(first).catalogItemId)
        assertFalse(base.components.selections.any { it.kind == "deployment_mode" })
    }

    @Test fun `foundation commands require exact catalog hash kind item and version without substitution`() {
        for (kind in kinds) {
            val command = add(kind) as SelectionCommand.Add
            val base = DraftContent()
            val choice = command.choice
            for (bad in listOf(choice.copy(snapshotHash = ArtifactHash("a".repeat(64))),
                choice.copy(kind = kinds.single { it != kind }), choice.copy(itemVersion = "latest"), choice.copy(itemId = "cat.missing.item"))) {
                assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH),
                    DraftSelectionCommands.apply(base, SelectionCommand.Add(command.selection, bad), catalog))
            }
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH), DraftSelectionCommands.apply(base, command))
            val absent = LocalSelection.fromJson(command.selection.toJson().put("catalog_item_version", "unavailable-version"))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_ITEM_UNAVAILABLE), DraftSelectionCommands.apply(base,
                SelectionCommand.Add(absent, choice.copy(itemVersion = absent.catalogItemVersion)), catalog))
            val content = ready(base, command).content
            val other = SelectionFixtures.selection(kinds.single { it != kind })
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.replace(command.selection, item(other.kind), catalog.hash.hex))
            assertEquals(content, ready(content, SelectionCommand.Replace(command.selection.id, choice)).content)
            assertTrue(base.components.selections.isEmpty())
        }
    }

    @Test fun `both foundation kinds keep one enabled selection and unique roles without promoting alternatives`() {
        for (kind in kinds) {
            val primary = ready(DraftContent(), add(kind, "primary_$kind", enabled = true)).content
            val pair = ready(primary, add(kind, "backup_$kind", "backup")).content
            val backup = pair.components.selections.last()
            assertEquals(SelectionEdit.Rejected(SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED),
                DraftSelectionCommands.apply(pair, SelectionCommand.SetEnabled(backup.id, true)))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE),
                DraftSelectionCommands.apply(pair, prepared(PreviewSelectionForms.configureRole(backup, "primary"))))
            val primaryId = primary.components.selections.single().id
            val inactive = ready(pair, SelectionCommand.SetEnabled(primaryId, false)).content
            assertFalse(inactive.components.selections.any { it.enabled })
            val alternate = ready(inactive, SelectionCommand.SetEnabled(backup.id, true)).content
            assertEquals("backup", alternate.components.selections.single { it.enabled }.role)
            val removed = ready(alternate, SelectionCommand.Remove(primaryId)).content
            assertEquals(1, removed.components.selections.size)
            assertEquals("backup", removed.components.selections.single().role)
            assertFalse(removed.components.selections.any { it.role == "primary" })
            assertTrue(primary.components.selections.single().enabled)
        }
    }

    @Test fun `foundation edits retain format four history and exact older bytes until an explicit role edit uses format five`() {
        for (kind in kinds) {
            val path = Files.createDirectory(root.resolve(kind))
            val store = LocalDraftStore(path)
            val original = (store.save(null, DraftContent("entered"), mapOf("opaque.keep" to "exact")) as DraftSave.Saved).draft
            val originalBytes = raw(path)
            var current = (store.applySelection(DraftSelectionIntent(original.version, add(kind)), catalog) as DraftSelectionSave.Saved).draft
            val added = current
            val selection = current.content.components.selections.single()
            for (command in listOf(SelectionCommand.SetEnabled(selection.id, true),
                SelectionCommand.SetEnabled(selection.id, false), SelectionCommand.Remove(selection.id))) {
                val previous = current
                current = (store.applySelection(DraftSelectionIntent(previous.version, command)) as DraftSelectionSave.Saved).draft
                assertEquals(previous.version.number + 1, current.version.number)
                assertEquals(4, mapper.readTree(raw(path))["local_format_version"].intValue())
                assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(previous.version, command)))
            }
            current = (store.restore(current.version, added.version.number) as DraftSave.Saved).draft
            assertEquals(added.content, current.content)
            assertEquals(4, mapper.readTree(raw(path))["local_format_version"].intValue())
            val priorBytes = raw(path)
            val changed = store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configureRole(selection, "explicit_other")))) as DraftSelectionSave.Saved
            assertEquals(SelectionAction.CONFIGURE_ROLE, changed.change!!.action)
            assertEquals(5, mapper.readTree(raw(path))["local_format_version"].intValue())
            assertEquals(current.history.map { it.content }, changed.draft.history.dropLast(1).map { it.content })
            assertEquals(original.metadata, changed.draft.metadata)
            assertEquals(selection.settings, changed.draft.content.components.selections.single().settings)
            val cache = VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            assertArrayEquals(originalBytes, (cache.pinned(original.version.hash) as CacheRead.Available).bytes)
            assertArrayEquals(priorBytes, (cache.pinned(current.version.hash) as CacheRead.Available).bytes)
            assertEquals(changed.draft.content, (LocalDraftStore(path).load() as DraftLoad.Available).draft.content)
        }
    }

    @Test fun `managed foundation edits preserve guest fields workload links and active identity while rejecting stale and deleted authority`() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val fields = PreviewDraftFields("explicit title", "opaque sample", "pinned-version", "b".repeat(64))
        val workload = DraftWorkload(region = "own-region", requestsPerDay = BigInteger.valueOf(17))
        var record = (adapter.save(created.record, fields, PreviewDraftContext("own objective", "own context"), workload) as PreviewDraftSave.Saved).record
        val context = record.contextInput
        val metadata = record.draft.metadata
        val pointer = PreviewActiveDraftStore(root).read()
        for (kind in kinds) {
            val previous = record
            record = saved(adapter.applySelection(previous, add(kind), catalog))
            assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(previous, add(kind), catalog))
            assertEquals(fields, record.fields)
            assertEquals(context, record.contextInput)
            assertEquals(workload, record.workloadInput)
            assertEquals(metadata, record.draft.metadata)
            assertEquals(pointer, PreviewActiveDraftStore(root).read())
        }
        val rawStore = LocalDraftStore(root.resolve(created.id.value))
        val link = SelectionFixtures.link("link.foundation", record.components.selections[0].id, record.components.selections[1].id)
        assertTrue(rawStore.save(record.draft.version, record.draft.content.withComponents(DraftSelections(record.components.selections, listOf(link))), metadata) is DraftSave.Saved)
        record = (adapter.load() as PreviewDraftLoad.Available).record
        val before = record
        record = saved(adapter.applySelection(record, SelectionCommand.Remove(record.components.selections[0].id)))
        assertEquals(listOf(link.id), record.history.last().selectionChange!!.removedLinkIds)
        assertTrue(record.components.links.isEmpty())
        assertEquals(before.components.selections[1], record.components.selections.single())
        assertEquals(before.fields, record.fields)
        assertEquals(before.workloadInput, record.workloadInput)
        val other = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(other.record, SelectionCommand.Remove(record.components.selections.single().id)))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, record))
        for (kind in kinds) assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, add(kind), catalog))
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record,
            SelectionCommand.SetEnabled(record.components.selections.single().id, false)))
        assertEquals(other.record.baselineIdentity, (library.open(other.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }

    @Test fun `snapshot projection consumes only explicit enabled primary foundations and withholds conflicting workload inputs`() {
        val complete = ProjectionFixtures.content()
        val existing = complete.components.selections.filter { it.kind !in kinds }
        val ids = existing.map { it.id }.toSet()
        val partial = complete.withComponents(DraftSelections(existing, complete.components.links.filter { it.fromSelectionId in ids && it.toSelectionId in ids }))
        val store = LocalDraftStore(root)
        var current = (store.save(null, partial) as DraftSave.Saved).draft
        fun missing() = (store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Withheld)
            .findings.filter { it.problem == DraftProjectionProblem.MISSING_FOUNDATION }.map { it.relatedValues.first() }.toSet()
        assertEquals(kinds.toSet(), missing())
        for (kind in kinds) {
            val settings = PreviewSelectionForms.settingsText(ProjectionFixtures.selection(kind))
            current = (store.applySelection(DraftSelectionIntent(current.version, add(kind, role = "backup", settings = settings)), catalog) as DraftSelectionSave.Saved).draft
        }
        assertEquals(kinds.toSet(), missing())
        for (kind in kinds) {
            val value = current.content.components.selections.single { it.kind == kind }
            current = (store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configureRole(value, "primary")))) as DraftSelectionSave.Saved).draft
            assertTrue(kind in missing())
            current = (store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(value.id, true))) as DraftSelectionSave.Saved).draft
        }
        val review = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val bytes = raw(root)
        val candidate = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val projected = (BoundedJsonReader().read(candidate.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        assertEquals(current.content.components.selections.map { it.toJson() }, projected["selections"].toList())
        assertArrayEquals(bytes, raw(root))
        val workload = current.content.components.selections.single { it.kind == "workload" }
        val changedSettings = workload.settings.put("concurrency", complete.workload.maxConcurrency!!.add(BigInteger.ONE))
        val previous = current
        current = (store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.Configure(workload.id, changedSettings))) as DraftSelectionSave.Saved).draft
        val withheld = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Withheld
        assertTrue(withheld.findings.any { it.problem == DraftProjectionProblem.CONFLICTING_WORKLOAD_INPUT })
        assertEquals(complete.workload, current.content.workload)
        assertEquals(previous.content.components.selections.single { it.kind == "workload" }, workload)
        assertEquals(DraftProjectionCreation.Conflict, store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())))
    }

    @Test fun `retained optional and core tier six kinds stay outside this command subset`() {
        val retained = mapper.readTree(SelectionFixtures.resource("/contracts/v1/examples/build.json"))["selections"]
            .map { LocalSelection.fromJson(it as ObjectNode) }.filter { ProjectionFixtures.tier(it.kind) in setOf("T3", "T4", "T5", "T6") }
        assertEquals(19, retained.size)
        val content = DraftContent(components = DraftSelections(retained))
        for (value in retained) {
            assertFalse(PreviewSelectionForms.supported(value.kind))
            for (command in listOf(SelectionCommand.Remove(value.id), SelectionCommand.SetEnabled(value.id, false),
                SelectionCommand.Configure(value.id, value.settings))) {
                assertEquals(SelectionEdit.Rejected(SelectionRejection.UNSUPPORTED_KIND), DraftSelectionCommands.apply(content, command))
            }
        }
        assertEquals(retained, content.components.selections)
    }
}
