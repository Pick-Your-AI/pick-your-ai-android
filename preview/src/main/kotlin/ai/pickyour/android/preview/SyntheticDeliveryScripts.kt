package ai.pickyour.android.preview

import java.util.Collections

/** Slots are script-local names. Only the existing coordinator can grant an actual attempt ticket. */
internal enum class SyntheticAttemptSlot { OLDER, SELECTED, RETRY }
internal enum class SyntheticScenarioTarget { CORE_BUILD, SELECTED }
internal enum class SyntheticDeliveryOutcome { SUCCESS, SIMULATED_UNAVAILABLE }

/** Data for the #58 owner's controlled adapter/scheduler, not a parallel executor or UI controller. */
internal sealed interface SyntheticDeliveryStep {
    data class Start(val slot: SyntheticAttemptSlot, val target: SyntheticScenarioTarget) : SyntheticDeliveryStep
    data class AdvanceVirtualTime(val milliseconds: Long) : SyntheticDeliveryStep
    data class Deliver(val slot: SyntheticAttemptSlot, val outcome: SyntheticDeliveryOutcome) : SyntheticDeliveryStep
    /** An explicit user/test retry action is required; this marker must never become an automatic retry. */
    data object RequireExplicitRetry : SyntheticDeliveryStep
}

/**
 * Immutable v1 plans. These definitions perform no waiting, IO, dispatch, cancellation or acceptance.
 * Delays are synthetic virtual durations, not latency budgets or observed performance. The eventual
 * consumer must keep synthetic/version labels visible and reject old/foreign/cancelled deliveries.
 */
internal enum class SyntheticDeliveryScript(val wireName: String, steps: List<SyntheticDeliveryStep>) {
    IMMEDIATE("immediate", listOf(
        SyntheticDeliveryStep.Start(SyntheticAttemptSlot.SELECTED, SyntheticScenarioTarget.SELECTED),
        SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.SELECTED, SyntheticDeliveryOutcome.SUCCESS),
    )),
    DELAYED("delayed_750ms", listOf(
        SyntheticDeliveryStep.Start(SyntheticAttemptSlot.SELECTED, SyntheticScenarioTarget.SELECTED),
        SyntheticDeliveryStep.AdvanceVirtualTime(750),
        SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.SELECTED, SyntheticDeliveryOutcome.SUCCESS),
    )),
    FAILURE_THEN_EXPLICIT_RETRY("failure_then_explicit_retry", listOf(
        SyntheticDeliveryStep.Start(SyntheticAttemptSlot.SELECTED, SyntheticScenarioTarget.SELECTED),
        SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.SELECTED, SyntheticDeliveryOutcome.SIMULATED_UNAVAILABLE),
        SyntheticDeliveryStep.RequireExplicitRetry,
        SyntheticDeliveryStep.Start(SyntheticAttemptSlot.RETRY, SyntheticScenarioTarget.SELECTED),
        SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.RETRY, SyntheticDeliveryOutcome.SUCCESS),
    )),
    NEWER_FIRST("newer_first", listOf(
        SyntheticDeliveryStep.Start(SyntheticAttemptSlot.OLDER, SyntheticScenarioTarget.CORE_BUILD),
        SyntheticDeliveryStep.Start(SyntheticAttemptSlot.SELECTED, SyntheticScenarioTarget.SELECTED),
        SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.SELECTED, SyntheticDeliveryOutcome.SUCCESS),
        SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.OLDER, SyntheticDeliveryOutcome.SUCCESS),
    ));

    val version = "synthetic-delivery-scripts-1"
    val identity = "$version/$wireName"
    val steps: List<SyntheticDeliveryStep> = Collections.unmodifiableList(ArrayList(steps))
}
