package ai.pickyour.android.preview

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.*
import org.json.JSONArray
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real retained archives, local evaluator and Views in Robolectric; no installed-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SnapshotUnknownPhaseActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val archivePath get() = activity.filesDir.toPath().resolve("retained-snapshots")
    private val sections = mutableListOf<SnapshotEvaluationSection>()
    private val fixed = LocalSnapshotEvaluator(Clock.fixed(Instant.parse("2026-10-20T19:00:00Z"), ZoneOffset.UTC))
    private var serial = 0
    private val yes = """{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""
    private val no = """{"op":"eq","left":{"literal":1},"right":{"literal":2}}"""
    private val unknown = """{"op":"eq","left":{"ref":{"scope":"build","pointer":"/missing"}},"right":{"literal":1}}"""
    private val unsupported = """{"op":"contains","left":{"literal":"text"},"right":{"literal":1}}"""
    private val applicability = "Observed unknown phase: Whether this rule applies could not be determined, so its assertion was not evaluated. " +
        "This observation identifies neither a cause nor a feasible next action."
    private val assertion = "Observed unknown phase: This rule applies, but its assertion did not determine a pass or fail. " +
        "This observation identifies neither a cause nor a feasible next action."

    private val inputHeading = "Observed input limitations for this unknown result:"
    private val missingInputs = "Observed input limitations for this unknown result:\n" +
        "A referenced value was absent from the captured inputs.\n" +
        "These observations are local input facts, not a complete cause or a verified remedy."
    private val combinedInputs = "Observed input limitations for this unknown result:\n" +
        "A referenced value was absent from the captured inputs.\n" +
        "A supplied value was outside the supported operand domain.\n" +
        "The local profile does not supply an evaluated metric.\n" +
        "These observations are local input facts, not a complete cause or a verified remedy."

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
    }
    @After fun close() { sections.forEach { it.close() }; controller.pause().stop().destroy() }
    private fun idle() = AutosaveUiClock.idle()
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) { idle(); if (check()) return; Thread.sleep(10) }
        fail("Expected saved-snapshot inspection state did not arrive")
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root); if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun text(window: AlertDialog = dialog()) = window.findViewById<TextView>(R.id.snapshot_report).text.toString()
    private fun node(name: String): JSONObject = activity.assets.open(name + ".json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun files(path: Path): Map<String, String> = if (!Files.exists(path)) emptyMap() else Files.walk(path).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path.relativize(it).toString() to hash(Files.readAllBytes(it)).hex }
    }
    private fun artifact(name: String, value: JSONObject = node(name)): DraftProjectionArtifact {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        return DraftProjectionArtifact(value.getString("id"),
            value.get(if (name == "ruleset") "version" else "revision").toString(), hash(bytes), bytes)
    }
    private class Saved(val sourcePath: Path, val archive: LocalSnapshotArchive,
        val entry: SnapshotArchiveEntry, val rules: ByteArray)

    /** Explicit synthetic rule, admitted through actual draft projection and immutable archival. */
    private fun save(outcome: String): Saved {
        val build = node("build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"),
            integer("avg_input_tokens"), integer("avg_output_tokens"), integer("max_concurrency"),
            integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections"); val links = build.getJSONArray("links")
        val content = DraftContent("private retained phase source", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map {
                LocalSelection.fromJson(bytes = selections.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))
            }, (0 until links.length()).map {
                LocalSelectionLink.fromJson(bytes = links.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))
            }))
        val rules = node("ruleset")
        val rule = rules.getJSONArray("rules").getJSONObject(0).put("id", "rule.retained.phase")
        val applies = when (outcome) { "applicability" -> unknown; "skipped" -> no; else -> yes }
        val assertion = when (outcome) {
            "applicability", "skipped" -> unsupported
            "assertion" -> unknown
            "combined" -> """{"op":"all","args":[{"op":"eq","left":{"ref":{"scope":"build","pointer":"/private_missing_path"}},"right":{"literal":1}},{"op":"lt","left":{"literal":"private_non_numeric_value"},"right":{"literal":1}},{"op":"gte","left":{"metric":"cost.monthly_total_amount"},"right":{"literal":0}}]}"""
            "pass" -> yes
            "fail" -> no
            else -> error("Explicit synthetic outcome required")
        }
        rule.put("applies_when", JSONObject(applies)).put("assert", JSONObject(assertion))
            .put("severity", "error").put("on_unknown", "review")
            .put("message", "Quoted ASSERTION and cause claim only").put("remediation", "Quoted APPLICABILITY and repair claim only")
        rules.put("rules", JSONArray().put(rule))
        val sourcePath = Files.createTempDirectory(activity.filesDir.toPath(), "retained-phase-source-")
        val source = LocalDraftStore(sourcePath)
        val stored = (source.save(null, content, mapOf("private" to "retained-phase-private")) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.retained.phase." + serial++, BigInteger.ONE,
            "Explicit retained phase fixture", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset", rules),
            artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val archived = source.archiveSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version) as DraftSnapshotArchival.Archived
        val rawRules = rules.toString().toByteArray(Charsets.UTF_8)
        val reopened = (archive.read(archived.entry.key) as SnapshotArchiveRead.Available).snapshot
        assertArrayEquals(rawRules, reopened.artifactBytes(ProjectionArtifactKind.RULESET))
        return Saved(sourcePath, archive, archived.entry, rawRules)
    }

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() {
            val failure = AtomicReference<Throwable>()
            val task = tasks.removeFirst()
            val thread = Thread { try { task.run() } catch (error: Throwable) { failure.set(error) } }
            thread.start(); thread.join(20_000)
            assertFalse("Worker must finish before controlled UI delivery", thread.isAlive)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        }
    }
    private class Harness(val section: SnapshotEvaluationSection, val controls: LinearLayout, val worker: Queue,
        val results: MutableList<SnapshotEvaluationOutcome>) {
        fun open() { controls.findViewById<Button>(R.id.snapshot_open).performClick() }
    }
    private fun harness(): Harness {
        val work = Queue()
        val results = mutableListOf<SnapshotEvaluationOutcome>()
        val evaluator = SnapshotEvaluator { request -> fixed.evaluate(request).also { results.add(it) } }
        val controls = LinearLayout(activity)
        val section = SnapshotEvaluationSection(activity, work, evaluator, { archivePath }) { true }
        section.attach(controls); sections += section
        return Harness(section, controls, work, results)
    }
    private fun choose(harness: Harness, saved: Saved): AlertDialog {
        harness.open(); harness.worker.run(); idle()
        val picker = dialog()
        val label = activity.getString(R.string.snapshot_entry, saved.entry.key.id, saved.entry.key.revision.toString())
        val index = (0 until picker.listView.count).single { picker.listView.adapter.getItem(it).toString() == label }
        picker.listView.performItemClick(null, index, index.toLong()); idle()
        return dialog()
    }
    private fun complete(harness: Harness, saved: Saved): AlertDialog {
        val window = choose(harness, saved)
        harness.worker.run(); idle()
        assertTrue(text(window).contains("Result in this profile:"))
        return window
    }
    private fun phase(harness: Harness, window: AlertDialog, saved: Saved, expected: SnapshotUnknownPhase, paragraph: String,
        inputText: String = missingInputs, observations: List<UnknownInputObservation> = listOf(UnknownInputObservation.MISSING_CAPTURED_VALUE),
    ) {
        val result = harness.results.last() as SnapshotEvaluationOutcome.Evaluated
        assertEquals(expected, result.unknownPhase("rule.retained.phase"))
        assertEquals(observations, result.unknownInputObservations("rule.retained.phase"))
        assertEquals(saved.entry.key.id, result.report.inspect().buildId)
        assertEquals(saved.entry.key.revision, result.report.inspect().buildRevision)
        assertEquals(saved.entry.bundleHash, result.inputs.bundleHash)
        assertEquals(hash(saved.rules), result.inputs.bindings.single { it.slot == SnapshotEvaluationSlot.RULESET }.hash)
        val rendered = text(window)
        assertEquals(paragraph, rendered.lineSequence().single { it.startsWith("Observed unknown phase:") })
        val observationAt = rendered.indexOf(inputHeading)
        assertTrue(observationAt >= 0)
        assertEquals(1, Regex(Regex.escape(inputHeading)).findAll(rendered).count())
        assertEquals(inputText, rendered.substring(observationAt))
        assertTrue(rendered.substring(0, observationAt).endsWith(paragraph + "\n"))
        assertTrue(rendered.contains("Outcome: unknown · Severity: error"))
        assertTrue(rendered.contains("Reported explanation: \"Quoted ASSERTION and cause claim only\""))
        assertTrue(rendered.contains("Reported remediation (not executed): \"Quoted APPLICABILITY and repair claim only\""))
        assertTrue(rendered.contains("Result in this profile: indeterminate"))
        assertTrue(rendered.contains("COST_ESTIMATE: NOT_SUPPLIED"))
        assertTrue(rendered.contains("RESOURCE_ESTIMATE: NOT_SUPPLIED"))
        assertFalse(rendered.contains("Readiness in this profile: ready"))
        assertFalse(rendered.contains("private retained phase source"))
        assertFalse(rendered.contains("retained-phase-private"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun freshApplicabilityObservationUsesTheActualPassAndPreservesPrivateBytes() {
        val saved = save("applicability")
        val source = files(saved.sourcePath); val archive = files(archivePath); val editor = files(activity.noBackupFilesDir.toPath())
        val harness = harness()
        val window = complete(harness, saved)
        phase(harness, window, saved, SnapshotUnknownPhase.APPLICABILITY, applicability)
        assertEquals(1, harness.results.size)
        assertEquals(source, files(saved.sourcePath)); assertEquals(archive, files(archivePath))
        assertEquals(editor, files(activity.noBackupFilesDir.toPath()))
    }

    @Test fun freshAssertionObservationRequiresTrueApplicabilityWithoutInferringAnAction() {
        val saved = save("assertion")
        val source = files(saved.sourcePath); val archive = files(archivePath); val editor = files(activity.noBackupFilesDir.toPath())
        val harness = harness()
        val window = complete(harness, saved)
        phase(harness, window, saved, SnapshotUnknownPhase.ASSERTION, assertion)
        assertFalse(text(window).contains(applicability))
        assertEquals(1, harness.results.size)
        assertEquals(source, files(saved.sourcePath)); assertEquals(archive, files(archivePath))
        assertEquals(editor, files(activity.noBackupFilesDir.toPath()))
    }

    @Test fun fixedInputObservationsPreserveRawFieldsAndWithholdPrivateOperands() {
        val saved = save("combined")
        val source = files(saved.sourcePath); val archive = files(archivePath); val editor = files(activity.noBackupFilesDir.toPath())
        val harness = harness()
        val window = complete(harness, saved)
        phase(harness, window, saved, SnapshotUnknownPhase.ASSERTION, assertion, combinedInputs,
            listOf(UnknownInputObservation.MISSING_CAPTURED_VALUE, UnknownInputObservation.OPERAND_DOMAIN_UNAVAILABLE,
                UnknownInputObservation.METRIC_NOT_SUPPLIED))
        assertFalse(text(window).contains("private_missing_path"))
        assertFalse(text(window).contains("private_non_numeric_value"))
        assertEquals(1, harness.results.size)
        assertEquals(source, files(saved.sourcePath)); assertEquals(archive, files(archivePath))
        assertEquals(editor, files(activity.noBackupFilesDir.toPath()))
    }

    @Test fun nonUnknownRetainedFindingsNeverDisplayAnObservedUnknownPhase() {
        val cases = listOf("skipped", "pass", "fail").associateWith { save(it) }
        val sources = cases.values.associate { it.sourcePath to files(it.sourcePath) }
        val archive = files(archivePath); val editor = files(activity.noBackupFilesDir.toPath())
        val harness = harness()
        for ((outcome, saved) in cases) {
            val window = complete(harness, saved)
            val result = harness.results.last() as SnapshotEvaluationOutcome.Evaluated
            assertEquals(outcome, result.report.inspect().findings.single().outcome)
            assertNull(result.unknownPhase("rule.retained.phase"))
            assertTrue(result.unknownInputObservations("rule.retained.phase").isEmpty())
            assertFalse(text(window).contains(inputHeading))
            assertFalse(text(window).contains("Observed unknown phase:"))
            assertTrue(text(window).contains("Outcome: " + outcome + " · Severity: error"))
        }
        sources.forEach { (path, before) -> assertEquals(before, files(path)) }
        assertEquals(archive, files(archivePath)); assertEquals(editor, files(activity.noBackupFilesDir.toPath()))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun cancellationReplacementAndCloseRetirePhaseAndCapturedControls() {
        val first = save("applicability"); val second = save("assertion")
        val sources = listOf(first, second).associate { it.sourcePath to files(it.sourcePath) }
        val archive = files(archivePath); val editor = files(activity.noBackupFilesDir.toPath())
        val harness = harness()
        val old = choose(harness, first)
        val oldRetry = old.getButton(AlertDialog.BUTTON_NEUTRAL)
        val oldFindings = old.findViewById<Button>(R.id.snapshot_findings)
        harness.worker.run() // Actual computation has completed, but delivery is still queued.
        old.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertTrue(text(old).contains("Inspection cancelled"))
        assertFalse(text(old).contains("Observed unknown phase:"))
        assertFalse(text(old).contains(inputHeading))
        assertFalse(text(old).contains("Result in this profile:"))
        oldRetry.performClick(); harness.worker.run(); idle()
        phase(harness, old, first, SnapshotUnknownPhase.APPLICABILITY, applicability)
        val fresh = complete(harness, second)
        phase(harness, fresh, second, SnapshotUnknownPhase.ASSERTION, assertion)
        assertFalse(old.isShowing)
        val currentText = text(fresh)
        val calls = harness.results.size
        oldRetry.performClick(); oldFindings.performClick(); idle()
        assertSame(fresh, dialog()); assertTrue(fresh.isShowing)
        assertEquals(currentText, text(fresh)); assertEquals(calls, harness.results.size)
        assertEquals(0, harness.worker.tasks.size)
        val retry = fresh.getButton(AlertDialog.BUTTON_NEUTRAL)
        harness.section.close(); retry.performClick(); idle()
        assertFalse(fresh.isShowing); assertEquals(0, harness.worker.tasks.size)
        assertEquals(calls, harness.results.size)
        sources.forEach { (path, before) -> assertEquals(before, files(path)) }
        assertEquals(archive, files(archivePath)); assertEquals(editor, files(activity.noBackupFilesDir.toPath()))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
