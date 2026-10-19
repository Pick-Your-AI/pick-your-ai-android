package ai.pickyour.core.comparison

import ai.pickyour.core.contracts.FrozenEvaluationReportDecoder
import ai.pickyour.core.contracts.FrozenEvaluationReportDecoding
import ai.pickyour.core.contracts.FrozenFindingOutcome
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger

class RecordedFindingsComparisonTest {
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    private fun fixture(name: String): ObjectNode = mapper.readTree(
        javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")) as ObjectNode
    private class Documents(val build: ObjectNode, val rules: ObjectNode, val report: ObjectNode)
    private fun documents(count: Int = 3): Documents {
        val d = Documents(fixture("build"), fixture("ruleset"), fixture("evaluation-report"))
        val rules = d.rules["rules"].take(count)
        val findings = d.report["findings"].take(count)
        d.rules.putArray("rules").addAll(rules)
        d.report.putArray("findings").addAll(findings)
        consistentSummary(d)
        return d
    }
    private fun snapshot(build: ObjectNode): ComparisonSnapshot = (ComparisonSnapshot.read(build.toString().byteInputStream(),
        SnapshotIdentity(build["id"].textValue(), build["revision"].bigIntegerValue())) as SnapshotRead.Available).snapshot
    private fun load(d: Documents, selected: ComparisonSnapshot = snapshot(d.build), expectedId: String = d.report["id"].textValue()): HistoricalReportInput =
        RecordedRuleReport.read(selected, expectedId, d.rules.toString().byteInputStream(), d.report.toString().byteInputStream())
    private fun admitted(d: Documents): HistoricalReportInput.Available =
        assertInstanceOf(HistoricalReportInput.Available::class.java, load(d))
    private fun compare(a: Documents, b: Documents, limit: Int = 10_000): RecordedFindingsComparison =
        RecordedFindingsComparator.compare(snapshot(a.build), snapshot(b.build), admitted(a), admitted(b), limit)
    private fun finding(d: Documents, index: Int = 0) = d.report["findings"][index] as ObjectNode
    private fun rule(d: Documents, index: Int = 0) = d.rules["rules"][index] as ObjectNode
    private fun consistentSummary(d: Documents) {
        val findings = d.report["findings"].toList()
        val summary = d.report.putObject("summary")
        listOf("passed" to "pass", "failed" to "fail", "unknown" to "unknown", "skipped" to "skipped").forEach { (key, value) ->
            summary.put(key, findings.count { it["outcome"].asText() == value })
        }
        val errors = findings.any { it["outcome"].asText() == "fail" && it["severity"].asText() == "error" }
        val unknowns = findings.any { it["outcome"].asText() == "unknown" }
        val unknownBlocks = findings.zip(d.rules["rules"].toList()).any { (f, r) ->
            f["outcome"].asText() == "unknown" && r["on_unknown"].asText() == "block"
        }
        d.report.put("result", if (errors) "invalid" else if (unknowns) "indeterminate" else "valid")
        d.report.put("deployment_readiness", if (errors || unknownBlocks ||
            (unknowns && d.build.path("policy").path("fail_on_unknown").asBoolean(false))) "blocked" else "needs_review")
    }
    private fun rejection(reason: HistoricalReportProblem, result: HistoricalReportInput) {
        assertEquals(HistoricalReportInput.Unavailable(reason), result)
        assertFalse(result.toString().contains("private.fixture"))
    }
    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var reads = 0
        override fun read(): Int { reads++; return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return super.read(b, off, len) }
        override fun close() { closed = true }
    }

