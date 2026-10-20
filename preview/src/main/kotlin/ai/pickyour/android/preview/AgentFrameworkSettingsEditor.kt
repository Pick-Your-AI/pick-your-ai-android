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

/** Native optional controls only; the existing worker and selection command own admission and saving. */
internal class AgentFrameworkSettingsEditor(
    private val activity: Activity,
    initial: PreviewAgentFrameworkInput,
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
        addView(TextView(activity).apply { setText(R.string.agent_framework_editor_guidance) })
        entry(PreviewAgentFrameworkSettings.AGENT_COUNT, R.string.agent_framework_agent_count, initial.agentCount)
        entry(PreviewAgentFrameworkSettings.MAX_ITERATIONS, R.string.agent_framework_max_iterations, initial.maxIterations)
        entry(PreviewAgentFrameworkSettings.TIMEOUT_MS, R.string.agent_framework_timeout_ms, initial.timeoutMs)
    }

    private fun entry(key: String, title: Int, initial: String?) {
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            // Retain literal JSON-number spelling and invalid text for explicit correction.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            hint = if (initial == null) activity.getString(R.string.agent_framework_not_supplied) else null
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
            text = activity.getString(R.string.agent_framework_supply, activity.getString(title))
            isChecked = initial != null
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                if (!checked) editor.setText("")
                editor.hint = if (checked) null else activity.getString(R.string.agent_framework_not_supplied)
                editor.error = null
                editor.isEnabled = checked
            }
        }
        label.labelFor = editor.id
        addView(label)
        addView(supply)
        addView(editor)
        addView(TextView(activity).apply { setText(R.string.agent_framework_integer_rule) })
        editors[key] = editor
        supplies[key] = supply
    }

    fun input(): PreviewAgentFrameworkInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewAgentFrameworkInput(text(PreviewAgentFrameworkSettings.AGENT_COUNT),
            text(PreviewAgentFrameworkSettings.MAX_ITERATIONS), text(PreviewAgentFrameworkSettings.TIMEOUT_MS))
    }

    fun showIssues(issues: List<PreviewAgentFrameworkIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) {
            val message = activity.getString(when (issue.problem) {
                PreviewAgentFrameworkProblem.REQUIRED -> R.string.agent_framework_value_required
                PreviewAgentFrameworkProblem.INTEGER -> R.string.agent_framework_integer_invalid
                PreviewAgentFrameworkProblem.RANGE -> R.string.agent_framework_integer_rule
                PreviewAgentFrameworkProblem.TOO_LONG -> R.string.agent_framework_too_long
            })
            editors[issue.field]?.error = message
        }
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
        fun readOnly(activity: Activity, input: PreviewAgentFrameworkInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.agent_framework_agent_count to input.agentCount,
                R.string.agent_framework_max_iterations to input.maxIterations,
                R.string.agent_framework_timeout_ms to input.timeoutMs)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = activity.getString(when {
                        value == null -> R.string.agent_framework_not_supplied
                        value.isEmpty() -> R.string.agent_framework_empty_value
                        else -> R.string.agent_framework_supplied_value
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
