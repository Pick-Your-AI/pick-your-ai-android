package ai.pickyour.core.evidence.placement

import ai.pickyour.core.evidence.answers.Cd1State
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger

class PlacementBindingDeploymentTest {
    @Test
    fun completeServingTupleUsesTheOneFrozenGraph() {
        val d = PlacementTestDraft().apply { serving() }
        val f = d.freeze()
        val graph = f.graph()
        val requirements = f.requirements().single()
        val result = graph.serving(f.basis, f.qs(), requirements, emptyList())
        assertEquals(PlacementOutcome.PASS, result.outcome)
        assertEquals(listOf("sel_engine"), result.engine!!.resolved.map { it.id })
        assertEquals(listOf("sel_host"), result.host!!.resolved.map { it.id })
        assertEquals(listOf("sel_gpu"), result.devices!!.resolved.map { it.id })
        assertEquals(3, f.basis.links.size)
        assertArrayEquals(d.mapper.writeValueAsBytes(d.build), f.basis.originalBuildBytes())
    }

    @Test
    fun catalogSelectionIsPartOfTheBasisEvenWithIdenticalNamedInputs() {
        val d = PlacementTestDraft().apply { serving() }
        val other = d.catalog.deepCopy().put("id", "catalog.other")
        d.extraInputs += PlacementInput("catalog-b", PlacementInputKind.CATALOG, d.mapper.writeValueAsBytes(other))
        val first = d.freeze()
        d.selectedCatalog = "catalog-b"
        val second = d.freeze()
        d.selectedCatalog = null
        val absent = d.freeze()
        assertEquals(first.basis.inputNames, second.basis.inputNames)
        assertEquals(first.inputs.map { it.sha256 }, second.inputs.map { it.sha256 })
        assertEquals(3, setOf(first.basis.sha256, second.basis.sha256, absent.basis.sha256).size)
        assertTrue(first.basis.catalogMatchesBuild)
        assertFalse(second.basis.catalogMatchesBuild)
        assertFalse(absent.basis.catalogMatchesBuild)
        assertNotNull(first.basis.catalogItem(first.basis.selection("sel_model")!!))
        assertNull(second.basis.catalogItem(second.basis.selection("sel_model")!!))
    }

    @Test
    fun completeOriginalBuildAndPolicyChangesInvalidateOldQualifications() {
        val d = PlacementTestDraft().apply { serving() }
        val old = d.freeze()
        val answer = old.a("/deployment/sel_model/operator", PlacementField.OPERATOR)
        val domain = PlacementDomain.component("sel_model")
        old.qualify(answer, PlacementFact.OPERATOR, domain)
        val q = old.qs()
        assertNotNull(q.use(old.basis, answer, PlacementFact.OPERATOR, domain))
        d.build.put("name", "Same ID and revision, different retained Build content")
        val changed = d.freeze()
        assertEquals(old.basis.buildId, changed.basis.buildId)
        assertEquals(old.basis.buildRevision, changed.basis.buildRevision)
        assertNotEquals(old.basis.sha256, changed.basis.sha256)
        assertEquals(PlacementEligibility.UNKNOWN, q.eligibility(changed.basis, answer, PlacementFact.OPERATOR, domain))
        assertNull(q.use(changed.basis, changed.a("/deployment/sel_model/operator", PlacementField.OPERATOR),
            PlacementFact.OPERATOR, domain))
        val supplied = old.basis.originalBuildBytes()
        supplied.fill(0)
        assertArrayEquals(d.mapper.writeValueAsBytes(d.build.deepCopy().put("name", "SYNTHETIC placement receiving regression")),
            old.basis.originalBuildBytes())
    }

