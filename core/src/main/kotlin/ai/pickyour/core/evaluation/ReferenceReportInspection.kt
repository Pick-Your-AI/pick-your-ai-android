package ai.pickyour.core.evaluation

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import java.math.BigInteger
import java.util.Collections

/** Copied presentation values from an already accepted reference report; no verdict reinterpretation. */
data class ReferenceEvidenceView(val path: String, val observedJson: String?, val expectedJson: String?, val sourceVersion: String?)
data class ReferenceFindingView(
    val ruleId: String, val outcome: String, val severity: String, val code: String,
    val message: String, val remediation: String?, val selectionIds: List<String>, val evidence: List<ReferenceEvidenceView>,
    val selectionContexts: List<ReferenceSelectionContext>,
)
data class ReferenceReportView(
    val id: String, val buildId: String, val buildRevision: BigInteger, val catalogSnapshotId: String,
    val rulesetId: String, val rulesetVersion: String, val dependencyGraphId: String,
    val dependencyGraphRevision: BigInteger, val evaluatedAt: String, val result: String,
    val deploymentReadiness: String, val findings: List<ReferenceFindingView>,
)

/** This view proves only the same binding/consistency acceptance as its receiver. */
fun ReportReception.Accepted.inspect(): ReferenceReportView {
    val root = (BoundedJsonReader().read(json.byteInputStream()) as JsonInputResult.Parsed).value
    val findings = root.path("findings").map { f ->
        val evidence = f.path("evidence").map { e -> ReferenceEvidenceView(
            e.path("path").asText(), e.get("observed")?.toString(), e.get("expected")?.toString(), e.get("source_version")?.asText(),
        ) }
        ReferenceFindingView(f.path("rule_id").asText(), f.path("outcome").asText(), f.path("severity").asText(),
            f.path("code").asText(), f.path("message").asText(), f.get("remediation")?.asText(),
            Collections.unmodifiableList(f.path("selection_ids").map { it.asText() }), Collections.unmodifiableList(evidence),
            Collections.unmodifiableList(f.path("selection_ids").map { selectionContext(it.asText()) }))
    }
    return ReferenceReportView(root.path("id").asText(), root.path("build_id").asText(), root.path("build_revision").bigIntegerValue(),
        root.path("catalog_snapshot_id").asText(), root.path("ruleset_id").asText(), root.path("ruleset_version").asText(),
        root.path("dependency_graph_id").asText(), root.path("dependency_graph_revision").bigIntegerValue(), root.path("evaluated_at").asText(),
        root.path("result").asText(), root.path("deployment_readiness").asText(), Collections.unmodifiableList(findings))
}
