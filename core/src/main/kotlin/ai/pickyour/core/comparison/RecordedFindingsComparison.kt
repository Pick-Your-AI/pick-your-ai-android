package ai.pickyour.core.comparison

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenEvaluationReport
import ai.pickyour.core.contracts.FrozenEvaluationReportDecoder
import ai.pickyour.core.contracts.FrozenEvaluationReportDecoding
import ai.pickyour.core.contracts.FrozenReportFinding
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.evaluation.ReferenceReportAdapter
import ai.pickyour.core.evaluation.ReportReception
import ai.pickyour.core.evaluation.ReportRejection
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.util.Collections

/** Input dispositions, not a claim that a repository, server or device was searched. */
enum class HistoricalReportProblem {
    REPORT_NOT_SUPPLIED, RULESET_NOT_SUPPLIED, REPORT_AND_RULESET_NOT_SUPPLIED,
    INVALID_BUILD, INVALID_RULESET, INVALID_REPORT,
    UNSUPPORTED_BUILD_SCHEMA_VERSION, UNSUPPORTED_RULESET_SCHEMA_VERSION, UNSUPPORTED_REPORT_SCHEMA_VERSION,
    REPORT_IDENTITY_MISMATCH, BINDING_MISMATCH, INCONSISTENT_FINDINGS, BUILD_RECORD_MISMATCH,
}

sealed interface HistoricalReportInput {
    /** Default: no historical report admission was requested. This is not an empty finding set. */
    data object NotLoaded : HistoricalReportInput
    data class Unavailable(val reason: HistoricalReportProblem) : HistoricalReportInput
    class Available internal constructor(val record: RecordedRuleReport) : HistoricalReportInput {
        override fun toString(): String = "HistoricalReportInput.Available"
    }
}

/**
 * A report admitted against this exact selected Build and the supplied ruleset record.
 * Acceptance proves the existing reference profile's structural/binding consistency only.
 * It does not prove correct rule execution, evidence authenticity, freshness or deployment readiness.
 */
class RecordedRuleReport private constructor(
    val build: ComparisonSnapshot,
    val report: FrozenEvaluationReport,
    private val rules: ObjectNode,
    private val document: ObjectNode,
) {
    /** Complete recorded values, not original whitespace, a content hash or an authenticity proof. */
    val rulesetJson: String = rules.toString()
    internal fun matchesBuild(selected: ComparisonSnapshot): Boolean =
        sameJsonValues(build.copyDocument(), selected.copyDocument())
    internal fun sameRules(other: RecordedRuleReport): Boolean = sameJsonValues(rules, other.rules)
    internal fun sameReport(other: RecordedRuleReport): Boolean = sameJsonValues(document, other.document)
    internal fun reportCopy(): ObjectNode = document.deepCopy()
    override fun toString(): String = "RecordedRuleReport"

    companion object {
        /**
         * An explicit attempted load. Null identifies only the input the caller did not supply.
         * Neither a missing input nor a stale identity/lock causes a lookup or recomputation.
         * Streams stay caller-owned, including on rejection. Use this synchronous boundary off the UI thread.
         */
        fun read(
            selectedBuild: ComparisonSnapshot,
            expectedReportId: String,
            ruleset: InputStream?,
            report: InputStream?,
        ): HistoricalReportInput {
            require(expectedReportId.isNotBlank())
            if (ruleset == null && report == null) return unavailable(HistoricalReportProblem.REPORT_AND_RULESET_NOT_SUPPLIED)
            if (report == null) return unavailable(HistoricalReportProblem.REPORT_NOT_SUPPLIED)
            if (ruleset == null) return unavailable(HistoricalReportProblem.RULESET_NOT_SUPPLIED)

            val reader = BoundedJsonReader()
            val rules = (reader.read(ruleset) as? JsonInputResult.Parsed)?.value
                ?: return unavailable(HistoricalReportProblem.INVALID_RULESET)
            if (unsupported(rules)) return unavailable(HistoricalReportProblem.UNSUPPORTED_RULESET_SCHEMA_VERSION)
            if (FrozenSchemaValidator().validate(FrozenDocument.RULESET, rules.toString().byteInputStream()) != FrozenValidation.Valid)
                return unavailable(HistoricalReportProblem.INVALID_RULESET)
            // Admit the complete ruleset before consuming the later report stream.
            val document = (reader.read(report) as? JsonInputResult.Parsed)?.value
                ?: return unavailable(HistoricalReportProblem.INVALID_REPORT)
            val reception = ReferenceReportAdapter().receive(
                selectedBuild.copyDocument().toString().byteInputStream(),
                rules.toString().byteInputStream(), document.toString().byteInputStream(),
            )
            if (reception is ReportReception.Rejected) return unavailable(when (reception.reason) {
                ReportRejection.INVALID_BUILD -> HistoricalReportProblem.INVALID_BUILD
                ReportRejection.INVALID_RULESET -> HistoricalReportProblem.INVALID_RULESET
                ReportRejection.INVALID_REPORT -> HistoricalReportProblem.INVALID_REPORT
                ReportRejection.UNSUPPORTED_BUILD_SCHEMA_VERSION -> HistoricalReportProblem.UNSUPPORTED_BUILD_SCHEMA_VERSION
                ReportRejection.UNSUPPORTED_RULESET_SCHEMA_VERSION -> HistoricalReportProblem.UNSUPPORTED_RULESET_SCHEMA_VERSION
                ReportRejection.UNSUPPORTED_REPORT_SCHEMA_VERSION -> HistoricalReportProblem.UNSUPPORTED_REPORT_SCHEMA_VERSION
                ReportRejection.BINDING_MISMATCH -> HistoricalReportProblem.BINDING_MISMATCH
                ReportRejection.INCONSISTENT_FINDINGS -> HistoricalReportProblem.INCONSISTENT_FINDINGS
            })
            if (document["id"].textValue() != expectedReportId)
                return unavailable(HistoricalReportProblem.REPORT_IDENTITY_MISMATCH)
            // The typed decoder preserves absent evidence versus an explicitly supplied empty array.
            val decoded = FrozenEvaluationReportDecoder().decode((reception as ReportReception.Accepted).json.byteInputStream())
                as? FrozenEvaluationReportDecoding.Decoded
                ?: return unavailable(HistoricalReportProblem.INVALID_REPORT)
            return HistoricalReportInput.Available(RecordedRuleReport(selectedBuild, decoded.report, rules, document))
        }

        private fun unsupported(node: ObjectNode): Boolean = node["schema_version"]?.let {
            it.isTextual && it.textValue() != "1.0.0"
        } == true
        private fun unavailable(reason: HistoricalReportProblem) = HistoricalReportInput.Unavailable(reason)
    }
}

