package ai.pickyour.core.preview

import java.util.Collections
import java.util.EnumSet

/** Local field identities for editor feedback only; never serialized as contract answer states. */
enum class PreviewWorkloadField {
    REGION, CURRENCY, BUDGET, REQUESTS, INPUT_TOKENS, OUTPUT_TOKENS, CONCURRENCY, LATENCY,
}

/** Value-free feedback over the existing parser, which remains the complete form/save authority. */
object PreviewWorkloadValidation {
    fun invalidFields(inputs: PreviewWorkloadInputs): Set<PreviewWorkloadField> {
        val invalid = EnumSet.noneOf(PreviewWorkloadField::class.java)
        for (field in PreviewWorkloadField.entries) {
            // DraftWorkload's fields are independently optional. Validate each unchanged raw value
            // through the same parser without blaming it for a different field's error. A rejected
            // whole paste is a separate form marker; it does not make the retained text invalid.
            val isolated = when (field) {
                PreviewWorkloadField.REGION -> PreviewWorkloadInputs(region = inputs.region)
                PreviewWorkloadField.CURRENCY -> PreviewWorkloadInputs(currency = inputs.currency)
                PreviewWorkloadField.BUDGET -> PreviewWorkloadInputs(budget = inputs.budget)
                PreviewWorkloadField.REQUESTS -> PreviewWorkloadInputs(requests = inputs.requests)
                PreviewWorkloadField.INPUT_TOKENS -> PreviewWorkloadInputs(inputTokens = inputs.inputTokens)
                PreviewWorkloadField.OUTPUT_TOKENS -> PreviewWorkloadInputs(outputTokens = inputs.outputTokens)
                PreviewWorkloadField.CONCURRENCY -> PreviewWorkloadInputs(concurrency = inputs.concurrency)
                PreviewWorkloadField.LATENCY -> PreviewWorkloadInputs(latency = inputs.latency)
            }
            if (isolated.parsed() == null) invalid.add(field)
        }
        return Collections.unmodifiableSet(invalid)
    }
}
