package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.*

/** Read-only transient comparison. It never receives an editor save token or worker. */
class CatalogComparisonSection(private val activity: Activity, saved: Bundle?) : LinearLayout(activity) {
    private var resetState = false
    private var tray = try {
        val ids = saved?.getStringArrayList("comparisonIds")
        val versions = saved?.getStringArrayList("comparisonVersions")
        val hashes = saved?.getStringArrayList("comparisonHashes")
        if (ids == null && versions == null && hashes == null) PreviewCatalogTray()
        else {
            require(ids != null && versions != null && hashes != null)
            require(ids.size <= PreviewCatalogTray.LIMIT && versions.size == ids.size && hashes.size == ids.size)
            PreviewCatalogTray(ids.indices.map { PreviewCatalogPin(requireNotNull(ids.getOrNull(it)),
                requireNotNull(versions.getOrNull(it)), requireNotNull(hashes.getOrNull(it))) })
        }
    } catch (_: IllegalArgumentException) { resetState = true; PreviewCatalogTray() }
    private var differencesOnly = saved?.getBoolean("comparisonDifferencesOnly", false) ?: false
    private var browser: PreviewCatalogBrowser? = null
    private var closed = false
    private var comparisonDialog: AlertDialog? = null
    private val status: TextView
    private val entries: LinearLayout
    private val compare: Button
    private var resultMessage: Int? = null

