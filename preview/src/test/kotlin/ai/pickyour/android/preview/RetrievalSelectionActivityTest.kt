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
class RetrievalSelectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val kinds = listOf("data_source", "knowledge_base", "embedding_model", "vector_database", "rag_pipeline")
    private fun minimum(kind: String) = if (kind == "vector_database") "{\"index_dimension\":1,\"similarity_metric\":\"cosine\"}" else "{}"

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
        fail("Timed out waiting for explicit retrieval selection state")
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun finishWork() { repeat(3) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId").apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun library() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
    private fun adapter() = library().open(currentId())
    private fun loaded() = (readDraftAfterOwnedWork(activity, ::adapter) as PreviewDraftLoad.Available).record
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
    private fun add(kind: String, role: String = "primary", enabled: Boolean = false, settings: String = minimum(kind)): PreviewStoredDraft {
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

    private fun setT3(label: Int): PreviewStoredDraft {
        val before = loaded()
        val tier = views(views().filterIsInstance<OptionalTierSection>().single()).filterIsInstance<Button>()
            .single { it.text.toString().startsWith("T3:") }
        assertTrue(tier.isEnabled)
        tier.performClick()
        val confirmation = select(dialog()) { it == activity.getString(label) }
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { tier.isEnabled && tier.text.toString().startsWith("T3: ${activity.getString(label)};") &&
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } }
        finishWork()
        return loaded().also { assertEquals(before.revision + 1, it.revision) }
    }

    @Test fun allRetrievalKindsExposeExplicitInputAndReopenWithoutCreatingAnotherComponent() {
        val empty = createEmpty()
        val id = currentId()
        val pointer = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        val catalog = activity.assets.open("catalog.json").use { requireNotNull(PreviewCatalogBrowser.read(it.readBytes())) }
        for ((index, kind) in kinds.withIndex()) {
            val before = loaded()
            val form = addForm(kind)
            assertEquals("", field(form, R.string.component_role).text.toString())
            assertEquals("{}", field(form, R.string.component_settings).text.toString())
            assertFalse(checkbox(form).isChecked)
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            field(form, R.string.component_role).setText("entered")
            field(form, R.string.component_settings).setText(minimum(kind))
            val saved = commit(form)
            val selection = saved.components.selections.single { it.kind == kind }
            val item = catalog.items.single { it.kind == kind }
            assertEquals(item.id, selection.catalogItemId)
            assertEquals(item.version, selection.catalogItemVersion)
            assertEquals("entered", selection.role)
            assertFalse(selection.enabled)
            assertEquals(minimum(kind), PreviewSelectionForms.settingsText(selection))
            assertEquals(index + 1, saved.components.selections.size)
            assertEquals(empty.fields, saved.fields)
            assertEquals(empty.contextInput, saved.contextInput)
            assertEquals(empty.workloadInput, saved.workloadInput)
            assertEquals(empty.optionalTiers, saved.optionalTiers)
            assertTrue(saved.components.links.isEmpty())
        }
        val saved = loaded()
        val bytes = disk()
        recreate()
        assertEquals(id, currentId())
        assertEquals(pointer, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(saved.components, loaded().components)
        assertEquals(bytes, disk())
    }

    @Test fun requiredVectorSettingsRejectInvalidInputWithoutSavingOrTruncatingAnEnteredValue() {
        save()
        val before = loaded()
        val bytes = disk()
        val form = addForm("vector_database")
        field(form, R.string.component_role).setText("entered")
        val invalid = listOf("{}", "{\"index_dimension\":1}", "{\"similarity_metric\":\"cosine\"}",
            "{\"index_dimension\":0,\"similarity_metric\":\"cosine\"}",
            "{\"index_dimension\":1,\"similarity_metric\":\"COSINE\"}",
            "{\"index_dimension\":1,\"index_dimension\":2,\"similarity_metric\":\"dot\"}",
            "{\"index_dimension\":1,\"similarity_metric\":\"dot\",\"credential_ref\":\"raw-token\"}")
        for (raw in invalid) {
            field(form, R.string.component_settings).setText(raw)
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.component_invalid)
            } }
            assertEquals(raw, field(form, R.string.component_settings).text.toString())
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(bytes, disk())
        }
        val raw = "{\"index_dimension\":999999999999999999999999,\"similarity_metric\":\"euclidean\",\"region\":\"unmeasured\",\"index_name\":\" exact name \"}"
        field(form, R.string.component_settings).setText(raw)
        val saved = commit(form)
        assertEquals(raw, PreviewSelectionForms.settingsText(saved.components.selections.single()))
        assertEquals(before.optionalTiers, saved.optionalTiers)
        assertTrue(saved.components.links.isEmpty())
        val configure = action(saved.components.selections.single(), R.string.component_configure)
        assertEquals(raw, field(configure, R.string.component_settings).text.toString())
        closeDialog(configure)
    }

    @Test fun retrievalSettingsAndComponentStatesSurviveExplicitTierDisableReopenAndReenable() {
        createEmpty()
        val entered = mapOf(
            "data_source" to "{\"connector_secret_ref\":\"secret://entered/source\",\"refresh_minutes\":9007199254740993,\"enabled\":false}",
            "knowledge_base" to "{\"chunk_size_tokens\":1,\"chunk_overlap_tokens\":9,\"refresh_minutes\":1,\"acl_enforced\":false}",
            "embedding_model" to "{\"credential_ref\":\"secret://entered/embedding\",\"output_dimensions\":9007199254740993,\"region\":\"exact \\n region\"}",
            "vector_database" to "{\"index_dimension\":1,\"similarity_metric\":\"dot\",\"region\":\"unknown\",\"index_name\":\" entered index \",\"credential_ref\":\"secret://entered/vector\"}",
            "rag_pipeline" to "{\"top_k\":9007199254740993,\"max_chunks\":1,\"rerank_enabled\":false}",
        )
        for (kind in kinds) add(kind, enabled = true, settings = entered.getValue(kind))
        val initial = loaded()
        assertEquals(Activation.DISABLED, initial.optionalTiers.getValue(OptionalTier.T3).activation)
        assertTrue(initial.components.selections.all { it.enabled })
        assertFalse(settings(initial.components.selections.single { it.kind == "data_source" }).getBoolean("enabled"))
        assertEquals(BigInteger("9007199254740993"), BigInteger(settings(initial.components.selections.first()).get("refresh_minutes").toString()))
        for (label in listOf(R.string.tier_enabled, R.string.tier_disabled)) {
            val saved = setT3(label)
            assertEquals(initial.components, saved.components)
            assertEquals(initial.fields, saved.fields)
            assertEquals(initial.workloadInput, saved.workloadInput)
        }
        val disabled = loaded()
        val bytes = disk()
        recreate()
        assertEquals(bytes, disk())
        assertEquals(disabled.baselineIdentity, loaded().baselineIdentity)
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T3).activation)
        val reenabled = setT3(R.string.tier_enabled)
        assertEquals(Activation.ENABLED, reenabled.optionalTiers.getValue(OptionalTier.T3).activation)
        assertEquals(initial.components, reenabled.components)
        for (value in reenabled.components.selections) assertEquals(entered.getValue(value.kind), PreviewSelectionForms.settingsText(value))
        val source = reenabled.components.selections.first()
        val confirmation = action(source, R.string.component_disable)
        assertTrue(views(confirmation.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(activity.getString(R.string.component_retrieval_intent_guidance))
        })
        val changed = commit(confirmation)
        assertFalse(changed.components.selections.first().enabled)
        assertFalse(settings(changed.components.selections.first()).getBoolean("enabled"))
        assertEquals(Activation.ENABLED, changed.optionalTiers.getValue(OptionalTier.T3).activation)
        assertEquals(initial.components.selections.drop(1), changed.components.selections.drop(1))
    }

    @Test fun severalEnabledRetrievalRolesCoexistWhileDuplicateRolesAndRemovalPreserveOtherSelections() {
        createEmpty()
        val first = add("knowledge_base", role = "source_a", enabled = true, settings = "{\"acl_enforced\":false}")
            .components.selections.single()
        val pair = add("knowledge_base", role = "source_b", enabled = true, settings = "{\"chunk_size_tokens\":7}")
        val second = pair.components.selections.last()
        assertEquals(2, pair.components.selections.count { it.enabled })
        val duplicate = action(second, R.string.component_edit_role)
        field(duplicate, R.string.component_role).setText("source_a")
        duplicate.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { duplicate.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(duplicate.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(SelectionRejection.DUPLICATE_KIND_ROLE.name)
        } }
        assertEquals(pair.baselineIdentity, loaded().baselineIdentity)
        closeDialog(duplicate)
        commit(action(first, R.string.component_disable))
        val removed = commit(action(first, R.string.component_remove))
        assertEquals(listOf(second), removed.components.selections)
        assertEquals("source_b", removed.components.selections.single().role)
        assertTrue(removed.components.selections.single().enabled)
        assertEquals(pair.optionalTiers, removed.optionalTiers)
    }

    @Test fun pendingRetrievalFormsKeepAllFiveKindsAndExactInputAcrossParcelRecreationWithoutOldConsent() {
        for (kind in kinds) {
            val before = createEmpty()
            val form = addForm(kind)
            val raw = "{\"pending\":\"$kind"
            field(form, R.string.component_role).setText("Pending Role")
            field(form, R.string.component_settings).setText(raw)
            checkbox(form).isChecked = true
            val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
            var selectionId: String? = null
            recreate { state ->
                val checkpoint = (ComponentFormRecovery.read(roundTrip(state)) as ComponentFormRecovery.Pending).form
                assertEquals(kind, checkpoint.kind)
                assertEquals(raw, checkpoint.settings)
                selectionId = checkpoint.selectionId
            }
            val restored = reviewPending()
            assertEquals(raw, field(restored, R.string.component_settings).text.toString())
            assertEquals("Pending Role", field(restored, R.string.component_role).text.toString())
            assertTrue(checkbox(restored).isChecked)
            retired.performClick(); finishWork()
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            field(restored, R.string.component_role).setText("entered")
            field(restored, R.string.component_settings).setText(minimum(kind))
            val saved = commit(restored)
            assertEquals(selectionId, saved.components.selections.single().id)
            assertEquals(kind, saved.components.selections.single().kind)
            assertEquals(before.optionalTiers, saved.optionalTiers)
        }
    }

    @Test fun canceledAndCatalogStaleRetrievalControlsCannotSaveOrSubstituteAnotherSnapshot() {
        save()
        for (kind in kinds) {
            val form = addForm(kind)
            field(form, R.string.component_role).setText("entered")
            field(form, R.string.component_settings).setText(minimum(kind))
            val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
            closeDialog(form)
            val bytes = disk()
            retired.performClick(); finishWork()
            assertEquals(bytes, disk())
        }
        val form = addForm("vector_database")
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText(minimum("vector_database"))
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

    @Test fun aNewerSavedHeadKeepsRecoveredRetrievalInputReadOnlyWithoutRebasingIt() {
        save()
        val before = loaded()
        val form = addForm("embedding_model")
        val raw = "{\"output_dimensions\":9007199254740993,\"credential_ref\":\"secret://pending/embedding\"}"
        field(form, R.string.component_role).setText("entered")
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

    @Test fun aSavingRetrievalCheckpointCannotReplayAfterItsOriginalWriteIsAcknowledged() {
        save()
        val before = loaded()
        val form = addForm("data_source")
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText("{\"enabled\":false,\"refresh_minutes\":17}")
        checkbox(form).isChecked = true
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
                // Pause retires the dialog before acknowledgment; finish the owned write before reading storage.
                finishWork()
                await { !form.isShowing && loaded().revision == before.revision + 1 }
            }
        } finally { release.countDown() }
        val saved = loaded()
        val review = reviewPending()
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == "{\"enabled\":false,\"refresh_minutes\":17}"
        })
        assertTrue(saved.components.selections.single().enabled)
        assertFalse(settings(saved.components.selections.single()).getBoolean("enabled"))
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(before.optionalTiers, saved.optionalTiers)
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsRetrievalGuidanceRequiredFieldsAndConfirmationReachable() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        save()
        val guidance = mapOf("data_source" to R.string.component_data_source_guidance, "knowledge_base" to R.string.component_knowledge_base_guidance,
            "embedding_model" to R.string.component_embedding_model_guidance, "vector_database" to R.string.component_vector_database_guidance,
            "rag_pipeline" to R.string.component_rag_pipeline_guidance)
        for (kind in kinds) {
            val form = addForm(kind)
            val role = field(form, R.string.component_role).apply { setText("entered") }
            val settings = field(form, R.string.component_settings).apply { setText(minimum(kind)) }
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
            for (label in listOf(guidance.getValue(kind), R.string.component_retrieval_intent_guidance))
                assertTrue(views(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }.isShown)
            for (view in views(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotBlank() }) {
                val layout = requireNotNull(view.layout)
                assertTrue(layout.lineCount > 0)
                assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            }
            assertEquals("entered", commit(form).components.selections.single { it.kind == kind }.role)
        }
    }

    private fun reachable(control: View, root: View) {
        control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
        val bounds = Rect(0, 0, control.width, control.height)
        var child = control
        while (child !== root) {
            val parent = child.parent as ViewGroup
            parent.offsetDescendantRectToMyCoords(child, bounds)
            assertTrue("Retrieval control must be reachable", Rect().also(parent::getDrawingRect).contains(bounds))
            child = parent
        }
    }
}
