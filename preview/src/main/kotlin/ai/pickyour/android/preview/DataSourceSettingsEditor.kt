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

/** Optional native fields; only the existing complete-selection worker may admit or save the input. */
internal class DataSourceSettingsEditor(
    private val activity: Activity,
    initial: PreviewDataSourceInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var selectedEnabled = initial.enabled
    private lateinit var enabledChoice: Button
    private var picker: AlertDialog? = null
    private var frozen = false
    private var closed = false

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.data_source_editor_guidance) })
        entry(PreviewDataSourceSettings.CONNECTOR_SECRET_REF, R.string.data_source_reference,
            R.string.data_source_reference_rule, initial.connectorSecretRef)
        entry(PreviewDataSourceSettings.REFRESH_MINUTES, R.string.data_source_minutes,
            R.string.data_source_minutes_rule, initial.refreshMinutes)
        val label = TextView(activity).apply { setText(R.string.data_source_enabled) }
        enabledChoice = Button(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            setSingleLine(false)
            text = enabledLabel(activity, selectedEnabled)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf<Boolean?>(null, false, true)
                picker = showPreviewSingleChoiceDialog(activity, R.string.data_source_enabled,
                    values.map { enabledLabel(activity, it) }, values.indexOf(selectedEnabled),
                    isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                    selectedEnabled = values[index]
                    text = enabledLabel(activity, selectedEnabled)
                }
            }
        }
        label.labelFor = enabledChoice.id
        addView(label)
        addView(enabledChoice)
        addView(TextView(activity).apply { setText(R.string.data_source_enabled_rule) })
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        check(!PreviewDataSourceSettings.fields.single { it.key == key }.required)
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
            setHint(R.string.data_source_optional_value)
            setText(initial ?: "")
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
            text = activity.getString(R.string.data_source_supply, activity.getString(title))
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

    fun input(): PreviewDataSourceInput {
        fun value(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewDataSourceInput(value(PreviewDataSourceSettings.CONNECTOR_SECRET_REF),
            value(PreviewDataSourceSettings.REFRESH_MINUTES), selectedEnabled)
    }

    fun showIssues(issues: List<PreviewDataSourceIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewDataSourceProblem.REQUIRED -> R.string.data_source_value_required
            PreviewDataSourceProblem.INTEGER -> R.string.data_source_integer_invalid
            PreviewDataSourceProblem.RANGE -> R.string.data_source_minutes_rule
            PreviewDataSourceProblem.REFERENCE -> R.string.data_source_reference_rule
            PreviewDataSourceProblem.TOO_LONG -> R.string.data_source_too_long
        })
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        if (!enabled) { picker?.dismiss(); picker = null }
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        enabledChoice.isEnabled = enabled
    }

    fun close() { closed = true; freeze(true) }

    companion object {
        private fun enabledLabel(activity: Activity, value: Boolean?) = activity.getString(when (value) {
            null -> R.string.data_source_not_supplied
            false -> R.string.data_source_false
            true -> R.string.data_source_true
        })

        fun readOnly(activity: Activity, input: PreviewDataSourceInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.data_source_reference to input.connectorSecretRef,
                R.string.data_source_minutes to input.refreshMinutes,
                R.string.data_source_enabled to enabledLabel(activity, input.enabled))) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.data_source_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