    init {
        orientation = VERTICAL
        caption(this, activity.getString(R.string.catalog_compare_heading), true)
        caption(this, activity.getString(R.string.catalog_compare_limit, PreviewCatalogTray.LIMIT))
        status = caption(this, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        entries = LinearLayout(activity).apply { orientation = VERTICAL }.also(::addView)
        compare = Button(activity).apply {
            setText(R.string.catalog_compare_open)
            setOnClickListener { openComparison() }
        }.also(::addView)
        render()
    }

    private fun active() = !closed && !activity.isDestroyed && !activity.isFinishing
    private fun literal(raw: String?) = CatalogDetailText.literal(activity, raw)
    private fun caption(parent: LinearLayout, value: String, heading: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = if (heading) 20f else 18f
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = heading
    }.also(parent::addView)

    fun show(value: PreviewCatalogBrowser?) { if (active()) { browser = value; render() } }

    fun add(item: PreviewCatalogItem, hash: String) {
        if (!active()) return
        val update = browser?.let { tray.add(it, item, hash) }
        if (update != null) tray = update.tray
        resultMessage = when (update?.result) {
            PreviewCatalogTrayResult.ADDED -> R.string.catalog_compare_added
            PreviewCatalogTrayResult.ALREADY_PRESENT -> R.string.catalog_compare_already_present
            PreviewCatalogTrayResult.FULL -> R.string.catalog_compare_full
            PreviewCatalogTrayResult.IDENTITY_TOO_LARGE -> R.string.catalog_compare_identity_large
            PreviewCatalogTrayResult.UNAVAILABLE, null -> R.string.catalog_compare_unavailable
        }
        render()
    }

    private fun identity(index: Int, entry: PreviewCatalogTrayEntry): String = activity.getString(
        R.string.catalog_compare_identity, index + 1,
        entry.detail?.item?.name?.let(::literal) ?: activity.getString(R.string.catalog_compare_missing_name),
        literal(entry.pin.id), literal(entry.pin.version),
    )

    private fun render() {
        if (!active()) return
        val current = tray.entries(browser)
        status.text = listOfNotNull(
            if (resetState) activity.getString(R.string.catalog_compare_reset) else null,
            resultMessage?.let(activity::getString),
            activity.getString(R.string.catalog_compare_count, current.size, PreviewCatalogTray.LIMIT),
        ).joinToString("\n")
        entries.removeAllViews()
        current.forEachIndexed { index, entry ->
            caption(entries, identity(index, entry), true)
            if (entry.detail == null) caption(entries, activity.getString(R.string.catalog_compare_unavailable))
            entries.addView(Button(activity).apply {
                text = activity.getString(R.string.catalog_compare_remove, index + 1, literal(entry.pin.version))
                setOnClickListener { control ->
                    if (!active() || control.parent !== entries) return@setOnClickListener
                    tray = tray.remove(entry.pin)
                    resultMessage = R.string.catalog_compare_removed
                    render()
                }
            })
        }
        compare.isEnabled = current.size == PreviewCatalogTray.LIMIT && current.all { it.detail != null }
    }

    private fun openComparison() {
        if (!active()) return
        val current = tray.entries(browser)
        if (current.size != PreviewCatalogTray.LIMIT || current.any { it.detail == null }) return
        val comparison = PreviewCatalogComparison(requireNotNull(current[0].detail), requireNotNull(current[1].detail))
        val content = LinearLayout(activity).apply {
            orientation = VERTICAL
            layoutDirection = resources.configuration.layoutDirection
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        caption(content, activity.getString(R.string.catalog_compare_conditions))
        if (!comparison.sameKind) caption(content, activity.getString(R.string.catalog_compare_different_kinds))
        val toggle = CheckBox(activity).apply {
            setText(R.string.catalog_compare_differences)
            isChecked = differencesOnly
            minHeight = (48 * resources.displayMetrics.density).toInt()
            minWidth = (48 * resources.displayMetrics.density).toInt()
        }.also(content::addView)
        current.forEachIndexed { index, entry ->
            caption(content, identity(index, entry), true)
            val detail = requireNotNull(entry.detail)
            caption(content, CatalogDetailText.describe(activity, detail, includeSpecifications = false))
            val conditions = detail.specs.filter { it.name in CONDITION_FIELDS }
            if (conditions.isNotEmpty()) caption(content, activity.getString(R.string.catalog_compare_recorded_conditions,
                conditions.joinToString("\n") { field -> activity.getString(R.string.catalog_detail_field,
                    field.name, field.type, CatalogDetailText.fieldValue(activity, field)) }))
        }
        caption(content, activity.getString(R.string.catalog_compare_values), true)
        val rows = LinearLayout(activity).apply { orientation = VERTICAL }.also(content::addView)
        fun renderRows() {
            rows.removeAllViews()
            val shown = comparison.visibleRows(differencesOnly)
            caption(rows, activity.getString(R.string.catalog_compare_row_count, shown.size, comparison.rows.size))
            shown.forEach { row ->
                val group = activity.getString(if (row.group == PreviewCatalogComparisonGroup.SPECIFICATION)
                    R.string.catalog_compare_spec else R.string.catalog_compare_license)
                caption(rows, activity.getString(R.string.catalog_compare_field, group, row.name), true)
                listOf(row.left, row.right).forEachIndexed { index, field ->
                    val value = if (field == null) activity.getString(R.string.catalog_compare_not_defined)
                        else activity.getString(R.string.catalog_detail_field, field.name, field.type,
                            CatalogDetailText.fieldValue(activity, field))
                    // Repeat exact version/identity for each stacked cell, including identical display names.
                    caption(rows, identity(index, current[index]) + "\n" + value)
                }
                caption(rows, activity.getString(when (row.match) {
                    PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE -> R.string.catalog_compare_same
                    PreviewCatalogComparisonMatch.DIFFERENT_RECORDED_VALUE -> R.string.catalog_compare_different
                    PreviewCatalogComparisonMatch.UNKNOWN -> R.string.catalog_compare_unknown
                    PreviewCatalogComparisonMatch.NOT_COMPARABLE -> R.string.catalog_compare_not_comparable
                }))
            }
        }
        renderRows()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.catalog_compare_open)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.close, null).create()
        comparisonDialog?.dismiss()
        comparisonDialog = dialog
        dialog.setOnDismissListener { if (comparisonDialog === dialog) comparisonDialog = null }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (active() && comparisonDialog === dialog && dialog.isShowing) {
                differencesOnly = checked
                renderRows()
            }
        }
        dialog.show()
        // Carry the activity's resolved direction into the custom dialog window as well as its content.
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    fun save(out: Bundle) {
        out.putStringArrayList("comparisonIds", ArrayList(tray.pins.map { it.id }))
        out.putStringArrayList("comparisonVersions", ArrayList(tray.pins.map { it.version }))
        out.putStringArrayList("comparisonHashes", ArrayList(tray.pins.map { it.snapshotHash }))
        out.putBoolean("comparisonDifferencesOnly", differencesOnly)
    }

    fun close() { closed = true; comparisonDialog?.dismiss(); comparisonDialog = null }

    companion object {
        // Frozen v1 condition/reference fields stay visible even when matching values are hidden.
        private val CONDITION_FIELDS = setOf("provider_ref", "hosting_ref", "accelerator_ref", "accelerator_count",
            "supported_precisions", "precisions", "context_window_tokens", "max_output_tokens", "access_modes",
            "weight_access", "model_formats", "region", "supported_regions", "workload_type", "modalities",
            "concurrency", "requests_per_day", "avg_input_tokens", "avg_output_tokens", "latency_budget_ms",
            "uptime_target_pct", "data_classification")
    }
}
