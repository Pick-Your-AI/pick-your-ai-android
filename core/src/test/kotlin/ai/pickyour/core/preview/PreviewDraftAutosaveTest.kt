package ai.pickyour.core.preview

import ai.pickyour.core.cache.CacheWritePoint
import ai.pickyour.core.cache.VerifiedArtifactCache
import ai.pickyour.core.drafts.DraftStorageFailure
import ai.pickyour.core.drafts.DraftWorkload
import ai.pickyour.core.drafts.LocalDraftStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class PreviewDraftAutosaveTest {
    @TempDir lateinit var root: Path

    private class Clock : PreviewAutosaveScheduler {
        class Task(val at: Long, val action: () -> Unit) { var cancelled = false; var fired = false }
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Long, action: () -> Unit): PreviewAutosaveCancellation {
            val task = Task(now + delayMillis, action)
            tasks += task
            return PreviewAutosaveCancellation { task.cancelled = true }
        }
        fun advance(millis: Long) {
            now += millis
            tasks.filter { !it.fired && !it.cancelled && it.at <= now }.toList().forEach {
                it.fired = true
                it.action()
            }
        }
    }

    private val id = PreviewDraftId("guest-draft")
    private fun adapter() = PreviewDraftPersistence(LocalDraftStore(root))
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun input(title: String, context: String? = null) = PreviewPendingDraft(
        PreviewDraftFields(title), PreviewDraftContext("objective", context), DraftWorkload())
    private fun write(request: PreviewAutosaveRequest) = adapter().save(request.expected,
        request.pending.fields, request.pending.context, request.pending.workload)

    @Test fun `rapid complete inputs replace the pending deadline and only the last input is committed`() {
        val clock = Clock()
        val results = mutableListOf<PreviewDraftSave>()
        val autosave = PreviewDraftAutosave(clock) { results += write(it) }
        val authority = Any()
        assertTrue(autosave.schedule(PreviewAutosaveRequest(id, null, authority, input("first", "first context"))))
        clock.advance(500)
        assertTrue(autosave.schedule(PreviewAutosaveRequest(id, null, authority, input("last", ""))))
        clock.advance(749)
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        clock.advance(1)
        assertEquals(1, results.size)
        assertTrue(results.single() is PreviewDraftSave.Saved)
        assertEquals("last", loaded().fields.title)
        assertEquals(PreviewDraftContext("objective", ""), loaded().contextInput)
        assertEquals(1, loaded().history.size)
    }

    @Test fun `an identical refresh keeps its deadline and an exact current save keeps every stored byte`() {
        val clock = Clock()
        val authority = Any()
        val autosave = PreviewDraftAutosave(clock) { assertTrue(write(it) is PreviewDraftSave.Saved) }
        autosave.schedule(PreviewAutosaveRequest(id, null, authority, input("same")))
        clock.advance(500)
        autosave.schedule(PreviewAutosaveRequest(id, null, authority, input("same")))
        assertEquals(1, clock.tasks.size)
        clock.advance(250)
        val first = loaded()
        fun bytes() = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList()
            .associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() } }
        val before = bytes()
        autosave.schedule(PreviewAutosaveRequest(id, first, Any(), input("same")))
        clock.advance(750)
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
        assertEquals(before, bytes())
    }

    @Test fun `cancelled replaced and closed callbacks cannot commit even when a scheduler replays them`() {
        val clock = Clock()
        val autosave = PreviewDraftAutosave(clock) { write(it); fail<Unit>("Retired callback dispatched") }
        autosave.schedule(PreviewAutosaveRequest(id, null, Any(), input("cancelled")))
        val cancelled = clock.tasks.last()
        autosave.cancel()
        cancelled.action()
        autosave.schedule(PreviewAutosaveRequest(id, null, Any(), input("replaced")))
        val replaced = clock.tasks.last()
        autosave.schedule(PreviewAutosaveRequest(id, null, Any(), input("closed")))
        replaced.action()
        val closed = clock.tasks.last()
        autosave.close()
        closed.action()
        assertFalse(autosave.schedule(PreviewAutosaveRequest(id, null, Any(), input("after close"))))
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
    }

    @Test fun `request authority includes local identity exact receipt token and every complete input`() {
        val first = (adapter().save(null, PreviewDraftFields("saved")) as PreviewDraftSave.Saved).record
        val authority = Any()
        val pending = input("pending", "context")
        val request = PreviewAutosaveRequest(id, first, authority, pending)
        assertTrue(request.matches(id, first, authority, input("pending", "context")))
        assertFalse(request.matches(PreviewDraftId("other-draft"), first, authority, pending))
        assertFalse(request.matches(id, loaded(), authority, pending)) // Equal hash is not this receipt.
        assertFalse(request.matches(id, first, Any(), pending))
        assertFalse(request.matches(id, first, authority, input("newer", "context")))
        assertFalse(request.matches(id, first, authority, input("pending", "")))
        assertFalse(request.matches(id, first, authority,
            PreviewPendingDraft(pending.fields, pending.context, DraftWorkload(currency = "USD"))))
        assertFalse(request.matches(id, first, authority, null))
        assertEquals("PreviewAutosaveRequest", request.toString())
    }

    @Test fun `reusing a request after cancel or pause gives it a new deadline and retires prior callbacks`() {
        val clock = Clock()
        val results = mutableListOf<PreviewDraftSave>()
        val autosave = PreviewDraftAutosave(clock) { results += write(it) }
        val request = PreviewAutosaveRequest(id, null, Any(), input("same request"))
        autosave.schedule(request)
        val first = clock.tasks.last()
        clock.advance(250)
        autosave.cancel()
        autosave.schedule(request)
        val second = clock.tasks.last()
        first.action()
        assertTrue(results.isEmpty())
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        clock.advance(250)
        autosave.pause()
        autosave.resume()
        autosave.schedule(request)
        first.action()
        second.action()
        assertTrue(results.isEmpty())
        clock.advance(749)
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        clock.advance(1)
        assertEquals(1, results.size)
        assertTrue(results.single() is PreviewDraftSave.Saved)
        assertEquals("same request", loaded().fields.title)
        assertEquals(1, loaded().history.size)
    }

    @Test fun `preactivation failure pauses automatic retries and leaves the acknowledged head intact`() {
        val first = (adapter().save(null, PreviewDraftFields("saved")) as PreviewDraftSave.Saved).record
        val faulty = PreviewDraftPersistence(LocalDraftStore(root) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
                if (it == CacheWritePoint.POINTER_STAGED) throw IOException("injected preactivation failure")
            }
        })
        val clock = Clock()
        lateinit var autosave: PreviewDraftAutosave
        var attempts = 0
        autosave = PreviewDraftAutosave(clock) { request ->
            attempts++
            val result = faulty.save(request.expected, request.pending.fields, request.pending.context, request.pending.workload)
            assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.IO_FAILURE), result)
            autosave.completed(result)
        }
        val request = PreviewAutosaveRequest(id, first, Any(), input("pending"))
        autosave.schedule(request)
        clock.advance(750)
        assertTrue(autosave.isPaused)
        assertFalse(autosave.schedule(request))
        clock.advance(60_000)
        assertEquals(1, attempts)
        assertEquals(first.baselineIdentity, loaded().baselineIdentity)
    }

    @Test fun `postactivation uncertainty cannot schedule another write or acknowledge the newly durable copy`() {
        val first = (adapter().save(null, PreviewDraftFields("saved")) as PreviewDraftSave.Saved).record
        val faulty = PreviewDraftPersistence(LocalDraftStore(root) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
                if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("injected postactivation failure")
            }
        })
        val clock = Clock()
        lateinit var autosave: PreviewDraftAutosave
        var outcome: PreviewDraftSave? = null
        autosave = PreviewDraftAutosave(clock) { request ->
            outcome = faulty.save(request.expected, request.pending.fields, request.pending.context, request.pending.workload)
            autosave.completed(checkNotNull(outcome))
        }
        val request = PreviewAutosaveRequest(id, first, Any(), input("unconfirmed"))
        autosave.schedule(request)
        clock.advance(750)
        assertEquals(PreviewDraftSave.Uncertain, outcome)
        assertEquals("unconfirmed", loaded().fields.title)
        assertTrue(autosave.isPaused)
        assertFalse(autosave.schedule(request))
        assertFalse(PreviewDraftRestoration.capture(first, false, false).canResume(loaded()))
        assertEquals(PreviewDraftSave.Conflict, write(request))
    }

    @Test fun `scheduler rejection pauses before a write and only explicit resume permits another attempt`() {
        var reject = true
        val clock = Clock()
        val scheduler = PreviewAutosaveScheduler { delay, action ->
            if (reject) null else clock.schedule(delay, action)
        }
        val autosave = PreviewDraftAutosave(scheduler) { assertTrue(write(it) is PreviewDraftSave.Saved) }
        val request = PreviewAutosaveRequest(id, null, Any(), input("retry"))
        assertFalse(autosave.schedule(request))
        assertTrue(autosave.isPaused)
        reject = false
        assertFalse(autosave.schedule(request))
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        autosave.resume()
        assertTrue(autosave.schedule(request))
        clock.advance(750)
        assertEquals("retry", loaded().fields.title)
    }
}
