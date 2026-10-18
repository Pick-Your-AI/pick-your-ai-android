package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.evaluation.*
import java.util.concurrent.ExecutorService

/** Read-only fixed fixture. It never evaluates, receives or writes the editable guest draft. */
internal class FindingsInspectionSection(
    private val activity: Activity, private val worker: ExecutorService, private val alive: () -> Boolean,
) {
    private var accepted: ReferenceReportView? = null
    private var request: Any? = null
    private var currentDialog: AlertDialog? = null
    private var navigator: FindingsSelectionNavigator? = null
    private var closed = false

    private fun active() = !closed && alive() && !activity.isFinishing && !activity.isDestroyed
    private fun current(report: ReferenceReportView) = active() && accepted === report
    private fun current(dialog: AlertDialog, report: ReferenceReportView) =
        current(report) && currentDialog === dialog && dialog.isShowing

    private fun retireWindows() {
        navigator?.retire()
        navigator = null
        val previous = currentDialog
        currentDialog = null
        previous?.dismiss()
    }

    fun close() {
        closed = true
        request = null
        accepted = null
        retireWindows()
    }

    fun attach(container: LinearLayout) {
        val output = TextView(activity).apply {
            setText(R.string.findings_notice)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val inspect = Button(activity).apply { setText(R.string.findings_inspect) }
        val browse = Button(activity).apply { setText(R.string.findings_browse); isEnabled = false }
        inspect.setOnClickListener {
            if (!active()) return@setOnClickListener
            val attempt = Any()
            request = attempt
            retireWindows()
            inspect.isEnabled = false; browse.isEnabled = false; accepted = null
            output.setText(R.string.findings_loading)
            worker.execute {
                var rejection: String? = null
                val value = try {
                    activity.assets.open("build.json").use { build ->
                        activity.assets.open("ruleset.json").use { rules ->
                            activity.assets.open("evaluation-report.json").use { report ->
                                when (val result = ReferenceReportAdapter().receive(build, rules, report)) {
                                    is ReportReception.Accepted -> result.inspect()
                                    is ReportReception.Rejected -> { rejection = result.reason.name; null }
                                }
                            }
                        }
                    }
                } catch (_: Exception) { null }
                activity.runOnUiThread {
                    if (active() && request === attempt) {
                        accepted = value
                        output.text = if (value == null) activity.getString(R.string.findings_rejected, rejection ?: "UNAVAILABLE")
                            else describe(value)
                        inspect.isEnabled = true; browse.isEnabled = value != null
                    }
                }
            }
        }
        browse.setOnClickListener {
            if (!active()) return@setOnClickListener
            val report = accepted ?: return@setOnClickListener
            retireWindows()
            val picker = AlertDialog.Builder(activity).setTitle(R.string.findings_browse)
                .setItems(report.findings.map { activity.getString(R.string.findings_row, it.ruleId, it.outcome, it.severity) }.toTypedArray()) { origin, index ->
                    val dialog = origin as? AlertDialog ?: return@setItems
                    if (!current(dialog, report)) return@setItems
                    showFinding(report, index)
                }.setNegativeButton(R.string.close, null).create()
            present(picker)
        }
        container.addView(inspect); container.addView(browse); container.addView(output)
    }

    private fun showFinding(report: ReferenceReportView, index: Int) {
        if (!current(report)) return
        val finding = report.findings.getOrNull(index) ?: return
        val detail = AlertDialog.Builder(activity).setTitle(finding.ruleId).setMessage(describe(finding))
            .setNeutralButton(R.string.findings_context_open) { origin, _ ->
                val dialog = origin as? AlertDialog ?: return@setNeutralButton
                if (!current(dialog, report)) return@setNeutralButton
                // The showing finding authorizes this transition; the child then owns its own lifetime.
                currentDialog = null
                dialog.dismiss()
                navigator?.retire()
                navigator = FindingsSelectionNavigator(activity, report, index) { current(report) }.also { it.show() }
            }.setPositiveButton(R.string.close, null).create()
        present(detail)
    }

    private fun present(dialog: AlertDialog) {
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.show()
    }
    private fun describe(report: ReferenceReportView): String = listOf(
        activity.getString(R.string.findings_notice),
        activity.getString(R.string.findings_identity, report.id, report.buildId, report.buildRevision.toString(), report.evaluatedAt),
        activity.getString(R.string.findings_locks, report.catalogSnapshotId, report.rulesetId, report.rulesetVersion,
            report.dependencyGraphId, report.dependencyGraphRevision.toString()),
        activity.getString(R.string.findings_status, report.result, report.deploymentReadiness),
        listOf("pass", "fail", "unknown", "skipped").joinToString(" · ") { outcome -> "$outcome: ${report.findings.count { it.outcome == outcome }}" },
        activity.getString(R.string.findings_limits),
    ).joinToString("\n\n")

    private fun describe(finding: ReferenceFindingView): String {
        fun supplied(value: String?) = value ?: activity.getString(R.string.findings_not_supplied)
        val evidence = if (finding.evidence.isEmpty()) activity.getString(R.string.findings_no_evidence) else
            finding.evidence.joinToString("\n\n") { activity.getString(R.string.findings_evidence,
                it.path, supplied(it.observedJson), supplied(it.expectedJson), supplied(it.sourceVersion)) }
        return listOf(activity.getString(R.string.findings_row, finding.ruleId, finding.outcome, finding.severity),
            activity.getString(R.string.findings_reason, finding.code, finding.message),
            activity.getString(R.string.findings_remediation, finding.remediation?.takeIf { it.isNotEmpty() }
                ?: activity.getString(R.string.findings_not_supplied)),
            activity.getString(R.string.findings_selections, finding.selectionIds.joinToString(", ").ifEmpty { activity.getString(R.string.findings_not_supplied) }),
            evidence, activity.getString(R.string.findings_limits)).joinToString("\n\n")
    }
}
