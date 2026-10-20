package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.PreviewCatalogComparison
import ai.pickyour.core.preview.PreviewCatalogComparisonGroup
import ai.pickyour.core.preview.PreviewCatalogComparisonMatch
import ai.pickyour.core.preview.PreviewCatalogComparisonRow
import ai.pickyour.core.preview.PreviewCatalogField
import ai.pickyour.core.preview.PreviewCatalogValueState
import ai.pickyour.core.pricing.RecordedPricebook

/** One captured comparison and its complete literal values; no draft or save authority. */
internal class CatalogComparisonBrowser(
    private val activity: Activity,
    private val comparison: PreviewCatalogComparison,
    private val pricebook: RecordedPricebook?,
    initialDifferencesOnly: Boolean,
    private val isCurrent: () -> Boolean,
    private val onDifferencesChanged: (Boolean) -> Unit,
) {
    private var differencesOnly = initialDifferencesOnly
    private var currentDialog: AlertDialog? = null
    private var retired = false
    private val details = listOf(comparison.left, comparison.right)

    fun show() = showComparison()

    fun retire() {
        if (retired) return
        retired = true
        val previous = currentDialog
        currentDialog = null
        previous?.dismiss()
    }

    private fun current(): Boolean {
        if (retired) return false
        if (activity.isDestroyed || activity.isFinishing || !isCurrent()) {
            retire()
            return false
        }
        return true
    }

    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing
    private fun literal(raw: String?) = CatalogDetailText.literal(activity, raw)
    private fun identity(index: Int): String = details[index].item.let { item ->
        ComponentSelectionText.indexed(activity, R.string.catalog_compare_identity, index + 1,
            literal(item.name), literal(item.id), literal(item.version))
    }
    private fun fieldHeading(row: PreviewCatalogComparisonRow) = activity.getString(
        R.string.catalog_compare_field,
        activity.getString(if (row.group == PreviewCatalogComparisonGroup.SPECIFICATION)
            R.string.catalog_compare_spec else R.string.catalog_compare_license), row.name,
    )
    private fun matchText(row: PreviewCatalogComparisonRow) = activity.getString(when (row.match) {
        PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE -> R.string.catalog_compare_same
        PreviewCatalogComparisonMatch.DIFFERENT_RECORDED_VALUE -> R.string.catalog_compare_different
        PreviewCatalogComparisonMatch.UNKNOWN -> R.string.catalog_compare_unknown
        PreviewCatalogComparisonMatch.NOT_COMPARABLE -> R.string.catalog_compare_not_comparable
    })

    private fun showComparison(scrollY: Int = 0) {
        if (!current()) return
        present(scrollY) {
            text(activity.getString(R.string.catalog_compare_conditions))
            if (!comparison.sameKind) text(activity.getString(R.string.catalog_compare_different_kinds))
            val toggle = CheckBox(activity).apply {
                setText(R.string.catalog_compare_differences)
                isChecked = differencesOnly
                minHeight = dp(48)
                minWidth = dp(48)
            }.also(content::addView)
            details.forEachIndexed { index, detail ->
                text(identity(index), heading = true)
                text(CatalogDetailText.describe(activity, detail, pricebook, includeSpecifications = false))
                val conditions = detail.specs.filter { it.name in CONDITION_FIELDS }
                if (conditions.isNotEmpty()) text(activity.getString(R.string.catalog_compare_recorded_conditions,
                    conditions.joinToString("\n") { field -> activity.getString(R.string.catalog_detail_field,
                        field.name, field.type, CatalogDetailText.fieldValue(activity, field)) }))
            }
            text(activity.getString(R.string.catalog_compare_values), heading = true)
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }.also(content::addView)
            fun renderRows() {
                rows.removeAllViews()
                val shown = comparison.visibleRows(differencesOnly)
                text(activity.getString(R.string.catalog_compare_row_count, shown.size, comparison.rows.size), parent = rows)
                shown.forEach { row ->
                    val index = comparison.rows.indexOf(row)
                    text(fieldHeading(row), heading = true, parent = rows)
                    listOf(row.left, row.right).forEachIndexed { side, field ->
                        val value = if (field == null) activity.getString(R.string.catalog_compare_not_defined)
                            else activity.getString(R.string.catalog_detail_field, field.name, field.type,
                                CatalogDetailText.fieldValue(activity, field))
                        text(identity(side) + "\n" + value, parent = rows)
                        action(activity.getString(R.string.catalog_compare_inspect_value, side + 1, row.name), rows) {
                            showValue(index, side, 0, scroll.scrollY)
                        }
                    }
                    text(matchText(row), parent = rows)
                }
            }
            renderRows()
            toggle.setOnCheckedChangeListener { _, checked ->
                if (current(dialog)) {
                    differencesOnly = checked
                    onDifferencesChanged(checked)
                    renderRows()
                }
            }
        }
    }

    private fun showValue(index: Int, side: Int, part: Int, comparisonScroll: Int) {
        if (!current() || side !in details.indices) return
        val row = comparison.rows.getOrNull(index) ?: return
        val field = if (side == 0) row.left else row.right
        val raw = field?.valueJson
        val length = raw?.length ?: 0
        val parts = if (length == 0) 1 else (length - 1) / VALUE_PART_SIZE + 1
        if (part !in 0 until parts) return
        val start = part * VALUE_PART_SIZE
        val end = minOf(start + VALUE_PART_SIZE, length)
        present {
            text(identity(side), heading = true)
            text(fieldHeading(row), heading = true)
            val detail = details[side]
            text(activity.getString(R.string.catalog_detail_snapshot, literal(detail.snapshotId),
                detail.snapshotRevision.toString(), detail.snapshotHash))
            text(matchText(row))
            text(fieldState(field))
            if (field != null) text(activity.getString(R.string.catalog_detail_browser_json, field.type))
            text(activity.getString(R.string.catalog_compare_value_part, part + 1, parts,
                if (length == 0) 0 else start + 1, end, length))
            text(literal(raw?.substring(start, end))).id = R.id.catalog_compare_value_literal
            text(activity.getString(R.string.catalog_detail_browser_limits))
            if (part + 1 < parts) action(activity.getString(R.string.catalog_detail_browser_next_part)) {
                showValue(index, side, part + 1, comparisonScroll)
            }
            if (part > 0) action(activity.getString(R.string.catalog_detail_browser_previous_part)) {
                showValue(index, side, part - 1, comparisonScroll)
            }
            action(activity.getString(R.string.catalog_compare_other_value, 2 - side)) {
                showValue(index, 1 - side, 0, comparisonScroll)
            }
            action(activity.getString(R.string.catalog_compare_back_values)) { showComparison(comparisonScroll) }
        }
    }

    private fun fieldState(field: PreviewCatalogField?) = activity.getString(if (field == null)
        R.string.catalog_compare_not_defined else when (field.state) {
            PreviewCatalogValueState.MISSING -> R.string.catalog_detail_missing
            PreviewCatalogValueState.EXPLICIT_UNKNOWN -> R.string.catalog_detail_explicit_unknown
            PreviewCatalogValueState.EMPTY_COLLECTION -> R.string.catalog_detail_empty_collection
            PreviewCatalogValueState.CONTAINS_UNKNOWN -> R.string.catalog_detail_browser_contains_unknown
            PreviewCatalogValueState.SUPPLIED -> R.string.catalog_detail_browser_supplied
        })

    private inner class Page(val dialog: AlertDialog) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = resources.configuration.layoutDirection
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(activity).apply { addView(content) }

        fun text(value: String, heading: Boolean = false, parent: LinearLayout = content) = TextView(activity).apply {
            text = value
            textSize = if (heading) 20f else 18f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = heading
        }.also(parent::addView)

        fun action(label: String, parent: LinearLayout = content, command: () -> Unit) {
            parent.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setSingleLine(false)
                minHeight = dp(48)
                minWidth = dp(48)
                setOnClickListener { control ->
                    if (current(dialog) && control.parent === parent) command()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun present(scrollY: Int = 0, render: Page.() -> Unit) {
        if (!current()) return
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.catalog_compare_open)
            .setPositiveButton(R.string.close, null).create()
        val page = Page(dialog).apply(render)
        dialog.setView(page.scroll)
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener { if (currentDialog === dialog) retire() }
        dialog.show()
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
        if (scrollY > 0) page.scroll.post { if (current(dialog)) page.scroll.scrollTo(0, scrollY) }
    }

    private fun dp(value: Int) = kotlin.math.ceil(value * activity.resources.displayMetrics.density.toDouble()).toInt()

    private companion object {
        const val VALUE_PART_SIZE = 160
        // Frozen condition/reference fields remain visible when matching values are hidden.
        val CONDITION_FIELDS = setOf("provider_ref", "hosting_ref", "accelerator_ref", "accelerator_count",
            "supported_precisions", "precisions", "context_window_tokens", "max_output_tokens", "access_modes",
            "weight_access", "model_formats", "region", "supported_regions", "workload_type", "modalities",
            "concurrency", "requests_per_day", "avg_input_tokens", "avg_output_tokens", "latency_budget_ms",
            "uptime_target_pct", "data_classification")
    }
}
