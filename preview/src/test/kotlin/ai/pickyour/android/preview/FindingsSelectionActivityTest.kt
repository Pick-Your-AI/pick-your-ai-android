package ai.pickyour.android.preview

import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.evaluation.*
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import org.json.JSONArray
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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Real receiving adapter and native controls in a simulation, not installed-device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FindingsSelectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val navigators = mutableListOf<FindingsSelectionNavigator>()

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.retry_catalog).isEnabled && title().isEnabled }
    }

    @After fun close() {
        navigators.forEach { it.retire() }
        controller.pause().stop().destroy()
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int, root: View = activity.window.decorView): Button =
        button(activity.getString(label), root)
    private fun button(text: String, root: View) = views(root).filterIsInstance<Button>().single { it.text.toString() == text }
    private fun title(): EditText {
        val label = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.draft_title) }
        return activity.findViewById(label.labelFor)
    }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun controls(window: AlertDialog = dialog()) = views(window.window!!.decorView).filterIsInstance<Button>()
        .filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
    private fun text(window: AlertDialog = dialog()) = views(window.window!!.decorView).filterIsInstance<TextView>()
        .filterNot { it is Button }.joinToString("\n") { it.text.toString() }
    private fun idle() = AutosaveUiClock.idle()
    private fun click(label: Int, window: AlertDialog = dialog()) {
        button(label, window.window!!.decorView).performClick()
        idle()
    }
    private fun inspectSelection(index: Int) {
        button(activity.getString(R.string.findings_context_inspect, index), dialog().window!!.decorView).performClick()
        idle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for the explicit findings state")
    }
    private fun assertNoDraftWrite() {
        assertTrue(PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy().load() is PreviewDraftLoad.Missing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun worker() = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(activity) as ExecutorService
    private fun finishFindingWork() {
        worker().submit {}.get(15, TimeUnit.SECONDS)
        idle()
    }
    private fun blockWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().submit { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        return release
    }
    private fun editorState(): Map<String, Any?> {
        val state = Bundle().also { controller.saveInstanceState(it) }
        val keys = listOf("title", "objective", "contextInput", "selectedId", "selectedVersion",
            "selectedCatalogHash", "draftId", "draftBaseline", "selectionResolved", "draftRequiresReopen",
            "workload.rejectedEdit", "workload.classification") + (0..7).map { "workload." + it }
        @Suppress("DEPRECATION")
        return keys.associateWith { state.get(it) }
    }
    private fun privateFiles() = activity.noBackupFilesDir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(activity.noBackupFilesDir).path to it.readBytes().toList() }
    private fun saveTitle(value: String) {
        title().setText(value)
        button(R.string.save_draft).performClick()
        await { !button(R.string.save_draft).isEnabled && views().filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.draft_saved) } }
    }
    private fun recreate() {
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && title().isEnabled }
    }
    private fun openFinding(): AlertDialog {
        button(R.string.findings_inspect).performClick()
        await { button(R.string.findings_browse).isEnabled }
        button(R.string.findings_browse).performClick()
        val picker = dialog()
        shadowOf(picker).clickOnItem(0)
        idle()
        assertNotSame(picker, dialog())
        return dialog()
    }
    private fun openActualContext(): AlertDialog {
        val finding = openFinding()
        finding.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertNotSame(finding, dialog())
        assertFalse(finding.isShowing)
        return dialog()
    }
    private fun fixture(name: String) = activity.assets.open(name + ".json").bufferedReader().use { JSONObject(it.readText()) }
    private fun model(build: JSONObject) = build.getJSONArray("selections").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.single { it.getString("id") == "sel_model" }
    }
    private fun accepted(change: (JSONObject, JSONObject) -> Unit = { _, _ -> }): ReferenceReportView {
        val build = fixture("build")
        val report = fixture("evaluation-report")
        val rules = fixture("ruleset")
        change(build, report)
        val selections = build.getJSONArray("selections")
        val findings = report.getJSONArray("findings")
        val declared = rules.getJSONArray("rules")
        repeat(findings.length()) { index ->
            val kinds = declared.getJSONObject(index).getJSONArray("affected_kinds").let { values ->
                (0 until values.length()).map { values.getString(it) }.toSet()
            }
            val ids = (0 until selections.length()).map { selections.getJSONObject(it) }
                .filter { it.getBoolean("enabled") && it.getString("kind") in kinds }.map { it.getString("id") }.toSortedSet()
            findings.getJSONObject(index).put("selection_ids", JSONArray(ids.toList()))
        }
        val received = ReferenceReportAdapter().receive(build.toString().byteInputStream(),
            rules.toString().byteInputStream(), report.toString().byteInputStream())
        assertTrue("Fixture must pass the actual receiving boundary", received is ReportReception.Accepted)
        return (received as ReportReception.Accepted).inspect()
    }
    private fun show(report: ReferenceReportView, current: () -> Boolean = { true }): FindingsSelectionNavigator =
        FindingsSelectionNavigator(activity, report, 0, current).also { navigators += it; it.show(); idle() }

    private fun countReport(count: Int): ReferenceReportView = accepted { build, _ ->
        val template = JSONObject(model(build).toString())
        val selections = build.getJSONArray("selections")
        repeat(selections.length()) { selections.getJSONObject(it).put("enabled", false) }
        repeat(count) { index ->
            selections.put(JSONObject(template.toString()).put("enabled", true)
                .put("id", "sel_count_" + index.toString().padStart(2, '0'))
                .put("role", "recorded_" + index))
        }
    }.also { assertEquals(count, it.findings.first().selectionContexts.size) }

    private fun pageHeader(): TextView = views(dialog().window!!.decorView).filterIsInstance<TextView>()
        .single { it.text.toString().startsWith("Page ") }

    @Test fun selectionPageCountsRetainEmptySingleAndWholePagedTotals() {
        // Count inspection has no save authority; the independent title debounce stays pending.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        title().setText("pending count inspection")
        for (count in listOf(1, 0, 2, 17)) {
            val report = countReport(count)
            val navigation = show(report)
            val pages = if (count > 16) 2 else 1
            val first = if (count == 0) 0 else 1
            val last = minOf(count, 16)
            val noun = if (count == 1) "selection" else "selections"
            assertEquals("Page 1 of $pages · $count $noun · $first–$last displayed", pageHeader().text.toString())
            val rows = controls().filter { it.text.toString().startsWith("Inspect selection ") }
            assertEquals(last, rows.size)
            assertEquals(count == 0, text().contains(activity.getString(R.string.findings_context_none)))
            if (count == 17) {
                click(R.string.findings_context_next)
                assertEquals("Page 2 of 2 · 17 selections · 17–17 displayed", pageHeader().text.toString())
                assertEquals(1, controls().count { it.text.toString().startsWith("Inspect selection ") })
                inspectSelection(17)
                assertTrue(text().contains("Recorded selection ID: \"sel_count_16\""))
                assertTrue(text().contains("Recorded role: \"recorded_16\""))
                click(R.string.findings_context_back)
                assertEquals("Page 2 of 2 · 17 selections · 17–17 displayed", pageHeader().text.toString())
                click(R.string.findings_context_previous)
                assertEquals("Page 1 of 2 · 17 selections · 1–16 displayed", pageHeader().text.toString())
            }
            assertEquals(count, report.findings.first().selectionContexts.size)
            navigation.retire()
        }
        assertEquals("pending count inspection", title().text.toString())
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
        assertNoDraftWrite()
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectionPageCountsKeepResourceLocaleAndExpandedRtlNavigation() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        title().setText("pending localized inspection")
        val single = show(countReport(1))
        assertEquals("Page ١ of ١ · ١ selection · ١–١ displayed", pageHeader().text.toString())
        single.retire()
        val report = countReport(17)
        show(report)
        assertEquals("Page ١ of ٢ · ١٧ selections · ١–١٦ displayed", pageHeader().text.toString())
        measure(dialog())
        assertEquals(View.LAYOUT_DIRECTION_RTL, dialog().window!!.decorView.layoutDirection)
        val header = pageHeader()
        val layout = requireNotNull(header.layout)
        repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
        assertTrue(header.height - header.compoundPaddingTop - header.compoundPaddingBottom >= layout.height)
        for (control in controls()) {
            assertLabelFits(control)
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
            val visible = Rect()
            assertTrue(control.getGlobalVisibleRect(visible))
            assertTrue(visible.height() >= control.height)
        }
        click(R.string.findings_context_next)
        assertEquals("Page ٢ of ٢ · ١٧ selections · ١٧–١٧ displayed", pageHeader().text.toString())
        measure(dialog())
        controls().forEach(::assertLabelFits)
        inspectSelection(17)
        assertTrue(text().contains("Recorded selection ID: \"sel_count_16\""))
        assertTrue(text().contains("Recorded role: \"recorded_16\""))
        assertEquals("sel_count_16", report.findings.first().selectionContexts.last().selectionId)
        assertEquals("pending localized inspection", title().text.toString())
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
        assertNoDraftWrite()
    }

    @Test fun actualSelectionContextKeepsRecordedRoleVersionAndDraftAuthority() {
        title().setText("pending local title")
        openActualContext()
        assertTrue(text().contains("Recorded build: \"build.fixture.1\", revision 1"))
        assertTrue(text().contains("Recorded catalog snapshot: \"catalog.fixture.1\""))
        inspectSelection(3)
        assertTrue(text().contains("Recorded selection ID: \"sel_model\""))
        assertTrue(text().contains("Kind: \"model\""))
        assertTrue(text().contains("Recorded role: \"primary\""))
        assertTrue(text().contains("Recorded item version: \"2026.10-demo\""))
        assertTrue(text().contains("Outcome: pass · Severity: error"))
        assertEquals("pending local title", title().text.toString())
        assertNoDraftWrite()
    }

    @Test fun acceptedMultiRoleContextNeverSubstitutesPrimaryOrPrivatePayloads() {
        val report = accepted { build, _ ->
            build.put("owner_ref", "private-owner-marker")
            build.put("extensions", JSONObject().put("x_private", "private-build-extension"))
            val other = JSONObject(model(build).toString()).put("id", "sel_model_fallback").put("role", "fallback")
                .put("catalog_item_version", "fallback\n\"v2\"\u202e").put("notes", "private-selection-notes")
                .put("extensions", JSONObject().put("x_private", "private-selection-extension"))
            other.getJSONObject("settings").put("credential_ref", "secret://private-test-reference")
            build.getJSONArray("selections").put(other)
        }
        show(report)
        inspectSelection(report.findings.first().selectionIds.indexOf("sel_model_fallback") + 1)
        val rendered = text()
        assertTrue(rendered.contains("Recorded role: \"fallback\""))
        assertTrue(rendered.contains("Recorded item version: \"fallback\\n\\\"v2\\\"\\u202e\""))
        assertFalse(rendered.contains("Recorded role: \"primary\""))
        for (marker in listOf("private-owner-marker", "private-build-extension", "private-selection-notes",
            "private-selection-extension", "private-test-reference")) assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains('\u202e'))
        assertTrue(views(dialog().window!!.decorView).filterIsInstance<TextView>().all { it.urls.isEmpty() })
        assertNoDraftWrite()
    }

    @Test fun ambiguousAcceptedSelectionRemainsUnavailableWithoutGuessingARole() {
        for (enabled in listOf(true, false)) {
            val report = accepted { build, _ ->
                build.getJSONArray("selections").put(JSONObject(model(build).toString()).put("role", "fallback").put("enabled", enabled))
            }
            val navigation = show(report)
            inspectSelection(report.findings.first().selectionIds.indexOf("sel_model") + 1)
            assertTrue(text().contains(activity.getString(R.string.findings_context_ambiguous)))
            assertFalse(text().contains("Recorded role:"))
            assertFalse(text().contains("Recorded item version:"))
            navigation.retire()
        }
        assertNoDraftWrite()
    }

    @Test fun acceptedLargeRevisionPreservesAllSupportedDigits() {
        val digits = "9".repeat(128)
        val report = accepted { build, result ->
            build.put("revision", BigInteger(digits))
            result.put("build_revision", BigInteger(digits))
        }
        show(report)
        inspectSelection(3)
        assertTrue(text().contains("revision " + digits))
        assertEquals(BigInteger(digits), report.buildRevision)
        assertNoDraftWrite()
    }

    @Test fun allAffectedSelectionsRemainReachableAcrossBoundedPages() {
        val report = accepted { build, _ ->
            val template = model(build)
            repeat(35) { index ->
                build.getJSONArray("selections").put(JSONObject(template.toString())
                    .put("id", "sel_model_extra_" + index.toString().padStart(2, '0'))
                    .put("role", "fallback_" + index).put("catalog_item_version", "recorded-" + index))
            }
        }
        show(report)
        val expected = report.findings.first().selectionIds
        val observed = mutableListOf<String>()
        for (page in 0 until 3) {
            val rows = controls().map { it.text.toString() }.filter { it.startsWith("Inspect selection ") }
            assertTrue(rows.size in 1..16)
            for (label in rows) {
                val index = label.substringAfter("Inspect selection ").toInt()
                button(label, dialog().window!!.decorView).performClick()
                idle()
                assertTrue(text().contains("Recorded selection ID: \"" + expected[index - 1] + "\""))
                observed += expected[index - 1]
                click(R.string.findings_context_back)
            }
            if (page < 2) click(R.string.findings_context_next)
        }
        assertEquals(expected, observed)
        assertEquals(expected.size, observed.toSet().size)
        assertFalse(controls().any { it.text.toString() == activity.getString(R.string.findings_context_next) })
        assertNoDraftWrite()
    }

    @Test fun cancelledFindingAndSelectionCallbacksCannotOpenDetailsOrPages() {
        val finding = openFinding()
        val oldOpen = finding.getButton(AlertDialog.BUTTON_NEUTRAL)
        finding.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        oldOpen.performClick()
        idle()
        assertSame(finding, dialog())
        assertFalse(finding.isShowing)

        val page = openActualContext()
        val oldRow = controls().first { it.text.toString().startsWith("Inspect selection ") }
        page.cancel()
        idle()
        oldRow.performClick()
        idle()
        assertSame(page, dialog())
        assertFalse(page.isShowing)
        assertNoDraftWrite()
    }

    @Test fun replacementInspectionAndSourceIdentityRevokeRetainedContextCallbacks() {
        val page = openActualContext()
        val oldRow = controls().first { it.text.toString().startsWith("Inspect selection ") }
        button(R.string.findings_inspect).performClick()
        assertFalse(page.isShowing)
        await { button(R.string.findings_browse).isEnabled }
        oldRow.performClick()
        idle()
        assertSame(page, dialog())

        val original = accepted()
        var source = original
        val navigation = show(original) { source === original }
        val sourcePage = dialog()
        val sourceRow = controls().first { it.text.toString().startsWith("Inspect selection ") }
        source = accepted() // The same wire identities do not revive another report object.
        sourceRow.performClick()
        idle()
        assertFalse(sourcePage.isShowing)
        source = original
        navigation.show()
        sourceRow.performClick()
        idle()
        assertSame(sourcePage, dialog())
        assertFalse(sourcePage.isShowing)

        // Deliver two actual receiving tasks in reverse order; only the current request can publish.
        val executor = QueuedExecutor()
        val section = FindingsInspectionSection(activity, executor) { true }
        val container = LinearLayout(activity)
        section.attach(container)
        val inspect = button(R.string.findings_inspect, container)
        val browse = button(R.string.findings_browse, container)
        inspect.performClick(); inspect.performClick()
        assertEquals(2, executor.tasks.size)
        executor.tasks[1].run()
        idle()
        assertTrue(browse.isEnabled)
        browse.performClick()
        val currentPicker = dialog()
        executor.tasks[0].run()
        idle()
        shadowOf(currentPicker).clickOnItem(0)
        idle()
        assertNotSame(currentPicker, dialog())
        assertTrue(dialog().isShowing)
        section.close()
        assertNoDraftWrite()
    }

    @Test fun actualPauseRetiresFindingsAndCapturedSelectionCallbacks() {
        AutosaveUiClock.pause()
        title().setText("pending survives findings pause")
        val page = openActualContext()
        val retainedRow = controls().first { it.text.toString().startsWith("Inspect selection ") }
        controller.pause()
        try {
            assertFalse("Paused Activity must retire its Findings window", page.isShowing)
            retainedRow.performClick()
            button(R.string.findings_inspect).performClick()
            button(R.string.findings_browse).performClick()
            idle()
            assertSame(page, dialog())
            assertFalse(page.isShowing)
        } finally {
            controller.resume()
        }
        retainedRow.performClick()
        button(R.string.findings_browse).performClick()
        idle()
        assertSame(page, dialog())
        assertFalse(page.isShowing)
        assertFalse(button(R.string.findings_browse).isEnabled)
        assertTrue(button(R.string.findings_inspect).isEnabled)
        assertEquals("pending survives findings pause", title().text.toString())
        val fresh = openActualContext()
        assertNotSame(page, fresh)
        assertTrue(fresh.isShowing)
        inspectSelection(3)
        assertTrue(text().contains("Recorded role: \"primary\""))
        assertNoDraftWrite()
    }

    @Test fun actualPauseRetiresFindingPickerAndDetailCallbacks() {
        AutosaveUiClock.pause()
        title().setText("pending picker and detail inspection")
        val before = editorState()
        val bytes = privateFiles()
        for (openDetail in listOf(false, true)) {
            button(R.string.findings_inspect).performClick()
            await { button(R.string.findings_browse).isEnabled }
            button(R.string.findings_browse).performClick()
            val picker = dialog()
            if (openDetail) { shadowOf(picker).clickOnItem(0); idle() }
            val previous = dialog()
            val oldOpen = if (openDetail) previous.getButton(AlertDialog.BUTTON_NEUTRAL) else null
            controller.pause()
            try {
                assertFalse("Pause retires the currently owned finding window", previous.isShowing)
                shadowOf(picker).clickOnItem(0)
                oldOpen?.performClick()
                idle()
                assertSame(previous, dialog())
                assertFalse(previous.isShowing)
            } finally { controller.resume() }
            shadowOf(picker).clickOnItem(0)
            oldOpen?.performClick()
            button(R.string.findings_browse).performClick()
            idle()
            assertSame(previous, dialog())
            assertFalse(previous.isShowing)
            assertFalse(button(R.string.findings_browse).isEnabled)
            assertTrue(button(R.string.findings_inspect).isEnabled)
            assertEquals(before, editorState())
            assertEquals(bytes, privateFiles())
        }
        assertTrue(openActualContext().isShowing)
        assertEquals(before, editorState())
        assertEquals(bytes, privateFiles())
        assertNoDraftWrite()
    }

    @Test fun pausedAndResumedActivityDiscardsQueuedFindingReports() {
        AutosaveUiClock.pause()
        saveTitle("saved before delayed findings")
        val bytes = privateFiles()
        assertTrue("The preservation check includes an explicitly saved local draft", bytes.isNotEmpty())
        title().setText("pending while findings are delayed")
        val before = editorState()
        for (deliverAfterResume in listOf(false, true)) {
            val release = blockWorker()
            var resumed = true
            try {
                button(R.string.findings_inspect).performClick()
                assertFalse(button(R.string.findings_inspect).isEnabled)
                assertTrue(views().filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.findings_loading) })
                controller.pause()
                resumed = false
                if (deliverAfterResume) { controller.resume(); resumed = true }
                release.countDown()
                finishFindingWork()
                assertTrue(button(R.string.findings_inspect).isEnabled)
                assertFalse(button(R.string.findings_browse).isEnabled)
                assertTrue(views().filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.findings_notice) })
            } finally {
                release.countDown()
                if (!resumed) controller.resume()
            }
            button(R.string.findings_browse).performClick()
            idle()
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            assertEquals(before, editorState())
            assertEquals(bytes, privateFiles())
        }
        assertTrue(openActualContext().isShowing)
        assertEquals(before, editorState())
        assertEquals(bytes, privateFiles())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun rejectedFindingDispatchAllowsExplicitRetryWithoutDraftWrites() {
        AutosaveUiClock.pause()
        saveTitle("saved before rejected findings")
        val bytes = privateFiles()
        assertTrue(bytes.isNotEmpty())
        title().setText("pending across rejected findings")
        val before = editorState()
        val executor = QueuedExecutor().apply { rejectNext = true }
        val section = FindingsInspectionSection(activity, executor) { true }
        val container = LinearLayout(activity)
        section.attach(container)
        val inspect = button(R.string.findings_inspect, container)
        val browse = button(R.string.findings_browse, container)
        try {
            inspect.performClick()
            assertEquals(0, executor.tasks.size)
            assertTrue(inspect.isEnabled)
            assertFalse(browse.isEnabled)
            assertTrue(views(container).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.findings_rejected, "UNAVAILABLE") })
            assertFalse(views(container).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.findings_loading) })
            browse.performClick()
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)

            inspect.performClick()
            assertEquals(1, executor.tasks.size)
            executor.tasks.single().run()
            idle()
            assertTrue(browse.isEnabled)
            browse.performClick()
            val picker = dialog()
            shadowOf(picker).clickOnItem(0)
            idle()
            assertNotSame(picker, dialog())
            assertTrue(dialog().isShowing)
            assertTrue(text().contains("Outcome: pass · Severity: error"))

            inspect.performClick()
            assertEquals(2, executor.tasks.size)
            val retired = dialog()
            section.close()
            executor.tasks.last().run()
            inspect.performClick()
            browse.performClick()
            idle()
            assertEquals(2, executor.tasks.size)
            assertFalse(inspect.isEnabled)
            assertFalse(browse.isEnabled)
            assertSame(retired, dialog())
            assertFalse(retired.isShowing)
            assertEquals(before, editorState())
            assertEquals(bytes, privateFiles())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { section.close(); executor.shutdownNow() }
    }

    @Test fun destroyedActivityRetiresRecordedSelectionWindowsAndCallbacks() {
        title().setText("pending survives recreation")
        val page = openActualContext()
        val retainedRow = controls().first { it.text.toString().startsWith("Inspect selection ") }
        recreate()
        assertFalse(page.isShowing)
        retainedRow.performClick()
        idle()
        assertSame(page, dialog())
        assertFalse(page.isShowing)
        assertEquals("pending survives recreation", title().text.toString())
        assertFalse(button(R.string.findings_browse).isEnabled)
        assertNoDraftWrite()
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlLargeTextContextActionsRemainVisibleAndComplete() {
        RuntimeEnvironment.setFontScale(2f)
        recreate()
        val finding = openFinding()
        measure(finding)
        val open = finding.getButton(AlertDialog.BUTTON_NEUTRAL)
        assertEquals("Selections", open.text.toString())
        assertLabelFits(open)
        open.performClick()
        idle()
        measure(dialog())
        assertEquals(View.LAYOUT_DIRECTION_RTL, dialog().window!!.decorView.layoutDirection)
        for (control in controls()) {
            assertLabelFits(control)
            control.requestRectangleOnScreen(Rect(0, 0, control.width, control.height), true)
            val visible = Rect()
            assertTrue(control.getGlobalVisibleRect(visible))
            assertTrue(visible.height() >= control.height)
        }
        inspectSelection(3)
        measure(dialog())
        controls().forEach(::assertLabelFits)
        assertTrue(text().contains("Recorded role: \"primary\""))
        assertNoDraftWrite()
    }

    private fun measure(window: AlertDialog) {
        val decor = window.window!!.decorView
        val metrics = activity.resources.displayMetrics
        val width = (320 * metrics.density).toInt()
        val height = (640 * metrics.density).toInt()
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        idle()
    }
    private fun assertLabelFits(control: Button) {
        val dp48 = (48 * activity.resources.displayMetrics.density).toInt()
        assertTrue(control.width >= dp48)
        assertTrue(control.height >= dp48)
        val layout = requireNotNull(control.layout)
        assertTrue(layout.lineCount > 0)
        repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
        assertTrue(control.height - control.compoundPaddingTop - control.compoundPaddingBottom >= layout.height)
    }

    private class QueuedExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        var rejectNext = false
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped || rejectNext) { rejectNext = false; throw RejectedExecutionException() }
            tasks += command
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
