package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path

class PreviewContextPolicySettingsTest {
    @TempDir lateinit var root: Path
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private val minimum = """{"requested_context_tokens":1024,"reserved_output_tokens":0}"""

    private fun selection(settings: String = minimum): LocalSelection {
        val node = SelectionFixtures.node("context_policy", "sel_context_policy", "primary", false)
        val parsed = (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value
        node.set<JsonNode>("settings", parsed)
        return LocalSelection.fromJson(node)
    }

    private fun input(requested: String = "1024", reserved: String = "0", truncation: String? = null, precision: String? = null) =
        PreviewContextPolicyInput(requested, reserved, truncation, precision)
    private fun prepared(source: LocalSelection, value: PreviewContextPolicyInput) =
        PreviewContextPolicySettings.configure(source, value)
    private fun command(source: LocalSelection, value: PreviewContextPolicyInput) =
        (prepared(source, value).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, value: PreviewContextPolicyInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, value)) as SelectionEdit.Ready).content.components.selections.single()
    private fun raw(path: Path = root) =
        (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes

    @Test fun metadataComesFromAllFrozenKindsAndKeepsContextConstraintsImmutable() {
        val kinds = mapper.readTree(SelectionFixtures.resource("/contracts/v1/schemas/component-registry.json"))["kinds"].properties().map { it.key }
        val all = kinds.flatMap { requireNotNull(PreviewSettingsMetadata.fields(it)) }
        assertEquals(29, kinds.size)
        assertEquals(100, all.size)
        assertNull(PreviewSettingsMetadata.fields("future_kind"))
        val fields = PreviewContextPolicySettings.fields
        assertEquals(listOf("requested_context_tokens", "reserved_output_tokens", "truncation_strategy", "kv_cache_precision"),
            fields.map { it.key })
        assertEquals(listOf(true, true, false, false), fields.map { it.required })
        assertEquals(listOf(PreviewSettingType.INTEGER, PreviewSettingType.INTEGER, PreviewSettingType.STRING, PreviewSettingType.STRING),
            fields.map { it.type })
        assertEquals(BigDecimal.ONE, fields[0].minimum)
        assertEquals(BigDecimal.ZERO, fields[1].minimum)
        assertEquals(listOf("reject", "truncate_oldest", "summarize", "sliding_window"), fields[2].enumValues)
        assertEquals(listOf("fp32", "bf16", "fp16", "fp8", "int8", "int4"), fields[3].enumValues)
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fields[2].enumValues as MutableList).clear() }
        assertEquals(4, PreviewContextPolicySettings.fields.size)
    }

    @Test fun initialInputPreservesExplicitZeroAndOptionalAbsenceWithoutDefaults() {
        val source = selection()
        assertEquals(input(), PreviewContextPolicySettings.initial(source))
        val configured = configured(source, input("2048"))
        assertEquals(setOf("requested_context_tokens", "reserved_output_tokens"), configured.settings.properties().map { it.key }.toSet())
        assertEquals(BigInteger.ZERO, configured.settings["reserved_output_tokens"].bigIntegerValue())
        assertEquals(minimum, PreviewSelectionForms.settingsText(source))
    }

    @Test fun requiredEmptyAndRequestedZeroAreRejectedWhileReservedZeroIsValid() {
        val source = selection()
        for ((value, key, problem) in listOf(
            Triple(input(""), PreviewContextPolicySettings.REQUESTED, PreviewSettingProblem.REQUIRED),
            Triple(input(reserved = ""), PreviewContextPolicySettings.RESERVED, PreviewSettingProblem.REQUIRED),
            Triple(input("0"), PreviewContextPolicySettings.REQUESTED, PreviewSettingProblem.MINIMUM),
            Triple(input(reserved = "-1"), PreviewContextPolicySettings.RESERVED, PreviewSettingProblem.MINIMUM),
        )) {
            val result = prepared(source, value)
            assertEquals(PreviewSelectionPreparation.Invalid, result.preparation)
            assertEquals(listOf(PreviewSettingIssue(key, problem)), result.issues)
        }
        assertTrue(prepared(source, input("1", "0")).preparation is PreviewSelectionPreparation.Ready)
        assertEquals(minimum, PreviewSelectionForms.settingsText(source))
    }

    @Test fun exactWideIntegersAndIntegralAliasesNeverNarrowOrRound() {
        val source = selection()
        for (text in listOf("9007199254740993", "999999999999999999999999", "9".repeat(128), "1.000", "1e3")) {
            val changed = configured(source, input(text))
            assertEquals(BigDecimal(text), changed.settings["requested_context_tokens"].decimalValue())
        }
        val retained = selection("""{"requested_context_tokens":1.000,"reserved_output_tokens":0.00}""")
        val original = PreviewContextPolicySettings.initial(retained)
        val changed = configured(retained, original.copy(reservedOutputTokens = "7"))
        assertEquals(BigDecimal("1.000"), changed.settings["requested_context_tokens"].decimalValue())
        assertEquals(BigDecimal("0.00"), retained.settings["reserved_output_tokens"].decimalValue())
        assertEquals(BigInteger.valueOf(7), changed.settings["reserved_output_tokens"].bigIntegerValue())
    }

    @Test fun malformedLocaleFractionalAndNonNumericInputCannotPrepareACommand() {
        val source = selection()
        val invalid = listOf("1.5", "1.000000000000000001", "1,5", "1,000", "01", "+1", ".5", "NaN", "Infinity",
            "null", "true", "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001",
            "1\u00a0", "\uD800", "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))
        for (text in invalid) {
            val result = prepared(source, input(text))
            assertEquals(PreviewSelectionPreparation.Invalid, result.preparation, "Invalid integer form was admitted")
            assertEquals(listOf(PreviewContextPolicySettings.REQUESTED), result.issues.map { it.field })
        }
        assertEquals(minimum, PreviewSelectionForms.settingsText(source))
    }

    @Test fun fieldParserRejectsExtraDuplicateAndTrailingWrapperContent() {
        val source = selection()
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"reserved_output_tokens\":0")) {
            val result = prepared(source, input(raw))
            assertEquals(PreviewSelectionPreparation.Invalid, result.preparation)
            assertEquals(PreviewSettingProblem.INTEGER, result.issues.single().problem)
        }
        assertEquals(minimum, PreviewSelectionForms.settingsText(source))
    }

    @Test fun everyCanonicalEnumAndExplicitRemovalPreserveAbsence() {
        val source = selection()
        for (truncation in PreviewContextPolicySettings.fields[2].enumValues) {
            for (precision in PreviewContextPolicySettings.fields[3].enumValues) {
                val selected = configured(source, input(truncation = truncation, precision = precision))
                assertEquals(truncation, selected.settings["truncation_strategy"].textValue())
                assertEquals(precision, selected.settings["kv_cache_precision"].textValue())
                val cleared = configured(selected, input())
                assertEquals(source.settings, cleared.settings)
                assertFalse(cleared.settings.has("truncation_strategy"))
                assertFalse(cleared.settings.has("kv_cache_precision"))
            }
        }
    }

    @Test fun unknownEmptyAndDisplayLabelsNeverBecomeEnumWireValues() {
        val source = selection()
        for (raw in listOf("", "unknown", "not_applicable", "Not supplied", "FP16", "fp16 ", "secret://fixture/hidden")) {
            for (value in listOf(input(truncation = raw), input(precision = raw))) {
                val result = prepared(source, value)
                assertEquals(PreviewSelectionPreparation.Invalid, result.preparation)
                assertEquals(PreviewSettingProblem.ENUM, result.issues.single().problem)
            }
        }
        assertEquals(minimum, PreviewSelectionForms.settingsText(source))
    }

    @Test fun configureRetainsSelectionIdentityRoleFlagsNotesExtensionsAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("retained", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val base = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source)))
        val changed = (DraftSelectionCommands.apply(base, command(source, input("2048", "3", "reject", "bf16"))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.single()
        val expected = source.toJson().also { it.set<JsonNode>("settings", selected.settings) }
        assertEquals(expected, selected.toJson())
        assertEquals(base, changed.withComponents(base.components))
        selected.settings.put("requested_context_tokens", 0)
        assertEquals(BigInteger.valueOf(2048), changed.components.selections.single().settings["requested_context_tokens"].bigIntegerValue())
        assertEquals(BigInteger.valueOf(1024), source.settings["requested_context_tokens"].bigIntegerValue())
    }

    @Test fun currentNoopStaleAndDeletedManagedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, input())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val changed = adapter.applySelection(before, command(source, input("2048"))) as PreviewSelectionSave.Saved
        assertEquals(before.revision + 1, changed.record.revision)
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, input("4096"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, changed.record))
        assertTrue(adapter.applySelection(changed.record, command(source, input("8192"))) is PreviewSelectionSave.Failed)
    }

    @Test fun managedTypedValuesRoundTripThroughAnExplicitValidatedBuildCandidate() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "context_policy" }
        val before = raw()
        val edit = command(source, input("9007199254740993", "0", "sliding_window", "int4"))
        val saved = (store.applySelection(DraftSelectionIntent(original.version, edit)) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, saved.metadata)
        val savedBytes = raw()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "context_policy" }
        assertEquals(saved.content.components.selections.single { it.kind == "context_policy" }.toJson(), projected)
        assertEquals(BigInteger("9007199254740993"), projected["settings"]["requested_context_tokens"].bigIntegerValue())
        assertEquals(BigInteger.ZERO, projected["settings"]["reserved_output_tokens"].bigIntegerValue())
        assertEquals("int4", projected["settings"]["kv_cache_precision"].textValue())
        val afterProjection = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, afterProjection.version.number)
        assertEquals(saved.version.hash, afterProjection.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(before, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun unsupportedKindAndValueFreeErrorsDoNotExposeRawSettings() {
        val raw = "secret://fixture/private-value"
        val input = input(raw, precision = raw)
        val result = prepared(selection(), input)
        assertEquals(PreviewSelectionPreparation.Invalid, result.preparation)
        assertFalse(input.toString().contains(raw))
        assertFalse(result.toString().contains(raw))
        assertFalse(result.issues.toString().contains(raw))
        assertEquals(PreviewSelectionPreparation.Invalid, prepared(SelectionFixtures.selection(), input()).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewContextPolicySettings.initial(SelectionFixtures.selection()) }
    }
}
