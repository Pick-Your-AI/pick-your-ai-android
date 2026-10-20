package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.*
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowAlertDialog

/** Simulated Activity callbacks at the validated snapshot boundary, not transport or device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogSnapshotActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.catalog_next).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun samples() = views().filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL") }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
    private fun save() {
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
    }

    @Test fun snapshotReplacementRetiresRowsAndDetailConfirmationWithoutChangingSavedDraft() {
        save()
        val baseline = loaded().baselineIdentity
        val searchLabel = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.catalog_search) }
        activity.findViewById<EditText>(searchLabel.labelFor).setText("FICTIONAL")
        button(R.string.catalog_next).performClick()
        val catalog = views().filterIsInstance<CatalogBrowseSection>().single()
        val before = Bundle().also(catalog::saveState)
        assertEquals(1, before.getInt("catalogPage"))
        val retiredRow = samples().first()
        retiredRow.performClick()
        val retainedDetail = dialog()

        // Accept a different valid snapshot with the same item identities and versions while a dialog is open.
        val document = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        document.put("revision", document.getInt("revision") + 1)
        val replacement = requireNotNull(PreviewCatalogBrowser.read(document.toString().toByteArray(Charsets.UTF_8)))
        assertNotEquals(before.getString("catalogSnapshot"), replacement.hash)
        catalog.show(replacement)
        val after = Bundle().also(catalog::saveState)
        assertEquals("FICTIONAL", after.getString("catalogQuery"))
        assertEquals(0, after.getInt("catalogPage"))
        assertEquals(replacement.hash, after.getString("catalogSnapshot"))
        assertTrue(views().filterIsInstance<TextView>().any { it.text.contains(activity.getString(R.string.catalog_snapshot_changed)) })

        retiredRow.performClick()
        assertSame("A row from the previous snapshot cannot open another dialog", retainedDetail, dialog())
        retainedDetail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !retainedDetail.isShowing }
        assertTrue(hasText(R.string.no_selection))
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(loaded().fields.selectedId)

        // A current row still permits an explicit unsaved selection and pins only the replacement on Save.
        samples().first().performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { button(R.string.save_draft).isEnabled }
        assertEquals(baseline, loaded().baselineIdentity)
        assertNull(loaded().fields.selectedId)
        save()
        assertEquals(replacement.items.first().id, loaded().fields.selectedId)
        assertEquals(replacement.items.first().version, loaded().fields.selectedVersion)
        assertEquals(replacement.hash, loaded().fields.catalogHash)
    }
}
