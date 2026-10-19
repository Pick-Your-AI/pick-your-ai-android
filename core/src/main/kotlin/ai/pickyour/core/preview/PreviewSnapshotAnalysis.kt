package ai.pickyour.core.preview

import ai.pickyour.core.analysis.AnalysisCoordinator
import ai.pickyour.core.analysis.AnalysisIdentity
import ai.pickyour.core.analysis.AnalysisTicket
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.drafts.LocalSnapshotArchive
import ai.pickyour.core.drafts.SnapshotArchiveEntry
import ai.pickyour.core.drafts.SnapshotArchiveFailure
import ai.pickyour.core.drafts.SnapshotArchiveKey
import ai.pickyour.core.drafts.SnapshotArchiveRead
import ai.pickyour.core.drafts.SnapshotArchiveVersion
import ai.pickyour.core.evaluation.LocalSnapshotEvaluator
import ai.pickyour.core.evaluation.SnapshotEvaluationOutcome
import ai.pickyour.core.evaluation.SnapshotEvaluationPreparation
import ai.pickyour.core.evaluation.SnapshotEvaluationRequest
import ai.pickyour.core.evaluation.SnapshotEvaluationSlot
import ai.pickyour.core.evaluation.SnapshotEvaluator
import ai.pickyour.core.evaluation.SnapshotInputDiagnostic
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Read-only captured index/entry identity. No archive CAS or editable source authority is exposed. */
class PreviewSnapshotSelection internal constructor(entry: SnapshotArchiveEntry, version: SnapshotArchiveVersion) {
    val key: SnapshotArchiveKey = entry.key
    val bundleHash: ArtifactHash = entry.bundleHash
    val bundleBytes: Int = entry.bundleBytes
    val indexHash: ArtifactHash = version.hash
    val indexGeneration: Int = version.generation
    override fun toString() = "PreviewSnapshotSelection"
}

enum class PreviewSnapshotFailure { MISSING, ARCHIVE_UNAVAILABLE, IDENTITY_MISMATCH, DISPATCH_UNAVAILABLE, EVALUATOR_UNAVAILABLE }
sealed interface PreviewSnapshotAnalysisState {
    data object Idle : PreviewSnapshotAnalysisState
    class Loading(val selection: PreviewSnapshotSelection) : PreviewSnapshotAnalysisState
    class Evaluating(val selection: PreviewSnapshotSelection, val request: SnapshotEvaluationRequest) : PreviewSnapshotAnalysisState
    class Current(val selection: PreviewSnapshotSelection, val result: SnapshotEvaluationOutcome.Evaluated) : PreviewSnapshotAnalysisState
    class Withheld(val selection: PreviewSnapshotSelection, val diagnostics: List<SnapshotInputDiagnostic>) : PreviewSnapshotAnalysisState
    class Failed(val selection: PreviewSnapshotSelection, val reason: PreviewSnapshotFailure,
        val archiveReason: SnapshotArchiveFailure? = null, val evaluation: SnapshotEvaluationOutcome.Failed? = null) : PreviewSnapshotAnalysisState
    class Cancelled(val selection: PreviewSnapshotSelection) : PreviewSnapshotAnalysisState
    data object Closed : PreviewSnapshotAnalysisState
}

/**
 * A private retained-snapshot viewing session. Worker owns all archive IO/evaluation; delivery owns
 * ordinary completions. changed is only a signal to read snapshot(), never a captured UI payload.
 * A new selection, retry, cancel or close retires the old attempt even when all visible values repeat.
 * Reading a captured historical index never grants archive-head CAS or requires its source Draft.
 */
