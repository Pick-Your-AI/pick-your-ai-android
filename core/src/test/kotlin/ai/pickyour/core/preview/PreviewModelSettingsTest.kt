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

class PreviewModelSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()

    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("model", "sel_model", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewModelInput) =
        (PreviewModelSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewModelInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(source: LocalSelection, input: PreviewModelInput, key: String, problem: PreviewModelProblem? = null) {
        val result = PreviewModelSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes

    @Test fun sevenOptionalFieldsRetainExactFrozenMetadataWithoutConditionalDefaults() {
        val fields = PreviewModelSettings.fields
        assertEquals(listOf("temperature", "top_p", "max_output_tokens", "precision", "serving_mode", "credential_ref", "weights_uri"),
            fields.map { it.key })
        assertTrue(fields.none { it.required })
        assertEquals(listOf(PreviewSettingType.NUMBER, PreviewSettingType.NUMBER, PreviewSettingType.INTEGER,
            PreviewSettingType.STRING, PreviewSettingType.STRING, PreviewSettingType.STRING, PreviewSettingType.STRING), fields.map { it.type })
        assertEquals(BigDecimal.ZERO, fields[0].minimum)
        assertEquals(BigDecimal("2"), fields[0].maximum)
        assertEquals(BigDecimal.ZERO, fields[1].exclusiveMinimum)
        assertEquals(BigDecimal.ONE, fields[1].maximum)
        assertEquals(BigDecimal.ONE, fields[2].minimum)
        assertNull(fields[2].maximum)
        assertEquals(listOf("fp32", "tf32", "bf16", "fp16", "fp8", "int8", "int4"), fields[3].enumValues)
        assertEquals(listOf("api", "weights"), fields[4].enumValues)
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields[5].pattern)
        assertEquals("uri", fields[6].format)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fields[3].enumValues as MutableList).clear() }
    }

    @Test fun initialAbsenceExplicitZeroAndRemovalDoNotInventSuppliedValues() {
        val empty = selection()
        assertEquals(PreviewModelInput(), PreviewModelSettings.initial(empty))
        assertEquals(empty, configured(empty, PreviewModelInput()))
        val source = selection("""{"temperature":0,"top_p":1,"max_output_tokens":1,"precision":"tf32","serving_mode":"api","credential_ref":"secret://fixture/key","weights_uri":"urn:fixture:model"}""")
        val initial = PreviewModelSettings.initial(source)
        assertEquals("0", initial.temperature)
        val removed = configured(source, initial.copy(temperature = null))
        assertFalse(removed.settings.has("temperature"))
        assertEquals(source.settings.also { it.remove("temperature") }, removed.settings)
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(source, PreviewModelInput())))
        assertEquals(7, source.settings.size())
    }

    @Test fun suppliedEmptyAndWhitespaceStayInvalidForEveryTextControl() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n")) {
            invalid(source, PreviewModelInput(temperature = text), "temperature", PreviewModelProblem.REQUIRED)
            invalid(source, PreviewModelInput(topP = text), "top_p", PreviewModelProblem.REQUIRED)
            invalid(source, PreviewModelInput(maxOutputTokens = text), "max_output_tokens", PreviewModelProblem.REQUIRED)
            invalid(source, PreviewModelInput(credentialRef = text), "credential_ref", PreviewModelProblem.REQUIRED)
            invalid(source, PreviewModelInput(weightsUri = text), "weights_uri", PreviewModelProblem.REQUIRED)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun temperatureAndTopPUseExactInclusiveAndExclusiveDecimalBounds() {
        val source = selection()
        for (text in listOf("0", "2", "0.100000000000000001", "1.999999999999999999999999")) {
            assertEquals(BigDecimal(text), configured(source, PreviewModelInput(temperature = text)).settings["temperature"].decimalValue())
        }
        for (text in listOf("-0.000000000000000001", "2.000000000000000001")) {
            invalid(source, PreviewModelInput(temperature = text), "temperature", PreviewModelProblem.RANGE)
        }
        for (text in listOf("1", "0.000000000000000000000000000001", "1e-100")) {
            assertEquals(BigDecimal(text), configured(source, PreviewModelInput(topP = text)).settings["top_p"].decimalValue())
        }
        for (text in listOf("0", "-0.000000000000000001", "1.000000000000000001")) {
            invalid(source, PreviewModelInput(topP = text), "top_p", PreviewModelProblem.RANGE)
        }
    }

    @Test fun outputTokensKeepWideExactIntegersAndMathematicalIntegerAliases() {
        val source = selection()
        for (text in listOf("1", "9007199254740993", "9".repeat(128), "1.000", "1e3")) {
            assertEquals(BigDecimal(text), configured(source, PreviewModelInput(maxOutputTokens = text)).settings["max_output_tokens"].decimalValue())
        }
        for (text in listOf("0", "-1")) invalid(source, PreviewModelInput(maxOutputTokens = text), "max_output_tokens", PreviewModelProblem.RANGE)
        for (text in listOf("1.5", "1.000000000000000001")) invalid(source, PreviewModelInput(maxOutputTokens = text), "max_output_tokens", PreviewModelProblem.INTEGER)
    }

    @Test fun numericSyntaxRejectsLocaleCoercionNonNumbersAndOversizedRepresentations() {
        val source = selection()
        val malformed = listOf("NaN", "Infinity", "-Infinity", "1,5", "1,000", "01", "+1", ".5", "١", "１",
            "null", "true", "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001",
            "1\u00a0", "\uD800", "9".repeat(129), " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))
        for (text in malformed) {
            invalid(source, PreviewModelInput(temperature = text), "temperature")
            invalid(source, PreviewModelInput(topP = text), "top_p")
            invalid(source, PreviewModelInput(maxOutputTokens = text), "max_output_tokens")
        }
    }

    @Test fun numericFieldCannotInjectAnotherKeyDuplicateOrTrailingDocument() {
        val source = selection()
        for (text in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "1,\"precision\":\"tf32\"")) {
            invalid(source, PreviewModelInput(temperature = text), "temperature", PreviewModelProblem.NUMBER)
            invalid(source, PreviewModelInput(maxOutputTokens = text), "max_output_tokens", PreviewModelProblem.INTEGER)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun untouchedAdmittedNumericNodesPreserveTheirExactScaleAndType() {
        val source = selection("""{"temperature":0.00,"top_p":1.000,"max_output_tokens":1e3}""")
        val changed = configured(source, PreviewModelSettings.initial(source).copy(precision = "tf32"))
        for (key in listOf("temperature", "top_p", "max_output_tokens")) {
            assertEquals(source.settings[key], changed.settings[key])
            assertEquals(source.settings[key].javaClass, changed.settings[key].javaClass)
            assertEquals(source.settings[key].decimalValue(), changed.settings[key].decimalValue())
        }
        assertEquals("tf32", changed.settings["precision"].textValue())
    }

    @Test fun everyCanonicalPrecisionAndServingModeRemainIndependentOptionalChoices() {
        val source = selection()
        for (precision in PreviewModelSettings.fields.single { it.key == "precision" }.enumValues) {
            for (mode in listOf("api", "weights")) {
                val input = PreviewModelInput(precision = precision, servingMode = mode,
                    credentialRef = "secret://fixture/Case:Reference_Exact", weightsUri = "urn:fixture:literal@value")
                val changed = configured(source, input)
                assertEquals(precision, changed.settings["precision"].textValue())
                assertEquals(mode, changed.settings["serving_mode"].textValue())
                val switched = configured(changed, input.copy(servingMode = if (mode == "api") "weights" else "api"))
                assertEquals(changed.settings.also { it.put("serving_mode", switched.settings["serving_mode"].textValue()) }, switched.settings)
                val removed = configured(changed, input.copy(precision = null, servingMode = null))
                assertEquals(2, removed.settings.size())
                assertEquals(input.credentialRef, removed.settings["credential_ref"].textValue())
                assertEquals(input.weightsUri, removed.settings["weights_uri"].textValue())
            }
        }
    }

    @Test fun enumDisplayLabelsUnknownEmptyAndCaseAliasesAreNeverWireValues() {
        val source = selection()
        for (text in listOf("", "unknown", "not_applicable", "Not supplied", "TF32", "tf32 ", "API", "api ", "secret://fixture/key")) {
            invalid(source, PreviewModelInput(precision = text), "precision", PreviewModelProblem.ENUM)
            invalid(source, PreviewModelInput(servingMode = text), "serving_mode", PreviewModelProblem.ENUM)
        }
    }

    @Test fun opaqueCredentialReferencePreservesFrozenFindAndTerminalLfSemantics() {
        val source = selection()
        for (text in listOf("secret://fixture/Case:Reference_Exact", "secret://fixture/ref.\n")) {
            val changed = configured(source, PreviewModelInput(credentialRef = text))
            assertEquals(text, changed.settings["credential_ref"].textValue())
            assertTrue(PreviewModelSettings.canRetainCredentialReference(text))
        }
        for (text in listOf("synthetic-password", "secret://", " secret://fixture/ref", "secret://fixture/ref ",
            "secret://fixture/ref\r", "secret://fixture/ref\r\n", "secret://fixture/ref\n\n", "secret://fixture/@ref",
            "secret://fixture/\uD800", "SECRET://fixture/ref")) {
            invalid(source, PreviewModelInput(credentialRef = text), "credential_ref", PreviewModelProblem.REFERENCE)
            assertFalse(PreviewModelSettings.canRetainCredentialReference(text))
        }
    }

    @Test fun legitimateUrisRemainLiteralWithoutOpeningOrCatalogCompatibilityInference() {
        val source = selection()
        val values = listOf("custom+fixture://Example.invalid/path@literal?q=exact@query#@fragment",
            "file:///literal/model", "urn:fixture:opaque@value", "https://example.invalid/path%40literal",
            "custom+fixture://host_name/resource", "custom+fixture://host_name:port/resource")
        for (text in values) {
            val changed = configured(source, PreviewModelInput(weightsUri = text, servingMode = "api"))
            assertEquals(text, changed.settings["weights_uri"].textValue())
            assertFalse(PreviewModelSettings.hasRecognizedWeightsUserinfo(text))
        }
    }

    @Test fun authorityUserinfoAndMalformedUnicodeCannotPrepareAnySave() {
        val source = selection()
        for (text in listOf("https://user:synthetic@host.invalid/path", "https://user%3Asecret@host.invalid",
            "custom+fixture://user@host_name/resource", "custom+fixture://@host_name/resource")) {
            invalid(source, PreviewModelInput(weightsUri = text), "weights_uri", PreviewModelProblem.URI)
            assertTrue(PreviewModelSettings.hasRecognizedWeightsUserinfo(text))
        }
        for (text in listOf("not a uri", "https://bad authority", "https://example.invalid/\uD800", "https://[broken")) {
            invalid(source, PreviewModelInput(weightsUri = text), "weights_uri", PreviewModelProblem.URI)
        }
    }

    @Test fun recoveryPrivacyClassificationDoesNotDependOnOtherFieldValidity() {
        val source = selection()
        val uri = "custom+fixture://synthetic:private@host_name/resource"
        val input = PreviewModelInput(temperature = "unfinished", topP = "0", credentialRef = "synthetic-password", weightsUri = uri)
        val result = PreviewModelSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("temperature", "top_p", "credential_ref", "weights_uri"), result.issues.map { it.field })
        assertTrue(PreviewModelSettings.hasRecognizedWeightsUserinfo(input.weightsUri))
        assertFalse(PreviewModelSettings.canRetainCredentialReference(input.credentialRef))
        assertTrue(PreviewModelSettings.canRetainCredentialReference(null))
        assertFalse(PreviewModelSettings.canRetainCredentialReference(""))
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun wholeSelectionAdmissionRejectsCombinedSettingsBeyondTheExistingBudget() {
        val source = selection()
        val credential = "secret://" + "a".repeat(9_000)
        val uri = "urn:fixture:" + "b".repeat(9_000)
        assertTrue(PreviewModelSettings.configure(source, PreviewModelInput(credentialRef = credential)).preparation is PreviewSelectionPreparation.Ready)
        assertTrue(PreviewModelSettings.configure(source, PreviewModelInput(weightsUri = uri)).preparation is PreviewSelectionPreparation.Ready)
        val combined = PreviewModelSettings.configure(source, PreviewModelInput(credentialRef = credential, weightsUri = uri))
        assertSame(PreviewSelectionPreparation.Invalid, combined.preparation)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurePreservesIdentityRoleFlagsNotesExtensionsLinksAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val provider = SelectionFixtures.selection("model_provider", "sel_provider")
        val link = SelectionFixtures.link("link.fixture", source.id, provider.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, provider), listOf(link)))
        val input = PreviewModelInput("0.123456789012345678", "1", "9007199254740993", "tf32", "weights",
            "secret://fixture/ref", "urn:fixture:model")
        val changed = (DraftSelectionCommands.apply(before, command(source, input)) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(provider, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(7, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun currentNoopStaleAndDeletedManagedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewModelInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewModelInput(temperature = "0"))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, command(source, PreviewModelInput(temperature = "1"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewModelInput(temperature = "2"))) is PreviewSelectionSave.Failed)
    }

    @Test fun managedSevenTypedValuesRoundTripThroughAnExplicitValidatedBuildCandidate() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "model" }
        val before = raw()
        val input = PreviewModelInput("0.100000000000000001", "1", "9007199254740993", "tf32", "api",
            "secret://fixture/Case:Reference_Exact", "custom+fixture://Example.invalid/path@literal?q=exact@query#@fragment")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "model" }
        assertEquals(saved.content.components.selections.single { it.kind == "model" }.toJson(), projected)
        assertEquals(BigDecimal("0.100000000000000001"), projected["settings"]["temperature"].decimalValue())
        assertEquals(BigInteger("9007199254740993"), projected["settings"]["max_output_tokens"].bigIntegerValue())
        assertEquals(input.credentialRef, projected["settings"]["credential_ref"].textValue())
        assertEquals(input.weightsUri, projected["settings"]["weights_uri"].textValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(before, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun errorsInputAndPreparationDescriptionsNeverEchoSuppliedValues() {
        val raw = "synthetic-private-value"
        val input = PreviewModelInput(raw, raw, raw, raw, raw, raw, raw)
        val result = PreviewModelSettings.configure(selection(), input)
        assertEquals(7, result.issues.size)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val unsupported = SelectionFixtures.selection("model_provider")
        assertSame(PreviewSelectionPreparation.Invalid, PreviewModelSettings.configure(unsupported, PreviewModelInput()).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewModelSettings.initial(unsupported) }
    }
}
