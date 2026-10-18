package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class LocalDraftSelectionRetentionTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private val model = SelectionFixtures.selection()
    private fun bounded(count: Int = 1, bytes: Long = 4L * LocalDraftStore.MAX_BYTES) =
        LocalDraftStore(root, CacheRetentionLimits(count, bytes))
    private fun load() = (bounded().load() as DraftLoad.Available).draft
    private fun raw() = (VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES).active() as CacheRead.Available).bytes
    private fun artifactCount() = Files.list(root.resolve("revisions")).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".bin") }.count()
    }
    private fun initial() = (bounded().save(null, DraftContent("keep", "context", "opaque", workload = DraftWorkload(region = "entered"),
        components = DraftSelections(listOf(model), listOf(SelectionFixtures.link("self-link", model.id, model.id)))),
        mapOf("unknown-metadata" to "keep")) as DraftSave.Saved).draft
    private fun assertPreserved(original: StoredDraft, bytes: ByteArray, count: Long = 1) {
        val current = load()
        assertEquals(original.version.hash, current.version.hash)
        assertEquals(original.content, current.content)
        assertEquals(original.metadata, current.metadata)
        assertEquals(original.history.size, current.history.size)
        assertEquals(original.history.last().selectionChange, current.history.last().selectionChange)
        assertArrayEquals(bytes, raw())
        assertEquals(original.content, (bounded().revision(original.version.hash) as DraftLoad.Available).draft.content)
        assertEquals(count, artifactCount())
    }

    @Test fun `every changed selection command refuses count and retained byte quotas without losing acknowledgement`() {
        val original = initial()
        val bytes = raw()
        val hosting = SelectionFixtures.selection("hosting_platform", "hosting")
        val settings = model.settings.put("temperature", 1)
        val commands = listOf(
            SelectionCommand.Add(hosting, SelectionFixtures.choice(catalog, hosting)),
            SelectionCommand.Replace(model.id, SelectionFixtures.choice(catalog, model), settings),
            SelectionCommand.Configure(model.id, settings),
            SelectionCommand.SetEnabled(model.id, false),
            SelectionCommand.Remove(model.id),
        )
        for (store in listOf(bounded(), bounded(10, bytes.size.toLong()))) {
            for (command in commands) {
                assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
                    store.applySelection(DraftSelectionIntent(original.version, command), catalog))
                assertPreserved(original, bytes)
            }
        }
    }

    @Test fun `current identical commands at quota are byte preserving without change receipts`() {
        val original = initial()
        val bytes = raw()
        for (command in listOf(SelectionCommand.SetEnabled(model.id, true), SelectionCommand.Configure(model.id, model.settings),
            SelectionCommand.Replace(model.id, SelectionFixtures.choice(catalog, model)))) {
            val outcome = bounded().applySelection(DraftSelectionIntent(original.version, command), catalog) as DraftSelectionSave.Saved
            assertEquals(original.version.hash, outcome.draft.version.hash)
            assertEquals(original.version.number, outcome.draft.version.number)
            assertNull(outcome.change)
            assertPreserved(original, bytes)
        }
    }

    @Test fun `quota cannot turn stale or foreign tokens into noops or validation acknowledgements`() {
        val first = initial()
        val second = (bounded(2).save(first.version, DraftContent("new", components = first.content.components), first.metadata) as DraftSave.Saved).draft
        val bytes = raw()
        val foreign = (LocalDraftStore(Files.createDirectory(root.resolve("foreign"))).save(null, second.content, second.metadata) as DraftSave.Saved).draft
        for (expected in listOf(first.version, foreign.version)) {
            for (command in listOf(SelectionCommand.SetEnabled(model.id, true), SelectionCommand.Remove("missing"))) {
                assertEquals(DraftSelectionSave.Conflict, bounded(2).applySelection(DraftSelectionIntent(expected, command)))
                assertPreserved(second, bytes, 2)
            }
        }
        assertEquals(first.content, (bounded().revision(first.version.hash) as DraftLoad.Available).draft.content)
    }

    @Test fun `reopening after quota refusal preserves the original intent and a later permitted retry commits once`() {
        val original = initial()
        val bytes = raw()
        val intent = DraftSelectionIntent(original.version, SelectionCommand.Remove(model.id))
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), bounded().applySelection(intent))
        assertPreserved(original, bytes)
        // Explicit caller-provided room is a test policy change, never automatic eviction or quota expansion.
        val outcome = bounded(2).applySelection(intent) as DraftSelectionSave.Saved
        assertEquals(SelectionAction.REMOVE, outcome.change!!.action)
        assertEquals(listOf("self-link"), outcome.change.removedLinkIds)
        assertEquals(original.version.number + 1, outcome.draft.version.number)
        assertEquals(2, load().history.size)
        assertTrue(load().content.components.selections.isEmpty())
        assertEquals(original.metadata, load().metadata)
        assertEquals(DraftSelectionSave.Conflict, bounded(2).applySelection(intent))
        assertEquals(original.content, (bounded().revision(original.version.hash) as DraftLoad.Available).draft.content)
        assertEquals(2L, artifactCount())
    }

    @Test fun `quota refusal during v3 migration preserves every original byte and retained history`() {
        bounded()
        val bytes = SelectionFixtures.resource("/drafts/local-format-3.json")
        val hash = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        val cache = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertTrue(cache.install(hash, bytes.inputStream()) is CacheInstall.Activated)
        val original = load()
        val intent = DraftSelectionIntent(original.version, SelectionCommand.Add(model, SelectionFixtures.choice(catalog, model)))
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), bounded().applySelection(intent, catalog))
        assertPreserved(original, bytes)
        assertEquals(3, load().localFormatVersion)
        val migrated = (bounded(2).applySelection(intent, catalog) as DraftSelectionSave.Saved).draft
        assertEquals(4, migrated.localFormatVersion)
        original.history.zip(migrated.history).forEach { (before, after) ->
            assertEquals(before.number, after.number)
            assertEquals(before.change, after.change)
            assertEquals(before.restoredFrom, after.restoredFrom)
            assertEquals(before.content, after.content)
            assertEquals(before.metadata, after.metadata)
            assertEquals(before.selectionChange, after.selectionChange)
        }
        assertArrayEquals(bytes, (cache.pinned(original.version.hash) as CacheRead.Available).bytes)
        assertEquals(original.legacySourceHash, migrated.legacySourceHash)
        assertEquals(DraftSelectionSave.Conflict, bounded(2).applySelection(intent, catalog))
    }
}
