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
class AgentSelectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val kinds = listOf("agent_framework", "tool", "mcp_server", "memory_store", "integration")

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
        fail("Timed out waiting for explicit agent selection state")
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

    private fun setT4(label: Int): PreviewStoredDraft {
        val before = loaded()
        val tier = views(views().filterIsInstance<OptionalTierSection>().single()).filterIsInstance<Button>()
            .single { it.text.toString().startsWith("T4:") }
        assertTrue(tier.isEnabled)
        tier.performClick()
        val confirmation = select(dialog()) { it == activity.getString(label) }
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { tier.isEnabled && tier.text.toString().startsWith("T4: ${activity.getString(label)};") &&
            views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } }
        finishWork()
        return loaded().also { assertEquals(before.revision + 1, it.revision) }
    }

    @Test fun allAgentKindsExposeExplicitInputAndReopenWithoutCreatingAnotherComponent() {
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
            field(form, R.string.component_settings).setText("{}")
            val saved = commit(form)
            val selection = saved.components.selections.single { it.kind == kind }
            val item = catalog.items.single { it.kind == kind }
            assertEquals(item.id, selection.catalogItemId)
            assertEquals(item.version, selection.catalogItemVersion)
            assertEquals("entered", selection.role)
            assertFalse(selection.enabled)
            assertEquals("{}", PreviewSelectionForms.settingsText(selection))
            assertEquals(index + 1, saved.components.selections.size)
            assertNull(shadowOf(activity).nextStartedActivity)
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


    @Test fun invalidScopesEndpointsAndReferencesKeepTheEntireFormWithoutSavingOrOpeningAConnection() {
        val invalid = mapOf(
            "tool" to listOf("{\"allowed_scopes\":[\"read\",\"read\"]}", "{\"allowed_scopes\":[null]}",
                "{\"credential_ref\":\"raw-token\"}", "{\"protocol\":\"http\"}"),
            "mcp_server" to listOf("{\"endpoint\":\"not a URI\"}", "{\"transport\":\"stdio\"}",
                "{\"read_only\":\"true\"}", "{\"credential_ref\":\"raw-token\"}"),
            "integration" to listOf("{\"rate_limit_per_minute\":-1}", "{\"endpoint\":\"https://example.invalid/a b\"}",
                "{\"enabled\":null}", "{\"enabled\":true,\"enabled\":false}"),
        )
        val entered = mapOf(
            "tool" to "{\"allowed_scopes\":[\"read:one\",\" write:* \",\"\",\"read:東京\"],\"timeout_ms\":999999999999999999999999,\"enabled\":false}",
            "mcp_server" to "{\"endpoint\":\"https://mcp.example.invalid:9443/a/%2F?q=read%3Aone#exact\",\"credential_ref\":\"secret://entered/mcp\",\"read_only\":false}",
            "integration" to "{\"endpoint\":\"custom+local://integration.example.invalid/exact\",\"rate_limit_per_minute\":0,\"enabled\":false}",
        )
        for ((kind, attempts) in invalid) {
            val before = createEmpty()
            val bytes = disk()
            val form = addForm(kind)
            field(form, R.string.component_role).setText("entered")
            for (raw in attempts) {
                field(form, R.string.component_settings).setText(raw)
                form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                await { form.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && views(form.window!!.decorView).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.component_invalid)
                } }
                assertEquals(raw, field(form, R.string.component_settings).text.toString())
                assertEquals(before.baselineIdentity, loaded().baselineIdentity)
                assertEquals(bytes, disk())
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            val raw = entered.getValue(kind)
            val input = field(form, R.string.component_settings)
            input.setText(raw)
            assertEquals(0, input.autoLinkMask)
            assertTrue(input.text.getSpans(0, input.text.length, android.text.style.URLSpan::class.java).isEmpty())
            val saved = commit(form)
            assertEquals(raw, PreviewSelectionForms.settingsText(saved.components.selections.single()))
            assertEquals(before.optionalTiers, saved.optionalTiers)
            assertTrue(saved.components.links.isEmpty())
            val configure = action(saved.components.selections.single(), R.string.component_configure)
            assertEquals(raw, field(configure, R.string.component_settings).text.toString())
            closeDialog(configure)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }


    @Test fun agentSettingsAndComponentStatesSurviveExplicitTierDisableReopenAndReenable() {
        createEmpty()
        val entered = mapOf(
            "agent_framework" to "{\"agent_count\":9007199254740993,\"max_iterations\":9,\"timeout_ms\":1}",
            "tool" to "{\"enabled\":false,\"allowed_scopes\":[\"read:one\",\" write:* \"],\"timeout_ms\":1,\"credential_ref\":\"secret://entered/tool\"}",
            "mcp_server" to "{\"endpoint\":\"https://mcp.example.invalid/a/%2F?q=one#exact\",\"credential_ref\":\"secret://entered/mcp\",\"read_only\":false}",
            "memory_store" to "{\"ttl_hours\":1,\"max_entries\":9007199254740993,\"encryption_key_ref\":\"secret://entered/memory\"}",
            "integration" to "{\"enabled\":false,\"endpoint\":\"custom+local://integration.example.invalid/exact\",\"credential_ref\":\"secret://entered/integration\",\"rate_limit_per_minute\":0}",
        )
        for (kind in kinds) add(kind, enabled = true, settings = entered.getValue(kind))
        val initial = loaded()
        assertEquals(Activation.DISABLED, initial.optionalTiers.getValue(OptionalTier.T4).activation)
        assertTrue(initial.components.selections.all { it.enabled })
        assertFalse(settings(initial.components.selections.single { it.kind == "tool" }).getBoolean("enabled"))
        assertEquals(BigInteger("9007199254740993"), BigInteger(settings(initial.components.selections.first()).get("agent_count").toString()))
        for (label in listOf(R.string.tier_enabled, R.string.tier_disabled)) {
            val saved = setT4(label)
            assertEquals(initial.components, saved.components)
            assertEquals(initial.fields, saved.fields)
            assertEquals(initial.workloadInput, saved.workloadInput)
        }
        val disabled = loaded()
        val bytes = disk()
        recreate()
        assertEquals(bytes, disk())
        assertEquals(disabled.baselineIdentity, loaded().baselineIdentity)
        assertEquals(Activation.DISABLED, loaded().optionalTiers.getValue(OptionalTier.T4).activation)
        val reenabled = setT4(R.string.tier_enabled)
        assertEquals(Activation.ENABLED, reenabled.optionalTiers.getValue(OptionalTier.T4).activation)
        assertEquals(initial.components, reenabled.components)
        for (value in reenabled.components.selections) assertEquals(entered.getValue(value.kind), PreviewSelectionForms.settingsText(value))
        val tool = reenabled.components.selections.single { it.kind == "tool" }
        val confirmation = action(tool, R.string.component_disable)
        assertTrue(views(confirmation.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(activity.getString(R.string.component_agent_intent_guidance))
        })
        val changed = commit(confirmation)
        val changedTool = changed.components.selections.single { it.id == tool.id }
        assertFalse(changedTool.enabled)
        assertFalse(settings(changedTool).getBoolean("enabled"))
        assertEquals(Activation.ENABLED, changed.optionalTiers.getValue(OptionalTier.T4).activation)
        assertEquals(initial.components.selections.filter { it.id != tool.id }, changed.components.selections.filter { it.id != tool.id })
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun severalEnabledAgentRolesCoexistWhileDuplicateRolesAndRemovalPreserveOtherSelections() {
        createEmpty()
        val first = add("tool", role = "source_a", enabled = true, settings = "{\"allowed_scopes\":[\"read:one\"]}")
            .components.selections.single()
        val pair = add("tool", role = "source_b", enabled = true, settings = "{\"allowed_scopes\":[\"read:two\"]}")
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

    @Test fun pendingAgentFormsKeepAllFiveKindsAndExactInputAcrossParcelRecreationWithoutOldConsent() {
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
            field(restored, R.string.component_settings).setText("{}")
            val saved = commit(restored)
            assertEquals(selectionId, saved.components.selections.single().id)
            assertEquals(kind, saved.components.selections.single().kind)
            assertEquals(before.optionalTiers, saved.optionalTiers)
        }
    }

    @Test fun canceledAndCatalogStaleAgentControlsCannotSaveOrSubstituteAnotherSnapshot() {
        save()
        for (kind in kinds) {
            val form = addForm(kind)
            field(form, R.string.component_role).setText("entered")
            field(form, R.string.component_settings).setText("{}")
            val retired = form.getButton(AlertDialog.BUTTON_POSITIVE)
            closeDialog(form)
            val bytes = disk()
            retired.performClick(); finishWork()
            assertEquals(bytes, disk())
        }
        val form = addForm("mcp_server")
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText("{\"endpoint\":\"https://stale.example.invalid/exact\"}")
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

    @Test fun aNewerSavedHeadKeepsRecoveredAgentInputReadOnlyWithoutRebasingIt() {
        save()
        val before = loaded()
        val form = addForm("mcp_server")
        val raw = "{\"endpoint\":\"https://pending.example.invalid/a/%2F#exact\",\"credential_ref\":\"secret://pending/mcp\"}"
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

    @Test fun aSavingAgentCheckpointCannotReplayAfterItsOriginalWriteIsAcknowledged() {
        save()
        val before = loaded()
        val form = addForm("tool")
        field(form, R.string.component_role).setText("entered")
        field(form, R.string.component_settings).setText("{\"enabled\":false,\"allowed_scopes\":[\"read:one\"],\"timeout_ms\":17}")
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
                await { !form.isShowing && loaded().revision == before.revision + 1 }
                finishWork()
            }
        } finally { release.countDown() }
        val saved = loaded()
        val review = reviewPending()
        assertTrue(views(review.window!!.decorView).filterIsInstance<EditText>().isEmpty())
        assertTrue(views(review.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString() == "{\"enabled\":false,\"allowed_scopes\":[\"read:one\"],\"timeout_ms\":17}"
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
    fun narrowRtlLargeTextKeepsAgentGuidanceFieldsAndConfirmationReachable() {
        RuntimeEnvironment.setFontScale(2f)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        save()
        val guidance = mapOf("agent_framework" to R.string.component_agent_framework_guidance, "tool" to R.string.component_tool_guidance,
            "mcp_server" to R.string.component_mcp_server_guidance, "memory_store" to R.string.component_memory_store_guidance,
            "integration" to R.string.component_integration_guidance)
        for (kind in kinds) {
            val form = addForm(kind)
            val role = field(form, R.string.component_role).apply { setText("entered") }
            val settings = field(form, R.string.component_settings).apply { setText("{}") }
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
            for (label in listOf(guidance.getValue(kind), R.string.component_agent_intent_guidance))
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
            assertTrue("Agent control must be reachable", Rect().also(parent::getDrawingRect).contains(bounds))
            child = parent
        }
    }
}
