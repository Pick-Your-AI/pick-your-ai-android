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

/** Explicit planning inputs; validation and acknowledged persistence stay with the existing worker. */
internal class CostPolicySettingsEditor(
    private val activity: Activity,
    initial: PreviewCostPolicyInput,
    private val isCurrent: () -> Boolean,
) : LinearLayout(activity) {
    private val editors = linkedMapOf<String, EditText>()
    private val supplies = linkedMapOf<String, CheckBox>()
    private val supplied = linkedMapOf<String, Boolean>()
    private val cap = Button(activity)
    private var selectedCap = initial.enforceHardCap
    private var frozen = false
    private var closed = false
    private var picker: AlertDialog? = null

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        addView(TextView(activity).apply { setText(R.string.cost_policy_editor_guidance) })
        entry(PreviewCostPolicySettings.MONTHLY, R.string.cost_policy_monthly, R.string.cost_policy_amount_rule, initial.monthlyBudgetAmount)
        entry(PreviewCostPolicySettings.PER_REQUEST, R.string.cost_policy_per_request, R.string.cost_policy_amount_rule, initial.perRequestBudgetAmount)
        val label = TextView(activity).apply { setText(R.string.cost_policy_hard_cap) }
        cap.id = View.generateViewId()
        cap.isSaveEnabled = false
        cap.setSingleLine(false)
        cap.text = capLabel(activity, selectedCap)
        cap.setOnClickListener {
            if (closed || frozen || !isCurrent()) return@setOnClickListener
            picker?.dismiss()
            val values = listOf<Boolean?>(null, false, true)
            picker = showPreviewSingleChoiceDialog(activity, R.string.cost_policy_hard_cap,
                values.map { capLabel(activity, it) }, values.indexOf(selectedCap),
                isCurrent = { !closed && !frozen && isCurrent() }) { index ->
                selectedCap = values[index]
                cap.text = capLabel(activity, selectedCap)
            }
        }
        label.labelFor = cap.id
        addView(label)
        addView(cap)
        addView(TextView(activity).apply { setText(R.string.cost_policy_cap_rule) })
        entry(PreviewCostPolicySettings.NOTIFY, R.string.cost_policy_notify, R.string.cost_policy_notify_rule, initial.notifyAtPct)
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
            // Literal text must survive unchanged, including already admitted terminal LF in amounts.
            minLines = 1
            maxLines = 4
            setText(initial ?: "")
            setHint(R.string.cost_policy_not_supplied)
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
            text = activity.getString(R.string.cost_policy_supply, activity.getString(title))
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

    fun input(): PreviewCostPolicyInput {
        fun text(key: String) = if (supplied.getValue(key)) editors.getValue(key).text.toString() else null
        return PreviewCostPolicyInput(text(PreviewCostPolicySettings.MONTHLY), text(PreviewCostPolicySettings.PER_REQUEST),
            selectedCap, text(PreviewCostPolicySettings.NOTIFY))
    }

    fun showIssues(issues: List<PreviewCostPolicyIssue>) {
        editors.values.forEach { it.error = null }
        for (issue in issues) editors[issue.field]?.error = activity.getString(when (issue.problem) {
            PreviewCostPolicyProblem.REQUIRED -> R.string.cost_policy_value_required
            PreviewCostPolicyProblem.DECIMAL_STRING -> R.string.cost_policy_amount_rule
            PreviewCostPolicyProblem.NUMBER -> R.string.cost_policy_number_invalid
            PreviewCostPolicyProblem.RANGE -> R.string.cost_policy_notify_rule
            PreviewCostPolicyProblem.TOO_LONG -> R.string.cost_policy_too_long
        })
    }

    fun freeze(value: Boolean) {
        frozen = value
        if (value) picker?.dismiss()
        val enabled = !closed && !value && isCurrent()
        supplies.values.forEach { it.isEnabled = enabled }
        editors.forEach { (key, editor) -> editor.isEnabled = enabled && supplied.getValue(key) }
        cap.isEnabled = enabled
    }

    fun close() {
        closed = true
        picker?.dismiss()
        picker = null
        freeze(true)
    }

    companion object {
        private fun capLabel(activity: Activity, value: Boolean?): String = activity.getString(when (value) {
            null -> R.string.cost_policy_not_supplied
            false -> R.string.cost_policy_no
            true -> R.string.cost_policy_yes
        })

        fun readOnly(activity: Activity, input: PreviewCostPolicyInput) = LinearLayout(activity).apply {
            orientation = VERTICAL
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val values = listOf(R.string.cost_policy_monthly to input.monthlyBudgetAmount,
                R.string.cost_policy_per_request to input.perRequestBudgetAmount,
                R.string.cost_policy_hard_cap to capLabel(activity, input.enforceHardCap),
                R.string.cost_policy_notify to input.notifyAtPct)
            for ((label, value) in values) {
                addView(TextView(activity).apply { setText(label) })
                addView(TextView(activity).apply {
                    text = value ?: activity.getString(R.string.cost_policy_not_supplied)
                    setTextIsSelectable(true)
                    isSaveEnabled = false
                })
            }
        }
    }
}
