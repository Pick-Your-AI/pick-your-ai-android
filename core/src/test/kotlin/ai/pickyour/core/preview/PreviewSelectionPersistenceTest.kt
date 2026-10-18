package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

class PreviewSelectionPersistenceTest {
    @TempDir lateinit var root: Path
    private val catalog = SelectionFixtures.catalog()
    private val model = SelectionFixtures.selection()
    private val host = SelectionFixtures.selection("hosting_platform", "hosting")
    private fun library() = PreviewDraftLibrary(root)
    private fun created() = library().create() as PreviewLibraryCreate.Saved
    private fun loaded(adapter: PreviewDraftPersistence) = (adapter.load() as PreviewDraftLoad.Available).record
    private fun add(item: LocalSelection = model) = SelectionCommand.Add(item, SelectionFixtures.choice(catalog, item))
    private fun saved(result: PreviewSelectionSave) = (result as PreviewSelectionSave.Saved).record

    @Test fun `all commands preserve guest context workload opaque metadata and acknowledge exact disclosures`() {
        val initial = created()
        val adapter = library().open(initial.id)
        val fields = PreviewDraftFields("title", "opaque", "v1", "a".repeat(64))
        val context = PreviewDraftContext("objective", "")
        val workload = DraftWorkload(region = "entered")
        val raw = LocalDraftStore(root.resolve(initial.id.value))
        assertTrue(raw.save(initial.record.draft.version, initial.record.draft.content,
            initial.record.draft.metadata + ("opaque.external" to "keep")) is DraftSave.Saved)
        var record = (adapter.save(loaded(adapter), fields, context, workload) as PreviewDraftSave.Saved).record
        val metadata = record.draft.metadata
        val commands = listOf(add(), SelectionCommand.Configure(model.id, model.settings.put("temperature", 1)),
            SelectionCommand.Replace(model.id, SelectionFixtures.choice(catalog, model), model.settings),
            SelectionCommand.SetEnabled(model.id, false), SelectionCommand.Remove(model.id))
        for (command in commands) {
            val previous = record
            val result = adapter.applySelection(previous, command, catalog) as PreviewSelectionSave.Saved
            record = result.record
            assertEquals(previous.revision + 1, record.revision)
            assertEquals(result.change, record.history.last().selectionChange)
            assertEquals(record.components, loaded(adapter).components)
            assertEquals(fields, record.fields)
            assertEquals(context, record.contextInput)
            assertEquals(workload, record.workloadInput)
            assertEquals(metadata, record.draft.metadata)
            assertEquals(previous.optionalTiers, record.optionalTiers)
            assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(previous, command, catalog))
        }
        assertTrue(record.components.selections.isEmpty())
    }

    @Test fun `current noop and typed rejection preserve baseline while stale and foreign commands conflict`() {
        val first = created()
        val adapter = library().open(first.id)
        val record = saved(adapter.applySelection(first.record, add(), catalog))
        val result = adapter.applySelection(record, SelectionCommand.SetEnabled(model.id, true)) as PreviewSelectionSave.Saved
        assertEquals(record.baselineIdentity, result.record.baselineIdentity)
        assertNull(result.change)
        assertEquals(PreviewSelectionSave.Rejected(SelectionRejection.MISSING_SELECTION),
            adapter.applySelection(record, SelectionCommand.Remove("missing")))
        val other = created()
        for (expected in listOf(first.record, other.record)) {
            assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(expected, SelectionCommand.SetEnabled(model.id, true)))
            assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(expected, SelectionCommand.Remove("missing")))
        }
        assertEquals(record.baselineIdentity, loaded(adapter).baselineIdentity)
        assertThrows(UnsupportedOperationException::class.java) { (record.components.selections as MutableList).clear() }
    }

    @Test fun `held managed adapter rejects every command and noops after completed or pending deletion`() {
        for (interrupted in listOf(false, true)) {
            val item = created()
            val adapter = library().open(item.id)
            val record = saved(adapter.applySelection(item.record, add(), catalog))
            val deleting = if (!interrupted) library() else PreviewDraftLibrary(root, {}, {
                if (it == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) throw IOException("injected")
            })
            val result = deleting.delete(item.id, record)
            if (interrupted) assertTrue(result is PreviewLibraryDelete.Unconfirmed)
            else assertEquals(PreviewLibraryDelete.Deleted, result)
            for (command in listOf(add(host), SelectionCommand.Replace(model.id, SelectionFixtures.choice(catalog, model)),
                SelectionCommand.Configure(model.id, model.settings), SelectionCommand.SetEnabled(model.id, true),
                SelectionCommand.SetEnabled(model.id, false), SelectionCommand.Remove(model.id))) {
                assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CORRUPT), adapter.applySelection(record, command, catalog))
            }
            if (interrupted) assertEquals(PreviewLibraryDelete.Deleted, library().delete(item.id, null))
            assertFalse(Files.exists(root.resolve(item.id.value)))
        }
    }

    @Test fun `library busy and cancellation return prewrite failure without advancing authority`() {
        val item = created()
        val adapter = library().open(item.id)
        FileChannel.open(root.resolve(".preview-library.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.BUSY), adapter.applySelection(item.record, add(), catalog))
            }
        }
        Thread.currentThread().interrupt()
        try {
            assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.CANCELLED), adapter.applySelection(item.record, add(), catalog))
        } finally { Thread.interrupted() }
        assertEquals(item.record.baselineIdentity, loaded(adapter).baselineIdentity)
    }

    @Test fun `unknown preview metadata cannot be loaded or silently rebased from an earlier record`() {
        val item = created()
        val adapter = library().open(item.id)
        val store = LocalDraftStore(root.resolve(item.id.value))
        val changed = (store.save(item.record.draft.version, item.record.draft.content,
            item.record.draft.metadata + ("preview.format" to "future")) as DraftSave.Saved).draft
        assertEquals(PreviewDraftLoad.Failed(DraftStorageFailure.UNSUPPORTED_VERSION), adapter.load())
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(item.record, add(), catalog))
        assertEquals(changed.version.hash, (store.load() as DraftLoad.Available).draft.version.hash)
    }

    @Test fun `retention refusal retains record and current noops still require live authority`() {
        val store = LocalDraftStore(root, CacheRetentionLimits(1, 4L * LocalDraftStore.MAX_BYTES))
        val adapter = PreviewDraftPersistence(store)
        store.save(null, DraftContent(components = DraftSelections(listOf(model))),
            mapOf("preview.format" to "1", "preview.title" to "retained"))
        val record = loaded(adapter)
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            adapter.applySelection(record, SelectionCommand.Remove(model.id)))
        assertEquals(record.baselineIdentity, loaded(adapter).baselineIdentity)
        val noop = adapter.applySelection(record, SelectionCommand.SetEnabled(model.id, true)) as PreviewSelectionSave.Saved
        assertNull(noop.change)
        assertEquals(record.baselineIdentity, noop.record.baselineIdentity)
    }

    @Test fun `preactivation failure and durable uncertainty stay distinct and never falsely acknowledge`() {
        val adapter = PreviewDraftPersistence(LocalDraftStore(root))
        val record = (adapter.save(null, PreviewDraftFields("keep")) as PreviewDraftSave.Saved).record
        fun failing(point: CacheWritePoint) = PreviewDraftPersistence(LocalDraftStore(root) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { if (it == point) throw IOException("injected") }
        })
        assertEquals(PreviewSelectionSave.Failed(DraftStorageFailure.IO_FAILURE),
            failing(CacheWritePoint.POINTER_STAGED).applySelection(record, add(), catalog))
        assertEquals(record.baselineIdentity, loaded(adapter).baselineIdentity)
        assertEquals(PreviewSelectionSave.Uncertain,
            failing(CacheWritePoint.POINTER_ACTIVATED).applySelection(record, add(), catalog))
        assertEquals(listOf(model), loaded(adapter).components.selections)
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(record, add(), catalog))
    }

    @Test fun `outer close failure after canonical acknowledgement requires reopen rather than a retry`() {
        val store = LocalDraftStore(root)
        val adapter = PreviewDraftPersistence(store)
        val record = (adapter.save(null, PreviewDraftFields("keep")) as PreviewDraftSave.Saved).record
        val access = object : PreviewDraftAccess {
            override fun <T> run(failed: (DraftStorageFailure) -> T, action: () -> T): T {
                action()
                return failed(DraftStorageFailure.IO_FAILURE)
            }
        }
        assertEquals(PreviewSelectionSave.Uncertain, PreviewDraftPersistence(store, access).applySelection(record, add(), catalog))
        val reopened = loaded(adapter)
        assertEquals(record.revision + 1, reopened.revision)
        assertEquals(listOf(model), reopened.components.selections)
        assertEquals(PreviewSelectionSave.Conflict, adapter.applySelection(record, add(), catalog))
        assertEquals(PreviewSelectionSave.Uncertain, PreviewDraftPersistence(store, access)
            .applySelection(reopened, SelectionCommand.SetEnabled(model.id, true)))
        assertEquals(reopened.baselineIdentity, loaded(adapter).baselineIdentity)
    }
}
