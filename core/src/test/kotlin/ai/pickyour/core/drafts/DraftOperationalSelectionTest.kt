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

/** Declared local planning input; no provisioning, telemetry, secret resolution or budget execution. */
class DraftOperationalSelectionTest {
    @TempDir lateinit var root: Path
    private val kinds = listOf("deployment_target", "monitoring_stack", "cost_policy")
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private val entered = mapOf(
        "deployment_target" to """{"environment":"production","replicas_min":999999999999999999999999,"replicas_max":1,"region":"منطقة\n literal ","approval_ticket":""}""",
        "monitoring_stack" to """{"sample_rate":0.123456789123456789,"retention_days":900719925474099312345678,"credential_ref":"secret://synthetic/monitoring:reference-1"}""",
        "cost_policy" to """{"monthly_budget_amount":"900719925474099312345678.000000000001","per_request_budget_amount":"0.100000000001","enforce_hard_cap":false,"notify_at_pct":99.123456789123456789}""",
    )
    private fun item(kind: String): PreviewCatalogItem {
        val value = SelectionFixtures.selection(kind)
        return PreviewCatalogItem(value.catalogItemId, "synthetic planning input", kind, value.catalogItemVersion, "T6")
    }
    private fun prepared(value: PreviewSelectionPreparation) = (value as PreviewSelectionPreparation.Ready).command
    private fun add(kind: String, id: String = "planning_" + kind, role: String = "entered", enabled: Boolean = false,
        settings: String = "{}") = prepared(PreviewSelectionForms.add(id, item(kind), catalog.hash.hex,
        PreviewSelectionForm(role, enabled, settings)))
    private fun ready(content: DraftContent, command: SelectionCommand, source: SelectionCatalogSnapshot? = catalog) =
        DraftSelectionCommands.apply(content, command, source) as SelectionEdit.Ready
    private fun raw(path: Path) =
        (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun saved(result: DraftSelectionSave) = (result as DraftSelectionSave.Saved).draft

    @Test fun allThreeKindsRequireExplicitIdentityAndRemainIndependentOfOptionalIntent() {
        assertEquals(listOf("T3", "T4", "T5"), OptionalTier.entries.map { it.name })
        for (activation in Activation.entries) {
            val original = DraftContent("entered objective", "unknown", "opaque model",
                OptionalTier.entries.associateWith { TierDraft(activation, setOf("retained " + it.name), mapOf("opaque" to "unchanged")) },
                DraftWorkload(region = "entered-region"))
            for (kind in kinds) {
                assertTrue(PreviewSelectionForms.supported(kind))
                assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.add("new_planning", item(kind),
                    catalog.hash.hex, PreviewSelectionForm("", false, "{}")))
                val result = ready(original, add(kind))
                val value = result.content.components.selections.single()
                assertEquals(kind, value.kind)
                assertEquals("planning_" + kind, value.id)
                assertEquals("entered", value.role)
                assertFalse(value.enabled)
                assertEquals("{}", PreviewSelectionForms.settingsText(value))
                assertEquals(item(kind).id, value.catalogItemId)
                assertEquals(item(kind).version, value.catalogItemVersion)
                assertEquals(original, result.content.withComponents(DraftSelections()))
                assertTrue(original.components.selections.isEmpty())
                assertTrue(result.content.components.links.isEmpty())
            }
        }
        for (kind in listOf("future_deployment", "future_monitoring", "future_cost_policy", "unknown"))
            assertFalse(PreviewSelectionForms.supported(kind))
    }

