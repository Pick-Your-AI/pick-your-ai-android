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
internal class RagPipelineSettingsEditor(
    private val activity: Activity,
    initial: PreviewRagPipelineInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var rerankSupplied = initial.rerankEnabled != null
    private var rerankValue = initial.rerankEnabled ?: false
    private val rerankControl: CheckBox
    private val rerankSupply: CheckBox
    private var frozen = false
    private var closed = false

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.rag_pipeline_editor_guidance) })
        entry(PreviewRagPipelineSettings.TOP_K, R.string.rag_pipeline_top_k, initial.topK)
        entry(PreviewRagPipelineSettings.MAX_CHUNKS, R.string.rag_pipeline_max_chunks, initial.maxChunks)
        val label = TextView(activity).apply { setText(R.string.rag_pipeline_rerank_enabled) }
        rerankControl = CheckBox(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(R.string.rag_pipeline_boolean_value)
            isChecked = rerankValue
            isEnabled = rerankSupplied
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent() || !rerankSupplied) {
                    isChecked = rerankValue
                    return@setOnCheckedChangeListener
                }
                rerankValue = checked
                error = null
            }
        }
        rerankSupply = CheckBox(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            text = activity.getString(R.string.rag_pipeline_supply,
                activity.getString(R.string.rag_pipeline_rerank_enabled))
            isChecked = rerankSupplied
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = rerankSupplied
                    return@setOnCheckedChangeListener
                }
                rerankSupplied = checked
                if (!checked) {
                    rerankValue = false
                    rerankControl.isChecked = false
                }
                rerankControl.error = null
                rerankControl.isEnabled = checked
            }
        }
        label.labelFor = rerankControl.id
        addView(label)
        addView(rerankSupply)
        addView(rerankControl)
        addView(TextView(activity).apply { setText(R.string.rag_pipeline_rerank_rule) })
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
            hint = if (initial == null) activity.getString(R.string.rag_pipeline_not_supplied) else null
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
            text = activity.getString(R.string.rag_pipeline_supply, activity.getString(title))
            isChecked = initial != null
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                if (!checked) editor.setText("")
                editor.hint = if (checked) null else activity.getString(R.string.rag_pipeline_not_supplied)
                editor.error = null
                editor.isEnabled = checked
            }
        }
        label.labelFor = editor.id
        addView(label)
        addView(supply)
        addView(editor)
        addView(TextView(activity).apply { setText(R.string.rag_pipeline_integer_rule) })
        editors[key] = editor
        supplies[key] = supply
    }

    fun input(): PreviewRagPipelineInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewRagPipelineInput(text(PreviewRagPipelineSettings.TOP_K),
            text(PreviewRagPipelineSettings.MAX_CHUNKS), if (rerankSupplied) rerankValue else null)
    }

    fun showIssues(issues: List<PreviewRagPipelineIssue>) {
        editors.values.forEach { it.error = null }
        rerankControl.error = null
        for (issue in issues) {
            val message = activity.getString(when (issue.problem) {
                PreviewRagPipelineProblem.REQUIRED -> R.string.rag_pipeline_value_required
                PreviewRagPipelineProblem.INTEGER -> R.string.rag_pipeline_integer_invalid
                PreviewRagPipelineProblem.RANGE -> R.string.rag_pipeline_integer_rule
                PreviewRagPipelineProblem.TOO_LONG -> R.string.rag_pipeline_too_long
            })
            editors[issue.field]?.error = message
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        rerankSupply.isEnabled = enabled
        rerankControl.isEnabled = enabled && rerankSupplied
    }

    fun close() {
        closed = true
        freeze(true)
    }

    companion object {
        fun readOnly(activity: Activity, input: PreviewRagPipelineInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.rag_pipeline_top_k to input.topK,
                R.string.rag_pipeline_max_chunks to input.maxChunks)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = activity.getString(when {
                        value == null -> R.string.rag_pipeline_not_supplied
                        value.isEmpty() -> R.string.rag_pipeline_empty_value
                        else -> R.string.rag_pipeline_supplied_value
                    })
                    isSaveEnabled = false
                })
                if (!value.isNullOrEmpty()) addView(TextView(activity).apply {
                    text = value
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
            addView(TextView(activity).apply { setText(R.string.rag_pipeline_rerank_enabled) })
            addView(TextView(activity).apply {
                text = activity.getString(when (input.rerankEnabled) {
                    null -> R.string.rag_pipeline_not_supplied
                    false -> R.string.rag_pipeline_supplied_false
                    true -> R.string.rag_pipeline_supplied_true
                })
                isSaveEnabled = false
            })
        }
    }
}
