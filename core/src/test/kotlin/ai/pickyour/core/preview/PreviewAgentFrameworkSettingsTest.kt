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

class PreviewAgentFrameworkSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("agent_framework", "sel_agent", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray(Charsets.UTF_8).inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewAgentFrameworkInput) =
        (PreviewAgentFrameworkSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewAgentFrameworkInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private val numericKeys = listOf("agent_count", "max_iterations", "timeout_ms")
    private fun numericInput(key: String, raw: String) = when (key) {
        PreviewAgentFrameworkSettings.AGENT_COUNT -> PreviewAgentFrameworkInput(agentCount = raw)
        PreviewAgentFrameworkSettings.MAX_ITERATIONS -> PreviewAgentFrameworkInput(maxIterations = raw)
        PreviewAgentFrameworkSettings.TIMEOUT_MS -> PreviewAgentFrameworkInput(timeoutMs = raw)
        else -> error("Unknown test field")
    }
    private fun inputFor(values: Map<String, String?>) =
        PreviewAgentFrameworkInput(values["agent_count"], values["max_iterations"], values["timeout_ms"])
    private fun invalid(input: PreviewAgentFrameworkInput, key: String, problem: PreviewAgentFrameworkProblem) {
        val source = selection()
        val before = source.toJson()
        val result = PreviewAgentFrameworkSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(PreviewAgentFrameworkIssue(key, problem)), result.issues)
        assertEquals(before, source.toJson())
    }
    private fun selectionNearByteLimit(settings: String, slack: Int): LocalSelection {
        val node = selection(settings).toJson()
        val limits = JsonInputLimits()
        val padding = node.putObject("extensions").putObject("x_agent_budget")
        repeat(limits.maxBytes / limits.maxStringLength - 1) {
            padding.put("part" + it, "x".repeat(limits.maxStringLength))
        }
        padding.put("tail", "")
        val target = limits.maxBytes - slack
        val tailLength = target - node.toString().toByteArray(Charsets.UTF_8).size
        require(tailLength in 0..limits.maxStringLength)
        padding.put("tail", "x".repeat(tailLength))
        assertEquals(target, node.toString().toByteArray(Charsets.UTF_8).size)
        return LocalSelection.fromJson(node).also {
            assertEquals(target, it.toJson().toString().toByteArray(Charsets.UTF_8).size)
        }
    }

    @Test fun fieldsMatchTheOptionalFrozenTypesAndBoundsWithoutAddedRelations() {
        val fields = PreviewAgentFrameworkSettings.fields
        assertEquals(numericKeys, fields.map { it.key })
        assertEquals(listOf(false, false, false), fields.map { it.required })
        assertTrue(fields.all { it.type == PreviewSettingType.INTEGER })
        assertEquals(listOf(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), fields.map { it.minimum })
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() })
        assertTrue(fields.all { it.minimumLength == null && it.pattern == null && it.format == null })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun allEightOptionalPresenceCombinationsAndExplicitRemovalRemainDistinct() {
        val source = selection()
        assertEquals(PreviewAgentFrameworkInput(), PreviewAgentFrameworkSettings.initial(source))
        assertEquals(source, configured(source, PreviewAgentFrameworkInput()))
        var cases = 0
        for (count in listOf(null, "2")) {
            for (iterations in listOf(null, "3")) {
                for (timeout in listOf(null, "1000")) {
                    val input = PreviewAgentFrameworkInput(count, iterations, timeout)
                    val supplied = configured(source, input)
                    assertEquals(listOf(count, iterations, timeout).count { it != null }, supplied.settings.size())
                    assertEquals(input, PreviewAgentFrameworkSettings.initial(supplied))
                    for ((key, raw) in numericKeys.zip(listOf(count, iterations, timeout))) {
                        assertEquals(raw != null, supplied.settings.has(key))
                        if (raw != null) assertEquals(BigInteger(raw), supplied.settings[key].bigIntegerValue())
                    }
                    assertEquals(source, configured(supplied, PreviewAgentFrameworkInput()))
                    assertEquals(input, PreviewAgentFrameworkSettings.initial(supplied))
                    cases += 1
                }
            }
        }
        assertEquals(8, cases)
        assertEquals(0, source.settings.size())
    }

    @Test fun allThreeFieldsKeepWideExactIntegersAndMathematicalIntegerAliases() {
        for (key in numericKeys) {
            for (raw in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
                val changed = configured(selection(), numericInput(key, raw))
                assertEquals(BigDecimal(raw), changed.settings[key].decimalValue())
                assertEquals(1, changed.settings.size())
            }
        }
    }

    @Test fun allThreeFieldsUseFrozenMinimumWithoutAnInventedRelativeLimit() {
        for (key in numericKeys) {
            for (raw in listOf("0", "-0.0", "-1", "-999999999999999999")) {
                invalid(numericInput(key, raw), key, PreviewAgentFrameworkProblem.RANGE)
            }
        }
        for (input in listOf(PreviewAgentFrameworkInput("1000", "1", "1"),
            PreviewAgentFrameworkInput("1", "1000", "1"), PreviewAgentFrameworkInput("1", "1", "1000"))) {
            val changed = configured(selection(), input)
            assertEquals(BigInteger(input.agentCount), changed.settings["agent_count"].bigIntegerValue())
            assertEquals(BigInteger(input.maxIterations), changed.settings["max_iterations"].bigIntegerValue())
            assertEquals(BigInteger(input.timeoutMs), changed.settings["timeout_ms"].bigIntegerValue())
        }
    }

    @Test fun suppliedBlankNumbersNeedCorrectionRatherThanRemoval() {
        for (key in numericKeys) {
            for (raw in listOf("", " ", "\t\r\n")) {
                invalid(numericInput(key, raw), key, PreviewAgentFrameworkProblem.REQUIRED)
            }
        }
        val source = selection("""{"agent_count":2,"max_iterations":3,"timeout_ms":1000}""")
        val result = PreviewAgentFrameworkSettings.configure(source, PreviewAgentFrameworkInput("", "", ""))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(numericKeys, result.issues.map { it.field })
        assertEquals(3, source.settings.size())
    }

    @Test fun numericSyntaxRejectsCoercionFractionsAndMalformedUnicode() {
        for (key in numericKeys) {
            for (raw in listOf("1.5", "1.000000000000000001", "1e-100", "NaN", "Infinity", "1,5", "1,000",
                "01", "+1", ".5", "١", "１", "null", "true", "false", "\"1\"", "{}", "[]",
                "unknown", "not_applicable", "1e", "1\u00a0", "\uD800", "\uDC00", "1\uD800")) {
                invalid(numericInput(key, raw), key, PreviewAgentFrameworkProblem.INTEGER)
            }
        }
    }

    @Test fun numericControlsRejectInjectedKeysDuplicatesAndTrailingDocuments() {
        for (key in numericKeys) {
            for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing",
                "1,\"timeout_ms\":2")) {
                invalid(numericInput(key, raw), key, PreviewAgentFrameworkProblem.INTEGER)
            }
        }
    }

    @Test fun existingParserAndControlBudgetsRemainFieldErrors() {
        for (key in numericKeys) {
            for (raw in listOf("9".repeat(129), "1e10001",
                " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
                invalid(numericInput(key, raw), key, PreviewAgentFrameworkProblem.TOO_LONG)
            }
        }
        val accepted = configured(selection(), PreviewAgentFrameworkInput(" ".repeat(128) + "1", "2"))
        assertEquals(BigInteger.ONE, accepted.settings["agent_count"].bigIntegerValue())
        assertEquals(BigInteger.valueOf(2), accepted.settings["max_iterations"].bigIntegerValue())
    }

    @Test fun independentErrorsDoNotPublishPartialSettings() {
        val source = selection("""{"agent_count":2,"max_iterations":3,"timeout_ms":1000}""")
        val before = source.toJson()
        val result = PreviewAgentFrameworkSettings.configure(source, PreviewAgentFrameworkInput("0", "1.5", ""))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(
            PreviewAgentFrameworkIssue("agent_count", PreviewAgentFrameworkProblem.RANGE),
            PreviewAgentFrameworkIssue("max_iterations", PreviewAgentFrameworkProblem.INTEGER),
            PreviewAgentFrameworkIssue("timeout_ms", PreviewAgentFrameworkProblem.REQUIRED),
        ), result.issues)
        for (key in numericKeys) {
            val values = numericKeys.associateWith { if (it == key) "\uD800" else "4" }
            val onlyOneInvalid = PreviewAgentFrameworkSettings.configure(source, inputFor(values))
            assertSame(PreviewSelectionPreparation.Invalid, onlyOneInvalid.preparation)
            assertEquals(listOf(PreviewAgentFrameworkIssue(key, PreviewAgentFrameworkProblem.INTEGER)), onlyOneInvalid.issues)
        }
        assertEquals(before, source.toJson())
    }

    @Test fun untouchedAdmittedNumericNodesRetainClassAndScale() {
        val source = selection("""{"agent_count":1.000,"max_iterations":1e3,"timeout_ms":2.00}""")
        val initial = PreviewAgentFrameworkSettings.initial(source)
        assertEquals(source, configured(source, initial))
        for (changedKey in numericKeys) {
            val values = mapOf("agent_count" to initial.agentCount, "max_iterations" to initial.maxIterations,
                "timeout_ms" to initial.timeoutMs).toMutableMap()
            values[changedKey] = "4"
            val changed = configured(source, inputFor(values))
            for (retainedKey in numericKeys.filter { it != changedKey }) {
                assertEquals(source.settings[retainedKey], changed.settings[retainedKey])
                assertEquals(source.settings[retainedKey].javaClass, changed.settings[retainedKey].javaClass)
                assertEquals(source.settings[retainedKey].decimalValue(), changed.settings[retainedKey].decimalValue())
            }
            assertEquals(BigInteger.valueOf(4), changed.settings[changedKey].bigIntegerValue())
        }
        assertEquals(BigDecimal("1.000"), source.settings["agent_count"].decimalValue())
        assertEquals(BigDecimal("1e3"), source.settings["max_iterations"].decimalValue())
        assertEquals(BigDecimal("2.00"), source.settings["timeout_ms"].decimalValue())
    }

    @Test fun completeSelectionBudgetRejectsOtherwiseValidGrowth() {
        val source = selectionNearByteLimit("""{"agent_count":1,"max_iterations":1,"timeout_ms":1}""", 8)
        val before = source.toJson()
        val input = PreviewAgentFrameworkInput("9".repeat(128), "9".repeat(128), "9".repeat(128))
        assertTrue(PreviewAgentFrameworkSettings.configure(selection(), input).preparation is PreviewSelectionPreparation.Ready)
        val result = PreviewAgentFrameworkSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertTrue(result.issues.isEmpty())
        assertEquals(before, source.toJson())
    }

    @Test fun completeSelectionAdmissionUsesFinalSiblingRemovalOrShrink() {
        val wide = "9".repeat(128)
        for (growingKey in numericKeys) {
            for (siblingKey in numericKeys.filter { it != growingKey }) {
                for (removeSibling in listOf(true, false)) {
                    val originalSettings = selection().settings.apply {
                        put(growingKey, BigInteger.ONE)
                        put(siblingKey, BigInteger(wide))
                    }
                    val source = selectionNearByteLimit(originalSettings.toString(), 0)
                    val before = source.toJson()
                    val finalSettings = source.settings
                    finalSettings.put(growingKey, BigInteger(wide))
                    if (removeSibling) finalSettings.remove(siblingKey) else finalSettings.put(siblingKey, BigInteger.ONE)
                    val intendedNode = source.toJson().also { it.set<JsonNode>("settings", finalSettings) }
                    assertTrue(intendedNode.toString().toByteArray(Charsets.UTF_8).size <= JsonInputLimits().maxBytes)
                    val intended = LocalSelection.fromJson(intendedNode)
                    val values = numericKeys.associateWith { key ->
                        when (key) {
                            growingKey -> wide
                            siblingKey -> if (removeSibling) null else "1"
                            else -> null
                        }
                    }
                    assertEquals(intended, configured(source, inputFor(values)))
                    assertEquals(before, source.toJson())
                }
            }
        }
    }

    @Test fun configurePreservesCompleteSelectionAndUnrelatedDraftData() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val peer = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", source.id, peer.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, peer), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before, command(source, PreviewAgentFrameworkInput("2", "3", "1000")))
            as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(peer, changed.components.selections.last())
        assertFalse(selected.enabled)
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun managedNoopAcknowledgmentStaleAndDeletedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewAgentFrameworkInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val changed = adapter.applySelection(before, command(source, PreviewAgentFrameworkInput(timeoutMs = "1000")))
            as PreviewSelectionSave.Saved
        val saved = changed.record
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(SelectionAction.CONFIGURE, changed.change!!.action)
        val selected = saved.draft.content.components.selections.single()
        assertTrue(selected.settings["timeout_ms"].isNumber)
        assertEquals(BigInteger.valueOf(1000), selected.settings["timeout_ms"].bigIntegerValue())
        assertEquals(1, selected.settings.size())
        assertFalse(selected.enabled)
        assertEquals(before.draft.content.optional, saved.draft.content.optional)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewAgentFrameworkInput(timeoutMs = "2000"))))
        val reopened = (library.open(created.id).load() as PreviewDraftLoad.Available).record
        assertEquals(saved.baselineIdentity, reopened.baselineIdentity)
        assertEquals(saved.draft.content, reopened.draft.content)
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(selected, PreviewAgentFrameworkInput(timeoutMs = "2000")))
            is PreviewSelectionSave.Failed)
    }

    @Test fun canonicalRejectionCannotReplaceAcknowledgedStorage() {
        val source = selection("""{"agent_count":2,"max_iterations":3,"timeout_ms":1000}""")
        val store = LocalDraftStore(root)
        val original = (store.save(null, DraftContent(components = DraftSelections(listOf(source))),
            mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        val beforeBytes = (cache.active() as CacheRead.Available).bytes
        val malformedSettings = listOf(source.settings.put("agent_count", 0),
            source.settings.put("max_iterations", BigDecimal("1.5")), source.settings.put("timeout_ms", "1000"),
            source.settings.put("extra", true))
        for (settings in malformedSettings) {
            val result = store.applySelection(DraftSelectionIntent(original.version,
                SelectionCommand.Configure(source.id, settings))) as DraftSelectionSave.Rejected
            assertEquals(SelectionRejection.INVALID_SETTINGS, result.reason)
            val current = (store.load() as DraftLoad.Available).draft
            assertEquals(original.version.number, current.version.number)
            assertEquals(original.version.hash, current.version.hash)
            assertEquals(original.content, current.content)
            assertEquals(original.history.size, current.history.size)
            for ((expected, actual) in original.history.zip(current.history)) {
                assertEquals(expected.number, actual.number)
                assertEquals(expected.change, actual.change)
                assertEquals(expected.restoredFrom, actual.restoredFrom)
                assertEquals(expected.content, actual.content)
                assertEquals(expected.metadata, actual.metadata)
                assertEquals(expected.selectionChange, actual.selectionChange)
            }
            assertEquals(original.metadata, current.metadata)
            assertArrayEquals(beforeBytes, (cache.active() as CacheRead.Available).bytes)
        }
    }

    @Test fun typedValuesRoundTripThroughManagedStorageAndExplicitFrozenBuildProjection() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "agent_framework" }
        val input = PreviewAgentFrameworkInput("9007199254740993", "1.000", "1e3")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input)))
            as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        assertEquals(original.content.optional, reopened.content.optional)
        assertEquals(original.content.components.links, reopened.content.components.links)
        assertEquals(SelectionAction.CONFIGURE, reopened.history.last().selectionChange!!.action)
        val bytes = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "agent_framework" }
        assertEquals(saved.content.components.selections.single { it.kind == "agent_framework" }.toJson(), projected)
        assertEquals(BigInteger(input.agentCount), projected["settings"]["agent_count"].bigIntegerValue())
        assertEquals(BigDecimal(input.maxIterations), projected["settings"]["max_iterations"].decimalValue())
        assertEquals(BigDecimal(input.timeoutMs), projected["settings"]["timeout_ms"].decimalValue())
        assertArrayEquals(bytes,
            (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun wrongKindsAndDiagnosticDescriptionsRemainValueFree() {
        val raw = "synthetic-private-value"
        val input = PreviewAgentFrameworkInput(raw, raw, raw)
        val result = PreviewAgentFrameworkSettings.configure(selection(), input)
        assertEquals(3, result.issues.size)
        assertEquals("PreviewAgentFrameworkInput", input.toString())
        assertEquals(input, input.copy())
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        val wrongKind = PreviewAgentFrameworkSettings.configure(other, input)
        assertSame(PreviewSelectionPreparation.Invalid, wrongKind.preparation)
        assertTrue(wrongKind.issues.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PreviewAgentFrameworkSettings.initial(other) }
    }
}
