package ai.pickyour.core.evidence.placement.context

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger
import java.security.MessageDigest
import java.util.HexFormat

class Cd11ContextRecordTest {
    private val mapper = ObjectMapper()
    private val resourceRoot = "/placement/context/reference/"

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resourceRoot + name)).use { it.readBytes() }

    private fun hash(algorithm: String, bytes: ByteArray) =
        HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes))

    private fun accepted(binding: ByteArray, answers: ByteArray): Cd11ContextRecord =
        assertInstanceOf(Cd11ContextAdmission.Accepted::class.java, Cd11ContextRecord.read(binding, answers)).record

    private fun rejected(binding: ByteArray, answers: ByteArray, reason: Cd11ContextRejection? = null) {
        val result = assertInstanceOf(Cd11ContextAdmission.Rejected::class.java, Cd11ContextRecord.read(binding, answers))
        if (reason != null) assertEquals(reason, result.reason)
    }

    private inner class Input {
        val binding = mapper.readTree(resource("context-budget-binding.json")) as ObjectNode
        val document = mapper.readTree(resource("context-budget-answers.json")) as ObjectNode
        val body get() = binding["x_t0_context_budget"] as ObjectNode
        val scope get() = body["scope"] as ObjectNode
        val values get() = document["x_t0_answer_states"]["answers"] as ObjectNode

        fun answer(field: String, value: String = "null", state: String = "provided", source: String? = null) {
            val row = mapper.createObjectNode().put("state", state)
            if (state == "provided") row.set<JsonNode>("value", mapper.readTree(value))
            if (source != null) row.put("source", source)
            values.set<JsonNode>("/context/" + field, row)
        }

        fun bytes(rawAnswers: ByteArray = mapper.writeValueAsBytes(document)): Pair<ByteArray, ByteArray> {
            body.put("answer_states_sha256", hash("SHA-256", rawAnswers))
            return mapper.writeValueAsBytes(binding) to rawAnswers
        }

        fun record(): Cd11ContextRecord = bytes().let { accepted(it.first, it.second) }
        fun result(): Cd11ContextResult = record().let { it.evaluate(it.scope, true, true) }
        fun fails(reason: Cd11ContextRejection? = null) = bytes().let { rejected(it.first, it.second, reason) }
        fun fitting() = answer("input_tokens", """{"lower":5000,"upper":6000}""")
    }

    @Test fun originalReferenceFilesHaveTheirPinnedGitIdentities() {
        val manifest = mapper.readTree(resource("reference-pins.json"))
        assertEquals("449a29be1752a3c545709da466c7d53fc18fef73", manifest["reference_commit"].textValue())
        assertEquals("cd11-0.1", manifest["binding_profile"].textValue())
        assertEquals("cd1-0.1", manifest["answer_profile"].textValue())
        val expected = mapOf(
            "context-budget-binding.json" to "659821843ecd9db3c9d85469cfa89877329da684",
            "context-budget-answers.json" to "3006ada1fe0945649296b3e3f9a5fec0aa240f0b",
            "context-budget-binding.schema.json" to "5480495d59bf9c41c9108c7d13934359420a088e",
            "t0-answer-states.schema.json" to "91aab5e6bae86f3b7dfb8e504a65c02502c6defb",
        )
        val entries = manifest["files"].toList()
        assertEquals(expected.keys, entries.map { it["resource"].textValue() }.toSet())
        assertEquals(expected.size, entries.size)
        for (entry in entries) {
            val name = entry["resource"].textValue()
            val bytes = resource(name)
            assertEquals(entry["bytes"].intValue(), bytes.size)
            assertEquals("tests/fixtures/" + name, entry["source_path"].textValue())
            val blob = hash("SHA-1", ("blob " + bytes.size + "\u0000").toByteArray() + bytes)
            assertEquals(expected.getValue(name), blob)
            assertEquals(blob, entry["git_blob"].textValue())
        }
        assertEquals(
            "be4fc13496144cb559fa2a8116da5f81fd5e1395f50fa6c6b1b8999bf6c9b2f1",
            hash("SHA-256", resource("context-budget-answers.json")),
        )
    }

    @Test fun originalSharedRecordRetainsEveryByteAndProvenanceField() {
        val binding = resource("context-budget-binding.json")
        val answers = resource("context-budget-answers.json")
        val record = accepted(binding, answers)
        assertArrayEquals(binding, record.originalBindingBytes())
        assertArrayEquals(answers, record.originalAnswerBytes())
        assertEquals(hash("SHA-256", binding), record.bindingSha256)
        assertEquals(hash("SHA-256", answers), record.answersSha256)
        val result = record.evaluate(record.scope, true, true)
        assertEquals(ContextOutcome.FAIL, result.outcome)
        assertFalse(result.mayNotFit)
        assertEquals(Cd11ContextReason.NORMALIZED_CONTEXT_SCREEN, result.reason)
        assertEquals(listOf(ContextCheckName.OUTPUT, ContextCheckName.COMBINED), result.checks.map { it.name })
        assertEquals(Cd11ContextField.entries.toSet(), result.answers.keys)
        val input = result.answers.getValue(Cd11ContextField.INPUT_TOKENS)
        assertEquals("/context/input_tokens", input.requestedPath)
        assertEquals("/context/input_tokens", input.sourcePath)
        assertEquals("synthetic:complete-request-count-including-history-and-injected-memory", input.source)
        assertEquals(Cd11AnswerOrigin.EXACT, input.origin)
        assertEquals(ContextTokenRange(BigInteger.valueOf(7000), BigInteger.valueOf(7000)),
            assertInstanceOf(Cd11ContextValue.Range::class.java, input.value).value)
    }

    @Test fun separateWireBudgetsRequireNoProvidedSharedVendorWindow() {
        val input = Input()
        input.answer("semantics", "\"separate\"")
        input.answer("input_cap_tokens", "8000")
        input.answer("shared_cap_tokens", state = "not_applicable")
        val result = input.result()
        assertEquals(ContextOutcome.PASS, result.outcome)
        assertEquals(
            ContextCheck(ContextCheckName.COMBINED, ContextOutcome.SKIPPED, ContextOutcome.SKIPPED),
            result.checks.single { it.name == ContextCheckName.COMBINED },
        )
        input.answer("shared_cap_tokens", "12000")
        input.fails(Cd11ContextRejection.CONTEXT_VALUE)
    }

    @Test fun boundaryStraddleAndIndependentOutputFailureUseTheReferencePolicy() {
        val input = Input()
        input.fitting()
        assertEquals(ContextOutcome.PASS, input.result().outcome)
        input.answer("input_tokens", """{"lower":5000,"upper":6500}""")
        assertEquals(ContextOutcome.FAIL, input.result().outcome)
        assertTrue(input.result().mayNotFit)
        input.answer("output_cap_tokens", "1000")
        assertEquals(ContextOutcome.FAIL, input.result().outcome)
        assertFalse(input.result().mayNotFit)
    }

    @Test fun selectedContextVectorsKeepSeparatePolicyAndExactStraddlingFacts() {
        val separate = Input()
        separate.answer("semantics", "\"separate\"")
        separate.answer("input_cap_tokens", "8000")
        separate.answer("shared_cap_tokens", state = "not_applicable")
        separate.answer("policy_total_cap_tokens", "8500")
        val policy = separate.result()
        assertEquals(ContextOutcome.FAIL, policy.outcome)
        assertFalse(policy.mayNotFit)
        assertEquals(ContextOutcome.PASS, policy.checks.single { it.name == ContextCheckName.INPUT }.upper)
        assertEquals(ContextOutcome.PASS, policy.checks.single { it.name == ContextCheckName.OUTPUT }.upper)
        assertEquals(ContextCheck(ContextCheckName.POLICY_TOTAL, ContextOutcome.FAIL, ContextOutcome.FAIL),
            policy.checks.single { it.name == ContextCheckName.POLICY_TOTAL })

        val straddle = Input()
        straddle.answer("input_tokens", """{"lower":5000,"upper":7000}""")
        val interval = straddle.result()
        assertEquals(ContextOutcome.FAIL, interval.outcome)
        assertTrue(interval.mayNotFit)
        assertEquals(ContextCheck(ContextCheckName.COMBINED, ContextOutcome.PASS, ContextOutcome.FAIL),
            interval.checks.single { it.name == ContextCheckName.COMBINED })
    }

    @Test fun zeroOutputAndPolicyValuesRemainProvidedFacts() {
        val input = Input()
        input.answer("reserved_output_tokens", "0")
        input.answer("output_cap_tokens", "0")
        assertEquals(ContextOutcome.PASS, input.result().outcome)
        val output = input.result().answers.getValue(Cd11ContextField.OUTPUT_CAP_TOKENS)
        assertEquals(Cd11AnswerState.PROVIDED, output.state)
        assertEquals(BigInteger.ZERO, assertInstanceOf(Cd11ContextValue.Count::class.java, output.value).value)
        input.answer("policy_total_cap_tokens", "0")
        assertEquals(ContextOutcome.FAIL, input.result().outcome)
    }

    @TestFactory fun invalidCountTokensNeverBecomeIntegers(): List<DynamicTest> =
        listOf("reserved_output_tokens", "output_cap_tokens", "shared_cap_tokens", "input_cap_tokens", "policy_total_cap_tokens")
            .flatMap { field ->
                listOf("true", "false", "1.0", "1e0", "\"1\"", "null", "-1", "[]", "{}", "\"\"")
                    .mapIndexed { index, value -> DynamicTest.dynamicTest(field + "_invalid_token_" + index) {
                        val input = Input()
                        input.answer(field, value)
                        input.fails(Cd11ContextRejection.CONTEXT_VALUE)
                    } }
            }

    @Test fun originalExponentsAndDecimalSpellingCannotAliasAnIntegerToken() {
        for (literal in listOf("1.0", "1e0", "1E+0", "-0.0", "0e0")) {
            val input = Input()
            input.answer("reserved_output_tokens", "\"LITERAL_SLOT\"")
            val raw = mapper.writeValueAsString(input.document).replace("\"LITERAL_SLOT\"", literal).toByteArray()
            input.bytes(raw).let { rejected(it.first, it.second, Cd11ContextRejection.CONTEXT_VALUE) }
        }
    }

    @TestFactory fun invalidRangesRejectBeforeAnyEvaluation(): List<DynamicTest> = listOf(
        """{"lower":1.0,"upper":2}""", """{"lower":1,"upper":true}""",
        """{"lower":2,"upper":1}""", """{"lower":0,"upper":1}""",
        """{"lower":1}""", """{"lower":1,"upper":2,"mean":1.5}""", "null", "[]",
    ).mapIndexed { index, value -> DynamicTest.dynamicTest("invalid_input_range_" + index) {
        val input = Input()
        input.answer("input_tokens", value)
        input.fails(Cd11ContextRejection.CONTEXT_VALUE)
    } }

    @Test fun positiveCapsAndWideIntegersRetainTheirExactBoundaries() {
        for (field in listOf("input_cap_tokens", "shared_cap_tokens")) {
            val input = Input()
            input.answer(field, "0")
            input.fails(Cd11ContextRejection.CONTEXT_VALUE)
        }
        val wide = BigInteger.ONE.shiftLeft(80)
        val input = Input()
        input.answer("input_tokens", """{"lower":""" + wide + ""","upper":""" + wide + "}")
        input.answer("shared_cap_tokens", (wide + BigInteger.valueOf(1999)).toString())
        assertEquals(ContextOutcome.FAIL, input.result().outcome)
        input.answer("shared_cap_tokens", (wide + BigInteger.valueOf(2000)).toString())
        assertEquals(ContextOutcome.PASS, input.result().outcome)
    }

    @TestFactory fun unresolvedRequiredOperandsNeverMeanZeroOrNotApplicable(): List<DynamicTest> =
        listOf("semantics", "input_tokens", "reserved_output_tokens", "output_cap_tokens", "shared_cap_tokens")
            .flatMap { field ->
                listOf("unknown", "missing", "withheld", "not_applicable").map { state ->
                    DynamicTest.dynamicTest("required_" + field + "_" + state) {
                        val input = Input()
                        input.fitting()
                        input.answer(field, state = state)
                        val result = input.result()
                        assertEquals(ContextOutcome.UNKNOWN, result.outcome)
                        val answer = result.answers.values.single { it.field.fieldName == field }
                        assertEquals(state, answer.state.wireValue)
                        assertNull(answer.value)
                        assertNull(answer.source)
                        assertEquals("/context/" + field, answer.sourcePath)
                    }
                }
            }

    @TestFactory fun optionalCapsRequireExplicitAbsence(): List<DynamicTest> =
        listOf("input_cap_tokens", "policy_total_cap_tokens").flatMap { field ->
            listOf("unknown", "missing", "withheld", "absent", "not_applicable").map { state ->
                DynamicTest.dynamicTest("optional_" + field + "_" + state) {
                    val input = Input()
                    input.fitting()
                    if (state == "absent") input.values.remove("/context/" + field)
                    else input.answer(field, state = state)
                    assertEquals(if (state == "not_applicable") ContextOutcome.PASS else ContextOutcome.UNKNOWN,
                        input.result().outcome)
                }
            }
        }

    @Test fun separateInputCapStaysRequiredAndKnownFailuresSurviveUnknowns() {
        val separate = Input()
        separate.answer("semantics", "\"separate\"")
        separate.answer("shared_cap_tokens", state = "not_applicable")
        assertEquals(ContextOutcome.UNKNOWN, separate.result().outcome)
        val input = Input()
        input.answer("semantics", state = "unknown")
        input.answer("input_cap_tokens", "6000")
        assertEquals(ContextOutcome.FAIL, input.result().outcome)
        input.answer("input_cap_tokens", "8000")
        assertEquals(ContextOutcome.UNKNOWN, input.result().outcome)
        input.answer("policy_total_cap_tokens", state = "withheld")
        input.answer("reserved_output_tokens", "5000")
        assertEquals(ContextOutcome.FAIL, input.result().outcome)
    }

    @TestFactory fun everyScopeDimensionMustMatchExactly(): List<DynamicTest> {
        val record = Input().record()
        val scope = record.scope
        val alternatives = listOf(
            scope.copy(selectionId = scope.selectionId + "-other"),
            scope.copy(operation = scope.operation + "-other"),
            scope.copy(settingsSha256 = "0".repeat(64)),
            scope.copy(model = scope.model.copy(id = scope.model.id + "-other")),
            scope.copy(model = scope.model.copy(version = scope.model.version + " ")),
            scope.copy(route = scope.route.copy(id = scope.route.id + "-other")),
            scope.copy(route = scope.route.copy(version = scope.route.version + "-other")),
            scope.copy(tokenizer = scope.tokenizer.copy(id = scope.tokenizer.id + "-other")),
            scope.copy(tokenizer = scope.tokenizer.copy(version = scope.tokenizer.version + "-other")),
        )
        return alternatives.mapIndexed { index, selected -> DynamicTest.dynamicTest("scope_dimension_" + index) {
            val result = record.evaluate(selected, true, true)
            assertEquals(ContextOutcome.UNKNOWN, result.outcome)
            assertEquals(Cd11ContextReason.ROUTE_SCOPE_MISMATCH, result.reason)
            assertTrue(result.checks.isEmpty())
            assertFalse(result.mayNotFit)
        } }
    }

    @Test fun applicabilityScopeAndSourceEligibilityPrecedeArithmetic() {
        val record = Input().record() // Its own fully eligible arithmetic fails.
        for ((eligible, reason) in listOf(
            false to Cd11ContextReason.SOURCE_EVIDENCE_INELIGIBLE,
            null to Cd11ContextReason.SOURCE_EVIDENCE_UNRESOLVED,
        )) {
            val result = record.evaluate(record.scope, true, eligible)
            assertEquals(ContextOutcome.UNKNOWN, result.outcome)
            assertEquals(reason, result.reason)
            assertTrue(result.checks.isEmpty())
        }
        assertEquals(Cd11ContextReason.SELECTION_SCOPE_MISSING, record.evaluate(null, true, true).reason)
        assertEquals(Cd11ContextReason.SELECTION_ACTIVATION_UNKNOWN, record.evaluate(null, null, true).reason)
        val disabled = record.evaluate(null, false, null)
        assertEquals(ContextOutcome.SKIPPED, disabled.outcome)
        assertEquals(Cd11ContextReason.SELECTION_NOT_ENABLED, disabled.reason)
        assertTrue(disabled.checks.isEmpty())
        assertEquals(Cd11ContextField.entries.toSet(), disabled.answers.keys)
    }

    @Test fun malformedTypedScopeCannotReachADisabledShortcut() {
        val record = Input().record()
        assertThrows(IllegalArgumentException::class.java) {
            record.evaluate(record.scope.copy(operation = "generate-text\n"), false, false)
        }
        for (version in listOf("", " \u0085\u00a0", "v1\u0000", "\ud800", "a".repeat(201))) {
            assertThrows(IllegalArgumentException::class.java) { Cd11Reference("model.synthetic", version) }
        }
        val wideVersion = "😀".repeat(200)
        val input = Input()
        (input.scope["route"] as ObjectNode).put("version", wideVersion)
        assertEquals(wideVersion, input.record().scope.route.version)
        (input.scope["route"] as ObjectNode).put("version", wideVersion + "😀")
        input.fails(Cd11ContextRejection.BINDING_SHAPE)
    }

    @Test fun malformedBindingIdentitiesAndClosedFieldsReject() {
        for ((field, value) in listOf(
            "selection_id" to "a".repeat(121), "operation" to "generate-text\n",
            "settings_sha256" to "A".repeat(64), "settings_sha256" to ("a".repeat(64) + "\n"),
        )) {
            val input = Input()
            input.scope.put(field, value)
            input.fails(Cd11ContextRejection.BINDING_SHAPE)
        }
        for (mutation in 0..4) {
            val input = Input()
            when (mutation) {
                0 -> input.body.put("proposal_version", "cd11-0.2")
                1 -> input.body.put("extra", true)
                2 -> input.scope.remove("route")
                3 -> (input.scope["model"] as ObjectNode).put("version", 1.0)
                else -> input.binding.put("extra", true)
            }
            input.fails(Cd11ContextRejection.BINDING_SHAPE)
        }
    }

    @Test fun withheldAndOtherUnresolvedParentsRetainTheirActualCause() {
        for (state in listOf("withheld", "unknown", "missing", "not_applicable")) {
            val input = Input()
            input.values.removeAll()
            input.values.set<JsonNode>("/context", mapper.createObjectNode().put("state", state))
            val result = input.result()
            assertEquals(ContextOutcome.UNKNOWN, result.outcome)
            for (answer in result.answers.values) {
                assertEquals(state, answer.state.wireValue)
                assertEquals("/context", answer.sourcePath)
                assertEquals(Cd11AnswerOrigin.UNRESOLVED_ANCESTOR, answer.origin)
                assertNull(answer.value)
                assertNull(answer.source)
            }
        }
    }

    @Test fun providedParentSuppliesTypedChildrenWithoutRewritingTheirSource() {
        val input = Input()
        val parent = mapper.createObjectNode()
        for ((path, value) in input.values.fields().asSequence()) {
            if (value["state"].textValue() == "provided") parent.set<JsonNode>(path.substringAfterLast('/'), value["value"])
        }
        input.values.removeAll()
        input.values.set<JsonNode>("/context", mapper.createObjectNode().put("state", "provided")
            .put("source", " exact parent source ").set<JsonNode>("value", parent))
        val result = input.result()
        val count = result.answers.getValue(Cd11ContextField.INPUT_TOKENS)
        assertEquals(Cd11AnswerState.PROVIDED, count.state)
        assertEquals("/context/input_tokens", count.requestedPath)
        assertEquals("/context", count.sourcePath)
        assertEquals(" exact parent source ", count.source)
        assertEquals(Cd11AnswerOrigin.PROVIDED_ANCESTOR, count.origin)
        val absent = result.answers.getValue(Cd11ContextField.INPUT_CAP_TOKENS)
        assertEquals(Cd11AnswerState.MISSING, absent.state)
        assertEquals("/context", absent.sourcePath)
        assertEquals(Cd11AnswerOrigin.ANCESTOR_MEMBER_MISSING, absent.origin)
        assertNull(absent.source)
    }

    @Test fun providedChildrenDoNotManufactureAParentAndWithheldWitnessUsesScalarOrder() {
        val input = Input()
        input.values.remove("/context/input_tokens")
        input.values.set<JsonNode>("/context/input_tokens/lower",
            mapper.createObjectNode().put("state", "provided").put("value", 1))
        input.values.set<JsonNode>("/context/input_tokens/upper",
            mapper.createObjectNode().put("state", "provided").put("value", 2))
        val absent = input.result().answers.getValue(Cd11ContextField.INPUT_TOKENS)
        assertEquals(Cd11AnswerState.MISSING, absent.state)
        assertNull(absent.sourcePath)
        assertEquals(Cd11AnswerOrigin.ABSENT, absent.origin)
        input.values.set<JsonNode>("/context/input_tokens/\uD800\uDC00", mapper.createObjectNode().put("state", "withheld"))
        input.values.set<JsonNode>("/context/input_tokens/\uE000", mapper.createObjectNode().put("state", "withheld"))
        val withheld = input.result().answers.getValue(Cd11ContextField.INPUT_TOKENS)
        assertEquals(Cd11AnswerState.WITHHELD, withheld.state)
        assertEquals("/context/input_tokens/\uE000", withheld.sourcePath)
        assertEquals(Cd11AnswerOrigin.WITHHELD_DESCENDANT, withheld.origin)
    }

    @Test fun providedNullFalseZeroAndEmptyUnusedPayloadsRemainOriginalData() {
        for (value in listOf("null", "false", "0", "-0.0", "\"\"", "[]", "{}", "1.0", "0.1", "5e-324")) {
            val input = Input()
            // Insert the literal directly so the fixture helper cannot normalize its numeric token.
            val values = input.values
            values.set<JsonNode>("/unused", mapper.createObjectNode().put("state", "provided").put("value", "LITERAL_SLOT"))
            val raw = mapper.writeValueAsString(input.document).replace("\"LITERAL_SLOT\"", value).toByteArray()
            val pair = input.bytes(raw)
            val record = accepted(pair.first, pair.second)
            assertArrayEquals(raw, record.originalAnswerBytes())
            assertEquals(ContextOutcome.FAIL, record.evaluate(record.scope, true, true).outcome)
        }
    }

    @Test fun unusedRowsStillRequireTheOriginalEnvelopeAndPrecisionPolicy() {
        for (row in listOf(
            """{"state":"provided"}""", """{"state":"unknown","value":null}""",
            """{"state":"withheld","source":"secret"}""", """{"state":"other"}""",
            """{"state":"provided","value":null,"assumption":false}""",
            """{"state":"provided","value":null,"source":null}""",
        )) {
            val input = Input()
            input.values.set<JsonNode>("/unused", mapper.readTree(row))
            input.fails(Cd11ContextRejection.ANSWER_ENCODING)
        }
        for (literal in listOf("0.10000000000000001", "1e23", "1e999", "4.9e-324", "1e-999", "NaN")) {
            val input = Input()
            input.values.set<JsonNode>("/unused", mapper.createObjectNode().put("state", "provided").put("value", "LITERAL_SLOT"))
            val raw = mapper.writeValueAsString(input.document).replace("\"LITERAL_SLOT\"", literal).toByteArray()
            input.bytes(raw).let { rejected(it.first, it.second) }
        }
        val input = Input()
        (input.document["x_t0_answer_states"] as ObjectNode).put("proposal_version", "cd1-0.2")
        input.fails(Cd11ContextRejection.ANSWER_ENCODING)
    }

    @Test fun sourceWhitespaceIsValidatedWithoutTrimmingOrInferringAPreset() {
        for (source in listOf("", " ", "\u0085", "\u00a0", "\u001c\u2000\u3000")) {
            val input = Input()
            input.answer("output_cap_tokens", "4000", source = source)
            input.fails(Cd11ContextRejection.ANSWER_ENCODING)
        }
        val input = Input()
        input.answer("output_cap_tokens", "4000", source = " \u200b unusual literal source ")
        assertEquals(" \u200b unusual literal source ",
            input.result().answers.getValue(Cd11ContextField.OUTPUT_CAP_TOKENS).source)
    }

    @Test fun invalidPointersOverlapsAndScalarTraversalReject() {
        for (path in listOf("", "context/input", "#/context", "/context/~2")) {
            val input = Input()
            input.values.set<JsonNode>(path, mapper.createObjectNode().put("state", "unknown"))
            input.fails(Cd11ContextRejection.ANSWER_ENCODING)
        }
        val overlap = Input()
        overlap.values.set<JsonNode>("/context", mapper.createObjectNode().put("state", "withheld"))
        overlap.fails(Cd11ContextRejection.ANSWER_ENCODING)
        for (value in listOf("null", "false", "0", "\"\"", "[]")) {
            val input = Input()
            input.values.removeAll()
            input.values.set<JsonNode>("/context", mapper.createObjectNode().put("state", "provided")
                .set<JsonNode>("value", mapper.readTree(value)))
            input.fails(Cd11ContextRejection.CONTEXT_VALUE)
        }
    }

    @Test fun validEscapedAndEmptyPointerTokensArePreservedForUnusedAnswers() {
        val input = Input()
        for (path in listOf("/", "/unused/", "/a~1b", "/a/b", "/tilde~0key", "/literal~01", "/literal~1")) {
            input.values.set<JsonNode>(path, mapper.createObjectNode().put("state", "missing"))
        }
        val pair = input.bytes()
        assertArrayEquals(pair.second, accepted(pair.first, pair.second).originalAnswerBytes())
        // Decoded slash in one token differs from two path tokens; only a true ancestor overlaps.
        input.values.set<JsonNode>("/a~1b/child", mapper.createObjectNode().put("state", "unknown"))
        input.fails(Cd11ContextRejection.ANSWER_ENCODING)
    }

    @Test fun hashBindingUsesOriginalWhitespaceAndCannotAcceptAReplacementObject() {
        val binding = resource("context-budget-binding.json")
        val answers = resource("context-budget-answers.json")
        rejected(binding, answers + byteArrayOf(10), Cd11ContextRejection.ANSWER_HASH_MISMATCH)
        val input = Input()
        val changed = answers + byteArrayOf(10)
        val pair = input.bytes(changed)
        assertArrayEquals(changed, accepted(pair.first, pair.second).originalAnswerBytes())
        rejected(binding, "{}".toByteArray(), Cd11ContextRejection.ANSWER_HASH_MISMATCH)
    }

    @Test fun duplicateKeysMalformedUtf8AndTrailingInputRemainRejected() {
        val input = Input()
        val original = resource("context-budget-answers.json").toString(Charsets.UTF_8)
        for (raw in listOf(
            original.replace("\"lower\": 7000", "\"lower\": 6000,\"lower\": 7000").toByteArray(),
            original.replace("\"lower\": 7000", "\"lower\": 6000,\"lo\\u0077er\": 7000").toByteArray(),
            original.replace("\"lower\": 7000", "\"lower\": NaN").toByteArray(),
            original.toByteArray() + " {}".toByteArray(),
            original.replace("shared", "\\uD800").toByteArray(),
            byteArrayOf(0xff.toByte()),
        )) input.bytes(raw).let { rejected(it.first, it.second) }
        val duplicateBinding = resource("context-budget-binding.json").toString(Charsets.UTF_8)
            .replace("\"proposal_version\": \"cd11-0.1\"",
                "\"proposal_version\": \"cd11-0.2\", \"proposal_version\": \"cd11-0.1\"").toByteArray()
        rejected(duplicateBinding, resource("context-budget-answers.json"), Cd11ContextRejection.BINDING_JSON)
        rejected("{}".toByteArray(), resource("context-budget-answers.json"), Cd11ContextRejection.BINDING_SHAPE)
    }

    @Test fun exactNativeByteBoundaryAppliesSeparatelyToEachOriginalInput() {
        val input = Input()
        val original = resource("context-budget-answers.json")
        val paddedAnswers = original + ByteArray(Cd11ContextRecord.MAX_INPUT_BYTES - original.size) { 32 }
        val pair = input.bytes(paddedAnswers)
        assertArrayEquals(paddedAnswers, accepted(pair.first, pair.second).originalAnswerBytes())
        rejected(pair.first, paddedAnswers + byteArrayOf(32), Cd11ContextRejection.NATIVE_INPUT_LIMIT)
        val binding = resource("context-budget-binding.json")
        val paddedBinding = binding + ByteArray(Cd11ContextRecord.MAX_INPUT_BYTES - binding.size) { 32 }
        assertArrayEquals(paddedBinding, accepted(paddedBinding, original).originalBindingBytes())
        rejected(paddedBinding + byteArrayOf(32), original, Cd11ContextRejection.NATIVE_INPUT_LIMIT)
    }

    @Test fun nativeParserLimitsRemainExplicitConsumerBounds() {
        for (literal in listOf(
            "1".repeat(129), "\"" + "x".repeat(16385) + "\"",
            "[".repeat(33) + "0" + "]".repeat(33),
        )) {
            val input = Input()
            input.values.set<JsonNode>("/unused", mapper.createObjectNode().put("state", "provided").put("value", "LITERAL_SLOT"))
            val raw = mapper.writeValueAsString(input.document).replace("\"LITERAL_SLOT\"", literal).toByteArray()
            input.bytes(raw).let { rejected(it.first, it.second, Cd11ContextRejection.NATIVE_INPUT_LIMIT) }
        }
        val input = Input()
        input.values.set<JsonNode>("/" + "x".repeat(256), mapper.createObjectNode().put("state", "missing"))
        input.fails(Cd11ContextRejection.NATIVE_INPUT_LIMIT)
        val within = Input()
        within.values.set<JsonNode>("/unused", mapper.createObjectNode().put("state", "provided")
            .set<JsonNode>("value", mapper.readTree("1".repeat(128))))
        assertEquals(ContextOutcome.FAIL, within.result().outcome)
    }

    @Test fun retainedBytesAndEveryReturnedCollectionAreDefensive() {
        val binding = resource("context-budget-binding.json")
        val answers = resource("context-budget-answers.json")
        val expectedBinding = binding.copyOf()
        val expectedAnswers = answers.copyOf()
        val record = accepted(binding, answers)
        binding.fill(0)
        answers.fill(0)
        record.originalBindingBytes().fill(0)
        record.originalAnswerBytes().fill(0)
        assertArrayEquals(expectedBinding, record.originalBindingBytes())
        assertArrayEquals(expectedAnswers, record.originalAnswerBytes())
        assertThrows(UnsupportedOperationException::class.java) {
            (record.answers as MutableMap<Cd11ContextField, Cd11AnswerObservation>).clear()
        }
        val result = record.evaluate(record.scope, true, true)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.answers as MutableMap<Cd11ContextField, Cd11AnswerObservation>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) { (result.checks as MutableList<ContextCheck>).clear() }
        val detached = record.scope.copy(model = record.scope.model.copy(version = "detached"))
        assertEquals(Cd11ContextReason.ROUTE_SCOPE_MISMATCH, record.evaluate(detached, true, true).reason)
        assertEquals("v1", record.scope.model.version)
        assertEquals(ContextOutcome.FAIL, record.evaluate(record.scope, true, true).outcome)
    }

    @Test fun automaticTextAndRejectionsDoNotExposeInputMetadata() {
        val secret = "private-fixture-value"
        val input = Input()
        input.answer("output_cap_tokens", "4000", source = secret)
        (input.scope["model"] as ObjectNode).put("version", secret)
        val record = input.record()
        val result = record.evaluate(record.scope, true, true)
        for (text in listOf(record.toString(), record.scope.toString(), record.scope.model.toString(),
            record.answers.toString(), result.toString(), result.answers.toString(),
            result.answers.getValue(Cd11ContextField.OUTPUT_CAP_TOKENS).value.toString())) {
            assertFalse(text.contains(secret))
            assertFalse(text.contains("4000"))
        }
        input.scope.put("selection_id", "INVALID-" + secret)
        val pair = input.bytes()
        val rejection = Cd11ContextRecord.read(pair.first, pair.second)
        assertInstanceOf(Cd11ContextAdmission.Rejected::class.java, rejection)
        assertFalse(rejection.toString().contains(secret))
    }
}
