package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path

class PreviewDeploymentTargetSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("deployment_target", "sel_deployment", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewDeploymentTargetInput) =
        (PreviewDeploymentTargetSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewDeploymentTargetInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewDeploymentTargetInput, key: String, problem: PreviewDeploymentTargetProblem? = null) {
        val result = PreviewDeploymentTargetSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes

    @Test fun fiveOptionalFieldsUseFrozenEnumsTypesAndIndependentBounds() {
        val fields = PreviewDeploymentTargetSettings.fields
        assertEquals(listOf("environment", "replicas_min", "replicas_max", "region", "approval_ticket"), fields.map { it.key })
        assertTrue(fields.none { it.required })
        assertEquals(listOf(PreviewSettingType.STRING, PreviewSettingType.INTEGER, PreviewSettingType.INTEGER,
            PreviewSettingType.STRING, PreviewSettingType.STRING), fields.map { it.type })
        assertEquals(listOf("development", "staging", "production"), fields[0].enumValues)
        assertEquals(BigDecimal.ZERO, fields[1].minimum)
        assertEquals(BigDecimal.ONE, fields[2].minimum)
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.minimumLength == null &&
            it.pattern == null && it.format == null })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fields[0].enumValues as MutableList).clear() }
    }

    @Test fun absenceZeroAndEmptyLiteralStringsRemainDistinctWithoutDefaults() {
        val source = selection()
        assertEquals(PreviewDeploymentTargetInput(), PreviewDeploymentTargetSettings.initial(source))
        assertEquals(source, configured(source, PreviewDeploymentTargetInput()))
        val changed = configured(source, PreviewDeploymentTargetInput(replicasMin = "0", region = "", approvalTicket = ""))
        assertEquals(3, changed.settings.size())
        assertEquals(BigInteger.ZERO, changed.settings["replicas_min"].bigIntegerValue())
        assertEquals("", changed.settings["region"].textValue())
        assertEquals("", changed.settings["approval_ticket"].textValue())
        assertFalse(changed.settings.has("environment"))
        assertFalse(changed.settings.has("replicas_max"))
        assertEquals(source, configured(changed, PreviewDeploymentTargetInput()))
        assertEquals(3, changed.settings.size())
    }

    @Test fun environmentAcceptsOnlyExplicitFrozenChoicesAndDoesNotNormalizeAliases() {
        for (value in listOf("development", "staging", "production")) {
            val changed = configured(selection(), PreviewDeploymentTargetInput(environment = value))
            assertEquals(value, changed.settings["environment"].textValue())
            assertEquals(value, PreviewDeploymentTargetSettings.initial(changed).environment)
        }
        for (value in listOf("", " ", "Production", "production\n", " development", "dev", "unknown", "not_applicable")) {
            invalid(PreviewDeploymentTargetInput(environment = value), "environment", PreviewDeploymentTargetProblem.ENUM)
        }
    }

    @Test fun exactReplicaIntegersHaveNoMachineWidthOrInventedCrossFieldCeiling() {
        for (raw in listOf("1", "9007199254740993", "9223372036854775808", "9".repeat(128), "1.000", "1e3")) {
            val changed = configured(selection(), PreviewDeploymentTargetInput(replicasMin = raw, replicasMax = raw))
            assertEquals(BigDecimal(raw), changed.settings["replicas_min"].decimalValue())
            assertEquals(BigDecimal(raw), changed.settings["replicas_max"].decimalValue())
        }
        val independent = configured(selection(), PreviewDeploymentTargetInput(replicasMin = "99", replicasMax = "1"))
        assertEquals(99, independent.settings["replicas_min"].intValue())
        assertEquals(1, independent.settings["replicas_max"].intValue())
        assertEquals(BigDecimal("0.000"), configured(selection(), PreviewDeploymentTargetInput(replicasMin = "0.000"))
            .settings["replicas_min"].decimalValue())
        invalid(PreviewDeploymentTargetInput(replicasMin = "-1"), "replicas_min", PreviewDeploymentTargetProblem.RANGE)
        for (raw in listOf("0", "-0.0", "-1")) {
            invalid(PreviewDeploymentTargetInput(replicasMax = raw), "replicas_max", PreviewDeploymentTargetProblem.RANGE)
        }
    }

    @Test fun emptyAndFractionalReplicaTextNeedsCorrectionWithoutImplicitRemoval() {
        for (raw in listOf("", " ", "\t\r\n")) {
            invalid(PreviewDeploymentTargetInput(replicasMin = raw), "replicas_min", PreviewDeploymentTargetProblem.REQUIRED)
            invalid(PreviewDeploymentTargetInput(replicasMax = raw), "replicas_max", PreviewDeploymentTargetProblem.REQUIRED)
        }
        for (raw in listOf("0.5", "1.000000000000000001", "1e-1")) {
            invalid(PreviewDeploymentTargetInput(replicasMin = raw), "replicas_min", PreviewDeploymentTargetProblem.INTEGER)
            invalid(PreviewDeploymentTargetInput(replicasMax = raw), "replicas_max", PreviewDeploymentTargetProblem.INTEGER)
        }
    }

    @Test fun replicaSyntaxRejectsLocaleCoercionNonNumbersAndReaderOverflows() {
        for (raw in listOf("NaN", "Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１", "null", "true",
            "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "1\u00a0", "\uD800",
            "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            invalid(PreviewDeploymentTargetInput(replicasMin = raw), "replicas_min")
            invalid(PreviewDeploymentTargetInput(replicasMax = raw), "replicas_max")
        }
    }

    @Test fun replicaControlsCannotInjectDuplicateFieldsOrAnotherDocument() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"region\":\"changed\"")) {
            invalid(PreviewDeploymentTargetInput(replicasMin = raw), "replicas_min", PreviewDeploymentTargetProblem.INTEGER)
            invalid(PreviewDeploymentTargetInput(replicasMax = raw), "replicas_max", PreviewDeploymentTargetProblem.INTEGER)
        }
    }

    @Test fun regionAndApprovalTicketPreserveLiteralTextWithoutReferenceAuthority() {
        for (value in listOf("", " ", "\t\r\n", "unknown", "not_applicable", " region-fixture ", "日本語🙂", "\u0000",
            "https://synthetic@fixture.invalid/literal", "ticket-123\nnot an authorization")) {
            val changed = configured(selection(), PreviewDeploymentTargetInput(region = value, approvalTicket = value))
            assertEquals(value, changed.settings["region"].textValue())
            assertEquals(value, changed.settings["approval_ticket"].textValue())
            assertEquals(PreviewDeploymentTargetInput(region = value, approvalTicket = value),
                PreviewDeploymentTargetSettings.initial(changed))
        }
    }

    @Test fun malformedUnicodeAndOversizedTextYieldIndependentValueFreeIssues() {
        for (value in listOf("\uD800", "\uDC00", "a\uD800b")) {
            invalid(PreviewDeploymentTargetInput(region = value), "region", PreviewDeploymentTargetProblem.TEXT)
            invalid(PreviewDeploymentTargetInput(approvalTicket = value), "approval_ticket", PreviewDeploymentTargetProblem.TEXT)
        }
        val oversized = "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)
        invalid(PreviewDeploymentTargetInput(region = oversized), "region", PreviewDeploymentTargetProblem.TOO_LONG)
        invalid(PreviewDeploymentTargetInput(approvalTicket = oversized), "approval_ticket", PreviewDeploymentTargetProblem.TOO_LONG)
    }

    @Test fun untouchedAdmittedIntegerNodesKeepScaleAndTypeWhenLiteralTextChanges() {
        val source = selection("""{"environment":"production","replicas_min":0.00,"replicas_max":1e3,"region":"old"}""")
        val changed = configured(source, PreviewDeploymentTargetSettings.initial(source).copy(region = "", approvalTicket = "unknown"))
        for (key in listOf("replicas_min", "replicas_max")) {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
        assertEquals("production", changed.settings["environment"].textValue())
    }

    @Test fun invalidSiblingFieldsAndAggregateEscapingCannotPublishPartialCommands() {
        val source = selection()
        val input = PreviewDeploymentTargetInput("unknown", "-1", "0", "\uD800", "\uDC00")
        val invalid = PreviewDeploymentTargetSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, invalid.preparation)
        assertEquals(listOf("environment", "replicas_min", "replicas_max", "region", "approval_ticket"), invalid.issues.map { it.field })
        assertSame(PreviewSelectionPreparation.Invalid, PreviewDeploymentTargetSettings.configure(source,
            PreviewDeploymentTargetInput(region = "\n".repeat(4_500), approvalTicket = "\n".repeat(4_500))).preparation)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurationPreservesIdentityFlagsNotesExtensionsLinksAndUnrelatedDraftContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection()
        val link = SelectionFixtures.link("link.fixture", model.id, source.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, model), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before,
            command(source, PreviewDeploymentTargetInput("staging", "0", "1", "", "ticket-fixture"))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(model, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(5, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun noOpStaleAndDeletedManagedSaveAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewDeploymentTargetInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source,
            PreviewDeploymentTargetInput(environment = "production"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewDeploymentTargetInput(environment = "staging"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewDeploymentTargetInput(region = "unknown"))) is PreviewSelectionSave.Failed)
    }

    @Test fun savedSettingsReopenAndProjectWithoutChangingHistoryOrPrivateMetadata() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "deployment_target" }
        val originalBytes = raw()
        val input = PreviewDeploymentTargetInput("production", "9007199254740993", "9223372036854775808", "", "unverified\nfixture")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        assertSame(PreviewSelectionPreparation.Invalid, PreviewDeploymentTargetSettings.configure(
            reopened.content.components.selections.single { it.kind == "deployment_target" }, input.copy(replicasMax = "0")).preparation)
        assertArrayEquals(savedBytes, raw())
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "deployment_target" }
        assertEquals(saved.content.components.selections.single { it.kind == "deployment_target" }.toJson(), projected)
        assertEquals(BigInteger(input.replicasMin), projected["settings"]["replicas_min"].bigIntegerValue())
        assertEquals(BigInteger(input.replicasMax), projected["settings"]["replicas_max"].bigIntegerValue())
        assertEquals("", projected["settings"]["region"].textValue())
        assertEquals(input.approvalTicket, projected["settings"]["approval_ticket"].textValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun diagnosticsDoNotEchoPrivateValuesAndOtherKindsCannotAcquireThisEditor() {
        val marker = "synthetic-private-approval"
        val input = PreviewDeploymentTargetInput(marker, marker, marker, "\uD800", "\uDC00")
        val result = PreviewDeploymentTargetSettings.configure(selection(), input)
        assertEquals(5, result.issues.size)
        for (value in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(value.contains(marker))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewDeploymentTargetSettings.configure(other, input).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewDeploymentTargetSettings.initial(other) }
    }
}
