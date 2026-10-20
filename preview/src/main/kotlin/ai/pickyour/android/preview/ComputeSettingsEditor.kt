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

/** Native controls preserve literal input; admission and canonical saving remain on the existing worker. */
internal class ComputeSettingsEditor(
    private val activity: Activity,
    initial: PreviewComputeInput,
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
        addView(TextView(activity).apply { setText(R.string.compute_editor_guidance) })
        entry(PreviewComputeSettings.INSTANCES, R.string.compute_instances, R.string.compute_instances_rule, initial.instances)
        entry(PreviewComputeSettings.RESERVED_HOURS, R.string.compute_reserved_hours, R.string.compute_reserved_hours_rule, initial.reservedHoursPerMonth)
        entry(PreviewComputeSettings.REGION, R.string.compute_region, R.string.compute_region_rule, initial.region)
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        val required = PreviewComputeSettings.fields.single { it.key == key }.required
        check(!required || initial != null)
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            // Keep pasted JSON-number syntax and literal region whitespace available for correction.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            if (!required) setHint(R.string.compute_optional_value)
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
                text = activity.getString(R.string.compute_supply, activity.getString(title))
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

    fun input(): PreviewComputeInput {
        fun optional(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewComputeInput(editors.getValue(PreviewComputeSettings.INSTANCES).text.toString(),
            optional(PreviewComputeSettings.RESERVED_HOURS), optional(PreviewComputeSettings.REGION))
    }

    fun showIssues(issues: List<PreviewComputeIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) {
            val resource = when (issue.problem) {
                PreviewComputeProblem.REQUIRED -> if (issue.field == PreviewComputeSettings.INSTANCES)
                    R.string.compute_instances_required else R.string.compute_number_required
                PreviewComputeProblem.NUMBER -> R.string.compute_number_invalid
                PreviewComputeProblem.INTEGER -> R.string.compute_integer_invalid
                PreviewComputeProblem.RANGE -> if (issue.field == PreviewComputeSettings.INSTANCES)
                    R.string.compute_instances_rule else R.string.compute_reserved_hours_rule
                PreviewComputeProblem.TEXT -> R.string.compute_text_invalid
                PreviewComputeProblem.TOO_LONG -> R.string.compute_too_long
            }
            editors[issue.field]?.error = activity.getString(resource)
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
    }

    fun close() { closed = true; freeze(true) }

    companion object {
        fun readOnly(activity: Activity, input: PreviewComputeInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.compute_instances to input.instances,
                R.string.compute_reserved_hours to input.reservedHoursPerMonth, R.string.compute_region to input.region)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.compute_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
                if (value != null && label == R.string.compute_region) {
                    addView(TextView(activity).apply { setText(R.string.compute_region_supplied) })
                }
            }
        }
    }
}
