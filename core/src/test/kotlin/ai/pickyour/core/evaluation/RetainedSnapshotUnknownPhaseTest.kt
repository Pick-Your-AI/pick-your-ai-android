package ai.pickyour.core.evaluation

import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.PreviewSnapshotAnalysis
import ai.pickyour.core.preview.PreviewSnapshotAnalysisState
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor

/** Real saved/projected/archived requests; phase is neither a frozen field nor a causal explanation. */
class RetainedSnapshotUnknownPhaseTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private val observedAt = Instant.parse("2026-10-20T19:00:00Z")
    private val local = LocalSnapshotEvaluator(Clock.fixed(observedAt, ZoneOffset.UTC))
    private val yes = """{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""
    private val no = """{"op":"eq","left":{"literal":1},"right":{"literal":2}}"""
    private val unknown = """{"op":"eq","left":{"ref":{"scope":"build","pointer":"/missing"}},"right":{"literal":1}}"""
    private val unsupported = """{"op":"contains","left":{"literal":"text"},"right":{"literal":1}}"""
    private val message = "ASSERTION; cause and feasible repair are quoted rule claims only"
    private val remediation = "APPLICABILITY; supplied advice is not executed"

    private class Saved(val archive: LocalSnapshotArchive, val state: SnapshotArchiveState,
        val entry: SnapshotArchiveEntry, val snapshot: RetainedDraftSnapshot, val request: SnapshotEvaluationRequest)

    private fun save(vararg expressions: Pair<String, String>): Saved {
        val directory = Files.createDirectory(root.resolve("case-" + serial++))
        val source = LocalDraftStore(Files.createDirectory(directory.resolve("source")))
        val stored = (source.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("private" to "retained-phase-private")) as DraftSave.Saved).draft
        val rules = ProjectionFixtures.node("ruleset")
        val template = rules["rules"][0].deepCopy<ObjectNode>()
        val values = rules.putArray("rules")
        expressions.forEachIndexed { index, (applicable, assertion) ->
            val rule = template.deepCopy().put("id", "rule.retained.phase." + (index + 1))
                .put("message", message).put("remediation", remediation)
            rule.putArray("affected_kinds").add("model")
            rule.set<JsonNode>("applies_when", mapper.readTree(applicable))
            rule.set<JsonNode>("assert", mapper.readTree(assertion))
            values.add(rule)
        }
        val input = ProjectionFixtures.inputs(ProjectionFixtures.identity("build.retained.phase." + serial),
            ruleset = ProjectionFixtures.artifact("ruleset", rules))
        val review = source.prepareSnapshotCandidate(stored.version, input) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(Files.createDirectory(directory.resolve("archive")))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val archived = source.archiveSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version) as DraftSnapshotArchival.Archived
        val state = (archive.load() as SnapshotArchiveLoad.Available).state
        val snapshot = (archive.read(archived.entry.key) as SnapshotArchiveRead.Available).snapshot
        val request = (LocalSnapshotEvaluator.prepare(snapshot) as SnapshotEvaluationPreparation.Ready).request
        return Saved(archive, state, archived.entry, snapshot, request)
    }

    private fun trace(request: SnapshotEvaluationRequest, budget: Int = 250_000) =
        FrozenDiagnosticEvaluator(budget).evaluateRetainedSnapshot(request)
    private fun report(trace: FrozenDiagnosticEvaluator.RetainedSnapshotTrace): ReportReception.Accepted {
        assertTrue(trace.evaluation is DiagnosticEvaluation.Evaluated, trace.evaluation.toString())
        return (trace.evaluation as DiagnosticEvaluation.Evaluated).report
    }
    private fun completed(request: SnapshotEvaluationRequest, trace: FrozenDiagnosticEvaluator.RetainedSnapshotTrace) =
        SnapshotEvaluationOutcome.Evaluated(request, report(trace), observedAt, trace)
    private fun legacy(request: SnapshotEvaluationRequest, budget: Int = 250_000) = FrozenDiagnosticEvaluator(budget).evaluate(
        request.bytes(SnapshotEvaluationSlot.BUILD).inputStream(), request.bytes(SnapshotEvaluationSlot.CATALOG).inputStream(),
        request.bytes(SnapshotEvaluationSlot.RULESET).inputStream())
    private fun rejected(value: DiagnosticEvaluation, reason: DiagnosticRejection) {
        assertTrue(value is DiagnosticEvaluation.Rejected, value.toString())
        assertEquals(reason, (value as DiagnosticEvaluation.Rejected).reason)
    }
    /** One affected kind: the original loop charges two per active row, plus its matching ID. */
    private fun selectionCharge(request: SnapshotEvaluationRequest): Int =
        mapper.readTree(request.bytes(SnapshotEvaluationSlot.BUILD))["selections"].filter { it["enabled"].asBoolean() }.sumOf {
            2 + if (it["kind"].asText() == "model") it["id"].asText().length * 32 else 0
        }
    private fun disk() = Files.walk(root).use { files -> files.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to Files.readAllBytes(it).toList()
    } }

    @Test fun unknownApplicabilityDoesNotEvaluateTheUnsupportedAssertion() {
        val request = save(unknown to unsupported).request
        val budget = 15 + selectionCharge(request)
        rejected(trace(request, budget - 1).evaluation, DiagnosticRejection.WORK_LIMIT)
        rejected(legacy(request, budget - 1), DiagnosticRejection.WORK_LIMIT)
        val actual = completed(request, trace(request, budget))
        val finding = actual.report.inspect().findings.single()
        assertEquals("unknown", finding.outcome)
        assertEquals(message, finding.message)
        assertEquals(remediation, finding.remediation)
        assertEquals(SnapshotUnknownPhase.APPLICABILITY, actual.unknownPhase(finding.ruleId))
        assertEquals((legacy(request, budget) as DiagnosticEvaluation.Evaluated).report.json, actual.report.json)
        rejected(trace(save(yes to unsupported).request).evaluation, DiagnosticRejection.UNSUPPORTED_OPERATION)
    }

    @Test fun unknownAssertionRequiresConfirmedApplicabilityWithUnchangedBudget() {
        val request = save(yes to unknown).request
        val budget = 23 + selectionCharge(request)
        rejected(trace(request, budget - 1).evaluation, DiagnosticRejection.WORK_LIMIT)
        rejected(legacy(request, budget - 1), DiagnosticRejection.WORK_LIMIT)
        val actual = completed(request, trace(request, budget))
        assertEquals("unknown", actual.report.inspect().findings.single().outcome)
        assertEquals(SnapshotUnknownPhase.ASSERTION, actual.unknownPhase("rule.retained.phase.1"))
        assertEquals((legacy(request, budget) as DiagnosticEvaluation.Evaluated).report.json, actual.report.json)
        assertNull(actual.unknownPhase("RULE.RETAINED.PHASE.1"))
        assertNull(actual.unknownPhase("rule.retained.phase.absent"))
    }

    @Test fun skippedPassAndFailReportsNeverAcquireUnknownPhase() {
        val request = save(no to unsupported, yes to yes, yes to no).request
        val actual = local.evaluate(request) as SnapshotEvaluationOutcome.Evaluated
        assertEquals(listOf("skipped", "pass", "fail"), actual.report.inspect().findings.map { it.outcome })
        actual.report.inspect().findings.forEach { assertNull(actual.unknownPhase(it.ruleId)) }
        assertEquals((legacy(request) as DiagnosticEvaluation.Evaluated).report.json, actual.report.json)
    }

    @Test fun bareReparsedForeignAndReissuedResultsCannotBorrowProducerPhase() {
        val saved = save(unknown to unsupported)
        val request = saved.request
        val produced = trace(request)
        val original = report(produced)
        val readmitted = ReferenceReportAdapter().receive(request.bytes(SnapshotEvaluationSlot.BUILD).inputStream(),
            request.bytes(SnapshotEvaluationSlot.RULESET).inputStream(), original.json.byteInputStream()) as ReportReception.Accepted
        assertEquals(original.json, readmitted.json)
        assertNotSame(original, readmitted)
        assertNull(SnapshotEvaluationOutcome.Evaluated(request, original, observedAt).unknownPhase("rule.retained.phase.1"))
        assertNull(SnapshotEvaluationOutcome.Evaluated(request, readmitted, observedAt, produced).unknownPhase("rule.retained.phase.1"))
        val repeated = trace(request)
        assertNotSame(original, report(repeated))
        assertNull(SnapshotEvaluationOutcome.Evaluated(request, report(repeated), observedAt, produced).unknownPhase("rule.retained.phase.1"))
        val reissued = (LocalSnapshotEvaluator.prepare(saved.snapshot) as SnapshotEvaluationPreparation.Ready).request
        assertEquals(request.digest, reissued.digest)
        assertNotSame(request, reissued)
        assertNull(SnapshotEvaluationOutcome.Evaluated(reissued, original, observedAt, produced).unknownPhase("rule.retained.phase.1"))
        val fresh = local.evaluate(reissued) as SnapshotEvaluationOutcome.Evaluated
        assertTrue(fresh.belongsTo(reissued)); assertFalse(fresh.belongsTo(request))
        assertEquals(SnapshotUnknownPhase.APPLICABILITY, fresh.unknownPhase("rule.retained.phase.1"))
        assertEquals(SnapshotUnknownPhase.APPLICABILITY, completed(request, produced).unknownPhase("rule.retained.phase.1"))
    }

    @Test fun laterWorkAndUnsupportedFailuresDiscardEveryPartialObservation() {
        val first = save(unknown to unsupported).request
        val accepted = report(trace(first))
        val limited = save(unknown to unsupported, yes to yes).request
        val unsupportedRequest = save(unknown to unsupported, yes to unsupported).request
        val before = disk()
        val exhausted = trace(limited, 15 + selectionCharge(limited))
        rejected(exhausted.evaluation, DiagnosticRejection.WORK_LIMIT)
        assertTrue(exhausted.phasesFor(limited, accepted).isEmpty())
        val failed = trace(unsupportedRequest)
        rejected(failed.evaluation, DiagnosticRejection.UNSUPPORTED_OPERATION)
        assertTrue(failed.phasesFor(unsupportedRequest, accepted).isEmpty())
        val outcome = local.evaluate(unsupportedRequest) as SnapshotEvaluationOutcome.Failed
        assertEquals(SnapshotEvaluationFailure.UNSUPPORTED_OPERATION, outcome.reason)
        assertEquals(before, disk())
    }

    @Test fun immutableObservationsPreserveStoredInputsAndCompleteGoldenReportBytes() {
        val request = save(unknown to unsupported, yes to unknown).request
        val golden = SnapshotEvaluationFixtures.save(root.resolve("golden"))
        val goldenRequest = SnapshotEvaluationFixtures.prepared(golden.snapshot)
        val before = disk()
        val produced = trace(request)
        val original = report(produced)
        val actual = completed(request, produced)
        assertThrows(UnsupportedOperationException::class.java) {
            (produced.phasesFor(request, original) as MutableMap<String, SnapshotUnknownPhase>).clear()
        }
        request.bytes(SnapshotEvaluationSlot.RULESET).fill(0)
        assertEquals(listOf(SnapshotUnknownPhase.APPLICABILITY, SnapshotUnknownPhase.ASSERTION),
            actual.report.inspect().findings.map { actual.unknownPhase(it.ruleId) })
        assertEquals((legacy(request) as DiagnosticEvaluation.Evaluated).report.json, actual.report.json)
        val result = local.evaluate(goldenRequest) as SnapshotEvaluationOutcome.Evaluated
        val expected = mapper.readTree(javaClass.getResourceAsStream("/evaluation/diagnostic-cases.json"))
            .single { it["name"].asText() == "original-strict-False" }["report"].deepCopy<ObjectNode>()
        expected.put("id", "evaluation." + golden.entry.key.id + ".r" + golden.entry.key.revision)
            .put("build_id", golden.entry.key.id).put("build_revision", golden.entry.key.revision)
        assertEquals(mapper.readTree(expected.toString()), mapper.readTree(result.report.json))
        assertEquals((legacy(goldenRequest) as DiagnosticEvaluation.Evaluated).report.json, result.report.json)
        assertEquals(result.report.json, (local.evaluate(goldenRequest) as SnapshotEvaluationOutcome.Evaluated).report.json)
        mapper.readTree(result.report.json)["findings"].forEach {
            assertEquals(setOf("rule_id", "outcome", "severity", "code", "message", "remediation", "selection_ids"),
                it.fieldNames().asSequence().toSet())
        }
        assertEquals(before, disk())
    }

    @Test fun cancellationAndClockFailureNeverExposeAnObservedResult() {
        val request = save(yes to unknown).request
        val before = disk()
        try {
            Thread.currentThread().interrupt()
            assertTrue(local.evaluate(request) is SnapshotEvaluationOutcome.Cancelled)
        } finally { Thread.interrupted() }
        val unavailable = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
            override fun instant(): Instant = throw IllegalStateException("private clock detail")
        }
        val failed = LocalSnapshotEvaluator(unavailable).evaluate(request) as SnapshotEvaluationOutcome.Failed
        assertEquals(SnapshotEvaluationFailure.ENGINE_FAILURE, failed.reason)
        assertFalse(failed.toString().contains("private clock detail"))
        var observations = 0
        val interrupted = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
            override fun instant(): Instant { observations++; Thread.currentThread().interrupt(); return observedAt }
        }
        try {
            assertTrue(LocalSnapshotEvaluator(interrupted).evaluate(request) is SnapshotEvaluationOutcome.Cancelled)
            assertEquals(1, observations)
        } finally { Thread.interrupted() }
        assertEquals(SnapshotUnknownPhase.ASSERTION,
            (local.evaluate(request) as SnapshotEvaluationOutcome.Evaluated).unknownPhase("rule.retained.phase.1"))
        assertEquals(before, disk())
    }

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { tasks.removeFirst().run() }
    }

    @Test fun replacementAndClosedSessionsCannotPublishAnOlderPhase() {
        val first = save(unknown to unsupported)
        val second = save(yes to unknown)
        val before = disk()
        val work = Queue(); val delivery = Queue()
        val session = PreviewSnapshotAnalysis(work, delivery, local)
        assertTrue(session.open(first.archive, first.state.version, first.entry))
        work.run()
        assertTrue(session.open(second.archive, second.state.version, second.entry))
        work.run()
        delivery.run()
        assertTrue(session.snapshot() is PreviewSnapshotAnalysisState.Evaluating)
        delivery.run()
        val current = session.snapshot() as PreviewSnapshotAnalysisState.Current
        assertEquals(second.entry.key, current.selection.key)
        assertEquals(SnapshotUnknownPhase.ASSERTION, current.result.unknownPhase("rule.retained.phase.1"))
        assertTrue(session.open(first.archive, first.state.version, first.entry))
        work.run(); session.close(); delivery.run()
        assertSame(PreviewSnapshotAnalysisState.Closed, session.snapshot())
        assertEquals(before, disk())
    }
}
