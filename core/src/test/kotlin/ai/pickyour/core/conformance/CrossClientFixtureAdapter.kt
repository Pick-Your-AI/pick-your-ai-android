package ai.pickyour.core.conformance

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Test-only wire interface. Acceptance here is frozen schema admission, never an evaluator verdict. */
internal data class WireFixture(
    val id: String,
    val document: FrozenDocument,
    val payloadJSON: String,
    val payloadSHA256: String,
    val expected: Boolean,
)

internal data class WireObservation(
    val bundleVersion: String,
    val id: String,
    val accepted: Boolean,
    val preserved: Boolean?,
)

/** One byte-identical producer bundle in a lossless offline transport, not an Android fixture generator. */
internal object CrossClientFixtures {
    const val VERSION = "apple-cross-client-1"
    const val RAW_BYTES = 1_273_770
    const val RAW_SHA256 = "499fd9a01780ac42fa9bf2ab8cf9e1195db27321fec36040451b553a8b9ea5f9"
    const val PACKED_BYTES = 43_929
    const val PACKED_SHA256 = "c1e66aa954c20354ec607ceb8f90a90c599a8748ab10dcff17ce63fda8bf366c"

    private val documents = mapOf(
        "BuildSnapshot" to ("build.schema.json" to FrozenDocument.BUILD),
        "CatalogItem" to ("catalog-item.schema.json" to FrozenDocument.CATALOG_ITEM),
        "Selection" to ("selection.schema.json" to FrozenDocument.SELECTION),
        "EvaluationReport" to ("evaluation-report.schema.json" to FrozenDocument.EVALUATION_REPORT),
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun load(): List<WireFixture> = requireNotNull(javaClass.getResourceAsStream("/conformance/cross-client-v1.json.gz"))
        .use(::load)

    fun load(input: InputStream): List<WireFixture> {
        val packed = input.readNBytes(PACKED_BYTES + 1)
        require(packed.size == PACKED_BYTES && sha256(packed) == PACKED_SHA256) { "Fixture transport differs from its pin" }
        val bytes = GZIPInputStream(packed.inputStream()).use { it.readNBytes(RAW_BYTES + 1) }
        require(bytes.size == RAW_BYTES && sha256(bytes) == RAW_SHA256) { "Fixture bytes differ from the neutral producer" }
        // The enclosing fixture carries whole JSON payloads as strings. Its test protocol budget
        // is separate from every production reader's unchanged per-payload limits.
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = RAW_BYTES, maxStringLength = 65_536))
            .read(bytes.inputStream()) as? JsonInputResult.Parsed ?: error("Malformed pinned fixture protocol")
        val root = parsed.value
        require(root["bundleVersion"].textValue() == VERSION && root["contractVersion"].textValue() == "1.0.0")
        require(root["cases"].isArray && root["cases"].size() == 223)
        val cases = root["cases"].map { row ->
            require(row.isObject && row.fieldNames().asSequence().toSet() == setOf(
                "id", "contract", "schema", "payloadJSON", "payloadSHA256", "valid", "reason", "disposition"))
            val selected = documents[row["contract"].textValue()] ?: error("Unsupported fixture contract")
            require(row["schema"].textValue() == selected.first && row["valid"].isBoolean)
            require(row["id"].isTextual && row["payloadJSON"].isTextual && row["payloadSHA256"].isTextual)
            val payload = row["payloadJSON"].textValue()
            require(sha256(payload.toByteArray(Charsets.UTF_8)) == row["payloadSHA256"].textValue())
            WireFixture(row["id"].textValue(), selected.second, payload, row["payloadSHA256"].textValue(), row["valid"].booleanValue())
        }
        require(cases.map { it.id }.toSet().size == 223 && cases.count { it.expected } == 141)
        return cases
    }
}

/** Uses the existing Android core validator and exact JSON reader; no production adapter is replaced. */
internal class CrossClientFixtureAdapter {
    private val validator = FrozenSchemaValidator()
    private val reader = BoundedJsonReader()
    private val mapper = ObjectMapper()

    fun observe(fixture: WireFixture): WireObservation {
        val bytes = fixture.payloadJSON.toByteArray(Charsets.UTF_8)
        require(CrossClientFixtures.sha256(bytes) == fixture.payloadSHA256) { "Changed fixture payload" }
        val original = bytes.copyOf()
        val result = validator.validate(fixture.document, bytes.inputStream())
        val accepted = result is FrozenValidation.Valid
        val preserved = if (accepted) {
            val parsed = reader.read(bytes.inputStream()) as? JsonInputResult.Parsed
                ?: error("Accepted payload failed the existing strict reader")
            // A serialization/read failure is a harness failure, never an expected wire rejection.
            val encoded = mapper.writeValueAsBytes(parsed.value)
            val restored = reader.read(encoded.inputStream()) as? JsonInputResult.Parsed
                ?: error("Accepted payload could not round-trip")
            check(parsed.value == restored.value) { "Accepted typed JSON changed during round-trip" }
            true
        } else null
        check(bytes.contentEquals(original)) { "The consumer mutated original fixture bytes" }
        return WireObservation(CrossClientFixtures.VERSION, fixture.id, accepted, preserved)
    }
}
