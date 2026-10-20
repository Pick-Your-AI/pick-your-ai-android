package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.evaluation.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Canonical durable reads around actual evaluation, with explicit controlled ordering at the caller seam. */
class PreviewCurrentDraftAnalysisTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private var serial = 0
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")).use { it.readBytes() }
    private fun inputs() = requireNotNull(DraftCurrentInputs.read(fixture("catalog"), fixture("dependency-graph"), fixture("ruleset")))
    private fun rows(vararg kinds: String) = mapper.readTree(fixture("build"))["selections"].filter { it["kind"].asText() in kinds }
        .map { LocalSelection.fromJson(it as ObjectNode) }
    private class Store(val store: LocalDraftStore, val adapter: PreviewDraftPersistence)
    private fun store(): Store {
        val local = LocalDraftStore(Files.createDirectory(root.resolve("draft-${serial++}")))
        val adapter = PreviewDraftPersistence(local)
        assertTrue(adapter.save(null, PreviewDraftFields("private synthetic draft")) is PreviewDraftSave.Saved)
        return Store(local, adapter)
    }
    private fun load(s: Store) = (s.adapter.load() as PreviewDraftLoad.Available).record
    private fun set(s: Store, kinds: List<String>): PreviewStoredDraft {
        val old = (s.store.load() as DraftLoad.Available).draft
        assertTrue(s.store.save(old.version, old.content.withComponents(DraftSelections(rows(*kinds.toTypedArray()))), old.metadata) is DraftSave.Saved)
        return load(s)
    }
    private fun start(session: PreviewCurrentDraftAnalysis, record: PreviewStoredDraft, inputs: DraftCurrentInputs, owner: Any = Any()) =
        requireNotNull(session.update(record, inputs, true, owner))
    private fun files() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to Files.readAllBytes(it).toList()
    } }

    @Test fun firstLoadReadsHeadTwiceRunsOnceAndRerendersWithoutRescheduling() {
        val s = store(); val record = load(s); val pins = inputs(); val owner = Any()
        var evaluations = 0; var reads = 0
        val session = PreviewCurrentDraftAnalysis { evaluations++; DraftCurrentEvaluator().evaluate(it) }
        val work = start(session, record, pins, owner)
        assertTrue(session.state is PreviewCurrentDraftState.Rechecking)
        val before = files()
        session.execute(work) { reads++; s.adapter.load() }
        session.execute(work) { fail<Unit>("An attempt started twice"); s.adapter.load() }
        repeat(5) { assertNull(session.update(record, pins, true, owner)) }
        assertEquals(1, evaluations); assertEquals(2, reads)
        assertTrue(session.state is PreviewCurrentDraftState.Current)
        assertEquals(before, files())
    }

    @Test fun unsavedEditAndRevertCannotReviveTheRetiredSameHashAttempt() {
        val s = store(); val record = load(s); val pins = inputs(); val owner = Any(); var evaluations = 0
        val session = PreviewCurrentDraftAnalysis { evaluations++; DraftCurrentEvaluator().evaluate(it) }
        val stale = start(session, record, pins, owner)
        session.invalidate(PreviewCurrentUnavailable.EDITING)
        assertTrue(session.state is PreviewCurrentDraftState.Unavailable)
        val current = start(session, record, pins, owner)
        assertNotSame(stale, current); assertEquals(stale.request.binding, current.request.binding)
        session.execute(stale) { fail<Unit>("Revoked work read the store"); s.adapter.load() }
        session.execute(current, s.adapter::load)
        assertEquals(1, evaluations)
        assertSame(current.request, (session.state as PreviewCurrentDraftState.Current).request)
    }

    @Test fun nextAcknowledgedSourceUsesSupersededPredecessorNotLastSuccessfulResult() {
        val s = store(); val pins = inputs(); val a = set(s, listOf("model"))
        val session = PreviewCurrentDraftAnalysis(); val first = start(session, a, pins)
        session.execute(first, s.adapter::load)
        val retained = session.state as PreviewCurrentDraftState.Current
        val retainedRows = retained.outcome.findings.map { it.ruleId to it.outcome }
        val b = set(s, listOf("model", "accelerator")); val ignored = start(session, b, pins)
        val c = set(s, listOf("workload")); val current = start(session, c, pins)
        assertEquals(b.revision, current.request.recheck.before!!.sourceRevision)
        assertTrue(current.request.recheck.selectionIds.contains("sel_accelerator"))
        session.execute(current, s.adapter::load)
        session.execute(ignored) { fail<Unit>("A superseded attempt read"); s.adapter.load() }
        assertSame(current.request, (session.state as PreviewCurrentDraftState.Current).request)
        assertEquals(retainedRows, retained.outcome.findings.map { it.ruleId to it.outcome })
    }

    @Test fun runningOldCompletionCannotOverwriteNewerSourceEvenWithReentrantDelivery() {
        val s = store(); val pins = inputs(); val a = load(s); var current: PreviewCurrentDraftAttempt? = null
        lateinit var session: PreviewCurrentDraftAnalysis
        var evaluations = 0
        session = PreviewCurrentDraftAnalysis { request ->
            evaluations++
            val original = DraftCurrentEvaluator().evaluate(request)
            if (evaluations == 1) {
                val b = set(s, listOf("model")); current = start(session, b, pins)
                session.execute(requireNotNull(current), s.adapter::load)
            }
            original
        }
        session.execute(start(session, a, pins), s.adapter::load)
        assertEquals(2, evaluations)
        assertSame(requireNotNull(current).request, (session.state as PreviewCurrentDraftState.Current).request)
    }

    @Test fun actualDurableMutationDuringEvaluationRejectsTheOtherwiseValidResult() {
        val s = store(); val pins = inputs(); val a = load(s)
        val session = PreviewCurrentDraftAnalysis { request ->
            val outcome = DraftCurrentEvaluator().evaluate(request)
            set(s, listOf("model")); outcome
        }
        val attempt = start(session, a, pins)
        session.execute(attempt, s.adapter::load)
        assertEquals(PreviewCurrentUnavailable.SOURCE_CHANGED, (session.state as PreviewCurrentDraftState.Unavailable).reason)
        assertTrue(load(s).revision > a.revision)
    }

    @Test fun readFailuresAndStaleHeadNeverInvokeTheEvaluatorAndRequireRetry() {
        val s = store(); val record = load(s); val pins = inputs(); var evaluated = 0
        val session = PreviewCurrentDraftAnalysis { evaluated++; DraftCurrentEvaluator().evaluate(it) }
        val owner = Any(); val first = start(session, record, pins, owner)
        session.execute(first) { PreviewDraftLoad.Failed(DraftStorageFailure.BUSY) }
        assertEquals(PreviewCurrentUnavailable.READ_FAILED, (session.state as PreviewCurrentDraftState.Unavailable).reason)
        assertNull(session.update(record, pins, true, owner))
        session.invalidate(PreviewCurrentUnavailable.STORAGE_UNCONFIRMED)
        val second = start(session, record, pins, owner)
        set(s, listOf("model")); session.execute(second, s.adapter::load)
        assertEquals(PreviewCurrentUnavailable.SOURCE_CHANGED, (session.state as PreviewCurrentDraftState.Unavailable).reason)
        assertEquals(0, evaluated)
        val latest = load(s); val retry = start(session, latest, pins)
        session.execute(retry, s.adapter::load)
        assertTrue(session.state is PreviewCurrentDraftState.Current)
        assertEquals(1, evaluated)
    }

    @Test fun postEvaluationReadFailureWithholdsAndExplicitRetryRecovers() {
        val s = store(); val record = load(s); val pins = inputs(); val session = PreviewCurrentDraftAnalysis(); var reads = 0
        session.execute(start(session, record, pins)) {
            if (++reads == 2) PreviewDraftLoad.Failed(DraftStorageFailure.IO_FAILURE) else s.adapter.load()
        }
        assertEquals(PreviewCurrentUnavailable.READ_FAILED, (session.state as PreviewCurrentDraftState.Unavailable).reason)
        session.invalidate(PreviewCurrentUnavailable.READ_FAILED)
        session.execute(start(session, record, pins), s.adapter::load)
        assertTrue(session.state is PreviewCurrentDraftState.Current)
    }

    @Test fun foreignOutcomeCannotAcquireAnotherSourceAttempt() {
        val s = store(); val record = load(s); val pins = inputs()
        val original = PreviewCurrentDraftAnalysis(); val first = start(original, record, pins)
        original.execute(first, s.adapter::load)
        val outcome = (original.state as PreviewCurrentDraftState.Current).outcome
        val receiving = PreviewCurrentDraftAnalysis { outcome }
        receiving.execute(start(receiving, record, pins), s.adapter::load)
        assertEquals(PreviewCurrentUnavailable.EXECUTION_FAILED, (receiving.state as PreviewCurrentDraftState.Unavailable).reason)
    }

    @Test fun differentStoreWithIdenticalBytesGetsItsOwnAttemptAndNoPredecessor() {
        val a = store(); val b = store(); val firstRecord = load(a); val secondRecord = load(b); val pins = inputs()
        assertEquals(firstRecord.draft.version.hash, secondRecord.draft.version.hash)
        assertEquals(firstRecord.revision, secondRecord.revision)
        val session = PreviewCurrentDraftAnalysis(); val first = start(session, firstRecord, pins)
        val second = start(session, secondRecord, pins)
        assertNotEquals(first.request.binding.inputDigest, second.request.binding.inputDigest)
        assertNull(second.request.recheck.before)
        session.execute(first) { fail<Unit>("Cross-store stale read"); a.adapter.load() }
        session.execute(second, b.adapter::load)
        assertSame(second.request, (session.state as PreviewCurrentDraftState.Current).request)
    }

    @Test fun artifactAbaAndLifecycleRetirementKeepFreshTokensAndHistoricalBytes() {
        val s = store(); val record = load(s); val a = inputs()
        val graph = mapper.readTree(fixture("dependency-graph")) as ObjectNode
        graph.put("revision", 2)
        val replacementId = "dep.replacement.current"
        val replacement = graph["edges"][0].deepCopy<ObjectNode>().put("id", replacementId)
        // Frozen graphs require an edge. Replace every old edge identity with one new valid edge.
        graph.putArray("edges").add(replacement)
        val b = requireNotNull(DraftCurrentInputs.read(fixture("catalog"), graph.toString().toByteArray(), fixture("ruleset")))
        val session = PreviewCurrentDraftAnalysis()
        val before = files(); val first = start(session, record, a)
        session.update(record, null, false, Any(), PreviewCurrentUnavailable.INPUT_UNAVAILABLE)
        val second = start(session, record, b)
        assertNotEquals(first.request.binding.inputDigest, second.request.binding.inputDigest)
        assertEquals(first.request.binding.graphHash, second.request.recheck.before!!.graphHash)
        assertFalse(first.request.recheck.edgeIds.contains(replacementId))
        assertEquals((first.request.recheck.edgeIds + replacementId).sorted(), second.request.recheck.edgeIds)
        session.invalidate(PreviewCurrentUnavailable.BACKGROUND)
        val third = start(session, record, a)
        assertNotSame(first, third)
        session.execute(first) { fail<Unit>("Old source A revived"); s.adapter.load() }
        session.execute(second) { fail<Unit>("Old source B revived"); s.adapter.load() }
        session.execute(third, s.adapter::load)
        session.close()
        assertNull(session.update(record, a, true, Any()))
        assertEquals(PreviewCurrentUnavailable.CLOSED, (session.state as PreviewCurrentDraftState.Unavailable).reason)
        assertEquals(before, files())
    }
}
