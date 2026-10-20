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
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.*
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
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Views and real local archive/evaluator in Robolectric; no installed-device qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SnapshotEvaluationActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val fixed = LocalSnapshotEvaluator(Clock.fixed(Instant.parse("2026-10-18T22:00:00Z"), ZoneOffset.UTC))
    private val sections = mutableListOf<SnapshotEvaluationSection>()
    private val archivePath get() = activity.filesDir.toPath().resolve("retained-snapshots")
    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
    }
    @After fun close() { sections.forEach { it.close() }; controller.pause().stop().destroy() }
    private fun idle() = AutosaveUiClock.idle()
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) { idle(); if (check()) return; Thread.sleep(10) }
        fail("Expected explicit native state did not arrive")
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root); if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun field(label: Int): EditText = activity.findViewById(views().filterIsInstance<TextView>()
        .single { it.text.toString() == activity.getString(label) }.labelFor)
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun report(dialog: AlertDialog = dialog()) = dialog.findViewById<TextView>(R.id.snapshot_report).text.toString()
    private fun findingAction(dialog: AlertDialog): Button {
        val action = views(requireNotNull(dialog.window).decorView).filterIsInstance<Button>()
            .singleOrNull { it.text.toString() == "Inspect affected selections" }
        assertNotNull("A current retained report must expose its recorded affected-selection route", action)
        return requireNotNull(action)
    }
    private fun dialogText(dialog: AlertDialog = dialog()) = views(requireNotNull(dialog.window).decorView)
        .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun selectionButton(dialog: AlertDialog = dialog(), index: Int = 1) =
        views(requireNotNull(dialog.window).decorView).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.findings_context_inspect, index)
        }
    private fun choose(index: Int = 0, dialog: AlertDialog = dialog()) {
        dialog.listView.performItemClick(null, index, index.toLong()); idle()
    }
    private fun files(path: Path): Map<String, String> = if (!Files.exists(path)) emptyMap() else Files.walk(path).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path.relativize(it).toString() to hash(Files.readAllBytes(it)).hex }
    }
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun node(name: String): JSONObject = activity.assets.open("$name.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun artifact(name: String, node: JSONObject = node(name)): DraftProjectionArtifact {
        val bytes = node.toString().toByteArray(Charsets.UTF_8)
        return DraftProjectionArtifact(node.getString("id"), node.get(if (name == "ruleset") "version" else "revision").toString(), hash(bytes), bytes)
    }
    private class Saved(val sourcePath: Path, val archive: LocalSnapshotArchive, val entry: SnapshotArchiveEntry)
    /** Explicit test caller inputs from the pinned synthetic fixture; production never calls this. */
    private fun save(id: String = "build.native.snapshot", revision: BigInteger = BigInteger.ONE,
        rules: JSONObject = node("ruleset")): Saved {
        val build = node("build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"), integer("avg_input_tokens"),
            integer("avg_output_tokens"), integer("max_concurrency"), integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections")
        val links = build.getJSONArray("links")
        val content = DraftContent("private archived text", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map { LocalSelection.fromJson(bytes = selections.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) },
                (0 until links.length()).map { LocalSelectionLink.fromJson(bytes = links.getJSONObject(it).toString().toByteArray(Charsets.UTF_8)) }))
        val sourcePath = Files.createTempDirectory(activity.filesDir.toPath(), "snapshot-test-source-")
        val source = LocalDraftStore(sourcePath)
        val stored = (source.save(null, content, mapOf("private" to "private archived metadata")) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity(id, revision, "Explicit test snapshot", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val input = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset", rules), artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, input) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val result = source.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
            as DraftSnapshotArchival.Archived
        return Saved(sourcePath, archive, result.entry)
    }
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) { if (reject) throw RejectedExecutionException(); tasks.addLast(command) }
        fun run() {
            val failure = AtomicReference<Throwable>()
            val task = tasks.removeFirst()
            val thread = Thread { try { task.run() } catch (error: Throwable) { failure.set(error) } }
            thread.start(); thread.join(20_000)
            assertFalse("Worker must finish before the test controls UI delivery", thread.isAlive)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        }
    }
    private class Harness(val section: SnapshotEvaluationSection, val controls: LinearLayout, val worker: Queue) {
        fun open() { controls.findViewById<Button>(R.id.snapshot_open).performClick() }
        fun status() = controls.findViewById<TextView>(R.id.snapshot_status).text.toString()
    }
    private fun harness(evaluator: SnapshotEvaluator = fixed): Harness {
        val queue = Queue()
        val controls = LinearLayout(activity)
        val section = SnapshotEvaluationSection(activity, queue, evaluator, { archivePath }) { true }
        section.attach(controls); sections += section
        return Harness(section, controls, queue)
    }
    private fun inventory(harness: Harness) { harness.open(); harness.worker.run(); idle() }
    private fun complete(harness: Harness, index: Int = 0): AlertDialog {
        choose(index); val shown = dialog(); harness.worker.run(); idle()
        assertTrue(report(shown).contains("Result in this profile:")); return shown
    }

    @Test fun actualActivityMissingArchiveRemainsMissingAndPendingEditorIsPreserved() {
        // Archive inspection is asserted before the separate draft autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("unsaved private editor text")
        val before = files(activity.noBackupFilesDir.toPath())
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { activity.findViewById<TextView>(R.id.snapshot_status).text.contains("NOT_FOUND") }
        assertFalse(Files.exists(archivePath))
        assertEquals(before, files(activity.noBackupFilesDir.toPath()))
        assertEquals("unsaved private editor text", field(R.string.context_objective).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun actualActivityReadsExactRetainedInputsAfterSourceDeletionWithoutEvaluatingPendingEdits() {
        val wide = BigInteger("9223372036854775808")
        val saved = save(revision = wide)
        Files.walk(saved.sourcePath).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        // Archive inspection is asserted before the separate draft autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending editor sentinel")
        val before = files(activity.noBackupFilesDir.toPath()); val archiveBefore = files(archivePath)
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        choose()
        await { report().contains("Result in this profile:") }
        val text = report()
        for (expected in listOf(saved.entry.key.id, wide.toString(), saved.entry.bundleHash.hex, RetainedEvaluationProfile.ID,
            "Fixed reference report time: 2026-10-16T09:00:00Z", "RESOURCE_ESTIMATE: NOT_SUPPLIED", "COST_ESTIMATE: NOT_SUPPLIED",
            "Outcome: unknown", "Severity: error", "unknown:", "skipped:")) assertTrue(expected, text.contains(expected))
        for (absent in listOf("pending editor sentinel", "private archived text", "private archived metadata", "Readiness in this profile: ready"))
            assertFalse(absent, text.contains(absent))
        assertEquals(before, files(activity.noBackupFilesDir.toPath())); assertEquals(archiveBefore, files(archivePath))
        assertEquals("pending editor sentinel", field(R.string.context_objective).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, dialog().findViewById<TextView>(R.id.snapshot_report).accessibilityLiveRegion)
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun actualRetainedFindingNavigationKeepsRecordedIdentityAndPendingEdits() {
        val wide = BigInteger("9223372036854775808")
        val saved = save(revision = wide)
        Files.walk(saved.sourcePath).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        field(R.string.context_objective).setText("pending finding-navigation sentinel")
        val before = files(activity.noBackupFilesDir.toPath()); val archiveBefore = files(archivePath)
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        choose(); await { report().contains("Result in this profile:") }
        val retained = dialog()
        assertTrue(report(retained).contains(saved.entry.bundleHash.hex))
        findingAction(retained).performClick()
        val findings = dialog()
        assertTrue(findings.listView.adapter.getItem(1).toString().contains("rule.model.provider.match"))
        choose(1, findings)
        val contexts = dialog()
        for (expected in listOf(saved.entry.key.id, wide.toString(), "rule.model.provider.match", "sel_model", "sel_model_provider"))
            assertTrue(expected, dialogText(contexts).contains(expected))
        views(requireNotNull(contexts.window).decorView).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.findings_context_inspect, 1)
        }.performClick()
        for (expected in listOf(saved.entry.key.id, wide.toString(), "sel_model", "cat.model.fixture", "2026.10-demo", "primary"))
            assertTrue(expected, dialogText().contains(expected))
        assertFalse(dialogText().contains("pending finding-navigation sentinel"))
        assertEquals(before, files(activity.noBackupFilesDir.toPath())); assertEquals(archiveBefore, files(archivePath))
        assertEquals("pending finding-navigation sentinel", field(R.string.context_objective).text.toString())
        assertTrue(button(R.string.save_draft).isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue("Navigation stays before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun findingNavigationRequiresCurrentAcceptedResultAndFreshRetry() {
        save(); val harness = harness(); inventory(harness); choose()
        val shown = dialog(); val action = findingAction(shown)
        assertFalse(action.isEnabled)
        action.performClick(); idle(); assertSame(shown, dialog())
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); harness.worker.run(); idle()
        assertTrue(report(shown).contains("Inspection cancelled")); assertFalse(action.isEnabled)
        action.performClick(); idle(); assertSame(shown, dialog())
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        assertFalse(action.isEnabled)
        harness.worker.run(); idle(); assertTrue(action.isEnabled)
        action.performClick()
        assertTrue(dialog().listView.adapter.count > 1)
        choose(1); assertTrue(dialogText().contains("rule.model.provider.match"))
    }

    @Test fun retryAndDismissRetireRetainedFindingControls() {
        save(); val before = files(archivePath)
        val harness = harness(); inventory(harness); val shown = complete(harness)
        val action = findingAction(shown); action.performClick()
        val oldPicker = dialog(); choose(1, oldPicker)
        val oldContext = dialog(); val oldSelection = selectionButton(oldContext)
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        assertFalse(oldContext.isShowing); assertFalse(action.isEnabled)
        oldSelection.performClick(); choose(0, oldPicker)
        assertSame(oldContext, dialog()); assertEquals(1, harness.worker.tasks.size)
        harness.worker.run(); idle(); action.performClick()
        val freshPicker = dialog()
        choose(0, oldPicker)
        assertSame(freshPicker, dialog()); assertTrue(freshPicker.isShowing)
        choose(1, freshPicker); selectionButton().performClick()
        val detail = dialog()
        val back = views(requireNotNull(detail.window).decorView).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.findings_context_back)
        }
        shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(detail.isShowing); assertFalse(shown.isShowing)
        back.performClick(); oldSelection.performClick(); action.performClick(); choose(0, freshPicker)
        assertSame(detail, dialog()); assertFalse(detail.isShowing)
        assertEquals(0, harness.worker.tasks.size); assertEquals(before, files(archivePath))
    }

    @Test fun switchingRetainedRevisionsRetiresCapturedFindingSelectionCallbacks() {
        save(revision = BigInteger.ONE); save(revision = BigInteger.TWO)
        val before = files(archivePath); val harness = harness()
        inventory(harness); val old = complete(harness)
        val oldAction = findingAction(old); oldAction.performClick()
        val oldPicker = dialog(); choose(1, oldPicker)
        val oldContext = dialog(); val oldSelection = selectionButton(oldContext)
        assertTrue(dialogText(oldContext).contains("Recorded build: \"build.native.snapshot\", revision 1"))
        inventory(harness); val fresh = complete(harness, 1)
        assertFalse(old.isShowing); assertFalse(oldContext.isShowing)
        oldAction.performClick(); oldSelection.performClick(); choose(0, oldPicker)
        assertSame(fresh, dialog()); assertEquals(0, harness.worker.tasks.size)
        findingAction(fresh).performClick(); choose(1); selectionButton().performClick()
        assertTrue(dialogText().contains("Recorded build: \"build.native.snapshot\", revision 2"))
        assertFalse(dialogText().contains("Recorded build: \"build.native.snapshot\", revision 1"))
        assertTrue(dialogText().contains("Recorded role: \"primary\""))
        assertTrue(dialogText().contains("Recorded item version: \"2026.10-demo\""))
        assertEquals(before, files(archivePath))
    }

    @Test fun actualActivityPauseAndRecreationRetireRetainedFindingNavigation() {
        save(); val before = files(archivePath)
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        choose(); await { report().contains("Result in this profile:") }
        val old = dialog(); val action = findingAction(old); action.performClick()
        val picker = dialog(); choose(1, picker)
        val context = dialog(); val selection = selectionButton(context)
        selection.performClick(); val detail = dialog()
        val state = Bundle(); controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && button(R.string.save_draft).isEnabled }
        action.performClick(); selection.performClick(); choose(0, picker)
        assertFalse(old.isShowing); assertFalse(context.isShowing); assertFalse(detail.isShowing)
        assertSame(detail, dialog())
        assertEquals(activity.getString(R.string.snapshot_notice), activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        choose(); await { report().contains("Result in this profile:") }
        findingAction(dialog()).performClick(); choose(1); selectionButton().performClick()
        assertTrue(dialogText().contains("Recorded build: \"build.native.snapshot\", revision 1"))
        assertEquals(before, files(archivePath)); assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun retainedFindingEntryRemainsReachableWithLargeFontInNarrowRtl() {
        RuntimeEnvironment.setFontScale(2f)
        val state = Bundle(); controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled && button(R.string.save_draft).isEnabled }
        save(); val harness = harness(); inventory(harness); val shown = complete(harness)
        val root = requireNotNull(shown.window).decorView
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt(); val height = (640 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val action = findingAction(shown)
        assertEquals(View.LAYOUT_DIRECTION_RTL, action.layoutDirection)
        assertTrue(action.height >= (48 * density).toInt())
        val layout = requireNotNull(action.layout)
        repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
        assertTrue(action.height - action.compoundPaddingTop - action.compoundPaddingBottom >= layout.height)
        action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
        val visible = Rect(); assertTrue(action.getGlobalVisibleRect(visible))
        assertTrue(visible.height() >= action.height)
        action.performClick(); choose(1); selectionButton().performClick()
        assertTrue(dialogText().contains("Recorded role: \"primary\""))
        assertEquals(View.LAYOUT_DIRECTION_RTL, dialog().window!!.decorView.layoutDirection)
    }

    @Test fun emptyAndPartiallyLostNamespacesAreExplicitWithoutIndexOrSealRepair() {
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        archive.load()
        val harness = harness(); val before = files(archivePath)
        inventory(harness)
        assertTrue(harness.status().contains("contains no saved snapshots")); assertEquals(before, files(archivePath))
        Files.delete(archivePath.resolve("namespace"))
        val missing = files(archivePath)
        inventory(harness)
        assertTrue(harness.status().contains("CORRUPT")); assertEquals(missing, files(archivePath))
    }

    @Test fun cancelledAndReplacedPickersCannotOpenRetainedItemCallbacks() {
        save(); val harness = harness(); inventory(harness)
        val cancelled = dialog(); cancelled.cancel(); idle(); choose(dialog = cancelled)
        assertEquals(0, harness.worker.tasks.size)
        inventory(harness); val old = dialog()
        inventory(harness); val fresh = dialog()
        choose(dialog = old)
        assertTrue(fresh.isShowing); assertEquals(0, harness.worker.tasks.size)
        complete(harness)
    }

    @Test fun cancelBeforeQueuedEvaluationPreventsEngineWorkAndFreshRetryWorks() {
        save(); var calls = 0
        val harness = harness(SnapshotEvaluator { calls++; fixed.evaluate(it) })
        inventory(harness); choose(); val shown = dialog()
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        harness.worker.run(); idle()
        assertEquals(0, calls); assertTrue(report(shown).contains("Inspection cancelled"))
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); harness.worker.run(); idle()
        assertEquals(1, calls); assertTrue(report(shown).contains("Result in this profile:"))
    }

    @Test fun cancelAfterComputationBeforeDeliveryDropsReportAndRetryHasFreshAuthority() {
        save(); val harness = harness(); inventory(harness); choose(); val shown = dialog()
        harness.worker.run()
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertTrue(report(shown).contains("Inspection cancelled")); assertFalse(report(shown).contains("Result in this profile:"))
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); harness.worker.run(); idle()
        assertTrue(report(shown).contains("Result in this profile:"))
    }

    @Test fun replacementRejectsOldResultAndOldRetryEvenWhenTheSameEntryIsSelectedAgain() {
        save(); val harness = harness(); inventory(harness); choose(); val old = dialog()
        val oldRetry = old.getButton(AlertDialog.BUTTON_NEUTRAL)
        harness.worker.run()
        harness.open(); harness.worker.run(); idle()
        assertFalse(old.isShowing); assertEquals(0, harness.worker.tasks.size)
        val fresh = complete(harness)
        oldRetry.performClick(); idle()
        assertEquals(0, harness.worker.tasks.size); assertTrue(fresh.isShowing)
        assertTrue(report(fresh).contains("Result in this profile:"))
    }

    @Test fun switchingBuildRevisionsCannotShowThePreviousCompletedReport() {
        save(revision = BigInteger.ONE); save(revision = BigInteger.TWO)
        val harness = harness(); inventory(harness); choose(0); val old = dialog()
        harness.worker.run(); harness.open(); harness.worker.run(); idle()
        val current = complete(harness, 1)
        assertFalse(old.isShowing)
        assertTrue(report(current).contains("Revision: 2")); assertFalse(report(current).contains("Revision: 1\n"))
    }

    @Test fun currentIndexLossAfterSelectionKeepsOnlyHistoricalReadAuthority() {
        save(); val harness = harness(); inventory(harness)
        Files.delete(archivePath.resolve("indices/active"))
        val before = files(archivePath)
        val shown = complete(harness)
        assertTrue(report(shown).contains("read-only historical inspection"))
        assertFalse(Files.exists(archivePath.resolve("indices/active"))); assertEquals(before, files(archivePath))
        shown.dismiss(); inventory(harness)
        assertTrue(harness.status().contains("CORRUPT")); assertFalse(Files.exists(archivePath.resolve("indices/active")))
    }

    @Test fun missingRetainedBundleHasNoFallbackAndExactRestorationRequiresExplicitRetry() {
        val saved = save(); val harness = harness(); inventory(harness)
        val path = archivePath.resolve("bundles/${saved.entry.bundleHash.hex}.bin")
        val original = Files.readAllBytes(path); Files.delete(path)
        choose(); val shown = dialog(); harness.worker.run(); idle()
        assertTrue(report(shown).contains("Inspection unavailable: CORRUPT"))
        assertFalse(report(shown).contains("Result in this profile:"))
        Files.write(path, original)
        assertFalse(report(shown).contains("Result in this profile:"))
        shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); harness.worker.run(); idle()
        assertTrue(report(shown).contains("Result in this profile:"))
    }

    @Test fun actualForeignBuildAndRevisionResultsAreRejectedBeforeBecomingVisible() {
        val selected = save()
        for ((id, revision) in listOf("build.foreign.snapshot" to BigInteger.ONE, selected.entry.key.id to BigInteger.TWO)) {
            val other = save(id, revision)
            val snapshot = (other.archive.read(other.entry.key) as SnapshotArchiveRead.Available).snapshot
            val request = (LocalSnapshotEvaluator.prepare(snapshot) as SnapshotEvaluationPreparation.Ready).request
            val foreign = fixed.evaluate(request)
            var substitute = true
            val harness = harness(SnapshotEvaluator { if (substitute) foreign else fixed.evaluate(it) })
            inventory(harness); choose(); val shown = dialog(); harness.worker.run(); idle()
            assertTrue(report(shown).contains("Inspection unavailable: IDENTITY_MISMATCH"))
            assertFalse(report(shown).contains("Result in this profile:"))
            val action = findingAction(shown); assertFalse(action.isEnabled)
            action.performClick(); idle(); assertSame(shown, dialog())
            substitute = false
            shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); harness.worker.run(); idle()
            assertTrue(report(shown).contains("Result in this profile:")); shown.dismiss()
        }
    }

    @Test fun schemaValidUnsupportedRuleOperandIsExplicitAndDoesNotAlterSavedBytes() {
        val rules = node("ruleset")
        val rule = rules.getJSONArray("rules").getJSONObject(0)
        rule.put("applies_when", JSONObject("""{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""))
        rule.put("assert", JSONObject("""{"op":"eq","left":{"literal":1.5},"right":{"literal":1.5}}"""))
        save(rules = rules); val before = files(archivePath)
        val harness = harness(); inventory(harness); choose(); val shown = dialog(); harness.worker.run(); idle()
        assertTrue(report(shown).contains("Inspection unavailable: UNSUPPORTED_OPERATION"))
        assertEquals(before, files(archivePath)); assertFalse(report(shown).contains("Result in this profile:"))
        shown.dismiss(); save("build.supported.snapshot"); inventory(harness); complete(harness, 1)
    }

    @Test fun dispatchRejectionIsRecoverableAndClosingDiscardsBothQueuedPhases() {
        save(); val harness = harness(); harness.worker.reject = true; harness.open()
        assertTrue(harness.status().contains("DISPATCH_UNAVAILABLE"))
        harness.worker.reject = false; inventory(harness); choose(); val old = dialog()
        harness.section.close(); harness.worker.run(); idle()
        assertFalse(old.isShowing); harness.open(); assertEquals(0, harness.worker.tasks.size)
        val second = harness(); inventory(second); choose(); val computed = dialog()
        second.worker.run(); second.section.close(); idle()
        assertFalse(computed.isShowing); second.open(); assertEquals(0, second.worker.tasks.size)
    }

    @Test fun actualActivityPauseAndRecreationRetireQueuedInventoryAndOldDialogControls() {
        save()
        val worker = PreviewActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { started.countDown(); release.await(20, TimeUnit.SECONDS) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        controller.pause(); release.countDown(); idle()
        assertEquals(activity.getString(R.string.snapshot_notice), activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
        controller.resume(); activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        choose(); await { report().contains("Result in this profile:") }
        val old = dialog(); val retry = old.getButton(AlertDialog.BUTTON_NEUTRAL)
        val state = Bundle(); controller.pause().saveInstanceState(state).stop().destroy()
        controller = Robolectric.buildActivity(PreviewActivity::class.java).create(state).start().resume().visible()
        await { button(R.string.retry_catalog).isEnabled }
        retry.performClick(); idle()
        assertFalse(old.isShowing)
        assertEquals(activity.getString(R.string.snapshot_notice), activity.findViewById<TextView>(R.id.snapshot_status).text.toString())
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        choose(); await { report().contains("Result in this profile:") }
    }
}
