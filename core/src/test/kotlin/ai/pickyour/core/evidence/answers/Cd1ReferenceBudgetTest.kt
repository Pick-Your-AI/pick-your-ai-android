package ai.pickyour.core.evidence.answers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Cd1ReferenceBudgetTest {
    @Test
    fun repeatedLocalReferencesRejectBeforeThePredicateRuns() {
        // A compact schema generates 8,191 local-reference applications.
        // This is below the original byte/depth limits, but above the local work budget.
        val raw = branchingSchema(12)
        val original = raw.copyOf()
        val field = KnownAnswerSchema.read(raw)
        raw.fill(0)
        val answers = provided("1")
        val answerBytes = answers.originalBytes
        var calls = 0
        repeat(2) {
            val failure = assertThrows(Cd1Rejected::class.java) {
                answers.predicate("/answer", field) { calls++; true }
            }
            assertEquals(Cd1Problem.INVALID_FIELD_SCHEMA, failure.problem)
            assertEquals(failure.problem.name, failure.message)
            assertNull(failure.cause)
        }
        assertEquals(0, calls)
        assertArrayEquals(original, field.originalBytes)
        assertEquals(sha(original), field.originalSHA256)
        assertArrayEquals(answerBytes, answers.originalBytes)
    }

    @Test
    fun theReferenceApplicationBoundaryIncludesRepeatedCachedTargets() {
        // A depth-eleven binary reference tree uses 4,095 references. Adding
        // one/two root siblings makes exactly 4,096/4,097 actual applications.
        val answers = provided("1")
        val atLimit = KnownAnswerSchema.read(branchingSchema(11, extraLeaves = 1))
        assertEquals(1, answers.read("/answer", atLimit).value!!.intValue())
        val overLimit = KnownAnswerSchema.read(branchingSchema(11, extraLeaves = 2))
        assertEquals(Cd1Problem.INVALID_FIELD_SCHEMA,
            assertThrows(Cd1Rejected::class.java) { answers.read("/answer", overLimit) }.problem)
    }

    @Test
    fun repeatedReadsUseFreshBudgetsAfterSuccessAndMismatch() {
        val field = KnownAnswerSchema.read(branchingSchema(11, extraLeaves = 1))
        val good = provided("1")
        repeat(3) { assertEquals(1, good.read("/answer", field).value!!.intValue()) }
        assertEquals(Cd1Problem.VALUE_SCHEMA_MISMATCH,
            assertThrows(Cd1Rejected::class.java) { provided("\"wrong type\"").read("/answer", field) }.problem)
        assertEquals(1, good.read("/answer", field).value!!.intValue())
    }

    @Test
    fun concurrentReadsOfOneCompiledFieldDoNotShareABudget() {
        val field = KnownAnswerSchema.read(branchingSchema(11, extraLeaves = 1))
        val answers = provided("1")
        assertEquals(1, answers.read("/answer", field).value!!.intValue())
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reads = (1..2).map {
                pool.submit<Int> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(2) { assertEquals(1, answers.read("/answer", field).value!!.intValue()) }
                    1
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            reads.forEach { assertEquals(1, it.get(10, TimeUnit.SECONDS)) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun unresolvedAndAbsentValuesDoNotCompileAnOverBudgetField() {
        val field = KnownAnswerSchema.read(branchingSchema(12))
        for (state in Cd1State.entries.filter { it != Cd1State.PROVIDED }) {
            val answers = index("""{"state":"${state.encoded}"}""")
            val result = answers.predicate("/answer", field) { error("Unresolved predicate ran") }
            assertEquals(state, result.state)
            assertNull(result.truth)
        }
        assertEquals(Cd1State.MISSING, provided("{}").read("/answer/absent", field).state)
        assertEquals(Cd1State.MISSING, provided("1").read("/unrecorded", field).state)
    }

    @Test
    fun progressingRecursiveSchemasRetainTheirValueAndMarkerSemantics() {
        val field = KnownAnswerSchema.read("""{
            "${'$'}defs":{"node":{"anyOf":[{"type":"null"},{"type":"object",
              "required":["next"],"properties":{"next":{"${'$'}ref":"#/${'$'}defs/node"}},
              "additionalProperties":false}]}},"${'$'}ref":"#/${'$'}defs/node"
        }""".toByteArray())
        val value = (1..8).fold("null") { child, _ -> """{"next":$child}""" }
        val answers = provided(value)
        val read = answers.read("/answer", field)
        var current = read.value!!
        repeat(8) { assertTrue(current.isObject); current = current["next"] }
        assertTrue(current.isNull)
        assertEquals(Cd1Problem.VALUE_SCHEMA_MISMATCH,
            assertThrows(Cd1Rejected::class.java) { provided("\"unknown\"").read("/answer", field) }.problem)
        assertEquals(Cd1State.PROVIDED, answers.read("/answer", field).state)
    }

    @Test
    fun nonProgressingRecursionStillRejectsWithTheExistingTypedBoundary() {
        val failure = assertThrows(Cd1Rejected::class.java) {
            provided("1").read("/answer", KnownAnswerSchema.read("""{"${'$'}ref":"#"}""".toByteArray()))
        }
        assertEquals(Cd1Problem.INVALID_FIELD_SCHEMA, failure.problem)
        assertNull(failure.cause)
        assertEquals(1, provided("1").read("/answer", KnownAnswerSchema.read("true".toByteArray())).value!!.intValue())
    }

    private fun branchingSchema(depth: Int, extraLeaves: Int = 0): ByteArray = buildString {
        append("""{"${'$'}defs":{"n0":{"type":"integer"}""")
        for (n in 1..depth) {
            append(""", "n$n":{"allOf":[{"${'$'}ref":"#/${'$'}defs/n${n - 1}"},{"${'$'}ref":"#/${'$'}defs/n${n - 1}"}]}""")
        }
        append("""},"allOf":[{"${'$'}ref":"#/${'$'}defs/n$depth"}""")
        repeat(extraLeaves) { append(""",{"${'$'}ref":"#/${'$'}defs/n0"}""") }
        append("]}")
    }.toByteArray()

    private fun provided(value: String): Cd1AnswerIndex = index("""{"state":"provided","value":$value,"assumption":false}""")
    private fun index(record: String): Cd1AnswerIndex = Cd1AnswerIndex.read(
        """{"x_t0_answer_states":{"proposal_version":"cd1-0.2","answers":{"/answer":$record}}}""".toByteArray())
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
