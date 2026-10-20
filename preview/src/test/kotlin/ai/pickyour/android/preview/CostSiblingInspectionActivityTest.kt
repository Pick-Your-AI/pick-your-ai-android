package ai.pickyour.android.preview

import android.app.AlertDialog
import android.content.Intent
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.PreviewCurrentDraftAnalysis
import ai.pickyour.core.preview.PreviewCurrentDraftState
import ai.pickyour.core.preview.PreviewDraftLibrary
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewStoredDraft
import org.json.JSONObject
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
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Activity controls, saved draft and retained archive in simulation; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CostSiblingInspectionActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val archivePath get() = activity.filesDir.toPath().resolve("retained-snapshots")

    @Before fun launch() {
        AutosaveUiClock.pause()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
        button(R.string.save_draft).performClick()
        await { state() is PreviewCurrentDraftState.Current }
        finishWork()
    }

    @After fun close() { controller.pause().stop().destroy() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = PreviewActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun worker() = field<ExecutorService>("worker")
    private fun state(): PreviewCurrentDraftState {
        val section = field<CurrentDraftCheckSection>("currentChecks")
        return (CurrentDraftCheckSection::class.java.getDeclaredField("analysis")
            .apply { isAccessible = true }.get(section) as PreviewCurrentDraftAnalysis).state
    }
    private fun idle() = AutosaveUiClock.idle()
    private fun finishWork() { repeat(2) { idle(); worker().submit {}.get(15, TimeUnit.SECONDS); idle() } }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            idle()
            if (condition()) return
            val remaining = deadline - System.nanoTime()
            assertTrue("The explicit Activity state must arrive within the owned-work bound", remaining > 0)
            worker().submit {}.get(remaining, TimeUnit.NANOSECONDS)
        }
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }
    private fun button(label: Int, root: View = activity.window.decorView): Button =
        button(activity.getString(label), root)
    private fun button(text: String, root: View) = views(root).filterIsInstance<Button>()
        .single { it.text.toString() == text }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        .also { assertTrue("The current inspection must be showing", it.isShowing) }
    private fun choose(window: AlertDialog, index: Int = 0) {
        shadowOf(window).clickOnItem(index); idle()
    }
    private fun openCurrent(): AlertDialog {
        val inspect = activity.findViewById<Button>(R.id.current_draft_check_inspect)
        assertTrue(inspect.isEnabled)
        inspect.performClick(); idle()
        return dialog().also { assertTrue(it.listView.adapter.count in 1..10) }
    }
    private fun openSnapshot(): AlertDialog {
        val before = ShadowAlertDialog.getLatestAlertDialog()
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.let { it !== before && it.isShowing } == true }
        return dialog().also { assertEquals(1, it.listView.adapter.count) }
    }
    private fun snapshotReport(inventory: AlertDialog = openSnapshot()): AlertDialog {
        choose(inventory)
        val report = dialog()
        await { report.findViewById<TextView>(R.id.snapshot_report).text.contains("Result in this profile:") }
        assertTrue(report.findViewById<TextView>(R.id.snapshot_report).text.contains("build.sibling.inspection"))
        return report
    }
    private fun openResource(): AlertDialog {
        if (!button(R.string.resource_inspection_browse).isEnabled) {
            button(R.string.resource_inspection_open).performClick()
            await { button(R.string.resource_inspection_browse).isEnabled }
        }
        button(R.string.resource_inspection_browse).performClick(); idle()
        return dialog().also { assertEquals(4, it.listView.adapter.count) }
    }
    private fun openFixedFindings(): AlertDialog {
        if (!button(R.string.findings_browse).isEnabled) {
            button(R.string.findings_inspect).performClick()
            await { button(R.string.findings_browse).isEnabled }
        }
        button(R.string.findings_browse).performClick(); idle()
        return dialog().also { assertTrue(it.listView.adapter.count > 0) }
    }
    private fun fixedContext(): AlertDialog {
        choose(openFixedFindings())
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        return dialog().also {
            assertTrue(button(activity.getString(R.string.findings_context_inspect, 1), it.window!!.decorView).isEnabled)
        }
    }
    private fun openHelp(): AlertDialog {
        button(R.string.help_open).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<Button>(R.id.help_bug)) }
    }
    private fun helpDestination(destination: HelpDestination = HelpDestination.BUG): AlertDialog {
        openHelp().findViewById<Button>(destination.control).performClick(); idle()
        return dialog().also {
            assertEquals(destination.url, it.findViewById<TextView>(R.id.help_destination).text.toString())
        }
    }
    private fun openDiagnostics(): AlertDialog {
        button(R.string.diagnostics_review).performClick(); idle()
        return dialog().also { assertNotNull(it.findViewById<TextView>(R.id.diagnostic_report)) }
    }
    private fun unchangedWindow(expected: AlertDialog) {
        idle(); assertSame(expected, dialog()); assertTrue(expected.isShowing)
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun files(root: Path): Map<String, String> = if (!Files.exists(root)) emptyMap() else Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to digest(Files.readAllBytes(it)) }
    }
    private fun preservingSavedState(inspect: () -> Unit) {
        finishWork()
        val original = state() as PreviewCurrentDraftState.Current
        val saved = field<PreviewStoredDraft>("savedRecord")
        val draftBytes = files(activity.noBackupFilesDir.toPath())
        val archiveBytes = files(archivePath)
        val status = activity.findViewById<TextView>(R.id.current_draft_check_status).text.toString()
        val affected = activity.findViewById<TextView>(R.id.current_draft_check_affected).text.toString()
        inspect(); finishWork()
        assertSame(original, state())
        assertSame(original.request.binding, (state() as PreviewCurrentDraftState.Current).request.binding)
        assertSame(saved, field<PreviewStoredDraft>("savedRecord"))
        assertEquals(status, activity.findViewById<TextView>(R.id.current_draft_check_status).text.toString())
        assertEquals(affected, activity.findViewById<TextView>(R.id.current_draft_check_affected).text.toString())
        assertEquals(draftBytes, files(activity.noBackupFilesDir.toPath()))
        assertEquals(archiveBytes, files(archivePath))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    private fun document(name: String) = activity.assets.open("$name.json").bufferedReader(Charsets.UTF_8)
        .use { JSONObject(it.readText()) }
    private fun artifact(name: String): DraftProjectionArtifact {
        val document = document(name)
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        return DraftProjectionArtifact(document.getString("id"), document.get(if (name == "ruleset") "version" else "revision").toString(),
            ArtifactHash(digest(bytes)), bytes)
    }
    /** Explicit synthetic caller inputs archived through the real candidate/acknowledgement boundary. */
    private fun saveSnapshot() {
        val build = document("build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"), integer("avg_input_tokens"),
            integer("avg_output_tokens"), integer("max_concurrency"), integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections")
        val links = build.getJSONArray("links")
        val content = DraftContent("private sibling fixture", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map { LocalSelection.fromJson(selections.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) },
                (0 until links.length()).map { LocalSelectionLink.fromJson(links.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) }))
        val source = LocalDraftStore(Files.createTempDirectory(activity.filesDir.toPath(), "sibling-snapshot-source-"))
        val stored = (source.save(null, content) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.sibling.inspection", BigInteger.ONE, "Explicit sibling snapshot", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset"), artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        assertTrue(source.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
            is DraftSnapshotArchival.Archived)
    }

    private class Captured(val window: AlertDialog, val replay: () -> Unit)

    private fun member(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun cost() = field<CostInspectionSection>("costInspection")
    private fun resource() = field<ResourceInspectionSection>("resourceInspection")
    private fun inline(owner: Any) = (member(owner, "output") as TextView).text.toString()
    private fun saved() = (readDraftAfterOwnedWork(activity) {
        PreviewDraftLibrary(activity.noBackupFilesDir.toPath()).openLegacy()
    } as PreviewDraftLoad.Available).record
    private fun input(label: Int): EditText {
        val caption = views().filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
        return activity.findViewById(caption.labelFor)
    }

    private fun openCost(): AlertDialog {
        if (!button(R.string.cost_inspection_browse).isEnabled) {
            button(R.string.cost_inspect).performClick()
            await { button(R.string.cost_inspection_browse).isEnabled }
        }
        button(R.string.cost_inspection_browse).performClick(); idle()
        return dialog().also { assertEquals(5, it.listView.adapter.count) }
    }

    private fun captureCost(kind: String): Captured {
        val menu = openCost()
        if (kind == "menu") return Captured(menu) { choose(menu) }
        choose(menu, 4)
        val fields = dialog()
        if (kind == "fields") return Captured(fields) { choose(fields, 1) }
        check(kind == "value")
        choose(fields, 1)
        val value = dialog()
        val back = value.getButton(AlertDialog.BUTTON_NEGATIVE)
        val next = value.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(View.VISIBLE, next.visibility)
        return Captured(value) { back.callOnClick(); next.callOnClick(); idle() }
    }

    private fun captureResource(kind: String): Captured {
        val menu = openResource()
        if (kind == "menu") return Captured(menu) { choose(menu) }
        choose(menu, 3)
        val fields = dialog()
        if (kind == "fields") return Captured(fields) { choose(fields, 1) }
        check(kind == "value")
        choose(fields, 1)
        val value = dialog()
        val back = value.getButton(AlertDialog.BUTTON_NEGATIVE)
        val next = value.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(View.VISIBLE, next.visibility)
        return Captured(value) { back.callOnClick(); next.callOnClick(); idle() }
    }

    private fun retiredAcrossClose(old: Captured, current: AlertDialog) {
        assertFalse("A new sibling must retire the old inspection", old.window.isShowing)
        old.replay(); unchangedWindow(current)
        current.dismiss(); idle()
        old.replay(); idle()
        assertSame(current, ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(current.isShowing)
    }

    @Test fun resourceEntryRetiresCostRowsBackAndNextBeforeAndAfterItsOwnClose() {
        preservingSavedState {
            for (kind in listOf("menu", "fields", "value")) {
                val old = captureCost(kind)
                val token = member(cost(), "request"); val record = member(cost(), "record")
                val text = inline(cost())
                assertNotNull(token); assertNotNull(record)
                retiredAcrossClose(old, openResource())
                assertSame(token, member(cost(), "request")); assertSame(record, member(cost(), "record"))
                assertEquals(text, inline(cost()))
                assertTrue(button(R.string.cost_inspection_browse).isEnabled)
                val fresh = openCost()
                old.replay(); unchangedWindow(fresh)
                choose(fresh); assertTrue(dialog().listView.adapter.count > 0)
            }
        }
    }

    @Test fun costEntryRetiresResourceRowsBackAndNextAndPreservesBothCapturedRecords() {
        preservingSavedState {
            for (kind in listOf("menu", "fields", "value")) {
                val old = captureResource(kind)
                val token = member(resource(), "request"); val record = member(resource(), "record")
                val text = inline(resource())
                retiredAcrossClose(old, openCost())
                assertSame(token, member(resource(), "request")); assertSame(record, member(resource(), "record"))
                assertEquals(text, inline(resource()))
                assertTrue(button(R.string.resource_inspection_browse).isEnabled)
                val fresh = openResource()
                old.replay(); unchangedWindow(fresh)
                choose(fresh); assertTrue(dialog().listView.adapter.count > 0)
            }
        }
    }

    @Test fun currentFindingsAndCostMutuallyRetireRowsAndDetailControls() {
        preservingSavedState {
            for (kind in listOf("menu", "fields", "value")) {
                val oldCost = captureCost(kind)
                retiredAcrossClose(oldCost, openCurrent())
                val page = openCurrent()
                retiredAcrossClose(Captured(page) { choose(page) }, openCost())
                val detailPage = openCurrent(); choose(detailPage)
                val detail = dialog()
                val back = detail.getButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals(activity.getString(R.string.current_check_back), back.text.toString())
                val freshCost = openCost()
                retiredAcrossClose(Captured(detail) { back.callOnClick(); idle() }, freshCost)
                val fresh = openCurrent(); choose(fresh)
                assertTrue(shadowOf(dialog()).message.toString().contains("rule."))
            }
        }
    }

    @Test fun fixedFindingsAndCostMutuallyRetireSelectionChildrenWithoutDiscardingInlineReports() {
        preservingSavedState {
            val oldCost = captureCost("value")
            retiredAcrossClose(oldCost, openFixedFindings())
            val accepted = member(field<FindingsInspectionSection>("findings"), "accepted")
            assertNotNull(accepted)
            val output = inline(field<FindingsInspectionSection>("findings"))
            val picker = openFixedFindings()
            retiredAcrossClose(Captured(picker) { choose(picker) }, openCost())
            val contexts = fixedContext()
            val inspect = button(activity.getString(R.string.findings_context_inspect, 1), contexts.window!!.decorView)
            inspect.performClick(); idle()
            val detail = dialog()
            val back = button(R.string.findings_context_back, detail.window!!.decorView)
            retiredAcrossClose(Captured(detail) { inspect.callOnClick(); back.callOnClick(); idle() }, openCost())
            assertFalse(contexts.isShowing)
            assertSame(accepted, member(field<FindingsInspectionSection>("findings"), "accepted"))
            assertEquals(output, inline(field<FindingsInspectionSection>("findings")))
            assertTrue(button(R.string.findings_browse).isEnabled)
            val fresh = fixedContext()
            inspect.callOnClick(); back.callOnClick(); unchangedWindow(fresh)
        }
    }

    @Test fun retainedSnapshotChildrenAndCostMutuallyRetireWithoutWritingTheArchive() {
        saveSnapshot()
        preservingSavedState {
            val oldCost = captureCost("value")
            val report = snapshotReport()
            assertFalse(oldCost.window.isShowing)
            oldCost.replay(); unchangedWindow(report)
            val action = report.findViewById<Button>(R.id.snapshot_findings)
            assertTrue(action.isEnabled); action.performClick(); idle()
            val picker = dialog(); choose(picker, 1)
            val contexts = dialog()
            val inspect = button(activity.getString(R.string.findings_context_inspect, 1), contexts.window!!.decorView)
            inspect.performClick(); idle()
            val detail = dialog()
            val back = button(R.string.findings_context_back, detail.window!!.decorView)
            val retry = report.getButton(AlertDialog.BUTTON_NEUTRAL)
            val replacement = openCost()
            assertFalse(report.isShowing); assertFalse(contexts.isShowing); assertFalse(detail.isShowing)
            val old = Captured(detail) {
                action.callOnClick(); choose(picker, 1); inspect.callOnClick(); back.callOnClick()
                retry.callOnClick(); finishWork()
            }
            retiredAcrossClose(old, replacement)
            val fresh = snapshotReport()
            assertTrue(fresh.findViewById<Button>(R.id.snapshot_findings).isEnabled)
            oldCost.replay(); unchangedWindow(fresh)
            fresh.findViewById<Button>(R.id.snapshot_findings).performClick(); choose(dialog(), 1)
            assertTrue(views(dialog().window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString().contains("build.sibling.inspection")
            })
        }
    }

    @Test fun helpAndDiagnosticsRetireCostAndTheirOldExternalActionsCannotReturnOverCost() {
        preservingSavedState {
            for (diagnostic in listOf(false, true)) {
                val oldCost = captureCost("value")
                val sibling = if (diagnostic) openDiagnostics() else helpDestination()
                retiredAcrossClose(oldCost, sibling)
                val old = if (diagnostic) openDiagnostics() else helpDestination()
                val external = old.findViewById<Button>(if (diagnostic) R.id.diagnostic_share else R.id.help_external_open)
                assertTrue(external.isEnabled)
                retiredAcrossClose(Captured(old) { external.callOnClick(); idle() }, openCost())
                assertNull(shadowOf(activity).nextStartedActivity)
                val fresh = if (diagnostic) openDiagnostics() else helpDestination()
                external.callOnClick(); unchangedWindow(fresh)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        }
    }

    @Test fun costEntryCancelsLateSnapshotInventoryAndPreservesThePendingAutosaveDeadline() {
        saveSnapshot()
        openCost().dismiss(); idle()
        val original = saved(); val baseline = original.baselineIdentity
        val before = files(activity.noBackupFilesDir.toPath()); val archive = files(archivePath)
        input(R.string.draft_title).setText("pending cost sibling deadline")
        val started = SystemClock.uptimeMillis()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            activity.findViewById<Button>(R.id.snapshot_open).performClick()
            assertEquals(activity.getString(R.string.snapshot_inventory_loading),
                activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
            val current = openCost()
            release.countDown(); finishWork(); unchangedWindow(current)
            assertEquals(activity.getString(R.string.snapshot_notice),
                activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
            assertEquals("pending cost sibling deadline", input(R.string.draft_title).text.toString())
            assertEquals(started, SystemClock.uptimeMillis())
            assertEquals(baseline, saved().baselineIdentity)
            assertEquals(before, files(activity.noBackupFilesDir.toPath()))
            assertEquals(archive, files(archivePath))
            shadowOf(Looper.getMainLooper()).idleFor(749, TimeUnit.MILLISECONDS); finishWork()
            assertEquals(baseline, saved().baselineIdentity)
            assertEquals(before, files(activity.noBackupFilesDir.toPath()))
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            await { !button(R.string.save_draft).isEnabled && state() is PreviewCurrentDraftState.Current }
            finishWork()
            val after = saved()
            assertEquals(original.revision + 1, after.revision)
            assertEquals("pending cost sibling deadline", after.fields.title)
            val acknowledged = files(activity.noBackupFilesDir.toPath())
            shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS); finishWork()
            assertEquals(after.baselineIdentity, saved().baselineIdentity)
            assertEquals(acknowledged, files(activity.noBackupFilesDir.toPath()))
            assertEquals(archive, files(archivePath))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { release.countDown() }
    }

    @Test fun siblingEntryPreservesThePendingCostReadButNeverOpensItsWindowOnCompletion() {
        preservingSavedState {
            openResource().dismiss(); idle()
            val resourceRecord = member(resource(), "record"); val resourceText = inline(resource())
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            worker().execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                button(R.string.cost_inspect).performClick()
                val attempt = member(cost(), "request")
                assertNotNull(attempt); assertNull(member(cost(), "record"))
                assertEquals(activity.getString(R.string.cost_loading), inline(cost()))
                val current = openResource()
                assertSame(attempt, member(cost(), "request"))
                assertEquals(activity.getString(R.string.cost_loading), inline(cost()))
                assertFalse(button(R.string.cost_inspection_browse).isEnabled)
                release.countDown(); finishWork()
                await { button(R.string.cost_inspection_browse).isEnabled }
                unchangedWindow(current)
                assertSame(attempt, member(cost(), "request")); assertNotNull(member(cost(), "record"))
                assertNull(member(cost(), "browser"))
                assertSame(resourceRecord, member(resource(), "record")); assertEquals(resourceText, inline(resource()))
                val next = openCost()
                assertFalse(current.isShowing); assertTrue(next.isShowing)
            } finally { release.countDown() }
        }
    }
}
