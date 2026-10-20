package ai.pickyour.android.preview

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Native inset dispatch and View geometry in simulation, not an installed window matrix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewWindowInsetsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun scrolledFormStaysInsideAllFourSystemWindowInsets() {
        val viewport = viewport()
        val content = viewport.getChildAt(0)
        // Isolate the actual Activity scroll tree from the simulated DecorView's fixed display.
        // Its real inset listener, child layout, scrolling and clipping still execute unchanged.
        (viewport.parent as ViewGroup).removeView(viewport)
        viewport.dispatchApplyWindowInsets(windowInsets(Rect(11, 29, 17, 43)))
        measureAndLayout(viewport, 720, 1280)
        assertTrue("The real form must exceed its viewport", content.height > viewport.height * 2)

        for (offset in listOf(0, content.height / 2, content.height)) {
            viewport.scrollTo(0, offset)
            val visible = Rect()
            assertTrue(content.getGlobalVisibleRect(visible))
            assertEquals("System UI must clip the viewport at scroll offset $offset",
                Rect(11, 29, 703, 1237), visible)
        }
        assertTrue("The clipping assertion must include actual scrolling", viewport.scrollY > 0)
    }

    @Test fun changingInsetsAndWindowSizePreservesPendingInputFocusAndSavedIdentity() {
        val title = field(R.string.draft_title)
        val objective = field(R.string.context_objective)
        val context = field(R.string.context_context)
        title.setText("Viewport acknowledged baseline")
        button(R.string.save_draft).performClick()
        await {
            !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.draft_saved)
            }
        }
        val baseline = (loaded() as PreviewDraftLoad.Available).record.baselineIdentity
        title.setText("Viewport pending title")
        objective.setText("Exact pending objective\nsecond line")
        context.setText("Exact pending context")
        assertTrue(title.requestFocus())
        title.setSelection(2, 9)

        val viewport = viewport()
        val content = viewport.getChildAt(0)
        val initialPadding = Rect(content.paddingLeft, content.paddingTop,
            content.paddingRight, content.paddingBottom)
        val configurations = listOf(
            Triple(720, 1280, Rect(11, 29, 17, 43)),
            Triple(720, 1280, Rect(0, 29, 0, 340)), // An occluding keyboard-height inset.
            Triple(1280, 720, Rect(0, 29, 80, 0)), // A landscape navigation-bar inset.
            Triple(720, 1280, Rect(0, 0, 0, 0)),
        )
        for ((width, height, insets) in configurations) {
            viewport.dispatchApplyWindowInsets(windowInsets(insets))
            viewport.dispatchApplyWindowInsets(windowInsets(insets))
            measureAndLayout(activity.window.decorView, width, height)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("Inset updates replace the protected viewport; they do not accumulate",
                insets, Rect(viewport.paddingLeft, viewport.paddingTop,
                    viewport.paddingRight, viewport.paddingBottom))
            assertEquals("Design spacing belongs to the form and stays independent of window insets",
                initialPadding, Rect(content.paddingLeft, content.paddingTop,
                    content.paddingRight, content.paddingBottom))
            assertEquals("Viewport pending title", title.text.toString())
            assertEquals("Exact pending objective\nsecond line", objective.text.toString())
            assertEquals("Exact pending context", context.text.toString())
            assertTrue(title.hasFocus())
            assertEquals(2, title.selectionStart)
            assertEquals(9, title.selectionEnd)
            assertEquals(baseline, (loaded() as PreviewDraftLoad.Available).record.baselineIdentity)
            assertTrue(button(R.string.save_draft).isEnabled)
        }
    }

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    // The pinned API28 runtime has this constructor; compileSdk35 hides it from app source.
    // Reflection is confined to constructing this test fixture, before public inset dispatch.
    private fun windowInsets(insets: Rect): WindowInsets =
        WindowInsets::class.java.getConstructor(Rect::class.java).newInstance(insets)

    private fun viewport() = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    private fun button(label: Int) = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }

    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }

    private fun loaded() = readDraftAfterOwnedWork(activity) {
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the current preview state")
    }
}
