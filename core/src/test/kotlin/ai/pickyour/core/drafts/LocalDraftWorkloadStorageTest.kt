package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.contracts.ContractDecimal
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Path
import java.security.MessageDigest

class LocalDraftWorkloadStorageTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private fun store() = LocalDraftStore(root)
    private fun saved(result: DraftSave) = (result as DraftSave.Saved).draft
    private fun loaded() = (store().load() as DraftLoad.Available).draft
    private fun cache() = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
    private fun raw() = (cache().active() as CacheRead.Available).bytes
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun seed(bytes: ByteArray) {
        store()
        assertTrue(cache().install(hash(bytes), bytes.inputStream()) is CacheInstall.Activated)
    }
    // Fixed predecessor fixture has no workload field; do not manufacture it with the current writer.
    private fun v2() = javaClass.getResourceAsStream("/drafts/local-format-2.json")!!.use { it.readBytes() }
    private fun workload(node: ObjectNode) = node["history"][0]["content"]["workload"] as ObjectNode

    @Test fun `empty current format writes every unanswered field explicitly and retains unknown versus zero`() {
        val empty = saved(store().save(null, DraftContent()))
        assertEquals(4, empty.localFormatVersion)
        val inputs = workload(mapper.readTree(raw()) as ObjectNode)
        assertEquals(9, inputs.size())
        assertTrue(inputs.all { it.isNull })
        assertEquals(DraftWorkload(), loaded().content.workload)
        val zero = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("0.000"),
            requestsPerDay = BigInteger.ZERO, avgInputTokens = BigInteger.ZERO, avgOutputTokens = BigInteger.ZERO)
        val entered = saved(store().save(empty.version, DraftContent(workload = zero)))
        assertEquals(zero, loaded().content.workload)
        assertNull(loaded().content.workload.currency)
        assertNull(loaded().content.workload.region)
        assertNull(loaded().content.workload.maxConcurrency)
        assertNotEquals(empty.content, entered.content)
        assertEquals(DraftWorkload(), entered.history.first().content.workload)
        val returned = saved(store().save(entered.version, DraftContent()))
        assertNull(returned.content.workload.requestsPerDay)
        assertNull(returned.content.workload.monthlyBudgetAmount)
    }

    @Test fun `exact decimal lexeme and arbitrary precision integers survive reopening all classifications`() {
        val huge = BigInteger("9".repeat(128))
        var current: StoredDraft? = null
        for (classification in DraftDataClassification.entries) {
            val inputs = DraftWorkload("user-entered-region", "JPY", ContractDecimal.parse("123456789012345678901234567890.000000000001"),
                huge, huge, huge, huge, huge, classification)
            current = saved(store().save(current?.version, DraftContent(workload = inputs)))
            assertEquals(inputs, loaded().content.workload)
            assertEquals("123456789012345678901234567890.000000000001", loaded().content.workload.monthlyBudgetAmount!!.encoded)
            val node = mapper.readTree(raw())["history"].last()["content"]["workload"]
            assertTrue(node["monthly_budget_amount"].isTextual)
            assertTrue(node["requests_per_day"].isIntegralNumber)
            assertEquals(huge, node["requests_per_day"].bigIntegerValue())
            assertEquals(classification.encoded, node["data_classification"].textValue())
        }
    }

    @Test fun `v2 reads and unchanged saves preserve bytes while first edit migrates every retained entry`() {
        val bytes = v2()
        seed(bytes)
        val old = loaded()
        assertEquals(2, old.localFormatVersion)
        assertEquals(listOf(0L, 1L, 2L), old.history.map { it.number })
        assertTrue(old.history.all { it.content.workload == DraftWorkload() })
        assertArrayEquals(bytes, raw())
        val noWrites = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            error("An unchanged v2 save must not attempt migration")
        } }
        assertEquals(old.version.hash, saved(noWrites.save(old.version, old.content)).version.hash)
        assertArrayEquals(bytes, raw())
        val content = DraftContent(old.content.objective, old.content.context, old.content.primaryModel, old.content.optional,
            DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("25.0000")))
        val edited = saved(store().save(old.version, content))
        assertEquals(4, edited.localFormatVersion)
        assertEquals(3L, edited.version.number)
        assertEquals(content, loaded().content)
        assertEquals(old.metadata, edited.metadata)
        assertNull(edited.legacySourceHash)
        for ((before, after) in old.history.zip(edited.history)) {
            assertEquals(before.number, after.number)
            assertEquals(before.change, after.change)
            assertEquals(before.restoredFrom, after.restoredFrom)
            assertEquals(before.content, after.content)
            assertEquals(before.metadata, after.metadata)
        }
        assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
        assertEquals(2, (store().revision(old.version.hash) as DraftLoad.Available).draft.localFormatVersion)
        assertEquals(DraftSave.Conflict, store().save(old.version, edited.content))
        assertEquals(DraftSave.Conflict, store().restore(old.version, 0))
        val unchanged = saved(store().save(edited.version, edited.content))
        assertEquals(edited.version.hash, unchanged.version.hash)
        assertEquals(4, unchanged.history.size)
    }

    @Test fun `v2 restore migration keeps original legacy anchor and source identity`() {
        val node = mapper.readTree(v2()) as ObjectNode
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
        assertEquals(8L, restored.history.last().restoredFrom)
        assertEquals(StoredRevisionChange.LEGACY_ANCHOR, restored.history.first().change)
        assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun `v2 migration failures preserve original bytes or report uncertain activation`() {
        val bytes = v2()
        seed(bytes)
        val old = loaded()
        val content = DraftContent(workload = DraftWorkload(region = "entered"))
        val failing = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_STAGED) throw IOException("injected")
        } }
        assertEquals(DraftSave.Failed(DraftStorageFailure.IO_FAILURE), failing.save(old.version, content))
        assertArrayEquals(bytes, raw())
        val uncertain = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("injected")
        } }
        val result = uncertain.save(old.version, content) as DraftSave.Uncertain
        assertEquals(result.hash, loaded().version.hash)
        assertEquals(content, loaded().content)
        assertEquals(4, loaded().history.size)
        assertArrayEquals(bytes, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
        assertEquals(DraftSave.Conflict, store().save(old.version, content))
    }

    @Test fun `domain restore restores all workload values while retaining current metadata`() {
        val inputs = DraftWorkload("entered", "EUR", ContractDecimal.parse("0.00"), BigInteger.ZERO,
            BigInteger.TEN, BigInteger.ONE, BigInteger.ONE, BigInteger.TEN, DraftDataClassification.RESTRICTED)
        val first = saved(store().save(null, DraftContent(workload = inputs), mapOf("pin" to "old")))
        val later = saved(store().save(first.version, DraftContent(), mapOf("pin" to "new")))
        val restored = saved(store().restore(later.version, 0))
        assertEquals(inputs, loaded().content.workload)
        assertEquals(mapOf("pin" to "new"), restored.metadata)
        assertEquals(mapOf("pin" to "old"), restored.history.first().metadata)
        assertEquals(DraftWorkload(), restored.history[1].content.workload)
        assertEquals(0L, restored.history.last().restoredFrom)
        val prior = (store().revision(first.version.hash) as DraftLoad.Available).draft
        assertEquals(inputs, prior.content.workload)
        assertEquals(1, prior.history.size)
    }

    @Test fun `invalid workload types ranges fields and missing values fail closed without replacing bytes`() {
        saved(store().save(null, DraftContent()))
        val original = raw()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("region", "") }, { it.put("region", 1) },
            { it.put("currency", "usd") }, { it.put("currency", true) },
            { it.put("monthly_budget_amount", 0) }, { it.put("monthly_budget_amount", "-1") },
            { it.put("monthly_budget_amount", "1.0000000000000") }, { it.put("monthly_budget_amount", "01.0") },
            { it.put("monthly_budget_amount", "1e2") }, { it.put("monthly_budget_amount", "") },
            { it.put("requests_per_day", -1) }, { it.put("requests_per_day", "0") },
            { it.put("requests_per_day", 1.0) }, { it.put("requests_per_day", true) },
            { it.put("avg_input_tokens", -1) }, { it.put("avg_output_tokens", -1) },
            { it.put("max_concurrency", 0) }, { it.put("max_latency_ms", 0) },
            { it.put("max_concurrency", -1) }, { it.put("max_latency_ms", -1) },
            { it.put("data_classification", "PUBLIC") }, { it.put("data_classification", "unknown") },
            { it.putArray("data_classification") }, { it.put("future", "retained") },
            { it.remove("requests_per_day") },
        )
        for (mutate in mutations) {
            val node = mapper.readTree(original) as ObjectNode
            mutate(workload(node))
            val bytes = mapper.writeValueAsBytes(node)
            seed(bytes)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
            assertEquals(DraftSave.Failed(DraftStorageFailure.CORRUPT), store().save(null, DraftContent()))
            assertArrayEquals(bytes, raw())
        }
        for (replace in listOf(false, true)) {
            val node = mapper.readTree(original) as ObjectNode
            val content = node["history"][0]["content"] as ObjectNode
            if (replace) content.putNull("workload") else content.remove("workload")
            val bytes = mapper.writeValueAsBytes(node)
            seed(bytes)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
            assertEquals(DraftSave.Failed(DraftStorageFailure.CORRUPT), store().save(null, DraftContent()))
            assertArrayEquals(bytes, raw())
        }
    }

    @Test fun `old format cannot smuggle new fields and future version remains intact`() {
        val original = v2()
        val futureVersion = (mapper.readTree(original) as ObjectNode).put("local_format_version", 5)
        val extra = mapper.readTree(original) as ObjectNode
        (extra["history"][0]["content"] as ObjectNode).putObject("workload")
        for ((node, failure) in listOf(futureVersion to DraftStorageFailure.UNSUPPORTED_VERSION, extra to DraftStorageFailure.CORRUPT)) {
            val bytes = mapper.writeValueAsBytes(node)
            seed(bytes)
            assertEquals(DraftLoad.Failed(failure), store().load())
            assertEquals(DraftSave.Failed(failure), store().save(null, DraftContent()))
            assertArrayEquals(bytes, raw())
        }
    }

    @Test fun `workload persistence retains existing numeric string and history budgets`() {
        val first = saved(store().save(null, DraftContent()))
        val excessive = listOf(
            DraftWorkload(requestsPerDay = BigInteger("9".repeat(129))),
            DraftWorkload(region = "x".repeat(16_385)),
            DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("9".repeat(16_385))),
        )
        for (inputs in excessive) {
            assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().save(first.version, DraftContent(workload = inputs)))
            assertEquals(first.version.hash, loaded().version.hash)
        }
        val node = mapper.readTree(v2()) as ObjectNode
        val entries = node.putArray("history")
        val template = mapper.readTree(v2())["history"][0]
        repeat(LocalDraftStore.MAX_HISTORY) { index ->
            val entry = template.deepCopy<ObjectNode>().put("number", index).put("change", if (index == 0) "INITIAL" else "EDIT")
            entries.add(entry)
        }
        node.put("revision", LocalDraftStore.MAX_HISTORY - 1)
        val bytes = mapper.writeValueAsBytes(node)
        seed(bytes)
        val full = loaded()
        assertEquals(full.version.hash, saved(store().save(full.version, full.content)).version.hash)
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            store().save(full.version, DraftContent(workload = DraftWorkload(requestsPerDay = BigInteger.ZERO))))
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().restore(full.version, 0))
        assertArrayEquals(bytes, raw())
    }

    @Test fun `oversized first envelope reports its byte limit without activating a draft`() {
        val content = DraftContent(optional = mapOf(OptionalTier.T3 to TierDraft(selections =
            (0 until 65).map { "selection-$it-" + "x".repeat(16_300) }.toSet())))
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().save(null, content))
        assertEquals(DraftLoad.Missing, store().load())
        assertEquals(CacheRead.Missing, cache().active())
    }

    @Test fun `v2 workload expansion over byte budget rejects migration without changing acknowledged history`() {
        val count = 64
        val node = mapper.readTree(v2()) as ObjectNode
        val template = node["history"][0].deepCopy<ObjectNode>()
        val entries = node.putArray("history")
        repeat(count) { index ->
            entries.add(template.deepCopy().put("number", index)
                .put("change", if (index == 0) "INITIAL" else "EDIT"))
        }
        node.put("revision", count - 1)
        // Keep the initial restore target short. Distribute padding below the existing per-string
        // limit, leaving ample space for a short v2 edit/restore but not v3's workload expansion.
        val padding = LocalDraftStore.MAX_BYTES - 2048 - mapper.writeValueAsBytes(node).size
        val perEntry = padding / (count - 1)
        val remainder = padding % (count - 1)
        assertTrue(perEntry + 1 <= 16_384)
        for (index in 1 until count) {
            (entries[index]["content"] as ObjectNode).put("objective",
                "x".repeat(perEntry + if (index <= remainder) 1 else 0))
        }
        val bytes = mapper.writeValueAsBytes(node)
        assertEquals(LocalDraftStore.MAX_BYTES - 2048, bytes.size)
        val withShortV2Append = node.deepCopy().put("revision", count)
        (withShortV2Append["history"] as com.fasterxml.jackson.databind.node.ArrayNode)
            .add(template.deepCopy().put("number", count).put("change", "EDIT"))
        assertTrue(mapper.writeValueAsBytes(withShortV2Append).size < LocalDraftStore.MAX_BYTES)
        val expanded = node.deepCopy().put("local_format_version", 3)
        expanded["history"].forEach { entry ->
            val inputs = (entry["content"] as ObjectNode).putObject("workload")
            listOf("region", "currency", "monthly_budget_amount", "requests_per_day", "avg_input_tokens",
                "avg_output_tokens", "max_concurrency", "max_latency_ms", "data_classification").forEach { inputs.putNull(it) }
        }
        assertTrue(mapper.writeValueAsBytes(expanded).size > LocalDraftStore.MAX_BYTES)

        seed(bytes)
        val original = loaded()
        assertEquals(2, original.localFormatVersion)
        assertEquals(count, original.history.size)
        fun assertPreserved() {
            val reopened = loaded()
            assertEquals(original.version.hash, reopened.version.hash)
            assertEquals(original.version.number, reopened.version.number)
            assertEquals(original.localFormatVersion, reopened.localFormatVersion)
            assertEquals(original.legacySourceHash, reopened.legacySourceHash)
            assertEquals(original.history.size, reopened.history.size)
            original.history.zip(reopened.history).forEach { (before, after) ->
                assertEquals(before.number, after.number)
                assertEquals(before.change, after.change)
                assertEquals(before.restoredFrom, after.restoredFrom)
                assertEquals(before.content, after.content)
                assertEquals(before.metadata, after.metadata)
            }
            assertArrayEquals(bytes, raw())
            assertArrayEquals(bytes, (cache().pinned(original.version.hash) as CacheRead.Available).bytes)
        }
        assertEquals(original.version.hash, saved(store().save(original.version, original.content)).version.hash)
        assertPreserved()
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            store().save(original.version, DraftContent(context = "short edit")))
        assertPreserved()
        assertEquals(DraftSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED), store().restore(original.version, 0))
        assertPreserved()
        assertEquals(original.version.hash, saved(store().save(original.version, original.content)).version.hash)
        assertPreserved()
    }
}
