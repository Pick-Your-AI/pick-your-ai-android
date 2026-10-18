package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PreviewRevisionComparisonTest {
    @TempDir lateinit var root: Path
    private fun store() = LocalDraftStore(root)
    private fun load() = (PreviewDraftPersistence(store()).load() as PreviewDraftLoad.Available).record
    @Test fun `typed display separates literal sentinels escapes controls and bounds values`() {
        assertEquals(PreviewComparedValueKind.ABSENT, PreviewComparedValue.from(null).kind)
        assertEquals(PreviewComparedValueKind.EMPTY, PreviewComparedValue.from("").kind)
        for (literal in listOf("absent or unanswered", "explicitly empty", "unknown")) {
            val display = PreviewComparedValue.from(literal)
            assertEquals(PreviewComparedValueKind.TEXT, display.kind)
            assertEquals("\"$literal\"", display.quotedLiteral)
        }
        val controls = PreviewComparedValue.from("a\n\t\r\"\\\u0000\u202e").quotedLiteral!!
        assertEquals("\"a\\n\\t\\r\\\"\\\\\\u0000\\u202e\"", controls)
        assertFalse(controls.contains('\n'))
        val bounded = PreviewComparedValue.from("x".repeat(161))
        assertTrue(bounded.truncated)
        assertEquals("\"" + "x".repeat(160) + "\"", bounded.quotedLiteral)
        assertFalse(PreviewComparedValue.from("x".repeat(160)).truncated)
    }
    @Test fun `comparison preserves null empty opaque unknown and settings changes without writes`() {
        val first = store().save(null, DraftContent(null, "", "same", mapOf(OptionalTier.T3 to
            TierDraft(Activation.DISABLED, setOf("old"), mapOf("same-key" to "old value")))),
            mapOf("preview.format" to "1", "preview.title" to "old")) as DraftSave.Saved
        store().save(first.draft.version, DraftContent("", "unknown", "same", mapOf(OptionalTier.T3 to
            TierDraft(Activation.UNKNOWN, setOf("new"), mapOf("same-key" to "new value")))),
            mapOf("preview.format" to "1", "preview.title" to "new"))
        val current = load()
        val comparison = current.compareRevision(0)!!
        assertEquals(current.baselineIdentity, load().baselineIdentity)
        assertEquals(0L, comparison.from); assertEquals(1L, comparison.to)
        assertEquals(null, comparison.changes.first { it.field == "objective" }.before)
        assertEquals("", comparison.changes.first { it.field == "objective" }.after)
        assertEquals("unknown", comparison.changes.first { it.field == "context" }.after)
        assertEquals("same-key", comparison.changes.first { it.field == "T3.setting" }.key)
        assertEquals(listOf("new", "old"), comparison.changes.filter { it.field == "T3.selection" }.map { it.key })
        assertTrue(comparison.changes.none { it.field == "primary_model" })
        assertTrue(comparison.metadataChanged)
        assertTrue(comparison.guestFieldsComparable)
        assertThrows(UnsupportedOperationException::class.java) { (comparison.changes as MutableList).clear() }
        assertEquals("PreviewRevisionChange", comparison.changes.first().toString())
    }
    @Test fun `same revision is unchanged and unknown revision unavailable`() {
        PreviewDraftPersistence(store()).save(null, PreviewDraftFields())
        val current = load()
        assertTrue(current.compareRevision(current.revision)!!.changes.isEmpty())
        assertFalse(current.compareRevision(current.revision)!!.metadataChanged)
        assertNull(current.compareRevision(999))
    }
    @Test fun `unavailable old guest metadata is not fabricated and retained metadata divergence stays visible`() {
        val first = store().save(null, DraftContent("objective"), mapOf("preview.format" to "future")) as DraftSave.Saved
        store().save(first.draft.version, first.draft.content, mapOf("preview.format" to "1", "preview.title" to "current"))
        val comparison = load().compareRevision(0)!!
        assertTrue(comparison.changes.isEmpty())
        assertTrue(comparison.metadataChanged)
        assertFalse(comparison.guestFieldsComparable)
    }
}
