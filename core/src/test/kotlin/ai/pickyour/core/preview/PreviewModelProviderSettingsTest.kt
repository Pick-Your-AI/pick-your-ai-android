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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Synthetic local settings only; no provider is contacted and no credential is resolved. */
class PreviewModelProviderSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()

    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("model_provider", "sel_provider", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewModelProviderInput) =
        (PreviewModelProviderSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewModelProviderInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(source: LocalSelection, input: PreviewModelProviderInput, key: String,
        problem: PreviewModelProviderProblem) {
        val result = PreviewModelProviderSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes
    private fun files(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }

    @Test fun threeOptionalStringsUseTheExactFrozenMetadataWithoutInventedRegionConstraints() {
        val fields = PreviewModelProviderSettings.fields
        assertSame(PreviewSettingsMetadata.fields("model_provider"), fields)
        assertEquals(listOf("region", "credential_ref", "api_base_url"), fields.map { it.key })
        assertTrue(fields.all { !it.required && it.type == PreviewSettingType.STRING })
        assertTrue(fields.all { it.enumValues.isEmpty() && it.minimumLength == null })
        assertNull(fields[0].pattern)
        assertNull(fields[0].format)
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields[1].pattern)
        assertEquals("Opaque secret manager reference, never the credential", fields[1].description)
        assertEquals("uri", fields[2].format)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun initialAbsenceSuppliedEmptyAndExplicitRemovalRemainDistinct() {
        val empty = selection()
        assertEquals(PreviewModelProviderInput(), PreviewModelProviderSettings.initial(empty))
        assertEquals(empty, configured(empty, PreviewModelProviderInput()))
        val source = selection("""{"region":"","credential_ref":"secret://fixture/ref","api_base_url":"urn:fixture:provider"}""")
        val initial = PreviewModelProviderSettings.initial(source)
        assertEquals("", initial.region)
        assertEquals("secret://fixture/ref", initial.credentialRef)
        assertEquals("urn:fixture:provider", initial.apiBaseUrl)
        assertEquals(source, configured(source, initial))
        for ((key, input) in listOf("region" to initial.copy(region = null),
            "credential_ref" to initial.copy(credentialRef = null), "api_base_url" to initial.copy(apiBaseUrl = null))) {
            val removed = configured(source, input)
            assertFalse(removed.settings.has(key))
            assertEquals(source.settings.also { it.remove(key) }, removed.settings)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(source, PreviewModelProviderInput())))
        assertEquals(3, source.settings.size())
    }

    @Test fun regionPreservesWhitespaceUnicodeControlsAndOrdinaryFreeTextLiterally() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", " literal region ", "東京/\uD834\uDD1E", "\u0000",
            "unknown", "not_applicable", "Not supplied", "secret://fixture/ref",
            "https://fixture-user:fixture-value@example.invalid/path")) {
            val changed = configured(source, PreviewModelProviderInput(region = text))
            assertTrue(changed.settings["region"].isTextual)
            assertEquals(text, changed.settings["region"].textValue())
            assertEquals(text, PreviewModelProviderSettings.initial(changed).region)
            assertEquals(1, changed.settings.size())
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun regionJsonLookingTextCannotSupplyAnotherKeyOrChangeItsStringType() {
        val source = selection()
        for (text in listOf("null", "true", "1", "[]", "{}", "\",\"credential_ref\":\"synthetic-password",
            "{\"api_base_url\":\"https://user:fixture@host.invalid\"}", "a\\b\"c\n")) {
            val changed = configured(source, PreviewModelProviderInput(region = text))
            assertEquals(setOf("region"), changed.settings.fieldNames().asSequence().toSet())
            assertTrue(changed.settings["region"].isTextual)
            assertEquals(text, changed.settings["region"].textValue())
        }
    }

    @Test fun malformedUnicodeAndOverlongFieldsCannotPrepareACommand() {
        val source = selection()
        for (text in listOf("\uD800", "\uDC00", "region\uD800suffix")) {
            invalid(source, PreviewModelProviderInput(region = text), "region", PreviewModelProviderProblem.TEXT)
            invalid(source, PreviewModelProviderInput(credentialRef = "secret://fixture/" + text),
                "credential_ref", PreviewModelProviderProblem.REFERENCE)
            invalid(source, PreviewModelProviderInput(apiBaseUrl = "urn:fixture:" + text),
                "api_base_url", PreviewModelProviderProblem.URI)
        }
        val tooLong = "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)
        invalid(source, PreviewModelProviderInput(region = tooLong), "region", PreviewModelProviderProblem.TOO_LONG)
        invalid(source, PreviewModelProviderInput(credentialRef = tooLong), "credential_ref", PreviewModelProviderProblem.TOO_LONG)
        invalid(source, PreviewModelProviderInput(apiBaseUrl = tooLong), "api_base_url", PreviewModelProviderProblem.TOO_LONG)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun completeSettingsBudgetIncludesJsonEnvelopeAndEscapedOriginalValues() {
        val source = selection()
        val admitted = "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT - "{\"region\":\"\"}".length)
        val changed = configured(source, PreviewModelProviderInput(region = admitted))
        assertEquals(PreviewSelectionForms.MAX_SETTINGS_TEXT, PreviewSelectionForms.settingsText(changed).length)
        assertEquals(admitted, changed.settings["region"].textValue())
        for (text in listOf(admitted + "a", "\n".repeat(9_000))) {
            assertTrue(text.length <= PreviewSelectionForms.MAX_SETTINGS_TEXT)
            val result = PreviewModelProviderSettings.configure(source, PreviewModelProviderInput(region = text))
            assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun opaqueCredentialReferencesPreserveCasePunctuationAndFrozenTerminalLfSemantics() {
        val source = selection()
        for (text in listOf("secret://fixture/Case:Reference_Exact", "secret://fixture/ref.\n")) {
            val changed = configured(source, PreviewModelProviderInput(credentialRef = text))
            assertEquals(text, changed.settings["credential_ref"].textValue())
            assertTrue(PreviewModelProviderSettings.canRetainCredentialReference(text))
            assertFalse(changed.settings.has("region"))
            assertFalse(changed.settings.has("api_base_url"))
        }
        assertTrue(PreviewModelProviderSettings.canRetainCredentialReference(null))
    }

    @Test fun credentialAliasesRawSecretsAndInvalidReferencesCannotSaveOrEnterTypedRecovery() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", "synthetic-password", "secret://", " secret://fixture/ref",
            "secret://fixture/ref ", "secret://fixture/ref\r", "secret://fixture/ref\r\n", "secret://fixture/ref\n\n",
            "secret://fixture/@ref", "secret://fixture/%2f", "SECRET://fixture/ref")) {
            invalid(source, PreviewModelProviderInput(credentialRef = text), "credential_ref", PreviewModelProviderProblem.REFERENCE)
            assertFalse(PreviewModelProviderSettings.canRetainCredentialReference(text))
        }
        val bounded = "secret://" + "a".repeat(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS - "secret://".length)
        assertTrue(PreviewModelProviderSettings.canRetainCredentialReference(bounded))
        assertFalse(PreviewModelProviderSettings.canRetainCredentialReference(bounded + "a"))
        invalid(source, PreviewModelProviderInput(credentialRef = bounded), "credential_ref", PreviewModelProviderProblem.TOO_LONG)
    }

    @Test fun legitimateUriSpellingRemainsLiteralIncludingQueryFragmentAndOpaqueAtSigns() {
        val source = selection()
        val values = listOf("custom+fixture://Example.invalid:9443/a/%2F?q=exact@query#@fragment",
            "https://example.invalid/path@literal?credential=synthetic-query#literal",
            "https://example.invalid/path%40literal", "file:///literal/provider",
            "urn:fixture:opaque@value", "custom+fixture:opaque@reference",
            "custom+fixture://host_name/resource", "custom+fixture://host_name:port/resource")
        for (text in values) {
            val changed = configured(source, PreviewModelProviderInput(apiBaseUrl = text))
            assertEquals(text, changed.settings["api_base_url"].textValue())
            assertEquals(text, PreviewModelProviderSettings.initial(changed).apiBaseUrl)
            assertFalse(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(text))
        }
        assertFalse(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(null))
    }

    @Test fun recognizedServerRegistryEncodedAndEmptyAuthorityUserinfoRejectsNewConfiguration() {
        val source = selection()
        for (text in listOf("https://user:synthetic@host.invalid/path", "https://user%3Asecret@host.invalid",
            "https://local%40user:fixture@[::1]:9443/path", "https://@host.invalid/path",
            "custom+fixture://user@host_name/resource", "custom+fixture://@host_name/resource")) {
            invalid(source, PreviewModelProviderInput(apiBaseUrl = text), "api_base_url", PreviewModelProviderProblem.URI)
            assertTrue(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(text))
        }
        val prefix = "custom+fixture://user@host_name/"
        val bounded = prefix + "a".repeat(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS - prefix.length)
        assertTrue(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(bounded))
        assertFalse(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(bounded + "a"))
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun malformedUriDoesNotMasqueradeAsRecognizedUserinfoAndCanBeCorrected() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", "not a uri", "relative/path", "https://bad authority",
            "https://[broken", "https://example.invalid/%broken")) {
            invalid(source, PreviewModelProviderInput(apiBaseUrl = text), "api_base_url", PreviewModelProviderProblem.URI)
            assertFalse(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(text))
        }
        val corrected = "custom+fixture://example.invalid/corrected@path?q=exact@value"
        assertEquals(corrected, configured(source, PreviewModelProviderInput(apiBaseUrl = corrected)).settings["api_base_url"].textValue())
    }

    @Test fun privacyClassificationAndIndependentFieldErrorsDoNotDependOnOtherInputValidity() {
        val source = selection()
        val uri = "custom+fixture://synthetic:private@host_name/resource"
        val input = PreviewModelProviderInput("region\uD800", "synthetic-password", uri)
        val result = PreviewModelProviderSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("region", "credential_ref", "api_base_url"), result.issues.map { it.field })
        assertEquals(listOf(PreviewModelProviderProblem.TEXT, PreviewModelProviderProblem.REFERENCE,
            PreviewModelProviderProblem.URI), result.issues.map { it.problem })
        assertTrue(PreviewModelProviderSettings.hasRecognizedApiBaseUrlUserinfo(input.apiBaseUrl))
        assertFalse(PreviewModelProviderSettings.canRetainCredentialReference(input.credentialRef))
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun individuallyAdmittedFieldsCannotBypassTheCombinedSettingsBudget() {
        val source = selection()
        val input = PreviewModelProviderInput("r".repeat(6_000), "secret://" + "a".repeat(6_000),
            "urn:fixture:" + "b".repeat(6_000))
        for (single in listOf(input.copy(credentialRef = null, apiBaseUrl = null),
            input.copy(region = null, apiBaseUrl = null), input.copy(region = null, credentialRef = null))) {
            assertTrue(PreviewModelProviderSettings.configure(source, single).preparation is PreviewSelectionPreparation.Ready)
        }
        val combined = PreviewModelProviderSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, combined.preparation)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurePreservesSelectionIdentityRoleFlagsNotesExtensionsLinksAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", model.id, source.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "unrelated objective region"),
            components = DraftSelections(listOf(source, model), listOf(link)))
        val input = PreviewModelProviderInput(" ", "secret://fixture/Ref:Exact", "urn:fixture:provider")
        val changed = (DraftSelectionCommands.apply(before, command(source, input)) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(model, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun managedNoopSuppliedEmptyStaleAndDeletedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewModelProviderInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewModelProviderInput(region = ""))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertTrue(saved.components.selections.single().settings.has("region"))
        assertEquals("", saved.components.selections.single().settings["region"].textValue())
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewModelProviderInput(region = "stale"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewModelProviderInput(region = "deleted"))) is PreviewSelectionSave.Failed)
    }

    @Test fun managedThreeStringsReopenAndRoundTripThroughAnExplicitValidatedBuildCandidate() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "model_provider" }
        val before = raw()
        val input = PreviewModelProviderInput(" \t東京/\uD834\uDD1E\n", "secret://fixture/Case:Reference_Exact",
            "custom+fixture://Example.invalid/path@literal?q=exact@query#@fragment")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "model_provider" }
        assertEquals(saved.content.components.selections.single { it.kind == "model_provider" }.toJson(), projected)
        assertEquals(input.region, projected["settings"]["region"].textValue())
        assertEquals(input.credentialRef, projected["settings"]["credential_ref"].textValue())
        assertEquals(input.apiBaseUrl, projected["settings"]["api_base_url"].textValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(before, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun rejectedSettingsCannotDispatchOrReplaceAcknowledgedRevisionAndManagedFileBytes() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content()) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "model_provider" }
        val originalFiles = files()
        val initial = PreviewModelProviderSettings.initial(source)
        for (input in listOf(initial.copy(region = "\uD800"), initial.copy(credentialRef = "synthetic-password"),
            initial.copy(apiBaseUrl = "custom+fixture://user:synthetic@host_name/path"),
            initial.copy(region = "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)))) {
            val prepared = PreviewModelProviderSettings.configure(source, input).preparation
            val dispatched = if (prepared is PreviewSelectionPreparation.Ready) {
                store.applySelection(DraftSelectionIntent(original.version, prepared.command))
                true
            } else false
            assertFalse(dispatched)
            val reopened = (store.load() as DraftLoad.Available).draft
            assertEquals(original.version.number, reopened.version.number)
            assertEquals(original.version.hash, reopened.version.hash)
            assertEquals(original.content, reopened.content)
            assertEquals(originalFiles, files())
        }
    }

    @Test fun historicalUriSettingsRemainReadableAndRequireExplicitCorrectionForNewConfiguration() {
        val uri = "https://user:synthetic@host.invalid/path"
        val source = selection(SelectionFixtures.mapper.createObjectNode().put("region", " ").put("api_base_url", uri).toString())
        val initial = PreviewModelProviderSettings.initial(source)
        assertEquals(uri, initial.apiBaseUrl)
        invalid(source, initial, "api_base_url", PreviewModelProviderProblem.URI)
        assertEquals(uri, LocalSelection.fromJson(source.toJson()).settings["api_base_url"].textValue())
        val corrected = configured(source, initial.copy(apiBaseUrl = null))
        assertFalse(corrected.settings.has("api_base_url"))
        assertEquals(" ", corrected.settings["region"].textValue())
        assertEquals(uri, source.settings["api_base_url"].textValue())
    }

    @Test fun unsupportedKindsAndValueFreeDescriptionsCannotLeakOrMutateInputs() {
        val marker = "synthetic-private-value"
        val input = PreviewModelProviderInput(marker + '\uD800', marker, "https://user:$marker@host.invalid")
        val result = PreviewModelProviderSettings.configure(selection(), input)
        assertEquals(3, result.issues.size)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        for (text in listOf(input.toString(), result.toString(), result.issues.toString(), result.preparation.toString()))
            assertFalse(text.contains(marker))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val unsupported = SelectionFixtures.selection("model")
        val original = unsupported.toJson()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewModelProviderSettings.configure(unsupported, PreviewModelProviderInput()).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewModelProviderSettings.initial(unsupported) }
        assertEquals(original, unsupported.toJson())
    }
}
