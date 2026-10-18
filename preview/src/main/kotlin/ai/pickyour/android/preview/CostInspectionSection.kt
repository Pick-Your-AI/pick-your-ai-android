package ai.pickyour.android.preview

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
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
            if (!alive()) return@setOnClickListener
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
                            is CostReception.Inspected -> CostInspectionText.describe(activity, result.estimate)
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

}
