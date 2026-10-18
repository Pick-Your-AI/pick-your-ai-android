package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import ai.pickyour.core.navigation.ExternalLinkPolicy
import ai.pickyour.core.navigation.LinkDecision
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.preview.PreviewComparedValue

/** Explicit navigation from one immutable selected detail; never fetches or changes a draft. */
internal class CatalogSourceNavigation(
    private val activity: Activity,
    private val detail: PreviewCatalogDetails,
    // Real source hosts require a reviewed application configuration, not a URI-derived allowlist.
    private val policy: ExternalLinkPolicy = ExternalLinkPolicy(emptySet()),
    private val isCurrent: () -> Boolean,
    private val launch: (Intent) -> Unit = { activity.startActivity(it) },
) {
    private var currentDialog: AlertDialog? = null

    private fun current() = !activity.isFinishing && !activity.isDestroyed && isCurrent()
    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing

    private fun present(dialog: AlertDialog) {
        if (!current()) return
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.show()
    }

    fun show() = showPage(0)

    private fun showPage(page: Int) {
        if (!current()) return
        if (detail.provenance.isEmpty()) {
            message(R.string.catalog_source_missing)
            return
        }
        val pages = (detail.provenance.size - 1) / PAGE_SIZE + 1
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val rows = detail.provenance.subList(start, minOf(start + PAGE_SIZE, detail.provenance.size)).mapIndexed { index, source ->
            val value = PreviewComparedValue.from(source.sourceUri)
            val text = value.quotedLiteral ?: activity.getString(R.string.catalog_source_missing)
            val displayed = if (value.truncated) activity.getString(R.string.catalog_detail_truncated, text) else text
            activity.getString(R.string.catalog_source_row, start + index + 1, displayed)
        }
        lateinit var dialog: AlertDialog
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.catalog_source_page, page + 1, pages, detail.provenance.size))
            .setItems(rows.toTypedArray()) { _, index ->
                if (current(dialog) && index in rows.indices) confirm(start + index)
            }
            .setNegativeButton(R.string.close, null)
        if (page + 1 < pages) builder.setPositiveButton(R.string.catalog_source_next) { _, _ ->
            if (current(dialog)) showPage(page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.catalog_source_previous) { _, _ ->
            if (current(dialog)) showPage(page - 1)
        }
        dialog = builder.create()
        present(dialog)
    }

    private fun confirm(index: Int) {
        if (!current()) return
        val raw = detail.provenance.getOrNull(index)?.sourceUri
        if (raw == null) {
            message(R.string.catalog_source_missing)
            return
        }
        val candidate = policy.inspect(raw) as? LinkDecision.Candidate
        if (candidate == null) {
            message(R.string.catalog_source_blocked)
            return
        }
        val destination = Uri.parse(candidate.link.uri.toString()).normalizeScheme()
        lateinit var dialog: AlertDialog
        var dispatched = false
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.catalog_source_confirm_title)
            .setMessage(activity.getString(R.string.catalog_source_confirm, index + 1,
                candidate.link.uri.host, destination.toString()))
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.catalog_source_open) { _, _ ->
                if (!current(dialog) || dispatched) return@setPositiveButton
                dispatched = true
                // Recheck the same exact source at the dispatch boundary; never derive a new URI.
                val accepted = policy.inspect(raw) as? LinkDecision.Candidate ?: return@setPositiveButton
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(accepted.link.uri.toString()).normalizeScheme())
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                try {
                    launch(intent)
                } catch (_: ActivityNotFoundException) {
                    message(R.string.catalog_source_unavailable)
                } catch (_: SecurityException) {
                    message(R.string.catalog_source_unavailable)
                }
            }.create()
        present(dialog)
    }

    private fun message(resource: Int) {
        if (!current()) return
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(activity).setTitle(R.string.catalog_sources)
            .setMessage(resource).setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.catalog_source_back) { _, _ ->
                if (current(dialog)) showPage(0)
            }.create()
        present(dialog)
    }

    private companion object { const val PAGE_SIZE = 8 }
}
