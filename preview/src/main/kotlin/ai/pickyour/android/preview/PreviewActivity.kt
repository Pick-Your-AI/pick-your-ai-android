package ai.pickyour.android.preview

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import java.util.concurrent.Executors

/** Disposable fixture diagnostics; no draft, account, server, or product workflow. */
class PreviewActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val notice = TextView(this).apply { setText(R.string.notice); textSize = 20f }
        val status = TextView(this).apply {
            setText(R.string.ready)
            textSize = 18f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val run = Button(this).apply { setText(R.string.run) }
        run.setOnClickListener {
            run.isEnabled = false
            status.setText(R.string.working)
            worker.execute {
                val message = try {
                    assets.open("build.json").use {
                        when (FrozenSchemaValidator().validate(FrozenDocument.BUILD, it)) {
                            FrozenValidation.Valid -> R.string.valid
                            else -> R.string.invalid
                        }
                    }
                } catch (_: Exception) {
                    R.string.unavailable
                }
                runOnUiThread {
                    if (!destroyed) {
                        status.setText(message)
                        run.isEnabled = true
                    }
                }
            }
        }
        content.addView(notice)
        content.addView(run)
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
        // Keep controls clear of system bars under Android 15 edge-to-edge enforcement.
        content.setOnApplyWindowInsetsListener { view, insets ->
            val padding = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            insets
        }
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        super.onDestroy()
    }
}
