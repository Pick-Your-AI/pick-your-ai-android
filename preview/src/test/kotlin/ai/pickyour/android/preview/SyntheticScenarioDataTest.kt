package ai.pickyour.android.preview

import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.pricing.CostEvidence
import ai.pickyour.core.pricing.DeclaredCoverage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Packaged fixture/core-reader conformance on a simulated Android runtime; no scenario UI is wired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyntheticScenarioDataTest {
    private fun asset(path: String) = RuntimeEnvironment.getApplication().assets.open(path).use { it.readBytes() }
    private fun manifest() = asset(SyntheticScenarioPins.MANIFEST_PATH)
    private fun inputs() = SyntheticScenarioAsset.entries.associateWith { asset(it.assetPath) }
    private fun sample(id: SyntheticScenarioId): SyntheticScenarioReception.Accepted {
        val result = SyntheticScenarioData.admit(id, manifest(), inputs())
        assertTrue("Pinned synthetic input must pass its existing readers", result is SyntheticScenarioReception.Accepted)
        return result as SyntheticScenarioReception.Accepted
    }
    private fun expected(id: SyntheticScenarioId): JSONObject {
        val rows = JSONObject(manifest().toString(Charsets.UTF_8)).getJSONArray("scenarios")
        return (0 until rows.length()).map(rows::getJSONObject)
            .single { it.getString("id") == id.wireName }.getJSONObject("expected")
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u${character.code.toString(16).padStart(4, '0')}")
                else append(character)
        }
        append('"')
    }
    // The frozen report profile contains strings, integer numbers, booleans, arrays and objects.
    // Sort only object keys; retain all array order and every message/selection/severity/value.
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        else -> error("Unexpected frozen report value")
    }

    @Test fun allFiveSamplesRetainSyntheticVersionAndDistinctInputIdentity() {
        val samples = SyntheticScenarioId.entries.map(::sample)
        assertEquals(5, samples.size)
        assertEquals(5, samples.map { it.identity.descriptor }.toSet().size)
        for (value in samples) {
            assertEquals("synthetic", value.identity.classification)
            assertEquals("android-preview-scenarios-1", value.identity.fixtureSet)
            assertEquals("1.0.0", value.identity.schemaVersion)
            assertEquals(sha256(manifest()), value.identity.manifestSha256)
        }
    }

    @Test fun emptySampleIsOnlyAnExpectedSchemaRejectionWithoutAnAcceptedBuildOrReport() {
        val empty = SyntheticScenarioAsset.EMPTY_BUILD
        assertEquals(FrozenValidation.SchemaMismatch, FrozenSchemaValidator().validate(empty.document, asset(empty.assetPath).inputStream()))
        assertSame(SyntheticScenarioContent.ExpectedEmptyRejection, sample(SyntheticScenarioId.EMPTY_DRAFT).content)
        val value = JSONObject(asset(empty.assetPath).toString(Charsets.UTF_8))
        assertEquals(0, value.getJSONObject("objectives").length())
        assertEquals(0, value.getJSONArray("selections").length())
        assertEquals(0, value.getJSONArray("links").length())
    }

    @Test fun allDiagnosticFieldsMatchActualFrozenPythonReferenceAndRetainUnknowns() {
        for (id in listOf(SyntheticScenarioId.CORE_BUILD, SyntheticScenarioId.UNKNOWN_EVIDENCE, SyntheticScenarioId.FAILED_RULE)) {
            val content = sample(id).content as SyntheticScenarioContent.Diagnostic
            val report = JSONObject(content.report.json)
            assertEquals(expected(id).getString("reference_report_sha256"), sha256(canonical(report).toByteArray(Charsets.UTF_8)))
            assertEquals("blocked", report.getString("deployment_readiness"))
            assertEquals(3, report.getJSONObject("summary").getInt("unknown"))
        }
        val failed = JSONObject((sample(SyntheticScenarioId.FAILED_RULE).content as SyntheticScenarioContent.Diagnostic).report.json)
        val findings = failed.getJSONArray("findings")
        val failures = (0 until findings.length()).map(findings::getJSONObject)
            .filter { it.getString("outcome") == "fail" }.map { it.getString("rule_id") }.toSet()
        assertEquals(setOf("rule.model.context.fit", "rule.context.policy.limit"), failures)
        assertEquals("invalid", failed.getString("result"))
    }

    @Test fun coreSampleKeepsT6AndDisablesOnlyTheFourteenOptionalSelections() {
        val core = JSONObject(asset(SyntheticScenarioAsset.CORE_BUILD.assetPath).toString(Charsets.UTF_8)).getJSONArray("selections")
        val selections = (0 until core.length()).map(core::getJSONObject)
        assertEquals(29, selections.size)
        assertEquals(15, selections.count { it.getBoolean("enabled") })
        val activeKinds = selections.filter { it.getBoolean("enabled") }.map { it.getString("kind") }.toSet()
        assertEquals(setOf("workload", "deployment_mode", "model_provider", "model", "context_policy", "hosting_platform",
            "accelerator", "compute_profile", "inference_engine", "storage", "guardrail", "evaluation_suite",
            "deployment_target", "monitoring_stack", "cost_policy"), activeKinds)
    }

    @Test fun partialQuoteKeepsRecordedMoneyUnpricedAndUnclassifiedEvidenceSeparate() {
        val estimate = (sample(SyntheticScenarioId.PARTIAL_QUOTE).content as SyntheticScenarioContent.PartialQuote).estimate
        assertEquals("build.android.synthetic.core.v1", estimate.buildId)
        assertEquals(DeclaredCoverage.PARTIAL, estimate.declaredCoverage)
        assertEquals("2.50", estimate.reportedSubtotal.amount.encoded)
        assertEquals("0.00", estimate.reportedTax.amount.encoded)
        assertEquals("2.50", estimate.reportedTotal.amount.encoded)
        assertEquals("USD", estimate.currency)
        assertEquals(setOf("sel_compute_profile"), estimate.reportedUnpricedSelectionIds)
        val expectedIds = expected(SyntheticScenarioId.PARTIAL_QUOTE).getJSONArray("unclassified_active_selection_ids")
        assertEquals((0 until expectedIds.length()).map(expectedIds::getString).toSet(), estimate.unclassifiedActiveSelectionIds)
        assertEquals(13, estimate.unclassifiedActiveSelectionIds.size)
        val line = estimate.lines.single()
        assertEquals("sel_storage", line.selectionId)
        assertEquals("50", line.quantity)
        assertEquals("0.05", line.unitPrice.amount.encoded)
        assertEquals(CostEvidence.NOT_ESTABLISHED, estimate.budgetPass)
        assertEquals(CostEvidence.NOT_ESTABLISHED, estimate.freshness)
        assertEquals(CostEvidence.NOT_ESTABLISHED, estimate.freeOrIncludedTreatment)
    }

    @Test fun manifestVersionsMalformedBytesAndSelfDeclaredReplacementHashesCannotBeAdmitted() {
        val original = manifest().toString(Charsets.UTF_8)
        val changed = listOf(
            original.replace("android-preview-scenarios-1", "android-preview-scenarios-2").toByteArray(),
            original.replace("\"schema_version\": \"1.0.0\"", "\"schema_version\": \"2.0.0\"").toByteArray(),
            original.replace("\"classification\": \"synthetic\"", "\"classification\": \"production\"").toByteArray(),
            original.replace("\"schema_version\":", "\"schema_version\":\"2.0.0\",\"schema_version\":").toByteArray(),
            original.toByteArray().dropLast(1).toByteArray(),
            (original + "{}").toByteArray(),
            byteArrayOf(0xff.toByte()),
        )
        for (bytes in changed) assertEquals(SyntheticScenarioReception.Rejected(SyntheticScenarioRejection.UNRECOGNIZED_MANIFEST),
            SyntheticScenarioData.admit(SyntheticScenarioId.CORE_BUILD, bytes, inputs()))
        val rebound = JSONObject(original)
        val rows = rebound.getJSONArray("assets")
        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("key") == "EMPTY_BUILD" }
        val replacement = asset(SyntheticScenarioAsset.CORE_BUILD.assetPath)
        row.put("sha256", sha256(replacement)).put("bytes", replacement.size)
        val replaced = inputs().toMutableMap().apply { put(SyntheticScenarioAsset.EMPTY_BUILD, replacement) }
        assertEquals(SyntheticScenarioReception.Rejected(SyntheticScenarioRejection.UNRECOGNIZED_MANIFEST),
            SyntheticScenarioData.admit(SyntheticScenarioId.EMPTY_DRAFT, rebound.toString().toByteArray(), replaced))
    }

    @Test fun eachMissingChangedOrSwappedInputRejectsBeforeAnUnrelatedSampleCanSucceed() {
        val original = inputs()
        for (key in SyntheticScenarioAsset.entries) {
            val missing = original.toMutableMap().apply { remove(key) }
            assertEquals(SyntheticScenarioReception.Rejected(SyntheticScenarioRejection.ASSET_INVENTORY),
                SyntheticScenarioData.admit(SyntheticScenarioId.EMPTY_DRAFT, manifest(), missing))
            val changed = original.toMutableMap().apply { put(key, getValue(key) + byteArrayOf(0x20)) }
            assertEquals(SyntheticScenarioReception.Rejected(SyntheticScenarioRejection.ASSET_INTEGRITY),
                SyntheticScenarioData.admit(SyntheticScenarioId.EMPTY_DRAFT, manifest(), changed))
        }
        val swapped = original.toMutableMap().apply { put(SyntheticScenarioAsset.CORE_BUILD, getValue(SyntheticScenarioAsset.ORIGINAL_BUILD)) }
        assertEquals(SyntheticScenarioReception.Rejected(SyntheticScenarioRejection.ASSET_INTEGRITY),
            SyntheticScenarioData.admit(SyntheticScenarioId.CORE_BUILD, manifest(), swapped))
    }

    @Test fun byteBudgetsFailClosedBeforeAnyFixtureEvaluation() {
        val original = inputs()
        val tooLarge = original.toMutableMap().apply { put(SyntheticScenarioAsset.CATALOG, ByteArray(SyntheticScenarioPins.MAX_DOCUMENT_BYTES + 1)) }
        val largeSet = SyntheticScenarioAsset.entries.associateWith { ByteArray(SyntheticScenarioPins.MAX_DOCUMENT_BYTES) }
        for ((manifest, bytes) in listOf(
            ByteArray(SyntheticScenarioPins.MAX_MANIFEST_BYTES + 1) to original,
            manifest() to tooLarge, manifest() to largeSet,
        )) assertEquals(SyntheticScenarioReception.Rejected(SyntheticScenarioRejection.INPUT_LIMIT),
            SyntheticScenarioData.admit(SyntheticScenarioId.CORE_BUILD, manifest, bytes))
    }

    @Test fun acceptedResultCannotBeReboundByLaterCallerArrayOrMapMutation() {
        val manifest = manifest()
        val supplied = inputs().toMutableMap()
        val accepted = SyntheticScenarioData.admit(SyntheticScenarioId.CORE_BUILD, manifest, supplied) as SyntheticScenarioReception.Accepted
        val content = accepted.content as SyntheticScenarioContent.Diagnostic
        val report = content.report.json
        val descriptor = accepted.identity.descriptor
        manifest.fill(0)
        supplied.values.forEach { it.fill(0) }
        supplied.clear()
        assertEquals(report, content.report.json)
        assertEquals(descriptor, accepted.identity.descriptor)
        assertTrue(report.contains("build.android.synthetic.core.v1"))
    }

    @Test fun deliveryPlansAreImmutableFiniteDefinitionsWithExplicitRetryAndNewerFirstOrder() {
        assertEquals(4, SyntheticDeliveryScript.entries.size)
        for (script in SyntheticDeliveryScript.entries) {
            assertEquals("synthetic-delivery-scripts-1", script.version)
            assertTrue(script.steps.size in 2..5)
            assertThrows(UnsupportedOperationException::class.java) { (script.steps as MutableList<SyntheticDeliveryStep>).clear() }
        }
        assertEquals(listOf(SyntheticDeliveryStep.AdvanceVirtualTime(750)),
            SyntheticDeliveryScript.DELAYED.steps.filterIsInstance<SyntheticDeliveryStep.AdvanceVirtualTime>())
        val retry = SyntheticDeliveryScript.FAILURE_THEN_EXPLICIT_RETRY.steps
        assertEquals(SyntheticDeliveryStep.Deliver(SyntheticAttemptSlot.SELECTED, SyntheticDeliveryOutcome.SIMULATED_UNAVAILABLE), retry[1])
        assertSame(SyntheticDeliveryStep.RequireExplicitRetry, retry[2])
        assertEquals(SyntheticDeliveryStep.Start(SyntheticAttemptSlot.RETRY, SyntheticScenarioTarget.SELECTED), retry[3])
        val newer = SyntheticDeliveryScript.NEWER_FIRST.steps
        assertEquals(SyntheticDeliveryStep.Start(SyntheticAttemptSlot.OLDER, SyntheticScenarioTarget.CORE_BUILD), newer[0])
        assertEquals(SyntheticDeliveryStep.Start(SyntheticAttemptSlot.SELECTED, SyntheticScenarioTarget.SELECTED), newer[1])
        assertEquals(listOf(SyntheticAttemptSlot.SELECTED, SyntheticAttemptSlot.OLDER),
            newer.filterIsInstance<SyntheticDeliveryStep.Deliver>().map { it.slot })
    }
}
