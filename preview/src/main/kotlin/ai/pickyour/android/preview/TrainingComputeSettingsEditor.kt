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
internal class TrainingComputeSettingsEditor(
    private val activity: Activity,
    initial: PreviewTrainingComputeInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var selectedSpotAllowed = initial.spotAllowed
    private lateinit var spotChoice: Button
    private var picker: AlertDialog? = null
    private var frozen = false
    private var closed = false
    private val touchMinimum = kotlin.math.ceil(48.0 * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.training_compute_editor_guidance) })
        entry(PreviewTrainingComputeSettings.GPU_HOURS_BUDGET, R.string.training_compute_gpu_hours,
            R.string.training_compute_hours_rule, initial.gpuHoursBudget)
        entry(PreviewTrainingComputeSettings.CHECKPOINT_INTERVAL_MINUTES, R.string.training_compute_checkpoint,
            R.string.training_compute_checkpoint_rule, initial.checkpointIntervalMinutes)
        val label = TextView(activity).apply { setText(R.string.training_compute_spot) }
        spotChoice = Button(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            setSingleLine(false)
            minimumHeight = touchMinimum
            minHeight = touchMinimum
            text = spotLabel(activity, selectedSpotAllowed)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf<Boolean?>(null, false, true)
                picker = showPreviewSingleChoiceDialog(activity, R.string.training_compute_spot,
                    values.map { spotLabel(activity, it) }, values.indexOf(selectedSpotAllowed),
                    isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                    selectedSpotAllowed = values[index]
                    text = spotLabel(activity, selectedSpotAllowed)
                }
            }
        }
        label.labelFor = spotChoice.id
        addView(label)
        addView(spotChoice)
        addView(TextView(activity).apply { setText(R.string.training_compute_spot_rule) })
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        check(!PreviewTrainingComputeSettings.fields.single { it.key == key }.required)
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
            setHint(R.string.training_compute_optional_value)
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
            text = activity.getString(R.string.training_compute_supply, activity.getString(title))
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

    fun input(): PreviewTrainingComputeInput {
        fun value(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewTrainingComputeInput(value(PreviewTrainingComputeSettings.GPU_HOURS_BUDGET),
            value(PreviewTrainingComputeSettings.CHECKPOINT_INTERVAL_MINUTES), selectedSpotAllowed)
    }

    fun showIssues(issues: List<PreviewTrainingComputeIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewTrainingComputeProblem.REQUIRED -> R.string.training_compute_value_required
            PreviewTrainingComputeProblem.INTEGER -> R.string.training_compute_integer_invalid
            PreviewTrainingComputeProblem.RANGE -> if (issue.field == PreviewTrainingComputeSettings.GPU_HOURS_BUDGET)
                R.string.training_compute_hours_rule else R.string.training_compute_checkpoint_rule
            PreviewTrainingComputeProblem.NUMBER -> R.string.training_compute_number_invalid
            PreviewTrainingComputeProblem.TOO_LONG -> R.string.training_compute_too_long
        })
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        if (!enabled) { picker?.dismiss(); picker = null }
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        spotChoice.isEnabled = enabled
    }

    fun close() { closed = true; freeze(true) }

    companion object {
        private fun spotLabel(activity: Activity, value: Boolean?) = activity.getString(when (value) {
            null -> R.string.training_compute_not_supplied
            false -> R.string.training_compute_false
            true -> R.string.training_compute_true
        })

        fun readOnly(activity: Activity, input: PreviewTrainingComputeInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.training_compute_gpu_hours to input.gpuHoursBudget,
                R.string.training_compute_checkpoint to input.checkpointIntervalMinutes,
                R.string.training_compute_spot to spotLabel(activity, input.spotAllowed))) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.training_compute_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