    @Test fun allTwelveFrozenFieldsRetainExactTypesSpellingAndIndependentDeclaredValues() {
        var content = DraftContent()
        for (kind in kinds) content = ready(content, add(kind, enabled = true, settings = entered.getValue(kind))).content
        for (value in content.components.selections) assertEquals(entered.getValue(value.kind), PreviewSelectionForms.settingsText(value))
        val deployment = content.components.selections[0]
        val monitoring = content.components.selections[1]
        val cost = content.components.selections[2]
        assertEquals(BigInteger("999999999999999999999999"), deployment.settings["replicas_min"].bigIntegerValue())
        assertEquals(BigInteger.ONE, deployment.settings["replicas_max"].bigIntegerValue())
        assertEquals("", deployment.settings["approval_ticket"].textValue())
        assertEquals("منطقة\n literal ", deployment.settings["region"].textValue())
        assertEquals(BigDecimal("0.123456789123456789"), monitoring.settings["sample_rate"].decimalValue())
        assertEquals(BigInteger("900719925474099312345678"), monitoring.settings["retention_days"].bigIntegerValue())
        assertTrue(cost.settings["monthly_budget_amount"].isTextual)
        assertEquals("0.100000000001", cost.settings["per_request_budget_amount"].textValue())
        assertFalse(cost.settings["enforce_hard_cap"].booleanValue())
        assertEquals(BigDecimal("99.123456789123456789"), cost.settings["notify_at_pct"].decimalValue())
        for (environment in listOf("development", "staging", "production")) {
            val text = """{"environment":"$environment","replicas_min":0,"replicas_max":1}"""
            val next = ready(content, prepared(PreviewSelectionForms.configure(deployment, text))).content
            assertEquals(text, PreviewSelectionForms.settingsText(next.components.selections[0]))
            assertEquals(listOf(monitoring, cost), next.components.selections.drop(1))
        }
        for ((value, texts) in listOf(
            monitoring to listOf("""{"sample_rate":0,"retention_days":0}""", """{"sample_rate":1,"retention_days":1}"""),
            cost to listOf("""{"monthly_budget_amount":"0","per_request_budget_amount":"0","enforce_hard_cap":true,"notify_at_pct":0}""",
                """{"notify_at_pct":100}"""),
        )) for (text in texts) {
            val changed = ready(content, prepared(PreviewSelectionForms.configure(value, text))).content.components.selections.single { it.id == value.id }
            assertEquals(text, PreviewSelectionForms.settingsText(changed))
        }
        for (value in content.components.selections) {
            val empty = ready(content, prepared(PreviewSelectionForms.configure(value, "{}"))).content.components.selections.single { it.id == value.id }
            assertEquals("{}", PreviewSelectionForms.settingsText(empty))
            assertEquals(value.catalogItemId, empty.catalogItemId)
            assertEquals(value.enabled, empty.enabled)
        }
        assertTrue(content.optional.values.all { it.activation == Activation.DISABLED })
        assertTrue(content.components.links.isEmpty())
    }

