package ai.pickyour.core.resources

import ai.pickyour.core.contracts.ResourceConfidence
import ai.pickyour.core.contracts.ResourceEstimateDecoder
import ai.pickyour.core.contracts.ResourceEstimateDecoding
import ai.pickyour.core.contracts.ResourceMetric
import ai.pickyour.core.contracts.ResourceUnit
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

class RecordedResourceInspectionTest {
    private val inspector = RecordedResourceInspector()
    private fun example(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/$name.json")).use { it.readBytes() }
    private fun build(): ObjectNode = parse(example("build"))
    private fun estimate(): ObjectNode = parse(example("resource-estimate"))
    private fun parse(bytes: ByteArray): ObjectNode =
        (BoundedJsonReader().read(bytes.inputStream()) as JsonInputResult.Parsed).value
    private fun bytes(node: ObjectNode): ByteArray = node.toString().toByteArray(Charsets.UTF_8)
    private fun inspect(build: ObjectNode = build(), estimate: ObjectNode = estimate()): ResourceInspection =
        inspector.inspect(bytes(build).inputStream(), bytes(estimate).inputStream())
    private fun inspected(build: ObjectNode = build(), estimate: ObjectNode = estimate()): InspectedResourceEstimate =
        (inspect(build, estimate) as ResourceInspection.Inspected).record
    private fun selection(build: ObjectNode, id: String): ObjectNode =
        build["selections"].first { it["id"].textValue() == id } as ObjectNode
    private fun entry(estimate: ObjectNode): ObjectNode = estimate["estimates"][0] as ObjectNode
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun rejected(reason: ResourceInspectionRejection, result: ResourceInspection) =
        assertEquals(ResourceInspection.Rejected(reason), result)

    @Test fun `canonical records retain all fields and explicit applicability gaps`() {
        val originalBuild = example("build")
        val originalEstimate = example("resource-estimate")
        val record = (inspector.inspect(originalBuild.inputStream(), originalEstimate.inputStream())
            as ResourceInspection.Inspected).record
        assertEquals("build.fixture.1", record.buildId)
        assertEquals(BigInteger.ONE, record.buildRevision)
        assertEquals("FICTIONAL Full-Stack AI Build", record.buildName)
        assertEquals("estimated", record.declaredMinimumConfidence)
        assertEquals(digest(originalBuild), record.identity.buildSha256)
        assertEquals(digest(originalEstimate), record.identity.estimateSha256)
        assertEquals(parse(originalBuild), parse(record.recordedBuildJson.toByteArray()))
        assertEquals(parse(originalEstimate), parse(record.estimate.json.toByteArray()))
        assertEquals(29, record.selections.size)
        val model = record.selections.single { it.id == "sel_model" }
        assertEquals("model", model.kind)
        assertEquals("cat.model.fixture", model.catalogItemId)
        assertEquals("2026.10-demo", model.catalogItemVersion)
        assertEquals("primary", model.role)
        assertTrue(model.enabled)
        assertEquals(selection(parse(originalBuild), "sel_model"), parse(model.recordedJson.toByteArray()))
        assertEquals("fp16", parse(model.settingsJson.toByteArray())["precision"].textValue())
        assertEquals(ResourceInspectionGap.entries.toSet(), record.gaps)
        assertEquals("ResourceInputIdentity", record.identity.toString())
        assertEquals("InspectedResourceEstimate", record.toString())
        assertEquals("RecordedResourceSelection", model.toString())
    }

    @Test fun `different build identity or exact revision is rejected without substitution`() {
        rejected(ResourceInspectionRejection.BUILD_BINDING_MISMATCH,
            inspect(estimate = estimate().put("build_id", "build.other")))
        rejected(ResourceInspectionRejection.BUILD_BINDING_MISMATCH,
            inspect(estimate = estimate().put("build_revision", BigInteger("9007199254740993"))))
        val large = BigInteger("123456789012345678901234567890123456789012345678901234567890")
        val record = inspected(build().put("revision", large), estimate().put("build_revision", large))
        assertEquals(large, record.buildRevision)
        assertEquals(large, record.estimate.buildRevision)
        rejected(ResourceInspectionRejection.BUILD_BINDING_MISMATCH,
            inspect(build().put("revision", large), estimate().put("build_revision", large.add(BigInteger.ONE))))
    }

    @Test fun `integral spellings bind exactly while fractional and normalized oversize revisions fail`() {
        val buildText = example("build").toString(Charsets.UTF_8).replace("\"revision\": 1", "\"revision\": 1.00e2")
        val estimateText = example("resource-estimate").toString(Charsets.UTF_8)
            .replace("\"build_revision\": 1", "\"build_revision\": 100")
        val record = (inspector.inspect(buildText.byteInputStream(), estimateText.byteInputStream())
            as ResourceInspection.Inspected).record
        assertEquals(BigInteger.valueOf(100), record.buildRevision)
        assertEquals(digest(buildText.toByteArray()), record.identity.buildSha256)
        for (fraction in listOf("1.1", "9007199254740992.1")) {
            rejected(ResourceInspectionRejection.INVALID_BUILD,
                inspect(build = build().put("revision", BigDecimal(fraction))))
            rejected(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE,
                inspect(estimate = estimate().put("build_revision", BigDecimal(fraction))))
        }
        val expanding = "9".repeat(126) + "e2"
        assertEquals(128, expanding.length)
        val input = example("build").toString(Charsets.UTF_8)
            .replace("\"revision\": 1", "\"revision\": $expanding")
        rejected(ResourceInspectionRejection.INVALID_BUILD,
            inspector.inspect(input.byteInputStream(), example("resource-estimate").inputStream()))
    }

    @Test fun `duplicate build selection IDs stop before reading the estimate`() {
        val input = build()
        selection(input, "sel_context_policy").put("id", "sel_model")
        val untouched = Tracked(example("resource-estimate"))
        rejected(ResourceInspectionRejection.DUPLICATE_BUILD_SELECTION_ID,
            inspector.inspect(bytes(input).inputStream(), untouched))
        assertEquals(0, untouched.readCalls)
        assertFalse(untouched.closed)
    }

    @Test fun `unknown and disabled references are rejected by the receiver not the wire decoder`() {
        val unknown = estimate()
        entry(unknown).set<ArrayNode>("selection_ids", unknown.arrayNode().add("sel_other_build"))
        assertInstanceOf(ResourceEstimateDecoding.Decoded::class.java,
            ResourceEstimateDecoder().decode(bytes(unknown).inputStream()))
        rejected(ResourceInspectionRejection.UNKNOWN_SELECTION_REFERENCE, inspect(estimate = unknown))
        val disabled = build()
        selection(disabled, "sel_model").put("enabled", false)
        rejected(ResourceInspectionRejection.DISABLED_SELECTION_REFERENCE, inspect(build = disabled))
        val disabledUnreferenced = build()
        selection(disabledUnreferenced, "sel_monitoring_stack").put("enabled", false)
        val record = inspected(disabledUnreferenced)
        assertFalse(record.selections.single { it.id == "sel_monitoring_stack" }.enabled)
        assertFalse("sel_monitoring_stack" in record.unreferencedActiveSelectionIds)
    }

    @Test fun `unreferenced active choices remain visible without inventing required metric coverage`() {
        val input = estimate()
        val entries = input["estimates"] as ArrayNode
        entries.remove(1)
        entry(input).set<ArrayNode>("selection_ids", input.arrayNode().add("sel_model"))
        val record = inspected(estimate = input)
        assertTrue("sel_context_policy" in record.unreferencedActiveSelectionIds)
        assertTrue("sel_accelerator" in record.unreferencedActiveSelectionIds)
        assertFalse("sel_model" in record.unreferencedActiveSelectionIds)
        assertEquals(record.selections.filter { it.enabled }.map { it.id }.toSet() - "sel_model",
            record.unreferencedActiveSelectionIds)
        assertEquals(1, record.estimate.estimates.size)
        assertEquals(ResourceInspectionGap.entries.toSet(), record.gaps)
    }

    @Test fun `changed artifact runtime context and topology cannot establish applicability by matching IDs`() {
        val original = inspected()
        val changed = build()
        selection(changed, "sel_model").put("catalog_item_version", "2026.10-other")
        (selection(changed, "sel_model")["settings"] as ObjectNode).put("precision", "fp32")
        (selection(changed, "sel_context_policy")["settings"] as ObjectNode).put("requested_context_tokens", 8192)
        (selection(changed, "sel_accelerator")["settings"] as ObjectNode).put("device_count", 2)
        selection(changed, "sel_inference_engine").put("catalog_item_version", "2026.10-new-runtime")
        (changed["locks"] as ObjectNode).put("catalog_snapshot_id", "catalog.other")
        val result = inspected(changed)
        assertEquals(original.buildId, result.buildId)
        assertEquals(original.buildRevision, result.buildRevision)
        assertEquals(original.identity.estimateSha256, result.identity.estimateSha256)
        assertNotEquals(original.identity.buildSha256, result.identity.buildSha256)
        assertEquals(changed.toString(), result.recordedBuildJson)
        assertEquals(ResourceInspectionGap.entries.toSet(), result.gaps)
        assertTrue(ResourceInspectionGap.CATALOG_ARTIFACT_CONDITIONS_NOT_ESTABLISHED in result.gaps)
        assertTrue(ResourceInspectionGap.RUNTIME_CONTEXT_TOPOLOGY_APPLICABILITY_NOT_ESTABLISHED in result.gaps)
    }

    @Test fun `declared verified confidence old time and mismatched units never become measured approval`() {
        val input = estimate().put("generated_at", "2000-01-01T00:00:00Z")
        val first = entry(input)
        first.put("confidence", "verified").put("metric", "training.estimated_gpu_hours")
            .put("unit", "tokens_per_second").put("source_uri", "file:///synthetic/resource")
        val inputBuild = build()
        (inputBuild["policy"] as ObjectNode).put("min_resource_confidence", "verified")
        val record = inspected(inputBuild, input)
        assertEquals("verified", record.declaredMinimumConfidence)
        assertEquals("2000-01-01T00:00:00Z", record.estimate.generatedAt)
        assertEquals(ResourceConfidence.VERIFIED, record.estimate.estimates.first().confidence)
        assertEquals(ResourceMetric.GPU_HOURS, record.estimate.estimates.first().metric)
        assertEquals(ResourceUnit.TOKENS_PER_SECOND, record.estimate.estimates.first().unit)
        assertEquals("file:///synthetic/resource", record.estimate.estimates.first().sourceUri)
        assertEquals(ResourceInspectionGap.entries.toSet(), record.gaps)
        assertTrue(ResourceInspectionGap.FRESHNESS_NOT_ESTABLISHED in record.gaps)
        assertTrue(ResourceInspectionGap.ESTIMATOR_EVIDENCE_NOT_ESTABLISHED in record.gaps)
        assertTrue(ResourceInspectionGap.METRIC_UNIT_RELATIONSHIP_NOT_ESTABLISHED in record.gaps)
    }

    @Test fun `decimal spelling duplicate rows assumptions and absent source remain exact`() {
        val input = estimate()
        val first = entry(input)
        first.put("value", "9007199254740993.123456789012000\n")
        rejected(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE, inspect(estimate = input))
        first.put("value", "9007199254740993.123456789000\n")
        first.set<ArrayNode>("assumptions", input.arrayNode().add("").add("same").add("same").add("한글\nالعربية"))
        (input["estimates"] as ArrayNode).add(first.deepCopy())
        val record = inspected(estimate = input)
        assertEquals(3, record.estimate.estimates.size)
        assertEquals("9007199254740993.123456789000\n", record.estimate.estimates.first().value)
        assertEquals(record.estimate.estimates.first().value, record.estimate.estimates.last().value)
        assertEquals(listOf("", "same", "same", "한글\nالعربية"), record.estimate.estimates.first().assumptions)
        assertNull(record.estimate.estimates.first().sourceUri)
        assertFalse(parse(record.estimate.json.toByteArray())["estimates"][0].has("source_uri"))
        assertEquals(input, parse(record.estimate.json.toByteArray()))
    }

    @Test fun `input identity covers every consumed byte and distinguishes layout without changing values`() {
        val buildBytes = example("build")
        val estimateBytes = example("resource-estimate")
        fun read(b: ByteArray, e: ByteArray) =
            (inspector.inspect(b.inputStream(), e.inputStream()) as ResourceInspection.Inspected).record
        val first = read(buildBytes, estimateBytes)
        val replay = read(buildBytes, estimateBytes)
        assertEquals(first.identity, replay.identity)
        val spacedBuild = "\n\t ".toByteArray() + buildBytes + "\n \t".toByteArray()
        val spacedEstimate = " \n".toByteArray() + estimateBytes + "\t \n".toByteArray()
        val spaced = read(spacedBuild, spacedEstimate)
        assertEquals(digest(spacedBuild), spaced.identity.buildSha256)
        assertEquals(digest(spacedEstimate), spaced.identity.estimateSha256)
        assertNotEquals(first.identity.buildSha256, spaced.identity.buildSha256)
        assertNotEquals(first.identity.estimateSha256, spaced.identity.estimateSha256)
        assertEquals(first.recordedBuildJson, spaced.recordedBuildJson)
        assertEquals(first.estimate.json, spaced.estimate.json)
    }

    @Test fun `snapshots and nested collections stay immutable after callers alter their inputs`() {
        val inputBuild = build()
        val inputEstimate = estimate()
        val rawBuild = bytes(inputBuild)
        val rawEstimate = bytes(inputEstimate)
        val record = (inspector.inspect(rawBuild.inputStream(), rawEstimate.inputStream())
            as ResourceInspection.Inspected).record
        val retainedBuild = record.recordedBuildJson
        val retainedEstimate = record.estimate.json
        rawBuild.fill(0)
        rawEstimate.fill(0)
        inputBuild.put("name", "later caller edit")
        (inputBuild["selections"] as ArrayNode).removeAll()
        entry(inputEstimate).put("value", "999")
        assertEquals(retainedBuild, record.recordedBuildJson)
        assertEquals(retainedEstimate, record.estimate.json)
        assertEquals("FICTIONAL Full-Stack AI Build", record.buildName)
        assertEquals(29, record.selections.size)
        assertEquals("20.0", record.estimate.estimates.first().value)
        assertThrows(UnsupportedOperationException::class.java) { (record.selections as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (record.gaps as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (record.unreferencedActiveSelectionIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (record.estimate.estimates as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (record.estimate.estimates.first().selectionIds as MutableList).clear() }
    }

    @Test fun `invalid and future schemas preserve side specific typed failures`() {
        rejected(ResourceInspectionRejection.UNSUPPORTED_BUILD_SCHEMA_VERSION,
            inspect(build = build().put("schema_version", "2.0.0")))
        rejected(ResourceInspectionRejection.UNSUPPORTED_RESOURCE_SCHEMA_VERSION,
            inspect(estimate = estimate().put("schema_version", "2.0.0")))
        val missingBuild = build().also { it.remove("schema_version") }
        rejected(ResourceInspectionRejection.INVALID_BUILD, inspect(build = missingBuild))
        val wrongBuild = build().put("schema_version", 1)
        rejected(ResourceInspectionRejection.INVALID_BUILD, inspect(build = wrongBuild))
        rejected(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE,
            inspect(estimate = estimate().put("private_provider_error", "synthetic-private-provider-error")))
        val nullSource = estimate()
        entry(nullSource).putNull("source_uri")
        rejected(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE, inspect(estimate = nullSource))
        val withoutPolicy = build().also { it.remove("policy") }
        assertNull(inspected(withoutPolicy).declaredMinimumConfidence)
    }

    @Test fun `malformed bytes trailing objects and bounded input failures never inspect partial records`() {
        val malformed = listOf(
            "{".toByteArray(),
            "[]".toByteArray(),
            "{\"schema_version\":\"1.0.0\",\"schema_version\":\"2.0.0\"}".toByteArray(),
            byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d),
            " ".repeat(1_048_577).toByteArray(),
        )
        for (input in malformed + listOf(example("build") + "{}".toByteArray())) {
            val untouched = Tracked(example("resource-estimate"))
            rejected(ResourceInspectionRejection.INVALID_BUILD, inspector.inspect(input.inputStream(), untouched))
            assertEquals(0, untouched.readCalls)
            assertFalse(untouched.closed)
        }
        for (input in malformed + listOf(example("resource-estimate") + "{}".toByteArray())) {
            rejected(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE,
                inspector.inspect(example("build").inputStream(), input.inputStream()))
        }
    }

    @Test fun `streams remain caller owned with ordered reads and sanitized IO rejection`() {
        val order = mutableListOf<String>()
        val build = Tracked(example("build")) { order.add("build") }
        val estimate = Tracked(example("resource-estimate")) { order.add("estimate") }
        assertInstanceOf(ResourceInspection.Inspected::class.java, inspector.inspect(build, estimate))
        assertTrue(order.contains("build"))
        assertTrue(order.contains("estimate"))
        assertTrue(order.indexOfLast { it == "build" } < order.indexOfFirst { it == "estimate" })
        assertEquals(0, build.available())
        assertEquals(0, estimate.available())
        assertFalse(build.closed)
        assertFalse(estimate.closed)
        class Broken : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("synthetic-private-provider-error")
            override fun close() { closed = true }
        }
        val brokenBuild = Broken()
        val untouched = Tracked(example("resource-estimate"))
        val first = inspector.inspect(brokenBuild, untouched)
        rejected(ResourceInspectionRejection.INVALID_BUILD, first)
        assertEquals(0, untouched.readCalls)
        assertFalse(brokenBuild.closed)
        assertFalse(untouched.closed)
        val brokenEstimate = Broken()
        val completeBuild = Tracked(example("build"))
        val second = inspector.inspect(completeBuild, brokenEstimate)
        rejected(ResourceInspectionRejection.INVALID_RESOURCE_ESTIMATE, second)
        assertFalse(completeBuild.closed)
        assertFalse(brokenEstimate.closed)
        assertFalse(first.toString().contains("synthetic-private-provider-error"))
        assertFalse(second.toString().contains("synthetic-private-provider-error"))
    }

    private class Tracked(bytes: ByteArray, private val observed: () -> Unit = {}) : ByteArrayInputStream(bytes) {
        var readCalls = 0
        var closed = false
        override fun read(): Int { readCalls++; observed(); return super.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCalls++; observed(); return super.read(bytes, offset, length)
        }
        override fun close() { closed = true; super.close() }
    }
}
