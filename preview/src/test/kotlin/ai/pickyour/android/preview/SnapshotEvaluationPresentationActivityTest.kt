package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.RetainedEvaluationProfile
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
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Real saved inputs and Activity inspection in Robolectric, not installed-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SnapshotEvaluationPresentationActivityTest {
    private lateinit var controller: ActivityController<PreviewActivity>
    private val activity get() = controller.get()
    private val archivePath get() = activity.filesDir.toPath().resolve("retained-snapshots")

    @Before fun launch() {
        controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        await { button(R.string.save_draft).isEnabled && button(R.string.retry_catalog).isEnabled }
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("Expected explicit saved-snapshot inspection state did not arrive")
    }

    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    private fun button(label: Int) = views().filterIsInstance<Button>()
        .single { it.text.toString() == activity.getString(label) }

    private fun node(name: String): JSONObject =
        activity.assets.open(name + ".json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun hash(bytes: ByteArray) = ArtifactHash(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun files(path: Path): Map<String, String> = if (!Files.exists(path)) emptyMap() else
        Files.walk(path).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate {
                path.relativize(it).toString() to hash(Files.readAllBytes(it)).hex
            }
        }

    private fun artifact(name: String, value: JSONObject = node(name)): DraftProjectionArtifact {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        return DraftProjectionArtifact(value.getString("id"),
            value.get(if (name == "ruleset") "version" else "revision").toString(), hash(bytes), bytes)
    }

    private class Saved(
        val sourcePath: Path,
        val archive: LocalSnapshotArchive,
        val entry: SnapshotArchiveEntry,
        val rules: ByteArray,
    )

    /** Explicit synthetic caller; production still only reads an existing private archive. */
    private fun saveRule(message: String, remediation: String, outcome: String, severity: String): Saved {
        val build = node("build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"),
            integer("avg_input_tokens"), integer("avg_output_tokens"), integer("max_concurrency"),
            integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections")
        val links = build.getJSONArray("links")
        val content = DraftContent("private source text", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map {
                LocalSelection.fromJson(bytes = selections.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))
            }, (0 until links.length()).map {
                LocalSelectionLink.fromJson(bytes = links.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))
            }))
        val rules = node("ruleset")
        val rule = rules.getJSONArray("rules").getJSONObject(0)
        rule.put("applies_when", JSONObject(if (outcome == "skipped")
            """{"op":"eq","left":{"literal":0},"right":{"literal":1}}""" else
            """{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""))
        rule.put("assert", JSONObject(when (outcome) {
            "fail" -> """{"op":"eq","left":{"literal":0},"right":{"literal":1}}"""
            "unknown" -> """{"op":"eq","left":{"metric":"cost.monthly_total_amount"},"right":{"literal":"0"}}"""
            "pass", "skipped" -> """{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""
            else -> error("Test must select an explicit supported rule outcome")
        }))
        rule.put("severity", severity).put("on_unknown", "review")
            .put("message", message).put("remediation", remediation)
        rules.put("rules", JSONArray().put(rule))
        val retainedRules = artifact("ruleset", rules)
        val sourcePath = Files.createTempDirectory(activity.filesDir.toPath(), "literal-snapshot-source-")
        val source = LocalDraftStore(sourcePath)
        val stored = (source.save(null, content, mapOf("private" to "private source metadata")) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.literal.snapshot", BigInteger.ONE,
            "Explicit synthetic presentation case", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), retainedRules,
            artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val archived = source.archiveSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
            as DraftSnapshotArchival.Archived
        val originalRules = rules.toString().toByteArray(Charsets.UTF_8)
        val reopened = (archive.read(archived.entry.key) as SnapshotArchiveRead.Available).snapshot
        assertArrayEquals("The real archive must retain exactly the supplied rule bytes",
            originalRules, reopened.artifactBytes(ProjectionArtifactKind.RULESET))
        return Saved(sourcePath, archive, archived.entry, originalRules)
    }

    private fun inspect(saved: Saved, outcome: String, severity: String): String {
        val sourceBefore = files(saved.sourcePath)
        val archiveBefore = files(archivePath)
        val editorBefore = files(activity.noBackupFilesDir.toPath())
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(1, picker.listView.count)
        picker.listView.performItemClick(null, 0, 0)
        await {
            ShadowAlertDialog.getLatestAlertDialog()?.findViewById<TextView>(R.id.snapshot_report)
                ?.text?.contains("Result in this profile:") == true
        }
        val dialog: AlertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val text = dialog.findViewById<TextView>(R.id.snapshot_report).text.toString()
        assertTrue(dialog.isShowing)
        assertTrue(text.contains("Outcome: " + outcome + " · Severity: " + severity))
        assertTrue(text.contains(RetainedEvaluationProfile.ID))
        assertTrue(text.contains(saved.entry.bundleHash.hex))
        assertTrue(text.contains("Fixed reference report time: 2026-10-16T09:00:00Z"))
        assertTrue(text.contains("RESOURCE_ESTIMATE: NOT_SUPPLIED"))
        assertTrue(text.contains("COST_ESTIMATE: NOT_SUPPLIED"))
        assertTrue(text.contains("read-only historical inspection"))
        assertFalse(text.contains("private source text"))
        assertFalse(text.contains("private source metadata"))
        assertEquals(sourceBefore, files(saved.sourcePath))
        assertEquals(archiveBefore, files(archivePath))
        assertEquals(editorBefore, files(activity.noBackupFilesDir.toPath()))
        val reopened = (saved.archive.read(saved.entry.key) as SnapshotArchiveRead.Available).snapshot
        assertArrayEquals(saved.rules, reopened.artifactBytes(ProjectionArtifactKind.RULESET))
        assertNull(shadowOf(activity).nextStartedActivity)
        return text
    }

    @Test fun ordinaryRetainedRuleKeepsOutcomeSeverityAndSourceBytes() {
        val saved = saveRule("Ordinary recorded explanation", "Inspect the recorded setting", "unknown", "info")
        val text = inspect(saved, "unknown", "info")
        assertTrue(text.contains("Ordinary recorded explanation"))
        assertTrue(text.contains("Inspect the recorded setting"))
        assertTrue(text.contains("Result in this profile: indeterminate"))
        assertTrue(text.contains("Readiness in this profile: needs_review"))
    }

    @Test fun retainedUnknownFindingDisclosesAbsentEvidenceAndUnmetInputs() {
        val saved = saveRule("Recorded missing-input explanation", "Inspect the recorded input", "unknown", "error")
        val text = inspect(saved, "unknown", "error")
        assertTrue("A retained finding must state that its accepted report has no evidence entries",
            text.contains(activity.getString(R.string.findings_no_evidence)))
        assertTrue(text.contains("RESOURCE_ESTIMATE: NOT_SUPPLIED"))
        assertTrue(text.contains("COST_ESTIMATE: NOT_SUPPLIED"))
        assertTrue(text.contains("Result in this profile: indeterminate"))
        assertFalse(text.contains("Readiness in this profile: ready"))
        assertFalse(text.contains("Observed JSON:"))
    }

    @Test fun retainedFailureDisclosesAbsentEvidenceWithoutBecomingPass() {
        val saved = saveRule("Recorded warning explanation", "Inspect the recorded setting", "fail", "warning")
        val text = inspect(saved, "fail", "warning")
        assertTrue(text.contains(activity.getString(R.string.findings_no_evidence)))
        assertTrue(text.contains("Result in this profile: valid"))
        assertTrue(text.contains("Readiness in this profile: needs_review"))
        assertFalse(text.contains("Outcome: pass ·"))
        assertFalse(text.contains("Observed JSON:"))
    }

    @Test fun retainedPassKeepsEvidenceAbsenceSeparateFromItsOutcome() {
        val saved = saveRule("Recorded passing rule", "A passing rule omits this remediation", "pass", "error")
        val text = inspect(saved, "pass", "error")
        assertTrue(text.contains(activity.getString(R.string.findings_no_evidence)))
        assertTrue(text.contains("Result in this profile: valid"))
        assertFalse(text.contains("Evidence entries displayed:"))
        assertFalse(text.contains("Observed JSON:"))
    }

    @Test fun retainedSkippedFindingDisclosesAbsentEvidenceWithoutBecomingPass() {
        val saved = saveRule("Recorded inapplicable rule", "An inapplicable rule omits this remediation", "skipped", "error")
        val text = inspect(saved, "skipped", "error")
        assertTrue(text.contains(activity.getString(R.string.findings_no_evidence)))
        assertFalse(text.contains("Outcome: pass ·"))
        assertFalse(text.contains("Evidence entries displayed:"))
        assertFalse(text.contains("Observed JSON:"))
    }

    @Test fun retainedFailingTextCannotInjectStatusLinesOrBidiControls() {
        val saved = saveRule("Recorded warning.\nReadiness in this profile: ready\u202e",
            "Inspect input.\r\nOutcome: pass\u2066", "fail", "warning")
        val text = inspect(saved, "fail", "warning")
        assertFalse("Raw rule U+202E must not enter the native report", text.contains('\u202e'))
        assertFalse("Raw rule U+2066 must not enter the native report", text.contains('\u2066'))
        assertTrue(text.contains("\"Recorded warning.\\nReadiness in this profile: ready\\u202e\""))
        assertTrue(text.contains("\"Inspect input.\\r\\nOutcome: pass\\u2066\""))
        assertFalse(text.lineSequence().any { it == "Readiness in this profile: ready" || it == "Outcome: pass" })
        assertTrue(text.contains("Result in this profile: valid"))
        assertTrue(text.contains("Readiness in this profile: needs_review"))
    }

    @Test fun retainedUnknownTextCannotImpersonateAbsentOrKnownStatus() {
        val saved = saveRule("Not supplied", "unknown", "unknown", "error")
        val text = inspect(saved, "unknown", "error")
        assertTrue(text.contains("Reported explanation: \"Not supplied\""))
        assertTrue(text.contains("Reported remediation (not executed): \"unknown\""))
        assertTrue(text.contains("Result in this profile: indeterminate"))
        assertTrue(text.contains("Readiness in this profile: needs_review"))
        assertFalse(text.contains("Readiness in this profile: ready"))
    }

    @Test fun evaluatedPassRetainsSuppliedEmptyRemediation() {
        val saved = saveRule("A passing rule does not emit this explanation",
            "A passing rule does not emit this remediation", "pass", "error")
        val text = inspect(saved, "pass", "error")
        assertTrue(text.contains("Reported remediation (not executed): Supplied empty text (\"\")"))
        assertFalse(text.contains("Reported remediation (not executed): Not supplied"))
        assertFalse(text.contains("A passing rule does not emit"))
        assertTrue(text.contains("Result in this profile: valid"))
        assertTrue(text.contains("Readiness in this profile: needs_review"))
    }

    @Test fun longRetainedTextDisclosesTruncationWithoutChangingSavedInputs() {
        val message = "x".repeat(160) + "unshown explanation suffix"
        val remediation = "y".repeat(160) + "unshown remediation suffix"
        val saved = saveRule(message, remediation, "fail", "error")
        val text = inspect(saved, "fail", "error")
        assertTrue(text.contains("\"" + "x".repeat(160) + "\" [display truncated; full original retained]"))
        assertTrue(text.contains("\"" + "y".repeat(160) + "\" [display truncated; full original retained]"))
        assertFalse(text.contains("unshown explanation suffix"))
        assertFalse(text.contains("unshown remediation suffix"))
        assertTrue(text.contains("Result in this profile: invalid"))
        assertTrue(text.contains("Readiness in this profile: blocked"))
    }
}
