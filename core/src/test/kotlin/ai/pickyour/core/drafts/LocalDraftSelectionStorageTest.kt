package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalDraftSelectionStorageTest {
    @TempDir lateinit var root: Path
    private val mapper = SelectionFixtures.mapper
    private val catalog = SelectionFixtures.catalog()
    private val model = SelectionFixtures.selection()
    private val add = SelectionCommand.Add(model, SelectionFixtures.choice(catalog, model))
    private fun store() = LocalDraftStore(root)
    private fun loaded() = (store().load() as DraftLoad.Available).draft
    private fun cache() = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
    private fun raw() = (cache().active() as CacheRead.Available).bytes
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun seed(bytes: ByteArray) { store(); assertTrue(cache().install(hash(bytes), bytes.inputStream()) is CacheInstall.Activated) }
    private fun predecessor(version: Int = 3) = SelectionFixtures.resource("/drafts/local-format-$version.json")
    private fun saved(result: DraftSave) = (result as DraftSave.Saved).draft

    @Test fun `fixed v2 and v3 reads and noops preserve bytes until an atomic command migrates all retained entries`() {
        for (format in listOf(2, 3)) {
            val bytes = predecessor(format)
            seed(bytes)
            val old = loaded()
            assertEquals(format, old.localFormatVersion)
            assertEquals(listOf(0L, 1L, 2L), old.history.map { it.number })
            assertTrue(old.history.all { it.content.components == DraftSelections() && it.selectionChange == null })
            assertArrayEquals(bytes, raw())
            val noWrites = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { error("No-op cannot write") } }
            assertEquals(old.version.hash, saved(noWrites.save(old.version, old.content)).version.hash)
            assertArrayEquals(bytes, raw())
            val intent = DraftSelectionIntent(old.version, add)
            val result = store().applySelection(intent, catalog) as DraftSelectionSave.Saved
            val migrated = loaded()
            assertEquals(4, migrated.localFormatVersion)
            assertEquals(3L, migrated.version.number)
            assertEquals(result.draft.content, migrated.content)
            assertEquals(old.content, migrated.content.withComponents(DraftSelections()))
            assertEquals(old.metadata, migrated.metadata)
            assertEquals(old.legacySourceHash, migrated.legacySourceHash)
            assertEquals(SelectionAction.ADD, migrated.history.last().selectionChange!!.action)
            old.history.zip(migrated.history).forEach { (before, after) -> assertRevision(before, after) }
            assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
            assertEquals(format, (store().revision(old.version.hash) as DraftLoad.Available).draft.localFormatVersion)
            assertEquals(DraftSelectionSave.Conflict, store().applySelection(intent, catalog))
            assertEquals(DraftSave.Conflict, store().restore(old.version, 0))
            assertEquals(migrated.version.hash, saved(store().save(migrated.version, migrated.content)).version.hash)
            val restored = saved(store().restore(migrated.version, 0))
            assertEquals(old.history[0].content, restored.content)
            assertEquals(old.metadata, restored.metadata)
            assertNull(restored.history.last().selectionChange)
            assertEquals(0L, restored.history.last().restoredFrom)
        }
    }

    @Test fun `v3 legacy source identity restore indices and historical metadata remain intact`() {
        val node = mapper.readTree(predecessor()) as ObjectNode
        node.put("revision", 9).put("legacy_source_hash", "a".repeat(64))
        node["history"].forEachIndexed { index, entry -> (entry as ObjectNode).put("number", 7 + index) }
        (node["history"][0] as ObjectNode).put("change", "LEGACY_ANCHOR")
        (node["history"][2] as ObjectNode).put("restored_from", 7)
        val bytes = mapper.writeValueAsBytes(node)
        seed(bytes)
        val old = loaded()
        val restored = saved(store().restore(old.version, 8))
        assertEquals(4, restored.localFormatVersion)
        assertEquals(listOf(7L, 8L, 9L, 10L), restored.history.map { it.number })
        assertEquals(old.legacySourceHash, restored.legacySourceHash)
        assertTrue(restored.earlierHistoryUnavailable)
        assertEquals(old.history[1].content, restored.content)
        assertEquals(old.metadata, restored.metadata)
        old.history.zip(restored.history).forEach { (before, after) -> assertRevision(before, after) }
        assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun `migration failure retains original while activation and outer cleanup failures require reopen`() {
        val bytes = predecessor()
        seed(bytes)
        val old = loaded()
        val intent = DraftSelectionIntent(old.version, add)
        val failing = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_STAGED) throw IOException("injected")
        } }
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.IO_FAILURE), failing.applySelection(intent, catalog))
        assertArrayEquals(bytes, raw())
        val uncertain = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("injected")
        } }
        val outcome = uncertain.applySelection(intent, catalog) as DraftSelectionSave.Uncertain
        assertEquals(outcome.hash, loaded().version.hash)
        assertEquals(listOf(model), loaded().content.components.selections)
        assertEquals(4, loaded().history.size)
        assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
        assertEquals(DraftSelectionSave.Conflict, store().applySelection(intent, catalog))
        val reopened = loaded()
        val cleanup = LocalDraftStore(root, { VerifiedArtifactCache(it, LocalDraftStore.MAX_BYTES) }, { throw IOException("cleanup") })
        val last = cleanup.applySelection(DraftSelectionIntent(reopened.version, SelectionCommand.SetEnabled(model.id, false))) as DraftSelectionSave.Uncertain
        assertEquals(last.hash, loaded().version.hash)
        assertFalse(loaded().content.components.selections.single().enabled)
    }

    @Test fun `all typed kinds and links survive reopen and restore without metadata loss`() {
        val selections = mapper.readTree(SelectionFixtures.resource("/contracts/v1/examples/build.json"))["selections"].map { LocalSelection.fromJson(it as ObjectNode) }
        val link = SelectionFixtures.link("link.one", selections[0].id, selections[1].id, false)
        val original = DraftContent(primaryModel = "opaque", components = DraftSelections(selections, listOf(link)))
        val first = saved(store().save(null, original, mapOf("unknown" to "old")))
        assertEquals(original, loaded().content)
        val later = saved(store().save(first.version, DraftContent(), mapOf("unknown" to "new")))
        val restored = saved(store().restore(later.version, 0))
        assertEquals(original, restored.content)
        assertEquals(mapOf("unknown" to "new"), restored.metadata)
        assertEquals(mapOf("unknown" to "old"), restored.history.first().metadata)
        assertEquals(original, (store().revision(first.version.hash) as DraftLoad.Available).draft.content)
    }

    @Test fun `invalid components descriptions and future formats block writes without replacing bytes`() {
        val first = saved(store().save(null, DraftContent(components = DraftSelections(listOf(model)))))
        val original = raw()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("local_format_version", 5) },
            { (it["history"][0]["content"] as ObjectNode).remove("components") },
            { (it["history"][0]["content"] as ObjectNode).putNull("components") },
            { components(it).put("unknown", true) },
            { components(it).put("selections", "opaque") },
            { components(it).putArray("links").add(SelectionFixtures.link("link.missing", model.id, "missing").toJson()) },
            { (components(it)["selections"] as ArrayNode).add(model.toJson()) },
            { (components(it)["selections"][0] as ObjectNode).put("enabled", "true") },
            { (components(it)["selections"][0] as ObjectNode).put("role", "") },
            { (components(it)["selections"][0] as ObjectNode).putObject("settings").put("unknown", 1) },
            { (it["history"][0] as ObjectNode).remove("selection_change") },
            { (it["history"][0] as ObjectNode).putObject("selection_change").put("action", "ADD") },
        )
        for ((index, mutate) in mutations.withIndex()) {
            val bytes = mapper.writeValueAsBytes((mapper.readTree(original) as ObjectNode).also(mutate))
            seed(bytes)
            val reason = if (index == 0) DraftStorageFailure.UNSUPPORTED_VERSION else DraftStorageFailure.CORRUPT
            assertEquals(DraftLoad.Failed(reason), store().load())
            assertEquals(DraftSelectionSave.Failed(reason), store().applySelection(DraftSelectionIntent(first.version, add), catalog))
            assertArrayEquals(bytes, raw())
        }
        val smuggled = mapper.readTree(predecessor()) as ObjectNode
        (smuggled["history"][0]["content"] as ObjectNode).putObject("components").putArray("selections")
        val bytes = mapper.writeValueAsBytes(smuggled)
        seed(bytes)
        assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
        assertArrayEquals(bytes, raw())
    }

    @Test fun `descriptions cannot invent changes or missing incident links`() {
        val second = SelectionFixtures.selection("hosting_platform", "host")
        val link = SelectionFixtures.link("link.one", model.id, second.id)
        val first = saved(store().save(null, DraftContent(components = DraftSelections(listOf(model, second), listOf(link)))))
        store().applySelection(DraftSelectionIntent(first.version, SelectionCommand.Remove(model.id)))
        val original = raw()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { (it["history"][1]["selection_change"] as ObjectNode).put("selection_id", "missing") },
            { (it["history"][1]["selection_change"] as ObjectNode).put("action", "ENABLE") },
            { (it["history"][1]["selection_change"] as ObjectNode).putArray("affected_link_ids") },
            { (it["history"][1]["content"] as ObjectNode).put("context", "not-a-selection-edit") },
        )
        for (mutate in mutations) {
            val bytes = mapper.writeValueAsBytes((mapper.readTree(original) as ObjectNode).also(mutate))
            seed(bytes)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
            assertArrayEquals(bytes, raw())
        }
    }

    @Test fun `v3 expansion over byte budget never discards acknowledged history or old artifacts`() {
        val node = mapper.readTree(predecessor()) as ObjectNode
        val template = node["history"][0].deepCopy<ObjectNode>()
        val entries = node.putArray("history")
        val count = 64
        repeat(count) { index -> entries.add(template.deepCopy().put("number", index).put("change", if (index == 0) "INITIAL" else "EDIT")) }
        node.put("revision", count - 1)
        val padding = LocalDraftStore.MAX_BYTES - 2048 - mapper.writeValueAsBytes(node).size
        val perEntry = padding / (count - 1)
        val remainder = padding % (count - 1)
        assertTrue(perEntry + 1 <= 16_384)
        for (index in 1 until count) (entries[index]["content"] as ObjectNode).put("objective", "x".repeat(perEntry + if (index <= remainder) 1 else 0))
        val bytes = mapper.writeValueAsBytes(node)
        assertEquals(LocalDraftStore.MAX_BYTES - 2048, bytes.size)
        seed(bytes)
        val old = loaded()
        assertEquals(old.version.hash, saved(store().save(old.version, old.content)).version.hash)
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().save(old.version, DraftContent(context = "short")))
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().restore(old.version, 0))
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().applySelection(DraftSelectionIntent(old.version, add), catalog))
        assertArrayEquals(bytes, raw())
        assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
        assertEquals(old.version.hash, loaded().version.hash)
        old.history.zip(loaded().history).forEach { (before, after) -> assertRevision(before, after) }
    }

    @Test fun `history selection numeric and nested envelope limits never trim work`() {
        val node = mapper.readTree(predecessor()) as ObjectNode
        val template = node["history"][0].deepCopy<ObjectNode>()
        val entries = node.putArray("history")
        repeat(LocalDraftStore.MAX_HISTORY) { index -> entries.add(template.deepCopy().put("number", index).put("change", if (index == 0) "INITIAL" else "EDIT")) }
        node.put("revision", LocalDraftStore.MAX_HISTORY - 1)
        val bytes = mapper.writeValueAsBytes(node)
        seed(bytes)
        val old = loaded()
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().applySelection(DraftSelectionIntent(old.version, add), catalog))
        assertEquals(old.version.hash, saved(store().save(old.version, old.content)).version.hash)
        assertArrayEquals(bytes, raw())
        val templateSelection = SelectionFixtures.node()
        assertThrows(IllegalArgumentException::class.java) { DraftSelections((0..DraftSelections.MAX_SELECTIONS).map {
            LocalSelection.fromJson(templateSelection.deepCopy().put("id", "sel_$it").put("role", "role_$it"))
        }) }
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(listOf(model), (0..DraftSelections.MAX_LINKS).map {
            SelectionFixtures.link("link.$it", model.id, model.id)
        }) }
        val hugeNumber = SelectionFixtures.node("accelerator").also { it.putObject("settings").put("device_count", java.math.BigInteger("9".repeat(129))) }
        assertThrows(IllegalArgumentException::class.java) { LocalSelection.fromJson(hugeNumber) }
        val nested = model.toJson()
        var part = nested.putObject("extensions").putObject("x_nested")
        repeat(26) { part = part.putObject("next") }
        part.put("value", true)
        val allowedPayload = LocalSelection.fromJson(nested)
        seed(predecessor())
        val short = loaded()
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().save(short.version, short.content.withComponents(DraftSelections(listOf(allowedPayload)))))
        assertArrayEquals(predecessor(), raw())
    }

    @Test fun `concurrent intent replay permits at most one acknowledgement and interrupted work never mutates`() {
        val first = saved(store().save(null, DraftContent()))
        val intent = DraftSelectionIntent(first.version, add)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..1).map { pool.submit<DraftSelectionSave> { gate.await(); store().applySelection(intent, catalog) } }
            gate.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is DraftSelectionSave.Saved })
            assertEquals(1, results.count { it == DraftSelectionSave.Conflict || it == DraftSelectionSave.Failed(DraftStorageFailure.BUSY) })
            assertEquals(1L, loaded().version.number)
        } finally { pool.shutdownNow() }
        val current = loaded()
        val alreadyOpen = store()
        Thread.currentThread().interrupt()
        try {
            assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.CANCELLED), alreadyOpen.applySelection(DraftSelectionIntent(current.version, SelectionCommand.Remove(model.id))))
        } finally { Thread.interrupted() }
        assertEquals(current.version.hash, loaded().version.hash)
    }

    private fun components(node: ObjectNode) = node["history"][0]["content"]["components"] as ObjectNode
    private fun assertRevision(before: StoredRevision, after: StoredRevision) {
        assertEquals(before.number, after.number)
        assertEquals(before.change, after.change)
        assertEquals(before.restoredFrom, after.restoredFrom)
        assertEquals(before.content, after.content)
        assertEquals(before.metadata, after.metadata)
        assertEquals(before.selectionChange, after.selectionChange)
    }
}
