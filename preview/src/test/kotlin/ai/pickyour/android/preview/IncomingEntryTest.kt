package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Activity entry and saved draft behavior in Robolectric, not installed-device evidence. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class IncomingEntryTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @After fun close() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    private fun button(label: Int): Button = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }

    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return activity.findViewById(caption.labelFor)
    }

    private fun shows(label: Int) = views().filterIsInstance<TextView>()
        .any { it.isShown && it.text.toString() == activity.getString(label) }

    private fun launchSaved(): Pair<Path, PreviewStoredDraft> {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        finishWork()
        field(R.string.draft_title).setText("saved local title")
        button(R.string.save_draft).performClick()
        finishWork()
        val root = activity.noBackupFilesDir.toPath()
        return root to (PreviewDraftLibrary(root).openLegacy().load() as PreviewDraftLoad.Available).record
    }

    private fun coldLaunch(intent: Intent) {
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java, intent).setup()
        finishWork()
    }

    private fun recreate() {
        val state = Bundle()
        val originalIntent = activity.intent
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java, originalIntent)
            .create(state).start().resume().visible()
        finishWork()
    }

    private fun unchanged(root: Path, before: PreviewStoredDraft, active: PreviewActiveRead) {
        assertEquals(before.baselineIdentity,
            (PreviewDraftLibrary(root).openLegacy().load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(root).read())
        assertNull("An incoming payload must not be forwarded", shadowOf(activity).nextStartedActivity)
    }

    private fun finishWork() {
        val field = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = field.get(activity) as ExecutorService
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker.submit {}.get(15, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun unsupportedColdEntryCannotOpenPreviousLocalDraft() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        finishWork()
        field(R.string.draft_title).setText("previous local draft")
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        finishWork()
        val root = activity.noBackupFilesDir.toPath()
        val before = (PreviewDraftLibrary(root).openLegacy().load() as PreviewDraftLoad.Available).record
        val active = PreviewActiveDraftStore(root).read()
        controller.pause().stop().destroy()

        controller = Robolectric.buildActivity(PreviewActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://unsupported.example.invalid/build/another-draft"))).setup()
        finishWork()

        assertEquals("An unsupported external target must not silently open the previous local draft", "",
            field(R.string.draft_title).text.toString())
        assertFalse("An unaccepted incoming target must not authorize a save", button(R.string.save_draft).isEnabled)
        assertEquals(before.baselineIdentity,
            (PreviewDraftLibrary(root).openLegacy().load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(root).read())
    }

    @Test fun unsupportedActionsDataTypesClipsAndSelectorsNeverAcquireLocalSaveAuthority() {
        val (root, before) = launchSaved()
        val active = PreviewActiveDraftStore(root).read()
        val canary = "OUTSIDE_INCOMING_DATA"
        val entries = listOf(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, canary),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/json"),
            Intent("outside.unsupported.ACTION"),
            Intent().setData(Uri.parse("content://outside.invalid/$canary")),
            Intent(Intent.ACTION_MAIN).setType("application/json"),
            Intent(Intent.ACTION_MAIN).apply { clipData = ClipData.newPlainText("incoming", canary) },
            Intent(Intent.ACTION_MAIN).apply { selector = Intent(Intent.ACTION_VIEW, Uri.parse("https://outside.invalid/$canary")) },
        )
        for (entry in entries) {
            coldLaunch(entry)
            assertTrue(shows(R.string.entry_unsupported))
            assertTrue(shows(R.string.entry_choose_local))
            field(R.string.draft_title).setText("unbound pending input")
            assertFalse(button(R.string.save_draft).isEnabled)
            button(R.string.save_draft).performClick()
            finishWork()
            assertFalse(views().filterIsInstance<TextView>().any { it.text.contains(canary) })
            assertFalse(button(R.string.reopen_draft).isEnabled)
            unchanged(root, before, active)
        }
    }

    @Test fun warmUnsupportedEntryPreservesPendingEditorAndRecreationContext() {
        val (root, before) = launchSaved()
        val active = PreviewActiveDraftStore(root).read()
        val original = activity
        field(R.string.draft_title).setText("pending local title")
        field(R.string.context_objective).setText("pending local objective")
        field(R.string.workload_budget).setText("12.3400")
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://outside.invalid/build/other")))
        finishWork()
        assertSame(original, activity)
        assertTrue(shows(R.string.entry_unsupported))
        recreate()
        assertTrue(shows(R.string.entry_unsupported))
        assertEquals("pending local title", field(R.string.draft_title).text.toString())
        assertEquals("pending local objective", field(R.string.context_objective).text.toString())
        assertEquals("12.3400", field(R.string.workload_budget).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        unchanged(root, before, active)
        button(R.string.save_draft).performClick()
        finishWork()
        val after = (PreviewDraftLibrary(root).openLegacy().load() as PreviewDraftLoad.Available).record
        assertEquals("pending local title", after.fields.title)
        assertEquals("pending local objective", after.contextInput.objective)
        assertEquals("12.3400", after.workloadInput.monthlyBudgetAmount?.encoded)
        assertEquals(before.revision + 1, after.revision)
    }

    @Test fun ordinaryColdAndWarmLaunchesKeepTheLocalDraftAndPendingEditor() {
        val (root, before) = launchSaved()
        val active = PreviewActiveDraftStore(root).read()
        for (intent in listOf(Intent(), Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))) {
            coldLaunch(intent)
            assertEquals("saved local title", field(R.string.draft_title).text.toString())
            assertFalse(shows(R.string.entry_unsupported))
            assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP,
                activity.packageManager.getActivityInfo(activity.componentName, 0).launchMode)
            val original = activity
            field(R.string.draft_title).setText("pending launcher input")
            controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra("draftId", "untrusted target")
                .putExtra(Intent.EXTRA_TEXT, "untrusted content"))
            finishWork()
            assertSame(original, activity)
            assertEquals("pending launcher input", field(R.string.draft_title).text.toString())
            assertTrue(button(R.string.save_draft).isEnabled)
            assertFalse(shows(R.string.entry_unsupported))
            unchanged(root, before, active)
        }
    }

    @Test fun blockedCheckpointCannotAdoptTheActiveDraftDuringRecreationOrLauncherRetry() {
        val (root, before) = launchSaved()
        val active = PreviewActiveDraftStore(root).read()
        coldLaunch(Intent(Intent.ACTION_VIEW, Uri.parse("https://outside.invalid/build/missing")))
        field(R.string.draft_title).setText("unbound pending title")
        field(R.string.context_objective).setText("unbound pending objective")
        recreate()
        controller.newIntent(Intent(Intent.ACTION_MAIN))
        finishWork()
        assertTrue(shows(R.string.entry_unsupported))
        assertTrue(shows(R.string.entry_choose_local))
        assertEquals("unbound pending title", field(R.string.draft_title).text.toString())
        assertEquals("unbound pending objective", field(R.string.context_objective).text.toString())
        assertFalse(button(R.string.save_draft).isEnabled)
        assertFalse(button(R.string.reopen_draft).isEnabled)
        unchanged(root, before, active)
    }

    @Test fun explicitLibraryChoiceOpensOnlyTheChosenDraftAndSurvivesRecreation() {
        val (root, before) = launchSaved()
        val library = PreviewDraftLibrary(root)
        val chosen = library.create() as PreviewLibraryCreate.Saved
        val chosenRecord = (library.open(chosen.id).save(chosen.record, PreviewDraftFields("chosen local draft"))
            as PreviewDraftSave.Saved).record
        coldLaunch(Intent(Intent.ACTION_VIEW, Uri.parse("https://outside.invalid/build/unrelated")))
        assertTrue(shows(R.string.entry_unsupported))
        button(R.string.library_open).performClick()
        finishWork()
        val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().contains(chosen.id.value)
        }
        shadowOf(picker).clickOnItem(index)
        val detail = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertFalse(shows(R.string.entry_unsupported))
        assertEquals("chosen local draft", field(R.string.draft_title).text.toString())
        assertEquals(PreviewActiveRead.Selected(chosen.id), PreviewActiveDraftStore(root).read())
        recreate()
        assertFalse(shows(R.string.entry_unsupported))
        assertEquals("chosen local draft", field(R.string.draft_title).text.toString())
        field(R.string.draft_title).setText("chosen draft after recreation")
        assertTrue(button(R.string.save_draft).isEnabled)
        button(R.string.save_draft).performClick()
        finishWork()
        val after = (library.open(chosen.id).load() as PreviewDraftLoad.Available).record
        assertEquals(chosenRecord.revision + 1, after.revision)
        assertEquals("chosen draft after recreation", after.fields.title)
        assertEquals(before.baselineIdentity, (library.openLegacy().load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }

    @Test fun explicitNewDraftRecoveryCreatesANewIdentityWithoutReplacingThePriorDraft() {
        val (root, before) = launchSaved()
        coldLaunch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "outside input"))
        assertTrue(shows(R.string.entry_unsupported))
        button(R.string.library_new).performClick()
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        val selected = PreviewActiveDraftStore(root).read() as PreviewActiveRead.Selected
        assertNotEquals(PreviewDraftId("guest-draft"), selected.id)
        assertFalse(shows(R.string.entry_unsupported))
        assertEquals("", field(R.string.draft_title).text.toString())
        field(R.string.draft_title).setText("deliberate new local draft")
        button(R.string.save_draft).performClick()
        finishWork()
        val library = PreviewDraftLibrary(root)
        assertEquals("deliberate new local draft", (library.open(selected.id).load() as PreviewDraftLoad.Available).record.fields.title)
        assertEquals(before.baselineIdentity, (library.openLegacy().load() as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
