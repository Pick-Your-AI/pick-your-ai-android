package ai.pickyour.core.preview

import java.util.Collections

/** Frozen BuildSnapshot objective fields only, not overall draft, deployment or evaluation readiness. */
enum class RequiredObjectiveInput(val key: String) {
    REGION("region"), CURRENCY("currency"), DATA_CLASSIFICATION("data_classification")
}
sealed interface PreviewObjectiveGuidance {
    data object InvalidInputs : PreviewObjectiveGuidance
    class Missing(inputs: List<RequiredObjectiveInput>) : PreviewObjectiveGuidance {
        val inputs: List<RequiredObjectiveInput> = Collections.unmodifiableList(ArrayList(inputs))
    }
    data object Answered : PreviewObjectiveGuidance

    companion object {
        fun assess(raw: PreviewWorkloadInputs): PreviewObjectiveGuidance {
            val parsed = raw.parsed() ?: return InvalidInputs
            val missing = buildList {
                if (parsed.region == null) add(RequiredObjectiveInput.REGION)
                if (parsed.currency == null) add(RequiredObjectiveInput.CURRENCY)
                if (parsed.dataClassification == null) add(RequiredObjectiveInput.DATA_CLASSIFICATION)
            }
            return if (missing.isEmpty()) Answered else Missing(missing)
        }
    }
}
