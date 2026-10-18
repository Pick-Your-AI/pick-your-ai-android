package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.Context

/** Native checked-choice semantics; opening the dialog never dispatches an edit. */
internal fun showPreviewSingleChoiceDialog(
    context: Context,
    title: Int,
    labels: List<String>,
    checkedIndex: Int,
    isCurrent: () -> Boolean,
    select: (Int) -> Unit,
): AlertDialog {
    val items = labels.toTypedArray()
    require(items.isNotEmpty() && checkedIndex in items.indices)
    var dispatched = false
    return AlertDialog.Builder(context).setTitle(title)
        .setSingleChoiceItems(items, checkedIndex) { opened, index ->
            val dialog = opened as? AlertDialog ?: return@setSingleChoiceItems
            if (!dialog.isShowing || dispatched || index !in items.indices) return@setSingleChoiceItems
            if (!isCurrent()) {
                // The native list checks a clicked row before its callback. Retire
                // stale presentation instead of showing an unapplied choice.
                dialog.dismiss()
                return@setSingleChoiceItems
            }
            dispatched = true
            select(index)
            // Single-choice lists do not dismiss automatically like action lists.
            dialog.dismiss()
        }.setNegativeButton(R.string.close, null).show()
}
