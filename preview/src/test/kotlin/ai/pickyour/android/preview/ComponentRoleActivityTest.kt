package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual Views, worker and durable-store interaction in simulation; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ComponentRoleActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private lateinit var seededSelectionJson: String
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        assertTrue(adapter().load() is PreviewDraftLoad.Missing)
    }
    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun hasText(label: Int) = views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
    private fun field(dialog: AlertDialog, label: Int): EditText {
        val caption = views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return dialog.findViewById(caption.labelFor)
    }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit role editing state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
    }
    private fun seed(large: Boolean = false, secondary: Boolean = false): PreviewStoredDraft {
        button(R.string.save_draft).performClick()
        await { hasText(R.string.draft_saved) && !button(R.string.save_draft).isEnabled }
        finishWork()
        val fixture = activity.assets.open("build.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }.getJSONArray("selections")
        fun node(kind: String) = (0 until fixture.length()).map { fixture.getJSONObject(it) }.single { it.getString("kind") == kind }
        fun parsed(value: JSONObject) = LocalSelection.fromJson(value.toString().toByteArray(Charsets.UTF_8))
        val modelNode = node("model").put("enabled", false).put("notes", "retained notes")
            .put("extensions", JSONObject().put("x_retained", JSONObject().put("empty", "").put("unknown", JSONObject.NULL)))
        if (large) modelNode.put("settings", JSONObject().put("credential_ref", "secret://" + "opaque/".repeat(1_300))
            .put("weights_uri", "https://never-resolve.invalid/" + "retained/".repeat(1_000)))
        seededSelectionJson = modelNode.toString()
        val model = parsed(modelNode)
        val provider = parsed(node("model_provider"))
        val values = listOf(model, provider) + if (secondary) listOf(parsed(JSONObject(modelNode.toString()).put("id", "sel_secondary").put("role", "secondary"))) else emptyList()
        val link = LocalSelectionLink.fromJson(JSONObject().put("id", "link.retained").put("from_selection_id", model.id)
            .put("to_selection_id", provider.id).put("relation", "uses").put("required", false).toString().toByteArray(Charsets.UTF_8))
        val old = (store().load() as DraftLoad.Available).draft
        store().save(old.version, old.content.withComponents(DraftSelections(values, listOf(link))), old.metadata + ("role-test" to "retained")) as DraftSave.Saved
        reopen()
        await { button(R.string.component_manage).isEnabled }
        return loaded()
    }
    private fun action(label: Int, id: String = "sel_model"): AlertDialog {
        button(R.string.component_manage).performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single { picker.listView.adapter.getItem(it).toString().endsWith(" · $id") }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        val actions = dialog()
        val action = (0 until actions.listView.adapter.count).single { actions.listView.adapter.getItem(it).toString() == activity.getString(label) }
        shadowOf(actions).clickOnItem(action)
        await { dialog() !== actions && dialog().isShowing }
        return dialog()
    }
    private fun roleForm() = action(R.string.component_edit_role)
    private fun roundTrip(state: Bundle): Bundle {
        val written = Parcel.obtain()
        val read = Parcel.obtain()
        return try {
            written.writeBundle(state)
            val bytes = written.marshall()
            read.unmarshall(bytes, 0, bytes.size); read.setDataPosition(0)
            requireNotNull(read.readBundle(javaClass.classLoader))
        } finally { written.recycle(); read.recycle() }
    }
    private fun recreate(afterCheckpoint: (Bundle) -> Unit = {}) {
        val state = Bundle()
        controller.pause().saveInstanceState(state)
        afterCheckpoint(state)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(roundTrip(state)).start().resume().visible()
        finishWork()
    }
    private fun review(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }
    private fun assertReadOnly(window: AlertDialog, raw: String) {
        assertTrue(window.isShowing)
        assertTrue(views(window.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(window.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == raw && it.isTextSelectable })
        assertNotEquals(View.VISIBLE, window.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
    }

    @Test fun roleEditPreservesLargeSettingsSelectionIdentityAndLinksAcrossReopen() {
        val before = seed(large = true)
        val selected = before.components.selections.first()
        assertTrue(PreviewSelectionForms.settingsText(selected).length > PreviewSelectionForms.MAX_SETTINGS_TEXT)
        val settings = action(R.string.component_configure)
        assertEquals(activity.getString(R.string.component_large_settings), shadowOf(settings).message.toString())
        settings.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); await { !settings.isShowing }
        val role = roleForm()
        assertEquals(selected.role, field(role, R.string.component_role).text.toString())
        assertEquals(1, views(role.window!!.decorView).filterIsInstance<EditText>().size)
        field(role, R.string.component_role).setText("candidate")
        role.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !role.isShowing && loaded().revision == before.revision + 1 }
        finishWork()
        val after = loaded()
        val expectedSelection = LocalSelection.fromJson(JSONObject(seededSelectionJson).put("role", "candidate")
            .toString().toByteArray(Charsets.UTF_8))
        assertEquals(expectedSelection, after.components.selections.first())
        assertEquals(before.components.selections.drop(1), after.components.selections.drop(1))
        assertEquals(before.components.links, after.components.links)
        assertEquals(before.fields, after.fields)
        assertEquals(5, (store().load() as DraftLoad.Available).draft.localFormatVersion)
        assertEquals(SelectionAction.CONFIGURE_ROLE, after.history.last().selectionChange!!.action)
        assertEquals(listOf("components.selection.role"), after.compareRevisions(before.revision, after.revision)!!.changes.map { it.field })
        reopen()
        assertEquals(after.baselineIdentity, loaded().baselineIdentity)
        assertEquals(after.components, loaded().components)
    }

    @Test fun duplicateAndInvalidRolesRemainVisibleUntilOneCorrectedExplicitCommit() {
        val before = seed(secondary = true)
        val role = roleForm()
        val input = field(role, R.string.component_role)
        val positive = role.getButton(AlertDialog.BUTTON_POSITIVE)
        input.setText("secondary"); positive.performClick(); finishWork()
        assertTrue(role.isShowing && positive.isEnabled)
        assertEquals("secondary", input.text.toString())
        assertTrue(views(role.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("DUPLICATE_KIND_ROLE") })
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        for (raw in listOf("", " candidate", "Candidate", "a".repeat(81))) {
            input.setText(raw); positive.performClick(); finishWork()
            assertTrue(role.isShowing && positive.isEnabled)
            assertTrue(hasText(R.string.component_invalid))
            assertEquals(raw, input.text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        }
        input.setText("candidate"); positive.performClick(); positive.performClick()
        await { !role.isShowing && loaded().revision == before.revision + 1 }
        positive.performClick(); finishWork()
        assertEquals(before.revision + 1, loaded().revision)
        assertEquals("candidate", loaded().components.selections.first().role)
        assertEquals(before.components.selections.drop(1), loaded().components.selections.drop(1))
    }

    @Test fun noOpCancellationAndRetiredControlsCannotConsumeARevisionOrReplaceNewInput() {
        val before = seed()
        val snapshot = disk()
        val noOp = roleForm()
        val oldPositive = noOp.getButton(AlertDialog.BUTTON_POSITIVE)
        oldPositive.performClick()
        await { !noOp.isShowing && hasText(R.string.component_noop) }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val canceled = roleForm()
        field(canceled, R.string.component_role).setText("canceled")
        val canceledPositive = canceled.getButton(AlertDialog.BUTTON_POSITIVE)
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); await { !canceled.isShowing }
        val retainedManage = button(R.string.component_manage)
        val fresh = roleForm()
        field(fresh, R.string.component_role).setText("fresh pending")
        oldPositive.performClick(); canceledPositive.performClick(); retainedManage.performClick(); finishWork()
        assertSame(fresh, dialog())
        assertEquals("fresh pending", field(fresh, R.string.component_role).text.toString())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals(snapshot, disk())
    }

    @Test fun repeatedRecreationKeepsExactRoleInputAndRequiresFreshConfirmation() {
        val before = seed()
        val form = roleForm()
        val raw = "Candidate\u2028pending\u0000\uD83D\uDE00"
        field(form, R.string.component_role).setText(raw)
        assertFalse(field(form, R.string.component_role).isSaveEnabled)
        val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
        recreate { state ->
            val checkpoint = ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending
            assertEquals(ComponentFormCheckpoint.ROLE, checkpoint.form.mode)
            assertEquals(raw, checkpoint.form.role)
            assertEquals("", checkpoint.form.settings)
            assertEquals(before.baselineIdentity, checkpoint.form.baseline)
        }
        val first = review()
        assertEquals(raw, field(first, R.string.component_role).text.toString())
        retired.performClick(); finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        recreate()
        val second = review()
        assertFalse(first.isShowing)
        assertEquals(raw, field(second, R.string.component_role).text.toString())
        field(second, R.string.component_role).setText("candidate")
        second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !second.isShowing && loaded().revision == before.revision + 1 }
        recreate()
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertEquals(before.revision + 1, loaded().revision)
        assertEquals("candidate", loaded().components.selections.first().role)
    }

    @Test fun changedSavedHeadKeepsRecoveredRoleReadOnlyEvenAfterExplicitReopen() {
        val before = seed()
        val form = roleForm()
        field(form, R.string.component_role).setText("pending_candidate")
        finishWork()
        val other = (adapter().save(before, PreviewDraftFields("another writer")) as PreviewDraftSave.Saved).record
        recreate()
        assertReadOnly(review(), "pending_candidate")
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); await { !dialog().isShowing }
        reopen()
        assertReadOnly(review(), "pending_candidate")
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); await { !dialog().isShowing }
        button(R.string.component_recovery_discard).performClick(); finishWork()
        assertEquals(other.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before.components, loaded().components)
        assertTrue(button(R.string.component_manage).isEnabled)
    }

    @Test fun differentDraftOrMissingSelectionCannotAdoptTheMatchingCurrentBaseline() {
        val before = seed()
        val mutations: List<(Bundle) -> Unit> = listOf(
            { it.putString("draftId", "draft-00000000-0000-0000-0000-000000000001") },
            { it.putString("selectionId", "missing_selection") },
        )
        for (mutate in mutations) {
            val form = roleForm()
            field(form, R.string.component_role).setText("pending_candidate")
            recreate { state -> mutate(state.getBundle(ComponentFormRecovery.KEY)!!) }
            assertReadOnly(review(), "pending_candidate")
            dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); await { !dialog().isShowing }
            button(R.string.component_recovery_discard).performClick(); finishWork()
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(before.components, loaded().components)
        }
    }

    @Test fun inFlightRoleCheckpointNeverReplaysAfterTheOriginalWriteCompletes() {
        val before = seed()
        val form = roleForm()
        field(form, R.string.component_role).setText("candidate")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(field(form, R.string.component_role).isEnabled)
            assertFalse(form.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled)
            recreate { state ->
                assertTrue((ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form.wasSaving)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val saved = loaded()
        assertEquals(before.revision + 1, saved.revision)
        assertReadOnly(review(), "candidate")
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); await { !dialog().isShowing }
        reopen()
        assertReadOnly(review(), "candidate")
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun oversizedRoleRecoveryKeepsAnUnavailableMarkerWithoutSavingAPrefix() {
        val before = seed()
        val form = roleForm()
        val raw = "candidate" + " ".repeat(ComponentFormCheckpoint.MAX_RAW_CHARACTERS)
        field(form, R.string.component_role).setText(raw)
        assertEquals(raw, field(form, R.string.component_role).text.toString())
        recreate { state ->
            assertSame(ComponentFormRecovery.Unavailable, ComponentFormRecovery.read(roundTrip(state)))
            assertEquals(setOf("version", "unavailable"), state.getBundle(ComponentFormRecovery.KEY)!!.keySet())
        }
        assertTrue(views().filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString() == activity.getString(R.string.component_recovery_unavailable) })
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
        assertFalse(button(R.string.component_manage).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        button(R.string.component_recovery_discard).performClick(); finishWork()
        assertTrue(button(R.string.component_manage).isEnabled)
        assertEquals(before.components, loaded().components)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsRoleInputAndCommitReachable() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        seed()
        val form = roleForm()
        val input = field(form, R.string.component_role)
        val raw = "a".repeat(50)
        input.setText(raw)
        val root = form.window!!.decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertEquals(raw, input.text.toString())
        val controls = views(root).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
        assertEquals(2, controls.size)
        for (control in controls) {
            assertTrue(control.height >= (48 * density).toInt())
            assertTrue(control.width >= (48 * density).toInt())
            reachable(control, root)
            completeText(control)
        }
        reachable(input, root)
        views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }.forEach(::completeText)
        val before = loaded()
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        assertEquals(raw, loaded().components.selections.first().role)
    }

    private fun completeText(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue(layout.lineCount > 0)
        assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) assertEquals("Complete visible label: ${view.text}", 0, layout.getEllipsisCount(line))
    }
    private fun reachable(control: View, root: View) {
        control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
        val bounds = Rect(0, 0, control.width, control.height)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            assertTrue("Role control must be reachable", Rect().also(parent::getDrawingRect).contains(bounds))
            child = parent
        }
    }
}
