package ai.pickyour.core.evaluation

import ai.pickyour.core.analysis.AnalysisCoordinator
import ai.pickyour.core.analysis.AnalysisIdentity
import ai.pickyour.core.analysis.AnalysisState
import ai.pickyour.core.analysis.AnalysisTicket

class CompatibleOptionsAttempt internal constructor(
    internal val request: CompatibleOptionsRequest, internal val ticket: AnalysisTicket,
)

/** In-memory delivery gate. Call bind on every editor/context change, before scheduling its work. */
class CompatibleOptionsCoordinator {
    private val coordinator = AnalysisCoordinator<CompatibleOptionsOutcome>()
    private var request: CompatibleOptionsRequest? = null

    @Synchronized
    fun snapshot(): AnalysisState<CompatibleOptionsOutcome> = coordinator.snapshot()

    /** Even byte-identical independently captured requests retire the old result and pending attempt. */
    @Synchronized
    fun bind(next: CompatibleOptionsRequest) {
        if (request === next) return
        coordinator.clear(); request = next; coordinator.bind(identity(next))
    }

    @Synchronized
    fun start(next: CompatibleOptionsRequest): CompatibleOptionsAttempt? {
        if (request !== next) return null
        return CompatibleOptionsAttempt(next, coordinator.start(identity(next)))
    }

    @Synchronized
    fun complete(attempt: CompatibleOptionsAttempt, outcome: CompatibleOptionsOutcome): Boolean {
        if (request !== attempt.request || !outcome.belongsTo(attempt.request)) return false
        return coordinator.succeed(attempt.ticket, outcome)
    }

    @Synchronized
    fun cancel(attempt: CompatibleOptionsAttempt): Boolean =
        request === attempt.request && coordinator.cancel(attempt.ticket)

    /** Recheck at use time. A cached result grants no authority to apply a selection. */
    @Synchronized
    fun isCurrent(outcome: CompatibleOptionsOutcome): Boolean =
        (coordinator.snapshot() as? AnalysisState.Succeeded)?.report === outcome && request?.let(outcome::belongsTo) == true

    @Synchronized
    fun clear() { request = null; coordinator.clear() }

    private fun identity(request: CompatibleOptionsRequest): AnalysisIdentity {
        val b = request.binding
        // Full mathematical revisions and every source byte are in the digest; never narrow to Long.
        return AnalysisIdentity("compatible-options:${b.inputDigest}", 0, b.catalogSnapshotId,
            b.dependencyGraphId, "${b.rulesetId}:${b.rulesetVersion}", b.pricebookId)
    }
}
