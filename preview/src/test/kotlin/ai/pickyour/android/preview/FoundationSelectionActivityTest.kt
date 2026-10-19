package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Native controls and canonical storage in simulation; no installed-process qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FoundationSelectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val kinds = listOf("workload", "deployment_mode")

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
    private fun field(root: View, label: Int): EditText {
        val caption = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return root.findViewById(caption.labelFor)
    }
    private fun field(dialog: AlertDialog, label: Int) = field(dialog.window!!.decorView, label)
    private fun checkbox(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<CheckBox>().single()
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for explicit foundation selection state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId").apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun library() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
    private fun adapter() = library().open(currentId())
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun settings(selection: LocalSelection) = JSONObject(PreviewSelectionForms.settingsText(selection))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun save() {
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.draft_saved)
        } }
        finishWork()
    }
    private fun createEmpty(): PreviewStoredDraft {
        val previous = currentId()
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { currentId() != previous && button(R.string.component_choose_catalog).isEnabled }
        finishWork()
        return loaded().also { assertTrue(it.components.selections.isEmpty()) }
    }
    private fun select(picker: AlertDialog, matches: (String) -> Boolean): AlertDialog {
        val index = (0 until picker.listView.adapter.count).single { matches(picker.listView.adapter.getItem(it).toString()) }
        shadowOf(picker).clickOnItem(index)
        await { dialog() !== picker && dialog().isShowing }
        return dialog()
    }
    private fun choices(kind: String): AlertDialog {
        await { button(R.string.component_choose_catalog).isEnabled }
        button(R.string.component_choose_catalog).performClick()
        return select(dialog()) { it.startsWith("${componentTokenForLookup(activity, kind)} ·") }
    }
    private fun addForm(kind: String) = select(choices(kind)) { it == activity.getString(R.string.component_add) }
    private fun action(selection: LocalSelection, label: Int): AlertDialog {
        button(R.string.component_manage).performClick()
        val actions = select(dialog()) { it.endsWith(" · ${componentTokenForLookup(activity, selection.id)}") }
        return select(actions) { it == activity.getString(label) }
    }
    private fun commit(form: AlertDialog, previous: PreviewStoredDraft = loaded()): PreviewStoredDraft {
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !form.isShowing && loaded().revision == previous.revision + 1 }
        finishWork()
        return loaded()
    }
    private fun add(kind: String, role: String = "primary", enabled: Boolean = false, settings: String = "{}"): PreviewStoredDraft {
        val form = addForm(kind)
        field(form, R.string.component_role).setText(role)
        field(form, R.string.component_settings).setText(settings)
        checkbox(form).isChecked = enabled
        return commit(form)
    }
    private fun closeDialog(form: AlertDialog) {
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing }
    }
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
    private fun reviewPending(): AlertDialog {
        assertEquals(View.VISIBLE, button(R.string.component_recovery_review).visibility)
        button(R.string.component_recovery_review).performClick()
        return dialog()
    }

    @Test fun newEmptyDraftKeepsOneExplicitWorkloadAcrossRecreationWithoutInventedInputs() {
        val empty = createEmpty()
        val id = currentId()
        val pointer = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        val form = addForm("workload")
        assertEquals("", field(form, R.string.component_role).text.toString())
        assertEquals("{}", field(form, R.string.component_settings).text.toString())
        assertFalse(checkbox(form).isChecked)
        assertEquals(empty.baselineIdentity, loaded().baselineIdentity)
        field(form, R.string.component_role).setText("unassigned")
        val saved = commit(form, empty)
        val selection = saved.components.selections.single()
        assertEquals("workload", selection.kind)
        assertEquals("unassigned", selection.role)
        assertFalse(selection.enabled)
        assertEquals(0, settings(selection).length())
        assertEquals(empty.fields, saved.fields)
        assertEquals(empty.contextInput, saved.contextInput)
        assertEquals(DraftWorkload(), saved.workloadInput)
        assertTrue(saved.components.links.isEmpty())
        assertEquals(pointer, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        val bytes = disk()
        recreate()
        assertEquals(id, currentId())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(listOf(selection), loaded().components.selections)
        assertEquals(bytes, disk())
        assertEquals(View.GONE, button(R.string.component_recovery_review).visibility)
    }

    @Test fun workloadValidationRetainsRawInvalidInputAndExactOverridesWithoutCopyingOverallAssumptions() {
        field(activity.window.decorView, R.string.workload_requests).setText("17")
        field(activity.window.decorView, R.string.workload_region).setText("entered-region")
        save()
        val before = loaded()
        val form = addForm("workload")
        field(form, R.string.component_role).setText("primary")
        for (raw in listOf("{\"concurrency\":0}", "{\"requests_per_day\":-1}", "{\"avg_input_tokens\":\"2\"}")) {
            field(form, R.string.component_settings).setText(raw)
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.component_invalid)
            } }
            assertEquals(raw, field(form, R.string.component_settings).text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        }
        val raw = "{\"concurrency\":9007199254740993,\"requests_per_day\":0,\"avg_output_tokens\":0}"
        field(form, R.string.component_settings).setText(raw)
        val saved = commit(form)
        val selection = saved.components.selections.single()
        assertEquals(BigInteger("9007199254740993"), BigInteger(settings(selection).get("concurrency").toString()))
        assertEquals(0, settings(selection).getInt("requests_per_day"))
        assertFalse(settings(selection).has("avg_input_tokens"))
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals("17", field(activity.window.decorView, R.string.workload_requests).text.toString())
        val configure = action(selection, R.string.component_configure)
        assertEquals(raw, field(configure, R.string.component_settings).text.toString())
        field(configure, R.string.component_settings).setText("{}")
        val cleared = commit(configure)
        assertEquals(0, settings(cleared.components.selections.single()).length())
        assertEquals(selection.id, cleared.components.selections.single().id)
        assertEquals(before.workloadInput, cleared.workloadInput)
    }

    @Test fun deploymentChoiceUsesExactCatalogItemAndEmptySettingsWithOneExplicitAcknowledgment() {
        save()
        val before = loaded()
        val catalog = activity.assets.open("catalog.json").use { requireNotNull(PreviewCatalogBrowser.read(it.readBytes())) }
        val item = catalog.items.single { it.kind == "deployment_mode" }
        val form = addForm("deployment_mode")
        field(form, R.string.component_role).setText("primary")
        checkbox(form).isChecked = true
        field(form, R.string.component_settings).setText("{\"mode\":\"api\"}")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.component_invalid)
        } }
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        field(form, R.string.component_settings).setText("{}")
        val retained = form.getButton(AlertDialog.BUTTON_POSITIVE)
        retained.performClick(); retained.performClick()
        await { !form.isShowing && loaded().revision == before.revision + 1 }
        retained.performClick(); finishWork()
        val saved = loaded()
        val selected = saved.components.selections.single()
        assertEquals(item.id, selected.catalogItemId)
        assertEquals(item.version, selected.catalogItemVersion)
        assertEquals("deployment_mode", selected.kind)
        assertEquals("primary", selected.role)
        assertTrue(selected.enabled)
        assertEquals(0, settings(selected).length())
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.fields, saved.fields)
        val replacement = select(choices("deployment_mode")) { it.startsWith(activity.getString(R.string.component_replace, "")) }
        replacement.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { !replacement.isShowing }
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(selected, loaded().components.selections.single())
    }

    @Test fun explicitFoundationEnableDisableRoleAndRemovalNeverPromoteAnotherSelection() {
        for (kind in kinds) {
            createEmpty()
            val first = add(kind, enabled = true)
            val primary = first.components.selections.single()
            val pair = add(kind, role = "backup")
            val backup = pair.components.selections.single { it.role == "backup" }
            val rejected = action(backup, R.string.component_enable)
            rejected.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { rejected.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(rejected.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString().contains(SelectionRejection.ACTIVE_CARDINALITY_EXCEEDED.name)
            } }
            assertEquals(pair.baselineIdentity, loaded().baselineIdentity)
            closeDialog(rejected)
            commit(action(primary, R.string.component_disable))
            assertFalse(loaded().components.selections.any { it.enabled })
            commit(action(backup, R.string.component_enable))
            assertEquals("backup", loaded().components.selections.single { it.enabled }.role)
            val role = action(backup, R.string.component_edit_role)
            field(role, R.string.component_role).setText("primary")
            role.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { role.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(role.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString().contains(SelectionRejection.DUPLICATE_KIND_ROLE.name)
            } }
            assertEquals("backup", loaded().components.selections.single { it.enabled }.role)
            closeDialog(role)
            val removed = commit(action(primary, R.string.component_remove))
            assertEquals(listOf("backup"), removed.components.selections.map { it.role })
            assertEquals(backup.id, removed.components.selections.single().id)
            assertTrue(removed.components.selections.single().enabled)
        }
    }

    @Test fun pendingFoundationFormsRetainKindAndInputAcrossParcelRecreationWithoutOldConsent() {
        for (kind in kinds) {
            val before = createEmpty()
            val form = addForm(kind)
            val raw = "{\"uncommitted\":\"$kind"
            field(form, R.string.component_role).setText("Pending Role")
            field(form, R.string.component_settings).setText(raw)
            checkbox(form).isChecked = true
            val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
            var selectionId: String? = null
            recreate { state ->
                val pending = (ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form
                assertEquals(kind, pending.kind)
                assertEquals(raw, pending.settings)
                selectionId = pending.selectionId
            }
            assertFalse(form.isShowing)
            val restored = reviewPending()
            assertEquals(raw, field(restored, R.string.component_settings).text.toString())
            assertEquals("Pending Role", field(restored, R.string.component_role).text.toString())
            assertTrue(checkbox(restored).isChecked)
            retired.performClick(); finishWork()
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            field(restored, R.string.component_role).setText("explicit")
            field(restored, R.string.component_settings).setText("{}")
            val saved = commit(restored)
            assertEquals(selectionId, saved.components.selections.single().id)
            assertEquals(kind, saved.components.selections.single().kind)
            assertEquals("explicit", saved.components.selections.single().role)
            assertEquals(before.fields, saved.fields)
            assertEquals(before.workloadInput, saved.workloadInput)
        }
    }

    @Test fun canceledAndCatalogStaleFoundationButtonsCannotSaveOrChooseAnotherSnapshot() {
        save()
        for (kind in kinds) {
            val canceled = addForm(kind)
            field(canceled, R.string.component_role).setText("primary")
            val retired = canceled.getButton(AlertDialog.BUTTON_POSITIVE)
            closeDialog(canceled)
            val bytes = disk()
            retired.performClick(); finishWork()
            assertEquals(bytes, disk())
        }
        val form = addForm("deployment_mode")
        field(form, R.string.component_role).setText("primary")
        val before = disk()
        val document = activity.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val old = requireNotNull(PreviewCatalogBrowser.read(document.toString().toByteArray()))
        document.put("revision", document.getInt("revision") + 1)
        val changed = requireNotNull(PreviewCatalogBrowser.read(document.toString().toByteArray()))
        assertNotEquals(old.hash, changed.hash)
        views().filterIsInstance<SelectionEditSection>().single().catalog(changed.items, changed.hash)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertTrue(form.isShowing)
        assertEquals(before, disk())
        assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun newerSavedHeadKeepsRecoveredFoundationInputReadOnlyInsteadOfRebasingIt() {
        save()
        val before = loaded()
        val form = addForm("workload")
        val raw = "{\"concurrency\":2}"
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText(raw)
        finishWork()
        val newer = (adapter().save(before, before.fields.copy(title = "other writer")) as PreviewDraftSave.Saved).record
        val bytes = disk()
        recreate()
        val review = reviewPending()
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == raw && it.isTextSelectable })
        assertNotEquals(View.VISIBLE, review.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(newer.baselineIdentity, loaded().baselineIdentity)
        assertEquals(bytes, disk())
        assertTrue(loaded().components.selections.isEmpty())
    }

    @Test fun savingFoundationCheckpointCannotReplayAfterTheOriginalWriteIsAcknowledged() {
        save()
        val before = loaded()
        val form = addForm("workload")
        field(form, R.string.component_role).setText("primary")
        field(form, R.string.component_settings).setText("{\"requests_per_day\":0}")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            recreate { state ->
                assertTrue((ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form.wasSaving)
                release.countDown()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val saved = loaded()
        val review = reviewPending()
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "{\"requests_per_day\":0}" })
        assertEquals(0, settings(saved.components.selections.single()).getInt("requests_per_day"))
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(before.workloadInput, saved.workloadInput)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsFoundationGuidanceFieldsAndConfirmationReachable() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        save()
        for (kind in kinds) {
            val form = addForm(kind)
            val role = field(form, R.string.component_role)
            role.setText("primary")
            val settings = field(form, R.string.component_settings)
            settings.setText(if (kind == "workload") "{\"concurrency\":1}" else "{}")
            val root = form.window!!.decorView
            val density = activity.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val height = (640 * density).toInt()
            root.layoutDirection = View.LAYOUT_DIRECTION_RTL
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
            for (control in views(root).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }) {
                assertTrue(control.height >= (48 * density).toInt())
                assertTrue(control.width >= (48 * density).toInt())
                reachable(control, root)
            }
            reachable(role, root); reachable(settings, root)
            val guidance = if (kind == "workload") R.string.component_workload_guidance else R.string.component_deployment_guidance
            val text = views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(guidance) }
            assertTrue(text.isShown)
            for (view in views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }) {
                val layout = requireNotNull(view.layout)
                assertTrue(layout.lineCount > 0)
                assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            }
            val saved = commit(form)
            assertEquals("primary", saved.components.selections.single { it.kind == kind }.role)
        }
    }

    private fun reachable(control: View, root: View) {
        control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
        val bounds = Rect(0, 0, control.width, control.height)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            assertTrue("Foundation control must be reachable", Rect().also(parent::getDrawingRect).contains(bounds))
            child = parent
        }
    }
}
