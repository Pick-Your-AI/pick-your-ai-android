package ai.pickyour.core.drafts

import ai.pickyour.core.cache.*
import ai.pickyour.core.contracts.*
import ai.pickyour.core.comparison.ComparisonSnapshot
import ai.pickyour.core.comparison.SnapshotIdentity
import ai.pickyour.core.comparison.SnapshotRead
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class DraftSnapshotProjectionTest {
    @TempDir lateinit var root: Path
    private fun store() = LocalDraftStore(root)
    private fun saved(result: DraftSave) = (result as DraftSave.Saved).draft
    private fun loaded() = (store().load() as DraftLoad.Available).draft
    private fun cache() = VerifiedArtifactCache(root.resolve("revisions"), LocalDraftStore.MAX_BYTES)
    private fun raw() = (cache().active() as CacheRead.Available).bytes
    private fun prepare(source: StoredDraft, inputs: DraftSnapshotInputs = ProjectionFixtures.inputs()) = store().prepareSnapshotCandidate(source.version, inputs)
    private fun review(source: StoredDraft, inputs: DraftSnapshotInputs = ProjectionFixtures.inputs()) = prepare(source, inputs) as DraftProjectionPreparation.Review
    private fun created(review: DraftProjectionPreparation.Review): DraftSnapshotCandidate =
        (store().createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())) as DraftProjectionCreation.Created).candidate
    private fun problems(result: DraftProjectionPreparation) = (result as DraftProjectionPreparation.Withheld).findings.map { it.problem }.toSet()
    private fun seed(bytes: ByteArray) {
        store()
        assertTrue(cache().install(ProjectionFixtures.hash(bytes), bytes.inputStream()) is CacheInstall.Activated)
    }

    @Test fun `empty and partial drafts persist unchanged and never fabricate a foundation from free text`() {
        var source = saved(store().save(null, DraftContent()))
        for (content in listOf(DraftContent(), DraftContent("entered objective", "context_policy", "model",
            workload = DraftWorkload(monthlyBudgetAmount = ContractDecimal.parse("0.000")),
            components = DraftSelections(listOf(ProjectionFixtures.selection("model_provider")))))) {
            source = saved(store().save(source.version, content))
            val before = raw()
            val result = prepare(loaded()) as DraftProjectionPreparation.Withheld
            assertTrue(result.findings.any { it.problem == DraftProjectionProblem.MISSING_FOUNDATION && it.relatedValues.first() == "model" })
            assertTrue(result.findings.any { it.problem == DraftProjectionProblem.MISSING_FOUNDATION && it.relatedValues.first() == "context_policy" })
            assertEquals(3, result.findings.count { it.problem == DraftProjectionProblem.MISSING_OBJECTIVE })
            assertEquals(content, result.source.content)
            assertEquals(source.version.hash, loaded().version.hash)
            assertArrayEquals(before, raw())
        }
        assertEquals("0.000", loaded().content.workload.monthlyBudgetAmount!!.encoded)
        assertNull(loaded().content.workload.currency)
        assertNull(loaded().content.workload.requestsPerDay)
    }

    @Test fun `schema valid two selection control is withheld for semantic foundation requirements`() {
        val build = ProjectionFixtures.node("build")
        val keep = setOf("model", "model_provider")
        val selections = build["selections"].filter { it["kind"].textValue() in keep }
        build.set<ArrayNode>("selections", ProjectionFixtures.mapper.createArrayNode().addAll(selections))
        val ids = selections.map { it["id"].textValue() }.toSet()
        build.set<ArrayNode>("links", ProjectionFixtures.mapper.createArrayNode().addAll(build["links"].filter {
            it["from_selection_id"].textValue() in ids && it["to_selection_id"].textValue() in ids
        }))
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, ProjectionFixtures.bytes(build).inputStream()))
        val source = saved(store().save(null, ProjectionFixtures.content(components = ProjectionFixtures.components(build))))
        val missing = (prepare(source) as DraftProjectionPreparation.Withheld).findings
            .filter { it.problem == DraftProjectionProblem.MISSING_FOUNDATION }.map { it.relatedValues.first() }.toSet()
        assertEquals(setOf("workload", "deployment_mode", "context_policy"), missing)
    }

    @Test fun `complete projection keeps exact typed values immutable source and every retained only group`() {
        val content = ProjectionFixtures.content(allKinds = true)
        val metadata = mapOf("preview.format" to "1", "preview.title" to "local title", "a/b~c" to "entered-only")
        val source = saved(store().save(null, content, metadata))
        val before = raw()
        val review = review(loaded())
        assertEquals(20, review.retainedOnlyPaths.size)
        assertTrue("/metadata/a~1b~0c" in review.retainedOnlyPaths)
        assertTrue("/history" in review.retainedOnlyPaths)
        for (tier in OptionalTier.entries) for (field in listOf("activation", "selections", "settings"))
            assertTrue("/content/optional/${tier.name}/$field" in review.retainedOnlyPaths)
        assertEquals(content, review.source.content)
        assertEquals(metadata, review.source.metadata)
        assertThrows(UnsupportedOperationException::class.java) { (review.retainedOnlyPaths as MutableList).clear() }
        val candidate = created(review)
        assertArrayEquals(before, candidate.sourceBytes())
        candidate.sourceBytes().fill(0)
        assertArrayEquals(before, candidate.sourceBytes())
        val bytes = candidate.bytes()
        val build = (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.BUILD, bytes.inputStream()))
        assertEquals("draft", build["lifecycle"].textValue())
        assertEquals("1000.00", build["objectives"]["monthly_budget_amount"].textValue())
        assertEquals(29, build["selections"].size())
        assertFalse(build["objectives"].has("objective"))
        assertFalse(build.has("extensions"))
        assertFalse(build.has("policy"))
        assertFalse(build.has("history"))
        assertEquals(content.components.selections.map { it.toJson() }, build["selections"].toList())
        assertEquals(content.components.links.map { it.toJson() }, build["links"].toList())
        assertEquals(BigInteger.valueOf(19), build["revision"].bigIntegerValue())
        assertEquals(0L, source.version.number)
        assertEquals(source.version.hash, candidate.source.version.hash)
        assertEquals(4, candidate.pins.size)
        for (kind in ProjectionArtifactKind.entries) {
            val artifact = candidate.artifactBytes(kind)!!
            assertEquals(candidate.pins.single { it.kind == kind }.hash, ProjectionFixtures.hash(artifact))
            artifact.fill(0)
            assertNotEquals(artifact.toList(), candidate.artifactBytes(kind)!!.toList())
        }
        bytes.fill(0)
        assertNotEquals(bytes.toList(), candidate.bytes().toList())
        assertArrayEquals(before, raw())
        val later = saved(store().save(source.version, content.withTier(OptionalTier.T3, TierDraft(Activation.DISABLED))))
        assertEquals(content, candidate.source.content)
        assertEquals(source.version.hash, candidate.source.version.hash)
        assertNotEquals(candidate.source.version.hash, later.version.hash)
        assertArrayEquals(before, (cache().pinned(source.version.hash) as CacheRead.Available).bytes)
    }

    @Test fun `complete acknowledgment belongs to exact review and later optional or metadata edits revoke it`() {
        var source = saved(store().save(null, ProjectionFixtures.content()))
        val first = review(source)
        assertNull(first.acknowledgeRetainedOnly(first.retainedOnlyPaths.dropLast(1).toSet()))
        assertNull(first.acknowledgeRetainedOnly(first.retainedOnlyPaths.toSet() + "/invented"))
        assertEquals(DraftProjectionCreation.ReviewAcknowledgmentMismatch, store().createSnapshotCandidate(first, null))
        val acknowledgment = first.acknowledgeRetainedOnly(first.retainedOnlyPaths.toSet())!!
        val another = review(source, ProjectionFixtures.inputs(identity = ProjectionFixtures.identity("build.other")))
        assertEquals(DraftProjectionCreation.ReviewAcknowledgmentMismatch, store().createSnapshotCandidate(another, acknowledgment))
        val otherCatalog = ProjectionFixtures.node("catalog").put("revision", 2)
        val changedPins = review(source, ProjectionFixtures.inputs(catalog = ProjectionFixtures.artifact("catalog", otherCatalog)))
        assertEquals(first.identity.id, changedPins.identity.id)
        assertEquals(first.retainedOnlyPaths, changedPins.retainedOnlyPaths)
        assertEquals(DraftProjectionCreation.ReviewAcknowledgmentMismatch, store().createSnapshotCandidate(changedPins, acknowledgment))
        source = saved(store().save(source.version, source.content.withTier(OptionalTier.T3, TierDraft(Activation.UNKNOWN))))
        assertEquals(DraftProjectionCreation.Conflict, store().createSnapshotCandidate(first, acknowledgment))
        source = saved(store().save(source.version, first.source.content))
        assertEquals(DraftProjectionCreation.Conflict, store().createSnapshotCandidate(first, acknowledgment))
        val fresh = review(source)
        assertTrue(store().createSnapshotCandidate(fresh, fresh.acknowledgeRetainedOnly(fresh.retainedOnlyPaths.toSet())) is DraftProjectionCreation.Created)
        saved(store().save(source.version, source.content, mapOf("extra" to "retained")))
        assertEquals(DraftProjectionPreparation.Conflict, prepare(source))
        assertEquals(DraftProjectionCreation.Conflict, store().createSnapshotCandidate(fresh, fresh.acknowledgeRetainedOnly(fresh.retainedOnlyPaths.toSet())))
    }

    @Test fun `foreign identical payload and missing or corrupt source cannot authorize creation`() {
        val source = saved(store().save(null, ProjectionFixtures.content()))
        val review = review(source)
        val foreign = LocalDraftStore(Files.createDirectory(root.resolve("foreign")))
        val other = saved(foreign.save(null, source.content))
        assertEquals(source.version.hash, other.version.hash)
        assertEquals(DraftProjectionPreparation.Conflict, foreign.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()))
        assertEquals(DraftProjectionCreation.Conflict, foreign.createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())))
        val node = ProjectionFixtures.mapper.readTree(raw()) as ObjectNode
        (node["history"][0]["content"]["components"]["links"][0] as ObjectNode).put("to_selection_id", "not_retained")
        val bad = ProjectionFixtures.bytes(node)
        seed(bad)
        assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.CORRUPT), prepare(source))
        assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.CORRUPT), store().createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())))
        assertArrayEquals(bad, raw())
        Files.delete(root.resolve("revisions/active"))
        assertEquals(DraftProjectionPreparation.Conflict, prepare(source))
        assertEquals(DraftProjectionCreation.Conflict, store().createSnapshotCandidate(review, review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())))
    }

    @Test fun `disabled references still validate and primary roles cannot be replaced by arbitrary labels`() {
        val original = ProjectionFixtures.content()
        for (mutation in listOf<(ObjectNode) -> Unit>(
            { it.put("catalog_item_version", "missing-version") },
            { it.put("catalog_item_id", "cat.model_provider.fixture") },
            { it.put("enabled", false).put("catalog_item_version", "missing-version") },
        )) {
            val selections = original.components.selections.map {
                if (it.kind == "model") LocalSelection.fromJson(it.toJson().also(mutation)) else it
            }
            val content = original.withComponents(DraftSelections(selections, original.components.links))
            val previous = (store().load() as? DraftLoad.Available)?.draft
            val source = saved(store().save(previous?.version, content))
            assertTrue(DraftProjectionProblem.SELECTION_CATALOG_MISMATCH in problems(prepare(source)))
        }
        val selections = original.components.selections.map {
            if (it.kind == "context_policy") LocalSelection.fromJson(it.toJson().put("role", "fallback")) else it
        }
        val source = saved(store().save(loaded().version, original.withComponents(DraftSelections(selections, original.components.links))))
        assertTrue((prepare(source) as DraftProjectionPreparation.Withheld).findings.any {
            it.problem == DraftProjectionProblem.MISSING_FOUNDATION && it.relatedValues == listOf("context_policy", "primary")
        })
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(selections + selections.first()) }
        assertThrows(IllegalArgumentException::class.java) { DraftSelections(original.components.selections +
            LocalSelection.fromJson(original.components.selections.first { it.kind == "context_policy" }.toJson().put("id", "sel_context_two").put("role", "other"))) }
    }

    @Test fun `all frozen catalog reference fields reject missing targets and ambiguity never picks a version`() {
        val source = saved(store().save(null, ProjectionFixtures.content()))
        val mappings = ProjectionFixtures.referenceFields()
        assertEquals(18, mappings.size)
        for ((kind, field) in mappings) {
            val catalog = ProjectionFixtures.node("catalog")
            val entry = catalog["items"].single { it["kind"].textValue() == kind }
            val spec = entry["spec"] as ObjectNode
            if (spec[field].isArray) spec.putArray(field).add("cat.missing.reference") else spec.put(field, "cat.missing.reference")
            val input = ProjectionFixtures.artifact("catalog", catalog)
            val result = prepare(source, ProjectionFixtures.inputs(catalog = input)) as DraftProjectionPreparation.Withheld
            assertTrue(result.findings.any { it.problem == DraftProjectionProblem.MISSING_CATALOG_REFERENCE && it.path.endsWith("/spec/$field") }, "$kind/$field")
        }
        val ambiguous = ProjectionFixtures.node("catalog")
        val provider = ambiguous["items"].single { it["kind"].textValue() == "model_provider" }.deepCopy<ObjectNode>()
        provider.put("version", "other-exact-version")
        (ambiguous["items"] as ArrayNode).add(provider)
        val result = prepare(source, ProjectionFixtures.inputs(catalog = ProjectionFixtures.artifact("catalog", ambiguous))) as DraftProjectionPreparation.Withheld
        val finding = result.findings.first { it.problem == DraftProjectionProblem.AMBIGUOUS_CATALOG_REFERENCE }
        assertEquals(listOf(provider["id"].textValue(), "2026.10-demo", "other-exact-version"), finding.relatedValues)
        assertFalse(result.findings.any { it.problem == DraftProjectionProblem.SELECTION_CATALOG_MISMATCH })
        val wrongKind = ProjectionFixtures.node("catalog")
        (wrongKind["items"].single { it["kind"].textValue() == "model" }["spec"] as ObjectNode).put("provider_ref", "cat.workload.fixture")
        assertTrue(DraftProjectionProblem.CATALOG_REFERENCE_KIND_MISMATCH in problems(prepare(source,
            ProjectionFixtures.inputs(catalog = ProjectionFixtures.artifact("catalog", wrongKind)))))
    }

    @Test fun `provider wiring and workload contradictions are withheld without guessed edges or values`() {
        val content = ProjectionFixtures.content()
        var source = saved(store().save(null, content.withComponents(DraftSelections(content.components.selections,
            content.components.links.filterNot { it.relation == "provided_by" }))))
        assertTrue(DraftProjectionProblem.MISSING_MODEL_PROVIDER_LINK in problems(prepare(source)))
        val workload = content.workload.copy(requestsPerDay = BigInteger.ONE, maxConcurrency = BigInteger.valueOf(999))
        source = saved(store().save(source.version, ProjectionFixtures.content(workload = workload)))
        val result = prepare(source) as DraftProjectionPreparation.Withheld
        assertEquals(2, result.findings.count { it.problem == DraftProjectionProblem.CONFLICTING_WORKLOAD_INPUT })
        assertEquals(workload, loaded().content.workload)
        assertEquals(BigInteger.valueOf(200), loaded().content.components.selections.single { it.kind == "workload" }.settings["requests_per_day"].bigIntegerValue())
    }

    @Test fun `optional intent distinguishes unknown contradiction and incomplete without requiring empty branches`() {
        var source = saved(store().save(null, ProjectionFixtures.content()))
        assertTrue(prepare(source) is DraftProjectionPreparation.Review)
        for ((activation, problem) in listOf(Activation.UNKNOWN to DraftProjectionProblem.UNKNOWN_OPTIONAL_INTENT,
            Activation.ENABLED to DraftProjectionProblem.INCOMPLETE_OPTIONAL_SELECTIONS)) {
            source = saved(store().save(source.version, ProjectionFixtures.content().withTier(OptionalTier.T3, TierDraft(activation))))
            assertTrue(problem in problems(prepare(source)))
        }
        val full = ProjectionFixtures.content(allKinds = true)
        source = saved(store().save(source.version, full.withTier(OptionalTier.T3, TierDraft(Activation.DISABLED))))
        assertTrue(DraftProjectionProblem.CONTRADICTORY_OPTIONAL_INTENT in problems(prepare(source)))
        val disabled = full.components.selections.map { selected ->
            if (ProjectionFixtures.tier(selected.kind) == "T3") LocalSelection.fromJson(selected.toJson().put("enabled", false)) else selected
        }
        source = saved(store().save(source.version, full.withTier(OptionalTier.T3, TierDraft(Activation.DISABLED, setOf("keep"), mapOf("opaque" to "retain")))
            .withComponents(DraftSelections(disabled, full.components.links))))
        val candidate = created(review(source))
        assertEquals(29, ProjectionFixtures.mapper.readTree(candidate.bytes())["selections"].size())
        assertTrue(candidate.source.content.optional.getValue(OptionalTier.T3).selections.contains("keep"))
    }

    @Test fun `every artifact checks exact hash identity version document and duplicate entries`() {
        val source = saved(store().save(null, ProjectionFixtures.content()))
        for ((kind, fixture) in listOf(ProjectionArtifactKind.CATALOG to "catalog", ProjectionArtifactKind.RULESET to "ruleset",
            ProjectionArtifactKind.GRAPH to "dependency-graph", ProjectionArtifactKind.PRICEBOOK to "pricebook")) {
            val original = ProjectionFixtures.artifact(fixture)
            val mutable = original.bytes()
            val captured = DraftProjectionArtifact(original.expectedId, original.expectedVersion, original.expectedHash, mutable)
            mutable.fill(0)
            assertTrue(prepare(source, ProjectionFixtures.replace(kind, captured)) is DraftProjectionPreparation.Review)
            for ((input, problem) in listOf(
                DraftProjectionArtifact(original.expectedId, original.expectedVersion, ArtifactHash("0".repeat(64)), original.bytes()) to DraftProjectionProblem.ARTIFACT_HASH_MISMATCH,
                DraftProjectionArtifact("different.pin", original.expectedVersion, original.expectedHash, original.bytes()) to DraftProjectionProblem.ARTIFACT_ID_MISMATCH,
                DraftProjectionArtifact(original.expectedId, "999.99", original.expectedHash, original.bytes()) to DraftProjectionProblem.ARTIFACT_VERSION_MISMATCH,
                ProjectionFixtures.artifact("build") to DraftProjectionProblem.ARTIFACT_SCHEMA_MISMATCH,
            )) assertTrue(problem in problems(prepare(source, ProjectionFixtures.replace(kind, input))), "$kind/$problem")
            val duplicate = ProjectionFixtures.node(fixture)
            val field = when (kind) { ProjectionArtifactKind.CATALOG -> "items"; ProjectionArtifactKind.RULESET -> "rules"; ProjectionArtifactKind.GRAPH -> "edges"; ProjectionArtifactKind.PRICEBOOK -> "rates" }
            (duplicate[field] as ArrayNode).add(duplicate[field][0].deepCopy<JsonNode>())
            assertTrue(DraftProjectionProblem.DUPLICATE_ARTIFACT_ENTRY in problems(prepare(source,
                ProjectionFixtures.replace(kind, ProjectionFixtures.artifact(fixture, duplicate)))))
        }
        val rates = ProjectionFixtures.node("pricebook")
        (rates["rates"][0] as ObjectNode).put("catalog_item_version", "unavailable-exact-version")
        assertTrue(DraftProjectionProblem.PRICEBOOK_CATALOG_MISMATCH in problems(prepare(source,
            ProjectionFixtures.inputs(pricebook = ProjectionFixtures.artifact("pricebook", rates)))))
        val noPricebook = created(review(source, ProjectionFixtures.inputs(pricebook = null)))
        assertNull(noPricebook.artifactBytes(ProjectionArtifactKind.PRICEBOOK))
        assertFalse(ProjectionFixtures.mapper.readTree(noPricebook.bytes())["locks"].has("pricebook_id"))
    }

    @Test fun `identity profile input budgets and malformed bytes reject without state changes or fallback`() {
        val source = saved(store().save(null, ProjectionFixtures.content()))
        val before = raw()
        assertTrue(DraftProjectionProblem.UNSUPPORTED_PROFILE in problems(prepare(source, DraftSnapshotInputs(2))))
        assertEquals(setOf(DraftProjectionProblem.MISSING_IDENTITY, DraftProjectionProblem.MISSING_ARTIFACT), problems(prepare(source, DraftSnapshotInputs(1))))
        val valid = ProjectionFixtures.identity()
        val invalid = listOf(DraftSnapshotIdentity("invalid id", valid.revision, valid.name, valid.ownerRef, valid.createdAt, valid.updatedAt),
            DraftSnapshotIdentity(valid.id, BigInteger.ZERO, valid.name, valid.ownerRef, valid.createdAt, valid.updatedAt),
            DraftSnapshotIdentity(valid.id, valid.revision, "", valid.ownerRef, valid.createdAt, valid.updatedAt),
            DraftSnapshotIdentity(valid.id, valid.revision, valid.name, "", valid.createdAt, valid.updatedAt))
        for (identity in invalid) assertTrue(DraftProjectionProblem.BUILD_SCHEMA_MISMATCH in problems(prepare(source, ProjectionFixtures.inputs(identity = identity))))
        val reverse = DraftSnapshotIdentity(valid.id, valid.revision, valid.name, valid.ownerRef, valid.updatedAt, valid.createdAt.minusSeconds(1))
        assertTrue(DraftProjectionProblem.INVALID_IDENTITY_TIME in problems(prepare(source, ProjectionFixtures.inputs(identity = reverse))))
        val huge = DraftSnapshotIdentity(valid.id, BigInteger.TEN.pow(129), valid.name, valid.ownerRef, valid.createdAt, valid.updatedAt)
        assertTrue(DraftProjectionProblem.INPUT_LIMIT in problems(prepare(source, ProjectionFixtures.inputs(identity = huge))))
        assertThrows(IllegalArgumentException::class.java) { DraftProjectionArtifact("id", "1", validHash(), ByteArray(LocalDraftStore.MAX_BYTES + 1)) }
        for (bad in listOf("{\"schema_version\":\"1.0.0\",\"schema_version\":\"1.0.0\"}".toByteArray(), byteArrayOf(0xff.toByte()), "{}{}".toByteArray())) {
            val input = DraftProjectionArtifact("id", "1", ProjectionFixtures.hash(bad), bad)
            assertTrue(DraftProjectionProblem.ARTIFACT_SCHEMA_MISMATCH in problems(prepare(source, ProjectionFixtures.inputs(catalog = input))))
        }
        assertArrayEquals(before, raw())
    }

    @Test fun `integral artifact counters preserve raw bytes and oversized semantic reviews stay withheld`() {
        val source = saved(store().save(null, ProjectionFixtures.content()))
        for ((kind, fixture) in listOf(ProjectionArtifactKind.CATALOG to "catalog", ProjectionArtifactKind.GRAPH to "dependency-graph",
            ProjectionArtifactKind.PRICEBOOK to "pricebook")) {
            val original = ProjectionFixtures.artifact(fixture)
            val raw = original.bytes().toString(Charsets.UTF_8).replaceFirst("\"revision\":1", "\"revision\":1.0").toByteArray()
            assertFalse(raw.contentEquals(original.bytes()))
            assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(kind.document, raw.inputStream()))
            val input = DraftProjectionArtifact(original.expectedId, "1", ProjectionFixtures.hash(raw), raw)
            val candidate = created(review(source, ProjectionFixtures.replace(kind, input)))
            assertArrayEquals(raw, candidate.artifactBytes(kind))
            assertEquals("1", candidate.pins.single { it.kind == kind }.version)
            val huge = original.bytes().toString(Charsets.UTF_8).replaceFirst("\"revision\":1", "\"revision\":1e129").toByteArray()
            val unsupported = DraftProjectionArtifact(original.expectedId, "1" + "0".repeat(129), ProjectionFixtures.hash(huge), huge)
            val rejected = problems(prepare(source, ProjectionFixtures.replace(kind, unsupported)))
            assertTrue(DraftProjectionProblem.ARTIFACT_COUNTER_UNSUPPORTED in rejected, "$kind/$rejected")
        }
        val catalog = ProjectionFixtures.node("catalog")
        val model = catalog["items"].single { it["kind"].textValue() == "model" }
        repeat(300) { index ->
            val extra = model.deepCopy<ObjectNode>().put("id", "cat.extra.model.$index")
            (extra["spec"] as ObjectNode).put("provider_ref", "cat.missing.reference")
            (catalog["items"] as ArrayNode).add(extra)
        }
        val result = prepare(source, ProjectionFixtures.inputs(catalog = ProjectionFixtures.artifact("catalog", catalog))) as DraftProjectionPreparation.Withheld
        assertTrue(result.findings.size <= 256)
        assertEquals(1, result.findings.count { it.problem == DraftProjectionProblem.REVIEW_LIMIT_EXCEEDED })
        assertTrue(result.findings.any { it.problem == DraftProjectionProblem.MISSING_CATALOG_REFERENCE })
        val ambiguous = ProjectionFixtures.node("catalog")
        val provider = ambiguous["items"].single { it["kind"].textValue() == "model_provider" }
        repeat(150) { index -> (ambiguous["items"] as ArrayNode).add(provider.deepCopy<ObjectNode>().put("version", "extra.$index")) }
        val limited = prepare(source, ProjectionFixtures.inputs(catalog = ProjectionFixtures.artifact("catalog", ambiguous))) as DraftProjectionPreparation.Withheld
        assertTrue(limited.findings.all { it.relatedValues.size <= 128 })
        assertTrue(limited.findings.any { it.problem == DraftProjectionProblem.REVIEW_LIMIT_EXCEEDED })
        assertEquals(source.version.hash, loaded().version.hash)
    }

    @Test fun `nullable workload stays absent and decimal spelling plus arbitrary integers round trip exactly`() {
        val original = ProjectionFixtures.content()
        val minimal = DraftWorkload(region = "explicit-region", currency = "USD", dataClassification = DraftDataClassification.INTERNAL)
        var source = saved(store().save(null, ProjectionFixtures.content(workload = minimal)))
        var build = ProjectionFixtures.mapper.readTree(created(review(source)).bytes())
        assertEquals(setOf("region", "currency", "data_classification"), build["objectives"].fieldNames().asSequence().toSet())
        val huge = BigInteger("9".repeat(128))
        val entered = minimal.copy(monthlyBudgetAmount = ContractDecimal.parse("0.000000000001"), requestsPerDay = huge, avgInputTokens = BigInteger.ZERO)
        val selections = original.components.selections.map { selected ->
            if (selected.kind != "workload") selected else LocalSelection.fromJson(selected.toJson().also { node ->
                (node["settings"] as ObjectNode).put("requests_per_day", huge).put("avg_input_tokens", BigInteger.ZERO)
            })
        }
        source = saved(store().save(source.version, ProjectionFixtures.content(workload = entered,
            components = DraftSelections(selections, original.components.links))))
        val candidate = created(review(loaded()))
        build = ProjectionFixtures.mapper.readTree(candidate.bytes())
        assertEquals("0.000000000001", build["objectives"]["monthly_budget_amount"].textValue())
        assertEquals(huge, build["objectives"]["requests_per_day"].bigIntegerValue())
        assertEquals(BigInteger.ZERO, build["objectives"]["avg_input_tokens"].bigIntegerValue())
        assertFalse(build["objectives"].has("avg_output_tokens"))
        assertEquals(entered, loaded().content.workload)
        assertTrue(ComparisonSnapshot.read(candidate.bytes().inputStream(), SnapshotIdentity(candidate.identity.id, candidate.identity.revision)) is SnapshotRead.Available)
    }

    @Test fun `all predecessor formats retain exact bytes and unsupported values during withheld conversion and explicit migration`() {
        val v1 = """{"local_format_version":1,"revision":7,"content":{"objective":"","context":null,"primaryModel":"entered",
            "optional":{"T3":{"activation":"UNKNOWN","selections":["raw"],"settings":{"amount":"0.00"}},
            "T4":{"activation":"DISABLED","selections":[],"settings":{}},"T5":{"activation":"ENABLED","selections":[],"settings":{}}}},
            "metadata":{"opaque":"retained"}}""".toByteArray()
        val predecessors = listOf(v1) + listOf(2, 3).map { version -> javaClass.getResourceAsStream("/drafts/local-format-$version.json")!!.use { it.readBytes() } }
        for ((index, before) in predecessors.withIndex()) {
            seed(before)
            val old = loaded()
            val result = prepare(old) as DraftProjectionPreparation.Withheld
            assertEquals(index + 1, result.source.localFormatVersion)
            assertEquals(old.content, result.source.content)
            assertArrayEquals(before, raw())
            val priorHash = old.version.hash
            val edited = saved(store().save(old.version, old.content.withComponents(ProjectionFixtures.content().components)))
            assertEquals(4, edited.localFormatVersion)
            assertEquals(old.history.map { it.content }, edited.history.dropLast(1).map { it.content })
            assertEquals(old.history.map { it.metadata }, edited.history.dropLast(1).map { it.metadata })
            assertEquals(old.content.optional, edited.content.optional)
            assertEquals(old.content.objective, edited.content.objective)
            assertEquals(old.content.primaryModel, edited.content.primaryModel)
            assertTrue(prepare(edited) is DraftProjectionPreparation.Withheld)
            assertArrayEquals(before, (cache().pinned(priorHash) as CacheRead.Available).bytes)
            assertEquals(DraftProjectionPreparation.Conflict, prepare(old))
        }
        val old = loaded()
        val unknown = ProjectionFixtures.mapper.readTree(raw()) as ObjectNode
        unknown.put("local_format_version", 999)
        val unknownBytes = ProjectionFixtures.bytes(unknown)
        seed(unknownBytes)
        assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.UNSUPPORTED_VERSION), prepare(old))
        assertArrayEquals(unknownBytes, raw())
    }

    @Test fun `read and materialization cannot succeed through cancellation contention or transaction cleanup failure`() {
        val source = saved(store().save(null, ProjectionFixtures.content()))
        val review = review(source)
        val acknowledgment = review.acknowledgeRetainedOnly(review.retainedOnlyPaths.toSet())
        val noWrites = LocalDraftStore(root, { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) { error("Projection must not write") } }, {})
        assertTrue(noWrites.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()) is DraftProjectionPreparation.Review)
        assertTrue(noWrites.createSnapshotCandidate(review, acknowledgment) is DraftProjectionCreation.Created)
        val failedClose = LocalDraftStore(root, { path -> VerifiedArtifactCache(path, LocalDraftStore.MAX_BYTES) }, { throw IOException("private injected value") })
        assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.IO_FAILURE), failedClose.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()))
        assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.IO_FAILURE), failedClose.createSnapshotCandidate(review, acknowledgment))
        Thread.currentThread().interrupt()
        try {
            assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.CANCELLED), noWrites.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()))
            assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.CANCELLED), noWrites.createSnapshotCandidate(review, acknowledgment))
        } finally { Thread.interrupted() }
        java.nio.channels.FileChannel.open(root.resolve("transaction.lock"), java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertEquals(DraftProjectionPreparation.Failed(DraftStorageFailure.BUSY), noWrites.prepareSnapshotCandidate(source.version, ProjectionFixtures.inputs()))
                assertEquals(DraftProjectionCreation.Failed(DraftStorageFailure.BUSY), noWrites.createSnapshotCandidate(review, acknowledgment))
            }
        }
        assertEquals(source.version.hash, loaded().version.hash)
    }

    private fun validHash() = ArtifactHash("0".repeat(64))
}

