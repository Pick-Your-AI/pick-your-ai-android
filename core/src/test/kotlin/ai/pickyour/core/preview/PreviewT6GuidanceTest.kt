package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreviewT6GuidanceTest {
    private fun fixtureSelections(): List<LocalSelection> = requireNotNull(
        javaClass.getResourceAsStream("/contracts/v1/examples/build.json"),
    ).use { stream ->
        val root = (BoundedJsonReader().read(stream) as JsonInputResult.Parsed).value
        root["selections"].map { LocalSelection.fromJson(it.toString().toByteArray()) }
    }

    @Test fun `planning areas exactly cover the frozen T6 registry`() {
        val root = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/schemas/component-registry.json")).use {
            (BoundedJsonReader().read(it) as JsonInputResult.Parsed).value
        }
        val kinds = root["kinds"].properties().filter { it.value["tier"].asText() == "T6" }.map { it.key }.toSet()
        assertEquals(kinds, PreviewT6Kind.entries.map { it.catalogKind }.toSet())
    }

    @Test fun `empty core drafts retain all planning areas across every optional activation combination`() {
        for (retrieval in Activation.entries) for (agents in Activation.entries) for (training in Activation.entries) {
            val content = DraftContent(optional = mapOf(OptionalTier.T3 to TierDraft(retrieval),
                OptionalTier.T4 to TierDraft(agents), OptionalTier.T5 to TierDraft(training)))
            val result = PreviewT6Guidance.recorded(content)
            assertEquals(PreviewT6Kind.entries, result.map { it.kind })
            assertTrue(result.all { it.enabled == 0 && it.disabled == 0 })
        }
    }

    @Test fun `enabled and disabled roles remain separate without changing any recorded selection`() {
        val original = fixtureSelections()
        val guardrail = original.single { it.kind == "guardrail" }
        val disabled = LocalSelection.fromJson(guardrail.toJson().put("id", "sel_guardrail_disabled")
            .put("role", "secondary").put("enabled", false).put("notes", "private recorded note"))
        val content = DraftContent(components = DraftSelections(original + disabled))
        val bytesBefore = content.components.selections.map { it.toJson().toString() }
        val result = PreviewT6Guidance.recorded(content)
        assertEquals(PreviewT6Choices(PreviewT6Kind.GUARDRAIL, 1, 1), result.single { it.kind == PreviewT6Kind.GUARDRAIL })
        assertTrue(result.filter { it.kind != PreviewT6Kind.GUARDRAIL }.all { it.enabled == 1 && it.disabled == 0 })
        assertEquals(bytesBefore, content.components.selections.map { it.toJson().toString() })
        assertFalse(result.toString().contains("private recorded note"))
        assertThrows(UnsupportedOperationException::class.java) { (result as MutableList).clear() }
    }

    @Test fun `disabled choices remain recorded and unrelated kinds do not supply planning choices`() {
        val selections = fixtureSelections()
        val withoutT6 = selections.filter { selection -> PreviewT6Kind.entries.none { it.catalogKind == selection.kind } }
        assertTrue(PreviewT6Guidance.recorded(DraftContent(components = DraftSelections(withoutT6)))
            .all { it.enabled == 0 && it.disabled == 0 })
        val disabled = selections.filter { selection -> PreviewT6Kind.entries.any { it.catalogKind == selection.kind } }
            .map { LocalSelection.fromJson(it.toJson().put("enabled", false)) }
        assertTrue(PreviewT6Guidance.recorded(DraftContent(components = DraftSelections(disabled)))
            .all { it.enabled == 0 && it.disabled == 1 })
    }
}
