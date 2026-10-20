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

/** Null means explicitly not supplied. A supplied empty value remains invalid correction input. */
data class PreviewModelInput(
    val temperature: String? = null,
    val topP: String? = null,
    val maxOutputTokens: String? = null,
    val precision: String? = null,
    val servingMode: String? = null,
    val credentialRef: String? = null,
    val weightsUri: String? = null,
) {
    override fun toString() = "PreviewModelInput"
}

enum class PreviewModelProblem { REQUIRED, NUMBER, INTEGER, RANGE, ENUM, REFERENCE, URI, TOO_LONG }
data class PreviewModelIssue(val field: String, val problem: PreviewModelProblem) {
    override fun toString() = "PreviewModelIssue"
}
class PreviewModelPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewModelIssue>,
) {
    val issues: List<PreviewModelIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewModelPreparation"
}

/** Seven optional frozen fields; the existing whole-selection adapter still owns final admission. */
object PreviewModelSettings {
    const val KIND = "model"
    const val TEMPERATURE = "temperature"
    const val TOP_P = "top_p"
    const val MAX_OUTPUT = "max_output_tokens"
    const val PRECISION = "precision"
    const val SERVING_MODE = "serving_mode"
    const val CREDENTIAL = "credential_ref"
    const val WEIGHTS = "weights_uri"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val mapper = ObjectMapper()
    private val credentialPattern by lazy {
        // Preserve the frozen validator's find and final-LF semantics, without trimming the reference.
        Pattern.compile(checkNotNull(field(CREDENTIAL).pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewModelInput {
        require(selection.kind == KIND)
        val value = selection.settings
        return PreviewModelInput(value.get(TEMPERATURE)?.toString(), value.get(TOP_P)?.toString(),
            value.get(MAX_OUTPUT)?.toString(), value.get(PRECISION)?.textValue(),
            value.get(SERVING_MODE)?.textValue(), value.get(CREDENTIAL)?.textValue(), value.get(WEIGHTS)?.textValue())
    }

    /** Preparation only: no write, catalog compatibility inference, credential resolution or URL opening. */
    fun configure(selection: LocalSelection, input: PreviewModelInput): PreviewModelPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewModelIssue>()
        for ((key, raw, retained) in listOf(Triple(TEMPERATURE, input.temperature, original.temperature),
            Triple(TOP_P, input.topP, original.topP), Triple(MAX_OUTPUT, input.maxOutputTokens, original.maxOutputTokens))) {
            val definition = field(key)
            check(!definition.required && definition.type in setOf(PreviewSettingType.NUMBER, PreviewSettingType.INTEGER)) {
                "Model numeric metadata differs"
            }
            if (raw == null) settings.remove(key)
            else {
                val value = number(raw, definition, issues)
                // Preserve the existing admitted node for untouched input, including integral decimal aliases.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        for ((key, value) in listOf(PRECISION to input.precision, SERVING_MODE to input.servingMode)) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.STRING && definition.enumValues.isNotEmpty()) {
                "Model choice metadata differs"
            }
            if (value == null) settings.remove(key)
            else if (value !in definition.enumValues) issues += PreviewModelIssue(key, PreviewModelProblem.ENUM)
            else settings.put(key, value)
        }
        for ((key, raw) in listOf(CREDENTIAL to input.credentialRef, WEIGHTS to input.weightsUri)) {
            if (raw == null) settings.remove(key)
            else text(selection, key, raw, issues)?.let { settings.set<JsonNode>(key, it) }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewModelPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** Bounded positive URI classification independent of any invalid numeric or enum field. */
    fun hasRecognizedWeightsUserinfo(raw: String?): Boolean =
        raw != null && raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS &&
            PreviewSelectionSensitiveInput.hasRecognizedUserinfo(KIND, mapper.createObjectNode().put(WEIGHTS, raw))

    /** Only new typed recovery uses this narrower credential-capture rule; old raw readers are unchanged. */
    fun canRetainCredentialReference(raw: String?): Boolean =
        raw == null || raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS && credentialPattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewModelIssue>) =
        PreviewModelPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun number(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewModelIssue>): JsonNode? {
        fun reject(problem: PreviewModelProblem): JsonNode? {
            issues += PreviewModelIssue(definition.key, problem)
            return null
        }
        val syntax = if (definition.type == PreviewSettingType.INTEGER) PreviewModelProblem.INTEGER else PreviewModelProblem.NUMBER
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewModelProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewModelProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(syntax) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewModelProblem.TOO_LONG else syntax)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(syntax)
        val exact = value.decimalValue()
        if (definition.type == PreviewSettingType.INTEGER && exact.stripTrailingZeros().scale() > 0) return reject(syntax)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewModelProblem.RANGE)
        return value
    }

    private fun text(selection: LocalSelection, key: String, raw: String,
        issues: MutableList<PreviewModelIssue>): JsonNode? {
        fun reject(problem: PreviewModelProblem): JsonNode? {
            issues += PreviewModelIssue(key, problem)
            return null
        }
        val definition = field(key)
        check(!definition.required && definition.type == PreviewSettingType.STRING) { "Model text metadata differs" }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewModelProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewModelProblem.REQUIRED)
        val problem = if (key == CREDENTIAL) PreviewModelProblem.REFERENCE else PreviewModelProblem.URI
        if (key == CREDENTIAL && !credentialPattern.matcher(raw).find()) return reject(problem)
        val value = mapper.nodeFactory.textNode(raw)
        // Check this field against its original frozen receiver for accurate, value-free inline feedback.
        // Other new fields can be invalid independently; no partial command leaves this method.
        val oneField = selection.settings.also { it.set<JsonNode>(key, value) }
        val accepted = try {
            LocalSelection.fromJson(selection.toJson().also { it.set<JsonNode>("settings", oneField) })
            key != WEIGHTS || PreviewSelectionSensitiveInput.allows(KIND, mapper.createObjectNode().put(WEIGHTS, raw))
        } catch (_: IllegalArgumentException) { false }
        return if (accepted) value else reject(problem)
    }
}
