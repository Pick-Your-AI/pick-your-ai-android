package ai.pickyour.android.preview

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import ai.pickyour.core.preview.PreviewCatalogBrowser
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
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogViewportTest {
    private val controllers = mutableListOf<ActivityController<Activity>>()
    private val hash = "a".repeat(64)
    private data class Harness(val viewport: CatalogViewport, val scroll: CatalogViewportScrollView,
        val rows: List<Pair<PreviewCatalogItem, View>>)

    @After fun close() { controllers.forEach { it.pause().stop().destroy() } }

    private fun harness(state: Bundle? = null, leading: Int = 600, snapshot: String = hash,
        ready: Boolean = true, version: String = "v1"): Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().also(controllers::add)
        val activity = controller.get()
        val viewport = CatalogViewport(state) { !activity.isDestroyed && !activity.isFinishing }
        val scroll = CatalogViewportScrollView(activity)
        scroll.onUserNavigation = viewport::cancel
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(View(activity), LinearLayout.LayoutParams(-1, leading))
        val rows = (0..5).map { index ->
            val row = View(activity)
            content.addView(row, LinearLayout.LayoutParams(-1, 80))
            PreviewCatalogItem("row-$index", "Synthetic row $index", "model", version, "T1") to row
        }
        content.addView(View(activity), LinearLayout.LayoutParams(-1, 900))
        scroll.addView(content)
        activity.setContentView(scroll)
        viewport.attach(scroll)
        viewport.ready(ready)
        viewport.render(snapshot, rows)
        return Harness(viewport, scroll, rows)
    }

    private fun layout(h: Harness, height: Int = 320) {
        h.scroll.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        h.scroll.layout(0, 0, 480, height)
        h.scroll.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun checkpoint(): Bundle {
        val source = harness()
        layout(source)
        source.scroll.scrollTo(0, 600 + 2 * 80 + 17)
        return Bundle().also(source.viewport::save).also {
            assertEquals("row-2", it.getString("catalogViewportId"))
            assertEquals(-17, it.getInt("catalogViewportOffset"))
        }
    }

    @Test fun sameSnapshotRestoresExactRowOffsetAfterUpstreamLayoutChangesOnlyOnce() {
        val restored = harness(checkpoint(), leading = 757)
        layout(restored)
        assertEquals(757 + 2 * 80 + 17, restored.scroll.scrollY)
        restored.scroll.scrollTo(0, 111)
        layout(restored)
        assertEquals(111, restored.scroll.scrollY)
    }

    @Test fun incompleteLoadingRetainsAnchorAcrossAnotherRecreation() {
        val waiting = harness(checkpoint(), ready = false)
        layout(waiting)
        assertEquals(0, waiting.scroll.scrollY)
        val next = Bundle().also(waiting.viewport::save)
        val restored = harness(next, leading = 650)
        layout(restored)
        assertEquals(650 + 2 * 80 + 17, restored.scroll.scrollY)
    }

    @Test fun changedSnapshotMissingRowWrongVersionAndUnknownFormatNeverRestore() {
        val original = checkpoint()
        val missing = Bundle(original).apply { putString("catalogViewportId", "removed-row") }
        val unsupported = Bundle(original).apply { putInt("catalogViewportFormat", 2) }
        val invalid = Bundle(original).apply { putString("catalogViewportHash", "not-a-digest") }
        for (candidate in listOf(harness(original, snapshot = "b".repeat(64)), harness(missing),
            harness(original, version = "v2"), harness(unsupported), harness(invalid))) {
            layout(candidate)
            assertEquals(0, candidate.scroll.scrollY)
        }
    }

    @Test fun newUserNavigationCancelsPendingRestoration() {
        val original = checkpoint()
        val accessibilityActions = listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
        for (kind in 0..7) {
            val restored = harness(original, ready = false)
            layout(restored)
            when (kind) {
                0 -> MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0).also {
                    restored.scroll.dispatchTouchEvent(it); it.recycle()
                }
                1 -> restored.scroll.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
                2 -> MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 10f, 10f, 0).also {
                    restored.scroll.onGenericMotionEvent(it); it.recycle()
                }
                3 -> restored.viewport.cancel()
                else -> restored.scroll.performAccessibilityAction(accessibilityActions[kind - 4], null)
            }
            restored.scroll.scrollTo(0, 111)
            restored.viewport.ready(true)
            layout(restored)
            assertEquals("Superseded navigation kind $kind", 111, restored.scroll.scrollY)
        }
    }

    @Test fun closedViewportAndSnapshotChangedBeforeDrawCannotMoveRetiredScroll() {
        val original = checkpoint()
        val retired = harness(original)
        retired.viewport.close()
        layout(retired)
        assertEquals(0, retired.scroll.scrollY)
        val replaced = harness(original)
        replaced.viewport.render("b".repeat(64), replaced.rows)
        layout(replaced)
        assertEquals(0, replaced.scroll.scrollY)
    }

    @Test fun observerTransferredDuringAttachmentIsRemovedAfterRestoration() {
        val state = checkpoint()
        val controller = Robolectric.buildActivity(Activity::class.java).setup().also(controllers::add)
        val activity = controller.get()
        var activeChecks = 0
        val viewport = CatalogViewport(state) { activeChecks++; !activity.isDestroyed }
        val scroll = CatalogViewportScrollView(activity)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(View(activity), LinearLayout.LayoutParams(-1, 760))
        val row = View(activity)
        content.addView(row, LinearLayout.LayoutParams(-1, 80))
        content.addView(View(activity), LinearLayout.LayoutParams(-1, 900))
        scroll.addView(content)
        val rows = listOf(PreviewCatalogItem("row-2", "Synthetic row 2", "model", "v1", "T1") to row)
        viewport.attach(scroll)
        viewport.ready(true)
        viewport.render(hash, rows)
        val floatingObserver = scroll.viewTreeObserver
        activity.setContentView(scroll)
        val attached = Harness(viewport, scroll, rows)
        layout(attached)
        assertFalse("Attachment must transfer the floating observer", floatingObserver.isAlive)
        assertNotSame(floatingObserver, scroll.viewTreeObserver)
        assertEquals(777, scroll.scrollY)
        val consumedChecks = activeChecks
        scroll.viewTreeObserver.dispatchOnPreDraw()
        assertEquals("Consumed callback must be removed from the live observer", consumedChecks, activeChecks)
    }

    @Test fun directAndAccessibilityDetailActionsCancelPendingRestoration() {
        val state = checkpoint()
        for (accessibility in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup().also(controllers::add)
            val activity = controller.get()
            val items = (0..5).map { PreviewCatalogItem("row-$it", "Synthetic row $it", "model", "v1", "T1") }
            var opened = 0
            val section = CatalogBrowseSection(activity, state) { item, snapshot ->
                assertEquals(items[2], item)
                assertEquals(hash, snapshot)
                opened++
            }
            val scroll = CatalogViewportScrollView(activity)
            scroll.onUserNavigation = section::cancelViewportRestore
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            content.addView(View(activity), LinearLayout.LayoutParams(-1, 600))
            content.addView(section)
            content.addView(View(activity), LinearLayout.LayoutParams(-1, 900))
            scroll.addView(content)
            activity.setContentView(scroll)
            section.attachViewport(scroll)
            section.show(PreviewCatalogBrowser(hash, items))
            section.selectionEnabled(false)
            scroll.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 480, 320)
            scroll.scrollTo(0, 111)
            section.selectionEnabled(true)
            val matches = arrayListOf<View>()
            section.findViewsWithText(matches, activity.getString(R.string.item_button, items[2].name, items[2].kind),
                View.FIND_VIEWS_WITH_TEXT)
            val row = matches.filterIsInstance<Button>().single()
            assertTrue(if (accessibility) row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                else row.performClick())
            assertEquals(1, opened)
            // Android may focus/scroll the row for ACTION_CLICK; retain that new navigation too.
            val userPosition = scroll.scrollY
            val rowLocation = IntArray(2)
            val viewportLocation = IntArray(2)
            row.getLocationInWindow(rowLocation)
            scroll.getLocationInWindow(viewportLocation)
            assertNotEquals("Fixture must distinguish the old anchor from current navigation", userPosition,
                userPosition + rowLocation[1] - viewportLocation[1] + 17)
            scroll.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("Detail action must keep the user's current viewport", userPosition, scroll.scrollY)
            section.close()
        }
    }

    @Test fun editorOnlyPositionDoesNotInventACatalogAnchor() {
        val source = harness()
        layout(source)
        val state = Bundle().also(source.viewport::save)
        assertFalse(state.containsKey("catalogViewportFormat"))
    }

    @Test fun changedGeometryAndExtremeOffsetsKeepTheExactRowVisibleWithoutOverflow() {
        val source = checkpoint()
        for ((offset, expected) in listOf(Int.MAX_VALUE to 99, Int.MIN_VALUE to -79)) {
            val state = Bundle(source).apply { putInt("catalogViewportOffset", offset) }
            val restored = harness(state)
            layout(restored, height = 100)
            assertEquals(600 + 2 * 80 - expected, restored.scroll.scrollY)
        }
    }
}
