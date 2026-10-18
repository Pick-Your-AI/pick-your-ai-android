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
    private var baseline: PreviewStoredDraft? = null
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
                    val expected = baseline ?: return@setOnClickListener
                    val choices = Activation.entries
                    AlertDialog.Builder(context).setTitle(tier.name)
                        .setItems(choices.map { label(it) }.toTypedArray()) { _, index ->
                            val next = choices[index]
                            AlertDialog.Builder(context).setTitle(tier.name)
                                .setMessage(context.getString(R.string.tier_confirm, label(next)))
                                .setNegativeButton(R.string.keep_edits, null)
                                .setPositiveButton(R.string.tier_commit) { _, _ -> commit(expected, tier, next) }.show()
                        }.setNegativeButton(R.string.close, null).show()
                }
            }.also(::addView)
        }
    }
    fun update(record: PreviewStoredDraft?, editable: Boolean) {
        baseline = record
        buttons.forEach { (tier, button) ->
            val input = record?.optionalTiers?.getValue(tier)
            button.text = if (input == null) context.getString(R.string.tier_no_saved, tier.name)
                else context.getString(R.string.tier_state, tier.name, label(input.activation),
                    input.selections.size.toString(), input.settings.size.toString())
            button.isEnabled = editable && record != null
        }
    }
    private fun label(value: Activation) = context.getString(when (value) {
        Activation.ENABLED -> R.string.tier_enabled
        Activation.DISABLED -> R.string.tier_disabled
        Activation.UNKNOWN -> R.string.tier_unknown
    })
}
