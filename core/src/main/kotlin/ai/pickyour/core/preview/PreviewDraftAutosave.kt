package ai.pickyour.core.preview

/** A complete input and the exact editor authority that existed when it was queued. */
class PreviewAutosaveRequest(
    val draftId: PreviewDraftId,
    val expected: PreviewStoredDraft?,
    val authority: Any,
    val pending: PreviewPendingDraft,
) {
    fun matches(id: PreviewDraftId, record: PreviewStoredDraft?, token: Any,
                input: PreviewPendingDraft?): Boolean =
        draftId == id && expected === record && authority === token && input != null &&
            pending.fields == input.fields && pending.context == input.context && pending.workload == input.workload

    override fun toString() = "PreviewAutosaveRequest"
}

fun interface PreviewAutosaveCancellation { fun cancel() }

/** Run the callback later on the caller's event thread; null means it was not accepted. */
fun interface PreviewAutosaveScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): PreviewAutosaveCancellation?
}

/**
 * Event-thread confined debounce policy, not a store or a save acknowledgement.
 * The caller rechecks a request's complete authority immediately before dispatching
 * canonical persistence. During a write, cancel the timer and keep newer edits in
 * the editor. Offer them with the newly acknowledged baseline only after success.
 * Failure pauses automatic attempts until an explicit retry/reopen resumes them.
 */
class PreviewDraftAutosave(
    private val scheduler: PreviewAutosaveScheduler,
    private val ready: (PreviewAutosaveRequest) -> Unit,
) : AutoCloseable {
    private var pending: PreviewAutosaveRequest? = null
    private var attempt: Any? = null
    private var cancellation: PreviewAutosaveCancellation? = null
    private var closed = false
    var isPaused = false
        private set

    /** Identical UI refreshes retain the original deadline; changed input replaces it. */
    fun schedule(request: PreviewAutosaveRequest): Boolean {
        if (closed || isPaused) return false
        if (pending?.matches(request.draftId, request.expected, request.authority, request.pending) == true) return true
        cancel()
        val token = Any()
        pending = request
        attempt = token
        val handle = try {
            scheduler.schedule(DELAY_MILLIS) {
                // Cancellation alone is insufficient: a retained callback must be inert too.
                if (!closed && !isPaused && pending === request && attempt === token) {
                    pending = null
                    attempt = null
                    cancellation = null
                    ready(request)
                }
            }
        } catch (_: RuntimeException) { null }
        if (handle == null) {
            pause()
            return false
        }
        cancellation = handle
        return true
    }

    fun cancel() {
        pending = null
        attempt = null
        val old = cancellation
        cancellation = null
        // A faulty cancellation cannot revive the retired request above.
        try { old?.cancel() } catch (_: RuntimeException) { }
    }

    fun completed(result: PreviewDraftSave) {
        if (result is PreviewDraftSave.Saved) resume() else pause()
    }

    fun pause() { cancel(); isPaused = true }

    /** This supplies no authority: the caller must still reconcile an uncertain/stale baseline. */
    fun resume() { if (!closed) isPaused = false }

    override fun close() { closed = true; cancel() }

    companion object {
        const val DELAY_MILLIS = 750L
    }
}
