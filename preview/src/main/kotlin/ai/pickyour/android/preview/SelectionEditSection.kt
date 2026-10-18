package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*

/** Explicit local draft edits. Recovered input never grants replacement save authority. */
class SelectionEditSection(private val activity: Activity,
    private val commit: (PreviewStoredDraft, String?, () -> PreviewSelectionPreparation, (Boolean, String) -> Unit) -> Unit,
) : LinearLayout(activity) {
    private var closed = false
    private var editable = false
    private var baseline: PreviewStoredDraft? = null
    private var currentDraftId: String? = null
    private var recovery: ComponentFormRecovery = ComponentFormRecovery.None
    private class FormBinding(val dialog: AlertDialog, val checkpoint: (Boolean) -> ComponentFormCheckpoint) {
        var saving = false
    }
    private var formBinding: FormBinding? = null
    private val dialogs = mutableListOf<AlertDialog>()
    private val dialogFeedback = mutableMapOf<AlertDialog, TextView>()
    private val status = TextView(activity)
    private val manage = Button(activity)
    private val choose = Button(activity)
    private val recoveryStatus = TextView(activity)
    private val reviewRecovery = Button(activity)
    private val discardRecovery = Button(activity)
    private var catalogHash: String? = null
    private var catalogItems: List<PreviewCatalogItem> = emptyList()

    init {
        orientation = VERTICAL
        addView(TextView(activity).apply {
            setText(R.string.component_heading); textSize = 22f
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        addView(TextView(activity).apply { setText(R.string.component_limits) })
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status)
        manage.setText(R.string.component_manage)
        manage.setOnClickListener { chooseSelection() }
        addView(manage)
        choose.setText(R.string.component_choose_catalog)
        choose.setOnClickListener {
            val expected = baseline ?: return@setOnClickListener
            val hash = catalogHash ?: return@setOnClickListener
            if (!canOpen(expected)) return@setOnClickListener
            val items = catalogItems
            lateinit var picker: AlertDialog
            picker = show(AlertDialog.Builder(activity).setTitle(R.string.component_choose_catalog)
                .setItems(items.map { "${it.kind} · ${it.id} @ ${it.version}" }.toTypedArray()) { _, index ->
                    if (canOpen(expected) && open(picker) && catalogHash == hash && catalogItems == items) fromCatalog(expected, items[index], hash)
                }.setNegativeButton(R.string.close, null).create())
        }
        addView(choose)
        recoveryStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(recoveryStatus)
        reviewRecovery.setText(R.string.component_recovery_review)
        reviewRecovery.setOnClickListener { reviewPendingInput() }
        addView(reviewRecovery)
        discardRecovery.setText(R.string.component_recovery_discard)
        discardRecovery.setOnClickListener {
            if (!closed) { recovery = ComponentFormRecovery.None; update(baseline, editable) }
        }
        addView(discardRecovery)
        update(null, false)
    }

    private fun active(expected: PreviewStoredDraft) = !closed && !activity.isDestroyed && !activity.isFinishing &&
        editable && baseline === expected && currentDraftId != null && recovery === ComponentFormRecovery.None
    private fun canOpen(expected: PreviewStoredDraft) = active(expected) && formBinding == null
    fun update(record: PreviewStoredDraft?, enabled: Boolean, draftId: String? = currentDraftId) {
        if (closed) return
        baseline = record; editable = enabled; currentDraftId = draftId
        status.text = activity.getString(R.string.component_count, (record?.components?.selections?.size ?: 0).toString())
        val noRecovery = recovery === ComponentFormRecovery.None
        manage.isEnabled = enabled && noRecovery && formBinding == null && record?.components?.selections?.isNotEmpty() == true
        choose.isEnabled = enabled && noRecovery && formBinding == null && record != null && catalogHash != null && catalogItems.isNotEmpty()
        recoveryStatus.visibility = if (noRecovery) View.GONE else View.VISIBLE
        recoveryStatus.setText(if (recovery === ComponentFormRecovery.Unavailable) R.string.component_recovery_unavailable
            else R.string.component_recovery_pending)
        reviewRecovery.visibility = if (recovery is ComponentFormRecovery.Pending) View.VISIBLE else View.GONE
        discardRecovery.visibility = if (noRecovery) View.GONE else View.VISIBLE
    }
    fun restore(state: Bundle?) {
        if (closed) return
        recovery = ComponentFormRecovery.read(state)
        update(baseline, editable)
    }
    fun save(outState: Bundle) {
        val form = formBinding?.takeIf { open(it.dialog) }
        (form?.let { ComponentFormRecovery.Pending(it.checkpoint(it.saving)) } ?: recovery).save(outState)
    }
    fun catalog(items: List<PreviewCatalogItem>, hash: String?) {
        if (closed) return
        catalogItems = items.filter { PreviewSelectionForms.supported(it.kind) }.toList()
        catalogHash = hash
        update(baseline, editable)
    }
    fun close() {
        closed = true; editable = false; baseline = null; manage.isEnabled = false; choose.isEnabled = false
        reviewRecovery.isEnabled = false; discardRecovery.isEnabled = false
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
        formBinding = null; recovery = ComponentFormRecovery.None
    }
    private fun show(dialog: AlertDialog, feedback: TextView? = null): AlertDialog {
        dialogs += dialog
        if (feedback != null) dialogFeedback[dialog] = feedback
        dialog.setOnDismissListener {
            dialogs.remove(dialog); dialogFeedback.remove(dialog)
            if (formBinding?.dialog === dialog) { formBinding = null; update(baseline, editable) }
        }
        dialog.show()
        return dialog
    }
    private fun open(dialog: AlertDialog) = dialog.isShowing && dialog in dialogs
    private fun label(selection: LocalSelection) = "${selection.kind} · ${selection.role} · ${selection.id}"

    private fun reviewPendingInput() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        val pending = (recovery as? ComponentFormRecovery.Pending)?.form ?: return
        val expected = baseline
        val exactDraft = editable && expected != null && currentDraftId == pending.draftId &&
            expected.baselineIdentity == pending.baseline && !pending.wasSaving
        val item = catalogItems.singleOrNull { it.id == pending.itemId && it.version == pending.itemVersion && it.kind == pending.kind }
        val selection = expected?.components?.selections?.singleOrNull { it.id == pending.selectionId }
        if (exactDraft && when (pending.mode) {
                ComponentFormCheckpoint.ADD -> item != null && catalogHash == pending.catalogHash
                ComponentFormCheckpoint.CONFIGURE, ComponentFormCheckpoint.ROLE -> selection != null && PreviewSelectionForms.supported(selection.kind)
                else -> false
            }) {
            recovery = ComponentFormRecovery.None
            update(baseline, editable)
            when (pending.mode) {
                ComponentFormCheckpoint.ADD -> addForm(expected, requireNotNull(item), requireNotNull(pending.catalogHash), pending)
                ComponentFormCheckpoint.CONFIGURE -> settingsForm(expected, requireNotNull(selection), pending)
                ComponentFormCheckpoint.ROLE -> roleForm(expected, requireNotNull(selection), pending)
            }
            return
        }
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply {
            setText(if (pending.wasSaving) R.string.component_recovery_saving else R.string.component_recovery_changed)
        })
        layout.addView(TextView(activity).apply {
            text = activity.getString(R.string.component_recovery_identity, pending.draftId, pending.selectionId)
        })
        fun value(label: Int, text: String) {
            layout.addView(TextView(activity).apply { setText(label) })
            layout.addView(TextView(activity).apply { this.text = text; setTextIsSelectable(true) })
        }
        if (pending.mode == ComponentFormCheckpoint.ADD) {
            layout.addView(TextView(activity).apply {
                text = activity.getString(R.string.component_recovery_catalog, pending.itemId, pending.itemVersion, pending.catalogHash)
            })
            value(R.string.component_role, pending.role)
            layout.addView(CheckBox(activity).apply { setText(R.string.component_enabled); isChecked = pending.enabled; isEnabled = false })
        }
        if (pending.mode == ComponentFormCheckpoint.ROLE) value(R.string.component_role, pending.role)
        if (pending.mode != ComponentFormCheckpoint.ROLE) value(R.string.component_settings, pending.settings)
        show(AlertDialog.Builder(activity).setTitle(R.string.component_recovery_review)
            .setView(ScrollView(activity).apply { addView(layout) }).setNegativeButton(R.string.close, null).create())
    }

    private fun chooseSelection() {
        val expected = baseline ?: return
        if (!canOpen(expected)) return
        val values = expected.components.selections
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle(R.string.component_manage)
            .setItems(values.map(::label).toTypedArray()) { _, index ->
                if (canOpen(expected) && open(picker)) chooseAction(expected, values[index])
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun chooseAction(expected: PreviewStoredDraft, selection: LocalSelection) {
        if (!canOpen(expected)) return
        if (!PreviewSelectionForms.supported(selection.kind)) {
            show(AlertDialog.Builder(activity).setTitle(label(selection)).setMessage(R.string.component_unsupported)
                .setNegativeButton(R.string.close, null).create())
            return
        }
        val options = arrayOf(activity.getString(R.string.component_configure), activity.getString(R.string.component_edit_role),
            activity.getString(if (selection.enabled) R.string.component_disable else R.string.component_enable),
            activity.getString(R.string.component_remove))
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle(label(selection)).setItems(options) { _, index ->
            if (!canOpen(expected) || !open(picker)) return@setItems
            when (index) {
                0 -> settingsForm(expected, selection)
                1 -> roleForm(expected, selection)
                2 -> confirm(expected, null, label(selection), activity.getString(R.string.component_toggle_warning)) {
                    PreviewSelectionPreparation.Ready(SelectionCommand.SetEnabled(selection.id, !selection.enabled))
                }
                3 -> {
                    val links = expected.components.links.filter { it.fromSelectionId == selection.id || it.toSelectionId == selection.id }.map { it.id }.sorted()
                    confirm(expected, null, label(selection), activity.getString(R.string.component_remove_warning, links.joinToString(", ").ifEmpty { "0" })) {
                        PreviewSelectionPreparation.Ready(SelectionCommand.Remove(selection.id))
                    }
                }
            }
        }.setNegativeButton(R.string.close, null).create())
    }

    /** Exact item/version/hash is carried separately from the existing opaque sample picker. */
    fun fromCatalog(expected: PreviewStoredDraft, item: PreviewCatalogItem, hash: String) {
        if (!canOpen(expected) || catalogHash != hash || item !in catalogItems || !PreviewSelectionForms.supported(item.kind)) return
        val targets = expected.components.selections.filter { it.kind == item.kind }
        val labels = listOf(activity.getString(R.string.component_add)) + targets.map { activity.getString(R.string.component_replace, label(it)) }
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle("${item.id} @ ${item.version}")
            .setItems(labels.toTypedArray()) { _, index ->
                if (!canOpen(expected) || !open(picker) || catalogHash != hash || item !in catalogItems) return@setItems
                if (index == 0) addForm(expected, item, hash) else {
                    val target = targets[index - 1]
                    confirm(expected, hash, label(target), activity.getString(R.string.component_replace_warning, item.id, item.version, hash)) {
                        PreviewSelectionForms.replace(target, item, hash)
                    }
                }
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun feedback() = TextView(activity).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun field(layout: LinearLayout, title: Int, value: String): EditText {
        val label = TextView(activity).apply { setText(title) }
        val editor = EditText(activity).apply {
            id = View.generateViewId(); setText(value); setSelectAllOnFocus(false)
            isSaveEnabled = false // Only the bounded primitive checkpoint retains this raw input.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        label.labelFor = editor.id; layout.addView(label); layout.addView(editor)
        return editor
    }
    private fun addForm(expected: PreviewStoredDraft, item: PreviewCatalogItem, hash: String, restored: ComponentFormCheckpoint? = null) {
        if (!canOpen(expected)) return
        val draftId = requireNotNull(currentDraftId)
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { text = activity.getString(R.string.component_add_warning, item.id, item.version, hash) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        val role = field(layout, R.string.component_role, restored?.role.orEmpty())
        val settings = field(layout, R.string.component_settings, restored?.settings ?: "{}")
        val enabled = CheckBox(activity).apply {
            setText(R.string.component_enabled); isChecked = restored?.enabled ?: false; isSaveEnabled = false
        }.also(layout::addView)
        val feedback = feedback().also(layout::addView)
        val selectionId = restored?.selectionId ?: ("selection_" + java.util.UUID.randomUUID().toString().replace("-", ""))
        val dialog = show(AlertDialog.Builder(activity).setTitle(R.string.component_add).setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        formBinding = FormBinding(dialog) { saving ->
            ComponentFormCheckpoint(ComponentFormCheckpoint.ADD, draftId, expected.baselineIdentity, selectionId,
                item.id, item.version, hash, item.kind, role.text.toString(), settings.text.toString(), enabled.isChecked, saving)
        }
        update(baseline, editable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val form = PreviewSelectionForm(role.text.toString(), enabled.isChecked, settings.text.toString())
            submit(dialog, expected, hash) { PreviewSelectionForms.add(selectionId, item, hash, form) }
        }
    }
    private fun settingsForm(expected: PreviewStoredDraft, selection: LocalSelection, restored: ComponentFormCheckpoint? = null) {
        if (!canOpen(expected)) return
        val draftId = requireNotNull(currentDraftId)
        val text = restored?.settings ?: PreviewSelectionForms.settingsText(selection)
        if (restored == null && text.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) {
            show(AlertDialog.Builder(activity).setTitle(label(selection)).setMessage(R.string.component_large_settings)
                .setNegativeButton(R.string.close, null).create())
            return
        }
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { setText(R.string.component_settings_warning) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        val settings = field(layout, R.string.component_settings, text)
        val feedback = feedback().also(layout::addView)
        val dialog = show(AlertDialog.Builder(activity).setTitle(label(selection)).setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        formBinding = FormBinding(dialog) { saving ->
            ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, draftId, expected.baselineIdentity, selection.id,
                null, null, null, null, "", settings.text.toString(), false, saving)
        }
        update(baseline, editable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val raw = settings.text.toString()
            submit(dialog, expected, null) { PreviewSelectionForms.configure(selection, raw) }
        }
    }
    private fun roleForm(expected: PreviewStoredDraft, selection: LocalSelection, restored: ComponentFormCheckpoint? = null) {
        if (!canOpen(expected)) return
        val draftId = requireNotNull(currentDraftId)
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { text = activity.getString(R.string.component_role_warning, selection.id) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        val role = field(layout, R.string.component_role, restored?.role ?: selection.role)
        val feedback = feedback().also(layout::addView)
        val dialog = show(AlertDialog.Builder(activity).setTitle(R.string.component_edit_role)
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        formBinding = FormBinding(dialog) { saving ->
            ComponentFormCheckpoint(ComponentFormCheckpoint.ROLE, draftId, expected.baselineIdentity, selection.id,
                null, null, null, null, role.text.toString(), "", false, saving)
        }
        update(baseline, editable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val raw = role.text.toString()
            submit(dialog, expected, null) { PreviewSelectionForms.configureRole(selection, raw) }
        }
    }
    private fun confirm(expected: PreviewStoredDraft, hash: String?, title: String, message: String,
        prepare: () -> PreviewSelectionPreparation) {
        if (!canOpen(expected)) return
        val feedback = feedback()
        val dialog = show(AlertDialog.Builder(activity).setTitle(title).setMessage(message).setView(feedback)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (active(expected)) submit(dialog, expected, hash, prepare)
        }
    }
    private fun submit(dialog: AlertDialog, expected: PreviewStoredDraft, hash: String?, prepare: () -> PreviewSelectionPreparation) {
        if (!active(expected) || !open(dialog) || hash != null && hash != catalogHash) return
        dialogFeedback[dialog]?.text = ""
        freezeForm(dialog, true)
        formBinding?.takeIf { it.dialog === dialog }?.saving = true
        commit(expected, hash, prepare) { acknowledged, message ->
            if (closed) return@commit
            formBinding?.takeIf { it.dialog === dialog }?.saving = false
            if (acknowledged) dialog.dismiss()
            else if (open(dialog)) {
                dialogFeedback[dialog]?.text = message
                freezeForm(dialog, false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = active(expected)
            }
        }
    }
    private fun freezeForm(dialog: AlertDialog, frozen: Boolean) {
        fun visit(view: View) {
            if (view is EditText || view is CheckBox) view.isEnabled = !frozen
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        dialog.window?.decorView?.let(::visit)
        dialog.setCancelable(!frozen)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !frozen
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !frozen
    }
}
