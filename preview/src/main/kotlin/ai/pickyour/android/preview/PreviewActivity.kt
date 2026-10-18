package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import ai.pickyour.core.preview.*
import ai.pickyour.core.drafts.Activation
import ai.pickyour.core.drafts.OptionalTier
import ai.pickyour.core.drafts.DraftStorageFailure
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import org.json.JSONObject
import java.util.concurrent.Executors
import ai.pickyour.core.preview.SyntheticCatalogEvidence
import ai.pickyour.core.evidence.EvidenceLabel

/** Guest preview over synthetic fixtures with explicit local save through the canonical store. */
class PreviewActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private lateinit var tierSection: OptionalTierSection
    private var selectedId: String? = null
    private var selectedVersion: String? = null
    private var selectedCatalogHash: String? = null
    private var loadedCatalogHash: String? = null
    private lateinit var title: EditText
    private lateinit var objective: EditText
    private lateinit var contextInput: EditText
    private lateinit var contextState: TextView
    private var domainContext = PreviewDraftContext()
    private var restoreContextFromDisk = false
    private lateinit var selection: TextView
    private lateinit var catalog: LinearLayout
    private lateinit var catalogStatus: TextView
    private lateinit var retry: Button
    private var items: List<SampleItem> = emptyList()
    private lateinit var save: Button
    private lateinit var reopen: Button
    private lateinit var clear: Button
    private lateinit var history: Button
    private lateinit var saveStatus: TextView
    private var draftLibrary: PreviewDraftLibrary? = null // Accessed only on the single worker.
    private var currentDraftId = PreviewDraftId("guest-draft")
    private var selectionResolved = false
    private lateinit var activeDraft: TextView
    private lateinit var libraryButton: Button
    private lateinit var newDraftButton: Button
    private lateinit var duplicateButton: Button
    private var savedRecord: PreviewStoredDraft? = null // UI-thread owned.
    private var storageReady = false
    private var storageBusy = true
    private var restoringFields = false
    private var restoration: PreviewDraftRestoration? = null


    private data class LoadedCatalog(val items: List<SampleItem>, val hash: String)
    private data class SampleItem(val id: String, val name: String, val kind: String, val version: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDraftId = PreviewDraftId(savedInstanceState?.getString("draftId") ?: "guest-draft")
        selectionResolved = savedInstanceState != null && savedInstanceState.getBoolean("selectionResolved", true)
        restoration = savedInstanceState?.let {
            PreviewDraftRestoration(it.getString("draftBaseline"), it.getBoolean("draftRequiresReopen", true))
        }
        restoreContextFromDisk = savedInstanceState != null && !savedInstanceState.getBoolean("contextInputsPresent", false)
        domainContext = PreviewDraftContext(savedInstanceState?.getString("objective"), savedInstanceState?.getString("contextInput"))
        selectedId = savedInstanceState?.getString("selectedId")
        selectedVersion = savedInstanceState?.getString("selectedVersion")
        selectedCatalogHash = savedInstanceState?.getString("selectedCatalogHash")
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun text(resource: Int, size: Float = 18f) = TextView(this).apply {
            setText(resource)
            textSize = size
        }.also(content::addView)
        text(R.string.notice, 20f)
        text(R.string.guest_draft, 22f).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        text(R.string.temporary)
        activeDraft = text(R.string.library_active).apply {
            text = if (selectionResolved) getString(R.string.library_active, currentDraftId.value) else getString(R.string.active_unknown)
        }
        val titleLabel = text(R.string.draft_title)
        title = EditText(this).apply {
            id = View.generateViewId()
            isSaveEnabled = false // Explicit restoration state never acknowledges a durable save.
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(120))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(savedInstanceState?.getString("title").orEmpty().take(120))
            setHint(R.string.title_hint)
        }
        titleLabel.labelFor = title.id
        content.addView(title)
        fun contextEditor(label: Int, hint: Int, initial: String?, changed: (String) -> Unit): EditText {
            val fieldLabel = text(label)
            return EditText(this).apply {
                id = View.generateViewId()
                isSaveEnabled = false
                minLines = 2; maxLines = 4
                filters = arrayOf(InputFilter.LengthFilter(16_384))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setHint(hint)
                setText(initial.orEmpty())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (!restoringFields) { changed(s.toString()); formChanged() }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
                fieldLabel.labelFor = id
            }.also(content::addView)
        }
        objective = contextEditor(R.string.context_objective, R.string.context_optional, domainContext.objective) {
            domainContext = domainContext.copy(objective = it)
        }
        contextInput = contextEditor(R.string.context_context, R.string.context_optional, domainContext.context) {
            domainContext = domainContext.copy(context = it)
        }
        text(R.string.context_limits)
        contextState = text(R.string.context_state)
        updateContextState()
        selection = text(R.string.no_selection).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        text(R.string.unknown)
        clear = Button(this).apply {
            setText(R.string.clear_draft)
            setOnClickListener {
                title.setText("")
                selectedId = null
                selectedVersion = null
                selectedCatalogHash = null
                applyContext(PreviewDraftContext())
                showSelection()
                formChanged()
            }
        }
        content.addView(clear)
        saveStatus = text(R.string.draft_loading).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        save = Button(this).apply { setText(R.string.save_draft); setOnClickListener { saveDraft() } }
        reopen = Button(this).apply {
            setText(R.string.reopen_draft)
            setOnClickListener {
                AlertDialog.Builder(this@PreviewActivity).setTitle(R.string.reopen_draft)
                    .setMessage(R.string.reopen_warning)
                    .setNegativeButton(R.string.keep_edits, null)
                    .setPositiveButton(R.string.reopen_confirm) { _, _ -> loadDraft(false) }.show()
            }
        }
        content.addView(save)
        content.addView(reopen)
        history = Button(this).apply { setText(R.string.draft_history); setOnClickListener { showHistory() } }
        content.addView(history)
        libraryButton = Button(this).apply { setText(R.string.library_open); setOnClickListener { showLibrary() } }
        newDraftButton = Button(this).apply { setText(R.string.library_new); setOnClickListener { confirmCreate(false) } }
        duplicateButton = Button(this).apply { setText(R.string.library_duplicate); setOnClickListener { confirmCreate(true) } }
        content.addView(libraryButton)
        content.addView(newDraftButton)
        content.addView(duplicateButton)
        tierSection = OptionalTierSection(this) { expected, tier, activation ->
            if (storageReady && !storageBusy && savedRecord === expected && matchesSaved())
                setDraftActivation(expected, tier, activation)
        }
        content.addView(tierSection)
        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { formChanged() }
            override fun afterTextChanged(s: Editable?) {}
        })
        text(R.string.catalog_heading, 22f).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        text(R.string.catalog_explanation)
        catalogStatus = text(R.string.working).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        retry = Button(this).apply { setText(R.string.retry_catalog); setOnClickListener { loadCatalog() } }
        content.addView(retry)
        catalog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(catalog)
        val status = text(R.string.ready).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val evidenceDetails = text(R.string.evidence_ready)
        val run = Button(this).apply { setText(R.string.run) }
        run.setOnClickListener {
            run.isEnabled = false
            status.setText(R.string.working)
            evidenceDetails.setText(R.string.evidence_ready)
            worker.execute {
                var evidence: SyntheticCatalogEvidence? = null
                val message = try {
                    assets.open("build.json").use {
                        if (FrozenSchemaValidator().validate(FrozenDocument.BUILD, it) == FrozenValidation.Valid) {
                            evidence = SyntheticCatalogEvidence.read(readCatalogFixture())
                            R.string.valid
                        } else R.string.invalid
                    }
                } catch (_: Exception) { R.string.unavailable }
                runOnUiThread {
                    if (!destroyed) {
                        status.setText(message)
                        evidenceDetails.text = evidence?.let { evidenceText(it) } ?: getString(R.string.evidence_unavailable)
                        run.isEnabled = true
                    }
                }
            }
        }
        content.addView(run)
        CostInspectionSection(this, worker) { !destroyed }.attach(content)
        FindingsInspectionSection(this, worker) { !destroyed }.attach(content)
        setContentView(ScrollView(this).apply { addView(content) })
        content.setOnApplyWindowInsetsListener { view, insets ->
            val padding = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            insets
        }
        // Also provide padding before the first insets dispatch.
        val padding = (24 * resources.displayMetrics.density).toInt()
        content.setPadding(padding, padding, padding, padding)
        updateEditingEnabled()
        loadCatalog()
        if (selectionResolved) loadDraft(true) else restoreActiveDraft(savedInstanceState != null)
    }

    private fun readCatalogFixture(): ByteArray = assets.open("catalog.json").use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            check(output.size() + count <= 1_048_576)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun loadCatalog() {
        retry.isEnabled = false
        catalogStatus.setText(R.string.working)
        worker.execute {
            val loaded = try {
                val bytes = readCatalogFixture()
                check(FrozenSchemaValidator().validate(FrozenDocument.CATALOG, bytes.inputStream()) == FrozenValidation.Valid)
                val rows = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("items")
                val rowsList = List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    SampleItem(row.getString("id"), row.getString("name"), row.getString("kind"), row.getString("version"))
                }
                LoadedCatalog(rowsList, java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) })
            } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    retry.isEnabled = true
                    catalog.removeAllViews()
                    items = loaded?.items.orEmpty()
                    loadedCatalogHash = loaded?.hash
                    catalogStatus.setText(if (loaded == null) R.string.catalog_unavailable else R.string.catalog_ready)
                    items.forEach { item ->
                        catalog.addView(Button(this).apply {
                            text = getString(R.string.item_button, item.name, item.kind)
                            isEnabled = !storageBusy
                            setOnClickListener { showDetails(item) }
                        })
                    }
                    showSelection()
                }
            }
        }
    }

    private fun showDetails(item: SampleItem) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.item_details, item.id, item.kind, item.version))
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.select_sample) { _, _ ->
                if (!storageBusy && loadedCatalogHash != null) {
                    selectedId = item.id
                    selectedVersion = item.version
                    selectedCatalogHash = loadedCatalogHash
                    showSelection()
                    formChanged()
                }
            }
            .show()
    }

    private fun showSelection() {
        val item = items.firstOrNull { it.id == selectedId && it.version == selectedVersion && selectedCatalogHash == loadedCatalogHash }
        selection.text = when {
            selectedId == null -> getString(R.string.no_selection)
            item == null -> getString(R.string.selection_unavailable)
            else -> getString(R.string.selected_sample, item.name)
        }
    }

    private fun applyContext(value: PreviewDraftContext) {
        domainContext = value
        val wasRestoring = restoringFields
        restoringFields = true
        objective.setText(value.objective.orEmpty())
        contextInput.setText(value.context.orEmpty())
        restoringFields = wasRestoring
        updateContextState()
    }

    private fun updateContextState() {
        if (!::contextState.isInitialized) return
        fun label(value: String?) = getString(when {
            value == null -> R.string.context_unanswered
            value.isEmpty() -> R.string.context_empty
            else -> R.string.context_entered
        })
        contextState.text = getString(R.string.context_state, label(domainContext.objective), label(domainContext.context))
    }

    private fun matchesSaved() = savedRecord?.let { it.fields == fields() && it.contextInput == domainContext } == true

    private fun fields() = PreviewDraftFields(title.text.toString(), selectedId, selectedVersion, selectedCatalogHash)

    private fun formChanged() {
        updateContextState()
        if (restoringFields || !::saveStatus.isInitialized) return
        if (!storageBusy && storageReady) {
            saveStatus.setText(if (matchesSaved()) R.string.draft_saved else R.string.draft_unsaved)
        }
        updateEditingEnabled()
    }

    private fun updateEditingEnabled() {
        title.isEnabled = !storageBusy
        objective.isEnabled = !storageBusy
        contextInput.isEnabled = !storageBusy
        clear.isEnabled = !storageBusy
        save.isEnabled = storageReady && !storageBusy && !matchesSaved()
        reopen.isEnabled = !storageBusy && selectionResolved
        history.isEnabled = storageReady && !storageBusy && savedRecord != null
        libraryButton.isEnabled = !storageBusy
        newDraftButton.isEnabled = !storageBusy
        duplicateButton.isEnabled = storageReady && !storageBusy && savedRecord != null
        if (::tierSection.isInitialized) tierSection.update(savedRecord, storageReady && !storageBusy && matchesSaved())
        if (::catalog.isInitialized) for (index in 0 until catalog.childCount) catalog.getChildAt(index).isEnabled = !storageBusy
    }

    private fun showHistory() {
        val baseline = savedRecord ?: return
        if (!storageReady || storageBusy) return
        val revisions = baseline.history.reversed()
        val rows = revisions.map { revision ->
            getString(if (revision.fields == null) R.string.history_unavailable else R.string.history_revision,
                revision.revision.toString()) + " · " + revision.change.name +
                (revision.restoredFrom?.let { getString(R.string.domain_history_source, it.toString()) } ?: "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.draft_history)
            .setItems(rows) { _, index ->
                val revision = revisions[index]
                val oldFields = revision.fields
                if (oldFields == null) {
                    AlertDialog.Builder(this).setMessage(R.string.history_unavailable_detail)
                        .setPositiveButton(R.string.close, null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.history_revision, revision.revision.toString()))
                        .setMessage(getString(R.string.history_copy_warning, oldFields.title,
                            if (baseline.earlierHistoryUnavailable) getString(R.string.history_earlier_unavailable) else ""))
                        .setNegativeButton(R.string.keep_edits, null)
                        .setNeutralButton(R.string.domain_restore_preview) { _, _ -> confirmDomainRestore(baseline, revision) }
                        .setPositiveButton(R.string.history_copy) { _, _ ->
                            // A dialog opened before another save/load cannot adopt that operation's baseline.
                            if (storageReady && !storageBusy && savedRecord === baseline) {
                                restoringFields = true
                                title.setText(oldFields.title)
                                selectedId = oldFields.selectedId
                                selectedVersion = oldFields.selectedVersion
                                selectedCatalogHash = oldFields.catalogHash
                                restoringFields = false
                                showSelection()
                                formChanged()
                            }
                        }.show()
                }
            }.setPositiveButton(R.string.revision_compare_open) { _, _ -> showComparisonPicker(baseline) }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun showComparisonPicker(baseline: PreviewStoredDraft) {
        if (!storageReady || storageBusy || savedRecord !== baseline) return
        val revisions = baseline.history.reversed()
        AlertDialog.Builder(this).setTitle(R.string.revision_compare_open)
            .setItems(revisions.map { getString(R.string.history_revision, it.revision.toString()) }.toTypedArray()) { _, index ->
                worker.execute {
                    val comparison = baseline.compareRevision(revisions[index].revision)
                    runOnUiThread {
                        if (!destroyed) {
                            if (savedRecord !== baseline || comparison == null) {
                                AlertDialog.Builder(this).setMessage(R.string.revision_compare_changed)
                                    .setPositiveButton(R.string.close, null).show()
                            } else showRevisionComparison(comparison)
                        }
                    }
                }
            }.setNegativeButton(R.string.close, null).show()
    }

    private fun setDraftActivation(expected: PreviewStoredDraft, tier: OptionalTier, activation: Activation) {
        val id = currentDraftId
        storageBusy = true
        saveStatus.setText(R.string.draft_saving)
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence(id).setActivation(expected, tier, activation) } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    when (result) {
                        is PreviewDraftSave.Saved -> {
                            savedRecord = result.record
                            saveStatus.setText(if (matchesSaved()) R.string.draft_saved else R.string.draft_unsaved)
                        }
                        PreviewDraftSave.Conflict -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_conflict)
                        }
                        PreviewDraftSave.Uncertain, null -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_uncertain)
                        }
                        is PreviewDraftSave.Failed -> saveStatus.setText(if (result.reason == DraftStorageFailure.LIMIT_EXCEEDED)
                            R.string.draft_history_full else R.string.draft_save_failed)
                    }
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun renderLocalValue(raw: String?): String {
        val display = PreviewComparedValue.from(raw)
        return when (display.kind) {
            PreviewComparedValueKind.ABSENT -> getString(R.string.revision_compare_absent)
            PreviewComparedValueKind.EMPTY -> getString(R.string.context_empty)
            PreviewComparedValueKind.TEXT -> {
                val literal = getString(R.string.revision_compare_text, display.quotedLiteral)
                if (display.truncated) getString(R.string.revision_compare_truncated, literal) else literal
            }
        }
    }

    private fun showRevisionComparison(comparison: PreviewRevisionComparison) {
        val value: (String?) -> String = ::renderLocalValue
        val rows = comparison.changes.take(100).map { change ->
            getString(R.string.revision_compare_row, change.field,
                change.key?.let { " [${value(it)}]" }.orEmpty(), value(change.before), value(change.after))
        }
        val message = listOf(getString(R.string.revision_compare_scope),
            getString(R.string.revision_compare_count, rows.size.toString(), comparison.changes.size.toString()),
            getString(if (comparison.guestFieldsComparable) R.string.revision_compare_guest_known else R.string.revision_compare_guest_unknown),
            getString(if (comparison.metadataChanged) R.string.revision_compare_metadata_changed else R.string.revision_compare_metadata_same),
            rows.joinToString("\n\n")).joinToString("\n\n")
        AlertDialog.Builder(this).setTitle(getString(R.string.revision_compare_title, comparison.from.toString(), comparison.to.toString()))
            .setMessage(message).setPositiveButton(R.string.close, null).show()
    }

    private fun confirmDomainRestore(baseline: PreviewStoredDraft, revision: PreviewDraftRevision) {
        if (!storageReady || storageBusy || savedRecord !== baseline) return
        val value: (String?) -> String = ::renderLocalValue
        val content = revision.content
        val summary = getString(R.string.domain_restore_values, value(content.objective), value(content.context),
            value(content.primaryModel), content.optional.entries.joinToString("\n") { (tier, input) ->
                getString(R.string.domain_restore_tier, tier.name, input.activation.name,
                    input.selections.size.toString(), input.settings.size.toString())
            })
        AlertDialog.Builder(this).setTitle(getString(R.string.history_revision, revision.revision.toString()))
            .setMessage(getString(R.string.domain_restore_warning, summary))
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(R.string.domain_restore_commit) { _, _ ->
                if (storageReady && !storageBusy && savedRecord === baseline) restoreDomain(baseline, revision.revision)
            }.show()
    }

    private fun restoreDomain(expected: PreviewStoredDraft, revision: Long) {
        val id = currentDraftId
        storageBusy = true
        saveStatus.setText(R.string.draft_saving)
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence(id).restore(expected, revision) } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    when (result) {
                        is PreviewDraftSave.Saved -> {
                            savedRecord = result.record
                            applyContext(result.record.contextInput)
                            saveStatus.setText(if (matchesSaved()) R.string.draft_saved else R.string.draft_unsaved)
                        }
                        PreviewDraftSave.Conflict -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_conflict)
                        }
                        PreviewDraftSave.Uncertain, null -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_uncertain)
                        }
                        is PreviewDraftSave.Failed -> saveStatus.setText(if (result.reason == DraftStorageFailure.LIMIT_EXCEEDED)
                            R.string.draft_history_full else R.string.draft_save_failed)
                    }
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun library(): PreviewDraftLibrary {
        draftLibrary?.let { return it }
        val parent = noBackupFilesDir.toPath()
        require(Files.isDirectory(parent, NOFOLLOW_LINKS))
        FileChannel.open(parent.parent, READ).use { it.force(true) }
        return PreviewDraftLibrary(parent).also { draftLibrary = it }
    }

    private fun openPersistence(id: PreviewDraftId): PreviewDraftPersistence {
        val library = library()
        return if (id.value == "guest-draft") library.openLegacy() else library.open(id)
    }

    private fun restoreActiveDraft(preserveRestoredFields: Boolean) {
        storageBusy = true
        storageReady = false
        updateEditingEnabled()
        worker.execute {
            val result = try { library(); PreviewActiveDraftStore(noBackupFilesDir.toPath()).read() } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    val id = when (result) {
                        PreviewActiveRead.None -> PreviewDraftId("guest-draft")
                        is PreviewActiveRead.Selected -> result.id
                        else -> null
                    }
                    if (id == null) {
                        storageBusy = false
                        selectionResolved = false
                        activeDraft.setText(R.string.active_unknown)
                        saveStatus.setText(R.string.active_read_failed)
                        updateEditingEnabled()
                    } else {
                        currentDraftId = id
                        selectionResolved = true
                        loadDraft(preserveRestoredFields)
                    }
                }
            }
        }
    }

    private fun showLibrary() {
        if (storageBusy) return
        storageBusy = true
        updateEditingEnabled()
        worker.execute {
            val result = try { library().list() } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    updateEditingEnabled()
                    val entries = (result as? PreviewLibraryRead.Available)?.entries
                    if (entries == null || entries.isEmpty()) {
                        AlertDialog.Builder(this).setMessage(if (entries == null) R.string.library_failed else R.string.library_empty)
                            .setPositiveButton(R.string.close, null).show()
                    } else {
                        val labels = entries.map { entry ->
                            val record = (entry.state as? PreviewDraftLoad.Available)?.record
                            getString(R.string.library_entry, if (entry.deletionPending) getString(R.string.library_delete_pending)
                                else record?.fields?.title?.ifEmpty { getString(R.string.library_untitled) }
                                ?: getString(R.string.library_unavailable), entry.id.value)
                        }.toTypedArray()
                        AlertDialog.Builder(this).setTitle(R.string.library_open).setItems(labels) { _, index ->
                            val entry = entries[index]
                            if (entry.deletionPending) confirmDelete(entry)
                            else AlertDialog.Builder(this).setTitle(getString(R.string.library_local_id, entry.id.value))
                                .setMessage(R.string.library_switch_warning)
                                .setNegativeButton(R.string.keep_edits, null)
                                .setNeutralButton(R.string.library_delete) { _, _ -> confirmDelete(entry) }
                                .setPositiveButton(R.string.library_open_selected) { _, _ ->
                                    if (!storageBusy) openDraftTarget(entry.id)
                                }.show()
                        }.setNegativeButton(R.string.close, null).show()
                    }
                }
            }
        }
    }

    private fun confirmDelete(entry: PreviewLibraryEntry) {
        if (storageBusy) return
        AlertDialog.Builder(this).setTitle(if (entry.deletionPending) R.string.library_retry_delete else R.string.library_delete)
            .setMessage(getString(R.string.library_delete_warning, entry.id.value))
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(if (entry.deletionPending) R.string.library_retry_delete else R.string.library_delete) { _, _ ->
                if (!storageBusy) deleteDraft(entry)
            }.show()
    }

    private fun deleteDraft(entry: PreviewLibraryEntry) {
        val previous = PreviewDraftSession(currentDraftId, savedRecord, fields(), storageReady)
        val expected = (entry.state as? PreviewDraftLoad.Available)?.record
        storageBusy = true
        updateEditingEnabled()
        worker.execute {
            val result = try { library().delete(entry.id, expected) }
                catch (_: Exception) { PreviewLibraryDelete.Unconfirmed(DraftStorageFailure.IO_FAILURE) }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    val next = previous.afterDeletion(entry.id, result)
                    savedRecord = next.record
                    storageReady = next.ready
                    if (next !== previous) {
                        restoration = null
                        saveStatus.setText(R.string.library_deleted_editor)
                    }
                    updateEditingEnabled()
                    AlertDialog.Builder(this).setMessage(when (result) {
                        PreviewLibraryDelete.Deleted -> R.string.library_deleted
                        PreviewLibraryDelete.Conflict -> R.string.library_delete_conflict
                        is PreviewLibraryDelete.Failed -> R.string.library_delete_failed
                        is PreviewLibraryDelete.Unconfirmed -> R.string.library_delete_unconfirmed
                    }).setPositiveButton(R.string.close, null).show()
                }
            }
        }
    }

    private fun openDraftTarget(target: PreviewDraftId) {
        if (storageBusy) return
        val previous = PreviewDraftSession(currentDraftId, savedRecord, fields(), storageReady)
        storageBusy = true
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence(target).load() } catch (_: Exception) { null }
            val selection = if (result is PreviewDraftLoad.Available || result == PreviewDraftLoad.Missing) {
                try { PreviewActiveDraftStore(noBackupFilesDir.toPath()).select(target) }
                catch (_: Exception) { PreviewActiveWrite.Uncertain }
            } else null
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    val next = previous.switchTo(target, result, selection ?: PreviewActiveWrite.Uncertain)
                    if (next === previous) {
                        // Identity, baseline, save readiness and pending form still belong together.
                        AlertDialog.Builder(this).setMessage(if (selection != null && selection != PreviewActiveWrite.Saved)
                            R.string.active_write_failed else R.string.library_switch_failed)
                            .setPositiveButton(R.string.close, null).show()
                    } else {
                        currentDraftId = next.id
                        selectionResolved = true
                        savedRecord = next.record
                        storageReady = next.ready
                        restoration = null
                        restoringFields = true
                        title.setText(next.fields.title)
                        selectedId = next.fields.selectedId
                        selectedVersion = next.fields.selectedVersion
                        selectedCatalogHash = next.fields.catalogHash
                        applyContext(next.record?.contextInput ?: PreviewDraftContext())
                        restoringFields = false
                        activeDraft.text = getString(R.string.library_active, currentDraftId.value)
                        showSelection()
                        formChanged()
                    }
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun confirmCreate(duplicate: Boolean) {
        if (storageBusy) return
        val source = if (duplicate) savedRecord ?: return else null
        AlertDialog.Builder(this).setTitle(if (duplicate) R.string.library_duplicate else R.string.library_new)
            .setMessage(if (duplicate) R.string.library_duplicate_warning else R.string.library_new_warning)
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(R.string.library_continue) { _, _ ->
                if (!storageBusy && (!duplicate || savedRecord === source && storageReady)) createDraft(source)
            }.show()
    }

    private fun createDraft(source: PreviewStoredDraft?) {
        storageBusy = true
        updateEditingEnabled()
        worker.execute {
            val result = try { if (source == null) library().create() else library().duplicate(source) } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    if (result is PreviewLibraryCreate.Saved) {
                        openDraftTarget(result.id)
                    } else {
                        updateEditingEnabled()
                        AlertDialog.Builder(this).setMessage(if (result is PreviewLibraryCreate.Unconfirmed)
                            getString(R.string.library_unconfirmed, result.id.value) else getString(R.string.library_create_failed))
                            .setPositiveButton(R.string.close, null).show()
                    }
                }
            }
        }
    }

    private fun loadDraft(preserveRestoredFields: Boolean) {
        val id = currentDraftId
        activeDraft.text = getString(R.string.library_active, id.value)
        storageBusy = true
        storageReady = false
        saveStatus.setText(R.string.draft_loading)
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence(id).load() } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    savedRecord = (result as? PreviewDraftLoad.Available)?.record
                    val loadSucceeded = result is PreviewDraftLoad.Available || result == PreviewDraftLoad.Missing
                    val needsReopen = preserveRestoredFields && restoration?.canResume(savedRecord) != true
                    storageReady = loadSucceeded && !needsReopen
                    if (!preserveRestoredFields && loadSucceeded) restoration = null
                    if (loadSucceeded) {
                        val checkpoint = PreviewContextCheckpoint(!restoreContextFromDisk, domainContext)
                        applyContext(checkpoint.forLoad(preserveRestoredFields, savedRecord?.contextInput ?: PreviewDraftContext()))
                        restoreContextFromDisk = false
                    }
                    if (storageReady && !preserveRestoredFields) {
                        val values = savedRecord?.fields ?: PreviewDraftFields()
                        restoringFields = true
                        title.setText(values.title)
                        selectedId = values.selectedId
                        selectedVersion = values.selectedVersion
                        selectedCatalogHash = values.catalogHash
                        restoringFields = false
                        showSelection()
                    }
                    saveStatus.setText(when {
                        !loadSucceeded -> R.string.draft_load_failed
                        needsReopen -> R.string.draft_reconcile
                        matchesSaved() -> R.string.draft_saved
                        else -> R.string.draft_unsaved
                    })
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun saveDraft() {
        if (!storageReady || storageBusy) return
        val id = currentDraftId
        val pendingContext = domainContext
        val pending = fields()
        val expected = savedRecord
        storageBusy = true
        saveStatus.setText(R.string.draft_saving)
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence(id).save(expected, pending, pendingContext) } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    when (result) {
                        is PreviewDraftSave.Saved -> {
                            savedRecord = result.record
                            saveStatus.setText(if (matchesSaved()) R.string.draft_saved else R.string.draft_unsaved)
                        }
                        PreviewDraftSave.Conflict -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_conflict)
                        }
                        PreviewDraftSave.Uncertain, null -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_uncertain)
                        }
                        is PreviewDraftSave.Failed -> saveStatus.setText(if (result.reason == DraftStorageFailure.LIMIT_EXCEEDED)
                            R.string.draft_history_full else R.string.draft_save_failed)
                    }
                    updateEditingEnabled()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draftId", currentDraftId.value)
        outState.putBoolean("selectionResolved", selectionResolved)
        val checkpoint = PreviewDraftRestoration.capture(savedRecord, storageReady, storageBusy)
        outState.putString("draftBaseline", checkpoint.baselineIdentity)
        outState.putBoolean("draftRequiresReopen", checkpoint.requiresReopen)
        outState.putBoolean("contextInputsPresent", true)
        outState.putString("objective", domainContext.objective)
        outState.putString("contextInput", domainContext.context)
        outState.putString("title", title.text.toString().take(120))
        outState.putString("selectedId", selectedId)
        outState.putString("selectedVersion", selectedVersion)
        outState.putString("selectedCatalogHash", selectedCatalogHash)
        super.onSaveInstanceState(outState)
    }

    private fun evidenceText(value: SyntheticCatalogEvidence): String {
        val label = when (value.presentation.label) {
            EvidenceLabel.INDEPENDENTLY_VERIFIED -> R.string.label_independently_verified
            EvidenceLabel.VERIFICATION_UNESTABLISHED -> R.string.label_verification_unestablished
            EvidenceLabel.UNVERIFIED -> R.string.label_unverified
            EvidenceLabel.USER_SUPPLIED -> R.string.label_user_supplied
            EvidenceLabel.ESTIMATED -> R.string.label_estimated
        }
        return listOf(
            getString(R.string.evidence_heading),
            getString(R.string.evidence_item, value.name, value.identity.claimId, value.identity.version),
            getString(R.string.evidence_snapshot, value.identity.snapshotId, value.identity.snapshotRevision.toString()),
            getString(R.string.evidence_status, value.presentation.rawStatus.encoded, getString(label)),
            getString(R.string.evidence_support_unestablished),
            getString(R.string.evidence_dates, value.presentation.capturedAt.toString(), value.snapshotCapturedAt.toString()),
            getString(R.string.evidence_pinned),
            getString(R.string.evidence_note, value.provenanceNote),
            getString(R.string.evidence_limits),
        ).joinToString("\n\n")
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        super.onDestroy()
    }
}
