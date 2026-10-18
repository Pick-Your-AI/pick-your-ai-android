package ai.pickyour.core.preview

import ai.pickyour.core.analysis.AnalysisFailure
import ai.pickyour.core.cache.CacheWritePoint
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.drafts.DraftWorkload
import ai.pickyour.core.drafts.LocalDraftStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PreviewDraftAnalysisTest {
    @TempDir lateinit var root: Path
    private val inputs = PreviewAnalysisInputs("catalog-v1:hash", "graph-v1:hash", "rules-v1:hash", "prices-v1:hash")
    private fun pending(title: String) = PreviewPendingDraft(PreviewDraftFields(title),
        PreviewDraftContext(null, ""), DraftWorkload())
    private fun harness(name: String = "draft") = Harness(Files.createDirectory(root.resolve(name)))

    private class Queue : Executor {
        val tasks = mutableListOf<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.add(command)
        }
        fun run(index: Int = 0) { tasks.removeAt(index).run() }
    }
    private class Call(val request: PreviewAnalysisRequest, val complete: (PreviewAnalysisCompletion<String>) -> Unit) {
        var cancellations = 0
        fun succeed(value: String, identity: PreviewAnalysisIdentity = request.identity) =
            complete(PreviewAnalysisCompletion.Succeeded(identity, value))
        fun fail(reason: AnalysisFailure) = complete(PreviewAnalysisCompletion.Failed(request.identity, reason))
    }
    private class Harness(val directory: Path, overridePersistence: PreviewDraftPersistence? = null) {
        var fault: CacheWritePoint? = null
        val persistence = overridePersistence ?: PreviewDraftPersistence(LocalDraftStore(directory) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { if (it == fault) throw IOException("injected") }
        })
        val worker = Queue()
        val delivery = Queue()
        val calls = mutableListOf<Call>()
        val session = PreviewDraftAnalysis(persistence, worker, delivery, PreviewAnalysisEvaluator<String> { request, complete ->
            val call = Call(request, complete).also(calls::add)
            PreviewAnalysisCancellation { call.cancellations++ }
        })
        fun seed(title: String = "saved"): PreviewStoredDraft =
            (persistence.save(null, PreviewDraftFields(title)) as PreviewDraftSave.Saved).record
        fun open(): PreviewAnalysisSaved {
            assertTrue(session.load())
            worker.run(); delivery.run()
            return binding()
        }
        fun binding() = (session.snapshot().storage as PreviewAnalysisStorage.Saved).binding
        fun analyze(binding: PreviewAnalysisSaved, inputs: PreviewAnalysisInputs): Call {
            assertTrue(session.setQualifiedInputs(binding, inputs))
            worker.run()
            return calls.last()
        }
        fun current(): PreviewAnalysisReport<String> =
            (session.snapshot().evaluation as PreviewAnalysisEvaluation.Current).report
        fun loaded() = (persistence.load() as PreviewDraftLoad.Available).record
        fun bytes(): Map<String, String> = Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile).sorted().toList().associate { path ->
                directory.relativize(path).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
    }

    @Test fun `required save must actually finish and acknowledge before inputs can start evaluation`() {
        val h = harness()
        assertFalse(h.session.save(null, pending("unopened")))
        assertTrue(h.session.load()); h.worker.run(); h.delivery.run()
        assertEquals(PreviewAnalysisStorage.Missing, h.session.snapshot().storage)
        assertTrue(h.session.save(null, pending("first")))
        assertInstanceOf(PreviewAnalysisStorage.Saving::class.java, h.session.snapshot().storage)
        assertEquals(PreviewDraftLoad.Missing, h.persistence.load())
        assertTrue(h.calls.isEmpty())
        h.worker.run() // The actual store is now durable, but its UI acknowledgement is still delayed.
        val disk = h.loaded()
        assertEquals("first", disk.fields.title)
        assertEquals(PreviewDraftContext(null, ""), disk.contextInput)
        assertFalse(h.session.setQualifiedInputs(PreviewAnalysisSaved(disk), inputs))
        assertTrue(h.calls.isEmpty())
        h.delivery.run()
        val binding = h.binding()
        val call = h.analyze(binding, inputs)
        assertEquals(disk.baselineIdentity, call.request.saved.record.baselineIdentity)
        assertEquals(disk.revision, call.request.identity.revision)
        assertEquals(disk.draft.version.hash.hex, call.request.identity.sourceHash)
        assertNull(call.request.identity.inputs.resourceEvidence)
        call.succeed("unknown"); h.delivery.run()
        assertEquals("unknown", h.current().value)
        assertNull(h.session.snapshot().stale)
    }

    @Test fun `capacity arrives and is replaced at the same revision with reverse results and unchanged history bytes`() {
        val h = harness(); h.seed()
        val binding = h.open()
        val before = h.bytes()
        val unknown = h.analyze(binding, inputs)
        unknown.succeed("unknown"); h.delivery.run()
        val historical = h.current()
        val first = h.analyze(binding, inputs.copy(resourceEvidence = "estimator-v1:artifact-a"))
        assertSame(historical, h.session.snapshot().stale)
        val second = h.analyze(binding, inputs.copy(resourceEvidence = "estimator-v2:artifact-b"))
        assertEquals(1, first.cancellations)
        assertEquals(binding.record.revision, first.request.identity.revision)
        assertEquals(first.request.identity.revision, second.request.identity.revision)
        second.succeed("new evidence, limitations retained"); h.delivery.run()
        first.succeed("old capacity"); unknown.succeed("late unknown")
        h.delivery.run(); h.delivery.run()
        assertEquals("new evidence, limitations retained", h.current().value)
        assertEquals("unknown", historical.value)
        assertEquals(before, h.bytes())
    }

    @ParameterizedTest
    @ValueSource(strings = ["catalog", "graph", "ruleset", "pricebook", "cost", "resource"])
    fun `each complete input change cancels and rejects old or mismatched responses`(field: String) {
        val h = harness(); h.seed()
        val binding = h.open()
        val old = h.analyze(binding, inputs)
        val changed = when (field) {
            "catalog" -> inputs.copy(catalog = "catalog-v2:hash")
            "graph" -> inputs.copy(graph = "graph-v2:hash")
            "ruleset" -> inputs.copy(ruleset = "rules-v2:hash")
            "pricebook" -> inputs.copy(pricebook = "prices-v2:hash")
            "cost" -> inputs.copy(costEvidence = "cost-v2:hash")
            else -> inputs.copy(resourceEvidence = "capacity-v2:hash")
        }
        val latest = h.analyze(binding, changed)
        assertEquals(1, old.cancellations)
        assertEquals(old.request.identity.revision, latest.request.identity.revision)
        old.succeed("old")
        latest.succeed("wrong full identity", old.request.identity)
        h.delivery.run(); h.delivery.run()
        assertInstanceOf(PreviewAnalysisEvaluation.Pending::class.java, h.session.snapshot().evaluation)
        latest.succeed("latest"); h.delivery.run()
        assertEquals("latest", h.current().value)
        assertEquals(changed, h.current().identity.inputs)
    }

    @Test fun `withdrawn qualification and an ABA evidence replacement cannot revive an old ticket`() {
        val h = harness(); h.seed()
        val binding = h.open()
        val a = h.analyze(binding, inputs)
        assertTrue(h.session.setQualifiedInputs(binding, null))
        assertEquals(1, a.cancellations)
        assertEquals(PreviewAnalysisEvaluation.AwaitingInputs, h.session.snapshot().evaluation)
        a.succeed("withdrawn"); h.delivery.run()
        assertEquals(PreviewAnalysisEvaluation.AwaitingInputs, h.session.snapshot().evaluation)
        val again = h.analyze(binding, inputs)
        a.succeed("ABA"); h.delivery.run()
        assertInstanceOf(PreviewAnalysisEvaluation.Pending::class.java, h.session.snapshot().evaluation)
        again.succeed("qualified again"); h.delivery.run()
        assertEquals("qualified again", h.current().value)
    }

    @Test fun `same input event is idempotent while explicit retry gets a fresh attempt`() {
        val h = harness(); h.seed()
        val binding = h.open()
        val old = h.analyze(binding, inputs)
        assertFalse(h.session.setQualifiedInputs(binding, inputs.copy()))
        assertEquals(1, h.calls.size)
        assertTrue(h.worker.tasks.isEmpty())
        assertTrue(h.session.retry(binding)); h.worker.run()
        val latest = h.calls.last()
        assertNotSame(old.request, latest.request)
        assertEquals(old.request.identity, latest.request.identity)
        assertEquals(1, old.cancellations)
        old.fail(AnalysisFailure.TIMED_OUT); h.delivery.run()
        assertInstanceOf(PreviewAnalysisEvaluation.Pending::class.java, h.session.snapshot().evaluation)
        latest.fail(AnalysisFailure.OFFLINE); h.delivery.run()
        assertEquals(AnalysisFailure.OFFLINE, (h.session.snapshot().evaluation as PreviewAnalysisEvaluation.Failed).reason)
    }

    @Test fun `reverse actual save acknowledgements cannot turn a newer conflict into an old saved success`() {
        val h = harness(); h.seed()
        val binding = h.open()
        h.analyze(binding, inputs).succeed("retained report"); h.delivery.run()
        val retained = h.current()
        val first = pending("first edit")
        val second = pending("newest pending edit")
        assertTrue(h.session.save(binding, first)); h.worker.run()
        assertEquals("first edit", h.loaded().fields.title)
        assertTrue(h.session.save(binding, second)); h.worker.run()
        h.delivery.run(1) // Newest save failed its real CAS; its original expected token was not replaced.
        assertEquals(PreviewAnalysisStorage.Conflict, h.session.snapshot().storage)
        h.delivery.run() // Older Saved is delivered last and must not regain authority.
        assertEquals(PreviewAnalysisStorage.Conflict, h.session.snapshot().storage)
        assertSame(retained, h.session.snapshot().stale)
        assertEquals("newest pending edit", second.fields.title)
        assertFalse(h.session.setQualifiedInputs(binding, inputs.copy(resourceEvidence = "late")))
        assertFalse(h.session.save(binding, second))
        assertEquals(1, h.calls.size)
        val reopened = h.open()
        assertEquals("first edit", reopened.record.fields.title)
        assertNotSame(binding, reopened)
    }

    @Test fun `an old load acknowledgement cannot overwrite a newer acknowledged save`() {
        val h = harness(); h.seed()
        assertTrue(h.session.load()); h.worker.run()
        assertTrue(h.session.load()); h.worker.run()
        h.delivery.run(1)
        val binding = h.binding()
        assertTrue(h.session.save(binding, pending("new"))); h.worker.run()
        h.delivery.run(1)
        val newest = h.binding()
        h.delivery.run()
        assertSame(newest, h.binding())
        assertEquals("new", newest.record.fields.title)
        assertFalse(h.session.setQualifiedInputs(binding, inputs))
        assertTrue(h.calls.isEmpty())
    }

    @Test fun `unchanged canonical save reuses revision but revokes old acknowledgement and evidence authority`() {
        val h = harness(); h.seed()
        val first = h.open()
        val old = h.analyze(first, inputs)
        val same = PreviewPendingDraft(first.record.fields, first.record.contextInput, first.record.workloadInput)
        assertTrue(h.session.save(first, same))
        assertEquals(1, old.cancellations)
        h.worker.run(); h.delivery.run()
        val second = h.binding()
        assertEquals(first.record.baselineIdentity, second.record.baselineIdentity)
        assertEquals(first.record.history.size, second.record.history.size)
        assertNotSame(first, second)
        assertFalse(h.session.setQualifiedInputs(first, inputs.copy(resourceEvidence = "late capacity")))
        old.succeed("late saved report"); h.delivery.run()
        assertEquals(PreviewAnalysisEvaluation.AwaitingInputs, h.session.snapshot().evaluation)
        h.analyze(second, inputs).succeed("new attempt"); h.delivery.run()
        assertEquals("new attempt", h.current().value)
    }

    @Test fun `identical revisions and bytes from another local store never share binding or response authority`() {
        val oneRoot = Files.createDirectory(root.resolve("one"))
        val twoRoot = Files.createDirectory(root.resolve("two"))
        val one = Harness(Files.createDirectory(oneRoot.resolve("guest-draft")))
        val two = Harness(Files.createDirectory(twoRoot.resolve("guest-draft")))
        one.seed(); two.seed()
        val first = one.open(); val second = two.open()
        assertEquals(first.record.baselineIdentity, second.record.baselineIdentity)
        assertFalse(two.session.setQualifiedInputs(first, inputs))
        assertFalse(two.session.save(first, pending("foreign")))
        val foreign = one.analyze(first, inputs)
        val own = two.analyze(second, inputs)
        assertNotEquals(foreign.request.identity, own.request.identity)
        own.succeed("foreign", foreign.request.identity); two.delivery.run()
        assertInstanceOf(PreviewAnalysisEvaluation.Pending::class.java, two.session.snapshot().evaluation)
        own.succeed("own"); two.delivery.run()
        assertEquals("own", two.current().value)
        assertEquals("PreviewAnalysisIdentity", own.request.identity.toString())
    }

    @Test fun `another writer before dispatch forces reopen instead of a stale current-head evaluation`() {
        val h = harness(); h.seed()
        val binding = h.open()
        val written = h.persistence.save(binding.record, PreviewDraftFields("another writer")) as PreviewDraftSave.Saved
        assertTrue(h.session.setQualifiedInputs(binding, inputs))
        h.worker.run(); h.delivery.run()
        assertEquals(PreviewAnalysisStorage.Conflict, h.session.snapshot().storage)
        assertTrue(h.calls.isEmpty())
        assertFalse(h.session.setQualifiedInputs(binding, inputs.copy(costEvidence = "late cost")))
        val reopened = h.open()
        assertEquals(written.record.baselineIdentity, reopened.record.baselineIdentity)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POINTER_STAGED", "POINTER_ACTIVATED"])
    fun `failed or uncertain actual save never exposes a current evaluation and preserves prior report`(point: String) {
        val h = harness(); h.seed()
        val binding = h.open()
        h.analyze(binding, inputs).succeed("historical unknown"); h.delivery.run()
        val previous = h.current()
        val before = binding.record.baselineIdentity
        h.fault = CacheWritePoint.valueOf(point)
        assertTrue(h.session.save(binding, pending("attempt"))); h.worker.run(); h.delivery.run()
        if (point == "POINTER_STAGED") {
            assertEquals(PreviewAnalysisStorage.Failed(DraftStorageFailure.IO_FAILURE), h.session.snapshot().storage)
            assertEquals(before, h.loaded().baselineIdentity)
        } else {
            assertEquals(PreviewAnalysisStorage.Uncertain, h.session.snapshot().storage)
            assertNotEquals(before, h.loaded().baselineIdentity)
        }
        assertSame(previous, h.session.snapshot().stale)
        assertEquals(PreviewAnalysisEvaluation.AwaitingInputs, h.session.snapshot().evaluation)
        assertFalse(h.session.setQualifiedInputs(binding, inputs.copy(resourceEvidence = "arrived while saving")))
        assertFalse(h.session.retry(binding))
        assertEquals(1, h.calls.size)
        h.fault = null
        val fresh = h.open()
        h.analyze(fresh, inputs).succeed("reopened"); h.delivery.run()
        assertEquals("reopened", h.current().value)
        assertEquals("historical unknown", previous.value)
    }

    @Test fun `cancel reaches transport and rejects delayed completion while retry remains possible`() {
        val h = harness(); h.seed()
        val binding = h.open()
        val old = h.analyze(binding, inputs)
        assertTrue(h.session.cancel())
        assertEquals(1, old.cancellations)
        assertFalse(h.session.cancel())
        old.succeed("cancelled"); h.delivery.run()
        assertInstanceOf(PreviewAnalysisEvaluation.Cancelled::class.java, h.session.snapshot().evaluation)
        assertTrue(h.session.retry(binding)); h.worker.run()
        h.calls.last().succeed("retried"); h.delivery.run()
        assertEquals("retried", h.current().value)
    }

    @Test fun `closing rejects a delayed saved acknowledgement but never rolls back the actual write`() {
        val h = harness(); h.seed()
        val binding = h.open()
        assertTrue(h.session.save(binding, pending("durable before close"))); h.worker.run()
        h.session.close()
        h.delivery.run()
        assertEquals(PreviewAnalysisStorage.Closed, h.session.snapshot().storage)
        assertEquals("durable before close", h.loaded().fields.title)
        assertFalse(h.session.load())
        assertFalse(h.session.save(binding, pending("closed")))
        assertFalse(h.session.setQualifiedInputs(binding, inputs))
        assertTrue(h.calls.isEmpty())
        assertNull(h.session.snapshot().stale)
        val recreated = Harness(h.directory, h.persistence)
        assertEquals("durable before close", recreated.open().record.fields.title)
    }

    @Test fun `closing queued work prevents an undispatched save from starting`() {
        val h = harness(); h.seed()
        val binding = h.open()
        assertTrue(h.session.save(binding, pending("queued")))
        h.session.close()
        h.worker.run()
        assertTrue(h.delivery.tasks.isEmpty())
        assertEquals("saved", h.loaded().fields.title)
    }

    @Test fun `delivery rejection after an actual save is uncertain while worker rejection before write is failed`() {
        val h = harness(); h.seed()
        val binding = h.open()
        h.delivery.reject = true
        assertTrue(h.session.save(binding, pending("durable"))); h.worker.run()
        assertEquals(PreviewAnalysisStorage.Uncertain, h.session.snapshot().storage)
        assertEquals("durable", h.loaded().fields.title)
        h.delivery.reject = false
        val reopened = h.open()
        h.worker.reject = true
        assertTrue(h.session.save(reopened, pending("never dispatched")))
        assertEquals(PreviewAnalysisStorage.Failed(DraftStorageFailure.IO_FAILURE), h.session.snapshot().storage)
        assertEquals("durable", h.loaded().fields.title)
        assertTrue(h.calls.isEmpty())
    }

    @Test fun `managed library deletion is checked by real persistence before evaluation dispatch`() {
        val libraryRoot = Files.createDirectory(root.resolve("library"))
        val library = PreviewDraftLibrary(libraryRoot)
        val persistence = library.openLegacy()
        val h = Harness(libraryRoot.resolve("guest-draft"), persistence)
        h.seed()
        val binding = h.open()
        assertEquals(PreviewLibraryDelete.Deleted, library.delete(PreviewDraftId("guest-draft"), binding.record))
        assertTrue(h.session.setQualifiedInputs(binding, inputs))
        h.worker.run(); h.delivery.run()
        assertEquals(PreviewAnalysisStorage.Failed(DraftStorageFailure.CORRUPT), h.session.snapshot().storage)
        assertTrue(h.calls.isEmpty())
    }

    @Test fun `cancellation before a start handle returns disposes that handle and ignores synchronous completion`() {
        val directory = Files.createDirectory(root.resolve("draft"))
        val persistence = PreviewDraftPersistence(LocalDraftStore(directory))
        persistence.save(null, PreviewDraftFields())
        val worker = Queue(); val delivery = Queue()
        var cancelled = 0
        lateinit var session: PreviewDraftAnalysis<String>
        session = PreviewDraftAnalysis(persistence, worker, delivery, PreviewAnalysisEvaluator { request, complete ->
            assertTrue(session.cancel())
            complete(PreviewAnalysisCompletion.Succeeded(request.identity, "late synchronous"))
            PreviewAnalysisCancellation { cancelled++ }
        })
        session.load(); worker.run(); delivery.run()
        val binding = (session.snapshot().storage as PreviewAnalysisStorage.Saved).binding
        session.setQualifiedInputs(binding, inputs); worker.run(); delivery.run()
        assertEquals(1, cancelled)
        assertInstanceOf(PreviewAnalysisEvaluation.Cancelled::class.java, session.snapshot().evaluation)
    }

    @Test fun `invalid input keys are rejected without inventing absent evidence`() {
        assertThrows(IllegalArgumentException::class.java) { inputs.copy(catalog = " ") }
        assertThrows(IllegalArgumentException::class.java) { inputs.copy(resourceEvidence = "") }
        assertThrows(IllegalArgumentException::class.java) { inputs.copy(pricebook = "x".repeat(4097)) }
        assertNull(inputs.costEvidence)
        assertNull(inputs.resourceEvidence)
        assertEquals("PreviewAnalysisInputs", inputs.toString())
    }

    @Test fun `newest queued save uses the original receipt while superseded undispatched work cannot write`() {
        val h = harness(); h.seed()
        val binding = h.open()
        assertTrue(h.session.save(binding, pending("old queued edit")))
        assertTrue(h.session.save(binding, pending("latest queued edit")))
        h.worker.run()
        assertTrue(h.delivery.tasks.isEmpty())
        assertEquals("saved", h.loaded().fields.title)
        h.worker.run(); h.delivery.run()
        assertEquals("latest queued edit", h.binding().record.fields.title)
        assertEquals(2, h.loaded().history.size)
        assertEquals(listOf("saved", "latest queued edit"), h.loaded().history.map { it.fields!!.title })
        assertTrue(h.calls.isEmpty())
    }

    @Test fun `response revision and source hash must each match the actual saved binding`() {
        val h = harness()
        val first = h.seed()
        h.persistence.save(first, PreviewDraftFields("next")) as PreviewDraftSave.Saved
        val binding = h.open()
        val before = h.bytes()
        val call = h.analyze(binding, inputs)
        val version = binding.record.draft.version
        val wrong = listOf(
            ai.pickyour.core.drafts.DraftVersion(version.store, version.number + 1, version.hash),
            ai.pickyour.core.drafts.DraftVersion(version.store, version.number, first.draft.version.hash),
        )
        for (candidate in wrong) {
            call.succeed("mismatched", PreviewAnalysisIdentity(candidate, inputs))
            h.delivery.run()
            assertInstanceOf(PreviewAnalysisEvaluation.Pending::class.java, h.session.snapshot().evaluation)
        }
        call.succeed("exact"); h.delivery.run()
        assertEquals("exact", h.current().value)
        assertEquals(before, h.bytes())
    }
}
