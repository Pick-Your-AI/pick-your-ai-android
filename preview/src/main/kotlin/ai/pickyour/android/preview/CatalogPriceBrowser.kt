package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.preview.PreviewCatalogDetails
import ai.pickyour.core.preview.PreviewComparedValue
import ai.pickyour.core.preview.PreviewComparedValueKind
import ai.pickyour.core.pricing.RecordedPriceRate
import ai.pickyour.core.pricing.RecordedPricebook

/** Read-only inspection of all retained rates, fields and literal parts in one exact immutable context. */
internal class CatalogPriceBrowser(
    private val activity: Activity,
    private val detail: PreviewCatalogDetails,
    private val book: RecordedPricebook?,
    private val isCurrent: () -> Boolean,
) {
    private val rates = book?.ratesFor(detail.item.id, detail.item.version).orEmpty()
    private var currentDialog: AlertDialog? = null
    private var retired = false

    private fun current() = !retired && !activity.isFinishing && !activity.isDestroyed && isCurrent()
    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing

    private fun present(dialog: AlertDialog) {
        if (!current()) return
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
        dialog.show()
    }

    fun show() = showRates(0)

    /** A new detail or Activity teardown permanently ends this inspection, even with unchanged data. */
    fun retire() {
        retired = true
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun text(raw: String?): String {
        val value = PreviewComparedValue.from(raw)
        val literal = when (value.kind) {
            PreviewComparedValueKind.ABSENT -> activity.getString(R.string.catalog_detail_missing)
            PreviewComparedValueKind.EMPTY -> "\"\""
            PreviewComparedValueKind.TEXT -> value.quotedLiteral!!
        }
        return if (value.truncated) activity.getString(R.string.catalog_detail_truncated, literal) else literal
    }

    private fun showRates(page: Int) {
        if (!current()) return
        if (book == null || rates.isEmpty()) {
            val resource = if (book == null) R.string.catalog_price_unavailable else R.string.catalog_price_no_record
            present(AlertDialog.Builder(activity).setTitle(R.string.catalog_price_browse)
                .setMessage(resource).setNegativeButton(R.string.close, null).create())
            return
        }
        val pages = (rates.size - 1) / PAGE_SIZE + 1
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val rows = rates.subList(start, minOf(start + PAGE_SIZE, rates.size)).mapIndexed { index, rate ->
            activity.getString(R.string.catalog_price_browser_row, start + index + 1, text(rate.id),
                rate.meter, text(rate.unitPrice.amount.encoded), rate.unitPrice.currency, rate.priceType)
        }
        lateinit var dialog: AlertDialog
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.catalog_price_browser_page, page + 1, pages, rates.size))
            .setItems(rows.toTypedArray()) { _, index ->
                if (current(dialog) && index in rows.indices) showFields(start + index, 0)
            }
            .setNegativeButton(R.string.close, null)
        if (page + 1 < pages) builder.setPositiveButton(R.string.catalog_price_next_rates) { _, _ ->
            if (current(dialog)) showRates(page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.catalog_price_previous_rates) { _, _ ->
            if (current(dialog)) showRates(page - 1)
        }
        dialog = builder.create()
        present(dialog)
    }

    private fun showFields(rateIndex: Int, page: Int) {
        if (!current()) return
        val rate = rates.getOrNull(rateIndex) ?: return
        val fields = Fields(rate)
        val pages = (fields.size - 1) / PAGE_SIZE + 1
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val count = minOf(PAGE_SIZE, fields.size - start)
        val rows = (start until start + count).map { index ->
            val field = fields.at(index)
            activity.getString(R.string.catalog_price_field_row, field.name, field.display())
        }
        lateinit var dialog: AlertDialog
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.catalog_price_fields_page, rateIndex + 1, rates.size, page + 1, pages))
            .setItems(rows.toTypedArray()) { _, index ->
                if (current(dialog) && index in rows.indices) showValue(rateIndex, start + index, 0)
            }
            .setNegativeButton(R.string.catalog_price_back_rates) { _, _ ->
                if (current(dialog)) showRates(rateIndex / PAGE_SIZE)
            }
        if (page + 1 < pages) builder.setPositiveButton(R.string.catalog_price_next_fields) { _, _ ->
            if (current(dialog)) showFields(rateIndex, page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.catalog_price_previous_fields) { _, _ ->
            if (current(dialog)) showFields(rateIndex, page - 1)
        }
        dialog = builder.create()
        present(dialog)
    }

    private fun showValue(rateIndex: Int, fieldIndex: Int, part: Int) {
        if (!current()) return
        val rate = rates.getOrNull(rateIndex) ?: return
        val fields = Fields(rate)
        if (fieldIndex !in 0 until fields.size) return
        val field = fields.at(fieldIndex)
        val length = field.raw?.length ?: 0
        val parts = if (length == 0) 1 else (length - 1) / VALUE_PART_SIZE + 1
        if (part !in 0 until parts) return
        val from = part * VALUE_PART_SIZE
        val rawPart = field.raw?.substring(from, minOf(from + VALUE_PART_SIZE, length))
        val value = if (field.quoted) text(rawPart) else rawPart.orEmpty()
        lateinit var dialog: AlertDialog
        val builder = AlertDialog.Builder(activity)
            .setTitle(field.name)
            .setMessage(activity.getString(R.string.catalog_price_value_part, rateIndex + 1, rates.size,
                part + 1, parts, value, activity.getString(R.string.catalog_price_browser_limits)))
            .setNegativeButton(R.string.catalog_price_back_fields) { _, _ ->
                if (current(dialog)) showFields(rateIndex, fieldIndex / PAGE_SIZE)
            }
        if (part + 1 < parts) builder.setPositiveButton(R.string.catalog_price_next_part) { _, _ ->
            if (current(dialog)) showValue(rateIndex, fieldIndex, part + 1)
        }
        if (part > 0) builder.setNeutralButton(R.string.catalog_price_previous_part) { _, _ ->
            if (current(dialog)) showValue(rateIndex, fieldIndex, part - 1)
        }
        dialog = builder.create()
        present(dialog)
    }

    private inner class Field(val name: String, val raw: String?, val quoted: Boolean = true) {
        fun display() = if (quoted) text(raw) else raw.orEmpty()
    }

    /** Only this fixed metadata list is allocated; arbitrarily many bounded conditions are read by index. */
    private inner class Fields(private val rate: RecordedPriceRate) {
        private val snapshot = requireNotNull(book)
        private val conditions = rate.conditions
        private val fixed = listOf(
            Field("pricebook.id", snapshot.id),
            Field("pricebook.revision", snapshot.revision.toString(), quoted = false),
            Field("pricebook.captured_at", snapshot.capturedAt),
            Field("pricebook.sha256", snapshot.hash),
            Field("catalog_snapshot.id", detail.snapshotId),
            Field("catalog_snapshot.revision", detail.snapshotRevision.toString(), quoted = false),
            Field("catalog_snapshot.captured_at", detail.snapshotCapturedAt),
            Field("catalog_snapshot.sha256", detail.snapshotHash),
            Field("rate.id", rate.id),
            Field("rate.catalog_item_id", rate.itemId),
            Field("rate.catalog_item_version", rate.itemVersion),
            Field("rate.meter", rate.meter),
            Field("rate.unit_price.amount", rate.unitPrice.amount.encoded),
            Field("rate.unit_price.currency", rate.unitPrice.currency),
            Field("rate.region", rate.region),
            Field("rate.valid_from", rate.validFrom),
            Field("rate.valid_until", rate.validUntil),
            Field("rate.price_type", rate.priceType),
            Field("rate.provenance.source_uri", rate.provenance.sourceUri),
            Field("rate.provenance.checked_at", rate.provenance.checkedAt),
            Field("rate.provenance.verification_status", rate.provenance.rawStatus.encoded),
            Field(activity.getString(R.string.catalog_price_unassessed_label), activity.getString(when (rate.provenance.label) {
                EvidenceLabel.INDEPENDENTLY_VERIFIED -> R.string.label_independently_verified
                EvidenceLabel.VERIFICATION_UNESTABLISHED -> R.string.label_verification_unestablished
                EvidenceLabel.UNVERIFIED -> R.string.label_unverified
                EvidenceLabel.USER_SUPPLIED -> R.string.label_user_supplied
                EvidenceLabel.ESTIMATED -> R.string.label_estimated
            }), quoted = false),
            Field("rate.provenance.notes", rate.provenance.notes),
            Field(activity.getString(R.string.catalog_price_conditions_state), when {
                conditions == null -> activity.getString(R.string.catalog_price_conditions_missing)
                conditions.isEmpty() -> activity.getString(R.string.catalog_price_conditions_empty)
                else -> activity.getString(R.string.catalog_price_all_conditions, conditions.size)
            }, quoted = false),
        )
        val size: Int get() = fixed.size + (conditions?.size ?: 0)
        fun at(index: Int): Field = if (index < fixed.size) fixed[index] else {
            val condition = index - fixed.size
            Field("rate.conditions[$condition]", requireNotNull(conditions)[condition])
        }
    }

    private companion object {
        const val PAGE_SIZE = 8
        // Match PreviewComparedValue's literal bound so a value part is complete, never prefix-truncated.
        const val VALUE_PART_SIZE = 160
    }
}
