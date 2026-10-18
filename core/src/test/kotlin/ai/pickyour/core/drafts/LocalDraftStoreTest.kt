package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalDraftStoreTest {
    @TempDir lateinit var root: Path
    private fun store() = LocalDraftStore(root)
    private fun saved(result: DraftSave) = (result as DraftSave.Saved).draft
    private fun loaded() = (store().load() as DraftLoad.Available).draft
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun cache() = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
    private fun raw() = (cache().active() as CacheRead.Available).bytes
    private fun seed(bytes: ByteArray) { assertTrue(cache().install(hash(bytes), bytes.inputStream()) is CacheInstall.Activated) }

    @Test fun `empty partial and populated inputs reopen without invented values`() {
        val empty = saved(store().save(null, DraftContent()))
        assertEquals(0, empty.version.number)
        assertEquals(DraftContent(), loaded().content)
        val partial = DraftContent(objective = "", primaryModel = "unresolved", optional = mapOf(
            OptionalTier.T3 to TierDraft(Activation.UNKNOWN, setOf("entered-not-validated"), mapOf("raw-price" to "0.00"))))
        val next = saved(store().save(empty.version, partial, mapOf("unresolved-lock" to "")))
        assertEquals(partial, loaded().content)
        assertNull(loaded().content.context)
        assertEquals(mapOf("unresolved-lock" to ""), loaded().metadata)
        val populated = DraftContent("objective", "context", "model", partial.optional)
        val preserved = saved(store().save(next.version, populated))
        assertEquals(mapOf("unresolved-lock" to ""), loaded().metadata)
        saved(store().save(preserved.version, populated, emptyMap()))
        assertTrue(loaded().metadata.isEmpty())
        assertEquals(populated, loaded().content)
        assertNull(empty.content.primaryModel)
        assertEquals(DraftContent(), (store().revision(empty.version.hash) as DraftLoad.Available).draft.content)
    }

    @Test fun `stale missing and foreign tokens cannot overwrite current revision`() {
        val first = saved(store().save(null, DraftContent()))
        val next = saved(store().save(first.version, DraftContent(context = "new")))
        assertEquals(DraftSave.Conflict, store().save(first.version, DraftContent(context = "stale")))
        assertEquals(DraftSave.Conflict, store().save(null, DraftContent()))
        val other = LocalDraftStore(root.resolve("other"))
        val foreign = saved(other.save(null, DraftContent()))
        assertEquals(DraftSave.Conflict, store().save(foreign.version, DraftContent()))
        assertEquals(next.version.hash, loaded().version.hash)
    }

    @Test fun `unknown version malformed truncated and unknown fields remain intact and block save`() {
        saved(store().save(null, DraftContent()))
        val original = raw()
        val mapper = ObjectMapper()
        val unsupported = (mapper.readTree(original) as ObjectNode).put("local_format_version", 3)
        val extra = (mapper.readTree(original) as ObjectNode).put("future-field", "retain-me")
        val cases = listOf(
            mapper.writeValueAsBytes(unsupported) to DraftStorageFailure.UNSUPPORTED_VERSION,
            mapper.writeValueAsBytes(extra) to DraftStorageFailure.CORRUPT,
            original.copyOf(original.size / 2) to DraftStorageFailure.CORRUPT,
            "not-json".toByteArray() to DraftStorageFailure.CORRUPT,
        )
        for ((bytes, reason) in cases) {
            seed(bytes)
            assertEquals(DraftLoad.Failed(reason), store().load())
            assertEquals(DraftSave.Failed(reason), store().save(null, DraftContent()))
            assertArrayEquals(bytes, raw())
        }
    }

    @Test fun `disk corruption is explicit and never reset to empty`() {
        val first = saved(store().save(null, DraftContent(context = "keep")))
        val file = root.resolve("revisions/${first.version.hash.hex}.bin")
        Files.writeString(file, "truncated")
        assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
        assertEquals(DraftSave.Failed(DraftStorageFailure.CORRUPT), store().save(first.version, DraftContent()))
        assertEquals("truncated", Files.readString(file))
    }

    @Test fun `preactivation failure keeps prior acknowledged state and postactivation reports uncertainty`() {
        val first = saved(store().save(null, DraftContent(context = "old")))
        val failing = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_STAGED) throw IOException("synthetic disk failure")
        } }
        assertEquals(DraftSave.Failed(DraftStorageFailure.IO_FAILURE), failing.save(first.version, DraftContent(context = "new")))
        assertEquals(first.version.hash, loaded().version.hash)
        val uncertain = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("synthetic sync failure")
        } }
        val result = uncertain.save(first.version, DraftContent(context = "new")) as DraftSave.Uncertain
        assertEquals(result.hash, loaded().version.hash)
        assertEquals("new", loaded().content.context)
        assertEquals(DraftSave.Conflict, store().save(first.version, DraftContent(context = "retry")))
    }

    @Test fun `outer cleanup failure after activation is uncertain never a failed save`() {
        val first = saved(store().save(null, DraftContent(context = "old")))
        val failing = LocalDraftStore(root, { VerifiedArtifactCache(it, LocalDraftStore.MAX_BYTES) },
            { throw IOException("synthetic outer cleanup failure") })
        val result = failing.save(first.version, DraftContent(context = "new")) as DraftSave.Uncertain
        assertEquals(result.hash, loaded().version.hash)
        assertEquals("new", loaded().content.context)
    }

    @Test fun `excessive input rejected without overwriting previous draft`() {
        val first = saved(store().save(null, DraftContent()))
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            store().save(first.version, DraftContent(objective = "x".repeat(16_385))))
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            store().save(first.version, DraftContent(objective = "x".repeat(LocalDraftStore.MAX_BYTES + 1))))
        assertEquals(first.version.hash, loaded().version.hash)
    }

    @Test fun `concurrent writers cannot both acknowledge same expected head`() {
        val first = saved(store().save(null, DraftContent()))
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf("one", "two").map { value -> pool.submit<DraftSave> {
                gate.await(); store().save(first.version, DraftContent(context = value))
            } }
            gate.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is DraftSave.Saved })
            assertEquals(1, results.count { it == DraftSave.Conflict || it == DraftSave.Failed(DraftStorageFailure.BUSY) })
            assertEquals(1, loaded().version.number)
        } finally { pool.shutdownNow() }
    }

    @Test fun `outer file lock blocks reads and writes without touching data`() {
        val first = saved(store().save(null, DraftContent()))
        FileChannel.open(root.resolve("transaction.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            assertEquals(DraftLoad.Failed(DraftStorageFailure.BUSY), store().load())
            assertEquals(DraftSave.Failed(DraftStorageFailure.BUSY), store().save(first.version, DraftContent(context = "new")))
        } }
        assertEquals(first.version.hash, loaded().version.hash)
    }
}
