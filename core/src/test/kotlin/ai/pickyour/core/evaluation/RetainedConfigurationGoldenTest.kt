package ai.pickyour.core.evaluation

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.contracts.ContractDecimal
import ai.pickyour.core.drafts.*
import ai.pickyour.core.preview.PreviewSnapshotAnalysis
import ai.pickyour.core.preview.PreviewSnapshotAnalysisState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.zip.GZIPInputStream

/** Full configuration semantics through the real retained-store and preview-session boundaries. */
class RetainedConfigurationGoldenTest {
    @TempDir lateinit var root: Path
    private val mapper = ObjectMapper()
    private val observed = Instant.parse("2026-10-19T02:00:00Z")
    private val expectedNames = listOf(
        "all-active-control", "contradictory-target-region", "contradictory-host-region",
        "contradictory-self-hosted-serving", "optional-t3-t4-t5-disabled", "optional-t3-enabled",
        "optional-t4-enabled-without-t3-vector", "optional-t5-enabled", "rag-with-disabled-knowledge",
        "agent-with-disabled-framework", "training-with-disabled-compute", "production-with-disabled-guardrail",
        "development-with-disabled-guardrail", "hard-cap-disabled-without-cost", "zero-budget-without-cost",
        "precise-budget-without-cost", "adjacent-budget-without-cost",
    )
    private fun raw(name: String): ByteArray = javaClass.getResourceAsStream(name)!!.use { it.readBytes() }
    private fun hash(bytes: ByteArray) = SnapshotEvaluationFixtures.hash(bytes)

