package ai.pickyour.core.preview

import ai.pickyour.core.analysis.AnalysisCoordinator
import ai.pickyour.core.analysis.AnalysisFailure
import ai.pickyour.core.analysis.AnalysisIdentity
import ai.pickyour.core.analysis.AnalysisState
import ai.pickyour.core.analysis.AnalysisTicket
import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.drafts.DraftVersion
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Complete, caller-qualified local input keys, not new wire fields or evidence validation.
 * Include the accepted source versions/content identities. Null evidence remains unavailable.
 */
data class PreviewAnalysisInputs(
    val catalog: String,
    val graph: String,
    val ruleset: String,
    val pricebook: String,
    val costEvidence: String? = null,
    val resourceEvidence: String? = null,
) {
    init {
        require(listOf(catalog, graph, ruleset, pricebook).all { it.isNotBlank() && it.length <= 4096 })
        require(listOf(costEvidence, resourceEvidence).all { it == null || (it.isNotBlank() && it.length <= 4096) })
    }
    override fun toString() = "PreviewAnalysisInputs"
}

/** A receipt issued only from this session's actual Saved or fresh Available result. */
class PreviewAnalysisSaved internal constructor(val record: PreviewStoredDraft) {
    override fun toString() = "PreviewAnalysisSaved"
}

/** Local directory, exact durable revision/hash and every qualified input participate in equality. */
class PreviewAnalysisIdentity internal constructor(
    private val version: DraftVersion,
    val inputs: PreviewAnalysisInputs,
) {
    val revision: Long get() = version.number
    val sourceHash: String get() = version.hash.hex
    internal fun coordinatorIdentity() = AnalysisIdentity(version.store.toString(), version.number,
        inputs.catalog, inputs.graph, inputs.ruleset, inputs.pricebook, inputs.costEvidence, inputs.resourceEvidence)
    override fun equals(other: Any?): Boolean = other is PreviewAnalysisIdentity &&
        version.store == other.version.store && version.number == other.version.number &&
        version.hash == other.version.hash && inputs == other.inputs
    override fun hashCode(): Int = listOf(version.store, version.number, version.hash, inputs).hashCode()
    override fun toString() = "PreviewAnalysisIdentity"
}

class PreviewAnalysisRequest internal constructor(
    val saved: PreviewAnalysisSaved,
    val identity: PreviewAnalysisIdentity,
    internal val ticket: AnalysisTicket,
) {
    override fun toString() = "PreviewAnalysisRequest"
}
class PreviewAnalysisReport<R> internal constructor(val identity: PreviewAnalysisIdentity, val value: R) {
    override fun toString() = "PreviewAnalysisReport"
}
sealed interface PreviewAnalysisCompletion<out R> {
    class Succeeded<R>(val identity: PreviewAnalysisIdentity, val report: R) : PreviewAnalysisCompletion<R>
    class Failed(val identity: PreviewAnalysisIdentity, val reason: AnalysisFailure) : PreviewAnalysisCompletion<Nothing>
}
fun interface PreviewAnalysisCancellation { fun cancel() }

/** Start must return promptly. Validate any response's complete input binding before completing. */
fun interface PreviewAnalysisEvaluator<R> {
    fun start(request: PreviewAnalysisRequest, complete: (PreviewAnalysisCompletion<R>) -> Unit): PreviewAnalysisCancellation
}

