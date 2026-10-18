package ai.pickyour.core.analysis

/** Client-local identity, not a wire contract. Null evidence means unavailable, never a pass. */
data class AnalysisIdentity(
    val draftId: String,
    val revision: Long,
    val catalog: String,
    val graph: String,
    val ruleset: String,
    val pricebook: String,
    val costEvidence: String? = null,
    val resourceEvidence: String? = null,
) {
    init {
        require(revision >= 0)
        require(listOf(draftId, catalog, graph, ruleset, pricebook).all { it.isNotBlank() })
        require(costEvidence == null || costEvidence.isNotBlank())
        require(resourceEvidence == null || resourceEvidence.isNotBlank())
    }
}

/** Adapter boundary: classify failures here; never put backend messages or throwable text in state. */
enum class AnalysisFailure {
    OFFLINE, TIMED_OUT, UNAUTHORIZED, NOT_FOUND, INVALID_INPUT, STORAGE_UNAVAILABLE, SERVICE_UNAVAILABLE,
}

/** Tickets are opaque capabilities: matching identity alone cannot complete a newer request. */
class AnalysisTicket internal constructor(val sequence: Long, val identity: AnalysisIdentity)

sealed interface AnalysisState<out R> {
    data class Idle(val identity: AnalysisIdentity?) : AnalysisState<Nothing>
    data class Pending(val ticket: AnalysisTicket) : AnalysisState<Nothing>
    data class Succeeded<R>(val ticket: AnalysisTicket, val report: R) : AnalysisState<R>
    data class Failed(val ticket: AnalysisTicket, val failure: AnalysisFailure) : AnalysisState<Nothing>
    data class Cancelled(val ticket: AnalysisTicket) : AnalysisState<Nothing>
}

/**
 * Thread-safe, in-memory request ordering only. R must be an immutable report snapshot.
 * Adapters schedule work, classify errors and check persistence acknowledgments before calling start.
 * This class performs no IO, dispatching, durable storage or historical-report deletion.
 *
 * bind invalidates the visible result when any input changes; the caller starts reevaluation.
 * Reading state is a snapshot, not a subscription. UI adapters must observe changes on their own
 * execution context. Cancellation rejects late callbacks but does not stop the transport itself.
 */
class AnalysisCoordinator<R> {
    private var sequence = 0L
    private var current: AnalysisState<R> = AnalysisState.Idle(null)
    private var binding: AnalysisIdentity? = null

    @Synchronized
    fun snapshot(): AnalysisState<R> = current

    /** Returns true exactly when a new binding invalidates the current analysis. */
    @Synchronized
    fun bind(identity: AnalysisIdentity): Boolean {
        if (binding == identity) return false
        binding = identity
        current = AnalysisState.Idle(identity)
        return true
    }

    /** Every attempt gets a fresh ticket, including retries for identical inputs. */
    @Synchronized
    fun start(identity: AnalysisIdentity): AnalysisTicket {
        val next = Math.incrementExact(sequence) // Fail closed rather than reuse an exhausted sequence.
        val ticket = AnalysisTicket(next, identity)
        sequence = next
        binding = identity
        current = AnalysisState.Pending(ticket)
        return ticket
    }

    @Synchronized
    fun succeed(ticket: AnalysisTicket, report: R): Boolean {
        if (!isActive(ticket)) return false
        current = AnalysisState.Succeeded(ticket, report)
        return true
    }

    @Synchronized
    fun fail(ticket: AnalysisTicket, failure: AnalysisFailure): Boolean {
        if (!isActive(ticket)) return false
        current = AnalysisState.Failed(ticket, failure)
        return true
    }

    /** A delayed cancellation of an old request must not cancel a newer request. */
    @Synchronized
    fun cancel(ticket: AnalysisTicket): Boolean {
        if (!isActive(ticket)) return false
        current = AnalysisState.Cancelled(ticket)
        return true
    }

    /** Detach a draft/session, invalidating all outstanding work and the visible report. */
    @Synchronized
    fun clear() {
        binding = null
        current = AnalysisState.Idle(null)
    }

    private fun isActive(ticket: AnalysisTicket): Boolean =
        (current as? AnalysisState.Pending)?.ticket === ticket && binding == ticket.identity
}
