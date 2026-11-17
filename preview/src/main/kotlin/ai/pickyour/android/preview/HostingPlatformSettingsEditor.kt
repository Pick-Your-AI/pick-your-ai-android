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

/** Declared native input only; whole-Selection validation and the managed store retain authority. */
internal class HostingPlatformSettingsEditor(
    private val activity: Activity,
    initial: PreviewHostingPlatformInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private var network = initial.networkIsolation
    private val networkChoice = Button(activity)
    private val networkError = TextView(activity)
    private var picker: AlertDialog? = null
    private var frozen = false
    private var closed = false

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.hosting_platform_editor_guidance) })
        entry(PreviewHostingPlatformSettings.REGION, R.string.hosting_platform_region,
            R.string.hosting_platform_region_rule, initial.region)
        entry(PreviewHostingPlatformSettings.ACCOUNT, R.string.hosting_platform_account,
            R.string.hosting_platform_account_rule, initial.accountRef)
        addNetworkChoice()
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
            // Preserve literal whitespace and the frozen reference pattern's admitted final LF.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            hint = if (initial == null) activity.getString(R.string.hosting_platform_not_supplied) else null
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
            text = activity.getString(R.string.hosting_platform_supply, activity.getString(title))
            isChecked = initial != null
            setOnCheckedChangeListener { _, checked ->
                if (closed || frozen || !isCurrent()) {
                    isChecked = supplied.getValue(key)
                    return@setOnCheckedChangeListener
                }
                supplied[key] = checked
                if (!checked) editor.setText("")
                editor.hint = if (checked) null else activity.getString(R.string.hosting_platform_not_supplied)
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

    private fun addNetworkChoice() {
        val definition = PreviewHostingPlatformSettings.fields.single { it.key == PreviewHostingPlatformSettings.NETWORK }
        require(network == null || network in definition.enumValues)
        val label = TextView(activity).apply { setText(R.string.hosting_platform_network) }
        networkError.visibility = View.GONE
        networkError.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        networkChoice.apply {
            id = View.generateViewId()
            isSaveEnabled = false
            text = network ?: activity.getString(R.string.hosting_platform_not_supplied)
            setOnClickListener {
                if (closed || frozen || !isCurrent()) return@setOnClickListener
                picker?.dismiss()
                val values = listOf(activity.getString(R.string.hosting_platform_not_supplied)) + definition.enumValues
                val checked = network?.let { definition.enumValues.indexOf(it) + 1 } ?: 0
                picker = showPreviewSingleChoiceDialog(activity, R.string.hosting_platform_network, values, checked,
                    isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                    network = if (index == 0) null else definition.enumValues[index - 1]
                    text = values[index]
                    networkError.visibility = View.GONE
                }
            }
        }
        label.labelFor = networkChoice.id
        addView(label)
        addView(networkChoice)
        addView(TextView(activity).apply { setText(R.string.hosting_platform_network_rule) })
        addView(networkError)
    }

    fun input(): PreviewHostingPlatformInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewHostingPlatformInput(text(PreviewHostingPlatformSettings.REGION),
            text(PreviewHostingPlatformSettings.ACCOUNT), network)
    }

    fun showIssues(issues: List<PreviewHostingPlatformIssue>) {
        editors.values.forEach { it.error = null }
        networkError.visibility = View.GONE
        for (issue in issues) {
            val message = activity.getString(when (issue.problem) {
                PreviewHostingPlatformProblem.TEXT -> R.string.hosting_platform_text_invalid
                PreviewHostingPlatformProblem.REFERENCE -> R.string.hosting_platform_reference_invalid
                PreviewHostingPlatformProblem.ENUM -> R.string.hosting_platform_enum_invalid
                PreviewHostingPlatformProblem.TOO_LONG -> R.string.hosting_platform_too_long
            })
            editors[issue.field]?.error = message
            if (issue.field == PreviewHostingPlatformSettings.NETWORK) {
                networkError.text = message
                networkError.visibility = View.VISIBLE
            }
        }
    }

    fun freeze(value: Boolean) {
        frozen = value
        if (value) picker?.dismiss()
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        networkChoice.isEnabled = enabled
    }

    fun close() {
        closed = true
        picker?.dismiss()
        picker = null
        freeze(true)
    }

    companion object {
        fun readOnly(activity: Activity, input: PreviewHostingPlatformInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val values = listOf(R.string.hosting_platform_region to input.region,
                R.string.hosting_platform_account to input.accountRef,
                R.string.hosting_platform_network to input.networkIsolation)
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = activity.getString(when {
                        value == null -> R.string.hosting_platform_not_supplied
                        value.isEmpty() -> R.string.hosting_platform_empty_value
                        else -> R.string.hosting_platform_supplied_value
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
