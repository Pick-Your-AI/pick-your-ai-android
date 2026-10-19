package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import ai.pickyour.core.preview.*
import ai.pickyour.core.drafts.DraftWorkload
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
import android.widget.TextView
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import ai.pickyour.core.preview.SyntheticCatalogEvidence
import ai.pickyour.core.evidence.EvidenceLabel

/** Guest preview over synthetic fixtures with acknowledged local saves through the canonical store. */
class PreviewActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val autosaveHandler = Handler(Looper.getMainLooper())
    private val autosave = PreviewDraftAutosave(PreviewAutosaveScheduler { delay, action ->
        val callback = Runnable { action() }
        if (autosaveHandler.postDelayed(callback, delay))
            PreviewAutosaveCancellation { autosaveHandler.removeCallbacks(callback) }
        else null
    }) { request -> saveDraft(request) }
    private var autosaveRequested = false
    private var savingInput: PreviewPendingDraft? = null
    private var destroyed = false
    private lateinit var workloadSection: WorkloadSection
    private var restoreWorkloadFromDisk = false
    private lateinit var tierSection: OptionalTierSection
    private lateinit var planningSection: T6PlanningSection
    private lateinit var draftSummary: CurrentDraftSummarySection
    private var selectedId: String? = null
    private var selectedVersion: String? = null
    private var selectedCatalogHash: String? = null
    private lateinit var title: EditText
    private lateinit var objective: EditText
    private lateinit var contextInput: EditText
    private lateinit var contextState: TextView
    private var domainContext = PreviewDraftContext()
    private var restoreContextFromDisk = false
    private lateinit var selection: TextView
    private lateinit var componentSection: SelectionEditSection
    private lateinit var compatibleOptions: CompatibleOptionsPicker
    private var componentCatalog: ai.pickyour.core.drafts.SelectionCatalogSnapshot? = null
    private lateinit var catalog: CatalogBrowseSection
    private lateinit var catalogComparison: CatalogComparisonSection
    private lateinit var catalogPinRecovery: CatalogPinRecovery
    private var catalogFixture: CatalogFixtureData? = null // UI-thread owned, replaced even when pricing is absent.
    // The origin remains current while its child inspection is open after parent dismissal.
    private var catalogDetailDialog: AlertDialog? = null
    private var catalogPriceBrowser: CatalogPriceBrowser? = null
    private var catalogDetailBrowser: CatalogDetailBrowser? = null
    private lateinit var catalogStatus: TextView
    private lateinit var catalogRetentionStatus: TextView
    private lateinit var catalogSearch: EditText
    private lateinit var retry: Button
    private lateinit var save: Button
    private lateinit var reopen: Button
    private lateinit var clear: Button
    private lateinit var history: Button
    private lateinit var compareRetained: Button
    private lateinit var compareLibrary: Button
    private lateinit var saveStatus: TextView
    private var draftLibrary: PreviewDraftLibrary? = null // Accessed only on the single worker.
    private var currentDraftId = PreviewDraftId("guest-draft")
    private var selectionResolved = false
    private lateinit var activeDraft: TextView
    private lateinit var libraryButton: Button
    private lateinit var newDraftButton: Button
    private lateinit var recoverButton: Button
    private lateinit var duplicateButton: Button
    private lateinit var retryDeleteButton: Button
    private var pendingDeleteId: PreviewDraftId? = null
    private var savedRecord: PreviewStoredDraft? = null // UI-thread owned.
    private var revisionDialog: AlertDialog? = null
    private var revisionRequest: Any? = null
    private var revisionBrowser: RevisionComparisonBrowser? = null
    private var libraryComparison: LibraryRevisionComparison? = null
    private var storageReady = false
    private var storageAuthority = Any() // UI-thread identity; never reused across storage operations.
    private var failedReadCheckpoint: PreviewDraftRestoration? = null
    private var storageBusy = true
        set(value) {
            if (value) {
                autosave.cancel()
                failedReadCheckpoint = null
                storageAuthority = Any()
                retireRevisionInspection()
                if (::compatibleOptions.isInitialized) compatibleOptions.dismiss()
            }
            field = value
            if (value && ::catalogPinRecovery.isInitialized) catalogPinRecovery.dismiss()
            if (value) catalogDetailBrowser?.retire()
        }
    private var restoringFields = false
    private var restoration: PreviewDraftRestoration? = null
    private lateinit var findings: FindingsInspectionSection
    private lateinit var snapshots: SnapshotEvaluationSection
    private lateinit var diagnostics: LocalDiagnostics
    private lateinit var help: LocalHelp
    private var diagnosticsResumed = false
    private var environmentAdmitted = false
    private var entryBlocked = false
    private var unsupportedEntry = false
    private lateinit var entryNotice: TextView
    private var incomingComponent: PreviewComponentRoute? = null
    private var incomingComponentUnavailable = false
    private lateinit var incomingComponentNotice: TextView
    private lateinit var incomingComponentOpen: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        environmentAdmitted = EnvironmentBootstrap.admit(this, EnvironmentBootstrap.read(this))
        if (!environmentAdmitted) return
        autosaveRequested = savedInstanceState?.getBoolean("autosaveRequested", false) == true
        if (savedInstanceState?.getBoolean("autosavePaused", false) == true) autosave.pause()
        // The checkpoint retains normalized navigation identity, never an incoming URI or write authority.
        incomingComponent = if (savedInstanceState == null) componentRoute(intent) else
            if (savedInstanceState.getBoolean("incomingComponentPresent", false)) PreviewComponentRoute.restore(
                savedInstanceState.getString("incomingComponentKind"), savedInstanceState.getString("incomingComponentId"),
                savedInstanceState.getString("incomingComponentVersion"), savedInstanceState.getString("incomingComponentSnapshot"))
            else null
        entryBlocked = if (savedInstanceState?.containsKey("entryBlocked") == true)
            savedInstanceState.getBoolean("entryBlocked") else !isLocalEntry(intent)
        unsupportedEntry = incomingComponent == null && (entryBlocked ||
            savedInstanceState?.getBoolean("unsupportedEntry", false) == true ||
            savedInstanceState?.getBoolean("incomingComponentPresent", false) == true)
        currentDraftId = PreviewDraftId(savedInstanceState?.getString("draftId") ?: "guest-draft")
        pendingDeleteId = savedInstanceState?.getString("pendingDeleteId")?.let(::PreviewDraftId)
        selectionResolved = !entryBlocked && savedInstanceState != null && savedInstanceState.getBoolean("selectionResolved", true)
        restoration = savedInstanceState?.let {
            PreviewDraftRestoration(it.getString("draftBaseline"), it.getBoolean("draftRequiresReopen", true))
        }
        val workloadCheckpoint = WorkloadSection.restore(savedInstanceState)
        restoreWorkloadFromDisk = !workloadCheckpoint.hasInputs
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
        entryNotice = text(R.string.entry_unsupported).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (unsupportedEntry) View.VISIBLE else View.GONE
        }
        incomingComponentNotice = text(R.string.component_link_ready).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (incomingComponent == null) View.GONE else View.VISIBLE
        }
        incomingComponentOpen = Button(this).apply {
            setText(R.string.component_link_open)
            setSingleLine(false)
            visibility = if (incomingComponent == null) View.GONE else View.VISIBLE
            setOnClickListener { openIncomingComponent() }
        }
        content.addView(incomingComponentOpen)
        text(R.string.guest_draft, 22f).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        text(R.string.temporary)
        activeDraft = text(R.string.library_active).apply {
            text = if (selectionResolved) getString(R.string.library_active, currentDraftId.value) else getString(R.string.active_unknown)
        }
        draftSummary = CurrentDraftSummarySection(this) { !destroyed && diagnosticsResumed }
        content.addView(draftSummary)
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
                        if (!destroyed && !restoringFields) { changed(s.toString()); formChanged() }
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
                if (destroyed) return@setOnClickListener
                title.setText("")
                selectedId = null
                selectedVersion = null
                selectedCatalogHash = null
                applyContext(PreviewDraftContext())
                workloadSection.apply(PreviewWorkloadInputs())
                showSelection()
                formChanged()
            }
        }
        content.addView(clear)
        workloadSection = WorkloadSection(this, workloadCheckpoint.inputs, { !destroyed }) { formChanged() }
        content.addView(workloadSection)
        saveStatus = text(R.string.draft_loading).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        save = Button(this).apply { setText(R.string.save_draft); setOnClickListener { saveDraft() } }
        reopen = Button(this).apply {
            setText(R.string.reopen_draft)
            setOnClickListener {
                if (destroyed || storageBusy || !selectionResolved) return@setOnClickListener
                val baseline = savedRecord
                val draftId = currentDraftId
                val authority = storageAuthority
                val keepingEdits = if (storageReady) PreviewDraftRestoration.capture(baseline, true, false)
                    else failedReadCheckpoint ?: PreviewDraftRestoration.capture(baseline, false, false)
                fun current(confirmation: DialogInterface) =
                    (confirmation as? AlertDialog)?.isShowing == true && !destroyed && !storageBusy &&
                        selectionResolved && currentDraftId == draftId && savedRecord === baseline && storageAuthority === authority
                AlertDialog.Builder(this@PreviewActivity).setTitle(R.string.reopen_draft)
                    .setMessage(R.string.reopen_warning)
                    .setNegativeButton(R.string.keep_edits, null)
                    .setNeutralButton(R.string.retry_keep_edits) { confirmation, _ ->
                        if (current(confirmation)) {
                            autosave.pause()
                            restoration = keepingEdits
                            // The visible raw inputs, including invalid text, own this read-only retry.
                            restoreContextFromDisk = false
                            restoreWorkloadFromDisk = false
                            loadDraft(true)
                        }
                    }
                    .setPositiveButton(R.string.reopen_confirm) { confirmation, _ ->
                        if (current(confirmation)) loadDraft(false)
                    }.show()
            }
        }
        content.addView(save)
        content.addView(reopen)
        history = Button(this).apply { setText(R.string.draft_history); setOnClickListener { showHistory() } }
        content.addView(history)
        compareRetained = Button(this).apply {
            setText(R.string.revision_compare_pair_open)
            setOnClickListener { savedRecord?.let { showComparisonPicker(it, pickBoth = true) } }
        }
        content.addView(compareRetained)
        compareLibrary = Button(this).apply {
            setText(R.string.library_compare_open)
            setOnClickListener { showLibraryComparison() }
        }
        content.addView(compareLibrary)
        libraryButton = Button(this).apply { setText(R.string.library_open); setOnClickListener { showLibrary() } }
        newDraftButton = Button(this).apply { setText(R.string.library_new); setOnClickListener { confirmCreate(false) } }
        duplicateButton = Button(this).apply { setText(R.string.library_duplicate); setOnClickListener { confirmCreate(true) } }
        retryDeleteButton = Button(this).apply {
            setText(R.string.library_retry_delete)
            setOnClickListener { pendingDeleteId?.let { confirmDelete(it, null, true) } }
        }
        content.addView(libraryButton)
        content.addView(newDraftButton)
        content.addView(duplicateButton)
        recoverButton = Button(this).apply { setText(R.string.recovery_copy); setOnClickListener { confirmRecoveryCopy() } }
        content.addView(recoverButton)
        content.addView(retryDeleteButton)
        tierSection = OptionalTierSection(this) { expected, tier, activation ->
            if (storageReady && !storageBusy && savedRecord === expected && matchesSaved())
                setDraftActivation(expected, tier, activation)
        }
        content.addView(tierSection)
        compatibleOptions = CompatibleOptionsPicker(this, worker) { !destroyed && diagnosticsResumed }
        componentSection = SelectionEditSection(this, ::commitSelection, ::showCompatibleOptions, compatibleOptions::dismiss)
        componentSection.onReviewAvailabilityChanged = ::updateDraftSummary
        componentSection.restore(savedInstanceState)
        content.addView(componentSection)
        planningSection = T6PlanningSection(this, { !destroyed }, { catalog.cancelViewportRestore() }) { kind ->
            if (!destroyed && !isFinishing && catalog.browseKind(kind.catalogKind)) catalogSearch.setText("")
        }
        content.addView(planningSection)
        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { formChanged() }
            override fun afterTextChanged(s: Editable?) {}
        })
        text(R.string.catalog_heading, 22f).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
        text(R.string.catalog_explanation)
        catalogStatus = text(R.string.working).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        catalogRetentionStatus = text(R.string.catalog_retention_unconfirmed).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val searchLabel = text(R.string.catalog_search)
        catalogSearch = EditText(this).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(PreviewCatalogQuery.MAX_LENGTH))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setHint(R.string.catalog_search_hint)
            setText(savedInstanceState?.getString("catalogQuery").orEmpty().take(PreviewCatalogQuery.MAX_LENGTH))
        }
        searchLabel.labelFor = catalogSearch.id
        content.addView(catalogSearch)
        retry = Button(this).apply { setText(R.string.retry_catalog); setOnClickListener { loadCatalog() } }
        content.addView(retry)
        catalogPinRecovery = CatalogPinRecovery(this, worker) {
            if (!destroyed) {
                catalogSearch.requestFocus()
                catalogSearch.requestRectangleOnScreen(android.graphics.Rect(0, 0, catalogSearch.width, catalogSearch.height), true)
            }
        }
        catalogComparison = CatalogComparisonSection(this, savedInstanceState, ::showPinnedDetails) {
            retireRevisionInspection()
            catalogPinRecovery.dismiss()
            catalogDetailDialog?.dismiss()
            catalogDetailDialog = null
            catalogPriceBrowser?.retire()
            catalogPriceBrowser = null
            catalogDetailBrowser?.retire()
        }
        content.addView(catalogComparison)
        catalog = CatalogBrowseSection(this, savedInstanceState, ::showDetails)
        content.addView(catalog)
        catalog.search(catalogSearch.text.toString())
        catalogSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                catalog.search(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val status = text(R.string.ready).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val evidenceDetails = text(R.string.evidence_ready)
        val run = Button(this).apply { setText(R.string.run) }
        run.setOnClickListener {
            if (destroyed) return@setOnClickListener
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
        findings = FindingsInspectionSection(this, worker) { !destroyed }
        findings.attach(content)
        snapshots = SnapshotEvaluationSection(this, worker) { !destroyed && diagnosticsResumed }
        snapshots.attach(content)
        diagnostics = LocalDiagnostics(this, {
            LocalDiagnosticReport(
                when {
                    storageBusy -> DiagnosticStorageState.BUSY
                    storageReady -> DiagnosticStorageState.READY
                    savedRecord != null -> DiagnosticStorageState.RECONCILIATION_REQUIRED
                    else -> DiagnosticStorageState.UNAVAILABLE
                },
                if (catalogFixture?.catalog != null) DiagnosticCatalogState.SYNTHETIC_LOADED
                else DiagnosticCatalogState.UNAVAILABLE_OR_LOADING,
            )
        }, { !destroyed && diagnosticsResumed })
        diagnostics.attach(content)
        help = LocalHelp(this, { !destroyed && diagnosticsResumed }, diagnostics::show,
            captureCatalogIdentity = {
                if (!retry.isEnabled) HelpCatalogIdentity.UNAVAILABLE_OR_LOADING
                else catalogFixture?.helpIdentity ?: HelpCatalogIdentity.UNAVAILABLE_OR_LOADING
            })
        help.attach(content)
        setContentView(CatalogViewportScrollView(this).apply {
            onUserNavigation = catalog::cancelViewportRestore
            addView(content)
            catalog.attachViewport(this)
        })
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
        if (entryBlocked) {
            storageBusy = false
            saveStatus.setText(R.string.entry_choose_local)
            updateEditingEnabled()
        } else if (selectionResolved) loadDraft(true) else restoreActiveDraft(savedInstanceState != null)
    }

    private fun isLocalEntry(value: Intent?): Boolean = value == null ||
        (value.action == null || value.action == Intent.ACTION_MAIN) && value.data == null &&
            value.type == null && value.clipData == null && value.selector == null

    private fun componentRoute(value: Intent?): PreviewComponentRoute? {
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        if (value == null || value.action != Intent.ACTION_VIEW || value.type != null || value.clipData != null ||
            value.selector != null || value.flags and grants != 0) return null
        return PreviewComponentRoute.parse(value.dataString)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (destroyed || !environmentAdmitted) return
        // A new entry never replaces an already open editor, its pending fields or its save authority.
        if (!isLocalEntry(intent)) {
            incomingComponent = componentRoute(intent)
            incomingComponentUnavailable = false
            unsupportedEntry = incomingComponent == null
        }
        else if (!entryBlocked) unsupportedEntry = false
        entryNotice.visibility = if (unsupportedEntry) View.VISIBLE else View.GONE
        updateIncomingComponent()
    }

    private fun updateIncomingComponent() {
        if (destroyed || !::incomingComponentOpen.isInitialized) return
        val visibility = if (incomingComponent == null) View.GONE else View.VISIBLE
        incomingComponentNotice.visibility = visibility
        incomingComponentOpen.visibility = visibility
        incomingComponentNotice.setText(if (incomingComponentUnavailable)
            R.string.component_link_unavailable else R.string.component_link_ready)
        incomingComponentOpen.isEnabled = incomingComponent != null && ::retry.isInitialized && retry.isEnabled
    }

    private fun openIncomingComponent() {
        if (destroyed || isFinishing || isDestroyed || !diagnosticsResumed || !::retry.isInitialized || !retry.isEnabled) return
        val route = incomingComponent ?: return
        val browser = catalogFixture?.catalog
        val item = browser?.items?.singleOrNull { route.matches(it, browser.hash) }
        if (browser == null || item == null || !catalog.contains(item, browser.hash)) {
            incomingComponentUnavailable = true
            updateIncomingComponent()
            return
        }
        incomingComponentUnavailable = false
        updateIncomingComponent()
        showDetails(item, browser.hash)
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
        if (destroyed) return
        compatibleOptions.dismiss()
        catalogPinRecovery.dismiss()
        catalogDetailBrowser?.retire()
        catalogDetailBrowser = null
        retry.isEnabled = false
        updateIncomingComponent()
        catalogStatus.setText(R.string.working)
        catalogRetentionStatus.visibility = View.GONE
        worker.execute {
            var catalogBytes: ByteArray? = null
            var componentSnapshot: ai.pickyour.core.drafts.SelectionCatalogSnapshot? = null
            val loaded = CatalogFixtureData.read({
                readCatalogFixture().also {
                    catalogBytes = it
                    componentSnapshot = ai.pickyour.core.drafts.SelectionCatalogSnapshot.fromJson(it)
                }
            }) { assets.open("pricebook.json") }
            val selectionSnapshot = componentSnapshot.takeIf { loaded.catalog != null }
            val retention = if (loaded.catalog == null) null else
                CatalogRetainedSnapshots.retain(noBackupFilesDir.toPath(), requireNotNull(catalogBytes))
            runOnUiThread {
                if (!destroyed) {
                    catalogFixture = loaded
                    retry.isEnabled = true
                    catalogStatus.setText(if (loaded.catalog == null) R.string.catalog_unavailable else R.string.catalog_ready)
                    catalogRetentionStatus.visibility = if (retention == null ||
                        retention is PreviewCatalogRetentionResult.Available) View.GONE else View.VISIBLE
                    catalogRetentionStatus.setText(if (retention is PreviewCatalogRetentionResult.Failed &&
                        retention.reason == ai.pickyour.core.cache.CacheFailure.RETENTION_LIMIT)
                        R.string.catalog_retention_limit else R.string.catalog_retention_unconfirmed)
                    componentCatalog = selectionSnapshot
                    componentSection.catalog(loaded.catalog?.items.orEmpty(), loaded.catalog?.hash)
                    if (loaded.catalog == null) catalog.unavailable() else catalog.show(loaded.catalog)
                    catalogComparison.show(loaded.catalog, loaded.pricebook)
                    catalog.selectionEnabled(!storageBusy)
                    updateIncomingComponent()
                    showSelection()
                    updateDraftSummary()
                }
            }
        }
    }

    private fun showDetails(item: PreviewCatalogItem, catalogHash: String) {
        if (destroyed) return
        retireRevisionInspection()
        catalogPinRecovery.dismiss()
        catalogDetailDialog?.dismiss()
        catalogDetailDialog = null
        catalogPriceBrowser?.retire()
        catalogPriceBrowser = null
        catalogDetailBrowser?.retire()
        catalogDetailBrowser = null
        val baseline = savedRecord
        val draftId = currentDraftId
        val detailAuthority = storageAuthority
        val detail = catalog.details(item, catalogHash)
        val priceContext = catalogFixture
        val prices = priceContext?.pricebook
        val canUseInDraft = {
            baseline != null && !destroyed && !isFinishing && !isDestroyed && diagnosticsResumed &&
                !entryBlocked && selectionResolved && !storageBusy && storageReady &&
                storageAuthority === detailAuthority && savedRecord === baseline && currentDraftId == draftId &&
                catalog.contains(item, catalogHash) && PreviewSelectionForms.supported(item.kind) &&
                componentSection.canReviewCurrentChoices(baseline)
        }
        lateinit var dialog: AlertDialog
        val builder = AlertDialog.Builder(this)
        // Keep actions above the independently scrolling message. A separate custom content panel
        // can collapse to zero height when the native message also fills the dialog.
        builder.setCustomTitle(LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL
            val target = kotlin.math.ceil(48 * resources.displayMetrics.density.toDouble()).toInt()
            val inset = kotlin.math.ceil(16 * resources.displayMetrics.density.toDouble()).toInt()
            setPadding(inset, inset, inset, 0)
            addView(TextView(builder.context).apply {
                text = item.name
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (detail != null) addView(Button(builder.context).apply {
                setText(R.string.catalog_detail_browser_open)
                setSingleLine(false)
                minHeight = target
                minWidth = target
                setOnClickListener {
                    val isCurrent = {
                        catalogDetailDialog === dialog && !destroyed && !storageBusy && storageAuthority === detailAuthority &&
                            savedRecord === baseline && currentDraftId == draftId && catalogFixture === priceContext &&
                            catalog.contains(item, catalogHash)
                    }
                    if (dialog.isShowing && isCurrent()) {
                        catalogComparison.dismissInspection()
                        dialog.dismiss()
                        CatalogDetailBrowser(this@PreviewActivity, detail, isCurrent).also {
                            catalogDetailBrowser = it
                            it.show()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (detail != null) addView(Button(builder.context).apply {
                setText(R.string.catalog_price_browse)
                setSingleLine(false)
                minHeight = target
                minWidth = target
                setOnClickListener {
                    val isCurrent = {
                        catalogDetailDialog === dialog && !destroyed && !storageBusy && savedRecord === baseline && currentDraftId == draftId &&
                            catalogFixture === priceContext && catalog.contains(item, catalogHash)
                    }
                    if (dialog.isShowing && isCurrent()) {
                        dialog.dismiss()
                        CatalogPriceBrowser(this@PreviewActivity, detail, prices, isCurrent).also {
                            catalogPriceBrowser = it
                            it.show()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(builder.context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(builder.context).apply {
                    setText(R.string.catalog_compare_add)
                    setSingleLine(false)
                    minHeight = target
                    minWidth = target
                    setOnClickListener {
                        if (catalogDetailDialog === dialog && dialog.isShowing && !destroyed) {
                            catalogComparison.add(item, catalogHash)
                            dialog.dismiss()
                        }
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(builder.context).apply {
                    setText(R.string.catalog_use_in_draft)
                    setSingleLine(false)
                    minHeight = target
                    minWidth = target
                    isEnabled = canUseInDraft()
                    setOnClickListener {
                        if (catalogDetailDialog !== dialog || !dialog.isShowing || destroyed) return@setOnClickListener
                        if (!canUseInDraft()) {
                            isEnabled = false
                            dialog.setMessage(getString(R.string.catalog_selection_changed))
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        componentSection.fromCatalog(requireNotNull(baseline), item, catalogHash)
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        dialog = builder
            .setTitle(item.name)
            .setMessage((detail?.let { CatalogDetailText.describe(this, it, prices) }
                ?: getString(R.string.catalog_detail_unavailable)) + if (canUseInDraft()) "" else "\n\n" +
                getString(if (PreviewSelectionForms.supported(item.kind)) R.string.catalog_selection_requires_saved
                    else R.string.component_unsupported))
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.catalog_sources) { _, _ ->
                if (dialog.isShowing && detail != null) CatalogSourceNavigation(this, detail, isCurrent = {
                    catalogDetailDialog === dialog && !destroyed && !storageBusy && savedRecord === baseline && currentDraftId == draftId &&
                        catalog.contains(item, catalogHash)
                }).show()
            }
            .setPositiveButton(R.string.select_sample) { _, _ ->
                if (catalogDetailDialog === dialog && dialog.isShowing && !destroyed && !storageBusy && savedRecord === baseline && currentDraftId == draftId &&
                    catalog.contains(item, catalogHash)) {
                    selectedId = item.id
                    selectedVersion = item.version
                    selectedCatalogHash = catalogHash
                    showSelection()
                    formChanged()
                }
            }
            .create()
        catalogDetailDialog = dialog
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
        dialog.show()
    }

    private fun showPinnedDetails(pin: PreviewCatalogPin, captured: PreviewCatalogBrowser?, originIsCurrent: () -> Boolean) {
        if (destroyed || storageBusy || !originIsCurrent()) return
        retireRevisionInspection()
        catalogDetailDialog?.dismiss()
        catalogDetailDialog = null
        catalogPriceBrowser?.retire()
        catalogPriceBrowser = null
        catalogDetailBrowser?.retire()
        catalogDetailBrowser = null
        val baseline = savedRecord
        val draftId = currentDraftId
        catalogPinRecovery.open(pin, captured) {
            !destroyed && !storageBusy && savedRecord === baseline && currentDraftId == draftId && originIsCurrent()
        }
    }

    private fun showSelection() {
        if (destroyed) return
        val name = catalog.selectedName(selectedId, selectedVersion, selectedCatalogHash)
        selection.text = when {
            selectedId == null -> getString(R.string.no_selection)
            name == null -> getString(R.string.selection_unavailable)
            else -> getString(R.string.selected_sample, name)
        }
    }

    private fun applyContext(value: PreviewDraftContext) {
        if (destroyed) return
        domainContext = value
        val wasRestoring = restoringFields
        restoringFields = true
        objective.setText(value.objective.orEmpty())
        contextInput.setText(value.context.orEmpty())
        restoringFields = wasRestoring
        updateContextState()
    }

    private fun updateContextState() {
        if (destroyed) return
        if (!::contextState.isInitialized) return
        fun label(value: String?) = getString(when {
            value == null -> R.string.context_unanswered
            value.isEmpty() -> R.string.context_empty
            else -> R.string.context_entered
        })
        contextState.text = getString(R.string.context_state, label(domainContext.objective), label(domainContext.context))
    }

    private fun matchesSaved() = savedRecord?.let { it.fields == fields() && it.contextInput == domainContext &&
        it.workloadInput == workloadSection.inputs.parsed() } == true

    private fun fields() = PreviewDraftFields(title.text.toString(), selectedId, selectedVersion, selectedCatalogHash)

    private fun pendingInput(): PreviewPendingDraft? = workloadSection.inputs.parsed()?.let {
        PreviewPendingDraft(fields(), domainContext, it)
    }

    private fun matchesInput(input: PreviewPendingDraft) = fields() == input.fields &&
        domainContext == input.context && workloadSection.inputs.parsed() == input.workload

    private fun formChanged(edited: Boolean = true) {
        if (destroyed) return
        if (::compatibleOptions.isInitialized) compatibleOptions.dismiss()
        updateContextState()
        if (restoringFields || !::saveStatus.isInitialized) return
        autosaveRequested = edited && !matchesSaved()
        if (!storageBusy && storageReady) {
            saveStatus.setText(when {
                matchesSaved() -> R.string.draft_saved
                autosave.isPaused -> R.string.draft_autosave_paused
                else -> R.string.draft_unsaved
            })
        } else if (storageBusy && savingInput != null) {
            saveStatus.setText(if (matchesInput(checkNotNull(savingInput))) R.string.draft_saving else R.string.draft_saving_pending)
        }
        updateEditingEnabled()
    }

    private fun updateEditingEnabled() {
        if (destroyed) return
        val formEditable = !storageBusy || savingInput != null
        title.isEnabled = formEditable
        objective.isEnabled = formEditable
        contextInput.isEnabled = formEditable
        clear.isEnabled = !storageBusy
        workloadSection.setEditable(formEditable)
        save.isEnabled = storageReady && !storageBusy && workloadSection.inputs.parsed() != null && !matchesSaved()
        reopen.isEnabled = !storageBusy && selectionResolved
        history.isEnabled = storageReady && !storageBusy && savedRecord != null
        compareRetained.isEnabled = history.isEnabled
        compareLibrary.isEnabled = !storageBusy && !entryBlocked && selectionResolved
        libraryButton.isEnabled = !storageBusy
        newDraftButton.isEnabled = !storageBusy
        duplicateButton.isEnabled = storageReady && !storageBusy && savedRecord != null
        recoverButton.isEnabled = !storageBusy && savedRecord != null && !matchesSaved() && workloadSection.inputs.parsed() != null
        retryDeleteButton.visibility = if (pendingDeleteId == null) View.GONE else View.VISIBLE
        retryDeleteButton.isEnabled = pendingDeleteId != null && !storageBusy
        if (::tierSection.isInitialized) tierSection.update(savedRecord, storageReady && !storageBusy && matchesSaved())
        if (::catalog.isInitialized) catalog.selectionEnabled(!storageBusy)
        if (::componentSection.isInitialized) componentSection.update(savedRecord, storageReady && !storageBusy && matchesSaved(), currentDraftId.value)
        updateIncomingComponent()
        if (::planningSection.isInitialized) planningSection.update(savedRecord, when {
            storageBusy -> PlanningDraftState.LOADING
            !storageReady -> PlanningDraftState.UNAVAILABLE
            savedRecord == null -> PlanningDraftState.UNSAVED
            !matchesSaved() -> PlanningDraftState.PENDING_EDITS
            else -> PlanningDraftState.SAVED
        })
        updateDraftSummary()
        updateAutosave()
    }

    private fun updateDraftSummary() {
        if (destroyed || !::draftSummary.isInitialized || !::workloadSection.isInitialized) return
        val baseline = savedRecord
        val id = currentDraftId
        val authority = storageAuthority
        val pendingFields = fields()
        val pendingContext = domainContext
        val pendingInputs = workloadSection.inputs
        val state = when {
            storageBusy -> PlanningDraftState.LOADING
            !storageReady || !selectionResolved || entryBlocked -> PlanningDraftState.UNAVAILABLE
            baseline == null -> PlanningDraftState.UNSAVED
            !matchesSaved() -> PlanningDraftState.PENDING_EDITS
            else -> PlanningDraftState.SAVED
        }
        fun current() = !destroyed && !isFinishing && !isDestroyed && diagnosticsResumed &&
            storageReady && !storageBusy && selectionResolved && !entryBlocked && currentDraftId == id &&
            savedRecord === baseline && storageAuthority === authority && fields() == pendingFields &&
            domainContext == pendingContext && workloadSection.inputs === pendingInputs
        val canReviewComponents = state == PlanningDraftState.SAVED && baseline != null &&
            ::componentSection.isInitialized && componentSection.canReviewCurrentChoices(baseline)
        draftSummary.update(baseline, state, pendingInputs, selectedId != null, canReviewComponents,
            ::current,
            { workloadSection.reviewObjectiveInputs(pendingInputs) },
            { if (baseline != null) componentSection.reviewCurrentChoices(baseline) },
            { if (::planningSection.isInitialized) planningSection.reviewGuidance() })
    }

    private fun updateAutosave() {
        if (!storageReady || storageBusy || !selectionResolved || !autosaveRequested || matchesSaved() || autosave.isPaused) {
            autosave.cancel()
            return
        }
        val pending = pendingInput()
        if (pending == null) {
            autosave.cancel()
            return
        }
        if (!autosave.schedule(PreviewAutosaveRequest(currentDraftId, savedRecord, storageAuthority, pending))) {
            saveStatus.setText(R.string.draft_save_failed)
        }
    }

    private fun showHistory() {
        if (destroyed) return
        val baseline = savedRecord ?: return
        if (!storageReady || storageBusy) return
        retireRevisionInspection()
        val revisions = baseline.history.reversed()
        val rows = revisions.map { revision ->
            getString(if (revision.fields == null) R.string.history_unavailable else R.string.history_revision,
                revision.revision.toString()) + " · " + revision.change.name +
                (revision.restoredFrom?.let { getString(R.string.domain_history_source, it.toString()) } ?: "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.draft_history)
            .setItems(rows) { dialog, index ->
                if (!historyDialogIsCurrent(dialog, baseline)) return@setItems
                val revision = revisions[index]
                val oldFields = revision.fields
                if (oldFields == null) {
                    AlertDialog.Builder(this).setMessage(R.string.history_unavailable_detail)
                        .setNeutralButton(R.string.domain_restore_preview) { dialog, _ ->
                            if (historyDialogIsCurrent(dialog, baseline)) confirmDomainRestore(baseline, revision)
                        }
                        .setPositiveButton(R.string.close, null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.history_revision, revision.revision.toString()))
                        .setMessage(getString(R.string.history_copy_warning, oldFields.title,
                            if (baseline.earlierHistoryUnavailable) getString(R.string.history_earlier_unavailable) else ""))
                        .setNegativeButton(R.string.keep_edits, null)
                        .setNeutralButton(R.string.domain_restore_preview) { dialog, _ ->
                            if (historyDialogIsCurrent(dialog, baseline)) confirmDomainRestore(baseline, revision)
                        }
                        .setPositiveButton(R.string.history_copy) { dialog, _ ->
                            // A dialog opened before another save/load cannot adopt that operation's baseline.
                            if (historyDialogIsCurrent(dialog, baseline)) {
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
            }.setPositiveButton(R.string.revision_compare_open) { dialog, _ ->
                if (historyDialogIsCurrent(dialog, baseline)) showComparisonPicker(baseline)
            }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun historyDialogIsCurrent(dialog: DialogInterface, baseline: PreviewStoredDraft): Boolean =
        !destroyed && (dialog as? AlertDialog)?.isShowing == true && storageReady && !storageBusy && savedRecord === baseline

    private fun showComparisonPicker(baseline: PreviewStoredDraft, pickBoth: Boolean = false) {
        if (destroyed || isFinishing) return
        if (!storageReady || storageBusy || savedRecord !== baseline) return
        retireRevisionInspection()
        val request = Any()
        revisionRequest = request
        val authority = storageAuthority
        val draftId = currentDraftId
        fun current() = !destroyed && !isFinishing && storageReady && !storageBusy &&
            currentDraftId == draftId && savedRecord === baseline && storageAuthority === authority && revisionRequest === request
        val revisions = baseline.history.reversed()
        val rows = revisions.map { getString(R.string.history_revision, it.revision.toString()) }.toTypedArray()
        fun pickerTitle(label: String) = TextView(this).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        fun open(dialog: AlertDialog) = current() && revisionDialog === dialog && dialog.isShowing
        fun prepare(from: Long, to: Long) {
            val preparing = AlertDialog.Builder(this)
                .setMessage(getString(R.string.revision_compare_preparing, from.toString(), to.toString()))
                .setNegativeButton(R.string.close, null).create()
            presentRevisionDialog(preparing)
            worker.execute {
                val comparison = baseline.compareRevisions(from, to)
                runOnUiThread {
                    if (current() && revisionDialog === preparing && preparing.isShowing) {
                        revisionDialog = null
                        preparing.dismiss()
                        if (comparison == null) {
                            retireRevisionInspection()
                            AlertDialog.Builder(this).setMessage(R.string.revision_compare_changed)
                                .setPositiveButton(R.string.close, null).show()
                        } else {
                            revisionBrowser = RevisionComparisonBrowser(this, comparison, ::current).also { it.show() }
                        }
                    }
                }
            }
        }
        lateinit var chooseBefore: () -> Unit
        fun chooseAfter(from: Long) {
            lateinit var picker: AlertDialog
            picker = AlertDialog.Builder(this).setCustomTitle(pickerTitle(getString(R.string.revision_compare_choose_after, from.toString())))
                .setItems(rows) { _, index ->
                    if (open(picker) && index in revisions.indices) prepare(from, revisions[index].revision)
                }.setNeutralButton(R.string.revision_compare_back_to_before) { _, _ ->
                    if (open(picker)) chooseBefore()
                }.setNegativeButton(R.string.close, null).create()
            presentRevisionDialog(picker)
        }
        chooseBefore = {
            lateinit var picker: AlertDialog
            picker = AlertDialog.Builder(this).setCustomTitle(pickerTitle(getString(
                if (pickBoth) R.string.revision_compare_choose_before else R.string.revision_compare_open)))
                .setItems(rows) { _, index ->
                    if (open(picker) && index in revisions.indices) {
                        val from = revisions[index].revision
                        if (pickBoth) chooseAfter(from) else prepare(from, baseline.revision)
                    }
                }.setNegativeButton(R.string.close, null).create()
            presentRevisionDialog(picker)
        }
        chooseBefore()
    }

    private fun presentRevisionDialog(dialog: AlertDialog) {
        val previous = revisionDialog
        revisionDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener {
            if (revisionDialog === dialog) {
                revisionDialog = null
                revisionRequest = null
            }
        }
        dialog.show()
    }

    private fun retireRevisionInspection() {
        revisionRequest = null
        revisionDialog?.dismiss()
        revisionDialog = null
        revisionBrowser?.retire()
        revisionBrowser = null
        libraryComparison?.retire()
        libraryComparison = null
    }

    private fun showLibraryComparison() {
        if (destroyed || isFinishing || storageBusy || entryBlocked || !selectionResolved) return
        retireRevisionInspection()
        catalogComparison.dismissInspection()
        catalogPinRecovery.dismiss()
        catalogDetailDialog?.dismiss()
        catalogDetailDialog = null
        catalogPriceBrowser?.retire()
        catalogPriceBrowser = null
        catalogDetailBrowser?.retire()
        catalogDetailBrowser = null
        val request = Any()
        revisionRequest = request
        val authority = storageAuthority
        val draftId = currentDraftId
        val baseline = savedRecord
        fun current() = !destroyed && !isFinishing && !storageBusy && !entryBlocked && selectionResolved &&
            currentDraftId == draftId && savedRecord === baseline && storageAuthority === authority && revisionRequest === request
        libraryComparison = LibraryRevisionComparison(this, { task -> worker.execute { task() } },
            { library().list() }, ::current).also { it.show() }
    }

    private fun commitSelection(expected: PreviewStoredDraft, hash: String?, prepare: () -> PreviewSelectionPreparation,
        completed: (Boolean, String) -> Unit) {
        if (destroyed || !storageReady || storageBusy || savedRecord !== expected || !matchesSaved() ||
            hash != null && componentCatalog?.hash?.hex != hash) { completed(false, getString(R.string.draft_reconcile)); return }
        val id = currentDraftId
        val snapshot = componentCatalog
        storageBusy = true
        saveStatus.setText(R.string.draft_saving)
        updateEditingEnabled()
        worker.execute {
            val result = try {
                when (val prepared = prepare()) {
                    PreviewSelectionPreparation.Invalid -> null
                    is PreviewSelectionPreparation.Ready -> openPersistence(id).applySelection(expected, prepared.command, snapshot)
                }
            } catch (_: Exception) { PreviewSelectionSave.Uncertain }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    var acknowledged = false
                    when (result) {
                        null -> saveStatus.setText(R.string.component_invalid)
                        is PreviewSelectionSave.Saved -> {
                            savedRecord = result.record
                            acknowledged = true
                            saveStatus.setText(if (result.change == null) R.string.component_noop else R.string.component_saved)
                        }
                        PreviewSelectionSave.Conflict -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_conflict)
                        }
                        PreviewSelectionSave.Uncertain -> {
                            storageReady = false
                            saveStatus.setText(R.string.draft_uncertain)
                        }
                        is PreviewSelectionSave.Rejected -> saveStatus.text = getString(R.string.component_rejected, result.reason.name)
                        is PreviewSelectionSave.Failed -> saveStatus.setText(if (result.reason == DraftStorageFailure.LIMIT_EXCEEDED)
                            R.string.draft_history_full else R.string.draft_save_failed)
                    }
                    updateEditingEnabled()
                    completed(acknowledged, saveStatus.text.toString())
                }
            }
        }
    }

    private fun showCompatibleOptions(launch: CompatiblePickerLaunch) {
        val fixture = catalogFixture ?: return
        val bytes = fixture.catalogBytes() ?: return
        val authority = storageAuthority
        val id = currentDraftId
        val capturedFields = fields()
        val capturedContext = domainContext
        val capturedWorkload = workloadSection.inputs
        fun current() = !destroyed && !isFinishing && !isDestroyed && diagnosticsResumed &&
            storageReady && !storageBusy && !entryBlocked && selectionResolved && retry.isEnabled &&
            savedRecord === launch.source && matchesSaved() && currentDraftId == id && storageAuthority === authority &&
            fields() == capturedFields && domainContext == capturedContext && workloadSection.inputs === capturedWorkload &&
            catalogFixture === fixture && fixture.catalog?.hash == launch.catalogHash &&
            componentCatalog?.hash?.hex == launch.catalogHash
        if (current()) compatibleOptions.show(launch, bytes, ::current)
    }

    private fun setDraftActivation(expected: PreviewStoredDraft, tier: OptionalTier, activation: Activation) {
        if (destroyed) return
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

    private fun confirmDomainRestore(baseline: PreviewStoredDraft, revision: PreviewDraftRevision) {
        if (destroyed) return
        if (!storageReady || storageBusy || savedRecord !== baseline) return
        val value: (String?) -> String = ::renderLocalValue
        val content = revision.content
        val summary = getString(R.string.domain_restore_values, value(content.objective), value(content.context),
            value(content.primaryModel), content.optional.entries.joinToString("\n") { (tier, input) ->
                getString(R.string.domain_restore_tier, tier.name, input.activation.name,
                    input.selections.size.toString(), input.settings.size.toString())
            }) + "\n\n" + content.workload.let { w ->
                listOf(R.string.workload_region to w.region, R.string.workload_currency to w.currency,
                    R.string.workload_budget to w.monthlyBudgetAmount?.encoded,
                    R.string.workload_requests to w.requestsPerDay?.toString(),
                    R.string.workload_input_tokens to w.avgInputTokens?.toString(),
                    R.string.workload_output_tokens to w.avgOutputTokens?.toString(),
                    R.string.workload_concurrency to w.maxConcurrency?.toString(),
                    R.string.workload_latency to w.maxLatencyMs?.toString(),
                    R.string.workload_classification to w.dataClassification?.encoded)
                    .joinToString("\n") { (label, input) -> "${getString(label)}: ${value(input)}" }
            }
        val components = getString(R.string.domain_restore_components,
            baseline.components.selections.size.toString(), content.components.selections.size.toString(),
            baseline.components.links.size.toString(), content.components.links.size.toString())
        AlertDialog.Builder(this).setTitle(getString(R.string.history_revision, revision.revision.toString()))
            .setMessage(getString(R.string.domain_restore_warning, summary + "\n\n" + components))
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(R.string.domain_restore_commit) { dialog, _ ->
                if (historyDialogIsCurrent(dialog, baseline)) restoreDomain(baseline, revision.revision)
            }.show()
    }

    private fun restoreDomain(expected: PreviewStoredDraft, revision: Long) {
        if (destroyed) return
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
                            workloadSection.apply(PreviewWorkloadInputs.from(result.record.workloadInput))
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
        if (destroyed) return
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
        if (destroyed) return
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
                        AlertDialog.Builder(this).setTitle(R.string.library_open).setItems(labels) { picker, index ->
                            if ((picker as? AlertDialog)?.isShowing != true || destroyed || storageBusy) return@setItems
                            val entry = entries[index]
                            if (entry.deletionPending) confirmDelete(entry)
                            else AlertDialog.Builder(this).setTitle(getString(R.string.library_local_id, entry.id.value))
                                .setMessage(R.string.library_switch_warning)
                                .setNegativeButton(R.string.keep_edits, null)
                                .setNeutralButton(R.string.library_delete) { detail, _ ->
                                    if ((detail as? AlertDialog)?.isShowing == true) confirmDelete(entry)
                                }
                                .setPositiveButton(R.string.library_open_selected) { detail, _ ->
                                    if ((detail as? AlertDialog)?.isShowing == true && !storageBusy) openDraftTarget(entry.id)
                                }.show()
                        }.setNegativeButton(R.string.close, null).show()
                    }
                }
            }
        }
    }

    private fun confirmDelete(entry: PreviewLibraryEntry) {
        if (destroyed) return
        confirmDelete(entry.id, (entry.state as? PreviewDraftLoad.Available)?.record, entry.deletionPending)
    }

    private fun confirmDelete(id: PreviewDraftId, expected: PreviewStoredDraft?, retry: Boolean) {
        if (destroyed) return
        if (storageBusy) return
        val pending = pendingDeleteId
        if (pending != null && pending != id) {
            AlertDialog.Builder(this).setMessage(getString(R.string.library_existing_cleanup, pending.value))
                .setPositiveButton(R.string.library_retry_delete) { redirect, _ ->
                    if ((redirect as? AlertDialog)?.isShowing == true) confirmDelete(pending, null, true)
                }
                .setNegativeButton(R.string.close, null).show()
            return
        }
        val retryOnly = retry || pending == id
        AlertDialog.Builder(this).setTitle(if (retryOnly) R.string.library_retry_delete else R.string.library_delete)
            .setMessage(getString(if (retryOnly) R.string.library_retry_warning else R.string.library_delete_warning, id.value))
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(if (retryOnly) R.string.library_retry_delete else R.string.library_delete) { confirmation, _ ->
                if ((confirmation as? AlertDialog)?.isShowing == true && !storageBusy) deleteDraft(id, expected, retryOnly)
            }.show()
    }

    private fun deleteDraft(id: PreviewDraftId, expected: PreviewStoredDraft?, retryOnly: Boolean) {
        if (destroyed || storageBusy) return
        val pending = pendingDeleteId
        if (pending != null && pending != id) return
        // A retained confirmation must use the operation that is current at dispatch.
        val retry = retryOnly || pending == id
        val previous = PreviewDraftSession(currentDraftId, savedRecord, fields(), storageReady)
        // Capture the named operation before dispatch so recreation can reconcile even if its row disappears.
        pendingDeleteId = id
        storageBusy = true
        updateEditingEnabled()
        worker.execute {
            val result = try { if (retry) library().retryDeletion(id) else library().delete(id, expected) }
                catch (_: Exception) { PreviewLibraryDelete.Unconfirmed(DraftStorageFailure.IO_FAILURE) }
            runOnUiThread {
                if (!destroyed) {
                    storageBusy = false
                    if (result == PreviewLibraryDelete.Deleted || result == PreviewLibraryDelete.Conflict ||
                        !retry && result is PreviewLibraryDelete.Failed) pendingDeleteId = null
                    val next = previous.afterDeletion(id, result)
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
                        is PreviewLibraryDelete.Failed -> if (retry) R.string.library_retry_failed else R.string.library_delete_failed
                        is PreviewLibraryDelete.Unconfirmed -> R.string.library_delete_unconfirmed
                    }).setPositiveButton(R.string.close, null).show()
                }
            }
        }
    }

    private fun openDraftTarget(target: PreviewDraftId) {
        if (destroyed) return
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
                        entryBlocked = false
                        unsupportedEntry = false
                        entryNotice.visibility = View.GONE
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
                        workloadSection.apply(PreviewWorkloadInputs.from(next.record?.workloadInput ?: DraftWorkload()))
                        restoringFields = false
                        activeDraft.text = getString(R.string.library_active, currentDraftId.value)
                        showSelection()
                        autosave.resume()
                        formChanged(edited = false)
                    }
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun confirmCreate(duplicate: Boolean) {
        if (destroyed) return
        if (storageBusy) return
        val source = if (duplicate) savedRecord ?: return else null
        AlertDialog.Builder(this).setTitle(if (duplicate) R.string.library_duplicate else R.string.library_new)
            .setMessage(if (duplicate) R.string.library_duplicate_warning else R.string.library_new_warning)
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(R.string.library_continue) { confirmation, _ ->
                if ((confirmation as? AlertDialog)?.isShowing == true && !storageBusy &&
                    (!duplicate || savedRecord === source && storageReady)) createDraft(source)
            }.show()
    }

    private fun confirmRecoveryCopy() {
        if (destroyed || storageBusy) return
        val source = savedRecord ?: return
        val rawWorkload = workloadSection.inputs
        val workload = rawWorkload.parsed() ?: return
        if (matchesSaved()) return
        val pending = PreviewPendingDraft(fields(), domainContext, workload)
        AlertDialog.Builder(this).setTitle(R.string.recovery_copy)
            .setMessage(getString(R.string.recovery_warning, source.revision.toString()))
            .setNegativeButton(R.string.keep_edits, null)
            .setPositiveButton(R.string.recovery_confirm) { confirmation, _ ->
                if ((confirmation as? AlertDialog)?.isShowing == true && !destroyed && !storageBusy &&
                    savedRecord === source && fields() == pending.fields &&
                    domainContext == pending.context && workloadSection.inputs === rawWorkload)
                    createDraft(source, pending)
            }.show()
    }

    private fun createDraft(source: PreviewStoredDraft?, pending: PreviewPendingDraft? = null) {
        if (destroyed) return
        storageBusy = true
        updateEditingEnabled()
        worker.execute {
            val result = try {
                if (source == null) library().create()
                else if (pending == null) library().duplicate(source)
                else library().recoverPending(source, pending)
            } catch (_: Exception) { null }
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
        if (destroyed) return
        val id = currentDraftId
        val readCheckpoint = if (preserveRestoredFields) restoration
            else PreviewDraftRestoration.capture(savedRecord, storageReady, storageBusy)
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
                    if (!loadSucceeded) failedReadCheckpoint = readCheckpoint
                    val needsReopen = preserveRestoredFields && restoration?.canResume(savedRecord) != true
                    storageReady = loadSucceeded && !needsReopen
                    if (!preserveRestoredFields && loadSucceeded) {
                        restoration = null
                        autosaveRequested = false
                        autosave.resume()
                    }
                    if (loadSucceeded) {
                        val checkpoint = PreviewContextCheckpoint(!restoreContextFromDisk, domainContext)
                        applyContext(checkpoint.forLoad(preserveRestoredFields, savedRecord?.contextInput ?: PreviewDraftContext()))
                        restoreContextFromDisk = false
                        val workloadCheckpoint = PreviewWorkloadCheckpoint(!restoreWorkloadFromDisk, workloadSection.inputs)
                        workloadSection.apply(workloadCheckpoint.forLoad(preserveRestoredFields, savedRecord?.workloadInput ?: DraftWorkload()))
                        restoreWorkloadFromDisk = false
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
                        !loadSucceeded -> draftLoadRecoveryMessage((result as? PreviewDraftLoad.Failed)?.reason)
                        needsReopen -> R.string.draft_reconcile
                        matchesSaved() -> R.string.draft_saved
                        autosave.isPaused -> R.string.draft_autosave_paused
                        else -> R.string.draft_unsaved
                    })
                    updateEditingEnabled()
                }
            }
        }
    }

    private fun saveDraft(request: PreviewAutosaveRequest? = null) {
        if (destroyed) return
        if (!storageReady || storageBusy) return
        val id = currentDraftId
        val pending = pendingInput() ?: return
        val expected = savedRecord
        if (request != null && !request.matches(id, expected, storageAuthority, pending)) return
        if (request == null) autosave.resume() // Explicit retry; readiness still excludes uncertain/conflicting authority.
        savingInput = pending
        storageBusy = true
        val authority = storageAuthority
        saveStatus.setText(R.string.draft_saving)
        updateEditingEnabled()
        fun accept(result: PreviewDraftSave) {
            if (destroyed || !storageBusy || currentDraftId != id || savedRecord !== expected || storageAuthority !== authority) return
            storageBusy = false
            savingInput = null
            autosave.completed(result)
            when (result) {
                is PreviewDraftSave.Saved -> {
                    savedRecord = result.record
                    autosaveRequested = !matchesSaved()
                    saveStatus.setText(if (matchesSaved()) R.string.draft_saved else R.string.draft_unsaved)
                }
                PreviewDraftSave.Conflict -> {
                    storageReady = false
                    saveStatus.setText(R.string.draft_conflict)
                }
                PreviewDraftSave.Uncertain -> {
                    storageReady = false
                    saveStatus.setText(R.string.draft_uncertain)
                }
                is PreviewDraftSave.Failed -> saveStatus.setText(if (result.reason == DraftStorageFailure.LIMIT_EXCEEDED)
                    R.string.draft_history_full else R.string.draft_save_failed)
            }
            updateEditingEnabled()
        }
        try {
            worker.execute {
                val result = try { openPersistence(id).save(expected, pending.fields, pending.context, pending.workload) }
                    catch (_: Exception) { PreviewDraftSave.Uncertain }
                runOnUiThread { accept(result) }
            }
        } catch (_: RejectedExecutionException) {
            accept(PreviewDraftSave.Failed(DraftStorageFailure.IO_FAILURE))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!environmentAdmitted) { super.onSaveInstanceState(outState); return }
        outState.putBoolean("entryBlocked", entryBlocked)
        outState.putBoolean("unsupportedEntry", unsupportedEntry)
        outState.putBoolean("incomingComponentPresent", incomingComponent != null)
        incomingComponent?.let {
            outState.putString("incomingComponentKind", it.kind)
            outState.putString("incomingComponentId", it.itemId)
            outState.putString("incomingComponentVersion", it.version)
            outState.putString("incomingComponentSnapshot", it.snapshotHash)
        }
        catalog.saveState(outState)
        catalogComparison.save(outState)
        componentSection.save(outState)
        outState.putString("pendingDeleteId", pendingDeleteId?.value)
        outState.putString("draftId", currentDraftId.value)
        outState.putBoolean("selectionResolved", selectionResolved)
        outState.putBoolean("autosaveRequested", autosaveRequested)
        outState.putBoolean("autosavePaused", autosave.isPaused)
        val checkpoint = PreviewDraftRestoration.capture(savedRecord, storageReady, storageBusy)
        outState.putString("draftBaseline", checkpoint.baselineIdentity)
        outState.putBoolean("draftRequiresReopen", checkpoint.requiresReopen)
        workloadSection.save(outState)
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

    override fun onResume() {
        super.onResume()
        diagnosticsResumed = true
        updateDraftSummary()
    }

    override fun onPause() {
        diagnosticsResumed = false
        if (::compatibleOptions.isInitialized) compatibleOptions.dismiss()
        if (::draftSummary.isInitialized) draftSummary.retireActions()
        if (::diagnostics.isInitialized) diagnostics.dismiss()
        if (::help.isInitialized) help.dismiss()
        if (::snapshots.isInitialized) snapshots.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        if (::compatibleOptions.isInitialized) compatibleOptions.close()
        if (::draftSummary.isInitialized) draftSummary.close()
        autosave.close()
        savingInput = null
        if (::findings.isInitialized) findings.close()
        if (::snapshots.isInitialized) snapshots.close()
        if (::diagnostics.isInitialized) diagnostics.close()
        if (::help.isInitialized) help.close()
        if (::catalogPinRecovery.isInitialized) catalogPinRecovery.close()
        catalogDetailDialog?.dismiss()
        catalogDetailDialog = null
        catalogPriceBrowser?.retire()
        catalogPriceBrowser = null
        catalogDetailBrowser?.retire()
        catalogDetailBrowser = null
        if (::catalog.isInitialized) catalog.close()
        if (::catalogComparison.isInitialized) catalogComparison.close()
        if (::componentSection.isInitialized) componentSection.close()
        storageReady = false
        storageBusy = true
        if (::tierSection.isInitialized) tierSection.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}
