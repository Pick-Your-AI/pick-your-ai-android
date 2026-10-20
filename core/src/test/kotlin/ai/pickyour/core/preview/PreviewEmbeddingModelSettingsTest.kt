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

class PreviewEmbeddingModelSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("embedding_model", "sel_embedding", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewEmbeddingModelInput) =
        (PreviewEmbeddingModelSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewEmbeddingModelInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(source: LocalSelection, input: PreviewEmbeddingModelInput, key: String,
        problem: PreviewEmbeddingModelProblem? = null) {
        val result = PreviewEmbeddingModelSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes

    @Test fun threeOptionalFieldsUseExactFrozenMetadataWithoutDefaults() {
        val fields = PreviewEmbeddingModelSettings.fields
        assertEquals(listOf("credential_ref", "output_dimensions", "region"), fields.map { it.key })
        assertTrue(fields.none { it.required })
        assertEquals(listOf(PreviewSettingType.STRING, PreviewSettingType.INTEGER, PreviewSettingType.STRING), fields.map { it.type })
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields[0].pattern)
        assertEquals(BigDecimal.ONE, fields[1].minimum)
        assertNull(fields[1].maximum)
        assertNull(fields[2].pattern)
        assertNull(fields[2].format)
        assertTrue(fields[2].enumValues.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun absenceAndExplicitRemovalNeverCreateOptionalValues() {
        val empty = selection()
        assertEquals(PreviewEmbeddingModelInput(), PreviewEmbeddingModelSettings.initial(empty))
        assertEquals(empty, configured(empty, PreviewEmbeddingModelInput()))
        val source = selection("""{"credential_ref":"secret://fixture/ref","output_dimensions":1,"region":""}""")
        val initial = PreviewEmbeddingModelSettings.initial(source)
        assertEquals("", initial.region)
        val removed = configured(source, initial.copy(region = null))
        assertFalse(removed.settings.has("region"))
        assertEquals(source.settings.also { it.remove("region") }, removed.settings)
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(source, PreviewEmbeddingModelInput())))
        assertEquals(3, source.settings.size())
    }

    @Test fun suppliedEmptyWhitespaceAndLiteralRegionRemainDistinctFromAbsence() {
        for (region in listOf("", " ", "\t\r\n", " exact \n region ", "unknown", "not_applicable", "世界\uD83D\uDE00")) {
            val result = configured(selection(), PreviewEmbeddingModelInput(region = region))
            assertTrue(result.settings.has("region"))
            assertEquals(region, result.settings["region"].textValue())
            assertEquals(region, PreviewEmbeddingModelSettings.initial(result).region)
        }
        for (text in listOf("", " ", "\t\r\n")) {
            invalid(selection(), PreviewEmbeddingModelInput(outputDimensions = text), "output_dimensions", PreviewEmbeddingModelProblem.REQUIRED)
            invalid(selection(), PreviewEmbeddingModelInput(credentialRef = text), "credential_ref", PreviewEmbeddingModelProblem.REQUIRED)
        }
        invalid(selection(), PreviewEmbeddingModelInput(region = "\uD800"), "region", PreviewEmbeddingModelProblem.TEXT)
    }

    @Test fun outputDimensionsKeepWideExactIntegersAndIntegralDecimalAliases() {
        for (text in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
            assertEquals(BigDecimal(text), configured(selection(), PreviewEmbeddingModelInput(outputDimensions = text))
                .settings["output_dimensions"].decimalValue())
        }
        for (text in listOf("0", "-1")) invalid(selection(), PreviewEmbeddingModelInput(outputDimensions = text),
            "output_dimensions", PreviewEmbeddingModelProblem.RANGE)
        for (text in listOf("1.5", "1.000000000000000001")) invalid(selection(), PreviewEmbeddingModelInput(outputDimensions = text),
            "output_dimensions", PreviewEmbeddingModelProblem.INTEGER)
    }

    @Test fun numericSyntaxRejectsLocaleCoercionInjectionAndBoundedReaderLimits() {
        for (text in listOf("NaN", "Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１", "null", "true",
            "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "1\u00a0", "\uD800", "9".repeat(129),
            " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1), "1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing")) {
            invalid(selection(), PreviewEmbeddingModelInput(outputDimensions = text), "output_dimensions")
        }
    }

    @Test fun untouchedNumericNodesRetainScaleTypeAndExactValue() {
        for (raw in listOf("1.000", "1e3", "9007199254740993")) {
            val source = selection("""{"output_dimensions":$raw}""")
            val changed = configured(source, PreviewEmbeddingModelSettings.initial(source).copy(region = ""))
            assertEquals(source.settings["output_dimensions"], changed.settings["output_dimensions"])
            assertEquals(source.settings["output_dimensions"].javaClass, changed.settings["output_dimensions"].javaClass)
            assertEquals(source.settings["output_dimensions"].decimalValue(), changed.settings["output_dimensions"].decimalValue())
            assertEquals("", changed.settings["region"].textValue())
        }
    }

    @Test fun credentialAdmissionMatchesFrozenFindAndTerminalLfSemantics() {
        for (text in listOf("secret://fixture/Case:Reference_Exact", "secret://fixture/ref.\n")) {
            val changed = configured(selection(), PreviewEmbeddingModelInput(credentialRef = text))
            assertEquals(text, changed.settings["credential_ref"].textValue())
            assertTrue(PreviewEmbeddingModelSettings.canRetainCredentialReference(text))
            assertEquals(changed, LocalSelection.fromJson(changed.toJson()))
        }
        for (text in listOf("synthetic-password", "secret://", " secret://fixture/ref", "secret://fixture/ref ",
            "secret://fixture/ref\r", "secret://fixture/ref\r\n", "secret://fixture/ref\n\n", "secret://fixture/@ref",
            "secret://fixture/\uD800", "SECRET://fixture/ref")) {
            invalid(selection(), PreviewEmbeddingModelInput(credentialRef = text), "credential_ref", PreviewEmbeddingModelProblem.REFERENCE)
            assertFalse(PreviewEmbeddingModelSettings.canRetainCredentialReference(text))
            assertThrows(IllegalArgumentException::class.java) {
                val source = selection()
                LocalSelection.fromJson(source.toJson().also {
                    it.set<JsonNode>("settings", source.settings.put("credential_ref", text))
                })
            }
        }
    }

    @Test fun independentValueFreeErrorsDoNotHideInvalidCredentialCapture() {
        val raw = "synthetic-private-value"
        val input = PreviewEmbeddingModelInput(raw, raw, "\uD800")
        val result = PreviewEmbeddingModelSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("credential_ref", "region", "output_dimensions"), result.issues.map { it.field })
        assertFalse(PreviewEmbeddingModelSettings.canRetainCredentialReference(input.credentialRef))
        assertTrue(PreviewEmbeddingModelSettings.canRetainCredentialReference(null))
        assertFalse(PreviewEmbeddingModelSettings.canRetainCredentialReference(""))
        for (value in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(value.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
    }

    @Test fun fieldAndCombinedWholeSelectionBudgetsRemainTheExistingAuthority() {
        val source = selection()
        val credential = "secret://" + "a".repeat(9_000)
        val region = "b".repeat(9_000)
        assertTrue(PreviewEmbeddingModelSettings.configure(source, PreviewEmbeddingModelInput(credentialRef = credential))
            .preparation is PreviewSelectionPreparation.Ready)
        assertTrue(PreviewEmbeddingModelSettings.configure(source, PreviewEmbeddingModelInput(region = region))
            .preparation is PreviewSelectionPreparation.Ready)
        assertSame(PreviewSelectionPreparation.Invalid, PreviewEmbeddingModelSettings.configure(source,
            PreviewEmbeddingModelInput(credentialRef = credential, region = region)).preparation)
        invalid(source, PreviewEmbeddingModelInput(region = "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)),
            "region", PreviewEmbeddingModelProblem.TOO_LONG)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurationPreservesSelectionIdentityNotesExtensionsLinksAndUnrelatedDraftContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val provider = SelectionFixtures.selection("model_provider", "sel_provider")
        val link = SelectionFixtures.link("link.fixture", source.id, provider.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, provider), listOf(link)))
        val input = PreviewEmbeddingModelInput("secret://fixture/ref\n", "9007199254740993", "")
        val changed = (DraftSelectionCommands.apply(before, command(source, input)) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(provider, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun managedNoopStaleAndDeletedAuthoritiesRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewEmbeddingModelInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewEmbeddingModelInput(outputDimensions = "1")))
            as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewEmbeddingModelInput(outputDimensions = "2"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewEmbeddingModelInput(outputDimensions = "3")))
            is PreviewSelectionSave.Failed)
    }

    @Test fun exactSettingsRoundTripThroughStoreHistoryAndValidatedBuildCandidate() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal"))
            as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "embedding_model" }
        val before = raw()
        val input = PreviewEmbeddingModelInput("secret://fixture/Case:Reference_Exact\n", "9007199254740993", "")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "embedding_model" }
        assertEquals(saved.content.components.selections.single { it.kind == "embedding_model" }.toJson(), projected)
        assertEquals(BigInteger("9007199254740993"), projected["settings"]["output_dimensions"].bigIntegerValue())
        assertEquals(input.credentialRef, projected["settings"]["credential_ref"].textValue())
        assertEquals("", projected["settings"]["region"].textValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(before, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun anotherKindCannotEnterTheEmbeddingPreparationRoute() {
        val other = SelectionFixtures.selection("model_provider")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewEmbeddingModelSettings.configure(other, PreviewEmbeddingModelInput()).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewEmbeddingModelSettings.initial(other) }
    }
}
