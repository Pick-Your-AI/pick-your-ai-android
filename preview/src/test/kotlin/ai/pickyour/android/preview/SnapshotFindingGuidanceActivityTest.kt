package ai.pickyour.android.preview

import android.app.AlertDialog
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
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

/** Actual retained archive -> evaluator -> Activity controls; no installed-device or input-rights claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SnapshotFindingGuidanceActivityTest {
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
            AutosaveUiClock.idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("Expected retained snapshot state did not arrive")
    }
    private fun views(root: View = activity.window.decorView): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }
    private fun button(label: Int) = views().filterIsInstance<Button>().single { it.text.toString() == activity.getString(label) }
    private fun objective(): EditText = activity.findViewById(views().filterIsInstance<TextView>()
        .single { it.text.toString() == activity.getString(R.string.context_objective) }.labelFor)
    private fun fixture(name: String) = activity.assets.open("$name.json").bufferedReader(Charsets.UTF_8)
        .use { JSONObject(it.readText()) }
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun files(path: Path): Map<String, ArtifactHash> = if (!Files.exists(path)) emptyMap() else Files.walk(path).use { stream ->
        stream.filter(Files::isRegularFile).toList().associate { path.relativize(it).toString() to hash(Files.readAllBytes(it)) }
    }
    private fun artifact(name: String, value: JSONObject = fixture(name)): DraftProjectionArtifact {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        return DraftProjectionArtifact(value.getString("id"), value.get(if (name == "ruleset") "version" else "revision").toString(), hash(bytes), bytes)
    }

    /** Explicit controlled fixture copies; production does not invent these snapshot inputs. */
    private fun saveMixedSnapshot(changedRules: Boolean): SnapshotArchiveEntry {
        val build = fixture("build"); val catalog = fixture("catalog"); val rules = fixture("ruleset")
        val selected = build.getJSONArray("selections")
        fun primary(kind: String) = (0 until selected.length()).map(selected::getJSONObject)
            .single { it.getString("kind") == kind && it.getString("role") == "primary" }
        // Preserve archive foundation/provider admission; these reported failures are archivable.
        primary("knowledge_base").put("enabled", false)
        val items = catalog.getJSONArray("items")
        val model = (0 until items.length()).map(items::getJSONObject)
            .single { it.getString("id") == primary("model").getString("catalog_item_id") }
        primary("context_policy").getJSONObject("settings").put("reserved_output_tokens",
            model.getJSONObject("spec").getLong("max_output_tokens") + 1)
        if (changedRules) rules.getJSONArray("rules").getJSONObject(20).put("message", "Changed unrelated recorded rule")
        val values = build.getJSONObject("objectives")
        fun integer(name: String) = if (values.has(name)) BigInteger(values.get(name).toString()) else null
        val workload = DraftWorkload(values.getString("region"), values.getString("currency"),
            ContractDecimal.parse(values.getString("monthly_budget_amount")), integer("requests_per_day"),
            integer("avg_input_tokens"), integer("avg_output_tokens"), integer("max_concurrency"), integer("max_latency_ms"),
            DraftDataClassification.INTERNAL)
        val links = build.getJSONArray("links")
        val content = DraftContent("private archived guidance text", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selected.length()).map { LocalSelection.fromJson(bytes = selected.getJSONObject(it).toString().toByteArray()) },
                (0 until links.length()).map { LocalSelectionLink.fromJson(bytes = links.getJSONObject(it).toString().toByteArray()) }))
        val store = LocalDraftStore(Files.createTempDirectory(activity.filesDir.toPath(), "guidance-source-"))
        val saved = (store.save(null, content, mapOf("private" to "private guidance metadata")) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.guidance.retained", BigInteger.ONE, "Controlled guidance snapshot", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val input = DraftSnapshotInputs(1, identity, artifact("catalog", catalog), artifact("ruleset", rules),
            artifact("dependency-graph"), artifact("pricebook"))
        val prepared = store.prepareSnapshotCandidate(saved.version, input)
        assertTrue("Controlled archive input must be admitted: " +
            (if (prepared is DraftProjectionPreparation.Withheld) prepared.findings.map { it.problem } else prepared),
            prepared is DraftProjectionPreparation.Review)
        val review = prepared as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(archivePath.also(Files::createDirectories))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val result = store.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
            as DraftSnapshotArchival.Archived
        return result.entry
    }

    private fun openReport(): String {
        activity.findViewById<Button>(R.id.snapshot_open).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        picker.listView.performItemClick(null, 0, 0)
        fun text(): String = ShadowAlertDialog.getLatestAlertDialog()?.findViewById<TextView>(R.id.snapshot_report)?.text?.toString().orEmpty()
        await { text().contains("Result in this profile:") }
        return text()
    }
    private fun row(id: String) = activity.getString(R.string.findings_row, id, "fail", "error")

    @Test fun actualRetainedMixedFailuresShowDistinctGuidanceWithoutChangingPendingDraft() {
        val entry = saveMixedSnapshot(changedRules = false)
        // Retained inspection is asserted before the separate draft autosave deadline.
        AutosaveUiClock.pause()
        val pendingSince = SystemClock.uptimeMillis()
        objective().setText("pending guidance sentinel")
        val draftBefore = files(activity.noBackupFilesDir.toPath()); val archiveBefore = files(archivePath)
        val text = openReport()
        assertTrue(text.contains(row("rule.rag.knowledge"))); assertTrue(text.contains(row("rule.model.output.limit")))
        assertTrue(text.contains(activity.getString(R.string.findings_row, "rule.required.foundation", "pass", "error")))
        assertTrue(text.contains(activity.getString(R.string.findings_row, "rule.model.provider.match", "pass", "error")))
        assertTrue(text.contains(row("rule.rag.knowledge") + "\nGuidance: Incomplete (provisional)"))
        assertTrue(text.contains(row("rule.model.output.limit") + "\nGuidance: Reported incompatibility (provisional)"))
        assertTrue(text.contains(entry.key.id)); assertTrue(text.contains("Readiness in this profile: blocked"))
        for (marker in listOf("pending guidance sentinel", "private archived guidance text", "private guidance metadata"))
            assertFalse(text.contains(marker))
        assertEquals("pending guidance sentinel", objective().text.toString())
        assertEquals(draftBefore, files(activity.noBackupFilesDir.toPath())); assertEquals(archiveBefore, files(archivePath))
        assertTrue(button(R.string.save_draft).isEnabled); assertNull(shadowOf(activity).nextStartedActivity)
        requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("pending guidance sentinel", objective().text.toString())
        assertTrue("Read-only actions stay before autosave", SystemClock.uptimeMillis() in pendingSince until pendingSince + 750)
    }

    @Test fun changedRetainedRuleContentKeepsCanonicalFailuresWithoutProvisionalClassification() {
        saveMixedSnapshot(changedRules = true)
        val before = files(archivePath)
        val text = openReport()
        assertTrue(text.contains(row("rule.rag.knowledge"))); assertTrue(text.contains(row("rule.model.output.limit")))
        assertFalse(text.contains("Guidance: Incomplete (provisional)"))
        assertFalse(text.contains("Guidance: Reported incompatibility (provisional)"))
        assertTrue(text.contains("Readiness in this profile: blocked")); assertEquals(before, files(archivePath))
    }
}
