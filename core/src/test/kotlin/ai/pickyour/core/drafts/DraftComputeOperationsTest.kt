package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.cache.VerifiedArtifactCache
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Durable T2 command cases; existing Add, role, restore and projection coverage remains separate. */
class DraftComputeOperationsTest {
    @TempDir lateinit var root: Path
    private val mapper = SelectionFixtures.mapper
    private val kinds = listOf("hosting_platform", "accelerator", "compute_profile", "inference_engine")
    private val subjectId = "selection_under_test"
    private val incidentIds = listOf("link.a", "link.self", "link.z")

    private class Fixture(
        val path: Path,
        val catalog: SelectionCatalogSnapshot,
        val replacement: CatalogSelectionChoice,
        val original: LocalSelection,
        val alternate: LocalSelection,
        val peer: LocalSelection,
        val links: List<LocalSelectionLink>,
        val head: StoredDraft,
    )

    private fun loaded(path: Path) = (LocalDraftStore(path).load() as DraftLoad.Available).draft
    private fun saved(result: DraftSave) = (result as DraftSave.Saved).draft
    private fun subject(draft: StoredDraft) = draft.content.components.selections.single { it.id == subjectId }
    private fun pinned(path: Path, hash: ArtifactHash): ByteArray =
        (VerifiedArtifactCache(path.resolve("revisions"), LocalDraftStore.MAX_BYTES).pinned(hash) as CacheRead.Available).bytes

    private fun fixture(kind: String): Fixture {
        val path = Files.createTempDirectory(root, "$kind-")
        val catalogNode = SelectionFixtures.catalogNode()
        val replacementItem = (catalogNode["items"].single { it["kind"].textValue() == kind } as ObjectNode)
            .deepCopy().put("version", "compute-coverage-v2")
        (catalogNode["items"] as ArrayNode).add(replacementItem)
        val catalog = SelectionCatalogSnapshot.fromJson(mapper.writeValueAsBytes(catalogNode))
        val original = LocalSelection.fromJson(SelectionFixtures.node(kind, subjectId, "primary", false).also {
            it.put("notes", "")
            it.putObject("extensions").putObject("x_retained").put("answer", "unanswered").put("flag", false)
        })
        val alternate = SelectionFixtures.selection(kind, "selection_alternate", "standby", false)
        val peer = SelectionFixtures.selection("model", "unrelated_model", "peer", false)
        val content = DraftContent(
            objective = "opaque objective", context = "", primaryModel = "legacy model input",
            optional = mapOf(OptionalTier.T3 to TierDraft(Activation.UNKNOWN, setOf("retained input"), mapOf("answer" to ""))),
            components = DraftSelections(listOf(alternate, peer)),
        )
        val initial = saved(LocalDraftStore(path).save(null, content, mapOf("opaque" to "retained", "empty" to "")))
        // These four kinds already have durable Add coverage. Add is an explicit setup command here.
        val added = changed(path, initial, SelectionCommand.Add(original, SelectionFixtures.choice(catalog, original)),
            catalog, SelectionAction.ADD, listOf(alternate, peer, original), emptyList(), emptyList())
        val links = listOf(
            SelectionFixtures.link("link.z", subjectId, peer.id, false),
            SelectionFixtures.link("link.a", peer.id, subjectId, true),
            SelectionFixtures.link("link.self", subjectId, subjectId),
            SelectionFixtures.link("link.keep", alternate.id, peer.id, false),
        )
        // There is no link-add command; ordinary content save supplies only the relationship fixture.
        val linkedContent = added.content.withComponents(DraftSelections(listOf(alternate, peer, original), links))
        val head = saved(LocalDraftStore(path).save(added.version, linkedContent))
        assertEquals(added.version.number + 1, head.version.number)
        assertEquals(added.metadata, head.metadata)
        assertEquals(linkedContent, head.content)
        assertSameDraft(head, loaded(path))
        val replacement = CatalogSelectionChoice(catalog.hash, kind, original.catalogItemId, "compute-coverage-v2")
        assertTrue(catalog.contains(replacement))
        assertNotEquals(original.catalogItemVersion, replacement.itemVersion)
        return Fixture(path, catalog, replacement, original, alternate, peer, links, head)
    }

    private fun assertRevision(expected: StoredRevision, actual: StoredRevision) {
        assertEquals(expected.number, actual.number)
        assertEquals(expected.change, actual.change)
        assertEquals(expected.restoredFrom, actual.restoredFrom)
        assertEquals(expected.content, actual.content)
        assertEquals(expected.metadata, actual.metadata)
        assertEquals(expected.selectionChange, actual.selectionChange)
    }

    private fun assertSameDraft(expected: StoredDraft, actual: StoredDraft) {
        assertEquals(expected.version.number, actual.version.number)
        assertEquals(expected.version.hash, actual.version.hash)
        assertEquals(expected.localFormatVersion, actual.localFormatVersion)
        assertEquals(expected.legacySourceHash, actual.legacySourceHash)
        assertEquals(expected.content, actual.content)
        assertEquals(expected.metadata, actual.metadata)
        assertEquals(expected.history.size, actual.history.size)
        expected.history.zip(actual.history).forEach { (before, after) -> assertRevision(before, after) }
    }

