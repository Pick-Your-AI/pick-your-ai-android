package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.drafts.LocalSnapshotArchive
import ai.pickyour.core.drafts.SnapshotArchiveEntry
import ai.pickyour.core.drafts.SnapshotArchiveLoad
import ai.pickyour.core.drafts.SnapshotArchiveState
import ai.pickyour.core.evaluation.LocalSnapshotEvaluator
import ai.pickyour.core.evaluation.RetainedEvaluationProfile
import ai.pickyour.core.evaluation.SnapshotEvaluationOutcome
import ai.pickyour.core.evaluation.SnapshotEvaluator
import ai.pickyour.core.evaluation.inspect
import ai.pickyour.core.preview.PreviewComparedValue
import ai.pickyour.core.preview.PreviewComparedValueKind
import ai.pickyour.core.preview.PreviewSnapshotAnalysis
import ai.pickyour.core.preview.PreviewSnapshotAnalysisState
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Real private archive reader. It never creates a snapshot or receives pending editor contents. */
internal class SnapshotEvaluationSection(
    private val activity: Activity,
    private val worker: Executor,
    private val evaluator: SnapshotEvaluator = LocalSnapshotEvaluator(),
    private val archiveRoot: () -> Path = { activity.filesDir.toPath().resolve("retained-snapshots") },
    private val beforeInspect: () -> Unit = {},
    private val alive: () -> Boolean,
) {
    private var closed = false
    private var inventoryRequest: Any? = null
    private var window: AlertDialog? = null
    private var analysis: PreviewSnapshotAnalysis? = null
    private lateinit var open: Button
    private lateinit var status: TextView
    private fun active() = !closed && alive() && !activity.isFinishing && !activity.isDestroyed
    private fun current(dialog: AlertDialog) = active() && window === dialog && dialog.isShowing

    fun attach(container: LinearLayout) {
        status = TextView(activity).apply {
            id = R.id.snapshot_status
            setText(R.string.snapshot_notice)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        open = Button(activity).apply { id = R.id.snapshot_open; setText(R.string.snapshot_open) }
        open.setOnClickListener { if (active()) { beforeInspect(); loadInventory() } }
        container.addView(open); container.addView(status)
    }

    /** Pause/background and explicit replacement both revoke every old callback before dismissal. */
    fun dismiss() {
        inventoryRequest = null
        retireWindow()
        if (::open.isInitialized) open.isEnabled = !closed
        if (::status.isInitialized) status.setText(R.string.snapshot_notice)
    }

    fun close() { closed = true; dismiss() }

    private fun retireWindow() {
        val previous = window
        window = null
        val previousAnalysis = analysis
        analysis = null
        previousAnalysis?.close()
        previous?.dismiss()
    }

    private fun loadInventory() {
        val attempt = Any()
        inventoryRequest = attempt
        retireWindow()
        open.isEnabled = false
        status.setText(R.string.snapshot_inventory_loading)
        try { worker.execute {
            var failure = "IO_FAILURE"
            val loaded = try {
                val root = archiveRoot()
                when {
                    !Files.exists(root, NOFOLLOW_LINKS) -> { failure = "NOT_FOUND"; null }
                    !Files.isDirectory(root, NOFOLLOW_LINKS) ||
                        !Files.isDirectory(root.resolve("bundles"), NOFOLLOW_LINKS) ||
                        !Files.isDirectory(root.resolve("indices"), NOFOLLOW_LINKS) ||
                        !Files.isRegularFile(root.resolve("namespace"), NOFOLLOW_LINKS) -> { failure = "CORRUPT"; null }
                    else -> {
                        val archive = LocalSnapshotArchive(root)
                        when (val value = archive.loadExisting()) {
                            is SnapshotArchiveLoad.Available -> archive to value.state
                            is SnapshotArchiveLoad.Failed -> { failure = value.reason.name; null }
                        }
                    }
                }
            } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!active() || inventoryRequest !== attempt) return@runOnUiThread
                inventoryRequest = null; open.isEnabled = true
                if (loaded == null) {
                    status.text = activity.getString(R.string.snapshot_inventory_unavailable, failure)
                } else if (loaded.second.entries.isEmpty()) {
                    status.setText(R.string.snapshot_empty)
                } else {
                    status.setText(R.string.snapshot_choose_notice)
                    choose(loaded.first, loaded.second)
                }
            }
        } } catch (_: RejectedExecutionException) {
            if (inventoryRequest === attempt) {
                inventoryRequest = null; open.isEnabled = true
                status.text = activity.getString(R.string.snapshot_inventory_unavailable, "DISPATCH_UNAVAILABLE")
            }
        }
    }

    private fun choose(archive: LocalSnapshotArchive, state: SnapshotArchiveState) {
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.snapshot_choose)
            .setItems(state.entries.map { activity.getString(R.string.snapshot_entry, it.key.id, it.key.revision.toString()) }.toTypedArray()) { origin, index ->
                val shown = origin as? AlertDialog ?: return@setItems
                if (!current(shown)) return@setItems
                val entry = state.entries.getOrNull(index) ?: return@setItems
                inspect(archive, state, entry)
            }.setNegativeButton(R.string.close, null).create()
        present(dialog)
    }

    private fun present(dialog: AlertDialog) {
        retireWindow()
        window = dialog
        dialog.setOnDismissListener {
            if (window === dialog) {
                window = null
                val previous = analysis; analysis = null; previous?.close()
            }
        }
        dialog.show()
    }

    private fun inspect(archive: LocalSnapshotArchive, state: SnapshotArchiveState, entry: SnapshotArchiveEntry) {
        val text = TextView(activity).apply {
            id = R.id.snapshot_report
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.snapshot_inspection)
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.snapshot_cancel, null)
            .setNeutralButton(R.string.snapshot_retry, null).create()
        present(dialog)
        lateinit var owned: PreviewSnapshotAnalysis
        fun valid() = current(dialog) && analysis === owned
        fun render() {
            if (!valid()) return
            val value = owned.snapshot()
            val busy = value is PreviewSnapshotAnalysisState.Loading || value is PreviewSnapshotAnalysisState.Evaluating
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = busy
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !busy
            val selection = activity.getString(R.string.snapshot_entry, entry.key.id, entry.key.revision.toString())
            text.text = when (value) {
                PreviewSnapshotAnalysisState.Idle, is PreviewSnapshotAnalysisState.Loading, is PreviewSnapshotAnalysisState.Evaluating ->
                    activity.getString(R.string.snapshot_evaluating, selection)
                is PreviewSnapshotAnalysisState.Current -> describe(value.result, state)
                is PreviewSnapshotAnalysisState.Cancelled -> activity.getString(R.string.snapshot_cancelled, selection)
                is PreviewSnapshotAnalysisState.Withheld -> activity.getString(R.string.snapshot_withheld, selection,
                    value.diagnostics.joinToString("\n") { "${it.slot}: ${it.status}" })
                is PreviewSnapshotAnalysisState.Failed -> activity.getString(R.string.snapshot_failed, selection,
                    value.evaluation?.reason?.name ?: value.archiveReason?.name ?: value.reason.name)
                PreviewSnapshotAnalysisState.Closed -> activity.getString(R.string.snapshot_cancelled, selection)
            }
        }
        owned = PreviewSnapshotAnalysis(worker, Executor { activity.runOnUiThread(it) }, evaluator) {
            activity.runOnUiThread { render() }
        }
        analysis = owned
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (valid()) { owned.cancel(); render() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (valid() && dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled) {
                owned.open(archive, state.version, entry); render()
            }
        }
        owned.open(archive, state.version, entry)
        render()
    }

    private fun describe(result: SnapshotEvaluationOutcome.Evaluated, state: SnapshotArchiveState): String {
        val report = result.report.inspect()
        val inputs = result.inputs
        return listOf(
            activity.getString(R.string.snapshot_result_notice),
            activity.getString(R.string.snapshot_entry, report.buildId, report.buildRevision.toString()),
            activity.getString(R.string.snapshot_result, report.result, report.deploymentReadiness),
            listOf("pass", "fail", "unknown", "skipped").joinToString(" · ") { outcome -> "$outcome: ${report.findings.count { it.outcome == outcome }}" },
            activity.getString(R.string.snapshot_times, result.observedAt.toString(), report.evaluatedAt),
            activity.getString(R.string.snapshot_profile, RetainedEvaluationProfile.ID),
            activity.getString(R.string.snapshot_receipt, inputs.bundleHash.hex, state.version.generation.toString(), state.version.hash.hex),
            inputs.diagnostics.joinToString("\n") { diagnostic -> diagnostic.binding?.let {
                activity.getString(R.string.snapshot_binding, it.slot.name, it.id, it.version, it.hash.hex)
            } ?: "${diagnostic.slot}: ${diagnostic.status}" },
            activity.getString(R.string.snapshot_limits),
            report.findings.joinToString("\n\n") { finding -> listOfNotNull(
                activity.getString(R.string.findings_row, finding.ruleId, finding.outcome, finding.severity),
                FindingsInspectionText.guidance(activity, finding),
                activity.getString(R.string.findings_reason, finding.code, literalText(finding.message)),
                activity.getString(R.string.findings_remediation, literalText(finding.remediation)),
                activity.getString(R.string.snapshot_affected, finding.selectionIds.joinToString(", ").ifEmpty {
                    activity.getString(R.string.findings_not_supplied) }),
            ).joinToString("\n") },
        ).joinToString("\n\n")
    }

    /** Display only: retained rule text cannot supply status lines or directional controls. */
    private fun literalText(raw: String?): String {
        val value = PreviewComparedValue.from(raw)
        return when (value.kind) {
            PreviewComparedValueKind.ABSENT -> activity.getString(R.string.findings_not_supplied)
            PreviewComparedValueKind.EMPTY -> activity.getString(R.string.snapshot_literal_empty)
            PreviewComparedValueKind.TEXT -> {
                // Input controls have already been escaped; only our formatter may add bidi marks.
                val literal = BidiFormatter.getInstance(activity.resources.configuration.locales[0])
                    .unicodeWrap(requireNotNull(value.quotedLiteral), TextDirectionHeuristics.LTR)
                if (value.truncated) activity.getString(R.string.snapshot_literal_truncated, literal) else literal
            }
        }
    }
}
