package ai.pickyour.android.preview

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.PreviewStoredDraft
import ai.pickyour.core.preview.PreviewT6Guidance
import ai.pickyour.core.preview.PreviewT6Kind

internal enum class PlanningDraftState { LOADING, UNAVAILABLE, UNSAVED, SAVED, PENDING_EDITS }

/** Read-only planning guidance. Browsing changes catalog context, never the captured draft. */
internal class T6PlanningSection(
    private val activity: Activity,
    private val alive: () -> Boolean,
    onReferenceNavigation: () -> Unit,
    browse: (PreviewT6Kind) -> Unit,
) : LinearLayout(activity) {
    private val heading: TextView
    private val source: TextView
    private val choices: Map<PreviewT6Kind, TextView>

    init {
        orientation = VERTICAL
        heading = TextView(activity).apply {
            setText(R.string.planning_heading)
            textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }.also(::addView)
        addView(TextView(activity).apply { setText(R.string.planning_notice) })
        source = TextView(activity).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(::addView)
        choices = PreviewT6Kind.entries.associateWith { kind ->
            val labels = when (kind) {
                PreviewT6Kind.GUARDRAIL -> Labels(R.string.planning_guardrail, R.string.planning_guardrail_step,
                    R.string.planning_guardrail_evidence, R.string.planning_browse_guardrail)
                PreviewT6Kind.EVALUATION -> Labels(R.string.planning_evaluation, R.string.planning_evaluation_step,
                    R.string.planning_evaluation_evidence, R.string.planning_browse_evaluation)
                PreviewT6Kind.DEPLOYMENT -> Labels(R.string.planning_deployment, R.string.planning_deployment_step,
                    R.string.planning_deployment_evidence, R.string.planning_browse_deployment)
                PreviewT6Kind.MONITORING -> Labels(R.string.planning_monitoring, R.string.planning_monitoring_step,
                    R.string.planning_monitoring_evidence, R.string.planning_browse_monitoring)
                PreviewT6Kind.COST_POLICY -> Labels(R.string.planning_cost, R.string.planning_cost_step,
                    R.string.planning_cost_evidence, R.string.planning_browse_cost)
            }
            addView(TextView(activity).apply {
                setText(labels.title)
                textSize = 18f
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
            val recorded = TextView(activity).also(::addView)
            addView(TextView(activity).apply {
                text = activity.getString(labels.step) + "\n" + activity.getString(labels.evidence)
            })
            addView(Button(activity).apply {
                setText(labels.browse)
                setOnClickListener { if (active()) browse(kind) }
            })
            recorded
        }
        addView(DeploymentPlanningGuides(activity, alive, onReferenceNavigation))
        update(null, PlanningDraftState.LOADING)
    }

    private fun active() = alive() && !activity.isFinishing && !activity.isDestroyed

    internal fun reviewGuidance() {
        if (!active()) return
        heading.isFocusableInTouchMode = true
        heading.requestFocus()
        heading.requestRectangleOnScreen(android.graphics.Rect(0, 0, heading.width, heading.height), true)
    }

    fun update(record: PreviewStoredDraft?, state: PlanningDraftState) {
        if (!active()) return
        val captured = record?.takeIf { state == PlanningDraftState.SAVED || state == PlanningDraftState.PENDING_EDITS }
        source.text = when {
            state == PlanningDraftState.LOADING -> activity.getString(R.string.planning_loading)
            state == PlanningDraftState.UNAVAILABLE -> activity.getString(R.string.planning_unavailable)
            captured == null -> activity.getString(R.string.planning_unsaved)
            state == PlanningDraftState.PENDING_EDITS -> activity.getString(R.string.planning_pending, captured.revision.toString())
            else -> activity.getString(R.string.planning_saved, captured.revision.toString())
        }
        val summaries = captured?.let { PreviewT6Guidance.recorded(it).associateBy { row -> row.kind } }
        choices.forEach { (kind, output) ->
            val row = summaries?.get(kind)
            output.text = when {
                row == null -> activity.getString(R.string.planning_choices_unavailable)
                row.enabled == 0 && row.disabled == 0 -> activity.getString(R.string.planning_choices_missing)
                else -> activity.getString(R.string.planning_choices_recorded, row.enabled, row.disabled)
            }
        }
    }

    private class Labels(val title: Int, val step: Int, val evidence: Int, val browse: Int)
}
