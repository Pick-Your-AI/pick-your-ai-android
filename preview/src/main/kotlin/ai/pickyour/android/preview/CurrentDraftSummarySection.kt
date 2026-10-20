package ai.pickyour.android.preview

import android.app.Activity
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.PreviewObjectiveGuidance
import ai.pickyour.core.preview.PreviewStoredDraft
import ai.pickyour.core.preview.PreviewWorkloadInputs
import ai.pickyour.core.preview.RequiredObjectiveInput

/** Current local input state only. Fixed/retained reports cannot become a current result here. */
internal class CurrentDraftSummarySection(
    private val activity: Activity,
    private val alive: () -> Boolean,
) : LinearLayout(activity) {
    private var closed = false
    private var generation: Any? = null
    private val source: TextView
    private val components: TextView
    private val componentDetails: LinearLayout
    private var detailsRecord: PreviewStoredDraft? = null
    private var detailsExpanded = false
    private var detailsToggle: Button? = null
    private val sample: TextView
    private val objectives: TextView
    private val actions: LinearLayout
    private val evaluation: TextView

    init {
        id = R.id.draft_summary
        orientation = VERTICAL
        addView(TextView(activity).apply {
            id = R.id.draft_summary_heading
            setText(R.string.draft_summary_heading)
            textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        source = TextView(activity).apply {
            id = R.id.draft_summary_source
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(::addView)
        components = TextView(activity).apply { id = R.id.draft_summary_components }.also(::addView)
        componentDetails = LinearLayout(activity).apply { orientation = VERTICAL }.also(::addView)
        sample = TextView(activity).apply { id = R.id.draft_summary_sample }.also(::addView)
        addView(TextView(activity).apply { setText(R.string.draft_summary_selection_limits) })
        objectives = TextView(activity).apply {
            id = R.id.draft_summary_objectives
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(::addView)
        evaluation = TextView(activity).apply {
            id = R.id.draft_summary_evaluation
            setText(R.string.draft_summary_evaluation_unavailable)
        }.also(::addView)
        addView(TextView(activity).apply {
            id = R.id.draft_summary_cost
            setText(R.string.draft_summary_cost_unavailable)
        })
        actions = LinearLayout(activity).apply { orientation = VERTICAL }.also(::addView)
    }

    fun currentCheckStatus(text: String) { if (!closed) evaluation.text = text }

    private fun active() = !closed && alive() && !activity.isFinishing && !activity.isDestroyed

    /** Retained controls never acquire a replacement generation's input or storage authority. */
    fun retireActions() {
        generation = null
        detailsToggle?.isEnabled = false
        repeat(actions.childCount) { actions.getChildAt(it).isEnabled = false }
    }

    fun close() {
        closed = true
        retireActions()
        detailsRecord = null
        detailsExpanded = false
        detailsToggle = null
        componentDetails.removeAllViews()
    }

    /** Inspect only this acknowledged record, including while main-form edits remain pending. */
    private fun showRecordedComponents(record: PreviewStoredDraft?, token: Any, current: () -> Boolean) {
        if (detailsRecord !== record) detailsExpanded = false
        detailsRecord = record
        detailsToggle = null
        componentDetails.removeAllViews()
        if (record == null) return
        val selections = record.components.selections
        if (selections.isEmpty()) {
            componentDetails.addView(TextView(activity).apply {
                setText(R.string.draft_summary_components_empty)
            })
            return
        }
        val toggle = Button(activity).apply {
            minimumHeight = (48 * resources.displayMetrics.density + 0.5f).toInt()
            isEnabled = active() && current()
        }.also { detailsToggle = it; componentDetails.addView(it) }
        val body = LinearLayout(activity).apply { orientation = VERTICAL }.also(componentDetails::addView)
        fun literal(raw: String) = BidiFormatter.getInstance(activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            .unicodeWrap(CatalogDetailText.literal(activity, raw), TextDirectionHeuristics.LTR)
        fun text(value: String) { body.addView(TextView(activity).apply { text = value }) }
        fun render() {
            toggle.setText(if (detailsExpanded) R.string.draft_summary_hide_components else R.string.draft_summary_show_components)
            body.removeAllViews()
            if (!detailsExpanded) return
            text(activity.getString(R.string.draft_summary_components_revision, record.revision.toString()))
            text(activity.getString(R.string.draft_summary_components_unresolved))
            for (selection in selections) {
                text(activity.getString(R.string.draft_summary_component_record,
                    literal(selection.id), literal(selection.kind), literal(selection.role),
                    literal(selection.catalogItemId), literal(selection.catalogItemVersion),
                    activity.getString(if (selection.enabled) R.string.draft_summary_component_enabled
                        else R.string.draft_summary_component_disabled)))
            }
        }
        toggle.setOnClickListener {
            if (toggle.isEnabled && generation === token && active() && current()) {
                detailsExpanded = !detailsExpanded
                render()
            }
        }
        render()
    }

    fun update(
        record: PreviewStoredDraft?,
        state: PlanningDraftState,
        inputs: PreviewWorkloadInputs,
        sampleSelected: Boolean,
        canReviewComponents: Boolean,
        current: () -> Boolean,
        reviewInputs: () -> Unit,
        reviewComponents: () -> Unit,
        reviewPlanning: () -> Unit,
    ) {
        if (closed || activity.isDestroyed) return
        retireActions()
        actions.removeAllViews()
        val token = Any().also { generation = it }
        val available = state == PlanningDraftState.UNSAVED || state == PlanningDraftState.SAVED ||
            state == PlanningDraftState.PENDING_EDITS
        val captured = record?.takeIf { state == PlanningDraftState.SAVED || state == PlanningDraftState.PENDING_EDITS }
        source.text = when {
            state == PlanningDraftState.LOADING -> activity.getString(R.string.draft_summary_loading)
            !available -> activity.getString(R.string.draft_summary_unavailable)
            captured == null -> activity.getString(R.string.draft_summary_unsaved)
            state == PlanningDraftState.PENDING_EDITS -> activity.getString(R.string.draft_summary_pending, captured.revision.toString())
            else -> activity.getString(R.string.draft_summary_saved, captured.revision.toString())
        }
        components.text = if (captured == null) activity.getString(R.string.draft_summary_components_unavailable) else {
            val selected = captured.components.selections
            activity.getString(R.string.draft_summary_component_counts,
                selected.count { it.enabled }, selected.count { !it.enabled })
        }
        showRecordedComponents(captured, token, current)
        sample.setText(when {
            !available -> R.string.draft_summary_sample_unavailable
            sampleSelected -> R.string.draft_summary_sample_present
            else -> R.string.draft_summary_sample_absent
        })
        val guidance = if (available) PreviewObjectiveGuidance.assess(inputs) else null
        objectives.text = when (guidance) {
            null -> activity.getString(R.string.draft_summary_objectives_unavailable)
            PreviewObjectiveGuidance.InvalidInputs -> activity.getString(R.string.objective_inputs_invalid)
            PreviewObjectiveGuidance.Answered -> activity.getString(R.string.objective_inputs_answered)
            is PreviewObjectiveGuidance.Missing -> activity.getString(R.string.objective_inputs_missing,
                guidance.inputs.joinToString(", ") { activity.getString(when (it) {
                    RequiredObjectiveInput.REGION -> R.string.workload_region
                    RequiredObjectiveInput.CURRENCY -> R.string.workload_currency
                    RequiredObjectiveInput.DATA_CLASSIFICATION -> R.string.workload_classification
                }) })
        }
        fun action(id: Int, label: Int, enabled: Boolean, navigate: () -> Unit) {
            actions.addView(Button(activity).apply {
                this.id = id
                setText(label)
                isEnabled = enabled && active() && current()
                setOnClickListener {
                    if (isEnabled && generation === token && active() && current()) navigate()
                }
            })
        }
        when (guidance) {
            PreviewObjectiveGuidance.InvalidInputs -> action(R.id.draft_summary_input_action,
                R.string.draft_summary_review_invalid, true, reviewInputs)
            is PreviewObjectiveGuidance.Missing -> action(R.id.draft_summary_input_action,
                R.string.draft_summary_next_input, true, reviewInputs)
            else -> Unit
        }
        action(R.id.draft_summary_component_action, R.string.draft_summary_review_components,
            canReviewComponents, reviewComponents)
        action(R.id.draft_summary_planning_action, R.string.draft_summary_review_planning,
            available, reviewPlanning)
    }
}
