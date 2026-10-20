package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.evaluation.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit observations of one captured native choice. The parent retains source and command
 * authority. This window owns only its current worker attempt and visible inspection controls.
 */
internal class AlternativeInspectionBrowser(
    private val activity: Activity,
    private val worker: Executor,
    private val parent: DraftCompatibleOptionsRequest,
    private val result: DraftCompatibleOptionsOutcome.Completed,
    private val option: DraftCompatibleOption,
    private val isCurrent: () -> Boolean,
    private val inspect: (DraftAlternativeInspectionRequest, () -> Boolean) -> DraftAlternativeOutcome =
        DraftAlternativeInspector()::inspect,
) {
    private val retired = AtomicBoolean(false)
    private var workRetired = AtomicBoolean(false)
    private var dialog: AlertDialog? = null
    private var request: DraftAlternativeInspectionRequest? = null
    private var observations: DraftAlternativeOutcome.Completed? = null

    private fun current(): Boolean {
        if (retired.get()) return false
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
        dialog = next // Revoke the old window before its dismissal callback can run.
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
        if (!retired.compareAndSet(false, true)) return
        workRetired.set(true)
        request = null
        observations = null
        val previous = dialog
        dialog = null
        previous?.dismiss()
    }

    fun show() {
        if (!current()) return
        workRetired.set(true)
        val token = AtomicBoolean(false).also { workRetired = it }
        request = null
        observations = null
        val loading = AlertDialog.Builder(activity).setTitle(R.string.alternative_title)
            .setMessage(R.string.alternative_loading).setNegativeButton(R.string.close, null).create()
        present(loading)
        try {
            worker.execute {
                // Worker access is limited to immutable captures and these cancellation flags.
                if (retired.get() || token.get()) return@execute
                val prepared = try { DraftAlternativeInspectionRequest.prepare(parent, result, option) }
                    catch (_: Exception) { null }
                val captured = (prepared as? DraftAlternativePreparation.Ready)?.request
                val outcome = if (captured == null) null else try {
                    inspect(captured) { retired.get() || token.get() }
                } catch (_: Exception) { null }
                if (retired.get() || token.get()) return@execute
                activity.runOnUiThread {
                    if (workRetired !== token || !current(loading)) return@runOnUiThread
                    if (captured != null && captured.belongsTo(parent, result, option) &&
                        outcome is DraftAlternativeOutcome.Completed && outcome.belongsTo(captured)) {
                        request = captured
                        observations = outcome
                        menu()
                    } else unavailable(
                        (prepared as? DraftAlternativePreparation.Unavailable)?.reason
                            ?: (outcome as? DraftAlternativeOutcome.Unavailable)?.takeIf {
                                captured != null && it.belongsTo(captured)
                            }?.reason ?: CompatibleOptionsUnavailable.INVALID_OUTPUT,
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            if (workRetired === token && current(loading)) unavailable(null)
        }
    }

    private fun unavailable(reason: CompatibleOptionsUnavailable?) {
        if (!current()) return
        lateinit var window: AlertDialog
        window = AlertDialog.Builder(activity).setTitle(R.string.alternative_title)
            .setMessage(activity.getString(R.string.alternative_unavailable) + "\n\n" +
                activity.getString(when (reason) {
                    null -> R.string.alternative_dispatch_failed
                    CompatibleOptionsUnavailable.INVALID_INPUT -> R.string.alternative_invalid_input
                    CompatibleOptionsUnavailable.BINDING_MISMATCH -> R.string.alternative_binding_mismatch
                    CompatibleOptionsUnavailable.INVALID_SLOT -> R.string.alternative_invalid_slot
                    CompatibleOptionsUnavailable.BASELINE_UNAVAILABLE -> R.string.alternative_baseline_unavailable
                    CompatibleOptionsUnavailable.WORK_LIMIT -> R.string.alternative_work_limit
                    CompatibleOptionsUnavailable.UNSUPPORTED_OPERATION -> R.string.alternative_unsupported
                    CompatibleOptionsUnavailable.INVALID_OUTPUT -> R.string.alternative_invalid_output
                    CompatibleOptionsUnavailable.CANCELLED -> R.string.alternative_cancelled
                    CompatibleOptionsUnavailable.OPTIONAL_INTENT_UNRESOLVED -> R.string.alternative_optional_unresolved
                }))
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.alternative_retry) { _, _ -> if (current(window)) show() }.create()
        present(window)
    }

    private fun menu() {
        if (!current()) return
        val captured = request ?: return
        val completed = observations ?: return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String, identifier: Int = View.NO_ID) {
            content.addView(TextView(activity).apply {
                id = identifier
                this.text = value
                autoLinkMask = 0
                linksClickable = false
                movementMethod = null
            })
        }
        text(activity.getString(R.string.alternative_scope), R.id.alternative_scope)
        text(activity.getString(R.string.alternative_summary, captured.binding.sourceRevision.toString(),
            completed.observations.size, activity.getString(when (captured.verdict) {
                CompatibleOptionVerdict.COMPATIBLE -> R.string.options_compatible
                CompatibleOptionVerdict.INCOMPATIBLE -> R.string.options_incompatible
                CompatibleOptionVerdict.UNKNOWN -> R.string.options_unknown
            })))
        text(activity.getString(if (captured.currentSelectionJson == null) R.string.alternative_no_current
            else R.string.alternative_has_current))
        text(activity.getString(R.string.alternative_limits))
        lateinit var window: AlertDialog
        fun button(id: Int, label: String, action: () -> Unit) {
            content.addView(Button(activity).apply {
                this.id = id
                this.text = label
                setOnClickListener { if (current(window)) action() }
            })
        }
        button(R.id.alternative_overview, activity.getString(R.string.alternative_overview)) {
            fields(activity.getString(R.string.alternative_overview), overview(captured), 0, ::menu)
        }
        button(R.id.alternative_rules, activity.getString(R.string.alternative_rules, completed.observations.size)) {
            rules(0)
        }
        button(R.id.alternative_records, activity.getString(R.string.alternative_records)) {
            fields(activity.getString(R.string.alternative_records), listOf(
                Field("current_selection", captured.currentSelectionJson, Format.JSON),
                Field("candidate_selection", captured.candidateSelectionJson, Format.JSON),
                Field("current_context", captured.currentContextJson, Format.JSON),
                Field("candidate_context", captured.candidateContextJson, Format.JSON),
                Field("slot_empty_context", captured.slotEmptyContextJson, Format.JSON),
                Field("context_format", activity.getString(R.string.alternative_context_format), Format.LOCAL),
            ), 0, ::menu)
        }
        window = AlertDialog.Builder(activity).setTitle(R.string.alternative_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.close, null).create()
        present(window)
    }

    private fun rules(page: Int) {
        val values = observations?.observations ?: return
        list(activity.getString(R.string.alternative_rules, values.size), values.size, page,
            { activity.getString(R.string.alternative_rule_row, it + 1,
                activity.getString(outcomeLabel(values[it].current.outcome)),
                activity.getString(outcomeLabel(values[it].candidate.outcome))) },
            { fields(activity.getString(R.string.alternative_rule_title, it + 1),
                findingFields(values[it]), 0, { rules(page) }) }, ::menu, ::rules)
    }

    private fun fields(title: String, values: List<Field>, page: Int, back: () -> Unit): Unit = list(
        title, values.size, page, { values[it].name },
        { value(values[it], 0) { fields(title, values, page, back) } }, back,
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
            .setTitle(activity.getString(R.string.alternative_page, title, page + 1, pages))
            .setItems(rows) { _, index -> if (current(window) && index in rows.indices) choose(start + index) }
            .setNegativeButton(R.string.alternative_back) { _, _ -> if (current(window)) back() }
        if (page + 1 < pages) builder.setPositiveButton(R.string.alternative_next) { _, _ ->
            if (current(window)) move(page + 1)
        }
        if (page > 0) builder.setNeutralButton(R.string.alternative_previous) { _, _ ->
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
        lateinit var window: AlertDialog
        val builder = AlertDialog.Builder(activity).setTitle(field.name)
            .setMessage(activity.getString(R.string.alternative_value, activity.getString(when (field.format) {
                Format.TEXT -> R.string.alternative_text
                Format.JSON -> R.string.alternative_json
                Format.BOOLEAN -> R.string.alternative_boolean
                Format.LOCAL -> R.string.alternative_local
            }), part + 1, parts, CatalogDetailText.literal(activity, raw)))
            .setNegativeButton(R.string.alternative_back) { _, _ -> if (current(window)) back() }
        if (part + 1 < parts) builder.setPositiveButton(R.string.alternative_next_part) { _, _ ->
            if (current(window)) value(field, part + 1, back)
        }
        if (part > 0) builder.setNeutralButton(R.string.alternative_previous_part) { _, _ ->
            if (current(window)) value(field, part - 1, back)
        }
        window = builder.create()
        present(window)
    }

    private enum class Format { TEXT, JSON, BOOLEAN, LOCAL }
    private data class Field(val name: String, val raw: String?, val format: Format = Format.TEXT)

    private fun overview(captured: DraftAlternativeInspectionRequest): List<Field> = buildList {
        val b = captured.binding
        add(Field("profile", captured.profile, Format.LOCAL))
        add(Field("compatible_options_profile", captured.compatibleOptionsProfile, Format.LOCAL))
        add(Field("saved_local_revision", b.sourceRevision.toString(), Format.LOCAL))
        add(Field("saved_source_sha256", b.sourceHash.hex, Format.LOCAL))
        add(Field("catalog.id", b.catalogSnapshotId))
        add(Field("catalog.revision", b.catalogRevision.toString()))
        add(Field("catalog.sha256", b.catalogHash.hex, Format.LOCAL))
        add(Field("ruleset.id", b.rulesetId))
        add(Field("ruleset.version", b.rulesetVersion))
        add(Field("ruleset.sha256", b.rulesetHash.hex, Format.LOCAL))
        add(Field("slot.kind", b.slot.kind))
        add(Field("slot.role", b.slot.role))
        add(Field("slot.selection_id", b.slot.selectionId))
        add(Field("slot.replacing", b.slot.replacing.toString(), Format.BOOLEAN))
        add(Field("candidate.catalog_item_id", captured.candidateIdentity.catalogItemId))
        add(Field("candidate.catalog_item_version", captured.candidateIdentity.catalogItemVersion))
        add(Field("input_digest", b.inputDigest, Format.LOCAL))
        add(Field("original_choice_verdict", captured.verdict.name, Format.LOCAL))
        add(Field("slot_empty_basis", activity.getString(R.string.alternative_slot_empty), Format.LOCAL))
        add(Field("unrepresented_field_groups.length", captured.unrepresentedFieldGroups.size.toString(), Format.LOCAL))
        captured.unrepresentedFieldGroups.forEachIndexed { index, group ->
            add(Field("unrepresented_field_groups[$index]", group, Format.LOCAL))
        }
        captured.gaps.forEach { gap ->
            add(Field(gap.name, activity.getString(when (gap) {
                DraftAlternativeGap.OBJECTIVE_TEXT_NOT_INTERPRETED -> R.string.alternative_gap_objective
                DraftAlternativeGap.EXTERNAL_EVIDENCE_APPLICABILITY_NOT_ESTABLISHED -> R.string.alternative_gap_evidence
                DraftAlternativeGap.COST_COMPARISON_UNAVAILABLE -> R.string.alternative_gap_cost
                DraftAlternativeGap.RESOURCE_COMPARISON_UNAVAILABLE -> R.string.alternative_gap_resource
                DraftAlternativeGap.MEASURED_QUALITY_UNAVAILABLE -> R.string.alternative_gap_quality
                DraftAlternativeGap.RUNTIME_READINESS_NOT_ESTABLISHED -> R.string.alternative_gap_readiness
            }), Format.LOCAL))
        }
    }

    private fun findingFields(row: DraftAlternativeRuleObservation): List<Field> = buildList {
        add(Field("rule_id", row.ruleId))
        add(Field("current_to_candidate.outcome_changed", row.outcomeChanged.toString(), Format.BOOLEAN))
        add(Field("current_to_candidate.details_changed", row.detailsChanged.toString(), Format.BOOLEAN))
        fun finding(prefix: String, value: DraftRuleFinding) {
            add(Field("$prefix.rule_id", value.ruleId))
            add(Field("$prefix.outcome", value.outcome))
            add(Field("$prefix.severity", value.severity))
            add(Field("$prefix.code", value.code))
            add(Field("$prefix.message", value.message))
            add(Field("$prefix.remediation", value.remediation))
            add(Field("$prefix.selection_ids.length", value.selectionIds.size.toString(), Format.LOCAL))
            value.selectionIds.forEachIndexed { index, id -> add(Field("$prefix.selection_ids[$index]", id)) }
        }
        finding("current", row.current)
        finding("candidate", row.candidate)
        finding("slot_empty", row.slotEmpty)
        val reason = row.candidateReason
        add(Field("candidate_reason.present", (reason != null).toString(), Format.BOOLEAN))
        if (reason == null) add(Field("candidate_reason", activity.getString(R.string.alternative_no_reason), Format.LOCAL))
        else {
            add(Field("candidate_reason.baseline_outcome", reason.baselineOutcome))
            finding("candidate_reason.finding", reason.finding)
        }
    }

    private fun outcomeLabel(value: String): Int = when (value) {
        "pass" -> R.string.alternative_pass
        "fail" -> R.string.alternative_fail
        "unknown" -> R.string.alternative_unknown
        else -> R.string.alternative_skipped // The Core inspector admits only the four frozen outcomes.
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val VALUE_PART_SIZE = 160
    }
}
