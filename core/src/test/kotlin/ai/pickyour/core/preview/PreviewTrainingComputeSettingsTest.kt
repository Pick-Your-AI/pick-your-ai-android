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

class PreviewTrainingComputeSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("training_compute", "sel_training", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewTrainingComputeInput) =
        (PreviewTrainingComputeSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewTrainingComputeInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewTrainingComputeInput, key: String, problem: PreviewTrainingComputeProblem? = null) {
        val source = selection("""{"gpu_hours_budget":2.5,"checkpoint_interval_minutes":7,"spot_allowed":false}""")
        val before = source.toJson()
        val result = PreviewTrainingComputeSettings.configure(source, input)
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

    @Test fun frozenMetadataKeepsZeroGpuBudgetAndPositiveCheckpointWithoutCapsOrDefaults() {
        val fields = PreviewTrainingComputeSettings.fields
        assertEquals(listOf("gpu_hours_budget", "spot_allowed", "checkpoint_interval_minutes"), fields.map { it.key })
        assertEquals(listOf(PreviewSettingType.NUMBER, PreviewSettingType.BOOLEAN, PreviewSettingType.INTEGER), fields.map { it.type })
        assertTrue(fields.none { it.required })
        assertEquals(BigDecimal.ZERO, fields[0].minimum)
        assertNull(fields[1].minimum)
        assertEquals(BigDecimal.ONE, fields[2].minimum)
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() &&
            it.pattern == null && it.format == null })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun allEightPresenceCombinationsRoundTripWithoutInventedDefaults() {
        val source = selection()
        assertEquals(PreviewTrainingComputeInput(), PreviewTrainingComputeSettings.initial(source))
        for (mask in 0..7) {
            val input = PreviewTrainingComputeInput(
                gpuHoursBudget = "0".takeIf { (mask and 1) != 0 },
                checkpointIntervalMinutes = "1".takeIf { (mask and 2) != 0 },
                spotAllowed = false.takeIf { (mask and 4) != 0 })
            val changed = configured(source, input)
            assertEquals((mask and 1) != 0, changed.settings.has("gpu_hours_budget"))
            assertEquals((mask and 2) != 0, changed.settings.has("checkpoint_interval_minutes"))
            assertEquals((mask and 4) != 0, changed.settings.has("spot_allowed"))
            assertEquals(input, PreviewTrainingComputeSettings.initial(changed))
            assertEquals(source, configured(changed, PreviewTrainingComputeInput()))
            assertEquals("{}", PreviewSelectionForms.settingsText(source))
        }
    }

    @Test fun exactGpuBudgetAcceptsZeroFractionalWideAndExponentValuesWithoutRounding() {
        for (raw in listOf("0", "-0.000", "0.5", "101", "9007199254740993", "9".repeat(128),
            "1e99", "1e-99", "0.123456789012345678901234567890123456789")) {
            val changed = configured(selection(), PreviewTrainingComputeInput(gpuHoursBudget = raw))
            assertTrue(changed.settings["gpu_hours_budget"].isNumber)
            assertEquals(BigDecimal(raw), changed.settings["gpu_hours_budget"].decimalValue())
            assertFalse(changed.settings.has("checkpoint_interval_minutes"))
            assertFalse(changed.settings.has("spot_allowed"))
        }
    }

    @Test fun negativeGpuBudgetsCannotBecomeAbsenceZeroOrRoundedPositiveValues() {
        for (raw in listOf("-1", "-0.000000000000000001", "-1e-99", "-1e99"))
            invalid(PreviewTrainingComputeInput(gpuHoursBudget = raw), "gpu_hours_budget", PreviewTrainingComputeProblem.RANGE)
    }

    @Test fun checkpointIntervalRetainsWideIntegersAndAcceptsExactIntegralAliases() {
        for (raw in listOf("1", "9007199254740993", "9223372036854775808", "9".repeat(128), "1.000", "1e3")) {
            val changed = configured(selection(), PreviewTrainingComputeInput(checkpointIntervalMinutes = raw))
            assertEquals(BigDecimal(raw), changed.settings["checkpoint_interval_minutes"].decimalValue())
        }
        for (raw in listOf("0", "-1", "-0.0")) invalid(PreviewTrainingComputeInput(checkpointIntervalMinutes = raw),
            "checkpoint_interval_minutes", PreviewTrainingComputeProblem.RANGE)
        for (raw in listOf("1.5", "1.000000000000000001", "1e-1")) invalid(PreviewTrainingComputeInput(checkpointIntervalMinutes = raw),
            "checkpoint_interval_minutes", PreviewTrainingComputeProblem.INTEGER)
    }

    @Test fun omittedFalseAndTrueSpotRemainSeparateFromOuterSelectionEnabled() {
        for (spot in listOf<Boolean?>(null, false, true)) {
            for (outer in listOf(false, true)) {
                val source = LocalSelection.fromJson(selection().toJson().put("enabled", outer))
                val changed = configured(source, PreviewTrainingComputeInput(spotAllowed = spot))
                assertEquals(spot != null, changed.settings.has("spot_allowed"))
                assertEquals(spot, PreviewTrainingComputeSettings.initial(changed).spotAllowed)
                assertEquals(outer, changed.enabled)
                assertFalse(changed.settings.has("gpu_hours_budget"))
                assertFalse(changed.settings.has("checkpoint_interval_minutes"))
            }
        }
    }

    @Test fun suppliedBlankNumbersStayInvalidUntilExplicitlyOmitted() {
        for (raw in listOf("", " ", "\t\r\n")) {
            invalid(PreviewTrainingComputeInput(gpuHoursBudget = raw), "gpu_hours_budget", PreviewTrainingComputeProblem.REQUIRED)
            invalid(PreviewTrainingComputeInput(checkpointIntervalMinutes = raw), "checkpoint_interval_minutes", PreviewTrainingComputeProblem.REQUIRED)
        }
        val source = selection("""{"gpu_hours_budget":0,"checkpoint_interval_minutes":1}""")
        val removed = configured(source, PreviewTrainingComputeInput())
        assertEquals(0, removed.settings.size())
        assertEquals(2, source.settings.size())
    }

    @Test fun malformedAndLocaleNumericTextCannotBecomeContractFacts() {
        for (raw in listOf("NaN", "Infinity", "-Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１",
            "null", "true", "false", "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e",
            "1\u00a0", "\uD800", "\uDC00", "1\uD800")) {
            invalid(PreviewTrainingComputeInput(gpuHoursBudget = raw), "gpu_hours_budget", PreviewTrainingComputeProblem.NUMBER)
            invalid(PreviewTrainingComputeInput(checkpointIntervalMinutes = raw), "checkpoint_interval_minutes", PreviewTrainingComputeProblem.INTEGER)
        }
    }

    @Test fun bothNumericFieldsRetainReaderLengthAndExponentBudgets() {
        for (raw in listOf("9".repeat(129), "1e10001", "1e-10001",
            " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            invalid(PreviewTrainingComputeInput(gpuHoursBudget = raw), "gpu_hours_budget", PreviewTrainingComputeProblem.TOO_LONG)
            invalid(PreviewTrainingComputeInput(checkpointIntervalMinutes = raw), "checkpoint_interval_minutes", PreviewTrainingComputeProblem.TOO_LONG)
        }
    }

    @Test fun numericControlsRejectInjectedMembersDuplicateValuesAndTrailingDocuments() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"spot_allowed\":true")) {
            invalid(PreviewTrainingComputeInput(gpuHoursBudget = raw), "gpu_hours_budget", PreviewTrainingComputeProblem.NUMBER)
            invalid(PreviewTrainingComputeInput(checkpointIntervalMinutes = raw), "checkpoint_interval_minutes", PreviewTrainingComputeProblem.INTEGER)
        }
    }

    @Test fun independentFieldErrorsCannotPublishAPartialCommand() {
        val source = selection("""{"gpu_hours_budget":1,"checkpoint_interval_minutes":2,"spot_allowed":false}""")
        val before = source.toJson()
        val result = PreviewTrainingComputeSettings.configure(source, PreviewTrainingComputeInput("-0.01", "1.5", true))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("gpu_hours_budget", "checkpoint_interval_minutes"), result.issues.map { it.field })
        assertEquals(listOf(PreviewTrainingComputeProblem.RANGE, PreviewTrainingComputeProblem.INTEGER), result.issues.map { it.problem })
        assertEquals(before, source.toJson())
        val corrected = configured(source, PreviewTrainingComputeInput("0.00", "1", true))
        assertEquals(BigDecimal("0.00"), corrected.settings["gpu_hours_budget"].decimalValue())
        assertEquals(BigInteger.ONE, corrected.settings["checkpoint_interval_minutes"].bigIntegerValue())
        assertTrue(corrected.settings["spot_allowed"].booleanValue())
    }

    @Test fun unchangedNumericNodesAndRemovedSiblingsUseOnlyFinalSettingsAdmission() {
        val source = selection("""{"gpu_hours_budget":0.2300,"checkpoint_interval_minutes":1.000,"spot_allowed":true}""")
        val initial = PreviewTrainingComputeSettings.initial(source)
        val noop = configured(source, initial)
        assertEquals(source, noop)
        assertEquals(PreviewSelectionForms.settingsText(source), PreviewSelectionForms.settingsText(noop))
        val changedSpot = configured(source, initial.copy(spotAllowed = false))
        for (key in listOf("gpu_hours_budget", "checkpoint_interval_minutes")) {
            assertEquals(source.settings[key], changedSpot.settings[key])
            assertEquals(source.settings[key].javaClass, changedSpot.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue().scale(), changedSpot.settings[key].decimalValue().scale())
            assertEquals(source.settings[key].toString(), changedSpot.settings[key].toString())
        }
        val removedBudget = configured(source, initial.copy(gpuHoursBudget = null, spotAllowed = null))
        assertEquals(1, removedBudget.settings.size())
        assertEquals(source.settings["checkpoint_interval_minutes"], removedBudget.settings["checkpoint_interval_minutes"])
        val removedInterval = configured(source, initial.copy(checkpointIntervalMinutes = null, spotAllowed = null))
        assertEquals(1, removedInterval.settings.size())
        assertEquals(source.settings["gpu_hours_budget"], removedInterval.settings["gpu_hours_budget"])
    }

    @Test fun finalWholeSelectionBudgetStillRejectsWithoutInventingFieldErrors() {
        val source = nearBudgetSelection()
        val before = source.toJson()
        val result = PreviewTrainingComputeSettings.configure(source, PreviewTrainingComputeInput("1.25", "1", false))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertTrue(result.issues.isEmpty())
        assertEquals(before, source.toJson())
    }

    @Test fun simultaneousSiblingRemovalAdmitsTheFinalSelectionNearItsByteBudget() {
        val wide = "9".repeat(128)
        val first = nearBudgetSelection("""{"gpu_hours_budget":1,"checkpoint_interval_minutes":$wide,"spot_allowed":true}""")
        val changedBudget = configured(first, PreviewTrainingComputeInput(gpuHoursBudget = wide))
        assertEquals(BigInteger(wide), changedBudget.settings["gpu_hours_budget"].bigIntegerValue())
        assertFalse(changedBudget.settings.has("checkpoint_interval_minutes"))
        assertFalse(changedBudget.settings.has("spot_allowed"))
        assertEquals(first.toJson()["extensions"], changedBudget.toJson()["extensions"])
        val second = nearBudgetSelection("""{"gpu_hours_budget":$wide,"checkpoint_interval_minutes":1,"spot_allowed":true}""")
        val changedInterval = configured(second, PreviewTrainingComputeInput(checkpointIntervalMinutes = wide))
        assertEquals(BigInteger(wide), changedInterval.settings["checkpoint_interval_minutes"].bigIntegerValue())
        assertFalse(changedInterval.settings.has("gpu_hours_budget"))
        assertFalse(changedInterval.settings.has("spot_allowed"))
        assertEquals(second.toJson()["notes"], changedInterval.toJson()["notes"])
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
            PreviewTrainingComputeInput("12.500", "9007199254740993", true))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(peer, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun closedTrainingSettingsKeepUnknownKeysOutsideFrozenAdmission() {
        val source = selection("""{"gpu_hours_budget":0,"spot_allowed":false}""")
        val before = source.toJson()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewSelectionForms.configure(source, """{"gpu_hours_budget":0,"future_training_setting":true}"""))
        assertThrows(IllegalArgumentException::class.java) {
            selection("""{"checkpoint_interval_minutes":1,"unknown":0}""")
        }
        assertEquals(before, source.toJson())
    }

    @Test fun managedNoopStaleAndDeletedSaveAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection("""{"gpu_hours_budget":-0.000,"checkpoint_interval_minutes":1e3,"spot_allowed":false}""")
        val input = PreviewTrainingComputeSettings.initial(source)
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, input)) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, input.copy(spotAllowed = null)))
            as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, input.copy(spotAllowed = true))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, input.copy(checkpointIntervalMinutes = "1"))) is PreviewSelectionSave.Failed)
    }

    @Test fun typedSettingsRoundTripThroughStorageAndExplicitFrozenBuildProjection() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "training_compute" }
        val input = PreviewTrainingComputeInput("123.45000000000000000001", "9007199254740993", false)
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val bytes = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "training_compute" }
        assertEquals(saved.content.components.selections.single { it.kind == "training_compute" }.toJson(), projected)
        assertEquals(BigDecimal(input.gpuHoursBudget), projected["settings"]["gpu_hours_budget"].decimalValue())
        assertEquals(BigInteger(input.checkpointIntervalMinutes), projected["settings"]["checkpoint_interval_minutes"].bigIntegerValue())
        assertFalse(projected["settings"]["spot_allowed"].booleanValue())
        assertArrayEquals(bytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun descriptionsAndIssuesNeverEchoInputAndOtherKindsAreRejected() {
        val raw = "synthetic-private-value"
        val input = PreviewTrainingComputeInput(raw, raw)
        val result = PreviewTrainingComputeSettings.configure(selection(), input)
        assertEquals(2, result.issues.size)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewTrainingComputeSettings.configure(other, input).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewTrainingComputeSettings.initial(other) }
    }
}
