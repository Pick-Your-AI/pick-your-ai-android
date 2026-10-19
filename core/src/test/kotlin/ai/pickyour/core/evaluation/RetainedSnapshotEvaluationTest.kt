package ai.pickyour.core.evaluation

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.drafts.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object SnapshotEvaluationFixtures {
    class Saved(val source: LocalDraftStore, val sourcePath: Path, val archive: LocalSnapshotArchive,
        val version: SnapshotArchiveVersion, val entry: SnapshotArchiveEntry, val snapshot: RetainedDraftSnapshot)
    fun save(root: Path, id: String = "build.snapshot.evaluation", revision: BigInteger = BigInteger.ONE,
        pricebook: Boolean = true, graph: DraftProjectionArtifact = ProjectionFixtures.artifact("dependency-graph"),
        metadata: Map<String, String> = mapOf("private" to "retained-only-value"), rawRevision: String? = null,
        retainedArchive: LocalSnapshotArchive? = null): Saved {
        val sourcePath = root.resolve("source").also(Files::createDirectories)
        val source = LocalDraftStore(sourcePath)
        val stored = (source.save(null, ProjectionFixtures.content(allKinds = true), metadata) as DraftSave.Saved).draft
        val original = ProjectionFixtures.identity(id)
        val identity = DraftSnapshotIdentity(id, revision, original.name, original.ownerRef, original.createdAt, original.updatedAt)
        val inputs = ProjectionFixtures.inputs(identity, graph = graph,
            pricebook = if (pricebook) ProjectionFixtures.artifact("pricebook") else null)
        var review = source.prepareSnapshotCandidate(stored.version, inputs) as DraftProjectionPreparation.Review
        if (rawRevision != null) {
            val bytes = review.candidateBytes.toString(Charsets.UTF_8).replace("\"revision\":$revision", "\"revision\":$rawRevision").toByteArray()
            review = DraftProjectionPreparation.Review(review.source, review.identity, review.sourceBytes, bytes, review.artifacts)
        }
        val archive = retainedArchive ?: LocalSnapshotArchive(root.resolve("archive").also(Files::createDirectories))
        val empty = (archive.load() as SnapshotArchiveLoad.Available).state
        val result = source.archiveSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, empty.version)
            as DraftSnapshotArchival.Archived
        val snapshot = (archive.read(result.entry.key) as SnapshotArchiveRead.Available).snapshot
        return Saved(source, sourcePath, archive, result.version, result.entry, snapshot)
    }
    fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    fun prepared(snapshot: RetainedDraftSnapshot) = (LocalSnapshotEvaluator.prepare(snapshot) as SnapshotEvaluationPreparation.Ready).request
    /** Deliberate internal malformed-object injection; public callers can only obtain a verified archive read. */
    fun malformed(original: RetainedDraftSnapshot, build: ByteArray = original.bytes(),
        artifacts: (List<CheckedProjectionArtifact>) -> List<CheckedProjectionArtifact> = { it }): RetainedDraftSnapshot {
        val values = original.pins.map { pin ->
            val raw = original.artifactBytes(pin.kind)!!
            CheckedProjectionArtifact(pin, raw, SelectionJson.parse(raw))
        }
        return RetainedDraftSnapshot(original.entry, original.identity, DraftEnvelope.decode(original.sourceBytes()),
            original.retainedOnlyPaths, artifacts(values), build, original.sourceBytes())
    }
}

class RetainedSnapshotEvaluationTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private val actualTime = Instant.parse("2026-10-18T22:00:00Z")
    private val evaluator = LocalSnapshotEvaluator(Clock.fixed(actualTime, ZoneOffset.UTC))
    private fun save(name: String = "one", revision: BigInteger = BigInteger.ONE, pricebook: Boolean = true) =
        SnapshotEvaluationFixtures.save(root.resolve(name), revision = revision, pricebook = pricebook)
    private fun run(snapshot: RetainedDraftSnapshot) = evaluator.evaluate(SnapshotEvaluationFixtures.prepared(snapshot)) as SnapshotEvaluationOutcome.Evaluated
    private fun statuses(value: SnapshotEvaluationPreparation) = (value as SnapshotEvaluationPreparation.Withheld).diagnostics.associate { it.slot to it.status }
    private fun evidence(name: String, snapshot: RetainedDraftSnapshot, change: (ObjectNode) -> Unit = {}): SnapshotEvidenceInput.Requested {
        val node = ProjectionFixtures.node(name).put("build_id", snapshot.entry.key.id).put("build_revision", snapshot.entry.key.revision)
        change(node)
        val bytes = node.toString().toByteArray()
        return SnapshotEvidenceInput.Requested(node["id"].textValue(), SnapshotEvaluationFixtures.hash(bytes), bytes)
    }

    @Test fun `actual retained bundle reproduces the complete no-evidence Python golden and keeps both times`() {
        val saved = save()
        val request = SnapshotEvaluationFixtures.prepared(saved.snapshot)
        val result = evaluator.evaluate(request) as SnapshotEvaluationOutcome.Evaluated
        val expected = mapper.readTree(javaClass.getResourceAsStream("/evaluation/diagnostic-cases.json"))
            .single { it["name"].textValue() == "original-strict-False" }["report"].deepCopy<ObjectNode>()
        expected.put("id", "evaluation.${saved.entry.key.id}.r${saved.entry.key.revision}")
            .put("build_id", saved.entry.key.id).put("build_revision", saved.entry.key.revision)
        // Apply the same JSON reader to both sides: a programmatically inserted BigIntegerNode(1)
        // differs from IntNode(1) under Jackson node equality despite identical JSON report values.
        assertEquals(mapper.readTree(expected.toString()), mapper.readTree(result.report.json))
        assertEquals(45, result.report.inspect().findings.size)
        assertEquals(RetainedEvaluationProfile.REFERENCE_TIME, result.report.inspect().evaluatedAt)
        assertEquals(actualTime, result.observedAt)
        assertTrue(result.belongsTo(request))
        assertTrue(result.report.inspect().findings.any { it.outcome == "unknown" })
        assertNotEquals("ready", result.report.inspect().deploymentReadiness)
        assertFalse(result.report.json.contains("retained-only-value"))
    }

    @Test fun `request captures every exact pin and remains independent of copied bytes and source deletion`() {
        val saved = save()
        val original = saved.snapshot.bytes()
        val request = SnapshotEvaluationFixtures.prepared(saved.snapshot)
        assertEquals(5, request.bindings.size)
        assertEquals(saved.entry.bundleHash, request.bundleHash)
        assertEquals(SnapshotEvaluationFixtures.hash(original), request.bindings.single { it.slot == SnapshotEvaluationSlot.BUILD }.hash)
        for (pin in saved.snapshot.pins) {
            val binding = request.bindings.single { it.slot.name == pin.kind.name }
            assertEquals(pin.id, binding.id); assertEquals(pin.version, binding.version); assertEquals(pin.hash, binding.hash)
            assertEquals(saved.snapshot.artifactBytes(pin.kind)!!.size, binding.bytes)
        }
        saved.snapshot.bytes().fill(0); saved.snapshot.artifactBytes(ProjectionArtifactKind.GRAPH)!!.fill(0)
        Files.walk(saved.sourcePath).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        assertArrayEquals(original, request.bytes(SnapshotEvaluationSlot.BUILD))
        assertTrue(evaluator.evaluate(request) is SnapshotEvaluationOutcome.Evaluated)
        assertThrows(UnsupportedOperationException::class.java) { (request.bindings as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (request.diagnostics as MutableList).clear() }
    }

    @Test fun `mathematical revisions above Long remain exact in request report and digest`() {
        val wide = BigInteger("9223372036854775808")
        val saved = save(revision = wide)
        val result = run(saved.snapshot)
        assertEquals(wide, result.report.inspect().buildRevision)
        assertTrue(result.report.inspect().id.endsWith("r$wide"))
        val another = save("another", wide + BigInteger.ONE)
        assertNotEquals(SnapshotEvaluationFixtures.prepared(saved.snapshot).digest, SnapshotEvaluationFixtures.prepared(another.snapshot).digest)
    }

    @Test fun `same Build bytes with different retained-only source and retry authority stay distinct`() {
        val first = SnapshotEvaluationFixtures.save(root.resolve("first"), metadata = mapOf("private" to "first"))
        val second = SnapshotEvaluationFixtures.save(root.resolve("second"), metadata = mapOf("private" to "second"))
        assertArrayEquals(first.snapshot.bytes(), second.snapshot.bytes())
        val a = SnapshotEvaluationFixtures.prepared(first.snapshot)
        val retry = SnapshotEvaluationFixtures.prepared(first.snapshot)
        val b = SnapshotEvaluationFixtures.prepared(second.snapshot)
        assertEquals(a.digest, retry.digest); assertNotEquals(a.digest, b.digest)
        val result = evaluator.evaluate(a)
        assertTrue(result.belongsTo(a)); assertFalse(result.belongsTo(retry)); assertFalse(result.belongsTo(b))
    }

    @Test fun `retained Pricebook changes exact input identity without supplying cost metrics`() {
        val with = save("with")
        val without = save("without", pricebook = false)
        val first = SnapshotEvaluationFixtures.prepared(with.snapshot)
        val second = SnapshotEvaluationFixtures.prepared(without.snapshot)
        assertEquals(SnapshotInputStatus.BOUND, first.diagnostics.single { it.slot == SnapshotEvaluationSlot.PRICEBOOK }.status)
        assertEquals(SnapshotInputStatus.NOT_SUPPLIED, second.diagnostics.single { it.slot == SnapshotEvaluationSlot.PRICEBOOK }.status)
        assertNotEquals(first.digest, second.digest)
        assertEquals((evaluator.evaluate(first) as SnapshotEvaluationOutcome.Evaluated).report.json,
            (evaluator.evaluate(second) as SnapshotEvaluationOutcome.Evaluated).report.json)
        assertTrue(first.diagnostics.filter { it.slot in listOf(SnapshotEvaluationSlot.COST_ESTIMATE, SnapshotEvaluationSlot.RESOURCE_ESTIMATE) }
            .all { it.status == SnapshotInputStatus.NOT_SUPPLIED })
    }

    @Test fun `missing requested evidence does not erase independently checked supplied evidence`() {
        val snapshot = save().snapshot
        val missing = SnapshotEvidenceInput.Requested("missing", ArtifactHash("0".repeat(64)), null)
        val value = LocalSnapshotEvaluator.prepare(snapshot, missing, evidence("cost-estimate", snapshot))
        val states = statuses(value)
        assertEquals(SnapshotInputStatus.REQUESTED_UNAVAILABLE, states[SnapshotEvaluationSlot.RESOURCE_ESTIMATE])
        assertEquals(SnapshotInputStatus.UNSUPPORTED_EVIDENCE, states[SnapshotEvaluationSlot.COST_ESTIMATE])
        assertTrue((value as SnapshotEvaluationPreparation.Withheld).diagnostics.single { it.slot == SnapshotEvaluationSlot.COST_ESTIMATE }.binding != null)
    }

    @Test fun `even fully bound declared complete and calibrated estimates are explicitly withheld`() {
        val snapshot = save().snapshot
        val states = statuses(LocalSnapshotEvaluator.prepare(snapshot,
            evidence("resource-estimate", snapshot), evidence("cost-estimate", snapshot)))
        assertEquals(SnapshotInputStatus.UNSUPPORTED_EVIDENCE, states[SnapshotEvaluationSlot.RESOURCE_ESTIMATE])
        assertEquals(SnapshotInputStatus.UNSUPPORTED_EVIDENCE, states[SnapshotEvaluationSlot.COST_ESTIMATE])
    }

    @Test fun `optional evidence distinguishes malformed wrong-version wrong-type foreign and wrong-hash inputs`() {
        val snapshot = save().snapshot
        val raw = "{\"schema_version\":\"1.0.0\",\"schema_version\":\"1.0.0\"}".toByteArray()
        val malformed = SnapshotEvidenceInput.Requested("opaque", SnapshotEvaluationFixtures.hash(raw), raw)
        val cases = listOf(
            malformed to SnapshotInputStatus.MALFORMED_INPUT,
            evidence("resource-estimate", snapshot) { it.put("schema_version", "2.0.0") } to SnapshotInputStatus.UNSUPPORTED_VERSION,
            evidence("resource-estimate", snapshot) { it.put("build_revision", "1") } to SnapshotInputStatus.SCHEMA_MISMATCH,
            evidence("resource-estimate", snapshot) { it.put("build_id", "build.foreign") } to SnapshotInputStatus.IDENTITY_MISMATCH,
            evidence("resource-estimate", snapshot) { it.put("build_revision", BigInteger.TWO) } to SnapshotInputStatus.IDENTITY_MISMATCH,
            SnapshotEvidenceInput.Requested("opaque", ArtifactHash("0".repeat(64)), raw) to SnapshotInputStatus.HASH_MISMATCH,
        )
        for ((input, expected) in cases) assertEquals(expected,
            statuses(LocalSnapshotEvaluator.prepare(snapshot, resource = input))[SnapshotEvaluationSlot.RESOURCE_ESTIMATE])
    }

    @Test fun `malformed Build and foreign exact graph locks reject without hiding other binding diagnostics`() {
        val snapshot = save().snapshot
        val invalidBuild = SnapshotEvaluationFixtures.malformed(snapshot, "{\"schema_version\":\"1.0.0\",\"revision\":false}".toByteArray())
        val bad = statuses(LocalSnapshotEvaluator.prepare(invalidBuild))
        assertEquals(SnapshotInputStatus.SCHEMA_MISMATCH, bad[SnapshotEvaluationSlot.BUILD])
        assertEquals(SnapshotInputStatus.BOUND, bad[SnapshotEvaluationSlot.GRAPH])
        val changed = mapper.readTree(snapshot.bytes()) as ObjectNode
        (changed["locks"] as ObjectNode).put("dependency_graph_revision", 2)
        assertEquals(SnapshotInputStatus.IDENTITY_MISMATCH,
            statuses(LocalSnapshotEvaluator.prepare(SnapshotEvaluationFixtures.malformed(snapshot, changed.toString().toByteArray())))[SnapshotEvaluationSlot.GRAPH])
        val missing = SnapshotEvaluationFixtures.malformed(snapshot) { it.filter { artifact -> artifact.pin.kind != ProjectionArtifactKind.GRAPH } }
        assertEquals(SnapshotInputStatus.REQUESTED_UNAVAILABLE, statuses(LocalSnapshotEvaluator.prepare(missing))[SnapshotEvaluationSlot.GRAPH])
    }

    @Test fun `original artifact hash mismatch rejects before a report can be prepared`() {
        val snapshot = save().snapshot
        val changed = SnapshotEvaluationFixtures.malformed(snapshot) { list -> list.map { artifact ->
            if (artifact.pin.kind != ProjectionArtifactKind.CATALOG) artifact else CheckedProjectionArtifact(artifact.pin,
                (artifact.bytes.toString(Charsets.UTF_8) + "\n").toByteArray(), artifact.value)
        } }
        assertEquals(SnapshotInputStatus.HASH_MISMATCH, statuses(LocalSnapshotEvaluator.prepare(changed))[SnapshotEvaluationSlot.CATALOG])
    }

    @Test fun `schema-valid integral decimal Build spelling stays explicitly unsupported by the local evaluator`() {
        val saved = SnapshotEvaluationFixtures.save(root.resolve("alias"), rawRevision = "1e0")
        assertEquals(BigInteger.ONE, saved.snapshot.entry.key.revision)
        assertTrue(saved.snapshot.bytes().toString(Charsets.UTF_8).contains("\"revision\":1e0"))
        val result = evaluator.evaluate(SnapshotEvaluationFixtures.prepared(saved.snapshot)) as SnapshotEvaluationOutcome.Failed
        assertEquals(SnapshotEvaluationFailure.UNSUPPORTED_OPERATION, result.reason)
    }

    @Test fun `unknown profile and reader budgets withhold instead of silently selecting another version`() {
        val snapshot = save().snapshot
        assertEquals(SnapshotInputStatus.UNSUPPORTED_PROFILE, statuses(LocalSnapshotEvaluator.prepare(snapshot, profile = "future/2"))[SnapshotEvaluationSlot.BUILD])
        val large = SnapshotEvaluationFixtures.malformed(snapshot, " ".repeat(262145).toByteArray())
        assertEquals(SnapshotInputStatus.LOCAL_INPUT_LIMIT, statuses(LocalSnapshotEvaluator.prepare(large))[SnapshotEvaluationSlot.BUILD])
    }

    @Test fun `cancelled work and unavailable observation clock do not return a report`() {
        val request = SnapshotEvaluationFixtures.prepared(save().snapshot)
        try {
            Thread.currentThread().interrupt()
            assertTrue(evaluator.evaluate(request) is SnapshotEvaluationOutcome.Cancelled)
        } finally { Thread.interrupted() }
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = throw IllegalStateException("private failure")
        }
        val failed = LocalSnapshotEvaluator(clock).evaluate(request) as SnapshotEvaluationOutcome.Failed
        assertEquals(SnapshotEvaluationFailure.ENGINE_FAILURE, failed.reason)
        assertFalse(failed.toString().contains("private failure"))
        assertTrue(evaluator.evaluate(request) is SnapshotEvaluationOutcome.Evaluated)
    }
}
