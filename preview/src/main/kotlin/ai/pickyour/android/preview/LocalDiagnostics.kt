package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Finite UI states only: this boundary cannot accept a record, path, URL, log or exception. */
internal enum class DiagnosticStorageState { BUSY, READY, RECONCILIATION_REQUIRED, UNAVAILABLE }
internal enum class DiagnosticCatalogState { SYNTHETIC_LOADED, UNAVAILABLE_OR_LOADING }

/**
 * An immutable allowlisted report. Values come only from compiled app metadata, the OS API integer
 * and finite UI state enums. No reflection, record serialization, file read or redaction heuristic.
 * Format 1 is a local diagnostic format, not a shared build/export contract.
 */
internal class LocalDiagnosticReport(
    storage: DiagnosticStorageState,
    catalog: DiagnosticCatalogState,
) {
    val text: String = listOf(
        "diagnostic_format=pyai.android.preview.diagnostics.v1",
        "application_id=${BuildConfig.APPLICATION_ID}",
        "application_version=${BuildConfig.VERSION_NAME}",
        "application_version_code=${BuildConfig.VERSION_CODE}",
        "android_api=${Build.VERSION.SDK_INT}",
        "client_mode=synthetic_guest_preview",
        "storage_state=${storage.name}",
        "catalog_state=${catalog.name}",
        "private_content=excluded",
        "support_submission=unavailable",
    ).joinToString("\n")

    override fun toString() = "LocalDiagnosticReport"
}

/** Local preview followed only by an explicit, one-use request for the system text Sharesheet. */
internal class LocalDiagnostics(
    private val activity: Activity,
    private val capture: () -> LocalDiagnosticReport,
    private val alive: () -> Boolean,
    private val beforeShow: () -> Unit = {},
    private val launch: (Intent) -> Unit = { activity.startActivity(it) },
) {
    private var currentDialog: AlertDialog? = null
    private var closed = false

    fun attach(container: LinearLayout) {
        container.addView(TextView(activity).apply { setText(R.string.diagnostics_support_unavailable) })
        container.addView(Button(activity).apply {
            setText(R.string.diagnostics_review)
            isSingleLine = false
            minimumHeight = dp(48)
            minHeight = dp(48)
            setOnClickListener { show() }
        })
    }

    private fun current() = !closed && alive() && !activity.isFinishing && !activity.isDestroyed

    fun show() {
        if (!current()) return
        beforeShow()
        val report = capture() // Capture once; dispatch uses these exact displayed bytes.
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        content.addView(TextView(activity).apply { setText(R.string.diagnostics_disclosure) })
        content.addView(TextView(activity).apply {
            id = R.id.diagnostic_report
            text = report.text
            textDirection = View.TEXT_DIRECTION_LTR // Stable machine field names, independent of locale.
            setPadding(0, dp(12), 0, dp(12))
            setTextIsSelectable(true)
        })
        val outcome = TextView(activity).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val share = Button(activity).apply {
            id = R.id.diagnostic_share
            setText(R.string.diagnostics_share)
            isSingleLine = false
            minimumHeight = dp(48)
            minHeight = dp(48)
        }
        content.addView(share)
        content.addView(outcome)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.diagnostics_review)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.close, null)
            .create()
        var consumed = false
        share.setOnClickListener {
            if (!current() || currentDialog !== dialog || !dialog.isShowing || consumed) return@setOnClickListener
            consumed = true
            share.isEnabled = false
            // Only the reviewed plain text. No recipient, attachment, data URI or URI grant.
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report.text)
            }
            try {
                launch(Intent.createChooser(send, activity.getString(R.string.diagnostics_chooser)))
                dialog.dismiss() // Starting a chooser does not acknowledge delivery to a recipient.
            } catch (_: ActivityNotFoundException) {
                outcome.setText(R.string.diagnostics_share_unavailable)
                outcome.visibility = View.VISIBLE
            } catch (_: SecurityException) {
                outcome.setText(R.string.diagnostics_share_unavailable)
                outcome.visibility = View.VISIBLE
            }
        }
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener {
            consumed = true
            if (currentDialog === dialog) currentDialog = null
        }
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
        dialog.show()
    }

    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    fun close() {
        closed = true
        dismiss()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
