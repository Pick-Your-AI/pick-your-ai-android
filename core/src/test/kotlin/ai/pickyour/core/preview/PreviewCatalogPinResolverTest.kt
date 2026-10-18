package ai.pickyour.core.preview

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class PreviewCatalogPinResolverTest {
    @TempDir lateinit var directory: Path
    private val mapper = ObjectMapper()
    private fun document() = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json"))
        .use { mapper.readTree(it) as ObjectNode }
    private fun model(root: ObjectNode) = root["items"].single { it["kind"].textValue() == "model" } as ObjectNode
    private fun bytes(root: ObjectNode) = mapper.writeValueAsBytes(root)
    private fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun browser(root: ObjectNode) = requireNotNull(PreviewCatalogBrowser.read(bytes(root)))
    private fun pin(root: ObjectNode) = PreviewCatalogPin(model(root)["id"].textValue(), model(root)["version"].textValue(), hash(bytes(root)).hex)
    private fun available(result: PreviewCatalogPinResolution) = result as PreviewCatalogPinResolution.Available
    private fun problem(expected: PreviewCatalogPinProblem, result: PreviewCatalogPinResolution) =
        assertEquals(expected, (result as PreviewCatalogPinResolution.Unavailable).problem)
    private fun retained(payload: ByteArray) = PreviewCatalogPinResolver { CacheRead.Available(hash(payload), payload) }
    private fun install(cache: VerifiedArtifactCache, payload: ByteArray) =
        assertEquals(CacheInstall.Activated(hash(payload)), cache.install(hash(payload), payload.inputStream()))

    @Test fun `exact captured details do not consult storage or relabel recorded retirement`() {
        for (status in listOf("active", "preview", "deprecated", "retired")) {
            val root = document()
            model(root).put("status", status)
            val captured = browser(root)
            val resolved = available(PreviewCatalogPinResolver { error("No retained read for the exact captured snapshot") }
                .resolve(pin(root), captured))
            assertEquals(PreviewCatalogPinSource.CAPTURED_CATALOG, resolved.source)
            assertEquals(status, resolved.detail.status)
            assertEquals(captured.hash, resolved.detail.snapshotHash)
            assertEquals(root["captured_at"].textValue(), resolved.detail.snapshotCapturedAt)
            assertEquals(model(root)["id"].textValue(), resolved.detail.item.id)
        }
    }

    @Test fun `real retained older bytes resolve while the newer active pointer and all files stay unchanged`() {
        val old = document()
        model(old).put("status", "retired")
        val current = document().put("revision", 2)
        model(current).put("name", "current replacement with the same identity")
        val cache = VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES, CacheRetentionLimits(2, 2_097_152))
        install(cache, bytes(old))
        install(cache, bytes(current))
        fun state() = Files.list(directory).use { paths -> paths.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() } }
        val before = state()
        val tray = PreviewCatalogTray(listOf(pin(old)))
        val result = available(PreviewCatalogPinResolver(cache::pinned).resolve(tray.pins.single(), browser(current)))
        assertEquals(PreviewCatalogPinSource.RETAINED_SNAPSHOT, result.source)
        assertEquals(model(old)["name"].textValue(), result.detail.item.name)
        assertEquals("retired", result.detail.status)
        assertEquals(hash(bytes(old)).hex, result.detail.snapshotHash)
        assertEquals(old["revision"].bigIntegerValue(), result.detail.snapshotRevision)
        assertEquals(hash(bytes(current)), (cache.active() as CacheRead.Available).hash)
        assertEquals(before, state())
        assertEquals(listOf(pin(old)), tray.pins)
        assertNull(tray.entries(browser(current)).single().detail)
    }

    @Test fun `missing exact bytes cannot fall back to a current row with the same ID version and name`() {
        val old = document()
        val current = document().put("revision", 2)
        assertEquals(browser(old).items, browser(current).items)
        val requests = mutableListOf<ArtifactHash>()
        val resolver = PreviewCatalogPinResolver { requests.add(it); CacheRead.Missing }
        problem(PreviewCatalogPinProblem.SNAPSHOT_MISSING, resolver.resolve(pin(old), browser(current)))
        assertEquals(listOf(hash(bytes(old))), requests)
    }

    @Test fun `IDs and opaque versions require exact equality with no latest sorting or case folding`() {
        val root = document()
        val original = model(root)
        original.put("version", "v09-final+recorded")
        (root["items"] as ArrayNode).add(original.deepCopy().put("version", "v100-latest"))
        val payload = bytes(root)
        val exact = PreviewCatalogPin(original["id"].textValue(), "v09-final+recorded", hash(payload).hex)
        val resolver = retained(payload)
        assertEquals(exact.version, available(resolver.resolve(exact, null)).detail.item.version)
        for (bad in listOf(exact.copy(id = exact.id.uppercase()), exact.copy(version = "V09-final+recorded"),
            exact.copy(version = "v10"))) {
            problem(PreviewCatalogPinProblem.EXACT_VERSION_MISSING, resolver.resolve(bad, null))
        }
        problem(PreviewCatalogPinProblem.EXACT_VERSION_MISSING, resolver.resolve(exact.copy(version = "absent"), browser(root)))
    }

    @Test fun `both returned digest metadata and actual bytes must match before interpretation`() {
        val root = document()
        val payload = bytes(root)
        val requested = pin(root)
        val changed = bytes(document().put("revision", 2))
        problem(PreviewCatalogPinProblem.HASH_MISMATCH,
            PreviewCatalogPinResolver { CacheRead.Available(hash(changed), payload) }.resolve(requested, null))
        problem(PreviewCatalogPinProblem.HASH_MISMATCH,
            PreviewCatalogPinResolver { CacheRead.Available(hash(payload), changed) }.resolve(requested, null))
        problem(PreviewCatalogPinProblem.HASH_MISMATCH,
            retained(bytes(document().put("schema_version", "99.0.0"))).resolve(requested, null))
    }

    @Test fun `unsupported exact schemas stay uninterpreted and do not damage the retained cache`() {
        val future = document().put("schema_version", "2.0.0")
        val current = document()
        val cache = VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES)
        install(cache, bytes(future))
        install(cache, bytes(current))
        val resolver = PreviewCatalogPinResolver(cache::pinned)
        problem(PreviewCatalogPinProblem.UNSUPPORTED_SCHEMA, resolver.resolve(pin(future), browser(current)))
        assertEquals(hash(bytes(current)), (cache.active() as CacheRead.Available).hash)
        assertArrayEquals(bytes(future), (cache.pinned(hash(bytes(future))) as CacheRead.Available).bytes)
    }

    @Test fun `invalid syntax schema duplicate identity and missing schema markers are rejected distinctly from unsupported versions`() {
        val original = document()
        val missingVersion = document().also { it.remove("schema_version") }
        val invalidStatus = document().also { model(it).put("status", "withdrawn") }
        val duplicate = document().also { (it["items"] as ArrayNode).add(model(it).deepCopy()) }
        val extra = document().put("not_frozen", true)
        val bodies = listOf(bytes(missingVersion), bytes(invalidStatus), bytes(duplicate), bytes(extra),
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"1.0.0\"}".toByteArray(), byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        for (payload in bodies) {
            val exact = pin(original).copy(snapshotHash = hash(payload).hex)
            problem(PreviewCatalogPinProblem.INVALID_SNAPSHOT, retained(payload).resolve(exact, null))
        }
    }

    @Test fun `preview byte numeric and item budgets never return partial details`() {
        val root = document()
        val payloads = mutableListOf(ByteArray(PreviewCatalogPinResolver.MAX_BYTES + 1) { 32 })
        payloads += bytes(root).toString(Charsets.UTF_8).replace("\"revision\":1", "\"revision\":" + "9".repeat(129)).toByteArray()
        val many = document()
        val items = many["items"] as ArrayNode
        val sample = model(many)
        while (items.size() <= 256) items.add(sample.deepCopy().put("version", "alternative-${items.size()}"))
        payloads += bytes(many)
        for (payload in payloads) {
            val exact = pin(root).copy(snapshotHash = hash(payload).hex)
            problem(PreviewCatalogPinProblem.PREVIEW_LIMIT, retained(payload).resolve(exact, null))
        }
    }

    @Test fun `manually constructed rows cannot become historical detail evidence`() {
        val root = document()
        val parsed = browser(root)
        val rowsOnly = PreviewCatalogBrowser(parsed.hash, parsed.items)
        problem(PreviewCatalogPinProblem.DETAIL_UNAVAILABLE, PreviewCatalogPinResolver().resolve(pin(root), rowsOnly))
    }

    @Test fun `cache failures and thrown provider errors remain sanitized typed outcomes`() {
        val expected = mapOf(CacheFailure.BUSY to PreviewCatalogPinProblem.READ_BUSY,
            CacheFailure.IO_FAILURE to PreviewCatalogPinProblem.READ_FAILED,
            CacheFailure.TOO_LARGE to PreviewCatalogPinProblem.PREVIEW_LIMIT,
            CacheFailure.RETENTION_LIMIT to PreviewCatalogPinProblem.READ_FAILED,
            CacheFailure.HASH_MISMATCH to PreviewCatalogPinProblem.HASH_MISMATCH,
            CacheFailure.CANCELLED to PreviewCatalogPinProblem.CANCELLED,
            CacheFailure.CORRUPT to PreviewCatalogPinProblem.CACHE_CORRUPT)
        for ((input, output) in expected) problem(output,
            PreviewCatalogPinResolver { CacheRead.Failed(input) }.resolve(pin(document()), null))
        val rejected = PreviewCatalogPinResolver { throw IOException("private provider path and rejected payload") }
            .resolve(pin(document()), null)
        problem(PreviewCatalogPinProblem.READ_FAILED, rejected)
        assertFalse(rejected.toString().contains("private"))
    }

    @Test fun `actual corrupted retained bytes do not get replaced by the active snapshot`() {
        val old = document()
        val current = document().put("revision", 2)
        val cache = VerifiedArtifactCache(directory, PreviewCatalogPinResolver.MAX_BYTES)
        install(cache, bytes(old))
        install(cache, bytes(current))
        Files.write(directory.resolve("${pin(old).snapshotHash}.bin"), bytes(current))
        problem(PreviewCatalogPinProblem.CACHE_CORRUPT, PreviewCatalogPinResolver(cache::pinned).resolve(pin(old), browser(current)))
        assertEquals(hash(bytes(current)), (cache.active() as CacheRead.Available).hash)
        assertArrayEquals(bytes(current), Files.readAllBytes(directory.resolve("${pin(old).snapshotHash}.bin")))
    }

    @Test fun `resolved details stay immutable after caller bytes change and cancellation never reads a fallback`() {
        val root = document()
        val payload = bytes(root)
        val exact = pin(root)
        val resolved = available(retained(payload).resolve(exact, null))
        payload.fill(0)
        assertEquals(exact.snapshotHash, resolved.detail.snapshotHash)
        assertEquals(exact.version, resolved.detail.item.version)
        assertEquals(model(root)["name"].textValue(), resolved.detail.item.name)
        try {
            Thread.currentThread().interrupt()
            problem(PreviewCatalogPinProblem.CANCELLED,
                PreviewCatalogPinResolver { error("An interrupted resolver must not read") }.resolve(exact, browser(root)))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        try {
            problem(PreviewCatalogPinProblem.CANCELLED,
                PreviewCatalogPinResolver { throw InterruptedException() }.resolve(exact, null))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
