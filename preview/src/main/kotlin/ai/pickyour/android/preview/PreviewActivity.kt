package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import ai.pickyour.core.preview.*
import ai.pickyour.core.drafts.LocalDraftStore
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
    private var selectedId: String? = null
    private var selectedVersion: String? = null
    private var selectedCatalogHash: String? = null
    private var loadedCatalogHash: String? = null
    private lateinit var title: EditText
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
    private var persistence: PreviewDraftPersistence? = null // Accessed only on the single worker.
    private var savedRecord: PreviewStoredDraft? = null // UI-thread owned.
    private var storageReady = false
    private var storageBusy = true
    private var restoringFields = false
    private var restoration: PreviewDraftRestoration? = null


    private data class LoadedCatalog(val items: List<SampleItem>, val hash: String)
    private data class SampleItem(val id: String, val name: String, val kind: String, val version: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoration = savedInstanceState?.let {
            PreviewDraftRestoration(it.getString("draftBaseline"), it.getBoolean("draftRequiresReopen", true))
        }
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
        selection = text(R.string.no_selection).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        text(R.string.unknown)
        clear = Button(this).apply {
            setText(R.string.clear_draft)
            setOnClickListener {
                title.setText("")
                selectedId = null
                selectedVersion = null
                selectedCatalogHash = null
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
        loadDraft(savedInstanceState != null)
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

    private fun fields() = PreviewDraftFields(title.text.toString(), selectedId, selectedVersion, selectedCatalogHash)

    private fun formChanged() {
        if (restoringFields || !::saveStatus.isInitialized) return
        if (!storageBusy && storageReady) {
            saveStatus.setText(if (savedRecord?.fields == fields()) R.string.draft_saved else R.string.draft_unsaved)
        }
        updateEditingEnabled()
    }

    private fun updateEditingEnabled() {
        title.isEnabled = !storageBusy
        clear.isEnabled = !storageBusy
        save.isEnabled = storageReady && !storageBusy && savedRecord?.fields != fields()
        reopen.isEnabled = !storageBusy
        history.isEnabled = storageReady && !storageBusy && savedRecord != null
        if (::catalog.isInitialized) for (index in 0 until catalog.childCount) catalog.getChildAt(index).isEnabled = !storageBusy
    }

    private fun showHistory() {
        val baseline = savedRecord ?: return
        if (!storageReady || storageBusy) return
        val revisions = baseline.history.reversed()
        val rows = revisions.map { revision ->
            getString(if (revision.fields == null) R.string.history_unavailable else R.string.history_revision,
                revision.revision.toString())
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
            }.setNegativeButton(R.string.close, null).show()
    }

    private fun openPersistence(): PreviewDraftPersistence {
        persistence?.let { return it }
        // Android owns the established app data directory. Repeat both parent syncs after mkdir,
        // including a retry after failed initialization; never acknowledge an unsynced root.
        val parent = noBackupFilesDir.toPath()
        require(Files.isDirectory(parent, NOFOLLOW_LINKS))
        FileChannel.open(parent.parent, READ).use { it.force(true) }
        val root = parent.resolve("guest-draft")
        require(!Files.isSymbolicLink(root))
        Files.createDirectories(root)
        FileChannel.open(parent, READ).use { it.force(true) }
        return PreviewDraftPersistence(LocalDraftStore(root)).also { persistence = it }
    }

    private fun loadDraft(preserveRestoredFields: Boolean) {
        storageBusy = true
        storageReady = false
        saveStatus.setText(R.string.draft_loading)
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence().load() } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    savedRecord = (result as? PreviewDraftLoad.Available)?.record
                    val loadSucceeded = result is PreviewDraftLoad.Available || result == PreviewDraftLoad.Missing
                    val needsReopen = preserveRestoredFields && restoration?.canResume(savedRecord) != true
                    storageReady = loadSucceeded && !needsReopen
                    if (!preserveRestoredFields && loadSucceeded) restoration = null
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
                        savedRecord?.fields == fields() -> R.string.draft_saved
                        else -> R.string.draft_unsaved
                    })
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun saveDraft() {
        if (!storageReady || storageBusy) return
        val pending = fields()
        val expected = savedRecord
        storageBusy = true
        saveStatus.setText(R.string.draft_saving)
        updateEditingEnabled()
        worker.execute {
            val result = try { openPersistence().save(expected, pending) } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    when (result) {
                        is PreviewDraftSave.Saved -> {
                            savedRecord = result.record
                            saveStatus.setText(if (fields() == result.record.fields) R.string.draft_saved else R.string.draft_unsaved)
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
        val checkpoint = PreviewDraftRestoration.capture(savedRecord, storageReady, storageBusy)
        outState.putString("draftBaseline", checkpoint.baselineIdentity)
        outState.putBoolean("draftRequiresReopen", checkpoint.requiresReopen)
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
