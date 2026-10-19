package ai.pickyour.android.preview

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.resources.InspectedResourceEstimate
import ai.pickyour.core.resources.RecordedResourceInspector
import ai.pickyour.core.resources.ResourceInspection
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Fixed input inspection, with no guest-draft, storage, evaluator or URI-resolution authority. */
internal class ResourceInspectionSection(
    private val activity: Activity,
    private val worker: Executor,
    private val alive: () -> Boolean,
    private val onNavigate: () -> Unit = {},
    private val openBuild: () -> InputStream = { activity.assets.open("build.json") },
    private val openEstimate: () -> InputStream = { activity.assets.open("resource-estimate.json") },
) {
    private var request: Any? = null
    private var record: InspectedResourceEstimate? = null
    private var browser: ResourceInspectionBrowser? = null
    private var closed = false
    private lateinit var output: TextView
    private lateinit var inspect: Button
    private lateinit var cancel: Button
    private lateinit var browse: Button

    fun attach(container: LinearLayout) {
        check(!::output.isInitialized)
        container.addView(TextView(activity).apply {
            setText(R.string.resource_inspection_heading)
            textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        container.addView(TextView(activity).apply { setText(R.string.resource_inspection_notice) })
        inspect = Button(activity).apply {
            setText(R.string.resource_inspection_open)
            setOnClickListener { if (current() && inspect.isEnabled) { onNavigate(); start() } }
        }
        cancel = Button(activity).apply {
            setText(R.string.resource_inspection_cancel)
            isEnabled = false
            setOnClickListener {
                if (current() && request != null) { onNavigate(); dismiss() }
            }
        }
        browse = Button(activity).apply {
            setText(R.string.resource_inspection_browse)
            isEnabled = false
            setOnClickListener {
                val token = request ?: return@setOnClickListener
                val captured = record ?: return@setOnClickListener
                if (!current(token)) return@setOnClickListener
                onNavigate()
                browser?.retire()
                val next = ResourceInspectionBrowser(activity, captured) {
                    current(token) && record === captured
                }
                browser = next
                next.show()
            }
        }
        output = TextView(activity).apply {
            setText(R.string.resource_inspection_idle)
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
        output.setText(R.string.resource_inspection_loading)
        try {
            worker.execute {
                val result = try {
                    openBuild().use { build -> openEstimate().use { estimate ->
                        RecordedResourceInspector().inspect(build, estimate)
                    } }
                } catch (_: Exception) { null }
                activity.runOnUiThread {
                    if (!current(token)) return@runOnUiThread
                    inspect.isEnabled = true
                    record = (result as? ResourceInspection.Inspected)?.record
                    browse.isEnabled = record != null
                    output.text = when (result) {
                        is ResourceInspection.Inspected -> activity.getString(R.string.resource_inspection_ready,
                            result.record.estimate.estimates.size, result.record.selections.size)
                        is ResourceInspection.Rejected -> activity.getString(R.string.resource_inspection_rejected, result.reason.name)
                        null -> activity.getString(R.string.resource_inspection_unavailable)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (current(token)) {
                inspect.isEnabled = true
                output.setText(R.string.resource_inspection_unavailable)
            }
        }
    }

    /** Pause and explicit cancellation retire the current read and all its pages, even after resume. */
    fun dismiss() {
        request = null
        record = null
        browser?.retire()
        browser = null
        if (::output.isInitialized) {
            inspect.isEnabled = !closed
            cancel.isEnabled = false
            browse.isEnabled = false
            output.setText(R.string.resource_inspection_idle)
        }
    }

    fun close() {
        closed = true
        dismiss()
    }
}
