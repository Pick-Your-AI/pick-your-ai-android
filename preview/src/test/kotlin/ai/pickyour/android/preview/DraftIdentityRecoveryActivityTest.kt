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
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Saved-state recovery on a simulated Activity; this is not installed process-death evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DraftIdentityRecoveryActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private lateinit var root: Path
    private lateinit var saved: PreviewStoredDraft
    private lateinit var active: PreviewActiveRead

    @Before fun launchSaved() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        finishWork()
        field(R.string.draft_title).setText("acknowledged local draft")
        button(R.string.save_draft).performClick()
        finishWork()
        root = activity.noBackupFilesDir.toPath()
        saved = loaded()
        active = PreviewActiveDraftStore(root).read()
        field(R.string.draft_title).setText("pending recovered edit")
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun malformedDraftIdentityKeepsPendingInputWithoutOpeningOrSavingAnotherDraft() {
        val state = capture()
        state.putString("draftId", "../another-private-draft")
        restore(state)
        assertUnbound()
    }

    @Test fun malformedCleanupIdentityCannotCrashOrReplayDeletion() {
        val state = capture()
        state.putString("pendingDeleteId", "../another-private-draft")
        restore(state)
        assertUnbound()
    }

    @Test fun wrongTypedAndOversizedIdentitiesRejectTheCompleteCheckpoint() {
        val original = capture()
        var first = true
        for (key in listOf("draftId", "pendingDeleteId")) for (oversized in listOf(false, true)) {
            if (!first) controller.pause().stop().destroy()
            first = false
            val state = Bundle(original)
            if (oversized) state.putString(key, "x".repeat(4096)) else state.putInt(key, 42)
            restore(state)
            assertUnbound()
        }
    }

    @Test fun rejectedIdentityRemainsUnboundAcrossRecreationAndLauncherRetry() {
        val state = capture()
        state.putString("draftId", "unsupported saved identity")
        restore(state)
        assertUnbound()
        controller.newIntent(android.content.Intent(android.content.Intent.ACTION_MAIN))
        finishWork()
        assertUnbound()
        val rejected = capture()
        assertTrue(rejected.getBoolean(DraftIdentityCheckpoint.UNAVAILABLE_KEY))
        assertFalse(rejected.getString("draftId").orEmpty().contains("unsupported"))
        restore(rejected)
        assertUnbound()
    }

    @Test fun explicitLibraryChoiceRecoversWithoutApplyingUnboundPendingInput() {
        val state = capture()
        state.putString("draftId", "unsupported saved identity")
        restore(state)
        assertUnbound()
        button(R.string.library_open).performClick()
        finishWork()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals("acknowledged local draft", field(R.string.draft_title).text.toString())
        assertTrue(button(R.string.reopen_draft).isEnabled)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        val recovered = capture()
        assertFalse(recovered.getBoolean(DraftIdentityCheckpoint.UNAVAILABLE_KEY))
        restore(recovered)
        assertEquals("acknowledged local draft", field(R.string.draft_title).text.toString())
        assertTrue(button(R.string.reopen_draft).isEnabled)
    }

    @Test fun validDraftAndCleanupIdentitiesRetainTheCorrectRecoveryControls() {
        val state = capture()
        state.putBoolean("autosaveRequested", false)
        state.putString("pendingDeleteId", "guest-draft")
        restore(state)
        assertEquals("pending recovered edit", field(R.string.draft_title).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue(button(R.string.reopen_draft).isEnabled)
        assertTrue(button(R.string.library_retry_delete).isShown)
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(root).read())
    }

    private fun capture(): Bundle = Bundle().also {
        controller.pause().saveInstanceState(it).stop().destroy()
    }

    private fun restore(state: Bundle) {
        controller = Robolectric.buildActivity(PreviewActivity::class.java)
        controller.create(state).start().resume().visible()
        finishWork()
    }

    private fun assertUnbound() {
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        finishWork()
        assertEquals("pending recovered edit", field(R.string.draft_title).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertFalse(button(R.string.reopen_draft).isEnabled)
        assertFalse(button(R.string.library_retry_delete).isShown)
        assertTrue(button(R.string.library_open).isEnabled)
        assertTrue(views().filterIsInstance<TextView>().any {
            it.isShown && it.text.toString() == activity.getString(R.string.draft_identity_unavailable)
        })
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(root).read())
    }

    private fun loaded() = (PreviewDraftLibrary(root).openLegacy().load() as PreviewDraftLoad.Available).record

    private fun views(rootView: View = activity.window.decorView): List<View> = buildList {
        add(rootView)
        if (rootView is ViewGroup) for (index in 0 until rootView.childCount) addAll(views(rootView.getChildAt(index)))
    }

    private fun button(label: Int): Button = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }

    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }

    private fun finishWork() {
        val member = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = member.get(activity) as ExecutorService
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker.submit {}.get(15, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
