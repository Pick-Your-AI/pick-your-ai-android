package ai.pickyour.core.preview

import ai.pickyour.core.evaluation.DraftCurrentEvaluation
import ai.pickyour.core.evaluation.DraftCurrentEvaluator
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.evaluation.DraftCurrentOutcome
import ai.pickyour.core.evaluation.DraftCurrentRecheck

/** Unavailable authority is not an empty/pass report or a zero cost/resource estimate. */
enum class PreviewCurrentUnavailable { EDITING, STORAGE_UNCONFIRMED, INPUT_UNAVAILABLE, BACKGROUND, CLOSED,
    SOURCE_CHANGED, READ_FAILED, EXECUTION_FAILED }

sealed interface PreviewCurrentDraftState {
    class Unavailable(val reason: PreviewCurrentUnavailable, val recheck: DraftCurrentRecheck? = null) : PreviewCurrentDraftState
    class Rechecking internal constructor(val request: DraftCurrentEvaluation) : PreviewCurrentDraftState
    class Current internal constructor(val request: DraftCurrentEvaluation, val outcome: DraftCurrentOutcome.Completed) : PreviewCurrentDraftState
    class Withheld internal constructor(val request: DraftCurrentEvaluation, val outcome: DraftCurrentOutcome.Unavailable) : PreviewCurrentDraftState
}

/** Session-owned, one-shot dispatch identity; equal hashes or a caller's retained result cannot recreate it. */
class PreviewCurrentDraftAttempt internal constructor(internal val request: DraftCurrentEvaluation) {
    internal var started = false
    override fun toString() = "PreviewCurrentDraftAttempt"
}

/**
 * Read-only consumer of Activity-acknowledged records. It never loads an editor, saves, migrates or
 * changes historical reports. Call update/invalidate at the caller's actual source/lifecycle events;
 * execute off the UI thread with the current canonical read path for that captured draft identity.
 */
class PreviewCurrentDraftAnalysis(
    private val evaluate: (DraftCurrentEvaluation) -> DraftCurrentOutcome = DraftCurrentEvaluator()::evaluate,
) {
    private class Basis(val record: PreviewStoredDraft, val inputs: DraftCurrentInputs?, val recheck: DraftCurrentRecheck)
    private var basis: Basis? = null // Last acknowledged source, even if its evaluation never completed.
    private var issuedRecord: PreviewStoredDraft? = null
    private var issuedInputs: DraftCurrentInputs? = null
    private var issuedAuthority: Any? = null
    private var active: PreviewCurrentDraftAttempt? = null
    private var closed = false
    private var value: PreviewCurrentDraftState = PreviewCurrentDraftState.Unavailable(PreviewCurrentUnavailable.STORAGE_UNCONFIRMED)
    val state: PreviewCurrentDraftState @Synchronized get() = value

    /** Idempotent on repeated rendering; a real revocation, new record/input instance or owner is a new attempt. */
    @Synchronized
    fun update(record: PreviewStoredDraft?, inputs: DraftCurrentInputs?, ready: Boolean, authority: Any,
        unavailable: PreviewCurrentUnavailable = PreviewCurrentUnavailable.STORAGE_UNCONFIRMED): PreviewCurrentDraftAttempt? {
        if (closed) return null
        val old = basis
        // A temporary input-read gap does not erase this same acknowledged source's old graph pin.
        // A new record with unavailable inputs is still recorded as unbound; no old catalog is assigned to it.
        if (record != null && (old?.record !== record || inputs != null && old?.inputs !== inputs)) {
            val plan = DraftCurrentEvaluation.plan(old?.record?.draft, old?.inputs, record.draft, inputs)
            basis = Basis(record, inputs, plan)
            revoke()
        }
        if (!ready || record == null || inputs == null) {
            revoke()
            value = PreviewCurrentDraftState.Unavailable(if (ready && inputs == null) PreviewCurrentUnavailable.INPUT_UNAVAILABLE else unavailable)
            return null
        }
        if (issuedRecord === record && issuedInputs === inputs && issuedAuthority === authority) return null
        revoke()
        issuedRecord = record; issuedInputs = inputs; issuedAuthority = authority
        val request = DraftCurrentEvaluation(record.draft, inputs, requireNotNull(basis).recheck)
        val attempt = PreviewCurrentDraftAttempt(request)
        active = attempt
        value = PreviewCurrentDraftState.Rechecking(request)
        return attempt
    }

    @Synchronized
    fun invalidate(reason: PreviewCurrentUnavailable) {
        if (closed) return
        revoke()
        value = PreviewCurrentDraftState.Unavailable(reason)
    }

    /** Each attempt can start once. Revoked queued/running work cannot publish or schedule more work. */
    fun execute(attempt: PreviewCurrentDraftAttempt, readCurrent: () -> PreviewDraftLoad) {
        synchronized(this) {
            if (closed || active !== attempt || attempt.started) return
            attempt.started = true
        }
        fun read(): PreviewDraftLoad? = try { readCurrent() } catch (_: Exception) { null }
        fun currentHead(result: PreviewDraftLoad?) = result is PreviewDraftLoad.Available && attempt.request.matches(result.record.draft)
        val start = read()
        if (!currentHead(start)) {
            reject(attempt, if (start is PreviewDraftLoad.Available || start == PreviewDraftLoad.Missing)
                PreviewCurrentUnavailable.SOURCE_CHANGED else PreviewCurrentUnavailable.READ_FAILED)
            return
        }
        synchronized(this) { if (closed || active !== attempt) return }
        val outcome = try { evaluate(attempt.request) } catch (_: Exception) {
            reject(attempt, PreviewCurrentUnavailable.EXECUTION_FAILED); return
        }
        synchronized(this) { if (closed || active !== attempt) return }
        // This is a fresh durable-head observation after the complete work, never a cached load receipt.
        val ending = read()
        synchronized(this) {
            if (closed || active !== attempt) return
            if (!currentHead(ending)) {
                reject(attempt, if (ending is PreviewDraftLoad.Available || ending == PreviewDraftLoad.Missing)
                    PreviewCurrentUnavailable.SOURCE_CHANGED else PreviewCurrentUnavailable.READ_FAILED)
            } else if (!outcome.belongsTo(attempt.request)) reject(attempt, PreviewCurrentUnavailable.EXECUTION_FAILED)
            else value = when (outcome) {
                is DraftCurrentOutcome.Completed -> PreviewCurrentDraftState.Current(attempt.request, outcome)
                is DraftCurrentOutcome.Unavailable -> PreviewCurrentDraftState.Withheld(attempt.request, outcome)
            }
        }
    }

    @Synchronized
    fun reject(attempt: PreviewCurrentDraftAttempt, reason: PreviewCurrentUnavailable) {
        if (closed || active !== attempt) return
        active = null
        value = PreviewCurrentDraftState.Unavailable(reason, attempt.request.recheck)
        // Keep the issued key: a failed read/work requires a deliberate retry or a new source event.
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true; revoke(); basis = null
        value = PreviewCurrentDraftState.Unavailable(PreviewCurrentUnavailable.CLOSED)
    }

    private fun revoke() {
        active = null
        issuedRecord = null; issuedInputs = null; issuedAuthority = null
    }
}
