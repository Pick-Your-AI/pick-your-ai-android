package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewCatalogKinds
import ai.pickyour.core.preview.PreviewCatalogSort
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
import org.robolectric.shadows.ShadowAlertDialog

/** Actual preview controls in JVM simulation; no installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogRowAuthorityActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private var pendingSince = 0L

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && rows().isNotEmpty() }
        field(R.string.draft_title).setText("acknowledged catalog row baseline")
        button(R.string.save_draft).performClick()
        await {
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } &&
                !button(R.string.save_draft).isEnabled
        }
        // These read-only inspections explicitly precede the automatic save deadline.
        AutosaveUiClock.pause()
        pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending objective remains unsaved")
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { rows().size == 1 }
        rows().single().performClick()
        val detail = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        views(detail.window!!.decorView).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.catalog_compare_add)
        }.performClick()
        await { !detail.isShowing }
        assertEquals(listOf("cat.model.fixture"), activityState().getStringArrayList("comparisonIds"))
        field(R.string.catalog_search).setText("")
        await { button(R.string.catalog_next).isEnabled }
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun currentRowOpensExactDetailsWithoutSavingPendingFields() {
        val before = (loaded() as PreviewDraftLoad.Available).record.baselineIdentity
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { rows().size == 1 }
        val row = rows().single()
        row.performClick()
        val detail = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(detail.isShowing)
        assertTrue(shadowOf(detail).message.toString().contains("cat.model.fixture"))
        assertEquals(before, (loaded() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals("pending objective remains unsaved", field(R.string.context_objective).text.toString())
        detail.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !detail.isShowing }
        assertFalse(detail.isShowing)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun sameSnapshotSearchCannotReviveRemovedRowCallbacks() {
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { rows().size == 1 }
        val retiredRow = rows().single()
        val before = (loaded() as PreviewDraftLoad.Available).record.baselineIdentity
        val snapshot = browseState().getString("catalogSnapshot")
        field(R.string.catalog_search).setText("no fixture can match this query")
        await { rows().isEmpty() }
        assertNull("The actual search removed this result view", retiredRow.parent)
        assertEquals(snapshot, browseState().getString("catalogSnapshot"))
        assertRetiredRowHasNoEffect(retiredRow)
        assertEquals(before, (loaded() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals("pending objective remains unsaved", field(R.string.context_objective).text.toString())
        assertEquals("no fixture can match this query", field(R.string.catalog_search).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun returningToQueryCannotReviveOldRowOrRetireCurrentDetail() {
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { rows().size == 1 }
        val retiredRow = rows().single()
        val snapshot = browseState().getString("catalogSnapshot")
        field(R.string.catalog_search).setText("no fixture can match this query")
        await { rows().isEmpty() }
        field(R.string.catalog_search).setText("cat.model.fixture")
        await { rows().size == 1 }
        val currentRow = rows().single()
        assertNotSame(retiredRow, currentRow)
        assertEquals(snapshot, browseState().getString("catalogSnapshot"))
        currentRow.performClick()
        val currentDetail = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(currentDetail.isShowing)
        assertTrue(shadowOf(currentDetail).message.toString().contains("cat.model.fixture"))

        assertRetiredRowHasNoEffect(retiredRow)

        assertSame(currentDetail, ShadowAlertDialog.getLatestAlertDialog())
        assertTrue("Retired rows cannot dismiss the current inspection", currentDetail.isShowing)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun pagingFacetsAndSortRetirePreviousResultControls() {
        fun choose(prefix: String, index: Int) {
            views().filterIsInstance<Button>().single { it.text.toString().startsWith(prefix) }.performClick()
            val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            shadowOf(picker).clickOnItem(index)
            await { !picker.isShowing }
        }
        val modelIndex = PreviewCatalogKinds.all.indexOfFirst { it.id == "model" } + 1
        val transitions = listOf<Pair<() -> Unit, () -> Unit>>(
            { button(R.string.catalog_next).performClick(); Unit } to
                { button(R.string.catalog_previous).performClick(); Unit },
            { choose("Tier:", 1) } to { choose("Tier:", 0) },
            { choose("Kind:", modelIndex) } to { choose("Kind:", 0) },
            { choose("Sort:", PreviewCatalogSort.NAME.ordinal) } to
                { choose("Sort:", PreviewCatalogSort.FIXTURE.ordinal) },
        )
        for ((change, restore) in transitions) {
            val retiredRow = rows().first()
            val snapshot = browseState().getString("catalogSnapshot")
            change()
            assertNull("The actual control replaced this result view", retiredRow.parent)
            assertEquals(snapshot, browseState().getString("catalogSnapshot"))
            assertRetiredRowHasNoEffect(retiredRow)
            restore()
        }
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    private fun assertRetiredRowHasNoEffect(row: Button) {
        val durable = (loaded() as PreviewDraftLoad.Available).record.baselineIdentity
        val before = activityState()
        val previousDialog = ShadowAlertDialog.getLatestAlertDialog()
        val showing = previousDialog?.isShowing
        row.performClick()
        AutosaveUiClock.idle()
        assertSame("A retired same-snapshot result cannot open a new detail", previousDialog,
            ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("Existing inspection visibility stays unchanged", showing, previousDialog?.isShowing)
        assertEquals(durable, (loaded() as PreviewDraftLoad.Available).record.baselineIdentity)
        val after = activityState()
        val strings = listOf("draftId", "draftBaseline", "title", "objective", "contextInput", "selectedId",
            "selectedVersion", "selectedCatalogHash", "pendingDeleteId", "catalogQuery", "catalogTier",
            "catalogKind", "catalogSort", "catalogSnapshot", "workload.classification") +
            (0..7).map { "workload.$it" }
        for (key in strings) assertEquals(key, before.getString(key), after.getString(key))
        for (key in listOf("entryBlocked", "unsupportedEntry", "selectionResolved", "draftRequiresReopen",
                "contextInputsPresent", "workloadInputsPresent", "workload.rejectedEdit", "comparisonDifferencesOnly")) {
            assertEquals(key, before.getBoolean(key), after.getBoolean(key))
        }
        for (key in listOf("comparisonIds", "comparisonVersions", "comparisonHashes")) {
            assertEquals(key, before.getStringArrayList(key), after.getStringArrayList(key))
        }
        assertEquals(before.getInt("catalogPage"), after.getInt("catalogPage"))
        assertTrue(button(R.string.save_draft).isEnabled)
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    private fun button(label: Int): Button = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }

    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }

    private fun rows() = views().filterIsInstance<CatalogBrowseSection>().single()
        .let(::views).filterIsInstance<Button>().filter { it.text.toString().startsWith("Open FICTIONAL ") }

    private fun browseState() = Bundle().also { state ->
        views().filterIsInstance<CatalogBrowseSection>().single().saveState(state)
    }

    private fun activityState() = Bundle().also { controller.saveInstanceState(it) }

    private fun loaded() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            AutosaveUiClock.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the current preview state")
    }
}
