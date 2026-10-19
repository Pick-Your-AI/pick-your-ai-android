package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import ai.pickyour.core.evaluation.ReferenceFindingView
import ai.pickyour.core.evaluation.ProvisionalFindingGuidance
import ai.pickyour.core.evaluation.ReferenceReportView
import ai.pickyour.core.preview.PreviewComparedValue
import ai.pickyour.core.preview.PreviewComparedValueKind

/** Bounded presentation of copied reference values; no verdict, evidence or navigation inference. */
internal object FindingsInspectionText {
    private const val MAX_NESTED_ENTRIES = 8

    fun title(context: Context, finding: ReferenceFindingView): String = literal(context, finding.ruleId)

    fun row(context: Context, finding: ReferenceFindingView): String = context.getString(
        R.string.findings_row, title(context, finding), finding.outcome, finding.severity)

    fun guidance(context: Context, finding: ReferenceFindingView): String? = when (finding.provisionalGuidance) {
        ProvisionalFindingGuidance.INCOMPLETE -> context.getString(R.string.findings_guidance_incomplete)
        ProvisionalFindingGuidance.REPORTED_INCOMPATIBILITY -> context.getString(R.string.findings_guidance_incompatible)
        ProvisionalFindingGuidance.UNCLASSIFIED -> null
    }

    fun describe(context: Context, report: ReferenceReportView): String = listOf(
        context.getString(R.string.findings_notice),
        context.getString(R.string.findings_display_policy),
        context.getString(R.string.findings_identity, literal(context, report.id), literal(context, report.buildId),
            literal(context, report.buildRevision.toString()), literal(context, report.evaluatedAt)),
        context.getString(R.string.findings_locks, literal(context, report.catalogSnapshotId),
            literal(context, report.rulesetId), literal(context, report.rulesetVersion),
            literal(context, report.dependencyGraphId), literal(context, report.dependencyGraphRevision.toString())),
        context.getString(R.string.findings_status, report.result, report.deploymentReadiness),
        context.getString(R.string.findings_outcome_counts, report.findings.count { it.outcome == "pass" },
            report.findings.count { it.outcome == "fail" }, report.findings.count { it.outcome == "unknown" },
            report.findings.count { it.outcome == "skipped" }),
        context.getString(R.string.findings_limits),
    ).joinToString("\n\n")

    fun describe(context: Context, finding: ReferenceFindingView): String {
        val selected = finding.selectionIds.take(MAX_NESTED_ENTRIES)
        val selections = if (selected.isEmpty()) context.getString(R.string.findings_no_selections) else
            context.resources.getQuantityString(R.plurals.findings_display_selections, finding.selectionIds.size,
                selected.size, finding.selectionIds.size, selected.joinToString("\n") { literal(context, it) })
        val shownEvidence = finding.evidence.take(MAX_NESTED_ENTRIES)
        val evidence = if (shownEvidence.isEmpty()) context.getString(R.string.findings_no_evidence) else {
            val entries = shownEvidence.map { context.getString(R.string.findings_evidence,
                literal(context, it.path), literal(context, it.observedJson), literal(context, it.expectedJson),
                literal(context, it.sourceVersion)) }
            context.resources.getQuantityString(R.plurals.findings_display_evidence, finding.evidence.size,
                shownEvidence.size, finding.evidence.size) + "\n\n" + entries.joinToString("\n\n")
        }
        return listOfNotNull(row(context, finding), guidance(context, finding), context.getString(R.string.findings_display_policy),
            context.getString(R.string.findings_reason, literal(context, finding.code), literal(context, finding.message)),
            context.getString(R.string.findings_remediation, literal(context, finding.remediation)),
            context.getString(R.string.findings_selections, selections), evidence,
            context.getString(R.string.findings_limits)).joinToString("\n\n")
    }

    private fun literal(context: Context, raw: String?): String {
        val value = PreviewComparedValue.from(raw)
        val escaped = when (value.kind) {
            PreviewComparedValueKind.ABSENT -> return context.getString(R.string.findings_not_supplied)
            PreviewComparedValueKind.EMPTY -> "\"\""
            PreviewComparedValueKind.TEXT -> requireNotNull(value.quotedLiteral)
        }
        val bounded = if (value.truncated) context.getString(R.string.findings_display_truncated, escaped) else escaped
        return BidiFormatter.getInstance(context.resources.configuration.locales[0])
            .unicodeWrap(bounded, TextDirectionHeuristics.LTR)
    }
}
