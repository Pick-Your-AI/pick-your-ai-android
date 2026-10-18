package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LocalDraftHistoryStorageTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private fun store() = LocalDraftStore(root)
    private fun saved(result: DraftSave) = (result as DraftSave.Saved).draft
    private fun loaded() = (store().load() as DraftLoad.Available).draft
    private fun cache() = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
    private fun raw() = (cache().active() as CacheRead.Available).bytes
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun seed(bytes: ByteArray) { store(); assertTrue(cache().install(hash(bytes), bytes.inputStream()) is CacheInstall.Activated) }
    private fun legacy(revision: Long): ByteArray = """{
        "local_format_version":1,"revision":$revision,
        "content":{"objective":"","context":null,"primaryModel":"entered",
        "optional":{"T3":{"activation":"UNKNOWN","selections":["raw"],"settings":{"keep":"0.00"}},
        "T4":{"activation":"DISABLED","selections":[],"settings":{}},
        "T5":{"activation":"ENABLED","selections":[],"settings":{}}}},
        "metadata":{"catalog":"old-pin","blank":""}}
    """.toByteArray()

    @Test fun `v1 reads do not migrate and first save preserves anchor without fabricating missing history`() {
        for (number in listOf(0L, 7L)) {
            val bytes = legacy(number)
            seed(bytes)
            val original = loaded()
            assertEquals(1, original.localFormatVersion)
            assertEquals(listOf(number), original.history.map { it.number })
            assertEquals(number > 0, original.earlierHistoryUnavailable)
            assertArrayEquals(bytes, raw())
            val migrated = saved(store().save(original.version, DraftContent(context = "next")))
            assertEquals(3, migrated.localFormatVersion)
            assertEquals(listOf(number, number + 1), migrated.history.map { it.number })
            assertEquals(original.content, migrated.history.first().content)
            assertEquals(original.metadata, migrated.metadata)
            assertEquals(hash(bytes), migrated.legacySourceHash)
            assertEquals(StoredRevisionChange.LEGACY_ANCHOR, migrated.history.first().change)
            assertArrayEquals(bytes, (cache().pinned(hash(bytes)) as CacheRead.Available).bytes)
            assertEquals(1, (store().revision(hash(bytes)) as DraftLoad.Available).draft.localFormatVersion)
            assertEquals(migrated.version.hash, loaded().version.hash)
            if (number > 0) assertEquals(DraftSave.UnknownRevision, store().restore(migrated.version, 0))
            val restored = saved(store().restore(migrated.version, number))
            assertEquals(original.content, restored.content)
            assertEquals(number + 2, restored.version.number)
        }
    }

    @Test fun `migration failure preserves legacy head or reports uncertainty without losing original artifact`() {
        val bytes = legacy(9)
        seed(bytes)
        val original = loaded()
        val failing = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_STAGED) throw IOException("migration write failure")
        } }
        assertEquals(DraftSave.Failed(DraftStorageFailure.IO_FAILURE), failing.restore(original.version, 9))
        assertEquals(1, loaded().localFormatVersion)
        assertArrayEquals(bytes, raw())
        val uncertain = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("migration sync failure")
        } }
        val result = uncertain.restore(original.version, 9) as DraftSave.Uncertain
        val migrated = loaded()
        assertEquals(result.hash, migrated.version.hash)
        assertEquals(listOf(9L, 10L), migrated.history.map { it.number })
        assertEquals(StoredRevisionChange.RESTORE, migrated.history.last().change)
        assertEquals(original.content, migrated.content)
        assertEquals(original.metadata, migrated.metadata)
        assertArrayEquals(bytes, (cache().pinned(hash(bytes)) as CacheRead.Available).bytes)
        assertEquals(DraftSave.Conflict, store().save(original.version, DraftContent()))
    }

    @Test fun `invalid legacy records are preserved instead of guessed during migration`() {
        val bytes = legacy(3)
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.remove("metadata") },
            { it.put("revision", -1) },
            { (it["content"]["optional"] as ObjectNode).remove("T4") },
            { it.put("unrecognized", "preserve") },
        )
        for (mutate in mutations) {
            val node = mapper.readTree(bytes) as ObjectNode
            mutate(node)
            val malformed = mapper.writeValueAsBytes(node)
            seed(malformed)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
            assertEquals(DraftSave.Failed(DraftStorageFailure.CORRUPT), store().save(null, DraftContent()))
            assertArrayEquals(malformed, raw())
        }
    }

    @Test fun `edit restore edit keeps earlier history immutable and current metadata`() {
        val first = saved(store().save(null, DraftContent(context = "first"), mapOf("pin" to "old")))
        val next = saved(store().save(first.version, DraftContent(context = "second"), mapOf("pin" to "new")))
        val restored = saved(store().restore(next.version, 0))
        assertEquals(listOf("first", "second", "first"), loaded().history.map { it.content.context })
        assertEquals(mapOf("pin" to "new"), restored.metadata)
        assertEquals(mapOf("pin" to "old"), restored.history.first().metadata)
        assertEquals(StoredRevisionChange.RESTORE, restored.history.last().change)
        assertEquals(0, restored.history.last().restoredFrom)
        assertEquals(1, first.history.size)
        assertEquals(2, next.history.size)
        assertEquals(DraftSave.Conflict, store().restore(next.version, 0))
        assertEquals(DraftSave.UnknownRevision, store().restore(restored.version, -1))
        assertEquals(DraftSave.UnknownRevision, store().restore(restored.version, 10))
        val final = saved(store().save(restored.version, DraftContent(context = "third")))
        assertEquals(4, final.history.size)
        assertEquals(first.version.hash, (store().revision(first.version.hash) as DraftLoad.Available).draft.version.hash)
        assertThrows(UnsupportedOperationException::class.java) { (final.history as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (final.history.first().metadata as MutableMap).clear() }
        assertFalse(final.earlierHistoryUnavailable)
    }

    @Test fun `restoring current content still appends a revision`() {
        val initial = saved(store().save(null, DraftContent()))
        val restored = saved(store().restore(initial.version, 0))
        assertEquals(initial.content, restored.content)
        assertEquals(1, restored.version.number)
        assertNotEquals(initial.version.hash, restored.version.hash)
    }

    @Test fun `repeated current saves retain the same durable revision without writing`() {
        val content = DraftContent(context = "retained", optional = mapOf(
            OptionalTier.T3 to TierDraft(Activation.UNKNOWN, setOf("unresolved"), mapOf("raw" to "0.00"))))
        val first = saved(store().save(null, content, mapOf("pin" to "one")))
        val bytes = raw()
        var cacheWrites = 0
        val noWrites = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            cacheWrites++
            throw IOException("A no-op must not attempt an artifact write")
        } }
        repeat(3) {
            val same = saved(noWrites.save(first.version, content))
            assertEquals(first.version.hash, same.version.hash)
            assertEquals(first.version.number, same.version.number)
            assertEquals(1, same.history.size)
        }
        val explicit = saved(noWrites.save(first.version, content, mapOf("pin" to "one")))
        assertEquals(first.version.hash, explicit.version.hash)
        assertEquals(0, cacheWrites)
        assertArrayEquals(bytes, raw())
        assertEquals(first.version.hash, loaded().version.hash)
        val cleared = saved(store().save(first.version, content, emptyMap()))
        assertEquals(1, cleared.version.number)
        assertTrue(loaded().metadata.isEmpty())
    }

    @Test fun `identical input cannot turn a stale missing or foreign token into a successful save`() {
        val first = saved(store().save(null, DraftContent(context = "initial")))
        val current = saved(store().save(first.version, DraftContent(context = "current")))
        assertEquals(DraftSave.Conflict, store().save(first.version, current.content, current.metadata))
        assertEquals(DraftSave.Conflict, store().save(null, current.content, current.metadata))
        val foreign = LocalDraftStore(java.nio.file.Files.createDirectory(root.resolve("other")))
        val other = saved(foreign.save(null, current.content, current.metadata))
        assertEquals(DraftSave.Conflict, store().save(other.version, current.content, current.metadata))
        assertEquals(current.version.hash, loaded().version.hash)
    }

    @Test fun `unchanged legacy save still performs the explicit migration once`() {
        val bytes = legacy(7)
        seed(bytes)
        val original = loaded()
        val migrated = saved(store().save(original.version, original.content))
        assertEquals(3, migrated.localFormatVersion)
        assertEquals(listOf(7L, 8L), migrated.history.map { it.number })
        assertEquals(original.content, migrated.content)
        assertEquals(original.metadata, migrated.metadata)
        assertEquals(hash(bytes), migrated.legacySourceHash)
        assertArrayEquals(bytes, (cache().pinned(hash(bytes)) as CacheRead.Available).bytes)
        assertEquals(migrated.version.hash, saved(store().save(migrated.version, migrated.content)).version.hash)
    }

    @Test fun `history bound rejects edits and restores without dropping acknowledged revisions`() {
        var draft = saved(store().save(null, DraftContent()))
        repeat(LocalDraftStore.MAX_HISTORY - 1) { draft = saved(store().save(draft.version, DraftContent(context = "edit-$it"))) }
        assertEquals(LocalDraftStore.MAX_HISTORY, loaded().history.size)
        assertEquals(draft.version.hash, saved(store().save(draft.version, draft.content)).version.hash)
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().save(draft.version, DraftContent()))
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().restore(draft.version, 0))
        assertEquals(draft.version.hash, loaded().version.hash)
        assertEquals(0, loaded().history.first().number)
    }

    @Test fun `legacy exhausted counter remains readable and never wraps during migration`() {
        val bytes = legacy(Long.MAX_VALUE)
        seed(bytes)
        val draft = loaded()
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().save(draft.version, draft.content))
        assertArrayEquals(bytes, raw())
    }

    @Test fun `invalid history identity restore lineage and metadata reject without reset`() {
        val initial = saved(store().save(null, DraftContent(context = "first"), mapOf("pin" to "one")))
        val edited = saved(store().save(initial.version, DraftContent(context = "second"), mapOf("pin" to "two")))
        saved(store().restore(edited.version, 0))
        val original = raw()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("revision", 9) },
            { (it["history"][1] as ObjectNode).put("number", 7) },
            { (it["history"][1] as ObjectNode).put("change", "INITIAL") },
            { (it["history"][2] as ObjectNode).put("restored_from", 2) },
            { (it["history"][2]["content"] as ObjectNode).put("context", "invented") },
            { (it["history"][2]["metadata"] as ObjectNode).put("pin", "old") },
            { it.put("legacy_source_hash", "0".repeat(64)) },
            { (it["history"][0] as ObjectNode).put("change", "LEGACY_ANCHOR") },
        )
        for (mutate in mutations) {
            val node = mapper.readTree(original) as ObjectNode
            mutate(node)
            val bytes = mapper.writeValueAsBytes(node)
            seed(bytes)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
            assertEquals(DraftSave.Failed(DraftStorageFailure.CORRUPT), store().save(null, DraftContent()))
            assertArrayEquals(bytes, raw())
        }
    }

    @Test fun `abrupt separate JVM exits preserve old or new complete history at activation boundary`() {
        for (point in listOf("before", "after")) {
            val current = when (val loaded = store().load()) {
                is DraftLoad.Available -> loaded.draft.version
                else -> null
            }
            val initial = saved(store().save(current, DraftContent(context = "before crash")))
            probe(point, 23)
            val reopened = loaded()
            if (point == "before") assertEquals(initial.version.hash, reopened.version.hash)
            else {
                assertEquals("after crash", reopened.content.context)
                assertEquals(initial.history.size + 1, reopened.history.size)
                assertEquals(initial.content, reopened.history[reopened.history.size - 2].content)
            }
            assertEquals("${reopened.history.size}:" + reopened.content.context, probe("read", 0))
            saved(store().restore(reopened.version, reopened.history.first().number))
        }
    }

    private fun probe(mode: String, expectedExit: Int): String {
        val classes = listOf(HistoryStoreSubprocess::class.java, LocalDraftStore::class.java, kotlin.Unit::class.java,
            ObjectMapper::class.java, com.fasterxml.jackson.core.JsonFactory::class.java, com.fasterxml.jackson.annotation.JsonCreator::class.java)
        val classpath = classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
            HistoryStoreSubprocess::class.java.name, root.toString(), mode).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(expectedExit, process.exitValue(), output + process.errorStream.bufferedReader().readText())
            return output
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}

internal object HistoryStoreSubprocess {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val mode = args[1]
        val store = if (mode == "read") LocalDraftStore(root) else LocalDraftStore(root) { path ->
            VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
                val point = if (mode == "before") CacheWritePoint.POINTER_STAGED else CacheWritePoint.POINTER_ACTIVATED
                if (it == point) Runtime.getRuntime().halt(23)
            }
        }
        val current = (store.load() as DraftLoad.Available).draft
        if (mode == "read") println("${current.history.size}:" + current.content.context)
        else {
            store.save(current.version, DraftContent(context = "after crash"))
            error("Crash checkpoint not reached")
        }
    }
}
