package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.evaluation.*
import ai.pickyour.core.preview.*
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** A finite capture from the existing editor. Neither this object nor a verdict grants a save. */
internal class CompatiblePickerLaunch(
    val source: PreviewStoredDraft,
    val slot: CompatibleOptionSlot,
    val catalogHash: String,
    items: List<PreviewCatalogItem>,
    val candidate: (PreviewCatalogItem) -> LocalSelection?,
    val current: () -> Boolean,
    val choose: (PreviewCatalogItem) -> Unit,
) {
    val items = items.toList()
}

/**
 * Actual native caller for the stored-draft profile. Worker tasks capture immutable form inputs;
 * UI callbacks recheck the exact record, editor epoch, artifact container and lifecycle. A retired
 * session cannot start an evaluation, render a result, or use an old option after an ABA edit.
 */
internal class CompatibleOptionsPicker(
    private val activity: Activity,
    private val worker: Executor,
    private val evaluate: (DraftCompatibleOptionsRequest) -> DraftCompatibleOptionsOutcome = DraftCompatibleOptionsEvaluator()::evaluate,
    private val rulesInput: () -> InputStream = { activity.assets.open("ruleset.json") },
    private val inspectAlternative: (DraftAlternativeInspectionRequest, () -> Boolean) -> DraftAlternativeOutcome =
        DraftAlternativeInspector()::inspect,
    private val beforeInspect: () -> Unit = {},
    private val alive: () -> Boolean,
) {
    private class Session(val launch: CompatiblePickerLaunch, val catalog: ByteArray, val current: () -> Boolean) {
        val retired = AtomicBoolean(false)
        val coordinator = DraftCompatibleOptionsCoordinator()
        var page = 0
        var pageEpoch: Any = Any()
        var rowEpoch: Any = Any()
        var workRetired = AtomicBoolean(false)
        var result: DraftCompatibleOptionsOutcome.Completed? = null
        var request: DraftCompatibleOptionsRequest? = null
        var includeIncompatible = false
        var includeUnknown = false
        lateinit var dialog: AlertDialog
        lateinit var status: TextView
        lateinit var rows: LinearLayout
        lateinit var previous: Button
        lateinit var next: Button
        lateinit var inherited: Button
        val children = mutableListOf<AlertDialog>()
        var alternative: AlternativeInspectionBrowser? = null
    }
    private var session: Session? = null
    private var closed = false
    private fun active(s: Session) = !closed && !s.retired.get() && session === s && alive() &&
        !activity.isDestroyed && !activity.isFinishing && s.dialog.isShowing && s.current() && s.launch.current()

    fun dismiss() {
        val old = session
        session = null
        if (old != null) {
            old.retired.set(true); old.workRetired.set(true); old.coordinator.clear()
            retireChildren(old)
            old.dialog.dismiss()
        }
    }
    fun close() { closed = true; dismiss() }

    /** Retire child windows and their work while retaining the admitted parent and choice authority. */
    fun dismissInspection() { session?.let(::retireChildren) }

    fun show(launch: CompatiblePickerLaunch, catalog: ByteArray, current: () -> Boolean) {
        if (closed || !alive() || !current() || !launch.current()) return
        dismiss()
        val s = Session(launch, catalog.copyOf(), current)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        content.setPadding(padding, padding, padding, padding)
        content.addView(TextView(activity).apply { setText(R.string.options_scope) })
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.options_slot, launch.slot.kind, launch.slot.role, launch.slot.selectionId)
        })
        s.status = TextView(activity).apply {
            id = R.id.options_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setText(R.string.options_evaluating)
        }.also(content::addView)
        CheckBox(activity).apply {
            id = R.id.options_include_incompatible; setText(R.string.options_include_incompatible)
            isChecked = false; isSaveEnabled = false
            setOnCheckedChangeListener { _, value -> if (active(s)) { s.includeIncompatible = value; render(s) } }
        }.also(content::addView)
        CheckBox(activity).apply {
            id = R.id.options_include_unknown; setText(R.string.options_include_unknown)
            isChecked = false; isSaveEnabled = false
            setOnCheckedChangeListener { _, value -> if (active(s)) { s.includeUnknown = value; render(s) } }
        }.also(content::addView)
        s.inherited = Button(activity).apply {
            id = R.id.options_inherited; setText(R.string.options_inherited)
            setOnClickListener {
                val result = s.result ?: return@setOnClickListener
                if (currentResult(s, result)) details(s, activity.getString(R.string.options_inherited),
                    result.baselineFindings.joinToString("\n\n", transform = ::findingText))
            }
        }.also(content::addView)
        s.rows = LinearLayout(activity).apply { id = R.id.options_rows; orientation = LinearLayout.VERTICAL }.also(content::addView)
        s.previous = Button(activity).apply {
            id = R.id.options_previous; setText(R.string.catalog_previous)
            setOnClickListener { if (active(s) && s.page > 0) { s.page--; calculate(s) } }
        }.also(content::addView)
        s.next = Button(activity).apply {
            id = R.id.options_next; setText(R.string.catalog_next)
            setOnClickListener { if (active(s) && (s.page + 1) * PAGE_SIZE < s.launch.items.size) { s.page++; calculate(s) } }
        }.also(content::addView)
        s.dialog = AlertDialog.Builder(activity).setTitle(R.string.options_open)
            .setView(ScrollView(activity).apply { addView(content) }).setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.options_retry, null).create()
        session = s
        s.dialog.setOnDismissListener { if (session === s) dismiss() }
        s.dialog.show()
        s.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if (active(s)) calculate(s) }
        calculate(s)
    }

    private fun calculate(s: Session) {
        if (!active(s)) return
        s.coordinator.clear(); s.result = null; s.request = null
        s.workRetired.set(true)
        val workRetired = AtomicBoolean(false).also { s.workRetired = it }
        val epoch = Any().also { s.pageEpoch = it }
        retireChildren(s)
        s.rows.removeAllViews(); s.inherited.isEnabled = false
        s.status.setText(R.string.options_evaluating)
        s.previous.isEnabled = s.page > 0
        s.next.isEnabled = (s.page + 1) * PAGE_SIZE < s.launch.items.size
        val page = s.page
        val items = s.launch.items.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        try { worker.execute {
            if (s.retired.get() || workRetired.get()) return@execute
            val prepared = try {
                val c = requireNotNull(PreviewCompatibleOptionInputs.catalog(s.catalog))
                val r = rulesInput().use { requireNotNull(PreviewCompatibleOptionInputs.ruleset(readBounded(it))) }
                if (c.expectedHash.hex != s.launch.catalogHash) null else {
                    val candidates = items.map { requireNotNull(s.launch.candidate(it)) }
                    s.launch.source.prepareCompatibleSelections(c, r, s.launch.slot, candidates)
                }
            } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!active(s) || s.pageEpoch !== epoch) return@runOnUiThread
                val request = (prepared as? DraftCompatibleOptionsPreparation.Ready)?.request
                if (request == null || !request.belongsTo(s.launch.source)) {
                    s.status.setText(if ((prepared as? DraftCompatibleOptionsPreparation.Unavailable)?.reason ==
                        CompatibleOptionsUnavailable.OPTIONAL_INTENT_UNRESOLVED) R.string.options_optional_unresolved else R.string.options_unavailable)
                    return@runOnUiThread
                }
                s.request = request; s.coordinator.bind(request)
                val attempt = s.coordinator.start(request) ?: return@runOnUiThread
                try { worker.execute evaluation@{
                    if (s.retired.get() || workRetired.get()) return@evaluation
                    // start() is called again nowhere: this exact opaque attempt owns the result.
                    val result = try { evaluate(request) } catch (_: Exception) { null }
                    activity.runOnUiThread delivery@{
                        if (!active(s) || s.pageEpoch !== epoch || s.request !== request) return@delivery
                        if (result == null || !s.coordinator.complete(attempt, result)) {
                            s.status.setText(R.string.options_unavailable); return@delivery
                        }
                        if (result is DraftCompatibleOptionsOutcome.Completed) { s.result = result; render(s) }
                        else s.status.setText(R.string.options_unavailable)
                    }
                } } catch (_: RejectedExecutionException) {
                    s.coordinator.cancel(attempt); s.status.setText(R.string.options_unavailable)
                }
            }
        } } catch (_: RejectedExecutionException) { if (active(s) && s.pageEpoch === epoch) s.status.setText(R.string.options_unavailable) }
    }

    private fun currentResult(s: Session, result: DraftCompatibleOptionsOutcome.Completed) =
        active(s) && s.result === result && s.coordinator.isCurrent(result) &&
            s.request?.let { result.belongsTo(it) && it.belongsTo(s.launch.source) } == true

    private fun render(s: Session) {
        val result = s.result ?: return
        if (!currentResult(s, result)) return
        val rowEpoch = Any().also { s.rowEpoch = it }
        retireChildren(s)
        val b = result.binding
        s.status.text = activity.getString(R.string.options_status_with_binding,
            activity.getString(R.string.options_counts, s.page + 1,
                maxOf(1, (s.launch.items.size + PAGE_SIZE - 1) / PAGE_SIZE),
                activity.resources.getQuantityString(R.plurals.options_catalog_count, s.launch.items.size, s.launch.items.size),
                result.compatibleCount, result.incompatibleCount, result.unknownCount),
            activity.getString(R.string.options_binding, b.sourceRevision.toString(), b.catalogSnapshotId,
                b.catalogRevision.toString(), b.rulesetId, b.rulesetVersion))
        s.inherited.isEnabled = true
        s.rows.removeAllViews()
        val shown = result.visibleOptions(s.includeIncompatible, s.includeUnknown)
        if (shown.isEmpty()) s.rows.addView(TextView(activity).apply { setText(R.string.options_no_visible) })
        for (option in shown) {
            val item = s.launch.items.single { it.id == option.identity.catalogItemId && it.version == option.identity.catalogItemVersion }
            s.rows.addView(TextView(activity).apply {
                text = activity.getString(R.string.options_row, item.name, activity.getString(verdictLabel(option.verdict)))
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
            option.reasons.forEach { reason ->
                s.rows.addView(Button(activity).apply {
                    text = activity.getString(R.string.options_rule, reason.finding.ruleId, reason.finding.outcome)
                    setOnClickListener { if (s.rowEpoch === rowEpoch && currentResult(s, result)) details(s, reason.finding.ruleId,
                        activity.getString(R.string.options_reason_baseline, reason.baselineOutcome) + "\n\n" + findingText(reason.finding)) }
                })
            }
            s.rows.addView(Button(activity).apply {
                id = R.id.alternative_inspect
                setText(R.string.alternative_inspect)
                setOnClickListener {
                    if (s.rowEpoch !== rowEpoch || !currentResult(s, result)) return@setOnClickListener
                    beforeInspect()
                    if (s.rowEpoch !== rowEpoch || !currentResult(s, result)) return@setOnClickListener
                    val parent = s.request ?: return@setOnClickListener
                    retireChildren(s)
                    val browser = AlternativeInspectionBrowser(activity, worker, parent, result, option,
                        { s.rowEpoch === rowEpoch && s.request === parent && currentResult(s, result) }, inspectAlternative)
                    s.alternative = browser
                    browser.show()
                }
            })
            s.rows.addView(Button(activity).apply {
                text = activity.getString(R.string.options_choose, item.name, activity.getString(verdictLabel(option.verdict)))
                setOnClickListener {
                    if (s.rowEpoch !== rowEpoch || !currentResult(s, result)) return@setOnClickListener
                    lateinit var confirmation: AlertDialog
                    confirmation = AlertDialog.Builder(activity).setTitle(R.string.options_confirm)
                        .setMessage(activity.getString(R.string.options_confirm_message, item.name,
                            activity.getString(verdictLabel(option.verdict)), s.launch.slot.kind, s.launch.slot.role))
                        .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit) { _, _ ->
                            if (confirmation.isShowing && confirmation in s.children && s.rowEpoch === rowEpoch && currentResult(s, result)) {
                                dismiss() // One-use result authority is revoked before the canonical form/store callback.
                                s.launch.choose(item)
                            }
                        }.create()
                    child(s, confirmation)
                }
            })
        }
    }

    private fun retireChildren(s: Session) {
        val browser = s.alternative
        s.alternative = null
        val children = s.children.toList()
        s.children.clear()
        browser?.retire()
        children.forEach { it.dismiss() }
    }

    private fun child(s: Session, dialog: AlertDialog) {
        retireChildren(s)
        s.children += dialog
        dialog.setOnDismissListener { s.children.remove(dialog) }
        dialog.show()
    }
    private fun details(s: Session, title: String, message: String) {
        val text = TextView(activity).apply { this.text = message; setTextIsSelectable(true) }
        child(s, AlertDialog.Builder(activity).setTitle(title).setView(ScrollView(activity).apply { addView(text) })
            .setNegativeButton(R.string.close, null).create())
    }
    private fun findingText(f: DraftRuleFinding) = activity.getString(R.string.options_finding,
        f.ruleId, f.outcome, f.severity, f.code, f.message, f.remediation, f.selectionIds.joinToString(", "))
    private fun verdictLabel(v: CompatibleOptionVerdict) = when (v) {
        CompatibleOptionVerdict.COMPATIBLE -> R.string.options_compatible
        CompatibleOptionVerdict.INCOMPATIBLE -> R.string.options_incompatible
        CompatibleOptionVerdict.UNKNOWN -> R.string.options_unknown
    }
    private fun readBounded(stream: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n == -1) break
            require(output.size() + n <= 262_144)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    companion object { private const val PAGE_SIZE = PreviewCatalogBrowser.PAGE_SIZE }
}