    @Test
    fun everyDeclaredOriginalBindingIsCheckedBeforeAnyPredicateUsesIt() {
        val d = PlacementTestDraft().apply { serving() }
        val f = d.freeze()
        val a = f.a("/deployment/sel_model/operator", PlacementField.OPERATOR)
        val key = a.qualificationKey(PlacementFact.OPERATOR, PlacementDomain.component("sel_model"))
        val evidence = f.basis.input("qualification")!!
        fun rejected(problem: PlacementProblem, action: () -> Unit) {
            assertEquals(problem, assertThrows(PlacementRejected::class.java, action).problem)
        }
        rejected(PlacementProblem.INVALID_FIELD) {
            PlacementQualification(key, PlacementEligibility.INELIGIBLE, "absent-original", evidence.sha256, null, false)
        }
        rejected(PlacementProblem.DECLARED_HASH_MISMATCH) {
            PlacementQualification(key, PlacementEligibility.UNKNOWN, evidence.name, "0".repeat(64), null, false)
        }
        rejected(PlacementProblem.INVALID_DOMAIN) {
            PlacementQualification(key, PlacementEligibility.QUALIFIED, "engines", f.basis.input("engines")!!.sha256, true, false)
        }
        rejected(PlacementProblem.DECLARED_HASH_MISMATCH) {
            PlacementInput("test", PlacementInputKind.HARDWARE, byteArrayOf(1), "0".repeat(64))
        }
        rejected(PlacementProblem.DECLARED_HASH_MISMATCH) {
            PlacementBasis.read(f.basis.originalBuildBytes(), "inference", "normalization", "source-policy",
                f.inputs, "catalog", "0".repeat(64))
        }
    }

    @Test
    fun originalAnswerStatesAndDecodedProvenanceRemainDistinct() {
        val d = PlacementTestDraft().apply {
            serving()
            put("/parent~1name", linkedMapOf("false" to false, "zero" to "0", "empty" to emptyList<String>(), "nil" to null),
                assumption = false, source = "preset:original-parent")
            state("/unresolved", Cd1State.UNKNOWN)
            state("/private/child", Cd1State.WITHHELD)
            state("/irrelevant", Cd1State.NOT_APPLICABLE)
        }
        val f = d.freeze()
        val falseValue = f.a("/parent~1name/false", PlacementField.BOOLEAN)
        assertTrue(falseValue.provided)
        assertEquals(false, falseValue.bool())
        assertEquals(false, falseValue.assumption)
        assertEquals("preset:original-parent", falseValue.presetSource)
        assertEquals("/parent~1name", falseValue.sourcePath)
        assertEquals(PlacementCause.ANCESTOR, falseValue.cause)
        assertEquals(BigInteger.ZERO, f.a("/parent~1name/zero", PlacementField.BYTE_COUNT).bytes())
        assertEquals(emptySet<String>(), f.a("/parent~1name/empty", PlacementField.TEXT_SET).strings())
        assertTrue(f.a("/parent~1name/nil", PlacementField.NULLABLE_TEXT).value!!.isNull)
        assertEquals(Cd1State.UNKNOWN, f.a("/unresolved", PlacementField.BOOLEAN).state)
        assertEquals(Cd1State.MISSING, f.a("/unresolved-prefix", PlacementField.BOOLEAN).state)
        val withheld = f.a("/private", PlacementField.TEXT_SET)
        assertEquals(Cd1State.WITHHELD, withheld.state)
        assertEquals("/private/child", withheld.sourcePath)
        assertEquals(PlacementCause.WITHHELD_DESCENDANT, withheld.cause)
        assertEquals(Cd1State.NOT_APPLICABLE, f.a("/irrelevant", PlacementField.BOOLEAN).state)
        val copy = f.answers.originalBytes()
        copy.fill(0)
        assertNotEquals(copy.toList(), f.answers.originalBytes().toList())
        assertEquals(f.basis.input("answers")!!.sha256, f.answers.originalSha256)
    }

