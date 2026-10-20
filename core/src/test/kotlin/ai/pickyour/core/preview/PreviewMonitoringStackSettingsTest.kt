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

class PreviewMonitoringStackSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private val keys = listOf("sample_rate", "retention_days", "credential_ref")
    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("monitoring_stack", "sel_monitoring", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun input(key: String, raw: String?) = when (key) {
        "sample_rate" -> PreviewMonitoringStackInput(sampleRate = raw)
        "retention_days" -> PreviewMonitoringStackInput(retentionDays = raw)
        "credential_ref" -> PreviewMonitoringStackInput(credentialRef = raw)
        else -> error("Unexpected fixture field")
    }
    private fun command(source: LocalSelection, input: PreviewMonitoringStackInput) =
        (PreviewMonitoringStackSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewMonitoringStackInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(key: String, raw: String, problem: PreviewMonitoringStackProblem? = null) {
        val result = PreviewMonitoringStackSettings.configure(selection(), input(key, raw))
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes

    @Test fun threeOptionalFieldsUseOnlyFrozenTypesBoundsAndReferencePattern() {
        val fields = PreviewMonitoringStackSettings.fields
        assertEquals(keys, fields.map { it.key })
        assertTrue(fields.all { !it.required && it.enumValues.isEmpty() })
        assertEquals(listOf(PreviewSettingType.NUMBER, PreviewSettingType.INTEGER, PreviewSettingType.STRING),
            fields.map { it.type })
        assertEquals(listOf(BigDecimal.ZERO, BigDecimal.ZERO, null), fields.map { it.minimum })
        assertEquals(listOf(BigDecimal.ONE, null, null), fields.map { it.maximum })
        assertTrue(fields.all { it.exclusiveMinimum == null })
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields.last().pattern)
        assertEquals(PreviewMonitoringStackInput(), PreviewMonitoringStackSettings.initial(selection()))
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun omittedAndSuppliedZeroRemainDifferentAndRemovalDoesNotSupplyDefaults() {
        val source = selection()
        assertEquals(source, configured(source, PreviewMonitoringStackInput()))
        val supplied = configured(source, PreviewMonitoringStackInput("0", "0", "secret://fixture/monitor"))
        assertTrue(supplied.settings["sample_rate"].isNumber)
        assertEquals(BigInteger.ZERO, supplied.settings["retention_days"].bigIntegerValue())
        val removed = configured(supplied, PreviewMonitoringStackSettings.initial(supplied).copy(retentionDays = null))
        assertFalse(removed.settings.has("retention_days"))
        assertEquals(supplied.settings.also { it.remove("retention_days") }, removed.settings)
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(supplied, PreviewMonitoringStackInput())))
        assertEquals(3, supplied.settings.size())
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun sampleRateUsesExactInclusiveFractionBoundsWithoutBinaryRounding() {
        for (value in listOf("0", "-0", "1", "0.000000000000000001", "0.999999999999999999", "1e-100", "1.000")) {
            val changed = configured(selection(), PreviewMonitoringStackInput(sampleRate = value))
            assertTrue(changed.settings["sample_rate"].isNumber)
            assertEquals(0, BigDecimal(value).compareTo(changed.settings["sample_rate"].decimalValue()))
        }
        for (value in listOf("-0.000000000000000001", "1.000000000000000001", "100"))
            invalid("sample_rate", value, PreviewMonitoringStackProblem.RANGE)
    }

    @Test fun retentionKeepsExactWideIntegersAndRejectsNegativeOrFractionalDays() {
        for (value in listOf("0", "-0", "9007199254740993", "9223372036854775808", "9".repeat(128), "1.000", "1e2")) {
            val changed = configured(selection(), PreviewMonitoringStackInput(retentionDays = value))
            assertTrue(changed.settings["retention_days"].isNumber)
            assertEquals(0, BigDecimal(value).compareTo(changed.settings["retention_days"].decimalValue()))
        }
        invalid("retention_days", "-1", PreviewMonitoringStackProblem.RANGE)
        for (value in listOf("0.5", "1.000000000000000001", "1e-100"))
            invalid("retention_days", value, PreviewMonitoringStackProblem.INTEGER)
    }

    @Test fun localeSyntaxNonNumbersAndInjectedMembersCannotPrepareEitherNumericField() {
        for (key in keys.take(2)) for (value in listOf("1,5", "1,000", "01", "+1", ".5", "1.", "١", "１",
            "NaN", "Infinity", "null", "true", "\"1\"", "{}", "[]", "unknown", "not_applicable",
            "1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1\u00a0", "1\uD800")) {
            invalid(key, value, if (key == "retention_days") PreviewMonitoringStackProblem.INTEGER else PreviewMonitoringStackProblem.NUMBER)
        }
    }

    @Test fun emptyAndOverBudgetNumericInputHasFieldErrorsWithoutPartialCommands() {
        for (key in keys.take(2)) {
            for (value in listOf("", " ", "\t\r\n")) invalid(key, value, PreviewMonitoringStackProblem.REQUIRED)
            invalid(key, "9".repeat(129), PreviewMonitoringStackProblem.TOO_LONG)
            invalid(key, "1e10001", PreviewMonitoringStackProblem.TOO_LONG)
            invalid(key, " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1), PreviewMonitoringStackProblem.TOO_LONG)
        }
    }

    @Test fun credentialReferencesRetainFrozenLiteralSpellingAndNeverAcceptCredentialValues() {
        for (value in listOf("secret://fixture/reference", "secret://A_z:9.-/path", "secret://fixture/reference\n")) {
            val changed = configured(selection(), PreviewMonitoringStackInput(credentialRef = value))
            assertEquals(value, changed.settings["credential_ref"].textValue())
            assertTrue(PreviewMonitoringStackSettings.canRetainCredentialReference(value))
        }
        for (value in listOf("", " ", "unknown", "not_applicable", "synthetic-private-password", "Bearer synthetic",
            "https://fixture.invalid", "secret://", "secret://fixture?token=value", "secret://fixture#part",
            " secret://fixture", "secret://fixture ", "secret://fixture\r\n", "secret://fixture\n\n", "secret://雪",
            "secret://fixture\uD800")) {
            invalid("credential_ref", value, PreviewMonitoringStackProblem.REFERENCE)
            assertFalse(PreviewMonitoringStackSettings.canRetainCredentialReference(value))
        }
        assertTrue(PreviewMonitoringStackSettings.canRetainCredentialReference(null))
        assertEquals("{}", PreviewSelectionForms.settingsText(selection()))
    }

    @Test fun credentialRecoveryClassificationIsIndependentOfInvalidNumericSiblingsAndBounded() {
        val reference = "secret://fixture/reference\n"
        val input = PreviewMonitoringStackInput("not a number", "fractional days", reference)
        val result = PreviewMonitoringStackSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(keys.take(2), result.issues.map { it.field })
        assertTrue(PreviewMonitoringStackSettings.canRetainCredentialReference(input.credentialRef))
        val oversized = "secret://" + "x".repeat(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS)
        assertFalse(PreviewMonitoringStackSettings.canRetainCredentialReference(oversized))
        invalid("credential_ref", oversized, PreviewMonitoringStackProblem.TOO_LONG)
        val bad = input.copy(credentialRef = "synthetic-private-value")
        assertFalse(PreviewMonitoringStackSettings.canRetainCredentialReference(bad.credentialRef))
    }

    @Test fun independentErrorsRemainValueFreeImmutableAndRejectValidSiblingEdits() {
        val marker = "synthetic-private-value"
        val input = PreviewMonitoringStackInput(marker, marker, marker)
        val result = PreviewMonitoringStackSettings.configure(selection(), input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(keys, result.issues.map { it.field })
        assertEquals(listOf(PreviewMonitoringStackProblem.NUMBER, PreviewMonitoringStackProblem.INTEGER,
            PreviewMonitoringStackProblem.REFERENCE), result.issues.map { it.problem })
        for (value in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(value.contains(marker))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val mixed = PreviewMonitoringStackSettings.configure(selection(), PreviewMonitoringStackInput("0", "7", marker))
        assertSame(PreviewSelectionPreparation.Invalid, mixed.preparation)
        assertEquals(listOf("credential_ref"), mixed.issues.map { it.field })
    }

    @Test fun untouchedAdmittedNumberNodesAndFinalLfReferenceSurviveSiblingChanges() {
        val source = selection("""{"sample_rate":0.00,"retention_days":1e2,"credential_ref":"secret://fixture/path\n"}""")
        val original = PreviewMonitoringStackSettings.initial(source)
        val changed = configured(source, original.copy(credentialRef = null))
        for (key in keys.take(2)) {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
        assertEquals("secret://fixture/path\n", configured(source, original.copy(sampleRate = "1")).settings["credential_ref"].textValue())
        assertEquals(source, configured(source, original))
    }

    @Test fun configurationPreservesWholeSelectionLinksAndOptionalIntent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection()
        val link = SelectionFixtures.link("link.fixture", source.id, model.id, false)
        val profile = DraftWorkload(region = "entered", requestsPerDay = BigInteger("17"))
        val before = DraftContent("objective", "context", workload = profile,
            components = DraftSelections(listOf(source, model), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before, command(source,
            PreviewMonitoringStackInput("0.125", "365", "secret://fixture/monitor"))) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(model, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun finalWholeSelectionAndCombinedSettingsBudgetsRemainMandatory() {
        val limits = JsonInputLimits()
        val node = selection("""{"credential_ref":"secret://short"}""").toJson()
        val padding = node.putObject("extensions").putArray("x_padding")
        repeat(64) { padding.add("") }
        val target = limits.maxBytes - 1_000
        var remaining = target - node.toString().toByteArray(Charsets.UTF_8).size
        repeat(64) { index ->
            val length = minOf(limits.maxStringLength, remaining)
            padding.set(index, SelectionFixtures.mapper.nodeFactory.textNode("a".repeat(length)))
            remaining -= length
        }
        assertEquals(0, remaining)
        val source = LocalSelection.fromJson(node)
        val original = source.toJson()
        val allowed = PreviewMonitoringStackInput("0.5", "2", "secret://fixture/reference")
        val accepted = configured(source, allowed)
        assertEquals(original["extensions"], accepted.toJson()["extensions"])
        val oversized = "secret://" + "a".repeat(2_000)
        val rejected = PreviewMonitoringStackSettings.configure(source, allowed.copy(credentialRef = oversized))
        assertSame(PreviewSelectionPreparation.Invalid, rejected.preparation)
        assertTrue(rejected.issues.isEmpty())
        assertEquals(original, source.toJson())
        val maxReference = "secret://" + "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT - 9)
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewMonitoringStackSettings.configure(selection(), PreviewMonitoringStackInput("0", "0", maxReference)).preparation)
    }

    @Test fun managedNoopStaleAndDeletedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewMonitoringStackInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewMonitoringStackInput(sampleRate = "0"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewMonitoringStackInput(retentionDays = "1"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewMonitoringStackInput(sampleRate = "1"))) is PreviewSelectionSave.Failed)
    }

    @Test fun savedProjectionReopenHistoryAndPrivateMetadataRetainExactSettings() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "monitoring_stack" }
        val originalBytes = raw()
        val input = PreviewMonitoringStackInput("0.999999999999999999", "9223372036854775808", "secret://fixture/reference\n")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.content.workload, reopened.content.workload)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewMonitoringStackSettings.configure(reopened.content.components.selections.single { it.kind == "monitoring_stack" },
                input.copy(credentialRef = "synthetic-private-password")).preparation)
        assertArrayEquals(savedBytes, raw())
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "monitoring_stack" }
        assertEquals(saved.content.components.selections.single { it.kind == "monitoring_stack" }.toJson(), projected)
        assertTrue(projected["settings"]["sample_rate"].isNumber)
        assertEquals(BigDecimal("0.999999999999999999"), projected["settings"]["sample_rate"].decimalValue())
        assertEquals(BigInteger("9223372036854775808"), projected["settings"]["retention_days"].bigIntegerValue())
        assertEquals("secret://fixture/reference\n", projected["settings"]["credential_ref"].textValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun anotherKindCannotAcquireMonitoringSettingsOrRecoveryAuthority() {
        val model = SelectionFixtures.selection()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewMonitoringStackSettings.configure(model, PreviewMonitoringStackInput(sampleRate = "0")).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewMonitoringStackSettings.initial(model) }
    }
}
