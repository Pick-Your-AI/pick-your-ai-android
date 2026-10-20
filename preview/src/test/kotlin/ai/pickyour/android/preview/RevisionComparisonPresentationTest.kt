package ai.pickyour.android.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.*
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
import java.security.MessageDigest
import java.util.Locale

/** Real comparison dialogs over saved local fixtures; no installed rendering or TalkBack claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class RevisionComparisonPresentationTest {
    private lateinit var controller: ActivityController<out Activity>
    private var browser: RevisionComparisonBrowser? = null
    private var libraryBrowser: LibraryRevisionComparison? = null
    private val activity get() = controller.get()

    class ForcedRtlActivity : Activity() {
        override fun attachBaseContext(base: Context) {
            val config = Configuration(base.resources.configuration).apply {
                setLayoutDirection(Locale.forLanguageTag("ar-EG"))
            }
            super.attachBaseContext(base.createConfigurationContext(config))
        }
    }

    @After fun close() {
        browser?.retire()
        libraryBrowser?.retire()
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun launch(forcedRtl: Boolean = false) {
        controller = if (forcedRtl) Robolectric.buildActivity(ForcedRtlActivity::class.java).setup()
            else Robolectric.buildActivity(Activity::class.java).setup()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertTrue(it.isShowing) }
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun texts(window: AlertDialog = dialog()) = views(window.window!!.decorView)
        .filterIsInstance<TextView>().filterNot { it is Button }.map { it.text.toString() }
    private fun button(text: String, window: AlertDialog = dialog()) = views(window.window!!.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == text }
    private fun click(resource: Int) { button(activity.getString(resource)).performClick(); idle() }
    private fun inspect(index: Int, field: String) {
        button(activity.getString(R.string.revision_compare_inspect, index, field)).performClick(); idle()
    }
    private fun value() = texts().single { it.startsWith("text: ") ||
        it == activity.getString(R.string.revision_compare_absent) || it == activity.getString(R.string.context_empty) }
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun comparison(before: DraftContent, after: DraftContent): PreviewRevisionComparison {
        val directory = Files.createDirectories(activity.noBackupFilesDir.toPath().resolve("comparison"))
        val store = LocalDraftStore(directory)
        val metadata = mapOf("preview.format" to "1", "preview.title" to "fixture")
        val first = store.save(null, before, metadata) as DraftSave.Saved
        assertTrue(store.save(first.draft.version, after, metadata) is DraftSave.Saved)
        val record = (PreviewDraftPersistence(store).load() as PreviewDraftLoad.Available).record
        return requireNotNull(record.compareRevisions(0, 1))
    }
    private fun settings(key: String, value: String) =
        DraftContent(optional = mapOf(OptionalTier.T3 to TierDraft(settings = mapOf(key to value))))
    private fun show(result: PreviewRevisionComparison, current: () -> Boolean = { true }) {
        browser = RevisionComparisonBrowser(activity, result, current).also { it.show(); idle() }
    }
    // Literal platform output for these known-direction inputs; do not call the production formatter.
    private fun ltrInRtl(value: String) = "\u200f\u202a$value\u202c\u200f"
    private fun rtlInLtr(value: String) = "\u200e\u202b$value\u202c\u200e"

    @Test fun ltrComparisonIsolatesRtlKeysAndValuesWithoutChangingCapturedText() {
        launch()
        val key = "مفتاح"
        val before = "قبل 12.3400"
        val after = "after 99.0000"
        val result = comparison(settings(key, before), settings(key, after))
        val bytes = disk()
        show(result)
        val expected = "T3.setting [text: ${rtlInLtr("\"$key\"")}]\n" +
            "Before: text: ${rtlInLtr("\"$before\"")}\nAfter: text: \"$after\""
        assertTrue(texts().contains(expected))
        inspect(1, "T3.setting")
        assertTrue(texts().contains(expected))
        click(R.string.revision_compare_inspect_key)
        assertEquals("text: ${rtlInLtr("\"$key\"")}", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_before)
        assertEquals("text: ${rtlInLtr("\"$before\"")}", value())
        click(R.string.revision_compare_back_field)
        click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"$after\"", value())
        val change = result.changes.single()
        assertEquals(key, change.key); assertEquals(before, change.before); assertEquals(after, change.after)
        assertEquals(0L, result.from); assertEquals(1L, result.to)
        assertEquals(bytes, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test @Config(qualifiers = "ar-rEG")
    fun rtlComparisonIsolatesLtrTextAndLeavesNaturalRtlValuesIntact() {
        launch()
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val result = comparison(settings("key-01", "before 12.3400"), settings("key-01", "بعد 7"))
        val bytes = disk()
        show(result)
        val expected = "T3.setting [text: ${ltrInRtl("\"key-01\"")}]\n" +
            "Before: text: ${ltrInRtl("\"before 12.3400\"")}\nAfter: text: \"بعد 7\""
        assertTrue(texts().contains(expected))
        inspect(1, "T3.setting"); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"بعد 7\"", value())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_inspect_before)
        assertEquals("text: ${ltrInRtl("\"before 12.3400\"")}", value())
        assertEquals("before 12.3400", result.changes.single().before)
        assertEquals(bytes, disk())
    }

    @Test fun forcedRtlLiteralPartsKeepEscapedCodeUnitsAndRetiredControls() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            launch(forcedRtl = true)
            assertEquals(Locale.US, activity.resources.configuration.locales[0])
            assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
            val raw = "A".repeat(159) + "\uD83D\uDE80\n\u202eאבג"
            val result = comparison(DraftContent(objective = "before"), DraftContent(objective = raw))
            val bytes = disk()
            show(result)
            val preview = "text: ${ltrInRtl("\"" + "A".repeat(159) + "\\ud83d\"")}"
            assertTrue(texts().any { it.endsWith("After: $preview… [truncated]") })
            inspect(1, "objective"); click(R.string.revision_compare_inspect_after)
            assertEquals(preview, value())
            val first = dialog()
            val next = button(activity.getString(R.string.revision_compare_next_part), first)
            next.performClick(); idle()
            val second = dialog()
            val lastValue = "text: ${ltrInRtl("\"\\ude80\\n\\u202eאבג\"")}"
            assertEquals(lastValue, value())
            assertTrue(texts().contains(activity.getString(R.string.revision_compare_value_part,
                2, 2, 161, raw.length, raw.length)))
            next.performClick(); idle()
            assertSame(second, dialog())
            val back = button(activity.getString(R.string.revision_compare_previous_part), second)
            browser!!.retire()
            back.performClick(); idle()
            assertSame(second, ShadowAlertDialog.getLatestAlertDialog())
            assertFalse(second.isShowing)
            assertEquals(raw, result.changes.single().after)
            assertEquals(bytes, disk())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { Locale.setDefault(oldLocale) }
    }

    @Test fun absentEmptyAndLiteralSentinelsRemainDistinctAfterAuthorityRetires() {
        launch()
        var current = true
        val result = comparison(DraftContent(), DraftContent(objective = "", context = "absent or unanswered"))
        val bytes = disk()
        show(result) { current }
        inspect(1, "objective"); click(R.string.revision_compare_inspect_before)
        assertEquals(activity.getString(R.string.revision_compare_absent), value())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_inspect_after)
        assertEquals(activity.getString(R.string.context_empty), value())
        click(R.string.revision_compare_back_field); click(R.string.revision_compare_back_changes)
        inspect(2, "context"); click(R.string.revision_compare_inspect_after)
        assertEquals("text: \"absent or unanswered\"", value())
        val old = dialog()
        val back = button(activity.getString(R.string.revision_compare_back_field), old)
        current = false; back.performClick(); idle()
        assertFalse(old.isShowing)
        current = true; back.performClick(); browser!!.show(); idle()
        assertSame(old, ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(old.isShowing)
        assertEquals(bytes, disk())
    }

    private fun libraryFlow(forcedRtl: Boolean) {
        launch(forcedRtl)
        val first = PreviewDraftId("guest-draft")
        val second = PreviewDraftId("draft-00000000-0000-0000-0000-000000000002")
        fun append(id: PreviewDraftId, title: String, objective: String) {
            val path = Files.createDirectories(activity.noBackupFilesDir.toPath().resolve(id.value))
            val adapter = PreviewDraftPersistence(LocalDraftStore(path))
            val record = (adapter.load() as? PreviewDraftLoad.Available)?.record
            assertTrue(adapter.save(record, PreviewDraftFields(title), PreviewDraftContext(objective)) is PreviewDraftSave.Saved)
        }
        val originalTitle = if (forcedRtl) "saved first 12.3400" else "عنوان أول"
        append(first, originalTitle, "captured before")
        append(first, "unrelated later head", "later before")
        append(second, "saved second", "captured after")
        val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
        val captured = library.list() as PreviewLibraryRead.Available
        val bytes = disk()
        var reads = 0
        libraryBrowser = LibraryRevisionComparison(activity, { it() }, { reads++; captured }, { true })
            .also { it.show(); idle() }
        fun formatted(raw: String) = "text: " + if (forcedRtl) ltrInRtl("\"$raw\"") else rtlInLtr("\"$raw\"")
        val titleLiteral = formatted(originalTitle)
        val headLiteral = if (forcedRtl) "text: ${ltrInRtl("\"unrelated later head\"")}" else "text: \"unrelated later head\""
        assertTrue(texts().contains(activity.getString(R.string.library_compare_current_title, headLiteral)))
        button(activity.getString(R.string.library_compare_pick_draft, first.value)).performClick(); idle()
        assertTrue(texts().contains(activity.getString(R.string.library_compare_saved_title, titleLiteral)))
        val obsoleteRevision = dialog()
        val oldPick = button(activity.getString(R.string.library_compare_pick_revision, "0"), obsoleteRevision)
        oldPick.performClick(); idle()
        assertTrue(texts().contains(activity.getString(R.string.library_compare_saved_title, titleLiteral)))
        button(activity.getString(R.string.library_compare_pick_draft, second.value)).performClick(); idle()
        button(activity.getString(R.string.library_compare_pick_revision, "0")).performClick(); idle()
        assertTrue(texts().contains(activity.getString(R.string.library_compare_scope)))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_saved_title, titleLiteral)))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_before_source, first.value, "0")))
        assertTrue(texts().contains(activity.getString(R.string.library_compare_after_source, second.value, "0")))
        assertTrue(texts().none { it.contains("unrelated later head") || it.contains("later before") })
        inspect(1, "objective"); click(R.string.revision_compare_inspect_before)
        val beforeLiteral = if (forcedRtl) "text: ${ltrInRtl("\"captured before\"")}" else "text: \"captured before\""
        assertEquals(beforeLiteral, value())
        val current = dialog()
        oldPick.performClick(); idle()
        assertSame(current, dialog())
        libraryBrowser!!.retire(); oldPick.performClick(); idle()
        assertFalse(current.isShowing)
        assertSame(current, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(1, reads)
        assertEquals(bytes, disk())
        assertEquals(PreviewActiveRead.None, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun libraryRtlTitlesStayIsolatedThroughExactSavedPairNavigation() = libraryFlow(false)

    @Test fun forcedRtlLibraryTitlesUseScreenDirectionAndKeepReadOnlyCapture() = libraryFlow(true)
}
