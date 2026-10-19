package ai.pickyour.android.preview

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ai.pickyour.core.preview.PreviewCatalogItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogViewportPaddingTest {
    private val controllers = mutableListOf<ActivityController<Activity>>()
    private val hash = "a".repeat(64)
    private data class Harness(val viewport: CatalogViewport, val scroll: CatalogViewportScrollView,
        val rows: List<Pair<PreviewCatalogItem, View>>)

    @After fun close() { controllers.forEach { it.pause().stop().destroy() } }

    private fun harness(state: Bundle? = null, top: Int = 48, bottom: Int = 84,
        clip: Boolean = true): Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().also(controllers::add)
        val activity = controller.get()
        val viewport = CatalogViewport(state) { !activity.isDestroyed && !activity.isFinishing }
        val scroll = CatalogViewportScrollView(activity).apply {
            setPadding(0, top, 0, bottom)
            clipToPadding = clip
            onUserNavigation = viewport::cancel
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(View(activity), LinearLayout.LayoutParams(-1, 600))
        val rows = (0..5).map { index ->
            val row = View(activity)
            content.addView(row, LinearLayout.LayoutParams(-1, 80))
            PreviewCatalogItem("row-$index", "Synthetic row $index", "model", "v1", "T1") to row
        }
        content.addView(View(activity), LinearLayout.LayoutParams(-1, 900))
        scroll.addView(content)
        activity.setContentView(FrameLayout(activity).apply {
            addView(scroll, FrameLayout.LayoutParams(480, 320))
        })
        viewport.attach(scroll)
        viewport.ready(true)
        viewport.render(hash, rows)
        return Harness(viewport, scroll, rows)
    }

    private fun layout(h: Harness, height: Int = 320, hostTop: Int = 0, draw: Boolean = true) {
        h.scroll.layoutParams = (h.scroll.layoutParams as FrameLayout.LayoutParams).apply {
            this.height = height
            topMargin = hostTop
        }
        h.scroll.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        h.scroll.layout(0, hostTop, 480, hostTop + height)
        if (draw) {
            h.scroll.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Automatic window layout must preserve the fixture viewport height", height, h.scroll.height)
        }
    }

    private fun state(offset: Int) = Bundle().apply {
        putInt("catalogViewportFormat", 1)
        putString("catalogViewportHash", hash)
        putString("catalogViewportId", "row-2")
        putString("catalogViewportVersion", "v1")
        putInt("catalogViewportOffset", offset)
    }

    private fun offset(h: Harness, index: Int): Int {
        val row = IntArray(2)
        val host = IntArray(2)
        h.rows[index].second.getLocationInWindow(row)
        h.scroll.getLocationInWindow(host)
        return row[1] - host[1]
    }

    private fun position(h: Harness, index: Int, desiredOffset: Int) {
        h.scroll.scrollTo(0, h.scroll.scrollY + offset(h, index) - desiredOffset)
        assertEquals("Fixture must reach the requested outer-host offset", desiredOffset, offset(h, index))
    }

    private fun visible(h: Harness, index: Int): Rect {
        val rectangle = Rect()
        assertTrue("The exact selected row must intersect the framework's clipped visible rectangle",
            h.rows[index].second.getGlobalVisibleRect(rectangle))
        assertTrue("The clipped row must have actual positive width and height",
            rectangle.width() > 0 && rectangle.height() > 0)
        return rectangle
    }

    @Test fun captureSkipsRowsWhollyClippedByTopAndBottomPadding() {
        val h = harness()
        layout(h)
        position(h, 2, -79)
        assertFalse(h.rows[2].second.getGlobalVisibleRect(Rect()))
        visible(h, 3)
        val captured = Bundle().also(h.viewport::save)
        assertEquals("row-3", captured.getString("catalogViewportId"))
        assertEquals(1, captured.getInt("catalogViewportOffset"))

        position(h, 0, 236)
        assertFalse(h.rows[0].second.getGlobalVisibleRect(Rect()))
        val outside = Bundle().also(h.viewport::save)
        assertFalse("Rows wholly below the drawable interval supply no anchor",
            outside.containsKey("catalogViewportFormat"))
    }

    @Test fun extremeSavedOffsetsRestoreTheExactRowInsidePaddedBounds() {
        for ((saved, expected) in listOf(Int.MIN_VALUE to -31, Int.MAX_VALUE to 235)) {
            for (hostTop in listOf(0, 37)) {
                val h = harness(state(saved))
                layout(h, hostTop = hostTop)
                assertEquals(expected, offset(h, 2))
                assertEquals(1, visible(h, 2).height())
                h.scroll.scrollTo(0, 111)
                h.scroll.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Restoration is consumed only once", 111, h.scroll.scrollY)
            }
        }
    }

    @Test fun emptyDrawableIntervalKeepsPendingAnchorUntilUsableInsetsReturn() {
        for ((top, bottom) in listOf(160 to 160, 180 to 160)) {
            val h = harness(state(-17), top = top, bottom = bottom)
            layout(h)
            assertEquals("No drawable pixels means no restoration scroll", 0, h.scroll.scrollY)
            val retained = Bundle().also(h.viewport::save)
            assertEquals("row-2", retained.getString("catalogViewportId"))
            assertEquals(hash, retained.getString("catalogViewportHash"))
            assertEquals(-17, retained.getInt("catalogViewportOffset"))
            h.scroll.setPadding(0, 48, 0, 84)
            layout(h)
            assertEquals(-17, offset(h, 2))
            visible(h, 2)
            h.scroll.scrollTo(0, 111)
            layout(h)
            assertEquals("The recovered restoration still consumes exactly once", 111, h.scroll.scrollY)
        }
    }

    @Test fun unclippedPaddingPreservesTheOriginalWholeHostBounds() {
        val source = harness(clip = false)
        layout(source)
        position(source, 2, -79)
        visible(source, 2)
        val captured = Bundle().also(source.viewport::save)
        assertEquals("row-2", captured.getString("catalogViewportId"))
        assertEquals(-79, captured.getInt("catalogViewportOffset"))
        for ((saved, expected) in listOf(Int.MIN_VALUE to -79, Int.MAX_VALUE to 319)) {
            val h = harness(state(saved), clip = false)
            layout(h)
            assertEquals(expected, offset(h, 2))
            visible(h, 2)
        }
    }

    @Test fun oneDrawablePixelIsEnoughToRestoreWithoutDiscardingTheRow() {
        for ((saved, expected) in listOf(Int.MIN_VALUE to 81, Int.MAX_VALUE to 160)) {
            val h = harness(state(saved), top = 160, bottom = 159)
            layout(h)
            assertEquals(expected, offset(h, 2))
            assertEquals(1, visible(h, 2).height())
        }
    }

    @Test fun currentInsetsAtPredrawDetermineTheRestorationBounds() {
        val h = harness(state(Int.MAX_VALUE), top = 0, bottom = 0)
        layout(h, draw = false)
        h.scroll.setPadding(0, 48, 0, 84)
        layout(h)
        assertEquals(235, offset(h, 2))
        visible(h, 2)
    }

    @Test fun userNavigationCancelsTheAnchorWhileDrawableBoundsAreEmpty() {
        val h = harness(state(-17), top = 160, bottom = 160)
        layout(h)
        h.viewport.cancel()
        h.scroll.setPadding(0, 48, 0, 84)
        layout(h)
        assertEquals("Restored geometry must not revive cancelled intent", 0, h.scroll.scrollY)
        assertFalse(Bundle().also(h.viewport::save).containsKey("catalogViewportFormat"))
    }
}
