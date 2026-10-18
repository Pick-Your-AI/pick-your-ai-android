package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.*

/** One read-only captured library listing and explicit pair; no active-draft or persistence writes. */
internal class LibraryRevisionComparison(
    private val activity: Activity,
    private val execute: (() -> Unit) -> Unit,
    private val readLibrary: () -> PreviewLibraryRead,
    private val isCurrent: () -> Boolean,
) {
    private var started = false
    private var retired = false
    private var currentDialog: AlertDialog? = null
    private var browser: RevisionComparisonBrowser? = null

    private fun current(): Boolean {
        if (retired) return false
        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) {
            retire()
            return false
        }
        return true
    }
    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing

    fun show() {
        if (started || !current()) return
        started = true
        val loading = present(R.string.library_compare_open) { text(R.string.library_compare_loading) } ?: return
        execute {
            val result = try { readLibrary() } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (current(loading)) {
                    val entries = (result as? PreviewLibraryRead.Available)?.entries
                    when {
                        entries == null -> present(R.string.library_compare_open) { text(R.string.library_compare_failed) }
                        entries.isEmpty() -> present(R.string.library_compare_open) { text(R.string.library_empty) }
                        else -> chooseDraft(entries, null, 0)
                    }
                }
            }
        }
    }

    fun retire() {
        retired = true
        currentDialog?.dismiss()
        currentDialog = null
        browser?.retire()
        browser = null
    }

    private fun chooseDraft(entries: List<PreviewLibraryEntry>, before: PreviewLibraryRevision?, page: Int) {
        present(if (before == null) R.string.library_compare_choose_before else R.string.library_compare_choose_after) {
            text(R.string.library_compare_capture_scope)
            before?.let { source(R.string.library_compare_before_source, it) }
            choices(entries, page, { chooseDraft(entries, before, it) }) { entry ->
                val record = (entry.state as? PreviewDraftLoad.Available)?.record
                text(activity.getString(R.string.library_local_id, entry.id.value), heading = true)
                if (entry.deletionPending || record == null) {
                    text(R.string.library_compare_unavailable)
                } else {
                    text(activity.getString(R.string.library_compare_current_title, literal(record.fields.title)))
                    action(activity.getString(R.string.library_compare_pick_draft, entry.id.value)) {
                        chooseRevision(entries, entry, before, 0)
                    }
                }
            }
            if (before != null) action(R.string.library_compare_change_before) { chooseDraft(entries, null, 0) }
        }
    }

    private fun chooseRevision(entries: List<PreviewLibraryEntry>, entry: PreviewLibraryEntry,
        before: PreviewLibraryRevision?, page: Int) {
        val record = (entry.state as? PreviewDraftLoad.Available)?.record ?: return
        if (entry.deletionPending) return
        present(activity.getString(R.string.library_compare_choose_revision, entry.id.value)) {
            before?.let { source(R.string.library_compare_before_source, it) }
            text(activity.getString(R.string.library_compare_captured_history, record.baselineIdentity))
            if (record.earlierHistoryUnavailable) text(R.string.history_earlier_unavailable)
            choices(record.history.reversed(), page, { chooseRevision(entries, entry, before, it) }) { revision ->
                text(activity.getString(R.string.history_revision, revision.revision.toString()), heading = true)
                text(activity.getString(R.string.library_compare_saved_title, literal(revision.fields?.title)))
                action(activity.getString(R.string.library_compare_pick_revision, revision.revision.toString())) {
                    val source = PreviewLibraryRevision.capture(entry, revision.revision)
                    if (source == null) present(R.string.library_compare_open) { text(R.string.library_compare_source_unavailable) }
                    else if (before == null) chooseDraft(entries, source, 0)
                    else prepare(before, source)
                }
            }
            action(R.string.library_compare_back_drafts) { chooseDraft(entries, before, 0) }
            if (before != null) action(R.string.library_compare_change_before) { chooseDraft(entries, null, 0) }
        }
    }

    private fun prepare(before: PreviewLibraryRevision, after: PreviewLibraryRevision) {
        val preparing = present(R.string.library_compare_title) {
            source(R.string.library_compare_before_source, before)
            source(R.string.library_compare_after_source, after)
            text(R.string.library_compare_preparing)
        } ?: return
        execute {
            val result = before.compareWith(after)
            activity.runOnUiThread {
                if (current(preparing)) {
                    if (result == null) present(R.string.library_compare_open) { text(R.string.library_compare_source_unavailable) }
                    else {
                        currentDialog = null
                        preparing.dismiss()
                        browser = RevisionComparisonBrowser(activity, result, ::current).also { it.show() }
                    }
                }
            }
        }
    }

    private fun literal(raw: String?): String {
        val value = PreviewComparedValue.from(raw)
        return when (value.kind) {
            PreviewComparedValueKind.ABSENT -> activity.getString(R.string.revision_compare_absent)
            PreviewComparedValueKind.EMPTY -> activity.getString(R.string.context_empty)
            PreviewComparedValueKind.TEXT -> activity.getString(R.string.revision_compare_text, value.quotedLiteral)
        }
    }

    private inner class Page(private val dialog: AlertDialog) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        fun text(label: Int) = text(activity.getString(label))
        fun text(value: String, heading: Boolean = false) {
            content.addView(TextView(activity).apply {
                text = value
                textSize = if (heading) 20f else 16f
                setPadding(0, dp(6), 0, dp(6))
                if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
        }
        fun source(label: Int, source: PreviewLibraryRevision) {
            text(activity.getString(label, source.draftId.value, source.revision.toString()), heading = true)
            text(activity.getString(R.string.library_compare_saved_title, literal(source.fields?.title)))
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
        fun <T> choices(items: List<T>, page: Int, move: (Int) -> Unit, render: Page.(T) -> Unit) {
            val pages = maxOf(1, (items.size + PAGE_SIZE - 1) / PAGE_SIZE)
            if (page !in 0 until pages) return
            val start = page * PAGE_SIZE
            val end = minOf(start + PAGE_SIZE, items.size)
            text(activity.getString(R.string.library_compare_choices, page + 1, pages,
                if (items.isEmpty()) 0 else start + 1, end, items.size))
            for (index in start until end) render(items[index])
            if (page + 1 < pages) action(R.string.library_compare_next_choices) { move(page + 1) }
            if (page > 0) action(R.string.library_compare_previous_choices) { move(page - 1) }
        }
    }

    private fun present(label: Int, render: Page.() -> Unit) = present(activity.getString(label), render)
    private fun present(label: String, render: Page.() -> Unit): AlertDialog? {
        if (!current()) return null
        val dialog = AlertDialog.Builder(activity).create()
        val page = Page(dialog)
        page.text(label, heading = true)
        page.render()
        page.action(R.string.revision_compare_close) { retire() }
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
        return dialog
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    private companion object { const val PAGE_SIZE = 8 }
}
