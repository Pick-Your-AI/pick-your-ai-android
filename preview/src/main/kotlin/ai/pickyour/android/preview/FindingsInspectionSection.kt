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
    fun attach(container: LinearLayout) {
        val output = TextView(activity).apply {
            setText(R.string.findings_notice)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val inspect = Button(activity).apply { setText(R.string.findings_inspect) }
        val browse = Button(activity).apply { setText(R.string.findings_browse); isEnabled = false }
        var accepted: ReferenceReportView? = null
        inspect.setOnClickListener {
            if (!alive()) return@setOnClickListener
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
                    if (alive()) {
                        accepted = value
                        output.text = if (value == null) activity.getString(R.string.findings_rejected, rejection ?: "UNAVAILABLE")
                            else describe(value)
                        inspect.isEnabled = true; browse.isEnabled = value != null
                    }
                }
            }
        }
        browse.setOnClickListener {
            if (!alive()) return@setOnClickListener
            val report = accepted ?: return@setOnClickListener
            AlertDialog.Builder(activity).setTitle(R.string.findings_browse)
                .setItems(report.findings.map { activity.getString(R.string.findings_row, it.ruleId, it.outcome, it.severity) }.toTypedArray()) { _, index ->
                    if (!alive()) return@setItems
                    val finding = report.findings[index]
                    AlertDialog.Builder(activity).setTitle(finding.ruleId).setMessage(describe(finding))
                        .setPositiveButton(R.string.close, null).show()
                }.setNegativeButton(R.string.close, null).show()
        }
        container.addView(inspect); container.addView(browse); container.addView(output)
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
