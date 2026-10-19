package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.util.Locale

/** Actual resources/dialogs and typed command capture; no installed rendering or save execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class ComponentSelectionPresentationTest {
    private lateinit var controller: ActivityController<out Activity>
    private lateinit var section: SelectionEditSection
    private lateinit var record: PreviewStoredDraft
    private lateinit var store: LocalDraftStore
    private lateinit var catalog: PreviewCatalogBrowser
    private lateinit var selection: LocalSelection
    private val dispatches = mutableListOf<Triple<PreviewStoredDraft, String?, PreviewSelectionPreparation>>()
    private val activity get() = controller.get()
    private val rtlVersion = "نسخة 2"

    @After fun close() {
        if (::section.isInitialized) section.close()
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    class ForcedRtlActivity : Activity() {
        override fun attachBaseContext(base: Context) {
            val config = Configuration(base.resources.configuration).apply {
                setLayoutDirection(Locale.forLanguageTag("ar-EG"))
            }
            super.attachBaseContext(base.createConfigurationContext(config))
        }
    }

    private fun launch(forcedRtl: Boolean = false) {
        controller = if (forcedRtl) Robolectric.buildActivity(ForcedRtlActivity::class.java).setup()
            else Robolectric.buildActivity(Activity::class.java).setup()
        fun fixture(name: String) = activity.assets.open("$name.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val source = fixture("catalog")
        val items = source.getJSONArray("items")
        val item = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("kind") == "model" }
        items.put(JSONObject(item.toString()).put("version", rtlVersion))
        catalog = requireNotNull(PreviewCatalogBrowser.read(source.toString().toByteArray(Charsets.UTF_8)))
        val selections = fixture("build").getJSONArray("selections")
        selection = LocalSelection.fromJson((0 until selections.length()).map { selections.getJSONObject(it) }
            .single { it.getString("kind") == "model" }.toString().toByteArray(Charsets.UTF_8))
        store = LocalDraftStore(Files.createDirectories(activity.noBackupFilesDir.toPath().resolve("guest-draft")))
        assertTrue(store.save(null, DraftContent().withComponents(DraftSelections(listOf(selection), emptyList())),
            mapOf("preview.format" to "1", "preview.title" to "Retained title")) is DraftSave.Saved)
        record = (PreviewDraftPersistence(store).load() as PreviewDraftLoad.Available).record
        section = SelectionEditSection(activity, { expected, hash, prepare, complete ->
            dispatches += Triple(expected, hash, prepare())
            // Capture at the real UI-to-domain boundary, with no test-only successful save.
            complete(false, "No test write dispatched")
        })
        activity.setContentView(section)
        section.catalog(catalog.items, catalog.hash)
        section.update(record, true, "guest-draft")
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertTrue(it.isShowing) }
    private fun rows(dialog: AlertDialog) = (0 until dialog.listView.adapter.count)
        .map { dialog.listView.adapter.getItem(it).toString() }
    private fun chooseCatalog(): AlertDialog {
        button(R.string.component_choose_catalog).performClick()
        return dialog()
    }
    private fun cancel(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to Files.readAllBytes(it).toList()
        }
    }
    // Literal API-28 BidiFormatter output, not an invocation of the production formatter.
    private fun ltrInRtl(value: String) = "\u200f\u202a$value\u202c\u200f"
    private fun rtlInLtr(value: String) = "\u200e\u202b$value\u202c\u200e"

    @Test fun mixedDirectionCatalogVersionKeepsItsExactReplacementCommand() {
        launch()
        val before = disk()
        val picker = chooseCatalog()
        val models = catalog.items.withIndex().filter { it.value.kind == "model" }
        assertEquals(2, models.size)
        val chosen = models.single { it.value.version == rtlVersion }
        assertEquals("model · ${chosen.value.id} @ ${rtlInLtr(rtlVersion)}", rows(picker)[chosen.index])
        shadowOf(picker).clickOnItem(chosen.index)
        val options = dialog()
        assertEquals("${chosen.value.id} @ ${rtlInLtr(rtlVersion)}", shadowOf(options).title.toString())
        assertTrue(dispatches.isEmpty())
        shadowOf(options).clickOnItem(1)
        val confirmation = dialog()
        assertTrue(shadowOf(confirmation).message.toString().contains(rtlInLtr(rtlVersion)))
        assertEquals(before, disk())
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val (expected, hash, preparation) = dispatches.single()
        assertSame(record, expected)
        assertEquals(catalog.hash, hash)
        val command = (preparation as PreviewSelectionPreparation.Ready).command as SelectionCommand.Replace
        assertEquals(selection.id, command.selectionId)
        assertEquals(chosen.value.id, command.choice.itemId)
        assertEquals(rtlVersion, command.choice.itemVersion)
        assertEquals(catalog.hash, command.choice.snapshotHash.hex)
        assertEquals(before, disk())
    }

    @Test @Config(qualifiers = "ar-rEG")
    fun rtlCatalogRowsKeepNaturalVersionDirectionAndRetireCanceledChoices() {
        launch()
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val before = disk()
        val picker = chooseCatalog()
        val displayed = rows(picker)
        catalog.items.forEachIndexed { index, item ->
            val version = if (item.version == rtlVersion) rtlVersion else ltrInRtl(item.version)
            assertEquals("${ltrInRtl(item.kind)} · ${ltrInRtl(item.id)} @ $version", displayed[index])
        }
        val list = picker.listView
        val oldClick = requireNotNull(list.onItemClickListener)
        cancel(picker)
        val current = chooseCatalog()
        oldClick.onItemClick(list, null, 0, list.adapter.getItemId(0))
        assertSame(current, dialog())
        assertTrue(dispatches.isEmpty())
        assertEquals(before, disk())
    }

    @Test
    fun forcedRtlSavedLabelsUseScreenDirectionWithoutChangingEditableRole() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            launch(forcedRtl = true)
            assertEquals(Locale.US, activity.resources.configuration.locales[0])
            assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
            val before = disk()
            button(R.string.component_manage).performClick()
            val picker = dialog()
            val label = "${ltrInRtl(selection.kind)} · ${ltrInRtl(selection.role)} · ${ltrInRtl(selection.id)}"
            assertEquals(listOf(label), rows(picker))
            shadowOf(picker).clickOnItem(0)
            val actions = dialog()
            assertEquals(label, shadowOf(actions).title.toString())
            shadowOf(actions).clickOnItem(rows(actions).indexOf(activity.getString(R.string.component_edit_role)))
            val form = dialog()
            assertTrue(views(form.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.component_role_warning, ltrInRtl(selection.id))
            })
            val labelView = views(form.window!!.decorView).filterIsInstance<TextView>()
                .single { it.text.toString() == activity.getString(R.string.component_role) }
            val field = form.findViewById<EditText>(labelView.labelFor)
            assertEquals(selection.role, field.text.toString())
            cancel(form)
            assertTrue(dispatches.isEmpty())
            assertEquals(before, disk())
        } finally { Locale.setDefault(previous) }
    }

    @Test fun ltrLabelsAndClosedSectionKeepOriginalIdentityAndRetirement() {
        launch()
        val before = disk()
        button(R.string.component_manage).performClick()
        val picker = dialog()
        assertEquals(listOf("${selection.kind} · ${selection.role} · ${selection.id}"), rows(picker))
        val list = picker.listView
        val oldClick = requireNotNull(list.onItemClickListener)
        section.close()
        assertFalse(picker.isShowing)
        oldClick.onItemClick(list, null, 0, list.adapter.getItemId(0))
        assertFalse(picker.isShowing)
        assertSame(picker, ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(dispatches.isEmpty())
        assertEquals(before, disk())
        assertEquals(selection.role, record.components.selections.single().role)
        assertEquals(selection.id, record.components.selections.single().id)
    }
}
