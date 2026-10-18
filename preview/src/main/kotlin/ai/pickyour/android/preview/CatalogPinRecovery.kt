package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/** Read-only exact-pin inspection; each dialog and worker result belongs to one live origin. */
internal class CatalogPinRecovery(
    private val activity: Activity,
    private val worker: ExecutorService,
    private val browseCurrent: () -> Unit,
) {
    private class Request(val pin: PreviewCatalogPin, val captured: PreviewCatalogBrowser?, val originIsCurrent: () -> Boolean) {
        var dialog: AlertDialog? = null
        var future: Future<*>? = null
    }
    private var request: Request? = null
    private var closed = false

    fun open(pin: PreviewCatalogPin, captured: PreviewCatalogBrowser?, originIsCurrent: () -> Boolean) {
        if (closed || activity.isDestroyed || activity.isFinishing || !originIsCurrent()) return
        dismiss()
        val next = Request(pin, captured, originIsCurrent)
        request = next
        show(next, null)
        try {
            next.future = worker.submit {
                val result = try {
                    PreviewCatalogPinResolver { hash -> CatalogRetainedSnapshots.read(activity.noBackupFilesDir.toPath(), hash) }
                        .resolve(pin, captured)
                } catch (_: Exception) {
                    PreviewCatalogPinResolution.Unavailable(PreviewCatalogPinProblem.READ_FAILED)
                }
                activity.runOnUiThread {
                    if (current(next, next.dialog)) show(next, result)
                    else if (request === next) dismiss()
                }
            }
        } catch (_: RejectedExecutionException) {
            if (current(next, next.dialog)) show(next, PreviewCatalogPinResolution.Unavailable(PreviewCatalogPinProblem.CANCELLED))
        }
    }

    private fun current(value: Request, dialog: AlertDialog?) = !closed && !activity.isDestroyed && !activity.isFinishing &&
        request === value && value.originIsCurrent() && dialog != null && value.dialog === dialog && dialog.isShowing

    private fun show(value: Request, result: PreviewCatalogPinResolution?) {
        val builder = AlertDialog.Builder(activity)
        lateinit var dialog: AlertDialog
        builder.setCustomTitle(LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL
            val target = kotlin.math.ceil(48 * resources.displayMetrics.density.toDouble()).toInt()
            val inset = kotlin.math.ceil(16 * resources.displayMetrics.density.toDouble()).toInt()
            setPadding(inset, inset, inset, 0)
            addView(TextView(builder.context).apply {
                setText(R.string.catalog_pin_title)
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            fun action(label: Int, enabled: Boolean = true, invoked: () -> Unit) {
                addView(Button(builder.context).apply {
                    setText(label)
                    setSingleLine(false)
                    minHeight = target
                    minWidth = target
                    isEnabled = enabled
                    setOnClickListener { if (isEnabled && current(value, dialog)) invoked() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            action(R.string.catalog_pin_retry, result != null) { open(value.pin, value.captured, value.originIsCurrent) }
            action(R.string.catalog_pin_browse) { dismiss(); browseCurrent() }
        })
        val identity = activity.getString(R.string.catalog_pin_identity,
            CatalogDetailText.literal(activity, value.pin.id), CatalogDetailText.literal(activity, value.pin.version), value.pin.snapshotHash)
        val details = when (result) {
            null -> activity.getString(R.string.catalog_pin_loading)
            is PreviewCatalogPinResolution.Unavailable -> activity.getString(problemText(result.problem))
            is PreviewCatalogPinResolution.Available -> listOf(
                activity.getString(if (result.source == PreviewCatalogPinSource.CAPTURED_CATALOG)
                    R.string.catalog_pin_captured else R.string.catalog_pin_retained),
                activity.getString(R.string.catalog_pin_recorded_status),
                activity.getString(R.string.catalog_pin_prices),
                CatalogDetailText.describe(activity, result.detail),
            ).joinToString("\n\n")
        }
        dialog = builder.setMessage(listOf(identity, activity.getString(R.string.catalog_pin_notice), details).joinToString("\n\n"))
            .setNegativeButton(R.string.close, null).create()
        val previous = value.dialog
        value.dialog = dialog
        // Replacing a loading/result view keeps this request; a user dismissal retires it permanently.
        previous?.dismiss()
        dialog.setOnDismissListener { if (request === value && value.dialog === dialog) dismiss() }
        dialog.show()
        dialog.window?.decorView?.layoutDirection = activity.resources.configuration.layoutDirection
    }

    fun dismiss() {
        val retired = request
        request = null
        retired?.future?.cancel(true)
        retired?.dialog?.dismiss()
    }

    fun close() { closed = true; dismiss() }

    private fun problemText(problem: PreviewCatalogPinProblem): Int = when (problem) {
        PreviewCatalogPinProblem.SNAPSHOT_MISSING -> R.string.catalog_pin_missing
        PreviewCatalogPinProblem.HASH_MISMATCH -> R.string.catalog_pin_hash_mismatch
        PreviewCatalogPinProblem.INVALID_SNAPSHOT -> R.string.catalog_pin_invalid
        PreviewCatalogPinProblem.UNSUPPORTED_SCHEMA -> R.string.catalog_pin_unsupported
        PreviewCatalogPinProblem.PREVIEW_LIMIT -> R.string.catalog_pin_limit
        PreviewCatalogPinProblem.EXACT_VERSION_MISSING -> R.string.catalog_pin_version_missing
        PreviewCatalogPinProblem.DETAIL_UNAVAILABLE -> R.string.catalog_pin_detail_missing
        PreviewCatalogPinProblem.READ_BUSY -> R.string.catalog_pin_busy
        PreviewCatalogPinProblem.READ_FAILED -> R.string.catalog_pin_read_failed
        PreviewCatalogPinProblem.CACHE_CORRUPT -> R.string.catalog_pin_corrupt
        PreviewCatalogPinProblem.CANCELLED -> R.string.catalog_pin_cancelled
    }
}
