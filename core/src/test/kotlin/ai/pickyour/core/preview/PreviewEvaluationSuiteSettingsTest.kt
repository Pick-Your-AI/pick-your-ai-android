package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path

class PreviewEvaluationSuiteSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("evaluation_suite", "sel_evaluation", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewEvaluationSuiteInput) =
        (PreviewEvaluationSuiteSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewEvaluationSuiteInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewEvaluationSuiteInput, key: String, problem: PreviewEvaluationSuiteProblem? = null) {
        val source = selection("""{"minimum_score":-2.5,"dataset_size":7,"gate_required":false}""")
        val before = source.toJson()
        val result = PreviewEvaluationSuiteSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
        assertEquals(before, source.toJson())
    }

    private fun nearBudgetSelection(settings: String = "{}"): LocalSelection {
        val node = selection(settings).toJson().put("notes", "")
        val extension = node.putObject("extensions").putObject("x_fixture")
        repeat(65) { extension.put("part$it", "x".repeat(16_000)) }
        val targetBytes = JsonInputLimits().maxBytes - 32
        node.put("notes", "n".repeat(targetBytes - SelectionFixtures.mapper.writeValueAsBytes(node).size))
        assertEquals(targetBytes, SelectionFixtures.mapper.writeValueAsBytes(node).size)
        return LocalSelection.fromJson(node)
    }

    @Test fun frozenMetadataKeepsScoreUnboundedAndAllThreeSettingsOptional() {
        val fields = PreviewEvaluationSuiteSettings.fields
        assertEquals(listOf("minimum_score", "gate_required", "dataset_size"), fields.map { it.key })
        assertEquals(listOf(PreviewSettingType.NUMBER, PreviewSettingType.BOOLEAN, PreviewSettingType.INTEGER), fields.map { it.type })
        assertTrue(fields.none { it.required })
        assertNull(fields[0].minimum)
        assertEquals(BigDecimal.ONE, fields[2].minimum)
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() &&
            it.pattern == null && it.format == null })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun allEightPresenceCombinationsRoundTripWithoutInventedDefaults() {
        val source = selection()
        assertEquals(PreviewEvaluationSuiteInput(), PreviewEvaluationSuiteSettings.initial(source))
        for (mask in 0..7) {
            val input = PreviewEvaluationSuiteInput(
                minimumScore = "0".takeIf { (mask and 1) != 0 },
                datasetSize = "1".takeIf { (mask and 2) != 0 },
                gateRequired = false.takeIf { (mask and 4) != 0 })
            val changed = configured(source, input)
            assertEquals((mask and 1) != 0, changed.settings.has("minimum_score"))
            assertEquals((mask and 2) != 0, changed.settings.has("dataset_size"))
            assertEquals((mask and 4) != 0, changed.settings.has("gate_required"))
            assertEquals(input, PreviewEvaluationSuiteSettings.initial(changed))
            assertEquals(source, configured(changed, PreviewEvaluationSuiteInput()))
            assertEquals("{}", PreviewSelectionForms.settingsText(source))
        }
    }

    @Test fun exactScoreAcceptsNegativeFractionalWideAndNonPercentageValues() {
        for (raw in listOf("-12345.678901234567890123456789", "0", "-0.000", "101", "9007199254740993",
            "9".repeat(128), "1e99", "1e-99", "0.123456789012345678901234567890123456789")) {
            val changed = configured(selection(), PreviewEvaluationSuiteInput(minimumScore = raw))
            assertTrue(changed.settings["minimum_score"].isNumber)
            assertEquals(BigDecimal(raw), changed.settings["minimum_score"].decimalValue())
            assertFalse(changed.settings.has("dataset_size"))
            assertFalse(changed.settings.has("gate_required"))
        }
    }

    @Test fun datasetSizeRetainsWideIntegersAndAcceptsExactIntegralAliases() {
        for (raw in listOf("1", "9007199254740993", "9223372036854775808", "9".repeat(128), "1.000", "1e3")) {
            val changed = configured(selection(), PreviewEvaluationSuiteInput(datasetSize = raw))
            assertEquals(BigDecimal(raw), changed.settings["dataset_size"].decimalValue())
        }
        for (raw in listOf("0", "-1", "-0.0")) invalid(PreviewEvaluationSuiteInput(datasetSize = raw),
            "dataset_size", PreviewEvaluationSuiteProblem.RANGE)
        for (raw in listOf("1.5", "1.000000000000000001", "1e-1")) invalid(PreviewEvaluationSuiteInput(datasetSize = raw),
            "dataset_size", PreviewEvaluationSuiteProblem.INTEGER)
    }

    @Test fun omittedFalseAndTrueGateRemainSeparateFromOuterSelectionEnabled() {
        for (gate in listOf<Boolean?>(null, false, true)) {
            for (outer in listOf(false, true)) {
                val source = LocalSelection.fromJson(selection().toJson().put("enabled", outer))
                val changed = configured(source, PreviewEvaluationSuiteInput(gateRequired = gate))
                assertEquals(gate != null, changed.settings.has("gate_required"))
                assertEquals(gate, PreviewEvaluationSuiteSettings.initial(changed).gateRequired)
                assertEquals(outer, changed.enabled)
                assertFalse(changed.settings.has("minimum_score"))
                assertFalse(changed.settings.has("dataset_size"))
            }
        }
    }

    @Test fun suppliedBlankNumbersStayInvalidUntilExplicitlyOmitted() {
        for (raw in listOf("", " ", "\t\r\n")) {
            invalid(PreviewEvaluationSuiteInput(minimumScore = raw), "minimum_score", PreviewEvaluationSuiteProblem.REQUIRED)
            invalid(PreviewEvaluationSuiteInput(datasetSize = raw), "dataset_size", PreviewEvaluationSuiteProblem.REQUIRED)
        }
        val source = selection("""{"minimum_score":0,"dataset_size":1}""")
        val removed = configured(source, PreviewEvaluationSuiteInput())
        assertEquals(0, removed.settings.size())
        assertEquals(2, source.settings.size())
    }

    @Test fun malformedAndLocaleNumericTextCannotBecomeContractFacts() {
        for (raw in listOf("NaN", "Infinity", "-Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１",
            "null", "true", "false", "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e",
            "1\u00a0", "\uD800", "\uDC00", "1\uD800")) {
            invalid(PreviewEvaluationSuiteInput(minimumScore = raw), "minimum_score", PreviewEvaluationSuiteProblem.NUMBER)
            invalid(PreviewEvaluationSuiteInput(datasetSize = raw), "dataset_size", PreviewEvaluationSuiteProblem.INTEGER)
        }
    }

    @Test fun bothNumericFieldsRetainReaderLengthAndExponentBudgets() {
        for (raw in listOf("9".repeat(129), "1e10001", "1e-10001",
            " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            invalid(PreviewEvaluationSuiteInput(minimumScore = raw), "minimum_score", PreviewEvaluationSuiteProblem.TOO_LONG)
            invalid(PreviewEvaluationSuiteInput(datasetSize = raw), "dataset_size", PreviewEvaluationSuiteProblem.TOO_LONG)
        }
    }

    @Test fun numericControlsRejectInjectedMembersDuplicateValuesAndTrailingDocuments() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"gate_required\":true")) {
            invalid(PreviewEvaluationSuiteInput(minimumScore = raw), "minimum_score", PreviewEvaluationSuiteProblem.NUMBER)
            invalid(PreviewEvaluationSuiteInput(datasetSize = raw), "dataset_size", PreviewEvaluationSuiteProblem.INTEGER)
        }
    }

    @Test fun independentFieldErrorsCannotPublishAPartialCommand() {
        val source = selection("""{"minimum_score":-1,"dataset_size":2,"gate_required":false}""")
        val before = source.toJson()
        val result = PreviewEvaluationSuiteSettings.configure(source, PreviewEvaluationSuiteInput("unknown", "0", true))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("minimum_score", "dataset_size"), result.issues.map { it.field })
        assertEquals(listOf(PreviewEvaluationSuiteProblem.NUMBER, PreviewEvaluationSuiteProblem.RANGE), result.issues.map { it.problem })
        assertEquals(before, source.toJson())
        val corrected = configured(source, PreviewEvaluationSuiteInput("-1.25", "1", true))
        assertEquals(BigDecimal("-1.25"), corrected.settings["minimum_score"].decimalValue())
        assertEquals(BigInteger.ONE, corrected.settings["dataset_size"].bigIntegerValue())
        assertTrue(corrected.settings["gate_required"].booleanValue())
    }

    @Test fun unchangedNumericNodesAndRemovedSiblingsUseOnlyFinalSettingsAdmission() {
        val source = selection("""{"minimum_score":-1.2300,"dataset_size":1.000,"gate_required":true}""")
        val initial = PreviewEvaluationSuiteSettings.initial(source)
        assertEquals(source, configured(source, initial))
        val changedGate = configured(source, initial.copy(gateRequired = false))
        for (key in listOf("minimum_score", "dataset_size")) {
            assertEquals(source.settings[key], changedGate.settings[key])
            assertEquals(source.settings[key].javaClass, changedGate.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue().scale(), changedGate.settings[key].decimalValue().scale())
        }
        val removedScore = configured(source, initial.copy(minimumScore = null, gateRequired = null))
        assertEquals(1, removedScore.settings.size())
        assertEquals(source.settings["dataset_size"], removedScore.settings["dataset_size"])
        val removedSize = configured(source, initial.copy(datasetSize = null, gateRequired = null))
        assertEquals(1, removedSize.settings.size())
        assertEquals(source.settings["minimum_score"], removedSize.settings["minimum_score"])
    }

    @Test fun finalWholeSelectionBudgetStillRejectsWithoutInventingFieldErrors() {
        val source = nearBudgetSelection()
        val before = source.toJson()
        val result = PreviewEvaluationSuiteSettings.configure(source, PreviewEvaluationSuiteInput("-1.25", "1", false))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertTrue(result.issues.isEmpty())
        assertEquals(before, source.toJson())
    }

    @Test fun simultaneousSiblingRemovalAdmitsTheFinalSelectionNearItsByteBudget() {
        val wide = "9".repeat(128)
        val first = nearBudgetSelection("""{"minimum_score":1,"dataset_size":$wide,"gate_required":true}""")
        val changedScore = configured(first, PreviewEvaluationSuiteInput(minimumScore = wide))
        assertEquals(BigInteger(wide), changedScore.settings["minimum_score"].bigIntegerValue())
        assertFalse(changedScore.settings.has("dataset_size"))
        assertFalse(changedScore.settings.has("gate_required"))
        assertEquals(first.toJson()["extensions"], changedScore.toJson()["extensions"])
        val second = nearBudgetSelection("""{"minimum_score":$wide,"dataset_size":1,"gate_required":true}""")
        val changedSize = configured(second, PreviewEvaluationSuiteInput(datasetSize = wide))
        assertEquals(BigInteger(wide), changedSize.settings["dataset_size"].bigIntegerValue())
        assertFalse(changedSize.settings.has("minimum_score"))
        assertFalse(changedSize.settings.has("gate_required"))
        assertEquals(second.toJson()["notes"], changedSize.toJson()["notes"])
    }

    @Test fun configurationPreservesMetadataLinksPeersAndOtherDraftInputs() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val peer = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", source.id, peer.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, peer), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before, command(source,
            PreviewEvaluationSuiteInput("-12.500", "9007199254740993", true))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(peer, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun managedNoopStaleAndDeletedSaveAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewEvaluationSuiteInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewEvaluationSuiteInput(gateRequired = false)))
            as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewEvaluationSuiteInput(gateRequired = true))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewEvaluationSuiteInput(datasetSize = "1"))) is PreviewSelectionSave.Failed)
    }

    @Test fun typedSettingsRoundTripThroughStorageAndExplicitFrozenBuildProjection() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "evaluation_suite" }
        val input = PreviewEvaluationSuiteInput("-123.45000000000000000001", "9007199254740993", false)
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val bytes = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "evaluation_suite" }
        assertEquals(saved.content.components.selections.single { it.kind == "evaluation_suite" }.toJson(), projected)
        assertEquals(BigDecimal(input.minimumScore), projected["settings"]["minimum_score"].decimalValue())
        assertEquals(BigInteger(input.datasetSize), projected["settings"]["dataset_size"].bigIntegerValue())
        assertFalse(projected["settings"]["gate_required"].booleanValue())
        assertArrayEquals(bytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun descriptionsAndIssuesNeverEchoInputAndOtherKindsAreRejected() {
        val raw = "synthetic-private-value"
        val input = PreviewEvaluationSuiteInput(raw, raw)
        val result = PreviewEvaluationSuiteSettings.configure(selection(), input)
        assertEquals(2, result.issues.size)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewEvaluationSuiteSettings.configure(other, input).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewEvaluationSuiteSettings.initial(other) }
    }
}