    @TestFactory
    fun canonicalByteCountsRejectAliases() = listOf<Any>("01", "-1", "+1", "1.0", "1e3", "1\n", " 1", "", 1, 1.0, true).map { value ->
        DynamicTest.dynamicTest("ByteCount rejects " + value.toString().replace("\n", "\\n") + " (" + value.javaClass.simpleName + ")") {
            val d = PlacementTestDraft().apply { serving(); put("/amount", value) }
            val f = d.freeze()
            assertEquals(PlacementProblem.INVALID_FIELD, assertThrows(PlacementRejected::class.java) {
                f.a("/amount", PlacementField.BYTE_COUNT)
            }.problem)
        }
    }

    @Test
    fun exactBytesAndSequenceIntegersDoNotRoundThroughDoubleOrLong() {
        val huge = BigInteger.ONE.shiftLeft(80).add(BigInteger.ONE)
        val d = PlacementTestDraft().apply {
            serving()
            put("/amount", huge.toString())
            put("/count", huge)
            put("/float", 1.0)
        }
        val f = d.freeze()
        assertEquals(huge, f.a("/amount", PlacementField.BYTE_COUNT).bytes())
        assertEquals(huge, f.a("/count", PlacementField.POSITIVE_INTEGER).integer())
        assertThrows(PlacementRejected::class.java) { f.a("/float", PlacementField.POSITIVE_INTEGER) }
    }

    @Test
    fun optionalUnusedBoundsDoNotBlockTheirRequiredDirection() {
        val d = PlacementTestDraft().apply {
            serving()
            range("/capacity", lower = "24")
            range("/charge", upper = "26")
            range("/bad", lower = "10", upper = "9")
        }
        val f = d.freeze()
        val domain = PlacementDomain.physical("pool", "space", "partition", "tenant")
        val capacity = f.range("/capacity", domain)
        val charge = f.range("/charge", domain)
        assertEquals(big(24), capacity.qualifiedLower(f.basis, f.qs(), domain)!!.value)
        assertNull(capacity.qualifiedUpper(f.basis, f.qs(), domain))
        assertEquals(big(26), charge.qualifiedUpper(f.basis, f.qs(), domain)!!.value)
        assertNull(charge.qualifiedLower(f.basis, f.qs(), domain))
        assertEquals(PlacementProblem.CONTRADICTORY_INPUT, assertThrows(PlacementRejected::class.java) {
            f.range("/bad", domain)
        }.problem)
    }

    @Test
    fun twoEngineCandidatesRemainAmbiguousInEitherGraphOrder() {
        for (reverse in listOf(false, true)) {
            val d = PlacementTestDraft().apply {
                serving()
                selection("sel_engine2", "inference_engine")
                link("link.second", "sel_model", "sel_engine2", PlacementRole.ENGINE)
                if (reverse) {
                    val ordered = build["links"].toList().reversed()
                    build.putArray("links").addAll(ordered)
                }
            }
            val f = d.freeze()
            val graph = f.graph()
            val required = f.requirements().single().engine
            val binding = graph.resolve(f.basis, f.qs(), "sel_model", PlacementRole.ENGINE, required)
            assertEquals(PlacementOutcome.UNKNOWN, binding.check.outcome)
            assertEquals(PlacementReason.AMBIGUOUS_BINDING, binding.check.reason)
            assertEquals(setOf("sel_engine", "sel_engine2"), binding.candidates.map { it.id }.toSet())
            assertTrue(binding.resolved.isEmpty())
        }
    }

    @Test
    fun absentRequiredOptionalAndUnestablishedBindingsAreDifferent() {
        val d = PlacementTestDraft().apply { serving(engine = null) }
        d.put("/requires/sel_model/engine", true)
        val f = d.freeze()
        val graph = f.graph()
        val required = f.requirements().single().engine
        assertEquals(PlacementOutcome.FAIL, graph.resolve(f.basis, f.qs(), "sel_model", PlacementRole.ENGINE, required).check.outcome)
        val incomplete = f.qs { it.key.requestedPath == "/graph/complete" }
        assertEquals(PlacementOutcome.UNKNOWN, graph.resolve(f.basis, incomplete, "sel_model", PlacementRole.ENGINE, required).check.outcome)
        d.put("/requires/sel_model/engine", false)
        val optional = d.freeze()
        val optionalGraph = optional.graph()
        val requirement = optional.requirements().single().engine
        assertEquals(PlacementOutcome.SKIPPED, optionalGraph.resolve(optional.basis, optional.qs(), "sel_model",
            PlacementRole.ENGINE, requirement).check.outcome)
        d.state("/requires/sel_model/engine", Cd1State.NOT_APPLICABLE)
        val unresolved = d.freeze()
        val unresolvedGraph = unresolved.graph()
        val unresolvedRequirement = unresolved.requirements().single().engine
        assertEquals(PlacementOutcome.UNKNOWN, unresolvedGraph.resolve(unresolved.basis, unresolved.qs(), "sel_model",
            PlacementRole.ENGINE, unresolvedRequirement).check.outcome)
    }

