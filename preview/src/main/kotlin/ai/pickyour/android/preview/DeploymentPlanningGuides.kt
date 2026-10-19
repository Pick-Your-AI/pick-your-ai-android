package ai.pickyour.android.preview

import android.app.Activity
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Reference navigation only. It receives no draft, catalog, evidence, storage or execution authority. */
internal class DeploymentPlanningGuides(
    private val activity: Activity,
    private val alive: () -> Boolean,
    private val onReferenceNavigation: () -> Unit,
) : LinearLayout(activity) {
    private enum class Mode(val key: String, val title: Int, val meaning: Int, val steps: Int,
        val monitoring: Int, val limits: Int) {
        API("api", R.string.deployment_guide_api, R.string.deployment_guide_api_meaning,
            R.string.deployment_guide_api_steps, R.string.deployment_guide_api_monitoring, R.string.deployment_guide_api_limits),
        MANAGED("managed", R.string.deployment_guide_managed, R.string.deployment_guide_managed_meaning,
            R.string.deployment_guide_managed_steps, R.string.deployment_guide_managed_monitoring, R.string.deployment_guide_managed_limits),
        SELF_HOSTED("self_hosted", R.string.deployment_guide_self_hosted, R.string.deployment_guide_self_hosted_meaning,
            R.string.deployment_guide_self_hosted_steps, R.string.deployment_guide_self_hosted_monitoring, R.string.deployment_guide_self_hosted_limits),
        HYBRID("hybrid", R.string.deployment_guide_hybrid, R.string.deployment_guide_hybrid_meaning,
            R.string.deployment_guide_hybrid_steps, R.string.deployment_guide_hybrid_monitoring, R.string.deployment_guide_hybrid_limits),
    }

    private class Card(val button: Button, val body: LinearLayout)
    private val cards: Map<Mode, Card>
    private var expanded: Mode? = null

    init {
        id = R.id.deployment_planning_guides
        orientation = VERTICAL
        isSaveEnabled = true
        heading(R.string.deployment_guide_heading)
        paragraph(R.string.deployment_guide_scope)
        paragraph(R.string.deployment_guide_edition)
        cards = Mode.entries.associateWith { mode ->
            val button = Button(activity).apply {
                minimumWidth = (48 * resources.displayMetrics.density).roundToInt()
                minimumHeight = (48 * resources.displayMetrics.density).roundToInt()
                setOnClickListener {
                    if (active()) {
                        onReferenceNavigation()
                        show(if (expanded == mode) null else mode)
                    }
                }
            }
            addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val body = LinearLayout(activity).apply {
                orientation = VERTICAL
                heading(mode.meaning)
                heading(R.string.deployment_guide_steps)
                paragraph(mode.steps)
                heading(R.string.deployment_guide_observation)
                paragraph(mode.monitoring)
                heading(R.string.deployment_guide_limits)
                paragraph(mode.limits)
                paragraph(R.string.deployment_guide_shared_review)
            }
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            Card(button, body)
        }
        show(null)
    }

    private fun LinearLayout.heading(text: Int) = addView(TextView(activity).apply {
        setText(text)
        textSize = 18f
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    })

    private fun LinearLayout.paragraph(text: Int) = addView(TextView(activity).apply { setText(text) })

    private fun active() = alive() && isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed

    private fun show(mode: Mode?) {
        expanded = mode
        cards.forEach { (entry, card) ->
            val selected = mode == entry
            card.button.text = activity.getString(
                if (selected) R.string.deployment_guide_hide else R.string.deployment_guide_open,
                activity.getString(entry.title),
            )
            card.button.isSelected = selected
            card.body.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    /** Only an optional reference-page key survives recreation; no planning inputs are captured. */
    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("parent", super.onSaveInstanceState())
        putInt("format", 1)
        putString("reference", expanded?.key)
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.getParcelable("parent"))
        show(if (state.getInt("format", 0) == 1)
            Mode.entries.firstOrNull { it.key == state.getString("reference") } else null)
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) = dispatchFreezeSelfOnly(container)
    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) = dispatchThawSelfOnly(container)
}