enum class FindingsComparisonState { NOT_COMPARED, COMPLETE, FINDING_LIMIT_EXCEEDED }
enum class ReportRecordIdentityCondition { DISTINCT_IDENTITIES, SAME_IDENTITY_AND_RECORD, SAME_IDENTITY_WITH_DIFFERENT_RECORDS }
enum class FindingsComparisonGap {
    RULE_ID_JOIN_NOT_SEMANTIC_EQUIVALENCE,
    EVALUATION_CORRECTNESS_NOT_ESTABLISHED,
    EVIDENCE_AUTHENTICITY_NOT_ESTABLISHED,
    FRESHNESS_NOT_ESTABLISHED,
    COMPONENT_CAUSATION_NOT_ESTABLISHED,
    CATALOG_AND_PRICEBOOK_REVISION_PINS_NOT_ESTABLISHED,
}

/** JSON values remain complete; null means absent and "null" means a supplied JSON null. */
class RecordedReportFieldChange internal constructor(
    val field: String, val beforeJson: String?, val afterJson: String?,
) {
    override fun toString(): String = "RecordedReportFieldChange"
}

/** Stable display join on rule ID. Full records remain available even when a field did not change. */
class RecordedFindingComparison internal constructor(
    val ruleId: String,
    val before: FrozenReportFinding?,
    val after: FrozenReportFinding?,
    changes: List<RecordedReportFieldChange>,
) {
    val kind: ChangeKind? = when {
        before == null -> ChangeKind.ADDED
        after == null -> ChangeKind.REMOVED
        changes.isNotEmpty() -> ChangeKind.CHANGED
        else -> null
    }
    val changes: List<RecordedReportFieldChange> = immutableFindingsList(changes)
    override fun toString(): String = "RecordedFindingComparison"
}

class RecordedFindingsComparison internal constructor(
    val beforeBuild: ComparisonSnapshot,
    val afterBuild: ComparisonSnapshot,
    val beforeInput: HistoricalReportInput,
    val afterInput: HistoricalReportInput,
    val state: FindingsComparisonState,
    val buildIdentityCondition: ReportRecordIdentityCondition,
    val rulesetIdentityCondition: ReportRecordIdentityCondition?,
    val reportIdentityCondition: ReportRecordIdentityCondition?,
    val rulesetRecordsDiffer: Boolean?,
    metadataChanges: List<RecordedReportFieldChange>,
    findings: List<RecordedFindingComparison>,
) {
    val recordedLocksDiffer: Boolean = !beforeBuild.hasSameRecordedLocks(afterBuild)
    /** Complete metadata changes excluding findings, including declared result/readiness and counters. */
    val metadataChanges: List<RecordedReportFieldChange> = immutableFindingsList(metadataChanges)
    /** No partial rows on an unavailable input or a finding-limit failure. */
    val findings: List<RecordedFindingComparison> = immutableFindingsList(findings)
    val gaps: Set<FindingsComparisonGap> = Collections.unmodifiableSet(LinkedHashSet(FindingsComparisonGap.entries))
    override fun toString(): String = "RecordedFindingsComparison"
}