    @TestFactory fun `complete retained configuration goldens preserve reference reports and explicit input authority`(): List<DynamicTest> {
        val corpus = GZIPInputStream(raw("/evaluation/configuration-cases.json.gz").inputStream()).use { mapper.readTree(it) }
        assertEquals(1, corpus["fixture_version"].intValue())
        assertEquals(RetainedEvaluationProfile.ID, corpus["profile"].textValue())
        assertEquals(RetainedEvaluationProfile.REFERENCE_SHA256,
            corpus["inputs"]["contracts/v1/reference/evaluate.py"].textValue())
        assertEquals(RetainedEvaluationProfile.SCHEMAS_SHA256, corpus["schema_descriptor_sha256"].textValue())
        assertEquals(expectedNames, corpus["cases"].map { it["name"].textValue() })
        assertEquals(45, corpus["rule_count"].intValue())
        assertEquals(mapper.readTree("""{"resource_estimate":"not_supplied","cost_estimate":"not_supplied"}"""),
            corpus["external_evidence"])
        for (path in listOf("contracts/v1/examples/build.json", "contracts/v1/schemas/component-registry.json")) {
            assertEquals(ArtifactHash(corpus["inputs"][path].textValue()), hash(raw("/$path")))
        }
        assertEquals(ArtifactHash(corpus["inputs"]["core/src/test/resources/evaluation/diagnostic-cases.json"].textValue()),
            hash(raw("/evaluation/diagnostic-cases.json")))

        return corpus["cases"].map { case ->
            DynamicTest.dynamicTest(case["name"].textValue()) {
                val name = case["name"].textValue()
                val build = case["build"] as ObjectNode
                val objectives = build["objectives"]
                fun integer(field: String) = objectives[field]?.bigIntegerValue()
                val workload = DraftWorkload(objectives["region"].textValue(), objectives["currency"].textValue(),
                    objectives["monthly_budget_amount"]?.let { ContractDecimal.parse(it.textValue()) },
                    integer("requests_per_day"), integer("avg_input_tokens"), integer("avg_output_tokens"),
                    integer("max_concurrency"), integer("max_latency_ms"),
                    DraftDataClassification.entries.single { it.encoded == objectives["data_classification"].textValue() })
                val supplied = ProjectionFixtures.content(allKinds = true, workload = workload,
                    components = ProjectionFixtures.components(build))
                val content = OptionalTier.entries.fold(supplied) { current, tier ->
                    current.withTier(tier, current.optional.getValue(tier).withActivation(when (case["optional_tiers"][tier.name].textValue()) {
                        "enabled" -> Activation.ENABLED
                        "disabled" -> Activation.DISABLED
                        else -> error("The fixed corpus must explicitly choose enabled or disabled.")
                    }))
                }
                val caseRoot = root.resolve(name)
                val store = LocalDraftStore(caseRoot.resolve("source").also(Files::createDirectories))
                val saved = (store.save(null, content, mapOf("fixture" to name)) as DraftSave.Saved).draft
                val identity = DraftSnapshotIdentity(build["id"].textValue(), build["revision"].bigIntegerValue(),
                    build["name"].textValue(), build["owner_ref"].textValue(),
                    Instant.parse(build["created_at"].textValue()), Instant.parse(build["updated_at"].textValue()))
                fun artifact(file: String): DraftProjectionArtifact {
                    val path = "contracts/v1/examples/$file.json"
                    val bytes = raw("/$path")
                    val node = mapper.readTree(bytes)
                    assertEquals(ArtifactHash(corpus["inputs"][path].textValue()), hash(bytes))
                    return DraftProjectionArtifact(node["id"].textValue(),
                        node[if (file == "ruleset") "version" else "revision"].asText(), hash(bytes), bytes)
                }
                val inputs = DraftSnapshotInputs(1, identity, artifact("catalog"), artifact("ruleset"),
                    artifact("dependency-graph"), artifact("pricebook"))
                // No internal Review/retained-object construction or unverified archive injection.
                val prepared = store.prepareSnapshotCandidate(saved.version, inputs)
                assertTrue(prepared is DraftProjectionPreparation.Review, prepared.toString())
                val review = prepared as DraftProjectionPreparation.Review
                val archive = LocalSnapshotArchive(caseRoot.resolve("archive").also(Files::createDirectories))
                val initial = (archive.load() as SnapshotArchiveLoad.Available).state
                val archived = store.archiveSnapshotCandidate(review,
                    review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet()), archive, initial.version)
                assertTrue(archived is DraftSnapshotArchival.Archived, archived.toString())
                archived as DraftSnapshotArchival.Archived
                val retained = (archive.read(archived.entry.key) as SnapshotArchiveRead.Available).snapshot
                assertEquals(build, mapper.readTree(retained.bytes()))
                for ((kind, file) in listOf(ProjectionArtifactKind.CATALOG to "catalog",
                    ProjectionArtifactKind.RULESET to "ruleset", ProjectionArtifactKind.GRAPH to "dependency-graph",
                    ProjectionArtifactKind.PRICEBOOK to "pricebook")) {
                    assertArrayEquals(raw("/contracts/v1/examples/$file.json"), retained.artifactBytes(kind))
                }

                val direct = FrozenDiagnosticEvaluator().evaluate(retained.bytes().inputStream(),
                    retained.artifactBytes(ProjectionArtifactKind.CATALOG)!!.inputStream(),
                    retained.artifactBytes(ProjectionArtifactKind.RULESET)!!.inputStream())
                assertTrue(direct is DiagnosticEvaluation.Evaluated, direct.toString())
                assertEquals(case["report"], mapper.readTree((direct as DiagnosticEvaluation.Evaluated).report.json))

                val inline = Executor { it.run() } // Deterministic test dispatch; native worker scheduling has its own tests.
                PreviewSnapshotAnalysis(inline, inline, LocalSnapshotEvaluator(Clock.fixed(observed, ZoneOffset.UTC))).use { session ->
                    assertTrue(session.open(archive, archived.version, archived.entry))
                    val state = session.snapshot()
                    assertTrue(state is PreviewSnapshotAnalysisState.Current, state.toString())
                    val result = (state as PreviewSnapshotAnalysisState.Current).result
                    val request = result.inputs
                    assertEquals(case["report"], mapper.readTree(result.report.json))
                    assertEquals(observed, result.observedAt)
                    assertEquals(RetainedEvaluationProfile.REFERENCE_TIME, result.report.inspect().evaluatedAt)
                    assertEquals(archived.entry.key, request.key)
                    assertEquals(archived.entry.bundleHash, request.bundleHash)
                    assertEquals(RetainedEvaluationProfile.ID, request.profile)
                    assertTrue(result.belongsTo(request))
                    assertEquals(5, request.bindings.size)
                    assertArrayEquals(retained.bytes(), request.bytes(SnapshotEvaluationSlot.BUILD))
                    assertEquals(hash(retained.bytes()), request.bindings.single { it.slot == SnapshotEvaluationSlot.BUILD }.hash)
                    for (pin in retained.pins) {
                        val binding = request.bindings.single { it.slot.name == pin.kind.name }
                        assertEquals(pin.id, binding.id); assertEquals(pin.version, binding.version)
                        assertEquals(pin.hash, binding.hash)
                    }
                    for (slot in listOf(SnapshotEvaluationSlot.RESOURCE_ESTIMATE, SnapshotEvaluationSlot.COST_ESTIMATE)) {
                        assertEquals(SnapshotInputStatus.NOT_SUPPLIED, request.diagnostics.single { it.slot == slot }.status)
                    }
                    val findings = result.report.inspect().findings
                    assertEquals(45, findings.size)
                    val byRule = mapper.readTree(result.report.json)["findings"].associateBy { it["rule_id"].textValue() }
                    case["focus"].properties().forEach { (rule, outcome) ->
                        assertEquals(outcome, byRule.getValue(rule)["outcome"], rule)
                    }
                    assertNotEquals("ready", result.report.inspect().deploymentReadiness)
                }
            }
        }
    }
}
