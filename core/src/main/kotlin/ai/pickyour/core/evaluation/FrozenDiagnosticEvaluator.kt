package ai.pickyour.core.evaluation

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonInputLimits
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.math.BigDecimal
import java.util.Locale
import java.util.Collections

enum class DiagnosticRejection { INVALID_INPUT, BINDING_MISMATCH, WORK_LIMIT, UNSUPPORTED_OPERATION, INVALID_OUTPUT }
sealed interface DiagnosticEvaluation {
    data class Rejected(val reason: DiagnosticRejection) : DiagnosticEvaluation
    /** Frozen-reference diagnostic only, never a deployment authorization. */
    data class Evaluated(val report: ReportReception.Accepted) : DiagnosticEvaluation
}

/**
 * Bounded local interpreter for the frozen reference profile. Missing external cost/resource evidence
 * stays unknown. No metric overrides, network, UI mutation or production-compatibility claim.
 * All streams stay caller-owned; dispatch off the UI thread and bind delivery with AnalysisCoordinator.
 * Budget exhaustion rejects the entire attempt, never a partially evaluated report.
 */
class FrozenDiagnosticEvaluator(private val maxWork: Int = 250_000) {
    init { require(maxWork in 1..1_000_000) }
    private val nodes = JsonNodeFactory.instance
    private val reader = BoundedJsonReader(JsonInputLimits(maxBytes = 262_144, maxTokens = 20_000, maxStringLength = 4096))
    private val validator = FrozenSchemaValidator()

    fun evaluate(build: InputStream, catalog: InputStream, ruleset: InputStream): DiagnosticEvaluation = try {
        val b = parse(FrozenDocument.BUILD, build)
        val c = parse(FrozenDocument.CATALOG, catalog)
        val r = parse(FrozenDocument.RULESET, ruleset)
        val locks = b["locks"]
        if (!b["revision"].isIntegralNumber || !locks["dependency_graph_revision"].isIntegralNumber)
            fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
        if (c["id"] != locks["catalog_snapshot_id"] || r["id"] != locks["ruleset_id"] ||
            r["version"] != locks["ruleset_version"] || c["schema_version"] != b["schema_version"] ||
            r["schema_version"] != b["schema_version"])
            fail(DiagnosticRejection.BINDING_MISMATCH)
        if (c["items"].map { it["id"].asText() }.distinct().size != c["items"].size() ||
            r["rules"].map { it["id"].asText() }.distinct().size != r["rules"].size() ||
            b["selections"].map { it["id"].asText() }.distinct().size != b["selections"].size())
            fail(DiagnosticRejection.BINDING_MISMATCH)
        val catalogItems = c["items"].associateBy { it["id"].asText() }
        for (selection in b["selections"]) {
            if (!selection["enabled"].asBoolean()) continue
            val item = catalogItems[selection["catalog_item_id"].asText()] ?: continue
            if (item["version"] != selection["catalog_item_version"] || item["kind"] != selection["kind"])
                fail(DiagnosticRejection.BINDING_MISMATCH)
        }
        val output = Run(b, c, r).evaluate()
        when (val received = ReferenceReportAdapter().receive(b.toString().byteInputStream(), r.toString().byteInputStream(), output.toString().byteInputStream())) {
            is ReportReception.Accepted -> DiagnosticEvaluation.Evaluated(received)
            is ReportReception.Rejected -> DiagnosticEvaluation.Rejected(DiagnosticRejection.INVALID_OUTPUT)
        }
    } catch (failure: Rejection) { DiagnosticEvaluation.Rejected(failure.reason) }

    private fun parse(type: FrozenDocument, stream: InputStream): ObjectNode {
        val value = (reader.read(stream) as? JsonInputResult.Parsed)?.value ?: fail(DiagnosticRejection.INVALID_INPUT)
        if (validator.validate(type, value.toString().byteInputStream()) != FrozenValidation.Valid)
            fail(DiagnosticRejection.INVALID_INPUT)
        return value
    }