sealed interface PreviewAnalysisStorage {
    data object Idle : PreviewAnalysisStorage
    data object Loading : PreviewAnalysisStorage
    data object Missing : PreviewAnalysisStorage
    class Saving(val pending: PreviewPendingDraft) : PreviewAnalysisStorage
    class Saved(val binding: PreviewAnalysisSaved) : PreviewAnalysisStorage
    data object Conflict : PreviewAnalysisStorage
    data class Failed(val reason: DraftStorageFailure) : PreviewAnalysisStorage
    data object Uncertain : PreviewAnalysisStorage
    data object Closed : PreviewAnalysisStorage
}
sealed interface PreviewAnalysisEvaluation<out R> {
    data object AwaitingInputs : PreviewAnalysisEvaluation<Nothing>
    class Pending(val request: PreviewAnalysisRequest) : PreviewAnalysisEvaluation<Nothing>
    class Current<R>(val report: PreviewAnalysisReport<R>) : PreviewAnalysisEvaluation<R>
    class Failed(val identity: PreviewAnalysisIdentity, val reason: AnalysisFailure) : PreviewAnalysisEvaluation<Nothing>
    class Cancelled(val identity: PreviewAnalysisIdentity) : PreviewAnalysisEvaluation<Nothing>
}
class PreviewDraftAnalysisSnapshot<R> internal constructor(
    val storage: PreviewAnalysisStorage,
    val evaluation: PreviewAnalysisEvaluation<R>,
    /** The last successful report, explicitly stale while it is not the current result. */
    val stale: PreviewAnalysisReport<R>?,
)

/**
 * One local persistence session. No network, evaluator policy, frozen Build projection or report store.
 * Both executors belong to the caller; worker performs IO/start, delivery returns asynchronous results.
 * A change callback is a signal to read snapshot(), never a captured UI payload. R must be immutable.
 *
 * Qualification is explicit and bound to a current save receipt. A new save/load revokes old receipts
 * even when canonical deduplication reuses the revision. Dispatched saves are never interrupted or
 * silently retried: superseding/closing discards their callbacks, not their possible durable effects.
 */
