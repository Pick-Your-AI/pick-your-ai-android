package ai.pickyour.core.evaluation

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream

enum class ReportRejection { INVALID_BUILD, INVALID_RULESET, INVALID_REPORT, BINDING_MISMATCH, INCONSISTENT_FINDINGS }

sealed interface ReportReception {
    data class Rejected(val reason: ReportRejection) : ReportReception
    /** Immutable JSON snapshot; acceptance proves structural/binding consistency, not correct evaluation. */
    class Accepted internal constructor(val json: String) : ReportReception
}

/**
 * Receiving boundary for the frozen Python reference profile. It does not run rules, attest a server,
 * authenticate evidence, or grant deployment readiness. In particular this reference never emits ready.
 * Streams remain caller-owned. Perform IO off the UI thread and use AnalysisCoordinator tickets to
 * reject callbacks after edits/retries; this synchronous adapter cannot establish current UI identity.
 */
class ReferenceReportAdapter {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun receive(build: InputStream, ruleset: InputStream, report: InputStream): ReportReception {
        val b = parse(FrozenDocument.BUILD, build) ?: return reject(ReportRejection.INVALID_BUILD)
        val rules = parse(FrozenDocument.RULESET, ruleset) ?: return reject(ReportRejection.INVALID_RULESET)
        val r = parse(FrozenDocument.EVALUATION_REPORT, report) ?: return reject(ReportRejection.INVALID_REPORT)
        val locks = b["locks"]
        if (r["build_id"] != b["id"] || r["build_revision"].bigIntegerValue() != b["revision"].bigIntegerValue() ||
            r["schema_version"] != b["schema_version"] ||
            listOf("catalog_snapshot_id", "ruleset_id", "ruleset_version", "dependency_graph_id")
                .any { r[it] != locks[it] } ||
            r["dependency_graph_revision"].bigIntegerValue() != locks["dependency_graph_revision"].bigIntegerValue() ||
            rules["id"] != locks["ruleset_id"] || rules["version"] != locks["ruleset_version"]
        ) return reject(ReportRejection.BINDING_MISMATCH)

        val expectedRules = rules["rules"].toList()
        val findings = r["findings"].toList()
        // The reference emits every rule once, in ruleset order (including skipped rules).
        if (expectedRules.map { it["id"].asText() }.distinct().size != expectedRules.size ||
            findings.size != expectedRules.size
        ) return reject(ReportRejection.INCONSISTENT_FINDINGS)
        val active = b["selections"].filter { it["enabled"].asBoolean() }
        for ((finding, rule) in findings.zip(expectedRules)) {
            val kinds = rule["affected_kinds"].map { it.asText() }.toSet()
            val ids = active.filter { it["kind"].asText() in kinds }.map { it["id"].asText() }.toSortedSet()
            if (finding["rule_id"] != rule["id"] || finding["severity"] != rule["severity"] ||
                finding["selection_ids"].map { it.asText() } != ids.toList()
            ) return reject(ReportRejection.INCONSISTENT_FINDINGS)
        }
        for ((key, outcome) in mapOf("passed" to "pass", "failed" to "fail", "unknown" to "unknown", "skipped" to "skipped")) {
            if (r["summary"][key].bigIntegerValue() != findings.count { it["outcome"].asText() == outcome }.toBigInteger())
                return reject(ReportRejection.INCONSISTENT_FINDINGS)
        }
        val errors = findings.any { it["outcome"].asText() == "fail" && it["severity"].asText() == "error" }
        val unknowns = findings.any { it["outcome"].asText() == "unknown" }
        val unknownBlocks = findings.zip(expectedRules).any { (f, rule) ->
            f["outcome"].asText() == "unknown" && rule["on_unknown"].asText() == "block"
        }
        val result = if (errors) "invalid" else if (unknowns) "indeterminate" else "valid"
        val readiness = if (errors || unknownBlocks || (unknowns && b.path("policy").path("fail_on_unknown").asBoolean(false)))
            "blocked" else "needs_review"
        if (r["result"].asText() != result || r["deployment_readiness"].asText() != readiness)
            return reject(ReportRejection.INCONSISTENT_FINDINGS)
        return ReportReception.Accepted(r.toString())
    }

    private fun parse(type: FrozenDocument, stream: InputStream): JsonNode? {
        val parsed = reader.read(stream) as? JsonInputResult.Parsed ?: return null
        // Revalidate bounded canonical bytes rather than expose the validator's mutable tree API.
        return if (validator.validate(type, parsed.value.toString().byteInputStream()) == FrozenValidation.Valid)
            parsed.value else null
    }

    private fun reject(reason: ReportRejection) = ReportReception.Rejected(reason)
}
