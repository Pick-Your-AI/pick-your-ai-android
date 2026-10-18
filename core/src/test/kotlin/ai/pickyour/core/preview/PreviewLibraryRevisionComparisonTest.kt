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

class PreviewLibraryRevisionComparisonTest {
    @TempDir lateinit var root: Path
    private val known = mapOf("preview.format" to "1", "preview.title" to "saved")
    private val first = PreviewDraftId("guest-draft")
    private val second = PreviewDraftId("draft-00000000-0000-0000-0000-000000000002")
    private fun store(id: PreviewDraftId, parent: Path = root): LocalDraftStore {
        Files.createDirectories(parent.resolve(id.value))
        return LocalDraftStore(parent.resolve(id.value))
    }
    private fun append(id: PreviewDraftId, content: DraftContent, metadata: Map<String, String> = known,
        parent: Path = root): StoredDraft {
        val store = store(id, parent)
        val previous = (store.load() as? DraftLoad.Available)?.draft
        return (store.save(previous?.version, content, metadata) as DraftSave.Saved).draft
    }
    private fun entry(id: PreviewDraftId, parent: Path = root) =
        (PreviewDraftLibrary(parent).list() as PreviewLibraryRead.Available).entries.single { it.id == id }
    private fun capture(id: PreviewDraftId, revision: Long, parent: Path = root) =
        requireNotNull(PreviewLibraryRevision.capture(entry(id, parent), revision))
    private fun values(result: PreviewLibraryComparison) = result.comparison.changes.associate {
        (it.field to it.key) to (it.before to it.after)
    }
    private fun bytes() = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate {
        root.relativize(it).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()
    } }

    @Test fun explicitDraftsAndOlderRevisionsKeepBothIdentitiesAndOwnMetadata() {
        append(first, DraftContent("before"), known + ("preview.title" to "before title"))
        append(first, DraftContent("first unrelated head"), known + ("preview.title" to "first head"))
        append(second, DraftContent("after"), known + ("preview.title" to "after title"))
        append(second, DraftContent("second unrelated head"), known + ("preview.title" to "second head"))
        val leftEntry = entry(first)
        val rightEntry = entry(second)
        val before = requireNotNull(PreviewLibraryRevision.capture(leftEntry, 0))
        val after = requireNotNull(PreviewLibraryRevision.capture(rightEntry, 0))
        val disk = bytes()
        val compared = before.compareWith(after)!!
        assertSame(before, compared.before); assertSame(after, compared.after)
        assertEquals(first, compared.before.draftId); assertEquals(second, compared.after.draftId)
        assertEquals(0L, compared.comparison.from); assertEquals(0L, compared.comparison.to)
        assertEquals((leftEntry.state as PreviewDraftLoad.Available).record.baselineIdentity, before.capturedBaselineIdentity)
        assertEquals((rightEntry.state as PreviewDraftLoad.Available).record.baselineIdentity, after.capturedBaselineIdentity)
        assertEquals("before title", before.fields!!.title); assertEquals("after title", after.fields!!.title)
        assertEquals(mapOf(("objective" to null) to ("before" to "after"),
            ("guest.title" to null) to ("before title" to "after title")), values(compared))
        assertTrue(compared.comparison.metadataChanged); assertTrue(compared.comparison.guestFieldsComparable)
        assertEquals(disk, bytes())
        assertEquals(PreviewActiveRead.None, PreviewActiveDraftStore(root).read())
    }

    @Test fun missingUnavailableDeletingAndMislabelledSourcesNeverFallBackToAnotherRecord() {
        append(first, DraftContent("retained")); append(second, DraftContent("other"))
        val available = entry(first)
        val disk = bytes()
        for (number in listOf(-1L, 1L, Long.MAX_VALUE)) assertNull(PreviewLibraryRevision.capture(available, number))
        assertNull(PreviewLibraryRevision.capture(PreviewLibraryEntry(first, PreviewDraftLoad.Missing), 0))
        for (failure in DraftStorageFailure.entries)
            assertNull(PreviewLibraryRevision.capture(PreviewLibraryEntry(first, PreviewDraftLoad.Failed(failure)), 0))
        assertNull(PreviewLibraryRevision.capture(PreviewLibraryEntry(first, available.state, true), 0))
        assertNull(PreviewLibraryRevision.capture(PreviewLibraryEntry(second, available.state), 0))
        assertEquals(disk, bytes())
    }

    @Test fun equalDirectoryNamesFromDifferentLibrariesCannotMasqueradeAsOneLocalSource() {
        val one = Files.createDirectory(root.resolve("one"))
        val two = Files.createDirectory(root.resolve("two"))
        append(first, DraftContent("same number but first library"), parent = one)
        append(first, DraftContent("same number but second library"), parent = two)
        val before = capture(first, 0, one)
        val foreign = capture(first, 0, two)
        val alias = capture(first, 0, one.resolve("."))
        val disk = bytes()
        assertNull(before.compareWith(foreign)); assertNull(foreign.compareWith(before))
        assertTrue(before.compareWith(alias)!!.comparison.changes.isEmpty())
        assertEquals(disk, bytes())
    }

    @Test fun unavailableHistoricalGuestMetadataIsNotFilledFromEitherCurrentTitle() {
        append(first, DraftContent("first old"), mapOf("preview.format" to "future", "opaque" to "left"))
        append(first, DraftContent("first current"), known + ("preview.title" to "must not use left head"))
        append(second, DraftContent("second old"), mapOf("preview.format" to "future", "opaque" to "right"))
        append(second, DraftContent("second current"), known + ("preview.title" to "must not use right head"))
        for ((from, to) in listOf(0L to 0L, 0L to 1L, 1L to 0L)) {
            val compared = capture(first, from).compareWith(capture(second, to))!!
            assertFalse(compared.comparison.guestFieldsComparable)
            assertTrue(compared.comparison.changes.none { it.field.startsWith("guest.") })
            assertEquals(from == 0L, compared.before.fields == null)
            assertEquals(to == 0L, compared.after.fields == null)
            assertTrue(compared.comparison.metadataChanged)
        }
    }

    @Test fun exactLocalValuesAndJsonNumericEqualityAreSharedWithRetainedPairComparison() {
        val left = SelectionFixtures.node().also {
            it.putObject("settings").put("max_output_tokens", BigInteger("10000000000000000000"))
            it.putObject("extensions").putArray("x_opaque").addNull().add("1")
        }
        val right = left.deepCopy().put("role", "secondary\n").also {
            it.putObject("settings").put("max_output_tokens", java.math.BigDecimal("1e19"))
        }
        val huge = BigInteger("1234567890123456789012345678901234567890")
        val before = DraftContent(null, "", workload = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("0.0000"),
            requestsPerDay = huge), components = DraftSelections(listOf(LocalSelection.fromJson(left))))
        val after = DraftContent("", "unknown", workload = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("0.00"),
            requestsPerDay = BigInteger.ZERO), components = DraftSelections(listOf(LocalSelection.fromJson(right))))
        append(first, before); append(first, after)
        append(second, after)
        val compared = capture(first, 0).compareWith(capture(second, 0))!!
        val sameDraft = (entry(first).state as PreviewDraftLoad.Available).record.compareRevisions(0, 1)!!
        assertEquals(sameDraft.changes.map { listOf(it.field, it.key, it.before, it.after) },
            compared.comparison.changes.map { listOf(it.field, it.key, it.before, it.after) })
        assertEquals(null to "", values(compared)["objective" to null])
        assertEquals("0.0000" to "0.00", values(compared)["workload.monthly_budget_amount" to null])
        assertEquals(huge.toString() to "0", values(compared)["workload.requests_per_day" to null])
        assertEquals("\"primary\"" to "\"secondary\\n\"", values(compared)["components.selection.role" to "sel_model"])
        assertTrue(compared.comparison.changes.none { it.field == "components.selection.settings" })
    }

    @Test fun differentSelectionIdsAreAddedAndRemovedWithoutRoleOrCatalogHeuristics() {
        val before = SelectionFixtures.node()
        val after = before.deepCopy().put("id", "independent_model")
        append(first, DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(before)))))
        append(second, DraftContent(components = DraftSelections(listOf(LocalSelection.fromJson(after)))))
        val compared = capture(first, 0).compareWith(capture(second, 0))!!
        val changes = compared.comparison.changes
        assertEquals(setOf("sel_model", "independent_model"), changes.map { it.key }.toSet())
        assertTrue(changes.filter { it.key == "sel_model" }.all { it.before != null && it.after == null })
        assertTrue(changes.filter { it.key == "independent_model" }.all { it.before == null && it.after != null })
        assertEquals(changes.map { it.key }.sortedBy { it }, changes.map { it.key })
    }

    @Test fun reverseAndSameSourceComparisonsRemainImmutableAfterOtherWritersAndDeletion() {
        append(first, DraftContent("left", optional = mapOf(OptionalTier.T3 to TierDraft(settings = mapOf("k\u202e" to "")))))
        append(second, DraftContent("right", optional = mapOf(OptionalTier.T3 to TierDraft(settings = mapOf("k\u202e" to "literal")))))
        val beforeEntry = entry(first)
        val afterEntry = entry(second)
        val before = PreviewLibraryRevision.capture(beforeEntry, 0)!!
        val after = PreviewLibraryRevision.capture(afterEntry, 0)!!
        val forward = before.compareWith(after)!!
        assertEquals(values(forward).mapValues { (_, pair) -> pair.second to pair.first }, values(after.compareWith(before)!!))
        assertTrue(before.compareWith(before)!!.comparison.changes.isEmpty())
        assertFalse(before.compareWith(before)!!.comparison.metadataChanged)
        append(first, DraftContent("later writer"))
        assertEquals(PreviewLibraryDelete.Deleted,
            PreviewDraftLibrary(root).delete(second, (afterEntry.state as PreviewDraftLoad.Available).record))
        val disk = bytes()
        assertEquals(values(forward), values(before.compareWith(after)!!))
        val stale = (beforeEntry.state as PreviewDraftLoad.Available).record
        assertEquals(PreviewDraftSave.Conflict, PreviewDraftLibrary(root).open(first).save(stale, PreviewDraftFields("stale write")))
        assertEquals(disk, bytes())
        assertThrows(UnsupportedOperationException::class.java) { (forward.comparison.changes as MutableList).clear() }
        assertEquals("PreviewLibraryRevision", before.toString()); assertEquals("PreviewLibraryComparison", forward.toString())
    }

    @Test fun duplicateLineageKeepsItsOwnIdAndMetadataWithoutInventingContentDifferences() {
        append(first, DraftContent("copied saved content"))
        val source = (entry(first).state as PreviewDraftLoad.Available).record
        val copy = PreviewDraftLibrary(root).duplicate(source) as PreviewLibraryCreate.Saved
        val before = capture(first, 0)
        val after = capture(copy.id, 0)
        val disk = bytes()
        val compared = before.compareWith(after)!!
        assertNotEquals(before.draftId, after.draftId)
        assertTrue(compared.comparison.changes.isEmpty())
        assertTrue(compared.comparison.metadataChanged)
        assertTrue(compared.comparison.guestFieldsComparable)
        assertEquals(disk, bytes())
        assertEquals(source.baselineIdentity, (entry(first).state as PreviewDraftLoad.Available).record.baselineIdentity)
    }
}
