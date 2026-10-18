package ai.pickyour.core.evidence.answers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Cd1AnswerBoundaryTest {
    private fun field(raw: String = "{}") = KnownAnswerSchema.read(raw.toByteArray())
    private fun bytes(record: String = """{"state":"provided","value":1,"assumption":false}""") =
        """{"x_t0_answer_states":{"proposal_version":"cd1-0.2","answers":{"/answer":$record}}}""".toByteArray()
    private fun index(record: String) = Cd1AnswerIndex.read(bytes(record))

    @Test
    fun originalAndReturnedValueCopiesCannotRewriteRetainedFacts() {
        val original = bytes("""{"state":"provided","value":{"flag":false},"assumption":true,"source":"preset:explicit"}""")
        val saved = original.copyOf()
        val answers = Cd1AnswerIndex.read(original)
        original.fill(0)
        answers.originalBytes.fill(0)
        val value = answers.read("/answer", field()).value as ObjectNode
        value.put("flag", true)
        assertArrayEquals(saved, answers.originalBytes)
        val repeat = answers.read("/answer/flag", field("""{"type":"boolean"}"""))
        assertFalse(repeat.value!!.booleanValue())
        assertEquals(true, repeat.assumption)
        assertEquals("preset:explicit", repeat.presetSource)
        assertEquals("/answer", repeat.sourcePath)
    }

    @Test
    fun originalSchemaAndPrivateRootIdentityCannotAliasAnotherField() {
        val raw = """{"${'$'}id":"urn:field","const":1}""".toByteArray()
        val first = KnownAnswerSchema.read(raw)
        raw.fill(0)
        first.originalBytes.fill(0)
        val second = field("""{"${'$'}id":"urn:field","const":2}""")
        val answers = Cd1AnswerIndex.read(bytes())
        assertEquals(1, answers.read("/answer", first).value!!.intValue())
        assertEquals(Cd1Problem.VALUE_SCHEMA_MISMATCH, assertThrows(Cd1Rejected::class.java) {
            answers.read("/answer", second)
        }.problem)
        assertEquals(1, answers.read("/answer", first).value!!.intValue())
    }

    @Test
    fun unresolvedAndAbsentMembersDoNotAdmitAnUnusedUnsafeVocabulary() {
        val unsafe = field("""{"enum":["persistent","unknown"]}""")
        var calls = 0
        for (state in Cd1State.entries.filter { it != Cd1State.PROVIDED }) {
            val result = index("""{"state":"${state.encoded}"}""").predicate("/answer/child", unsafe) { calls++; true }
            assertEquals(state, result.state)
            assertNull(result.truth)
        }
        val absent = index("""{"state":"provided","value":{},"assumption":true,"source":"preset:parent"}""")
            .predicate("/answer/child", unsafe) { calls++; true }
        assertEquals(Cd1State.MISSING, absent.state)
        assertNull(absent.presetSource)
        assertNull(absent.assumption)
        assertEquals(0, calls)
    }

    @Test
    fun invalidProvidedVocabularyAndValueNeverExecutePredicates() {
        var calls = 0
        val answers = index("""{"state":"provided","value":"persistent","assumption":false}""")
        for (schema in listOf("""{"enum":["persistent","unknown"]}""", """{"type":"integer"}""")) {
            assertThrows(Cd1Rejected::class.java) { answers.predicate("/answer", field(schema)) { calls++; true } }
        }
        assertEquals(0, calls)
        assertEquals("persistent", answers.read("/answer", field()).value!!.textValue())
    }

    @Test
    fun predicatesReceiveDetachedObjects() {
        val answers = index("""{"state":"provided","value":{"x":1},"assumption":false}""")
        val result = answers.predicate("/answer", field()) { (it as ObjectNode).put("x", 2); true }
        assertEquals(true, result.truth)
        assertEquals(1, answers.read("/answer/x", field()).value!!.intValue())
    }

    @Test
    fun unreadOriginalNumbersCannotHideRounding() {
        val raw = """{"x_t0_answer_states":{"proposal_version":"cd1-0.2","answers":{
            "/answer":{"state":"provided","value":true,"assumption":false},
            "/unread":{"state":"provided","value":9007199254740993.0,"assumption":false}}}}""".toByteArray()
        assertEquals(Cd1Problem.UNSUPPORTED_NUMBER, assertThrows(Cd1Rejected::class.java) { Cd1AnswerIndex.read(raw) }.problem)
    }

    @Test
    fun runtimeFloatKindAndNegativeZeroDoNotReplaceOriginalBytes() {
        for (token in listOf("-0.0", "1.0", "5e-324")) {
            val original = bytes("""{"state":"provided","value":$token,"assumption":false}""")
            val answers = Cd1AnswerIndex.read(original)
            val value = answers.read("/answer", field("""{"type":"number"}""")).value!!
            assertTrue(value.isFloatingPointNumber)
            assertEquals(token.toDouble().toRawBits(), value.doubleValue().toRawBits())
            assertArrayEquals(original, answers.originalBytes)
        }
    }

    @Test
    fun malformedEncodingsDuplicateAliasesAndTrailingDocumentsRejectWithoutLibraryFragments() {
        val good = bytes()
        val invalid = listOf(good + "{}".toByteArray(), good.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + good,
            good.toString(Charsets.UTF_8).replace("\"value\":1", "\"value\":1,\"\\u0076alue\":2").toByteArray())
        for (raw in invalid) {
            val result = assertThrows(Cd1Rejected::class.java) { Cd1AnswerIndex.read(raw) }
            assertEquals(result.problem.name, result.message)
            assertNull(result.cause)
        }
    }

    @Test
    fun localInputAndSchemaBudgetsRejectBeforeUnboundedWork() {
        assertThrows(Cd1Rejected::class.java) { Cd1AnswerIndex.read(ByteArray(1_048_577)) }
        assertThrows(Cd1Rejected::class.java) { KnownAnswerSchema.read(ByteArray(65_537)) }
        assertThrows(Cd1Rejected::class.java) {
            index("""{"state":"provided","value":"${"x".repeat(16_385)}","assumption":false}""")
        }
        assertThrows(Cd1Rejected::class.java) {
            index("""{"state":"provided","value":${"1".repeat(129)},"assumption":false}""")
        }
    }

    @Test
    fun privateBooleanSchemaWrapperCannotAdmitExtraCallerFieldsOrASecondRoot() {
        for (schema in listOf("true,\"other\":true", "true}{}", "true,true", "true,\"schema\":true", "null")) {
            assertThrows(Cd1Rejected::class.java) { field(schema) }
        }
        assertEquals(1, Cd1AnswerIndex.read(bytes()).read("/answer", field("true")).value!!.intValue())
    }

    @Test
    fun independentProvidedSiblingsDoNotInventAParentAnswer() {
        val raw = """{"x_t0_answer_states":{"proposal_version":"cd1-0.2","answers":{
            "/a/x":{"state":"provided","value":1,"assumption":false},
            "/a/y":{"state":"provided","value":2,"assumption":false}}}}""".toByteArray()
        val answer = Cd1AnswerIndex.read(raw).read("/a", field())
        assertEquals(Cd1State.MISSING, answer.state)
        assertNull(answer.value)
        assertNull(answer.sourcePath)
    }

    @Test
    fun literalUnknownRemainsFreeTextAndCannotBecomeFiniteKnownVocabulary() {
        val answers = index("""{"state":"provided","value":"unknown","assumption":false}""")
        assertEquals(true, answers.predicate("/answer", field("""{"type":"string"}""")) { it.textValue() == "unknown" }.truth)
        assertThrows(Cd1Rejected::class.java) { answers.predicate("/answer", field("""{"const":"unknown"}""")) { true } }
        assertThrows(Cd1Rejected::class.java) { index("""{"state":"not_selected"}""") }
    }

    @Test
    fun causalRuleMetadataIsTakenOnlyFromTheExecutedTerminalPredicate() {
        var assertions = 0
        val result = Cd1RulePolicy.evaluate(Cd1Rule("synthetic", Cd1Severity.ERROR, Cd1Policy.COMPATIBILITY,
            { Cd1Predicate.provided(false, true, "/applies", "preset:explicit") },
            { assertions++; error("Unevaluated assertion executed") }))
        assertEquals(0, assertions)
        assertEquals(Cd1Outcome.SKIPPED, result.outcome)
        assertEquals(Cd1Severity.ERROR, result.severity)
        assertEquals(Cd1Position.APPLIES_WHEN, result.position)
        assertEquals("preset:explicit", result.presetSource)
        assertEquals(true, result.assumption)
    }

    @Test
    fun explicitPredicateFactoriesCannotAttachProvidedMetadataToUnresolvedStates() {
        assertThrows(Cd1Rejected::class.java) { Cd1Predicate.unresolved(Cd1State.PROVIDED) }
        assertThrows(Cd1Rejected::class.java) { Cd1Predicate.provided(true, false, "/bad~2") }
        assertThrows(Cd1Rejected::class.java) { Cd1Predicate.provided(true, false, presetSource = "https://example.invalid/") }
        assertThrows(IllegalArgumentException::class.java) {
            Cd1Rule(" ", Cd1Severity.INFO, Cd1Policy.COMPATIBILITY,
                { Cd1Predicate.provided(true, false) }, { Cd1Predicate.provided(true, false) })
        }
        val missing = Cd1Predicate.unresolved(Cd1State.MISSING, "/answer").negated()
        assertEquals(Cd1State.MISSING, missing.state)
        assertNull(missing.truth)
    }

    @Test
    fun fullIntegerSchemaBoundsCannotPassThroughNarrowedDoubleComparisons() {
        val answers = index("""{"state":"provided","value":99999999999999991611393,"assumption":false}""")
        assertThrows(Cd1Rejected::class.java) { answers.read("/answer", field("""{"maximum":1e23}""")) }
        assertNotNull(answers.read("/answer", field("""{"maximum":100000000000000000000000}""")).value)
    }

    @Test
    fun structuralEqualityKeepsBooleanAndLargeIntegerDistinctions() {
        val answers = index("""{"state":"provided","value":[true,1,99999999999999991611393,1e20],"assumption":false}""")
        assertNotNull(answers.read("/answer", field("""{"uniqueItems":true}""")).value)
        val aliases = index("""{"state":"provided","value":[1,1.0],"assumption":false}""")
        assertThrows(Cd1Rejected::class.java) { aliases.read("/answer", field("""{"uniqueItems":true}""")) }
    }

    @Test
    fun unsupportedRegexFormsRejectBeforeAnyPredicateIncludingUnusedDefinitions() {
        val answers = index("""{"state":"provided","value":"known","assumption":false}""")
        var calls = 0
        val patterns = listOf("\\d+", "\\w", "(?i)known", "(a+)+$", "known|other", "[a-z&&[^x]]",
            "[^x]", "é", "a*b", "^a*b*$", "^a{1025}$", "^a{2,1}$", "^a**$", "[z-a]", "x".repeat(257))
        for (pattern in patterns) for (unused in listOf(false, true)) {
            val schema = mapOf("pattern" to pattern)
            val document = if (unused) mapOf("${'$'}defs" to mapOf("unused" to schema)) else schema
            val supplied = KnownAnswerSchema.read(ObjectMapper().writeValueAsBytes(document))
            assertThrows(Cd1Rejected::class.java) { answers.predicate("/answer", supplied) { calls++; true } }
        }
        assertEquals(0, calls)
        // An annotation and an unused unsafe known vocabulary are still data.
        val annotation = field("""{"x-example":{"pattern":"(a+)+$"},"${'$'}defs":{"unused":{"enum":["unknown"]}}}""")
        assertEquals("known", answers.read("/answer", annotation).value!!.textValue())
    }

    @Test
    fun schemaCountBudgetsCannotWrapIntoPermissiveNativeIntBounds() {
        val answers = index("""{"state":"provided","value":[],"assumption":false}""")
        var calls = 0
        for (keyword in listOf("minLength", "maxLength", "minItems", "maxItems", "minProperties",
                "maxProperties", "minContains", "maxContains")) {
            for (bound in listOf("2147483648", "4294967296", "1e20")) {
                val schema = field("""{"$keyword":$bound}""")
                assertThrows(Cd1Rejected::class.java) { answers.predicate("/answer", schema) { calls++; true } }
            }
        }
        assertEquals(0, calls)
        assertNotNull(answers.read("/answer", field("""{"maxItems":2147483647}""")).value)
    }

    @Test
    fun unresolvedAnswersDoNotCompileUnsupportedPatternsOrCountLimits() {
        val unresolved = index("""{"state":"unknown"}""")
        for (schema in listOf("""{"pattern":"(a+)+$"}""", """{"minItems":4294967296}""")) {
            val result = unresolved.predicate("/answer", field(schema)) { error("Unresolved callback") }
            assertEquals(Cd1State.UNKNOWN, result.state)
            assertNull(result.truth)
        }
    }
}
