package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.*

/** Local four-field view. It owns no JSON parser, record, transaction, or replacement save token. */
internal class ContextPolicySettingsEditor(
    private val activity: Activity,
    input: PreviewContextPolicyInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val numbers = linkedMapOf<String, EditText>()
    private val choices = linkedMapOf<String, Button>()
    private val selected = linkedMapOf<String, String?>()
    private val choiceErrors = linkedMapOf<String, TextView>()
    private var frozen = false
    private var closed = false
    private var picker: AlertDialog? = null

    init {
        orientation = VERTICAL
        addView(TextView(activity).apply { setText(R.string.context_policy_editor_guidance) })
        number(PreviewContextPolicySettings.REQUESTED, R.string.context_policy_requested, input.requestedContextTokens)
        number(PreviewContextPolicySettings.RESERVED, R.string.context_policy_reserved, input.reservedOutputTokens)
        choice(PreviewContextPolicySettings.TRUNCATION, R.string.context_policy_truncation, input.truncationStrategy)
        choice(PreviewContextPolicySettings.PRECISION, R.string.context_policy_precision, input.kvCachePrecision)
    }

    private fun number(key: String, title: Int, raw: String) {
        val definition = PreviewContextPolicySettings.fields.single { it.key == key }
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            setText(raw)
            isSaveEnabled = false
            // Keep malformed/localized text intact for explicit validation and bounded recovery.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        label.labelFor = editor.id
        addView(label)
        addView(editor)
        addView(TextView(activity).apply {
            text = activity.getString(R.string.context_policy_integer_rule, key, requireNotNull(definition.minimum).toPlainString())
        })
        numbers[key] = editor
    }

    private fun choice(key: String, title: Int, initial: String?) {
        val definition = PreviewContextPolicySettings.fields.single { it.key == key }
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
            text = initial ?: activity.getString(R.string.context_policy_not_supplied)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf(activity.getString(R.string.context_policy_not_supplied)) + definition.enumValues
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
        addView(TextView(activity).apply { text = activity.getString(R.string.context_policy_choice_rule, key) })
        addView(error)
        choices[key] = control
        choiceErrors[key] = error
    }

    fun input() = PreviewContextPolicyInput(
        numbers.getValue(PreviewContextPolicySettings.REQUESTED).text.toString(),
        numbers.getValue(PreviewContextPolicySettings.RESERVED).text.toString(),
        selected[PreviewContextPolicySettings.TRUNCATION],
        selected[PreviewContextPolicySettings.PRECISION],
    )

    fun showIssues(issues: List<PreviewSettingIssue>) {
        numbers.values.forEach { it.error = null }
        choiceErrors.values.forEach { it.visibility = View.GONE }
        for (issue in issues) {
            val message = when (issue.problem) {
                PreviewSettingProblem.REQUIRED -> activity.getString(R.string.context_policy_required)
                PreviewSettingProblem.INTEGER -> activity.getString(R.string.context_policy_integer)
                PreviewSettingProblem.MINIMUM -> activity.getString(R.string.context_policy_minimum,
                    PreviewContextPolicySettings.fields.single { it.key == issue.field }.minimum!!.toPlainString())
                PreviewSettingProblem.ENUM -> activity.getString(R.string.context_policy_enum)
                PreviewSettingProblem.TOO_LONG -> activity.getString(R.string.context_policy_too_long)
            }
            numbers[issue.field]?.error = message
            choiceErrors[issue.field]?.let { it.text = message; it.visibility = View.VISIBLE }
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        if (value) picker?.dismiss()
        numbers.values.forEach { it.isEnabled = !closed && !value && isCurrent() }
        choices.values.forEach { it.isEnabled = !closed && !value && isCurrent() }
    }

    fun close() {
        closed = true
        picker?.dismiss()
        picker = null
        freeze(true)
    }

    companion object {
        fun readOnly(activity: Activity, input: PreviewContextPolicyInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            val values = listOf(
                R.string.context_policy_requested to input.requestedContextTokens,
                R.string.context_policy_reserved to input.reservedOutputTokens,
                R.string.context_policy_truncation to input.truncationStrategy,
                R.string.context_policy_precision to input.kvCachePrecision,
            )
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.context_policy_not_supplied)
                    setTextIsSelectable(true)
                })
            }
        }
    }
}
