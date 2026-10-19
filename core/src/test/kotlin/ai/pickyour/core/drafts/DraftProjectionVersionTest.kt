package ai.pickyour.core.drafts

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.preview.*
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Exercises the supported store/managed facade, not the internal projection implementation. */
class DraftProjectionVersionTest {
    @TempDir lateinit var root: Path

    private data class Fixture(val kind: ProjectionArtifactKind, val name: String)
    private val fixtures = listOf(
        Fixture(ProjectionArtifactKind.CATALOG, "catalog"),
        Fixture(ProjectionArtifactKind.RULESET, "ruleset"),
        Fixture(ProjectionArtifactKind.GRAPH, "dependency-graph"),
        Fixture(ProjectionArtifactKind.PRICEBOOK, "pricebook"),
    )

    @TestFactory fun unsupportedTextualVersionsHaveAnExplicitBoundedOutcome() = fixtures.flatMap { fixture ->
        listOf("future" to "2.0.0", "empty" to "", "nonexact" to "1.0.0\n").map { (label, version) ->
            dynamicTest(fixture.kind.toString() + ": unsupported textual " + label) {
                val bytes = ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).put("schema_version", version))
                // A boundary-specific diagnostic must not change the generic frozen validator API.
                assertEquals(FrozenValidation.SchemaMismatch,
                    FrozenSchemaValidator().validate(fixture.kind.document, bytes.inputStream()))
                assertWithheld(fixture, bytes, "ARTIFACT_UNSUPPORTED_SCHEMA_VERSION")
            }
        }
    }

    @TestFactory fun MissingNullAndNontextVersionsRemainSchemaFailures() = fixtures.flatMap { fixture ->
        val changes: List<Pair<String, (ObjectNode) -> Unit>> = listOf(
            "missing" to { it.remove("schema_version"); Unit },
            "null" to { it.putNull("schema_version"); Unit },
            "integer" to { it.put("schema_version", 2); Unit },
            "boolean" to { it.put("schema_version", true); Unit },
            "array" to { it.putArray("schema_version"); Unit },
            "object" to { it.putObject("schema_version"); Unit },
        )
        changes.map { (label, change) ->
            dynamicTest(fixture.kind.toString() + ": invalid version field " + label) {
                assertWithheld(fixture, ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).also(change)),
                    "ARTIFACT_SCHEMA_MISMATCH")
            }
        }
    }

    @TestFactory fun completeStrictParsingPrecedesVersionClassification() = fixtures.flatMap { fixture ->
        val future = ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).put("schema_version", "2.0.0"))
        val malformed = listOf(
            "duplicate header" to """{"schema_version":"2.0.0","schema_version":"1.0.0"}""".toByteArray(),
            "truncated object" to """{"schema_version":"2.0.0","unfinished":""".toByteArray(),
            "invalid UTF8" to (future + byteArrayOf(0xff.toByte())),
            "trailing object" to (future + " {}".toByteArray()),
            "array root" to """[{"schema_version":"2.0.0"}]""".toByteArray(),
            "string budget" to ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name)
                .put("schema_version", "x".repeat(16_385))),
            "depth budget" to ("""{"schema_version":"2.0.0","extra":""" +
                "[".repeat(33) + "null" + "]".repeat(33) + "}").toByteArray(),
        )
        malformed.map { (label, bytes) ->
            dynamicTest(fixture.kind.toString() + ": parse rejection " + label) {
                assertWithheld(fixture, bytes, "ARTIFACT_SCHEMA_MISMATCH")
            }
        }
    }

    @TestFactory fun exactHashMismatchStillPrecedesBothParsingAndVersionChecks() = fixtures.flatMap { fixture ->
        listOf(
            "unsupported version" to ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).put("schema_version", "2.0.0")),
            "malformed bytes" to "{".toByteArray(),
        ).map { (label, bytes) ->
            dynamicTest(fixture.kind.toString() + ": wrong hash before " + label) {
                val wrongHash = ArtifactHash("0".repeat(64))
                assertNotEquals(wrongHash, ProjectionFixtures.hash(bytes))
                assertWithheld(fixture, bytes, "ARTIFACT_HASH_MISMATCH", wrongHash)
            }
        }
    }

    @TestFactory fun supportedVersionDoesNotBypassTheWholeFrozenSchema() = fixtures.map { fixture ->
        dynamicTest(fixture.kind.toString() + ": supported version with extra field") {
            val bytes = ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).put("unexpected_field", true))
            assertWithheld(fixture, bytes, "ARTIFACT_SCHEMA_MISMATCH")
        }
    }

    @TestFactory fun supportedControlsPreserveOriginalArtifactsAndDecimalSpelling() = fixtures.map { fixture ->
        dynamicTest(fixture.kind.toString() + ": supported exact-byte control") {
            val directory = Files.createTempDirectory(root, "supported-")
            val store = LocalDraftStore(directory)
            val source = (store.save(null, content()) as DraftSave.Saved).draft
            val before = snapshot(directory)
            val artifact = ProjectionFixtures.artifact(fixture.name)
            val result = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.replace(fixture.kind, artifact))
                as DraftProjectionPreparation.Review
            val candidate = materialize(store, result)
            assertArrayEquals(artifact.bytes(), candidate.artifactBytes(fixture.kind))
            assertEquals("0.001230000000",
                ProjectionFixtures.mapper.readTree(candidate.bytes())["objectives"]["monthly_budget_amount"].textValue())
            assertEquals(source.version.hash, candidate.source.version.hash)
            assertEquals(before, snapshot(directory))
        }
    }

    @Test fun managedFacadePropagatesVersionFailuresWithoutChangingTheActiveDraftOrPriorCandidate() {
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val store = LocalDraftStore(root.resolve(created.id.value))
        assertTrue(store.save(created.record.draft.version, content(), created.record.draft.metadata) is DraftSave.Saved)
        val adapter = library.open(created.id)
        val record = (adapter.load() as PreviewDraftLoad.Available).record
        val active = PreviewActiveDraftStore(root)
        assertEquals(PreviewActiveWrite.Saved, active.select(created.id))
        val review = adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val candidate = (adapter.createSnapshotCandidate(review,
            review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created).candidate
        val candidateBytes = candidate.bytes()
        val before = snapshot(root)
        for (fixture in fixtures) {
            val bytes = ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).put("schema_version", "2.0.0"))
            val input = artifact(fixture, bytes)
            val result = adapter.prepareSnapshotCandidate(record, ProjectionFixtures.replace(fixture.kind, input))
            assertFinding(result, fixture, "ARTIFACT_UNSUPPORTED_SCHEMA_VERSION")
        }
        val loaded = (adapter.load() as PreviewDraftLoad.Available).record
        assertEquals(record.baselineIdentity, loaded.baselineIdentity)
        assertEquals(record.fields, loaded.fields)
        assertEquals(record.contextInput, loaded.contextInput)
        assertEquals(record.workloadInput, loaded.workloadInput)
        assertEquals(record.history.map { it.content }, loaded.history.map { it.content })
        assertEquals(PreviewActiveRead.Selected(created.id), active.read())
        assertArrayEquals(candidateBytes, candidate.bytes())
        assertEquals(before, snapshot(root))
        assertTrue(adapter.prepareSnapshotCandidate(record, ProjectionFixtures.inputs()) is DraftProjectionPreparation.Review)
    }

    @Test fun unsupportedArtifactsCannotRefreshAStalePreparationToken() {
        val store = LocalDraftStore(root)
        val old = (store.save(null, content()) as DraftSave.Saved).draft
        val current = (store.save(old.version, old.content, mapOf("retained" to "later")) as DraftSave.Saved).draft
        val before = snapshot(root)
        for (fixture in fixtures) {
            val bytes = ProjectionFixtures.bytes(ProjectionFixtures.node(fixture.name).put("schema_version", "2.0.0"))
            assertEquals(DraftProjectionPreparation.Conflict,
                store.prepareSnapshotCandidate(old.version, ProjectionFixtures.replace(fixture.kind, artifact(fixture, bytes))))
        }
        assertEquals(current.version.hash, (store.load() as DraftLoad.Available).draft.version.hash)
        assertEquals(before, snapshot(root))
    }

    private fun assertWithheld(fixture: Fixture, bytes: ByteArray, expected: String,
        hash: ArtifactHash = ProjectionFixtures.hash(bytes)) {
        val directory = Files.createTempDirectory(root, "withheld-")
        val store = LocalDraftStore(directory)
        val source = (store.save(null, content(), mapOf("retained" to "untouched")) as DraftSave.Saved).draft
        val priorReview = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()) as DraftProjectionPreparation.Review
        val priorCandidate = materialize(store, priorReview)
        val priorBytes = priorCandidate.bytes()
        val priorSource = priorCandidate.sourceBytes()
        val priorArtifacts = fixtures.associate { it.kind to priorCandidate.artifactBytes(it.kind)!!.toList() }
        val before = snapshot(directory)
        val input = artifact(fixture, bytes, hash)
        val result = store.prepareSnapshotCandidate(source.version, ProjectionFixtures.replace(fixture.kind, input))
        assertFinding(result, fixture, expected)
        assertEquals(source.version.hash, (store.load() as DraftLoad.Available).draft.version.hash)
        assertEquals(source.content, (result as DraftProjectionPreparation.Withheld).source.content)
        assertEquals(before, snapshot(directory))
        assertArrayEquals(bytes, input.bytes())
        assertArrayEquals(priorBytes, priorCandidate.bytes())
        assertArrayEquals(priorSource, priorCandidate.sourceBytes())
        assertEquals(priorArtifacts, fixtures.associate { it.kind to priorCandidate.artifactBytes(it.kind)!!.toList() })
        // Retrying with the original supported inputs requires no repair, migration or new token.
        assertTrue(store.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()) is DraftProjectionPreparation.Review)
        assertEquals(before, snapshot(directory))
    }

    private fun assertFinding(result: DraftProjectionPreparation, fixture: Fixture, expected: String) {
        val withheld = result as DraftProjectionPreparation.Withheld
        val finding = withheld.findings.single()
        assertEquals(expected, finding.problem.name)
        assertEquals("/artifacts/" + fixture.kind.name.lowercase(), finding.path)
        assertEquals(emptyList<String>(), finding.relatedValues)
        assertEquals(expected, finding.toString())
        assertEquals("DraftProjectionPreparation.Withheld", withheld.toString())
    }

    private fun artifact(fixture: Fixture, bytes: ByteArray, hash: ArtifactHash = ProjectionFixtures.hash(bytes)): DraftProjectionArtifact {
        val original = ProjectionFixtures.artifact(fixture.name)
        return DraftProjectionArtifact(original.expectedId, original.expectedVersion, hash, bytes)
    }

    private fun content(): DraftContent {
        val original = ProjectionFixtures.content()
        return DraftContent(original.objective, original.context, original.primaryModel, original.optional,
            original.workload.copy(monthlyBudgetAmount = ContractDecimal.parse("0.001230000000")), original.components)
    }

    private fun materialize(store: LocalDraftStore, review: DraftProjectionPreparation.Review): DraftSnapshotCandidate =
        (store.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()))
            as DraftProjectionCreation.Created).candidate

    private fun snapshot(directory: Path): Map<String, List<Byte>> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { directory.relativize(it).toString() to Files.readAllBytes(it).toList() }
    }
}