    @Test
    fun knownWrongKindAndDisabledTargetsFailButMissingExactVersionIsUnknown() {
        for (change in listOf("disabled", "kind", "version")) {
            val d = PlacementTestDraft().apply { serving() }
            when (change) {
                "disabled" -> d.selected("sel_engine").put("enabled", false)
                "kind" -> {
                    val model = d.item("model")
                    d.selected("sel_engine").put("catalog_item_id", model["id"].textValue())
                        .put("catalog_item_version", model["version"].textValue())
                }
                else -> d.selected("sel_engine").put("catalog_item_version", "absent-exact-version")
            }
            val f = d.freeze()
            val graph = f.graph()
            val required = f.requirements().single().engine
            val result = graph.resolve(f.basis, f.qs(), "sel_model", PlacementRole.ENGINE, required)
            assertEquals(if (change == "version") PlacementOutcome.UNKNOWN else PlacementOutcome.FAIL, result.check.outcome, change)
            assertTrue(result.resolved.isEmpty())
        }
    }

    @Test
    fun sourceArtifactKindCannotPassThroughAnExactEngineCatalogReference() {
        val d = PlacementTestDraft().apply { serving() }
        val engine = d.item("inference_engine")
        d.selected("sel_model").put("catalog_item_id", engine["id"].textValue())
            .put("catalog_item_version", engine["version"].textValue())
        val f = d.freeze()
        val deployment = f.deployment()
        val result = deployment.evaluate(f.basis, f.qs())
        assertEquals(PlacementOutcome.FAIL, result.result.outcome)
        assertEquals(PlacementOutcome.FAIL, f.graph().lineage(f.basis, f.qs(), "sel_model", emptyList()).outcome)
        assertEquals(PlacementMode.UNKNOWN, result.components.single().mode)
    }

    @Test
    fun contradictoryAndDanglingOriginalGraphRecordsRejectBeforeEvaluation() {
        val d = PlacementTestDraft().apply { serving() }
        (d.build["links"][0] as ObjectNode).put("to_selection_id", "sel_absent")
        assertEquals(PlacementProblem.DANGLING_LINK, assertThrows(PlacementRejected::class.java) { d.freeze() }.problem)
        val duplicate = PlacementTestDraft().apply { serving() }
        duplicate.build.withArray("links").add(duplicate.build["links"][0].deepCopy<JsonNode>())
        assertEquals(PlacementProblem.DUPLICATE_IDENTITY, assertThrows(PlacementRejected::class.java) { duplicate.freeze() }.problem)
    }

