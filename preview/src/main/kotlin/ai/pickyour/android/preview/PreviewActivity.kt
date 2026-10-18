package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
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

/** Guest UI scaffolding over bundled synthetic fixtures, not a durable draft repository. */
class PreviewActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var selectedId: String? = null
    private lateinit var title: EditText
    private lateinit var selection: TextView
    private lateinit var catalog: LinearLayout
    private lateinit var catalogStatus: TextView
    private lateinit var retry: Button
    private var items: List<SampleItem> = emptyList()

    private data class SampleItem(val id: String, val name: String, val kind: String, val version: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("selectedId")
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
            isSaveEnabled = false // Explicit small restoration state below; never durable-save acknowledgement.
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
        content.addView(Button(this).apply {
            setText(R.string.clear_draft)
            setOnClickListener {
                title.setText("")
                selectedId = null
                showSelection()
            }
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
        loadCatalog()
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
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    SampleItem(row.getString("id"), row.getString("name"), row.getString("kind"), row.getString("version"))
                }
            } catch (_: Exception) { null }
            runOnUiThread {
                if (!destroyed) {
                    retry.isEnabled = true
                    catalog.removeAllViews()
                    items = loaded.orEmpty()
                    catalogStatus.setText(if (loaded == null) R.string.catalog_unavailable else R.string.catalog_ready)
                    items.forEach { item ->
                        catalog.addView(Button(this).apply {
                            text = getString(R.string.item_button, item.name, item.kind)
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
            .setPositiveButton(R.string.select_sample) { _, _ -> selectedId = item.id; showSelection() }
            .show()
    }

    private fun showSelection() {
        val item = items.firstOrNull { it.id == selectedId }
        selection.text = when {
            selectedId == null -> getString(R.string.no_selection)
            item == null -> getString(R.string.selection_unavailable)
            else -> getString(R.string.selected_sample, item.name)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("title", title.text.toString().take(120))
        outState.putString("selectedId", selectedId)
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
