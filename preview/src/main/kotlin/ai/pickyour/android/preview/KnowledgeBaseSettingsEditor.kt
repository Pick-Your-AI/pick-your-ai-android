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

/** Native controls retain literal input; the existing worker owns admission and managed saving. */
internal class KnowledgeBaseSettingsEditor(
    private val activity: Activity,
    initial: PreviewKnowledgeBaseInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private val acl = CheckBox(activity)
    private var aclValue = initial.aclEnforced ?: false
    private var frozen = false
    private var closed = false

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.knowledge_base_editor_guidance) })
        entry(PreviewKnowledgeBaseSettings.CHUNK_SIZE, R.string.knowledge_base_chunk_size,
            R.string.knowledge_base_positive_rule, initial.chunkSizeTokens)
        entry(PreviewKnowledgeBaseSettings.CHUNK_OVERLAP, R.string.knowledge_base_chunk_overlap,
            R.string.knowledge_base_overlap_rule, initial.chunkOverlapTokens)
        entry(PreviewKnowledgeBaseSettings.REFRESH, R.string.knowledge_base_refresh,
            R.string.knowledge_base_positive_rule, initial.refreshMinutes)
        booleanEntry(initial.aclEnforced)
    }

    private fun supply(key: String, title: Int, present: Boolean, changed: (Boolean) -> Unit): CheckBox {
        supplied[key] = present
        return CheckBox(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            text = activity.getString(R.string.knowledge_base_supply, activity.getString(title))
            isChecked = present
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                changed(checked)
            }
        }.also { supplies[key] = it; addView(it) }
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        val definition = PreviewKnowledgeBaseSettings.fields.single { it.key == key }
        check(!definition.required && definition.type == PreviewSettingType.INTEGER)
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 1; maxLines = 4
            setText(initial ?: "")
            setHint(R.string.knowledge_base_optional_value)
            isEnabled = initial != null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { error = null }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        label.labelFor = editor.id
        addView(label)
        supply(key, title, initial != null) { checked ->
            if (!checked) editor.setText("")
            editor.error = null
            editor.isEnabled = checked
        }
        addView(editor)
        addView(TextView(activity).apply { setText(rule) })
        editors[key] = editor
    }

    private fun booleanEntry(initial: Boolean?) {
        val definition = PreviewKnowledgeBaseSettings.fields.single { it.key == PreviewKnowledgeBaseSettings.ACL }
        check(!definition.required && definition.type == PreviewSettingType.BOOLEAN)
        acl.id = View.generateViewId()
        acl.isSaveEnabled = false
        acl.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        acl.setText(R.string.knowledge_base_acl_value)
        acl.isChecked = aclValue
        acl.isEnabled = initial != null
        acl.setOnCheckedChangeListener { _, checked ->
            if (closed || frozen || !isCurrent() || !supplied.getValue(PreviewKnowledgeBaseSettings.ACL)) {
                acl.isChecked = aclValue
                return@setOnCheckedChangeListener
            }
            aclValue = checked
        }
        addView(TextView(activity).apply { setText(R.string.knowledge_base_acl); labelFor = acl.id })
        supply(PreviewKnowledgeBaseSettings.ACL, R.string.knowledge_base_acl, initial != null) { checked ->
            if (!checked) { aclValue = false; acl.isChecked = false }
            acl.isEnabled = checked
        }
        addView(acl)
        addView(TextView(activity).apply { setText(R.string.knowledge_base_acl_rule) })
    }

    fun input(): PreviewKnowledgeBaseInput {
        fun optional(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewKnowledgeBaseInput(optional(PreviewKnowledgeBaseSettings.CHUNK_SIZE),
            optional(PreviewKnowledgeBaseSettings.CHUNK_OVERLAP), optional(PreviewKnowledgeBaseSettings.REFRESH),
            if (supplied.getValue(PreviewKnowledgeBaseSettings.ACL)) aclValue else null)
    }

    fun showIssues(issues: List<PreviewKnowledgeBaseIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) {
            val resource = when (issue.problem) {
                PreviewKnowledgeBaseProblem.REQUIRED -> R.string.knowledge_base_number_required
                PreviewKnowledgeBaseProblem.INTEGER -> R.string.knowledge_base_integer_invalid
                PreviewKnowledgeBaseProblem.RANGE -> if (issue.field == PreviewKnowledgeBaseSettings.CHUNK_OVERLAP)
                    R.string.knowledge_base_overlap_rule else R.string.knowledge_base_positive_rule
                PreviewKnowledgeBaseProblem.TOO_LONG -> R.string.knowledge_base_too_long
            }
            editors[issue.field]?.error = activity.getString(resource)
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        acl.isEnabled = enabled && supplied.getValue(PreviewKnowledgeBaseSettings.ACL)
    }

    fun close() { closed = true; freeze(true) }

    companion object {
        fun readOnly(activity: Activity, input: PreviewKnowledgeBaseInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            for ((label, value) in listOf(R.string.knowledge_base_chunk_size to input.chunkSizeTokens,
                R.string.knowledge_base_chunk_overlap to input.chunkOverlapTokens,
                R.string.knowledge_base_refresh to input.refreshMinutes)) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.knowledge_base_not_supplied)
                    setTextIsSelectable(true); isSaveEnabled = false
                })
            }
            addView(TextView(activity).apply { setText(R.string.knowledge_base_acl) })
            addView(TextView(activity).apply {
                setText(when (input.aclEnforced) {
                    null -> R.string.knowledge_base_not_supplied
                    true -> R.string.knowledge_base_acl_true
                    false -> R.string.knowledge_base_acl_false
                })
                isSaveEnabled = false
            })
            addView(TextView(activity).apply { setText(R.string.knowledge_base_acl_rule) })
        }
    }
}
