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
internal class EvaluationSuiteSettingsEditor(
    private val activity: Activity,
    initial: PreviewEvaluationSuiteInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var selectedGateRequired = initial.gateRequired
    private lateinit var gateChoice: Button
    private var picker: AlertDialog? = null
    private var frozen = false
    private var closed = false
    private val touchMinimum = kotlin.math.ceil(48.0 * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.evaluation_suite_editor_guidance) })
        entry(PreviewEvaluationSuiteSettings.MINIMUM_SCORE, R.string.evaluation_suite_score,
            R.string.evaluation_suite_number_rule, initial.minimumScore)
        entry(PreviewEvaluationSuiteSettings.DATASET_SIZE, R.string.evaluation_suite_dataset,
            R.string.evaluation_suite_dataset_rule, initial.datasetSize)
        val label = TextView(activity).apply { setText(R.string.evaluation_suite_gate) }
        gateChoice = Button(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            setSingleLine(false)
            minimumHeight = touchMinimum
            minHeight = touchMinimum
            text = gateLabel(activity, selectedGateRequired)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf<Boolean?>(null, false, true)
                picker = showPreviewSingleChoiceDialog(activity, R.string.evaluation_suite_gate,
                    values.map { gateLabel(activity, it) }, values.indexOf(selectedGateRequired),
                    isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                    selectedGateRequired = values[index]
                    text = gateLabel(activity, selectedGateRequired)
                }
            }
        }
        label.labelFor = gateChoice.id
        addView(label)
        addView(gateChoice)
        addView(TextView(activity).apply { setText(R.string.evaluation_suite_gate_rule) })
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        check(!PreviewEvaluationSuiteSettings.fields.single { it.key == key }.required)
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minimumHeight = touchMinimum
            minHeight = touchMinimum
            minLines = 1
            maxLines = 4
            setHint(R.string.evaluation_suite_optional_value)
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
            text = activity.getString(R.string.evaluation_suite_supply, activity.getString(title))
            isChecked = initial != null
            minimumHeight = touchMinimum
            minHeight = touchMinimum
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

    fun input(): PreviewEvaluationSuiteInput {
        fun value(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewEvaluationSuiteInput(value(PreviewEvaluationSuiteSettings.MINIMUM_SCORE),
            value(PreviewEvaluationSuiteSettings.DATASET_SIZE), selectedGateRequired)
    }

    fun showIssues(issues: List<PreviewEvaluationSuiteIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewEvaluationSuiteProblem.REQUIRED -> R.string.evaluation_suite_value_required
            PreviewEvaluationSuiteProblem.INTEGER -> R.string.evaluation_suite_integer_invalid
            PreviewEvaluationSuiteProblem.RANGE -> R.string.evaluation_suite_dataset_rule
            PreviewEvaluationSuiteProblem.NUMBER -> R.string.evaluation_suite_number_invalid
            PreviewEvaluationSuiteProblem.TOO_LONG -> R.string.evaluation_suite_too_long
        })
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        if (!enabled) { picker?.dismiss(); picker = null }
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        gateChoice.isEnabled = enabled
    }

    fun close() { closed = true; freeze(true) }

    companion object {
        private fun gateLabel(activity: Activity, value: Boolean?) = activity.getString(when (value) {
            null -> R.string.evaluation_suite_not_supplied
            false -> R.string.evaluation_suite_false
            true -> R.string.evaluation_suite_true
        })

        fun readOnly(activity: Activity, input: PreviewEvaluationSuiteInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.evaluation_suite_score to input.minimumScore,
                R.string.evaluation_suite_dataset to input.datasetSize,
                R.string.evaluation_suite_gate to gateLabel(activity, input.gateRequired))) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.evaluation_suite_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
