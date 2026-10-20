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

/** Actual admitted Current/archived inputs; observations are not a complete cause or remedy. */
class UnknownInputObservationTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private val observedAt = Instant.parse("2026-10-20T20:00:00Z")
    private val local = LocalSnapshotEvaluator(Clock.fixed(observedAt, ZoneOffset.UTC))
    private val yes = """{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""
    private val no = """{"op":"eq","left":{"literal":1},"right":{"literal":2}}"""
    private val missing = """{"ref":{"scope":"build","pointer":"/missing"}}"""
    private val domain = """{"op":"lt","left":{"literal":"private_non_numeric_value"},"right":{"literal":1}}"""
    private val metric = """{"op":"gte","left":{"metric":"cost.monthly_total_amount"},"right":{"literal":0}}"""
    private val unsupported = """{"op":"contains","left":{"literal":"text"},"right":{"literal":1}}"""
    private val unknown get() = compare("eq", missing, """{"literal":1}""")
    private val message = "Quoted missing input and feasible remedy claim"
    private val remediation = "Quoted field path and candidate advice only"

    private fun fixture(name: String) = ProjectionFixtures.node(name)
    private fun bytes(node: JsonNode) = mapper.writeValueAsBytes(node)
    private fun compare(op: String, left: String, right: String) = """{"op":"$op","left":$left,"right":$right}"""
    private fun group(op: String, vararg args: String) = """{"op":"$op","args":[""" + args.joinToString(",") + "]}"
    private fun implies(a: String, b: String) = """{"op":"implies","if":$a,"then":$b}"""
    private fun negate(arg: String) = """{"op":"not","arg":$arg}"""
    private fun exists(operand: String) = """{"op":"exists","value":$operand}"""
    private fun rules(vararg stages: Pair<String, String>): ObjectNode {
        val rules = fixture("ruleset")
        val template = rules["rules"][0].deepCopy<ObjectNode>()
        val rows = rules.putArray("rules")
        stages.forEachIndexed { index, (applicable, assertion) ->
            val rule = template.deepCopy().put("id", "rule.inputs." + (index + 1))
                .put("message", message).put("remediation", remediation)
            rule.putArray("affected_kinds").add("model")
            rule.set<JsonNode>("applies_when", mapper.readTree(applicable))
            rule.set<JsonNode>("assert", mapper.readTree(assertion))
            rows.add(rule)
        }
        return rules
    }
    private fun capture(rules: ObjectNode, content: DraftContent = DraftContent(),
        catalog: ObjectNode = fixture("catalog")): DraftCurrentEvaluation {
        val inputs = requireNotNull(DraftCurrentInputs.read(bytes(catalog), bytes(fixture("dependency-graph")), bytes(rules)))
        val store = LocalDraftStore(Files.createDirectory(root.resolve("current-" + serial++)))
        val saved = (store.save(null, content, mapOf("private" to "input-observation-private")) as DraftSave.Saved).draft
        return DraftCurrentEvaluation(saved, inputs, DraftCurrentEvaluation.plan(null, null, saved, inputs))
    }
    private fun trace(request: DraftCurrentEvaluation, budget: Int = 250_000) =
        FrozenDiagnosticEvaluator(budget).evaluateCurrentDraft(request)
    private fun rows(value: FrozenDiagnosticEvaluator.CurrentDraftTrace): List<DraftRuleFinding> {
        assertTrue(value.evaluation is DraftRuleEvaluation.Evaluated, value.evaluation.toString())
        return (value.evaluation as DraftRuleEvaluation.Evaluated).findings
    }
    private fun current(request: DraftCurrentEvaluation, budget: Int = 250_000): DraftCurrentOutcome.Completed {
        val produced = trace(request, budget)
        return DraftCurrentOutcome.Completed(request, rows(produced), produced)
    }
    private fun legacy(request: DraftCurrentEvaluation, budget: Int = 250_000) = FrozenDiagnosticEvaluator(budget).evaluateDraft(
        DraftDiagnosticContext.create(request.source.content.components, request.source.content.workload),
        request.inputs.catalogBytes(), request.inputs.rulesBytes())
    private fun findingBytes(findings: List<DraftRuleFinding>) = mapper.writeValueAsBytes(findings.map {
        listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds)
    })
    private fun rejected(value: DraftRuleEvaluation, reason: DiagnosticRejection) {
        assertTrue(value is DraftRuleEvaluation.Rejected, value.toString())
        assertEquals(reason, (value as DraftRuleEvaluation.Rejected).reason)
    }
    private fun disk() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to Files.readAllBytes(it).toList()
    } }
    private fun observed(actual: DraftCurrentOutcome.Completed, index: Int, outcome: String,
        vararg inputs: UnknownInputObservation) {
        val finding = actual.findings[index]
        assertEquals(outcome, finding.outcome)
        assertEquals(inputs.toList(), actual.unknownInputObservations(finding.ruleId))
        if (outcome != "unknown") assertNull(actual.unknownPhase(finding.ruleId))
    }

    @Test fun absentOperandsAndPresentJsonNullKeepTheirDifferentSemantics() {
        val nullValue = """{"literal":null}"""
        val request = capture(rules(yes to unknown, yes to compare("eq", nullValue, """{"literal":1}"""),
            yes to compare("lt", nullValue, """{"literal":1}"""), yes to exists(missing), yes to exists(nullValue)))
        val actual = current(request)
        observed(actual, 0, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
        observed(actual, 1, "fail")
        observed(actual, 2, "unknown", UnknownInputObservation.OPERAND_DOMAIN_UNAVAILABLE)
        observed(actual, 3, "fail"); observed(actual, 4, "fail")
        assertArrayEquals(findingBytes((legacy(request) as DraftRuleEvaluation.Evaluated).findings), findingBytes(actual.findings))
    }

    @Test fun absentAmbiguousAndUnresolvedCatalogReadsRemainDistinct() {
        val selections = DraftSelections(listOf(ProjectionFixtures.selection("model"), ProjectionFixtures.selection("model_provider")))
        val catalog = fixture("catalog")
        val kept = catalog["items"].filter { it["id"].asText() != "cat.model.fixture" }
        catalog.putArray("items").addAll(kept)
        val noMatch = """{"ref":{"scope":"selection","kind":"tool","pointer":"/id"}}"""
        val ambiguous = """{"ref":{"scope":"selection","pointer":"/id"}}"""
        val absentCatalog = """{"ref":{"scope":"catalog","kind":"model","pointer":"/spec/provider_ref"}}"""
        val selected = """{"ref":{"scope":"selection","kind":"model","pointer":"/id"}}"""
        val request = capture(rules(yes to compare("eq", noMatch, """{"literal":1}"""),
            yes to compare("eq", ambiguous, """{"literal":1}"""), yes to compare("eq", absentCatalog, """{"literal":1}"""),
            yes to compare("eq", selected, """{"literal":"sel_model"}""")),
            ProjectionFixtures.content(components = selections), catalog)
        val actual = DraftCurrentEvaluator().evaluate(request) as DraftCurrentOutcome.Completed
        observed(actual, 0, "unknown", UnknownInputObservation.NO_MATCHING_SELECTION)
        observed(actual, 1, "unknown", UnknownInputObservation.AMBIGUOUS_SELECTION)
        observed(actual, 2, "unknown", UnknownInputObservation.CATALOG_ENTRY_UNAVAILABLE)
        observed(actual, 3, "pass")
        assertEquals(message, actual.findings[0].message); assertEquals(remediation, actual.findings[1].remediation)
        assertArrayEquals(findingBytes((legacy(request) as DraftRuleEvaluation.Evaluated).findings), findingBytes(actual.findings))
    }

    @Test fun boundedUnionSeparatesEveryReachedLimitationWithoutRawValues() {
        val noMatch = compare("eq", """{"ref":{"scope":"selection","kind":"tool","pointer":"/id"}}""", """{"literal":1}""")
        val ambiguous = compare("eq", """{"ref":{"scope":"selection","pointer":"/id"}}""", """{"literal":1}""")
        val absentCatalog = compare("eq", """{"ref":{"scope":"catalog","kind":"model","pointer":"/spec/provider_ref"}}""", """{"literal":1}""")
        val invalidPointer = compare("eq", """{"ref":{"scope":"build","pointer":"/selections/not_an_index"}}""", """{"literal":1}""")
        val overflowPointer = compare("eq", """{"ref":{"scope":"build","pointer":"/selections/2147483648"}}""", """{"literal":1}""")
        val absentIndex = compare("eq", """{"ref":{"scope":"build","pointer":"/selections/20"}}""", """{"literal":1}""")
        val catalog = fixture("catalog")
        val kept = catalog["items"].filter { it["id"].asText() != "cat.model.fixture" }
        catalog.putArray("items").addAll(kept)
        val content = ProjectionFixtures.content(components = DraftSelections(
            listOf(ProjectionFixtures.selection("model"), ProjectionFixtures.selection("model_provider"))))
        val request = capture(rules(yes to group("all", metric, domain, absentCatalog, invalidPointer, unknown, ambiguous, noMatch,
            unknown, metric), yes to overflowPointer, yes to absentIndex), content, catalog)
        val actual = current(request)
        assertEquals(UnknownInputObservation.entries.toList(), actual.unknownInputObservations("rule.inputs.1"))
        assertEquals(7, actual.unknownInputObservations("rule.inputs.1").size)
        observed(actual, 1, "unknown", UnknownInputObservation.UNRESOLVABLE_REFERENCE)
        observed(actual, 2, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
        assertFalse(actual.unknownInputObservations("rule.inputs.1").toString().contains("private_non_numeric_value"))
        assertFalse(actual.unknownInputObservations("rule.inputs.1").toString().contains("/missing"))
    }

    @Test fun eagerBooleanResultsKeepOnlyContributingUnknownObservations() {
        val cases = listOf(negate(unknown), group("all", no, unknown), group("any", yes, unknown),
            implies(no, unknown), implies(unknown, yes), group("all", yes, unknown, domain),
            group("any", no, unknown, domain), implies(yes, unknown), implies(unknown, no),
            group("any", group("all", no, unknown), metric))
        val actual = current(capture(rules(*cases.map { yes to it }.toTypedArray())))
        observed(actual, 0, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
        observed(actual, 1, "fail"); observed(actual, 2, "pass"); observed(actual, 3, "pass"); observed(actual, 4, "pass")
        observed(actual, 5, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE, UnknownInputObservation.OPERAND_DOMAIN_UNAVAILABLE)
        observed(actual, 6, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE, UnknownInputObservation.OPERAND_DOMAIN_UNAVAILABLE)
        observed(actual, 7, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
        observed(actual, 8, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
        observed(actual, 9, "unknown", UnknownInputObservation.METRIC_NOT_SUPPLIED)
        // Determined results must not short-circuit the original eager visits or their rejection.
        for (expression in listOf(group("all", no, unsupported), group("any", yes, unsupported),
            implies(no, unsupported), implies(unsupported, yes))) {
            val request = capture(rules(yes to expression))
            rejected(trace(request).evaluation, DiagnosticRejection.UNSUPPORTED_OPERATION)
            rejected(legacy(request), DiagnosticRejection.UNSUPPORTED_OPERATION)
        }
    }

    @Test fun unsupportedOperandsAndLaterFailuresDiscardTheCompleteTrace() {
        val nested = compare("intersects", missing, """{"literal":[[1]]}""")
        val fractional = compare("eq", missing, """{"literal":1.5}""")
        for (expression in listOf(nested, fractional)) {
            val request = capture(rules(unknown to unsupported, yes to expression))
            val failed = trace(request)
            rejected(failed.evaluation, DiagnosticRejection.UNSUPPORTED_OPERATION)
            rejected(legacy(request), DiagnosticRejection.UNSUPPORTED_OPERATION)
            assertTrue(failed.observationsFor(request, emptyList()).isEmpty())
            assertTrue(DraftCurrentEvaluator().evaluate(request) is DraftCurrentOutcome.Unavailable)
        }
        val request = capture(rules(unknown to unsupported, yes to yes))
        val exhausted = trace(request, 15)
        rejected(exhausted.evaluation, DiagnosticRejection.WORK_LIMIT)
        assertTrue(exhausted.observationsFor(request, emptyList()).isEmpty())
        rejected(legacy(request, 15), DiagnosticRejection.WORK_LIMIT)
    }

    @Test fun phaseBudgetsAndEveryLegacyFindingByteRemainUnchanged() {
        for ((stages, budget, phase) in listOf(Triple(unknown to unsupported, 15, DraftCurrentUnknownPhase.APPLICABILITY),
            Triple(yes to unknown, 23, DraftCurrentUnknownPhase.ASSERTION))) {
            val request = capture(rules(stages))
            rejected(trace(request, budget - 1).evaluation, DiagnosticRejection.WORK_LIMIT)
            rejected(legacy(request, budget - 1), DiagnosticRejection.WORK_LIMIT)
            val actual = current(request, budget)
            assertEquals(phase, actual.unknownPhase("rule.inputs.1"))
            observed(actual, 0, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
            assertArrayEquals(findingBytes((legacy(request, budget) as DraftRuleEvaluation.Evaluated).findings), findingBytes(actual.findings))
        }
    }

    @Test fun currentInputObservationsRequireOriginalRowsAndRequestAndStayImmutable() {
        val request = capture(rules(unknown to unsupported, yes to metric))
        val produced = trace(request); val original = rows(produced)
        val actual = DraftCurrentOutcome.Completed(request, original, produced)
        val before = disk()
        val copied = original.map { DraftRuleFinding(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds.toList()) }
        assertTrue(DraftCurrentOutcome.Completed(request, original).unknownInputObservations("rule.inputs.1").isEmpty())
        assertTrue(DraftCurrentOutcome.Completed(request, copied, produced).unknownInputObservations("rule.inputs.1").isEmpty())
        assertTrue(DraftCurrentOutcome.Completed(request, rows(trace(request)), produced).unknownInputObservations("rule.inputs.1").isEmpty())
        val reissued = DraftCurrentEvaluation(request.source, request.inputs, request.recheck)
        assertEquals(request.binding, reissued.binding)
        assertTrue(DraftCurrentOutcome.Completed(reissued, original, produced).unknownInputObservations("rule.inputs.1").isEmpty())
        assertTrue(actual.unknownInputObservations("RULE.INPUTS.1").isEmpty())
        assertTrue(actual.unknownInputObservations("absent.rule").isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (produced.observationsFor(request, original) as MutableMap<String, List<UnknownInputObservation>>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (produced.observationsFor(request, original).getValue("rule.inputs.1") as MutableList<UnknownInputObservation>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (actual.unknownInputObservations("rule.inputs.1") as MutableList<UnknownInputObservation>).add(UnknownInputObservation.METRIC_NOT_SUPPLIED)
        }
        request.inputs.rulesBytes().fill(0)
        observed(actual, 0, "unknown", UnknownInputObservation.MISSING_CAPTURED_VALUE)
        observed(actual, 1, "unknown", UnknownInputObservation.METRIC_NOT_SUPPLIED)
        assertEquals(before, disk())
    }

    private class Saved(val archive: LocalSnapshotArchive, val state: SnapshotArchiveState,
        val entry: SnapshotArchiveEntry, val snapshot: RetainedDraftSnapshot, val request: SnapshotEvaluationRequest)
    private fun retained(vararg stages: Pair<String, String>): Saved {
        val directory = Files.createDirectory(root.resolve("retained-" + serial++))
        val source = LocalDraftStore(Files.createDirectory(directory.resolve("source")))
        val saved = (source.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("private" to "retained-inputs-private")) as DraftSave.Saved).draft
        val inputs = ProjectionFixtures.inputs(ProjectionFixtures.identity("build.inputs." + serial),
            ruleset = ProjectionFixtures.artifact("ruleset", rules(*stages)))
        val review = source.prepareSnapshotCandidate(saved.version, inputs) as DraftProjectionPreparation.Review
        val archive = LocalSnapshotArchive(Files.createDirectory(directory.resolve("archive")))
        val version = (archive.load() as SnapshotArchiveLoad.Available).state.version
        val archived = source.archiveSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version) as DraftSnapshotArchival.Archived
        val state = (archive.load() as SnapshotArchiveLoad.Available).state
        val snapshot = (archive.read(archived.entry.key) as SnapshotArchiveRead.Available).snapshot
        return Saved(archive, state, archived.entry, snapshot, SnapshotEvaluationFixtures.prepared(snapshot))
    }
    private fun retainedTrace(request: SnapshotEvaluationRequest, budget: Int = 250_000) =
        FrozenDiagnosticEvaluator(budget).evaluateRetainedSnapshot(request)
    private fun report(produced: FrozenDiagnosticEvaluator.RetainedSnapshotTrace): ReportReception.Accepted {
        assertTrue(produced.evaluation is DiagnosticEvaluation.Evaluated, produced.evaluation.toString())
        return (produced.evaluation as DiagnosticEvaluation.Evaluated).report
    }
    private fun selectionCharge(request: SnapshotEvaluationRequest) =
        mapper.readTree(request.bytes(SnapshotEvaluationSlot.BUILD))["selections"].filter { it["enabled"].asBoolean() }.sumOf {
            2 + if (it["kind"].asText() == "model") it["id"].asText().length * 32 else 0
        }

    @Test fun retainedInputsRequireOriginalReportAndRequestAndSurviveDefensiveCopies() {
        val saved = retained(yes to group("all", unknown, metric))
        val request = saved.request
        val produced = retainedTrace(request); val original = report(produced)
        val actual = SnapshotEvaluationOutcome.Evaluated(request, original, observedAt, produced)
        val inputs = listOf(UnknownInputObservation.MISSING_CAPTURED_VALUE, UnknownInputObservation.METRIC_NOT_SUPPLIED)
        assertEquals(inputs, actual.unknownInputObservations("rule.inputs.1"))
        val readmitted = ReferenceReportAdapter().receive(request.bytes(SnapshotEvaluationSlot.BUILD).inputStream(),
            request.bytes(SnapshotEvaluationSlot.RULESET).inputStream(), original.json.byteInputStream()) as ReportReception.Accepted
        assertEquals(original.json, readmitted.json)
        assertTrue(SnapshotEvaluationOutcome.Evaluated(request, original, observedAt).unknownInputObservations("rule.inputs.1").isEmpty())
        assertTrue(SnapshotEvaluationOutcome.Evaluated(request, readmitted, observedAt, produced).unknownInputObservations("rule.inputs.1").isEmpty())
        assertTrue(SnapshotEvaluationOutcome.Evaluated(request, report(retainedTrace(request)), observedAt, produced)
            .unknownInputObservations("rule.inputs.1").isEmpty())
        val reissued = SnapshotEvaluationFixtures.prepared(saved.snapshot)
        assertEquals(request.digest, reissued.digest); assertNotSame(request, reissued)
        assertTrue(SnapshotEvaluationOutcome.Evaluated(reissued, original, observedAt, produced).unknownInputObservations("rule.inputs.1").isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (produced.observationsFor(request, original) as MutableMap<String, List<UnknownInputObservation>>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (actual.unknownInputObservations("rule.inputs.1") as MutableList<UnknownInputObservation>).clear()
        }
        request.bytes(SnapshotEvaluationSlot.RULESET).fill(0)
        val fresh = local.evaluate(reissued) as SnapshotEvaluationOutcome.Evaluated
        assertTrue(fresh.belongsTo(reissued)); assertFalse(fresh.belongsTo(request))
        assertEquals(inputs, fresh.unknownInputObservations("rule.inputs.1"))
        assertEquals(original.json, fresh.report.json)
    }

    @Test fun retainedLateRejectionsCancellationAndClockFailureExposeNoObservations() {
        val limited = retained(unknown to unsupported, yes to yes).request
        val failing = retained(unknown to unsupported, yes to unsupported).request
        val accepted = report(retainedTrace(retained(unknown to unsupported).request))
        val before = disk()
        val exhausted = retainedTrace(limited, 15 + selectionCharge(limited))
        assertEquals(DiagnosticRejection.WORK_LIMIT, (exhausted.evaluation as DiagnosticEvaluation.Rejected).reason)
        assertTrue(exhausted.observationsFor(limited, accepted).isEmpty())
        val unsupportedResult = retainedTrace(failing)
        assertEquals(DiagnosticRejection.UNSUPPORTED_OPERATION, (unsupportedResult.evaluation as DiagnosticEvaluation.Rejected).reason)
        assertTrue(unsupportedResult.observationsFor(failing, accepted).isEmpty())
        assertTrue(local.evaluate(failing) is SnapshotEvaluationOutcome.Failed)
        try {
            Thread.currentThread().interrupt()
            assertTrue(local.evaluate(limited) is SnapshotEvaluationOutcome.Cancelled)
        } finally { Thread.interrupted() }
        val unavailable = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
            override fun instant(): Instant = throw IllegalStateException("private input failure")
        }
        val failed = LocalSnapshotEvaluator(unavailable).evaluate(limited) as SnapshotEvaluationOutcome.Failed
        assertEquals(SnapshotEvaluationFailure.ENGINE_FAILURE, failed.reason)
        assertFalse(failed.toString().contains("private input failure"))
        assertEquals(before, disk())
    }

    @Test fun completeGoldenReportAndStoredBytesRemainUnchangedWithTheNewTrace() {
        val saved = SnapshotEvaluationFixtures.save(root.resolve("golden"))
        val request = SnapshotEvaluationFixtures.prepared(saved.snapshot)
        val before = disk()
        val actual = local.evaluate(request) as SnapshotEvaluationOutcome.Evaluated
        val expected = mapper.readTree(javaClass.getResourceAsStream("/evaluation/diagnostic-cases.json"))
            .single { it["name"].asText() == "original-strict-False" }["report"].deepCopy<ObjectNode>()
        expected.put("id", "evaluation." + saved.entry.key.id + ".r" + saved.entry.key.revision)
            .put("build_id", saved.entry.key.id).put("build_revision", saved.entry.key.revision)
        assertEquals(mapper.readTree(expected.toString()), mapper.readTree(actual.report.json))
        val legacy = FrozenDiagnosticEvaluator().evaluate(request.bytes(SnapshotEvaluationSlot.BUILD).inputStream(),
            request.bytes(SnapshotEvaluationSlot.CATALOG).inputStream(), request.bytes(SnapshotEvaluationSlot.RULESET).inputStream()) as DiagnosticEvaluation.Evaluated
        assertEquals(legacy.report.json, actual.report.json)
        assertEquals(actual.report.json, (local.evaluate(request) as SnapshotEvaluationOutcome.Evaluated).report.json)
        actual.report.inspect().findings.filter { it.outcome != "unknown" }.forEach {
            assertTrue(actual.unknownInputObservations(it.ruleId).isEmpty())
        }
        mapper.readTree(actual.report.json)["findings"].forEach {
            assertEquals(setOf("rule_id", "outcome", "severity", "code", "message", "remediation", "selection_ids"),
                it.fieldNames().asSequence().toSet())
        }
        assertEquals(before, disk())
    }

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { tasks.removeFirst().run() }
    }

    @Test fun replacementAndClosedRetainedSessionsCannotDeliverAnOlderInputTrace() {
        val first = retained(unknown to unsupported)
        val second = retained(yes to metric)
        val before = disk()
        val work = Queue(); val delivery = Queue()
        val session = PreviewSnapshotAnalysis(work, delivery, local)
        assertTrue(session.open(first.archive, first.state.version, first.entry)); work.run()
        assertTrue(session.open(second.archive, second.state.version, second.entry)); work.run()
        delivery.run(); assertTrue(session.snapshot() is PreviewSnapshotAnalysisState.Evaluating)
        delivery.run()
        val current = session.snapshot() as PreviewSnapshotAnalysisState.Current
        assertEquals(second.entry.key, current.selection.key)
        assertEquals(listOf(UnknownInputObservation.METRIC_NOT_SUPPLIED), current.result.unknownInputObservations("rule.inputs.1"))
        assertTrue(session.open(first.archive, first.state.version, first.entry)); work.run()
        session.close(); delivery.run()
        assertSame(PreviewSnapshotAnalysisState.Closed, session.snapshot())
        assertEquals(before, disk())
    }
}
