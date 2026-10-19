package ai.pickyour.android.preview

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.*

/** Local fixture browse state is independent of the editor's durable save authority. */
class CatalogBrowseSection(
    private val activity: Activity,
    savedState: Bundle?,
    private val openDetails: (PreviewCatalogItem, String) -> Unit,
) : LinearLayout(activity) {
    private var resetState = false
    private var state = try {
        PreviewCatalogBrowseState(
            savedState?.getString("catalogQuery").orEmpty(), savedState?.getString("catalogTier"),
            savedState?.getString("catalogKind"), savedState?.getString("catalogSort")?.let(PreviewCatalogSort::valueOf)
                ?: PreviewCatalogSort.FIXTURE, savedState?.getInt("catalogPage", 0) ?: 0,
        )
    } catch (_: IllegalArgumentException) { resetState = true; PreviewCatalogBrowseState() }
    private var lastSnapshotHash = savedState?.getString("catalogSnapshot") ?: savedState?.getString("searchCatalogHash")
    private var snapshotChanged = false
    private var browser: PreviewCatalogBrowser? = null
    private var selectable = false
    private var closed = false
    private var rowAuthority = Any()
    private val viewport = CatalogViewport(savedState?.takeUnless { resetState }) { active() }
    private val tier: Button
    private val kind: Button
    private val sort: Button
    private val status: TextView
    private val results: LinearLayout
    private val previous: Button
    private val next: Button

    init {
        orientation = VERTICAL
        tier = Button(activity).apply { setOnClickListener { chooseTier() } }.also(::addView)
        kind = Button(activity).apply { setOnClickListener { chooseKind() } }.also(::addView)
        sort = Button(activity).apply { setOnClickListener { chooseSort() } }.also(::addView)
        status = TextView(activity).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(::addView)
        results = LinearLayout(activity).apply { orientation = VERTICAL }.also(::addView)
        previous = Button(activity).apply {
            setText(R.string.catalog_previous)
            setOnClickListener { if (active() && state.page > 0) {
                viewport.cancel(); state = state.copy(page = state.page - 1); render()
            } }
        }.also(::addView)
        next = Button(activity).apply {
            setText(R.string.catalog_next)
            setOnClickListener {
                if (!active()) return@setOnClickListener
                val page = browser?.browse(state)
                if (page != null && page.page + 1 < page.pages) {
                    viewport.cancel(); state = state.copy(page = page.page + 1); render()
                }
            }
        }.also(::addView)
        render()
    }

    private fun active() = !closed && !activity.isDestroyed && !activity.isFinishing

    /** Destruction is terminal even if an editor or dialog retains one of this instance's callbacks. */
    fun close() { closed = true; selectable = false; viewport.close() }

    fun attachViewport(scroll: ScrollView) { if (active()) viewport.attach(scroll) }
    fun cancelViewportRestore() { viewport.cancel() }

    fun show(value: PreviewCatalogBrowser) {
        if (!active()) return
        val previousHash = lastSnapshotHash
        snapshotChanged = previousHash != null && previousHash != value.hash
        if (snapshotChanged) state = state.copy(page = 0)
        lastSnapshotHash = value.hash
        browser = value
        render()
    }

    fun unavailable() { if (active()) { browser = null; render() } }

    fun search(query: String) {
        if (!active() || query == state.query) return
        viewport.cancel()
        state = state.copy(query = query, page = 0)
        render()
    }

    fun selectionEnabled(value: Boolean) {
        if (!active()) return
        selectable = value
        for (index in 0 until results.childCount) results.getChildAt(index).isEnabled = value
        viewport.ready(value)
    }

    fun contains(item: PreviewCatalogItem, hash: String) = active() && browser?.contains(item, hash) == true

    fun details(item: PreviewCatalogItem, hash: String): PreviewCatalogDetails? =
        if (active()) browser?.details(item, hash) else null

    fun selectedName(id: String?, version: String?, hash: String?): String? = browser?.takeIf { it.hash == hash }
        ?.items?.singleOrNull { it.id == id && it.version == version }?.name

    fun saveState(out: Bundle) {
        viewport.save(out)
        out.putString("catalogQuery", state.query)
        out.putString("catalogTier", state.tier)
        out.putString("catalogKind", state.kind)
        out.putString("catalogSort", state.sort.name)
        out.putInt("catalogPage", state.page)
        out.putString("catalogSnapshot", lastSnapshotHash)
    }

    private fun chooseTier() {
        if (!active()) return
        val values = listOf<String?>(null) + PreviewCatalogKinds.tiers
        showPreviewSingleChoiceDialog(activity, R.string.catalog_tier_title,
            values.map { it ?: activity.getString(R.string.catalog_all_tiers) },
            values.indexOf(state.tier), ::active) { index ->
            viewport.cancel()
            state = state.copy(tier = values[index], page = 0)
            render()
        }
    }

    private fun chooseKind() {
        if (!active()) return
        // All frozen kinds remain reachable, including those with no row in this snapshot/tier.
        val values = listOf<PreviewCatalogKind?>(null) + PreviewCatalogKinds.all
        showPreviewSingleChoiceDialog(activity, R.string.catalog_kind_title,
            values.map { it?.let { entry -> "${entry.tier} · ${entry.id}" }
                ?: activity.getString(R.string.catalog_all_kinds) },
            values.indexOfFirst { it?.id == state.kind }, ::active) { index ->
            viewport.cancel()
            state = state.copy(kind = values[index]?.id, page = 0)
            render()
        }
    }

    private fun chooseSort() {
        if (!active()) return
        val values = PreviewCatalogSort.entries
        showPreviewSingleChoiceDialog(activity, R.string.catalog_sort_title,
            values.map(::sortName), values.indexOf(state.sort), ::active) { index ->
            viewport.cancel()
            state = state.copy(sort = values[index], page = 0)
            render()
        }
    }

    private fun sortName(value: PreviewCatalogSort) = activity.getString(when (value) {
        PreviewCatalogSort.FIXTURE -> R.string.catalog_sort_fixture
        PreviewCatalogSort.NAME -> R.string.catalog_sort_name
        PreviewCatalogSort.KIND -> R.string.catalog_sort_kind
    })

    private fun render() {
        if (!active()) return
        val renderedRows = Any().also { rowAuthority = it }
        tier.text = activity.getString(R.string.catalog_tier, state.tier ?: activity.getString(R.string.catalog_all_tiers))
        kind.text = activity.getString(R.string.catalog_kind, state.kind ?: activity.getString(R.string.catalog_all_kinds))
        sort.text = activity.getString(R.string.catalog_sort, sortName(state.sort))
        val current = browser
        val page = current?.browse(state)
        if (page != null && page.page != state.page) state = state.copy(page = page.page)
        results.removeAllViews()
        val rows = mutableListOf<Pair<PreviewCatalogItem, View>>()
        if (current != null && page != null) page.items.forEach { item ->
            val row = Button(activity).apply {
                text = activity.getString(R.string.item_button, item.name, item.kind)
                isEnabled = selectable
                setOnClickListener {
                    if (active() && selectable && browser === current && rowAuthority === renderedRows) {
                        viewport.cancel()
                        openDetails(item, current.hash)
                    }
                }
            }
            results.addView(row)
            rows.add(item to row)
        }
        val message = when {
            page == null -> activity.getString(R.string.catalog_browse_waiting)
            page.total == 0 -> activity.getString(if (state.kind == null && state.tier == null)
                R.string.catalog_search_empty else R.string.catalog_no_matches)
            else -> activity.getString(R.string.catalog_page, page.page + 1, page.pages, page.total)
        }
        status.text = listOfNotNull(
            if (snapshotChanged) activity.getString(R.string.catalog_snapshot_changed) else null,
            if (resetState) activity.getString(R.string.catalog_state_reset) else null,
            message,
        ).joinToString("\n")
        previous.isEnabled = page != null && page.page > 0
        next.isEnabled = page != null && page.page + 1 < page.pages
        viewport.render(current?.hash, rows)
    }
}
