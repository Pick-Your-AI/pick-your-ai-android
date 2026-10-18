package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.evaluation.ReferenceReportView
import ai.pickyour.core.evaluation.ReferenceSelectionContext
import ai.pickyour.core.evaluation.ReferenceSelectionUnavailable

/** Read-only navigation within one accepted report and its same-build identity projection. */
internal class FindingsSelectionNavigator(
    private val activity: Activity,
    private val report: ReferenceReportView,
    findingIndex: Int,
    private val isCurrent: () -> Boolean,
) {
    private val finding = report.findings.getOrNull(findingIndex)
    private var currentDialog: AlertDialog? = null
    private var retired = false

    fun show() { if (finding != null) showSelections(0) }

    fun retire() {
        retired = true
        val previous = currentDialog
        currentDialog = null
        previous?.dismiss()
    }

    private fun current(): Boolean {
        if (retired) return false
        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) {
            retire()
            return false
        }
        return true
    }

    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing
    private fun literal(value: String) = CatalogDetailText.literal(activity, value)

    private fun showSelections(page: Int) {
        val source = finding ?: return
        if (!current()) return
        val contexts = source.selectionContexts
        val pages = maxOf(1, (contexts.size + PAGE_SIZE - 1) / PAGE_SIZE)
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, contexts.size)
        present {
            text(activity.getString(R.string.findings_context_page, page + 1, pages,
                if (contexts.isEmpty()) 0 else start + 1, end, contexts.size))
            if (contexts.isEmpty()) text(activity.getString(R.string.findings_context_none))
            for (index in start until end) {
                text(activity.getString(R.string.findings_context_selection, literal(contexts[index].selectionId)))
                action(activity.getString(R.string.findings_context_inspect, index + 1)) { showSelection(index) }
            }
            if (page + 1 < pages) action(R.string.findings_context_next) { showSelections(page + 1) }
            if (page > 0) action(R.string.findings_context_previous) { showSelections(page - 1) }
            action(R.string.close) { retire() }
        }
    }

    private fun showSelection(index: Int) {
        val source = finding ?: return
        val context = source.selectionContexts.getOrNull(index) ?: return
        if (!current()) return
        present {
            text(activity.getString(R.string.findings_context_selection, literal(context.selectionId)), heading = true)
            when (context) {
                is ReferenceSelectionContext.Recorded -> {
                    text(activity.getString(R.string.findings_context_recorded,
                        literal(context.kind), literal(context.role), literal(context.catalogItemId),
                        literal(context.catalogItemVersion),
                        activity.getString(if (context.enabled) R.string.findings_context_enabled else R.string.findings_context_disabled)))
                }
                is ReferenceSelectionContext.Unavailable -> text(activity.getString(when (context.reason) {
                    ReferenceSelectionUnavailable.MISSING -> R.string.findings_context_missing
                    ReferenceSelectionUnavailable.AMBIGUOUS -> R.string.findings_context_ambiguous
                }))
            }
            action(R.string.findings_context_back) { showSelections(index / PAGE_SIZE) }
            action(R.string.close) { retire() }
        }
    }

    private inner class Page(private val dialog: AlertDialog) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        fun text(value: String, heading: Boolean = false) {
            content.addView(TextView(activity).apply {
                text = value
                textSize = if (heading) 20f else 16f
                setPadding(0, dp(6), 0, dp(6))
                if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
        }

        fun action(label: Int, command: () -> Unit) = action(activity.getString(label), command)

        fun action(label: String, command: () -> Unit) {
            content.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setSingleLine(false)
                minHeight = dp(48)
                minimumWidth = dp(48)
                setOnClickListener { if (current(dialog)) command() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun present(render: Page.() -> Unit) {
        val source = finding ?: return
        if (!current()) return
        val dialog = AlertDialog.Builder(activity).create()
        val page = Page(dialog)
        page.text(activity.getString(R.string.findings_context_title), heading = true)
        page.text(activity.getString(R.string.findings_context_scope))
        page.text(activity.getString(R.string.findings_context_identity,
            literal(report.id), literal(report.buildId), report.buildRevision.toString()))
        page.text(activity.getString(R.string.findings_context_locks, literal(report.catalogSnapshotId),
            literal(report.rulesetId), literal(report.rulesetVersion), literal(report.dependencyGraphId),
            report.dependencyGraphRevision.toString()))
        page.text(activity.getString(R.string.findings_row, literal(source.ruleId), source.outcome, source.severity))
        page.render()
        dialog.setView(ScrollView(activity).apply { addView(page.content) })
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener {
            if (currentDialog === dialog) {
                currentDialog = null
                retired = true
            }
        }
        dialog.show()
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object { const val PAGE_SIZE = 16 }
}
