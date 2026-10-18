package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import ai.pickyour.core.cache.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.io.IOException

class PreviewDraftPersistenceTest {
    @TempDir lateinit var root: Path
    private fun store() = LocalDraftStore(root)
    private fun adapter() = PreviewDraftPersistence(store())
    private fun loaded() = (adapter().load() as PreviewDraftLoad.Available).record
    private fun saved(result: PreviewDraftSave) = (result as PreviewDraftSave.Saved).record

    @Test fun `domain restore appends exact retained content and preserves current guest metadata`() {
        val initial = DraftContent("old", null, "model", mapOf(OptionalTier.T3 to
            TierDraft(Activation.UNKNOWN, setOf("opaque"), mapOf("setting" to "value"))))
        store().save(null, initial, mapOf("preview.format" to "1", "preview.title" to "old title", "lock" to "current"))
        val first = loaded()
        val next = saved(adapter().save(first, PreviewDraftFields("current title", "id", "v1", "a".repeat(64)),
            PreviewDraftContext("new", "context")))
        val prior = next.draft.history
        val restored = saved(adapter().restore(next, first.revision))
        assertEquals(initial, restored.draft.content)
        assertEquals(next.draft.metadata, restored.draft.metadata)
        assertEquals(next.fields, restored.fields)
        assertEquals(next.revision + 1, restored.revision)
        assertEquals(prior.map { it.content }, restored.draft.history.dropLast(1).map { it.content })
        assertEquals(StoredRevisionChange.RESTORE, restored.history.last().change)
        assertEquals(first.revision, restored.history.last().restoredFrom)
        assertEquals(initial, loaded().history.last().content)
        val edited = saved(adapter().save(restored, restored.fields, PreviewDraftContext("after restore", "")))
        assertEquals(StoredRevisionChange.EDIT, edited.history.last().change)
        assertEquals(initial, edited.history.dropLast(1).last().content)
    }
    @Test fun `restore same content appends but stale foreign and unknown targets cannot change head`() {
        val first = saved(adapter().save(null, PreviewDraftFields(), PreviewDraftContext("objective", null)))
        val next = saved(adapter().restore(first, first.revision))
        assertEquals(first.revision + 1, next.revision)
        assertEquals(PreviewDraftSave.Conflict, adapter().restore(first, first.revision))
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.CORRUPT), adapter().restore(next, 1000))
        val foreign = PreviewDraftPersistence(LocalDraftStore(java.nio.file.Files.createDirectory(root.resolve("other"))))
        assertEquals(PreviewDraftSave.Conflict, foreign.restore(next, first.revision))
        assertEquals(next.baselineIdentity, loaded().baselineIdentity)
    }
    @Test fun `restore failure preserves prior baseline and uncertainty requires reconciliation`() {
        val first = saved(adapter().save(null, PreviewDraftFields(), PreviewDraftContext("old", null)))
        val current = saved(adapter().save(first, first.fields, PreviewDraftContext("new", "pending")))
        fun failing(point: CacheWritePoint) = PreviewDraftPersistence(LocalDraftStore(root) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { if (it == point) throw IOException("injected") }
        })
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.IO_FAILURE), failing(CacheWritePoint.POINTER_STAGED).restore(current, first.revision))
        assertEquals(current.baselineIdentity, loaded().baselineIdentity)
        assertEquals(PreviewDraftSave.Uncertain, failing(CacheWritePoint.POINTER_ACTIVATED).restore(current, first.revision))
        assertEquals(first.contextInput, loaded().contextInput)
        assertEquals(PreviewDraftSave.Conflict, adapter().restore(current, first.revision))
        assertFalse(PreviewDraftRestoration.capture(current, false, false).canResume(loaded()))
        assertFalse(PreviewDraftRestoration.capture(current, true, true).canResume(loaded()))
    }

    @Test fun `older saved state without context keys hydrates baseline while explicit pending null stays unanswered`() {
        val baseline = PreviewDraftContext("saved objective", "saved context")
        assertEquals(baseline, PreviewContextCheckpoint(false, PreviewDraftContext()).forLoad(true, baseline))
        val explicitNull = PreviewDraftContext()
        assertEquals(explicitNull, PreviewContextCheckpoint(true, explicitNull).forLoad(true, baseline))
        val explicitEmpty = PreviewDraftContext("", "")
        assertEquals(explicitEmpty, PreviewContextCheckpoint(true, explicitEmpty).forLoad(true, baseline))
        assertEquals(baseline, PreviewContextCheckpoint(true, explicitEmpty).forLoad(false, baseline))
    }
    @Test fun `objective and context retain unanswered empty and entered values across reopen`() {
        val first = saved(adapter().save(null, PreviewDraftFields(), PreviewDraftContext()))
        assertEquals(PreviewDraftContext(), loaded().contextInput)
        val entered = PreviewDraftContext("objective", "context")
        val second = saved(adapter().save(first, PreviewDraftFields(), entered))
        assertEquals(entered, loaded().contextInput)
        val empty = saved(adapter().save(second, PreviewDraftFields(), PreviewDraftContext("", "")))
        assertEquals(PreviewDraftContext("", ""), loaded().contextInput)
        saved(adapter().save(empty, PreviewDraftFields(), PreviewDraftContext()))
        assertEquals(PreviewDraftContext(), loaded().contextInput)
    }
    @Test fun `explicit context edit preserves primary model tiers settings and unrelated metadata`() {
        val content = DraftContent("old objective", "old context", "opaque model", mapOf(
            OptionalTier.T3 to TierDraft(Activation.UNKNOWN, setOf("id"), mapOf("setting" to "exact"))))
        store().save(null, content, mapOf("preview.format" to "1", "preview.title" to "title", "pin" to "keep"))
        val current = loaded()
        saved(adapter().save(current, current.fields, PreviewDraftContext("new objective", "new context")))
        val raw = (store().load() as DraftLoad.Available).draft
        assertEquals("opaque model", raw.content.primaryModel)
        assertEquals(content.optional, raw.content.optional)
        assertEquals("keep", raw.metadata["pin"])
        assertEquals("old context", raw.history.first().content.context)
    }
    @Test fun `stale context edit cannot replace newer content and old form copy preserves current context`() {
        val first = saved(adapter().save(null, PreviewDraftFields("first"), PreviewDraftContext("old", null)))
        val newer = saved(adapter().save(first, PreviewDraftFields("newer"), PreviewDraftContext("new", "context")))
        assertEquals(PreviewDraftSave.Conflict, adapter().save(first, first.fields, PreviewDraftContext("stale", "pending")))
        assertEquals(newer.contextInput, loaded().contextInput)
        saved(adapter().save(newer, first.fields))
        assertEquals(newer.contextInput, loaded().contextInput)
    }
    @Test fun `context uses canonical string bounds without truncation or diagnostic disclosure`() {
        val exact = PreviewDraftContext("x".repeat(16_384), "y".repeat(16_384))
        saved(adapter().save(null, PreviewDraftFields(), exact))
        assertEquals(exact, loaded().contextInput)
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftContext("x".repeat(16_385), null) }
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftContext(null, "x".repeat(16_385)) }
        assertEquals("PreviewDraftContext", exact.toString())
    }
    @Test fun `guest history retains exact form pins and copying requires explicit new save`() {
        val original = PreviewDraftFields("old", "id.old", "v1", "a".repeat(64))
        val first = saved(adapter().save(null, original))
        val current = saved(adapter().save(first, PreviewDraftFields("new")))
        val history = loaded().history
        assertEquals(listOf(0L, 1L), history.map { it.revision })
        assertEquals(original, history.first().fields)
        assertEquals("new", loaded().fields.title)
        val copy = history.first().fields!!
        assertEquals("new", loaded().fields.title) // Reading/copying has no write side effect.
        val edited = saved(adapter().save(current, copy))
        assertEquals(original, edited.fields)
        assertEquals(3, edited.history.size)
        assertEquals(StoredRevisionChange.EDIT, (store().load() as DraftLoad.Available).draft.history.last().change)
        assertThrows(UnsupportedOperationException::class.java) { (history as MutableList).clear() }
        assertEquals("PreviewDraftRevision", history.first().toString())
    }
    @Test fun `historical unknown guest metadata is unavailable rather than invented`() {
        val raw = store().save(null, DraftContent(), mapOf("preview.format" to "future")) as DraftSave.Saved
        store().save(raw.draft.version, DraftContent(), mapOf("preview.format" to "1", "preview.title" to "current"))
        assertNull(loaded().history.first().fields)
        assertEquals("current", loaded().history.last().fields!!.title)
        assertFalse(loaded().earlierHistoryUnavailable)
    }
    @Test fun `copied historical form cannot overwrite a concurrently saved head`() {
        val first = saved(adapter().save(null, PreviewDraftFields("old")))
        val second = saved(adapter().save(first, PreviewDraftFields("middle")))
        val pending = second.history.first().fields!!
        saved(adapter().save(second, PreviewDraftFields("other writer")))
        assertEquals(PreviewDraftSave.Conflict, adapter().save(second, pending))
        assertEquals("other writer", loaded().fields.title)
        assertEquals("old", pending.title)
    }
    @Test fun `recreation rejects newer disk baseline without adopting its save authority`() {
        val first = saved(adapter().save(null, PreviewDraftFields("first")))
        val checkpoint = PreviewDraftRestoration.capture(first, true, false)
        assertTrue(checkpoint.canResume(loaded()))
        saved(adapter().save(first, PreviewDraftFields("another activity")))
        assertFalse(checkpoint.canResume(loaded()))
        assertFalse(checkpoint.canResume(null))
    }
    @Test fun `conflict and uncertain blockade survives recreation even at same baseline`() {
        val first = saved(adapter().save(null, PreviewDraftFields("first")))
        assertFalse(PreviewDraftRestoration.capture(first, false, false).canResume(loaded()))
        assertFalse(PreviewDraftRestoration(null, true).canResume(null))
    }
    @Test fun `in flight save recreation requires explicit reopen regardless of outcome`() {
        val first = saved(adapter().save(null, PreviewDraftFields("first")))
        val checkpoint = PreviewDraftRestoration.capture(first, true, true)
        assertFalse(checkpoint.canResume(loaded()))
        saved(adapter().save(first, PreviewDraftFields("committed while rotating")))
        assertFalse(checkpoint.canResume(loaded()))
    }
    @Test fun `missing baseline resumes only while still missing and established`() {
        val checkpoint = PreviewDraftRestoration.capture(null, true, false)
        assertTrue(checkpoint.canResume(null))
        saved(adapter().save(null, PreviewDraftFields("new")))
        assertFalse(checkpoint.canResume(loaded()))
        assertFalse(PreviewDraftRestoration.capture(null, false, true).canResume(null))
    }
    @Test fun `explicit save reopens exact empty incomplete and selected guest fields`() {
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
        val first = saved(adapter().save(null, PreviewDraftFields()))
        assertEquals(PreviewDraftFields(), loaded().fields)
        val next = PreviewDraftFields("entered title", "opaque-unavailable-selection", "pinned-version", "a".repeat(64))
        saved(adapter().save(first, next))
        assertEquals(next, loaded().fields)
        assertEquals(1, loaded().revision)
        assertEquals("PreviewDraftFields", next.toString())
        assertEquals("PreviewStoredDraft", loaded().toString())
    }
    @Test fun `cleared form is a new committed revision not deletion of history`() {
        val first = saved(adapter().save(null, PreviewDraftFields("old", "id.old")))
        saved(adapter().save(first, PreviewDraftFields()))
        assertEquals(PreviewDraftFields(), loaded().fields)
        val raw = (store().load() as DraftLoad.Available).draft
        assertEquals(2, raw.history.size)
        assertEquals("old", raw.history.first().metadata["preview.title"])
        assertFalse(raw.metadata.containsKey("preview.selectedId"))
    }
    @Test fun `stale token conflicts without overwriting pending or newer fields`() {
        val first = saved(adapter().save(null, PreviewDraftFields("first")))
        val newer = saved(adapter().save(first, PreviewDraftFields("newer")))
        val pending = PreviewDraftFields("pending")
        assertEquals(PreviewDraftSave.Conflict, adapter().save(first, pending))
        assertEquals(newer.fields, loaded().fields)
        assertEquals("pending", pending.title)
        assertEquals(PreviewDraftSave.Conflict, adapter().save(null, pending))
    }
    @Test fun `domain content and unrelated metadata survive preview edits`() {
        val raw = store().save(null, DraftContent(context = "preserve domain"), mapOf(
            "preview.format" to "1", "preview.title" to "old", "catalog" to "old-pin", "other" to "keep")) as DraftSave.Saved
        val current = loaded()
        saved(adapter().save(current, PreviewDraftFields("new")))
        val reopened = (store().load() as DraftLoad.Available).draft
        assertEquals(raw.draft.content, reopened.content)
        assertEquals("old-pin", reopened.metadata["catalog"])
        assertEquals("keep", reopened.metadata["other"])
    }
    @Test fun `unsupported preview metadata is never silently reset`() {
        for (metadata in listOf(emptyMap(), mapOf("preview.format" to "2", "preview.title" to "future"),
            mapOf("preview.format" to "1"), mapOf("preview.format" to "1", "preview.title" to "x".repeat(121)))) {
            val expected = (store().load() as? DraftLoad.Available)?.draft?.version
            val record = store().save(expected, DraftContent(), metadata) as DraftSave.Saved
            assertTrue(adapter().load() is PreviewDraftLoad.Failed)
            assertEquals(PreviewDraftSave.Conflict, adapter().save(null, PreviewDraftFields()))
            assertEquals(record.draft.version.hash, (store().load() as DraftLoad.Available).draft.version.hash)
        }
    }
    @Test fun `preactivation failure preserves acknowledged fields and postactivation is uncertain`() {
        val first = saved(adapter().save(null, PreviewDraftFields("first")))
        fun failing(point: CacheWritePoint) = PreviewDraftPersistence(LocalDraftStore(root) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { if (it == point) throw IOException("injected") }
        })
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.IO_FAILURE), failing(CacheWritePoint.POINTER_STAGED).save(first, PreviewDraftFields("pending")))
        assertEquals(first.fields, loaded().fields)
        assertEquals(PreviewDraftSave.Uncertain, failing(CacheWritePoint.POINTER_ACTIVATED).save(first, PreviewDraftFields("committed but uncertain")))
        assertEquals("committed but uncertain", loaded().fields.title)
        assertEquals(PreviewDraftSave.Conflict, adapter().save(first, PreviewDraftFields("retry old token")))
    }
    @Test fun `bounded form rejects values before persistence without truncation`() {
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftFields("x".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftFields(selectedId = " ") }
        assertThrows(IllegalArgumentException::class.java) { PreviewDraftFields(selectedId = "x".repeat(257)) }
        assertEquals(PreviewDraftLoad.Missing, adapter().load())
    }
}
