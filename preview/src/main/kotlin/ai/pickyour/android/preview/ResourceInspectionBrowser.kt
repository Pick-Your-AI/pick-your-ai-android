package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.widget.TextView
import ai.pickyour.core.resources.InspectedResourceEstimate
import ai.pickyour.core.resources.ResourceInspectionGap

/** Bounded pages over one immutable input pair. Every recorded value remains accessible. */
internal class ResourceInspectionBrowser(
    private val activity: Activity,
    private val record: InspectedResourceEstimate,
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
            activity.getString(R.string.resource_browser_overview),
            activity.getString(R.string.resource_browser_estimates, record.estimate.estimates.size),
            activity.getString(R.string.resource_browser_selections, record.selections.size),
            activity.getString(R.string.resource_browser_documents),
        )
        lateinit var window: AlertDialog
        window = AlertDialog.Builder(activity).setTitle(R.string.resource_browser_title)
            .setItems(rows) { _, index ->
                if (current(window)) when (index) {
                    0 -> fields(rows[0], overview(), 0, ::show)
                    1 -> estimates(0)
                    2 -> selections(0)
                    3 -> fields(rows[3], listOf(
                        Field("build", record.recordedBuildJson, Format.JSON),
                        Field("resource_estimate", record.estimate.json, Format.JSON),
                    ), 0, ::show)
                }
            }.setNegativeButton(R.string.close, null).create()
        present(window)
    }

    private fun estimates(page: Int): Unit = list(
        activity.getString(R.string.resource_browser_estimates, record.estimate.estimates.size),
        record.estimate.estimates.size, page,
        { activity.getString(R.string.resource_browser_estimate, it + 1) },
        { index -> fields(activity.getString(R.string.resource_browser_estimate, index + 1),
            estimateFields(index), 0, { estimates(page) }) }, ::show,
        ::estimates,
    )

    private fun selections(page: Int): Unit = list(
        activity.getString(R.string.resource_browser_selections, record.selections.size),
        record.selections.size, page,
        { activity.getString(R.string.resource_browser_selection, it + 1) },
        { index -> fields(activity.getString(R.string.resource_browser_selection, index + 1),
            selectionFields(index), 0, { selections(page) }) }, ::show,
        ::selections,
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
            .setTitle(activity.getString(R.string.resource_browser_page, title, page + 1, pages))
            .setItems(rows) { _, index -> if (current(window) && index in rows.indices) choose(start + index) }
            .setNegativeButton(R.string.resource_browser_back) { _, _ -> if (current(window)) back() }
        if (page + 1 < pages) builder.setPositiveButton(R.string.resource_browser_next) { _, _ ->
            if (current(window)) move(page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.resource_browser_previous) { _, _ ->
            if (current(window)) move(page - 1)
        }
        window = builder.create()
        present(window)
    }

    private fun value(field: Field, part: Int, back: () -> Unit) {
        if (!current()) return
        val length = field.raw?.length ?: 0
        val parts = maxOf(1, (length + VALUE_PART_SIZE - 1) / VALUE_PART_SIZE)
        if (part !in 0 until parts) return
        val start = part * VALUE_PART_SIZE
        val raw = field.raw?.substring(start, minOf(length, start + VALUE_PART_SIZE))
        val format = activity.getString(when (field.format) {
            Format.TEXT -> R.string.resource_browser_text
            Format.INTEGER -> R.string.resource_browser_integer
            Format.BOOLEAN -> R.string.resource_browser_boolean
            Format.JSON -> R.string.resource_browser_json
            Format.LOCAL -> R.string.resource_browser_local
        })
        lateinit var window: AlertDialog
        val builder = AlertDialog.Builder(activity).setTitle(field.name)
            .setMessage(activity.getString(R.string.resource_browser_value, format, part + 1, parts,
                CatalogDetailText.literal(activity, raw), activity.getString(R.string.resource_browser_limits)))
            .setNegativeButton(R.string.resource_browser_back) { _, _ -> if (current(window)) back() }
        if (part + 1 < parts) builder.setPositiveButton(R.string.resource_browser_next_part) { _, _ ->
            if (current(window)) value(field, part + 1, back)
        }
        if (part > 0) builder.setNeutralButton(R.string.resource_browser_previous_part) { _, _ ->
            if (current(window)) value(field, part - 1, back)
        }
        window = builder.create()
        present(window)
    }

    private enum class Format { TEXT, INTEGER, BOOLEAN, JSON, LOCAL }
    private data class Field(val name: String, val raw: String?, val format: Format = Format.TEXT)

    private fun overview(): List<Field> = buildList {
        add(Field("resource_estimate.schema_version", record.estimate.schemaVersion))
        add(Field("resource_estimate.id", record.estimate.id))
        add(Field("resource_estimate.build_id", record.estimate.buildId))
        add(Field("resource_estimate.build_revision", record.estimate.buildRevision.toString(), Format.INTEGER))
        add(Field("resource_estimate.generated_at", record.estimate.generatedAt))
        add(Field("build.id", record.buildId))
        add(Field("build.revision", record.buildRevision.toString(), Format.INTEGER))
        add(Field("build.name", record.buildName))
        add(Field("build.policy.min_resource_confidence", record.declaredMinimumConfidence))
        add(Field("consumed_build_sha256", record.identity.buildSha256, Format.LOCAL))
        add(Field("consumed_resource_estimate_sha256", record.identity.estimateSha256, Format.LOCAL))
        record.gaps.forEach { gap -> add(Field(gap.name, activity.getString(when (gap) {
            ResourceInspectionGap.CATALOG_ARTIFACT_CONDITIONS_NOT_ESTABLISHED -> R.string.resource_gap_catalog
            ResourceInspectionGap.RUNTIME_CONTEXT_TOPOLOGY_APPLICABILITY_NOT_ESTABLISHED -> R.string.resource_gap_runtime
            ResourceInspectionGap.ESTIMATOR_EVIDENCE_NOT_ESTABLISHED -> R.string.resource_gap_estimator
            ResourceInspectionGap.FRESHNESS_NOT_ESTABLISHED -> R.string.resource_gap_freshness
            ResourceInspectionGap.SOURCE_ACCESS_AND_RIGHTS_NOT_ESTABLISHED -> R.string.resource_gap_rights
            ResourceInspectionGap.MEASUREMENT_METHOD_AND_RANGE_NOT_RECORDED -> R.string.resource_gap_range
            ResourceInspectionGap.METRIC_UNIT_RELATIONSHIP_NOT_ESTABLISHED -> R.string.resource_gap_unit
        }), Format.LOCAL)) }
        add(Field("unreferenced_active_selection_count", record.unreferencedActiveSelectionIds.size.toString(), Format.LOCAL))
        add(Field("unreferenced_selection_scope", activity.getString(R.string.resource_unreferenced_notice), Format.LOCAL))
        record.unreferencedActiveSelectionIds.forEachIndexed { index, id ->
            add(Field("unreferenced_active_selection_ids[$index]", id, Format.LOCAL))
        }
    }

    private fun estimateFields(index: Int): List<Field> = buildList {
        val entry = record.estimate.estimates[index]
        add(Field("metric", entry.metric.encoded))
        add(Field("value", entry.value))
        add(Field("unit", entry.unit.encoded))
        add(Field("estimator_id", entry.estimatorId))
        add(Field("estimator_version", entry.estimatorVersion))
        add(Field("confidence", entry.confidence.encoded))
        add(Field("source_uri", entry.sourceUri))
        add(Field("assumptions.length", entry.assumptions.size.toString(), Format.LOCAL))
        add(Field("selection_ids.length", entry.selectionIds.size.toString(), Format.LOCAL))
        entry.assumptions.forEachIndexed { at, raw -> add(Field("assumptions[$at]", raw)) }
        entry.selectionIds.forEachIndexed { at, raw -> add(Field("selection_ids[$at]", raw)) }
    }

    private fun selectionFields(index: Int): List<Field> {
        val entry = record.selections[index]
        return listOf(
            Field("id", entry.id), Field("kind", entry.kind),
            Field("catalog_item_id", entry.catalogItemId), Field("catalog_item_version", entry.catalogItemVersion),
            Field("role", entry.role), Field("enabled", entry.enabled.toString(), Format.BOOLEAN),
            Field("settings", entry.settingsJson, Format.JSON), Field("complete_selection", entry.recordedJson, Format.JSON),
        )
    }

    private companion object {
        const val PAGE_SIZE = 8
        // Each exact UTF-16 part uses the existing escaping bound, including split surrogate units.
        const val VALUE_PART_SIZE = 160
    }
}
