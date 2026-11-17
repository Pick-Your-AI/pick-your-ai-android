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
import ai.pickyour.core.evaluation.ProvisionalFindingGuidance
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
    private val reviewSelection: ((PreviewStoredDraft, String) -> Unit)? = null,
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

    private fun literal(raw: String) = BidiFormatter.getInstance(activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
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
        fun current() = owns(captured, state, page)
        val builder = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.current_check_page, offset + 1, end, rows.size))
            .setItems(labels) { _, index ->
                if (!current() || index !in labels.indices) return@setItems
                showFinding(captured, state, offset, offset + index)
            }.setNegativeButton(R.string.close, null)
        if (end < rows.size) builder.setPositiveButton(R.string.current_check_next) { _, _ -> if (current()) showFindings(captured, state, end) }
        if (offset > 0) builder.setNeutralButton(R.string.current_check_previous) { _, _ -> if (current()) showFindings(captured, state, maxOf(0, offset - 10)) }
        page = builder.create()
        present(page)
    }

    private fun owns(captured: Source, state: PreviewCurrentDraftState.Current, window: AlertDialog) =
        active(captured) && analysis.state === state && dialog === window && window.isShowing

    private fun showFinding(captured: Source, state: PreviewCurrentDraftState.Current, offset: Int, index: Int) {
        if (!active(captured) || analysis.state !== state || index !in state.outcome.findings.indices) return
        val row = state.outcome.findings[index]
        val text = activity.getString(R.string.current_check_finding, literal(row.ruleId), literal(row.outcome),
            literal(row.severity), literal(row.message), literal(row.remediation),
            row.selectionIds.joinToString(", ", transform = ::literal)) + when (row.outcome) {
                "unknown" -> "\n\n" + activity.getString(R.string.current_finding_unknown_meaning)
                "skipped" -> "\n\n" + activity.getString(R.string.current_finding_skipped_meaning)
                else -> ""
            } + when (state.outcome.provisionalGuidance(row.ruleId)) {
                ProvisionalFindingGuidance.INCOMPLETE -> "\n\n" + activity.getString(R.string.findings_guidance_incomplete)
                ProvisionalFindingGuidance.REPORTED_INCOMPATIBILITY -> "\n\n" + activity.getString(R.string.findings_guidance_incompatible)
                ProvisionalFindingGuidance.UNCLASSIFIED -> ""
            }
        lateinit var detail: AlertDialog
        val context = LinearLayout(activity).apply {
            orientation = VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(TextView(activity).apply {
                setText(R.string.current_check_inspect)
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                isSingleLine = false
                ellipsize = null
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(Button(activity).apply {
                setText(R.string.current_finding_context)
                isSingleLine = false
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    if (owns(captured, state, detail)) showAffectedSelections(captured, state, offset, index, 0)
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        detail = AlertDialog.Builder(activity).setTitle(R.string.current_check_inspect).setMessage(text)
            .setCustomTitle(context)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.current_check_back) { _, _ ->
                if (owns(captured, state, detail)) showFindings(captured, state, offset)
            }.setNeutralButton(R.string.current_finding_full_text) { _, _ ->
                if (owns(captured, state, detail)) showFields(captured, state, offset, index)
            }.create()
        present(detail)
    }

    private fun showAffectedSelections(captured: Source, state: PreviewCurrentDraftState.Current,
        offset: Int, index: Int, selectionOffset: Int) {
        if (!active(captured) || analysis.state !== state || index !in state.outcome.findings.indices) return
        val ids = state.outcome.findings[index].selectionIds
        if (selectionOffset != 0 && selectionOffset !in ids.indices) return
        val end = minOf(selectionOffset + 10, ids.size)
        lateinit var page: AlertDialog
        fun current() = owns(captured, state, page)
        val builder = AlertDialog.Builder(activity)
            .setNegativeButton(R.string.current_finding_back_detail) { _, _ ->
                if (current()) showFinding(captured, state, offset, index)
            }
        if (ids.isEmpty()) {
            builder.setTitle(R.string.current_finding_context).setMessage(R.string.current_finding_context_empty)
        } else {
            val labels = ids.subList(selectionOffset, end).map(::literal).toTypedArray()
            builder.setTitle(activity.getString(R.string.current_finding_context_page, selectionOffset + 1, end, ids.size))
                .setItems(labels) { _, selected ->
                    if (current() && selected in labels.indices)
                        showSelectionContext(captured, state, offset, index, selectionOffset, ids[selectionOffset + selected])
                }
            if (end < ids.size) builder.setPositiveButton(R.string.current_finding_context_next) { _, _ ->
                if (current()) showAffectedSelections(captured, state, offset, index, end)
            }
            if (selectionOffset > 0) builder.setNeutralButton(R.string.current_finding_context_previous) { _, _ ->
                if (current()) showAffectedSelections(captured, state, offset, index, maxOf(0, selectionOffset - 10))
            }
        }
        page = builder.create()
        present(page)
    }

    private fun showSelectionContext(captured: Source, state: PreviewCurrentDraftState.Current,
        offset: Int, index: Int, selectionOffset: Int, selectionId: String) {
        if (!active(captured) || analysis.state !== state || index !in state.outcome.findings.indices ||
            selectionId !in state.outcome.findings[index].selectionIds) return
        val selection = captured.record.components.selections.singleOrNull { it.id == selectionId }
        val text = if (selection == null) activity.getString(R.string.current_finding_selection_unavailable, literal(selectionId))
            else activity.getString(R.string.current_finding_selection_fields, literal(selection.id), literal(selection.kind),
                literal(selection.role), literal(selection.catalogItemId), literal(selection.catalogItemVersion), selection.enabled.toString())
        lateinit var detail: AlertDialog
        fun current() = owns(captured, state, detail)
        val builder = AlertDialog.Builder(activity).setTitle(R.string.current_finding_selection_title).setMessage(text)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.current_finding_context_back) { _, _ ->
                if (current()) showAffectedSelections(captured, state, offset, index, selectionOffset)
            }
        if (selection != null) builder.setNeutralButton(R.string.current_finding_version_full) { _, _ ->
            if (current()) showSelectionVersionPart(captured, state, offset, index, selectionOffset, selectionId, 0)
        }
        val review = reviewSelection
        if (selection != null && review != null) builder.setCustomTitle(LinearLayout(activity).apply {
            orientation = VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(TextView(activity).apply {
                setText(R.string.current_finding_selection_title)
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                isSingleLine = false
                ellipsize = null
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(Button(activity).apply {
                setText(R.string.current_finding_manage_selection)
                isSingleLine = false
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    if (!current()) return@setOnClickListener
                    // Consume this showing owner before entering the existing management route.
                    dismissInspection()
                    review(captured.record, selection.id)
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        })
        detail = builder.create()
        present(detail)
    }

    private fun showSelectionVersionPart(captured: Source, state: PreviewCurrentDraftState.Current,
        offset: Int, index: Int, selectionOffset: Int, selectionId: String, part: Int) {
        if (!active(captured) || analysis.state !== state || index !in state.outcome.findings.indices ||
            selectionId !in state.outcome.findings[index].selectionIds) return
        val raw = captured.record.components.selections.singleOrNull { it.id == selectionId }?.catalogItemVersion ?: return
        val count = maxOf(1, (raw.length + FIELD_PART_UNITS - 1) / FIELD_PART_UNITS)
        if (part !in 0 until count) return
        val start = part * FIELD_PART_UNITS
        lateinit var window: AlertDialog
        fun current() = owns(captured, state, window)
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.current_finding_version_part, part + 1, count))
            .setMessage(literal(raw.substring(start, minOf(start + FIELD_PART_UNITS, raw.length))))
            .setNegativeButton(R.string.current_finding_selection_back) { _, _ ->
                if (current()) showSelectionContext(captured, state, offset, index, selectionOffset, selectionId)
            }
        if (part + 1 < count) builder.setPositiveButton(R.string.current_finding_next_part) { _, _ ->
            if (current()) showSelectionVersionPart(captured, state, offset, index, selectionOffset, selectionId, part + 1)
        }
        if (part > 0) builder.setNeutralButton(R.string.current_finding_previous_part) { _, _ ->
            if (current()) showSelectionVersionPart(captured, state, offset, index, selectionOffset, selectionId, part - 1)
        }
        window = builder.create()
        present(window)
    }

    private enum class FindingField(val label: Int) {
        EXPLANATION(R.string.current_finding_explanation),
        GUIDANCE(R.string.current_finding_guidance),
    }

    private fun showFields(captured: Source, state: PreviewCurrentDraftState.Current, offset: Int, index: Int) {
        if (!active(captured) || analysis.state !== state || index !in state.outcome.findings.indices) return
        val fields = FindingField.entries
        lateinit var chooser: AlertDialog
        chooser = AlertDialog.Builder(activity).setTitle(R.string.current_finding_full_text)
            .setItems(fields.map { activity.getString(it.label) }.toTypedArray()) { _, selected ->
                if (owns(captured, state, chooser) && selected in fields.indices)
                    showFieldPart(captured, state, offset, index, fields[selected], 0)
            }.setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.current_finding_back_detail) { _, _ ->
                if (owns(captured, state, chooser)) showFinding(captured, state, offset, index)
            }.create()
        present(chooser)
    }

    private fun showFieldPart(captured: Source, state: PreviewCurrentDraftState.Current, offset: Int,
        index: Int, field: FindingField, part: Int) {
        if (!active(captured) || analysis.state !== state || index !in state.outcome.findings.indices) return
        val row = state.outcome.findings[index]
        val raw = when (field) {
            FindingField.EXPLANATION -> row.message
            FindingField.GUIDANCE -> row.remediation
        }
        val count = maxOf(1, (raw.length + FIELD_PART_UNITS - 1) / FIELD_PART_UNITS)
        if (part !in 0 until count) return
        val start = part * FIELD_PART_UNITS
        val text = literal(raw.substring(start, minOf(start + FIELD_PART_UNITS, raw.length)))
        lateinit var window: AlertDialog
        fun current() = owns(captured, state, window)
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.current_finding_text_part, activity.getString(field.label), part + 1, count))
            .setMessage(text)
            .setNegativeButton(R.string.current_finding_back_fields) { _, _ ->
                if (current()) showFields(captured, state, offset, index)
            }
        if (part + 1 < count) builder.setPositiveButton(R.string.current_finding_next_part) { _, _ ->
            if (current()) showFieldPart(captured, state, offset, index, field, part + 1)
        }
        if (part > 0) builder.setNeutralButton(R.string.current_finding_previous_part) { _, _ ->
            if (current()) showFieldPart(captured, state, offset, index, field, part - 1)
        }
        window = builder.create()
        present(window)
    }

    /** Internal navigation changes only the owned window, without reentering sibling inspection. */
    private fun present(next: AlertDialog) {
        val previous = dialog
        dialog = next
        next.setOnDismissListener { if (dialog === next) dialog = null }
        previous?.dismiss()
        next.show()
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
        private const val FIELD_PART_UNITS = 160

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
