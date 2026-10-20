package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowSystemClock
import ai.pickyour.core.preview.PreviewDraftAutosave
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.security.MessageDigest

/** Actual Activity callbacks in the pinned SDK 28 simulation; not installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyBackNavigationTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled }
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Suppress("DEPRECATION")
    @Test fun sdk28BackKeepsInvalidPendingFieldsUntilExplicitChoice() {
        field(R.string.draft_title).setText("Acknowledged draft")
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && hasText(R.string.draft_saved) }
        val before = storedFiles()
        assertTrue(before.isNotEmpty())
        field(R.string.workload_requests).setText("invalid")
        assertFalse(button(R.string.save_draft).isEnabled)

        activity.onBackPressed()

        assertFalse("Back must not finish an editor with unacknowledged invalid input before a choice", activity.isFinishing)
        assertEquals("invalid", field(R.string.workload_requests).text.toString())
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun stayRetainsRawFieldsAndDoesNotWriteAnInvalidForm() {
        acknowledge()
        val before = storedFiles()
        field(R.string.workload_requests).setText("invalid")
        field(R.string.draft_title).setText("Pending title")
        activity.onBackPressed()
        val choice = dialog()
        choose(choice, AlertDialog.BUTTON_NEGATIVE)
        assertFalse(choice.isShowing)
        assertFalse(activity.isFinishing)
        assertEquals("Pending title", field(R.string.draft_title).text.toString())
        assertEquals("invalid", field(R.string.workload_requests).text.toString())
        advance(PreviewDraftAutosave.DELAY_MILLIS + 1)
        finishWorker()
        assertEquals(before, storedFiles())
        assertFalse(button(R.string.save_draft).isEnabled)
    }

    @Suppress("DEPRECATION")
    @Test fun explicitLeaveCancelsQueuedAutosaveAndRetainedSaveCannotWrite() {
        acknowledge()
        val before = storedFiles()
        field(R.string.draft_title).setText("Pending title")
        val oldSave = button(R.string.save_draft)
        assertTrue(oldSave.isEnabled)
        activity.onBackPressed()
        val choice = dialog()
        choose(choice, AlertDialog.BUTTON_POSITIVE)
        assertTrue(activity.isFinishing)
        assertFalse(choice.isShowing)
        oldSave.performClick()
        advance(PreviewDraftAutosave.DELAY_MILLIS + 1)
        finishWorker()
        assertEquals(before, storedFiles())
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { hasText(R.string.draft_saved) }
        assertEquals("Acknowledged draft", field(R.string.draft_title).text.toString())
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun untouchedBlankEditorUsesThePlatformBackAction() {
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        val before = storedFiles()
        activity.onBackPressed()
        assertTrue(activity.isFinishing)
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun acknowledgedEditorUsesThePlatformBackActionWithoutWriting() {
        acknowledge()
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        val before = storedFiles()
        activity.onBackPressed()
        assertTrue(activity.isFinishing)
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun changedThenRestoredInputCannotReviveAnOldLeaveChoice() {
        acknowledge()
        val before = storedFiles()
        field(R.string.workload_requests).setText("invalid")
        activity.onBackPressed()
        val old = dialog()
        field(R.string.draft_title).setText("Changed")
        assertFalse(old.isShowing)
        field(R.string.draft_title).setText("Acknowledged draft")
        choose(old, AlertDialog.BUTTON_POSITIVE)
        assertFalse(activity.isFinishing)
        assertEquals(before, storedFiles())
        activity.onBackPressed()
        assertNotSame(old, dialog())
        assertTrue(dialog().isShowing)
    }

    @Suppress("DEPRECATION")
    @Test fun repeatedBackRetiresTheOlderChoiceAndConsumesTheCurrentChoiceOnce() {
        acknowledge()
        val before = storedFiles()
        field(R.string.workload_requests).setText("invalid")
        activity.onBackPressed()
        val old = dialog()
        activity.onBackPressed()
        val current = dialog()
        assertNotSame(old, current)
        assertFalse(old.isShowing)
        choose(old, AlertDialog.BUTTON_POSITIVE)
        assertFalse(activity.isFinishing)
        choose(current, AlertDialog.BUTTON_POSITIVE)
        assertTrue(activity.isFinishing)
        choose(current, AlertDialog.BUTTON_POSITIVE)
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun pauseRetiresTheChoiceAndResumeRequiresANewBackAction() {
        acknowledge()
        val before = storedFiles()
        field(R.string.workload_requests).setText("invalid")
        activity.onBackPressed()
        val old = dialog()
        controller.pause()
        assertFalse(old.isShowing)
        controller.resume()
        choose(old, AlertDialog.BUTTON_POSITIVE)
        assertFalse(activity.isFinishing)
        activity.onBackPressed()
        assertTrue(dialog().isShowing)
        assertNotSame(old, dialog())
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun recreatedPendingEditorDoesNotRestoreTheOldLeaveAuthority() {
        acknowledge()
        val before = storedFiles()
        field(R.string.workload_requests).setText("invalid")
        activity.onBackPressed()
        val old = dialog()
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.library_open).isEnabled }
        choose(old, AlertDialog.BUTTON_POSITIVE)
        assertFalse(activity.isFinishing)
        assertEquals("invalid", field(R.string.workload_requests).text.toString())
        activity.onBackPressed()
        assertTrue(dialog().isShowing)
        assertNotSame(old, dialog())
        assertEquals(before, storedFiles())
    }

    @Suppress("DEPRECATION")
    @Test fun pendingWriteOffersNoLeaveChoiceAndItsAcknowledgmentRetiresTheNotice() {
        acknowledge()
        field(R.string.draft_title).setText("Second acknowledged draft")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        try {
            button(R.string.save_draft).performClick()
            activity.onBackPressed()
            val waiting = dialog()
            assertEquals(activity.getString(R.string.legacy_back_wait_message),
                waiting.findViewById<TextView>(android.R.id.message).text.toString())
            assertEquals(View.GONE, waiting.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            assertFalse(activity.isFinishing)
            release.countDown()
            await { hasText(R.string.draft_saved) }
            assertFalse(waiting.isShowing)
            assertEquals("Second acknowledged draft", field(R.string.draft_title).text.toString())
        } finally { release.countDown() }
    }

    @Suppress("DEPRECATION")
    @Test fun stayPreservesTheExistingValidFormAutosaveDeadline() {
        acknowledge()
        val before = storedFiles()
        field(R.string.draft_title).setText("Pending valid title")
        val editedAt = SystemClock.uptimeMillis()
        advance(300)
        activity.onBackPressed()
        choose(dialog(), AlertDialog.BUTTON_NEGATIVE)
        assertEquals(editedAt + 300, SystemClock.uptimeMillis())
        advance(PreviewDraftAutosave.DELAY_MILLIS - 301)
        finishWorker()
        assertEquals(before, storedFiles())
        assertTrue(button(R.string.save_draft).isEnabled)
        advance(1)
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
        assertNotEquals(before, storedFiles())
        assertFalse(activity.isFinishing)
    }

    private fun acknowledge() {
        field(R.string.draft_title).setText("Acknowledged draft")
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && hasText(R.string.draft_saved) }
    }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun choose(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
        // AlertController posts the listener and dismissal. Observe both, including stale replays,
        // without advancing the debounce clock or executing any future delayed autosave.
        AutosaveUiClock.idle()
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWorker() {
        AutosaveUiClock.idle()
        worker().submit {}.get(15, TimeUnit.SECONDS)
        AutosaveUiClock.idle()
    }
    private fun advance(millis: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
        AutosaveUiClock.idle()
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>()
        .any { it.text.toString() == activity.getString(label) }
    private fun storedFiles(): Map<String, String> {
        val root = activity.noBackupFilesDir.toPath()
        return Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList()
            .associate { root.relativize(it).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(it)).joinToString("") { byte -> "%02x".format(byte) } } }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            AutosaveUiClock.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit UI state")
    }
}
