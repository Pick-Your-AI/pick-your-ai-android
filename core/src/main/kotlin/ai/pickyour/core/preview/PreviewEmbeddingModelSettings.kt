package ai.pickyour.core.preview

import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.regex.Pattern

/** Null means absent. Supplied empty region is a literal valid string, distinct from absence. */
data class PreviewEmbeddingModelInput(
    val credentialRef: String? = null,
    val outputDimensions: String? = null,
    val region: String? = null,
) {
    override fun toString() = "PreviewEmbeddingModelInput"
}

enum class PreviewEmbeddingModelProblem { REQUIRED, INTEGER, RANGE, REFERENCE, TEXT, TOO_LONG }
data class PreviewEmbeddingModelIssue(val field: String, val problem: PreviewEmbeddingModelProblem) {
    override fun toString() = "PreviewEmbeddingModelIssue"
}
class PreviewEmbeddingModelPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewEmbeddingModelIssue>,
) {
    val issues: List<PreviewEmbeddingModelIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewEmbeddingModelPreparation"
}

/** Three optional frozen settings. The existing whole-Selection receiver still owns final admission. */
object PreviewEmbeddingModelSettings {
    const val KIND = "embedding_model"
    const val CREDENTIAL = "credential_ref"
    const val DIMENSIONS = "output_dimensions"
    const val REGION = "region"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val mapper = ObjectMapper()
    private val credentialPattern by lazy {
        // Match the frozen validator's find and final-LF semantics; never trim or resolve a reference.
        Pattern.compile(checkNotNull(field(CREDENTIAL).pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewEmbeddingModelInput {
        require(selection.kind == KIND)
        val value = selection.settings
        return PreviewEmbeddingModelInput(value.get(CREDENTIAL)?.textValue(),
            value.get(DIMENSIONS)?.toString(), value.get(REGION)?.textValue())
    }

    /** Preparation only: no write, remote operation, credential resolution or compatibility inference. */
    fun configure(selection: LocalSelection, input: PreviewEmbeddingModelInput): PreviewEmbeddingModelPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewEmbeddingModelIssue>()
        for ((key, raw) in listOf(CREDENTIAL to input.credentialRef, REGION to input.region)) {
            if (raw == null) settings.remove(key)
            else text(selection, key, raw, issues)?.let { settings.set<JsonNode>(key, it) }
        }
        if (input.outputDimensions == null) settings.remove(DIMENSIONS)
        else {
            val value = integer(input.outputDimensions, issues)
            // Untouched admitted numeric nodes retain exact scale/type, including integral decimal aliases.
            if (value != null && input.outputDimensions != original.outputDimensions) settings.set<JsonNode>(DIMENSIONS, value)
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewEmbeddingModelPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** New typed recovery retains only an absent or bounded frozen-pattern-valid opaque reference. */
    fun canRetainCredentialReference(raw: String?): Boolean =
        raw == null || raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS && credentialPattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewEmbeddingModelIssue>) =
        PreviewEmbeddingModelPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun integer(raw: String, issues: MutableList<PreviewEmbeddingModelIssue>): JsonNode? {
        fun reject(problem: PreviewEmbeddingModelProblem): JsonNode? {
            issues += PreviewEmbeddingModelIssue(DIMENSIONS, problem)
            return null
        }
        val definition = field(DIMENSIONS)
        check(!definition.required && definition.type == PreviewSettingType.INTEGER) { "Embedding numeric metadata differs" }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewEmbeddingModelProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewEmbeddingModelProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewEmbeddingModelProblem.INTEGER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewEmbeddingModelProblem.TOO_LONG else PreviewEmbeddingModelProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewEmbeddingModelProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewEmbeddingModelProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewEmbeddingModelProblem.RANGE)
        return value
    }

    private fun text(selection: LocalSelection, key: String, raw: String,
        issues: MutableList<PreviewEmbeddingModelIssue>): JsonNode? {
        fun reject(problem: PreviewEmbeddingModelProblem): JsonNode? {
            issues += PreviewEmbeddingModelIssue(key, problem)
            return null
        }
        val definition = field(key)
        check(!definition.required && definition.type == PreviewSettingType.STRING) { "Embedding text metadata differs" }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewEmbeddingModelProblem.TOO_LONG)
        val problem = if (key == CREDENTIAL) PreviewEmbeddingModelProblem.REFERENCE else PreviewEmbeddingModelProblem.TEXT
        if (key == CREDENTIAL) {
            if (raw.isBlank()) return reject(PreviewEmbeddingModelProblem.REQUIRED)
            if (!credentialPattern.matcher(raw).find()) return reject(problem)
        }
        // Region intentionally accepts supplied empty/whitespace text under its unchanged frozen string rule.
        val value = mapper.nodeFactory.textNode(raw)
        val oneField = mapper.createObjectNode().also { it.set<JsonNode>(key, value) }
        val accepted = try {
            LocalSelection.fromJson(selection.toJson().also { it.set<JsonNode>("settings", oneField) })
            true
        } catch (_: IllegalArgumentException) { false }
        return if (accepted) value else reject(problem)
    }
}
