package ai.pickyour.core.preview

import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Null means explicitly not supplied. A supplied empty value remains invalid correction input. */
data class PreviewInferenceInput(
    val tensorParallelSize: String? = null,
    val maxBatchTokens: String? = null,
    val precision: String? = null,
    val quantization: String? = null,
) {
    override fun toString() = "PreviewInferenceInput"
}

enum class PreviewInferenceProblem { REQUIRED, INTEGER, RANGE, ENUM, TOO_LONG }
data class PreviewInferenceIssue(val field: String, val problem: PreviewInferenceProblem) {
    override fun toString() = "PreviewInferenceIssue"
}
class PreviewInferencePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewInferenceIssue>,
) {
    val issues: List<PreviewInferenceIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewInferencePreparation"
}

/** Four optional frozen fields; the existing whole-selection adapter still owns final admission. */
object PreviewInferenceSettings {
    const val KIND = "inference_engine"
    const val TENSOR_PARALLEL = "tensor_parallel_size"
    const val MAX_BATCH = "max_batch_tokens"
    const val PRECISION = "precision"
    const val QUANTIZATION = "quantization"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewInferenceInput {
        require(selection.kind == KIND)
        val value = selection.settings
        return PreviewInferenceInput(value.get(TENSOR_PARALLEL)?.toString(), value.get(MAX_BATCH)?.toString(),
            value.get(PRECISION)?.textValue(), value.get(QUANTIZATION)?.textValue())
    }

    /** Preparation only: no write, catalog compatibility inference, performance claim or execution. */
    fun configure(selection: LocalSelection, input: PreviewInferenceInput): PreviewInferencePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewInferenceIssue>()
        for ((key, raw, retained) in listOf(Triple(TENSOR_PARALLEL, input.tensorParallelSize, original.tensorParallelSize),
            Triple(MAX_BATCH, input.maxBatchTokens, original.maxBatchTokens))) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.INTEGER) {
                "Inference numeric metadata differs"
            }
            if (raw == null) settings.remove(key)
            else {
                val value = number(raw, definition, issues)
                // Preserve the existing admitted node for untouched input, including integral decimal aliases.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        for ((key, value) in listOf(PRECISION to input.precision, QUANTIZATION to input.quantization)) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.STRING && definition.enumValues.isNotEmpty()) {
                "Inference choice metadata differs"
            }
            if (value == null) settings.remove(key)
            else if (value !in definition.enumValues) issues += PreviewInferenceIssue(key, PreviewInferenceProblem.ENUM)
            else settings.put(key, value)
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewInferencePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewInferenceIssue>) =
        PreviewInferencePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun number(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewInferenceIssue>): JsonNode? {
        fun reject(problem: PreviewInferenceProblem): JsonNode? {
            issues += PreviewInferenceIssue(definition.key, problem)
            return null
        }
        val syntax = PreviewInferenceProblem.INTEGER
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewInferenceProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewInferenceProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(syntax) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewInferenceProblem.TOO_LONG else syntax)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(syntax)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(syntax)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewInferenceProblem.RANGE)
        return value
    }

}
