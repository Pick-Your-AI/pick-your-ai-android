package ai.pickyour.android.preview

import android.content.Context
import ai.pickyour.core.evaluation.UnknownInputObservation

/** Fixed display vocabulary only; callers supply observations from their captured produced result. */
internal object UnknownInputObservationText {
    fun describe(context: Context, observations: List<UnknownInputObservation>): String? {
        if (observations.isEmpty()) return null
        val lines = observations.map { observation -> context.getString(when (observation) {
            UnknownInputObservation.NO_MATCHING_SELECTION -> R.string.input_observation_no_selection
            UnknownInputObservation.AMBIGUOUS_SELECTION -> R.string.input_observation_ambiguous_selection
            UnknownInputObservation.MISSING_CAPTURED_VALUE -> R.string.input_observation_missing_value
            UnknownInputObservation.UNRESOLVABLE_REFERENCE -> R.string.input_observation_unresolvable_reference
            UnknownInputObservation.CATALOG_ENTRY_UNAVAILABLE -> R.string.input_observation_catalog
            UnknownInputObservation.OPERAND_DOMAIN_UNAVAILABLE -> R.string.input_observation_domain
            UnknownInputObservation.METRIC_NOT_SUPPLIED -> R.string.input_observation_metric
        }) }
        return (listOf(context.getString(R.string.input_observation_heading)) + lines +
            context.getString(R.string.input_observation_limits)).joinToString("\n")
    }
}
