package ai.pickyour.core.conformance

import ai.pickyour.core.cache.*
import ai.pickyour.core.drafts.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Fixture-only native-core adapter. Private version numbers are not shared wire or Apple DB versions. */
class LocalMigrationFixtureTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val ordinaryPoints = setOf(CacheWritePoint.STAGED, CacheWritePoint.ARTIFACT_PUBLISHED,
        CacheWritePoint.POINTER_STAGED, CacheWritePoint.POINTER_ACTIVATED)

    @TestFactory
    fun exactPrivateMigrationCases(): List<DynamicTest> {
        val bundle = LocalMigrationFixtures.load()
        val inputs = bundle["inputs"].associateBy { it["id"].textValue() }
        return bundle["cases"].map { case ->
            DynamicTest.dynamicTest("migration:${case["id"].textValue()}") {
                runCase(case, requireNotNull(inputs[case["input"].textValue()]))
            }
        }
    }

    @Test
    fun migrationInputTransportCannotChangeOrGrowBehindItsVersion() {
        val bytes = javaClass.getResourceAsStream("/conformance/local-migrations-v1.json")!!.use { it.readBytes() }
        for (changed in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), bytes.copyOf().also { it[0] = 0 }))
            assertThrows(IllegalArgumentException::class.java) { LocalMigrationFixtures.load(changed.inputStream()) }
    }

    private fun runCase(case: JsonNode, input: JsonNode) {
        val id = case["id"].textValue()
        val path = Files.createDirectory(root.resolve(id))
        val store = LocalDraftStore(path)
        val bytes = input["payloadJSON"].textValue().toByteArray(Charsets.UTF_8)
        val hash = ArtifactHash(input["sha256"].textValue())
        val cache = VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES)
        assertTrue(cache.install(hash, bytes.inputStream()) is CacheInstall.Activated)
        val expected = case["expected"]
        val action = case["action"].textValue()
        val observedFormat: Int?
        val outcome: String
        if (action == "reject") {
            val failure = DraftStorageFailure.valueOf(input["expected"]["failure"].textValue())
            assertEquals(DraftLoad.Failed(failure), store.load())
            assertEquals(DraftLoad.Failed(failure), store.revision(hash))
            assertEquals(DraftSave.Failed(failure), store.save(null, DraftContent()))
            assertEquals(hash, (cache.active() as CacheRead.Available).hash)
            observedFormat = null
            outcome = "REJECTED"
        } else {
            val old = loaded(path)
            assertMeaning(input["expected"], old)
            assertEquals(hash, old.version.hash)
            when (action) {
                "read" -> {
                    assertMeaning(input["expected"], (store.revision(hash) as DraftLoad.Available).draft)
                    assertEquals(hash, loaded(path).version.hash)
                    observedFormat = old.localFormatVersion
                    outcome = "READ_ONLY"
                }
                "same-content" -> {
                    val writer = if (old.localFormatVersion == 1) store else LocalDraftStore(path) { p ->
                        VerifiedArtifactCache(p, LocalDraftStore.MAX_BYTES) { error("A supported no-op cannot write or migrate") }
                    }
                    val next = (writer.save(old.version, old.content, old.metadata) as DraftSave.Saved).draft
                    if (old.localFormatVersion == 1) {
                        assertAppend(old, next)
                        assertEquals(hash, next.legacySourceHash)
                        assertEquals(StoredRevisionChange.EDIT, next.history.last().change)
                        assertEquals(old.content, next.content)
                        outcome = "MIGRATED"
                    } else {
                        assertEquals(hash, next.version.hash)
                        assertMeaning(input["expected"], next)
                        outcome = "NO_CHANGE"
                    }
                    observedFormat = next.localFormatVersion
                }
                "edit-restore" -> {
                    val changed = edit(old.content)
                    val next = (store.save(old.version, changed, old.metadata + ("fixture-current" to "")) as DraftSave.Saved).draft
                    assertAppend(old, next)
                    assertEquals(changed, next.content)
                    assertEquals(DraftSave.Conflict, store.save(old.version, changed))
                    val restored = restore(store, old, next)
                    assertEquals(old.content, restored.content)
                    assertEquals(next.metadata, restored.metadata)
                    observedFormat = restored.localFormatVersion
                    outcome = "RESTORED"
                }
                "edit-fault", "role-fault" -> {
                    val point = CacheWritePoint.valueOf(case["checkpoint"].textValue())
                    var injections = 0
                    val failing = LocalDraftStore(path) { p -> VerifiedArtifactCache(p, LocalDraftStore.MAX_BYTES) { reached ->
                        assertTrue(reached in ordinaryPoints, "Unexpected ordinary draft checkpoint: $reached")
                        if (reached == point) { injections++; throw IOException("SYNTHETIC migration interruption") }
                    } }
                    val role = action == "role-fault"
                    val result: Any = if (role) failing.applySelection(roleIntent(old)) else failing.save(old.version, edit(old.content))
                    assertEquals(1, injections, "The requested interruption must execute exactly once")
                    val reopened = loaded(path)
                    observedFormat = reopened.localFormatVersion
                    if (point == CacheWritePoint.POINTER_STAGED) {
                        if (role) assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.IO_FAILURE), result)
                        else assertEquals(DraftSave.Failed(DraftStorageFailure.IO_FAILURE), result)
                        assertEquals(hash, reopened.version.hash)
                        assertMeaning(input["expected"], reopened)
                        outcome = "FAILED_ROLLED_BACK"
                        val retry = if (role) (store.applySelection(roleIntent(old)) as DraftSelectionSave.Saved).draft
                            else (store.save(old.version, edit(old.content)) as DraftSave.Saved).draft
                        assertAppend(old, retry)
                        if (role) assertRole(old, retry) else assertEquals(edit(old.content), retry.content)
                        restore(store, old, retry)
                    } else {
                        val written = if (role) (result as DraftSelectionSave.Uncertain).hash else (result as DraftSave.Uncertain).hash
                        assertEquals(written, reopened.version.hash)
                        assertAppend(old, reopened)
                        if (role) {
                            assertRole(old, reopened)
                            assertEquals(DraftSelectionSave.Conflict, store.applySelection(roleIntent(old)))
                        } else {
                            assertEquals(edit(old.content), reopened.content)
                            assertEquals(DraftSave.Conflict, store.save(old.version, edit(old.content)))
                        }
                        outcome = "UNCERTAIN_REOPENED"
                        restore(store, old, reopened)
                    }
                }
                "role" -> {
                    val next = (store.applySelection(roleIntent(old)) as DraftSelectionSave.Saved).draft
                    assertAppend(old, next)
                    assertRole(old, next)
                    assertEquals(DraftSelectionSave.Conflict, store.applySelection(roleIntent(old)))
                    val restored = restore(store, old, next)
                    assertEquals(5, restored.localFormatVersion)
                    observedFormat = next.localFormatVersion
                    outcome = "MIGRATED"
                }
                "archive" -> {
                    archiveThenMigrateAndRestore(path, store, old, bytes, input["expected"]["history"])
                    observedFormat = loaded(path).localFormatVersion
                    outcome = "RETAINED_SNAPSHOT"
                }
                else -> error("Unknown pinned migration action")
            }
            // The previous format remains readable by its original content address after every action.
            assertMeaning(input["expected"], (store.revision(hash) as DraftLoad.Available).draft)
        }
        assertArrayEquals(bytes, (cache.pinned(hash) as CacheRead.Available).bytes)
        assertArrayEquals(bytes, Files.readAllBytes(path.resolve("revisions/${hash.hex}.bin")))
        assertEquals(expected["outcome"].textValue(), outcome)
        assertEquals(if (expected["currentFormat"].isNull) null else expected["currentFormat"].intValue(), observedFormat)
        assertTrue(expected["originalPreserved"].isBoolean && expected["originalPreserved"].booleanValue())
        println("ANDROID_MIGRATION_CASE " + mapper.writeValueAsString(linkedMapOf(
            "bundleVersion" to LocalMigrationFixtures.VERSION, "id" to id, "inputSha256" to hash.hex,
            "outcome" to outcome, "currentFormat" to observedFormat, "originalPreserved" to true)))
    }

    private fun loaded(path: Path) = (LocalDraftStore(path).load() as DraftLoad.Available).draft
    private fun edit(old: DraftContent) = DraftContent("SYNTHETIC fixture edit", old.context, old.primaryModel,
        old.optional, old.workload, old.components)
    private fun roleIntent(old: StoredDraft): DraftSelectionIntent {
        val selection = old.content.components.selections.single { it.kind == "model" }
        return DraftSelectionIntent(old.version, SelectionCommand.Configure(selection.id, selection.settings, "fixture_secondary"))
    }
    private fun assertRole(old: StoredDraft, next: StoredDraft) {
        assertEquals(5, next.localFormatVersion)
        val model = old.content.components.selections.single { it.kind == "model" }
        assertEquals("fixture_secondary", next.content.components.selections.single { it.id == model.id }.role)
        assertEquals(model.settings, next.content.components.selections.single { it.id == model.id }.settings)
        assertEquals(old.content.components.links, next.content.components.links)
        val change = next.history.last().selectionChange!!
        assertEquals(SelectionAction.CONFIGURE_ROLE, change.action)
        assertEquals(model.id, change.selectionId)
        assertEquals(old.content.components.links.filter { it.fromSelectionId == model.id || it.toSelectionId == model.id }.map { it.id }, change.affectedLinkIds)
        assertTrue(change.removedLinkIds.isEmpty())
    }
    private fun restore(store: LocalDraftStore, old: StoredDraft, current: StoredDraft): StoredDraft {
        val restored = (store.restore(current.version, old.version.number) as DraftSave.Saved).draft
        assertAppend(current, restored)
        assertEquals(StoredRevisionChange.RESTORE, restored.history.last().change)
        assertEquals(old.version.number, restored.history.last().restoredFrom)
        assertEquals(old.content, restored.content)
        assertEquals(current.metadata, restored.metadata)
        assertNull(restored.history.last().selectionChange)
        assertEquals(DraftSave.Conflict, store.restore(current.version, old.version.number))
        return restored
    }
    private fun assertAppend(old: StoredDraft, next: StoredDraft) {
        assertEquals(old.version.number + 1, next.version.number)
        assertEquals(old.history.size + 1, next.history.size)
        assertEquals(history(old.history), history(next.history.take(old.history.size)))
        assertEquals(old.legacySourceHash, next.legacySourceHash)
        assertEquals(old.earlierHistoryUnavailable, next.earlierHistoryUnavailable)
    }
    private fun assertMeaning(expected: JsonNode, actual: StoredDraft) {
        assertEquals(expected["format"].intValue(), actual.localFormatVersion)
        assertEquals(expected["revision"].longValue(), actual.version.number)
        assertEquals(expected["legacySourceHash"].textValue(), actual.legacySourceHash?.hex)
        assertEquals(expected["earlierHistoryUnavailable"].booleanValue(), actual.earlierHistoryUnavailable)
        assertEquals(normalize(expected["history"]), history(actual.history))
    }
    // This test-only observation projection is explicit; it never invokes the production envelope writer.
    private fun history(rows: List<StoredRevision>): JsonNode = normalize(mapper.valueToTree(rows.map { row ->
        val value = row.content
        val workload = value.workload
        linkedMapOf("number" to row.number, "change" to row.change.name, "restored_from" to row.restoredFrom,
            "metadata" to row.metadata, "selection_change" to row.selectionChange?.let { change -> linkedMapOf(
                "action" to change.action.name, "selection_id" to change.selectionId,
                "removed_link_ids" to change.removedLinkIds, "affected_link_ids" to change.affectedLinkIds) },
            "content" to linkedMapOf("objective" to value.objective, "context" to value.context, "primaryModel" to value.primaryModel,
                "optional" to value.optional.mapKeys { it.key.name }.mapValues { (_, tier) -> linkedMapOf(
                    "activation" to tier.activation.name, "selections" to tier.selections.toList(), "settings" to tier.settings) },
                "components" to linkedMapOf("selections" to value.components.selections.map { it.toJson() }, "links" to value.components.links.map { it.toJson() }),
                "workload" to linkedMapOf("region" to workload.region, "currency" to workload.currency,
                    "monthly_budget_amount" to workload.monthlyBudgetAmount?.encoded, "requests_per_day" to workload.requestsPerDay,
                    "avg_input_tokens" to workload.avgInputTokens, "avg_output_tokens" to workload.avgOutputTokens,
                    "max_concurrency" to workload.maxConcurrency, "max_latency_ms" to workload.maxLatencyMs,
                    "data_classification" to workload.dataClassification?.encoded)))
    }))
    private fun normalize(value: JsonNode): JsonNode = mapper.readTree(mapper.writeValueAsBytes(value))

    private fun archiveThenMigrateAndRestore(path: Path, store: LocalDraftStore, old: StoredDraft, original: ByteArray, expectedHistory: JsonNode) {
        fun artifact(name: String): DraftProjectionArtifact {
            val bytes = javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")!!.use { it.readBytes() }
            val node = mapper.readTree(bytes)
            return DraftProjectionArtifact(node["id"].textValue(), node[if (name == "ruleset") "version" else "revision"].asText(),
                ArtifactHash(CrossClientFixtures.sha256(bytes)), bytes)
        }
        val identity = DraftSnapshotIdentity("build.synthetic.migration", BigInteger.ONE, "SYNTHETIC migration fixture", "synthetic.local.owner",
            Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
        val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset"), artifact("dependency-graph"), artifact("pricebook"))
        val review = store.prepareSnapshotCandidate(old.version, inputs) as DraftProjectionPreparation.Review
        val acknowledgment = review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())
        val archive = LocalSnapshotArchive(Files.createDirectory(path.resolveSibling(path.fileName.toString() + "-archive")))
        val available = (archive.load() as SnapshotArchiveLoad.Available).state
        val saved = store.archiveSnapshotCandidate(review, acknowledgment, archive, available.version) as DraftSnapshotArchival.Archived
        val before = (archive.read(saved.entry.key) as SnapshotArchiveRead.Available).snapshot
        val buildBytes = before.bytes()
        val bundleHash = saved.entry.bundleHash
        assertArrayEquals(original, before.sourceBytes())
        assertEquals(4, before.sourceFormatVersion)
        assertEquals(normalize(expectedHistory), history(before.sourceHistory))
        val changed = (store.applySelection(roleIntent(old)) as DraftSelectionSave.Saved).draft
        assertRole(old, changed)
        assertEquals(5, restore(store, old, changed).localFormatVersion)
        val after = (LocalSnapshotArchive(path.resolveSibling(path.fileName.toString() + "-archive"))
            .readAt(saved.version, saved.entry.key) as SnapshotArchiveRead.Available).snapshot
        assertEquals(bundleHash, after.entry.bundleHash)
        assertEquals(1, after.profileVersion)
        assertEquals(4, after.sourceFormatVersion)
        assertArrayEquals(original, after.sourceBytes())
        assertArrayEquals(buildBytes, after.bytes())
        assertEquals(normalize(expectedHistory), history(after.sourceHistory))
        for (pin in after.pins) assertEquals(pin.hash.hex, CrossClientFixtures.sha256(after.artifactBytes(pin.kind)!!))
    }
}
