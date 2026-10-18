package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenValidation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocalSnapshotArchiveTest {
    @TempDir lateinit var root: Path
    // The immutable archive uses ordinary install; current-selection cleanup is a separate operation.
    // Every observed callback must remain in this explicit matrix, so a future ordinary checkpoint
    // cannot silently disappear from interruption coverage when the cache enum grows.
    private val archiveWritePoints = listOf(CacheWritePoint.STAGED, CacheWritePoint.ARTIFACT_PUBLISHED,
        CacheWritePoint.POINTER_STAGED, CacheWritePoint.POINTER_ACTIVATED)
    private fun requireArchiveCheckpoint(reached: CacheWritePoint) {
        assertTrue(reached in archiveWritePoints, "Unexpected ordinary archive checkpoint: $reached")
    }
    private val sourcePath get() = directory("source")
    private val archivePath get() = directory("archive")
    private fun directory(name: String) = root.resolve(name).also { Files.createDirectories(it) }
    private fun store() = LocalDraftStore(sourcePath)
    private fun archive() = LocalSnapshotArchive(archivePath)
    private fun current(archive: LocalSnapshotArchive = archive()) = (archive.load() as SnapshotArchiveLoad.Available).state
    private fun source() = (store().load() as DraftLoad.Available).draft
    private fun complete(content: DraftContent = ProjectionFixtures.content(allKinds = true), metadata: Map<String, String> = mapOf("opaque" to "retain only")) =
        (store().save(null, content, metadata) as DraftSave.Saved).draft
    private fun review(source: StoredDraft = source(), id: String = "build.local.archive", revision: BigInteger = BigInteger.ONE,
        pricebook: DraftProjectionArtifact? = ProjectionFixtures.artifact("pricebook")): DraftProjectionPreparation.Review {
        val identity = ProjectionFixtures.identity(id).let { DraftSnapshotIdentity(it.id, revision, it.name, it.ownerRef, it.createdAt, it.updatedAt) }
        return store().prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs(identity, pricebook = pricebook)) as DraftProjectionPreparation.Review
    }
    private fun save(review: DraftProjectionPreparation.Review, version: SnapshotArchiveVersion = current().version,
        archive: LocalSnapshotArchive = archive(), store: LocalDraftStore = store()) =
        store.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, version)
    private fun archived(result: DraftSnapshotArchival) = result as DraftSnapshotArchival.Archived
    private fun read(entry: SnapshotArchiveEntry, archive: LocalSnapshotArchive = archive()) =
        (archive.read(entry.key) as SnapshotArchiveRead.Available).snapshot
    private fun sourceRaw() = (VerifiedArtifactCache(sourcePath.resolve("revisions")).active() as CacheRead.Available).bytes
    private fun retainedFiles(path: Path): Map<String, String> = Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it) }
        .toList().associate { path.relativize(it).toString() to archiveHash(Files.readAllBytes(it)).hex } }
    private fun alteredReview(original: DraftProjectionPreparation.Review, build: ByteArray) = DraftProjectionPreparation.Review(
        original.source, original.identity, original.sourceBytes, build, original.artifacts)

    @Test fun `complete bundle round trip retains exact source history artifacts and immutable independent values`() {
        var source = complete(metadata = mapOf("a/b~c" to "private entered value", "empty" to ""))
        source = (store().save(source.version, source.content, source.metadata + ("later" to "retained")) as DraftSave.Saved).draft
        val review = review(source)
        val before = sourceRaw()
        val empty = current()
        val result = archived(save(review, empty.version))
        assertFalse(result.alreadyPresent)
        assertEquals(1, result.version.generation)
        val reopened = read(result.entry)
        assertArrayEquals(before, reopened.sourceBytes())
        assertArrayEquals(review.candidateBytes, reopened.bytes())
        assertEquals(source.history.map { it.content }, reopened.sourceHistory.map { it.content })
        assertEquals(source.history.map { it.metadata }, reopened.sourceHistory.map { it.metadata })
        assertEquals(review.retainedOnlyPaths, reopened.retainedOnlyPaths)
        assertEquals(29, reopened.sourceContent.components.selections.size)
        assertEquals("1000.00", reopened.sourceContent.workload.monthlyBudgetAmount!!.encoded)
        assertEquals("draft", SelectionJson.parse(reopened.bytes())["lifecycle"].textValue())
        for (pin in review.pins) assertArrayEquals(review.artifacts.single { it.pin.kind == pin.kind }.bytes, reopened.artifactBytes(pin.kind))
        reopened.bytes().fill(0); reopened.sourceBytes().fill(0); reopened.artifactBytes(ProjectionArtifactKind.CATALOG)!!.fill(0)
        assertArrayEquals(before, read(result.entry).sourceBytes())
        assertArrayEquals(review.candidateBytes, reopened.bytes())
        assertThrows(UnsupportedOperationException::class.java) { (reopened.sourceHistory as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (reopened.retainedOnlyPaths as MutableList).clear() }
        assertArrayEquals(before, sourceRaw())
        assertEquals(SnapshotArchiveRead.Missing, archive().readAt(empty.version, result.entry.key))
        assertEquals("RetainedDraftSnapshot", reopened.toString())
        assertFalse(listOf(reopened.entry, reopened.identity, reopened.entry.key, result.version).joinToString().contains("build.local.archive"))
    }

    @Test fun `exact retry is idempotent and different retained only source bytes conflict on the same build identity`() {
        val original = complete()
        val review = review(original)
        val result = archived(save(review))
        val before = retainedFiles(archivePath)
        val retry = archived(save(review))
        assertTrue(retry.alreadyPresent)
        assertEquals(result.version.hash, retry.version.hash)
        assertEquals(before, retainedFiles(archivePath))
        val changed = (store().save(original.version, original.content, mapOf("different" to "retained only")) as DraftSave.Saved).draft
        val nextReview = review(changed)
        assertArrayEquals(review.candidateBytes, nextReview.candidateBytes)
        assertFalse(review.sourceBytes.contentEquals(nextReview.sourceBytes))
        assertEquals(DraftSnapshotArchival.IdentityConflict, save(nextReview))
        assertEquals(before, retainedFiles(archivePath))
        assertArrayEquals(review.sourceBytes, read(result.entry).sourceBytes())
        val newRevision = archived(save(review(changed, revision = BigInteger.TWO)))
        assertEquals(2, newRevision.version.generation)
        assertArrayEquals(nextReview.sourceBytes, read(newRevision.entry).sourceBytes())
        assertEquals(2, current().entries.size)
    }

    @Test fun `mathematical revision aliases retain their raw bytes but cannot replace one identity`() {
        complete()
        val ordinary = review()
        val saved = archived(save(ordinary))
        for (raw in listOf("1.0", "1e0", "1.00e+0")) {
            val bytes = String(ordinary.candidateBytes, Charsets.UTF_8).replace("\"revision\":1,", "\"revision\":$raw,").toByteArray()
            assertFalse(bytes.contentEquals(ordinary.candidateBytes))
            assertEquals(FrozenValidation.Valid, SelectionJson.validator.validate(FrozenDocument.BUILD, bytes.inputStream()))
            val alias = alteredReview(ordinary, bytes)
            val decoded = SnapshotBundleCodec.decode(SnapshotBundleCodec.encode(DraftSnapshotCandidate(alias)))
            assertEquals(saved.entry.key, decoded.entry.key)
            assertArrayEquals(bytes, decoded.bytes())
            assertNotEquals(saved.entry.bundleHash, decoded.entry.bundleHash)
            assertEquals(DraftSnapshotArchival.IdentityConflict, save(alias))
            assertArrayEquals(ordinary.candidateBytes, read(saved.entry).bytes())
        }
    }

    @Test fun `source and archive CAS remain independent across stale foreign and repeated state inputs`() {
        val source = complete()
        val review = review(source)
        val empty = current()
        val first = archived(save(review, empty.version))
        assertEquals(DraftSnapshotArchival.ArchiveConflict, save(review, empty.version))
        val foreign = LocalSnapshotArchive(directory("foreign-archive"))
        assertEquals(DraftSnapshotArchival.ArchiveConflict, save(review, current(foreign).version))
        assertEquals(DraftSnapshotArchival.ReviewAcknowledgmentMismatch, store().archiveSnapshotCandidate(review, null, archive(), current().version))
        val other = review(source, id = "build.other")
        assertEquals(DraftSnapshotArchival.ReviewAcknowledgmentMismatch, store().archiveSnapshotCandidate(other,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive(), current().version))
        val changed = (store().save(source.version, source.content, mapOf("changed" to "value")) as DraftSave.Saved).draft
        (store().save(changed.version, source.content, source.metadata) as DraftSave.Saved).draft
        val before = retainedFiles(archivePath)
        assertEquals(DraftSnapshotArchival.SourceConflict, save(other))
        assertEquals(before, retainedFiles(archivePath))
        val fresh = archived(save(review(id = "build.other")))
        assertEquals(2, fresh.version.generation)
        assertEquals(SnapshotArchiveRead.Missing, archive().readAt(first.version, fresh.entry.key))
        assertTrue(archive().readAt(first.version, first.entry.key) is SnapshotArchiveRead.Available)
    }

    @Test fun `omitted pricebook remains absent and source role format five survives later restore without migration`() {
        var source = complete()
        val selected = source.content.components.selections.single { it.kind == "compute_profile" }
        source = (store().applySelection(DraftSelectionIntent(source.version,
            SelectionCommand.Configure(selected.id, selected.settings, "secondary"))) as DraftSelectionSave.Saved).draft
        assertEquals(5, source.localFormatVersion)
        assertEquals(SelectionAction.CONFIGURE_ROLE, source.history.last().selectionChange!!.action)
        val review = review(source, pricebook = null)
        val before = sourceRaw()
        val result = archived(save(review))
        val snapshot = read(result.entry)
        assertEquals(5, snapshot.sourceFormatVersion)
        assertEquals(SelectionAction.CONFIGURE_ROLE, snapshot.sourceHistory.last().selectionChange!!.action)
        assertNull(snapshot.artifactBytes(ProjectionArtifactKind.PRICEBOOK))
        assertFalse(SelectionJson.parse(snapshot.bytes())["locks"].has("pricebook_id"))
        assertArrayEquals(before, snapshot.sourceBytes())
        val restored = (store().restore(source.version, 0) as DraftSave.Saved).draft
        assertEquals(5, restored.localFormatVersion)
        assertArrayEquals(before, read(result.entry).sourceBytes())
        assertEquals("secondary", read(result.entry).sourceContent.components.selections.single { it.id == selected.id }.role)
    }

    @Test fun `all bundle and index write interruptions preserve earlier entries and reconcile exact complete orphans`() {
        for (target in listOf("bundles", "indices")) for (point in archiveWritePoints) {
            val name = "$target-${point.name.lowercase()}"
            val path = directory(name)
            var armed = false
            var injected = false
            val archive = LocalSnapshotArchive(path, SnapshotArchiveLimits(), { cachePath, max, retention ->
                VerifiedArtifactCache(cachePath, max, retention) { reached ->
                    requireArchiveCheckpoint(reached)
                    if (armed && cachePath.fileName.toString() == target && reached == point) {
                        injected = true
                        throw IOException("injected archive point")
                    }
                }
            })
            if (store().load() is DraftLoad.Missing) complete()
            val first = archived(save(review(id = "build.before.$name"), current(archive).version, archive))
            val next = review(id = "build.after.$name")
            val oldVersion = current(archive).version
            armed = true
            val result = save(next, oldVersion, archive)
            armed = false
            assertTrue(injected, "Requested archive interruption was not reached: $name")
            val uncertain = target == "indices" && point == CacheWritePoint.POINTER_ACTIVATED
            val entry = if (uncertain) (result as DraftSnapshotArchival.Uncertain).entry else (result as DraftSnapshotArchival.NotPublished).entry
            assertArrayEquals(review(id = "build.before.$name").candidateBytes, read(first.entry, archive).bytes())
            val retained = archive.inspectRetained(entry.bundleHash)
            if (target == "bundles" && point == CacheWritePoint.STAGED) assertEquals(SnapshotArchiveRead.Missing, retained)
            else assertTrue(retained is SnapshotArchiveRead.Available, name)
            assertEquals(if (uncertain) 2 else 1, current(archive).entries.size, name)
            if (uncertain) assertEquals(DraftSnapshotArchival.ArchiveConflict, save(next, oldVersion, archive))
            if (retained is SnapshotArchiveRead.Available && !uncertain) {
                val altered = alteredReview(next, String(next.candidateBytes).replace("\"revision\":1,", "\"revision\":1.0,").toByteArray())
                assertEquals(DraftSnapshotArchival.IdentityConflict, save(altered, current(archive).version, archive), name)
            }
            val recovered = archived(save(next, current(archive).version, archive))
            assertEquals(uncertain, recovered.alreadyPresent, name)
            assertEquals(entry.bundleHash, recovered.entry.bundleHash)
            assertEquals(2, current(archive).entries.size)
            assertTrue(archive.readAt(first.version, first.entry.key) is SnapshotArchiveRead.Available)
        }
    }

    @Test fun `ordinary archive publication never uses current cleanup and preserves every earlier bundle and index pin`() {
        complete()
        val sourceBefore = sourceRaw()
        val reached = mutableMapOf<String, MutableSet<CacheWritePoint>>()
        val currentOnly = setOf(CacheWritePoint.CURRENT_POINTER_VERIFIED, CacheWritePoint.CURRENT_PAYLOAD_REMOVED)
        val archive = LocalSnapshotArchive(archivePath, SnapshotArchiveLimits(), { path, max, retention ->
            VerifiedArtifactCache(path, max, retention) { point ->
                assertFalse(point in currentOnly, "Immutable archive must not invoke current-value cleanup: $point")
                reached.getOrPut(path.fileName.toString()) { mutableSetOf() }.add(point)
            }
        })
        val empty = current(archive)
        var priorPins = retainedFiles(archivePath).filterKeys { it.endsWith(".bin") }
        assertTrue(priorPins.isNotEmpty())
        val publications = listOf("first", "second", "third").map { name ->
            val review = review(id = "build.ordinary.$name")
            val saved = archived(save(review, current(archive).version, archive))
            val after = retainedFiles(archivePath)
            priorPins.forEach { (path, hash) -> assertEquals(hash, after[path], "Previously retained pin: $path") }
            priorPins = after.filterKeys { it.endsWith(".bin") }
            review to saved
        }
        assertEquals(3, current(archive).entries.size)
        assertEquals(SnapshotArchiveRead.Missing, archive.readAt(empty.version, publications.first().second.entry.key))
        for ((review, saved) in publications) {
            val retained = archive.readAt(saved.version, saved.entry.key) as SnapshotArchiveRead.Available
            assertArrayEquals(review.candidateBytes, retained.snapshot.bytes())
            assertArrayEquals(review.sourceBytes, retained.snapshot.sourceBytes())
        }
        assertEquals(setOf("bundles", "indices"), reached.keys)
        reached.forEach { (target, points) -> assertEquals(archiveWritePoints.toSet(), points, target) }
        assertArrayEquals(sourceBefore, sourceRaw())
    }

    @Test fun `loss of current index never resets sealed namespace and captured history stays read only`() {
        complete()
        val first = archived(save(review()))
        val next = review(id = "build.next")
        Files.delete(archivePath.resolve("indices/active"))
        val before = retainedFiles(archivePath)
        assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CORRUPT), archive().load())
        assertTrue(archive().readAt(first.version, first.entry.key) is SnapshotArchiveRead.Available)
        assertTrue(archive().inspectRetained(first.entry.bundleHash) is SnapshotArchiveRead.Available)
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), save(next, first.version))
        assertEquals(before, retainedFiles(archivePath))
        Files.delete(archivePath.resolve("indices/${first.version.hash.hex}.bin"))
        assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CORRUPT), archive().load())
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), save(next, first.version))
    }

    @Test fun `lost seal with retained data cannot reinitialize and an explicitly new empty namespace rejects old token`() {
        complete()
        val archive = archive()
        val empty = current(archive)
        Files.delete(archivePath.resolve("namespace"))
        val replacement = current()
        assertNotEquals(empty.version.namespace, replacement.version.namespace)
        assertEquals(DraftSnapshotArchival.ArchiveConflict, save(review(), empty.version))
        val saved = archived(save(review(), replacement.version))
        Files.delete(archivePath.resolve("namespace"))
        val before = retainedFiles(archivePath)
        assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CORRUPT), archive.load())
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), save(review(), saved.version))
        assertEquals(before, retainedFiles(archivePath))
    }

    @Test fun `initial empty index interruption can resume only that exact never published baseline`() {
        for (point in archiveWritePoints) {
            val path = directory("initial-${point.name}")
            var armed = true
            var injected = false
            val failing = LocalSnapshotArchive(path, SnapshotArchiveLimits(), { cachePath, max, retention ->
                VerifiedArtifactCache(cachePath, max, retention) { reached ->
                    requireArchiveCheckpoint(reached)
                    if (armed && reached == point) {
                        injected = true
                        throw IOException("initial interruption")
                    }
                }
            })
            val result = failing.load()
            assertTrue(injected, "Requested initial index interruption was not reached: $point")
            assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.IO_FAILURE), result)
            assertFalse(Files.exists(path.resolve("namespace")))
            armed = false
            val resumed = current(failing)
            assertEquals(0, resumed.version.generation)
            assertTrue(resumed.entries.isEmpty())
            assertEquals(resumed.version.hash, current(LocalSnapshotArchive(path)).version.hash)
            assertEquals(resumed.version.namespace, current(LocalSnapshotArchive(path)).version.namespace)
        }
    }

    @Test fun `archive and source transaction completion failures never acknowledge a possibly committed save`() {
        complete()
        for (target in listOf("archive", "source")) {
            val path = directory("closing-$target")
            var armed = false
            val archive = LocalSnapshotArchive(path, SnapshotArchiveLimits(), { p, max, retention -> VerifiedArtifactCache(p, max, retention) },
                { if (armed && target == "archive") throw IOException("archive close") })
            val sourceStore = LocalDraftStore(sourcePath, { VerifiedArtifactCache(it, LocalDraftStore.MAX_BYTES) },
                { if (armed && target == "source") throw IOException("source close") })
            val review = review(id = "build.close.$target")
            val expected = current(archive).version
            armed = true
            val result = save(review, expected, archive, sourceStore) as DraftSnapshotArchival.Uncertain
            armed = false
            val state = current(archive)
            assertEquals(1, state.entries.size)
            assertEquals(result.entry.bundleHash, state.entries.single().bundleHash)
            assertArrayEquals(review.candidateBytes, read(result.entry, archive).bytes())
            assertTrue(archived(save(review, state.version, archive, sourceStore)).alreadyPresent)
        }
    }

    @Test fun `bounded retention refuses new data without evicting earlier index or complete bundles`() {
        complete()
        val limited = LocalSnapshotArchive(archivePath, SnapshotArchiveLimits(maxSnapshots = 1), { p, max, retention -> VerifiedArtifactCache(p, max, retention) })
        val saved = archived(save(review(), current(limited).version, limited))
        val before = retainedFiles(archivePath)
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.LIMIT_EXCEEDED),
            save(review(id = "build.over.limit"), current(limited).version, limited))
        assertEquals(before, retainedFiles(archivePath))
        assertTrue(limited.read(saved.entry.key) is SnapshotArchiveRead.Available)
        val budgetPath = directory("budget")
        val budget = LocalSnapshotArchive(budgetPath, SnapshotArchiveLimits(indices = CacheRetentionLimits(2, 4L * 1024 * 1024)),
            { p, max, retention -> VerifiedArtifactCache(p, max, retention) })
        val first = archived(save(review(), current(budget).version, budget))
        val failed = save(review(id = "build.index.full"), current(budget).version, budget) as DraftSnapshotArchival.NotPublished
        assertEquals(SnapshotArchiveFailure.LIMIT_EXCEEDED, failed.reason)
        assertEquals(first.version.hash, current(budget).version.hash)
        assertTrue(budget.inspectRetained(failed.entry.bundleHash) is SnapshotArchiveRead.Available)
        assertArrayEquals(review().candidateBytes, read(first.entry, budget).bytes())
    }

    @Test fun `cancellation and canonical nonreentrant guards preserve both source and archive`() {
        complete()
        val review = review()
        val state = current()
        FileChannel.open(archivePath.resolve("transaction.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.BUSY), save(review, state.version))
        } }
        val openedArchive = archive()
        val openedStore = store()
        Thread.currentThread().interrupt()
        try {
            assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.CANCELLED), openedArchive.load())
            assertEquals(DraftSnapshotArchival.SourceUnavailable(DraftStorageFailure.CANCELLED), save(review, state.version, openedArchive, openedStore))
        } finally { Thread.interrupted() }
        var armed = false
        val probe = LocalSnapshotArchive(archivePath.resolve("."), SnapshotArchiveLimits(), { path, max, retention ->
            VerifiedArtifactCache(path, max, retention) { point -> if (armed && point == CacheWritePoint.STAGED) {
                assertEquals(SnapshotArchiveLoad.Failed(SnapshotArchiveFailure.BUSY), archive().load())
                assertEquals(DraftSave.Failed(DraftStorageFailure.BUSY), openedStore.save(review.source.version, review.source.content))
            } }
        })
        armed = true
        assertTrue(save(review, state.version, probe) is DraftSnapshotArchival.Archived)
    }

    @Test fun `two concurrent draft writers cannot publish a mixed bundle or reuse a stale archive head`() {
        complete()
        val firstReview = review()
        val firstStore = store()
        val secondStore = LocalDraftStore(directory("second-source"))
        val secondSource = (secondStore.save(null, ProjectionFixtures.content()) as DraftSave.Saved).draft
        val secondReview = secondStore.prepareSnapshotCandidate(secondSource.version,
            ProjectionFixtures.inputs(ProjectionFixtures.identity("build.concurrent.other"))) as DraftProjectionPreparation.Review
        val initial = current().version
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var armed = true
        val archive = LocalSnapshotArchive(archivePath, SnapshotArchiveLimits(), { path, max, retention ->
            VerifiedArtifactCache(path, max, retention) { point ->
                if (armed && path.fileName.toString() == "bundles" && point == CacheWritePoint.STAGED) {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
        })
        val result = AtomicReference<DraftSnapshotArchival>()
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            try { result.set(save(firstReview, initial, archive, firstStore)) } catch (failure: Throwable) { error.set(failure) }
        }
        worker.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(DraftSave.Failed(DraftStorageFailure.BUSY), firstStore.save(firstReview.source.version, DraftContent()))
            assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.BUSY),
                save(secondReview, initial, archive(), secondStore))
        } finally { release.countDown(); worker.join(10_000) }
        assertFalse(worker.isAlive)
        error.get()?.let { throw AssertionError("concurrent archive worker", it) }
        armed = false
        val first = archived(result.get())
        assertEquals(DraftSnapshotArchival.ArchiveConflict, save(secondReview, initial, archive, secondStore))
        val second = archived(save(secondReview, current(archive).version, archive, secondStore))
        assertArrayEquals(firstReview.sourceBytes, read(first.entry, archive).sourceBytes())
        assertArrayEquals(secondReview.sourceBytes, read(second.entry, archive).sourceBytes())
        assertEquals(2, current(archive).entries.size)
    }

    @Test fun `legacy source hash stays a provenance reference while current envelope and represented history survive`() {
        val store = store()
        val legacy = """{"local_format_version":1,"revision":7,"content":{"objective":"","context":null,"primaryModel":"entered",
            "optional":{"T3":{"activation":"UNKNOWN","selections":["raw"],"settings":{"amount":"0.00"}},
            "T4":{"activation":"DISABLED","selections":[],"settings":{}},"T5":{"activation":"DISABLED","selections":[],"settings":{}}}},
            "metadata":{"opaque":"legacy retained"}}""".toByteArray()
        val hash = archiveHash(legacy)
        val revisions = VerifiedArtifactCache(sourcePath.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertTrue(revisions.install(hash, legacy.inputStream()) is CacheInstall.Activated)
        val old = source()
        assertTrue(store.prepareSnapshotCandidate(old.version, ProjectionFixtures.inputs()) is DraftProjectionPreparation.Withheld)
        val complete = (store.save(old.version, ProjectionFixtures.content()) as DraftSave.Saved).draft
        assertEquals(4, complete.localFormatVersion)
        assertEquals(hash, complete.legacySourceHash)
        val currentBytes = sourceRaw()
        Files.delete(sourcePath.resolve("revisions/${hash.hex}.bin"))
        val saved = archived(save(review(complete)))
        val retained = read(saved.entry)
        assertEquals(hash, retained.sourceLegacyHash)
        assertEquals(7, retained.sourceHistory.first().number)
        assertEquals(old.content, retained.sourceHistory.first().content)
        assertEquals(old.metadata, retained.sourceHistory.first().metadata)
        assertArrayEquals(currentBytes, retained.sourceBytes())
        assertEquals(SnapshotArchiveRead.Missing, archive().inspectRetained(hash))
        assertFalse(Files.exists(sourcePath.resolve("revisions/${hash.hex}.bin")))
    }

    @Test fun `archive refuses source bytes that no longer match the review source hash`() {
        complete()
        val review = review()
        val changed = ProjectionFixtures.mapper.readTree(review.sourceBytes) as com.fasterxml.jackson.databind.node.ObjectNode
        (changed["history"].last()["metadata"] as com.fasterxml.jackson.databind.node.ObjectNode).put("opaque", "not the reviewed value")
        val altered = DraftProjectionPreparation.Review(review.source, review.identity, ProjectionFixtures.bytes(changed),
            review.candidateBytes, review.artifacts)
        val before = current().version
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), save(altered, before))
        assertEquals(before.hash, current().version.hash)
        assertArrayEquals(review.sourceBytes, sourceRaw())
    }

    @Test fun `archive preserves the acknowledged artifact hash instead of recomputing a replacement pin`() {
        complete()
        val review = review()
        val artifacts = review.artifacts.map { artifact ->
            if (artifact.pin.kind != ProjectionArtifactKind.CATALOG) artifact else {
                val changed = artifact.value.deepCopy().put("captured_at", "2026-10-17T08:00:00Z")
                val raw = ProjectionFixtures.bytes(changed)
                assertEquals(FrozenValidation.Valid, SelectionJson.validator.validate(FrozenDocument.CATALOG, raw.inputStream()))
                assertNotEquals(artifact.pin.hash, archiveHash(raw))
                CheckedProjectionArtifact(artifact.pin, raw, changed)
            }
        }
        val altered = DraftProjectionPreparation.Review(review.source, review.identity, review.sourceBytes, review.candidateBytes, artifacts)
        val before = current().version
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), save(altered, before))
        assertEquals(before.hash, current().version.hash)
    }

    @Test fun `archive inside a source or its ancestor cannot receive an independently retained snapshot`() {
        complete()
        val review = review()
        val nested = LocalSnapshotArchive(sourcePath.resolve("nested").also { Files.createDirectory(it) })
        assertEquals(DraftSnapshotArchival.ArchiveUnavailable(SnapshotArchiveFailure.CORRUPT), save(review, current(nested).version, nested))
        assertEquals(0, current(nested).entries.size)
    }
}