    @Test fun malformedRangesTypesUnknownAndProposedRuntimeFieldsCannotChangeSavedBytes() {
        val invalid = mapOf(
            "deployment_target" to listOf("""{"environment":"unknown"}""", """{"environment":"Production"}""", """{"environment":null}""",
                """{"replicas_min":-1}""", """{"replicas_min":0.5}""", """{"replicas_min":"0"}""", """{"replicas_max":0}""",
                """{"region":false}""", """{"approval_ticket":true}""", """{"approved":true}""", """{"deployed":true}""",
                """{"hosting_ref":"cat.entered.host"}""", """{"environment":"staging","environment":"production"}"""),
            "monitoring_stack" to listOf("""{"sample_rate":-0.000000001}""", """{"sample_rate":1.000000001}""",
                """{"sample_rate":"unknown"}""", """{"retention_days":-1}""", """{"retention_days":1.5}""",
                """{"retention_days":"1"}""", """{"credential_ref":null}""", """{"credential_value":"synthetic raw value"}""",
                """{"signals":[]}""", """{"active":true}""", """{"sample_rate":0,"sample_rate":1}"""),
            "cost_policy" to listOf("""{"monthly_budget_amount":0}""", """{"monthly_budget_amount":"01"}""",
                """{"monthly_budget_amount":"-1"}""", """{"monthly_budget_amount":"1,2"}""", """{"monthly_budget_amount":"1e2"}""",
                """{"monthly_budget_amount":"0.1234567890123"}""", """{"per_request_budget_amount":null}""",
                """{"enforce_hard_cap":"true"}""", """{"notify_at_pct":-1}""", """{"notify_at_pct":100.000000001}""",
                """{"notify_at_pct":"50"}""", """{"default_currency":"USD"}""", """{"spent_amount":"0"}""",
                """{"budget_approved":true}""", """{"enforce_hard_cap":false,"enforce_hard_cap":true}"""),
        )
        for (kind in kinds) {
            val path = Files.createDirectory(root.resolve(kind))
            val store = LocalDraftStore(path)
            val initial = (store.save(null, DraftContent()) as DraftSave.Saved).draft
            val before = saved(store.applySelection(DraftSelectionIntent(initial.version, add(kind, settings = entered.getValue(kind))), catalog))
            val bytes = raw(path)
            val value = before.content.components.selections.single()
            for (text in invalid.getValue(kind) + listOf("[]", "{} trailing", """{"unknown":1}""",
                " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
                assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(value, text))
                assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.add("rejected", item(kind), catalog.hash.hex,
                    PreviewSelectionForm("entered", true, text)))
                assertArrayEquals(bytes, raw(path))
            }
            assertEquals(DraftSelectionSave.Rejected(SelectionRejection.INVALID_SETTINGS), store.applySelection(DraftSelectionIntent(
                before.version, SelectionCommand.Configure(value.id, mapper.createObjectNode().put("executed", true)))))
            val reopened = (LocalDraftStore(path).load() as DraftLoad.Available).draft
            assertEquals(before.version.hash, reopened.version.hash)
            assertEquals(before.version.number, reopened.version.number)
            assertEquals(before.content, reopened.content)
            assertArrayEquals(bytes, raw(path))
        }
    }

    @Test fun opaqueSecretReferencesRemainLiteralWhileRawOrMalformedCredentialsAreRejected() {
        val store = LocalDraftStore(root)
        val initial = (store.save(null, DraftContent()) as DraftSave.Saved).draft
        var current = saved(store.applySelection(DraftSelectionIntent(initial.version, add("monitoring_stack")), catalog))
        for (reference in listOf("secret://synthetic/monitoring:reference-1", "secret://synthetic/reference\n")) {
            val settings = mapper.createObjectNode().put("credential_ref", reference).toString()
            current = saved(store.applySelection(DraftSelectionIntent(current.version,
                prepared(PreviewSelectionForms.configure(current.content.components.selections.single(), settings)))))
            assertEquals(reference, current.content.components.selections.single().settings["credential_ref"].textValue())
            assertEquals(settings, PreviewSelectionForms.settingsText((LocalDraftStore(root).load() as DraftLoad.Available).draft.content.components.selections.single()))
        }
        val before = raw(root)
        val original = current
        for (reference in listOf("synthetic raw password", "https://synthetic.invalid/reference", "secret://",
            "secret://synthetic?value=example", "secret://synthetic#fragment", "secret://user:example@synthetic",
            "secret://synthetic reference", "secret://synthetic/reference\n\n")) {
            val form = PreviewSelectionForm("entered", false, mapper.createObjectNode().put("credential_ref", reference).toString())
            val add = PreviewSelectionForms.add("rejected_secret", item("monitoring_stack"), catalog.hash.hex, form)
            val configure = PreviewSelectionForms.configure(current.content.components.selections.single(), form.settingsJson)
            assertEquals(PreviewSelectionPreparation.Invalid, add)
            assertEquals(PreviewSelectionPreparation.Invalid, configure)
            assertFalse(form.toString().contains(reference))
            assertFalse(add.toString().contains(reference))
            assertFalse(configure.toString().contains(reference))
            assertArrayEquals(before, raw(root))
        }
        assertEquals(original.content, (LocalDraftStore(root).load() as DraftLoad.Available).draft.content)
    }

    @Test fun multipleRolesAndSingleActiveCostPolicyKeepEveryOtherSelectionAndIncidentLink() {
        for (kind in kinds) {
            val one = ready(DraftContent(), add(kind, "first_" + kind, "source_a", true, entered.getValue(kind))).content
            if (kind == "cost_policy") assertEquals(SelectionEdit.Rejected(SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED),
                DraftSelectionCommands.apply(one, add(kind, "second_" + kind, "source_b", true), catalog))
            val pair = ready(one, add(kind, "second_" + kind, "source_b", kind != "cost_policy")).content
            assertEquals(if (kind == "cost_policy") 1 else 2, pair.components.selections.count { it.enabled })
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE),
                DraftSelectionCommands.apply(pair, add(kind, "third_" + kind, "source_a", false), catalog))
            val first = pair.components.selections.first()
            val second = pair.components.selections.last()
            assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE),
                DraftSelectionCommands.apply(pair, prepared(PreviewSelectionForms.configureRole(second, first.role))))
            if (kind == "cost_policy") assertEquals(SelectionEdit.Rejected(SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED),
                DraftSelectionCommands.apply(pair, SelectionCommand.SetEnabled(second.id, true)))
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
            val enabled = ready(removed.content, SelectionCommand.SetEnabled(second.id, true)).content
            assertTrue(enabled.components.selections.single { it.id == second.id }.enabled)
        }
    }

    @Test fun exactCatalogReplacementAndPinChecksNeverRewriteEnteredOperationalSettings() {
        val document = SelectionFixtures.catalogNode()
        val originalBytes = mapper.writeValueAsBytes(document)
        val alternatives = kinds.associateWith { kind ->
            val entry = (document["items"].first { it["kind"].asText() == kind } as ObjectNode).deepCopy()
                .put("id", "cat." + kind + ".test_alternative").put("version", "test-planning-2")
            (document["items"] as ArrayNode).add(entry)
            PreviewCatalogItem(entry["id"].asText(), "test-only alternative", kind, entry["version"].asText(), "T6")
        }
        val bytes = mapper.writeValueAsBytes(document)
        val alternate = SelectionCatalogSnapshot.fromJson(bytes)
        for (kind in kinds) {
            val command = add(kind, settings = entered.getValue(kind)) as SelectionCommand.Add
            val source = command.selection
            val content = DraftContent(components = DraftSelections(listOf(source)))
            val replaced = ready(content, prepared(PreviewSelectionForms.replace(source, alternatives.getValue(kind), alternate.hash.hex)), alternate)
                .content.components.selections.single()
            assertEquals(source.id, replaced.id)
            assertEquals(source.role, replaced.role)
            assertEquals(source.enabled, replaced.enabled)
            assertEquals(source.settings, replaced.settings)
            assertEquals(alternatives.getValue(kind).id, replaced.catalogItemId)
            assertEquals(alternatives.getValue(kind).version, replaced.catalogItemVersion)
            for (bad in listOf(command.choice.copy(snapshotHash = ArtifactHash("a".repeat(64))),
                command.choice.copy(kind = kinds.first { it != kind }), command.choice.copy(itemVersion = "latest"),
                command.choice.copy(itemId = "cat.missing.item")))
                assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH),
                    DraftSelectionCommands.apply(DraftContent(), SelectionCommand.Add(source, bad), catalog))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH), DraftSelectionCommands.apply(DraftContent(), command))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH), DraftSelectionCommands.apply(content,
                prepared(PreviewSelectionForms.replace(source, alternatives.getValue(kind), alternate.hash.hex)), catalog))
            val absent = LocalSelection.fromJson(source.toJson().put("catalog_item_version", "absent-version"))
            assertEquals(SelectionEdit.Rejected(SelectionRejection.CATALOG_ITEM_UNAVAILABLE), DraftSelectionCommands.apply(DraftContent(),
                SelectionCommand.Add(absent, command.choice.copy(itemVersion = absent.catalogItemVersion)), catalog))
        }
        assertArrayEquals(bytes, mapper.writeValueAsBytes(document))
        assertArrayEquals(originalBytes, mapper.writeValueAsBytes(SelectionFixtures.catalogNode()))
    }

    @Test fun operationalHistoryPreservesFormatFourThenFiveRoleChangesAndRestoration() {
        val store = LocalDraftStore(root)
        val initial = (store.save(null, DraftContent("unchanged", "unknown"), mapOf("private.note" to "synthetic retained text")) as DraftSave.Saved).draft
        val initialBytes = raw(root)
        var current = initial
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind, settings = entered.getValue(kind))), catalog))
        val added = current
        for (kind in kinds) {
            val previous = current
            current = saved(store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.SetEnabled("planning_" + kind, true))))
            assertEquals(4, mapper.readTree(raw(root))["local_format_version"].intValue())
            assertEquals(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(previous.version, SelectionCommand.Remove("planning_" + kind))))
        }
        val beforeRole = current
        val beforeBytes = raw(root)
        current = saved(store.applySelection(DraftSelectionIntent(current.version,
            prepared(PreviewSelectionForms.configureRole(current.content.components.selections.last(), "candidate_budget")))))
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

    @Test fun unchangedCommandsAndDefensiveCopiesCannotRewriteTheAcknowledgedPlan() {
        val store = LocalDraftStore(root)
        var current = (store.save(null, DraftContent()) as DraftSave.Saved).draft
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version, add(kind, settings = entered.getValue(kind))), catalog))
        val bytes = raw(root)
        for (value in current.content.components.selections) for (command in listOf(
            prepared(PreviewSelectionForms.configure(value, PreviewSelectionForms.settingsText(value))),
            prepared(PreviewSelectionForms.configureRole(value, value.role)), SelectionCommand.SetEnabled(value.id, value.enabled),
            prepared(PreviewSelectionForms.replace(value, item(value.kind), catalog.hash.hex)))) {
            val result = store.applySelection(DraftSelectionIntent(current.version, command), catalog) as DraftSelectionSave.Saved
            assertNull(result.change)
            assertEquals(current.version.number, result.draft.version.number)
            assertEquals(current.version.hash, result.draft.version.hash)
            assertEquals(current.content, result.draft.content)
            assertArrayEquals(bytes, raw(root))
        }
        val cost = current.content.components.selections.last()
        val mutable = mapper.createObjectNode().put("enforce_hard_cap", false)
        val command = SelectionCommand.Configure(cost.id, mutable)
        mutable.put("enforce_hard_cap", true)
        command.settings.put("enforce_hard_cap", true)
        val changed = saved(store.applySelection(DraftSelectionIntent(current.version, command)))
        assertFalse(changed.content.components.selections.last().settings["enforce_hard_cap"].booleanValue())
        changed.content.components.selections.last().settings.put("enforce_hard_cap", true)
        assertFalse(changed.content.components.selections.last().settings["enforce_hard_cap"].booleanValue())
        assertArrayEquals(bytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(current.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun structuralCandidateKeepsDeclaredInputsAndWithholdsMissingHostingWithoutAReadinessVerdict() {
        val source = ProjectionFixtures.node("catalog")
        val store = LocalDraftStore(root)
        var current = (store.save(null, ProjectionFixtures.content(), mapOf("private.note" to "synthetic retained text")) as DraftSave.Saved).draft
        for (kind in kinds) current = saved(store.applySelection(DraftSelectionIntent(current.version,
            add(kind, enabled = true, settings = entered.getValue(kind))), catalog))
        val before = raw(root)
        val review = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs(pricebook = null)) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val bytes = created.candidate.bytes()
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, bytes.inputStream()))
        val node = (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(current.content.components.selections.map { it.toJson() }, node["selections"].toList())
        assertEquals(current.content.components.links.map { it.toJson() }, node["links"].toList())
        for (field in listOf("deployment_manifest", "approved", "deployed", "monitoring_active", "budget_enforced", "readiness", "cost_estimate"))
            assertFalse(node.has(field))
        assertFalse(node["locks"].has("pricebook_id"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("synthetic retained text"))
        assertArrayEquals(before, raw(root))
        val missing = source.deepCopy()
        (missing["items"].single { it["kind"].textValue() == "deployment_target" }["spec"] as ObjectNode)
            .put("hosting_ref", "cat.missing.host")
        val withheld = store.prepareSnapshotCandidate(current.version, ProjectionFixtures.inputs(
            catalog = ProjectionFixtures.artifact("catalog", missing))) as DraftProjectionPreparation.Withheld
        assertTrue(withheld.findings.any { it.problem == DraftProjectionProblem.MISSING_CATALOG_REFERENCE && it.path.endsWith("/spec/hosting_ref") })
        assertArrayEquals(before, raw(root))
    }

    @Test fun managedLibraryPlansRejectStaleForeignAndDeletedSaveAuthority() {
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
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(other.record, add("deployment_target"), catalog))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, record))
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, add("monitoring_stack"), catalog))
        assertEquals(other.record.baselineIdentity, (library.open(other.id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
