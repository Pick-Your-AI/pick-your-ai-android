package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import ai.pickyour.core.drafts.SelectionCatalogSnapshot
import ai.pickyour.core.drafts.SelectionCommand
import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.preview.PreviewSelectionSave
import ai.pickyour.core.preview.PreviewSelectionForms
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.preview.PreviewDraftId
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewStoredDraft
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual search controls and durable transactions in JVM simulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogSelectionHandoffActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }

    private fun button(label: Int) = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }

    private fun field(root: View, label: Int): EditText {
        val caption = views(root).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(label)
        }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return root.findViewById(caption.labelFor)
    }

    private fun field(label: Int) = field(activity.window.decorView, label)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = AutosaveUiClock.idle()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit search-selection state")
    }

    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService

    private fun finishWork() {
        repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() }
    }

    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId

    private fun loaded() = (readDraftAfterOwnedWork(activity) {
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
    } as PreviewDraftLoad.Available).record

    private fun emptyDraft(): PreviewStoredDraft {
        val previous = currentId()
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { currentId() != previous && button(R.string.component_choose_catalog).isEnabled }
        finishWork()
        return loaded().also { assertTrue(it.components.selections.isEmpty()) }
    }

    private fun browseState() = Bundle().also {
        views().filterIsInstance<CatalogBrowseSection>().single().saveState(it)
    }

    private fun details(id: String): AlertDialog {
        field(R.string.catalog_search).setText(id)
        val rows = {
            views().filterIsInstance<CatalogBrowseSection>().single()
                .let(::views).filterIsInstance<Button>()
                .filter { it.text.toString().startsWith("Open FICTIONAL ") }
        }
        await { rows().size == 1 }
        rows().single().performClick()
        return dialog().also { assertTrue(it.isShowing) }
    }

    private fun useInDraft(detail: AlertDialog) =
        views(detail.window!!.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == "Use in draft" }

    private fun chooseAdd(picker: AlertDialog): AlertDialog {
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString() == activity.getString(R.string.component_add)
        }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }

    @Test fun searchResultAddsExactlyOnePinnedSelectionToTheCapturedDraftAndRole() {
        val before = emptyDraft()
        val id = currentId()
        val catalog = activity.assets.open("catalog.json").use {
            JSONObject(it.readBytes().toString(Charsets.UTF_8))
        }.getJSONArray("items")
        val expected = (0 until catalog.length()).map { catalog.getJSONObject(it) }
            .single { it.getString("id") == "cat.model.fixture" }
        val detail = details(expected.getString("id"))
        val context = browseState()
        val action = useInDraft(detail)
        assertTrue(action.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)

        action.performClick()
        val picker = dialog()
        assertFalse(detail.isShowing)
        assertNotSame(detail, picker)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val form = chooseAdd(picker)
        field(form.window!!.decorView, R.string.component_role).setText("primary")
        field(form.window!!.decorView, R.string.component_settings).setText("{}")
        val save = form.getButton(AlertDialog.BUTTON_POSITIVE)
        repeat(3) { save.performClick() }
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()

        val saved = loaded()
        val selection = saved.components.selections.single()
        assertEquals(id, currentId())
        assertEquals(expected.getString("id"), selection.catalogItemId)
        assertEquals(expected.getString("version"), selection.catalogItemVersion)
        assertEquals("model", selection.kind)
        assertEquals("primary", selection.role)
        assertFalse(selection.enabled)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.contextInput, saved.contextInput)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.components.links, saved.components.links)
        assertEquals(context.getString("catalogQuery"), browseState().getString("catalogQuery"))
        assertEquals(context.getString("catalogSnapshot"), browseState().getString("catalogSnapshot"))
        assertEquals(context.getInt("catalogPage"), browseState().getInt("catalogPage"))

        action.performClick()
        save.performClick()
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(listOf(selection), loaded().components.selections)
    }

    @Test fun recreationRetiresTheSearchSelectionOriginWithoutChangingTheSavedDraft() {
        val before = emptyDraft()
        val id = currentId()
        val detail = details("cat.model.fixture")
        val retained = useInDraft(detail)
        controller.recreate()
        await { button(R.string.component_choose_catalog).isEnabled && button(R.string.retry_catalog).isEnabled }
        finishWork()
        val context = browseState()

        retained.performClick()
        finishWork()

        assertFalse(detail.isShowing)
        assertEquals(id, currentId())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertEquals(context.getString("catalogQuery"), browseState().getString("catalogQuery"))
        assertEquals("cat.model.fixture", field(R.string.catalog_search).text.toString())
    }

    private fun document() = activity.assets.open("catalog.json").use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8))
    }

    /** Feed exact validated fixture bytes to both existing catalog consumers; no network or cache writer. */
    private fun showCatalog(root: JSONObject) {
        finishWork()
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        val browser = requireNotNull(PreviewCatalogBrowser.read(bytes))
        val snapshot = SelectionCatalogSnapshot.fromJson(bytes)
        assertEquals(browser.hash, snapshot.hash.hex)
        PreviewActivity::class.java.getDeclaredField("componentCatalog")
            .apply { isAccessible = true }.set(activity, snapshot)
        views().filterIsInstance<CatalogBrowseSection>().single().show(browser)
        views().filterIsInstance<SelectionEditSection>().single().catalog(browser.items, browser.hash)
    }

    private fun nextModelVersion() = document().also { root ->
        root.put("revision", root.getLong("revision") + 1)
        val items = root.getJSONArray("items")
        (0 until items.length()).map { items.getJSONObject(it) }
            .single { it.getString("kind") == "model" }.put("version", "next-exact-version")
    }

    private fun addFormFromSearch(kind: String, role: String, enabled: Boolean, settings: String = "{}"): AlertDialog {
        useInDraft(details("cat.$kind.fixture")).performClick()
        val form = chooseAdd(dialog())
        field(form.window!!.decorView, R.string.component_role).setText(role)
        field(form.window!!.decorView, R.string.component_settings).setText(settings)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = enabled
        return form
    }

    private fun addFromSearch(kind: String, role: String, enabled: Boolean = false, settings: String = "{}") {
        val before = loaded()
        val form = addFormFromSearch(kind, role, enabled, settings)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
    }

    private fun replaceFromSearch(selectionId: String): AlertDialog {
        useInDraft(details("cat.model.fixture")).performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().endsWith(selectionId)
        }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }

    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any {
        it.text.toString() == activity.getString(label)
    }

    @Test fun searchReplacementPreservesPrimaryAndSecondaryIdentitiesAndExactlyTheirRoles() {
        emptyDraft()
        addFromSearch("model", "primary", true, "{\"temperature\":0.25}")
        addFromSearch("model", "secondary", false, "{\"top_p\":0.9}")
        showCatalog(nextModelVersion())
        for (role in listOf("primary", "secondary")) {
            val before = loaded()
            val target = before.components.selections.single { it.role == role }
            val other = before.components.selections.single { it.role != role }
            val form = replaceFromSearch(target.id)
            val context = browseState()
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
            repeat(3) { commit.performClick() }
            await { !form.isShowing && loaded().revision == before.revision + 1 }
            finishWork()
            val saved = loaded()
            val replaced = saved.components.selections.single { it.id == target.id }
            assertEquals("next-exact-version", replaced.catalogItemVersion)
            assertEquals(target.id, replaced.id)
            assertEquals(target.kind, replaced.kind)
            assertEquals(target.catalogItemId, replaced.catalogItemId)
            assertEquals(target.role, replaced.role)
            assertEquals(target.enabled, replaced.enabled)
            assertEquals(PreviewSelectionForms.settingsText(target), PreviewSelectionForms.settingsText(replaced))
            assertEquals(other, saved.components.selections.single { it.id == other.id })
            assertEquals(before.components.links, saved.components.links)
            assertEquals(before.fields, saved.fields)
            assertEquals(before.contextInput, saved.contextInput)
            assertEquals(before.workloadInput, saved.workloadInput)
            assertEquals(context.getString("catalogQuery"), browseState().getString("catalogQuery"))
            commit.performClick()
            finishWork()
            assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        }
    }

    @Test fun cardinalityAndDuplicateRoleRejectionRetainInputUntilExplicitValidCorrection() {
        emptyDraft()
        addFromSearch("workload", "primary", true)
        val before = loaded()
        val form = addFormFromSearch("workload", "secondary", true)
        val save = form.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick()
        await { save.isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.toString().contains("ACTIVE_CARDINALITY_EXCEEDED") } }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("secondary", field(form.window!!.decorView, R.string.component_role).text.toString())
        assertTrue(views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked)
        views(form.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = false
        field(form.window!!.decorView, R.string.component_role).setText("primary")
        save.performClick()
        await { save.isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.toString().contains("DUPLICATE_KIND_ROLE") } }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        field(form.window!!.decorView, R.string.component_role).setText("secondary")
        save.performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
        val selections = loaded().components.selections
        assertEquals(2, selections.size)
        assertTrue(selections.single { it.role == "primary" }.enabled)
        assertFalse(selections.single { it.role == "secondary" }.enabled)
    }

    @Test fun pendingBuilderTextDisablesSearchSelectionAndRemainsUntouched() {
        val before = emptyDraft()
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("unsaved title retained")
        val detail = details("cat.model.fixture")
        val context = browseState()
        val action = useInDraft(detail)
        assertFalse(action.isEnabled)
        assertTrue(shadowOf(detail).message.toString().contains(activity.getString(R.string.catalog_selection_requires_saved)))
        action.performClick()
        finishWork()
        assertTrue(detail.isShowing)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("unsaved title retained", field(R.string.draft_title).text.toString())
        assertEquals(context.getString("catalogQuery"), browseState().getString("catalogQuery"))
        assertTrue("Selection must observe pending input before autosave", SystemClock.uptimeMillis() - inputStartedAt < 750L)
    }

    @Test fun changedSavedDraftRetiresSearchAuthorityAndExplainsRecoveryWithoutOverwriting() {
        emptyDraft()
        val id = currentId()
        val detail = details("cat.model.fixture")
        val action = useInDraft(detail)
        assertTrue(action.isEnabled)
        field(R.string.draft_title).setText("new acknowledged draft revision")
        button(R.string.save_draft).performClick()
        assertFalse("Saving retires the captured detail synchronously", detail.isShowing)
        assertNull(PreviewActivity::class.java.getDeclaredField("catalogDetailDialog")
            .apply { isAccessible = true }.get(activity))
        val retiredEnabled = action.isEnabled
        val retiredMessage = shadowOf(detail).message.toString()
        await { hasText(R.string.draft_saved) }
        finishWork()
        val saved = loaded()
        val currentWindow = dialog()
        action.performClick()
        finishWork()
        assertFalse(detail.isShowing)
        assertSame(currentWindow, dialog())
        assertEquals(retiredEnabled, action.isEnabled)
        assertEquals(retiredMessage, shadowOf(detail).message.toString())
        assertEquals(id, currentId())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertEquals("new acknowledged draft revision", field(R.string.draft_title).text.toString())

        val fresh = details("cat.model.fixture")
        val freshAction = useInDraft(fresh)
        assertTrue(freshAction.isEnabled)
        action.performClick()
        finishWork()
        assertSame(fresh, dialog())
        assertTrue(fresh.isShowing)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        freshAction.performClick()
        val picker = dialog()
        assertFalse(fresh.isShowing)
        assertNotSame(fresh, picker)
        assertTrue(picker.isShowing)
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        action.performClick()
        finishWork()
        assertFalse(picker.isShowing)
        assertSame(picker, dialog())
        assertEquals(id, currentId())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertEquals("new acknowledged draft revision", field(R.string.draft_title).text.toString())

        val nextDetail = details("cat.model.fixture")
        val nextAction = useInDraft(nextDetail)
        assertTrue(nextAction.isEnabled)
        action.performClick()
        finishWork()
        assertSame(nextDetail, dialog())
        assertTrue(nextDetail.isShowing)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        nextAction.performClick()
        val form = chooseAdd(dialog())
        field(form.window!!.decorView, R.string.component_role).setText("primary")
        field(form.window!!.decorView, R.string.component_settings).setText("{}")
        val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        repeat(2) { commit.performClick() }
        await { !form.isShowing && loaded().revision == saved.revision + 1 }
        finishWork()
        val added = loaded()
        assertEquals(id, currentId())
        assertEquals("cat.model.fixture", added.components.selections.single().catalogItemId)
        assertEquals("primary", added.components.selections.single().role)
        assertEquals(saved.fields, added.fields)
        assertEquals(saved.contextInput, added.contextInput)
        assertEquals(saved.workloadInput, added.workloadInput)
        assertEquals(saved.components.links, added.components.links)
        action.performClick()
        commit.performClick()
        finishWork()
        assertEquals(added.baselineIdentity, loaded().baselineIdentity)
        assertEquals(added.components.selections, loaded().components.selections)
    }

    @Test fun aRemovedReplacementTargetCannotOverwriteTheOtherWritersNewRevision() {
        emptyDraft()
        addFromSearch("model", "primary")
        showCatalog(nextModelVersion())
        val before = loaded()
        val target = before.components.selections.single()
        val form = replaceFromSearch(target.id)
        val adapter = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(currentId())
        val removal = adapter.applySelection(before, SelectionCommand.Remove(target.id), null) as PreviewSelectionSave.Saved
        assertTrue(removal.record.components.selections.isEmpty())
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { hasText(R.string.draft_conflict) }
        finishWork()
        assertEquals(removal.record.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertTrue(form.isShowing)
        assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun changedCatalogRejectsTheOriginalSearchActionWithoutSelectingAnotherVersion() {
        val before = emptyDraft()
        val detail = details("cat.model.fixture")
        val action = useInDraft(detail)
        val context = browseState()
        showCatalog(nextModelVersion())
        action.performClick()
        finishWork()
        assertFalse(action.isEnabled)
        assertTrue(shadowOf(detail).message.toString().contains(activity.getString(R.string.catalog_selection_changed)))
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertEquals(context.getString("catalogQuery"), browseState().getString("catalogQuery"))
    }
}