    private fun changed(
        path: Path, before: StoredDraft, command: SelectionCommand, catalog: SelectionCatalogSnapshot?,
        action: SelectionAction, selections: List<LocalSelection>, links: List<LocalSelectionLink>,
        affected: List<String> = incidentIds, removed: List<String> = emptyList(),
    ): StoredDraft {
        val previousBytes = pinned(path, before.version.hash)
        val result = LocalDraftStore(path).applySelection(DraftSelectionIntent(before.version, command), catalog)
            as DraftSelectionSave.Saved
        val after = result.draft
        assertEquals(before.version.number + 1, after.version.number)
        assertNotEquals(before.version.hash, after.version.hash)
        assertEquals(before.history.size + 1, after.history.size)
        assertEquals(before.metadata, after.metadata)
        assertEquals(before.content.withComponents(DraftSelections()), after.content.withComponents(DraftSelections()))
        assertEquals(DraftSelections(selections, links), after.content.components)
        val change = requireNotNull(result.change)
        assertEquals(action, change.action)
        assertEquals(subjectId, change.selectionId)
        assertEquals(affected, change.affectedLinkIds)
        assertEquals(removed, change.removedLinkIds)
        assertEquals(change, after.history.last().selectionChange)
        assertEquals(StoredRevisionChange.EDIT, after.history.last().change)
        assertNull(after.history.last().restoredFrom)
        before.history.zip(after.history).forEach { (old, retained) -> assertRevision(old, retained) }
        assertSameDraft(after, loaded(path))
        assertArrayEquals(previousBytes, pinned(path, before.version.hash))
        assertSameDraft(before, (LocalDraftStore(path).revision(before.version.hash) as DraftLoad.Available).draft)
        return after
    }

