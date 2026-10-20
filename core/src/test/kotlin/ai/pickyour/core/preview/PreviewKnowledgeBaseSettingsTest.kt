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

class PreviewKnowledgeBaseSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("knowledge_base", "sel_knowledge", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewKnowledgeBaseInput) =
        (PreviewKnowledgeBaseSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewKnowledgeBaseInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewKnowledgeBaseInput, key: String, problem: PreviewKnowledgeBaseProblem? = null) {
        val result = PreviewKnowledgeBaseSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun numericInputs(raw: String) = listOf(
        PreviewKnowledgeBaseInput(chunkSizeTokens = raw) to "chunk_size_tokens",
        PreviewKnowledgeBaseInput(chunkOverlapTokens = raw) to "chunk_overlap_tokens",
        PreviewKnowledgeBaseInput(refreshMinutes = raw) to "refresh_minutes",
    )

    @Test fun fourFieldsUseTheExactFrozenOptionalTypesAndBounds() {
        val fields = PreviewKnowledgeBaseSettings.fields
        assertEquals(listOf("chunk_size_tokens", "chunk_overlap_tokens", "refresh_minutes", "acl_enforced"), fields.map { it.key })
        assertTrue(fields.none { it.required })
        assertEquals(listOf(PreviewSettingType.INTEGER, PreviewSettingType.INTEGER, PreviewSettingType.INTEGER,
            PreviewSettingType.BOOLEAN), fields.map { it.type })
        assertEquals(listOf(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null), fields.map { it.minimum })
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun optionalAbsenceExplicitZeroAndFalseStayDistinct() {
        val source = selection()
        assertEquals(PreviewKnowledgeBaseInput(), PreviewKnowledgeBaseSettings.initial(source))
        assertEquals(source, configured(source, PreviewKnowledgeBaseInput()))
        val supplied = configured(source, PreviewKnowledgeBaseInput(chunkOverlapTokens = "0", aclEnforced = false))
        assertEquals(2, supplied.settings.size())
        assertEquals(BigDecimal.ZERO, supplied.settings["chunk_overlap_tokens"].decimalValue())
        assertFalse(supplied.settings["acl_enforced"].booleanValue())
        assertEquals(PreviewKnowledgeBaseInput(chunkOverlapTokens = "0", aclEnforced = false), PreviewKnowledgeBaseSettings.initial(supplied))
        assertEquals(source, configured(supplied, PreviewKnowledgeBaseInput()))
        assertEquals(2, supplied.settings.size())
    }

    @Test fun eachOptionalFieldCanBeSuppliedWithoutInventingItsPeers() {
        for ((input, key) in numericInputs("1") + listOf(
            PreviewKnowledgeBaseInput(aclEnforced = false) to "acl_enforced",
            PreviewKnowledgeBaseInput(aclEnforced = true) to "acl_enforced")) {
            val changed = configured(selection(), input)
            assertEquals(listOf(key), changed.settings.fieldNames().asSequence().toList())
            assertEquals(input, PreviewKnowledgeBaseSettings.initial(changed))
        }
    }

    @Test fun allThreeIntegerControlsKeepWideExactValuesAndIntegerAliases() {
        for (raw in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
            for ((input, key) in numericInputs(raw)) {
                assertEquals(BigDecimal(raw), configured(selection(), input).settings[key].decimalValue())
            }
        }
        for (raw in listOf("1.5", "1.000000000000000001", "1e-100")) {
            numericInputs(raw).forEach { (input, key) -> invalid(input, key, PreviewKnowledgeBaseProblem.INTEGER) }
        }
    }

    @Test fun minimaAreIndependentAndNoOverlapOrRefreshCeilingIsInvented() {
        invalid(PreviewKnowledgeBaseInput(chunkSizeTokens = "0"), "chunk_size_tokens", PreviewKnowledgeBaseProblem.RANGE)
        invalid(PreviewKnowledgeBaseInput(refreshMinutes = "0"), "refresh_minutes", PreviewKnowledgeBaseProblem.RANGE)
        numericInputs("-1").forEach { (input, key) -> invalid(input, key, PreviewKnowledgeBaseProblem.RANGE) }
        val changed = configured(selection(), PreviewKnowledgeBaseInput("1", "9007199254740993", "9007199254740993", true))
        assertEquals(BigInteger("9007199254740993"), changed.settings["chunk_overlap_tokens"].bigIntegerValue())
        assertEquals(BigInteger("9007199254740993"), changed.settings["refresh_minutes"].bigIntegerValue())
        assertEquals(BigDecimal("0.0"), configured(selection(),
            PreviewKnowledgeBaseInput(chunkOverlapTokens = "-0.0")).settings["chunk_overlap_tokens"].decimalValue())
    }

    @Test fun suppliedEmptyAndWhitespaceNumbersNeedCorrectionUntilExplicitRemoval() {
        for (raw in listOf("", " ", "\t\r\n")) {
            numericInputs(raw).forEach { (input, key) -> invalid(input, key, PreviewKnowledgeBaseProblem.REQUIRED) }
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(selection(), PreviewKnowledgeBaseInput())))
    }

    @Test fun numericSyntaxRejectsCoercionAnswerStatesAndExistingReaderOverflows() {
        for (raw in listOf("NaN", "Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１", "null", "true",
            "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "1\u00a0", "\uD800",
            "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            numericInputs(raw).forEach { (input, key) -> invalid(input, key) }
        }
    }

    @Test fun numericControlsCannotInjectKeysDuplicatesOrTrailingDocuments() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"acl_enforced\":true")) {
            numericInputs(raw).forEach { (input, key) -> invalid(input, key, PreviewKnowledgeBaseProblem.INTEGER) }
        }
    }

    @Test fun changingTheBooleanPreservesUntouchedAdmittedIntegerNodes() {
        val source = selection("""{"chunk_size_tokens":1e3,"chunk_overlap_tokens":0.00,"refresh_minutes":1.000}""")
        val changed = configured(source, PreviewKnowledgeBaseSettings.initial(source).copy(aclEnforced = false))
        for (key in listOf("chunk_size_tokens", "chunk_overlap_tokens", "refresh_minutes")) {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
        assertFalse(changed.settings["acl_enforced"].booleanValue())
    }

    @Test fun independentInvalidNumbersCannotPublishAPartialBooleanOrSettingsChange() {
        val source = selection("""{"acl_enforced":false}""")
        val result = PreviewKnowledgeBaseSettings.configure(source, PreviewKnowledgeBaseInput("0", "-1", "unfinished", true))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("chunk_size_tokens", "chunk_overlap_tokens", "refresh_minutes"), result.issues.map { it.field })
        assertEquals("{\"acl_enforced\":false}", PreviewSelectionForms.settingsText(source))
        numericInputs("1".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)).forEach { (input, key) ->
            invalid(input, key, PreviewKnowledgeBaseProblem.TOO_LONG)
        }
    }

    @Test fun explicitBooleanChangesNeverImplyAnEnforcementOrServiceCheck() {
        val source = selection("""{"acl_enforced":true}""")
        val falseValue = configured(source, PreviewKnowledgeBaseInput(aclEnforced = false))
        assertEquals("{\"acl_enforced\":false}", PreviewSelectionForms.settingsText(falseValue))
        val trueValue = configured(falseValue, PreviewKnowledgeBaseInput(aclEnforced = true))
        assertEquals(source, trueValue)
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(trueValue, PreviewKnowledgeBaseInput())))
    }

    @Test fun configurePreservesSelectionIdentityLinksFlagsNotesAndUnrelatedDraftData() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val peer = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", source.id, peer.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, peer), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before,
            command(source, PreviewKnowledgeBaseInput("2", "0", "1", false))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(peer, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(4, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun managedNoopStaleAndDeletedSaveAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewKnowledgeBaseInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewKnowledgeBaseInput(aclEnforced = false))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, PreviewKnowledgeBaseInput(aclEnforced = true))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewKnowledgeBaseInput("4"))) is PreviewSelectionSave.Failed)
    }

    @Test fun fourTypedValuesRoundTripThroughManagedStorageAndExplicitFrozenBuildProjection() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "knowledge_base" }
        val input = PreviewKnowledgeBaseInput("9007199254740993", "0", "9007199254740995", false)
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val bytes = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "knowledge_base" }
        assertEquals(saved.content.components.selections.single { it.kind == "knowledge_base" }.toJson(), projected)
        assertEquals(BigInteger(input.chunkSizeTokens), projected["settings"]["chunk_size_tokens"].bigIntegerValue())
        assertEquals(BigInteger.ZERO, projected["settings"]["chunk_overlap_tokens"].bigIntegerValue())
        assertEquals(BigInteger(input.refreshMinutes), projected["settings"]["refresh_minutes"].bigIntegerValue())
        assertFalse(projected["settings"]["acl_enforced"].booleanValue())
        assertArrayEquals(bytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun descriptionsDoNotEchoInputAndOtherKindsCannotUseThisEditor() {
        val raw = "synthetic-private-value"
        val input = PreviewKnowledgeBaseInput(raw, raw, raw, false)
        val result = PreviewKnowledgeBaseSettings.configure(selection(), input)
        assertEquals(3, result.issues.size)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewKnowledgeBaseSettings.configure(other, input).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewKnowledgeBaseSettings.initial(other) }
    }
}