class PreviewSnapshotAnalysis(
    private val worker: Executor, private val delivery: Executor,
    private val evaluator: SnapshotEvaluator = LocalSnapshotEvaluator(), private val changed: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val coordinator = AnalysisCoordinator<SnapshotEvaluationOutcome.Evaluated>()
    private var state: PreviewSnapshotAnalysisState = PreviewSnapshotAnalysisState.Idle
    private var attempt: Any? = null
    private var ticket: AnalysisTicket? = null
    private var selection: PreviewSnapshotSelection? = null

    fun snapshot(): PreviewSnapshotAnalysisState = synchronized(lock) { state }

    /** Explicit selection/retry. The index receipt is verified against this archive before use. */
    fun open(archive: LocalSnapshotArchive, version: SnapshotArchiveVersion, entry: SnapshotArchiveEntry): Boolean {
        val token = Any()
        val captured = PreviewSnapshotSelection(entry, version)
        synchronized(lock) {
            if (state == PreviewSnapshotAnalysisState.Closed) return false
            attempt = token; ticket = null; coordinator.clear(); selection = captured
            state = PreviewSnapshotAnalysisState.Loading(captured)
        }
        signal()
        try { worker.execute {
            if (!active(token)) return@execute
            val read = try { archive.readAt(version, captured.key) } catch (_: RuntimeException) {
                SnapshotArchiveRead.Failed(SnapshotArchiveFailure.IO_FAILURE)
            }
            if (!active(token)) return@execute
            when (read) {
                SnapshotArchiveRead.Missing -> finish(token, PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.MISSING))
                is SnapshotArchiveRead.Failed -> finish(token, PreviewSnapshotAnalysisState.Failed(captured,
                    PreviewSnapshotFailure.ARCHIVE_UNAVAILABLE, read.reason))
                is SnapshotArchiveRead.Available -> {
                    if (read.snapshot.entry.bundleHash != captured.bundleHash || read.snapshot.entry.bundleBytes != captured.bundleBytes ||
                        read.snapshot.entry.key != captured.key) {
                        finish(token, PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.IDENTITY_MISMATCH))
                        return@execute
                    }
                    val prepared = try { LocalSnapshotEvaluator.prepare(read.snapshot) } catch (_: RuntimeException) {
                        finish(token, PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.EVALUATOR_UNAVAILABLE))
                        return@execute
                    }
                    when (prepared) {
                        is SnapshotEvaluationPreparation.Withheld -> finish(token, PreviewSnapshotAnalysisState.Withheld(captured, prepared.diagnostics))
                        is SnapshotEvaluationPreparation.Ready -> evaluate(token, captured, prepared.request)
                    }
                }
            }
        } } catch (_: RejectedExecutionException) {
            finish(token, PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.DISPATCH_UNAVAILABLE))
        }
        return true
    }

    fun cancel(): Boolean {
        synchronized(lock) {
            if (attempt == null) return false
            val captured = selection ?: return false
            attempt = null; ticket?.let(coordinator::cancel); ticket = null
            state = PreviewSnapshotAnalysisState.Cancelled(captured)
        }
        signal(); return true
    }

    override fun close() {
        synchronized(lock) {
            if (state == PreviewSnapshotAnalysisState.Closed) return
            attempt = null; ticket = null; selection = null; coordinator.clear()
            state = PreviewSnapshotAnalysisState.Closed
        }
        signal()
    }

    private fun evaluate(token: Any, captured: PreviewSnapshotSelection, request: SnapshotEvaluationRequest) {
        val issued = synchronized(lock) {
            if (attempt !== token) return
            // Long 0 is a local immutable-view epoch, not the Build revision. The digest includes
            // the complete mathematical Build revision, source/bundle bytes, pins and profile.
            fun pin(slot: SnapshotEvaluationSlot) = request.bindings.singleOrNull { it.slot == slot }?.hash?.hex ?: "not-supplied"
            coordinator.start(AnalysisIdentity("retained:${request.digest.hex}", 0,
                pin(SnapshotEvaluationSlot.CATALOG), pin(SnapshotEvaluationSlot.GRAPH), pin(SnapshotEvaluationSlot.RULESET),
                pin(SnapshotEvaluationSlot.PRICEBOOK))).also {
                ticket = it; state = PreviewSnapshotAnalysisState.Evaluating(captured, request)
            }
        }
        signal()
        if (!active(token)) return
        val result = try { evaluator.evaluate(request) } catch (_: RuntimeException) {
            finish(token, PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.EVALUATOR_UNAVAILABLE)); return
        }
        dispatch(token) {
            if (ticket !== issued || !result.belongsTo(request)) {
                coordinator.clear(); state = PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.IDENTITY_MISMATCH)
            } else when (result) {
                is SnapshotEvaluationOutcome.Evaluated -> {
                    if (!coordinator.succeed(issued, result)) return@dispatch
                    state = PreviewSnapshotAnalysisState.Current(captured, result)
                }
                is SnapshotEvaluationOutcome.Failed -> {
                    coordinator.clear(); state = PreviewSnapshotAnalysisState.Failed(captured, PreviewSnapshotFailure.EVALUATOR_UNAVAILABLE, evaluation = result)
                }
                is SnapshotEvaluationOutcome.Cancelled -> {
                    coordinator.cancel(issued); state = PreviewSnapshotAnalysisState.Cancelled(captured)
                }
            }
        }
    }

    private fun active(token: Any) = synchronized(lock) { attempt === token }
    private fun finish(token: Any, result: PreviewSnapshotAnalysisState) = dispatch(token) {
        coordinator.clear(); state = result
    }
    private fun dispatch(token: Any, accept: () -> Unit) {
        fun apply(rejected: Boolean) {
            synchronized(lock) {
                if (attempt !== token) return
                if (rejected) {
                    coordinator.clear()
                    state = PreviewSnapshotAnalysisState.Failed(checkNotNull(selection), PreviewSnapshotFailure.DISPATCH_UNAVAILABLE)
                } else accept()
                attempt = null; ticket = null
            }
            signal()
        }
        try { delivery.execute { apply(false) } } catch (_: RejectedExecutionException) { apply(true) }
    }
    private fun signal() { try { changed() } catch (_: RuntimeException) { /* State remains readable without a stale captured payload. */ } }
}
