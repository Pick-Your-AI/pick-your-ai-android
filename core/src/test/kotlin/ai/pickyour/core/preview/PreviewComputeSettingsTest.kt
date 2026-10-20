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

class PreviewComputeSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = """{"instances":1}"""): LocalSelection {
        val node = SelectionFixtures.node("compute_profile", "sel_compute", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewComputeInput) =
        (PreviewComputeSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewComputeInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewComputeInput, key: String, problem: PreviewComputeProblem? = null) {
        val result = PreviewComputeSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }

    @Test fun threeFieldsUseTheExactFrozenRequiredTypesAndBounds() {
        val fields = PreviewComputeSettings.fields
        assertEquals(listOf("instances", "reserved_hours_per_month", "region"), fields.map { it.key })
        assertEquals(listOf(true, false, false), fields.map { it.required })
        assertEquals(listOf(PreviewSettingType.INTEGER, PreviewSettingType.NUMBER, PreviewSettingType.STRING), fields.map { it.type })
        assertEquals(BigDecimal.ONE, fields[0].minimum)
        assertEquals(BigDecimal.ZERO, fields[1].minimum)
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() })
        assertNull(fields[2].minimumLength)
        assertNull(fields[2].pattern)
        assertNull(fields[2].format)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun optionalAbsenceExplicitZeroAndEmptyRegionStayDistinct() {
        val source = selection()
        assertEquals(PreviewComputeInput("1"), PreviewComputeSettings.initial(source))
        assertEquals(source, configured(source, PreviewComputeInput("1")))
        val supplied = configured(source, PreviewComputeInput("1", "0", ""))
        assertEquals(3, supplied.settings.size())
        assertEquals(BigDecimal.ZERO, supplied.settings["reserved_hours_per_month"].decimalValue())
        assertEquals("", supplied.settings["region"].textValue())
        val removed = configured(supplied, PreviewComputeInput("1"))
        assertEquals(source, removed)
        assertEquals(3, supplied.settings.size())
    }

    @Test fun instanceCountsKeepWideExactIntegersAndMathematicalIntegerAliases() {
        for (raw in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
            assertEquals(BigDecimal(raw), configured(selection(), PreviewComputeInput(raw)).settings["instances"].decimalValue())
        }
        for (raw in listOf("0", "-1")) invalid(PreviewComputeInput(raw), "instances", PreviewComputeProblem.RANGE)
        for (raw in listOf("1.5", "1.000000000000000001")) invalid(PreviewComputeInput(raw), "instances", PreviewComputeProblem.INTEGER)
    }

    @Test fun reservedHoursKeepExactNonnegativeNumbersWithoutCalendarOrCapacityCeilings() {
        for (raw in listOf("0", "-0.0", "0.100000000000000001", "1e-100", "9007199254740993", "1000.25")) {
            assertEquals(BigDecimal(raw), configured(selection(), PreviewComputeInput("1", raw)).settings["reserved_hours_per_month"].decimalValue())
        }
        for (raw in listOf("-1", "-0.000000000000000001")) {
            invalid(PreviewComputeInput("1", raw), "reserved_hours_per_month", PreviewComputeProblem.RANGE)
        }
    }

    @Test fun emptyAndWhitespaceNumericInputsNeedCorrectionWithoutRemovingInstances() {
        for (raw in listOf("", " ", "\t\r\n")) {
            invalid(PreviewComputeInput(raw), "instances", PreviewComputeProblem.REQUIRED)
            invalid(PreviewComputeInput("1", raw), "reserved_hours_per_month", PreviewComputeProblem.REQUIRED)
        }
    }

    @Test fun numericSyntaxRejectsLocaleCoercionNonNumbersAndExistingReaderOverflows() {
        for (raw in listOf("NaN", "Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１", "null", "true",
            "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "1\u00a0", "\uD800",
            "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            invalid(PreviewComputeInput(raw), "instances")
            invalid(PreviewComputeInput("1", raw), "reserved_hours_per_month")
        }
    }

    @Test fun numericControlsCannotInjectKeysDuplicatesOrTrailingDocuments() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"region\":\"changed\"")) {
            invalid(PreviewComputeInput(raw), "instances", PreviewComputeProblem.INTEGER)
            invalid(PreviewComputeInput("1", raw), "reserved_hours_per_month", PreviewComputeProblem.NUMBER)
        }
    }

    @Test fun regionPreservesLiteralEmptyWhitespaceUnicodeAndAnswerLikeText() {
        for (region in listOf("", " ", "\t\r\n", "unknown", "not_applicable", " eu-fixture-1 ", "日本語🙂", "\u0000",
            "https://synthetic@fixture.invalid/literal")) {
            val changed = configured(selection(), PreviewComputeInput("1", region = region))
            assertEquals(region, changed.settings["region"].textValue())
            assertEquals(region, PreviewComputeSettings.initial(changed).region)
        }
    }

    @Test fun malformedUnicodeAndOversizedRegionAreValueFreeFieldErrors() {
        for (region in listOf("\uD800", "\uDC00", "a\uD800b")) {
            invalid(PreviewComputeInput("1", region = region), "region", PreviewComputeProblem.TEXT)
        }
        invalid(PreviewComputeInput("1", region = "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)),
            "region", PreviewComputeProblem.TOO_LONG)
    }

    @Test fun untouchedAdmittedNodesKeepScaleAndTypeWhenOnlyRegionChanges() {
        val source = selection("""{"instances":1e3,"reserved_hours_per_month":0.00}""")
        val changed = configured(source, PreviewComputeSettings.initial(source).copy(region = ""))
        for (key in listOf("instances", "reserved_hours_per_month")) {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
        assertEquals("", changed.settings["region"].textValue())
    }

    @Test fun wholeSelectionBudgetAndIndependentErrorsCannotPublishPartialSettings() {
        val source = selection()
        val invalid = PreviewComputeSettings.configure(source, PreviewComputeInput("0", "-1", "\uD800"))
        assertSame(PreviewSelectionPreparation.Invalid, invalid.preparation)
        assertEquals(listOf("instances", "reserved_hours_per_month", "region"), invalid.issues.map { it.field })
        // Escaping can exceed the existing aggregate JSON budget even below a single control's limit.
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewComputeSettings.configure(source, PreviewComputeInput("2", region = "\n".repeat(9_000))).preparation)
        assertEquals("{\"instances\":1}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurePreservesSelectionIdentityLinksFlagsNotesAndUnrelatedDraftData() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val peer = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", source.id, peer.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, peer), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before, command(source, PreviewComputeInput("2", "0", ""))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(peer, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(1, source.settings.size())
    }

    @Test fun managedNoopStaleAndDeletedSaveAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewComputeInput("1"))) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewComputeInput("2"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, PreviewComputeInput("3"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewComputeInput("4"))) is PreviewSelectionSave.Failed)
    }

    @Test fun threeTypedValuesRoundTripThroughManagedStorageAndExplicitFrozenBuildProjection() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "compute_profile" }
        val input = PreviewComputeInput("9007199254740993", "0.100000000000000001", "")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val bytes = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "compute_profile" }
        assertEquals(saved.content.components.selections.single { it.kind == "compute_profile" }.toJson(), projected)
        assertEquals(BigInteger(input.instances), projected["settings"]["instances"].bigIntegerValue())
        assertEquals(BigDecimal(input.reservedHoursPerMonth), projected["settings"]["reserved_hours_per_month"].decimalValue())
        assertEquals("", projected["settings"]["region"].textValue())
        assertArrayEquals(bytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun descriptionsDoNotEchoInputAndOtherKindsCannotUseThisEditor() {
        val raw = "synthetic-private-value"
        val input = PreviewComputeInput(raw, raw, "\uD800")
        val result = PreviewComputeSettings.configure(selection(), input)
        assertEquals(3, result.issues.size)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewComputeSettings.configure(other, input).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewComputeSettings.initial(other) }
    }
}
