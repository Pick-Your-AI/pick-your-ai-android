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

/** Native optional controls only. The worker and canonical selection command own admission and saving. */
internal class ModelSettingsEditor(
    private val activity: Activity,
    initial: PreviewModelInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private val choices = linkedMapOf<String, Button>()
    private val selected = linkedMapOf<String, String?>()
    private val choiceErrors = linkedMapOf<String, TextView>()
    private var frozen = false
    private var closed = false
    private var picker: AlertDialog? = null

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.model_editor_guidance) })
        entry(PreviewModelSettings.TEMPERATURE, R.string.model_temperature, R.string.model_temperature_rule, initial.temperature)
        entry(PreviewModelSettings.TOP_P, R.string.model_top_p, R.string.model_top_p_rule, initial.topP)
        entry(PreviewModelSettings.MAX_OUTPUT, R.string.model_max_output, R.string.model_max_output_rule, initial.maxOutputTokens)
        choice(PreviewModelSettings.PRECISION, R.string.model_precision, initial.precision)
        choice(PreviewModelSettings.SERVING_MODE, R.string.model_serving_mode, initial.servingMode)
        entry(PreviewModelSettings.CREDENTIAL, R.string.model_credential, R.string.model_credential_rule, initial.credentialRef)
        entry(PreviewModelSettings.WEIGHTS, R.string.model_weights, R.string.model_weights_rule, initial.weightsUri)
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
            // Multiline preserves literal accepted text, including the frozen reference pattern's final LF.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            setHint(R.string.model_not_supplied)
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
            text = activity.getString(R.string.model_supply, activity.getString(title))
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

    private fun choice(key: String, title: Int, initial: String?) {
        val definition = PreviewModelSettings.fields.single { it.key == key }
        require(initial == null || initial in definition.enumValues)
        selected[key] = initial
        val label = TextView(activity).apply { setText(title) }
        val error = TextView(activity).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val control = Button(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            setSingleLine(false)
            text = initial ?: activity.getString(R.string.model_not_supplied)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf(activity.getString(R.string.model_not_supplied)) + definition.enumValues
                val checked = selected[key]?.let { definition.enumValues.indexOf(it) + 1 } ?: 0
                picker = showPreviewSingleChoiceDialog(activity, title, values, checked,
                    isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                    selected[key] = if (index == 0) null else definition.enumValues[index - 1]
                    text = values[index]
                    error.visibility = View.GONE
                }
            }
        }
        label.labelFor = control.id
        addView(label)
        addView(control)
        addView(TextView(activity).apply { text = activity.getString(R.string.model_choice_rule, key) })
        addView(error)
        choices[key] = control
        choiceErrors[key] = error
    }

    fun input(): PreviewModelInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewModelInput(text(PreviewModelSettings.TEMPERATURE), text(PreviewModelSettings.TOP_P),
            text(PreviewModelSettings.MAX_OUTPUT), selected[PreviewModelSettings.PRECISION],
            selected[PreviewModelSettings.SERVING_MODE], text(PreviewModelSettings.CREDENTIAL), text(PreviewModelSettings.WEIGHTS))
    }

    fun showIssues(issues: List<PreviewModelIssue>) {
        editors.values.forEach { it.error = null }
        choiceErrors.values.forEach { it.visibility = View.GONE }
        for (issue in issues) {
            val message = activity.getString(when (issue.problem) {
                PreviewModelProblem.REQUIRED -> R.string.model_value_required
                PreviewModelProblem.NUMBER -> R.string.model_number_invalid
                PreviewModelProblem.INTEGER -> R.string.model_integer_invalid
                PreviewModelProblem.RANGE -> when (issue.field) {
                    PreviewModelSettings.TEMPERATURE -> R.string.model_temperature_rule
                    PreviewModelSettings.TOP_P -> R.string.model_top_p_rule
                    else -> R.string.model_max_output_rule
                }
                PreviewModelProblem.ENUM -> R.string.model_choice_invalid
                PreviewModelProblem.REFERENCE -> R.string.model_reference_invalid
                PreviewModelProblem.URI -> R.string.model_uri_invalid
                PreviewModelProblem.TOO_LONG -> R.string.model_too_long
            })
            editors[issue.field]?.error = message
            choiceErrors[issue.field]?.let { it.text = message; it.visibility = View.VISIBLE }
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        if (value) picker?.dismiss()
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        choices.values.forEach { it.isEnabled = enabled }
    }

    fun close() {
        closed = true
        picker?.dismiss()
        picker = null
        freeze(true)
    }

    companion object {
        fun readOnly(activity: Activity, input: PreviewModelInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val values = listOf(R.string.model_temperature to input.temperature, R.string.model_top_p to input.topP,
                R.string.model_max_output to input.maxOutputTokens, R.string.model_precision to input.precision,
                R.string.model_serving_mode to input.servingMode, R.string.model_credential to input.credentialRef,
                R.string.model_weights to input.weightsUri)
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.model_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
