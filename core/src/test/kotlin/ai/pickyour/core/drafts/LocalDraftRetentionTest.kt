package ai.pickyour.core.drafts

import ai.pickyour.core.cache.CacheRetentionLimits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LocalDraftRetentionTest {
    @TempDir lateinit var root: Path
    private fun bounded() = LocalDraftStore(root, CacheRetentionLimits(1, 1_048_576))

    @Test fun `configured retention rejects a changed save while preserving acknowledged revision`() {
        val original = (bounded().save(null, DraftContent(objective = "keep this")) as DraftSave.Saved).draft
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            bounded().save(original.version, DraftContent(objective = "not acknowledged")))
        val reopened = (bounded().load() as DraftLoad.Available).draft
        assertEquals(original.version.hash, reopened.version.hash)
        assertEquals(original.content, reopened.content)
        assertEquals(1, reopened.history.size)
        assertEquals(original.content, (bounded().revision(original.version.hash) as DraftLoad.Available).draft.content)
    }

    @Test fun `unchanged save remains acknowledged at quota without manufacturing a revision`() {
        val original = (bounded().save(null, DraftContent(objective = "unchanged")) as DraftSave.Saved).draft
        val repeated = (bounded().save(original.version, original.content) as DraftSave.Saved).draft
        assertEquals(original.version.hash, repeated.version.hash)
        assertEquals(original.version.number, repeated.version.number)
        assertEquals(1, repeated.history.size)
    }

    @Test fun `failed restore at quota leaves current head and all historical artifacts available`() {
        val unbounded = LocalDraftStore(root)
        val first = (unbounded.save(null, DraftContent(context = "first")) as DraftSave.Saved).draft
        val second = (unbounded.save(first.version, DraftContent(context = "second")) as DraftSave.Saved).draft
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), bounded().restore(second.version, first.version.number))
        val current = (bounded().load() as DraftLoad.Available).draft
        assertEquals(second.version.hash, current.version.hash)
        assertEquals(2, current.history.size)
        assertEquals(first.content, (bounded().revision(first.version.hash) as DraftLoad.Available).draft.content)
    }
}