    @Test fun `default comparison keeps reports not loaded and never claims an empty evaluation`() {
        val a = snapshot(fixture("build"))
        val result = RecordedFindingsComparator.compare(a, a)
        assertEquals(FindingsComparisonState.NOT_COMPARED, result.state)
        assertSame(HistoricalReportInput.NotLoaded, result.beforeInput)
        assertSame(HistoricalReportInput.NotLoaded, result.afterInput)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.metadataChanges.isEmpty())
        assertNull(result.reportIdentityCondition)
        assertNull(result.rulesetRecordsDiffer)
        val old = BuildComparator.compare(a, a)
        assertEquals(HistoricalOutputState.NOT_LOADED, old.historicalFindings)
        assertEquals(HistoricalOutputState.NOT_LOADED, old.historicalCost)
    }

    @Test fun `explicit absent inputs remain unavailable without consuming the other input`() {
        val d = documents()
        val build = snapshot(d.build)
        val rules = TrackedInput(d.rules.toString().toByteArray())
        val report = TrackedInput(d.report.toString().toByteArray())
        rejection(HistoricalReportProblem.REPORT_NOT_SUPPLIED, RecordedRuleReport.read(build, "report.fixture", rules, null))
        rejection(HistoricalReportProblem.RULESET_NOT_SUPPLIED, RecordedRuleReport.read(build, "report.fixture", null, report))
        val missing = RecordedRuleReport.read(build, "report.fixture", null, null)
        rejection(HistoricalReportProblem.REPORT_AND_RULESET_NOT_SUPPLIED, missing)
        assertEquals(0, rules.reads)
        assertEquals(0, report.reads)
        assertFalse(rules.closed || report.closed)
        val comparison = RecordedFindingsComparator.compare(build, build, admitted(d), missing)
        assertEquals(FindingsComparisonState.NOT_COMPARED, comparison.state)
        assertSame(missing, comparison.afterInput)
        assertTrue(comparison.findings.isEmpty())
    }

    @Test fun `full frozen golden is admitted and preserves all forty five recorded findings`() {
        val d = Documents(fixture("build"), fixture("ruleset"), fixture("evaluation-report"))
        val value = admitted(d).record
        assertEquals(45, value.report.findings.size)
        assertEquals(d.report, mapper.readTree(value.report.json))
        assertEquals(d.rules, mapper.readTree(value.rulesetJson))
        assertEquals("prices.fixture.1", value.build.locks["pricebook_id"])
        assertEquals("1", value.build.locks["dependency_graph_revision"])
        assertEquals("needs_review", value.report.deploymentReadiness.encoded)
        assertNull(value.report.findings.first().evidence)
    }

    @Test fun `expected report identity is required independently of valid Build binding`() {
        val d = documents()
        rejection(HistoricalReportProblem.REPORT_IDENTITY_MISMATCH, load(d, expectedId = "other.private.fixture"))
        assertThrows(IllegalArgumentException::class.java) { load(d, expectedId = " ") }
        assertTrue(load(d) is HistoricalReportInput.Available)
    }

    @Test fun `stale revision or recorded locks reject without latest data substitution`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("build_revision", 2) }, { it.put("build_id", "build.other.fixture") },
            { it.put("catalog_snapshot_id", "catalog.other.fixture") }, { it.put("ruleset_id", "rules.other.fixture") },
            { it.put("ruleset_version", "2.0.0") }, { it.put("dependency_graph_id", "graph.other.fixture") },
            { it.put("dependency_graph_revision", BigInteger("9007199254740993")) },
        )
        for (mutate in mutations) {
            val d = documents().also { mutate(it.report) }
            assertTrue(FrozenEvaluationReportDecoder().decode(d.report.toString().byteInputStream()) is FrozenEvaluationReportDecoding.Decoded)
            rejection(HistoricalReportProblem.BINDING_MISMATCH, load(d))
        }
    }

    @Test fun `a report admitted to one full Build cannot be relabeled onto equal IDs and locks`() {
        val d = documents()
        val original = snapshot(d.build)
        val input = admitted(d)
        val changed = d.build.deepCopy().also { ((it["selections"][0] as ObjectNode)["settings"] as ObjectNode).put("concurrency", 9) }
        val selected = snapshot(changed)
        assertEquals(original.identity, selected.identity)
        assertEquals(original.locks, selected.locks)
        val result = RecordedFindingsComparator.compare(original, selected, input, input)
        rejection(HistoricalReportProblem.BUILD_RECORD_MISMATCH, result.afterInput)
        assertSame(input, result.beforeInput)
        assertEquals(FindingsComparisonState.NOT_COMPARED, result.state)
        assertTrue(result.findings.isEmpty())
        assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS, result.buildIdentityCondition)
    }

    @Test fun `separately admitted Build changes are disclosed without inventing finding changes`() {
        val a = documents()
        val b = documents()
        (b.build["selections"][0] as ObjectNode).put("role", "secondary")
        val result = compare(a, b)
        assertEquals(FindingsComparisonState.COMPLETE, result.state)
        assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS, result.buildIdentityCondition)
        assertTrue(result.findings.all { it.kind == null && it.changes.isEmpty() })
        assertFalse(result.recordedLocksDiffer)
        assertTrue(result.gaps.contains(FindingsComparisonGap.COMPONENT_CAUSATION_NOT_ESTABLISHED))
    }

    @Test fun `all admitted streams remain caller owned and mutable source trees cannot alter records`() {
        val d = documents()
        val build = snapshot(d.build)
        val rules = TrackedInput(d.rules.toString().toByteArray())
        val report = TrackedInput(d.report.toString().toByteArray())
        val input = RecordedRuleReport.read(build, d.report["id"].asText(), rules, report) as HistoricalReportInput.Available
        val rulesBefore = input.record.rulesetJson
        val reportBefore = input.record.report.json
        d.build.put("name", "mutated.private.fixture")
        d.rules.put("version", "9.0.0")
        finding(d).put("message", "mutated.private.fixture")
        input.record.reportCopy().put("result", "invalid")
        input.record.build.copyDocument().put("name", "mutated.private.fixture")
        assertEquals(rulesBefore, input.record.rulesetJson)
        assertEquals(reportBefore, input.record.report.json)
        assertFalse(rules.closed || report.closed)
        assertTrue(rules.reads > 0 && report.reads > 0)
        assertEquals(0, rules.available())
        assertEquals(0, report.available())
    }

    @Test fun `unsupported ruleset is distinguished only after complete strict parsing`() {
        val d = documents()
        for (version in listOf("2.0.0", "", "1.0.0 ")) {
            val report = TrackedInput(d.report.toString().toByteArray())
            val rules = TrackedInput(d.rules.deepCopy().put("schema_version", version).toString().toByteArray())
            rejection(HistoricalReportProblem.UNSUPPORTED_RULESET_SCHEMA_VERSION,
                RecordedRuleReport.read(snapshot(d.build), d.report["id"].asText(), rules, report))
            assertEquals(0, report.reads)
            assertFalse(rules.closed || report.closed)
        }
        for (bytes in listOf("{\"schema_version\":\"2.0.0\",\"schema_version\":\"1.0.0\"}".toByteArray(),
            "{\"schema_version\":\"2.0.0\"} {}".toByteArray(), "{\"schema_version\":\"2.0.0\"".toByteArray(),
            "{\"schema_version\":\"2.0.0\"}".toByteArray() + byteArrayOf(0xc3.toByte()))) {
            val report = TrackedInput(d.report.toString().toByteArray())
            rejection(HistoricalReportProblem.INVALID_RULESET,
                RecordedRuleReport.read(snapshot(d.build), d.report["id"].asText(), bytes.inputStream(), report))
            assertEquals(0, report.reads)
        }
    }

    @Test fun `unsupported report remains separate from malformed or unsupported ruleset inputs`() {
        val d = documents()
        rejection(HistoricalReportProblem.UNSUPPORTED_REPORT_SCHEMA_VERSION,
            load(Documents(d.build, d.rules, d.report.deepCopy().put("schema_version", "2.0.0"))))
        val malformed = d.report.deepCopy().put("schema_version", "2.0.0").toString() + " private.fixture"
        rejection(HistoricalReportProblem.INVALID_REPORT, RecordedRuleReport.read(snapshot(d.build), d.report["id"].asText(),
            d.rules.toString().byteInputStream(), malformed.byteInputStream()))
        d.rules.put("schema_version", "2.0.0")
        val neverRead = TrackedInput(malformed.toByteArray())
        rejection(HistoricalReportProblem.UNSUPPORTED_RULESET_SCHEMA_VERSION, RecordedRuleReport.read(snapshot(d.build),
            d.report["id"].asText(), d.rules.toString().byteInputStream(), neverRead))
        assertEquals(0, neverRead.reads)
    }

    @Test fun `supported records still require the unchanged fixed schemas`() {
        for (field in listOf("schema_version", "id")) {
            val d = documents().also { it.rules.remove(field) }
            rejection(HistoricalReportProblem.INVALID_RULESET, load(d))
        }
        val rulesExtra = documents().also { it.rules.put("private.fixture.extra", true) }
        rejection(HistoricalReportProblem.INVALID_RULESET, load(rulesExtra))
        val reportExtra = documents().also { it.report.put("private.fixture.extra", true) }
        rejection(HistoricalReportProblem.INVALID_REPORT, load(reportExtra))
        val wrongEvidence = documents().also { finding(it).put("evidence", "private.fixture") }
        rejection(HistoricalReportProblem.INVALID_REPORT, load(wrongEvidence))
    }

    @Test fun `size bounds and provider failures reject without private error text or closing streams`() {
        val d = documents()
        val tooLong = d.rules.deepCopy().also { (it["rules"][0] as ObjectNode).put("message", "x".repeat(16_385)) }
        rejection(HistoricalReportProblem.INVALID_RULESET, load(Documents(d.build, tooLong, d.report)))
        val tooLarge = d.report.toString() + " ".repeat(1_048_577)
        rejection(HistoricalReportProblem.INVALID_REPORT, RecordedRuleReport.read(snapshot(d.build), d.report["id"].asText(),
            d.rules.toString().byteInputStream(), tooLarge.byteInputStream()))
        var closed = false
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("private.fixture.provider")
            override fun close() { closed = true }
        }
        rejection(HistoricalReportProblem.INVALID_REPORT, RecordedRuleReport.read(snapshot(d.build), d.report["id"].asText(),
            d.rules.toString().byteInputStream(), failing))
        assertFalse(closed)
    }

    @Test fun `duplicate missing reordered or wrong selection findings fail reference admission`() {
        val mutations: List<(Documents) -> Unit> = listOf(
            { (it.report["findings"] as ArrayNode).add(finding(it).deepCopy()) },
            { (it.report["findings"] as ArrayNode).remove(0) },
            { val rows = it.report["findings"].toList().reversed(); it.report.putArray("findings").addAll(rows) },
            { finding(it).putArray("selection_ids").add("sel_other_fixture") },
            { (it.report["summary"] as ObjectNode).put("passed", 999) },
            { rule(it, 1).put("id", rule(it)["id"].asText()) },
        )
        for (mutate in mutations) rejection(HistoricalReportProblem.INCONSISTENT_FINDINGS, load(documents().also(mutate)))
    }

    @Test fun `schema valid ready and contradictory results cannot become admitted comparison inputs`() {
        for ((field, value) in listOf("deployment_readiness" to "ready", "result" to "invalid")) {
            val d = documents().also { it.report.put(field, value) }
            assertTrue(FrozenEvaluationReportDecoder().decode(d.report.toString().byteInputStream()) is FrozenEvaluationReportDecoding.Decoded)
            rejection(HistoricalReportProblem.INCONSISTENT_FINDINGS, load(d))
        }
    }

    @Test fun `recorded outcome transitions expose counters and declarations without a new verdict`() {
        for (outcome in listOf("fail", "unknown", "skipped")) {
            val a = documents()
            val b = documents().also { finding(it).put("outcome", outcome); consistentSummary(it) }
            val result = compare(a, b)
            val row = result.findings.single { it.ruleId == finding(a)["rule_id"].asText() }
            assertEquals(ChangeKind.CHANGED, row.kind)
            assertEquals(listOf("outcome"), row.changes.map { it.field })
            assertEquals("\"pass\"", row.changes.single().beforeJson)
            assertEquals("\"$outcome\"", row.changes.single().afterJson)
            assertEquals(FrozenFindingOutcome.PASS, row.before!!.outcome)
            assertTrue(result.metadataChanges.any { it.field == "summary" })
            assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS, result.reportIdentityCondition)
            assertTrue(result.gaps.contains(FindingsComparisonGap.EVALUATION_CORRECTNESS_NOT_ESTABLISHED))
        }
    }

    @Test fun `severity code message remediation and literal control text remain complete changes`() {
        val a = documents()
        val b = documents()
        val text = "private.fixture \u202e\u2066\n" + "x".repeat(12_000)
        finding(b).put("severity", "warning").put("code", "CHANGED_FIXTURE").put("message", text).remove("remediation")
        rule(b).put("severity", "warning")
        val result = compare(a, b)
        val row = result.findings.single { it.ruleId == finding(a)["rule_id"].asText() }
        assertEquals(listOf("code", "message", "remediation", "severity"), row.changes.map { it.field })
        assertEquals(text, row.after!!.message)
        val missing = row.changes.single { it.field == "remediation" }
        assertEquals("\"\"", missing.beforeJson)
        assertNull(missing.afterJson)
        assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS, result.rulesetIdentityCondition)
        assertEquals(true, result.rulesetRecordsDiffer)
    }

    @Test fun `absent evidence and supplied empty evidence remain different with both full inputs`() {
        val a = documents()
        val b = documents().also { finding(it).putArray("evidence") }
        val row = compare(a, b).findings.single { it.ruleId == finding(a)["rule_id"].asText() }
        assertNull(row.before!!.evidence)
        assertEquals(emptyList<Any>(), row.after!!.evidence)
        assertEquals(listOf("evidence"), row.changes.map { it.field })
        assertNull(row.changes.single().beforeJson)
        assertEquals("[]", row.changes.single().afterJson)
    }

    @Test fun `evidence absent null string and typed values are never collapsed`() {
        val a = documents()
        finding(a).putArray("evidence").addObject().put("path", "https://private.fixture/inert")
        for (value in listOf("null", "\"null\"", "false", "0", "[]", "{}")) {
            val b = documents()
            finding(b).putArray("evidence").addObject().put("path", "https://private.fixture/inert")
                .set<JsonNode>("observed", mapper.readTree(value))
            val row = compare(a, b).findings.single { it.ruleId == finding(a)["rule_id"].asText() }
            assertNull(row.before!!.evidence!!.single().observed)
            assertEquals(mapper.readTree(value), mapper.readTree(row.after!!.evidence!!.single().observed!!.json))
            assertEquals(listOf("evidence"), row.changes.map { it.field })
        }
    }

    @Test fun `evidence duplicate paths order and empty source version are preserved`() {
        fun setEvidence(d: Documents, values: List<Int>) {
            val array = finding(d).putArray("evidence")
            values.forEach { array.addObject().put("path", "/same").put("observed", it).put("source_version", "") }
        }
        val a = documents().also { setEvidence(it, listOf(1, 2, 1)) }
        val b = documents().also { setEvidence(it, listOf(1, 1, 2)) }
        val row = compare(a, b).findings.single { it.ruleId == finding(a)["rule_id"].asText() }
        assertEquals(3, row.before!!.evidence!!.size)
        assertEquals(listOf("1", "2", "1"), row.before.evidence!!.map { it.observed!!.json })
        assertEquals(listOf("1", "1", "2"), row.after!!.evidence!!.map { it.observed!!.json })
        assertTrue(row.before.evidence!!.all { it.sourceVersion == "" })
        assertEquals(listOf("evidence"), row.changes.map { it.field })
        (finding(b)["evidence"][0] as ObjectNode).remove("source_version")
        assertNull(compare(a, b).findings.single { it.ruleId == row.ruleId }.after!!.evidence!!.first().sourceVersion)
    }

    @Test fun `exact numeric evidence equality avoids both spelling changes and floating point collapse`() {
        fun evidence(d: Documents, number: String) = finding(d).putArray("evidence").addObject().put("path", "/exact")
            .set<JsonNode>("observed", mapper.readTree(number))
        val a = documents().also { evidence(it, "9007199254740993.000000000000000000000001") }
        val equal = documents().also { evidence(it, "9007199254740993.0000000000000000000000010") }
        assertTrue(compare(a, equal).findings.all { it.kind == null })
        val different = documents().also { evidence(it, "9007199254740993.000000000000000000000002") }
        assertEquals(1, compare(a, different).findings.count { it.kind == ChangeKind.CHANGED })
        val string = documents().also { evidence(it, "\"9007199254740993.000000000000000000000001\"") }
        assertEquals(1, compare(a, string).findings.count { it.kind == ChangeKind.CHANGED })
    }

    @Test fun `rule ID display join reports additions removals and changed association without guessing`() {
        val a = documents()
        val b = documents()
        val oldId = rule(b)["id"].asText()
        rule(b).put("id", "rule.added.fixture")
        finding(b).put("rule_id", "rule.added.fixture")
        val result = compare(a, b)
        assertEquals(result.findings.map { it.ruleId }.sorted(), result.findings.map { it.ruleId })
        assertEquals(ChangeKind.REMOVED, result.findings.single { it.ruleId == oldId }.kind)
        assertEquals(ChangeKind.ADDED, result.findings.single { it.ruleId == "rule.added.fixture" }.kind)
        assertNull(result.findings.single { it.ruleId == oldId }.after)
        assertNull(result.findings.single { it.ruleId == "rule.added.fixture" }.before)
        assertTrue(result.gaps.contains(FindingsComparisonGap.RULE_ID_JOIN_NOT_SEMANTIC_EQUIVALENCE))
    }

    @Test fun `changed affected selections are retained as ordered recorded IDs`() {
        val a = documents()
        val b = documents()
        rule(b).putArray("affected_kinds").add("workload")
        finding(b).putArray("selection_ids").add("sel_workload")
        val row = compare(a, b).findings.single { it.ruleId == finding(a)["rule_id"].asText() }
        assertEquals(listOf("selection_ids"), row.changes.map { it.field })
        assertEquals(listOf("sel_workload"), row.after!!.selectionIds)
        assertEquals(4, row.before!!.selectionIds.size)
    }

    @Test fun `catalog graph rules and pricebook locks are disclosed independently of finding values`() {
        val a = documents()
        val b = documents()
        val locks = b.build["locks"] as ObjectNode
        locks.put("catalog_snapshot_id", "catalog.other.fixture").put("dependency_graph_id", "graph.other.fixture")
            .put("dependency_graph_revision", BigInteger("9007199254740993"))
            .put("ruleset_id", "rules.other.fixture").put("ruleset_version", "2.1.0").put("pricebook_id", "prices.other.fixture")
        b.rules.put("id", "rules.other.fixture").put("version", "2.1.0")
        for (key in listOf("catalog_snapshot_id", "dependency_graph_id", "dependency_graph_revision", "ruleset_id", "ruleset_version"))
            b.report.set<JsonNode>(key, locks[key])
        val result = compare(a, b)
        assertTrue(result.recordedLocksDiffer)
        assertEquals("prices.other.fixture", result.afterBuild.locks["pricebook_id"])
        assertEquals("9007199254740993", result.afterBuild.locks["dependency_graph_revision"])
        assertEquals(ReportRecordIdentityCondition.DISTINCT_IDENTITIES, result.rulesetIdentityCondition)
        assertEquals(listOf("catalog_snapshot_id", "dependency_graph_id", "dependency_graph_revision", "ruleset_id", "ruleset_version"),
            result.metadataChanges.map { it.field })
        assertTrue(result.findings.all { it.kind == null })
    }

    @Test fun `same ruleset identity with a different expression is an integrity difference not an evaluation`() {
        val a = documents()
        val b = documents()
        rule(b).putObject("assert").put("op", "eq").also {
            it.putObject("left").put("literal", true); it.putObject("right").put("literal", false)
        }
        val result = compare(a, b)
        assertEquals(true, result.rulesetRecordsDiffer)
        assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS, result.rulesetIdentityCondition)
        assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_AND_RECORD, result.reportIdentityCondition)
        assertTrue(result.findings.all { it.kind == null })
        assertTrue(result.findings.all { it.after!!.outcome == FrozenFindingOutcome.PASS })
    }

    @Test fun `distinct report identities and recorded times are not mistaken for freshness`() {
        val a = documents()
        val b = documents().also { it.report.put("id", "report.other.fixture").put("evaluated_at", "2025-01-01T00:00:00Z") }
        val result = compare(a, b)
        assertEquals(ReportRecordIdentityCondition.DISTINCT_IDENTITIES, result.reportIdentityCondition)
        assertEquals(listOf("evaluated_at", "id"), result.metadataChanges.map { it.field })
        assertTrue(result.gaps.contains(FindingsComparisonGap.FRESHNESS_NOT_ESTABLISHED))
        assertTrue(result.findings.all { it.kind == null })
    }

    @Test fun `finding budget never returns partial success even when most findings are unchanged`() {
        val a = documents()
        val b = documents().also { finding(it).put("message", "changed.private.fixture") }
        val limited = compare(a, b, 2)
        assertEquals(FindingsComparisonState.FINDING_LIMIT_EXCEEDED, limited.state)
        assertTrue(limited.findings.isEmpty())
        assertTrue(limited.metadataChanges.isEmpty())
        assertTrue(limited.beforeInput is HistoricalReportInput.Available)
        assertEquals(ReportRecordIdentityCondition.SAME_IDENTITY_WITH_DIFFERENT_RECORDS, limited.reportIdentityCondition)
        assertEquals(3, compare(a, b, 3).findings.size)
        for (limit in listOf(0, -1, 10_001)) assertThrows(IllegalArgumentException::class.java) { compare(a, b, limit) }
    }

    @Test fun `swapping inputs reverses additions and full before after values`() {
        val a = documents()
        val b = documents()
        rule(b).put("id", "rule.new.fixture")
        finding(b).put("rule_id", "rule.new.fixture")
        finding(b, 1).put("message", "changed.private.fixture")
        val forward = compare(a, b)
        val reverse = compare(b, a)
        assertEquals(forward.findings.map { it.ruleId }, reverse.findings.map { it.ruleId })
        for ((old, new) in forward.findings.zip(reverse.findings)) {
            assertEquals(old.before?.message, new.after?.message)
            assertEquals(old.after?.message, new.before?.message)
            assertEquals(old.changes.map { it.beforeJson }, new.changes.map { it.afterJson })
            assertEquals(old.changes.map { it.afterJson }, new.changes.map { it.beforeJson })
            assertEquals(when (old.kind) { ChangeKind.ADDED -> ChangeKind.REMOVED; ChangeKind.REMOVED -> ChangeKind.ADDED; else -> old.kind }, new.kind)
        }
    }

    @Test fun `collections and debug summaries cannot mutate or disclose private comparison values`() {
        val a = documents()
        val b = documents().also { finding(it).put("message", "private.fixture.message")
            finding(it).putArray("evidence").addObject().put("path", "private.fixture.path").putNull("observed") }
        val result = compare(a, b)
        val row = result.findings.single { it.kind == ChangeKind.CHANGED }
        assertThrows(UnsupportedOperationException::class.java) { (result.findings as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.metadataChanges as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.gaps as MutableSet<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (row.changes as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (row.after!!.selectionIds as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (row.after!!.evidence as MutableList<*>).clear() }
        val record = (result.afterInput as HistoricalReportInput.Available).record
        listOf(result, result.afterInput, record, record.report, row, row.after!!, row.changes.first(),
            row.after!!.evidence!!.single(), row.after!!.evidence!!.single().observed!!).forEach {
            assertFalse(it.toString().contains("private.fixture"), it.javaClass.simpleName)
        }
        assertEquals(FindingsComparisonGap.entries.toSet(), result.gaps)
    }
}
