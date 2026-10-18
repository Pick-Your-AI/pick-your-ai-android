package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PreviewSelectionPreservationTest {
    @TempDir lateinit var root: Path
    private fun components(): DraftSelections {
        val model = SelectionFixtures.selection()
        val hosting = SelectionFixtures.selection("hosting_platform", "host")
        val optional = SelectionFixtures.selection("tool", "tool", "explicit")
        return DraftSelections(listOf(model, hosting, optional), listOf(SelectionFixtures.link("link.one", model.id, hosting.id, false)))
    }
    @Test fun `context workload guest tier duplicate and pending recovery preserve typed components across reopen`() {
        val id = PreviewDraftId("guest-draft")
        val store = LocalDraftStore(Files.createDirectory(root.resolve(id.value)))
        val original = DraftContent("objective", "context", "opaque-primary", mapOf(OptionalTier.T3 to
            TierDraft(Activation.UNKNOWN, setOf("opaque-selection"), mapOf("keep" to "0.00"))), DraftWorkload(region = "old"), components())
        val metadata = mapOf("preview.format" to "1", "preview.title" to "old", "unknown-lock" to "retained")
        assertTrue(store.save(null, original, metadata) is DraftSave.Saved)
        val library = PreviewDraftLibrary(root)
        val adapter = library.open(id)
        val baseline = (adapter.load() as PreviewDraftLoad.Available).record
        val renamed = (adapter.save(baseline, PreviewDraftFields("renamed")) as PreviewDraftSave.Saved).record
        val context = (adapter.save(renamed, renamed.fields, PreviewDraftContext("", "new"), DraftWorkload(currency = "EUR")) as PreviewDraftSave.Saved).record
        val activated = (adapter.setActivation(context, OptionalTier.T3, Activation.DISABLED) as PreviewDraftSave.Saved).record
        assertEquals(original.components, activated.draft.content.components)
        assertEquals(original.components, (adapter.load() as PreviewDraftLoad.Available).record.draft.content.components)
        assertTrue(activated.history.all { it.content.components == original.components })
        assertEquals("retained", activated.draft.metadata["unknown-lock"])
        assertTrue(activated.compareRevision(0)!!.changes.none { it.field.startsWith("components.") })
        val duplicate = library.duplicate(activated) as PreviewLibraryCreate.Saved
        assertEquals(activated.draft.content, duplicate.record.draft.content)
        assertEquals(0L, duplicate.record.revision)
        assertEquals(original.components, (library.open(duplicate.id).load() as PreviewDraftLoad.Available).record.draft.content.components)
        val pending = PreviewPendingDraft(PreviewDraftFields("pending"), PreviewDraftContext(null, "pending"), DraftWorkload(region = "pending"))
        val recovery = library.recoverPending(baseline, pending) as PreviewLibraryCreate.Saved
        assertEquals(original.components, recovery.record.draft.content.components)
        assertEquals(original.primaryModel, recovery.record.draft.content.primaryModel)
        assertEquals(original.optional, recovery.record.optionalTiers)
        assertEquals(pending.workload, recovery.record.workloadInput)
        assertEquals(0L, recovery.record.revision)
        assertEquals(original.components, (library.open(recovery.id).load() as PreviewDraftLoad.Available).record.draft.content.components)
        assertEquals(activated.baselineIdentity, (adapter.load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertEquals(PreviewDraftSave.Conflict, library.open(recovery.id).save(baseline, baseline.fields))
        val restored = (adapter.restore(activated, 0) as PreviewDraftSave.Saved).record
        assertEquals(original, restored.draft.content)
        assertEquals(activated.fields, restored.fields)
        assertEquals(activated.draft.metadata, restored.draft.metadata)
    }
    @Test fun `pending recovery from a full library retention budget keeps typed components and source artifacts`() {
        // Seed the pre-existing legacy directory before giving the library ownership of it.
        val id = PreviewDraftId("guest-draft")
        val directory = Files.createDirectory(root.resolve(id.value))
        val original = DraftContent(primaryModel = "opaque", components = components())
        val store = LocalDraftStore(directory)
        assertTrue(store.save(null, original, mapOf("preview.format" to "1", "preview.title" to "original")) is DraftSave.Saved)
        val library = PreviewDraftLibrary(root)
        val baseline = (library.open(id).load() as PreviewDraftLoad.Available).record
        val revisions = directory.resolve("revisions")
        repeat(LocalDraftStore.MAX_HISTORY - 1) { index ->
            val bytes = "unactivated-selection-$index".toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            Files.write(revisions.resolve("$hash.bin"), bytes)
        }
        val pointer = Files.readAllBytes(revisions.resolve("active"))
        val pending = PreviewPendingDraft(PreviewDraftFields("pending"), baseline.contextInput, DraftWorkload(region = "pending"))
        assertEquals(PreviewDraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            library.open(id).save(baseline, pending.fields, pending.context, pending.workload))
        val recovery = library.recoverPending(baseline, pending) as PreviewLibraryCreate.Saved
        assertNotEquals(id, recovery.id)
        assertEquals(original.components, recovery.record.draft.content.components)
        assertEquals(original.primaryModel, recovery.record.draft.content.primaryModel)
        assertEquals(pending.workload, recovery.record.workloadInput)
        assertEquals(original.components, (library.open(recovery.id).load() as PreviewDraftLoad.Available).record.draft.content.components)
        assertEquals(baseline.baselineIdentity, (library.open(id).load() as PreviewDraftLoad.Available).record.baselineIdentity)
        assertArrayEquals(pointer, Files.readAllBytes(revisions.resolve("active")))
        assertEquals(LocalDraftStore.MAX_HISTORY.toLong(), Files.list(revisions).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".bin") }.count()
        })
    }
    @Test fun `comparison is deterministic by selection identity and preserves settings types and absent values`() {
        val store = LocalDraftStore(root)
        val original = DraftContent(components = components())
        val first = (store.save(null, original, mapOf("preview.format" to "1", "preview.title" to "title")) as DraftSave.Saved).draft
        val model = original.components.selections.first()
        val changed = LocalSelection.fromJson(model.toJson().put("notes", "").also { it.putObject("settings").put("temperature", 0).put("top_p", 1) })
        val next = original.withComponents(DraftSelections(listOf(changed) + original.components.selections.drop(1), emptyList()))
        store.save(first.version, next)
        val adapter = PreviewDraftPersistence(store)
        val loaded = (adapter.load() as PreviewDraftLoad.Available).record
        val typed = loaded.compareRevision(0)!!.changes.filter { it.field.startsWith("components.") }
        assertEquals(listOf("components.selection.settings", "components.selection.notes", "components.link"), typed.map { it.field })
        assertEquals(model.id, typed.first().key)
        assertEquals("{\"temperature\":0,\"top_p\":1}", typed.first().after)
        assertNull(typed[1].before)
        assertEquals("\"\"", typed[1].after)
        assertEquals("link.one", typed.last().key)
        assertNull(typed.last().after)
        val reorder = changed.toJson().also { it.putObject("settings").put("top_p", 1).put("temperature", 0) }
        val reordered = next.withComponents(DraftSelections(listOf(LocalSelection.fromJson(reorder)) + next.components.selections.drop(1)))
        val noOp = store.save(loaded.draft.version, reordered) as DraftSave.Saved
        assertEquals(loaded.draft.version.hash, noOp.draft.version.hash)
        assertEquals(typed.map { it.field to it.after }, loaded.compareRevision(0)!!.changes.filter { it.field.startsWith("components.") }.map { it.field to it.after })
        val removed = store.applySelection(DraftSelectionIntent(noOp.draft.version, SelectionCommand.Remove(model.id))) as DraftSelectionSave.Saved
        val preview = (adapter.load() as PreviewDraftLoad.Available).record
        assertEquals(removed.change, preview.history.last().selectionChange)
        assertTrue(preview.compareRevision(noOp.draft.version.number)!!.changes.any { it.field == "components.selection.kind" && it.key == model.id && it.after == null })
    }
}