/**
 * Pure comparison of explicitly selected historical inputs. The default does not load reports.
 * It never evaluates rules, reads storage, fetches missing evidence, uses a clock or mutates a Build.
 * The existing BuildComparator and its NOT_LOADED cost/findings behavior are unchanged.
 */
object RecordedFindingsComparator {
    fun compare(
        before: ComparisonSnapshot,
        after: ComparisonSnapshot,
        beforeReport: HistoricalReportInput = HistoricalReportInput.NotLoaded,
        afterReport: HistoricalReportInput = HistoricalReportInput.NotLoaded,
        maxFindings: Int = 10_000,
    ): RecordedFindingsComparison {
        require(maxFindings in 1..10_000)
        fun boundInput(selected: ComparisonSnapshot, input: HistoricalReportInput): HistoricalReportInput =
            if (input is HistoricalReportInput.Available && !input.record.matchesBuild(selected))
                HistoricalReportInput.Unavailable(HistoricalReportProblem.BUILD_RECORD_MISMATCH) else input
        val leftInput = boundInput(before, beforeReport)
        val rightInput = boundInput(after, afterReport)
        val buildCondition = identityCondition(before.identity == after.identity,
            sameJsonValues(before.copyDocument(), after.copyDocument()))
        val left = (leftInput as? HistoricalReportInput.Available)?.record
        val right = (rightInput as? HistoricalReportInput.Available)?.record
        if (left == null || right == null) return RecordedFindingsComparison(before, after, leftInput, rightInput,
            FindingsComparisonState.NOT_COMPARED, buildCondition, null, null, null, emptyList(), emptyList())

        val sameRules = left.sameRules(right)
        val rulesCondition = identityCondition(
            left.report.rulesetId == right.report.rulesetId && left.report.rulesetVersion == right.report.rulesetVersion, sameRules)
        val reportCondition = identityCondition(left.report.id == right.report.id, left.sameReport(right))
        val a = left.reportCopy()
        val b = right.reportCopy()
        val oldFindings = a["findings"].associateBy { it["rule_id"].textValue() }
        val newFindings = b["findings"].associateBy { it["rule_id"].textValue() }
        // Admission requires one finding per unique ruleset ID; no repeated row can be silently lost.
        val ids = (oldFindings.keys + newFindings.keys).sorted()
        if (ids.size > maxFindings) return RecordedFindingsComparison(before, after, leftInput, rightInput,
            FindingsComparisonState.FINDING_LIMIT_EXCEEDED, buildCondition, rulesCondition, reportCondition,
            !sameRules, emptyList(), emptyList())

        val oldValues = left.report.findings.associateBy { it.ruleId }
        val newValues = right.report.findings.associateBy { it.ruleId }
        val findings = ids.map { id -> RecordedFindingComparison(id, oldValues[id], newValues[id],
            changedFields(oldFindings[id], newFindings[id])) }
        val metadata = changedFields(a, b).filter { it.field != "findings" }
        return RecordedFindingsComparison(before, after, leftInput, rightInput, FindingsComparisonState.COMPLETE,
            buildCondition, rulesCondition, reportCondition, !sameRules, metadata, findings)
    }

    private fun identityCondition(sameIdentity: Boolean, sameRecord: Boolean): ReportRecordIdentityCondition = when {
        !sameIdentity -> ReportRecordIdentityCondition.DISTINCT_IDENTITIES
        sameRecord -> ReportRecordIdentityCondition.SAME_IDENTITY_AND_RECORD
        else -> ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS
    }

    private fun changedFields(a: JsonNode?, b: JsonNode?): List<RecordedReportFieldChange> {
        val names = (a?.fieldNames()?.asSequence()?.toSet().orEmpty() + b?.fieldNames()?.asSequence()?.toSet().orEmpty()).sorted()
        return names.filter { !sameJsonValues(a?.get(it), b?.get(it)) }.map {
            RecordedReportFieldChange(it, a?.get(it)?.toString(), b?.get(it)?.toString())
        }
    }
}

private fun <T> immutableFindingsList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
