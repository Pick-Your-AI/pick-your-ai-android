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

/** Frozen planning inputs only; no control execution, measurement or readiness verdict. */
class DraftGuardrailSelectionTest {
    @TempDir lateinit var root: Path
    private val kinds = listOf("guardrail", "evaluation_suite")
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private val entered = mapOf(
        "guardrail" to """{"enforcement":"audit","failure_action":"fail_open"}""",
        "evaluation_suite" to """{"minimum_score":-9007199254740993.123456789,"gate_required":false,"dataset_size":999999999999999999999999}""",
    )
    private fun item(kind: String): PreviewCatalogItem {
        val value = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(value.catalogItemId, "synthetic core planning input", kind, value.catalogItemVersion, "T6")
    }
    private fun prepared(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun add(kind: String, id: String = "core_$kind", role: String = "entered", enabled: Boolean = false,
        settings: String = "{}") = prepared(PreviewSelectionForms.add(id, item(kind), catalog.hash.hex,
        PreviewSelectionForm(role, enabled, settings)))
    private fun ready(content: DraftContent, command: SelectionCommand, source: SelectionCatalogSnapshot? = catalog) =
        DraftSelectionCommands.apply(content, command, source) as SelectionEdit.Ready
    private fun raw(path: Path) = (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun saved(result: DraftSelectionSave) = (result as DraftSelectionSave.Saved).draft

    @Test fun `guardrail and evaluation inputs remain explicit and independent of every optional tier`() {
        assertEquals(listOf("T3", "T4", "T5"), OptionalTier.entries.map { it.name })
        for (activation in Activation.entries) {
            val original = DraftContent("entered objective", "unknown", "opaque model",
                OptionalTier.entries.associateWith { TierDraft(activation, setOf("retained ${it.name}"), mapOf("opaque" to "unchanged")) },
                DraftWorkload(region = "entered-region"))
            for (kind in kinds) {
                assertTrue(PreviewSelectionForms.supported(kind))
                assertEquals(PreviewSelectionPreparation.Invalid,
                    PreviewSelectionForms.add("new_core", item(kind), catalog.hash.hex, PreviewSelectionForm("", false, "{}")))
                val result = ready(original, add(kind))
                val value = result.content.components.selections.single()
                assertEquals(kind, value.kind)
                assertEquals("core_$kind", value.id)
                assertEquals("entered", value.role)
                assertFalse(value.enabled)
                assertEquals("{}", PreviewSelectionForms.settingsText(value))
                assertEquals(item(kind).id, value.catalogItemId)
                assertEquals(item(kind).version, value.catalogItemVersion)
                assertTrue(result.content.components.links.isEmpty())
                assertEquals(original, result.content.withComponents(DraftSelections()))
                assertTrue(original.components.selections.isEmpty())
            }
        }
        for (kind in listOf("deployment_target", "monitoring_stack", "cost_policy", "future_guardrail", "unknown"))
            assertFalse(PreviewSelectionForms.supported(kind))
    }

    @Test fun `five frozen fields retain exact values without recommending a threshold or enforcing a control`() {
        var content = DraftContent()
        for (kind in kinds) content = ready(content, add(kind, enabled = true, settings = entered.getValue(kind))).content
        for (value in content.components.selections) assertEquals(entered.getValue(value.kind), PreviewSelectionForms.settingsText(value))
        val guardrail = content.components.selections.first()
        val evaluation = content.components.selections.last()
        assertEquals(BigDecimal("-9007199254740993.123456789"), evaluation.settings["minimum_score"].decimalValue())
        assertEquals(BigInteger("999999999999999999999999"), evaluation.settings["dataset_size"].bigIntegerValue())
        assertFalse(evaluation.settings["gate_required"].booleanValue())
        for (enforcement in listOf("audit", "block", "redact", "escalate")) {
            for (failure in listOf("fail_closed", "fail_open")) {
                val text = """{"enforcement":"$enforcement","failure_action":"$failure"}"""
                val next = ready(content, prepared(PreviewSelectionForms.configure(guardrail, text))).content
                assertEquals(text, PreviewSelectionForms.settingsText(next.components.selections.first()))
                assertEquals(content.optional, next.optional)
                assertEquals(evaluation, next.components.selections.last())
            }
        }
        // The metric's range/direction and threshold rationale are not defined by these five settings.
        for (score in listOf("-2", "0", "2", "9007199254740993.123456789")) {
            val next = ready(content, prepared(PreviewSelectionForms.configure(evaluation,
                """{"minimum_score":$score,"gate_required":true,"dataset_size":1}"""))).content.components.selections.last()
            assertEquals(0, BigDecimal(score).compareTo(next.settings["minimum_score"].decimalValue()))
            assertTrue(next.settings["gate_required"].booleanValue())
        }
        for (value in content.components.selections) {
            val empty = ready(content, prepared(PreviewSelectionForms.configure(value, "{}"))).content
                .components.selections.single { it.id == value.id }
            assertEquals("{}", PreviewSelectionForms.settingsText(empty))
            assertEquals(value.catalogItemId, empty.catalogItemId)
            assertEquals(value.enabled, empty.enabled)
        }
        assertTrue(content.optional.values.all { it.activation == Activation.DISABLED })
        assertTrue(content.components.links.isEmpty())
    }

    @Test fun `invalid enum type unknown and proposed evidence fields cannot change a durable revision`() {
        val invalid = mapOf(
            "guardrail" to listOf("""{"enforcement":"unknown"}""", """{"enforcement":"BLOCK"}""",
                """{"enforcement":"flag"}""", """{"failure_action":"fail-closed"}""", """{"failure_action":null}""",
                """{"enforcement":false}""", """{"targets":[]}""", """{"policies":[]}""",
                """{"enforced":true}""", """{"safety_approved":true}""", """{"enforcement":"audit","enforcement":"block"}"""),
            "evaluation_suite" to listOf("""{"minimum_score":"unknown"}""", """{"minimum_score":null}""",
                """{"minimum_score":true}""", """{"gate_required":"false"}""", """{"dataset_size":0}""",
                """{"dataset_size":-1}""", """{"dataset_size":1.5}""", """{"dataset_size":"1"}""",
                """{"metric_names":[]}""", """{"dataset_refs":[]}""", """{"evaluation_report_ref":"entered"}""",
                """{"measured_score":1}""", """{"quality_verified":true}""", """{"minimum_score":1,"minimum_score":2}"""),
        )
        for (kind in kinds) {
            val path = Files.createDirectory(root.resolve(kind))
            val store = LocalDraftStore(path)
            val initial = (store.save(null, DraftContent()) as DraftSave.Saved).draft
            val before = saved(store.applySelection(DraftSelectionIntent(initial.version, add(kind, settings = entered.getValue(kind))), catalog))
            val bytes = raw(path)
            val value = before.content.components.selections.single()
            for (text in invalid.getValue(kind) + listOf("[]", "{} trailing", """{"unknown":1}""", " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
                assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(value, text), "$kind: $text")
                assertArrayEquals(bytes, raw(path))
            }
            assertEquals(DraftSelectionSave.Rejected(SelectionRejection.INVALID_SETTINGS), store.applySelection(DraftSelectionIntent(
                before.version, SelectionCommand.Configure(value.id, mapper.createObjectNode().put("measured", true)))))
            val reopened = (LocalDraftStore(path).load() as DraftLoad.Available).draft
            assertEquals(before.version.number, reopened.version.number)
            assertEquals(before.version.hash, reopened.version.hash)
            assertEquals(before.content, reopened.content)
            assertArrayEquals(bytes, raw(path))
        }
    }

    @Test fun `multiple enabled roles and selective removal preserve other components and incident review`() {
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
            val other = SelectionFixtures.selection("model", "other_model")
            val incident = SelectionFixtures.link("link.incident", first.id, other.id, true)
            val retained = SelectionFixtures.link("link.retained", second.id, other.id, false)
            val linked = pair.withComponents(DraftSelections(pair.components.selections + other, listOf(incident, retained)))
            val disabled = ready(linked, SelectionCommand.SetEnabled(first.id, false))
            assertEquals(listOf(incident.id), disabled.change.affectedLinkIds)
            assertEquals(first.settings, disabled.content.components.selections.first().settings)
            assertEquals(linked.components.links, disabled.content.components.links)
            val removed = ready(disabled.content, SelectionCommand.Remove(first.id))
            assertEquals(listOf(second, other), removed.content.components.selections)
            assertEquals(listOf(incident.id), removed.change.removedLinkIds)
            assertEquals(listOf(retained), removed.content.components.links)
            assertEquals(pair.optional, removed.content.optional)
        }
    }

    @Test fun `catalog replacement checks exact pins without rewriting targets policies metrics or datasets`() {
        val document = SelectionFixtures.catalogNode()
        val originalBytes = mapper.writeValueAsBytes(document)
        val alternatives = kinds.associateWith { kind ->
            val entry = (document["items"].first { it["kind"].asText() == kind } as ObjectNode).deepCopy()
                .put("id", "cat.$kind.test_alternative").put("version", "test-core-2")
            (document["items"] as ArrayNode).add(entry)
            PreviewCatalogItem(entry["id"].asText(), "test-only alternative", kind, entry["version"].asText(), "T6")
        }
        val bytes = mapper.writeValueAsBytes(document)
        val alternateCatalog = SelectionCatalogSnapshot.fromJson(bytes)
        for (kind in kinds) {
            val command = add(kind, settings = entered.getValue(kind)) as SelectionCommand.Add
            val source = command.selection
            val content = DraftContent(components = DraftSelections(listOf(source)))
            val replaced = ready(content, prepared(PreviewSelectionForms.replace(source, alternatives.getValue(kind), alternateCatalog.hash.hex)), alternateCatalog)
                .content.components.selections.single()
            assertEquals(source.id, replaced.id)
            assertEquals(source.role, replaced.role)
            assertEquals(source.enabled, replaced.enabled)
            assertEquals(source.settings, replaced.settings)
            assertEquals(alternatives.getValue(kind).id, replaced.catalogItemId)
            assertEquals(alternatives.getValue(kind).version, replaced.catalogItemVersion)
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
        assertArrayEquals(originalBytes, mapper.writeValueAsBytes(SelectionFixtures.catalogNode()))
    }

    @Test fun `guardrail evaluation history keeps exact inputs through format five role changes and restore`() {
        val store = LocalDraftStore(root)
        val initial = (store.save(null, DraftContent("unchanged", "unknown", optional = OptionalTier.entries.associateWith {
            TierDraft(Activation.DISABLED, setOf("retained ${it.name}"), mapOf("evidence" to "unknown")) }),
            mapOf("retained.evidence" to "exact reference")) as DraftSave.Saved).draft
        val initialBytes = raw(root)
        var current = initial
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind, settings = entered.getValue(kind))), catalog))
        val added = current
        for (kind in kinds) {
            val previous = current
            current = saved(store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.SetEnabled("core_$kind", true))))
            assertEquals(4, mapper.readTree(raw(root))["local_format_version"].intValue())
            assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.Remove("core_$kind"))))
        }
        val beforeRole = current
        val beforeBytes = raw(root)
        current = saved(store.applySelection(DraftSelectionIntent(current.version,
            prepared(PreviewSelectionForms.configureRole(current.content.components.selections.last(), "candidate_gate")))))
        assertEquals(5, mapper.readTree(raw(root))["local_format_version"].intValue())
        assertEquals(SelectionAction.CONFIGURE_ROLE, current.history.last().selectionChange!!.action)
        assertEquals(beforeRole.history.map { it.content }, current.history.dropLast(1).map { it.content })
        assertEquals(beforeRole.content.components.selections.last().settings, current.content.components.selections.last().settings)
        assertEquals(initial.content.optional, current.content.optional)
        assertEquals(initial.metadata, current.metadata)
        assertEquals(current.content, (LocalDraftStore(root).load() as DraftLoad.Available).draft.content)
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertArrayEquals(initialBytes, (cache.pinned(initial.version.hash) as CacheRead.Available).bytes)
        assertArrayEquals(beforeBytes, (cache.pinned(beforeRole.version.hash) as CacheRead.Available).bytes)
        val restored = (store.restore(current.version, added.version.number) as DraftSave.Saved).draft
        assertEquals(added.content, restored.content)
        assertEquals(current.version.number + 1, restored.version.number)
        assertEquals(initial.metadata, restored.metadata)
    }

    @Test fun `unchanged inputs reuse the acknowledged revision and mutable callers cannot rewrite saved assumptions`() {
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
        val evaluation = current.content.components.selections.last()
        val mutable = mapper.createObjectNode().put("gate_required", false)
        val command = SelectionCommand.Configure(evaluation.id, mutable)
        mutable.put("gate_required", true)
        command.settings.put("gate_required", true)
        val changed = saved(store.applySelection(DraftSelectionIntent(current.version, command)))
        assertFalse(changed.content.components.selections.last().settings["gate_required"].booleanValue())
        assertEquals(entered.getValue(evaluation.kind), PreviewSelectionForms.settingsText(evaluation))
        assertEquals(current.content, (store.revision(current.version.hash) as DraftLoad.Available).draft.content)
    }

    @Test fun `core only structural projection keeps configured T6 inputs without inventing an evaluation or readiness result`() {
        val source = ProjectionFixtures.node("catalog")
        val catalogBytes = ProjectionFixtures.bytes(source)
        val store = LocalDraftStore(root)
        var current = (store.save(null, ProjectionFixtures.content()) as DraftSave.Saved).draft
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version,
            add(kind, enabled = true, settings = entered.getValue(kind))), catalog))
        assertTrue(current.content.optional.values.all { it.activation == Activation.DISABLED })
        val before = raw(root)
        val review = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs(pricebook = null)) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val exported = created.candidate.bytes()
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, exported.inputStream()))
        val node = (BoundedJsonReader().read(exported.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(current.content.components.selections.map { it.toJson() }, node["selections"].toList())
        assertEquals(current.content.components.links.map { it.toJson() }, node["links"].toList())
        for (field in listOf("evaluation_report", "quality_evaluation_run", "readiness", "safety_approved", "cost_estimate")) assertFalse(node.has(field))
        assertFalse(node["locks"].has("pricebook_id"))
        assertArrayEquals(before, raw(root))
        val missing = source.deepCopy()
        (missing["items"].single { it["kind"].textValue() == "evaluation_suite" }["spec"] as ObjectNode)
            .putArray("dataset_refs").add("cat.missing.evaluation_dataset")
        val withheld = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs(
            catalog = ProjectionFixtures.artifact("catalog", missing))) as DraftProjectionPreparation.Withheld
        assertTrue(withheld.findings.any { it.problem == DraftProjectionProblem.MISSING_CATALOG_REFERENCE && it.path.endsWith("/spec/dataset_refs") })
        assertArrayEquals(catalogBytes, ProjectionFixtures.bytes(source))
        assertArrayEquals(before, raw(root))
    }

    @Test fun `managed core guidance edits preserve guest context and reject stale foreign or deleted save authority`() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val fields = PreviewDraftFields("entered title", "opaque sample", "exact-version", "b".repeat(64))
        var record = (adapter.save(created.record, fields, PreviewDraftContext("entered goal", "unknown"),
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
        val other = library.create() as PreviewLibraryCreate.Saved
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(other.record, add("guardrail"), catalog))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, record))
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, add("evaluation_suite"), catalog))
        assertEquals(other.record.baselineIdentity, (library.open(other.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
