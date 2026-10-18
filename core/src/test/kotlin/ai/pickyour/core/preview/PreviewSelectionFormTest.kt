package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreviewSelectionFormTest {
    private val model = SelectionFixtures.selection()
    private val catalog = SelectionFixtures.catalog()
    private fun item(selection: LocalSelection = model) = PreviewCatalogItem(selection.catalogItemId, "fixture", selection.kind,
        selection.catalogItemVersion, PreviewCatalogKinds.all.single { it.id == selection.kind }.tier)

    @Test fun `add retains explicit role disabled state and exact settings without defaults`() {
        val form = PreviewSelectionForm("secondary", false, "{\"temperature\":0,\"top_p\":0.2500}")
        val command = (PreviewSelectionForms.add("new_selection", item(), catalog.hash.hex, form) as PreviewSelectionPreparation.Ready).command
        val result = DraftSelectionCommands.apply(DraftContent(), command, catalog) as SelectionEdit.Ready
        val selection = result.content.components.selections.single()
        assertEquals("secondary", selection.role)
        assertFalse(selection.enabled)
        assertEquals(0, selection.settings["temperature"].intValue())
        assertEquals(java.math.BigDecimal("0.2500"), selection.settings["top_p"].decimalValue())
        assertEquals(model.catalogItemVersion, selection.catalogItemVersion)
    }

    @Test fun `raw invalid oversized duplicate and missing required fields are not truncated or defaulted`() {
        for (form in listOf(PreviewSelectionForm("", true, "{}"), PreviewSelectionForm("primary", true, ""),
            PreviewSelectionForm("Primary", true, "{}"), PreviewSelectionForm("x".repeat(81), true, "{}"),
            PreviewSelectionForm("primary", true, "{\"temperature\":1,\"temperature\":0}"),
            PreviewSelectionForm("primary", true, "{} {}"), PreviewSelectionForm("primary", true, "[]"),
            PreviewSelectionForm("primary", true, " ".repeat(PreviewSelectionForms.MAX_SETTINGS_TEXT + 1)))) {
            assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.add("new_selection", item(), catalog.hash.hex, form))
        }
        val hosting = SelectionFixtures.selection("hosting_platform", "hosting")
        assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.add("new_selection", item(hosting), catalog.hash.hex,
            PreviewSelectionForm("primary", true, "{\"region\":\"" + '\uD800' + "\"}")))
        val accelerator = SelectionFixtures.selection("accelerator", "accelerator")
        assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.add("new_selection", item(accelerator), catalog.hash.hex,
            PreviewSelectionForm("primary", true, "{}")))
        assertTrue(PreviewSelectionForms.add("new_selection", item(accelerator), catalog.hash.hex,
            PreviewSelectionForm("primary", true, "{\"device_count\":1}")) is PreviewSelectionPreparation.Ready)
    }

    @Test fun `configure validates settings and replacement keeps existing role and settings`() {
        assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.configure(model, "{\"temperature\":9}"))
        val configured = PreviewSelectionForms.configure(model, "{}") as PreviewSelectionPreparation.Ready
        assertTrue(configured.command is SelectionCommand.Configure)
        val replace = PreviewSelectionForms.replace(model, item(), catalog.hash.hex) as PreviewSelectionPreparation.Ready
        val result = DraftSelectionCommands.apply(DraftContent(components = DraftSelections(listOf(model))), replace.command, catalog) as SelectionEdit.Ready
        assertEquals(model, result.content.components.selections.single())
        assertEquals(PreviewSelectionPreparation.Invalid, PreviewSelectionForms.replace(model, item(SelectionFixtures.selection("hosting_platform", "hosting")), catalog.hash.hex))
        assertFalse(PreviewSelectionForms.supported("deployment_mode"))
        assertFalse(PreviewSelectionForms.supported("tool"))
        assertEquals(model.settings.toString(), PreviewSelectionForms.settingsText(model))
    }
}
