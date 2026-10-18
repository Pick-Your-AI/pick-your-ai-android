package ai.pickyour.core.preview

import ai.pickyour.core.drafts.DraftDataClassification
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreviewObjectiveGuidanceTest {
    @Test fun `objective guidance requirements exactly match frozen schema`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/schemas/build.schema.json"))
        val root = stream.use { (BoundedJsonReader().read(it) as JsonInputResult.Parsed).value }
        val required = root.path("properties").path("objectives").path("required").map { it.asText() }.toSet()
        assertEquals(required, RequiredObjectiveInput.entries.map { it.key }.toSet())
    }
    @Test fun `unanswered fields stay missing and exact optional zero does not answer them`() {
        val raw = PreviewWorkloadInputs(requests="0", budget="12.3400")
        val missing = PreviewObjectiveGuidance.assess(raw) as PreviewObjectiveGuidance.Missing
        assertEquals(RequiredObjectiveInput.entries, missing.inputs)
        assertThrows(UnsupportedOperationException::class.java) { (missing.inputs as MutableList).clear() }
        val one = PreviewObjectiveGuidance.assess(raw.copy(region="user region", currency="USD")) as PreviewObjectiveGuidance.Missing
        assertEquals(listOf(RequiredObjectiveInput.DATA_CLASSIFICATION), one.inputs)
        assertEquals(PreviewObjectiveGuidance.Answered,
            PreviewObjectiveGuidance.assess(raw.copy(region="user region", currency="USD", classification=DraftDataClassification.INTERNAL)))
    }
    @Test fun `invalid or rejected inputs never produce answered guidance`() {
        val raw = PreviewWorkloadInputs(region="user region", currency="USD", classification=DraftDataClassification.PUBLIC)
        for (bad in listOf(raw.copy(currency="USDD"), raw.copy(requests="-1"), raw.copy(budget="not a budget"),
            raw.copy(rejectedEdit=true))) assertEquals(PreviewObjectiveGuidance.InvalidInputs, PreviewObjectiveGuidance.assess(bad))
        val missing = PreviewObjectiveGuidance.assess(raw.copy(region="")) as PreviewObjectiveGuidance.Missing
        assertEquals(listOf(RequiredObjectiveInput.REGION), missing.inputs)
    }
}
