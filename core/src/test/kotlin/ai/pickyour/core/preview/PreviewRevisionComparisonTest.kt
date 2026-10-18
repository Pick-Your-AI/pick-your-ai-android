package ai.pickyour.core.preview

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.math.BigInteger

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
        val controls = PreviewComparedValue.from("a\n\t\r\"\\\u0000\u202e\u2028\u2029").quotedLiteral!!
        assertEquals("\"a\\n\\t\\r\\\"\\\\\\u0000\\u202e\\u2028\\u2029\"", controls)
        assertFalse(controls.contains('\n'))
        assertFalse(controls.contains('\u2028'))
        assertFalse(controls.contains('\u2029'))
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

    @Test fun `comparison exposes every workload field with exact lexeme and unanswered distinctions`() {
        val first = store().save(null, DraftContent(), mapOf("preview.format" to "1", "preview.title" to "current")) as DraftSave.Saved
        val huge = BigInteger("1234567890123456789012345678901234567890")
        val workload = DraftWorkload("entered", "USD", ContractDecimal.parse("0.0000"), BigInteger.ZERO,
            huge, BigInteger.ZERO, BigInteger.ONE, huge, DraftDataClassification.INTERNAL)
        val edited = store().save(first.draft.version, DraftContent(workload = workload)) as DraftSave.Saved
        val comparison = load().compareRevision(0)!!
        assertEquals(mapOf(
            "workload.region" to "entered", "workload.currency" to "USD", "workload.monthly_budget_amount" to "0.0000",
            "workload.requests_per_day" to "0", "workload.avg_input_tokens" to huge.toString(), "workload.avg_output_tokens" to "0",
            "workload.max_concurrency" to "1", "workload.max_latency_ms" to huge.toString(), "workload.data_classification" to "internal",
        ), comparison.changes.associate { it.field to it.after })
        assertTrue(comparison.changes.all { it.before == null && it.key == null })
        assertFalse(comparison.metadataChanged)
        assertEquals(edited.draft.version.hash, load().draft.version.hash)
        val exact = workload.copy(monthlyBudgetAmount = ContractDecimal.parse("0.00"))
        store().save(edited.draft.version, DraftContent(workload = exact))
        val decimalChange = load().compareRevision(1)!!.changes.single()
        assertEquals("workload.monthly_budget_amount", decimalChange.field)
        assertEquals("0.0000", decimalChange.before)
        assertEquals("0.00", decimalChange.after)
        assertTrue(load().compareRevision(2)!!.changes.isEmpty())
    }
}
