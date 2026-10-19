package ai.pickyour.android.preview

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import ai.pickyour.core.preview.PreviewCatalogItem

/** A transient visible row, bound to one exact snapshot. This carries no draft authority. */
internal class CatalogViewport(savedState: Bundle?, private val active: () -> Boolean) {
    private data class Anchor(val hash: String, val id: String, val version: String, val offset: Int)
    private var pending = savedState?.takeIf { it.getInt("catalogViewportFormat", 0) == 1 }?.let { state ->
        val hash = state.getString("catalogViewportHash")
        val id = state.getString("catalogViewportId")
        val version = state.getString("catalogViewportVersion")
        if (hash?.matches(Regex("[a-f0-9]{64}")) == true && id != null && version != null &&
            state.containsKey("catalogViewportOffset")) Anchor(hash, id, version, state.getInt("catalogViewportOffset"))
        else null
    }
    private var host: ScrollView? = null
    private var snapshot: String? = null
    private var rows: List<Pair<PreviewCatalogItem, View>> = emptyList()
    private var ready = false
    private var observer: ViewTreeObserver? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null

    fun attach(view: ScrollView) { detachListener(); host = view; schedule() }
    fun ready(value: Boolean) { ready = value; schedule() }

    fun render(hash: String?, visibleRows: List<Pair<PreviewCatalogItem, View>>) {
        if (hash != null && pending?.let { it.hash != hash } == true) cancel()
        snapshot = hash
        rows = visibleRows.toList()
        schedule()
    }

    /** Any new user navigation supersedes an old restoration before it can scroll. */
    fun cancel() { pending = null; detachListener() }
    fun close() { cancel(); host = null; rows = emptyList() }

    fun save(out: Bundle) {
        for (key in listOf("Format", "Hash", "Id", "Version", "Offset")) out.remove("catalogViewport$key")
        // A second recreation before loading must retain the original restoration intent.
        val anchor = pending ?: capture() ?: return
        out.putInt("catalogViewportFormat", 1)
        out.putString("catalogViewportHash", anchor.hash)
        out.putString("catalogViewportId", anchor.id)
        out.putString("catalogViewportVersion", anchor.version)
        out.putInt("catalogViewportOffset", anchor.offset)
    }

    private fun capture(): Anchor? {
        val scroll = host ?: return null
        val hash = snapshot ?: return null
        if (!active()) return null
        val visible = drawableBounds(scroll) ?: return null
        for ((item, view) in rows) {
            val top = offset(view, scroll)
            if (view.height > 0 && top < visible.bottom && top + view.height > visible.top)
                return Anchor(hash, item.id, item.version, top.toInt())
        }
        return null // The user is outside catalog rows; do not jump them into the catalog.
    }

    private fun schedule() {
        if (!active() || !ready || listener != null) return
        val anchor = pending ?: return
        val scroll = host ?: return
        val hash = snapshot ?: return
        if (hash != anchor.hash || rows.none { it.first.id == anchor.id && it.first.version == anchor.version }) {
            cancel()
            return
        }
        val callback = ViewTreeObserver.OnPreDrawListener {
            val current = pending
            if (!active() || current == null || snapshot != current.hash) {
                cancel()
            } else if (ready) {
                val target = rows.singleOrNull { it.first.id == current.id && it.first.version == current.version }?.second
                if (target == null) cancel()
                else {
                    val visible = drawableBounds(scroll)
                    if (visible != null && target.height > 0) {
                        // Keep format-1 offsets relative to the outer host, including its current padding.
                        // An empty drawable interval defers this intent until a later usable layout.
                        val restoredOffset = current.offset.toLong().coerceIn(
                            visible.top - target.height + 1, visible.bottom - 1)
                        val destination = scroll.scrollY.toLong() + offset(target, scroll) - restoredOffset
                        cancel() // Consume once before scrolling; later layouts must respect new user movement.
                        scroll.scrollTo(scroll.scrollX, destination.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    }
                }
            }
            true
        }
        observer = scroll.viewTreeObserver
        listener = callback
        observer?.addOnPreDrawListener(callback)
        scroll.invalidate()
    }

    private fun detachListener() {
        val callback = listener
        // Window attachment can merge the floating observer into a live tree and retire the original.
        val liveObserver = observer?.takeIf { it.isAlive } ?: host?.viewTreeObserver?.takeIf { it.isAlive }
        if (callback != null) liveObserver?.removeOnPreDrawListener(callback)
        listener = null
        observer = null
    }

    private data class DrawableBounds(val top: Long, val bottom: Long)

    private fun drawableBounds(scroll: ScrollView): DrawableBounds? {
        val height = scroll.height.toLong()
        if (height <= 0) return null
        val top = if (scroll.clipToPadding) scroll.paddingTop.toLong().coerceIn(0L, height) else 0L
        val bottom = if (scroll.clipToPadding) (height - scroll.paddingBottom).coerceIn(0L, height) else height
        return if (top < bottom) DrawableBounds(top, bottom) else null
    }

    private fun offset(view: View, scroll: ScrollView): Long {
        val row = IntArray(2)
        val viewport = IntArray(2)
        view.getLocationInWindow(row)
        scroll.getLocationInWindow(viewport)
        return row[1].toLong() - viewport[1].toLong()
    }
}

/** User navigation takes precedence over delayed restoration, including accessibility scrolling. */
internal class CatalogViewportScrollView(context: Context) : ScrollView(context) {
    var onUserNavigation: () -> Unit = {}
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserNavigation()
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) onUserNavigation()
        return super.dispatchKeyEvent(event)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) onUserNavigation()
        return super.onGenericMotionEvent(event)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
            action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id ||
            action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id) onUserNavigation()
        return super.performAccessibilityAction(action, arguments)
    }
}
