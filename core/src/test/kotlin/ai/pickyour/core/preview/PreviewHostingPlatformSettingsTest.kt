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

/** Synthetic declared settings only; no account resolution or hosting/network action. */
class PreviewHostingPlatformSettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()

    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("hosting_platform", "sel_hosting", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewHostingPlatformInput) =
        (PreviewHostingPlatformSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewHostingPlatformInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(source: LocalSelection, input: PreviewHostingPlatformInput, key: String,
        problem: PreviewHostingPlatformProblem) {
        val result = PreviewHostingPlatformSettings.configure(source, input)
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

    @Test fun threeOptionalFieldsUseTheExactFrozenStringPatternAndChoiceMetadata() {
        val fields = PreviewHostingPlatformSettings.fields
        assertSame(PreviewSettingsMetadata.fields("hosting_platform"), fields)
        assertEquals(listOf("region", "account_ref", "network_isolation"), fields.map { it.key })
        assertTrue(fields.all { !it.required && it.type == PreviewSettingType.STRING })
        assertTrue(fields.all { it.minimumLength == null && it.format == null })
        assertTrue(fields[0].enumValues.isEmpty())
        assertNull(fields[0].pattern)
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields[1].pattern)
        assertEquals("Opaque secret manager reference, never the credential", fields[1].description)
        assertEquals(listOf("public", "private", "air_gapped"), fields[2].enumValues)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (fields[2].enumValues as MutableList).clear() }
    }

    @Test fun allEightPresenceCombinationsAndExplicitRemovalKeepOmissionDistinct() {
        val source = selection()
        val full = PreviewHostingPlatformInput("", "secret://fixture/Ref", "private")
        for (mask in 0..7) {
            val input = PreviewHostingPlatformInput(if (mask and 1 != 0) full.region else null,
                if (mask and 2 != 0) full.accountRef else null, if (mask and 4 != 0) full.networkIsolation else null)
            val changed = configured(source, input)
            assertEquals(input, PreviewHostingPlatformSettings.initial(changed))
            assertEquals(Integer.bitCount(mask), changed.settings.size())
        }
        val supplied = configured(source, full)
        for ((key, input) in listOf("region" to full.copy(region = null),
            "account_ref" to full.copy(accountRef = null), "network_isolation" to full.copy(networkIsolation = null))) {
            val changed = configured(supplied, input)
            assertFalse(changed.settings.has(key))
            assertEquals(supplied.settings.also { it.remove(key) }, changed.settings)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(supplied, PreviewHostingPlatformInput())))
        assertEquals(3, supplied.settings.size())
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun regionPreservesEmptyWhitespaceUnicodeControlsAndUnknownAsLiteralStrings() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", " literal region ", "東京/\uD834\uDD1E", "\u0000",
            "unknown", "not_applicable", "Not supplied", "secret://fixture/ref",
            "https://fixture-user:fixture-value@example.invalid/path")) {
            val changed = configured(source, PreviewHostingPlatformInput(region = text))
            assertTrue(changed.settings["region"].isTextual)
            assertEquals(text, changed.settings["region"].textValue())
            assertEquals(text, PreviewHostingPlatformSettings.initial(changed).region)
            assertEquals(1, changed.settings.size())
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun jsonLookingRegionTextCannotInjectSettingsOrChangeItsStringType() {
        val source = selection()
        for (text in listOf("null", "true", "1", "[]", "{}", "\",\"account_ref\":\"synthetic-password",
            "{\"network_isolation\":\"private\"}", "a\\b\"c\n")) {
            val changed = configured(source, PreviewHostingPlatformInput(region = text))
            assertEquals(setOf("region"), changed.settings.fieldNames().asSequence().toSet())
            assertTrue(changed.settings["region"].isTextual)
            assertEquals(text, changed.settings["region"].textValue())
        }
    }

    @Test fun everyNetworkChoiceAndOmissionRoundTripWithoutDefaultsOrCaseCoercion() {
        val source = selection()
        for (choice in listOf(null, "public", "private", "air_gapped")) {
            val changed = configured(source, PreviewHostingPlatformInput(networkIsolation = choice))
            assertEquals(choice, PreviewHostingPlatformSettings.initial(changed).networkIsolation)
            assertEquals(choice != null, changed.settings.has("network_isolation"))
            if (choice != null) {
                assertTrue(changed.settings["network_isolation"].isTextual)
                assertEquals(choice, changed.settings["network_isolation"].textValue())
            }
        }
        for (choice in listOf("", " ", "unknown", "not_applicable", "PUBLIC", " private", "private\n", "air-gapped")) {
            invalid(source, PreviewHostingPlatformInput(networkIsolation = choice),
                "network_isolation", PreviewHostingPlatformProblem.ENUM)
        }
    }

    @Test fun frozenReceiverRejectsNonStringFieldsAndUnrecognizedKeysWithoutTypedCoercion() {
        for (key in listOf("region", "account_ref", "network_isolation")) {
            for (value in listOf("null", "true", "0", "[]", "{}")) {
                assertThrows(IllegalArgumentException::class.java) { selection("{\"$key\":$value}") }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { selection("""{"network":"private"}""") }
        assertThrows(IllegalArgumentException::class.java) { selection("""{"account_ref":"synthetic-password"}""") }
        assertThrows(IllegalArgumentException::class.java) { selection("""{"network_isolation":"unknown"}""") }
    }

    @Test fun malformedUnicodeAndOverlongFieldsCannotPrepareACommand() {
        val source = selection()
        for (text in listOf("\uD800", "\uDC00", "region\uD800suffix")) {
            invalid(source, PreviewHostingPlatformInput(region = text), "region", PreviewHostingPlatformProblem.TEXT)
            invalid(source, PreviewHostingPlatformInput(accountRef = "secret://fixture/" + text),
                "account_ref", PreviewHostingPlatformProblem.REFERENCE)
            invalid(source, PreviewHostingPlatformInput(networkIsolation = text),
                "network_isolation", PreviewHostingPlatformProblem.ENUM)
        }
        val tooLong = "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)
        invalid(source, PreviewHostingPlatformInput(region = tooLong), "region", PreviewHostingPlatformProblem.TOO_LONG)
        invalid(source, PreviewHostingPlatformInput(accountRef = tooLong), "account_ref", PreviewHostingPlatformProblem.TOO_LONG)
        invalid(source, PreviewHostingPlatformInput(networkIsolation = tooLong),
            "network_isolation", PreviewHostingPlatformProblem.TOO_LONG)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun completeSettingsBudgetIncludesJsonEnvelopeAndEscapedOriginalValues() {
        val source = selection()
        val admitted = "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT - "{\"region\":\"\"}".length)
        val changed = configured(source, PreviewHostingPlatformInput(region = admitted))
        assertEquals(PreviewSelectionForms.MAX_SETTINGS_TEXT, PreviewSelectionForms.settingsText(changed).length)
        assertEquals(admitted, changed.settings["region"].textValue())
        for (text in listOf(admitted + "a", "\n".repeat(9_000))) {
            assertTrue(text.length <= PreviewSelectionForms.MAX_SETTINGS_TEXT)
            assertSame(PreviewSelectionPreparation.Invalid,
                PreviewHostingPlatformSettings.configure(source, PreviewHostingPlatformInput(region = text)).preparation)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun opaqueAccountReferencesPreserveCasePunctuationAndFrozenFinalLfSemantics() {
        val source = selection()
        for (text in listOf("secret://fixture/Case:Reference_Exact", "secret://fixture/ref.\n")) {
            val changed = configured(source, PreviewHostingPlatformInput(accountRef = text))
            assertEquals(text, changed.settings["account_ref"].textValue())
            assertTrue(PreviewHostingPlatformSettings.canRetainAccountReference(text))
            assertFalse(changed.settings.has("region"))
            assertFalse(changed.settings.has("network_isolation"))
        }
        assertTrue(PreviewHostingPlatformSettings.canRetainAccountReference(null))
    }

    @Test fun rawSecretsAndInvalidReferencesCannotSaveOrEnterBoundedTypedRecovery() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", "synthetic-password", "secret://", " secret://fixture/ref",
            "secret://fixture/ref ", "secret://fixture/ref\r", "secret://fixture/ref\r\n", "secret://fixture/ref\n\n",
            "secret://fixture/@ref", "secret://fixture/%2f", "SECRET://fixture/ref")) {
            invalid(source, PreviewHostingPlatformInput(accountRef = text), "account_ref", PreviewHostingPlatformProblem.REFERENCE)
            assertFalse(PreviewHostingPlatformSettings.canRetainAccountReference(text))
        }
        val bounded = "secret://" + "a".repeat(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS - "secret://".length)
        assertTrue(PreviewHostingPlatformSettings.canRetainAccountReference(bounded))
        assertFalse(PreviewHostingPlatformSettings.canRetainAccountReference(bounded + "a"))
        invalid(source, PreviewHostingPlatformInput(accountRef = bounded), "account_ref", PreviewHostingPlatformProblem.TOO_LONG)
    }

    @Test fun independentFieldErrorsAreValueFreeAndNeverExposeAPartialCommand() {
        val source = selection()
        val marker = "synthetic-private-value"
        val input = PreviewHostingPlatformInput(marker + '\uD800', marker, marker)
        val result = PreviewHostingPlatformSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("region", "account_ref", "network_isolation"), result.issues.map { it.field })
        assertEquals(listOf(PreviewHostingPlatformProblem.TEXT, PreviewHostingPlatformProblem.REFERENCE,
            PreviewHostingPlatformProblem.ENUM), result.issues.map { it.problem })
        for (text in listOf(input.toString(), result.toString(), result.issues.toString(), result.preparation.toString()))
            assertFalse(text.contains(marker))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun individuallyAdmittedFieldsCannotBypassTheCombinedSettingsBudget() {
        val source = selection()
        val input = PreviewHostingPlatformInput("r".repeat(9_000), "secret://" + "a".repeat(9_000), "air_gapped")
        for (single in listOf(input.copy(accountRef = null, networkIsolation = null),
            input.copy(region = null, networkIsolation = null), input.copy(region = null, accountRef = null))) {
            assertTrue(PreviewHostingPlatformSettings.configure(source, single).preparation is PreviewSelectionPreparation.Ready)
        }
        assertSame(PreviewSelectionPreparation.Invalid, PreviewHostingPlatformSettings.configure(source, input).preparation)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurePreservesIdentityRoleFlagsNotesExtensionsLinksAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", model.id, source.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "unrelated objective region"),
            components = DraftSelections(listOf(source, model), listOf(link)))
        val changed = (DraftSelectionCommands.apply(before,
            command(source, PreviewHostingPlatformInput(" ", "secret://fixture/Ref:Exact", "private"))) as SelectionEdit.Ready).content
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
        val noop = adapter.applySelection(before, command(source, PreviewHostingPlatformInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewHostingPlatformInput(region = ""))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertTrue(saved.components.selections.single().settings.has("region"))
        assertEquals("", saved.components.selections.single().settings["region"].textValue())
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewHostingPlatformInput(region = "stale"))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewHostingPlatformInput(region = "deleted"))) is PreviewSelectionSave.Failed)
    }

    @Test fun removedSelectionCannotBeRecreatedByAPreparedHostingCommand() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val prepared = command(source, PreviewHostingPlatformInput(networkIsolation = "private"))
        val removed = (adapter.applySelection(before, SelectionCommand.Remove(source.id)) as PreviewSelectionSave.Saved).record
        assertTrue(removed.components.selections.isEmpty())
        val priorFiles = files()
        assertSame(PreviewSelectionSave.Conflict, adapter.applySelection(before, prepared))
        assertFalse(adapter.applySelection(removed, prepared) is PreviewSelectionSave.Saved)
        val reopened = (adapter.load() as PreviewDraftLoad.Available).record
        assertEquals(removed.baselineIdentity, reopened.baselineIdentity)
        assertTrue(reopened.components.selections.isEmpty())
        assertEquals(priorFiles, files())
    }

    @Test fun threeStringsReopenAndRoundTripThroughAnExplicitValidatedBuildCandidate() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "hosting_platform" }
        val before = raw()
        val input = PreviewHostingPlatformInput(" \t東京/\uD834\uDD1E\n", "secret://fixture/Case:Reference_Exact\n", "air_gapped")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "hosting_platform" }
        assertEquals(saved.content.components.selections.single { it.kind == "hosting_platform" }.toJson(), projected)
        assertEquals(input.region, projected["settings"]["region"].textValue())
        assertEquals(input.accountRef, projected["settings"]["account_ref"].textValue())
        assertEquals(input.networkIsolation, projected["settings"]["network_isolation"].textValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(before, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun rejectedSettingsCannotReplaceAcknowledgedRevisionOrManagedFileBytes() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true)) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "hosting_platform" }
        val originalFiles = files()
        val initial = PreviewHostingPlatformSettings.initial(source)
        for (input in listOf(initial.copy(region = "\uD800"), initial.copy(accountRef = "synthetic-password"),
            initial.copy(networkIsolation = "unknown"), initial.copy(region = "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)))) {
            val prepared = PreviewHostingPlatformSettings.configure(source, input).preparation
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

    @Test fun unsupportedKindsStayUnchangedAndCannotSupplyHostingPreparation() {
        val unsupported = SelectionFixtures.selection("model")
        val original = unsupported.toJson()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewHostingPlatformSettings.configure(unsupported, PreviewHostingPlatformInput()).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewHostingPlatformSettings.initial(unsupported) }
        assertEquals(original, unsupported.toJson())
    }
}
