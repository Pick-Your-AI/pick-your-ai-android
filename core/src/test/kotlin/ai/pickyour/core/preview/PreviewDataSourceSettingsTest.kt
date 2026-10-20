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

class PreviewDataSourceSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("data_source", "sel_data", "primary", false)
        node.set<JsonNode>("settings", (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewDataSourceInput) =
        (PreviewDataSourceSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewDataSourceInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))), command(source, input))
            as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(input: PreviewDataSourceInput, field: String, problem: PreviewDataSourceProblem? = null) {
        val source = selection()
        val result = PreviewDataSourceSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(field), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun exactFrozenThreeFieldMetadataKeepsEverySettingOptional() {
        val fields = PreviewDataSourceSettings.fields
        assertEquals(listOf("connector_secret_ref", "refresh_minutes", "enabled"), fields.map { it.key })
        assertEquals(listOf(PreviewSettingType.STRING, PreviewSettingType.INTEGER, PreviewSettingType.BOOLEAN), fields.map { it.type })
        assertTrue(fields.none { it.required })
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields[0].pattern)
        assertEquals(BigDecimal.ONE, fields[1].minimum)
        assertTrue(fields.all { it.maximum == null && it.exclusiveMinimum == null && it.enumValues.isEmpty() })
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun absentFalseAndTrueDoNotChangeOuterSelectionEnabled() {
        val source = selection()
        assertEquals(PreviewDataSourceInput(), PreviewDataSourceSettings.initial(source))
        for (enabled in listOf<Boolean?>(null, false, true)) {
            val changed = configured(source, PreviewDataSourceInput(enabled = enabled))
            assertEquals(enabled != null, changed.settings.has("enabled"))
            assertEquals(enabled, PreviewDataSourceSettings.initial(changed).enabled)
            assertFalse(changed.enabled)
            val outerEnabled = LocalSelection.fromJson(source.toJson().put("enabled", true))
            assertTrue(configured(outerEnabled, PreviewDataSourceInput(enabled = enabled)).enabled)
        }
        assertEquals(source, configured(configured(source, PreviewDataSourceInput(enabled = true)), PreviewDataSourceInput()))
    }

    @Test fun exactIntegralMinutesRetainWideValuesAndAcceptedDecimalAliases() {
        for (raw in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
            assertEquals(BigDecimal(raw), configured(selection(), PreviewDataSourceInput(refreshMinutes = raw))
                .settings["refresh_minutes"].decimalValue())
        }
        for (raw in listOf("0", "-1", "-0.0")) invalid(PreviewDataSourceInput(refreshMinutes = raw),
            "refresh_minutes", PreviewDataSourceProblem.RANGE)
        for (raw in listOf("1.5", "1.000000000000000001")) invalid(PreviewDataSourceInput(refreshMinutes = raw),
            "refresh_minutes", PreviewDataSourceProblem.INTEGER)
    }

    @Test fun emptySuppliedMinutesRemainInvalidUntilExplicitlyOmitted() {
        for (raw in listOf("", " ", "\t\r\n")) invalid(PreviewDataSourceInput(refreshMinutes = raw),
            "refresh_minutes", PreviewDataSourceProblem.REQUIRED)
        val original = selection("""{"refresh_minutes":1}""")
        assertFalse(configured(original, PreviewDataSourceInput()).settings.has("refresh_minutes"))
        assertEquals("1", PreviewDataSourceSettings.initial(original).refreshMinutes)
    }

    @Test fun minuteSyntaxRejectsCoercionAndBoundedReaderOverflow() {
        for (raw in listOf("NaN", "Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１", "null", "false",
            "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "1\u00a0", "\uD800",
            "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            invalid(PreviewDataSourceInput(refreshMinutes = raw), "refresh_minutes")
        }
    }

    @Test fun numericControlCannotInjectAnotherSettingOrTrailingDocument() {
        for (raw in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"enabled\":true")) {
            invalid(PreviewDataSourceInput(refreshMinutes = raw), "refresh_minutes", PreviewDataSourceProblem.INTEGER)
        }
    }

    @Test fun opaqueReferencesStayLiteralIncludingFrozenFinalLfSemantics() {
        for (raw in listOf("secret://fixture/key", "secret://A0_:-./", "secret://fixture/key\n")) {
            val changed = configured(selection(), PreviewDataSourceInput(connectorSecretRef = raw))
            assertEquals(raw, changed.settings["connector_secret_ref"].textValue())
            assertTrue(PreviewDataSourceSettings.canRetainConnectorReference(raw))
            assertEquals(raw, PreviewDataSourceSettings.initial(changed).connectorSecretRef)
        }
        assertTrue(PreviewDataSourceSettings.canRetainConnectorReference(null))
    }

    @Test fun malformedReferencesNeverBecomeCredentialsOrTrimmedValues() {
        for (raw in listOf("", " ", "secret://", " secret://fixture/key", "secret://fixture/key ", "secret://fixture/key\r\n",
            "secret://fixture/key\nmore", "https://fixture.invalid", "synthetic-private-value", "secret://a@b",
            "secret://fixture/\uD800", "secret://日本語", "SECRET://fixture/key")) {
            invalid(PreviewDataSourceInput(connectorSecretRef = raw), "connector_secret_ref", PreviewDataSourceProblem.REFERENCE)
            assertFalse(PreviewDataSourceSettings.canRetainConnectorReference(raw))
        }
        invalid(PreviewDataSourceInput(connectorSecretRef = "secret://" + "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT)),
            "connector_secret_ref", PreviewDataSourceProblem.TOO_LONG)
    }

    @Test fun fieldErrorsAreIndependentAndCannotPublishAPartialCommand() {
        val result = PreviewDataSourceSettings.configure(selection(), PreviewDataSourceInput("not-a-reference", "0", true))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("connector_secret_ref", "refresh_minutes"), result.issues.map { it.field })
        assertEquals(listOf(PreviewDataSourceProblem.REFERENCE, PreviewDataSourceProblem.RANGE), result.issues.map { it.problem })
        val corrected = configured(selection(), PreviewDataSourceInput("secret://fixture/key", "1", false))
        assertEquals(3, corrected.settings.size())
    }

    @Test fun untouchedIntegralAliasAndRemovedSiblingUseOnlyFinalSettingsAdmission() {
        val source = selection("""{"connector_secret_ref":"secret://fixture/old","refresh_minutes":1.000,"enabled":true}""")
        val changed = configured(source, PreviewDataSourceSettings.initial(source).copy(connectorSecretRef = null, enabled = false))
        assertEquals(source.settings["refresh_minutes"], changed.settings["refresh_minutes"])
        assertEquals(source.settings["refresh_minutes"].javaClass, changed.settings["refresh_minutes"].javaClass)
        assertEquals(BigDecimal("1.000"), changed.settings["refresh_minutes"].decimalValue())
        assertFalse(changed.settings.has("connector_secret_ref"))
        assertFalse(changed.settings["enabled"].booleanValue())
        assertEquals(source, configured(source, PreviewDataSourceSettings.initial(source)))
    }

    @Test fun finalWholeSelectionAndSettingsBudgetsStillRejectWithoutFieldErrors() {
        val node = selection().toJson().put("notes", "n".repeat(6_000))
        val extension = node.putObject("extensions").putObject("x_fixture")
        repeat(65) { extension.put("part$it", "x".repeat(16_000)) }
        val source = LocalSelection.fromJson(node)
        val result = PreviewDataSourceSettings.configure(source, PreviewDataSourceInput("secret://" + "y".repeat(3_000), "1", true))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertTrue(result.issues.isEmpty())
        assertEquals(node, source.toJson())
        val settingsBudget = PreviewDataSourceSettings.configure(selection(),
            PreviewDataSourceInput("secret://" + "y".repeat(16_350), "1", true))
        assertSame(PreviewSelectionPreparation.Invalid, settingsBudget.preparation)
        assertTrue(settingsBudget.issues.isEmpty())
    }

    @Test fun exactConfigurePreservesMetadataLinksPeersAndOtherDraftInputs() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val peer = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", source.id, peer.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, peer), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before, command(source,
            PreviewDataSourceInput("secret://fixture/key", "9007199254740993", true))) as SelectionEdit.Ready).content
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
        val noop = adapter.applySelection(before, command(source, PreviewDataSourceInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewDataSourceInput(enabled = false))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, PreviewDataSourceInput(enabled = true))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewDataSourceInput(refreshMinutes = "1"))) is PreviewSelectionSave.Failed)
    }

    @Test fun typedSettingsRoundTripThroughStorageAndExplicitFrozenBuildProjection() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "data_source" }
        val input = PreviewDataSourceInput("secret://fixture/key", "9007199254740993", false)
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val bytes = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "data_source" }
        assertEquals(saved.content.components.selections.single { it.kind == "data_source" }.toJson(), projected)
        assertEquals(BigInteger(input.refreshMinutes), projected["settings"]["refresh_minutes"].bigIntegerValue())
        assertFalse(projected["settings"]["enabled"].booleanValue())
        assertArrayEquals(bytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes)
    }

    @Test fun descriptionsAndIssuesDoNotEchoInputAndOtherKindsAreRejected() {
        val raw = "synthetic-private-value"
        val input = PreviewDataSourceInput(raw, raw)
        val result = PreviewDataSourceSettings.configure(selection(), input)
        assertEquals(2, result.issues.size)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val other = SelectionFixtures.selection("model")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewDataSourceSettings.configure(other, input).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewDataSourceSettings.initial(other) }
    }
}
