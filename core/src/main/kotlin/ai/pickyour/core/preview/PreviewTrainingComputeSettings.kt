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

/** Null is absent; supplied numeric zero and false remain explicit values. */
data class PreviewTrainingComputeInput(
    val gpuHoursBudget: String? = null,
    val checkpointIntervalMinutes: String? = null,
    val spotAllowed: Boolean? = null,
) {
    override fun toString() = "PreviewTrainingComputeInput"
}

enum class PreviewTrainingComputeProblem { REQUIRED, NUMBER, INTEGER, RANGE, TOO_LONG }
data class PreviewTrainingComputeIssue(val field: String, val problem: PreviewTrainingComputeProblem) {
    override fun toString() = "PreviewTrainingComputeIssue"
}
class PreviewTrainingComputePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewTrainingComputeIssue>,
) {
    val issues: List<PreviewTrainingComputeIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewTrainingComputePreparation"
}

/** Three frozen planning settings only; no training execution, capacity or price is established. */
object PreviewTrainingComputeSettings {
    const val KIND = "training_compute"
    const val GPU_HOURS_BUDGET = "gpu_hours_budget"
    const val CHECKPOINT_INTERVAL_MINUTES = "checkpoint_interval_minutes"
    const val SPOT_ALLOWED = "spot_allowed"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewTrainingComputeInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewTrainingComputeInput(settings.get(GPU_HOURS_BUDGET)?.toString(),
            settings.get(CHECKPOINT_INTERVAL_MINUTES)?.toString(), settings.get(SPOT_ALLOWED)?.booleanValue())
    }

    /** Independent field feedback precedes admission of the complete prospective Selection. */
    fun configure(selection: LocalSelection, input: PreviewTrainingComputeInput): PreviewTrainingComputePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewTrainingComputeIssue>()
        for ((key, raw, retained) in listOf(Triple(GPU_HOURS_BUDGET, input.gpuHoursBudget, original.gpuHoursBudget),
            Triple(CHECKPOINT_INTERVAL_MINUTES, input.checkpointIntervalMinutes, original.checkpointIntervalMinutes))) {
            val definition = field(key)
            val expectedType = if (key == GPU_HOURS_BUDGET) PreviewSettingType.NUMBER else PreviewSettingType.INTEGER
            check(!definition.required && definition.type == expectedType) {
                "Training numeric metadata differs"
            }
            if (raw == null) settings.remove(key)
            else number(raw, definition, issues)?.let { value ->
                // Retain an untouched admitted numeric node, including its scale and integral aliases.
                if (raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        val spot = field(SPOT_ALLOWED)
        check(!spot.required && spot.type == PreviewSettingType.BOOLEAN) { "Training spot metadata differs" }
        if (input.spotAllowed == null) settings.remove(SPOT_ALLOWED)
        else settings.put(SPOT_ALLOWED, input.spotAllowed)
        if (issues.isNotEmpty()) return invalid(issues)
        // Retained notes, extensions and sibling settings share the existing final parser/admission budgets.
        return PreviewTrainingComputePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewTrainingComputeIssue>) =
        PreviewTrainingComputePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun number(raw: String, definition: PreviewSettingField,
        issues: MutableList<PreviewTrainingComputeIssue>): JsonNode? {
        fun reject(problem: PreviewTrainingComputeProblem): JsonNode? {
            issues += PreviewTrainingComputeIssue(definition.key, problem)
            return null
        }
        val syntax = if (definition.type == PreviewSettingType.INTEGER) PreviewTrainingComputeProblem.INTEGER
            else PreviewTrainingComputeProblem.NUMBER
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewTrainingComputeProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewTrainingComputeProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(syntax) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewTrainingComputeProblem.TOO_LONG else syntax)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        // A numeric field cannot inject another member or a second JSON document.
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(syntax)
        val exact = value.decimalValue()
        if (definition.type == PreviewSettingType.INTEGER && exact.stripTrailingZeros().scale() > 0)
            return reject(PreviewTrainingComputeProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewTrainingComputeProblem.RANGE)
        return value
    }
}
