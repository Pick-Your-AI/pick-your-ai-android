package ai.pickyour.core.drafts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DraftHistoryTest {
    @Test
    fun `empty and partially entered drafts preserve unanswered fields`() {
        val history = DraftHistory()
        val empty = history.head()
        assertNull(empty.content.objective)
        assertNull(empty.content.context)
        assertNull(empty.content.primaryModel)
        val partial = DraftContent(objective = "", primaryModel = "unresolved-local-input")
        val result = history.edit(empty, partial) as DraftEditResult.Applied
        assertEquals("", result.revision.content.objective)
        assertNull(result.revision.content.context)
        assertEquals("unresolved-local-input", result.revision.content.primaryModel)
        assertNull(empty.content.primaryModel)
    }

    @Test
    fun `all optional activation combinations are independent and retain inputs`() {
        for (t3 in Activation.entries) for (t4 in Activation.entries) for (t5 in Activation.entries) {
            val activations = listOf(t3, t4, t5)
            var content = DraftContent(context = "core context")
            for ((index, tier) in OptionalTier.entries.withIndex()) {
                content = content.withTier(tier, TierDraft(activations[index], setOf(tier.name), mapOf("setting" to "input")))
            }
            val original = content
            for (tier in OptionalTier.entries) {
                val before = content.optional.getValue(tier)
                val disabled = content.withTier(tier, before.withActivation(Activation.DISABLED))
                assertFalse(disabled.activeOptionalSelections().containsKey(tier))
                val enabled = disabled.withTier(tier, disabled.optional.getValue(tier).withActivation(Activation.ENABLED))
                assertEquals(setOf(tier.name), enabled.activeOptionalSelections()[tier])
                assertEquals(before.settings, enabled.optional.getValue(tier).settings)
                assertEquals(before.selections, enabled.optional.getValue(tier).selections)
                for (other in OptionalTier.entries.filter { it != tier }) {
                    assertEquals(original.optional[other], enabled.optional[other])
                }
                assertEquals("core context", enabled.context)
            }
            assertEquals(OptionalTier.entries.filterIndexed { index, _ -> activations[index] == Activation.UNKNOWN }.toSet(),
                content.unknownActivations())
            assertEquals(OptionalTier.entries.filterIndexed { index, _ -> activations[index] == Activation.ENABLED }.toSet(),
                content.activeOptionalSelections().keys)
        }
        assertEquals(listOf("T3", "T4", "T5"), OptionalTier.entries.map { it.name })
    }

    @Test
    fun `input aliases and exposed collections cannot rewrite revisions`() {
        val selections = mutableSetOf("selection")
        val settings = mutableMapOf("setting" to "value")
        val tiers = mutableMapOf(OptionalTier.T3 to TierDraft(Activation.ENABLED, selections, settings))
        val metadata = mutableMapOf("lock" to "current")
        val content = DraftContent(optional = tiers)
        val history = DraftHistory(content, metadata)
        selections.clear()
        settings.clear()
        tiers.clear()
        metadata.clear()
        val tier = content.optional.getValue(OptionalTier.T3)
        assertEquals(setOf("selection"), tier.selections)
        assertEquals(mapOf("setting" to "value"), tier.settings)
        assertEquals(mapOf("lock" to "current"), history.head().metadata)
        assertThrows(UnsupportedOperationException::class.java) { (tier.selections as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (tier.settings as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (content.optional as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (history.head().metadata as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (history.history() as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (content.activeOptionalSelections() as MutableMap).clear() }
    }

    @Test
    fun `edit restore edit leaves history intact and preserves current metadata`() {
        val history = DraftHistory(DraftContent(objective = "first"), mapOf("version" to "old"))
        val first = history.head()
        val second = (history.edit(first, DraftContent(objective = "second"), mapOf("version" to "new")) as DraftEditResult.Applied).revision
        val savedHistory = history.history()
        val restored = (history.restore(second, first) as DraftEditResult.Applied).revision
        assertEquals(2, restored.number)
        assertEquals(RevisionChange.RESTORE, restored.change)
        assertEquals(0, restored.restoredFrom)
        assertEquals("first", restored.content.objective)
        assertEquals(second.metadata, restored.metadata)
        assertEquals("second", second.content.objective)
        assertEquals(mapOf("version" to "old"), first.metadata)
        val final = (history.edit(restored, DraftContent(objective = "third")) as DraftEditResult.Applied).revision
        assertEquals(3, final.number)
        assertEquals(listOf(first, second), savedHistory)
        assertEquals(listOf(first, second, restored, final), history.history())
    }

    @Test
    fun `duplicate edits are no ops but stale and foreign heads conflict`() {
        val history = DraftHistory()
        val initial = history.head()
        assertTrue(history.edit(initial, DraftContent()) is DraftEditResult.Unchanged)
        val edited = (history.edit(initial, DraftContent(context = "new")) as DraftEditResult.Applied).revision
        val conflict = history.edit(initial, edited.content) as DraftEditResult.Conflict
        assertSame(edited, conflict.current)
        assertTrue(history.edit(DraftHistory().head(), DraftContent()) is DraftEditResult.Conflict)
        assertEquals(2, history.history().size)
        assertTrue(history.restore(edited, DraftHistory().head()) is DraftEditResult.UnknownRevision)
        assertSame(edited, history.head())
        assertTrue(history.restore(initial, initial) is DraftEditResult.Conflict)
    }

    @Test
    fun `restore matching content still creates new identity and stale repeat conflicts`() {
        val history = DraftHistory()
        val initial = history.head()
        val restored = (history.restore(initial, initial) as DraftEditResult.Applied).revision
        assertEquals(initial.content, restored.content)
        assertNotSame(initial, restored)
        assertTrue(history.restore(initial, initial) is DraftEditResult.Conflict)
        assertEquals(2, history.history().size)
    }

    @Test
    fun `simultaneous edits have exactly one winner and retain losing input`() {
        val history = DraftHistory()
        val initial = history.head()
        val edits = listOf(DraftContent(objective = "one"), DraftContent(objective = "two"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = edits.map { content -> executor.submit<DraftEditResult> { start.await(); history.edit(initial, content) } }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is DraftEditResult.Applied })
            assertEquals(1, results.count { it is DraftEditResult.Conflict })
            val losingIndex = results.indexOfFirst { it is DraftEditResult.Conflict }
            assertNotEquals(edits[losingIndex], history.head().content)
            assertTrue(history.edit(history.head(), edits[losingIndex]) is DraftEditResult.Applied)
            assertEquals(edits[losingIndex], history.head().content)
            assertEquals(3, history.history().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `diagnostic strings exclude user inputs`() {
        assertEquals("DraftContent", DraftContent(objective = "private").toString())
        assertEquals("TierDraft", TierDraft(settings = mapOf("private" to "secret")).toString())
    }
}
