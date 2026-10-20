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

/** Explicit participant input; complete Selection validation and the managed store retain authority. */
internal class StorageSettingsEditor(
    private val activity: Activity,
    initial: PreviewStorageInput,
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
        addView(TextView(activity).apply { setText(R.string.storage_editor_guidance) })
        entry(PreviewStorageSettings.PROVISIONED, R.string.storage_provisioned,
            R.string.storage_provisioned_rule, initial.provisionedGb)
        entry(PreviewStorageSettings.REGION, R.string.storage_region,
            R.string.storage_region_rule, initial.region)
        entry(PreviewStorageSettings.ENCRYPTION_KEY, R.string.storage_encryption_key,
            R.string.storage_encryption_key_rule, initial.encryptionKeyRef)
    }

    private fun entry(key: String, title: Int, rule: Int, initial: String?) {
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            // A text keyboard retains exact JSON-number spelling and literal region/reference characters.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            hint = if (initial == null) activity.getString(R.string.storage_not_supplied) else null
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
            text = activity.getString(R.string.storage_supply, activity.getString(title))
            isChecked = initial != null
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                if (!checked) editor.setText("")
                editor.hint = if (checked) null else activity.getString(R.string.storage_not_supplied)
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

    fun input(): PreviewStorageInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewStorageInput(text(PreviewStorageSettings.PROVISIONED), text(PreviewStorageSettings.REGION),
            text(PreviewStorageSettings.ENCRYPTION_KEY))
    }

    fun showIssues(issues: List<PreviewStorageIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) {
            val message = activity.getString(when (issue.problem) {
                PreviewStorageProblem.REQUIRED -> R.string.storage_required
                PreviewStorageProblem.NUMBER -> R.string.storage_number_invalid
                PreviewStorageProblem.RANGE -> R.string.storage_range_invalid
                PreviewStorageProblem.TEXT -> R.string.storage_text_invalid
                PreviewStorageProblem.REFERENCE -> R.string.storage_reference_invalid
                PreviewStorageProblem.TOO_LONG -> R.string.storage_too_long
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
        fun readOnly(activity: Activity, input: PreviewStorageInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val values = listOf(R.string.storage_provisioned to input.provisionedGb,
                R.string.storage_region to input.region, R.string.storage_encryption_key to input.encryptionKeyRef)
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = activity.getString(when {
                        value == null -> R.string.storage_not_supplied
                        value.isEmpty() -> R.string.storage_empty_value
                        else -> R.string.storage_supplied_value
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
