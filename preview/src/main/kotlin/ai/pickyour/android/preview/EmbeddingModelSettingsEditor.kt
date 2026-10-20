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

/** Optional native controls. Canonical selection admission and the managed worker own saving. */
internal class EmbeddingModelSettingsEditor(
    private val activity: Activity,
    initial: PreviewEmbeddingModelInput,
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
        addView(TextView(activity).apply { setText(R.string.embedding_model_editor_guidance) })
        entry(PreviewEmbeddingModelSettings.CREDENTIAL, R.string.embedding_model_credential,
            R.string.embedding_model_credential_rule, initial.credentialRef)
        entry(PreviewEmbeddingModelSettings.DIMENSIONS, R.string.embedding_model_dimensions,
            R.string.embedding_model_dimensions_rule, initial.outputDimensions)
        entry(PreviewEmbeddingModelSettings.REGION, R.string.embedding_model_region,
            R.string.embedding_model_region_rule, initial.region)
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
            // Literal region text and the accepted credential final LF must survive native editing.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            setHint(R.string.embedding_model_not_supplied)
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
            text = activity.getString(R.string.embedding_model_supply, activity.getString(title))
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

    fun input(): PreviewEmbeddingModelInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewEmbeddingModelInput(text(PreviewEmbeddingModelSettings.CREDENTIAL),
            text(PreviewEmbeddingModelSettings.DIMENSIONS), text(PreviewEmbeddingModelSettings.REGION))
    }

    fun showIssues(issues: List<PreviewEmbeddingModelIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) {
            editors[issue.field]?.error = activity.getString(when (issue.problem) {
                PreviewEmbeddingModelProblem.REQUIRED -> R.string.embedding_model_value_required
                PreviewEmbeddingModelProblem.INTEGER -> R.string.embedding_model_integer_invalid
                PreviewEmbeddingModelProblem.RANGE -> R.string.embedding_model_dimensions_rule
                PreviewEmbeddingModelProblem.REFERENCE -> R.string.embedding_model_reference_invalid
                PreviewEmbeddingModelProblem.TEXT -> R.string.embedding_model_text_invalid
                PreviewEmbeddingModelProblem.TOO_LONG -> R.string.embedding_model_too_long
            })
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
        fun readOnly(activity: Activity, input: PreviewEmbeddingModelInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.embedding_model_credential to input.credentialRef,
                R.string.embedding_model_dimensions to input.outputDimensions, R.string.embedding_model_region to input.region)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = when (value) {
                        null -> activity.getString(R.string.embedding_model_not_supplied)
                        "" -> activity.getString(R.string.embedding_model_supplied_empty)
                        else -> value
                    }
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