    private fun fileHashes(path: Path): Map<String, String> = Files.walk(path).use { files ->
        files.filter { Files.isRegularFile(it) }.toList().associate { file ->
            path.relativize(file).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    private fun configuredSettings(kind: String): ObjectNode = when (kind) {
        "hosting_platform" -> mapper.createObjectNode().put("region", "exact fixture region")
            .put("account_ref", "secret://fixture/hosting-account").put("network_isolation", "private")
        "accelerator" -> mapper.createObjectNode().put("device_count", BigInteger("9007199254740993"))
            .put("tensor_parallel_groups", BigInteger("2"))
        "compute_profile" -> mapper.createObjectNode().put("instances", BigInteger("3"))
            .put("reserved_hours_per_month", BigDecimal("0.125000000000000001")).put("region", "exact fixture region")
        "inference_engine" -> mapper.createObjectNode().put("tensor_parallel_size", BigInteger("2"))
            .put("max_batch_tokens", BigInteger("9007199254740993")).put("precision", "fp16").put("quantization", "awq")
        else -> error("Unknown compute kind")
    }

    private fun invalidSettings(kind: String): ObjectNode = when (kind) {
        "hosting_platform" -> mapper.createObjectNode().put("network_isolation", "unsupported-isolation")
        "accelerator" -> mapper.createObjectNode().put("device_count", 0)
        "compute_profile" -> mapper.createObjectNode().put("instances", 0)
        "inference_engine" -> mapper.createObjectNode().put("precision", "unsupported-precision")
        else -> error("Unknown compute kind")
    }

    private fun lifecycle(kind: String) {
        val fixture = fixture(kind)
        var current = fixture.head
        val settings = configuredSettings(kind)
        assertNotEquals(fixture.original.settings, settings)
        var expected = LocalSelection.fromJson(fixture.original.toJson().also { it.set<JsonNode>("settings", settings) })
        val command = SelectionCommand.Configure(subjectId, settings)
        settings.put("unknown_fixture_property", true)
        command.settings.put("another_unknown_fixture_property", false)
        current = changed(fixture.path, current, command, null, SelectionAction.CONFIGURE,
            listOf(fixture.alternate, fixture.peer, expected), fixture.links)
        assertEquals(configuredSettings(kind), subject(current).settings)
        when (kind) {
            "accelerator" -> assertEquals(BigInteger("9007199254740993"), subject(current).settings["device_count"].bigIntegerValue())
            "compute_profile" -> assertEquals(BigDecimal("0.125000000000000001"), subject(current).settings["reserved_hours_per_month"].decimalValue())
            "inference_engine" -> assertEquals(BigInteger("9007199254740993"), subject(current).settings["max_batch_tokens"].bigIntegerValue())
        }
        expected = LocalSelection.fromJson(expected.toJson().put("catalog_item_version", fixture.replacement.itemVersion))
        current = changed(fixture.path, current, SelectionCommand.Replace(subjectId, fixture.replacement),
            fixture.catalog, SelectionAction.REPLACE, listOf(fixture.alternate, fixture.peer, expected), fixture.links)
        for (enabled in listOf(true, false)) {
            expected = LocalSelection.fromJson(expected.toJson().put("enabled", enabled))
            current = changed(fixture.path, current, SelectionCommand.SetEnabled(subjectId, enabled), null,
                if (enabled) SelectionAction.ENABLE else SelectionAction.DISABLE,
                listOf(fixture.alternate, fixture.peer, expected), fixture.links)
        }
        val beforeNoop = fileHashes(fixture.path)
        val noop = LocalDraftStore(fixture.path).applySelection(
            DraftSelectionIntent(current.version, SelectionCommand.SetEnabled(subjectId, false))) as DraftSelectionSave.Saved
        assertNull(noop.change)
        assertSameDraft(current, noop.draft)
        assertSameDraft(current, loaded(fixture.path))
        assertEquals(beforeNoop, fileHashes(fixture.path))

        expected = LocalSelection.fromJson(expected.toJson().put("enabled", true))
        current = changed(fixture.path, current, SelectionCommand.SetEnabled(subjectId, true), null,
            SelectionAction.ENABLE, listOf(fixture.alternate, fixture.peer, expected), fixture.links)
        val removed = changed(fixture.path, current, SelectionCommand.Remove(subjectId), null, SelectionAction.REMOVE,
            listOf(fixture.alternate, fixture.peer), listOf(fixture.links.single { it.id == "link.keep" }),
            removed = incidentIds)
        val alternate = removed.content.components.selections.single { it.id == fixture.alternate.id }
        assertEquals("standby", alternate.role)
        assertFalse(alternate.enabled)
        assertEquals(fixture.alternate, alternate)
    }

    @TestFactory fun `compute settings replacement activation and removal preserve acknowledged history`(): List<DynamicTest> =
        kinds.map { kind ->
            DynamicTest.dynamicTest("$kind: durable settings replacement activation and incident removal") { lifecycle(kind) }
        }

    @TestFactory fun `compute rejected or stale commands leave every saved file unchanged`(): List<DynamicTest> =
        kinds.flatMap { kind ->
            listOf("wrong catalog hash", "wrong catalog kind", "absent catalog version", "invalid settings",
                "disabled duplicate role", "stale token before invalid settings").map { scenario ->
                DynamicTest.dynamicTest("$kind: $scenario") {
                    val fixture = fixture(kind)
                    var current = fixture.head
                    val captured = current.version
                    val choice = fixture.replacement
                    val invalid = invalidSettings(kind)
                    val command = when (scenario) {
                        "wrong catalog hash" -> {
                            val wrong = ArtifactHash("0".repeat(64))
                            assertNotEquals(choice.snapshotHash, wrong)
                            SelectionCommand.Replace(subjectId, choice.copy(snapshotHash = wrong))
                        }
                        "wrong catalog kind" -> SelectionCommand.Replace(subjectId, choice.copy(kind = "model"))
                        "absent catalog version" -> {
                            val absent = choice.copy(itemVersion = "absent-from-this-captured-catalog")
                            assertFalse(fixture.catalog.contains(absent))
                            SelectionCommand.Replace(subjectId, absent)
                        }
                        "invalid settings" -> SelectionCommand.Configure(subjectId, invalid)
                        "disabled duplicate role" -> {
                            assertFalse(subject(current).enabled)
                            assertFalse(fixture.alternate.enabled)
                            SelectionCommand.Configure(subjectId, subject(current).settings, fixture.alternate.role)
                        }
                        "stale token before invalid settings" -> {
                            val enabled = LocalSelection.fromJson(fixture.original.toJson().put("enabled", true))
                            current = changed(fixture.path, current, SelectionCommand.SetEnabled(subjectId, true), null,
                                SelectionAction.ENABLE, listOf(fixture.alternate, fixture.peer, enabled), fixture.links)
                            SelectionCommand.Configure(subjectId, invalid)
                        }
                        else -> error("Unknown test scenario")
                    }
                    val beforeFiles = fileHashes(fixture.path)
                    val result = LocalDraftStore(fixture.path).applySelection(DraftSelectionIntent(captured, command), fixture.catalog)
                    val expected = when (scenario) {
                        "wrong catalog hash", "wrong catalog kind" -> DraftSelectionSave.Rejected(SelectionRejection.CATALOG_PIN_MISMATCH)
                        "absent catalog version" -> DraftSelectionSave.Rejected(SelectionRejection.CATALOG_ITEM_UNAVAILABLE)
                        "invalid settings" -> DraftSelectionSave.Rejected(SelectionRejection.INVALID_SETTINGS)
                        "disabled duplicate role" -> DraftSelectionSave.Rejected(SelectionRejection.DUPLICATE_KIND_ROLE)
                        "stale token before invalid settings" -> DraftSelectionSave.Conflict
                        else -> error("Unknown test scenario")
                    }
                    assertEquals(expected, result)
                    assertSameDraft(current, loaded(fixture.path))
                    assertEquals(beforeFiles, fileHashes(fixture.path))
                }
            }
        }
}

