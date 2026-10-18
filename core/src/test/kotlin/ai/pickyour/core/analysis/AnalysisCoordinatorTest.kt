package ai.pickyour.core.analysis

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AnalysisCoordinatorTest {
    private val identity = AnalysisIdentity("draft", 4, "catalog", "graph", "rules", "prices")

    @Test
    fun `out of order success and failure cannot replace newest report`() {
        val coordinator = AnalysisCoordinator<String>()
        val old = coordinator.start(identity)
        val newest = coordinator.start(identity.copy(revision = 5))
        assertTrue(coordinator.succeed(newest, "new report"))
        assertFalse(coordinator.succeed(old, "old report"))
        assertFalse(coordinator.fail(old, AnalysisFailure.OFFLINE))
        assertEquals(AnalysisState.Succeeded(newest, "new report"), coordinator.snapshot())
    }

    @Test
    fun `same identity retry and duplicate callback cannot win`() {
        val coordinator = AnalysisCoordinator<String>()
        val old = coordinator.start(identity)
        val retry = coordinator.start(identity)
        assertTrue(retry.sequence > old.sequence)
        assertFalse(coordinator.cancel(old))
        assertFalse(coordinator.succeed(old, "old"))
        assertTrue(coordinator.fail(retry, AnalysisFailure.TIMED_OUT))
        assertFalse(coordinator.succeed(retry, "duplicate"))
        assertEquals(AnalysisState.Failed(retry, AnalysisFailure.TIMED_OUT), coordinator.snapshot())
    }

    @Test
    fun `cancellation and clear reject pending callbacks`() {
        val coordinator = AnalysisCoordinator<String>()
        val ticket = coordinator.start(identity)
        assertTrue(coordinator.cancel(ticket))
        assertFalse(coordinator.fail(ticket, AnalysisFailure.OFFLINE))
        assertFalse(coordinator.succeed(ticket, "late"))
        val next = coordinator.start(identity)
        coordinator.clear()
        assertFalse(coordinator.succeed(next, "late"))
        assertEquals(AnalysisState.Idle(null), coordinator.snapshot())
    }

    @Test
    fun `every binding input invalidates even when revision stays the same`() {
        val variants = listOf(
            identity.copy(draftId = "other"), identity.copy(revision = 5),
            identity.copy(catalog = "v2"), identity.copy(graph = "v2"),
            identity.copy(ruleset = "v2"), identity.copy(pricebook = "v2"),
            identity.copy(costEvidence = "cost-v1"), identity.copy(resourceEvidence = "estimate-v1"),
        )
        for (changed in variants) {
            val coordinator = AnalysisCoordinator<String>()
            val old = coordinator.start(identity)
            assertFalse(coordinator.bind(identity))
            assertTrue(coordinator.bind(changed))
            assertFalse(coordinator.succeed(old, "stale"))
            assertEquals(AnalysisState.Idle(changed), coordinator.snapshot())
            coordinator.bind(identity) // ABA must not reactivate the old ticket.
            assertFalse(coordinator.succeed(old, "stale"))
        }
    }

    @Test
    fun `capacity evidence replacement rejects earlier estimator result`() {
        val coordinator = AnalysisCoordinator<String>()
        val unknown = coordinator.start(identity)
        val first = coordinator.start(identity.copy(resourceEvidence = "estimator-v1"))
        val second = coordinator.start(identity.copy(resourceEvidence = "estimator-v2"))
        assertTrue(coordinator.succeed(second, "latest estimate"))
        assertFalse(coordinator.succeed(first, "previous estimate"))
        assertFalse(coordinator.succeed(unknown, "unknown"))
    }

    @Test
    fun `tickets from different coordinators cannot complete requests`() {
        val one = AnalysisCoordinator<String>()
        val two = AnalysisCoordinator<String>()
        val foreign = one.start(identity)
        val own = two.start(identity)
        assertEquals(foreign.sequence, own.sequence)
        assertFalse(two.succeed(foreign, "foreign"))
        assertTrue(two.succeed(own, "own"))
    }

    @Test
    fun `concurrent terminal callbacks have exactly one winner`() {
        val coordinator = AnalysisCoordinator<String>()
        val ticket = coordinator.start(identity)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val success = executor.submit<Boolean> { start.await(); coordinator.succeed(ticket, "result") }
            val failure = executor.submit<Boolean> { start.await(); coordinator.fail(ticket, AnalysisFailure.OFFLINE) }
            start.countDown()
            assertNotEquals(success.get(5, TimeUnit.SECONDS), failure.get(5, TimeUnit.SECONDS))
            assertFalse(coordinator.cancel(ticket))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalid client identity rejected before starting work`() {
        assertThrows(IllegalArgumentException::class.java) { identity.copy(revision = -1) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(catalog = " ") }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(costEvidence = "") }
    }
}
