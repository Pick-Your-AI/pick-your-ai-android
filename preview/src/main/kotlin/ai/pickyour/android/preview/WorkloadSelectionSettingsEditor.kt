package ai.pickyour.android.preview

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.*

/** Per-selection assumptions; validation and acknowledged persistence use the existing worker. */
internal class WorkloadSelectionSettingsEditor(
    private val activity: Activity,
    initial: PreviewWorkloadSelectionInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var frozen = false
    private var closed = false

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.workload_selection_editor_guidance) })
        entry(PreviewWorkloadSelectionSettings.CONCURRENCY, R.string.workload_selection_concurrency,
            R.string.workload_selection_positive_integer_rule, initial.concurrency)
        entry(PreviewWorkloadSelectionSettings.REQUESTS, R.string.workload_selection_requests,
            R.string.workload_selection_nonnegative_integer_rule, initial.requestsPerDay)
        entry(PreviewWorkloadSelectionSettings.INPUT, R.string.workload_selection_input,
            R.string.workload_selection_nonnegative_integer_rule, initial.averageInputTokens)
        entry(PreviewWorkloadSelectionSettings.OUTPUT, R.string.workload_selection_output,
            R.string.workload_selection_nonnegative_integer_rule, initial.averageOutputTokens)
        entry(PreviewWorkloadSelectionSettings.LATENCY, R.string.workload_selection_latency,
            R.string.workload_selection_positive_integer_rule, initial.latencyBudgetMs)
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
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            setHint(R.string.workload_selection_not_supplied)
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
            text = activity.getString(R.string.workload_selection_supply, activity.getString(title))
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

    fun input(): PreviewWorkloadSelectionInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewWorkloadSelectionInput(text(PreviewWorkloadSelectionSettings.CONCURRENCY),
            text(PreviewWorkloadSelectionSettings.REQUESTS), text(PreviewWorkloadSelectionSettings.INPUT),
            text(PreviewWorkloadSelectionSettings.OUTPUT), text(PreviewWorkloadSelectionSettings.LATENCY))
    }

    fun showIssues(issues: List<PreviewWorkloadSelectionIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewWorkloadSelectionProblem.REQUIRED -> R.string.workload_selection_value_required
            PreviewWorkloadSelectionProblem.INTEGER -> R.string.workload_selection_integer_invalid
            PreviewWorkloadSelectionProblem.RANGE -> if (issue.field in setOf(PreviewWorkloadSelectionSettings.CONCURRENCY,
                    PreviewWorkloadSelectionSettings.LATENCY)) R.string.workload_selection_positive_integer_rule
                else R.string.workload_selection_nonnegative_integer_rule
            PreviewWorkloadSelectionProblem.TOO_LONG -> R.string.workload_selection_too_long
        })
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
    }

    fun close() { closed = true; freeze(true) }

    companion object {
        fun readOnly(activity: Activity, input: PreviewWorkloadSelectionInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val values = listOf(R.string.workload_selection_concurrency to input.concurrency,
                R.string.workload_selection_requests to input.requestsPerDay,
                R.string.workload_selection_input to input.averageInputTokens,
                R.string.workload_selection_output to input.averageOutputTokens,
                R.string.workload_selection_latency to input.latencyBudgetMs)
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.workload_selection_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
