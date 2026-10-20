package ai.pickyour.core.evaluation

import ai.pickyour.core.drafts.DraftContent
import ai.pickyour.core.drafts.DraftSave
import ai.pickyour.core.drafts.LocalDraftStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Actual private evaluation and stored requests; synthetic rules expose only the observed phase. */
class DraftCurrentUnknownPhaseTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private val yes = """{"op":"eq","left":{"literal":1},"right":{"literal":1}}"""
    private val no = """{"op":"eq","left":{"literal":1},"right":{"literal":2}}"""
    private val unknown = """{"op":"eq","left":{"ref":{"scope":"build","pointer":"/missing"}},"right":{"literal":1}}"""
    private val unsupported = """{"op":"contains","left":{"literal":"text"},"right":{"literal":1}}"""
    private val suppliedMessage = "ASSERTION phase; missing model; Ready; replace selection — quoted rule text only"
    private val suppliedGuidance = "APPLICABILITY phase; feasible fix — quoted rule text only"

    private fun fixture(name: String) = mapper.readTree(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private fun bytes(node: JsonNode) = node.toString().toByteArray()
    private fun rules(vararg stages: Pair<String, String>): ObjectNode {
        val rules = fixture("ruleset")
        val template = rules["rules"][0].deepCopy<ObjectNode>()
        val rows = rules.putArray("rules")
        stages.forEachIndexed { index, (applicable, assertion) ->
            val rule = template.deepCopy<ObjectNode>().put("id", "rule.phase.${index + 1}")
                .put("message", suppliedMessage).put("remediation", suppliedGuidance)
            rule.set<JsonNode>("applies_when", mapper.readTree(applicable))
            rule.set<JsonNode>("assert", mapper.readTree(assertion))
            rows.add(rule)
        }
        return rules
    }
    private fun capture(rules: ObjectNode): DraftCurrentEvaluation {
        val inputs = requireNotNull(DraftCurrentInputs.read(bytes(fixture("catalog")), bytes(fixture("dependency-graph")), bytes(rules)))
        val store = LocalDraftStore(Files.createDirectory(root.resolve("phase-${serial++}")))
        val saved = (store.save(null, DraftContent(), mapOf("note" to "private phase test")) as DraftSave.Saved).draft
        return DraftCurrentEvaluation(saved, inputs, DraftCurrentEvaluation.plan(null, null, saved, inputs))
    }
    private fun runTrace(request: DraftCurrentEvaluation, budget: Int = 250_000) =
        FrozenDiagnosticEvaluator(budget).evaluateCurrentDraft(request)
    private fun rows(trace: FrozenDiagnosticEvaluator.CurrentDraftTrace): List<DraftRuleFinding> {
        assertTrue(trace.evaluation is DraftRuleEvaluation.Evaluated, trace.evaluation.toString())
        return (trace.evaluation as DraftRuleEvaluation.Evaluated).findings
    }
    private fun completed(request: DraftCurrentEvaluation, trace: FrozenDiagnosticEvaluator.CurrentDraftTrace) =
        DraftCurrentOutcome.Completed(request, rows(trace), trace)
    private fun legacy(request: DraftCurrentEvaluation, budget: Int = 250_000) = FrozenDiagnosticEvaluator(budget).evaluateDraft(
        DraftDiagnosticContext.create(request.source.content.components, request.source.content.workload),
        request.inputs.catalogBytes(), request.inputs.rulesBytes())
    private fun findingBytes(findings: List<DraftRuleFinding>) = mapper.writeValueAsBytes(findings.map {
        listOf(it.ruleId, it.outcome, it.severity, it.code, it.message, it.remediation, it.selectionIds)
    })
    private fun disk() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to Files.readAllBytes(it).toList()
    } }
    private fun rejected(result: DraftRuleEvaluation, reason: DiagnosticRejection) {
        assertTrue(result is DraftRuleEvaluation.Rejected, result.toString())
        assertEquals(reason, (result as DraftRuleEvaluation.Rejected).reason)
    }

    @Test fun unknownApplicabilityDoesNotEvaluateUnsupportedAssertion() {
        val request = capture(rules(unknown to unsupported))
        // Original loop charge: 1 rule + 14 for this exact /missing comparison, with no assertion.
        rejected(runTrace(request, 14).evaluation, DiagnosticRejection.WORK_LIMIT)
        rejected(legacy(request, 14), DiagnosticRejection.WORK_LIMIT)
        val trace = runTrace(request, 15)
        val current = completed(request, trace)
        val finding = current.findings.single()
        assertEquals("unknown", finding.outcome)
        assertEquals(DraftCurrentUnknownPhase.APPLICABILITY, current.unknownPhase(finding.ruleId))
        assertEquals(suppliedMessage, finding.message)
        assertEquals(suppliedGuidance, finding.remediation)
        assertArrayEquals(findingBytes((legacy(request, 15) as DraftRuleEvaluation.Evaluated).findings), findingBytes(current.findings))
        // This same assertion really is unsupported when applicability is true.
        rejected(runTrace(capture(rules(yes to unsupported))).evaluation, DiagnosticRejection.UNSUPPORTED_OPERATION)
    }

    @Test fun unknownAssertionRequiresConfirmedApplicability() {
        val request = capture(rules(yes to unknown))
        // Original loop charge: 1 rule + 8 for literal applicability + 14 for the assertion.
        rejected(runTrace(request, 22).evaluation, DiagnosticRejection.WORK_LIMIT)
        rejected(legacy(request, 22), DiagnosticRejection.WORK_LIMIT)
        val current = completed(request, runTrace(request, 23))
        assertEquals("unknown", current.findings.single().outcome)
        assertEquals(DraftCurrentUnknownPhase.ASSERTION, current.unknownPhase("rule.phase.1"))
        assertArrayEquals(findingBytes((legacy(request, 23) as DraftRuleEvaluation.Evaluated).findings), findingBytes(current.findings))
        assertNull(current.unknownPhase("RULE.PHASE.1"))
        assertNull(current.unknownPhase("rule.phase.absent"))
    }

    @Test fun skippedPassAndFailFindingsHaveNoUnknownPhase() {
        val request = capture(rules(no to unsupported, yes to yes, yes to no))
        val current = completed(request, runTrace(request))
        assertEquals(listOf("skipped", "pass", "fail"), current.findings.map { it.outcome })
        current.findings.forEach { assertNull(current.unknownPhase(it.ruleId)) }
        assertEquals("", current.findings[0].remediation)
        assertEquals("", current.findings[1].remediation)
        assertEquals(suppliedGuidance, current.findings[2].remediation)
        assertArrayEquals(findingBytes((legacy(request) as DraftRuleEvaluation.Evaluated).findings), findingBytes(current.findings))
    }

    @Test fun absentFabricatedAndForeignTracesCannotSupplyPhase() {
        val request = capture(rules(unknown to unsupported))
        val actualTrace = runTrace(request)
        val actualRows = rows(actualTrace)
        val actual = completed(request, actualTrace)
        val copied = actualRows.map { DraftRuleFinding(it.ruleId, it.outcome, it.severity, it.code,
            "Unknown phase: This rule applies", "claimed repair", it.selectionIds.toList()) }
        assertNull(DraftCurrentOutcome.Completed(request, actualRows).unknownPhase("rule.phase.1"))
        assertNull(DraftCurrentOutcome.Completed(request, copied, actualTrace).unknownPhase("rule.phase.1"))
        val repeatedTrace = runTrace(request)
        assertNotSame(actualRows, rows(repeatedTrace))
        assertNull(DraftCurrentOutcome.Completed(request, rows(repeatedTrace), actualTrace).unknownPhase("rule.phase.1"))
        assertEquals(DraftCurrentUnknownPhase.APPLICABILITY, actual.unknownPhase("rule.phase.1"))
        assertEquals(suppliedMessage, actual.findings.single().message)
        assertEquals(suppliedGuidance, actual.findings.single().remediation)
    }

    @Test fun sameInputReissuedRequestCannotBorrowAnOldTrace() {
        val request = capture(rules(yes to unknown))
        val oldTrace = runTrace(request)
        val reissued = DraftCurrentEvaluation(request.source, request.inputs, request.recheck)
        assertNotSame(request, reissued)
        assertEquals(request.binding, reissued.binding)
        assertNull(DraftCurrentOutcome.Completed(reissued, rows(oldTrace), oldTrace).unknownPhase("rule.phase.1"))
        val fresh = completed(reissued, runTrace(reissued))
        assertTrue(fresh.belongsTo(reissued))
        assertFalse(fresh.belongsTo(request))
        assertEquals(DraftCurrentUnknownPhase.ASSERTION, fresh.unknownPhase("rule.phase.1"))
        assertEquals(DraftCurrentUnknownPhase.ASSERTION, completed(request, oldTrace).unknownPhase("rule.phase.1"))
    }

    @Test fun partialWorkOrUnsupportedFailureDiscardsEveryPhase() {
        val budgetRequest = capture(rules(unknown to unsupported, yes to yes))
        val exhausted = runTrace(budgetRequest, 15)
        rejected(exhausted.evaluation, DiagnosticRejection.WORK_LIMIT)
        assertTrue(exhausted.phasesFor(budgetRequest, emptyList()).isEmpty())
        val unsupportedRequest = capture(rules(unknown to unsupported, yes to unsupported))
        val failed = runTrace(unsupportedRequest)
        rejected(failed.evaluation, DiagnosticRejection.UNSUPPORTED_OPERATION)
        assertTrue(failed.phasesFor(unsupportedRequest, emptyList()).isEmpty())
        assertTrue(DraftCurrentEvaluator().evaluate(unsupportedRequest) is DraftCurrentOutcome.Unavailable)
        // The first rule is independently reachable; the later failure cannot expose its partial trace.
        val first = capture(rules(unknown to unsupported))
        assertEquals(DraftCurrentUnknownPhase.APPLICABILITY, completed(first, runTrace(first)).unknownPhase("rule.phase.1"))
    }

    @Test fun traceAndCompletedCopiesAreImmutableAndPreserveStoredBytes() {
        val request = capture(rules(unknown to unsupported, yes to unknown))
        val before = disk()
        val trace = runTrace(request)
        val produced = rows(trace)
        val current = completed(request, trace)
        val capturedBytes = findingBytes(current.findings)
        assertNotSame(produced, current.findings)
        assertNotSame(produced[0], current.findings[0])
        assertThrows(UnsupportedOperationException::class.java) {
            (trace.phasesFor(request, produced) as MutableMap<String, DraftCurrentUnknownPhase>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) { (current.findings as MutableList<DraftRuleFinding>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (current.findings[0].selectionIds as MutableList<String>).add("fabricated") }
        val borrowed = request.inputs.rulesBytes()
        borrowed.fill(0)
        assertArrayEquals(capturedBytes, findingBytes(current.findings))
        assertEquals(DraftCurrentUnknownPhase.APPLICABILITY, current.unknownPhase("rule.phase.1"))
        assertEquals(DraftCurrentUnknownPhase.ASSERTION, current.unknownPhase("rule.phase.2"))
        assertEquals(request.binding, current.binding)
        assertEquals(before, disk())
    }

    @Test fun currentTraceDoesNotChangeFrozenReportsOrLegacyFindingBytes() {
        val cases = mapper.readTree(javaClass.getResourceAsStream("/evaluation/diagnostic-cases.json"))
        val case = cases.first { it["report"]["findings"].any { finding -> finding["outcome"].asText() == "unknown" } }
        fun report(): String {
            val value = FrozenDiagnosticEvaluator().evaluate(bytes(case["build"]).inputStream(),
                bytes(fixture("catalog")).inputStream(), bytes(case["ruleset"]).inputStream())
            assertTrue(value is DiagnosticEvaluation.Evaluated, value.toString())
            return (value as DiagnosticEvaluation.Evaluated).report.json
        }
        val before = report()
        assertEquals(case["report"], mapper.readTree(before))
        val request = capture(rules(unknown to unsupported, yes to unknown))
        val old = legacy(request) as DraftRuleEvaluation.Evaluated
        val current = DraftCurrentEvaluator().evaluate(request) as DraftCurrentOutcome.Completed
        assertArrayEquals(findingBytes(old.findings), findingBytes(current.findings))
        assertEquals(listOf(DraftCurrentUnknownPhase.APPLICABILITY, DraftCurrentUnknownPhase.ASSERTION),
            current.findings.map { current.unknownPhase(it.ruleId) })
        val after = report()
        assertEquals(before, after)
        assertEquals(case["report"], mapper.readTree(after))
        mapper.readTree(after)["findings"].forEach { finding ->
            assertEquals(setOf("rule_id", "outcome", "severity", "code", "message", "remediation", "selection_ids"),
                finding.fieldNames().asSequence().toSet())
        }
    }
}
