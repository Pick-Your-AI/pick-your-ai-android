package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
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
import org.robolectric.shadows.ShadowSystemClock
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real simulated Activity and canonical local records; recorded inspection remains separate from current local-rule observations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SummaryRecordedComponentsActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
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
    private fun field(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }
    private fun summary() = activity.findViewById<CurrentDraftSummarySection>(R.id.draft_summary)
    private fun text() = views(summary()).filterIsInstance<TextView>().joinToString("\n") { it.text }
    private fun toggleOrNull() = views(summary()).filterIsInstance<Button>().singleOrNull {
        it.text.toString() == "Show saved components" || it.text.toString() == "Hide saved components"
    }
    private fun toggle(): Button {
        val value = toggleOrNull()
        assertNotNull("The acknowledged summary has no way to inspect its saved component identities", value)
        return requireNotNull(value)
    }
    private fun expand() { if (toggle().text.toString() == "Show saved components") toggle().performClick(); idle() }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun adapter() = PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun store() = LocalDraftStore(activity.noBackupFilesDir.toPath().resolve("guest-draft"))
    private fun disk() = Files.walk(activity.noBackupFilesDir.toPath()).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            activity.noBackupFilesDir.toPath().relativize(it).toString() to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
        }
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(activity) as ExecutorService
    private fun idle() = AutosaveUiClock.idle()
    private fun finishWork() { repeat(2) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) { idle(); if (condition()) return; Thread.sleep(10) }
        fail("Timed out waiting for the actual draft state")
    }
    private fun save() {
        button(R.string.save_draft).performClick()
        await { views().filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.draft_saved) } &&
            !button(R.string.save_draft).isEnabled }
        finishWork()
    }
    private fun reopen() {
        button(R.string.reopen_draft).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        finishWork()
    }
    private fun installRecorded(version: String? = null): PreviewStoredDraft {
        save()
        val old = (store().load() as DraftLoad.Available).draft
        val array = activity.assets.open("build.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("selections") }
        val entries = (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.getString("kind") in setOf("model", "model_provider") }
            .map { node ->
                node.put("enabled", node.getString("kind") == "model")
                node.put("notes", "private-summary-selection-note")
                if (node.getString("kind") == "model_provider")
                    node.getJSONObject("settings").put("credential_ref", "secret://private-summary-provider")
                if (version != null && node.getString("kind") == "model") node.put("catalog_item_version", version)
                LocalSelection.fromJson(node.toString().toByteArray())
            }
        assertEquals(2, entries.size)
        val next = store().save(old.version, old.content.withComponents(DraftSelections(entries))) as DraftSave.Saved
        reopen()
        await { summary().findViewById<TextView>(R.id.draft_summary_source).text.toString() ==
            activity.getString(R.string.draft_summary_saved, next.draft.version.number.toString()) }
        finishWork()
        return loaded()
    }
    private fun assertCurrentResultLimits() {
        val evaluation = summary().findViewById<TextView>(R.id.draft_summary_evaluation).text.toString()
        val id = PreviewActivity::class.java.getDeclaredField("currentDraftId")
            .apply { isAccessible = true }.get(activity) as PreviewDraftId
        val record = (readDraftAfterOwnedWork(activity) {
            val library = PreviewDraftLibrary(activity.noBackupFilesDir.toPath())
            if (id.value == "guest-draft") library.openLegacy() else library.open(id)
        } as? PreviewDraftLoad.Available)?.record
        val acknowledged = record != null && summary().findViewById<TextView>(R.id.draft_summary_source).text.toString() ==
            activity.getString(R.string.draft_summary_saved, record.revision.toString())
        val unavailable = setOf(
            "No evaluation is available for the current draft.",
            "Checks are unavailable while editing. Save the current inputs before reevaluation.",
            "Current check authority was retired while this screen was inactive.",
            "Checks are unavailable until the exact catalog, graph and rules are loaded.",
            "The stored draft changed. Reopen or reconcile the editor before checking it again.",
            "The current source or check could not be confirmed. Retry without treating an earlier result as current.",
        )
        val revision = record?.revision
        val observations = acknowledged && Regex("Saved revision $revision: [0-9]+ pass, [0-9]+ fail, [0-9]+ unknown, " +
            "[0-9]+ skipped rule observations\\. Severity is shown separately in each finding\\.").matches(evaluation)
        val withheld = acknowledged && evaluation.startsWith("Checks for saved revision $revision are unavailable: ") &&
            evaluation.endsWith("The affected set remains visible; unavailable is not pass or a zero estimate.")
        val rechecking = acknowledged && evaluation == "Rechecking saved revision $revision. " +
            "Results remain unavailable until its inputs and current revision are confirmed."
        assertTrue("Summary must expose only unavailable or exact saved-source local observations: $evaluation",
            evaluation in unavailable || observations || withheld || rechecking)
        assertTrue(views().filterIsInstance<TextView>().any {
            val value = it.text.toString()
            value.contains("loaded synthetic catalog, dependency graph and rules") &&
                value.contains("Free-text objective and context are not interpreted.") &&
                value.contains("Current cost and resource estimates are unavailable.")
        })
        assertEquals(activity.getString(R.string.draft_summary_cost_unavailable),
            summary().findViewById<TextView>(R.id.draft_summary_cost).text.toString())
    }

    @Test fun savedComponentDetailsExposeExactRecordedIdentityWithoutSaving() {
        val record = installRecorded()
        val before = disk()
        assertEquals("Show saved components", toggle().text.toString())
        expand()
        for (selection in record.components.selections) {
            assertTrue(text().contains("Selection: \"${selection.id}\""))
            assertTrue(text().contains("Kind: \"${selection.kind}\""))
            assertTrue(text().contains("Role: \"${selection.role}\""))
            assertTrue(text().contains("Catalog item: \"${selection.catalogItemId}\""))
            assertTrue(text().contains("Catalog version: \"${selection.catalogItemVersion}\""))
        }
        assertTrue(text().contains("Enabled in saved configuration"))
        assertTrue(text().contains("Disabled in saved configuration"))
        assertFalse(text().contains("private-summary-selection-note"))
        assertFalse(text().contains("secret://private-summary-provider"))
        assertEquals("Hide saved components", toggle().text.toString())
        toggle().performClick()
        assertFalse(text().contains("Catalog item:"))
        assertEquals(record.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertCurrentResultLimits()
    }

    @Test fun unsavedAndAcknowledgedEmptyComponentListsRemainDistinct() {
        assertNull(toggleOrNull())
        assertTrue(text().contains(activity.getString(R.string.draft_summary_components_unavailable)))
        assertFalse(text().contains("No components recorded in this saved revision."))
        save()
        assertTrue(text().contains("No components recorded in this saved revision."))
        assertNull(toggleOrNull())
        assertCurrentResultLimits()
    }

    @Test fun pendingEditsCanInspectSavedComponentsWithoutAcknowledgingThemAcrossRecreation() {
        val record = installRecorded()
        val before = disk()
        expand()
        val old = toggle()
        field(R.string.draft_title).setText("pending summary title")
        field(R.string.context_context).setText("pending summary context")
        assertFalse(old.isEnabled)
        old.callOnClick()
        assertTrue(toggle().isEnabled)
        assertTrue(text().contains("Catalog item:"))
        assertEquals(activity.getString(R.string.draft_summary_pending, record.revision.toString()),
            summary().findViewById<TextView>(R.id.draft_summary_source).text.toString())
        recreate()
        expand()
        assertTrue(text().contains("Catalog item:"))
        assertEquals("pending summary title", field(R.string.draft_title).text.toString())
        assertEquals("pending summary context", field(R.string.context_context).text.toString())
        assertEquals(record.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before, disk())
        assertCurrentResultLimits()
    }

    @Test fun loadingWithholdsExpandedRecordsAndRetiredControlsCannotRevealThem() {
        installRecorded()
        expand()
        val old = toggle()
        val before = disk()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { started.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(started.await(15, TimeUnit.SECONDS))
        try {
            reopen(); idle()
            assertTrue(text().contains(activity.getString(R.string.draft_summary_loading)))
            assertFalse(text().contains("Catalog item:"))
            assertNull(toggleOrNull())
            old.callOnClick(); idle()
            assertFalse(text().contains("Catalog item:"))
        } finally { release.countDown() }
        finishWork()
        assertEquals("Show saved components", toggle().text.toString())
        assertEquals(before, disk())
    }

    @Test fun componentInspectionPreservesAutosaveDeadlineAndUsesOnlyItsAcknowledgedRevision() {
        val recorded = installRecorded()
        val before = disk()
        field(R.string.draft_title).setText("pending summary autosave")
        val old = toggle()
        expand()
        ShadowSystemClock.advanceBy(Duration.ofMillis(PreviewDraftAutosave.DELAY_MILLIS - 1))
        finishWork()
        assertEquals(recorded.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before, disk())
        toggle().performClick()
        expand()
        assertTrue(text().contains(activity.getString(R.string.draft_summary_components_revision, recorded.revision.toString())))
        assertEquals(before, disk())

        // Inspection cannot cancel or restart the already scheduled form-save deadline.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        await { summary().findViewById<TextView>(R.id.draft_summary_source).text.toString() ==
            activity.getString(R.string.draft_summary_saved, (recorded.revision + 1).toString()) }
        finishWork()
        val acknowledged = loaded()
        assertEquals(recorded.revision + 1, acknowledged.revision)
        assertEquals("pending summary autosave", acknowledged.fields.title)
        assertEquals(recorded.components, acknowledged.components)
        assertFalse(old.isEnabled)
        old.callOnClick(); idle()
        assertEquals("Show saved components", toggle().text.toString())
        assertFalse(text().contains("Catalog item:"))
        expand()
        assertTrue(text().contains(activity.getString(R.string.draft_summary_components_revision, acknowledged.revision.toString())))
        assertCurrentResultLimits()
    }

    @Test fun oldExpansionControlsCannotReplayAfterPauseOrIntoAnotherDraft() {
        installRecorded()
        val old = toggle()
        controller.pause()
        old.callOnClick(); idle()
        assertFalse(text().contains("Catalog item:"))
        controller.resume(); finishWork()
        old.callOnClick(); idle()
        assertFalse(text().contains("Catalog item:"))
        expand()
        val beforeNew = toggle()
        button(R.string.library_new).performClick()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishWork()
        val before = disk()
        beforeNew.callOnClick(); old.callOnClick(); idle()
        assertFalse(text().contains("Catalog item:"))
        assertNull(toggleOrNull())
        assertEquals(before, disk())
        assertCurrentResultLimits()
    }

    @Test fun unconfirmedRestorationNeverUsesPriorComponentDetails() {
        installRecorded()
        expand()
        val old = toggle()
        field(R.string.draft_title).setText("pending over an old saved revision")
        val prior = (store().load() as DraftLoad.Available).draft
        assertTrue(store().save(prior.version, prior.content.withComponents(DraftSelections())) is DraftSave.Saved)
        val before = disk()
        recreate()
        assertTrue(text().contains(activity.getString(R.string.draft_summary_unavailable)))
        assertFalse(text().contains("Catalog item:"))
        assertNull(toggleOrNull())
        old.callOnClick(); idle()
        assertFalse(text().contains("Catalog item:"))
        assertEquals("pending over an old saved revision", field(R.string.draft_title).text.toString())
        assertEquals(before, disk())
    }

    @Test fun arbitraryRecordedVersionsRemainLiteralAndUnresolvedWithExplicitTruncation() {
        val raw = "unknown\nEnabled\u202e\"" + "v".repeat(200)
        val record = installRecorded(raw)
        val before = disk()
        expand()
        assertTrue(text().contains("unknown\\nEnabled\\u202e\\\""))
        assertFalse(text().contains('\u202e'))
        assertTrue(text().contains("truncated"))
        assertTrue(text().contains("Availability and suitability have not been checked."))
        assertEquals(raw, loaded().components.selections.single { it.kind == "model" }.catalogItemVersion)
        assertEquals(record.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before, disk())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertCurrentResultLimits()
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextKeepsExpandedRecordsReadableAndToggleReachable() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        installRecorded()
        expand()
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        for (view in views(summary()).filterIsInstance<TextView>()) {
            val layout = requireNotNull(view.layout)
            assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
        }
        val collapse = toggle()
        collapse.requestRectangleOnScreen(Rect(0, 0, collapse.width, collapse.height), true)
        idle()
        val visible = Rect()
        assertTrue(collapse.getLocalVisibleRect(visible))
        assertEquals(collapse.width, visible.width())
        assertEquals(collapse.height, visible.height())
        assertTrue(collapse.height >= (48 * density).toInt())
        assertTrue(collapse.isFocusable)
        assertTrue(collapse.createAccessibilityNodeInfo().isClickable)
        collapse.performClick()
        assertEquals("Show saved components", toggle().text.toString())
        assertFalse(text().contains("Catalog item:"))
    }
}
