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

/** Literal input controls; the existing worker and canonical adapter own admission and saving. */
internal class AcceleratorSettingsEditor(
    private val activity: Activity,
    initial: PreviewAcceleratorInput,
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
        addView(TextView(activity).apply { setText(R.string.accelerator_editor_guidance) })
        entry(PreviewAcceleratorSettings.DEVICES, R.string.accelerator_device_count,
            R.string.accelerator_device_count_rule, initial.deviceCount, required = true)
        entry(PreviewAcceleratorSettings.GROUPS, R.string.accelerator_groups,
            R.string.accelerator_groups_rule, initial.tensorParallelGroups, required = false)
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?, required: Boolean) {
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
            if (!required) setHint(R.string.accelerator_not_supplied)
            isEnabled = required || initial != null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        supplied[key] = required || initial != null
        label.labelFor = editor.id
        addView(label)
        if (!required) {
            val supply = CheckBox(activity).apply {
                id = View.generateViewId()
                isSaveEnabled = false
                text = activity.getString(R.string.accelerator_supply, activity.getString(title))
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
            supplies[key] = supply
            addView(supply)
        }
        addView(editor)
        addView(TextView(activity).apply { setText(rule) })
        editors[key] = editor
    }

    fun input() = PreviewAcceleratorInput(editors.getValue(PreviewAcceleratorSettings.DEVICES).text.toString(),
        if (supplied.getValue(PreviewAcceleratorSettings.GROUPS))
            editors.getValue(PreviewAcceleratorSettings.GROUPS).text.toString() else null)

    fun showIssues(issues: List<PreviewAcceleratorIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewAcceleratorProblem.REQUIRED -> R.string.accelerator_value_required
            PreviewAcceleratorProblem.INTEGER -> R.string.accelerator_integer_invalid
            PreviewAcceleratorProblem.RANGE -> if (issue.field == PreviewAcceleratorSettings.DEVICES)
                R.string.accelerator_device_count_rule else R.string.accelerator_groups_rule
            PreviewAcceleratorProblem.TOO_LONG -> R.string.accelerator_too_long
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
        fun readOnly(activity: Activity, input: PreviewAcceleratorInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.accelerator_device_count to input.deviceCount,
                R.string.accelerator_groups to input.tensorParallelGroups)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.accelerator_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
