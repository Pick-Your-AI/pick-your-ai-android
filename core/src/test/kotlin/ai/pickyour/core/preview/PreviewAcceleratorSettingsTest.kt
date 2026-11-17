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

class PreviewAcceleratorSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = """{"device_count":1}"""): LocalSelection {
        val node = SelectionFixtures.node("accelerator", "sel_accelerator", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewAcceleratorInput) =
        (PreviewAcceleratorSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewAcceleratorInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(text: String, key: String, problem: PreviewAcceleratorProblem? = null) {
        val source = selection()
        val input = if (key == "device_count") PreviewAcceleratorInput(text) else PreviewAcceleratorInput("1", text)
        val result = PreviewAcceleratorSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
        assertEquals("""{"device_count":1}""", PreviewSelectionForms.settingsText(source))
    }

    @Test fun requiredDeviceAndOptionalGroupsRetainFrozenIntegerMetadata() {
        val fields = PreviewAcceleratorSettings.fields
        assertEquals(listOf("device_count", "tensor_parallel_groups"), fields.map { it.key })
        assertEquals(listOf(true, false), fields.map { it.required })
        fields.forEach {
            assertEquals(PreviewSettingType.INTEGER, it.type)
            assertEquals(BigDecimal.ONE, it.minimum)
            assertNull(it.maximum)
            assertTrue(it.enumValues.isEmpty())
        }
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun optionalAbsenceHasNoDefaultAndRemovalPreservesRequiredDeviceCount() {
        val minimum = selection()
        assertEquals(PreviewAcceleratorInput("1"), PreviewAcceleratorSettings.initial(minimum))
        assertEquals(minimum, configured(minimum, PreviewAcceleratorInput("1")))
        val source = selection("""{"device_count":2,"tensor_parallel_groups":9}""")
        val removed = configured(source, PreviewAcceleratorSettings.initial(source).copy(tensorParallelGroups = null))
        assertEquals(source.settings.also { it.remove("tensor_parallel_groups") }, removed.settings)
        assertEquals(2, source.settings.size())
        assertEquals(1, removed.settings.size())
    }

    @Test fun requiredAndExplicitlySuppliedBlankInputsNeedCorrectionWithoutDefaulting() {
        for (text in listOf("", " ", "\t\r\n")) for (key in PreviewAcceleratorSettings.fields.map { it.key })
            invalid(text, key, PreviewAcceleratorProblem.REQUIRED)
    }

    @Test fun exactLargeAndIntegralDecimalAliasesAreAdmittedWithoutCountRelations() {
        for (raw in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3", " 2\n")) {
            val changed = configured(selection(), PreviewAcceleratorInput(raw, raw))
            for (key in PreviewAcceleratorSettings.fields.map { it.key })
                assertEquals(0, BigDecimal(raw.trim()).compareTo(changed.settings[key].decimalValue()))
        }
        val independent = configured(selection(), PreviewAcceleratorInput("1", "9007199254740993"))
        assertEquals(BigInteger("9007199254740993"), independent.settings["tensor_parallel_groups"].bigIntegerValue())
        assertEquals(BigInteger.ONE, independent.settings["device_count"].bigIntegerValue())
    }

    @Test fun exactMinimumAndFractionalRejectionNeverUseFloatingPointRounding() {
        for (key in PreviewAcceleratorSettings.fields.map { it.key }) {
            for (raw in listOf("0", "-1", "-0", "-0.000")) invalid(raw, key, PreviewAcceleratorProblem.RANGE)
            for (raw in listOf("1.5", "1e-1", "1.00000000000000000000000000000000000001"))
                invalid(raw, key, PreviewAcceleratorProblem.INTEGER)
        }
    }

    @Test fun malformedTypesLocaleSentinelsAndParserLimitsNeverBecomeCounts() {
        for (key in PreviewAcceleratorSettings.fields.map { it.key }) {
            for (raw in listOf("NaN", "Infinity", "1,5", "01", "+1", ".5", "١", "null", "true", "\"1\"",
                "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "\uD800", "9".repeat(129))) invalid(raw, key)
            invalid("9".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1), key, PreviewAcceleratorProblem.TOO_LONG)
        }
    }

    @Test fun injectedKeysDuplicateValuesAndTrailingDocumentsCannotEscapeOneNumericValue() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1} {}", "1} {\"value\":2"))
            for (key in PreviewAcceleratorSettings.fields.map { it.key }) invalid(raw, key, PreviewAcceleratorProblem.INTEGER)
    }

    @Test fun untouchedAdmittedNumericNodesKeepTheirScaleTypeAndExactValue() {
        val source = selection("""{"device_count":1.000,"tensor_parallel_groups":1e3}""")
        for ((changedKey, input) in listOf("device_count" to PreviewAcceleratorSettings.initial(source).copy(deviceCount = "2"),
            "tensor_parallel_groups" to PreviewAcceleratorSettings.initial(source).copy(tensorParallelGroups = "2"))) {
            val retained = PreviewAcceleratorSettings.fields.single { it.key != changedKey }.key
            val changed = configured(source, input)
            assertEquals(source.settings[retained], changed.settings[retained])
            assertEquals(source.settings[retained].javaClass, changed.settings[retained].javaClass)
            assertEquals(source.settings[retained].decimalValue(), changed.settings[retained].decimalValue())
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
            command(source, PreviewAcceleratorInput("2", "9007199254740993"))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(provider, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(2, changed.components.selections.first().settings.size())
        assertEquals(1, source.settings.size())
    }

    @Test fun currentNoopStaleAndDeletedManagedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewAcceleratorInput("1"))) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewAcceleratorInput("2"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, PreviewAcceleratorInput("3"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewAcceleratorInput("4"))) is PreviewSelectionSave.Failed)
    }

    @Test fun exactCountsRoundTripThroughStorageAndExplicitValidatedBuildProjection() {
        val store = LocalDraftStore(root)
        val content = ProjectionFixtures.content()
        val accelerator = ProjectionFixtures.selection("accelerator")
        val added = content.withComponents(DraftSelections(content.components.selections + accelerator, content.components.links))
        val original = (store.save(null, added, mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        fun bytes() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val originalBytes = bytes()
        val saved = (store.applySelection(DraftSelectionIntent(original.version,
            command(accelerator, PreviewAcceleratorInput("2", "9007199254740993")))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = bytes()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "accelerator" }
        assertEquals(saved.content.components.selections.single { it.kind == "accelerator" }.toJson(), projected)
        assertEquals(BigInteger("9007199254740993"), projected["settings"]["tensor_parallel_groups"].bigIntegerValue())
        assertArrayEquals(savedBytes, bytes())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun errorsStayIndependentValueFreeImmutableAndWrongKindsCannotUseThisAdapter() {
        val raw = "synthetic-private-value"
        val input = PreviewAcceleratorInput(raw, raw)
        val result = PreviewAcceleratorSettings.configure(selection(), input)
        assertEquals(listOf("device_count", "tensor_parallel_groups"), result.issues.map { it.field })
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val unsupported = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewAcceleratorSettings.configure(unsupported, PreviewAcceleratorInput("1")).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewAcceleratorSettings.initial(unsupported) }
    }
}