class PreviewDraftAnalysis<R>(
    private val persistence: PreviewDraftPersistence,
    private val worker: Executor,
    private val delivery: Executor,
    private val evaluator: PreviewAnalysisEvaluator<R>,
    private val changed: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val coordinator = AnalysisCoordinator<PreviewAnalysisReport<R>>()
    private var storage: PreviewAnalysisStorage = PreviewAnalysisStorage.Idle
    private var saved: PreviewAnalysisSaved? = null
    private var inputs: PreviewAnalysisInputs? = null
    private var request: PreviewAnalysisRequest? = null
    private var cancellation: PreviewAnalysisCancellation? = null
    private var attempt: Any? = null
    private var lastReport: PreviewAnalysisReport<R>? = null

    fun snapshot(): PreviewDraftAnalysisSnapshot<R> = synchronized(lock) {
        val state = coordinator.snapshot()
        val evaluation = when (state) {
            is AnalysisState.Idle -> PreviewAnalysisEvaluation.AwaitingInputs
            is AnalysisState.Pending -> PreviewAnalysisEvaluation.Pending(checkNotNull(request))
            is AnalysisState.Succeeded -> PreviewAnalysisEvaluation.Current(state.report)
            is AnalysisState.Failed -> PreviewAnalysisEvaluation.Failed(checkNotNull(request).identity, state.failure)
            is AnalysisState.Cancelled -> PreviewAnalysisEvaluation.Cancelled(checkNotNull(request).identity)
        }
        PreviewDraftAnalysisSnapshot(storage, evaluation,
            lastReport?.takeUnless { state is AnalysisState.Succeeded && state.report === it })
    }

    /** Explicit reopen is also the recovery path after conflict, failure or uncertain persistence. */
    fun load(): Boolean {
        val token = Any()
        val old = synchronized(lock) {
            if (storage == PreviewAnalysisStorage.Closed) return false
            attempt = token
            storage = PreviewAnalysisStorage.Loading
            saved = null
            invalidate()
        }
        cancel(old); signal()
        dispatchStorage(token, saving = false) {
            when (val result = persistence.load()) {
                is PreviewDraftLoad.Available -> PreviewAnalysisStorage.Saved(PreviewAnalysisSaved(result.record))
                is PreviewDraftLoad.Failed -> PreviewAnalysisStorage.Failed(result.reason)
                PreviewDraftLoad.Missing -> PreviewAnalysisStorage.Missing
            }
        }
        return true
    }

    /** Capture the original acknowledged baseline. A pending save never supplies a guessed revision. */
    fun save(expected: PreviewAnalysisSaved?, pending: PreviewPendingDraft): Boolean {
        val token = Any()
        val old = synchronized(lock) {
            val writable = storage is PreviewAnalysisStorage.Saved || storage is PreviewAnalysisStorage.Saving ||
                storage == PreviewAnalysisStorage.Missing
            if (!writable || expected !== saved) return false
            attempt = token
            storage = PreviewAnalysisStorage.Saving(pending)
            invalidate()
        }
        cancel(old); signal()
        dispatchStorage(token, saving = true) {
            when (val result = persistence.save(expected?.record, pending.fields, pending.context, pending.workload)) {
                is PreviewDraftSave.Saved -> PreviewAnalysisStorage.Saved(PreviewAnalysisSaved(result.record))
                is PreviewDraftSave.Failed -> PreviewAnalysisStorage.Failed(result.reason)
                PreviewDraftSave.Conflict -> PreviewAnalysisStorage.Conflict
                PreviewDraftSave.Uncertain -> PreviewAnalysisStorage.Uncertain
            }
        }
        return true
    }

    /**
     * Call on an accepted producer event after qualifying these keys against binding.record.
     * Every change starts evaluation automatically, including evidence arrival at the same revision.
     * Null withdraws qualification. Missing cost/resource keys stay null, never an invented estimate.
     */
    fun setQualifiedInputs(binding: PreviewAnalysisSaved, value: PreviewAnalysisInputs?): Boolean {
        val old: PreviewAnalysisCancellation?
        val next: PreviewAnalysisRequest?
        synchronized(lock) {
            if (!isSaved(binding) || inputs == value) return false
            old = invalidate()
            inputs = value
            next = value?.let { begin(binding, it) }
        }
        cancel(old); signal()
        next?.let(::dispatchAnalysis)
        return true
    }

    /** Explicit retry preserves the complete identity but receives a new opaque coordinator ticket. */
    fun retry(binding: PreviewAnalysisSaved): Boolean {
        val old: PreviewAnalysisCancellation?
        val next: PreviewAnalysisRequest
        synchronized(lock) {
            val qualified = inputs ?: return false
            if (!isSaved(binding)) return false
            old = invalidate()
            inputs = qualified
            next = begin(binding, qualified)
        }
        cancel(old); signal(); dispatchAnalysis(next)
        return true
    }

    fun cancel(): Boolean {
        val old = synchronized(lock) {
            val current = request ?: return false
            if (!coordinator.cancel(current.ticket)) return false
            cancellation.also { cancellation = null }
        }
        cancel(old); signal()
        return true
    }

    override fun close() {
        val old = synchronized(lock) {
            if (storage == PreviewAnalysisStorage.Closed) return
            attempt = null
            saved = null
            storage = PreviewAnalysisStorage.Closed
            lastReport = null
            invalidate()
        }
        cancel(old); signal()
    }

    private fun isSaved(binding: PreviewAnalysisSaved): Boolean =
        (storage as? PreviewAnalysisStorage.Saved)?.binding === binding && saved === binding

    /** Called only under lock. The complete private store identity stays inside the coordinator. */
    private fun begin(binding: PreviewAnalysisSaved, qualified: PreviewAnalysisInputs): PreviewAnalysisRequest {
        val identity = PreviewAnalysisIdentity(binding.record.draft.version, qualified)
        return PreviewAnalysisRequest(binding, identity, coordinator.start(identity.coordinatorIdentity())).also { request = it }
    }

    private fun invalidate(): PreviewAnalysisCancellation? {
        coordinator.clear()
        inputs = null
        request = null
        return cancellation.also { cancellation = null }
    }

    private fun dispatchStorage(token: Any, saving: Boolean, operation: () -> PreviewAnalysisStorage) {
        fun uncertainDelivery() = if (saving) PreviewAnalysisStorage.Uncertain
            else PreviewAnalysisStorage.Failed(DraftStorageFailure.IO_FAILURE)
        try {
            worker.execute {
                if (!synchronized(lock) { attempt === token }) return@execute
                val result = try { operation() } catch (_: Exception) { uncertainDelivery() }
                try { delivery.execute { acceptStorage(token, result) } }
                catch (_: RejectedExecutionException) { acceptStorage(token, uncertainDelivery()) }
            }
        } catch (_: RejectedExecutionException) {
            // The write was never dispatched; this is a known failure, not an uncertain save.
            acceptStorage(token, PreviewAnalysisStorage.Failed(DraftStorageFailure.IO_FAILURE))
        }
    }

    private fun acceptStorage(token: Any, result: PreviewAnalysisStorage) {
        synchronized(lock) {
            if (attempt !== token) return
            attempt = null
            storage = result
            saved = (result as? PreviewAnalysisStorage.Saved)?.binding
        }
        signal()
    }

    private fun active(value: PreviewAnalysisRequest): Boolean = request === value && isSaved(value.saved) &&
        (coordinator.snapshot() as? AnalysisState.Pending)?.ticket === value.ticket

    private fun dispatchAnalysis(value: PreviewAnalysisRequest) {
        try {
            worker.execute {
                if (!synchronized(lock) { active(value) }) return@execute
                // Another writer must not turn an old durable receipt into a current-head request.
                val current = try { persistence.load() } catch (_: Exception) {
                    PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE)
                }
                val matches = current is PreviewDraftLoad.Available &&
                    current.record.draft.version.store == value.saved.record.draft.version.store &&
                    current.record.baselineIdentity == value.saved.record.baselineIdentity
                if (!matches) {
                    val failure = if (current is PreviewDraftLoad.Failed) PreviewAnalysisStorage.Failed(current.reason)
                        else PreviewAnalysisStorage.Conflict
                    try { delivery.execute { rejectStorage(value, failure) } }
                    catch (_: RejectedExecutionException) { rejectStorage(value, failure) }
                    return@execute
                }
                if (!synchronized(lock) { active(value) }) return@execute
                try {
                    val handle = evaluator.start(value) { result ->
                        try { delivery.execute { acceptAnalysis(value, result) } }
                        catch (_: RejectedExecutionException) {
                            acceptAnalysis(value, PreviewAnalysisCompletion.Failed(value.identity, AnalysisFailure.SERVICE_UNAVAILABLE))
                        }
                    }
                    val retained = synchronized(lock) {
                        if (active(value)) { cancellation = handle; true } else false
                    }
                    if (!retained) cancel(handle)
                } catch (_: Exception) {
                    acceptAnalysis(value, PreviewAnalysisCompletion.Failed(value.identity, AnalysisFailure.SERVICE_UNAVAILABLE))
                }
            }
        } catch (_: RejectedExecutionException) {
            acceptAnalysis(value, PreviewAnalysisCompletion.Failed(value.identity, AnalysisFailure.SERVICE_UNAVAILABLE))
        }
    }

    private fun rejectStorage(value: PreviewAnalysisRequest, failure: PreviewAnalysisStorage) {
        val old = synchronized(lock) {
            if (!active(value)) return
            storage = failure
            saved = null
            invalidate()
        }
        cancel(old); signal()
    }

    private fun acceptAnalysis(value: PreviewAnalysisRequest, result: PreviewAnalysisCompletion<R>) {
        synchronized(lock) {
            if (!active(value)) return
            val identity = when (result) {
                is PreviewAnalysisCompletion.Succeeded -> result.identity
                is PreviewAnalysisCompletion.Failed -> result.identity
            }
            if (identity != value.identity) return
            when (result) {
                is PreviewAnalysisCompletion.Succeeded -> {
                    val report = PreviewAnalysisReport(identity, result.report)
                    if (coordinator.succeed(value.ticket, report)) lastReport = report
                }
                is PreviewAnalysisCompletion.Failed -> coordinator.fail(value.ticket, result.reason)
            }
            cancellation = null
        }
        signal()
    }

    private fun cancel(value: PreviewAnalysisCancellation?) {
        try { value?.cancel() } catch (_: Exception) { /* State was invalidated before best-effort transport cancellation. */ }
    }
    private fun signal() {
        try { changed() } catch (_: RuntimeException) { /* Notification failure cannot turn state into a stale payload. */ }
    }
}
