package ai.pickyour.core.preview

import ai.pickyour.core.drafts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PreviewDraftLibraryTest {
    @TempDir lateinit var root: Path
    private fun library() = PreviewDraftLibrary(root)
    private fun snapshot(result: PreviewLibraryResult = library().read()) = (result as PreviewLibraryResult.Available).snapshot
    private fun record(snapshot: PreviewLibrarySnapshot) = snapshot.current!!.record!!
    private fun entryPath(id: LocalDraftId) = root.resolve("records").resolve(id.value)
    private fun fault(point: LibraryWritePoint) = PreviewDraftLibrary(root, null) { if (it == point) throw IOException("injected private detail") }

    @Test fun `create rename duplicate open and delete preserve other identities and source history on reopen`() {
        val first = snapshot(library().create())
        val firstId = first.currentId!!
        assertEquals(PreviewDraftFields(), record(first).fields)
        val fields = PreviewDraftFields("named", "unavailable-item", "old-version", "a".repeat(64))
        val renamed = snapshot(library().save(firstId, record(first), fields))
        assertEquals(2, record(renamed).history.size)
        val copy = snapshot(library().duplicate(firstId, record(renamed)))
        val copyId = copy.currentId!!
        assertNotEquals(firstId, copyId)
        assertEquals(fields, record(copy).fields)
        assertEquals(1, record(copy).history.size)
        val rawCopy = (LocalDraftStore(entryPath(copyId)).load() as DraftLoad.Available).draft
        assertEquals(firstId.value, rawCopy.metadata["library.sourceId"])
        assertEquals("1", rawCopy.metadata["library.sourceRevision"])
        assertEquals(record(renamed).draft.version.hash.hex, rawCopy.metadata["library.sourceHash"])
        assertEquals(copyId, snapshot().currentId)
        val reopened = snapshot(library().select(firstId))
        assertEquals(fields, record(reopened).fields)
        assertEquals(2, record(reopened).history.size)
        snapshot(library().delete(copyId, record(copy)))
        assertFalse(Files.exists(entryPath(copyId)))
        assertEquals(listOf(firstId), snapshot().entries.map { it.id })
        assertEquals(firstId, snapshot().currentId)
        assertEquals(fields, record(snapshot()).fields)
    }

    @Test fun `duplicates preserve local content and guest pins without transferring unknown metadata authority`() {
        val initial = snapshot(library().create(PreviewDraftFields("source", "id", "v1", "b".repeat(64))))
        val id = initial.currentId!!
        val raw = record(initial).draft
        val content = DraftContent(context = "local context", optional = mapOf(OptionalTier.T4 to TierDraft(Activation.DISABLED, setOf("retained"))))
        LocalDraftStore(entryPath(id)).save(raw.version, content, raw.metadata + mapOf("remote.owner" to "other user", "unknown" to "not authority"))
        val source = record(snapshot())
        val duplicate = record(snapshot(library().duplicate(id, source)))
        assertEquals(content, duplicate.draft.content)
        assertEquals(source.fields, duplicate.fields)
        assertFalse(duplicate.draft.metadata.containsKey("remote.owner"))
        assertFalse(duplicate.draft.metadata.containsKey("unknown"))
        assertEquals("other user", record(snapshot(library().select(id))).draft.metadata["remote.owner"])
    }

    @Test fun `stale and foreign equal-byte tokens cannot rename duplicate or delete another draft`() {
        val first = snapshot(library().create())
        val second = snapshot(library().create())
        val secondId = second.currentId!!
        assertEquals(record(first).baselineIdentity, record(second).baselineIdentity)
        assertEquals(PreviewLibraryResult.Conflict, library().save(secondId, record(first), PreviewDraftFields("wrong")))
        assertEquals(PreviewLibraryResult.Conflict, library().duplicate(secondId, record(first)))
        assertEquals(PreviewLibraryResult.Conflict, library().delete(secondId, record(first)))
        val newer = snapshot(library().save(secondId, record(second), PreviewDraftFields("newer")))
        assertEquals(PreviewLibraryResult.Conflict, library().delete(secondId, record(second)))
        assertEquals("newer", record(snapshot()).fields.title)
        assertEquals(PreviewLibraryResult.Conflict, library().delete(secondId, null))
        assertEquals(record(newer).baselineIdentity, record(snapshot()).baselineIdentity)
    }

    @Test fun `one corrupt record stays visible while other drafts remain usable and is explicitly deletable`() {
        val first = snapshot(library().create(PreviewDraftFields("broken")))
        val brokenId = first.currentId!!
        val good = snapshot(library().create(PreviewDraftFields("good")))
        Files.writeString(entryPath(brokenId).resolve("revisions/active"), "bad pointer")
        val all = snapshot()
        assertEquals(LibraryEntryState.UNAVAILABLE, all.entries.single { it.id == brokenId }.state)
        assertEquals("good", record(all).fields.title)
        assertTrue(library().select(brokenId) is PreviewLibraryResult.Failed)
        assertEquals(good.currentId, snapshot().currentId)
        assertEquals(PreviewLibraryResult.Conflict, library().delete(brokenId, record(first)))
        snapshot(library().delete(brokenId, null))
        assertEquals(listOf(good.currentId), snapshot().entries.map { it.id })
    }

    @Test fun `interrupted create is an explicit recoverable record without overwriting selected draft`() {
        val first = snapshot(library().create(PreviewDraftFields("saved")))
        assertEquals(PreviewLibraryResult.Uncertain, fault(LibraryWritePoint.RECORD_SAVED).create(PreviewDraftFields("recover me")))
        val all = snapshot()
        assertEquals(first.currentId, all.currentId)
        val orphan = all.entries.single { it.state == LibraryEntryState.RECOVERABLE }
        assertEquals("recover me", orphan.record!!.fields.title)
        val recovered = snapshot(library().recover(orphan.id))
        assertEquals(orphan.id, recovered.currentId)
        assertEquals("recover me", record(recovered).fields.title)
        assertEquals(2, recovered.entries.size)
        assertEquals(PreviewLibraryResult.Conflict, library().recover(orphan.id))
    }

    @Test fun `selection failure before activation retains old choice and ambiguous activation requires reread`() {
        val first = snapshot(library().create(PreviewDraftFields("one")))
        val second = snapshot(library().create(PreviewDraftFields("two")))
        assertTrue(fault(LibraryWritePoint.INDEX_STAGED).select(first.currentId!!) is PreviewLibraryResult.Failed)
        assertEquals(second.currentId, snapshot().currentId)
        assertEquals(PreviewLibraryResult.Uncertain, fault(LibraryWritePoint.INDEX_ACTIVATED).select(first.currentId))
        assertEquals(first.currentId, snapshot().currentId)
        assertEquals("one", record(snapshot()).fields.title)
    }

    @Test fun `deletion tombstone blocks resurrection after failure and retry removes only marked bytes`() {
        val first = snapshot(library().create(PreviewDraftFields("keep")))
        val second = snapshot(library().create(PreviewDraftFields("delete")))
        val id = second.currentId!!
        assertEquals(PreviewLibraryResult.Uncertain, fault(LibraryWritePoint.DELETION_MARKED).delete(id, record(second)))
        val pending = snapshot()
        assertNull(pending.currentId)
        assertEquals(LibraryEntryState.DELETION_PENDING, pending.entries.single { it.id == id }.state)
        assertTrue(Files.exists(entryPath(id)))
        assertEquals(PreviewLibraryResult.Conflict, library().save(id, record(second), PreviewDraftFields("resurrect")))
        assertEquals(PreviewLibraryResult.Conflict, library().recover(id))
        snapshot(library().delete(id, null))
        assertFalse(Files.exists(entryPath(id)))
        assertEquals(listOf(first.currentId), snapshot().entries.map { it.id })
        assertEquals("keep", record(snapshot(library().select(first.currentId!!))).fields.title)
    }

    @Test fun `legacy migration moves immutable history intact and never reimports after explicit deletion`() {
        val old = Files.createDirectory(root.resolve("old-guest"))
        val new = Files.createDirectory(root.resolve("new-library"))
        val adapter = PreviewDraftPersistence(LocalDraftStore(old))
        val first = (adapter.save(null, PreviewDraftFields("earlier", "id", "pinned", "c".repeat(64))) as PreviewDraftSave.Saved).record
        val last = (adapter.save(first, PreviewDraftFields("latest")) as PreviewDraftSave.Saved).record
        val migrated = snapshot(PreviewDraftLibrary(new, old).read())
        assertEquals(PreviewDraftLibrary.LEGACY_ID, migrated.currentId)
        assertEquals(last.baselineIdentity, record(migrated).baselineIdentity)
        assertEquals(first.fields, record(migrated).history.first().fields)
        assertFalse(Files.exists(old))
        snapshot(PreviewDraftLibrary(new, old).delete(migrated.currentId!!, record(migrated)))
        assertTrue(snapshot(PreviewDraftLibrary(new, old).read()).entries.isEmpty())
    }

    @Test fun `migration interrupted after move reuses its reserved identity without duplicating history`() {
        val old = Files.createDirectory(root.resolve("old"))
        val new = Files.createDirectory(root.resolve("new"))
        PreviewDraftPersistence(LocalDraftStore(old)).save(null, PreviewDraftFields("retained"))
        val interrupted = PreviewDraftLibrary(new, old) { if (it == LibraryWritePoint.LEGACY_MOVED) throw IOException() }
        assertEquals(PreviewLibraryResult.Uncertain, interrupted.read())
        val reopened = snapshot(PreviewDraftLibrary(new, old).read())
        assertEquals(1, reopened.entries.size)
        assertEquals(PreviewDraftLibrary.LEGACY_ID, reopened.currentId)
        assertEquals("retained", record(reopened).fields.title)
    }

    @Test fun `bounds include unregistered records and do not evict earlier drafts`() {
        for (index in 0 until PreviewDraftLibrary.MAX_RECORDS) snapshot(library().create(PreviewDraftFields("$index")))
        val before = snapshot()
        assertEquals(PreviewLibraryResult.Failed(DraftStorageFailure.LIMIT_EXCEEDED), library().create())
        assertEquals(before.entries.map { it.id }, snapshot().entries.map { it.id })
        assertEquals(before.currentId, snapshot().currentId)
    }

    @Test fun `unsupported malformed and oversized index versions fail closed without rewriting records`() {
        val created = snapshot(library().create(PreviewDraftFields("keep")))
        val index = root.resolve("index.json")
        val original = Files.readString(index)
        for (modified in listOf(original.replace("\"local_library_version\":1", "\"local_library_version\":2"),
            original.replace("\"local_library_version\":1", "\"local_library_version\":4294967297"),
            "{}", "x".repeat(16_385))) {
            Files.writeString(index, modified)
            assertTrue(library().read() is PreviewLibraryResult.Failed)
            assertTrue(library().create() is PreviewLibraryResult.Failed)
            assertEquals(modified, Files.readString(index))
        }
        Files.writeString(index, original)
        assertEquals(created.currentId, snapshot().currentId)
        assertEquals("keep", record(snapshot()).fields.title)
    }

    @Test fun `restoration binds identity even when two drafts have identical revision bytes`() {
        val first = snapshot(library().create())
        val checkpoint = PreviewLibraryRestoration(first.currentId!!.value,
            PreviewDraftRestoration.capture(record(first), true, false))
        assertTrue(checkpoint.canResume(snapshot()))
        val second = snapshot(library().create())
        assertEquals(record(first).baselineIdentity, record(second).baselineIdentity)
        assertFalse(checkpoint.canResume(second))
    }

    @Test fun `cross-instance contention cannot modify library while another transaction holds its lock`() {
        snapshot(library().read())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val held = PreviewDraftLibrary(root, null) {
            if (it == LibraryWritePoint.INDEX_STAGED) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        try {
            val future = executor.submit<PreviewLibraryResult> { held.create(PreviewDraftFields("one transaction")) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(PreviewLibraryResult.Failed(DraftStorageFailure.BUSY), library().create())
            assertEquals(PreviewLibraryResult.Failed(DraftStorageFailure.BUSY), library().read())
            release.countDown()
            assertTrue(future.get(10, TimeUnit.SECONDS) is PreviewLibraryResult.Available)
            assertEquals(1, snapshot().entries.size)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
