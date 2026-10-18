package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreviewSelectionRoleFormTest {
    private val selection = SelectionFixtures.selection(role = "secondary", enabled = false)
    private val before = DraftContent(components = DraftSelections(listOf(selection)))

    @Test fun explicitRoleAndExactSettingsArePreparedWithoutMutatingTheSelection() {
        val result = PreviewSelectionForms.configure(selection, "{\"temperature\":0.100000000000000001}", "candidate")
            as PreviewSelectionPreparation.Ready
        val changed = (DraftSelectionCommands.apply(before, result.command) as SelectionEdit.Ready).content.components.selections.single()
        assertEquals("candidate", changed.role)
        assertEquals("0.100000000000000001", changed.settings["temperature"].toString())
        assertFalse(changed.enabled)
        assertEquals(selection.id, changed.id)
        assertEquals("secondary", selection.role)
    }

    @Test fun settingsOnlyCallerRetainsTheExistingRoleAndDoesNotSelectANewAction() {
        val result = PreviewSelectionForms.configure(selection, "{}") as PreviewSelectionPreparation.Ready
        val changed = DraftSelectionCommands.apply(before, result.command) as SelectionEdit.Ready
        assertEquals(selection.role, changed.content.components.selections.single().role)
        assertEquals(SelectionAction.CONFIGURE, changed.change.action)
        assertNull((result.command as SelectionCommand.Configure).role)
    }

    @Test fun invalidUntrimmedRoleOrSettingsRemainInvalidTogether() {
        for (role in listOf("", " candidate", "candidate ", "Candidate", "a".repeat(81), "candidate\n\n", "candidate\uD800")) {
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(selection, "{}", role))
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configureRole(selection, role))
        }
        for (settings in listOf("", "[]", "{} {}", "{\"temperature\":1,\"temperature\":0}",
            "{\"temperature\":9}", " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1))) {
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(selection, settings, "candidate"))
        }
        val accelerator = SelectionFixtures.selection("accelerator", "selection_accelerator")
        assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(accelerator, "{}", "candidate"))
        assertEquals(listOf(selection), before.components.selections)
    }

    @Test fun frozenValidRoleAndSettingsRemainExactThroughPreparation() {
        for (role in listOf("user_custom-role", "a".repeat(50), "candidate\n")) {
            val result = PreviewSelectionForms.configure(selection, PreviewSelectionForms.settingsText(selection), role)
                as PreviewSelectionPreparation.Ready
            val changed = (DraftSelectionCommands.apply(before, result.command) as SelectionEdit.Ready).content.components.selections.single()
            assertEquals(role, changed.role)
            assertEquals(selection.settings, changed.settings)
        }
    }

    @Test fun roleOnlyPreparationPreservesSettingsBeyondTheSmallJsonEditorLimit() {
        val large = LocalSelection.fromJson(SelectionFixtures.node().also {
            it.putObject("settings").put("credential_ref", "secret://" + "opaque/".repeat(1_300))
                .put("weights_uri", "https://never-resolve.invalid/" + "retained/".repeat(1_000))
        })
        assertTrue(PreviewSelectionForms.settingsText(large).length > PreviewSelectionForms.MAX_SETTINGS_TEXT)
        assertEquals(PreviewSelectionPreparation.Invalid,
            PreviewSelectionForms.configure(large, PreviewSelectionForms.settingsText(large), "candidate"))
        val prepared = PreviewSelectionForms.configureRole(large, "candidate") as PreviewSelectionPreparation.Ready
        val changed = (DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(large))), prepared.command)
            as SelectionEdit.Ready).content.components.selections.single()
        assertEquals(large.toJson().put("role", "candidate"), changed.toJson())
        assertEquals(large.settings, changed.settings)
    }
}
