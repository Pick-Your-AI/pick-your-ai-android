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

/** Frozen local T3 configuration only; structural validity is not interoperability or access proof. */
class DraftRetrievalSelectionTest {
    @TempDir lateinit var root: Path
    private val kinds = listOf("data_source", "knowledge_base", "embedding_model", "vector_database", "rag_pipeline")
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private fun item(kind: String): PreviewCatalogItem {
        val value = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(value.catalogItemId, "synthetic retrieval input", kind, value.catalogItemVersion, "T3")
    }
    private fun minimum(kind: String) = if (kind == "vector_database") "{\"index_dimension\":1,\"similarity_metric\":\"cosine\"}" else "{}"
    private fun prepared(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun add(kind: String, id: String = "retrieval_$kind", role: String = "entered", enabled: Boolean = false,
        settings: String = minimum(kind)) = prepared(PreviewSelectionForms.add(id, item(kind), catalog.hash.hex,
        PreviewSelectionForm(role, enabled, settings)))
    private fun ready(content: DraftContent, command: SelectionCommand, source: SelectionCatalogSnapshot? = catalog) =
        DraftSelectionCommands.apply(content, command, source) as SelectionEdit.Ready
    private fun raw(path: Path) = (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun saved(result: DraftSelectionSave) = (result as DraftSelectionSave.Saved).draft

    @Test fun `all five retrieval kinds require explicit identity role and vector inputs without creating a dependency stack`() {
        val original = DraftContent("entered objective", "entered context", "opaque model",
            OptionalTier.entries.associateWith { TierDraft(Activation.UNKNOWN, setOf("retained ${it.name}"), mapOf("opaque" to "unchanged")) },
            DraftWorkload(region = "entered-region"))
        for (kind in kinds) {
            assertTrue(PreviewSelectionForms.supported(kind))
            assertEquals(PreviewSelectionPreparation.Invalid,
                PreviewSelectionForms.add("new_retrieval", item(kind), catalog.hash.hex, PreviewSelectionForm("", false, minimum(kind))))
            val result = ready(original, add(kind))
            val value = result.content.components.selections.single()
            assertEquals(kind, value.kind)
            assertEquals("retrieval_$kind", value.id)
            assertEquals("entered", value.role)
            assertFalse(value.enabled)
            assertEquals(minimum(kind), PreviewSelectionForms.settingsText(value))
            assertEquals(item(kind).id, value.catalogItemId)
            assertEquals(item(kind).version, value.catalogItemVersion)
            assertTrue(result.content.components.links.isEmpty())
            assertEquals(original, result.content.withComponents(DraftSelections()))
            assertTrue(original.components.selections.isEmpty())
        }
        assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.add("vector", item("vector_database"),
            catalog.hash.hex, PreviewSelectionForm("entered", false, "{}")))
        assertTrue(PreviewSelectionForms.supported("cost_policy"))
        assertFalse(PreviewSelectionForms.supported("future_cost_policy"))
        assertTrue(PreviewSelectionForms.supported("monitoring_stack"))
        assertFalse(PreviewSelectionForms.supported("future_monitoring_stack"))
        assertTrue(PreviewSelectionForms.supported("deployment_target"))
        assertFalse(PreviewSelectionForms.supported("future_deployment_target"))
    }

    @Test fun `every frozen retrieval setting retains exact numbers booleans references and unmeasured declarations`() {
        val settings = mapOf(
            "data_source" to "{\"connector_secret_ref\":\"secret://retrieval/source:1\",\"refresh_minutes\":9007199254740993,\"enabled\":false}",
            "knowledge_base" to "{\"chunk_size_tokens\":1,\"chunk_overlap_tokens\":999999999999999999999999,\"refresh_minutes\":1,\"acl_enforced\":false}",
            "embedding_model" to "{\"credential_ref\":\"secret://retrieval/embedding\",\"output_dimensions\":9007199254740993,\"region\":\"unknown \\n literal-東京\"}",
            "vector_database" to "{\"index_dimension\":999999999999999999999999,\"similarity_metric\":\"euclidean\",\"region\":\"unmeasured\",\"index_name\":\" exact name \",\"credential_ref\":\"secret://retrieval/vector\"}",
            "rag_pipeline" to "{\"top_k\":9007199254740993,\"max_chunks\":1,\"rerank_enabled\":false}",
        )
        var content = DraftContent()
        for (kind in kinds) content = ready(content, add(kind, enabled = true, settings = settings.getValue(kind))).content
        for (value in content.components.selections) {
            assertEquals(settings.getValue(value.kind), PreviewSelectionForms.settingsText(value))
            assertTrue(value.enabled)
            assertEquals(Activation.DISABLED, content.optional.getValue(OptionalTier.T3).activation)
        }
        assertEquals(BigInteger("9007199254740993"), content.components.selections.first().settings["refresh_minutes"].bigIntegerValue())
        val knowledge = content.components.selections.single { it.kind == "knowledge_base" }
        assertEquals(BigInteger("999999999999999999999999"), knowledge.settings["chunk_overlap_tokens"].bigIntegerValue())
        val mutable = mapper.createObjectNode().put("top_k", 7)
        val rag = content.components.selections.last()
        val command = SelectionCommand.Configure(rag.id, mutable)
        mutable.put("top_k", 0)
        command.settings.put("top_k", 0)
        val configured = ready(content, command).content.components.selections.last()
        assertEquals("{\"top_k\":7}", PreviewSelectionForms.settingsText(configured))
        assertEquals(settings.getValue("rag_pipeline"), PreviewSelectionForms.settingsText(rag))
        assertFalse(configured.settings.has("max_chunks"))
        // Overlap, dimensional compatibility, region support and rerank applicability need separate evidence.
        assertTrue(content.components.links.isEmpty())
    }

    @Test fun `invalid retrieval settings never replace the durable head or retain a valid prefix`() {
        val invalid = mapOf(
            "data_source" to listOf("{\"refresh_minutes\":0}", "{\"refresh_minutes\":1.5}", "{\"enabled\":\"false\"}",
                "{\"connector_secret_ref\":\"raw-credential\"}", "{\"connector_secret_ref\":null}", "{\"enabled\":true,\"enabled\":false}"),
            "knowledge_base" to listOf("{\"chunk_size_tokens\":0}", "{\"chunk_overlap_tokens\":-1}", "{\"refresh_minutes\":false}",
                "{\"acl_enforced\":1}", "{\"transport\":\"https\"}", "{\"chunk_size_tokens\":1,\"chunk_size_tokens\":2}"),
            "embedding_model" to listOf("{\"output_dimensions\":0}", "{\"output_dimensions\":\"768\"}", "{\"region\":null}",
                "{\"credential_ref\":\"https://example.invalid/token\"}", "{\"provider_ref\":\"cat.provider.other\"}", "[]"),
            "vector_database" to listOf("{}", "{\"index_dimension\":1}", "{\"similarity_metric\":\"cosine\"}",
                "{\"index_dimension\":0,\"similarity_metric\":\"cosine\"}", "{\"index_dimension\":1,\"similarity_metric\":\"COSINE\"}",
                "{\"index_dimension\":1,\"similarity_metric\":\"dot\",\"index_name\":\"\"}"),
            "rag_pipeline" to listOf("{\"top_k\":0}", "{\"max_chunks\":0}", "{\"rerank_enabled\":null}",
                "{\"top_k\":true}", "{\"retrieval_mode\":\"dense\"}", "{\"max_chunks\":1} trailing"),
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
            val invalidCommand = SelectionCommand.Configure(value.id, mapper.createObjectNode().put("unknown", 1))
            assertEquals(DraftSelectionSave.Rejected(SelectionRejection.INVALID_SETTINGS),
                store.applySelection(DraftSelectionIntent(before.version, invalidCommand)))
            val reopened = (LocalDraftStore(path).load() as DraftLoad.Available).draft
            assertEquals(before.version.number, reopened.version.number)
            assertEquals(before.version.hash, reopened.version.hash)
            assertEquals(before.content, reopened.content)
            assertEquals(minimum(kind), PreviewSelectionForms.settingsText(value))
            assertArrayEquals(bytes, raw(path))
        }
    }

    @Test fun `many retrieval roles can be enabled together but duplicate inactive roles never displace another selection`() {
        for (kind in kinds) {
            val one = ready(DraftContent(), add(kind, "first_$kind", "source_a", true)).content
            val pair = ready(one, add(kind, "second_$kind", "source_b", true)).content
            assertEquals(2, pair.components.selections.count { it.enabled })
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE),
                DraftSelectionCommands.apply(pair, add(kind, "third_$kind", "source_a", false), catalog))
            val first = pair.components.selections.first()
            val second = pair.components.selections.last()
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE), DraftSelectionCommands.apply(pair,
                prepared(PreviewSelectionForms.configureRole(second, first.role))))
            val disabled = ready(pair, SelectionCommand.SetEnabled(first.id, false)).content
            assertEquals(second, disabled.components.selections.last())
            val removed = ready(disabled, SelectionCommand.Remove(first.id)).content
            assertEquals(listOf(second), removed.components.selections)
            assertEquals("source_b", removed.components.selections.single().role)
            assertEquals(2, pair.components.selections.count { it.enabled })
            assertEquals(pair.optional, removed.optional)
        }
    }

    @Test fun `retrieval add and replace require exact catalog pins and preserve existing selection identity and relationships`() {
        val document = SelectionFixtures.catalogNode()
        val alternatives = kinds.associateWith { kind ->
            val entry = (document["items"].first { it["kind"].asText() == kind } as ObjectNode).deepCopy()
                .put("id", "cat.$kind.test_alternative").put("version", "test-retrieval-2")
            (document["items"] as ArrayNode).add(entry)
            PreviewCatalogItem(entry["id"].asText(), "test-only alternative", kind, entry["version"].asText(), "T3")
        }
        val alternateCatalog = SelectionCatalogSnapshot.fromJson(mapper.writeValueAsBytes(document))
        for (kind in kinds) {
            val command = add(kind) as SelectionCommand.Add
            val source = command.selection
            val other = SelectionFixtures.selection("model", "other_model")
            val link = SelectionFixtures.link("link.retrieval", source.id, other.id, true)
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

    @Test fun `retrieval history reopens exact format four values while a real role change alone upgrades to format five`() {
        val store = LocalDraftStore(root)
        val initial = (store.save(null, DraftContent("unchanged", optional = mapOf(OptionalTier.T3 to
            TierDraft(Activation.UNKNOWN, setOf("opaque source"), mapOf("access assumption" to "unverified")))),
            mapOf("retained.evidence" to "exact reference")) as DraftSave.Saved).draft
        val initialBytes = raw(root)
        var current = initial
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind)), catalog))
        val added = current
        val first = added.content.components.selections.first()
        current = saved(store.applySelection(DraftSelectionIntent(current.version,
            prepared(PreviewSelectionForms.configure(first, "{\"enabled\":true,\"connector_secret_ref\":\"secret://retained/source\"}")))))
        for (kind in kinds) {
            val previous = current
            current = saved(store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.SetEnabled("retrieval_$kind", true))))
            assertEquals(4, mapper.readTree(raw(root))["local_format_version"].intValue())
            assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.Remove(first.id))))
            assertEquals(initial.content.optional, current.content.optional)
            assertEquals(initial.metadata, current.metadata)
        }
        val beforeRole = current
        val beforeBytes = raw(root)
        current = saved(store.applySelection(DraftSelectionIntent(current.version,
            prepared(PreviewSelectionForms.configureRole(current.content.components.selections.first(), "separate_source")))))
        assertEquals(5, mapper.readTree(raw(root))["local_format_version"].intValue())
        assertEquals(SelectionAction.CONFIGURE_ROLE, current.history.last().selectionChange!!.action)
        assertEquals(beforeRole.history.map { it.content }, current.history.dropLast(1).map { it.content })
        assertEquals(beforeRole.content.components.selections.first().settings, current.content.components.selections.first().settings)
        assertEquals(current.content, (LocalDraftStore(root).load() as DraftLoad.Available).draft.content)
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertArrayEquals(initialBytes, (cache.pinned(initial.version.hash) as CacheRead.Available).bytes)
        assertArrayEquals(beforeBytes, (cache.pinned(beforeRole.version.hash) as CacheRead.Available).bytes)
        val restored = (store.restore(current.version, added.version.number) as DraftSave.Saved).draft
        assertEquals(added.content, restored.content)
        assertEquals(current.version.number + 1, restored.version.number)
        assertEquals(initial.metadata, restored.metadata)
    }

    @Test fun `tier intent and retained retrieval settings stay separate and contradictory or unknown intent cannot export a candidate`() {
        val complete = ProjectionFixtures.content(allKinds = true)
        val t3 = complete.optional.getValue(OptionalTier.T3)
        val store = LocalDraftStore(root)
        var current = (store.save(null, complete) as DraftSave.Saved).draft
        fun finding(problem: DraftProjectionProblem) {
            val result = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Withheld
            assertTrue(result.findings.any { it.problem == problem && it.path == "/content/optional/T3/activation" })
        }
        for (activation in listOf(Activation.DISABLED, Activation.UNKNOWN)) {
            current = (store.save(current.version, current.content.withTier(OptionalTier.T3,
                TierDraft(activation, t3.selections, t3.settings)), current.metadata) as DraftSave.Saved).draft
            val before = current.content
            val value = before.components.selections.single { it.kind == "data_source" }
            current = saved(store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configure(value, "{\"enabled\":false,\"refresh_minutes\":17}")))))
            assertEquals(before.optional, current.content.optional)
            assertTrue(current.content.components.selections.single { it.kind == "data_source" }.enabled)
            finding(if (activation == Activation.DISABLED) DraftProjectionProblem.CONTRADICTORY_OPTIONAL_INTENT else DraftProjectionProblem.UNKNOWN_OPTIONAL_INTENT)
        }
        for (value in current.content.components.selections.filter { it.kind in kinds })
            current = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(value.id, false))))
        val disabledValues = current.content.components.selections.filter { it.kind in kinds }
        current = (store.save(current.version, current.content.withTier(OptionalTier.T3, t3), current.metadata) as DraftSave.Saved).draft
        finding(DraftProjectionProblem.INCOMPLETE_OPTIONAL_SELECTIONS)
        for (value in disabledValues) current = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(value.id, true))))
        assertEquals(disabledValues.map { it.settings }, current.content.components.selections.filter { it.kind in kinds }.map { it.settings })
        val review = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val before = raw(root)
        val result = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val exported = result.candidate.bytes()
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, exported.inputStream()))
        val node = (BoundedJsonReader().read(exported.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(current.content.components.selections.map { it.toJson() }, node["selections"].toList())
        assertEquals(current.content.components.links.map { it.toJson() }, node["links"].toList())
        assertArrayEquals(before, raw(root))
        assertEquals(t3, current.content.optional.getValue(OptionalTier.T3))
    }

    @Test fun `managed retrieval saves preserve guest inputs and incident links while rejecting stale foreign and deleted authority`() {
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
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(other.record, add("data_source"), catalog))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, record))
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, add("data_source"), catalog))
        assertEquals(other.record.baselineIdentity, (library.open(other.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
