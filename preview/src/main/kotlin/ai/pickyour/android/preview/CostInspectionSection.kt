package ai.pickyour.android.preview

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.InspectedCostEstimate
import ai.pickyour.core.contracts.ContractMoney
import java.util.concurrent.ExecutorService

/** Read-only immutable fixture diagnostic, deliberately separate from the editable guest form. */
internal class CostInspectionSection(
    private val activity: Activity,
    private val worker: ExecutorService,
    private val alive: () -> Boolean,
) {
    fun attach(container: LinearLayout) {
        val output = TextView(activity).apply {
            setText(R.string.cost_fixture_notice)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val button = Button(activity).apply { setText(R.string.cost_inspect) }
        button.setOnClickListener {
            button.isEnabled = false
            output.setText(R.string.cost_loading)
            worker.execute {
                val result = try {
                    activity.assets.open("build.json").use { build ->
                        activity.assets.open("cost-estimate.json").use { CostEstimateAdapter().inspect(build, it) }
                    }
                } catch (_: Exception) { null }
                activity.runOnUiThread {
                    if (alive()) {
                        output.text = when (result) {
                            is CostReception.Inspected -> describe(result.estimate)
                            is CostReception.Rejected -> activity.getString(R.string.cost_rejected, result.reason.name)
                            null -> activity.getString(R.string.cost_unavailable)
                        }
                        button.isEnabled = true
                    }
                }
            }
        }
        container.addView(button)
        container.addView(output)
    }

    private fun describe(value: InspectedCostEstimate): String {
        fun money(value: ContractMoney) = "${value.amount.encoded} ${value.currency}"
        fun ids(values: Collection<String>) = values.joinToString(", ").ifEmpty { activity.getString(R.string.cost_none_declared) }
        val summary = listOf(
            activity.getString(R.string.cost_fixture_notice),
            activity.getString(R.string.cost_identity, value.buildId, value.buildRevision.toString(), value.estimateId, value.pricebookId),
            activity.getString(R.string.cost_period, value.periodDays.toString(), value.currency, value.estimatedAt),
            activity.getString(R.string.cost_coverage, value.declaredCoverage.name),
            activity.getString(R.string.cost_amounts, money(value.reportedSubtotal), money(value.reportedTax), money(value.reportedTotal)),
            activity.getString(R.string.cost_unpriced, ids(value.reportedUnpricedSelectionIds)),
            activity.getString(R.string.cost_unclassified, ids(value.unclassifiedActiveSelectionIds)),
            activity.getString(R.string.cost_evidence_limits),
        )
        val lines = value.lines.map { line ->
            activity.getString(R.string.cost_line, line.id, line.selectionId, line.meter, line.quantity,
                line.rateId, money(line.unitPrice), money(line.amount), line.method, line.confidence,
                ids(line.assumptions))
        }
        return (summary + lines + activity.getString(R.string.cost_warnings, ids(value.warnings))).joinToString("\n\n")
    }
}
