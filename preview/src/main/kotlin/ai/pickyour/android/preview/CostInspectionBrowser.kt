package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.widget.TextView
import ai.pickyour.core.pricing.InspectedCostEstimate

/** Complete, bounded pages over the same immutable declaration used by the summary. */
internal class CostInspectionBrowser(
    private val activity: Activity,
    private val record: InspectedCostEstimate,
    private val isCurrent: () -> Boolean,
) {
    private var dialog: AlertDialog? = null
    private var retired = false

    private fun current(): Boolean {
        if (retired) return false
        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) {
            retire()
            return false
        }
        return true
    }
    private fun current(window: AlertDialog) = current() && dialog === window && window.isShowing

    private fun present(next: AlertDialog) {
        if (!current()) return
        val previous = dialog
        dialog = next
        previous?.dismiss()
        next.setOnDismissListener { if (dialog === next) retire() }
        next.show()
        next.findViewById<TextView>(android.R.id.message)?.apply {
            autoLinkMask = 0
            linksClickable = false
            movementMethod = null
        }
        next.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
    }

    fun retire() {
        if (retired) return
        retired = true
        val previous = dialog
        dialog = null
        previous?.dismiss()
    }

    fun show() {
        if (!current()) return
        val rows = arrayOf(
            activity.getString(R.string.cost_browser_overview),
            activity.getString(R.string.cost_browser_lines, record.lines.size),
            activity.getString(R.string.cost_browser_references),
            activity.getString(R.string.cost_browser_warnings, record.warnings.size),
            activity.getString(R.string.cost_browser_documents),
        )
        lateinit var window: AlertDialog
        window = AlertDialog.Builder(activity).setTitle(R.string.cost_browser_title)
            .setItems(rows) { _, index ->
                if (current(window)) when (index) {
                    0 -> fields(rows[0], overview(), 0, ::show)
                    1 -> lines(0)
                    2 -> fields(rows[2], references(), 0, ::show)
                    3 -> fields(rows[3], record.warnings.mapIndexed { at, raw -> Field("warnings[$at]", raw) }, 0, ::show)
                    4 -> fields(rows[4], listOf(
                        Field("build", record.recordedBuildJson, Format.JSON),
                        Field("cost_estimate", record.recordedJson, Format.JSON),
                    ), 0, ::show)
                }
            }.setNegativeButton(R.string.close, null).create()
        present(window)
    }

    private fun lines(page: Int): Unit = list(
        activity.getString(R.string.cost_browser_lines, record.lines.size), record.lines.size, page,
        { activity.getString(R.string.cost_browser_line, it + 1) },
        { index -> fields(activity.getString(R.string.cost_browser_line, index + 1),
            lineFields(index), 0, { lines(page) }) }, ::show, ::lines,
    )

    private fun fields(title: String, values: List<Field>, page: Int, back: () -> Unit): Unit = list(
        title, values.size, page, { values[it].name },
        { index -> value(values[index], 0) { fields(title, values, page, back) } }, back,
        { fields(title, values, it, back) },
    )

    private fun list(
        title: String, size: Int, page: Int, label: (Int) -> String, choose: (Int) -> Unit,
        back: () -> Unit, move: (Int) -> Unit,
    ) {
        if (!current()) return
        val pages = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)
        if (page !in 0 until pages) return
        val start = page * PAGE_SIZE
        val rows = (start until minOf(start + PAGE_SIZE, size)).map(label).toTypedArray()
        lateinit var window: AlertDialog
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cost_browser_page, title, page + 1, pages))
            .setNegativeButton(R.string.cost_browser_back) { _, _ -> if (current(window)) back() }
        if (rows.isEmpty()) builder.setMessage(R.string.cost_none_declared)
        else builder.setItems(rows) { _, index -> if (current(window) && index in rows.indices) choose(start + index) }
        if (page + 1 < pages) builder.setPositiveButton(R.string.cost_browser_next) { _, _ ->
            if (current(window)) move(page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.cost_browser_previous) { _, _ ->
            if (current(window)) move(page - 1)
        }
        window = builder.create()
        present(window)
    }

    private fun value(field: Field, part: Int, back: () -> Unit) {
        if (!current()) return
        val length = field.raw.length
        val parts = maxOf(1, (length + VALUE_PART_SIZE - 1) / VALUE_PART_SIZE)
        if (part !in 0 until parts) return
        val start = part * VALUE_PART_SIZE
        val raw = field.raw.substring(start, minOf(length, start + VALUE_PART_SIZE))
        val format = activity.getString(when (field.format) {
            Format.TEXT -> R.string.cost_browser_text
            Format.INTEGER -> R.string.cost_browser_integer
            Format.DECIMAL -> R.string.cost_browser_decimal
            Format.JSON -> R.string.cost_browser_json
            Format.LOCAL -> R.string.cost_browser_local
        })
        lateinit var window: AlertDialog
        val builder = AlertDialog.Builder(activity).setTitle(field.name)
            .setMessage(activity.getString(R.string.cost_browser_value, format, part + 1, parts,
                CatalogDetailText.literal(activity, raw), activity.getString(R.string.cost_evidence_limits)))
            .setNegativeButton(R.string.cost_browser_back) { _, _ -> if (current(window)) back() }
        if (part + 1 < parts) builder.setPositiveButton(R.string.cost_browser_next_part) { _, _ ->
            if (current(window)) value(field, part + 1, back)
        }
        if (part > 0) builder.setNeutralButton(R.string.cost_browser_previous_part) { _, _ ->
            if (current(window)) value(field, part - 1, back)
        }
        window = builder.create()
        present(window)
    }

    private enum class Format { TEXT, INTEGER, DECIMAL, JSON, LOCAL }
    private data class Field(val name: String, val raw: String, val format: Format = Format.TEXT)

    private fun overview(): List<Field> = listOf(
        Field("cost_estimate.id", record.estimateId),
        Field("cost_estimate.build_id", record.buildId),
        Field("cost_estimate.build_revision", record.buildRevision.toString(), Format.INTEGER),
        Field("cost_estimate.pricebook_id", record.pricebookId),
        Field("cost_estimate.period_days", record.periodDays.toString(), Format.INTEGER),
        Field("cost_estimate.estimated_at", record.estimatedAt),
        Field("cost_estimate.currency", record.currency),
        Field("cost_estimate.coverage", record.declaredCoverage.name.lowercase(java.util.Locale.ROOT)),
        Field("subtotal.amount", record.reportedSubtotal.amount.encoded, Format.DECIMAL),
        Field("subtotal.currency", record.reportedSubtotal.currency),
        Field("tax.amount", record.reportedTax.amount.encoded, Format.DECIMAL),
        Field("tax.currency", record.reportedTax.currency),
        Field("total.amount", record.reportedTotal.amount.encoded, Format.DECIMAL),
        Field("total.currency", record.reportedTotal.currency),
        Field("freshness", record.freshness.name, Format.LOCAL),
        Field("free_or_included_treatment", record.freeOrIncludedTreatment.name, Format.LOCAL),
        Field("budget_pass", record.budgetPass.name, Format.LOCAL),
    )

    private fun references(): List<Field> = buildList {
        add(Field("recorded_line_selection_ids.length", record.lines.size.toString(), Format.LOCAL))
        record.lines.forEachIndexed { at, line -> add(Field("recorded_line_selection_ids[$at]", line.selectionId, Format.LOCAL)) }
        add(Field("unpriced_selection_ids.length", record.reportedUnpricedSelectionIds.size.toString(), Format.LOCAL))
        record.reportedUnpricedSelectionIds.forEachIndexed { at, raw -> add(Field("unpriced_selection_ids[$at]", raw)) }
        add(Field("unclassified_active_selection_ids.length", record.unclassifiedActiveSelectionIds.size.toString(), Format.LOCAL))
        record.unclassifiedActiveSelectionIds.forEachIndexed { at, raw -> add(Field("unclassified_active_selection_ids[$at]", raw, Format.LOCAL)) }
        add(Field("classification_limits", activity.getString(R.string.cost_browser_reference_limits), Format.LOCAL))
    }

    private fun lineFields(index: Int): List<Field> = buildList {
        val line = record.lines[index]
        add(Field("id", line.id))
        add(Field("selection_id", line.selectionId))
        add(Field("price_rate_id", line.rateId))
        add(Field("meter", line.meter))
        // Quantity remains an exact recorded string, including a schema-permitted terminal LF.
        add(Field("quantity", line.quantity))
        add(Field("unit_price.amount", line.unitPrice.amount.encoded, Format.DECIMAL))
        add(Field("unit_price.currency", line.unitPrice.currency))
        add(Field("amount.amount", line.amount.amount.encoded, Format.DECIMAL))
        add(Field("amount.currency", line.amount.currency))
        add(Field("method", line.method))
        add(Field("confidence", line.confidence))
        add(Field("assumptions.length", line.assumptions.size.toString(), Format.LOCAL))
        line.assumptions.forEachIndexed { at, raw -> add(Field("assumptions[$at]", raw)) }
    }

    private companion object {
        const val PAGE_SIZE = 8
        // Exact UTF-16 parts use the established escaping bound, including split surrogate units.
        const val VALUE_PART_SIZE = 160
    }
}
