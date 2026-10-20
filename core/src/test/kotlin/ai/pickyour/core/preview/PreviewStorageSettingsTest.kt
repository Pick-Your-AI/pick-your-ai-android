package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Declared local settings only; no capacity measurement, key lookup, or storage service access. */
class PreviewStorageSettingsTest {
    @TempDir lateinit var root: Path

    private fun settings(text: String): ObjectNode =
        (BoundedJsonReader().read(text.toByteArray().inputStream()) as JsonInputResult.Parsed).value
    private fun selection(text: String = "{}"): LocalSelection = LocalSelection.fromJson(
        SelectionFixtures.node("storage", "sel_storage", "primary", false).also {
            it.set<JsonNode>("settings", settings(text))
        })
    private fun command(source: LocalSelection, input: PreviewStorageInput): SelectionCommand =
        (PreviewStorageSettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewStorageInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(source: LocalSelection, input: PreviewStorageInput, key: String,
        problem: PreviewStorageProblem) {
        val result = PreviewStorageSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        assertEquals(problem, result.issues.single().problem)
    }
    /** Keep the coherent complete fixture, its catalog identities, and every original relationship. */
    private fun content(storageSettings: String? = null): DraftContent {
        val original = ProjectionFixtures.content(allKinds = true)
        if (storageSettings == null) return original
        return original.withComponents(DraftSelections(original.components.selections.map { source ->
            if (source.kind != "storage") source else LocalSelection.fromJson(source.toJson().also {
                it.set<JsonNode>("settings", settings(storageSettings))
            })
        }, original.components.links))
    }
    private fun storage(draft: StoredDraft) = draft.content.components.selections.single { it.kind == "storage" }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes
    private fun files(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }

    @Test fun threeOptionalFieldsUseTheExactFrozenNumberTextAndReferenceMetadata() {
        val fields = PreviewStorageSettings.fields
        assertSame(PreviewSettingsMetadata.fields("storage"), fields)
        assertEquals(listOf("provisioned_gb", "region", "encryption_key_ref"), fields.map { it.key })
        assertEquals(listOf(PreviewSettingType.NUMBER, PreviewSettingType.STRING, PreviewSettingType.STRING),
            fields.map { it.type })
        assertTrue(fields.all { !it.required && it.enumValues.isEmpty() && it.minimumLength == null && it.format == null })
        assertEquals(BigDecimal.ZERO, fields[0].minimum)
        assertNull(fields[0].exclusiveMinimum)
        assertNull(fields[0].maximum)
        assertNull(fields[1].pattern)
        assertEquals("^secret://[A-Za-z0-9/_:.-]+$", fields[2].pattern)
        assertEquals("Opaque secret manager reference, never the credential", fields[2].description)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun allEightPresenceCombinationsAndExplicitRemovalKeepZeroEmptyAndAbsenceDistinct() {
        val source = selection()
        val full = PreviewStorageInput("0", "", "secret://fixture/Ref")
        for (mask in 0..7) {
            val input = PreviewStorageInput(if (mask and 1 != 0) full.provisionedGb else null,
                if (mask and 2 != 0) full.region else null, if (mask and 4 != 0) full.encryptionKeyRef else null)
            val changed = configured(source, input)
            assertEquals(input, PreviewStorageSettings.initial(changed))
            assertEquals(Integer.bitCount(mask), changed.settings.size())
            if (input.provisionedGb != null) assertTrue(changed.settings["provisioned_gb"].isNumber)
        }
        val supplied = configured(source, full)
        for ((key, input) in listOf("provisioned_gb" to full.copy(provisionedGb = null),
            "region" to full.copy(region = null), "encryption_key_ref" to full.copy(encryptionKeyRef = null))) {
            val changed = configured(supplied, input)
            assertFalse(changed.settings.has(key))
            assertEquals(supplied.settings.also { it.remove(key) }, changed.settings)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(supplied, PreviewStorageInput())))
        assertEquals(3, supplied.settings.size())
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun suppliedBlankNumberRequiresCorrectionWhileOmissionRemainsValid() {
        val source = selection()
        assertEquals(source, configured(source, PreviewStorageInput()))
        for (text in listOf("", " ", "\t\r\n"))
            invalid(source, PreviewStorageInput(provisionedGb = text), "provisioned_gb", PreviewStorageProblem.REQUIRED)
        assertEquals(BigDecimal("42.1250"), configured(source,
            PreviewStorageInput(provisionedGb = " \t42.1250\r\n ")).settings["provisioned_gb"].decimalValue())
        assertFalse(source.settings.has("provisioned_gb"))
    }

    @Test fun nonnegativeNumbersRetainExactDecimalsAndIntegersBeyondMachinePrecision() {
        val source = selection()
        for (text in listOf("0", "0.00", "-0.000", "0.100000000000000001", "12345678901234567890.01234567890123456789",
            "9007199254740993", "9223372036854775808", "9".repeat(128), "1e3", "1e-100")) {
            val value = configured(source, PreviewStorageInput(provisionedGb = text)).settings["provisioned_gb"]
            assertTrue(value.isNumber)
            assertEquals(BigDecimal(text), value.decimalValue())
        }
        assertEquals(BigInteger("9223372036854775808"), configured(source,
            PreviewStorageInput(provisionedGb = "9223372036854775808")).settings["provisioned_gb"].bigIntegerValue())
        for (text in listOf("-1", "-0.000000000000000001", "-1e-10000"))
            invalid(source, PreviewStorageInput(provisionedGb = text), "provisioned_gb", PreviewStorageProblem.RANGE)
    }

    @Test fun receiverNumberAndScaleBudgetsAreExactWithoutInventingACapacityMaximum() {
        val source = selection()
        for (text in listOf("9".repeat(128), "1e10000", "1e-10000")) {
            val value = configured(source, PreviewStorageInput(provisionedGb = text)).settings["provisioned_gb"]
            assertEquals(BigDecimal(text), value.decimalValue())
        }
        for (text in listOf("9".repeat(129), "1e10001", "1e-10001"))
            invalid(source, PreviewStorageInput(provisionedGb = text), "provisioned_gb", PreviewStorageProblem.TOO_LONG)
        val exactlyBounded = "1" + " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT - 1)
        assertEquals(BigDecimal.ONE, configured(source,
            PreviewStorageInput(provisionedGb = exactlyBounded)).settings["provisioned_gb"].decimalValue())
        invalid(source, PreviewStorageInput(provisionedGb = exactlyBounded + " "),
            "provisioned_gb", PreviewStorageProblem.TOO_LONG)
    }

    @Test fun numericSyntaxRejectsLocaleCoercionNonNumbersAndMalformedUnicode() {
        val source = selection()
        for (text in listOf("NaN", "Infinity", "-Infinity", "1,5", "1,000", "01", "+1", ".5", "1.", "١", "１",
            "null", "true", "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1\u00a0", "\uD800", "1\uDC00")) {
            invalid(source, PreviewStorageInput(provisionedGb = text), "provisioned_gb", PreviewStorageProblem.NUMBER)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun numericFieldCannotInjectAnotherKeyDuplicateOrTrailingDocument() {
        val source = selection()
        for (text in listOf("1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing",
            "1,\"region\":\"injected\"", "1,\"encryption_key_ref\":\"synthetic-password\"")) {
            invalid(source, PreviewStorageInput(provisionedGb = text), "provisioned_gb", PreviewStorageProblem.NUMBER)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun untouchedAdmittedNumericNodesKeepTheirExactTypeScaleAndRepresentation() {
        for (text in listOf("0", "0.00", "123.4500", "1e3", "9007199254740993", "9".repeat(128))) {
            val source = selection("{\"provisioned_gb\":$text}")
            val original = source.settings["provisioned_gb"]
            val changed = configured(source, PreviewStorageSettings.initial(source).copy(region = " edited literally "))
            val actual = changed.settings["provisioned_gb"]
            assertEquals(original, actual)
            assertEquals(original.javaClass, actual.javaClass)
            assertEquals(original.decimalValue(), actual.decimalValue())
            assertEquals(original.toString(), actual.toString())
            assertEquals(" edited literally ", changed.settings["region"].textValue())
            assertFalse(source.settings.has("region"))
        }
    }

    @Test fun changingOnlyCapacityPreservesTheOtherTwoLiteralStrings() {
        val source = selection("""{"provisioned_gb":1.2300,"region":" ","encryption_key_ref":"secret://fixture/Ref\n"}""")
        val initial = PreviewStorageSettings.initial(source)
        val changed = configured(source, initial.copy(provisionedGb = "9007199254740993.1250"))
        assertEquals(BigDecimal("9007199254740993.1250"), changed.settings["provisioned_gb"].decimalValue())
        for (key in listOf("region", "encryption_key_ref")) assertEquals(source.settings[key], changed.settings[key])
        assertEquals(BigDecimal("1.2300"), source.settings["provisioned_gb"].decimalValue())
        assertEquals(initial, PreviewStorageSettings.initial(source))
    }

    @Test fun regionPreservesEmptyWhitespaceUnicodeControlsAndUnknownAsLiteralText() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", " literal region ", "東京/\uD834\uDD1E", "\u0000",
            "unknown", "not_applicable", "Not supplied", "secret://fixture/ref",
            "https://fixture-user:fixture-value@example.invalid/path")) {
            val changed = configured(source, PreviewStorageInput(region = text))
            assertTrue(changed.settings["region"].isTextual)
            assertEquals(text, changed.settings["region"].textValue())
            assertEquals(text, PreviewStorageSettings.initial(changed).region)
            assertEquals(1, changed.settings.size())
        }
    }

    @Test fun jsonLookingRegionCannotSupplyAnotherSettingOrChangeItsType() {
        val source = selection()
        for (text in listOf("null", "true", "1", "[]", "{}", "\",\"provisioned_gb\":-1",
            "{\"encryption_key_ref\":\"synthetic-password\"}", "a\\b\"c\n")) {
            val changed = configured(source, PreviewStorageInput(region = text))
            assertEquals(setOf("region"), changed.settings.fieldNames().asSequence().toSet())
            assertTrue(changed.settings["region"].isTextual)
            assertEquals(text, changed.settings["region"].textValue())
        }
    }

    @Test fun opaqueEncryptionReferencesKeepCasePunctuationAndFrozenFinalLfSemantics() {
        val source = selection()
        for (text in listOf("secret://fixture/Case:Reference_Exact", "secret://fixture/ref.\n")) {
            val changed = configured(source, PreviewStorageInput(encryptionKeyRef = text))
            assertEquals(text, changed.settings["encryption_key_ref"].textValue())
            assertEquals(text, PreviewStorageSettings.initial(changed).encryptionKeyRef)
            assertTrue(PreviewStorageSettings.canRetainEncryptionKeyReference(text))
            assertEquals(1, changed.settings.size())
        }
        assertTrue(PreviewStorageSettings.canRetainEncryptionKeyReference(null))
    }

    @Test fun rawSecretsAndInvalidReferencesCannotSaveOrEnterBoundedTypedRecovery() {
        val source = selection()
        for (text in listOf("", " ", "\t\r\n", "synthetic-password", "secret://", " secret://fixture/ref",
            "secret://fixture/ref ", "secret://fixture/ref\r", "secret://fixture/ref\r\n", "secret://fixture/ref\n\n",
            "secret://fixture/@ref", "secret://fixture/%2f", "SECRET://fixture/ref")) {
            invalid(source, PreviewStorageInput(encryptionKeyRef = text), "encryption_key_ref", PreviewStorageProblem.REFERENCE)
            assertFalse(PreviewStorageSettings.canRetainEncryptionKeyReference(text))
        }
        val bounded = "secret://" + "a".repeat(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS - "secret://".length)
        assertTrue(PreviewStorageSettings.canRetainEncryptionKeyReference(bounded))
        assertFalse(PreviewStorageSettings.canRetainEncryptionKeyReference(bounded + "a"))
        invalid(source, PreviewStorageInput(encryptionKeyRef = bounded), "encryption_key_ref", PreviewStorageProblem.TOO_LONG)
    }

    @Test fun malformedUnicodeAndOverlongTextAreRejectedWithoutChangingTheSelection() {
        val source = selection()
        for (text in listOf("\uD800", "\uDC00", "region\uD800suffix")) {
            invalid(source, PreviewStorageInput(region = text), "region", PreviewStorageProblem.TEXT)
            invalid(source, PreviewStorageInput(encryptionKeyRef = "secret://fixture/" + text),
                "encryption_key_ref", PreviewStorageProblem.REFERENCE)
        }
        val tooLong = "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)
        invalid(source, PreviewStorageInput(region = tooLong), "region", PreviewStorageProblem.TOO_LONG)
        invalid(source, PreviewStorageInput(encryptionKeyRef = tooLong), "encryption_key_ref", PreviewStorageProblem.TOO_LONG)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun completeSettingsBudgetIncludesNumericEnvelopeAndEscapedLiteralText() {
        val source = selection()
        val envelope = "{\"provisioned_gb\":0,\"region\":\"\"}"
        val admitted = "a".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT - envelope.length)
        val changed = configured(source, PreviewStorageInput("0", admitted))
        assertEquals(PreviewSelectionForms.MAX_SETTINGS_TEXT, PreviewSelectionForms.settingsText(changed).length)
        assertEquals(admitted, changed.settings["region"].textValue())
        for (text in listOf(admitted + "a", "\n".repeat(9_000))) {
            assertTrue(text.length <= PreviewSelectionForms.MAX_SETTINGS_TEXT)
            assertSame(PreviewSelectionPreparation.Invalid,
                PreviewStorageSettings.configure(source, PreviewStorageInput("0", text)).preparation)
        }
        val combined = PreviewStorageInput("0.1250", "r".repeat(9_000), "secret://" + "a".repeat(9_000))
        for (single in listOf(combined.copy(region = null, encryptionKeyRef = null),
            combined.copy(provisionedGb = null, encryptionKeyRef = null), combined.copy(provisionedGb = null, region = null)))
            assertTrue(PreviewStorageSettings.configure(source, single).preparation is PreviewSelectionPreparation.Ready)
        assertSame(PreviewSelectionPreparation.Invalid, PreviewStorageSettings.configure(source, combined).preparation)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun frozenReceiverRejectsWrongTypesAndUndeclaredSettingAliases() {
        for (value in listOf("null", "true", "\"1\"", "[]", "{}", "-0.000000000000000001"))
            assertThrows(IllegalArgumentException::class.java) { selection("{\"provisioned_gb\":$value}") }
        for (key in listOf("region", "encryption_key_ref")) for (value in listOf("null", "true", "0", "[]", "{}"))
            assertThrows(IllegalArgumentException::class.java) { selection("{\"$key\":$value}") }
        assertThrows(IllegalArgumentException::class.java) { selection("""{"capacity":1}""") }
        assertThrows(IllegalArgumentException::class.java) { selection("""{"encryption_key_ref":"synthetic-password"}""") }
    }

    @Test fun independentErrorsAndAllIncidentalDescriptionsRemainValueFree() {
        val marker = "synthetic-private-value"
        val source = selection()
        val input = PreviewStorageInput(marker, marker + '\uD800', marker)
        val result = PreviewStorageSettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("provisioned_gb", "region", "encryption_key_ref"), result.issues.map { it.field })
        assertEquals(listOf(PreviewStorageProblem.NUMBER, PreviewStorageProblem.TEXT, PreviewStorageProblem.REFERENCE),
            result.issues.map { it.problem })
        assertFalse(PreviewStorageSettings.canRetainEncryptionKeyReference(input.encryptionKeyRef))
        for (text in listOf(input.toString(), result.toString(), result.issues.toString(), result.preparation.toString()))
            assertFalse(text.contains(marker))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        val validInput = PreviewStorageInput("0.1250", marker, "secret://fixture/$marker")
        val ready = PreviewStorageSettings.configure(source, validInput)
        assertTrue(ready.preparation is PreviewSelectionPreparation.Ready)
        for (text in listOf(validInput.toString(), ready.toString(), ready.preparation.toString(),
            (ready.preparation as PreviewSelectionPreparation.Ready).command.toString())) assertFalse(text.contains(marker))
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurePreservesFullSelectionIdentityFlagsNotesExtensionsLinksAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection("model", "sel_model")
        val link = SelectionFixtures.link("link.fixture", model.id, source.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "unrelated objective region"),
            components = DraftSelections(listOf(source, model), listOf(link)))
        val prepared = command(source, PreviewStorageInput("9007199254740993.1250", " ", "secret://fixture/Ref:Exact"))
        assertTrue(prepared is SelectionCommand.Configure)
        val configure = prepared as SelectionCommand.Configure
        assertEquals(source.id, configure.selectionId)
        assertNull(configure.role)
        configure.settings.removeAll()
        val changed = (DraftSelectionCommands.apply(before, prepared) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(model, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(3, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun unsupportedKindsCannotAcquireStoragePreparationOrMutateTheirPayload() {
        val unsupported = SelectionFixtures.selection("model")
        val before = unsupported.toJson()
        val prepared = PreviewStorageSettings.configure(unsupported, PreviewStorageInput("0", "", "secret://fixture/ref"))
        assertSame(PreviewSelectionPreparation.Invalid, prepared.preparation)
        assertTrue(prepared.issues.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PreviewStorageSettings.initial(unsupported) }
        assertEquals(before, unsupported.toJson())
    }

    @Test fun preparingAndAbandoningValidInputCannotWriteManagedRevisionOrFileBytes() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, content(), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val originalFiles = files()
        val before = raw()
        val source = storage(original)
        val prepared = PreviewStorageSettings.configure(source,
            PreviewStorageSettings.initial(source).copy(provisionedGb = "9007199254740993.1250"))
        assertTrue(prepared.preparation is PreviewSelectionPreparation.Ready)
        // No applySelection call: closing this prepared form grants no save authority by itself.
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(original.version.number, reopened.version.number)
        assertEquals(original.version.hash, reopened.version.hash)
        assertEquals(original.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        assertArrayEquals(before, raw())
        assertEquals(originalFiles, files())
    }

    @Test fun rejectedInputsCannotDispatchOrReplaceAcknowledgedManagedBytes() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, content()) as DraftSave.Saved).draft
        val originalFiles = files()
        val before = raw()
        val source = storage(original)
        val initial = PreviewStorageSettings.initial(source)
        for (input in listOf(initial.copy(provisionedGb = "-0.000000000000000001"), initial.copy(provisionedGb = "9".repeat(129)),
            initial.copy(provisionedGb = "1,\"region\":\"injected\""), initial.copy(region = "\uD800"),
            initial.copy(encryptionKeyRef = "synthetic-password"), initial.copy(region = "x".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)))) {
            val prepared = PreviewStorageSettings.configure(source, input).preparation
            val dispatched = if (prepared is PreviewSelectionPreparation.Ready) {
                store.applySelection(DraftSelectionIntent(original.version, prepared.command))
                true
            } else false
            assertFalse(dispatched)
            val reopened = (store.load() as DraftLoad.Available).draft
            assertEquals(original.version.number, reopened.version.number)
            assertEquals(original.version.hash, reopened.version.hash)
            assertEquals(original.content, reopened.content)
            assertArrayEquals(before, raw())
            assertEquals(originalFiles, files())
        }
    }

    @Test fun cancelledManagedSaveLeavesTheAcknowledgedRevisionAndEveryFileUnchanged() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, content()) as DraftSave.Saved).draft
        val source = storage(original)
        val intent = DraftSelectionIntent(original.version,
            command(source, PreviewStorageSettings.initial(source).copy(provisionedGb = "0.1250")))
        val originalFiles = files()
        val before = raw()
        Thread.currentThread().interrupt()
        try {
            assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.CANCELLED), store.applySelection(intent))
        } finally { Thread.interrupted() }
        val reopened = (store.load() as DraftLoad.Available).draft
        assertEquals(original.version.number, reopened.version.number)
        assertEquals(original.version.hash, reopened.version.hash)
        assertEquals(original.content, reopened.content)
        assertArrayEquals(before, raw())
        assertEquals(originalFiles, files())
    }

    @Test fun managedNoopZeroAndRemovalKeepExplicitSupplyDistinctFromAbsence() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, content("{}")) as DraftSave.Saved).draft
        val originalFiles = files()
        val originalBytes = raw()
        val source = storage(original)
        val noop = store.applySelection(DraftSelectionIntent(original.version,
            command(source, PreviewStorageSettings.initial(source)))) as DraftSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(original.version.hash, noop.draft.version.hash)
        assertEquals(original.version.number, noop.draft.version.number)
        assertArrayEquals(originalBytes, raw())
        assertEquals(originalFiles, files())
        val supplied = (store.applySelection(DraftSelectionIntent(original.version,
            command(source, PreviewStorageInput(provisionedGb = "0")))) as DraftSelectionSave.Saved).draft
        assertEquals(original.version.number + 1, supplied.version.number)
        assertTrue(storage(supplied).settings.has("provisioned_gb"))
        assertTrue(storage(supplied).settings["provisioned_gb"].isNumber)
        assertEquals(BigDecimal.ZERO, storage(supplied).settings["provisioned_gb"].decimalValue())
        val suppliedFiles = files()
        val secondNoop = store.applySelection(DraftSelectionIntent(supplied.version,
            command(storage(supplied), PreviewStorageSettings.initial(storage(supplied))))) as DraftSelectionSave.Saved
        assertNull(secondNoop.change)
        assertEquals(supplied.version.hash, secondNoop.draft.version.hash)
        assertEquals(suppliedFiles, files())
        val removed = (store.applySelection(DraftSelectionIntent(supplied.version,
            command(storage(supplied), PreviewStorageInput()))) as DraftSelectionSave.Saved).draft
        assertEquals(supplied.version.number + 1, removed.version.number)
        assertFalse(storage(removed).settings.has("provisioned_gb"))
        assertEquals(original.content, removed.content)
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun originalVersionCasCannotOverwriteAnotherWritersAcceptedStorageChange() {
        val store = LocalDraftStore(root)
        val other = LocalDraftStore(root)
        val original = (store.save(null, content()) as DraftSave.Saved).draft
        val source = storage(original)
        val initial = PreviewStorageSettings.initial(source)
        val stale = DraftSelectionIntent(original.version, command(source, initial.copy(provisionedGb = "9007199254740993.1250")))
        val winner = (other.applySelection(DraftSelectionIntent(original.version,
            command(source, initial.copy(region = "accepted other writer")))) as DraftSelectionSave.Saved).draft
        val winningFiles = files()
        val winningBytes = raw()
        assertSame(DraftSelectionSave.Conflict, store.applySelection(stale))
        val reopened = (store.load() as DraftLoad.Available).draft
        assertEquals(winner.version.number, reopened.version.number)
        assertEquals(winner.version.hash, reopened.version.hash)
        assertEquals(winner.content, reopened.content)
        assertEquals(initial.provisionedGb, PreviewStorageSettings.initial(storage(reopened)).provisionedGb)
        assertEquals("accepted other writer", storage(reopened).settings["region"].textValue())
        assertArrayEquals(winningBytes, raw())
        assertEquals(winningFiles, files())
    }

    @Test fun removedSelectionCannotBeResurrectedByAnEarlierPreparedStorageCommand() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, content()) as DraftSave.Saved).draft
        val source = storage(original)
        val prepared = command(source, PreviewStorageSettings.initial(source).copy(provisionedGb = "0.1250"))
        val removed = (store.applySelection(DraftSelectionIntent(original.version,
            SelectionCommand.Remove(source.id))) as DraftSelectionSave.Saved).draft
        val removedFiles = files()
        val removedBytes = raw()
        assertSame(DraftSelectionSave.Conflict, store.applySelection(DraftSelectionIntent(original.version, prepared)))
        assertEquals(DraftSelectionSave.Rejected(SelectionRejection.MISSING_SELECTION),
            store.applySelection(DraftSelectionIntent(removed.version, prepared)))
        val reopened = (store.load() as DraftLoad.Available).draft
        assertEquals(removed.version.hash, reopened.version.hash)
        assertEquals(removed.content, reopened.content)
        assertFalse(reopened.content.components.selections.any { it.id == source.id })
        assertArrayEquals(removedBytes, raw())
        assertEquals(removedFiles, files())
    }

    @Test fun exactNumbersAndBothStringsReopenAndExportThroughAnExplicitValidatedBuildCandidate() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, content(), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = storage(original)
        val originalBytes = raw()
        val input = PreviewStorageInput("9007199254740993.125000000000000001", " \t東京/\uD834\uDD1E\n",
            "secret://fixture/Case:Reference_Exact\n")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        assertEquals(input, PreviewStorageSettings.initial(storage(reopened)))
        val savedBytes = raw()
        val savedFiles = files()
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val exported = created.candidate.bytes()
        val originalExport = exported.copyOf()
        assertEquals(ProjectionFixtures.hash(exported), created.candidate.hash)
        val build = (BoundedJsonReader().read(exported.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(saved.content.components.selections.size, build["selections"].size())
        val projected = build["selections"].single { it["kind"].textValue() == "storage" }
        assertEquals(storage(saved).toJson(), projected)
        assertEquals(BigDecimal(input.provisionedGb!!), projected["settings"]["provisioned_gb"].decimalValue())
        assertEquals(input.region, projected["settings"]["region"].textValue())
        assertEquals(input.encryptionKeyRef, projected["settings"]["encryption_key_ref"].textValue())
        assertArrayEquals(savedBytes, created.candidate.sourceBytes())
        exported.fill(0)
        assertArrayEquals(originalExport, created.candidate.bytes())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertEquals(savedFiles, files())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }
}