    @Test
    fun hybridKeepsLocalFailuresAndRemoteSupportFailuresIndependent() {
        val d = PlacementTestDraft().apply {
            serving()
            serving("sel_remote", operator = "provider", capacity = "shared", exposed = false, engine = null, host = null, devices = emptyList())
            condition("sel_model", "local-budget", PlacementConditionKind.LOCAL_HARDWARE, false)
            condition("sel_remote", "service-support", PlacementConditionKind.SERVICE_SUPPORT, false)
            condition("sel_remote", "remote-local-input", PlacementConditionKind.LOCAL_HARDWARE, false)
        }
        val f = d.freeze()
        val deployment = f.deployment()
        val result = deployment.evaluate(f.basis, f.qs())
        assertEquals(PlacementMode.HYBRID, result.mode)
        assertEquals(PlacementOutcome.FAIL, result.result.outcome)
        val local = result.components.single { it.selectionId == "sel_model" }
        val remote = result.components.single { it.selectionId == "sel_remote" }
        assertTrue(local.checks.any { it.rule == PlacementRule.LOCAL_HARDWARE && it.outcome == PlacementOutcome.FAIL })
        assertTrue(remote.checks.any { it.rule == PlacementRule.LOCAL_HARDWARE && it.outcome == PlacementOutcome.SKIPPED })
        assertFalse(remote.checks.any { it.rule == PlacementRule.LOCAL_HARDWARE && it.outcome == PlacementOutcome.FAIL })
        assertTrue(remote.checks.any { it.rule == PlacementRule.RUNTIME_ACCESS && it.outcome == PlacementOutcome.FAIL })
        assertTrue(remote.checks.any { it.rule == PlacementRule.REMOTE_CAPACITY && it.outcome == PlacementOutcome.UNKNOWN })
    }

    @Test
    fun protocolAndCatalogAccessLabelsDoNotSupplyTheRuntimeOperator() {
        val d = PlacementTestDraft().apply { serving() }
        (d.item("model")["spec"] as ObjectNode).putArray("access_modes").add("api")
        (d.selected("sel_model")["settings"] as ObjectNode).put("serving_mode", "api")
        val f = d.freeze()
        val deployment = f.deployment()
        assertEquals(PlacementMode.SELF_HOSTED, deployment.evaluate(f.basis, f.qs()).mode)
        val unqualified = f.qs { it.key.fact == PlacementFact.OPERATOR }
        assertEquals(PlacementMode.UNKNOWN, deployment.evaluate(f.basis, unqualified).mode)
    }

    @TestFactory
    fun providerCapacityAndExposureHaveSeparateEffects() = listOf(
        Triple("shared", false, PlacementMode.API),
        Triple("reserved_throughput", false, PlacementMode.MANAGED),
        Triple("dedicated_instances", true, PlacementMode.MANAGED),
    ).map { (capacity, exposed, expected) ->
        DynamicTest.dynamicTest("provider " + capacity + " exposure " + exposed) {
            val d = PlacementTestDraft().apply {
                serving(operator = "provider", capacity = capacity, exposed = exposed)
                condition("sel_model", "support", PlacementConditionKind.SERVICE_SUPPORT, false)
                condition("sel_model", "hardware", PlacementConditionKind.LOCAL_HARDWARE, false)
            }
            val f = d.freeze()
            val deployment = f.deployment()
            val result = deployment.evaluate(f.basis, f.qs())
            assertEquals(expected, result.mode)
            assertEquals(PlacementOutcome.FAIL, result.result.outcome)
            val component = result.components.single()
            assertEquals(if (exposed) PlacementApplicability.APPLICABLE else PlacementApplicability.INAPPLICABLE, component.localApplicability)
            assertEquals(exposed, component.checks.any { it.rule == PlacementRule.LOCAL_HARDWARE && it.outcome == PlacementOutcome.FAIL })
            assertTrue(component.checks.any { it.rule == PlacementRule.RUNTIME_ACCESS && it.outcome == PlacementOutcome.FAIL })
        }
    }

    @Test
    fun supportCompletenessCannotEraseKnownModeOrHideADroppedCondition() {
        val d = PlacementTestDraft().apply {
            serving()
            condition("sel_model", "must-have", PlacementConditionKind.SERVICE_SUPPORT, false)
            state("/conditions/sel_model/complete", Cd1State.UNKNOWN)
        }
        val f = d.freeze()
        val deployment = f.deployment()
        val result = deployment.evaluate(f.basis, f.qs())
        assertEquals(PlacementMode.SELF_HOSTED, result.mode)
        assertEquals(PlacementOutcome.FAIL, result.result.outcome)
        assertTrue(result.result.checks.any { it.rule == PlacementRule.COVERAGE && it.outcome == PlacementOutcome.UNKNOWN })
        assertEquals(PlacementProblem.CONTRADICTORY_INPUT, assertThrows(PlacementRejected::class.java) {
            f.deployment(conditions = emptyList())
        }.problem)
        val membershipUnknown = f.qs { it.key.requestedPath == "/serving/complete" }
        assertEquals(PlacementMode.UNKNOWN, deployment.evaluate(f.basis, membershipUnknown).mode)
    }

