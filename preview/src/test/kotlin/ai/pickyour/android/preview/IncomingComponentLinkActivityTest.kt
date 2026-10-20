package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Explicit Activity entry and real managed draft transactions in JVM simulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class IncomingComponentLinkActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        finishWork()
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }

    private fun button(label: Int) = views().filterIsInstance<Button>().single {
        it.text.toString() == activity.getString(label)
    }

    private fun textButton(text: String, root: View = activity.window.decorView) =
        views(root).filterIsInstance<Button>().single { it.text.toString() == text }

    private fun field(root: View, label: Int): EditText {
        val caption = views(root).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(label)
        }
        assertNotEquals(View.NO_ID, caption.labelFor)
        return root.findViewById(caption.labelFor)
    }

    private fun field(label: Int) = field(activity.window.decorView, label)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun currentId() = PreviewActivity::class.java.getDeclaredField("currentDraftId")
        .apply { isAccessible = true }.get(activity) as PreviewDraftId
    private fun loaded(id: PreviewDraftId = currentId()) =
        (readDraftAfterOwnedWork(activity) {
            PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).open(id)
        } as PreviewDraftLoad.Available).record

    private fun finishWork() {
        val worker = PreviewActivity::class.java.getDeclaredField("worker")
            .apply { isAccessible = true }.get(activity) as ExecutorService
        repeat(3) {
            AutosaveUiClock.idle()
            worker.submit {}.get(15, TimeUnit.SECONDS)
            AutosaveUiClock.idle()
        }
    }

    private fun emptyDraft(): PreviewStoredDraft {
        val previous = currentId()
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertNotEquals(previous, currentId())
        return loaded().also { assertTrue(it.components.selections.isEmpty()) }
    }

    private fun fixture() = requireNotNull(activity.assets.open("catalog.json").use {
        PreviewCatalogBrowser.read(it.readBytes())
    })

    private fun route(): Intent {
        val browser = fixture()
        val item = browser.items.single { it.id == "cat.model.fixture" }
        val uri = "pickyourai-preview://component/v1?kind=${item.kind}&id=${Uri.encode(item.id)}" +
            "&version=${Uri.encode(item.version)}&snapshot=${browser.hash}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    }

    private fun addFromDetails(role: String): Pair<Button, Button> {
        val detail = dialog()
        val use = textButton("Use in draft", detail.window!!.decorView)
        assertTrue(use.isEnabled)
        use.performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString() == activity.getString(R.string.component_add)
        }
        shadowOf(picker).clickOnItem(index)
        val form = dialog()
        field(form.window!!.decorView, R.string.component_role).setText(role)
        field(form.window!!.decorView, R.string.component_settings).setText("{}")
        val save = form.getButton(AlertDialog.BUTTON_POSITIVE)
        repeat(3) { save.performClick() }
        finishWork()
        assertFalse(form.isShowing)
        return use to save
    }

    private fun chooseLocal(id: PreviewDraftId) {
        button(R.string.library_open).performClick()
        finishWork()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString().contains(id.value)
        }
        shadowOf(picker).clickOnItem(index)
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        assertEquals(id, currentId())
    }

    @Test fun warmComponentLinkRequiresExplicitAddAndRepeatedDeliveryCannotDuplicateIt() {
        val before = emptyDraft()
        val id = currentId()
        val active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        field(R.string.catalog_search).setText("kept search context")
        val entry = route()
        repeat(3) { controller.newIntent(entry) }
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertEquals("kept search context", field(R.string.catalog_search).text.toString())
        assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        textButton("Open linked component").performClick()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        val (oldUse, oldSave) = addFromDetails("primary")
        val saved = loaded()
        val selected = saved.components.selections.single()
        val expected = fixture().items.single { it.id == "cat.model.fixture" }
        assertEquals(before.revision + 1, saved.revision)
        assertEquals(id, currentId())
        assertEquals(expected.id, selected.catalogItemId)
        assertEquals(expected.version, selected.catalogItemVersion)
        assertEquals(expected.kind, selected.kind)
        assertEquals("primary", selected.role)
        assertEquals(before.fields, saved.fields)
        assertEquals(before.contextInput, saved.contextInput)
        assertEquals(before.workloadInput, saved.workloadInput)
        assertEquals(before.components.links, saved.components.links)
        repeat(3) { controller.newIntent(entry); oldUse.performClick(); oldSave.performClick() }
        finishWork()
        assertEquals(saved.baselineIdentity, loaded().baselineIdentity)
        assertEquals(listOf(selected), loaded().components.selections)
        assertEquals("kept search context", field(R.string.catalog_search).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun coldComponentLinkStaysUnboundAcrossRecreationUntilAnExplicitLocalChoice() {
        val before = emptyDraft()
        val id = currentId()
        val entry = route()
        val active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java, entry).setup()
        finishWork()
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals("", field(R.string.draft_title).text.toString())
        assertEquals(before.baselineIdentity, loaded(id).baselineIdentity)
        textButton("Open linked component").performClick()
        assertFalse(textButton("Use in draft", dialog().window!!.decorView).isEnabled)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val checkpoint = Bundle()
        controller.pause().saveInstanceState(checkpoint).stop().destroy()
        assertFalse(checkpoint.keySet().any { checkpoint.get(it) is Uri || checkpoint.get(it) is Intent })
        assertFalse(checkpoint.keySet().any { checkpoint.get(it) == entry.dataString })
        controller = Robolectric.buildActivity(PreviewActivity::class.java, entry)
            .create(checkpoint).start().resume().visible()
        finishWork()
        assertFalse(button(R.string.save_draft).isEnabled)
        assertEquals(before.baselineIdentity, loaded(id).baselineIdentity)
        assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        chooseLocal(id)
        textButton("Open linked component").performClick()
        addFromDetails("explicit")
        val saved = loaded()
        assertEquals(before.revision + 1, saved.revision)
        assertEquals("explicit", saved.components.selections.single().role)
        assertEquals(before.fields, saved.fields)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun malformedOversizedAndPermissionBearingLinksCannotReplaceWarmDraftOrBrowseInput() {
        val before = emptyDraft()
        val id = currentId()
        val active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("Uncommitted local title")
        field(R.string.catalog_search).setText("Keep this search")
        field(R.string.catalog_search).setSelection(2, 7)
        val entry = route()
        val raw = requireNotNull(entry.dataString)
        val attacks = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse(raw + "&draft=guest-draft&role=primary")),
            Intent(Intent.ACTION_VIEW, Uri.parse(raw + "x")),
            Intent(Intent.ACTION_VIEW, Uri.parse(raw.replace("pickyourai-preview:", "https:"))),
            Intent(Intent.ACTION_VIEW, Uri.parse(raw.replace("version=2026.10-demo", "version=%C0%AF"))),
            Intent(Intent.ACTION_VIEW, Uri.parse(raw + "x".repeat(4097))),
            Intent(Intent.ACTION_SEND, entry.data),
            Intent(Intent.ACTION_MAIN, entry.data),
            Intent(Intent.ACTION_VIEW).setDataAndType(entry.data, "text/plain"),
            Intent(entry).apply { clipData = ClipData.newRawUri("untrusted", Uri.parse("content://untrusted.invalid/item")) },
            Intent(entry).apply { selector = Intent(Intent.ACTION_MAIN) },
        ) + listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            .map { Intent(entry).addFlags(it) }
        val open = textButton("Open linked component")
        for ((index, attack) in attacks.withIndex()) {
            controller.newIntent(entry)
            assertEquals(View.VISIBLE, open.visibility)
            controller.newIntent(attack)
            open.performClick() // A retained hidden control cannot reuse the previous valid route.
            finishWork()
            assertEquals("attack $index", View.GONE, open.visibility)
            assertFalse(open.isEnabled)
            assertTrue(views().filterIsInstance<TextView>().any {
                it.visibility == View.VISIBLE && it.text.toString() == activity.getString(R.string.entry_unsupported)
            })
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertEquals(id, currentId())
            assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
            assertEquals("Uncommitted local title", field(R.string.draft_title).text.toString())
            assertEquals("Keep this search", field(R.string.catalog_search).text.toString())
            assertEquals(2, field(R.string.catalog_search).selectionStart)
            assertEquals(7, field(R.string.catalog_search).selectionEnd)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        assertTrue("Navigation must observe pending input before autosave", SystemClock.uptimeMillis() - inputStartedAt < 750L)
    }

    @Test fun recognizedWarmLinkPreservesUncommittedBuilderContextAcrossRecreation() {
        val before = emptyDraft()
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("Keep pending title")
        field(R.string.context_objective).setText("Keep pending objective")
        field(R.string.context_context).setText("Keep pending context")
        field(R.string.catalog_search).setText("Keep filtered search")
        controller.newIntent(route())
        textButton("Open linked component").performClick()
        assertFalse(textButton("Use in draft", dialog().window!!.decorView).isEnabled)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val checkpoint = Bundle()
        controller.pause().saveInstanceState(checkpoint).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(checkpoint).start().resume().visible()
        finishWork()
        assertEquals("Keep pending title", field(R.string.draft_title).text.toString())
        assertEquals("Keep pending objective", field(R.string.context_objective).text.toString())
        assertEquals("Keep pending context", field(R.string.context_context).text.toString())
        assertEquals("Keep filtered search", field(R.string.catalog_search).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        textButton("Open linked component").performClick()
        assertFalse(textButton("Use in draft", dialog().window!!.decorView).isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Navigation must observe pending input before autosave", SystemClock.uptimeMillis() - inputStartedAt < 750L)
    }

    @Test fun warmLinkPreservesTheAutosaveDeadlineAndRequiresAFreshSelectionCapture() {
        val before = emptyDraft()
        val id = currentId()
        val active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        AutosaveUiClock.pause()
        val inputStartedAt = SystemClock.uptimeMillis()
        field(R.string.draft_title).setText("Ordinary autosave continues")
        shadowOf(Looper.getMainLooper()).idleFor(749, TimeUnit.MILLISECONDS)
        val entry = route()
        repeat(3) { controller.newIntent(entry) }
        finishWork()
        assertEquals(749L, SystemClock.uptimeMillis() - inputStartedAt)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        textButton("Open linked component").performClick()
        val oldDetail = dialog()
        val oldUse = textButton("Use in draft", oldDetail.window!!.decorView)
        assertFalse(oldUse.isEnabled)
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        finishWork()
        val acknowledged = loaded()
        assertEquals(750L, SystemClock.uptimeMillis() - inputStartedAt)
        assertEquals(before.revision + 1, acknowledged.revision)
        assertEquals("Ordinary autosave continues", acknowledged.fields.title)
        assertEquals(before.contextInput, acknowledged.contextInput)
        assertEquals(before.workloadInput, acknowledged.workloadInput)
        assertTrue(acknowledged.components.selections.isEmpty())
        assertEquals(before.components.links, acknowledged.components.links)
        assertEquals(id, currentId())
        assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
        repeat(3) { oldUse.performClick() }
        finishWork()
        assertEquals(acknowledged.baselineIdentity, loaded().baselineIdentity)
        oldDetail.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()

        textButton("Open linked component").performClick()
        assertTrue(textButton("Use in draft", dialog().window!!.decorView).isEnabled)
        addFromDetails("after-autosave")
        val selected = loaded()
        assertEquals(acknowledged.revision + 1, selected.revision)
        assertEquals("after-autosave", selected.components.selections.single().role)
        assertEquals("Ordinary autosave continues", selected.fields.title)
        shadowOf(Looper.getMainLooper()).idleFor(60_000, TimeUnit.MILLISECONDS)
        finishWork()
        assertEquals(selected.baselineIdentity, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun incomingDeliveryCannotDismissCommitOrReplaceAnOpenComponentForm() {
        val before = emptyDraft()
        val entry = route()
        controller.newIntent(entry)
        textButton("Open linked component").performClick()
        textButton("Use in draft", dialog().window!!.decorView).performClick()
        val picker = dialog()
        val index = (0 until picker.listView.adapter.count).single {
            picker.listView.adapter.getItem(it).toString() == activity.getString(R.string.component_add)
        }
        shadowOf(picker).clickOnItem(index)
        val form = dialog()
        val role = field(form.window!!.decorView, R.string.component_role)
        val settings = field(form.window!!.decorView, R.string.component_settings)
        role.setText("working-role")
        settings.setText("{pending")
        settings.setSelection(2, 5)
        val commit = form.getButton(AlertDialog.BUTTON_POSITIVE)
        repeat(3) {
            controller.newIntent(entry)
            controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://untrusted.invalid/component")))
            finishWork()
            assertSame(form, dialog())
            assertTrue(form.isShowing)
            assertEquals("working-role", role.text.toString())
            assertEquals("{pending", settings.text.toString())
            assertEquals(2, settings.selectionStart)
            assertEquals(5, settings.selectionEnd)
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        }
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        repeat(3) { commit.performClick() }
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun missingKindItemVersionAndSnapshotNeverFallBackToAnAvailableSelection() {
        val before = emptyDraft()
        field(R.string.catalog_search).setText("Unchanged filter")
        val raw = requireNotNull(route().dataString)
        val missing = listOf(
            raw.replace("kind=model", "kind=accelerator"),
            raw.replace("id=cat.model.fixture", "id=cat.model.missing"),
            raw.replace("version=2026.10-demo", "version=unavailable-successor"),
            raw.replace(fixture().hash, "c".repeat(64)),
        )
        for (uri in missing) {
            controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            val previous = ShadowAlertDialog.getLatestAlertDialog()
            val open = textButton("Open linked component")
            assertTrue(open.isEnabled)
            open.performClick()
            assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(views().filterIsInstance<TextView>().any {
                it.visibility == View.VISIBLE && it.text.toString() == activity.getString(R.string.component_link_unavailable)
            })
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
            assertTrue(loaded().components.selections.isEmpty())
            assertEquals("Unchanged filter", field(R.string.catalog_search).text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun corruptedNormalizedCheckpointCannotReparseOriginalIntentOrAcquireAnActiveDraft() {
        val before = emptyDraft()
        val id = currentId()
        val entry = route()
        val active = PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read()
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java, entry).setup()
        finishWork()
        val checkpoint = Bundle()
        controller.pause().saveInstanceState(checkpoint).stop().destroy()
        val corruptions = listOf(
            "incomingComponentKind" to "future-kind",
            "incomingComponentId" to "../untrusted",
            "incomingComponentVersion" to "\uD800",
            "incomingComponentSnapshot" to "c".repeat(63),
            "incomingComponentVersion" to null,
        )
        for ((index, corruption) in corruptions.withIndex()) {
            val restored = Bundle(checkpoint).apply { putString(corruption.first, corruption.second) }
            controller = Robolectric.buildActivity(PreviewActivity::class.java, entry).create(restored).start().resume().visible()
            finishWork()
            val open = textButton("Open linked component")
            assertEquals(View.GONE, open.visibility)
            open.performClick()
            assertFalse(button(R.string.save_draft).isEnabled)
            assertEquals("", field(R.string.draft_title).text.toString())
            assertEquals(before.baselineIdentity, loaded(id).baselineIdentity)
            assertEquals(active, PreviewActiveDraftStore(activity.noBackupFilesDir.toPath()).read())
            assertNull(shadowOf(activity).nextStartedActivity)
            if (index < corruptions.lastIndex) controller.pause().stop().destroy()
        }
    }

    @Test fun backgroundAndDestroyedLinkControlsCannotOpenAWindowOrWriteTheDraft() {
        val before = emptyDraft()
        controller.newIntent(route())
        val open = textButton("Open linked component")
        val previous = ShadowAlertDialog.getLatestAlertDialog()
        controller.pause()
        repeat(3) { open.performClick() }
        assertSame(previous, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        controller.resume()
        open.performClick()
        val oldUse = textButton("Use in draft", dialog().window!!.decorView)
        assertTrue(oldUse.isEnabled)
        val checkpoint = Bundle()
        controller.pause().saveInstanceState(checkpoint).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(checkpoint).start().resume().visible()
        finishWork()
        repeat(3) { open.performClick(); oldUse.performClick() }
        finishWork()
        assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        assertTrue(loaded().components.selections.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsIncomingAndDraftSelectionActionsReachable() {
        val before = emptyDraft()
        RuntimeEnvironment.setFontScale(2f)
        try {
            val checkpoint = Bundle()
            controller.pause().saveInstanceState(checkpoint).stop().destroy()
            controller = Robolectric.buildActivity(PreviewActivity::class.java).create(checkpoint).start().resume().visible()
            finishWork()
            assertEquals(2f, activity.resources.configuration.fontScale, 0f)
            controller.newIntent(route())
            val open = textButton("Open linked component")
            assertMeasuredButtons(activity.window.decorView, listOf(open))
            open.performClick()
            val root = dialog().window!!.decorView
            val controls = listOf(R.string.catalog_detail_browser_open, R.string.catalog_price_browse,
                R.string.catalog_compare_add, R.string.catalog_use_in_draft).map {
                textButton(activity.getString(it), root)
            }
            assertMeasuredButtons(root, controls)
            val use = textButton("Use in draft", root)
            assertTrue(use.isEnabled)
            use.performClick()
            assertTrue(dialog().isShowing)
            assertEquals(before.baselineIdentity, loaded().baselineIdentity)
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun assertMeasuredButtons(root: View, controls: List<Button>) {
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        for (control in controls) {
            assertTrue(control.isShown)
            assertTrue(control.text.isNotBlank())
            assertTrue("48dp measured height: ${control.text}", control.height >= (48 * density).toInt())
            assertTrue("48dp measured width: ${control.text}", control.width >= (48 * density).toInt())
            control.requestRectangleOnScreen(android.graphics.Rect(0, 0, control.width, control.height), true)
            val bounds = android.graphics.Rect(0, 0, control.width, control.height)
            var child: View = control
            while (child !== root) {
                val parent = child.parent as ViewGroup
                parent.offsetDescendantRectToMyCoords(child, bounds)
                val viewport = android.graphics.Rect().also(parent::getDrawingRect)
                assertTrue("Control is clipped by ${parent.javaClass.simpleName}: ${control.text}", viewport.contains(bounds))
                child = parent
            }
            val label = requireNotNull(control.layout)
            assertTrue(label.lineCount > 0)
            assertTrue("Complete label height: ${control.text}", label.height > 0 &&
                label.height <= control.height - control.compoundPaddingTop - control.compoundPaddingBottom)
            val left = control.scrollX.toFloat()
            val right = left + control.width - control.compoundPaddingLeft - control.compoundPaddingRight
            assertTrue(right > left)
            repeat(label.lineCount) { line ->
                assertEquals("No ellipsis: ${control.text}", 0, label.getEllipsisCount(line))
                assertTrue("Complete label width: ${control.text}", label.getLineLeft(line) >= left &&
                    label.getLineRight(line) <= right && label.getLineLeft(line) <= label.getLineRight(line))
            }
            assertEquals(label.text.length, label.getLineEnd(label.lineCount - 1))
        }
    }
}
