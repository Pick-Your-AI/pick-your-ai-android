package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.evaluation.DraftCurrentUnavailable
import ai.pickyour.core.evaluation.DraftCurrentRecheck
import ai.pickyour.core.preview.PreviewCurrentDraftAnalysis
import ai.pickyour.core.preview.PreviewCurrentDraftState
import ai.pickyour.core.preview.PreviewCurrentUnavailable
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewStoredDraft
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Current saved-draft observations only. Recorded fixtures and retained reports are separate views. */
internal class CurrentDraftCheckSection(
    private val activity: Activity,
    private val worker: Executor,
    private val alive: () -> Boolean,
    private val statusChanged: (String) -> Unit,
    private val beforeInspect: () -> Unit,
) : LinearLayout(activity) {
    private class Source(val record: PreviewStoredDraft, val inputs: DraftCurrentInputs, val authority: Any,
        val current: () -> Boolean, val readCurrent: () -> PreviewDraftLoad)
    private val analysis = PreviewCurrentDraftAnalysis()
    private var source: Source? = null
    private var closed = false
    private var dialog: AlertDialog? = null
    private var presented: PreviewCurrentDraftState? = null
    private val status = TextView(activity)
    private val affected = TextView(activity)
    private val retry = Button(activity)
    private val inspect = Button(activity)

    init {
        id = R.id.current_draft_check
        orientation = VERTICAL
        addView(TextView(activity).apply {
            setText(R.string.current_check_heading); textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        status.id = R.id.current_draft_check_status
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status)
        affected.id = R.id.current_draft_check_affected
        addView(affected)
        addView(TextView(activity).apply { setText(R.string.current_check_limits) })
        retry.id = R.id.current_draft_check_retry
        retry.setText(R.string.current_check_retry)
        retry.setOnClickListener {
            val captured = source ?: return@setOnClickListener
            if (retry.isEnabled && active(captured) && analysis.state !is PreviewCurrentDraftState.Rechecking) {
                analysis.invalidate(PreviewCurrentUnavailable.INPUT_UNAVAILABLE)
                dispatch(captured)
            }
        }
        addView(retry)
        inspect.id = R.id.current_draft_check_inspect
        inspect.setText(R.string.current_check_inspect)
        inspect.setOnClickListener {
            val captured = source ?: return@setOnClickListener
            val state = analysis.state as? PreviewCurrentDraftState.Current ?: return@setOnClickListener
            if (active(captured)) {
                beforeInspect()
                showFindings(captured, state, 0)
            }
        }
        addView(inspect)
        render()
    }

    private fun active(captured: Source) = !closed && source === captured && alive() &&
        !activity.isFinishing && !activity.isDestroyed && captured.current()

    fun update(record: PreviewStoredDraft?, inputs: DraftCurrentInputs?, ready: Boolean, authority: Any,
        unavailable: PreviewCurrentUnavailable, current: () -> Boolean, readCurrent: () -> PreviewDraftLoad) {
        if (closed) return
        if (!ready || record == null || inputs == null) {
            source = null
            analysis.update(record, inputs, false, authority, unavailable)
            render()
            return
        }
        val old = source
        val captured = if (old != null && old.record === record && old.inputs === inputs && old.authority === authority) old
            else Source(record, inputs, authority, current, readCurrent).also { source = it }
        dispatch(captured)
    }

    private fun dispatch(captured: Source) {
        if (!active(captured)) { invalidate(PreviewCurrentUnavailable.STORAGE_UNCONFIRMED); return }
        val attempt = analysis.update(captured.record, captured.inputs, true, captured.authority)
        render()
        if (attempt == null) return
        try {
            worker.execute {
                analysis.execute(attempt, captured.readCurrent)
                activity.runOnUiThread { if (active(captured)) render() }
            }
        } catch (_: RejectedExecutionException) {
            analysis.reject(attempt, PreviewCurrentUnavailable.EXECUTION_FAILED)
            render()
        }
    }

    fun invalidate(reason: PreviewCurrentUnavailable) {
        if (closed) return
        source = null
        analysis.invalidate(reason)
        render()
    }

    private fun literal(raw: String) = BidiFormatter.getInstance(activity.resources.configuration.locales[0])
        .unicodeWrap(CatalogDetailText.literal(activity, raw), TextDirectionHeuristics.LTR)

    private fun render() {
        if (closed) return
        val state = analysis.state
        if (presented !== state) { dismissInspection(); presented = state }
        val plan: DraftCurrentRecheck? = when (state) {
            is PreviewCurrentDraftState.Rechecking -> state.request.recheck
            is PreviewCurrentDraftState.Current -> state.request.recheck
            is PreviewCurrentDraftState.Withheld -> state.request.recheck
            is PreviewCurrentDraftState.Unavailable -> state.recheck
        }
        status.text = when (state) {
            is PreviewCurrentDraftState.Rechecking -> activity.getString(R.string.current_check_rechecking, state.request.binding.sourceRevision.toString())
            is PreviewCurrentDraftState.Current -> {
                val findings = state.outcome.findings
                activity.getString(R.string.current_check_current, state.request.binding.sourceRevision.toString(),
                    findings.count { it.outcome == "pass" }, findings.count { it.outcome == "fail" },
                    findings.count { it.outcome == "unknown" }, findings.count { it.outcome == "skipped" })
            }
            is PreviewCurrentDraftState.Withheld -> activity.getString(R.string.current_check_withheld,
                state.request.binding.sourceRevision.toString(), activity.getString(when (state.outcome.reason) {
                    DraftCurrentUnavailable.OPTIONAL_INTENT_UNRESOLVED -> R.string.current_check_optional_unresolved
                    DraftCurrentUnavailable.WORK_LIMIT -> R.string.current_check_work_limit
                    DraftCurrentUnavailable.BINDING_MISMATCH -> R.string.current_check_binding_mismatch
                    DraftCurrentUnavailable.UNSUPPORTED_OPERATION -> R.string.current_check_unsupported
                    else -> R.string.current_check_invalid
                }))
            is PreviewCurrentDraftState.Unavailable -> activity.getString(when (state.reason) {
                PreviewCurrentUnavailable.EDITING -> R.string.current_check_pending
                PreviewCurrentUnavailable.BACKGROUND -> R.string.current_check_background
                PreviewCurrentUnavailable.INPUT_UNAVAILABLE -> R.string.current_check_inputs_unavailable
                PreviewCurrentUnavailable.SOURCE_CHANGED -> R.string.current_check_source_changed
                PreviewCurrentUnavailable.READ_FAILED, PreviewCurrentUnavailable.EXECUTION_FAILED -> R.string.current_check_failed
                else -> R.string.current_check_unavailable
            })
        }
        affected.text = plan?.let {
            activity.getString(R.string.current_check_affected, if (it.selectionIds.isEmpty())
                activity.getString(R.string.current_check_no_selections) else it.selectionIds.joinToString(", ", transform = ::literal))
        }.orEmpty()
        val usable = source?.let(::active) == true
        retry.isEnabled = usable && state !is PreviewCurrentDraftState.Rechecking
        inspect.isEnabled = usable && state is PreviewCurrentDraftState.Current
        statusChanged(status.text.toString())
    }

    private fun showFindings(captured: Source, state: PreviewCurrentDraftState.Current, offset: Int) {
        if (!active(captured) || analysis.state !== state) return
        val rows = state.outcome.findings
        val end = minOf(offset + 10, rows.size)
        val labels = rows.subList(offset, end).map {
            "${literal(it.ruleId)} · ${literal(it.outcome)} · ${literal(it.severity)}"
        }.toTypedArray()
        lateinit var page: AlertDialog
        fun current() = active(captured) && analysis.state === state && dialog === page && page.isShowing
        val builder = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.current_check_page, offset + 1, end, rows.size))
            .setItems(labels) { _, index ->
                if (!current() || index !in labels.indices) return@setItems
                val row = rows[offset + index]
                val text = activity.getString(R.string.current_check_finding, literal(row.ruleId), literal(row.outcome),
                    literal(row.severity), literal(row.message), literal(row.remediation),
                    row.selectionIds.joinToString(", ", transform = ::literal))
                page.dismiss()
                lateinit var detail: AlertDialog
                detail = AlertDialog.Builder(activity).setTitle(R.string.current_check_inspect).setMessage(text)
                    .setNegativeButton(R.string.close, null).setPositiveButton(R.string.current_check_back) { _, _ ->
                        if (dialog === detail && detail.isShowing && active(captured) && analysis.state === state)
                            showFindings(captured, state, offset)
                    }.create()
                dialog = detail; detail.show()
            }.setNegativeButton(R.string.close, null)
        if (end < rows.size) builder.setPositiveButton(R.string.current_check_next) { _, _ -> if (current()) showFindings(captured, state, end) }
        if (offset > 0) builder.setNeutralButton(R.string.current_check_previous) { _, _ -> if (current()) showFindings(captured, state, maxOf(0, offset - 10)) }
        page = builder.create()
        dialog?.dismiss(); dialog = page; page.show()
    }

    /** Retire only presentation callbacks; the current saved analysis remains available. */
    fun dismissInspection() {
        val previous = dialog
        dialog = null
        previous?.dismiss()
    }

    fun close() {
        if (closed) return
        closed = true; source = null; analysis.close()
        dismissInspection()
        retry.isEnabled = false; inspect.isEnabled = false
    }

    companion object {
        fun readInputs(catalog: ByteArray, graph: InputStream, ruleset: InputStream): DraftCurrentInputs? {
            fun bounded(stream: InputStream): ByteArray {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) return output.toByteArray()
                    check(output.size() + count <= DraftCurrentInputs.MAX_BYTES)
                    output.write(buffer, 0, count)
                }
            }
            return DraftCurrentInputs.read(catalog, bounded(graph), bounded(ruleset))
        }
    }
}
