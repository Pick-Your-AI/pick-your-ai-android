package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PreviewSnapshotAnalysisTest {
    @TempDir lateinit var root: Path
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() = tasks.removeFirst().run()
        fun all() { while (tasks.isNotEmpty()) next() }
    }
    private val worker = Queue()
    private val delivery = Queue()
    private val real = LocalSnapshotEvaluator()
    private val requests = mutableListOf<SnapshotEvaluationRequest>()
    private fun session(evaluator: SnapshotEvaluator = SnapshotEvaluator { requests += it; real.evaluate(it) },
        changed: () -> Unit = {}) = PreviewSnapshotAnalysis(worker, delivery, evaluator, changed)
    private fun saved(name: String = "first", revision: BigInteger = BigInteger.ONE, rawRevision: String? = null) =
        SnapshotEvaluationFixtures.save(root.resolve(name), "build.$name", revision, rawRevision = rawRevision)
    private fun PreviewSnapshotAnalysis.open(saved: SnapshotEvaluationFixtures.Saved) = open(saved.archive, saved.version, saved.entry)
    private fun finish() { worker.all(); delivery.all() }
    private fun current(value: PreviewSnapshotAnalysis) = value.snapshot() as PreviewSnapshotAnalysisState.Current

    @Test fun `real archived input runs on the worker and only the delivered request becomes current`() {
        val saved = saved(revision = BigInteger("9223372036854775808"))
        val session = session()
        assertTrue(session.open(saved))
        assertTrue(session.snapshot() is PreviewSnapshotAnalysisState.Loading)
        assertTrue(requests.isEmpty())
        worker.next()
        assertTrue(session.snapshot() is PreviewSnapshotAnalysisState.Evaluating)
        assertEquals(1, requests.size)
        delivery.next()
        val current = current(session)
        assertEquals(saved.entry.bundleHash, current.selection.bundleHash)
        assertEquals(saved.entry.key.revision, current.result.report.inspect().buildRevision)
        assertTrue(current.result.belongsTo(requests.single()))
        assertEquals(saved.version.hash, current.selection.indexHash)
        assertFalse(session.cancel())
    }

    @Test fun `cancel before queued read prevents evaluation and late work cannot restore state`() {
        val saved = saved()
        val session = session()
        session.open(saved)
        assertTrue(session.cancel())
        finish()
        assertTrue(requests.isEmpty())
        assertTrue(session.snapshot() is PreviewSnapshotAnalysisState.Cancelled)
        session.open(saved); finish()
        assertEquals(saved.entry.key, current(session).selection.key)
    }

    @Test fun `cancel after computation but before delivery rejects the completed report`() {
        val saved = saved()
        val session = session()
        session.open(saved); worker.next()
        assertTrue(session.cancel())
        delivery.all()
        assertTrue(session.snapshot() is PreviewSnapshotAnalysisState.Cancelled)
        session.open(saved); finish()
        assertTrue(current(session).result.belongsTo(requests.last()))
        assertFalse(current(session).result.belongsTo(requests.first()))
    }

    @Test fun `switching snapshots prevents an older delivered result from replacing the selected identity`() {
        val first = saved()
        val second = saved("second")
        val session = session()
        session.open(first); worker.next()
        session.open(second)
        delivery.next()
        assertEquals(second.entry.key, (session.snapshot() as PreviewSnapshotAnalysisState.Loading).selection.key)
        finish()
        assertEquals(second.entry.key, current(session).selection.key)
        assertEquals(second.entry.key.id, current(session).result.report.inspect().buildId)
    }

    @Test fun `same entry retry and ABA selection require new opaque request authority`() {
        val first = saved()
        val second = saved("second")
        val session = session()
        session.open(first); worker.next()
        session.open(second); session.open(first); worker.all()
        assertEquals(2, requests.size)
        assertEquals(requests.first().digest, requests.last().digest)
        assertNotSame(requests.first(), requests.last())
        val old = delivery.tasks.removeFirst()
        delivery.all()
        assertTrue(current(session).result.belongsTo(requests.last()))
        old.run()
        assertTrue(current(session).result.belongsTo(requests.last()))
    }

    @Test fun `a real result for another Build or revision is rejected by the active delivery boundary`() {
        val selected = saved()
        for (other in listOf(saved("foreign"), SnapshotEvaluationFixtures.save(root.resolve("revision"), selected.entry.key.id, BigInteger.TWO))) {
            val wrong = real.evaluate(SnapshotEvaluationFixtures.prepared(other.snapshot))
            val session = session(SnapshotEvaluator { wrong })
            session.open(selected); finish()
            assertEquals(PreviewSnapshotFailure.IDENTITY_MISMATCH, (session.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
        }
        val fresh = session(); fresh.open(selected); finish()
        assertEquals(selected.entry.key.id, current(fresh).result.report.inspect().buildId)
        assertEquals(selected.entry.key.revision, current(fresh).result.report.inspect().buildRevision)
    }

    @Test fun `a result from an equivalent earlier request cannot complete a newer attempt`() {
        val saved = saved()
        val oldRequest = SnapshotEvaluationFixtures.prepared(saved.snapshot)
        val oldResult = real.evaluate(oldRequest)
        val session = session(SnapshotEvaluator { request ->
            assertEquals(oldRequest.digest, request.digest)
            assertNotSame(oldRequest, request)
            oldResult
        })
        session.open(saved); finish()
        assertEquals(PreviewSnapshotFailure.IDENTITY_MISMATCH, (session.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
    }

    @Test fun `source deletion does not revoke an independently retained historical read`() {
        val saved = saved()
        Files.walk(saved.sourcePath).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        val session = session()
        session.open(saved); finish()
        assertEquals(saved.entry.key, current(session).selection.key)
        assertEquals(saved.entry.bundleHash, current(session).selection.bundleHash)
        assertFalse(Files.exists(saved.sourcePath))
    }

    @Test fun `captured old index permits historical inspection without repairing missing current index`() {
        val saved = saved()
        SnapshotEvaluationFixtures.save(root.resolve("later"), "build.later", retainedArchive = saved.archive)
        val pointer = root.resolve("first/archive/indices/active")
        Files.delete(pointer)
        assertTrue(saved.archive.read(saved.entry.key) is SnapshotArchiveRead.Failed)
        val session = session()
        session.open(saved); finish()
        assertEquals(saved.version.hash, current(session).selection.indexHash)
        assertEquals(saved.version.generation, current(session).selection.indexGeneration)
        assertFalse(Files.exists(pointer))
        assertTrue(saved.archive.read(saved.entry.key) is SnapshotArchiveRead.Failed)
    }

    @Test fun `foreign archive receipt and missing retained bytes never select a replacement`() {
        val saved = saved()
        val other = saved("other")
        val session = session()
        session.open(other.archive, saved.version, saved.entry); finish()
        assertEquals(PreviewSnapshotFailure.ARCHIVE_UNAVAILABLE, (session.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
        Files.delete(root.resolve("first/archive/bundles/${saved.entry.bundleHash.hex}.bin"))
        session.open(saved); finish()
        assertEquals(PreviewSnapshotFailure.ARCHIVE_UNAVAILABLE, (session.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
        assertTrue(requests.isEmpty())
        session.open(other); finish()
        assertEquals(other.entry.key, current(session).selection.key)
    }

    @Test fun `unsupported numeric profile produces a recoverable explicit failure`() {
        val unsupported = saved(rawRevision = "1e0")
        val session = session()
        session.open(unsupported); finish()
        val failed = session.snapshot() as PreviewSnapshotAnalysisState.Failed
        assertEquals(SnapshotEvaluationFailure.UNSUPPORTED_OPERATION, failed.evaluation!!.reason)
        session.open(saved("supported")); finish()
        assertTrue(current(session).result.report.inspect().findings.isNotEmpty())
    }

    @Test fun `worker and delivery rejection expose failure instead of an apparent current result`() {
        val saved = saved()
        val rejected = Executor { throw RejectedExecutionException("private transport text") }
        val neverStarted = PreviewSnapshotAnalysis(rejected, delivery)
        neverStarted.open(saved); delivery.all()
        assertEquals(PreviewSnapshotFailure.DISPATCH_UNAVAILABLE, (neverStarted.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
        val neverDelivered = PreviewSnapshotAnalysis(worker, rejected)
        neverDelivered.open(saved); worker.all()
        assertEquals(PreviewSnapshotFailure.DISPATCH_UNAVAILABLE, (neverDelivered.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
        assertFalse(neverDelivered.snapshot().toString().contains("private transport text"))
    }

    @Test fun `evaluator and observer exceptions cannot manufacture a report or strand a completed attempt`() {
        val saved = saved()
        val failed = session(SnapshotEvaluator { throw IllegalStateException("private failure") })
        failed.open(saved); finish()
        assertEquals(PreviewSnapshotFailure.EVALUATOR_UNAVAILABLE, (failed.snapshot() as PreviewSnapshotAnalysisState.Failed).reason)
        val observer = session(changed = { throw IllegalStateException("observer") })
        observer.open(saved); finish()
        assertEquals(saved.entry.key, current(observer).selection.key)
    }

    @Test fun `close retires both queued phases and cannot be reopened by an old or fresh caller`() {
        val saved = saved()
        val queued = session()
        queued.open(saved); queued.close(); finish()
        assertEquals(PreviewSnapshotAnalysisState.Closed, queued.snapshot())
        assertTrue(requests.isEmpty()); assertFalse(queued.open(saved))
        val completed = session()
        completed.open(saved); worker.next(); completed.close(); delivery.all()
        assertEquals(PreviewSnapshotAnalysisState.Closed, completed.snapshot())
        assertFalse(completed.open(saved))
    }
}