/** Independent frozen fixtures are test inputs only; no runtime constructor manufactures these facts. */
internal object ProjectionFixtures {
    val mapper = ObjectMapper()
    fun node(name: String): ObjectNode = javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")!!.use { mapper.readTree(it) as ObjectNode }
    fun bytes(node: JsonNode): ByteArray = mapper.writeValueAsBytes(node)
    fun hash(bytes: ByteArray) = ArtifactHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    fun artifact(name: String, node: ObjectNode = node(name)): DraftProjectionArtifact {
        val bytes = bytes(node)
        return DraftProjectionArtifact(node["id"].textValue(), node[if (name == "ruleset") "version" else "revision"].asText(), hash(bytes), bytes)
    }
    fun identity(id: String = "build.local.projection") = DraftSnapshotIdentity(id, BigInteger.valueOf(19), "Explicit projection name", "explicit.local.owner",
        Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-18T10:00:00Z"))
    fun inputs(identity: DraftSnapshotIdentity = identity(), catalog: DraftProjectionArtifact = artifact("catalog"),
        ruleset: DraftProjectionArtifact = artifact("ruleset"), graph: DraftProjectionArtifact = artifact("dependency-graph"),
        pricebook: DraftProjectionArtifact? = artifact("pricebook")) = DraftSnapshotInputs(1, identity, catalog, ruleset, graph, pricebook)
    fun replace(kind: ProjectionArtifactKind, artifact: DraftProjectionArtifact) = when (kind) {
        ProjectionArtifactKind.CATALOG -> inputs(catalog = artifact)
        ProjectionArtifactKind.RULESET -> inputs(ruleset = artifact)
        ProjectionArtifactKind.GRAPH -> inputs(graph = artifact)
        ProjectionArtifactKind.PRICEBOOK -> inputs(pricebook = artifact)
    }
    fun components(build: ObjectNode): DraftSelections = DraftSelections(build["selections"].map { LocalSelection.fromJson(it as ObjectNode) },
        build["links"].map { LocalSelectionLink.fromJson(it as ObjectNode) })
    fun selection(kind: String) = LocalSelection.fromJson(node("build")["selections"].single { it["kind"].textValue() == kind } as ObjectNode)
    private val registry by lazy { javaClass.getResourceAsStream("/contracts/v1/schemas/component-registry.json")!!.use { mapper.readTree(it)["kinds"] } }
    fun tier(kind: String): String = registry[kind]["tier"].textValue()
    fun content(allKinds: Boolean = false, workload: DraftWorkload? = null, components: DraftSelections? = null): DraftContent {
        val build = node("build")
        val all = ProjectionFixtures.components(build)
        val kept = if (allKinds) all.selections else all.selections.filter { it.kind in setOf("workload", "deployment_mode", "model", "model_provider", "context_policy") }
        val ids = kept.map { it.id }.toSet()
        val selected = components ?: DraftSelections(kept, all.links.filter { it.fromSelectionId in ids && it.toSelectionId in ids })
        val objectives = build["objectives"]
        fun integer(field: String) = objectives[field]?.bigIntegerValue()
        val typedWorkload = workload ?: DraftWorkload(objectives["region"].textValue(), objectives["currency"].textValue(),
            ContractDecimal.parse(objectives["monthly_budget_amount"].textValue()), integer("requests_per_day"), integer("avg_input_tokens"),
            integer("avg_output_tokens"), integer("max_concurrency"), integer("max_latency_ms"), DraftDataClassification.INTERNAL)
        val optional = OptionalTier.entries.associateWith { tier ->
            TierDraft(if (selected.selections.any { it.enabled && ProjectionFixtures.tier(it.kind) == tier.name }) Activation.ENABLED else Activation.DISABLED,
                setOf("opaque retained choice"), mapOf("entered" to "0.00"))
        }
        return DraftContent("entered free text", "", "cat.model.fixture", optional, typedWorkload, selected)
    }
    fun referenceFields(): List<Pair<String, String>> = listOf(
        "model" to "provider_ref", "embedding_model" to "provider_ref", "compute_profile" to "hosting_ref",
        "compute_profile" to "accelerator_ref", "hosting_platform" to "accelerator_refs", "storage" to "hosting_ref",
        "knowledge_base" to "data_source_refs", "vector_database" to "hosting_ref", "mcp_server" to "tool_refs",
        "integration" to "associated_tool_refs", "training_compute" to "compute_profile_ref", "training_compute" to "storage_ref",
        "training_dataset" to "data_source_refs", "fine_tuned_model" to "base_model_ref", "fine_tuned_model" to "training_framework_ref",
        "fine_tuned_model" to "training_dataset_ref", "evaluation_suite" to "dataset_refs", "deployment_target" to "hosting_ref",
    )
}
