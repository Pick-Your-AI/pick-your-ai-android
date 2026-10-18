package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.*
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*

/** Explicit local draft edits. A form is transient and never grants replacement save authority. */
class SelectionEditSection(private val activity: Activity,
    private val commit: (PreviewStoredDraft, String?, () -> PreviewSelectionPreparation, (Boolean, String) -> Unit) -> Unit,
) : LinearLayout(activity) {
    private var closed = false
    private var editable = false
    private var baseline: PreviewStoredDraft? = null
    private val dialogs = mutableListOf<AlertDialog>()
    private val dialogFeedback = mutableMapOf<AlertDialog, TextView>()
    private val status = TextView(activity)
    private val manage = Button(activity)
    private val choose = Button(activity)
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
            if (!active(expected)) return@setOnClickListener
            val items = catalogItems
            lateinit var picker: AlertDialog
            picker = show(AlertDialog.Builder(activity).setTitle(R.string.component_choose_catalog)
                .setItems(items.map { "${it.kind} · ${it.id} @ ${it.version}" }.toTypedArray()) { _, index ->
                    if (active(expected) && open(picker) && catalogHash == hash && catalogItems == items) fromCatalog(expected, items[index], hash)
                }.setNegativeButton(R.string.close, null).create())
        }
        addView(choose)
    }

    private fun active(expected: PreviewStoredDraft) = !closed && !activity.isDestroyed && !activity.isFinishing && editable && baseline === expected
    fun update(record: PreviewStoredDraft?, enabled: Boolean) {
        if (closed) return
        baseline = record; editable = enabled
        status.text = activity.getString(R.string.component_count, (record?.components?.selections?.size ?: 0).toString())
        manage.isEnabled = enabled && record?.components?.selections?.isNotEmpty() == true
        choose.isEnabled = enabled && record != null && catalogHash != null && catalogItems.isNotEmpty()
    }
    fun catalog(items: List<PreviewCatalogItem>, hash: String?) {
        if (closed) return
        catalogItems = items.filter { PreviewSelectionForms.supported(it.kind) }.toList()
        catalogHash = hash
        update(baseline, editable)
    }
    fun close() {
        closed = true; editable = false; baseline = null; manage.isEnabled = false; choose.isEnabled = false
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
    }
    private fun show(dialog: AlertDialog, feedback: TextView? = null): AlertDialog {
        dialogs += dialog
        if (feedback != null) dialogFeedback[dialog] = feedback
        dialog.setOnDismissListener { dialogs.remove(dialog); dialogFeedback.remove(dialog) }
        dialog.show()
        return dialog
    }
    private fun open(dialog: AlertDialog) = dialog.isShowing && dialog in dialogs
    private fun label(selection: LocalSelection) = "${selection.kind} · ${selection.role} · ${selection.id}"

    private fun chooseSelection() {
        val expected = baseline ?: return
        if (!active(expected)) return
        val values = expected.components.selections
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle(R.string.component_manage)
            .setItems(values.map(::label).toTypedArray()) { _, index ->
                if (active(expected) && open(picker)) chooseAction(expected, values[index])
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun chooseAction(expected: PreviewStoredDraft, selection: LocalSelection) {
        if (!active(expected)) return
        if (!PreviewSelectionForms.supported(selection.kind)) {
            show(AlertDialog.Builder(activity).setTitle(label(selection)).setMessage(R.string.component_unsupported)
                .setNegativeButton(R.string.close, null).create())
            return
        }
        val options = arrayOf(activity.getString(R.string.component_configure),
            activity.getString(if (selection.enabled) R.string.component_disable else R.string.component_enable),
            activity.getString(R.string.component_remove))
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle(label(selection)).setItems(options) { _, index ->
            if (!active(expected) || !open(picker)) return@setItems
            when (index) {
                0 -> settingsForm(expected, selection)
                1 -> confirm(expected, null, label(selection), activity.getString(R.string.component_toggle_warning)) {
                    PreviewSelectionPreparation.Ready(SelectionCommand.SetEnabled(selection.id, !selection.enabled))
                }
                2 -> {
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
        if (!active(expected) || catalogHash != hash || item !in catalogItems || !PreviewSelectionForms.supported(item.kind)) return
        val targets = expected.components.selections.filter { it.kind == item.kind }
        val labels = listOf(activity.getString(R.string.component_add)) + targets.map { activity.getString(R.string.component_replace, label(it)) }
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle("${item.id} @ ${item.version}")
            .setItems(labels.toTypedArray()) { _, index ->
                if (!active(expected) || !open(picker) || catalogHash != hash || item !in catalogItems) return@setItems
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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        label.labelFor = editor.id; layout.addView(label); layout.addView(editor)
        return editor
    }
    private fun addForm(expected: PreviewStoredDraft, item: PreviewCatalogItem, hash: String) {
        if (!active(expected)) return
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { text = activity.getString(R.string.component_add_warning, item.id, item.version, hash) })
        val role = field(layout, R.string.component_role, "")
        val settings = field(layout, R.string.component_settings, "{}")
        val enabled = CheckBox(activity).apply { setText(R.string.component_enabled); isChecked = false }.also(layout::addView)
        val feedback = feedback().also(layout::addView)
        val selectionId = "selection_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val dialog = show(AlertDialog.Builder(activity).setTitle(R.string.component_add).setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val form = PreviewSelectionForm(role.text.toString(), enabled.isChecked, settings.text.toString())
            submit(dialog, expected, hash) { PreviewSelectionForms.add(selectionId, item, hash, form) }
        }
    }
    private fun settingsForm(expected: PreviewStoredDraft, selection: LocalSelection) {
        if (!active(expected)) return
        val text = PreviewSelectionForms.settingsText(selection)
        if (text.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) {
            show(AlertDialog.Builder(activity).setTitle(label(selection)).setMessage(R.string.component_large_settings)
                .setNegativeButton(R.string.close, null).create())
            return
        }
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { setText(R.string.component_settings_warning) })
        val settings = field(layout, R.string.component_settings, text)
        val feedback = feedback().also(layout::addView)
        val dialog = show(AlertDialog.Builder(activity).setTitle(label(selection)).setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val raw = settings.text.toString()
            submit(dialog, expected, null) { PreviewSelectionForms.configure(selection, raw) }
        }
    }
    private fun confirm(expected: PreviewStoredDraft, hash: String?, title: String, message: String,
        prepare: () -> PreviewSelectionPreparation) {
        if (!active(expected)) return
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
        commit(expected, hash, prepare) { acknowledged, message ->
            if (closed) return@commit
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
