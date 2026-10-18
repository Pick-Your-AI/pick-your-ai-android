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