    /**
     * Private typed draft caller only. This computes the same rule loop without creating a Build or
     * admitting an EvaluationReport. Missing shared Build fields stay absent in the local context.
     * The caller binds the exact stored source and artifact pins before exposing these findings.
     */
    internal fun evaluateDraft(context: DraftDiagnosticContext, catalog: ByteArray, ruleset: ByteArray): DraftRuleEvaluation = try {
        val b = context.node()
        val c = parse(FrozenDocument.CATALOG, catalog.inputStream())
        val r = parse(FrozenDocument.RULESET, ruleset.inputStream())
        if (c["schema_version"] != r["schema_version"] ||
            c["items"].map { it["id"].asText() }.distinct().size != c["items"].size() ||
            r["rules"].map { it["id"].asText() }.distinct().size != r["rules"].size())
            fail(DiagnosticRejection.BINDING_MISMATCH)
        val items = c["items"].associateBy { it["id"].asText() }
        for (selection in b["selections"]) {
            if (!selection["enabled"].asBoolean()) continue
            val item = items[selection["catalog_item_id"].asText()] ?: continue
            if (item["version"] != selection["catalog_item_version"] || item["kind"] != selection["kind"])
                fail(DiagnosticRejection.BINDING_MISMATCH)
        }
        val findings = Run(b, c, r).evaluateFindings().findings.map { finding ->
            DraftRuleFinding(finding["rule_id"].asText(), finding["outcome"].asText(), finding["severity"].asText(),
                finding["code"].asText(), finding["message"].asText(), finding["remediation"].asText(),
                Collections.unmodifiableList(finding["selection_ids"].map { it.asText() }))
        }
        DraftRuleEvaluation.Evaluated(Collections.unmodifiableList(findings))
    } catch (failure: Rejection) { DraftRuleEvaluation.Rejected(failure.reason) }

    private class RuleResults(val findings: ArrayNode, val errors: Boolean, val unknowns: Boolean,
        val blocks: Boolean, val counts: Map<String, Int>)

