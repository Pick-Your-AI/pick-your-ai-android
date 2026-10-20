package ai.pickyour.android.preview

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.preview.*
import ai.pickyour.core.pricing.RecordedPricebook

/** Read-only transient comparison. It never receives an editor save token or worker. */
class CatalogComparisonSection(
    private val activity: Activity,
    saved: Bundle?,
    private val inspectPin: (PreviewCatalogPin, PreviewCatalogBrowser?, () -> Boolean) -> Unit,
    private val beforeInspect: () -> Unit = {},
    private val dismissPinInspection: () -> Unit,
) : LinearLayout(activity) {
    private var resetState = false
    private var tray = try {
        // The Bundle getter validates the outer ArrayList only; elements remain untrusted.
        val ids: List<*>? = saved?.getStringArrayList("comparisonIds")
        val versions: List<*>? = saved?.getStringArrayList("comparisonVersions")
        val hashes: List<*>? = saved?.getStringArrayList("comparisonHashes")
        val hasPins = saved != null && listOf("comparisonIds", "comparisonVersions", "comparisonHashes").any(saved::containsKey)
        if (!hasPins) PreviewCatalogTray()
        else {
            require(ids != null && versions != null && hashes != null)
            require(ids.size <= PreviewCatalogTray.LIMIT && versions.size == ids.size && hashes.size == ids.size)
            PreviewCatalogTray(ids.indices.map { PreviewCatalogPin(requireNotNull(ids.getOrNull(it) as? String),
                requireNotNull(versions.getOrNull(it) as? String), requireNotNull(hashes.getOrNull(it) as? String)) })
        }
    } catch (_: IllegalArgumentException) { resetState = true; PreviewCatalogTray() }
    private var differencesOnly = saved?.getBoolean("comparisonDifferencesOnly", false) ?: false
    private var browser: PreviewCatalogBrowser? = null
    private var pricebook: RecordedPricebook? = null
    private var closed = false
    private var comparisonBrowser: CatalogComparisonBrowser? = null
    private val status: TextView
    private val entries: LinearLayout
    private val compare: Button
    private var resultMessage: Int? = null

    init {
        orientation = VERTICAL
        caption(this, activity.getString(R.string.catalog_compare_heading), true)
        caption(this, activity.resources.getQuantityString(R.plurals.catalog_compare_limit, PreviewCatalogTray.LIMIT, PreviewCatalogTray.LIMIT))
        status = caption(this, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        entries = LinearLayout(activity).apply { orientation = VERTICAL }.also(::addView)
        compare = Button(activity).apply {
            setText(R.string.catalog_compare_open)
            setOnClickListener { openComparison() }
        }.also(::addView)
        render()
    }

    private fun active() = !closed && !activity.isDestroyed && !activity.isFinishing
    private fun literal(raw: String?) = CatalogDetailText.literal(activity, raw)
    private fun caption(parent: LinearLayout, value: String, heading: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = if (heading) 20f else 18f
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = heading
    }.also(parent::addView)

    fun show(value: PreviewCatalogBrowser?, prices: RecordedPricebook? = null) {
        if (!active()) return
        browser = value
        pricebook = if (value == null) null else prices
        render()
    }

    fun add(item: PreviewCatalogItem, hash: String) {
        if (!active()) return
        val update = browser?.let { tray.add(it, item, hash) }
        if (update != null) tray = update.tray
        resultMessage = when (update?.result) {
            PreviewCatalogTrayResult.ADDED -> R.string.catalog_compare_added
            PreviewCatalogTrayResult.ALREADY_PRESENT -> R.string.catalog_compare_already_present
            PreviewCatalogTrayResult.FULL -> R.string.catalog_compare_full
            PreviewCatalogTrayResult.IDENTITY_TOO_LARGE -> R.string.catalog_compare_identity_large
            PreviewCatalogTrayResult.UNAVAILABLE, null -> R.string.catalog_compare_unavailable
        }
        render()
    }

    private fun identity(index: Int, entry: PreviewCatalogTrayEntry): String = ComponentSelectionText.indexed(activity,
        R.string.catalog_compare_identity, index + 1,
        entry.detail?.item?.name?.let(::literal) ?: activity.getString(R.string.catalog_compare_missing_name),
        literal(entry.pin.id), literal(entry.pin.version),
    )

    private fun render() {
        if (!active()) return
        dismissPinInspection()
        dismissInspection()
        val current = tray.entries(browser)
        status.text = listOfNotNull(
            if (resetState) activity.getString(R.string.catalog_compare_reset) else null,
            resultMessage?.let(activity::getString),
            activity.getString(R.string.catalog_compare_count, current.size, PreviewCatalogTray.LIMIT),
        ).joinToString("\n")
        entries.removeAllViews()
        current.forEachIndexed { index, entry ->
            caption(entries, identity(index, entry), true)
            if (entry.detail == null) caption(entries, activity.getString(R.string.catalog_compare_unavailable))
            entries.addView(Button(activity).apply {
                text = activity.getString(R.string.catalog_pin_inspect, index + 1)
                setSingleLine(false)
                minHeight = kotlin.math.ceil(48 * resources.displayMetrics.density.toDouble()).toInt()
                minWidth = minHeight
                val originTray = tray
                val originBrowser = browser
                setOnClickListener { control ->
                    val isCurrent = { active() && control.parent === entries && tray === originTray && browser === originBrowser }
                    if (isCurrent()) {
                        dismissInspection()
                        inspectPin.invoke(entry.pin, originBrowser, isCurrent)
                    }
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            entries.addView(Button(activity).apply {
                text = ComponentSelectionText.indexed(activity, R.string.catalog_compare_remove, index + 1, literal(entry.pin.version))
                setOnClickListener { control ->
                    if (!active() || control.parent !== entries) return@setOnClickListener
                    tray = tray.remove(entry.pin)
                    resultMessage = R.string.catalog_compare_removed
                    render()
                }
            })
        }
        compare.isEnabled = current.size == PreviewCatalogTray.LIMIT && current.all { it.detail != null }
    }

    private fun openComparison() {
        if (!active()) return
        dismissPinInspection()
        dismissInspection()
        val current = tray.entries(browser)
        if (current.size != PreviewCatalogTray.LIMIT || current.any { it.detail == null }) return
        beforeInspect()
        val comparison = PreviewCatalogComparison(requireNotNull(current[0].detail), requireNotNull(current[1].detail))
        val originTray = tray
        val originBrowser = browser
        val originPricebook = pricebook
        lateinit var viewer: CatalogComparisonBrowser
        viewer = CatalogComparisonBrowser(activity, comparison, originPricebook, differencesOnly,
            isCurrent = {
                active() && comparisonBrowser === viewer && tray === originTray &&
                    browser === originBrowser && pricebook === originPricebook
            },
            onDifferencesChanged = { differencesOnly = it },
        )
        comparisonBrowser = viewer
        viewer.show()
    }

    fun save(out: Bundle) {
        out.putStringArrayList("comparisonIds", ArrayList(tray.pins.map { it.id }))
        out.putStringArrayList("comparisonVersions", ArrayList(tray.pins.map { it.version }))
        out.putStringArrayList("comparisonHashes", ArrayList(tray.pins.map { it.snapshotHash }))
        out.putBoolean("comparisonDifferencesOnly", differencesOnly)
    }

    fun dismissInspection() {
        val previous = comparisonBrowser
        comparisonBrowser = null
        previous?.retire()
    }

    fun close() { closed = true; dismissPinInspection(); dismissInspection() }

}
