package ai.pickyour.android.preview

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.ReferenceReportAdapter
import ai.pickyour.core.evaluation.ReportReception
import ai.pickyour.core.evaluation.RetainedEvaluationProfile
import ai.pickyour.core.evaluation.inspect
import ai.pickyour.core.preview.PreviewDraftLoad
import ai.pickyour.core.preview.PreviewDraftPersistence
import ai.pickyour.core.preview.PreviewWorkloadInputs
import ai.pickyour.core.pricing.CostEstimateAdapter
import ai.pickyour.core.pricing.CostEvidence
import ai.pickyour.core.pricing.CostReception
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executor

/** Actual public text/section paths on simulated Activities; no installed rendering or TalkBack claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class InspectionLayoutDirectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test @Config(qualifiers = "en-rUS +ldrtl")
    fun englishFindingsRespectRtlLayout() = withActivity("en", true, ::checkFindings)

    @Test @Config(qualifiers = "ar-rEG +ldltr")
    fun arabicFindingsRespectLtrLayout() = withActivity("ar", false, ::checkFindings)

    @Test @Config(qualifiers = "en-rUS +ldrtl")
    fun englishCostRespectsRtlLayoutWithoutChangingNumberLocale() = withActivity("en", true, ::checkCost)

    @Test @Config(qualifiers = "ar-rEG +ldltr")
    fun arabicCostRespectsLtrLayoutWithoutChangingNumberLocale() = withActivity("ar", false, ::checkCost)

    @Test @Config(qualifiers = "en-rUS +ldrtl")
    fun englishSavedComponentsRespectRtlLayout() = withActivity("en", true, ::checkSummary)

    @Test @Config(qualifiers = "ar-rEG +ldltr")
    fun arabicSavedComponentsRespectLtrLayout() = withActivity("ar", false, ::checkSummary)

    @Test @Config(qualifiers = "en-rUS +ldrtl")
    fun englishRetainedSnapshotRespectsRtlLayout() = withActivity("en", true, ::checkSnapshot)

    @Test @Config(qualifiers = "ar-rEG +ldltr")
    fun arabicRetainedSnapshotRespectsLtrLayout() = withActivity("ar", false, ::checkSnapshot)

    private fun withActivity(language: String, rtl: Boolean, check: (Activity, Boolean) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(language, activity.resources.configuration.locales[0].language)
            assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,
                activity.resources.configuration.layoutDirection)
            check(activity, rtl)
        } finally { controller.pause().stop().destroy() }
    }

    // Exact expected controls for escaped ASCII fragments with the declared LTR text direction.
    private fun isolated(text: String, rtl: Boolean) = if (rtl) "\u200f\u202a$text\u202c\u200f" else text
    private fun fixture(activity: Activity, name: String): JSONObject = activity.assets.open("$name.json")
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun bytes(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8)
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    private fun files(path: Path): Map<String, String> = Files.walk(path).use { entries ->
        entries.filter(Files::isRegularFile).toList().associate {
            path.relativize(it).toString() to hash(Files.readAllBytes(it)).hex
        }
    }
    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(views(root.getChildAt(it))) }
    }

    private fun checkFindings(activity: Activity, rtl: Boolean) {
        val message = "recorded\nOutcome: fail\u202e"
        for (remediation in listOf<String?>(null, "", "Inspect the recorded input")) {
            val report = fixture(activity, "evaluation-report")
            report.getJSONArray("findings").getJSONObject(0).put("message", message).put("remediation", remediation)
            val accepted = ReferenceReportAdapter().receive(bytes(fixture(activity, "build")).inputStream(),
                bytes(fixture(activity, "ruleset")).inputStream(), bytes(report).inputStream())
            assertTrue("The unchanged report receiver must admit the fixture", accepted is ReportReception.Accepted)
            val retained = accepted as ReportReception.Accepted
            val before = retained.json
            val finding = retained.inspect().findings.first()
            val title = isolated("\"${finding.ruleId}\"", rtl)
            assertEquals(title, FindingsInspectionText.title(activity, finding))
            assertEquals(activity.getString(R.string.findings_row, title, finding.outcome, finding.severity),
                FindingsInspectionText.row(activity, finding))
            val text = FindingsInspectionText.describe(activity, finding)
            assertTrue(text.contains(activity.getString(R.string.findings_reason,
                isolated("\"${finding.code}\"", rtl), isolated("\"recorded\\nOutcome: fail\\u202e\"", rtl))))
            val displayedRemediation = remediation?.let { isolated("\"$it\"", rtl) }
                ?: activity.getString(R.string.findings_not_supplied)
            assertTrue(text.contains(activity.getString(R.string.findings_remediation, displayedRemediation)))
            assertFalse(text.contains('\u202e'))
            assertEquals(message, finding.message)
            assertEquals(remediation, finding.remediation)
            assertEquals(before, retained.json)
        }
    }

    private fun checkCost(activity: Activity, rtl: Boolean) {
        val amount = ContractDecimal.parse("1234.50")
        val localized = if (activity.resources.configuration.locales[0].language == "ar") "١٬٢٣٤٫٥٠" else "1,234.50"
        assertEquals(activity.getString(R.string.cost_display_decimal,
            isolated(localized, rtl), isolated("\"1234.50\"", rtl)), CostInspectionText.decimal(activity, amount))
        assertEquals("1234.50", amount.encoded)
        val rawWarning = "recorded\nTOTAL: FREE\u202e"
        val estimate = fixture(activity, "cost-estimate")
        estimate.put("warnings", JSONArray().put(rawWarning).put(""))
        val received = CostEstimateAdapter().inspect(bytes(fixture(activity, "build")).inputStream(), bytes(estimate).inputStream())
        assertTrue("The unchanged cost adapter must admit the fixture", received is CostReception.Inspected)
        val value = (received as CostReception.Inspected).estimate
        val before = value.recordedJson
        val text = CostInspectionText.describe(activity, value)
        assertTrue(text.contains(activity.getString(R.string.cost_identity,
            isolated("\"${value.buildId}\"", rtl), isolated("\"${value.buildRevision}\"", rtl),
            isolated("\"${value.estimateId}\"", rtl), isolated("\"${value.pricebookId}\"", rtl))))
        assertTrue(text.contains(isolated("\"recorded\\nTOTAL: FREE\\u202e\"", rtl)))
        assertTrue(text.contains(isolated("\"\"", rtl)))
        assertTrue(text.contains(isolated("USD", rtl)))
        assertFalse(text.contains('\u202e'))
        assertEquals(listOf(rawWarning, ""), value.warnings)
        assertEquals("USD", value.currency)
        assertEquals("578.50", value.reportedTotal.amount.encoded)
        assertEquals(CostEvidence.NOT_ESTABLISHED, value.budgetPass)
        assertEquals(before, value.recordedJson)
    }

    private fun checkSummary(activity: Activity, rtl: Boolean) {
        val array = fixture(activity, "build").getJSONArray("selections")
        val node = (0 until array.length()).map(array::getJSONObject).single { it.getString("kind") == "model" }
        val version = "revision\nLine\u202e"
        node.put("catalog_item_version", version).put("notes", "private direction note")
        val selection = LocalSelection.fromJson(bytes(node))
        val path = temporary.newFolder("summary").toPath()
        val store = LocalDraftStore(path)
        assertTrue(store.save(null, DraftContent(components = DraftSelections(listOf(selection))),
            mapOf("preview.format" to "1", "preview.title" to "Direction fixture")) is DraftSave.Saved)
        val persistence = PreviewDraftPersistence(store)
        val record = (persistence.load() as PreviewDraftLoad.Available).record
        val before = files(path)
        val section = CurrentDraftSummarySection(activity) { true }
        try {
            activity.setContentView(section)
            section.update(record, PlanningDraftState.SAVED, PreviewWorkloadInputs(), false, false,
                { true }, {}, {}, {})
            val toggle = views(section).filterIsInstance<Button>().single {
                it.text.toString() == activity.getString(R.string.draft_summary_show_components)
            }
            assertTrue(toggle.isEnabled)
            toggle.performClick()
            val expected = activity.getString(R.string.draft_summary_component_record,
                isolated("\"${selection.id}\"", rtl), isolated("\"${selection.kind}\"", rtl),
                isolated("\"${selection.role}\"", rtl), isolated("\"${selection.catalogItemId}\"", rtl),
                isolated("\"revision\\nLine\\u202e\"", rtl),
                activity.getString(R.string.draft_summary_component_enabled))
            val displayed = views(section).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("The expanded saved record must use the host direction", expected in displayed)
            assertTrue(displayed.any { activity.getString(R.string.draft_summary_components_unresolved) == it })
            assertFalse(displayed.any { it.contains("private direction note") || it.contains('\u202e') })
            assertEquals(activity.getString(R.string.draft_summary_evaluation_unavailable),
                section.findViewById<TextView>(R.id.draft_summary_evaluation).text.toString())
            assertEquals(activity.getString(R.string.draft_summary_cost_unavailable),
                section.findViewById<TextView>(R.id.draft_summary_cost).text.toString())
            section.retireActions()
            assertFalse(toggle.isEnabled)
            toggle.performClick()
            assertEquals(displayed, views(section).filterIsInstance<TextView>().map { it.text.toString() })
            val after = (persistence.load() as PreviewDraftLoad.Available).record
            assertEquals(record.baselineIdentity, after.baselineIdentity)
            assertEquals(listOf(selection), after.components.selections)
            assertEquals(version, after.components.selections.single().catalogItemVersion)
            assertEquals(before, files(path))
        } finally { section.close() }
    }

    private class SavedSnapshot(val source: Path, val archivePath: Path, val archive: LocalSnapshotArchive,
        val entry: SnapshotArchiveEntry, val rules: ByteArray)

    private fun saveSnapshot(activity: Activity, passing: Boolean): SavedSnapshot {
        val build = fixture(activity, "build")
        val objective = build.getJSONObject("objectives")
        fun integer(name: String) = if (objective.has(name)) BigInteger(objective.get(name).toString()) else null
        val workload = DraftWorkload(objective.getString("region"), objective.getString("currency"),
            ContractDecimal.parse(objective.getString("monthly_budget_amount")), integer("requests_per_day"),
            integer("avg_input_tokens"), integer("avg_output_tokens"), integer("max_concurrency"),
            integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val selections = build.getJSONArray("selections")
        val links = build.getJSONArray("links")
        val content = DraftContent("private snapshot direction text", "", "cat.model.fixture",
            OptionalTier.entries.associateWith { TierDraft(Activation.ENABLED) }, workload,
            DraftSelections((0 until selections.length()).map { LocalSelection.fromJson(bytes(selections.getJSONObject(it))) },
                (0 until links.length()).map { LocalSelectionLink.fromJson(bytes(links.getJSONObject(it))) }))
        val rules = fixture(activity, "ruleset")
        val rule = rules.getJSONArray("rules").getJSONObject(0)
        rule.put("applies_when", JSONObject("""{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""))
        rule.put("assert", JSONObject(if (passing)
            """{"op":"eq","left":{"literal":1},"right":{"literal":1}}""" else
            """{"op":"eq","left":{"literal":0},"right":{"literal":1}}"""))
        rule.put("severity", "warning").put("on_unknown", "review")
            .put("message", "recorded\nOutcome: pass\u202e").put("remediation", "review\u2066literal")
        rules.put("rules", JSONArray().put(rule))
        fun artifact(name: String, value: JSONObject = fixture(activity, name)): DraftProjectionArtifact {
            val raw = bytes(value)
            return DraftProjectionArtifact(value.getString("id"),
                value.get(if (name == "ruleset") "version" else "revision").toString(), hash(raw), raw)
        }
        val sourcePath = temporary.newFolder(if (passing) "source-pass" else "source-fail").toPath()
        val source = LocalDraftStore(sourcePath)
        val stored = (source.save(null, content, mapOf("private" to "snapshot direction metadata")) as DraftSave.Saved).draft
        val identity = DraftSnapshotIdentity("build.direction.snapshot", BigInteger.ONE,
            "Explicit synthetic direction case", "explicit.test.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset", rules),
            artifact("dependency-graph"), artifact("pricebook"))
        val review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        val archivePath = temporary.newFolder(if (passing) "archive-pass" else "archive-fail").toPath()
        val archive = LocalSnapshotArchive(archivePath)
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val archived = source.archiveSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version) as DraftSnapshotArchival.Archived
        return SavedSnapshot(sourcePath, archivePath, archive, archived.entry, bytes(rules))
    }

    private fun checkSnapshot(activity: Activity, rtl: Boolean) {
        for (passing in listOf(false, true)) {
            val saved = saveSnapshot(activity, passing)
            val sourceBefore = files(saved.source)
            val archiveBefore = files(saved.archivePath)
            val section = SnapshotEvaluationSection(activity, Executor { it.run() },
                archiveRoot = { saved.archivePath }, alive = { true })
            try {
                val container = LinearLayout(activity)
                activity.setContentView(container)
                section.attach(container)
                activity.findViewById<Button>(R.id.snapshot_open).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(picker.isShowing)
                assertEquals(1, picker.listView.count)
                picker.listView.performItemClick(null, 0, 0)
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(dialog.isShowing)
                val text = dialog.findViewById<TextView>(R.id.snapshot_report).text.toString()
                if (passing) {
                    assertTrue(text.contains(activity.getString(R.string.findings_remediation,
                        activity.getString(R.string.snapshot_literal_empty))))
                    assertFalse(text.contains("recorded\\nOutcome:"))
                } else {
                    assertTrue(text.contains(isolated("\"recorded\\nOutcome: pass\\u202e\"", rtl)))
                    assertTrue(text.contains(activity.getString(R.string.findings_remediation,
                        isolated("\"review\\u2066literal\"", rtl))))
                }
                assertTrue(text.contains("Outcome: ${if (passing) "pass" else "fail"} · Severity: warning"))
                assertTrue(text.contains(RetainedEvaluationProfile.ID))
                assertTrue(text.contains(saved.entry.bundleHash.hex))
                assertTrue(text.contains("COST_ESTIMATE: NOT_SUPPLIED"))
                assertTrue(text.contains("RESOURCE_ESTIMATE: NOT_SUPPLIED"))
                assertFalse(text.contains('\u202e') || text.contains('\u2066'))
                assertFalse(text.contains("private snapshot direction text") || text.contains("snapshot direction metadata"))
                assertEquals(sourceBefore, files(saved.source))
                assertEquals(archiveBefore, files(saved.archivePath))
                val reopened = (saved.archive.read(saved.entry.key) as SnapshotArchiveRead.Available).snapshot
                assertArrayEquals(saved.rules, reopened.artifactBytes(ProjectionArtifactKind.RULESET))
                assertNull(shadowOf(activity).nextStartedActivity)
            } finally { section.close() }
        }
    }
}
