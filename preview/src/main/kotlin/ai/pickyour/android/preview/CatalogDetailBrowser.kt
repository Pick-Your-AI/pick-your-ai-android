package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.preview.PreviewCatalogField
import ai.pickyour.core.preview.PreviewCatalogValueState

/** Complete read-only access to one immutable recorded detail; no schema, price or source interpretation. */
internal class CatalogDetailBrowser(
    private val activity: Activity,
    private val detail: PreviewCatalogDetails,
    private val isCurrent: () -> Boolean,
    private val onClosed: () -> Unit = {},
) {
    private var currentDialog: AlertDialog? = null
    private var retired = false
    private val fields = Fields()

    private fun current(): Boolean {
        if (retired) return false
        if (activity.isDestroyed || activity.isFinishing || !isCurrent()) {
            retire()
            return false
        }
        return true
    }

    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing

    private fun present(dialog: AlertDialog) {
        if (!current()) return
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        // A deliberate page transition already installed its successor. Any other dismissal is terminal.
        dialog.setOnDismissListener { if (currentDialog === dialog) retire() }
        dialog.show()
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
    }

    fun show() = showFields(0)

    /** Closing the owner or any current dialog cannot lend its authority to a later inspection. */
    fun retire() {
        if (retired) return
        retired = true
        val previous = currentDialog
        currentDialog = null
        previous?.dismiss()
        onClosed()
    }

    private fun showFields(page: Int) {
        if (!current()) return
        val pages = (fields.size - 1) / PAGE_SIZE + 1
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val count = minOf(PAGE_SIZE, fields.size - start)
        // Rows contain only fixed schema/field names and local state labels. A value is inspected
        // explicitly, so hostile or very wide literals cannot turn a field row into an unbounded label.
        val rows = (start until start + count).map { index ->
            val field = fields.at(index)
            activity.getString(R.string.catalog_detail_browser_row, field.name, field.description(), field.stateText())
        }
        lateinit var dialog: AlertDialog
        val countLabel = activity.resources.getQuantityString(R.plurals.catalog_detail_browser_field_count, fields.size, fields.size)
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.catalog_detail_browser_page, page + 1, pages, countLabel))
            .setItems(rows.toTypedArray()) { _, index ->
                if (current(dialog) && index in rows.indices) showValue(start + index, 0)
            }
            .setNegativeButton(R.string.close, null)
        if (page + 1 < pages) builder.setPositiveButton(R.string.catalog_detail_browser_next_fields) { _, _ ->
            if (current(dialog)) showFields(page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.catalog_detail_browser_previous_fields) { _, _ ->
            if (current(dialog)) showFields(page - 1)
        }
        dialog = builder.create()
        present(dialog)
    }

    private fun showValue(index: Int, part: Int) {
        if (!current() || index !in 0 until fields.size) return
        val field = fields.at(index)
        val length = field.raw?.length ?: 0
        val parts = if (length == 0) 1 else (length - 1) / VALUE_PART_SIZE + 1
        if (part !in 0 until parts) return
        val from = part * VALUE_PART_SIZE
        val rawPart = field.raw?.substring(from, minOf(from + VALUE_PART_SIZE, length))
        val value = CatalogDetailText.literal(activity, rawPart)
        lateinit var dialog: AlertDialog
        val builder = AlertDialog.Builder(activity)
            .setTitle(field.name)
            .setMessage(activity.getString(R.string.catalog_detail_browser_value, field.description(), field.stateText(),
                part + 1, parts, value, activity.getString(R.string.catalog_detail_browser_limits)))
            .setNegativeButton(R.string.catalog_detail_browser_back_fields) { _, _ ->
                if (current(dialog)) showFields(index / PAGE_SIZE)
            }
        if (part + 1 < parts) builder.setPositiveButton(R.string.catalog_detail_browser_next_part) { _, _ ->
            if (current(dialog)) showValue(index, part + 1)
        }
        if (part > 0) builder.setNeutralButton(R.string.catalog_detail_browser_previous_part) { _, _ ->
            if (current(dialog)) showValue(index, part - 1)
        }
        dialog = builder.create()
        present(dialog)
    }

    private enum class Format { TEXT, INTEGER, JSON, PRESENTATION }

    private inner class Field(
        val name: String,
        val raw: String?,
        val format: Format = Format.TEXT,
        val type: String? = null,
        val state: PreviewCatalogValueState = if (raw == null) PreviewCatalogValueState.MISSING else PreviewCatalogValueState.SUPPLIED,
    ) {
        fun description(): String = when (format) {
            Format.TEXT -> activity.getString(R.string.catalog_detail_browser_text)
            Format.INTEGER -> activity.getString(R.string.catalog_detail_browser_integer)
            Format.JSON -> activity.getString(R.string.catalog_detail_browser_json, requireNotNull(type))
            Format.PRESENTATION -> activity.getString(R.string.catalog_detail_browser_presentation)
        }
        fun stateText(): String = if (format == Format.PRESENTATION) activity.getString(R.string.catalog_detail_browser_derived) else activity.getString(when (state) {
            PreviewCatalogValueState.MISSING -> R.string.catalog_detail_missing
            PreviewCatalogValueState.EXPLICIT_UNKNOWN -> R.string.catalog_detail_explicit_unknown
            PreviewCatalogValueState.EMPTY_COLLECTION -> R.string.catalog_detail_empty_collection
            PreviewCatalogValueState.CONTAINS_UNKNOWN -> R.string.catalog_detail_browser_contains_unknown
            PreviewCatalogValueState.SUPPLIED -> R.string.catalog_detail_browser_supplied
        })
    }

    /** Fixed metadata plus indexed immutable fields and provenance; no copied or truncated source collection. */
    private inner class Fields {
        private val fixed = listOf(
            Field("catalog_item.id", detail.item.id),
            Field("catalog_item.name", detail.item.name),
            Field("catalog_item.kind", detail.item.kind),
            Field("catalog_item.tier", detail.item.tier),
            Field("catalog_item.version", detail.item.version),
            Field("catalog_snapshot.id", detail.snapshotId),
            Field("catalog_snapshot.revision", detail.snapshotRevision.toString(), Format.INTEGER),
            Field("catalog_snapshot.captured_at", detail.snapshotCapturedAt),
            Field("catalog_snapshot.sha256", detail.snapshotHash),
            Field("catalog_item.updated_at", detail.updatedAt),
            Field("catalog_item.status", detail.status),
            Field("catalog_item.billing_class", detail.billingClass),
            Field("catalog_item.description", detail.description),
        )
        val size: Int get() = fixed.size + detail.specs.size + detail.license.size + detail.provenance.size * PROVENANCE_FIELDS

        private fun typed(prefix: String, value: PreviewCatalogField) =
            Field("$prefix.${value.name}", value.valueJson, Format.JSON, value.type, value.state)

        fun at(index: Int): Field {
            if (index < fixed.size) return fixed[index]
            var remaining = index - fixed.size
            if (remaining < detail.specs.size) return typed("catalog_item.spec", detail.specs[remaining])
            remaining -= detail.specs.size
            if (remaining < detail.license.size) return typed("catalog_item.license", detail.license[remaining])
            remaining -= detail.license.size
            val sourceIndex = remaining / PROVENANCE_FIELDS
            val source = detail.provenance[sourceIndex]
            val prefix = "catalog_item.provenance[$sourceIndex]"
            return when (remaining % PROVENANCE_FIELDS) {
                0 -> Field("$prefix.source_uri", source.sourceUri)
                1 -> Field("$prefix.checked_at", source.checkedAt)
                2 -> Field("$prefix.verification_status", source.rawStatus.encoded)
                3 -> Field(activity.getString(R.string.catalog_detail_browser_assessment, sourceIndex + 1),
                    activity.getString(when (source.label) {
                        EvidenceLabel.INDEPENDENTLY_VERIFIED -> R.string.label_independently_verified
                        EvidenceLabel.VERIFICATION_UNESTABLISHED -> R.string.label_verification_unestablished
                        EvidenceLabel.UNVERIFIED -> R.string.label_unverified
                        EvidenceLabel.USER_SUPPLIED -> R.string.label_user_supplied
                        EvidenceLabel.ESTIMATED -> R.string.label_estimated
                    }), Format.PRESENTATION)
                else -> Field("$prefix.notes", source.notes)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val PROVENANCE_FIELDS = 5
        // Match the shared literal bound; split surrogate units are escaped without dropping either unit.
        const val VALUE_PART_SIZE = 160
    }
}
