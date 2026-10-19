package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
import ai.pickyour.core.evaluation.CompatibleOptionSlot

/** Explicit local draft edits. Recovered input never grants replacement save authority. */
internal class SelectionEditSection(private val activity: Activity,
    private val commit: (PreviewStoredDraft, String?, () -> PreviewSelectionPreparation, (Boolean, String) -> Unit) -> Unit,
    private val compareOptions: (CompatiblePickerLaunch) -> Unit = {},
    private val retireOptions: () -> Unit = {},
) : LinearLayout(activity) {
    private var closed = false
    private var editable = false
    private var baseline: PreviewStoredDraft? = null
    private var currentDraftId: String? = null
    private var recovery: ComponentFormRecovery = ComponentFormRecovery.None
    private class FormBinding(val dialog: AlertDialog, val capture: (Boolean) -> ComponentFormRecovery) {
        var saving = false
        var onFreeze: (Boolean) -> Unit = {}
        var onClose: () -> Unit = {}
        var onStateChange: () -> Unit = {}
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
    private var reviewAvailable = false
    internal var onReviewAvailabilityChanged: (() -> Unit)? = null

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
                .setItems(items.map { ComponentSelectionText.catalog(activity, it) }.toTypedArray()) { _, index ->
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

    internal fun canReviewCurrentChoices(expected: PreviewStoredDraft): Boolean = canOpen(expected) &&
        (if (expected.components.selections.isEmpty()) choose.isEnabled else manage.isEnabled)

    private fun publishReviewAvailability() {
        val available = baseline?.let(::canReviewCurrentChoices) == true
        if (available == reviewAvailable) return
        reviewAvailable = available
        onReviewAvailabilityChanged?.invoke()
    }

    /** Navigate through the existing current-record controls; all later editor checks still apply. */
    internal fun reviewCurrentChoices(expected: PreviewStoredDraft) {
        if (!canReviewCurrentChoices(expected)) return
        if (expected.components.selections.isEmpty()) choose.performClick() else manage.performClick()
    }

    fun update(record: PreviewStoredDraft?, enabled: Boolean, draftId: String? = currentDraftId) {
        if (closed) return
        if (baseline !== record || editable != enabled || currentDraftId != draftId) retireOptions()
        baseline = record; editable = enabled; currentDraftId = draftId
        status.text = activity.getString(R.string.component_count, (record?.components?.selections?.size ?: 0).toString())
        val noRecovery = recovery === ComponentFormRecovery.None
        manage.isEnabled = enabled && noRecovery && formBinding == null && record?.components?.selections?.isNotEmpty() == true
        choose.isEnabled = enabled && noRecovery && formBinding == null && record != null && catalogHash != null && catalogItems.isNotEmpty()
        recoveryStatus.visibility = if (noRecovery) View.GONE else View.VISIBLE
        recoveryStatus.setText(when (recovery) {
            ComponentFormRecovery.Unavailable -> R.string.component_recovery_unavailable
            ComponentFormRecovery.UriUserinfoExcluded -> R.string.component_recovery_userinfo_excluded
            else -> R.string.component_recovery_pending
        })
        reviewRecovery.visibility = if (recovery is ComponentFormRecovery.Pending ||
            recovery is ComponentFormRecovery.TypedContextPending) View.VISIBLE else View.GONE
        discardRecovery.visibility = if (noRecovery) View.GONE else View.VISIBLE
        formBinding?.onStateChange?.invoke()
        publishReviewAvailability()
    }
    fun restore(state: Bundle?) {
        if (closed) return
        recovery = ComponentFormRecovery.read(state)
        update(baseline, editable)
    }
    fun save(outState: Bundle) {
        val form = formBinding?.takeIf { open(it.dialog) }
        (form?.let { it.capture(it.saving) } ?: recovery).save(outState)
    }
    fun catalog(items: List<PreviewCatalogItem>, hash: String?) {
        if (closed) return
        retireOptions()
        catalogItems = items.filter { PreviewSelectionForms.supported(it.kind) }.toList()
        catalogHash = hash
        update(baseline, editable)
    }
    fun close() {
        retireOptions()
        closed = true; editable = false; baseline = null; manage.isEnabled = false; choose.isEnabled = false
        reviewRecovery.isEnabled = false; discardRecovery.isEnabled = false
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
        formBinding = null; recovery = ComponentFormRecovery.None
        publishReviewAvailability()
        onReviewAvailabilityChanged = null
    }
    private fun show(dialog: AlertDialog, feedback: TextView? = null): AlertDialog {
        retireOptions()
        dialogs += dialog
        if (feedback != null) dialogFeedback[dialog] = feedback
        dialog.setOnDismissListener {
            dialogs.remove(dialog); dialogFeedback.remove(dialog)
            if (formBinding?.dialog === dialog) {
                retireOptions()
                formBinding?.onClose?.invoke()
                formBinding = null
                update(baseline, editable)
            }
        }
        dialog.show()
        return dialog
    }
    private fun open(dialog: AlertDialog) = dialog.isShowing && dialog in dialogs
    private fun label(selection: LocalSelection) = ComponentSelectionText.selection(activity, selection)

    private fun reviewPendingInput() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        (recovery as? ComponentFormRecovery.TypedContextPending)?.let {
            reviewTypedContextInput(it.form)
            return
        }
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

    private fun reviewTypedContextInput(pending: ContextPolicyFormCheckpoint) {
        val expected = baseline
        val selection = expected?.components?.selections?.singleOrNull { it.id == pending.selectionId }
        if (editable && expected != null && currentDraftId == pending.draftId &&
            expected.baselineIdentity == pending.baseline && !pending.wasSaving &&
            selection != null && selection.kind == PreviewContextPolicySettings.KIND) {
            recovery = ComponentFormRecovery.None
            update(baseline, editable)
            contextSettingsForm(expected, selection, pending)
            return
        }
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply {
            setText(if (pending.wasSaving) R.string.component_recovery_saving else R.string.component_recovery_changed)
        })
        layout.addView(TextView(activity).apply {
            text = activity.getString(R.string.component_recovery_identity, pending.draftId, pending.selectionId)
        })
        layout.addView(ContextPolicySettingsEditor.readOnly(activity, pending.input))
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
            activity.getString(R.string.component_remove), activity.getString(R.string.options_open))
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle(label(selection)).setItems(options) { _, index ->
            if (!canOpen(expected) || !open(picker)) return@setItems
            when (index) {
                0 -> settingsForm(expected, selection)
                1 -> roleForm(expected, selection)
                2 -> confirm(expected, null, label(selection), toggleWarning(selection.kind)) {
                    PreviewSelectionPreparation.Ready(SelectionCommand.SetEnabled(selection.id, !selection.enabled))
                }
                3 -> {
                    val links = expected.components.links.filter { it.fromSelectionId == selection.id || it.toSelectionId == selection.id }.map { it.id }.sorted()
                    confirm(expected, null, label(selection), activity.getString(R.string.component_remove_warning, links.joinToString(", ").ifEmpty { "0" })) {
                        PreviewSelectionPreparation.Ready(SelectionCommand.Remove(selection.id))
                    }
                }
                4 -> compareReplacement(expected, selection)
            }
        }.setNegativeButton(R.string.close, null).create())
    }

    private fun compareReplacement(expected: PreviewStoredDraft, selection: LocalSelection) {
        val hash = catalogHash ?: return
        if (!canOpen(expected)) return
        if (!selection.enabled) {
            show(AlertDialog.Builder(activity).setTitle(R.string.options_open).setMessage(R.string.options_enabled_required)
                .setNegativeButton(R.string.close, null).create())
            return
        }
        val items = catalogItems.filter { it.kind == selection.kind }
        val scope = Any()
        var live: Any? = scope
        fun current() = live === scope && canOpen(expected) && catalogHash == hash &&
            catalogItems.filter { it.kind == selection.kind } == items
        compareOptions(CompatiblePickerLaunch(expected, CompatibleOptionSlot(selection.kind, selection.role, selection.id, true),
            hash, items, { item -> prepareCompatibleReplacement(selection, item) }, ::current) { item ->
            if (current() && item in items) {
                live = null
                confirm(expected, hash, label(selection), ComponentSelectionText.format(activity, R.string.component_replace_warning, item.id, item.version, hash)) {
                    PreviewSelectionForms.replace(selection, item, hash)
                }
            }
        })
    }

    /** Exact item/version/hash is carried separately from the existing opaque sample picker. */
    fun fromCatalog(expected: PreviewStoredDraft, item: PreviewCatalogItem, hash: String) {
        if (!canOpen(expected) || catalogHash != hash || item !in catalogItems || !PreviewSelectionForms.supported(item.kind)) return
        val targets = expected.components.selections.filter { it.kind == item.kind }
        val labels = listOf(activity.getString(R.string.component_add)) + targets.map { activity.getString(R.string.component_replace, label(it)) }
        lateinit var picker: AlertDialog
        picker = show(AlertDialog.Builder(activity).setTitle(ComponentSelectionText.catalogIdentity(activity, item))
            .setItems(labels.toTypedArray()) { _, index ->
                if (!canOpen(expected) || !open(picker) || catalogHash != hash || item !in catalogItems) return@setItems
                if (index == 0) addForm(expected, item, hash) else {
                    val target = targets[index - 1]
                    confirm(expected, hash, label(target), ComponentSelectionText.format(activity, R.string.component_replace_warning, item.id, item.version, hash)) {
                        PreviewSelectionForms.replace(target, item, hash)
                    }
                }
            }.setNegativeButton(R.string.close, null).create())
    }

    private fun feedback() = TextView(activity).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private fun foundationGuidance(layout: LinearLayout, kind: String, showRoleChoice: Boolean = false) {
        val guidance = when (kind) {
            "workload" -> R.string.component_workload_guidance
            "deployment_mode" -> R.string.component_deployment_guidance
            else -> return
        }
        layout.addView(TextView(activity).apply { setText(guidance) })
        if (showRoleChoice) layout.addView(TextView(activity).apply { setText(R.string.component_foundation_role_guidance) })
    }
    private fun retrievalGuidanceResource(kind: String): Int? = when (kind) {
        "data_source" -> R.string.component_data_source_guidance
        "knowledge_base" -> R.string.component_knowledge_base_guidance
        "embedding_model" -> R.string.component_embedding_model_guidance
        "vector_database" -> R.string.component_vector_database_guidance
        "rag_pipeline" -> R.string.component_rag_pipeline_guidance
        else -> null
    }
    private fun retrievalGuidance(layout: LinearLayout, kind: String) {
        val guidance = retrievalGuidanceResource(kind) ?: return
        layout.addView(TextView(activity).apply { setText(guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_retrieval_intent_guidance) })
    }
    private fun agentGuidanceResource(kind: String): Int? = when (kind) {
        "agent_framework" -> R.string.component_agent_framework_guidance
        "tool" -> R.string.component_tool_guidance
        "mcp_server" -> R.string.component_mcp_server_guidance
        "memory_store" -> R.string.component_memory_store_guidance
        "integration" -> R.string.component_integration_guidance
        else -> null
    }
    private fun agentGuidance(layout: LinearLayout, kind: String) {
        val guidance = agentGuidanceResource(kind) ?: return
        layout.addView(TextView(activity).apply { setText(guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_agent_intent_guidance) })
    }
    private fun trainingGuidanceResource(kind: String): Int? = when (kind) {
        "training_framework" -> R.string.component_training_framework_guidance
        "training_compute" -> R.string.component_training_compute_guidance
        "training_dataset" -> R.string.component_training_dataset_guidance
        "fine_tuned_model" -> R.string.component_fine_tuned_model_guidance
        else -> null
    }
    private fun trainingGuidance(layout: LinearLayout, kind: String) {
        val guidance = trainingGuidanceResource(kind) ?: return
        layout.addView(TextView(activity).apply { setText(guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_training_intent_guidance) })
    }
    private fun guardrailGuidanceResource(kind: String): Int? = when (kind) {
        "guardrail" -> R.string.component_guardrail_guidance
        "evaluation_suite" -> R.string.component_evaluation_guidance
        else -> null
    }
    private fun guardrailGuidance(layout: LinearLayout, kind: String) {
        val guidance = guardrailGuidanceResource(kind) ?: return
        layout.addView(TextView(activity).apply { setText(guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_core_input_guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_core_guidance_unavailable) })
    }
    private fun operationalGuidanceResource(kind: String): Int? = when (kind) {
        "deployment_target" -> R.string.component_deployment_target_guidance
        "monitoring_stack" -> R.string.component_monitoring_stack_guidance
        "cost_policy" -> R.string.component_cost_policy_guidance
        else -> null
    }
    private fun operationalGuidance(layout: LinearLayout, kind: String) {
        val guidance = operationalGuidanceResource(kind) ?: return
        layout.addView(TextView(activity).apply { setText(guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_core_input_guidance) })
        layout.addView(TextView(activity).apply { setText(R.string.component_operational_guidance_unavailable) })
    }
    private fun toggleWarning(kind: String): String = activity.getString(R.string.component_toggle_warning) +
        when {
            retrievalGuidanceResource(kind) != null -> "\n\n" + activity.getString(R.string.component_retrieval_intent_guidance)
            agentGuidanceResource(kind) != null -> "\n\n" + activity.getString(R.string.component_agent_intent_guidance)
            trainingGuidanceResource(kind) != null -> "\n\n" + activity.getString(R.string.component_training_intent_guidance)
            guardrailGuidanceResource(kind) != null -> "\n\n" + activity.getString(R.string.component_core_input_guidance)
            operationalGuidanceResource(kind) != null -> "\n\n" + activity.getString(R.string.component_core_input_guidance)
            else -> ""
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
        var currentItem = item
        val warning = TextView(activity).apply { text = ComponentSelectionText.format(activity, R.string.component_add_warning, item.id, item.version, hash) }.also(layout::addView)
        foundationGuidance(layout, item.kind, showRoleChoice = true)
        retrievalGuidance(layout, item.kind)
        agentGuidance(layout, item.kind)
        trainingGuidance(layout, item.kind)
        guardrailGuidance(layout, item.kind)
        operationalGuidance(layout, item.kind)
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_userinfo_limit) })
        val role = field(layout, R.string.component_role, restored?.role.orEmpty())
        val settings = field(layout, R.string.component_settings, restored?.settings ?: "{}")
        val enabled = CheckBox(activity).apply {
            setText(R.string.component_enabled); isChecked = restored?.enabled ?: false; isSaveEnabled = false
        }.also(layout::addView)
        val compare = Button(activity).apply {
            id = R.id.options_form_compare; setText(R.string.options_open)
            setSingleLine(false); ellipsize = null
        }.also(layout::addView)
        val feedback = feedback().also(layout::addView)
        val selectionId = restored?.selectionId ?: ("selection_" + java.util.UUID.randomUUID().toString().replace("-", ""))
        val dialog = show(AlertDialog.Builder(activity).setTitle(R.string.component_add).setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        var inputEpoch: Any = Any()
        fun changed() { inputEpoch = Any(); retireOptions() }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        role.addTextChangedListener(watcher); settings.addTextChangedListener(watcher)
        enabled.setOnCheckedChangeListener { _, _ -> changed() }
        formBinding = FormBinding(dialog) { saving ->
            ComponentFormCheckpoint(ComponentFormCheckpoint.ADD, draftId, expected.baselineIdentity, selectionId,
                currentItem.id, currentItem.version, hash, item.kind, role.text.toString(), settings.text.toString(), enabled.isChecked, saving)
                .captureNewSettings(item.kind)
        }
        update(baseline, editable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val form = PreviewSelectionForm(role.text.toString(), enabled.isChecked, settings.text.toString())
            submit(dialog, expected, hash) { PreviewSelectionForms.add(selectionId, currentItem, hash, form) }
        }
        compare.setOnClickListener {
            if (!active(expected) || !open(dialog) || formBinding?.saving == true) return@setOnClickListener
            if (!enabled.isChecked) { feedback.setText(R.string.options_enabled_required); return@setOnClickListener }
            val form = PreviewSelectionForm(role.text.toString(), true, settings.text.toString())
            val epoch = inputEpoch
            val items = catalogItems.filter { it.kind == item.kind }
            fun current() = active(expected) && open(dialog) && formBinding?.dialog === dialog &&
                formBinding?.saving == false && inputEpoch === epoch && catalogHash == hash &&
                role.text.toString() == form.role && settings.text.toString() == form.settingsJson && enabled.isChecked
            compareOptions(CompatiblePickerLaunch(expected, CompatibleOptionSlot(item.kind, form.role, selectionId, false),
                hash, items, { candidate ->
                    val prepared = PreviewSelectionForms.add(selectionId, candidate, hash, form) as? PreviewSelectionPreparation.Ready
                    (prepared?.command as? SelectionCommand.Add)?.selection
                }, ::current) { candidate ->
                if (current() && candidate in items) {
                    currentItem = candidate
                    warning.text = ComponentSelectionText.format(activity, R.string.component_add_warning, candidate.id, candidate.version, hash)
                    submit(dialog, expected, hash) { PreviewSelectionForms.add(selectionId, candidate, hash, form) }
                }
            })
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
        if (restored == null && selection.kind == PreviewContextPolicySettings.KIND) {
            contextSettingsForm(expected, selection)
            return
        }
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { setText(R.string.component_settings_warning) })
        foundationGuidance(layout, selection.kind)
        retrievalGuidance(layout, selection.kind)
        agentGuidance(layout, selection.kind)
        trainingGuidance(layout, selection.kind)
        guardrailGuidance(layout, selection.kind)
        operationalGuidance(layout, selection.kind)
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_userinfo_limit) })
        val settings = field(layout, R.string.component_settings, text)
        val feedback = feedback().also(layout::addView)
        val dialog = show(AlertDialog.Builder(activity).setTitle(label(selection)).setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        formBinding = FormBinding(dialog) { saving ->
            ComponentFormCheckpoint(ComponentFormCheckpoint.CONFIGURE, draftId, expected.baselineIdentity, selection.id,
                null, null, null, null, "", settings.text.toString(), false, saving)
                .captureNewSettings(selection.kind)
        }
        update(baseline, editable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected)) return@setOnClickListener
            val raw = settings.text.toString()
            submit(dialog, expected, null) { PreviewSelectionForms.configure(selection, raw) }
        }
    }
    private fun contextSettingsForm(expected: PreviewStoredDraft, selection: LocalSelection,
        restored: ContextPolicyFormCheckpoint? = null) {
        if (!canOpen(expected) || selection.kind != PreviewContextPolicySettings.KIND) return
        val draftId = requireNotNull(currentDraftId)
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { setText(R.string.component_settings_warning) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        lateinit var dialog: AlertDialog
        val editor = ContextPolicySettingsEditor(activity, restored?.input ?: PreviewContextPolicySettings.initial(selection)) {
            active(expected) && open(dialog) && formBinding?.saving == false
        }.also(layout::addView)
        val feedback = feedback().also(layout::addView)
        dialog = show(AlertDialog.Builder(activity).setTitle(label(selection))
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        val binding = FormBinding(dialog) { saving ->
            ContextPolicyFormCheckpoint(draftId, expected.baselineIdentity, selection.id, editor.input(), saving).recovery()
        }
        binding.onFreeze = editor::freeze
        binding.onClose = editor::close
        binding.onStateChange = {
            editor.freeze(binding.saving || !active(expected))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !binding.saving && active(expected)
        }
        formBinding = binding
        update(baseline, editable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!active(expected) || binding.saving) return@setOnClickListener
            val raw = editor.input()
            val result = java.util.concurrent.atomic.AtomicReference<PreviewContextPolicyPreparation?>()
            submit(dialog, expected, null, onRejected = {
                if (active(expected) && editor.input() == raw) editor.showIssues(result.get()?.issues.orEmpty())
            }) {
                PreviewContextPolicySettings.configure(selection, raw).also(result::set).preparation
            }
        }
    }

    private fun roleForm(expected: PreviewStoredDraft, selection: LocalSelection, restored: ComponentFormCheckpoint? = null) {
        if (!canOpen(expected)) return
        val draftId = requireNotNull(currentDraftId)
        val layout = LinearLayout(activity).apply { orientation = VERTICAL }
        layout.addView(TextView(activity).apply { text = ComponentSelectionText.format(activity, R.string.component_role_warning, selection.id) })
        layout.addView(TextView(activity).apply { setText(R.string.component_recovery_limit) })
        val role = field(layout, R.string.component_role, restored?.role ?: selection.role)
        val feedback = feedback().also(layout::addView)
        val dialog = show(AlertDialog.Builder(activity).setTitle(R.string.component_edit_role)
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.component_commit, null).create(), feedback)
        formBinding = FormBinding(dialog) { saving ->
            ComponentFormRecovery.Pending(ComponentFormCheckpoint(ComponentFormCheckpoint.ROLE, draftId, expected.baselineIdentity, selection.id,
                null, null, null, null, role.text.toString(), "", false, saving))
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
            if (active(expected)) submit(dialog, expected, hash, prepare = prepare)
        }
    }
    private fun submit(dialog: AlertDialog, expected: PreviewStoredDraft, hash: String?,
        onRejected: (() -> Unit)? = null, prepare: () -> PreviewSelectionPreparation) {
        if (!active(expected) || !open(dialog) || hash != null && hash != catalogHash) return
        retireOptions()
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
                onRejected?.invoke()
            }
        }
    }
    private fun freezeForm(dialog: AlertDialog, frozen: Boolean) {
        fun visit(view: View) {
            if (view is EditText || view is CheckBox || view.id == R.id.options_form_compare) view.isEnabled = !frozen
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        dialog.window?.decorView?.let(::visit)
        formBinding?.takeIf { it.dialog === dialog }?.onFreeze?.invoke(frozen)
        dialog.setCancelable(!frozen)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !frozen
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !frozen
    }
}