    @Test
    fun adapterUsesExactBaseEngineAndBothArtifactsConditions() {
        val d = PlacementTestDraft().apply {
            serving()
            selection("sel_adapter", "fine_tuned_model")
            link("link.adapter.base", "sel_adapter", "sel_model", PlacementRole.BASE)
            artifact("sel_adapter", "adapter")
            requirements["sel_adapter"] = "sel_model"
            put("/requires/sel_adapter/engine", true)
            put("/requires/sel_adapter/host", true)
            put("/requires/sel_adapter/devices", true)
            condition("sel_adapter", "adapter-engine-tuple", PlacementConditionKind.ARTIFACT_SUPPORT, false)
            condition("sel_model", "base-engine-tuple", PlacementConditionKind.ARTIFACT_SUPPORT, true)
        }
        val f = d.freeze()
        val deployment = f.deployment()
        val result = deployment.evaluate(f.basis, f.qs()).components.single { it.selectionId == "sel_adapter" }
        assertEquals("sel_model", result.effectiveSelectionId)
        assertEquals("sel_engine", result.serving!!.engine!!.resolved.single().id)
        assertEquals(PlacementMode.SELF_HOSTED, result.mode)
        assertTrue(result.checks.any { it.outcome == PlacementOutcome.FAIL && it.observations.any { a ->
            a.requestedPath == "/conditions/sel_adapter/adapter-engine-tuple/satisfied" } })
        d.put("/artifacts/sel_adapter/version", "different-exact-version")
        val changed = d.freeze()
        val changedGraph = changed.graph()
        val changedFacts = changed.artifacts()
        assertEquals(PlacementOutcome.FAIL, changedGraph.lineage(changed.basis, changed.qs(), "sel_adapter", changedFacts).outcome)
    }

    @Test
    fun fullArtifactUsesOwnDeploymentAndDoesNotInheritBaseSupport() {
        val d = PlacementTestDraft().apply {
            serving()
            serving("sel_full", "fine_tuned_model", "provider", "shared", false, null, null, emptyList())
            (item("fine_tuned_model")["spec"] as ObjectNode).put("adapter_only", false)
            artifact("sel_full", "full")
            link("link.full.base", "sel_full", "sel_model", PlacementRole.BASE)
            condition("sel_model", "base-only", PlacementConditionKind.ARTIFACT_SUPPORT, false)
        }
        val f = d.freeze()
        val deployment = f.deployment()
        val full = deployment.evaluate(f.basis, f.qs()).components.single { it.selectionId == "sel_full" }
        assertEquals("sel_full", full.effectiveSelectionId)
        assertEquals(PlacementMode.API, full.mode)
        assertFalse(full.checks.any { check -> check.observations.any { it.requestedPath.contains("base-only") } })
    }

    @Test
    fun checkedOnlyAttributionUsesOnlyEvidenceActuallyConsumed() {
        val d = PlacementTestDraft().apply { serving(); put("/unused", true) }
        val f = d.freeze()
        val deployment = f.deployment()
        val unused = f.a("/unused", PlacementField.BOOLEAN)
        f.qualify(unused, PlacementFact.ACCESS, PlacementDomain.component("sel_model"), checked = true)
        assertFalse(deployment.evaluate(f.basis, f.qs()).result.checks.any { it.checkedOnly })
        val operator = f.a("/deployment/sel_model/operator", PlacementField.OPERATOR)
        f.qualify(operator, PlacementFact.OPERATOR, PlacementDomain.component("sel_model"), checked = true)
        assertTrue(deployment.evaluate(f.basis, f.qs()).result.checks.single { it.rule == PlacementRule.DEPLOYMENT }.checkedOnly)
        f.qualify(operator, PlacementFact.OPERATOR, PlacementDomain.component("sel_model"), resolved = null)
        assertEquals(PlacementMode.UNKNOWN, deployment.evaluate(f.basis, f.qs()).mode)
    }

