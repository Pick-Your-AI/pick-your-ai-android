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

class PreviewRagPipelineSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("rag_pipeline", "sel_rag", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray(Charsets.UTF_8).inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewRagPipelineInput) =
        (PreviewRagPipelineSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewRagPipelineInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun numericInput(key: String, raw: String) =
        if (key == PreviewRagPipelineSettings.TOP_K) PreviewRagPipelineInput(topK = raw)
        else PreviewRagPipelineInput(maxChunks = raw)
    private fun invalid(input: PreviewRagPipelineInput, key: String, problem: PreviewRagPipelineProblem) {
        val source = selection()
        val before = source.toJson()
        val result = PreviewRagPipelineSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(PreviewRagPipelineIssue(key, problem)), result.issues)
        assertEquals(before, source.toJson())
    }
    private fun selectionNearByteLimit(settings: String, slack: Int): LocalSelection {
        val node = selection(settings).toJson()
        val limits = JsonInputLimits()
        val padding = node.putObject("extensions").putObject("x_rag_budget")
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
        val fields = PreviewRagPipelineSettings.fields
        assertEquals(listOf("top_k", "max_chunks", "rerank_enabled"), fields.map { it.key })
        assertEquals(listOf(false, false, false), fields.map { it.required })
        assertEquals(listOf(PreviewSettingType.INTEGER, PreviewSettingType.INTEGER, PreviewSettingType.BOOLEAN),
            fields.map { it.type })
        assertEquals(listOf(BigDecimal.ONE, BigDecimal.ONE, null), fields.map { it.minimum })
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() })
        assertTrue(fields.all { it.minimumLength == null && it.pattern == null && it.format == null })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun absenceFalseTrueAndRemovalRemainDistinct() {
        val source = selection()
        assertEquals(PreviewRagPipelineInput(), PreviewRagPipelineSettings.initial(source))
        assertEquals(source, configured(source, PreviewRagPipelineInput()))
        for (rerank in listOf(false, true)) {
            val supplied = configured(source, PreviewRagPipelineInput(rerankEnabled = rerank))
            assertEquals(1, supplied.settings.size())
            assertTrue(supplied.settings["rerank_enabled"].isBoolean)
            assertEquals(rerank, supplied.settings["rerank_enabled"].booleanValue())
            assertEquals(PreviewRagPipelineInput(rerankEnabled = rerank), PreviewRagPipelineSettings.initial(supplied))
            assertEquals(source, configured(supplied, PreviewRagPipelineInput()))
            assertEquals(rerank, supplied.settings["rerank_enabled"].booleanValue())
        }
        val all = configured(source, PreviewRagPipelineInput("2", "3", false))
        assertEquals(3, all.settings.size())
        assertEquals(PreviewRagPipelineInput("2", "3", false), PreviewRagPipelineSettings.initial(all))
        assertEquals(source, configured(all, PreviewRagPipelineInput()))
        assertEquals(0, source.settings.size())
    }

    @Test fun bothCountsKeepWideExactIntegersAndMathematicalIntegerAliases() {
        for (key in listOf("top_k", "max_chunks")) {
            for (raw in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
                val changed = configured(selection(), numericInput(key, raw))
                assertEquals(BigDecimal(raw), changed.settings[key].decimalValue())
                assertEquals(1, changed.settings.size())
            }
        }
    }

    @Test fun bothCountsUseFrozenMinimumWithoutAnInventedRelativeLimit() {
        for (key in listOf("top_k", "max_chunks")) {
            for (raw in listOf("0", "-0.0", "-1", "-999999999999999999")) {
                invalid(numericInput(key, raw), key, PreviewRagPipelineProblem.RANGE)
            }
        }
        for (input in listOf(PreviewRagPipelineInput("1000", "1"), PreviewRagPipelineInput("1", "1000"))) {
            val changed = configured(selection(), input)
            assertEquals(BigInteger(input.topK), changed.settings["top_k"].bigIntegerValue())
            assertEquals(BigInteger(input.maxChunks), changed.settings["max_chunks"].bigIntegerValue())
            assertFalse(changed.settings.has("rerank_enabled"))
        }
    }

    @Test fun suppliedBlankNumbersNeedCorrectionRatherThanRemoval() {
        for (key in listOf("top_k", "max_chunks")) {
            for (raw in listOf("", " ", "\t\r\n")) {
                invalid(numericInput(key, raw), key, PreviewRagPipelineProblem.REQUIRED)
            }
        }
        val source = selection("""{"top_k":2,"max_chunks":3,"rerank_enabled":false}""")
        val result = PreviewRagPipelineSettings.configure(source, PreviewRagPipelineInput("", ""))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("top_k", "max_chunks"), result.issues.map { it.field })
        assertEquals(3, source.settings.size())
    }

    @Test fun numericSyntaxRejectsCoercionFractionsAndMalformedUnicode() {
        for (key in listOf("top_k", "max_chunks")) {
            for (raw in listOf("1.5", "1.000000000000000001", "1e-100", "NaN", "Infinity", "1,5", "1,000",
                "01", "+1", ".5", "١", "１", "null", "true", "false", "\"1\"", "{}", "[]",
                "unknown", "not_applicable", "1e", "1\u00a0", "\uD800", "\uDC00", "1\uD800")) {
                invalid(numericInput(key, raw), key, PreviewRagPipelineProblem.INTEGER)
            }
        }
    }

    @Test fun numericControlsRejectInjectedKeysDuplicatesAndTrailingDocuments() {
        for (key in listOf("top_k", "max_chunks")) {
            for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing",
                "1,\"rerank_enabled\":true")) {
                invalid(numericInput(key, raw), key, PreviewRagPipelineProblem.INTEGER)
            }
        }
    }

    @Test fun existingParserAndControlBudgetsRemainFieldErrors() {
        for (key in listOf("top_k", "max_chunks")) {
            for (raw in listOf("9".repeat(129), "1e10001",
                " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
                invalid(numericInput(key, raw), key, PreviewRagPipelineProblem.TOO_LONG)
            }
        }
        val accepted = configured(selection(), PreviewRagPipelineInput(" ".repeat(128) + "1", "2"))
        assertEquals(BigInteger.ONE, accepted.settings["top_k"].bigIntegerValue())
        assertEquals(BigInteger.valueOf(2), accepted.settings["max_chunks"].bigIntegerValue())
    }

    @Test fun independentErrorsDoNotPublishPartialSettings() {
        val source = selection("""{"top_k":2,"max_chunks":3,"rerank_enabled":true}""")
        val before = source.toJson()
        val result = PreviewRagPipelineSettings.configure(source, PreviewRagPipelineInput("0", "1.5", false))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(
            PreviewRagPipelineIssue("top_k", PreviewRagPipelineProblem.RANGE),
            PreviewRagPipelineIssue("max_chunks", PreviewRagPipelineProblem.INTEGER),
        ), result.issues)
        val onlyOneInvalid = PreviewRagPipelineSettings.configure(source, PreviewRagPipelineInput("4", "\uD800"))
        assertSame(PreviewSelectionPreparation.Invalid, onlyOneInvalid.preparation)
        assertEquals(listOf(PreviewRagPipelineIssue("max_chunks", PreviewRagPipelineProblem.INTEGER)), onlyOneInvalid.issues)
        assertEquals(before, source.toJson())
    }

    @Test fun untouchedAdmittedNumericNodesRetainClassAndScale() {
        val source = selection("""{"top_k":1.000,"max_chunks":1e3}""")
        val initial = PreviewRagPipelineSettings.initial(source)
        val changedBoolean = configured(source, initial.copy(rerankEnabled = false))
        for (key in listOf("top_k", "max_chunks")) {
            assertEquals(source.settings[key], changedBoolean.settings[key])
            assertEquals(source.settings[key].javaClass, changedBoolean.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changedBoolean.settings[key].decimalValue())
        }
        val changedCount = configured(source, initial.copy(topK = "2"))
        assertEquals(source.settings["max_chunks"].javaClass, changedCount.settings["max_chunks"].javaClass)
        assertEquals(source.settings["max_chunks"].decimalValue(), changedCount.settings["max_chunks"].decimalValue())
        assertEquals(BigDecimal("1.000"), source.settings["top_k"].decimalValue())
        assertEquals(BigInteger.valueOf(2), changedCount.settings["top_k"].bigIntegerValue())
    }

    @Test fun completeSelectionBudgetRejectsOtherwiseValidGrowth() {
        val source = selectionNearByteLimit("""{"top_k":1,"max_chunks":1}""", 8)
        val before = source.toJson()
        val input = PreviewRagPipelineInput("9".repeat(128), "9".repeat(128), true)
        assertTrue(PreviewRagPipelineSettings.configure(selection(), input).preparation is PreviewSelectionPreparation.Ready)
        val result = PreviewRagPipelineSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertTrue(result.issues.isEmpty())
        assertEquals(before, source.toJson())
    }

    @Test fun completeSelectionAdmissionUsesFinalSiblingRemovalOrShrink() {
        val wide = "9".repeat(128)
        for (growTopK in listOf(true, false)) {
            for (removeSibling in listOf(true, false)) {
                val originalSettings = if (growTopK) "{\"top_k\":1,\"max_chunks\":" + wide + "}"
                    else "{\"top_k\":" + wide + ",\"max_chunks\":1}"
                val source = selectionNearByteLimit(originalSettings, 0)
                val before = source.toJson()
                val finalSettings = source.settings
                val growingKey = if (growTopK) "top_k" else "max_chunks"
                val siblingKey = if (growTopK) "max_chunks" else "top_k"
                finalSettings.put(growingKey, BigInteger(wide))
                if (removeSibling) finalSettings.remove(siblingKey) else finalSettings.put(siblingKey, BigInteger.ONE)
                val intendedNode = source.toJson().also { it.set<JsonNode>("settings", finalSettings) }
                assertTrue(intendedNode.toString().toByteArray(Charsets.UTF_8).size <= JsonInputLimits().maxBytes)
                val intended = LocalSelection.fromJson(intendedNode)
                val input = if (growTopK) PreviewRagPipelineInput(wide, if (removeSibling) null else "1")
                    else PreviewRagPipelineInput(if (removeSibling) null else "1", wide)
                assertEquals(intended, configured(source, input))
                assertEquals(before, source.toJson())
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
        val changed = (DraftSelectionCommands.apply(before, command(source, PreviewRagPipelineInput("2", "3", false)))
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

    @Test fun managedNoopFalseAcknowledgmentStaleAndDeletedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewRagPipelineInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val changed = adapter.applySelection(before, command(source, PreviewRagPipelineInput(rerankEnabled = false)))
            as PreviewSelectionSave.Saved
        val saved = changed.record
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(SelectionAction.CONFIGURE, changed.change!!.action)
        val selected = saved.draft.content.components.selections.single()
        assertTrue(selected.settings["rerank_enabled"].isBoolean)
        assertFalse(selected.settings["rerank_enabled"].booleanValue())
        assertFalse(selected.enabled)
        assertEquals(before.draft.content.optional, saved.draft.content.optional)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewRagPipelineInput(rerankEnabled = true))))
        val reopened = (library.open(created.id).load() as PreviewDraftLoad.Available).record
        assertEquals(saved.baselineIdentity, reopened.baselineIdentity)
        assertEquals(saved.draft.content, reopened.draft.content)
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(selected, PreviewRagPipelineInput(rerankEnabled = true)))
            is PreviewSelectionSave.Failed)
    }

    @Test fun canonicalRejectionCannotReplaceAcknowledgedStorage() {
        val source = selection("""{"top_k":2,"rerank_enabled":false}""")
        val store = LocalDraftStore(root)
        val original = (store.save(null, DraftContent(components = DraftSelections(listOf(source))),
            mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        val beforeBytes = (cache.active() as CacheRead.Available).bytes
        val malformedSettings = listOf(source.settings.put("top_k", 0),
            source.settings.put("rerank_enabled", "false"), source.settings.put("extra", true))
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
        val source = original.content.components.selections.single { it.kind == "rag_pipeline" }
        val input = PreviewRagPipelineInput("9007199254740993", "1.000", false)
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
        val projected = build["selections"].single { it["kind"].textValue() == "rag_pipeline" }
        assertEquals(saved.content.components.selections.single { it.kind == "rag_pipeline" }.toJson(), projected)
        assertEquals(BigInteger(input.topK), projected["settings"]["top_k"].bigIntegerValue())
        assertEquals(BigDecimal(input.maxChunks), projected["settings"]["max_chunks"].decimalValue())
        assertTrue(projected["settings"]["rerank_enabled"].isBoolean)
        assertFalse(projected["settings"]["rerank_enabled"].booleanValue())
        assertArrayEquals(bytes,
            (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun wrongKindsAndDiagnosticDescriptionsRemainValueFree() {
        val raw = "synthetic-private-value"
        val input = PreviewRagPipelineInput(raw, raw, false)
        val result = PreviewRagPipelineSettings.configure(selection(), input)
        assertEquals(2, result.issues.size)
        assertEquals("PreviewRagPipelineInput", input.toString())
        assertEquals(input, input.copy())
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        val wrongKind = PreviewRagPipelineSettings.configure(other, input)
        assertSame(PreviewSelectionPreparation.Invalid, wrongKind.preparation)
        assertTrue(wrongKind.issues.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PreviewRagPipelineSettings.initial(other) }
    }
}
