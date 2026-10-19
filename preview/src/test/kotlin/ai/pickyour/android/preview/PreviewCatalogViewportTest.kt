package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Real Activity/Bundle/layout simulation. This does not assert an installed-device result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w480dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewCatalogViewportTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && hasText(R.string.catalog_ready) }
    }
    @After fun close() { controller.pause().stop().destroy() }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun search(): EditText {
        val label = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.catalog_search) }
        return activity.findViewById(label.labelFor)
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Catalog Activity did not reach the required state")
    }
    private fun layout(): ScrollView = views().filterIsInstance<ScrollView>().single().also {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 480, 640)
        root.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun rowOffset(row: View, scroll: ScrollView): Int {
        val item = IntArray(2); val viewport = IntArray(2)
        row.getLocationInWindow(item); scroll.getLocationInWindow(viewport)
        return item[1] - viewport[1]
    }
    private fun baseline() = (PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()
        as PreviewDraftLoad.Available).record.baselineIdentity

    @Test fun catalogRowPositionSurvivesDetailsAndActivityRecreationWithoutDraftWrites() {
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) }
        val saved = baseline()
        search().setText("fictional")
        button(R.string.catalog_next).performClick()
        var scroll = layout()
        val row = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL") }[4]
        val rowText = row.text.toString()
        scroll.scrollTo(0, scroll.scrollY + rowOffset(row, scroll) + 19)
        assertEquals(-19, rowOffset(row, scroll))
        row.performClick()
        val detail = ShadowAlertDialog.getLatestAlertDialog()!!
        detail.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !detail.isShowing }
        assertEquals(-19, rowOffset(row, scroll))

        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { hasText(R.string.draft_saved) && hasText(R.string.catalog_ready) }
        scroll = layout()
        val restored = views().filterIsInstance<Button>().single { it.text.toString() == rowText }
        assertEquals("fictional", search().text.toString())
        assertEquals(-19, rowOffset(restored, scroll))
        assertEquals(1, state.getInt("catalogViewportFormat", 0))
        assertEquals(-19, state.getInt("catalogViewportOffset"))
        assertEquals(saved, baseline())
        assertFalse(button(R.string.save_draft).isEnabled)
    }
}