    @Test
    fun unestablishedReserveBasisCannotQualifyABudget() {
        val d = PlacementTestDraft().apply {
            serving()
            budget("/budget", "24", "8")
            state("/budget/basis", Cd1State.UNKNOWN)
        }
        val f = d.freeze()
        val domain = PlacementDomain.physical("pool.1", "space.1", "partition.1", "tenant.1")
        val budget = f.budget("/budget", domain)
        assertNull(budget.qualified(f.basis, f.qs(), domain))
    }

    @Test
    fun runtimeOriginalSourceReferencesRejectBeforeUnusedScopeEvaluation() {
        for (source in listOf("absent-input", "hardware")) {
            val d = PlacementTestDraft().apply { serving(); put("/runtime", runtime(source = source)) }
            val f = d.freeze()
            assertThrows(PlacementRejected::class.java) {
                PlacementRuntimeBinding(f.a("/runtime", PlacementField.RUNTIME_BINDING))
            }
        }
    }

    @TestFactory
    fun checkedOnlyAdapterKindSurvivesEveryDependentBaseExit() = listOf(
        "missing-base", "disabled-base", "unresolved-version", "ineligible-version",
        "mismatched-version", "mismatched-base-id",
    ).map { defect ->
        DynamicTest.dynamicTest("checked-only adapter prerequisite " + defect) {
            val d = PlacementTestDraft().apply {
                serving()
                selection("sel_adapter", "fine_tuned_model")
                artifact("sel_adapter", "adapter")
                if (defect != "missing-base")
                    link("link.adapter.base", "sel_adapter", "sel_model", PlacementRole.BASE)
                when (defect) {
                    "disabled-base" -> selected("sel_model").put("enabled", false)
                    "unresolved-version" -> state("/artifacts/sel_adapter/version", Cd1State.UNKNOWN)
                    "mismatched-version" -> put("/artifacts/sel_adapter/version", "different-exact-version")
                    "mismatched-base-id" -> (item("fine_tuned_model")["spec"] as ObjectNode)
                        .put("base_model_ref", item("embedding_model")["id"].textValue())
                }
            }
            val f = d.freeze()
            val graph = f.graph()
            val facts = f.artifacts()
            val kind = facts.single().artifactKind
            f.qualify(kind, PlacementFact.COMPATIBILITY, PlacementDomain.component("sel_adapter"), checked = true)
            val q = f.qs {
                defect == "ineligible-version" && it.key.requestedPath == "/artifacts/sel_adapter/version"
            }
            val result = graph.lineage(f.basis, q, "sel_adapter", facts)
            val unknown = defect in setOf("unresolved-version", "ineligible-version")
            assertEquals(if (unknown) PlacementOutcome.UNKNOWN else PlacementOutcome.FAIL, result.outcome)
            assertTrue(result.checks.isNotEmpty())
            assertTrue(result.checks.all { it.checkedOnly }, defect)
            assertTrue(result.checks.all { check ->
                check.evidence.any { it.answer.requestedPath == kind.requestedPath && it.checkedOnly }
            }, defect)
            val last = result.checks.last()
            if (defect in setOf("missing-base", "disabled-base", "mismatched-base-id"))
                assertFalse(last.observations.any { it.requestedPath == "/artifacts/sel_adapter/version" })
            else
                assertTrue(last.observations.any { it.requestedPath == "/artifacts/sel_adapter/version" })
        }
    }