    private inner class Run(val build: ObjectNode, catalog: ObjectNode, val ruleset: ObjectNode) {
        var remaining = maxWork.toLong()
        val active = build["selections"].filter { it["enabled"].asBoolean() }
        val items = catalog["items"].associateBy { it["id"].asText() }
        fun charge(amount: Long = 1) { remaining -= amount; if (remaining < 0) fail(DiagnosticRejection.WORK_LIMIT) }
        fun textEqual(a: String, b: String): Boolean { charge(a.length.toLong() + b.length); return a == b }
        fun numeric(value: JsonNode): BigDecimal? {
            if (value.isNumber || value.isTextual) charge(value.toString().length.toLong())
            return when {
                value.isNumber -> value.decimalValue()
                value.isTextual && Regex("^(0|[1-9][0-9]*)(\\.[0-9]{1,12})?$").matches(value.textValue()) -> value.textValue().toBigDecimal()
                else -> null
            }
        }
        // Python container equality is recursive, does not coerce decimal strings, and equates bool/int.
        fun equal(a: JsonNode, b: JsonNode): Boolean {
            charge()
            if ((a.isNumber || a.isBoolean) && (b.isNumber || b.isBoolean)) {
                val x = if (a.isBoolean) if (a.booleanValue()) BigDecimal.ONE else BigDecimal.ZERO else a.decimalValue()
                val y = if (b.isBoolean) if (b.booleanValue()) BigDecimal.ONE else BigDecimal.ZERO else b.decimalValue()
                charge(a.toString().length.toLong() + b.toString().length)
                return x.compareTo(y) == 0
            }
            if (a.isTextual && b.isTextual) return textEqual(a.textValue(), b.textValue())
            if (a.isArray && b.isArray) return a.size() == b.size() && (0 until a.size()).all { equal(a[it], b[it]) }
            if (a.isObject && b.isObject) return a.size() == b.size() && a.fields().asSequence().all { (key, value) ->
                charge(key.length.toLong()); b.has(key) && equal(value, b[key])
            }
            return a.isNull && b.isNull
        }
        fun selected(ref: JsonNode): JsonNode? {
            var result: JsonNode? = null
            for (selection in active) {
                charge()
                if (listOf("kind", "role", "selection_id").all { key ->
                    !ref.has(key) || textEqual(selection[if (key == "selection_id") "id" else key].asText(), ref[key].asText())
                }) { if (result != null) return null; result = selection }
            }
            return result
        }
        fun pointer(document: JsonNode, path: String): JsonNode? {
            charge(path.length.toLong())
            if (!path.startsWith('/')) return null
            var current = document
            for (segment in path.substring(1).split('/')) {
                charge()
                val key = segment.replace("~1", "/").replace("~0", "~")
                if (current.isArray && key.any { it.code > 127 }) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                current = if (current.isObject) current.get(key) ?: return null
                    else if (current.isArray && key.isNotEmpty() && key.all { it in '0'..'9' }) {
                        current.get(key.toIntOrNull() ?: return null) ?: return null
                    } else return null
            }
            return current
        }
        fun supported(value: JsonNode?): JsonNode? {
            if (value == null) return null
            charge()
            // Frozen Python loads raw fractional/exponent tokens through binary float. Keep exact
            // decimal strings supported, but reject raw floating operands even when nested.
            if (value.isFloatingPointNumber) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
            if (value.isContainerNode) for (child in value) supported(child)
            return value
        }
        fun value(operand: JsonNode): JsonNode? {
            charge()
            if (operand.has("literal")) return supported(operand["literal"])
            if (operand.has("metric")) return metric(operand["metric"].asText())
            val ref = operand["ref"]
            if (ref["scope"].asText() == "build") return supported(pointer(build, ref["pointer"].asText()))
            val selection = selected(ref) ?: return null
            if (ref["scope"].asText() == "selection") return supported(pointer(selection, ref["pointer"].asText()))
            val item = items[selection["catalog_item_id"].asText()] ?: return null
            if (item["version"] != selection["catalog_item_version"]) return null
            return supported(pointer(item, ref["pointer"].asText()))
        }
        fun metric(name: String): JsonNode? {
            if (name == "context.total_requested_tokens") {
                val ref = nodes.objectNode().put("kind", "context_policy").put("role", "primary")
                val s = selected(ref) ?: return null
                val a = s["settings"]["requested_context_tokens"] ?: return null
                val b = s["settings"]["reserved_output_tokens"] ?: return null
                if (!a.isIntegralNumber || !b.isIntegralNumber) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                return nodes.numberNode(a.decimalValue() + b.decimalValue())
            }
            if (name == "tools.mutating_count") {
                var count = 0
                for (s in active) {
                    charge()
                    if (s["kind"].asText() != "tool" || !s["settings"].path("enabled").asBoolean(true)) continue
                    val item = items[s["catalog_item_id"].asText()] ?: return null
                    val effect = item["spec"]["side_effect"] ?: return null
                    if (effect.asText() == "mutating") count++
                }
                return nodes.numberNode(count)
            }
            if (name == "compute.total_selected_vram_gb" || name == "compute.min_selected_device_vram_gb") {
                var amount: BigDecimal? = null
                for (s in active) {
                    charge()
                    if (s["kind"].asText() != "accelerator") continue
                    val vram = items[s["catalog_item_id"].asText()]?.get("spec")?.get("vram_gb") ?: return null
                    if (!vram.isNumber) return null
                    if (!vram.isIntegralNumber) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                    val n = vram.decimalValue()
                    if (n.precision() > 28) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                    amount = if (name == "compute.min_selected_device_vram_gb") amount?.min(n) ?: n else {
                        val count = s["settings"]["device_count"] ?: return null
                        if (!count.isIntegralNumber) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                        val product = n * count.decimalValue()
                        val sum = (amount ?: BigDecimal.ZERO) + product
                        if (product.precision() > 28 || sum.precision() > 28) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                        sum
                    }
                }
                return amount?.let { nodes.numberNode(it) }
            }
            return null // External estimates are deliberately not accepted by this diagnostic profile.
        }
        fun contains(container: JsonNode, item: JsonNode): Boolean? {
            if (container.isArray) return container.any { charge(); equal(it, item) }
            if (container.isTextual) {
                if (!item.isTextual) fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
                val a = container.textValue(); val b = item.textValue()
                // Charge worst-case substring work before the library scan, including empty needles.
                charge((a.length.toLong() + 1) * (b.length.toLong() + 1))
                return a.contains(b)
            }
            return null
        }
        fun compare(op: String, left: JsonNode?, right: JsonNode?): Boolean? {
            charge()
            // Validate supplied unsupported operands even when the opposite side is missing.
            if (op == "intersects" || op == "subset_of") {
                for (side in listOfNotNull(left, right)) if (side.isArray) {
                    for (element in side) { charge(); if (element.isContainerNode) fail(DiagnosticRejection.UNSUPPORTED_OPERATION) }
                }
            }
            val container = if (op == "contains") left else if (op == "in") right else null
            val item = if (op == "contains") right else if (op == "in") left else null
            if (container?.isTextual == true && item != null && !item.isTextual)
                fail(DiagnosticRejection.UNSUPPORTED_OPERATION)
            if (left == null || right == null) return null
            if (op in listOf("eq", "ne", "lt", "lte", "gt", "gte")) {
                val a = numeric(left); val b = numeric(right)
                if (op == "eq" || op == "ne") {
                    val same = if (a != null && b != null) a.compareTo(b) == 0 else equal(left, right)
                    return if (op == "eq") same else !same
                }
                if (a == null || b == null) return null
                return when (op) { "lt" -> a < b; "lte" -> a <= b; "gt" -> a > b; else -> a >= b }
            }
            if (op == "contains") return contains(left, right)
            if (op == "in") return contains(right, left)
            if (!left.isArray || !right.isArray) return null
            // The Python reference raises for unhashable set elements; do not invent a verdict.
            for (v in left.toList() + right.toList()) { charge(); if (v.isContainerNode) fail(DiagnosticRejection.UNSUPPORTED_OPERATION) }
            return if (op == "intersects") left.any { contains(right, it) == true }
                else left.all { contains(right, it) == true }
        }
        fun expression(e: JsonNode): Boolean? {
            charge()
            return when (val op = e["op"].asText()) {
                "exists" -> value(e["value"])?.let { !it.isNull } ?: false
                "not" -> expression(e["arg"])?.not()
                "all", "any" -> {
                    val values = e["args"].map { expression(it) }
                    if (op == "all") if (false in values) false else if (null in values) null else true
                    else if (true in values) true else if (null in values) null else false
                }
                "implies" -> {
                    val a = expression(e["if"]); val b = expression(e["then"])
                    if (a == false || b == true) true else if (a == null || b == null) null else false
                }
                else -> compare(op, value(e["left"]), value(e["right"]))
            }
        }
        fun evaluate(): ObjectNode {
            val output = nodes.objectNode()
            for (key in listOf("schema_version")) output.set<JsonNode>(key, build[key])
            output.put("id", "evaluation.${build["id"].asText()}.r${build["revision"].bigIntegerValue()}")
            output.set<JsonNode>("build_id", build["id"]); output.set<JsonNode>("build_revision", build["revision"])
            for (key in listOf("catalog_snapshot_id", "ruleset_id", "ruleset_version", "dependency_graph_id", "dependency_graph_revision"))
                output.set<JsonNode>(key, build["locks"][key])
            output.put("evaluated_at", "2026-10-16T09:00:00Z") // Pinned reference fixture timestamp, not a wall clock receipt.
            val result = evaluateFindings()
            output.set<JsonNode>("findings", result.findings)
            output.put("result", if (result.errors) "invalid" else if (result.unknowns) "indeterminate" else "valid")
            output.put("deployment_readiness", if (result.errors || result.blocks ||
                (result.unknowns && build.path("policy").path("fail_on_unknown").asBoolean(false))) "blocked" else "needs_review")
            val summary = output.putObject("summary")
            for ((key, value) in mapOf("passed" to "pass", "failed" to "fail", "unknown" to "unknown", "skipped" to "skipped"))
                summary.put(key, result.counts.getValue(value))
            return output
        }

        fun evaluateFindings(): RuleResults {
            val findings = nodes.arrayNode()
            var errors = false; var unknowns = false; var blocks = false
            val counts = mutableMapOf("pass" to 0, "fail" to 0, "unknown" to 0, "skipped" to 0)
            for (rule in ruleset["rules"]) {
                charge()
                val applicable = expression(rule["applies_when"])
                val outcome = if (applicable == false) "skipped" else if (applicable == null) "unknown"
                    else when (expression(rule["assert"])) { true -> "pass"; false -> "fail"; null -> "unknown" }
                counts[outcome] = counts.getValue(outcome) + 1
                errors = errors || (outcome == "fail" && rule["severity"].asText() == "error")
                unknowns = unknowns || outcome == "unknown"
                blocks = blocks || (outcome == "unknown" && rule["on_unknown"].asText() == "block")
                val finding = findings.addObject()
                val id = rule["id"].asText()
                finding.put("rule_id", id).put("outcome", outcome).put("severity", rule["severity"].asText())
                    .put("code", id.replace('.', '_').uppercase(Locale.ROOT))
                    .put("message", if (outcome in listOf("fail", "unknown")) rule["message"].asText() else "$id: $outcome")
                    .put("remediation", if (outcome in listOf("fail", "unknown")) rule["remediation"].asText() else "")
                val ids = sortedSetOf<String>()
                for (s in active) { charge(); if (rule["affected_kinds"].any { charge(); it == s["kind"] }) { val id = s["id"].asText(); charge(id.length.toLong() * 32); ids.add(id) } }
                val selections = finding.putArray("selection_ids"); ids.forEach { selections.add(it) }
            }
            return RuleResults(findings, errors, unknowns, blocks, counts)
        }
    }
    private class Rejection(val reason: DiagnosticRejection) : RuntimeException()
    private fun fail(reason: DiagnosticRejection): Nothing = throw Rejection(reason)
}
