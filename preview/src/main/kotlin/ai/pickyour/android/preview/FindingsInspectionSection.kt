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
                            else FindingsInspectionText.describe(activity, value)
                        inspect.isEnabled = true; browse.isEnabled = value != null
                    }
                }
            }
        }
        browse.setOnClickListener {
            if (!alive()) return@setOnClickListener
            val report = accepted ?: return@setOnClickListener
            AlertDialog.Builder(activity).setTitle(R.string.findings_browse)
                .setItems(report.findings.map { FindingsInspectionText.row(activity, it) }.toTypedArray()) { _, index ->
                    if (!alive()) return@setItems
                    val finding = report.findings[index]
                    AlertDialog.Builder(activity).setTitle(FindingsInspectionText.title(activity, finding))
                        .setMessage(FindingsInspectionText.describe(activity, finding))
                        .setPositiveButton(R.string.close, null).show()
                }.setNegativeButton(R.string.close, null).show()
        }
        container.addView(inspect); container.addView(browse); container.addView(output)
    }
}
