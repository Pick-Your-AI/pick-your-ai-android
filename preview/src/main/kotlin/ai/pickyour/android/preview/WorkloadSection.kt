package ai.pickyour.android.preview

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.drafts.DraftDataClassification
import ai.pickyour.core.preview.PreviewWorkloadCheckpoint
import ai.pickyour.core.preview.PreviewWorkloadInputs
import ai.pickyour.core.preview.PreviewObjectiveGuidance
import ai.pickyour.core.preview.RequiredObjectiveInput

/** User-entered planning assumptions only; no catalog, placement, compatibility or cost verdict. */
class WorkloadSection(context: Context, initial: PreviewWorkloadInputs,
    private val alive: () -> Boolean, private val changed: () -> Unit) : LinearLayout(context) {
    var inputs: PreviewWorkloadInputs = initial
        private set
    private var applying = false
    private var editable = false
    private val editors = mutableListOf<EditText>()
    private val classification: Button
    private val state: TextView
    private val objectiveGuidance: TextView
    private val nextObjective: Button

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            setText(R.string.workload_heading); textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        addView(TextView(context).apply { setText(R.string.workload_limits) })
        val labels = intArrayOf(R.string.workload_region, R.string.workload_currency, R.string.workload_budget,
            R.string.workload_requests, R.string.workload_input_tokens, R.string.workload_output_tokens,
            R.string.workload_concurrency, R.string.workload_latency)
        labels.forEachIndexed { index, label ->
            val caption = TextView(context).apply { setText(label) }
            addView(caption)
            val editor = EditText(context).apply {
                id = View.generateViewId(); isSaveEnabled = false; setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setHint(R.string.workload_unanswered)
                setText(values(inputs)[index])
                filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                    if (!alive()) dest.subSequence(dstart, dend)
                    else if (dest.length - (dend - dstart) + (end - start) <= MAX_RAW_LENGTH) null
                    else {
                        // Reject the whole replacement, never keep a valid-looking prefix.
                        inputs = inputs.copy(rejectedEdit = true)
                        post { if (alive()) { updateState(); changed() } }
                        dest.subSequence(dstart, dend)
                    }
                })
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun afterTextChanged(s: Editable?) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (!alive() || applying) return
                        val value = s.toString()
                        if (value == values(inputs)[index]) return
                        inputs = inputs.copy(rejectedEdit = false)
                        inputs = when(index) {
                            0 -> inputs.copy(region = value); 1 -> inputs.copy(currency = value)
                            2 -> inputs.copy(budget = value); 3 -> inputs.copy(requests = value)
                            4 -> inputs.copy(inputTokens = value); 5 -> inputs.copy(outputTokens = value)
                            6 -> inputs.copy(concurrency = value); else -> inputs.copy(latency = value)
                        }
                        updateState(); changed()
                    }
                })
            }
            caption.labelFor = editor.id
            editors.add(editor); addView(editor)
        }
        classification = Button(context).apply {
            setOnClickListener {
                if (!alive() || !editable) return@setOnClickListener
                val expected = inputs
                val choices = listOf<DraftDataClassification?>(null) + DraftDataClassification.entries
                showPreviewSingleChoiceDialog(context, R.string.workload_classification,
                    choices.map { classificationLabel(it) }, choices.indexOf(inputs.classification),
                    { alive() && editable && inputs === expected }) { index ->
                    inputs = inputs.copy(classification = choices[index]); updateState(); changed()
                }
            }
        }
        addView(classification)
        state = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        addView(state)
        objectiveGuidance = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        addView(objectiveGuidance)
        nextObjective = Button(context).apply {
            setText(R.string.objective_next_input)
            setOnClickListener {
                if (!alive() || !editable) return@setOnClickListener
                val next = (PreviewObjectiveGuidance.assess(inputs) as? PreviewObjectiveGuidance.Missing)?.inputs?.firstOrNull()
                when (next) {
                    RequiredObjectiveInput.REGION -> editors[0].requestFocus()
                    RequiredObjectiveInput.CURRENCY -> editors[1].requestFocus()
                    RequiredObjectiveInput.DATA_CLASSIFICATION -> classification.performClick()
                    null -> Unit
                }
            }
        }
        addView(nextObjective)
        updateState()
    }

    fun apply(value: PreviewWorkloadInputs) {
        applying = true
        inputs = value
        editors.zip(values(value)).forEach { (editor, text) -> editor.setText(text) }
        applying = false
        updateState()
    }
    fun setEditable(value: Boolean) {
        editable = value
        editors.forEach { it.isEnabled = value }; classification.isEnabled = value
        updateState()
    }
    private fun classificationLabel(value: DraftDataClassification?): String = context.getString(when (value) {
        null -> R.string.workload_unanswered
        DraftDataClassification.PUBLIC -> R.string.workload_classification_public
        DraftDataClassification.INTERNAL -> R.string.workload_classification_internal
        DraftDataClassification.CONFIDENTIAL -> R.string.workload_classification_confidential
        DraftDataClassification.RESTRICTED -> R.string.workload_classification_restricted
    })
    private fun updateState() {
        classification.text = context.getString(R.string.workload_classification_value, classificationLabel(inputs.classification))
        state.setText(when {
            inputs.rejectedEdit -> R.string.workload_rejected
            inputs.parsed() == null -> R.string.workload_invalid
            else -> R.string.workload_editable
        })
        val guidance = PreviewObjectiveGuidance.assess(inputs)
        objectiveGuidance.text = when (guidance) {
            PreviewObjectiveGuidance.InvalidInputs -> context.getString(R.string.objective_inputs_invalid)
            PreviewObjectiveGuidance.Answered -> context.getString(R.string.objective_inputs_answered)
            is PreviewObjectiveGuidance.Missing -> context.getString(R.string.objective_inputs_missing,
                guidance.inputs.joinToString(", ") { context.getString(when (it) {
                    RequiredObjectiveInput.REGION -> R.string.workload_region
                    RequiredObjectiveInput.CURRENCY -> R.string.workload_currency
                    RequiredObjectiveInput.DATA_CLASSIFICATION -> R.string.workload_classification
                }) })
        }
        nextObjective.isEnabled = editable && guidance is PreviewObjectiveGuidance.Missing
    }
    fun save(bundle: Bundle) {
        bundle.putBoolean("workloadInputsPresent", true)
        bundle.putBoolean("workload.rejectedEdit", inputs.rejectedEdit)
        values(inputs).forEachIndexed { index, value -> bundle.putString("workload.$index", value) }
        bundle.putString("workload.classification", inputs.classification?.encoded)
    }
    companion object {
        private const val MAX_RAW_LENGTH = 16_385
        private fun values(v: PreviewWorkloadInputs) = listOf(v.region, v.currency, v.budget, v.requests,
            v.inputTokens, v.outputTokens, v.concurrency, v.latency)
        fun restore(bundle: Bundle?): PreviewWorkloadCheckpoint {
            fun value(index: Int) = bundle?.getString("workload.$index").orEmpty()
            val classification = DraftDataClassification.entries.find { it.encoded == bundle?.getString("workload.classification") }
            return PreviewWorkloadCheckpoint(bundle?.getBoolean("workloadInputsPresent", false) == true,
                PreviewWorkloadInputs(value(0), value(1), value(2), value(3), value(4), value(5), value(6), value(7), classification, bundle?.getBoolean("workload.rejectedEdit", false) == true))
        }
    }
}
