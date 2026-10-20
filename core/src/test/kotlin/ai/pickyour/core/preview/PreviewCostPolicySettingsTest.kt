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
import java.nio.file.Path

class PreviewCostPolicySettingsTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()

    private fun selection(settings: String = "{}"): LocalSelection {
        val node = SelectionFixtures.node("cost_policy", "sel_cost", "primary", false)
        node.set<JsonNode>("settings",
            (BoundedJsonReader().read(settings.toByteArray().inputStream()) as JsonInputResult.Parsed).value)
        return LocalSelection.fromJson(node)
    }
    private fun command(source: LocalSelection, input: PreviewCostPolicyInput) =
        (PreviewCostPolicySettings.configure(source, input).preparation as PreviewSelectionPreparation.Ready).command
    private fun configured(source: LocalSelection, input: PreviewCostPolicyInput): LocalSelection =
        (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(source))),
            command(source, input)) as SelectionEdit.Ready).content.components.selections.single()
    private fun invalid(source: LocalSelection, input: PreviewCostPolicyInput, key: String,
        problem: PreviewCostPolicyProblem? = null) {
        val result = PreviewCostPolicySettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf(key), result.issues.map { it.field })
        if (problem != null) assertEquals(problem, result.issues.single().problem)
    }
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        .active() as CacheRead.Available).bytes

    @Test fun fourOptionalFieldsUseExactFrozenMetadataWithoutCurrencyOrDefaults() {
        val fields = PreviewCostPolicySettings.fields
        assertEquals(listOf("monthly_budget_amount", "per_request_budget_amount", "enforce_hard_cap", "notify_at_pct"),
            fields.map { it.key })
        assertTrue(fields.none { it.required })
        assertEquals(listOf(PreviewSettingType.STRING, PreviewSettingType.STRING,
            PreviewSettingType.BOOLEAN, PreviewSettingType.NUMBER), fields.map { it.type })
        assertEquals("^(0|[1-9][0-9]*)(\\.[0-9]{1,12})?$", fields[0].pattern)
        assertEquals(fields[0].pattern, fields[1].pattern)
        assertEquals(BigDecimal.ZERO, fields[3].minimum)
        assertEquals(BigDecimal("100"), fields[3].maximum)
        assertNull(fields[3].exclusiveMinimum)
        assertEquals(emptyList<String>(), fields[2].enumValues)
        assertThrows(UnsupportedOperationException::class.java) { (fields as MutableList).clear() }
    }

    @Test fun omissionFalseTrueAndExplicitZeroRemainIndependentSuppliedStates() {
        val empty = selection()
        assertEquals(PreviewCostPolicyInput(), PreviewCostPolicySettings.initial(empty))
        assertEquals(empty, configured(empty, PreviewCostPolicyInput()))
        val all = configured(empty, PreviewCostPolicyInput("0", "0.000", false, "0"))
        assertEquals(4, all.settings.size())
        assertTrue(all.settings["enforce_hard_cap"].isBoolean)
        assertEquals(false, PreviewCostPolicySettings.initial(all).enforceHardCap)
        val changed = configured(all, PreviewCostPolicySettings.initial(all).copy(enforceHardCap = true))
        assertTrue(changed.settings["enforce_hard_cap"].booleanValue())
        val removed = configured(all, PreviewCostPolicySettings.initial(all).copy(enforceHardCap = null))
        assertFalse(removed.settings.has("enforce_hard_cap"))
        assertEquals(all.settings.also { it.remove("enforce_hard_cap") }, removed.settings)
        assertEquals("{}", PreviewSelectionForms.settingsText(configured(all, PreviewCostPolicyInput())))
        assertEquals(4, all.settings.size())
    }

    @Test fun decimalStringsRetainScaleWideDigitsAndTheirStringType() {
        val source = selection()
        for (value in listOf("0", "0.000000000001", "100.000000000000", "9007199254740993.123456789012", "9".repeat(200))) {
            val changed = configured(source, PreviewCostPolicyInput(value, value))
            for (key in listOf("monthly_budget_amount", "per_request_budget_amount")) {
                assertTrue(changed.settings[key].isTextual)
                assertEquals(value, changed.settings[key].textValue())
            }
            assertEquals(value, PreviewCostPolicySettings.initial(changed).monthlyBudgetAmount)
            assertEquals(value, PreviewCostPolicySettings.initial(changed).perRequestBudgetAmount)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun frozenTerminalLfIsRetainedWithoutTrimmingOrBroaderWhitespaceAdmission() {
        val source = selection()
        for (value in listOf("0\n", "12.3400\n")) {
            val changed = configured(source, PreviewCostPolicyInput(value, value))
            assertEquals(value, changed.settings["monthly_budget_amount"].textValue())
            assertEquals(value, changed.settings["per_request_budget_amount"].textValue())
            val edited = configured(changed, PreviewCostPolicySettings.initial(changed).copy(enforceHardCap = false))
            assertEquals(changed.settings["monthly_budget_amount"], edited.settings["monthly_budget_amount"])
            assertEquals(changed.settings["per_request_budget_amount"], edited.settings["per_request_budget_amount"])
        }
        for (value in listOf(" 1", "1 ", "1\t", "1\r", "1\r\n", "1\n\n", "1\u00a0")) {
            invalid(source, PreviewCostPolicyInput(monthlyBudgetAmount = value), "monthly_budget_amount", PreviewCostPolicyProblem.DECIMAL_STRING)
            invalid(source, PreviewCostPolicyInput(perRequestBudgetAmount = value), "per_request_budget_amount", PreviewCostPolicyProblem.DECIMAL_STRING)
        }
    }

    @Test fun malformedLocaleNegativeAndNondecimalBudgetInputsCannotPrepareACommand() {
        val source = selection()
        for (value in listOf("unknown", "not_applicable", "null", "false", "1,5", "1,000", "01", "-0", "-1", "+1",
            "1e2", ".5", "1.", "0.1234567890123", "١", "１", "NaN", "Infinity", "\"1\"", "{}", "[]",
            "synthetic-password", "secret://fixture/reference", "https://synthetic:private@host.invalid", "1\uD800")) {
            invalid(source, PreviewCostPolicyInput(monthlyBudgetAmount = value), "monthly_budget_amount", PreviewCostPolicyProblem.DECIMAL_STRING)
            invalid(source, PreviewCostPolicyInput(perRequestBudgetAmount = value), "per_request_budget_amount", PreviewCostPolicyProblem.DECIMAL_STRING)
        }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun suppliedEmptyTextRequiresCorrectionOrExplicitOmission() {
        val source = selection()
        for (value in listOf("", " ", "\t\r\n")) {
            invalid(source, PreviewCostPolicyInput(monthlyBudgetAmount = value), "monthly_budget_amount", PreviewCostPolicyProblem.REQUIRED)
            invalid(source, PreviewCostPolicyInput(perRequestBudgetAmount = value), "per_request_budget_amount", PreviewCostPolicyProblem.REQUIRED)
            invalid(source, PreviewCostPolicyInput(notifyAtPct = value), "notify_at_pct", PreviewCostPolicyProblem.REQUIRED)
        }
        assertEquals(source, configured(source, PreviewCostPolicyInput()))
    }

    @Test fun notificationUsesExactInclusivePercentageBoundsWithoutFloatingPointRounding() {
        val source = selection()
        for (value in listOf("0", "100", "0.000000000000000001", "99.999999999999999999", "1e-100", "1e2")) {
            val changed = configured(source, PreviewCostPolicyInput(notifyAtPct = value))
            assertTrue(changed.settings["notify_at_pct"].isNumber)
            assertEquals(BigDecimal(value), changed.settings["notify_at_pct"].decimalValue())
        }
        for (value in listOf("-0.000000000000000001", "100.000000000000000001", "101"))
            invalid(source, PreviewCostPolicyInput(notifyAtPct = value), "notify_at_pct", PreviewCostPolicyProblem.RANGE)
    }

    @Test fun notificationRejectsLocaleSyntaxInjectionNonNumbersAndParserLimits() {
        val source = selection()
        for (value in listOf("NaN", "Infinity", "-Infinity", "1,5", "01", "+1", ".5", "١", "１", "null", "false",
            "\"1\"", "{}", "[]", "unknown", "not_applicable", "1e", "1e10001", "1\u00a0", "\uD800",
            "1,\"other\":2", "1,\"value\":2", "1}{}", "1} trailing", "9".repeat(129)))
            invalid(source, PreviewCostPolicyInput(notifyAtPct = value), "notify_at_pct")
        val tooLong = " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)
        invalid(source, PreviewCostPolicyInput(notifyAtPct = tooLong), "notify_at_pct", PreviewCostPolicyProblem.TOO_LONG)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun untouchedAdmittedNotificationNodeKeepsScaleAndRuntimeType() {
        for (value in listOf("0.00", "100.000", "1e1")) {
            val source = selection("""{"notify_at_pct":$value,"monthly_budget_amount":"1.00"}""")
            val changed = configured(source, PreviewCostPolicySettings.initial(source).copy(enforceHardCap = false))
            assertEquals(source.settings["notify_at_pct"], changed.settings["notify_at_pct"])
            assertEquals(source.settings["notify_at_pct"].javaClass, changed.settings["notify_at_pct"].javaClass)
            assertEquals(source.settings["notify_at_pct"].decimalValue(), changed.settings["notify_at_pct"].decimalValue())
            assertEquals("1.00", changed.settings["monthly_budget_amount"].textValue())
        }
    }

    @Test fun fieldAndCombinedTextBudgetsDoNotAllowAPartialSelection() {
        val source = selection()
        val wide = "9".repeat(9_000)
        assertTrue(PreviewCostPolicySettings.configure(source, PreviewCostPolicyInput(monthlyBudgetAmount = wide))
            .preparation is PreviewSelectionPreparation.Ready)
        assertTrue(PreviewCostPolicySettings.configure(source, PreviewCostPolicyInput(perRequestBudgetAmount = wide))
            .preparation is PreviewSelectionPreparation.Ready)
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewCostPolicySettings.configure(source, PreviewCostPolicyInput(wide, wide)).preparation)
        val tooLong = "9".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)
        invalid(source, PreviewCostPolicyInput(monthlyBudgetAmount = tooLong), "monthly_budget_amount", PreviewCostPolicyProblem.TOO_LONG)
        invalid(source, PreviewCostPolicyInput(perRequestBudgetAmount = tooLong), "per_request_budget_amount", PreviewCostPolicyProblem.TOO_LONG)
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun independentDecimalProbesAllowSiblingRemovalOrShrinkingWithoutRelaxingWholeSelectionLimits() {
        val limits = JsonInputLimits()
        val wide = "9".repeat(8_000)
        for (edited in listOf(PreviewCostPolicySettings.MONTHLY, PreviewCostPolicySettings.PER_REQUEST)) {
            val sibling = if (edited == PreviewCostPolicySettings.MONTHLY)
                PreviewCostPolicySettings.PER_REQUEST else PreviewCostPolicySettings.MONTHLY
            for (remove in listOf(true, false)) {
                val node = selection().toJson()
                node.set<JsonNode>("settings", SelectionFixtures.mapper.createObjectNode().put(edited, "1").put(sibling, wide))
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
                assertEquals(target, node.toString().toByteArray(Charsets.UTF_8).size)
                val source = LocalSelection.fromJson(node)
                val original = source.toJson()
                val settings = source.settings.put(edited, wide)
                if (remove) settings.remove(sibling) else settings.put(sibling, "0")
                val expected = source.toJson().also { it.set<JsonNode>("settings", settings) }
                assertEquals(expected, LocalSelection.fromJson(expected).toJson())
                assertTrue(PreviewSelectionForms.configure(source, settings.toString()) is PreviewSelectionPreparation.Ready)
                val input = if (edited == PreviewCostPolicySettings.MONTHLY)
                    PreviewCostPolicyInput(wide, if (remove) null else "0")
                else PreviewCostPolicyInput(if (remove) null else "0", wide)
                val changed = configured(source, input)
                assertEquals(expected, changed.toJson())
                assertEquals(original["extensions"], changed.toJson()["extensions"])
                assertEquals(wide, changed.settings[edited].textValue())
                if (remove) assertFalse(changed.settings.has(sibling))
                else assertEquals("0", changed.settings[sibling].textValue())
                assertEquals(original, source.toJson())

                // Valid fields cannot bypass the final whole-Selection payload limit.
                val oversized = source.settings.put(edited, wide)
                assertTrue(oversized.toString().length < PreviewSelectionForms.MAX_SETTINGS_TEXT)
                assertSame(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(source, oversized.toString()))
                val rejected = PreviewCostPolicySettings.configure(source, PreviewCostPolicyInput(wide, wide))
                assertSame(PreviewSelectionPreparation.Invalid, rejected.preparation)
                assertTrue(rejected.issues.isEmpty())
                assertEquals(original, source.toJson())
            }
        }
    }

    @Test fun independentErrorsRemainValueFreeAndCannotApplyValidSiblingValues() {
        val source = selection()
        val raw = "synthetic-private-value"
        val input = PreviewCostPolicyInput(raw, raw, false, raw)
        val result = PreviewCostPolicySettings.configure(source, input)
        assertSame(PreviewSelectionPreparation.Invalid, result.preparation)
        assertEquals(listOf("monthly_budget_amount", "per_request_budget_amount", "notify_at_pct"), result.issues.map { it.field })
        assertEquals(listOf(PreviewCostPolicyProblem.DECIMAL_STRING, PreviewCostPolicyProblem.DECIMAL_STRING,
            PreviewCostPolicyProblem.NUMBER), result.issues.map { it.problem })
        for (text in listOf(input.toString(), result.toString(), result.issues.toString())) assertFalse(text.contains(raw))
        assertThrows(UnsupportedOperationException::class.java) { (result.issues as MutableList).clear() }
        assertEquals("{}", PreviewSelectionForms.settingsText(source))
    }

    @Test fun configurationPreservesIdentityFlagsNotesExtensionsLinksAndUnrelatedContent() {
        val node = selection().toJson().put("notes", "literal unknown")
        node.putObject("extensions").putObject("x_private").put("kept", "not_applicable")
        val source = LocalSelection.fromJson(node)
        val model = SelectionFixtures.selection()
        val link = SelectionFixtures.link("link.fixture", model.id, source.id, false)
        val before = DraftContent("objective", "context", workload = DraftWorkload(region = "entered"),
            components = DraftSelections(listOf(source, model), listOf(link)))
        val input = PreviewCostPolicyInput("123.4500", "0.000000000001", false, "99.999999999999999999")
        val changed = (DraftSelectionCommands.apply(before, command(source, input)) as SelectionEdit.Ready).content
        val selected = changed.components.selections.first()
        assertEquals(source.toJson().also { it.set<JsonNode>("settings", selected.settings) }, selected.toJson())
        assertEquals(before, changed.withComponents(before.components))
        assertEquals(before.components.links, changed.components.links)
        assertEquals(model, changed.components.selections.last())
        selected.settings.removeAll()
        assertEquals(4, changed.components.selections.first().settings.size())
        assertEquals(0, source.settings.size())
    }

    @Test fun currentNoopStaleAndDeletedManagedAuthorityRemainDistinct() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val adapter = library.open(created.id)
        val source = selection()
        val before = (adapter.applySelection(created.record,
            SelectionCommand.Add(source, SelectionFixtures.choice(catalog, source)), catalog) as PreviewSelectionSave.Saved).record
        val noop = adapter.applySelection(before, command(source, PreviewCostPolicyInput())) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(before.baselineIdentity, noop.record.baselineIdentity)
        val saved = (adapter.applySelection(before, command(source, PreviewCostPolicyInput(enforceHardCap = false))) as PreviewSelectionSave.Saved).record
        assertEquals(before.revision + 1, saved.revision)
        assertSame(PreviewSelectionSave.Conflict,
            adapter.applySelection(before, command(source, PreviewCostPolicyInput(enforceHardCap = true))))
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(created.id, saved))
        assertTrue(adapter.applySelection(saved, command(source, PreviewCostPolicyInput(notifyAtPct = "50"))) is PreviewSelectionSave.Failed)
    }

    @Test fun savedValuesAndExplicitBuildProjectionRetainTypesHistoryAndPrivateMetadata() {
        val store = LocalDraftStore(root)
        val original = (store.save(null, ProjectionFixtures.content(allKinds = true), mapOf("private.keep" to "literal")) as DraftSave.Saved).draft
        val source = original.content.components.selections.single { it.kind == "cost_policy" }
        val originalBytes = raw()
        val input = PreviewCostPolicyInput("9007199254740993.123456789012", "0.000000000001", false, "99.999999999999999999")
        val saved = (store.applySelection(DraftSelectionIntent(original.version, command(source, input))) as DraftSelectionSave.Saved).draft
        val reopened = (LocalDraftStore(root).load() as DraftLoad.Available).draft
        assertEquals(saved.content, reopened.content)
        assertEquals(original.metadata, reopened.metadata)
        val savedBytes = raw()
        val invalid = PreviewCostPolicySettings.configure(reopened.content.components.selections.single { it.kind == "cost_policy" },
            input.copy(monthlyBudgetAmount = "1,5"))
        assertSame(PreviewSelectionPreparation.Invalid, invalid.preparation)
        assertArrayEquals(savedBytes, raw())
        val review = store.prepareSnapshotCandidate(reopened.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val created = store.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created
        val build = (BoundedJsonReader().read(created.candidate.bytes().inputStream()) as JsonInputResult.Parsed).value
        val projected = build["selections"].single { it["kind"].textValue() == "cost_policy" }
        assertEquals(saved.content.components.selections.single { it.kind == "cost_policy" }.toJson(), projected)
        assertTrue(projected["settings"]["monthly_budget_amount"].isTextual)
        assertEquals(input.monthlyBudgetAmount, projected["settings"]["monthly_budget_amount"].textValue())
        assertTrue(projected["settings"]["per_request_budget_amount"].isTextual)
        assertEquals(input.perRequestBudgetAmount, projected["settings"]["per_request_budget_amount"].textValue())
        assertTrue(projected["settings"]["enforce_hard_cap"].isBoolean)
        assertFalse(projected["settings"]["enforce_hard_cap"].booleanValue())
        assertEquals(BigDecimal(input.notifyAtPct), projected["settings"]["notify_at_pct"].decimalValue())
        val after = (store.load() as DraftLoad.Available).draft
        assertEquals(saved.version.number, after.version.number)
        assertEquals(saved.version.hash, after.version.hash)
        assertArrayEquals(savedBytes, raw())
        assertArrayEquals(originalBytes, (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
            .pinned(original.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun anotherSelectionKindCannotAcquireCostPolicyConfiguration() {
        val model = SelectionFixtures.selection()
        assertSame(PreviewSelectionPreparation.Invalid,
            PreviewCostPolicySettings.configure(model, PreviewCostPolicyInput(enforceHardCap = true)).preparation)
        assertThrows(IllegalArgumentException::class.java) { PreviewCostPolicySettings.initial(model) }
    }
}
