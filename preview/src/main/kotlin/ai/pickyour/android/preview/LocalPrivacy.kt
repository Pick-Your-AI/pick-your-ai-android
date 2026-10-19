package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Fixed disclosures for the admitted preview. No data capture, preferences or external actions. */
internal class LocalPrivacy(
    private val activity: Activity,
    private val beforeShow: () -> Unit = {},
    private val alive: () -> Boolean,
) {
    private var currentDialog: AlertDialog? = null
    private var closed = false

    fun attach(container: LinearLayout) {
        container.addView(Button(activity).apply {
            id = R.id.privacy_open
            setText(R.string.privacy_title)
            isSingleLine = false
            isAllCaps = false
            minimumHeight = dp(48)
            minHeight = dp(48)
            minimumWidth = dp(48)
            minWidth = dp(48)
            setOnClickListener { show() }
        })
    }

    private fun current() = !closed && alive() && !activity.isFinishing && !activity.isDestroyed

    private fun show() {
        if (!current()) return
        beforeShow()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(text(R.string.privacy_scope))
        }
        section(content, R.string.privacy_drafts_title, R.string.privacy_drafts)
        section(content, R.string.privacy_samples_title, R.string.privacy_samples)
        section(content, R.string.privacy_diagnostics_title, R.string.privacy_diagnostics)
        section(content, R.string.privacy_browser_title, R.string.privacy_browser)
        section(content, R.string.privacy_delete_title, R.string.privacy_delete)
        section(content, R.string.privacy_recovery_title, R.string.privacy_recovery)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.privacy_title)
            .setView(ScrollView(activity).apply {
                id = R.id.privacy_scroll
                if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = activity.getString(R.string.privacy_title)
                addView(content)
            })
            .setNegativeButton(R.string.close, null)
            .create()
        val previous = currentDialog
        currentDialog = dialog
        previous?.dismiss()
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
        dialog.show()
    }

    private fun section(content: LinearLayout, heading: Int, body: Int) {
        content.addView(text(heading).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        content.addView(text(body))
    }

    private fun text(resource: Int) = TextView(activity).apply {
        setText(resource)
        setPadding(0, dp(8), 0, dp(8))
    }

    fun dismiss() {
        val previous = currentDialog
        currentDialog = null
        previous?.dismiss()
    }

    fun close() {
        closed = true
        dismiss()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
