package ai.pickyour.core.conformance.typedcomparison

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger
import java.util.Locale

class TypedComparisonAdmissionTest {
    private val profile = TypedComparison01.PROFILE
    private val mapper = ObjectMapper()

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)
    private fun quoted(text: String) = mapper.writeValueAsBytes(text)
    private fun compare(left: String?, right: String?, operator: String = "eq",
                        limits: TypedComparison01.Limits = TypedComparison01.Limits()) =
        TypedComparison01.compare(profile, operator, left?.let(::bytes), right?.let(::bytes), limits)

    private fun outcome(wanted: TypedComparison01.Outcome, left: String?, right: String?,
                        operator: String = "eq", limits: TypedComparison01.Limits = TypedComparison01.Limits()) {
        assertEquals(TypedComparison01.Result.Compared(wanted), compare(left, right, operator, limits))
    }

    private fun rejected(wanted: TypedComparison01.Reason, left: ByteArray?, right: ByteArray? = bytes("0"),
                         operator: String = "eq", limits: TypedComparison01.Limits = TypedComparison01.Limits()) {
        val actual = TypedComparison01.compare(profile, operator, left, right, limits)
        assertEquals(TypedComparison01.Result.Rejected(wanted), actual)
        // A failed attempt must not poison the shared tokenizer or a later attempt.
        outcome(TypedComparison01.Outcome.TRUE, "1.00", "\"1\"")
    }

    @Test
    fun profileAndOperatorAreExactBeforeEitherMalformedOperand() {
        for (candidate in listOf(null, "", "typed-comparison/0.2", "$profile ",
            "frozen-v1-reference-py312-decimal28", "local-retained-frozen-v1-no-external-evidence/1")) {
            assertEquals(TypedComparison01.Result.Rejected(TypedComparison01.Reason.UNSUPPORTED_PROFILE),
                TypedComparison01.compare(candidate, "eq", byteArrayOf(-1), bytes("NaN")))
        }
        for (candidate in listOf(null, "", "EQ", "eq ", "all", "pass")) {
            assertEquals(TypedComparison01.Result.Rejected(TypedComparison01.Reason.UNSUPPORTED_OPERATOR),
                TypedComparison01.compare(profile, candidate, byteArrayOf(-1), bytes("NaN")))
        }
    }

    @TestFactory
    fun malformedOperandsRejectInBothPositions(): List<DynamicTest> {
        val malformed = listOf(
            Triple("empty", bytes(""), TypedComparison01.Reason.INVALID_JSON),
            Triple("trailing-root", bytes("1 2"), TypedComparison01.Reason.INVALID_JSON),
            Triple("trailing-array-comma", bytes("[1,]"), TypedComparison01.Reason.INVALID_JSON),
            Triple("leading-zero", bytes("01"), TypedComparison01.Reason.INVALID_JSON),
            Triple("raw-control", bytes("\"private\ntext\""), TypedComparison01.Reason.INVALID_JSON),
            Triple("bom", byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 49), TypedComparison01.Reason.INVALID_JSON),
            Triple("non-utf8", byteArrayOf(-1), TypedComparison01.Reason.INVALID_UTF8),
            Triple("surrogate-utf8", byteArrayOf(34, 0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 34), TypedComparison01.Reason.INVALID_UTF8),
            Triple("high-surrogate", bytes("\"\\ud800\""), TypedComparison01.Reason.INVALID_UNICODE),
            Triple("low-surrogate-key", bytes("{\"\\udfff\":0}"), TypedComparison01.Reason.INVALID_UNICODE),
            Triple("duplicate-key", bytes("{\"a\":1,\"a\":2}"), TypedComparison01.Reason.DUPLICATE_KEY),
            Triple("decoded-duplicate-key", bytes("{\"a\":1,\"\\u0061\":2}"), TypedComparison01.Reason.DUPLICATE_KEY),
            Triple("nan", bytes("NaN"), TypedComparison01.Reason.NON_FINITE),
            Triple("infinity", bytes("Infinity"), TypedComparison01.Reason.NON_FINITE),
            Triple("negative-infinity", bytes("-Infinity"), TypedComparison01.Reason.NON_FINITE),
            Triple("nested-nan", bytes("[0,NaN]"), TypedComparison01.Reason.NON_FINITE),
            Triple("plus", bytes("+1"), TypedComparison01.Reason.INVALID_JSON),
            Triple("short-infinity", bytes("INF"), TypedComparison01.Reason.INVALID_JSON),
            Triple("plus-infinity", bytes("+Infinity"), TypedComparison01.Reason.INVALID_JSON),
            Triple("missing-value", bytes("[,0]"), TypedComparison01.Reason.INVALID_JSON),
            Triple("single-quote", bytes("'value'"), TypedComparison01.Reason.INVALID_JSON),
            Triple("comment", bytes("/* text */0"), TypedComparison01.Reason.INVALID_JSON),
            Triple("unquoted-key", bytes("{a:0}"), TypedComparison01.Reason.INVALID_JSON),
            Triple("trailing-point", bytes("1."), TypedComparison01.Reason.INVALID_JSON),
        )
        return malformed.flatMap { (name, raw, reason) ->
            listOf(false, true).map { right ->
                DynamicTest.dynamicTest("malformed:" + name + ":" + (if (right) "right" else "left")) {
                    if (right) rejected(reason, null, raw) else rejected(reason, raw, null)
                }
            }
        }
    }

    @Test
    fun allProvidedMembersPrecedeUnknownEmptyOrKnownMatchResults() {
        data class Bad(val operator: String, val left: String, val right: String, val reason: TypedComparison01.Reason)
        val cases = listOf(
            Bad("eq", "[]", "[\"\\ud800\"]", TypedComparison01.Reason.INVALID_UNICODE),
            Bad("contains", "[1,\"\\ud800\"]", "1", TypedComparison01.Reason.INVALID_UNICODE),
            Bad("contains", "[{\"a\":1},{\"b\":NaN}]", "{\"c\":0}", TypedComparison01.Reason.NON_FINITE),
            Bad("subset_of", "[]", "[{\"a\":1,\"a\":2}]", TypedComparison01.Reason.DUPLICATE_KEY),
            Bad("intersects", "[]", "[Infinity]", TypedComparison01.Reason.NON_FINITE),
            Bad("contains", "[]", "1e1001", TypedComparison01.Reason.NUMBER_LIMIT),
            Bad("contains", "[1,{\"extra\":\"" + "x".repeat(4097) + "\"}]", "1", TypedComparison01.Reason.STRING_LIMIT),
        )
        for (case in cases) rejected(case.reason, bytes(case.left), bytes(case.right), case.operator)
    }

    @Test
    fun duplicateAdmissionWaitsForItsSuppliedChildrenButPrecedesLaterSiblings() {
        rejected(TypedComparison01.Reason.NON_FINITE, bytes("{\"a\":1,\"a\":NaN}"))
        rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes("{\"a\":1,\"a\":1e1001}"))
        rejected(TypedComparison01.Reason.DUPLICATE_KEY, bytes("[{\"a\":1,\"a\":2},NaN]"))
        rejected(TypedComparison01.Reason.DUPLICATE_KEY, bytes("{\"\\ud800\":1,\"\\ud800\":2}"))
        rejected(TypedComparison01.Reason.DUPLICATE_KEY, bytes("{\"a\":1,\"a\":2} 0"))
    }

    @Test
    fun byteCeilingIncludesOriginalWhitespaceSeparatelyForEachOperand() {
        val exact = bytes("0" + " ".repeat(65_535))
        assertEquals(TypedComparison01.Result.Compared(TypedComparison01.Outcome.TRUE),
            TypedComparison01.compare(profile, "eq", exact, bytes("0")))
        rejected(TypedComparison01.Reason.BYTE_LIMIT, exact + byteArrayOf(32))
        rejected(TypedComparison01.Reason.BYTE_LIMIT, null, exact + byteArrayOf(32))
        rejected(TypedComparison01.Reason.BYTE_LIMIT, bytes("0 "), limits = TypedComparison01.Limits(maxBytes = 1))
    }

    @Test
    fun depthIsContainerNestingAndQuotedEscapesDoNotSpendIt() {
        val exact = "[".repeat(32) + "0" + "]".repeat(32)
        outcome(TypedComparison01.Outcome.UNKNOWN, exact, exact)
        rejected(TypedComparison01.Reason.DEPTH_LIMIT, bytes("[$exact]"))
        val quoted = String(quoted("\\\"" + "[".repeat(50) + "]".repeat(50)), Charsets.UTF_8)
        outcome(TypedComparison01.Outcome.TRUE, quoted, quoted)
        rejected(TypedComparison01.Reason.DEPTH_LIMIT, bytes("{\"a\":[]}"),
            limits = TypedComparison01.Limits(maxDepth = 1))
        outcome(TypedComparison01.Outcome.UNKNOWN, "[]", "[]", limits = TypedComparison01.Limits(maxDepth = 1))
    }

    @Test
    fun nodesCountEveryKeyAndValueEvenWithoutComparableRootTypes() {
        val exact = "[" + List(9999) { "0" }.joinToString(",") + "]"
        outcome(TypedComparison01.Outcome.UNKNOWN, exact, "[]")
        rejected(TypedComparison01.Reason.NODE_LIMIT, bytes(exact.dropLast(1) + ",0]"))
        outcome(TypedComparison01.Outcome.UNKNOWN, "{\"a\":0}", "0", limits = TypedComparison01.Limits(maxNodes = 3))
        rejected(TypedComparison01.Reason.NODE_LIMIT, bytes("{\"a\":0}"),
            limits = TypedComparison01.Limits(maxNodes = 2))
    }

    @Test
    fun textBudgetCountsScalarsIncludingDecodedObjectKeys() {
        val exact = quoted("x".repeat(4096))
        assertEquals(TypedComparison01.Result.Compared(TypedComparison01.Outcome.TRUE),
            TypedComparison01.compare(profile, "eq", exact, exact))
        rejected(TypedComparison01.Reason.STRING_LIMIT, quoted("x".repeat(4097)))
        rejected(TypedComparison01.Reason.STRING_LIMIT, bytes("{\"" + "x".repeat(4097) + "\":0}"))
        outcome(TypedComparison01.Outcome.TRUE, "\"𝄞𝄞\"", "\"𝄞𝄞\"",
            limits = TypedComparison01.Limits(maxStringChars = 2))
        outcome(TypedComparison01.Outcome.TRUE, "\"\\ud834\\udd1e\"", "\"𝄞\"",
            limits = TypedComparison01.Limits(maxStringChars = 1))
        rejected(TypedComparison01.Reason.STRING_LIMIT, bytes("\"𝄞𝄞\""),
            limits = TypedComparison01.Limits(maxStringChars = 1))
        rejected(TypedComparison01.Reason.STRING_LIMIT, bytes("\"x\\ud800\""),
            limits = TypedComparison01.Limits(maxStringChars = 1))
    }

    @Test
    fun numberTokenAndCanonicalStringUseTheSameFinitePrecisionAdmission() {
        val exact = "9".repeat(128)
        outcome(TypedComparison01.Outcome.TRUE, exact, "\"$exact\"")
        rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes(exact + "9"))
        rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes("\"" + exact + "9\""))
        outcome(TypedComparison01.Outcome.TRUE, "1.00", "1", limits = TypedComparison01.Limits(maxNumberChars = 4))
        rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes("1.00"),
            limits = TypedComparison01.Limits(maxNumberChars = 3))
        outcome(TypedComparison01.Outcome.TRUE, "\"01\"", "\"01\"",
            limits = TypedComparison01.Limits(maxNumberChars = 1))
    }

    @Test
    fun exponentAndResultingScaleAreIndependentlyBoundedWithoutClamping() {
        for (raw in listOf("1e1001", "1e-1001", "0e1001", "0.1e-1000",
            "1e999999999999999999999999999999")) {
            rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes(raw))
        }
        val limits = TypedComparison01.Limits(maxAbsExponent = 0)
        outcome(TypedComparison01.Outcome.TRUE, "1e0", "1", limits = limits)
        rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes("1.0"), limits = limits)
        rejected(TypedComparison01.Reason.NUMBER_LIMIT, bytes("\"1.0\""), limits = limits)
        outcome(TypedComparison01.Outcome.TRUE, "1e1000", "10e999")
        outcome(TypedComparison01.Outcome.TRUE, "1e-1000", "0.1e-999")
    }

    @Test
    fun workChargesOriginalWhitespaceAndEscapesBeforeParsing() {
        val limits = TypedComparison01.Limits(maxWork = 32)
        outcome(TypedComparison01.Outcome.TRUE, "0", "0", limits = limits)
        rejected(TypedComparison01.Reason.WORK_LIMIT, bytes(" ".repeat(33) + "0"), limits = limits)
        rejected(TypedComparison01.Reason.WORK_LIMIT, bytes("\"" + "\\u0078".repeat(6) + "\""), limits = limits)
    }

    @Test
    fun exactWorkBoundariesPreserveIntegerDecimalAndElementDispatchKinds() {
        data class Boundary(val left: String, val right: String, val operator: String, val units: Int)
        val boundaries = listOf(
            Boundary("0", "0", "eq", 9),
            Boundary("-0", "0", "eq", 10),
            Boundary("\"a\"", "\"a\"", "eq", 13),
            Boundary("1.0", "1.0", "eq", 25),
            Boundary("1e0", "1e0", "eq", 17),
            Boundary("[1]", "1", "contains", 14),
        )
        for (case in boundaries) {
            outcome(TypedComparison01.Outcome.TRUE, case.left, case.right, case.operator,
                TypedComparison01.Limits(maxWork = case.units))
            rejected(TypedComparison01.Reason.WORK_LIMIT, bytes(case.left), bytes(case.right), case.operator,
                TypedComparison01.Limits(maxWork = case.units - 1))
        }
    }

    @Test
    fun fullCartesianWorkCannotBeBypassedByTheFirstKnownMatch() {
        val values = "[" + List(300) { "1" }.joinToString(",") + "]"
        rejected(TypedComparison01.Reason.WORK_LIMIT, bytes(values), bytes(values), "intersects")
        outcome(TypedComparison01.Outcome.UNKNOWN, values, values)
        rejected(TypedComparison01.Reason.WORK_LIMIT, bytes(values), bytes(values), "subset_of")
    }

    @Test
    fun substringWorkPrecedesKnownMatchAndEmptyNeedle() {
        rejected(TypedComparison01.Reason.WORK_LIMIT, quoted("x".repeat(1000)), quoted("x".repeat(1000)), "contains")
        rejected(TypedComparison01.Reason.WORK_LIMIT, quoted("x".repeat(20)), quoted(""), "contains",
            TypedComparison01.Limits(maxWork = 50))
        outcome(TypedComparison01.Outcome.TRUE, "\"abc\"", "\"\"", "contains")
        outcome(TypedComparison01.Outcome.TRUE, "\"𝄞\"", "\"𝄞\"", "contains",
            TypedComparison01.Limits(maxWork = 15))
        rejected(TypedComparison01.Reason.WORK_LIMIT, quoted("𝄞"), quoted("𝄞"), "contains",
            TypedComparison01.Limits(maxWork = 14))
    }

    @Test
    fun limitConstructionRejectsExpansionAndEntryRevalidatesChangedState() {
        val invalid = listOf<() -> TypedComparison01.Limits>(
            { TypedComparison01.Limits(maxBytes = 65_537) }, { TypedComparison01.Limits(maxBytes = 0) },
            { TypedComparison01.Limits(maxDepth = 33) }, { TypedComparison01.Limits(maxDepth = 0) },
            { TypedComparison01.Limits(maxNodes = 10_001) }, { TypedComparison01.Limits(maxNodes = 0) },
            { TypedComparison01.Limits(maxStringChars = 4_097) }, { TypedComparison01.Limits(maxStringChars = 0) },
            { TypedComparison01.Limits(maxNumberChars = 129) }, { TypedComparison01.Limits(maxNumberChars = 0) },
            { TypedComparison01.Limits(maxAbsExponent = 1_001) }, { TypedComparison01.Limits(maxAbsExponent = -1) },
            { TypedComparison01.Limits(maxWork = 200_001) }, { TypedComparison01.Limits(maxWork = 0) },
        )
        for (construct in invalid) assertEquals("invalid_limits",
            assertThrows(IllegalArgumentException::class.java) { construct() }.message)
        val changed = TypedComparison01.Limits()
        val field = changed.javaClass.getDeclaredField("maxBytes")
        field.isAccessible = true
        field.setInt(changed, 65_537)
        rejected(TypedComparison01.Reason.INVALID_LIMITS, bytes("0"), limits = changed)
    }

    @Test
    fun exactOrderingDoesNotPassThroughBinaryFloatOrUnsignedMachineLimits() {
        val beyondUnsigned = BigInteger.ONE.shiftLeft(100)
        outcome(TypedComparison01.Outcome.TRUE, beyondUnsigned.toString(), (beyondUnsigned + BigInteger.ONE).toString(), "lt")
        outcome(TypedComparison01.Outcome.FALSE, "1.00000000000000000000000000000000000001", "1", "eq")
        outcome(TypedComparison01.Outcome.TRUE, "1.00000000000000000000000000000000000001", "1", "gt")
        outcome(TypedComparison01.Outcome.TRUE, "1e-1000", "2e-1000", "lt")
    }

    @Test
    fun knownNullUnresolvedAndTheBooleanNumberDomainsStayDistinct() {
        outcome(TypedComparison01.Outcome.TRUE, "null", "null")
        outcome(TypedComparison01.Outcome.UNKNOWN, null, "null")
        outcome(TypedComparison01.Outcome.UNKNOWN, "null", null)
        outcome(TypedComparison01.Outcome.UNKNOWN, "false", "0")
        outcome(TypedComparison01.Outcome.UNKNOWN, "true", "\"1\"", "ne")
        outcome(TypedComparison01.Outcome.UNKNOWN, "[]", null, "subset_of")
        outcome(TypedComparison01.Outcome.TRUE, "[]", "[]", "subset_of")
    }

    @Test
    fun recursiveKeysRemainLiteralAndArraysKeepOrderWhileSetsUseMembership() {
        outcome(TypedComparison01.Outcome.FALSE, "[{\"01\":1}]", "{\"1\":1}", "contains")
        outcome(TypedComparison01.Outcome.TRUE, "[{\"b\":[\"1.00\"],\"a\":null}]", "{\"a\":null,\"b\":[1]}", "contains")
        outcome(TypedComparison01.Outcome.FALSE, "[[1,2]]", "[2,1]", "contains")
        outcome(TypedComparison01.Outcome.TRUE, "[[1],[1]]", "[[\"1.0\"]]", "subset_of")
        outcome(TypedComparison01.Outcome.UNKNOWN, "[[true]]", "[[1]]", "intersects")
        outcome(TypedComparison01.Outcome.FALSE, "[true,2]", "[1]", "subset_of")
    }

    @Test
    fun callerArraysAndLocaleDoNotBecomeRetainedOrAmbientComparisonAuthority() {
        val left = bytes("1.25")
        val right = bytes("\"1.250\"")
        val saved = TypedComparison01.compare(profile, "eq", left, right)
        left.fill(0)
        right.fill(0)
        assertEquals(TypedComparison01.Result.Compared(TypedComparison01.Outcome.TRUE), saved)
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            outcome(TypedComparison01.Outcome.TRUE, "1.25", "\"1.250\"")
            outcome(TypedComparison01.Outcome.UNKNOWN, "\"1,25\"", "1.25")
        } finally {
            Locale.setDefault(locale)
        }
    }
}
