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

class PreviewWorkloadSelectionSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private val keys = listOf("concurrency", "requests_per_day", "avg_input_tokens", "avg_output_tokens", "latency_budget_ms")
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("workload", "sel_workload", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun input(key: String, value: String?) = when (key) {
        "concurrency" -> PreviewWorkloadSelectionInput(concurrency = value)
        "requests_per_day" -> PreviewWorkloadSelectionInput(requestsPerDay = value)
        "avg_input_tokens" -> PreviewWorkloadSelectionInput(averageInputTokens = value)
        "avg_output_tokens" -> PreviewWorkloadSelectionInput(averageOutputTokens = value)
        "latency_budget_ms" -> PreviewWorkloadSelectionInput(latencyBudgetMs = value)
        else -> error("Unexpected fixture field")
    }
    private fun command(source: LocalSelection, input: PreviewWorkloadSelectionInput) =
        (PreviewWorkloadSelectionSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewWorkloadSelectionInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(key: String, raw: String, problem: PreviewWorkloadSelectionProblem? = null) {
        val result = PreviewWorkloadSelectionSettings.configure(selection(), input(key, raw))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes

    @Test fun fiveOptionalIntegerFieldsUseExactFrozenBoundsWithoutDefaults() {
        val fields = PreviewWorkloadSelectionSettings.fields
        assertEquals(keys, fields.map { it.key })
        assertTrue(fields.all { !it.required && it.type == PreviewSettingType.INTEGER })
        assertEquals(listOf(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE),
            fields.map { it.minimum })
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() })
        assertEquals(PreviewWorkloadSelectionInput(), PreviewWorkloadSelectionSettings.initial(selection()))
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun omissionAndSuppliedZeroRemainDistinctForEachOptionalSetting() {
        val source = selection()
        assertEquals(source, configured(source, PreviewWorkloadSelectionInput()))
        val changed = configured(source, PreviewWorkloadSelectionInput("1", "0", "0", "0", "1"))
        assertEquals(5, changed.settings.size())
        for (key in listOf("requests_per_day", "avg_input_tokens", "avg_output_tokens")) {
            assertTrue(changed.settings[key].isNumber)
            assertEquals(BigInteger.ZERO, changed.settings[key].bigIntegerValue())
        }
        val removed = configured(changed, PreviewWorkloadSelectionSettings.initial(changed).copy(requestsPerDay = null))
        assertFalse(removed.settings.has("requests_per_day"))
        assertEquals(changed.settings.also { it.remove("requests_per_day") }, removed.settings)
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(changed, PreviewWorkloadSelectionInput())))
        assertEquals(5, changed.settings.size())
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun wideAndMathematicallyIntegralJsonNumbersRemainExactWithoutMachineWidthCaps() {
        val source = selection()
        for (key in keys) for (value in listOf("9007199254740993", "9223372036854775808", "9".repeat(128), "1.000", "1e2")) {
            val changed = configured(source, input(key, value))
            assertTrue(changed.settings[key].isNumber)
            assertEquals(0, BigDecimal(value).compareTo(changed.settings[key].decimalValue()))
            assertEquals(1, changed.settings.size())
        }
        for (key in listOf("requests_per_day", "avg_input_tokens", "avg_output_tokens")) {
            val changed = configured(source, input(key, "-0"))
            assertEquals(0, changed.settings[key].decimalValue().compareTo(BigDecimal.ZERO))
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun eachFrozenLowerBoundIsEnforcedWithoutInventingCrossFieldConstraints() {
        for (key in keys) invalid(key, "-1", PreviewWorkloadSelectionProblem.RANGE)
        for (key in listOf("concurrency", "latency_budget_ms")) invalid(key, "0", PreviewWorkloadSelectionProblem.RANGE)
        val unrelated = configured(selection(), PreviewWorkloadSelectionInput("1000", "0", "1000000", "2000000", "1"))
        assertEquals(PreviewWorkloadSelectionInput("1000", "0", "1000000", "2000000", "1"),
            PreviewWorkloadSelectionSettings.initial(unrelated))
    }

    @Test fun fractionalLocaleTextAndInjectedMembersCannotPrepareAnyField() {
        for (key in keys) for (value in listOf("0.5", "1.000000000000000001", "1e-100", "1,5", "1,000", "01", "+1",
            ".5", "1.", "١", "１", "NaN", "Infinity", "null", "true", "\"1\"", "{}", "[]", "unknown",
            "not_applicable", "1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1\u00a0", "1\uD800")) {
            invalid(key, value, PreviewWorkloadSelectionProblem.INTEGER)
        }
    }

    @Test fun suppliedEmptyTextAndRepresentationLimitsRemainActionableWithoutPartialCommands() {
        for (key in keys) {
            for (value in listOf("", " ", "\t\r\n")) invalid(key, value, PreviewWorkloadSelectionProblem.REQUIRED)
            invalid(key, "9".repeat(129), PreviewWorkloadSelectionProblem.TOO_LONG)
            invalid(key, "1e10001", PreviewWorkloadSelectionProblem.TOO_LONG)
            invalid(key, " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1), PreviewWorkloadSelectionProblem.TOO_LONG)
        }
    }

    @Test fun untouchedAdmittedNumericNodesKeepTheirScaleTypeAndIndependentOmission() {
        val source = selection("""{"concurrency":1.000,"requests_per_day":0.00,"avg_input_tokens":1e2,"avg_output_tokens":2.000,"latency_budget_ms":3.00}""")
        val unchanged = PreviewWorkloadSelectionSettings.initial(source)
        val changed = configured(source, unchanged.copy(requestsPerDay = null))
        assertFalse(changed.settings.has("requests_per_day"))
        for (key in keys - "requests_per_day") {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
        assertEquals(source, configured(source, unchanged))
    }

    @Test fun independentValueFreeErrorsRejectTheWholeInputAndRemainImmutable() {
        val raw = "synthetic-private-input"
        val input = PreviewWorkloadSelectionInput(raw, raw, raw, raw, raw)
        val result = PreviewWorkloadSelectionSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(keys, result.issues.map { it.field })
        assertTrue(result.issues.all { it.problem == PreviewWorkloadSelectionProblem.INTEGER })
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val mixed = PreviewWorkloadSelectionSettings.configure(selection(), PreviewWorkloadSelectionInput("1", "2", raw, "3", "4"))
        assertSame(PreviewSelectionPreparation.Invalid, mixed.preparation)
        assertEquals(listOf("avg_input_tokens"), mixed.issues.map { it.field })
    }

    @Test fun configurationPreservesWholeSelectionLinksAndOverallWorkloadAssumptions() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection()
        val link = SelectionFixtures.link("link.fixture", source.id, model.id, false)
        val profile = DraftWorkload(region = "entered", requestsPerDay = BigInteger("17"), maxConcurrency = BigInteger("9"))
        val before = DraftContent("objective", "context", workload = profile,
            components = DraftSelections(listOf(source, model), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before, command(source,
            PreviewWorkloadSelectionInput("1", "0", "2", "3", "4"))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(profile, changed.workload)
        assertEquals(before.components.links, changed.components.links)
        assertEquals(model, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(5, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun currentNoopStaleAndDeletedManagedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewWorkloadSelectionInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewWorkloadSelectionInput(requestsPerDay = "0"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewWorkloadSelectionInput(concurrency = "1"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewWorkloadSelectionInput(latencyBudgetMs = "1"))) is PreviewSelectionSave.Failed)
    }

    @Test fun savedValuesAndExplicitBuildProjectionRetainTypesHistoryAndPrivateMetadata() {
        val store = LocalDraftStore(root)
        val complete = ProjectionFixtures.content(allKinds = true)
        // Omit optional overall numbers so this success case does not contradict
        // the independently edited Selection values. Required objectives stay exact.
        val content = ProjectionFixtures.content(allKinds = true, workload = complete.workload.copy(
            requestsPerDay = null, avgInputTokens = null, avgOutputTokens = null,
            maxConcurrency = null, maxLatencyMs = null))
        val original = (store.save(null, content, mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "workload" }
        val originalBytes = raw()
        val input = PreviewWorkloadSelectionInput("9007199254740993", "0", "17", "23", "9223372036854775808")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.content.workload, reopened.content.workload)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        val invalid = PreviewWorkloadSelectionSettings.configure(reopened.content.components.selections.single { it.kind == "workload" },
            input.copy(requestsPerDay = "1,5"))
        assertSame(PreviewSelectionPreparation.Invalid, invalid.preparation)
        assertArrayEquals(savedBytes, raw())
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "workload" }
        assertEquals(saved.content.components.selections.single { it.kind == "workload" }.toJson(), projected)
        for (key in keys) assertTrue(projected["settings"][key].isNumber)
        assertEquals(BigInteger("9007199254740993"), projected["settings"]["concurrency"].bigIntegerValue())
        assertEquals(BigInteger("9223372036854775808"), projected["settings"]["latency_budget_ms"].bigIntegerValue())
        for (field in listOf("requests_per_day", "avg_input_tokens", "avg_output_tokens", "max_concurrency", "max_latency_ms")) {
            assertFalse(build["objectives"].has(field))
        }
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun conflictingOverallObjectivesWithholdProjectionWithoutChangingSavedSelectionOrMetadata() {
        val store = LocalDraftStore(root)
        val content = ProjectionFixtures.content(allKinds = true)
        val original = (store.save(null, content, mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "workload" }
        val input = PreviewWorkloadSelectionInput("9007199254740993", "0", "17", "23", "9223372036854775808")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val savedBytes = raw()
        val withheld = store.prepareSnapshotCandidate(saved.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Withheld
        assertEquals(keys.size, withheld.findings.size)
        assertTrue(withheld.findings.all { it.problem == DraftProjectionProblem.CONFLICTING_WORKLOAD_INPUT })
        assertEquals(keys.map { "/selections/${source.id}/settings/$it" }.toSet(), withheld.findings.map { it.path }.toSet())
        assertEquals(content.workload, saved.content.workload)
        assertEquals(input, PreviewWorkloadSelectionSettings.initial(saved.content.components.selections.single { it.kind == "workload" }))
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        assertEquals(saved.version.number, reopened.version.number)
        assertEquals(saved.version.hash, reopened.version.hash)
        assertArrayEquals(savedBytes, raw())
    }

    @Test fun anotherSelectionKindCannotAcquireWorkloadConfiguration() {
        val model = SelectionFixtures.selection()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewWorkloadSelectionSettings.configure(model, PreviewWorkloadSelectionInput(concurrency = "1")).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewWorkloadSelectionSettings.initial(model) }
    }
}
