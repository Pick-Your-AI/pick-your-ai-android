package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
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

/** Local declared T4 inputs; these commands cannot connect, authorize or execute an external action. */
class DraftAgentSelectionTest {
    @TempDir lateinit var root: Path
    private val kinds = listOf("agent_framework", "tool", "mcp_server", "memory_store", "integration")
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private fun item(kind: String): PreviewCatalogItem {
        val value = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(value.catalogItemId, "synthetic agent input", kind, value.catalogItemVersion, "T4")
    }
    private fun prepared(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun add(kind: String, id: String = "agent_$kind", role: String = "entered", enabled: Boolean = false,
        settings: String = "{}") = prepared(PreviewSelectionForms.add(id, item(kind), catalog.hash.hex,
        PreviewSelectionForm(role, enabled, settings)))
    private fun ready(content: DraftContent, command: SelectionCommand, source: SelectionCatalogSnapshot? = catalog) =
        DraftSelectionCommands.apply(content, command, source) as SelectionEdit.Ready
    private fun raw(path: Path) = (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun saved(result: DraftSelectionSave) = (result as DraftSelectionSave.Saved).draft

    @Test fun `all five agent kinds need explicit identity role and enabled state without creating another capability`() {
        val original = DraftContent("entered objective", "entered context", "opaque model",
            OptionalTier.entries.associateWith { TierDraft(Activation.UNKNOWN, setOf("retained ${it.name}"), mapOf("opaque" to "unchanged")) },
            DraftWorkload(region = "entered-region"))
        for (kind in kinds) {
            assertTrue(PreviewSelectionForms.supported(kind))
            assertEquals(PreviewSelectionPreparation.Invalid,
                PreviewSelectionForms.add("new_agent", item(kind), catalog.hash.hex, PreviewSelectionForm("", false, "{}")))
            val result = ready(original, add(kind))
            val value = result.content.components.selections.single()
            assertEquals(kind, value.kind)
            assertEquals("agent_$kind", value.id)
            assertEquals("entered", value.role)
            assertFalse(value.enabled)
            assertEquals("{}", PreviewSelectionForms.settingsText(value))
            assertEquals(item(kind).id, value.catalogItemId)
            assertEquals(item(kind).version, value.catalogItemVersion)
            assertTrue(result.content.components.links.isEmpty())
            assertEquals(original, result.content.withComponents(DraftSelections()))
            assertTrue(original.components.selections.isEmpty())
        }
        assertFalse(PreviewSelectionForms.supported("guardrail"))
        assertFalse(PreviewSelectionForms.supported("monitoring_stack"))
        assertFalse(PreviewSelectionForms.supported("deployment_target"))
    }

    @Test fun `all frozen agent fields preserve exact scopes URIs references numbers and explicit false values`() {
        val settings = mapOf(
            "agent_framework" to "{\"agent_count\":9007199254740993,\"max_iterations\":999999999999999999999999,\"timeout_ms\":1}",
            "tool" to "{\"enabled\":false,\"allowed_scopes\":[\"read:alpha\",\" write:* \",\"\",\"read:東京\"],\"timeout_ms\":9007199254740993,\"credential_ref\":\"secret://agent/tool:1\"}",
            "mcp_server" to "{\"endpoint\":\"https://mcp.example.invalid:9443/a/%2F?q=read%3Aalpha#exact\",\"credential_ref\":\"secret://agent/mcp\",\"read_only\":false}",
            "memory_store" to "{\"ttl_hours\":1,\"max_entries\":999999999999999999999999,\"encryption_key_ref\":\"secret://agent/memory\"}",
            "integration" to "{\"enabled\":false,\"endpoint\":\"custom+local://integration.example.invalid/exact\",\"credential_ref\":\"secret://agent/integration\",\"rate_limit_per_minute\":0}",
        )
        var content = DraftContent()
        for (kind in kinds) content = ready(content, add(kind, enabled = true, settings = settings.getValue(kind))).content
        for (value in content.components.selections) {
            assertEquals(settings.getValue(value.kind), PreviewSelectionForms.settingsText(value))
            assertTrue(value.enabled)
            assertEquals(Activation.DISABLED, content.optional.getValue(OptionalTier.T4).activation)
        }
        assertEquals(BigInteger("9007199254740993"), content.components.selections.first().settings["agent_count"].bigIntegerValue())
        val tool = content.components.selections.single { it.kind == "tool" }
        assertEquals(listOf("read:alpha", " write:* ", "", "read:東京"), tool.settings["allowed_scopes"].map { it.textValue() })
        val mutable = mapper.createObjectNode()
        mutable.putArray("allowed_scopes").add("read:one")
        val command = SelectionCommand.Configure(tool.id, mutable)
        (mutable["allowed_scopes"] as ArrayNode).add("mutated")
        (command.settings["allowed_scopes"] as ArrayNode).add("also mutated")
        val configured = ready(content, command).content.components.selections.single { it.id == tool.id }
        assertEquals("{\"allowed_scopes\":[\"read:one\"]}", PreviewSelectionForms.settingsText(configured))
        assertEquals(settings.getValue("tool"), PreviewSelectionForms.settingsText(tool))
        assertFalse(configured.settings.has("enabled"))
        assertEquals("{\"allowed_scopes\":[]}", PreviewSelectionForms.settingsText(ready(content,
            prepared(PreviewSelectionForms.configure(tool, "{\"allowed_scopes\":[]}"))).content.components.selections.single { it.id == tool.id }))
        assertTrue(content.components.links.isEmpty())
        // Declared scopes, read_only, timeouts and endpoints do not grant authority or demonstrate a connection.
        assertFalse(listOf(command, configured).joinToString().contains("secret://"))
    }

    @Test fun `invalid agent fields duplicate scopes and malformed input never alter a durable revision`() {
        val invalid = mapOf(
            "agent_framework" to listOf("{\"agent_count\":0}", "{\"agent_count\":1.5}", "{\"max_iterations\":0}",
                "{\"timeout_ms\":\"1\"}", "{\"supports_memory\":true}", "{\"agent_count\":1,\"agent_count\":2}"),
            "tool" to listOf("{\"allowed_scopes\":[\"read\",\"read\"]}", "{\"allowed_scopes\":[\"read\",1]}",
                "{\"allowed_scopes\":null}", "{\"enabled\":\"false\"}", "{\"credential_ref\":\"raw-credential\"}", "{\"protocol\":\"http\"}"),
            "mcp_server" to listOf("{\"endpoint\":\"not a URI\"}", "{\"endpoint\":\"/relative\"}", "{\"read_only\":null}",
                "{\"credential_ref\":null}", "{\"transport\":\"stdio\"}", "[]"),
            "memory_store" to listOf("{\"ttl_hours\":0}", "{\"max_entries\":1.5}", "{\"encryption_key_ref\":\"raw-key\"}",
                "{\"encryption_key_ref\":null}", "{\"backing_type\":\"vector\"}", "{\"ttl_hours\":1} trailing"),
            "integration" to listOf("{\"enabled\":1}", "{\"rate_limit_per_minute\":-1}", "{\"rate_limit_per_minute\":0.5}",
                "{\"endpoint\":\"https://example.invalid/a b\"}", "{\"credential_ref\":\"plain\"}", "{\"enabled\":true,\"enabled\":false}"),
        )
        for (kind in kinds) {
            val path = Files.createDirectory(root.resolve(kind))
            val store = LocalDraftStore(path)
            val initial = (store.save(null, DraftContent()) as DraftSave.Saved).draft
            val before = saved(store.applySelection(DraftSelectionIntent(initial.version, add(kind)), catalog))
            val bytes = raw(path)
            val value = before.content.components.selections.single()
            for (text in invalid.getValue(kind) + "{\"unknown\":1}" + "{\"x\":\"${"x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT)}\"}") {
                assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(value, text), "$kind: $text")
                assertArrayEquals(bytes, raw(path))
            }
            assertEquals(DraftSelectionSave.Rejected(SelectionRejection.INVALID_SETTINGS), store.applySelection(DraftSelectionIntent(
                before.version, SelectionCommand.Configure(value.id, mapper.createObjectNode().put("unknown", 1)))))
            val reopened = (LocalDraftStore(path).load() as DraftLoad.Available).draft
            assertEquals(before.version.number, reopened.version.number)
            assertEquals(before.version.hash, reopened.version.hash)
            assertEquals(before.content, reopened.content)
            assertArrayEquals(bytes, raw(path))
        }
    }

    @Test fun `many explicit agent roles coexist and disabling or removing one does not promote another`() {
        for (kind in kinds) {
            val one = ready(DraftContent(), add(kind, "first_$kind", "capability_a", true)).content
            val pair = ready(one, add(kind, "second_$kind", "capability_b", true)).content
            assertEquals(2, pair.components.selections.count { it.enabled })
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE),
                DraftSelectionCommands.apply(pair, add(kind, "third_$kind", "capability_a", false), catalog))
            val first = pair.components.selections.first()
            val second = pair.components.selections.last()
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE), DraftSelectionCommands.apply(pair,
                prepared(PreviewSelectionForms.configureRole(second, first.role))))
            val disabled = ready(pair, SelectionCommand.SetEnabled(first.id, false)).content
            assertEquals(second, disabled.components.selections.last())
            val removed = ready(disabled, SelectionCommand.Remove(first.id)).content
            assertEquals(listOf(second), removed.components.selections)
            assertEquals("capability_b", removed.components.selections.single().role)
            assertEquals(2, pair.components.selections.count { it.enabled })
            assertEquals(pair.optional, removed.optional)
        }
    }

    @Test fun `agent add and replacement require exact catalog pins and retain identity settings and incident relationships`() {
        val document = SelectionFixtures.catalogNode()
        val alternatives = kinds.associateWith { kind ->
            val entry = (document["items"].first { it["kind"].asText() == kind } as ObjectNode).deepCopy()
                .put("id", "cat.$kind.test_alternative").put("version", "test-agent-2")
            (document["items"] as ArrayNode).add(entry)
            PreviewCatalogItem(entry["id"].asText(), "test-only alternative", kind, entry["version"].asText(), "T4")
        }
        val alternateCatalog = SelectionCatalogSnapshot.fromJson(mapper.writeValueAsBytes(document))
        for (kind in kinds) {
            val command = add(kind) as SelectionCommand.Add
            val source = command.selection
            val other = SelectionFixtures.selection("model", "other_model")
            val link = SelectionFixtures.link("link.agent", source.id, other.id, true)
            val content = DraftContent(components = DraftSelections(listOf(source, other), listOf(link)))
            val replaced = ready(content, prepared(PreviewSelectionForms.replace(source, alternatives.getValue(kind), alternateCatalog.hash.hex)), alternateCatalog)
            val value = replaced.content.components.selections.first()
            assertEquals(source.id, value.id)
            assertEquals(source.role, value.role)
            assertEquals(source.enabled, value.enabled)
            assertEquals(source.settings, value.settings)
            assertEquals(alternatives.getValue(kind).id, value.catalogItemId)
            assertEquals(alternatives.getValue(kind).version, value.catalogItemVersion)
            assertEquals(content.components.links, replaced.content.components.links)
            assertEquals(listOf(link.id), replaced.change.affectedLinkIds)
            assertEquals(source, content.components.selections.first())
            for (bad in listOf(command.choice.copy(snapshotHash = ArtifactHash("a".repeat(64))),
                command.choice.copy(kind = kinds.first { it != kind }), command.choice.copy(itemVersion = "latest"),
                command.choice.copy(itemId = "cat.missing.item"))) {
                assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH),
                    DraftSelectionCommands.apply(DraftContent(), SelectionCommand.Add(source, bad), catalog))
            }
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH), DraftSelectionCommands.apply(DraftContent(), command))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH), DraftSelectionCommands.apply(content,
                prepared(PreviewSelectionForms.replace(source, alternatives.getValue(kind), alternateCatalog.hash.hex)), catalog))
            val absent = LocalSelection.fromJson(source.toJson().put("catalog_item_version", "absent-version"))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_ITEM_UNAVAILABLE), DraftSelectionCommands.apply(DraftContent(),
                SelectionCommand.Add(absent, command.choice.copy(itemVersion = absent.catalogItemVersion)), catalog))
        }
    }

    @Test fun `agent edits retain format four history and only a real role edit upgrades the exact saved values to five`() {
        val store = LocalDraftStore(root)
        val initial = (store.save(null, DraftContent("unchanged", optional = mapOf(OptionalTier.T4 to
            TierDraft(Activation.UNKNOWN, setOf("opaque connector"), mapOf("permission assumption" to "unverified")))),
            mapOf("retained.evidence" to "exact reference")) as DraftSave.Saved).draft
        val initialBytes = raw(root)
        var current = initial
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind)), catalog))
        val added = current
        val tool = current.content.components.selections.single { it.kind == "tool" }
        current = saved(store.applySelection(DraftSelectionIntent(current.version, prepared(PreviewSelectionForms.configure(tool,
            "{\"enabled\":false,\"allowed_scopes\":[\"read:one\",\" write:* \"],\"credential_ref\":\"secret://retained/tool\"}")))))
        for (kind in kinds) {
            val previous = current
            current = saved(store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.SetEnabled("agent_$kind", true))))
            assertEquals(4, mapper.readTree(raw(root))["local_format_version"].intValue())
            assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.Remove(tool.id))))
            assertEquals(initial.content.optional, current.content.optional)
            assertEquals(initial.metadata, current.metadata)
        }
        val beforeRole = current
        val beforeBytes = raw(root)
        current = saved(store.applySelection(DraftSelectionIntent(current.version,
            prepared(PreviewSelectionForms.configureRole(current.content.components.selections.single { it.id == tool.id }, "separate_capability")))))
        assertEquals(5, mapper.readTree(raw(root))["local_format_version"].intValue())
        assertEquals(SelectionAction.CONFIGURE_ROLE, current.history.last().selectionChange!!.action)
        assertEquals(beforeRole.history.map { it.content }, current.history.dropLast(1).map { it.content })
        assertEquals(beforeRole.content.components.selections.single { it.id == tool.id }.settings,
            current.content.components.selections.single { it.id == tool.id }.settings)
        assertEquals(current.content, (LocalDraftStore(root).load() as DraftLoad.Available).draft.content)
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertArrayEquals(initialBytes, (cache.pinned(initial.version.hash) as CacheRead.Available).bytes)
        assertArrayEquals(beforeBytes, (cache.pinned(beforeRole.version.hash) as CacheRead.Available).bytes)
        val restored = (store.restore(current.version, added.version.number) as DraftSave.Saved).draft
        assertEquals(added.content, restored.content)
        assertEquals(current.version.number + 1, restored.version.number)
        assertEquals(initial.metadata, restored.metadata)
    }

    @Test fun `T4 intent stays independent and structural snapshot preparation neither forces memory nor grants permissions`() {
        val complete = ProjectionFixtures.content(allKinds = true)
        val t4 = complete.optional.getValue(OptionalTier.T4)
        val store = LocalDraftStore(root)
        var current = (store.save(null, complete) as DraftSave.Saved).draft
        fun finding(problem: DraftProjectionProblem) {
            val result = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Withheld
            assertTrue(result.findings.any { it.problem == problem && it.path == "/content/optional/T4/activation" })
        }
        for (activation in listOf(Activation.DISABLED, Activation.UNKNOWN)) {
            current = (store.save(current.version, current.content.withTier(OptionalTier.T4,
                TierDraft(activation, t4.selections, t4.settings)), current.metadata) as DraftSave.Saved).draft
            val before = current.content
            val tool = before.components.selections.single { it.kind == "tool" }
            current = saved(store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configure(tool, "{\"enabled\":false,\"allowed_scopes\":[]}")))))
            assertEquals(before.optional, current.content.optional)
            assertTrue(current.content.components.selections.single { it.kind == "tool" }.enabled)
            finding(if (activation == Activation.DISABLED) DraftProjectionProblem.CONTRADICTORY_OPTIONAL_INTENT else DraftProjectionProblem.UNKNOWN_OPTIONAL_INTENT)
        }
        for (value in current.content.components.selections.filter { it.kind in kinds })
            current = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(value.id, false))))
        val retained = current.content.components.selections.filter { it.kind in kinds }
        current = (store.save(current.version, current.content.withTier(OptionalTier.T4, t4), current.metadata) as DraftSave.Saved).draft
        finding(DraftProjectionProblem.INCOMPLETE_OPTIONAL_SELECTIONS)
        val tool = retained.single { it.kind == "tool" }
        current = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(tool.id, true))))
        assertEquals(1, current.content.components.selections.count { it.kind in kinds && it.enabled })
        assertEquals(retained.map { it.settings }, current.content.components.selections.filter { it.kind in kinds }.map { it.settings })
        val review = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val before = raw(root)
        val result = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val exported = result.candidate.bytes()
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, exported.inputStream()))
        val node = (BoundedJsonReader().read(exported.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(current.content.components.selections.map { it.toJson() }, node["selections"].toList())
        assertEquals(current.content.components.links.map { it.toJson() }, node["links"].toList())
        assertFalse(node.has("evaluation_report"))
        assertArrayEquals(before, raw(root))
        assertEquals(t4, current.content.optional.getValue(OptionalTier.T4))
    }

    @Test fun `managed agent commands retain guest context and links while stale foreign or deleted authority cannot save`() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val fields = PreviewDraftFields("entered title", "opaque sample", "exact-version", "b".repeat(64))
        var record = (adapter.save(created.record, fields, PreviewDraftContext("entered goal", "entered context"),
            DraftWorkload(region = "entered-region", requestsPerDay = BigInteger.valueOf(19))) as PreviewDraftSave.Saved).record
        val original = record
        val pointer = PreviewActiveDraftStore(root).read()
        for (kind in kinds) {
            val previous = record
            record = (adapter.applySelection(previous, add(kind), catalog) as PreviewSelectionSave.Saved).record
            assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(previous, add(kind), catalog))
            assertEquals(fields, record.fields)
            assertEquals(original.contextInput, record.contextInput)
            assertEquals(original.workloadInput, record.workloadInput)
            assertEquals(original.draft.metadata, record.draft.metadata)
            assertEquals(original.draft.content.optional, record.draft.content.optional)
            assertEquals(pointer, PreviewActiveDraftStore(root).read())
        }
        val values = record.components.selections
        val incident = SelectionFixtures.link("link.incident", values[0].id, values[1].id, true)
        val retained = SelectionFixtures.link("link.retained", values[2].id, values[3].id, false)
        val store = LocalDraftStore(root.resolve(created.id.value))
        assertTrue(store.save(record.draft.version, record.draft.content.withComponents(DraftSelections(values, listOf(incident, retained))),
            record.draft.metadata) is DraftSave.Saved)
        record = (adapter.load() as PreviewDraftLoad.Available).record
        record = (adapter.applySelection(record, SelectionCommand.Remove(values[0].id)) as PreviewSelectionSave.Saved).record
        assertEquals(listOf(incident.id), record.history.last().selectionChange!!.removedLinkIds)
        assertEquals(listOf(retained), record.components.links)
        assertEquals(values.drop(1), record.components.selections)
        val other = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(other.record, add("tool"), catalog))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, record))
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, add("tool"), catalog))
        assertEquals(other.record.baselineIdentity, (library.open(other.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
