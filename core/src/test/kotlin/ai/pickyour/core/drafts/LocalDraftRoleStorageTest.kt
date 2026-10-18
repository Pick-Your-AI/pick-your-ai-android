package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalDraftRoleStorageTest {
    @TempDir lateinit var root: Path
    private val mapper = SelectionFixtures.mapper
    private val model = SelectionFixtures.selection()
    private val host = SelectionFixtures.selection("hosting_platform", "sel_host")
    private val link = SelectionFixtures.link("link.retained", model.id, host.id, false)
    private fun store() = LocalDraftStore(root)
    private fun loaded() = (store().load() as DraftLoad.Available).draft
    private fun cache() = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
    private fun raw() = (cache().active() as CacheRead.Available).bytes
    private fun initial() = (store().save(null, DraftContent(objective = "retained", components = DraftSelections(listOf(model, host), listOf(link))),
        mapOf("opaque" to "keep")) as DraftSave.Saved).draft
    private fun settings() = mapper.createObjectNode().put("temperature", java.math.BigDecimal("0.314159265358979323"))
    private fun command(role: String = "candidate") = SelectionCommand.Configure(model.id, settings(), role)
    private fun seed(bytes: ByteArray) {
        val hash = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        assertTrue(cache().install(hash, bytes.inputStream()) is CacheInstall.Activated)
    }
    private fun assertRevision(before: StoredRevision, after: StoredRevision) {
        assertEquals(before.number, after.number)
        assertEquals(before.change, after.change)
        assertEquals(before.restoredFrom, after.restoredFrom)
        assertEquals(before.content, after.content)
        assertEquals(before.metadata, after.metadata)
        assertEquals(before.selectionChange, after.selectionChange)
    }

    @Test fun firstRoleChangeAppendsOneVersionFiveRevisionAndRetainsVersionFourHistory() {
        val first = initial()
        val old = (store().applySelection(DraftSelectionIntent(first.version, SelectionCommand.Configure(model.id, settings()))) as DraftSelectionSave.Saved).draft
        val original = raw()
        assertEquals(4, old.localFormatVersion)
        assertEquals(SelectionAction.CONFIGURE, old.history.last().selectionChange!!.action)
        val outcome = store().applySelection(DraftSelectionIntent(old.version, command("candidate\n"))) as DraftSelectionSave.Saved
        val next = loaded()
        assertEquals(5, next.localFormatVersion)
        assertEquals(old.version.number + 1, next.version.number)
        assertEquals(old.history.size + 1, next.history.size)
        assertEquals(SelectionAction.CONFIGURE_ROLE, outcome.change!!.action)
        assertEquals(outcome.change, next.history.last().selectionChange)
        assertEquals(listOf(link.id), outcome.change.affectedLinkIds)
        assertTrue(outcome.change.removedLinkIds.isEmpty())
        assertEquals("candidate\n", next.content.components.selections.first().role)
        assertEquals(settings(), next.content.components.selections.first().settings)
        assertEquals(host, next.content.components.selections[1])
        assertEquals(listOf(link), next.content.components.links)
        assertEquals(old.metadata, next.metadata)
        old.history.zip(next.history).forEach { (before, after) -> assertRevision(before, after) }
        assertArrayEquals(original, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
        assertEquals(4, (store().revision(old.version.hash) as DraftLoad.Available).draft.localFormatVersion)
    }

    @Test fun noOpAndRejectedRolesDoNotWriteOrUpgradeTheAcknowledgedVersionFourRecord() {
        val old = initial()
        val original = raw()
        val noWrites = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { error("No-op or rejection cannot write") } }
        val unchanged = noWrites.applySelection(DraftSelectionIntent(old.version, SelectionCommand.Configure(model.id, model.settings, model.role))) as DraftSelectionSave.Saved
        assertNull(unchanged.change)
        assertEquals(old.version.hash, unchanged.draft.version.hash)
        assertEquals(4, unchanged.draft.localFormatVersion)
        assertEquals(DraftSelectionSave.Rejected(SelectionRejection.INVALID_SETTINGS),
            noWrites.applySelection(DraftSelectionIntent(old.version, command(" candidate"))))
        assertArrayEquals(original, raw())
        assertEquals(old.history.size, loaded().history.size)
    }

    @Test fun staleAndForeignAuthorityCannotApplyARoleEditOrASeeminglyEqualReplay() {
        val old = initial()
        val next = (store().applySelection(DraftSelectionIntent(old.version, command())) as DraftSelectionSave.Saved).draft
        val original = raw()
        assertEquals(DraftSelectionSave.Conflict, store().applySelection(DraftSelectionIntent(old.version, command())))
        val foreign = (LocalDraftStore(Files.createDirectory(root.resolve("foreign"))).save(null, next.content, next.metadata) as DraftSave.Saved).draft
        assertEquals(DraftSelectionSave.Conflict, store().applySelection(DraftSelectionIntent(foreign.version, command("another"))))
        assertArrayEquals(original, raw())
        assertEquals(next.version.hash, loaded().version.hash)
    }

    @Test fun migrationFailurePreservesOldBytesAndUncertainActivationRequiresReopen() {
        val old = initial()
        val original = raw()
        val intent = DraftSelectionIntent(old.version, command())
        val failed = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_STAGED) throw IOException("injected")
        } }
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.IO_FAILURE), failed.applySelection(intent))
        assertArrayEquals(original, raw())
        assertEquals(4, loaded().localFormatVersion)
        val uncertain = LocalDraftStore(root) { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) {
            if (it == CacheWritePoint.POINTER_ACTIVATED) throw IOException("injected")
        } }
        val outcome = uncertain.applySelection(intent) as DraftSelectionSave.Uncertain
        val reopened = loaded()
        assertEquals(outcome.hash, reopened.version.hash)
        assertEquals(5, reopened.localFormatVersion)
        assertEquals("candidate", reopened.content.components.selections.first().role)
        assertEquals(old.version.number + 1, reopened.version.number)
        assertEquals(old.metadata, reopened.metadata)
        assertRevision(old.history.single(), reopened.history.first())
        assertArrayEquals(original, (cache().pinned(old.version.hash) as CacheRead.Available).bytes)
        assertEquals(DraftSelectionSave.Conflict, store().applySelection(intent))
    }

    @Test fun versionedRoleDescriptionsCannotSmuggleOtherChangesOrRewriteLegacyMeaning() {
        val old = initial()
        store().applySelection(DraftSelectionIntent(old.version, command())) as DraftSelectionSave.Saved
        val valid = raw()
        fun last(node: ObjectNode) = node["history"].last() as ObjectNode
        fun selected(node: ObjectNode) = last(node)["content"]["components"]["selections"][0] as ObjectNode
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("local_format_version", 4) },
            { (last(it)["selection_change"] as ObjectNode).put("action", "CONFIGURE") },
            { selected(it).put("role", model.role) },
            { selected(it).put("enabled", false) },
            { selected(it).put("catalog_item_version", "another") },
            { selected(it).put("notes", "not a role edit") },
            { selected(it).putObject("extensions").put("x_extra", true) },
            { (last(it)["content"]["components"]["selections"][1] as ObjectNode).put("role", "also_changed") },
            { (last(it)["content"]["components"] as ObjectNode).putArray("links") },
            { (last(it)["selection_change"] as ObjectNode).putArray("affected_link_ids") },
            { (last(it)["metadata"] as ObjectNode).put("opaque", "changed") },
            { selected(it).put("role", "invalid role") },
        )
        for (mutate in mutations) {
            val bytes = mapper.writeValueAsBytes((mapper.readTree(valid) as ObjectNode).also(mutate))
            seed(bytes)
            assertEquals(DraftLoad.Failed(DraftStorageFailure.CORRUPT), store().load())
            assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.CORRUPT), store().applySelection(DraftSelectionIntent(old.version, command())))
            assertArrayEquals(bytes, raw())
        }
        // A future private format remains distinguishable from a malformed supported format.
        val future = mapper.writeValueAsBytes((mapper.readTree(valid) as ObjectNode).put("local_format_version", 6))
        seed(future)
        assertEquals(DraftLoad.Failed(DraftStorageFailure.UNSUPPORTED_VERSION), store().load())
        assertEquals(DraftSave.Failed(DraftStorageFailure.UNSUPPORTED_VERSION), store().save(null, DraftContent()))
        assertArrayEquals(future, raw())
    }

    @Test fun restoreRetainsRoleHistoryWhileRestoringContentAndCurrentMetadata() {
        val old = initial()
        val role = (store().applySelection(DraftSelectionIntent(old.version, command())) as DraftSelectionSave.Saved).draft
        val metadata = (store().save(role.version, role.content, mapOf("opaque" to "current")) as DraftSave.Saved).draft
        assertEquals(5, metadata.localFormatVersion)
        val restored = (store().restore(metadata.version, old.version.number) as DraftSave.Saved).draft
        assertEquals(5, restored.localFormatVersion)
        assertEquals(old.content, restored.content)
        assertEquals(metadata.metadata, restored.metadata)
        assertEquals(metadata.version.number + 1, restored.version.number)
        assertEquals(old.version.number, restored.history.last().restoredFrom)
        assertNull(restored.history.last().selectionChange)
        metadata.history.zip(restored.history).forEach { (before, after) -> assertRevision(before, after) }
        assertEquals(role.content, (store().revision(role.version.hash) as DraftLoad.Available).draft.content)
        val noOp = store().applySelection(DraftSelectionIntent(restored.version, SelectionCommand.Configure(model.id, model.settings, model.role))) as DraftSelectionSave.Saved
        assertEquals(restored.version.hash, noOp.draft.version.hash)
        assertNull(noOp.change)
    }

    @Test fun historyLimitRejectsRoleMigrationWithoutDroppingEntriesAndStillAllowsNoOp() {
        initial()
        val node = mapper.readTree(raw()) as ObjectNode
        val template = node["history"][0] as ObjectNode
        val entries = node.putArray("history")
        repeat(LocalDraftStore.MAX_HISTORY) { index ->
            entries.add(template.deepCopy().put("number", index).put("change", if (index == 0) "INITIAL" else "EDIT"))
        }
        node.put("revision", LocalDraftStore.MAX_HISTORY - 1)
        val original = mapper.writeValueAsBytes(node)
        seed(original)
        val old = loaded()
        assertEquals(DraftSelectionSave.Failed(DraftStorageFailure.LIMIT_EXCEEDED),
            store().applySelection(DraftSelectionIntent(old.version, command())))
        val noOp = store().applySelection(DraftSelectionIntent(old.version, SelectionCommand.Configure(model.id, model.settings, model.role))) as DraftSelectionSave.Saved
        assertEquals(old.version.hash, noOp.draft.version.hash)
        assertEquals(4, loaded().localFormatVersion)
        assertEquals(LocalDraftStore.MAX_HISTORY, loaded().history.size)
        assertArrayEquals(original, raw())
    }

    @Test fun concurrentRoleCommandsAcknowledgeAtMostOneMigration() {
        val old = initial()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf("candidate_a", "candidate_b").map { role -> pool.submit<DraftSelectionSave> {
                gate.await()
                store().applySelection(DraftSelectionIntent(old.version, command(role)))
            } }
            gate.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is DraftSelectionSave.Saved })
            assertEquals(1, results.count { it == DraftSelectionSave.Conflict || it == DraftSelectionSave.Failed(DraftStorageFailure.BUSY) })
            val current = loaded()
            assertEquals(5, current.localFormatVersion)
            assertEquals(old.version.number + 1, current.version.number)
            assertTrue(current.content.components.selections.first().role in setOf("candidate_a", "candidate_b"))
            assertEquals(listOf(link), current.content.components.links)
            assertEquals(old.metadata, current.metadata)
        } finally { pool.shutdownNow() }
    }
}
