package ai.pickyour.android.preview

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostReception
import ai.pickyour.core.pricing.InspectedCostEstimate
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Fixed immutable input inspection; it has no guest-draft, pricing or evaluator authority. */
internal class CostInspectionSection(
    private val activity: Activity,
    private val worker: Executor,
    private val alive: () -> Boolean,
    private val onNavigate: () -> Unit = {},
    private val openBuild: () -> InputStream = { activity.assets.open("build.json") },
    private val openEstimate: () -> InputStream = { activity.assets.open("cost-estimate.json") },
) {
    private var request: Any? = null
    private var record: InspectedCostEstimate? = null
    private var browser: CostInspectionBrowser? = null
    private var closed = false
    private lateinit var output: TextView
    private lateinit var inspect: Button
    private lateinit var cancel: Button
    private lateinit var browse: Button

    fun attach(container: LinearLayout) {
        check(!::output.isInitialized)
        container.addView(TextView(activity).apply {
            setText(R.string.cost_inspection_heading)
            textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        container.addView(TextView(activity).apply { setText(R.string.cost_fixture_notice) })
        inspect = Button(activity).apply {
            setText(R.string.cost_inspect)
            setOnClickListener { if (current() && isEnabled) { onNavigate(); start() } }
        }
        cancel = Button(activity).apply {
            setText(R.string.cost_inspection_cancel)
            isEnabled = false
            setOnClickListener { if (current() && request != null) { onNavigate(); dismiss() } }
        }
        browse = Button(activity).apply {
            setText(R.string.cost_inspection_browse)
            isEnabled = false
            setOnClickListener {
                val token = request ?: return@setOnClickListener
                val captured = record ?: return@setOnClickListener
                if (!current(token)) return@setOnClickListener
                onNavigate()
                if (!current(token) || record !== captured) return@setOnClickListener
                dismissInspection()
                val next = CostInspectionBrowser(activity, captured) {
                    current(token) && record === captured
                }
                browser = next
                next.show()
            }
        }
        output = TextView(activity).apply {
            setText(R.string.cost_inspection_idle)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        container.addView(inspect)
        container.addView(cancel)
        container.addView(browse)
        container.addView(output)
    }

    private fun current() = !closed && !activity.isDestroyed && !activity.isFinishing && alive()
    private fun current(token: Any) = current() && request === token

    private fun start() {
        dismiss()
        val token = Any()
        request = token
        inspect.isEnabled = false
        cancel.isEnabled = true
        output.setText(R.string.cost_loading)
        try {
            worker.execute {
                val result = try {
                    openBuild().use { build -> openEstimate().use { estimate ->
                        CostEstimateAdapter().inspect(build, estimate)
                    } }
                } catch (_: Exception) { null }
                activity.runOnUiThread {
                    if (!current(token)) return@runOnUiThread
                    inspect.isEnabled = true
                    record = (result as? CostReception.Inspected)?.estimate
                    browse.isEnabled = record != null
                    output.text = when (result) {
                        is CostReception.Inspected -> CostInspectionText.describe(activity, result.estimate)
                        is CostReception.Rejected -> activity.getString(R.string.cost_rejected, result.reason.name)
                        null -> activity.getString(R.string.cost_unavailable)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (current(token)) {
                inspect.isEnabled = true
                output.setText(R.string.cost_unavailable)
            }
        }
    }

    /** A sibling view retires presentation without cancelling the captured read or inline result. */
    fun dismissInspection() {
        val previous = browser
        browser = null
        previous?.retire()
    }

    /** Cancellation and pause retire the read and all its windows, including after resume. */
    fun dismiss() {
        request = null
        record = null
        dismissInspection()
        if (::output.isInitialized) {
            inspect.isEnabled = !closed
            cancel.isEnabled = false
            browse.isEnabled = false
            output.setText(R.string.cost_inspection_idle)
        }
    }

    fun close() {
        closed = true
        dismiss()
    }
}
