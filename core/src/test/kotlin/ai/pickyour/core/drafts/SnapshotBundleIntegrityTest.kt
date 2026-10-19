package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

class SnapshotBundleIntegrityTest {
    @TempDir lateinit var root: Path
    private fun bundle(): ByteArray {
        val store = LocalDraftStore(root.resolve("source").also { Files.createDirectories(it) })
        val current = (store.load() as? DraftLoad.Available)?.draft
        val source = current ?: (store.save(null, ProjectionFixtures.content(allKinds = true),
            mapOf("retained/field" to "private synthetic value")) as DraftSave.Saved).draft
        val review = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        return SnapshotBundleCodec.encode(DraftSnapshotCandidate(review))
    }
    private class Parts(val manifest: ObjectNode, val members: LinkedHashMap<String, ByteArray>, var version: Int = 1)
    private fun parts(bytes: ByteArray): Parts {
        val input = ByteBuffer.wrap(bytes)
        val magic = ByteArray(14).also(input::get)
        assertArrayEquals("PYAI-SNAPSHOT\u0000".toByteArray(), magic)
        val version = input.int
        val metadata = ByteArray(input.int).also(input::get)
        val manifest = ProjectionFixtures.mapper.readTree(metadata) as ObjectNode
        val members = linkedMapOf<String, ByteArray>()
        manifest["members"].forEach { member -> members[member["kind"].textValue()] = ByteArray(member["bytes"].intValue()).also(input::get) }
        assertFalse(input.hasRemaining())
        return Parts(manifest, members, version)
    }
    private fun packed(parts: Parts, rawManifest: ByteArray = ProjectionFixtures.bytes(parts.manifest)): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.write("PYAI-SNAPSHOT\u0000".toByteArray()); stream.writeInt(parts.version)
            stream.writeInt(rawManifest.size); stream.write(rawManifest); parts.members.values.forEach(stream::write)
        }
        return output.toByteArray()
    }
    private fun changeMember(parts: Parts, kind: String, change: (ObjectNode) -> Unit) {
        val node = ProjectionFixtures.mapper.readTree(parts.members.getValue(kind)) as ObjectNode
        change(node)
        val bytes = ProjectionFixtures.bytes(node)
        parts.members[kind] = bytes
        (parts.manifest["members"].single { it["kind"].textValue() == kind } as ObjectNode)
            .put("bytes", bytes.size).put("sha256", archiveHash(bytes).hex)
    }
    private fun rejected(bytes: ByteArray, reason: SnapshotArchiveFailure = SnapshotArchiveFailure.CORRUPT) {
        val copy = bytes.copyOf()
        assertEquals(reason, assertThrows(ArchiveFormatFailure::class.java) { SnapshotBundleCodec.decode(bytes) }.reason)
        assertArrayEquals(copy, bytes)
    }

    @Test fun `binary framing rejects truncation trailing bytes wrong magic and excessive member length without mutation`() {
        val good = bundle()
        for (size in listOf(0, 13, 17, 21, good.size - 1)) rejected(good.copyOf(size))
        rejected(good + byteArrayOf(0))
        rejected(good.copyOf().also { it[0] = 0 })
        rejected(good.copyOf().also { ByteBuffer.wrap(it).putInt(18, Int.MAX_VALUE) })
        val oversized = parts(good)
        (oversized.manifest["members"][0] as ObjectNode).put("bytes", LocalDraftStore.MAX_BYTES + 1)
        rejected(packed(oversized))
        assertTrue(SnapshotBundleCodec.decode(good).bytes().isNotEmpty())
    }

    @Test fun `manifest binds exact roles identities lengths digests and every retained only field`() {
        val good = bundle()
        val mutations: List<(Parts) -> Unit> = listOf(
            { it.manifest.put("extra", true) },
            { (it.manifest["identity"] as ObjectNode).put("id", "build.other") },
            { (it.manifest["identity"] as ObjectNode).put("revision", "019") },
            { (it.manifest["source"] as ObjectNode).put("format", 3) },
            { (it.manifest["source"] as ObjectNode).put("revision", 2) },
            { (it.manifest["members"][0] as ObjectNode).put("kind", "source") },
            { (it.manifest["members"][0] as ObjectNode).put("bytes", 1) },
            { (it.manifest["members"][0] as ObjectNode).put("sha256", "0".repeat(64)) },
            { (it.manifest["members"][2] as ObjectNode).put("id", "catalog.other") },
            { (it.manifest["members"][2] as ObjectNode).put("version", "2") },
            { (it.manifest["retained_only_paths"] as ArrayNode).remove(0) },
            { (it.manifest["retained_only_paths"] as ArrayNode).add("/not-acknowledged") },
            { (it.manifest["retained_only_paths"] as ArrayNode).add(it.manifest["retained_only_paths"][0]) },
        )
        for ((i, mutation) in mutations.withIndex()) {
            val parts = parts(good); mutation(parts)
            try { rejected(packed(parts)) } catch (error: AssertionError) { throw AssertionError("manifest vector $i", error) }
        }
        val parts = parts(good)
        val raw = String(ProjectionFixtures.bytes(parts.manifest)).replace("\"bundle_version\":1", "\"bundle_version\":1,\"bundle_version\":1").toByteArray()
        rejected(packed(parts, raw))
    }

    @Test fun `rehashed structurally valid build changes cannot detach a snapshot from the retained projection`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { (it["locks"] as ObjectNode).put("catalog_snapshot_id", "catalog.changed") },
            { (it["locks"] as ObjectNode).put("dependency_graph_revision", 2) },
            { (it["objectives"] as ObjectNode).put("requests_per_day", 7) },
            { it.put("name", "different explicitly entered name") },
            { (it["selections"][0]["settings"] as ObjectNode).put("requests_per_day", 7) },
        )
        val original = bundle()
        for ((i, mutation) in mutations.withIndex()) {
            val parts = parts(original)
            changeMember(parts, "build", mutation)
            assertEquals(FrozenValidation.Valid, SelectionJson.validator.validate(FrozenDocument.BUILD,
                parts.members.getValue("build").inputStream()), "build structural control $i")
            // Identity/name/time are explicitly represented by the Build itself, so a new name with
            // otherwise coherent facts is a distinct valid bundle, not a corrupted relationship.
            if (i == 3) {
                val decoded = SnapshotBundleCodec.decode(packed(parts))
                assertEquals("different explicitly entered name", decoded.identity.name)
                assertNotEquals(archiveHash(original), decoded.entry.bundleHash)
            } else rejected(packed(parts))
        }
    }

    @Test fun `rehashed source history and artifact reference changes are checked beyond member hashes`() {
        val original = bundle()
        val source = parts(original)
        changeMember(source, "source") { (it["history"][0] as ObjectNode).put("number", 99) }
        rejected(packed(source))
        val catalog = parts(original)
        changeMember(catalog, "catalog") { node ->
            val model = node["items"].single { it["kind"].textValue() == "model" }
            (model["spec"] as ObjectNode).put("provider_ref", "cat.missing.provider")
        }
        assertEquals(FrozenValidation.Valid, SelectionJson.validator.validate(FrozenDocument.CATALOG, catalog.members.getValue("catalog").inputStream()))
        rejected(packed(catalog))
        val pricebook = parts(original)
        changeMember(pricebook, "pricebook") { (it["rates"][0] as ObjectNode).put("catalog_item_version", "different.exact.version") }
        assertEquals(FrozenValidation.Valid, SelectionJson.validator.validate(FrozenDocument.PRICEBOOK, pricebook.members.getValue("pricebook").inputStream()))
        rejected(packed(pricebook))
        val graph = parts(original)
        changeMember(graph, "graph") { it.put("id", "graph.different") }
        rejected(packed(graph))
    }

    @Test fun `unsupported private shared or local source versions stay unavailable with all bytes retained`() {
        val good = bundle()
        val header = parts(good); header.version = 2
        rejected(packed(header), SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        for (field in listOf("bundle_version", "projection_profile")) {
            val parts = parts(good); parts.manifest.put(field, 2)
            rejected(packed(parts), SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        }
        for (kind in listOf("build", "catalog", "ruleset", "graph", "pricebook")) {
            val parts = parts(good); changeMember(parts, kind) { it.put("schema_version", "9.0.0") }
            rejected(packed(parts), SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        }
        val source = parts(good); changeMember(source, "source") { it.put("local_format_version", 99) }
        rejected(packed(source), SnapshotArchiveFailure.UNSUPPORTED_VERSION)
        val wide = parts(good)
        changeMember(wide, "build") { it.put("revision", java.math.BigDecimal("1e129")) }
        assertEquals(FrozenValidation.Valid, SelectionJson.validator.validate(FrozenDocument.BUILD, wide.members.getValue("build").inputStream()))
        rejected(packed(wide), SnapshotArchiveFailure.LIMIT_EXCEEDED)
    }

    @Test fun `complete retained only checklist is not truncated to an arbitrary small metadata count`() {
        val sourcePath = root.resolve("source").also { Files.createDirectory(it) }
        val store = LocalDraftStore(sourcePath)
        val metadata = (1..300).associate { "key.$it" to "retained value $it" }
        val source = (store.save(null, ProjectionFixtures.content(), metadata) as DraftSave.Saved).draft
        val review = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val decoded = SnapshotBundleCodec.decode(SnapshotBundleCodec.encode(DraftSnapshotCandidate(review)))
        assertEquals(metadata, decoded.sourceMetadata)
        assertEquals(review.retainedOnlyPaths, decoded.retainedOnlyPaths)
        assertEquals(317, decoded.retainedOnlyPaths.size)
    }

    @Test fun `indexed missing corrupt or substituted bundle blocks append while healthy previous bytes remain inspectable`() {
        for (damage in listOf("missing", "corrupt", "symlink")) {
            val sourcePath = root.resolve("source-$damage").also { Files.createDirectory(it) }
            val archivePath = root.resolve("archive-$damage").also { Files.createDirectory(it) }
            val store = LocalDraftStore(sourcePath)
            val archive = LocalSnapshotArchive(archivePath)
            val source = (store.save(null, ProjectionFixtures.content()) as DraftSave.Saved).draft
            fun review(id: String) = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs(ProjectionFixtures.identity(id))) as DraftProjectionPreparation.Review
            fun append(review: DraftProjectionPreparation.Review) = store.archiveSnapshotCandidate(review,
                review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, (archive.load() as SnapshotArchiveLoad.Available).state.version)
            val first = append(review("build.first")) as DraftSnapshotArchival.Archived
            val second = append(review("build.second")) as DraftSnapshotArchival.Archived
            val good = (archive.read(first.entry.key) as SnapshotArchiveRead.Available).snapshot.sourceBytes()
            val target = archivePath.resolve("bundles/${second.entry.bundleHash.hex}.bin")
            val original = Files.readAllBytes(target)
            when (damage) {
                "missing" -> Files.delete(target)
                "corrupt" -> Files.write(target, original.copyOf().also { it[it.lastIndex] = 0 })
                "symlink" -> { Files.delete(target); Files.createSymbolicLink(target, sourcePath.resolve("revisions/${source.version.hash.hex}.bin")) }
            }
            assertEquals(SnapshotArchiveRead.Failed(SnapshotArchiveFailure.CORRUPT), archive.read(second.entry.key))
            assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), append(review("build.third")))
            assertArrayEquals(good, (archive.read(first.entry.key) as SnapshotArchiveRead.Available).snapshot.sourceBytes())
            assertEquals(2, (archive.load() as SnapshotArchiveLoad.Available).state.entries.size)
            if (damage == "symlink") assertTrue(Files.isSymbolicLink(target))
        }
    }

    @Test fun `corrupt or unsupported current index cannot silently choose a historical head`() {
        bundle()
        val store = LocalDraftStore(root.resolve("source"))
        val source = (store.load() as DraftLoad.Available).draft
        val review = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        for (unsupported in listOf(false, true)) {
            val path = root.resolve("index-$unsupported").also { Files.createDirectory(it) }
            val archive = LocalSnapshotArchive(path)
            val empty = (archive.load() as SnapshotArchiveLoad.Available).state
            val saved = store.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive,
                empty.version) as DraftSnapshotArchival.Archived
            val cache = VerifiedArtifactCache(path.resolve("indices"), LocalDraftStore.MAX_BYTES)
            val original = (cache.active() as CacheRead.Available).bytes
            val changed = ProjectionFixtures.mapper.readTree(original) as ObjectNode
            if (unsupported) changed.put("archive_index_version", 2) else changed.put("generation", 0)
            val bytes = ProjectionFixtures.bytes(changed)
            assertTrue(cache.install(archiveHash(bytes), bytes.inputStream()) is CacheInstall.Activated)
            val reason = if (unsupported) SnapshotArchiveFailure.UNSUPPORTED_VERSION else SnapshotArchiveFailure.CORRUPT
            assertEquals(SnapshotArchiveLoad.Failed(reason), archive.load())
            assertEquals(DraftSnapshotArchival.ArchiveUnavailable(reason), store.archiveSnapshotCandidate(review,
                review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, saved.version))
            assertTrue(archive.readAt(saved.version, saved.entry.key) is SnapshotArchiveRead.Available)
            assertArrayEquals(bytes, (cache.active() as CacheRead.Available).bytes)
        }
    }
}
