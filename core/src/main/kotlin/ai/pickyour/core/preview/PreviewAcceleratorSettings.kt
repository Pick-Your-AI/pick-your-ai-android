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

/** Required device text has no default; null groups means explicitly not supplied. */
data class PreviewAcceleratorInput(val deviceCount: String, val tensorParallelGroups: String? = null) {
    override fun toString() = "PreviewAcceleratorInput"
}

enum class PreviewAcceleratorProblem { REQUIRED, INTEGER, RANGE, TOO_LONG }
data class PreviewAcceleratorIssue(val field: String, val problem: PreviewAcceleratorProblem) {
    override fun toString() = "PreviewAcceleratorIssue"
}
class PreviewAcceleratorPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewAcceleratorIssue>,
) {
    val issues: List<PreviewAcceleratorIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewAcceleratorPreparation"
}

/** Two frozen settings; whole-selection validation and managed writes retain their authority. */
object PreviewAcceleratorSettings {
    const val KIND = "accelerator"
    const val DEVICES = "device_count"
    const val GROUPS = "tensor_parallel_groups"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))

    fun initial(selection: LocalSelection): PreviewAcceleratorInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewAcceleratorInput(settings[DEVICES].toString(), settings.get(GROUPS)?.toString())
    }

    /** Preparation only: no hardware verification, relationship inference or execution. */
    fun configure(selection: LocalSelection, input: PreviewAcceleratorInput): PreviewAcceleratorPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewAcceleratorIssue>()
        for ((key, raw, retained) in listOf(Triple(DEVICES, input.deviceCount, original.deviceCount),
            Triple(GROUPS, input.tensorParallelGroups, original.tensorParallelGroups))) {
            val definition = fields.single { it.key == key }
            check(definition.type == PreviewSettingType.INTEGER && definition.required == (key == DEVICES)) {
                "Accelerator integer metadata differs"
            }
            if (raw == null) {
                check(!definition.required)
                settings.remove(key)
            } else {
                val value = integer(raw, definition, issues)
                // Retain untouched admitted nodes, including integral decimal aliases and exact scale.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewAcceleratorPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewAcceleratorIssue>) =
        PreviewAcceleratorPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun integer(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewAcceleratorIssue>): JsonNode? {
        fun reject(problem: PreviewAcceleratorProblem): JsonNode? {
            issues += PreviewAcceleratorIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewAcceleratorProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewAcceleratorProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewAcceleratorProblem.INTEGER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewAcceleratorProblem.TOO_LONG else PreviewAcceleratorProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewAcceleratorProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewAcceleratorProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewAcceleratorProblem.RANGE)
        return value
    }
}
