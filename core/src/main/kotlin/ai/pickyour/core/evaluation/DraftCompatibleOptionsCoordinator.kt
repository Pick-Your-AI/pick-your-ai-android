package ai.pickyour.core.evaluation

import ai.pickyour.core.analysis.AnalysisCoordinator
import ai.pickyour.core.analysis.AnalysisIdentity
import ai.pickyour.core.analysis.AnalysisState
import ai.pickyour.core.analysis.AnalysisTicket

class DraftCompatibleOptionsAttempt internal constructor(
    internal val request: DraftCompatibleOptionsRequest, internal val ticket: AnalysisTicket,
)

/** Bind on every editor, role, stored-source or artifact event before dispatch. No storage authority. */
class DraftCompatibleOptionsCoordinator {
    private val coordinator = AnalysisCoordinator<DraftCompatibleOptionsOutcome>()
    private var request: DraftCompatibleOptionsRequest? = null

    @Synchronized
    fun snapshot(): AnalysisState<DraftCompatibleOptionsOutcome> = coordinator.snapshot()

    @Synchronized
    fun bind(next: DraftCompatibleOptionsRequest) {
        if (request === next) return
        coordinator.clear(); request = next; coordinator.bind(identity(next))
    }

    @Synchronized
    fun start(next: DraftCompatibleOptionsRequest): DraftCompatibleOptionsAttempt? {
        if (request !== next) return null
        return DraftCompatibleOptionsAttempt(next, coordinator.start(identity(next)))
    }

    @Synchronized
    fun complete(attempt: DraftCompatibleOptionsAttempt, outcome: DraftCompatibleOptionsOutcome): Boolean {
        if (request !== attempt.request || !outcome.belongsTo(attempt.request)) return false
        return coordinator.succeed(attempt.ticket, outcome)
    }

    @Synchronized
    fun cancel(attempt: DraftCompatibleOptionsAttempt): Boolean =
        request === attempt.request && coordinator.cancel(attempt.ticket)

    @Synchronized
    fun isCurrent(outcome: DraftCompatibleOptionsOutcome): Boolean =
        (coordinator.snapshot() as? AnalysisState.Succeeded)?.report === outcome && request?.let(outcome::belongsTo) == true

    @Synchronized
    fun clear() { request = null; coordinator.clear() }

    private fun identity(request: DraftCompatibleOptionsRequest): AnalysisIdentity {
        val b = request.binding
        // Local identity labels, not invented Graph/Pricebook locks. This profile consumes neither.
        return AnalysisIdentity("draft-options:${b.inputDigest}", 0, b.catalogSnapshotId,
            "${request.profile}:graph-not-consumed", "${b.rulesetId}:${b.rulesetVersion}",
            "${request.profile}:pricebook-not-consumed")
    }
}
