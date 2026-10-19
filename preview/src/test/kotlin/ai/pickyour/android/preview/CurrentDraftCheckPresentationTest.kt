package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import ai.pickyour.core.drafts.DraftContent
import ai.pickyour.core.drafts.DraftSave
import ai.pickyour.core.drafts.DraftSelections
import ai.pickyour.core.drafts.LocalDraftStore
import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.evaluation.DraftCurrentInputs
import ai.pickyour.core.preview.PreviewCurrentUnavailable
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewDraftPersistence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Locale
import java.util.concurrent.Executor

/** Visible section content on an Activity whose layout direction differs from its locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CurrentDraftCheckPresentationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    @Config(qualifiers = "en +ldrtl")
    fun englishLocaleWithRtlLayoutIsolatesRecordedIdentifiersInTheVisibleSection() {
        checkPresentation(Locale.ENGLISH, View.LAYOUT_DIRECTION_RTL,
            "\u200f\u202a\"sel_model\"\u202c\u200f")
    }

    @Test
    @Config(qualifiers = "ar +ldltr")
    fun arabicLocaleWithLtrLayoutKeepsRecordedIdentifiersInTheVisibleSectionLtr() {
        checkPresentation(Locale("ar"), View.LAYOUT_DIRECTION_LTR, "\"sel_model\"")
    }

    private fun checkPresentation(locale: Locale, direction: Int, displayedId: String) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var section: CurrentDraftCheckSection? = null
        try {
            assertEquals(locale.language, activity.resources.configuration.locales[0].language)
            assertEquals(direction, activity.resources.configuration.layoutDirection)
            fun fixture(name: String) = activity.assets.open("$name.json").use { it.readBytes() }
            val rows = JSONObject(String(fixture("build"), Charsets.UTF_8)).getJSONArray("selections")
            val model = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("kind") == "model" }
            val selection = LocalSelection.fromJson(model.toString().toByteArray(Charsets.UTF_8))
            assertEquals("sel_model", selection.id)
            val store = LocalDraftStore(temporary.newFolder("draft").toPath())
            assertTrue(store.save(null, DraftContent(components = DraftSelections(listOf(selection))),
                mapOf("preview.format" to "1", "preview.title" to "Direction fixture")) is DraftSave.Saved)
            val persistence = PreviewDraftPersistence(store)
            val record = (persistence.load() as PreviewDraftLoad.Available).record
            val inputs = requireNotNull(DraftCurrentInputs.read(fixture("catalog"), fixture("dependency-graph"), fixture("ruleset")))
            val visible = CurrentDraftCheckSection(activity, Executor { it.run() }, { true }, {})
            section = visible
            activity.setContentView(visible)
            visible.update(record, inputs, true, Any(), PreviewCurrentUnavailable.STORAGE_UNCONFIRMED,
                { true }, persistence::load)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(activity.getString(R.string.current_check_affected, displayedId),
                activity.findViewById<TextView>(R.id.current_draft_check_affected).text.toString())
            val inspect = activity.findViewById<Button>(R.id.current_draft_check_inspect)
            assertTrue(inspect.isEnabled)
            inspect.performClick()
            val page = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val label = page.listView.adapter.getItem(0).toString()
            if (direction == View.LAYOUT_DIRECTION_RTL) {
                assertTrue(label.startsWith("\u200f\u202a\""))
                assertTrue(label.contains("\"\u202c\u200f · "))
            } else {
                assertTrue(label.startsWith("\""))
                assertFalse(label.contains('\u200f'))
                assertFalse(label.contains('\u202a'))
                assertFalse(label.contains('\u202c'))
            }
            page.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            val after = (persistence.load() as PreviewDraftLoad.Available).record
            assertEquals(record.baselineIdentity, after.baselineIdentity)
            assertEquals(listOf(selection), after.components.selections)
        } finally {
            section?.close()
            controller.pause().stop().destroy()
        }
    }
}
