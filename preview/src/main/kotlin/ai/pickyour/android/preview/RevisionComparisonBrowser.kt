package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.PreviewComparedValue
import ai.pickyour.core.preview.PreviewComparedValueKind
import ai.pickyour.core.preview.PreviewRevisionComparison
import ai.pickyour.core.preview.PreviewLibraryComparison
import ai.pickyour.core.preview.PreviewLibraryRevision

/** All changes and literal parts from one captured local comparison; never reads or writes a draft. */
internal class RevisionComparisonBrowser private constructor(
    private val activity: Activity,
    private val comparison: PreviewRevisionComparison,
    private val isCurrent: () -> Boolean,
    private val libraryComparison: PreviewLibraryComparison?,
) {
    constructor(activity: Activity, comparison: PreviewRevisionComparison, isCurrent: () -> Boolean) :
        this(activity, comparison, isCurrent, null)
    constructor(activity: Activity, comparison: PreviewLibraryComparison, isCurrent: () -> Boolean) :
        this(activity, comparison.comparison, isCurrent, comparison)
    private var currentDialog: AlertDialog? = null
    private var retired = false

    private fun current(): Boolean {
        if (retired) return false
        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) {
            retire()
            return false
        }
        return true
    }

    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing

    fun show() = showChanges(0)

    /** Cancellation, a new inspection or changed storage authority cannot later regain this context. */
    fun retire() {
        retired = true
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun literal(raw: String?): String {
        val value = PreviewComparedValue.from(raw)
        return when (value.kind) {
            PreviewComparedValueKind.ABSENT -> activity.getString(R.string.revision_compare_absent)
            PreviewComparedValueKind.EMPTY -> activity.getString(R.string.context_empty)
            PreviewComparedValueKind.TEXT -> {
                val text = activity.getString(R.string.revision_compare_text, value.quotedLiteral)
                if (value.truncated) activity.getString(R.string.revision_compare_truncated, text) else text
            }
        }
    }

    private fun showChanges(page: Int) {
        if (!current()) return
        val changes = comparison.changes
        val pages = maxOf(1, (changes.size + PAGE_SIZE - 1) / PAGE_SIZE)
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, changes.size)
        present {
            text(activity.getString(if (libraryComparison == null) R.string.revision_compare_scope
                else R.string.library_compare_scope))
            text(activity.getString(R.string.revision_compare_page, page + 1, pages,
                if (changes.isEmpty()) 0 else start + 1, end, changes.size))
            text(activity.getString(if (comparison.guestFieldsComparable) R.string.revision_compare_guest_known
                else R.string.revision_compare_guest_unknown))
            text(activity.getString(if (comparison.metadataChanged) R.string.revision_compare_metadata_changed
                else R.string.revision_compare_metadata_same))
            if (changes.isEmpty()) text(activity.getString(R.string.revision_compare_no_fields))
            for (index in start until end) {
                val change = changes[index]
                text(activity.getString(R.string.revision_compare_row, change.field,
                    change.key?.let { " [${literal(it)}]" }.orEmpty(), literal(change.before), literal(change.after)))
                action(activity.getString(R.string.revision_compare_inspect, index + 1, change.field)) { showChange(index) }
            }
            if (page + 1 < pages) action(R.string.revision_compare_next_changes) { showChanges(page + 1) }
            if (page > 0) action(R.string.revision_compare_previous_changes) { showChanges(page - 1) }
            action(R.string.revision_compare_close) { retire() }
        }
    }

    private fun showChange(index: Int) {
        if (!current()) return
        val change = comparison.changes.getOrNull(index) ?: return
        present {
            changeHeading(index)
            text(activity.getString(R.string.revision_compare_row, change.field,
                change.key?.let { " [${literal(it)}]" }.orEmpty(), literal(change.before), literal(change.after)))
            text(activity.getString(R.string.revision_compare_inspection_hint))
            if (change.key == null) text(activity.getString(R.string.revision_compare_no_key))
            else action(R.string.revision_compare_inspect_key) { showValue(index, Value.KEY, 0) }
            action(R.string.revision_compare_inspect_before) { showValue(index, Value.BEFORE, 0) }
            action(R.string.revision_compare_inspect_after) { showValue(index, Value.AFTER, 0) }
            action(R.string.revision_compare_back_changes) { showChanges(index / PAGE_SIZE) }
            action(R.string.revision_compare_close) { retire() }
        }
    }

    private fun showValue(index: Int, value: Value, part: Int) {
        if (!current()) return
        val change = comparison.changes.getOrNull(index) ?: return
        val raw = when (value) {
            Value.KEY -> change.key ?: return
            Value.BEFORE -> change.before
            Value.AFTER -> change.after
        }
        val length = raw?.length ?: 0
        val parts = maxOf(1, (length + VALUE_PART_SIZE - 1) / VALUE_PART_SIZE)
        if (part !in 0 until parts) return
        val start = part * VALUE_PART_SIZE
        val end = minOf(start + VALUE_PART_SIZE, length)
        present {
            changeHeading(index)
            text(activity.getString(value.label), heading = true)
            text(activity.getString(R.string.revision_compare_value_part, part + 1, parts,
                if (length == 0) 0 else start + 1, end, length))
            text(literal(raw?.substring(start, end)))
            text(activity.getString(R.string.revision_compare_literal_parts))
            if (part + 1 < parts) action(R.string.revision_compare_next_part) { showValue(index, value, part + 1) }
            if (part > 0) action(R.string.revision_compare_previous_part) { showValue(index, value, part - 1) }
            action(R.string.revision_compare_back_field) { showChange(index) }
            action(R.string.revision_compare_close) { retire() }
        }
    }

    /** A single scrollable column keeps wrapped labels and actions reachable at narrow/large-text sizes. */
    private inner class Page(private val dialog: AlertDialog) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        fun text(value: String, heading: Boolean = false) {
            content.addView(TextView(activity).apply {
                text = value
                textSize = if (heading) 20f else 16f
                setPadding(0, dp(6), 0, dp(6))
                if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
        }

        fun changeHeading(index: Int) {
            text(activity.getString(R.string.revision_compare_change, index + 1, comparison.changes.size,
                comparison.changes[index].field), heading = true)
        }

        fun source(label: Int, source: PreviewLibraryRevision) {
            text(activity.getString(label, source.draftId.value, source.revision.toString()), heading = true)
            text(activity.getString(R.string.library_compare_saved_title, literal(source.fields?.title)))
            text(activity.getString(R.string.library_compare_captured_history, source.capturedBaselineIdentity))
            if (source.earlierHistoryUnavailable) text(activity.getString(R.string.history_earlier_unavailable))
        }

        fun action(label: Int, command: () -> Unit) = action(activity.getString(label), command)

        fun action(label: String, command: () -> Unit) {
            content.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setSingleLine(false)
                minHeight = dp(48)
                minimumWidth = dp(48)
                setOnClickListener { if (current(dialog)) command() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun present(render: Page.() -> Unit) {
        if (!current()) return
        val dialog = AlertDialog.Builder(activity).create()
        val page = Page(dialog)
        if (libraryComparison == null) {
            page.text(activity.getString(R.string.revision_compare_title, comparison.from.toString(), comparison.to.toString()), heading = true)
        } else {
            page.text(activity.getString(R.string.library_compare_title), heading = true)
            page.source(R.string.library_compare_before_source, libraryComparison.before)
            page.source(R.string.library_compare_after_source, libraryComparison.after)
        }
        page.render()
        dialog.setView(ScrollView(activity).apply { addView(page.content) })
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener {
            if (currentDialog === dialog) {
                currentDialog = null
                retired = true
            }
        }
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
        dialog.show()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private enum class Value(val label: Int) {
        KEY(R.string.revision_compare_key), BEFORE(R.string.revision_compare_before), AFTER(R.string.revision_compare_after),
    }

    private companion object {
        const val PAGE_SIZE = 8
        // Every UTF16 code unit is preserved, including escaped surrogate halves at a part boundary.
        const val VALUE_PART_SIZE = 160
    }
}
