package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.preview.PreviewStoredDraft

/** Local activation editing only. No catalog capability, evaluation or cost is inferred. */
class OptionalTierSection(context: Context,
    private val commit: (PreviewStoredDraft, OptionalTier, Activation) -> Unit,
) : LinearLayout(context) {
    private var closed = false
    private var editable = false
    private var savedBaseline: PreviewStoredDraft? = null
    private var currentDialog: AlertDialog? = null
    private val buttons = linkedMapOf<OptionalTier, Button>()
    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            setText(R.string.tier_heading); textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        addView(TextView(context).apply { setText(R.string.tier_limits) })
        OptionalTier.entries.forEach { tier ->
            buttons[tier] = Button(context).apply {
                setOnClickListener {
                    if (closed || !editable) return@setOnClickListener
                    val expected = savedBaseline ?: return@setOnClickListener
                    val choices = Activation.entries
                    val checked = choices.indexOf(expected.optionalTiers.getValue(tier).activation)
                    lateinit var picker: AlertDialog
                    picker = AlertDialog.Builder(context).setTitle(tier.name)
                        .setSingleChoiceItems(choices.map { label(it) }.toTypedArray(), checked) { _, index ->
                            if (!current(picker, expected)) return@setSingleChoiceItems
                            val next = choices[index]
                            lateinit var confirmation: AlertDialog
                            confirmation = AlertDialog.Builder(context).setTitle(tier.name)
                                .setMessage(context.getString(R.string.tier_confirm, label(next)))
                                .setNegativeButton(R.string.keep_edits, null)
                                .setPositiveButton(R.string.tier_commit) { _, _ ->
                                    if (!current(confirmation, expected)) return@setPositiveButton
                                    dismissCurrent()
                                    commit(expected, tier, next)
                                }.create()
                            present(confirmation)
                        }.setNegativeButton(R.string.close, null).create()
                    present(picker)
                }
            }.also(::addView)
        }
    }
    fun update(record: PreviewStoredDraft?, editable: Boolean) {
        if (closed) return
        if (!editable || savedBaseline !== record) dismissCurrent()
        this.editable = editable
        savedBaseline = record
        buttons.forEach { (tier, button) ->
            val input = record?.optionalTiers?.getValue(tier)
            button.text = if (input == null) context.getString(R.string.tier_no_saved, tier.name)
                else context.getString(R.string.tier_state, tier.name, label(input.activation),
                    input.selections.size.toString(), input.settings.size.toString())
            button.isEnabled = editable && record != null
        }
    }
    fun close() {
        dismissCurrent()
        closed = true
        editable = false
        savedBaseline = null
        buttons.values.forEach { it.isEnabled = false }
    }

    private fun current(dialog: AlertDialog, expected: PreviewStoredDraft) =
        !closed && editable && savedBaseline === expected && currentDialog === dialog && dialog.isShowing

    private fun present(dialog: AlertDialog) {
        dismissCurrent()
        currentDialog = dialog
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.show()
    }

    private fun dismissCurrent() {
        val previous = currentDialog
        currentDialog = null
        previous?.dismiss()
    }

    private fun label(value: Activation) = context.getString(when (value) {
        Activation.ENABLED -> R.string.tier_enabled
        Activation.DISABLED -> R.string.tier_disabled
        Activation.UNKNOWN -> R.string.tier_unknown
    })
}
