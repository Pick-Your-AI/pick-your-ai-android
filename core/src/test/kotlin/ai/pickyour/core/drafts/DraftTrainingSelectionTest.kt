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
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/** Recorded frozen T5 inputs only; no training, rights, pricing or serving qualification. */
class DraftTrainingSelectionTest {
    @TempDir lateinit var root: Path
    private val kinds = listOf("training_framework", "training_compute", "training_dataset", "fine_tuned_model")
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private val entered = mapOf(
        "training_framework" to """{"method":"qlora","epochs":2.5000000000000000001,"learning_rate":0.000125,"batch_size":9007199254740993}""",
        "training_compute" to """{"gpu_hours_budget":9007199254740993.123456789,"spot_allowed":false,"checkpoint_interval_minutes":999999999999999999999999}""",
        "training_dataset" to """{"train_split":0.9000000000000000001,"eval_split":0.2000000000000000001,"test_split":0,"redaction_enabled":false}""",
        "fine_tuned_model" to """{"use_adapter":false,"artifact_uri":"https://artifacts.example.invalid/model/%2F?q=exact#revision","revision":"  entered 東京 \n"}""",
    )
    private fun item(kind: String): PreviewCatalogItem {
        val value = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(value.catalogItemId, "synthetic training input", kind, value.catalogItemVersion, "T5")
    }
    private fun prepared(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun add(kind: String, id: String = "training_$kind", role: String = "entered", enabled: Boolean = false,
        settings: String = "{}") = prepared(PreviewSelectionForms.add(id, item(kind), catalog.hash.hex,
        PreviewSelectionForm(role, enabled, settings)))
    private fun ready(content: DraftContent, command: SelectionCommand, source: SelectionCatalogSnapshot? = catalog) =
        DraftSelectionCommands.apply(content, command, source) as SelectionEdit.Ready
    private fun raw(path: Path) = (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun saved(result: DraftSelectionSave) = (result as DraftSelectionSave.Saved).draft

    @Test fun `all four training kinds require explicit identity role and component state without changing optional intent`() {
        val original = DraftContent("entered objective", "entered context", "opaque model",
            OptionalTier.entries.associateWith { TierDraft(Activation.UNKNOWN, setOf("retained ${it.name}"), mapOf("opaque" to "unchanged")) },
            DraftWorkload(region = "entered-region"))
        for (kind in kinds) {
            assertTrue(PreviewSelectionForms.supported(kind))
            assertEquals(PreviewSelectionPreparation.Invalid,
                PreviewSelectionForms.add("new_training", item(kind), catalog.hash.hex, PreviewSelectionForm("", false, "{}")))
            val result = ready(original, add(kind))
            val value = result.content.components.selections.single()
            assertEquals(kind, value.kind)
            assertEquals("training_$kind", value.id)
            assertEquals("entered", value.role)
            assertFalse(value.enabled)
            assertEquals("{}", PreviewSelectionForms.settingsText(value))
            assertEquals(item(kind).id, value.catalogItemId)
            assertEquals(item(kind).version, value.catalogItemVersion)
            assertTrue(result.content.components.links.isEmpty())
            assertEquals(original, result.content.withComponents(DraftSelections()))
            assertTrue(original.components.selections.isEmpty())
        }
        assertFalse(PreviewSelectionForms.supported("deployment_target"))
        assertFalse(PreviewSelectionForms.supported("cost_policy"))
    }

    @Test fun `all fourteen frozen training fields retain exact values and all six methods remain explicit inputs`() {
        var content = DraftContent()
        for (kind in kinds) content = ready(content, add(kind, enabled = true, settings = entered.getValue(kind))).content
        for (value in content.components.selections) {
            assertEquals(entered.getValue(value.kind), PreviewSelectionForms.settingsText(value))
            assertTrue(value.enabled)
            assertEquals(Activation.DISABLED, content.optional.getValue(OptionalTier.T5).activation)
        }
        val framework = content.components.selections.first()
        assertEquals(BigInteger("9007199254740993"), framework.settings["batch_size"].bigIntegerValue())
        assertEquals(BigDecimal("2.5000000000000000001"), framework.settings["epochs"].decimalValue())
        val compute = content.components.selections.single { it.kind == "training_compute" }
        assertEquals(BigDecimal("9007199254740993.123456789"), compute.settings["gpu_hours_budget"].decimalValue())
        val dataset = content.components.selections.single { it.kind == "training_dataset" }
        assertTrue(dataset.settings["train_split"].decimalValue() + dataset.settings["eval_split"].decimalValue() > BigDecimal.ONE)
        assertEquals(BigDecimal.ZERO, dataset.settings["test_split"].decimalValue())
        assertFalse(dataset.settings["redaction_enabled"].booleanValue())
        // v1 bounds each fraction individually; a new normalization or sum constraint is not adopted.
        for (method in listOf("sft", "lora", "qlora", "dpo", "full_finetune", "distillation")) {
            val updated = ready(content, prepared(PreviewSelectionForms.configure(framework, """{"method":"$method"}"""))).content
            assertEquals(method, updated.components.selections.first().settings["method"].textValue())
            assertEquals(framework.catalogItemId, updated.components.selections.first().catalogItemId)
            assertFalse(updated.components.selections.first().settings.has("epochs"))
        }
        val artifact = content.components.selections.last()
        val emptyRevision = ready(content, prepared(PreviewSelectionForms.configure(artifact, """{"revision":""}"""))).content.components.selections.last()
        assertEquals("", emptyRevision.settings["revision"].textValue())
        assertFalse(emptyRevision.settings.has("use_adapter"))
        assertEquals("  entered 東京 \n", artifact.settings["revision"].textValue())
        assertTrue(content.components.links.isEmpty())
    }

    @Test fun `invalid methods ranges types URIs and proposed fields leave the complete durable input unchanged`() {
        val invalid = mapOf(
            "training_framework" to listOf("""{"method":"grpo"}""", """{"method":"LoRA"}""", """{"epochs":0}""",
                """{"learning_rate":-1}""", """{"batch_size":1.5}""", """{"batch_size":"1"}""", """{"epochs":1,"epochs":2}"""),
            "training_compute" to listOf("""{"gpu_hours_budget":-0.1}""", """{"spot_allowed":"false"}""",
                """{"checkpoint_interval_minutes":0}""", """{"checkpoint_interval_minutes":0.5}""", """{"compute_profile_ref":"cat.compute_profile.fixture"}"""),
            "training_dataset" to listOf("""{"train_split":-0.1}""", """{"eval_split":1.0000000000000000001}""",
                """{"test_split":null}""", """{"redaction_enabled":0}""", """{"license_verified":true}""", """{"privacy_approved":true}""", """{"data_source_refs":[]}"""),
            "fine_tuned_model" to listOf("""{"use_adapter":null}""", """{"artifact_uri":"not a URI"}""", """{"artifact_uri":"/relative"}""",
                """{"artifact_uri":"https://example.invalid/a b"}""", """{"revision":null}""", """{"base_model_ref":"cat.model.fixture"}""",
                """{"base_model_revision":"unadopted"}""", """{"evaluation_report_ref":"unadopted"}""", """{"credential_ref":"secret://not-a-v1-setting"}"""),
        )
        for (kind in kinds) {
            val path = Files.createDirectory(root.resolve(kind))
            val store = LocalDraftStore(path)
            val initial = (store.save(null, DraftContent()) as DraftSave.Saved).draft
            val before = saved(store.applySelection(DraftSelectionIntent(initial.version, add(kind, settings = entered.getValue(kind))), catalog))
            val bytes = raw(path)
            val value = before.content.components.selections.single()
            for (text in invalid.getValue(kind) + listOf("[]", "{} trailing", """{"unknown":1}""", "{\"revision\":\"${"x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT)}\"}")) {
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

    @Test fun `all training kinds allow separate roles while duplicate roles and selective removal preserve other inputs`() {
        for (kind in kinds) {
            val one = ready(DraftContent(), add(kind, "first_$kind", "source_a", true, entered.getValue(kind))).content
            val pair = ready(one, add(kind, "second_$kind", "source_b", true)).content
            assertEquals(2, pair.components.selections.count { it.enabled })
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE),
                DraftSelectionCommands.apply(pair, add(kind, "third_$kind", "source_a", false), catalog))
            val first = pair.components.selections.first()
            val second = pair.components.selections.last()
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE), DraftSelectionCommands.apply(pair,
                prepared(PreviewSelectionForms.configureRole(second, first.role))))
            val disabled = ready(pair, SelectionCommand.SetEnabled(first.id, false)).content
            assertEquals(first.settings, disabled.components.selections.first().settings)
            assertEquals(second, disabled.components.selections.last())
            val removed = ready(disabled, SelectionCommand.Remove(first.id)).content
            assertEquals(listOf(second), removed.components.selections)
            assertEquals("source_b", removed.components.selections.single().role)
            assertEquals(2, pair.components.selections.count { it.enabled })
            assertEquals(pair.optional, removed.optional)
        }
    }

    @Test fun `training add and replacement check exact catalog pins and preserve settings identity and incident links`() {
        val document = SelectionFixtures.catalogNode()
        val beforeDocument = mapper.writeValueAsBytes(document)
        val alternatives = kinds.associateWith { kind ->
            val entry = (document["items"].first { it["kind"].asText() == kind } as ObjectNode).deepCopy()
                .put("id", "cat.$kind.test_alternative").put("version", "test-training-2")
            (document["items"] as ArrayNode).add(entry)
            PreviewCatalogItem(entry["id"].asText(), "test-only alternative", kind, entry["version"].asText(), "T5")
        }
        val alternateCatalog = SelectionCatalogSnapshot.fromJson(mapper.writeValueAsBytes(document))
        val bytes = mapper.writeValueAsBytes(document)
        for (kind in kinds) {
            val command = add(kind, settings = entered.getValue(kind)) as SelectionCommand.Add
            val source = command.selection
            val other = SelectionFixtures.selection("model", "other_model")
            val link = SelectionFixtures.link("link.training", source.id, other.id, true)
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
        assertArrayEquals(bytes, mapper.writeValueAsBytes(document))
        assertArrayEquals(beforeDocument, mapper.writeValueAsBytes(SelectionFixtures.catalogNode()))
    }

    @Test fun `training history retains exact assumptions and old bytes through role upgrade reopen and restore`() {
        val store = LocalDraftStore(root)
        val initial = (store.save(null, DraftContent("unchanged", optional = mapOf(OptionalTier.T5 to
            TierDraft(Activation.UNKNOWN, setOf("opaque training path"), mapOf("rights" to "unknown", "price" to "unavailable")))),
            mapOf("retained.evidence" to "exact reference")) as DraftSave.Saved).draft
        val initialBytes = raw(root)
        var current = initial
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind, settings = entered.getValue(kind))), catalog))
        val added = current
        val artifact = current.content.components.selections.single { it.kind == "fine_tuned_model" }
        current = saved(store.applySelection(DraftSelectionIntent(current.version, prepared(PreviewSelectionForms.configure(artifact,
            """{"use_adapter":true,"artifact_uri":"custom+local://artifact.example.invalid/exact","revision":""}""")))))
        for (kind in kinds) {
            val previous = current
            current = saved(store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.SetEnabled("training_$kind", true))))
            assertEquals(4, mapper.readTree(raw(root))["local_format_version"].intValue())
            assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.Remove(artifact.id))))
            assertEquals(initial.content.optional, current.content.optional)
            assertEquals(initial.metadata, current.metadata)
        }
        val beforeRole = current
        val beforeBytes = raw(root)
        current = saved(store.applySelection(DraftSelectionIntent(current.version,
            prepared(PreviewSelectionForms.configureRole(current.content.components.selections.single { it.id == artifact.id }, "derived_candidate")))))
        assertEquals(5, mapper.readTree(raw(root))["local_format_version"].intValue())
        assertEquals(SelectionAction.CONFIGURE_ROLE, current.history.last().selectionChange!!.action)
        assertEquals(beforeRole.history.map { it.content }, current.history.dropLast(1).map { it.content })
        assertEquals(beforeRole.content.components.selections.single { it.id == artifact.id }.settings,
            current.content.components.selections.single { it.id == artifact.id }.settings)
        assertEquals(current.content, (LocalDraftStore(root).load() as DraftLoad.Available).draft.content)
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertArrayEquals(initialBytes, (cache.pinned(initial.version.hash) as CacheRead.Available).bytes)
        assertArrayEquals(beforeBytes, (cache.pinned(beforeRole.version.hash) as CacheRead.Available).bytes)
        val restored = (store.restore(current.version, added.version.number) as DraftSave.Saved).draft
        assertEquals(added.content, restored.content)
        assertEquals(current.version.number + 1, restored.version.number)
        assertEquals(initial.metadata, restored.metadata)
    }

    @Test fun `unchanged training commands reuse the durable revision and defensive copies cannot modify entered assumptions`() {
        val store = LocalDraftStore(root)
        var current = (store.save(null, DraftContent()) as DraftSave.Saved).draft
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind, settings = entered.getValue(kind))), catalog))
        val bytes = raw(root)
        for (value in current.content.components.selections) {
            for (command in listOf(prepared(PreviewSelectionForms.configure(value, PreviewSelectionForms.settingsText(value))),
                prepared(PreviewSelectionForms.configureRole(value, value.role)), SelectionCommand.SetEnabled(value.id, value.enabled),
                prepared(PreviewSelectionForms.replace(value, item(value.kind), catalog.hash.hex)))) {
                val result = store.applySelection(DraftSelectionIntent(current.version, command), catalog) as DraftSelectionSave.Saved
                assertNull(result.change)
                assertEquals(current.version.number, result.draft.version.number)
                assertEquals(current.version.hash, result.draft.version.hash)
                assertEquals(current.content, result.draft.content)
                assertArrayEquals(bytes, raw(root))
            }
        }
        val framework = current.content.components.selections.first()
        val mutable = mapper.createObjectNode().put("method", "sft")
        val command = SelectionCommand.Configure(framework.id, mutable)
        mutable.put("method", "distillation")
        command.settings.put("method", "qlora")
        val changed = saved(store.applySelection(DraftSelectionIntent(current.version, command)))
        assertEquals("sft", changed.content.components.selections.first().settings["method"].textValue())
        assertEquals(entered.getValue(framework.kind), PreviewSelectionForms.settingsText(framework))
        assertEquals(current.content, (store.revision(current.version.hash) as DraftLoad.Available).draft.content)
    }

    @Test fun `T5 intent remains independent and structural snapshot preparation neither trains nor fabricates prices`() {
        val complete = ProjectionFixtures.content(allKinds = true)
        val t5 = complete.optional.getValue(OptionalTier.T5)
        val store = LocalDraftStore(root)
        var current = (store.save(null, complete) as DraftSave.Saved).draft
        fun finding(problem: DraftProjectionProblem) {
            val result = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Withheld
            assertTrue(result.findings.any { it.problem == problem && it.path == "/content/optional/T5/activation" })
        }
        for (activation in listOf(Activation.DISABLED, Activation.UNKNOWN)) {
            current = (store.save(current.version, current.content.withTier(OptionalTier.T5,
                TierDraft(activation, t5.selections, t5.settings)), current.metadata) as DraftSave.Saved).draft
            val before = current.content
            val framework = before.components.selections.single { it.kind == "training_framework" }
            current = saved(store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configure(framework, entered.getValue(framework.kind))))))
            assertEquals(before.optional, current.content.optional)
            assertTrue(current.content.components.selections.single { it.kind == "training_framework" }.enabled)
            finding(if (activation == Activation.DISABLED) DraftProjectionProblem.CONTRADICTORY_OPTIONAL_INTENT else DraftProjectionProblem.UNKNOWN_OPTIONAL_INTENT)
        }
        for (value in current.content.components.selections.filter { it.kind in kinds })
            current = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(value.id, false))))
        val retained = current.content.components.selections.filter { it.kind in kinds }
        current = (store.save(current.version, current.content.withTier(OptionalTier.T5, t5), current.metadata) as DraftSave.Saved).draft
        finding(DraftProjectionProblem.INCOMPLETE_OPTIONAL_SELECTIONS)
        val framework = retained.single { it.kind == "training_framework" }
        current = saved(store.applySelection(DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(framework.id, true))))
        assertEquals(1, current.content.components.selections.count { it.kind in kinds && it.enabled })
        assertEquals(retained.map { it.settings }, current.content.components.selections.filter { it.kind in kinds }.map { it.settings })
        val review = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs(pricebook = null)) as DraftProjectionPreparation.Review
        val before = raw(root)
        val result = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val exported = result.candidate.bytes()
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, exported.inputStream()))
        val node = (BoundedJsonReader().read(exported.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(current.content.components.selections.map { it.toJson() }, node["selections"].toList())
        assertEquals(current.content.components.links.map { it.toJson() }, node["links"].toList())
        assertFalse(node["locks"].has("pricebook_id"))
        assertFalse(node.has("cost_estimate"))
        assertFalse(node.has("evaluation_report"))
        assertArrayEquals(before, raw(root))
        assertEquals(t5, current.content.optional.getValue(OptionalTier.T5))
    }

    @Test fun `training settings preserve catalog owned lineage and unresolved targets withhold snapshot creation`() {
        val source = ProjectionFixtures.node("catalog")
        val sourceBytes = ProjectionFixtures.bytes(source)
        val store = LocalDraftStore(root)
        var current = (store.save(null, ProjectionFixtures.content(allKinds = true)) as DraftSave.Saved).draft
        val original = current.content.components
        for (kind in kinds) {
            val value = current.content.components.selections.single { it.kind == kind }
            current = saved(store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configure(value, entered.getValue(kind))))))
            val changed = current.content.components.selections.single { it.kind == kind }
            assertEquals(value.catalogItemId, changed.catalogItemId)
            assertEquals(value.catalogItemVersion, changed.catalogItemVersion)
            assertEquals(value.id, changed.id)
        }
        assertEquals(original.links, current.content.components.links)
        assertArrayEquals(sourceBytes, ProjectionFixtures.bytes(source))
        val record = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(current.content, record.content)
        val before = raw(root)
        for ((kind, field) in listOf("fine_tuned_model" to "base_model_ref", "fine_tuned_model" to "training_framework_ref",
            "fine_tuned_model" to "training_dataset_ref", "training_dataset" to "data_source_refs", "training_compute" to "compute_profile_ref")) {
            val catalogNode = source.deepCopy()
            val spec = catalogNode["items"].single { it["kind"].textValue() == kind }["spec"] as ObjectNode
            if (spec[field].isArray) spec.putArray(field).add("cat.missing.lineage") else spec.put(field, "cat.missing.lineage")
            val result = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs(
                catalog = ProjectionFixtures.artifact("catalog", catalogNode))) as DraftProjectionPreparation.Withheld
            assertTrue(result.findings.any { it.problem == DraftProjectionProblem.MISSING_CATALOG_REFERENCE && it.path.endsWith("/spec/$field") })
            assertArrayEquals(before, raw(root))
        }
        assertArrayEquals(sourceBytes, ProjectionFixtures.bytes(source))
        assertEquals(current.content, (LocalDraftStore(root).load() as DraftLoad.Available).draft.content)
    }

    @Test fun `managed training edits retain guest context while stale foreign and deleted authority cannot save`() {
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
            record = (adapter.applySelection(previous, add(kind, settings = entered.getValue(kind)), catalog) as PreviewSelectionSave.Saved).record
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
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(other.record, add("training_dataset"), catalog))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, record))
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, add("training_dataset"), catalog))
        assertEquals(other.record.baselineIdentity, (library.open(other.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
