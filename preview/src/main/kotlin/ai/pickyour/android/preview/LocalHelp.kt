package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.navigation.ExternalLinkPolicy
import ai.pickyour.core.navigation.LinkDecision

/** Source-owned destinations only. No user text, draft, diagnostic report or arbitrary URL input. */
internal enum class HelpDestination(val label: Int, val control: Int, val url: String) {
    BUG(R.string.help_bug, R.id.help_bug,
        "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/new?template=android-bug.yml"),
    EVIDENCE(R.string.help_evidence, R.id.help_evidence,
        "https://github.com/Pick-Your-AI/pick-your-ai-android/issues/new?template=android-evidence-correction.yml"),
    EXISTING(R.string.help_existing, R.id.help_existing,
        "https://github.com/Pick-Your-AI/pick-your-ai-android/issues"),
}

/** Offline guidance with a separate, explicit, one-use handoff to a fixed public project page. */
internal class LocalHelp(
    private val activity: Activity,
    private val alive: () -> Boolean,
    private val reviewDiagnostics: () -> Unit,
    private val launch: (Intent) -> Unit = { activity.startActivity(it) },
    private val captureCatalogIdentity: () -> HelpCatalogIdentity = { HelpCatalogIdentity.UNAVAILABLE_OR_LOADING },
) {
    private var currentDialog: AlertDialog? = null
    private var closed = false
    private val policy = ExternalLinkPolicy(setOf("github.com"))

    fun attach(container: LinearLayout) {
        container.addView(button(R.string.help_open, R.id.help_open).apply {
            setOnClickListener { show() }
        })
    }

    private fun current() = !closed && alive() && !activity.isFinishing && !activity.isDestroyed
    private fun current(dialog: AlertDialog) = current() && currentDialog === dialog && dialog.isShowing

    fun show() {
        if (!current()) return
        val catalogIdentity = captureCatalogIdentity() // One finite snapshot per current Help dialog.
        val content = content()
        content.addView(text(activity.getString(R.string.help_build,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)))
        content.addView(text(activity.getString(R.string.help_environment, BuildConfig.APPLICATION_ID)))
        val catalogMessage = when (catalogIdentity) {
            HelpCatalogIdentity.BUNDLED_V1 -> R.string.help_catalog_known
            HelpCatalogIdentity.UNRECOGNIZED -> R.string.help_catalog_unrecognized
            HelpCatalogIdentity.UNAVAILABLE_OR_LOADING -> R.string.help_catalog_unavailable
        }
        content.addView(text(activity.getString(catalogMessage)).apply { id = R.id.help_catalog_identity })
        section(content, R.string.help_local_title, R.string.help_local)
        section(content, R.string.help_recovery_title, R.string.help_recovery)
        section(content, R.string.help_reproduction_title, R.string.help_reproduction)
        section(content, R.string.help_public_title, R.string.help_public)
        section(content, R.string.help_private_title, R.string.help_private)
        section(content, R.string.help_delete_title, R.string.help_delete)
        val dialog = dialog(R.string.help_title, content)
        var consumed = false
        for (destination in HelpDestination.entries) {
            content.addView(button(destination.label, destination.control).apply {
                setOnClickListener {
                    if (!current(dialog) || consumed) return@setOnClickListener
                    consumed = true
                    confirm(destination)
                }
            })
        }
        content.addView(button(R.string.diagnostics_review, R.id.help_diagnostics).apply {
            setOnClickListener {
                if (!current(dialog) || consumed) return@setOnClickListener
                consumed = true
                dialog.dismiss()
                reviewDiagnostics() // Reuse the existing report preview; do not capture or attach it here.
            }
        })
        present(dialog)
    }

    private fun confirm(destination: HelpDestination) {
        if (!current()) return
        val content = content()
        content.addView(text(activity.getString(destination.label)))
        content.addView(text(activity.getString(R.string.help_external_disclosure)))
        content.addView(text(destination.url).apply {
            id = R.id.help_destination
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
        })
        val outcome = text("").apply {
            id = R.id.help_external_outcome
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val open = button(R.string.help_external_open, R.id.help_external_open)
        content.addView(open)
        content.addView(outcome)
        val dialog = dialog(R.string.help_confirm_title, content)
        var consumed = false
        open.setOnClickListener {
            if (!current(dialog) || consumed) return@setOnClickListener
            consumed = true
            open.isEnabled = false
            // Inspect exactly the displayed compiled URI at dispatch. No draft-derived query or payload.
            val accepted = policy.inspect(destination.url) as? LinkDecision.Candidate
            if (accepted == null) {
                outcome.setText(R.string.help_external_unavailable)
                outcome.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(accepted.link.uri.toString()))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                launch(intent)
                dialog.dismiss() // Browser launch is not report submission, receipt or triage.
            } catch (_: ActivityNotFoundException) {
                outcome.setText(R.string.help_external_unavailable)
                outcome.visibility = View.VISIBLE
            } catch (_: SecurityException) {
                outcome.setText(R.string.help_external_unavailable)
                outcome.visibility = View.VISIBLE
            }
        }
        present(dialog)
    }

    private fun present(dialog: AlertDialog) {
        if (!current()) return
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
        dialog.show()
    }

    private fun dialog(title: Int, content: LinearLayout) = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(ScrollView(activity).apply { addView(content) })
        .setNegativeButton(R.string.close, null)
        .create()

    private fun content() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun section(content: LinearLayout, heading: Int, body: Int) {
        content.addView(text(activity.getString(heading)).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        content.addView(text(activity.getString(body)))
    }

    private fun text(value: String) = TextView(activity).apply {
        text = value
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(label: Int, control: Int) = Button(activity).apply {
        id = control
        setText(label)
        isSingleLine = false
        isAllCaps = false
        minimumHeight = dp(48)
        minHeight = dp(48)
        minimumWidth = dp(48)
        minWidth = dp(48)
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
