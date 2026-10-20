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

class PreviewInferenceSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("inference_engine", "sel_inference", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewInferenceInput) =
        (PreviewInferenceSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewInferenceInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewInferenceInput, key: String, problem: PreviewInferenceProblem? = null) {
        val source = selection()
        val result = PreviewInferenceSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun fourOptionalFieldsRetainExactFrozenMetadataWithoutDefaults() {
        val fields = PreviewInferenceSettings.fields
        assertEquals(listOf("tensor_parallel_size", "max_batch_tokens", "precision", "quantization"), fields.map { it.key })
        assertTrue(fields.none { it.required })
        assertEquals(listOf(PreviewSettingType.INTEGER, PreviewSettingType.INTEGER,
            PreviewSettingType.STRING, PreviewSettingType.STRING), fields.map { it.type })
        fields.take(2).forEach { assertEquals(BigDecimal.ONE, it.minimum); assertNull(it.maximum) }
        assertEquals(listOf("fp32", "tf32", "bf16", "fp16", "fp8", "int8", "int4"), fields[2].enumValues)
        assertEquals(listOf("none", "awq", "gptq", "gguf", "bitsandbytes", "other"), fields[3].enumValues)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fields[3].enumValues as MutableList).clear() }
    }

    @Test fun absentAndExplicitNoneRemainDifferentAndRemovalTouchesOnlyItsField() {
        val empty = selection()
        assertEquals(PreviewInferenceInput(), PreviewInferenceSettings.initial(empty))
        assertEquals(empty, configured(empty, PreviewInferenceInput()))
        val source = selection("""{"tensor_parallel_size":1,"max_batch_tokens":2,"precision":"tf32","quantization":"none"}""")
        val initial = PreviewInferenceSettings.initial(source)
        assertEquals("none", initial.quantization)
        for (key in PreviewInferenceSettings.fields.map { it.key }) {
            val input = when (key) {
                "tensor_parallel_size" -> initial.copy(tensorParallelSize = null)
                "max_batch_tokens" -> initial.copy(maxBatchTokens = null)
                "precision" -> initial.copy(precision = null)
                else -> initial.copy(quantization = null)
            }
            assertEquals(source.settings.also { it.remove(key) }, configured(source, input).settings)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(source, PreviewInferenceInput())))
        assertEquals(4, source.settings.size())
    }

    @Test fun suppliedEmptyAndWhitespaceNeedCorrectionWithoutBecomingAbsent() {
        for (text in listOf("", " ", "\t\r\n")) {
            invalid(PreviewInferenceInput(tensorParallelSize = text), "tensor_parallel_size", PreviewInferenceProblem.REQUIRED)
            invalid(PreviewInferenceInput(maxBatchTokens = text), "max_batch_tokens", PreviewInferenceProblem.REQUIRED)
        }
    }

    @Test fun bothIntegerFieldsKeepWideExactValuesAndMathematicalIntegerAliases() {
        for (text in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
            val changed = configured(selection(), PreviewInferenceInput(text, text))
            for (key in listOf("tensor_parallel_size", "max_batch_tokens"))
                assertEquals(BigDecimal(text), changed.settings[key].decimalValue())
        }
    }

    @Test fun bothIntegerFieldsEnforceMinimumAndRejectFractionalValuesWithoutRounding() {
        for (text in listOf("0", "-1", "-0.000")) {
            invalid(PreviewInferenceInput(tensorParallelSize = text), "tensor_parallel_size", PreviewInferenceProblem.RANGE)
            invalid(PreviewInferenceInput(maxBatchTokens = text), "max_batch_tokens", PreviewInferenceProblem.RANGE)
        }
        for (text in listOf("1.5", "1.000000000000000001", "1e-1")) {
            invalid(PreviewInferenceInput(tensorParallelSize = text), "tensor_parallel_size", PreviewInferenceProblem.INTEGER)
            invalid(PreviewInferenceInput(maxBatchTokens = text), "max_batch_tokens", PreviewInferenceProblem.INTEGER)
        }
    }

    @Test fun malformedLocaleUnicodeNonNumbersAndRepresentationLimitsCannotPrepareACommand() {
        val malformed = listOf("NaN", "Infinity", "-Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１",
            "null", "true", "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001",
            "1\u00a0", "\uD800", "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))
        for (text in malformed) {
            invalid(PreviewInferenceInput(tensorParallelSize = text), "tensor_parallel_size")
            invalid(PreviewInferenceInput(maxBatchTokens = text), "max_batch_tokens")
        }
    }

    @Test fun numericInputCannotInjectAnAdditionalKeyDuplicateOrTrailingDocument() {
        for (text in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"precision\":\"tf32\"")) {
            invalid(PreviewInferenceInput(tensorParallelSize = text), "tensor_parallel_size", PreviewInferenceProblem.INTEGER)
            invalid(PreviewInferenceInput(maxBatchTokens = text), "max_batch_tokens", PreviewInferenceProblem.INTEGER)
        }
    }

    @Test fun untouchedAdmittedNumericNodesPreserveTheirScaleAndType() {
        val source = selection("""{"tensor_parallel_size":1.000,"max_batch_tokens":1e3}""")
        val changed = configured(source, PreviewInferenceSettings.initial(source).copy(precision = "tf32"))
        for (key in listOf("tensor_parallel_size", "max_batch_tokens")) {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
    }

    @Test fun everyCanonicalPrecisionAndQuantizationRemainsIndependentAndOptional() {
        for (precision in PreviewInferenceSettings.fields[2].enumValues) {
            for (quantization in PreviewInferenceSettings.fields[3].enumValues) {
                val input = PreviewInferenceInput("2", "9007199254740993", precision, quantization)
                val changed = configured(selection(), input)
                assertEquals(precision, changed.settings["precision"].textValue())
                assertEquals(quantization, changed.settings["quantization"].textValue())
                val removed = configured(changed, input.copy(precision = null, quantization = null))
                assertEquals(changed.settings.also { it.remove(listOf("precision", "quantization")) }, removed.settings)
            }
        }
    }

    @Test fun enumDisplayLabelsUnknownEmptyCaseAndWhitespaceAliasesAreRejected() {
        for (text in listOf("", "unknown", "not_applicable", "Not supplied", "TF32", "tf32 ", "AWQ", "awq ")) {
            invalid(PreviewInferenceInput(precision = text), "precision", PreviewInferenceProblem.ENUM)
            invalid(PreviewInferenceInput(quantization = text), "quantization", PreviewInferenceProblem.ENUM)
        }
    }

    @Test fun configurePreservesIdentityRoleFlagsNotesExtensionsLinksAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val provider = SelectionFixtures.selection("model_provider", "sel_provider")
        val link = SelectionFixtures.link("link.fixture", source.id, provider.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, provider), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before,
            command(source, PreviewInferenceInput("2", "9007199254740993", "tf32", "awq"))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(provider, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(4, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun currentNoopStaleAndDeletedManagedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewInferenceInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewInferenceInput(tensorParallelSize = "1"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, PreviewInferenceInput(tensorParallelSize = "2"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewInferenceInput(tensorParallelSize = "3"))) is PreviewSelectionSave.Failed)
    }

    @Test fun typedValuesRoundTripThroughStorageAndExplicitValidatedBuildProjection() {
        val store = LocalDraftStore(root)
        val content = ProjectionFixtures.content()
        val engine = ProjectionFixtures.selection("inference_engine")
        val added = content.withComponents(DraftSelections(content.components.selections + engine, content.components.links))
        val original = (store.save(null, added, mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        fun bytes() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val originalBytes = bytes()
        val saved = (store.applySelection(DraftSelectionIntent(original.version,
            command(engine, PreviewInferenceInput("2", "9007199254740993", "tf32", "awq")))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = bytes()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "inference_engine" }
        assertEquals(saved.content.components.selections.single { it.kind == "inference_engine" }.toJson(), projected)
        assertEquals(BigInteger("9007199254740993"), projected["settings"]["max_batch_tokens"].bigIntegerValue())
        assertArrayEquals(savedBytes, bytes())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun errorsStayIndependentValueFreeImmutableAndWrongKindsCannotUseThisAdapter() {
        val raw = "synthetic-private-value"
        val input = PreviewInferenceInput(raw, raw, raw, raw)
        val result = PreviewInferenceSettings.configure(selection(), input)
        assertEquals(4, result.issues.size)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val unsupported = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewInferenceSettings.configure(unsupported, PreviewInferenceInput()).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewInferenceSettings.initial(unsupported) }
    }
}
