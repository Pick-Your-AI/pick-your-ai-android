package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.*

/** Explicit optional controls; the existing worker and complete-Selection adapter own admission. */
internal class DeploymentTargetSettingsEditor(
    private val activity: Activity,
    initial: PreviewDeploymentTargetInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var environment = initial.environment
    private val environmentControl = Button(activity)
    private val environmentError = TextView(activity)
    private var frozen = false
    private var closed = false
    private var picker: AlertDialog? = null

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.deployment_target_editor_guidance) })
        environmentChoice()
        entry(PreviewDeploymentTargetSettings.REPLICAS_MIN, R.string.deployment_target_replicas_min,
            R.string.deployment_target_replicas_min_rule, initial.replicasMin)
        entry(PreviewDeploymentTargetSettings.REPLICAS_MAX, R.string.deployment_target_replicas_max,
            R.string.deployment_target_replicas_max_rule, initial.replicasMax)
        entry(PreviewDeploymentTargetSettings.REGION, R.string.deployment_target_region,
            R.string.deployment_target_region_rule, initial.region)
        entry(PreviewDeploymentTargetSettings.APPROVAL_TICKET, R.string.deployment_target_approval_ticket,
            R.string.deployment_target_approval_ticket_rule, initial.approvalTicket)
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            // Keep exact number syntax and literal string whitespace available for correction.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            hint = if (initial == null) activity.getString(R.string.deployment_target_not_supplied) else null
            isEnabled = initial != null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        supplied[key] = initial != null
        val supply = CheckBox(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            text = activity.getString(R.string.deployment_target_supply, activity.getString(title))
            isChecked = initial != null
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                if (!checked) editor.setText("")
                editor.error = null
                editor.isEnabled = checked
                editor.hint = if (checked) null else activity.getString(R.string.deployment_target_not_supplied)
            }
        }
        label.labelFor = editor.id
        addView(label)
        addView(supply)
        addView(editor)
        addView(TextView(activity).apply { setText(rule) })
        editors[key] = editor
        supplies[key] = supply
    }

    private fun environmentChoice() {
        val definition = PreviewDeploymentTargetSettings.fields.single { it.key == PreviewDeploymentTargetSettings.ENVIRONMENT }
        require(environment == null || environment in definition.enumValues)
        val label = TextView(activity).apply { setText(R.string.deployment_target_environment) }
        environmentError.apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        environmentControl.apply {
            id = View.generateViewId()
            isSaveEnabled = false
            setSingleLine(false)
            text = environment ?: activity.getString(R.string.deployment_target_not_supplied)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf(activity.getString(R.string.deployment_target_not_supplied)) + definition.enumValues
                val checked = environment?.let { definition.enumValues.indexOf(it) + 1 } ?: 0
                picker = showPreviewSingleChoiceDialog(activity, R.string.deployment_target_environment, values, checked,
                    isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                    environment = if (index == 0) null else definition.enumValues[index - 1]
                    text = values[index]
                    environmentError.visibility = View.GONE
                }
            }
        }
        label.labelFor = environmentControl.id
        addView(label)
        addView(environmentControl)
        addView(TextView(activity).apply { setText(R.string.deployment_target_environment_rule) })
        addView(environmentError)
    }

    fun input(): PreviewDeploymentTargetInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewDeploymentTargetInput(environment, text(PreviewDeploymentTargetSettings.REPLICAS_MIN),
            text(PreviewDeploymentTargetSettings.REPLICAS_MAX), text(PreviewDeploymentTargetSettings.REGION),
            text(PreviewDeploymentTargetSettings.APPROVAL_TICKET))
    }

    fun showIssues(issues: List<PreviewDeploymentTargetIssue>) {
        editors.values.forEach { it.error = null }
        environmentError.visibility = View.GONE
        for (issue in issues) {
            val message = activity.getString(when (issue.problem) {
                PreviewDeploymentTargetProblem.REQUIRED -> R.string.deployment_target_number_required
                PreviewDeploymentTargetProblem.INTEGER -> R.string.deployment_target_integer_invalid
                PreviewDeploymentTargetProblem.RANGE -> if (issue.field == PreviewDeploymentTargetSettings.REPLICAS_MIN)
                    R.string.deployment_target_replicas_min_rule else R.string.deployment_target_replicas_max_rule
                PreviewDeploymentTargetProblem.ENUM -> R.string.deployment_target_environment_invalid
                PreviewDeploymentTargetProblem.TEXT -> R.string.deployment_target_text_invalid
                PreviewDeploymentTargetProblem.TOO_LONG -> R.string.deployment_target_too_long
            })
            editors[issue.field]?.error = message
            if (issue.field == PreviewDeploymentTargetSettings.ENVIRONMENT) {
                environmentError.text = message
                environmentError.visibility = View.VISIBLE
            }
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        if (value) picker?.dismiss()
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        environmentControl.isEnabled = enabled
    }

    fun close() {
        closed = true
        picker?.dismiss()
        picker = null
        freeze(true)
    }

    companion object {
        fun readOnly(activity: Activity, input: PreviewDeploymentTargetInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.deployment_target_environment to input.environment,
                R.string.deployment_target_replicas_min to input.replicasMin,
                R.string.deployment_target_replicas_max to input.replicasMax,
                R.string.deployment_target_region to input.region,
                R.string.deployment_target_approval_ticket to input.approvalTicket)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.deployment_target_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
                if (value != null && label in setOf(R.string.deployment_target_region, R.string.deployment_target_approval_ticket)) {
                    addView(TextView(activity).apply { setText(R.string.deployment_target_supplied_text) })
                }
            }
        }
    }
}
