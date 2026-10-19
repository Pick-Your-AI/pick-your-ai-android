package ai.pickyour.core.drafts

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DraftSelectionRoleTest {
    private fun ready(content: DraftContent, command: SelectionCommand) =
        DraftSelectionCommands.apply(content, command) as SelectionEdit.Ready

    @Test fun roleConfigurationPreservesEveryOtherFieldAcrossAllEightSupportedKinds() {
        val kinds = listOf("model_provider", "model", "context_policy", "hosting_platform", "accelerator",
            "compute_profile", "inference_engine", "storage")
        val values = kinds.map { kind ->
            LocalSelection.fromJson(SelectionFixtures.node(kind, "chosen_$kind", enabled = false).put("notes", "").also {
                it.putObject("extensions").putObject("x_retained").putArray("values").addNull().add(false).add("opaque")
            })
        }
        val links = listOf(SelectionFixtures.link("link.z", values[0].id, values[1].id, false),
            SelectionFixtures.link("link.a", values[1].id, values[0].id),
            SelectionFixtures.link("link.self", values[0].id, values[0].id))
        val before = DraftContent(objective = "retained", primaryModel = "opaque", components = DraftSelections(values, links))
        for (selection in values) {
            val result = ready(before, SelectionCommand.Configure(selection.id, selection.settings, "standby_2"))
            val expected = LocalSelection.fromJson(selection.toJson().put("role", "standby_2"))
            assertEquals(before.withComponents(DraftSelections(values.map { if (it.id == selection.id) expected else it }, links)), result.content)
            assertEquals(SelectionAction.CONFIGURE_ROLE, result.change.action)
            assertEquals(selection.id, result.change.selectionId)
            assertTrue(result.change.removedLinkIds.isEmpty())
            assertEquals(links.filter { it.fromSelectionId == selection.id || it.toSelectionId == selection.id }.map { it.id }.sorted(),
                result.change.affectedLinkIds)
        }
        assertEquals(values, before.components.selections)
        assertTrue(values.all { it.role == "primary" && !it.enabled })
    }

    @Test fun roleAndExactSettingsAreOneImmutableCommandWithoutCatalogOrActivationChanges() {
        val selection = LocalSelection.fromJson(SelectionFixtures.node(role = "secondary", enabled = false)
            .put("notes", "retained").also { it.putObject("extensions").put("x_flag", false) })
        val settings = SelectionFixtures.mapper.createObjectNode().put("temperature", BigDecimal("0.100000000000000001"))
            .put("top_p", BigDecimal("0.2500")).put("credential_ref", "secret://opaque/reference")
        val expectedSettings = settings.deepCopy()
        val command = SelectionCommand.Configure(selection.id, settings, "candidate")
        settings.removeAll()
        command.settings.removeAll()
        val before = DraftContent(components = DraftSelections(listOf(selection)))
        val changed = ready(before, command).content.components.selections.single()
        assertEquals(selection.toJson().put("role", "candidate").also { it.set<JsonNode>("settings", expectedSettings) }, changed.toJson())
        assertEquals("0.100000000000000001", changed.settings["temperature"].toString())
        assertEquals(BigDecimal("0.2500"), changed.settings["top_p"].decimalValue())
        assertEquals("secondary", selection.role)
        assertFalse(changed.enabled)
    }

    @Test fun omittedAndUnchangedRolesKeepTheExistingSettingsOnlyAction() {
        val selection = SelectionFixtures.selection(role = "secondary")
        val before = DraftContent(components = DraftSelections(listOf(selection)))
        val settings = selection.settings.put("temperature", 0)
        for (role in listOf(null, selection.role)) {
            val result = ready(before, SelectionCommand.Configure(selection.id, settings, role))
            assertEquals(SelectionAction.CONFIGURE, result.change.action)
            val expected = LocalSelection.fromJson(selection.toJson().also { it.set<JsonNode>("settings", settings) })
            assertEquals(expected, result.content.components.selections.single())
        }
    }

    @Test fun collidingRolesRejectTheWholeEditIncludingDisabledAlternatives() {
        for (kind in listOf("model", "accelerator", "context_policy")) {
            val first = SelectionFixtures.selection(kind, "selection_first")
            val second = SelectionFixtures.selection(kind, "selection_second", "secondary", false)
            val before = DraftContent(components = DraftSelections(listOf(first, second)))
            for ((selection, role) in listOf(first to second.role, second to first.role)) {
                val result = DraftSelectionCommands.apply(before, SelectionCommand.Configure(selection.id, selection.settings, role))
                assertEquals(SelectionEdit.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE), result)
                assertEquals(listOf(first, second), before.components.selections)
            }
        }
    }

    @Test fun changingThePrimaryRoleNeverPromotesOrEnablesOtherSelections() {
        val first = SelectionFixtures.selection("context_policy", "context_primary")
        val second = SelectionFixtures.selection("context_policy", "context_secondary", "secondary", false)
        val link = SelectionFixtures.link("link.context", second.id, first.id, true)
        val before = DraftContent(components = DraftSelections(listOf(first, second), listOf(link)))
        val result = ready(before, SelectionCommand.Configure(first.id, first.settings, "custom_context"))
        assertEquals(second, result.content.components.selections[1])
        assertTrue(result.content.components.selections[0].enabled)
        assertFalse(result.content.components.selections.any { it.role == "primary" })
        assertEquals(listOf(link), result.content.components.links)
        assertEquals(SelectionEdit.Rejected(SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED),
            DraftSelectionCommands.apply(result.content, SelectionCommand.SetEnabled(second.id, true)))
    }

    @Test fun invalidRoleOrSettingsMissingSelectionAndUnsupportedKindCannotPartiallyEdit() {
        val selection = SelectionFixtures.selection()
        val before = DraftContent(components = DraftSelections(listOf(selection)))
        for (role in listOf("", " primary", "primary ", "Primary", "1primary", "a".repeat(51), "primary\r", "primary\n\n", "рrimary")) {
            assertEquals(SelectionEdit.Rejected(SelectionRejection.INVALID_SETTINGS),
                DraftSelectionCommands.apply(before, SelectionCommand.Configure(selection.id, selection.settings, role)), role)
        }
        assertEquals(SelectionEdit.Rejected(SelectionRejection.INVALID_SETTINGS),
            DraftSelectionCommands.apply(before, SelectionCommand.Configure(selection.id, selection.settings.put("temperature", 9), "candidate")))
        assertEquals(SelectionEdit.Rejected(SelectionRejection.MISSING_SELECTION),
            DraftSelectionCommands.apply(before, SelectionCommand.Configure("missing_selection", selection.settings, "candidate")))
        val retained = SelectionFixtures.selection("deployment_target", "selection_deployment")
        assertThrows(IllegalArgumentException::class.java) {
            LocalSelection.fromJson(retained.toJson().put("kind", "future_deployment_target"))
        }
        val changed = ready(DraftContent(components = DraftSelections(listOf(retained))),
            SelectionCommand.Configure(retained.id, retained.settings, "candidate"))
        assertEquals("candidate", changed.content.components.selections.single().role)
        assertEquals(retained.settings, changed.content.components.selections.single().settings)
        assertEquals(listOf(selection), before.components.selections)
    }

    @Test fun exactFrozenRolePatternRetainsCustomVocabularyAndFinalLineFeed() {
        val selection = SelectionFixtures.selection()
        val before = DraftContent(components = DraftSelections(listOf(selection)))
        for (role in listOf("user_selected_role-2", "a".repeat(50), "primary\n")) {
            val result = ready(before, SelectionCommand.Configure(selection.id, selection.settings, role))
            assertEquals(role, result.content.components.selections.single().role)
            assertEquals(SelectionAction.CONFIGURE_ROLE, result.change.action)
        }
    }
}
