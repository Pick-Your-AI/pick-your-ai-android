package ai.pickyour.core.preview

import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class PreviewRevisionPairComparisonTest {
    @TempDir lateinit var root: Path
    private val known = mapOf("preview.format" to "1", "preview.title" to "retained")
    private fun store() = LocalDraftStore(root)
    private fun load() = (PreviewDraftPersistence(store()).load() as PreviewDraftLoad.Available).record
    private fun append(content: DraftContent, metadata: Map<String, String> = known): StoredDraft {
        val previous = (store().load() as? DraftLoad.Available)?.draft
        return (store().save(previous?.version, content, metadata) as DraftSave.Saved).draft
    }
    private fun values(result: PreviewRevisionComparison) = result.changes.associate {
        (it.field to it.key) to (it.before to it.after)
    }
    private fun bytes() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
    } }

    @Test fun selectedOlderRevisionsUseNeitherCurrentContentNorCurrentGuestMetadata() {
        append(DraftContent("before", "same"), known + ("preview.title" to "before title"))
        append(DraftContent("after", "same"), known + ("preview.title" to "after title"))
        append(DraftContent("unrelated head", "unrelated context", "unrelated model"), known + ("preview.title" to "head title"))
        val current = load()
        val disk = bytes()
        val result = current.compareRevisions(0, 1)!!
        assertEquals(0L, result.from); assertEquals(1L, result.to)
        assertEquals(mapOf(("objective" to null) to ("before" to "after"),
            ("guest.title" to null) to ("before title" to "after title")), values(result))
        assertTrue(result.guestFieldsComparable); assertTrue(result.metadataChanged)
        assertEquals(disk, bytes())
        assertEquals(current.baselineIdentity, load().baselineIdentity)
    }

    @Test fun reversingSelectedPairReversesMarkersIncludingAbsentEmptyAndOpaqueSettings() {
        append(DraftContent(null, "", optional = mapOf(OptionalTier.T3 to
            TierDraft(Activation.DISABLED, setOf("removed"), mapOf("key\u202e" to "absent or unanswered")))))
        append(DraftContent("", "unknown", optional = mapOf(OptionalTier.T3 to
            TierDraft(Activation.UNKNOWN, setOf("added"), mapOf("key\u202e" to "")))))
        append(DraftContent("unrelated head"))
        val forward = load().compareRevisions(0, 1)!!
        val reverse = load().compareRevisions(1, 0)!!
        assertEquals(1L, reverse.from); assertEquals(0L, reverse.to)
        assertEquals(values(forward).mapValues { (_, pair) -> pair.second to pair.first }, values(reverse))
        assertEquals(null to "", values(forward)["objective" to null])
        assertEquals("absent or unanswered" to "", values(forward)["T3.setting" to "key\u202e"])
        assertEquals(null to "selected", values(forward)["T3.selection" to "added"])
        assertEquals("selected" to null, values(forward)["T3.selection" to "removed"])
    }

    @Test fun sameRetainedRevisionReportsNoChangesIndependentlyOfSavedHead() {
        append(DraftContent("old"))
        append(DraftContent("middle"))
        append(DraftContent("different saved head"), known + ("preview.title" to "current"))
        for (number in 0L..2L) {
            val result = load().compareRevisions(number, number)!!
            assertEquals(number, result.from); assertEquals(number, result.to)
            assertTrue(result.changes.isEmpty()); assertFalse(result.metadataChanged)
            assertTrue(result.guestFieldsComparable)
        }
    }

    @Test fun missingEitherSelectedRevisionNeverFallsBackToHead() {
        append(DraftContent("before")); append(DraftContent("after"))
        val current = load()
        val disk = bytes()
        for (missing in listOf(-1L, 2L, Long.MAX_VALUE)) {
            assertNull(current.compareRevisions(missing, 0))
            assertNull(current.compareRevisions(0, missing))
            assertNull(current.compareRevisions(missing, missing))
        }
        assertEquals(disk, bytes())
    }

    @Test fun unavailableGuestFieldsOnEitherOrBothSelectedSidesRemainUnavailable() {
        append(DraftContent("first"), mapOf("preview.format" to "future", "private" to "first"))
        append(DraftContent("second"), mapOf("preview.format" to "future", "private" to "second"))
        append(DraftContent("third"), known + ("preview.title" to "known older title"))
        append(DraftContent("head"), known + ("preview.title" to "must not substitute"))
        val current = load()
        for ((from, to) in listOf(0L to 1L, 1L to 0L, 0L to 2L, 2L to 0L, 0L to 0L)) {
            val result = current.compareRevisions(from, to)!!
            assertFalse(result.guestFieldsComparable)
            assertTrue(result.changes.none { it.field.startsWith("guest.") })
            assertEquals(from != to, result.metadataChanged)
        }
        assertEquals("known older title" to "must not substitute", values(current.compareRevisions(2, 3)!!)["guest.title" to null])
    }

    @Test fun selectedRolesSettingsAndRelationshipsComeFromChosenSnapshots() {
        val before = SelectionFixtures.node().also { it.putObject("settings").put("temperature", 0.25) }
        val after = before.deepCopy().put("role", "secondary").also { it.putObject("settings").put("temperature", 0.75) }
        val host = SelectionFixtures.selection("hosting_platform", "host")
        append(DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(before), host),
            listOf(SelectionFixtures.link("edge", "sel_model", "host", false)))))
        append(DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(after), host),
            listOf(SelectionFixtures.link("edge", "sel_model", "host", true)))))
        append(DraftContent("head has neither selected component nor link"))
        val result = load().compareRevisions(0, 1)!!
        assertEquals(setOf("components.selection.role", "components.selection.settings", "components.link"), result.changes.map { it.field }.toSet())
        assertEquals("\"primary\"" to "\"secondary\"", values(result)["components.selection.role" to "sel_model"])
        assertEquals("{\"temperature\":0.25}" to "{\"temperature\":0.75}", values(result)["components.selection.settings" to "sel_model"])
        val edge = result.changes.single { it.field == "components.link" }
        assertEquals("edge", edge.key)
        assertTrue(edge.before!!.contains("\"required\":false")); assertTrue(edge.after!!.contains("\"required\":true"))
        assertTrue(result.changes.none { it.field.contains("catalog_item") })
    }

    @Test fun workloadLexemesAndHugeIntegersAreReadFromBothSelectedRevisions() {
        val huge = BigInteger("1234567890123456789012345678901234567890")
        append(DraftContent(workload = DraftWorkload("before region", "USD", ContractDecimal.parse("0.0000"),
            BigInteger.ZERO, huge, BigInteger.ONE, BigInteger.ONE, huge, DraftDataClassification.INTERNAL)))
        append(DraftContent(workload = DraftWorkload("after region", "EUR", ContractDecimal.parse("0.00"),
            huge, BigInteger.ZERO, BigInteger.TWO, huge, BigInteger.ONE, null)))
        append(DraftContent(workload = DraftWorkload("unrelated head", "JPY", ContractDecimal.parse("999"))))
        val result = load().compareRevisions(0, 1)!!
        assertEquals(9, result.changes.size)
        assertEquals("0.0000" to "0.00", values(result)["workload.monthly_budget_amount" to null])
        assertEquals(huge.toString() to "0", values(result)["workload.avg_input_tokens" to null])
        assertEquals("internal" to null, values(result)["workload.data_classification" to null])
        assertEquals("USD" to "EUR", values(result)["workload.currency" to null])
        assertTrue(result.changes.none { it.before == "unrelated head" || it.after == "unrelated head" })
    }

    @Test fun capturedHistoryComparisonDoesNotReadOrRewriteLaterDurableHead() {
        append(DraftContent("before")); append(DraftContent("after"))
        val captured = load()
        val originalToken = captured.draft.version
        val expected = values(captured.compareRevisions(0, 1)!!)
        append(DraftContent("a different writer committed this head"))
        val current = load()
        val disk = bytes()
        assertEquals(expected, values(captured.compareRevisions(0, 1)!!))
        assertNull(captured.compareRevisions(0, current.revision))
        assertSame(originalToken, captured.draft.version)
        assertEquals(DraftSave.Conflict, store().save(captured.draft.version, DraftContent("must not gain write authority")))
        assertEquals(current.baselineIdentity, load().baselineIdentity)
        assertEquals(disk, bytes())
        assertThrows(UnsupportedOperationException::class.java) { (captured.compareRevisions(0, 1)!!.changes as MutableList).clear() }
    }

    @Test fun restoreAppendsAHeadWithoutChangingTheSelectedHistoricalPair() {
        append(DraftContent("before")); append(DraftContent("after"))
        val captured = load()
        val before = values(captured.compareRevisions(0, 1)!!)
        val restored = store().restore(captured.draft.version, 0) as DraftSave.Saved
        assertEquals(2L, restored.draft.version.number)
        assertEquals(0L, restored.draft.history.last().restoredFrom)
        val current = load()
        val disk = bytes()
        assertEquals(before, values(current.compareRevisions(0, 1)!!))
        assertEquals(mapOf(("objective" to null) to ("after" to "before")), values(current.compareRevisions(1, 2)!!))
        assertEquals(disk, bytes())
    }
}
