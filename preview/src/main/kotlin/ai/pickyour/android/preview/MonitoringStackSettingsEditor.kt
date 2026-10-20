package ai.pickyour.android.preview

import android.app.Activity
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

/** Transient local input; it never resolves a reference or changes monitoring configuration elsewhere. */
internal class MonitoringStackSettingsEditor(
    private val activity: Activity,
    initial: PreviewMonitoringStackInput,
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
        addView(TextView(activity).apply { setText(R.string.monitoring_stack_editor_guidance) })
        entry(PreviewMonitoringStackSettings.SAMPLE, R.string.monitoring_stack_sample,
            R.string.monitoring_stack_sample_rule, initial.sampleRate)
        entry(PreviewMonitoringStackSettings.RETENTION, R.string.monitoring_stack_retention,
            R.string.monitoring_stack_retention_rule, initial.retentionDays)
        entry(PreviewMonitoringStackSettings.CREDENTIAL, R.string.monitoring_stack_credential,
            R.string.monitoring_stack_credential_rule, initial.credentialRef)
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
            hint = if (initial == null) activity.getString(R.string.monitoring_stack_not_supplied) else null
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
            text = activity.getString(R.string.monitoring_stack_supply, activity.getString(title))
            keepMinimumTouchTarget()
            isChecked = initial != null
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                if (!checked) editor.setText("")
                editor.hint = if (checked) null else activity.getString(R.string.monitoring_stack_not_supplied)
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

    private fun Button.keepMinimumTouchTarget() {
        val minimum = kotlin.math.ceil(48 * resources.displayMetrics.density.toDouble()).toInt()
        minHeight = maxOf(minHeight, minimum)
        minimumHeight = maxOf(minimumHeight, minimum)
        minWidth = maxOf(minWidth, minimum)
        minimumWidth = maxOf(minimumWidth, minimum)
    }

    fun input(): PreviewMonitoringStackInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewMonitoringStackInput(text(PreviewMonitoringStackSettings.SAMPLE),
            text(PreviewMonitoringStackSettings.RETENTION), text(PreviewMonitoringStackSettings.CREDENTIAL))
    }

    fun showIssues(issues: List<PreviewMonitoringStackIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewMonitoringStackProblem.REQUIRED -> R.string.monitoring_stack_required
            PreviewMonitoringStackProblem.NUMBER -> R.string.monitoring_stack_number_invalid
            PreviewMonitoringStackProblem.INTEGER -> R.string.monitoring_stack_integer_invalid
            PreviewMonitoringStackProblem.RANGE -> if (issue.field == PreviewMonitoringStackSettings.SAMPLE)
                R.string.monitoring_stack_sample_range else R.string.monitoring_stack_retention_range
            PreviewMonitoringStackProblem.REFERENCE -> R.string.monitoring_stack_reference_invalid
            PreviewMonitoringStackProblem.TOO_LONG -> R.string.monitoring_stack_too_long
        })
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
    }

    fun close() {
        closed = true
        freeze(true)
    }

    companion object {
        fun readOnly(activity: Activity, input: PreviewMonitoringStackInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val values = listOf(R.string.monitoring_stack_sample to input.sampleRate,
                R.string.monitoring_stack_retention to input.retentionDays,
                R.string.monitoring_stack_credential to input.credentialRef)
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = activity.getString(when {
                        value == null -> R.string.monitoring_stack_not_supplied
                        value.isEmpty() -> R.string.monitoring_stack_empty_value
                        else -> R.string.monitoring_stack_supplied_value
                    })
                    isSaveEnabled = false
                })
                if (!value.isNullOrEmpty()) addView(TextView(activity).apply {
                    text = value
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