    @Test
    fun aBaseIdMismatchDoesNotConsumeAnUnusedCheckedOnlyVersion() {
        val d = PlacementTestDraft().apply {
            serving()
            selection("sel_adapter", "fine_tuned_model")
            link("link.adapter.base", "sel_adapter", "sel_model", PlacementRole.BASE)
            artifact("sel_adapter", "adapter")
            (item("fine_tuned_model")["spec"] as ObjectNode)
                .put("base_model_ref", item("embedding_model")["id"].textValue())
        }
        val f = d.freeze()
        val graph = f.graph()
        val facts = f.artifacts()
        f.qualify(facts.single().baseVersion, PlacementFact.COMPATIBILITY,
            PlacementDomain.component("sel_adapter"), checked = true)
        val result = graph.lineage(f.basis, f.qs(), "sel_adapter", facts)
        assertEquals(PlacementOutcome.FAIL, result.outcome)
        val mismatch = result.checks.last()
        assertEquals(PlacementReason.BASE_ARTIFACT_MISMATCH, mismatch.reason)
        assertFalse(mismatch.checkedOnly)
        assertFalse(mismatch.observations.any { it.requestedPath == "/artifacts/sel_adapter/version" })
        assertFalse(mismatch.evidence.any { it.answer.requestedPath == "/artifacts/sel_adapter/version" })
    }

    @Test
    fun currentCoverageCannotMakeAnOlderInactiveDeploymentInapplicable() {
        val draft = PlacementTestDraft().apply {
            serving()
            selected("sel_model").put("enabled", false)
        }
        val old = draft.freeze()
        val oldGraph = old.graph()
        val oldCoverage = old.coverage("/serving/complete",
            PlacementCoverageKind.SERVING_COMPONENTS, scenario = old.basis.operation)

        fun oldDeployment(coverage: PlacementCoverage) = PlacementDeployment(
            basis = old.basis,
            graph = oldGraph,
            rows = emptyList(),
            artifactFacts = emptyList(),
            conditions = emptyList(),
            conditionCoverage = emptyList(),
            servingCoverage = coverage,
            requirements = emptyList(),
        )

        // Positive control: an actually inactive, current basis can be skipped.
        val oldOnly = oldDeployment(oldCoverage)
        assertEquals(PlacementOutcome.SKIPPED,
            oldOnly.evaluate(old.basis, old.qs()).result.outcome)

        draft.selected("sel_model").put("enabled", true)
        val current = draft.freeze()
        assertEquals(old.basis.buildId, current.basis.buildId)
        assertEquals(old.basis.operation, current.basis.operation)
        assertNotEquals(old.basis.sha256, current.basis.sha256)

        // Positive control: the current source contains an enabled serving model.
        val actualCurrent = current.deployment()
        val actualCurrentResult = actualCurrent.evaluate(current.basis, current.qs())
        assertEquals(PlacementOutcome.PASS, actualCurrentResult.result.outcome)
        assertTrue(actualCurrentResult.components.any { it.selectionId == "sel_model" })

        // Existing retained old answers already become unknown on a new basis.
        assertEquals(PlacementOutcome.UNKNOWN,
            oldOnly.evaluate(current.basis, current.qs()).result.outcome)

        // Public constructors currently permit a current-basis coverage answer
        // with the old deployment graph/basis when build ID and operation agree.
        val currentCoverage = current.coverage("/serving/complete",
            PlacementCoverageKind.SERVING_COMPONENTS, scenario = current.basis.operation)
        val mixed = oldDeployment(currentCoverage)
        val evaluated = mixed.evaluate(current.basis, current.qs())
        val result = evaluated.result

        // Fresh coverage cannot classify membership retained from an older basis.
        assertEquals(PlacementOutcome.UNKNOWN, result.outcome)
        assertTrue(result.checks.any { it.reason == PlacementReason.CHANGED_BASIS })
        assertEquals(current.basis.sha256, result.basisSha256)
        assertEquals(PlacementMode.UNKNOWN, evaluated.mode)
        assertTrue(evaluated.components.isEmpty())
    }
}
