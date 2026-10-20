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

/** Null is absent. A supplied empty region is literal text; a supplied empty number needs correction. */
data class PreviewComputeInput(
    val instances: String,
    val reservedHoursPerMonth: String? = null,
    val region: String? = null,
) {
    override fun toString() = "PreviewComputeInput"
}

enum class PreviewComputeProblem { REQUIRED, NUMBER, INTEGER, RANGE, TEXT, TOO_LONG }
data class PreviewComputeIssue(val field: String, val problem: PreviewComputeProblem) {
    override fun toString() = "PreviewComputeIssue"
}
class PreviewComputePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewComputeIssue>,
) {
    val issues: List<PreviewComputeIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewComputePreparation"
}

/** Frozen structural fields only; no calendar, billing, regional availability or capacity inference. */
object PreviewComputeSettings {
    const val KIND = "compute_profile"
    const val INSTANCES = "instances"
    const val RESERVED_HOURS = "reserved_hours_per_month"
    const val REGION = "region"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewComputeInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewComputeInput(settings[INSTANCES].toString(), settings.get(RESERVED_HOURS)?.toString(),
            settings.get(REGION)?.textValue())
    }

    /** Preparation has no storage authority. The existing adapter admits and saves the complete selection. */
    fun configure(selection: LocalSelection, input: PreviewComputeInput): PreviewComputePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewComputeIssue>()
        for ((key, raw, retained) in listOf(Triple(INSTANCES, input.instances, original.instances),
            Triple(RESERVED_HOURS, input.reservedHoursPerMonth, original.reservedHoursPerMonth))) {
            val definition = field(key)
            check(definition.required == (key == INSTANCES) &&
                definition.type == if (key == INSTANCES) PreviewSettingType.INTEGER else PreviewSettingType.NUMBER) {
                "Compute numeric metadata differs"
            }
            if (raw == null) settings.remove(key)
            else {
                val value = number(raw, definition, issues)
                // Keep the admitted node, including scale and integer aliases, when the control is untouched.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        val region = field(REGION)
        check(!region.required && region.type == PreviewSettingType.STRING) { "Compute region metadata differs" }
        when {
            input.region == null -> settings.remove(REGION)
            input.region.length > PreviewSelectionForms.MAX_SETTINGS_TEXT ->
                issues.add(PreviewComputeIssue(REGION, PreviewComputeProblem.TOO_LONG))
            utf8(input.region) == null -> issues.add(PreviewComputeIssue(REGION, PreviewComputeProblem.TEXT))
            else -> settings.put(REGION, input.region)
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewComputePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewComputeIssue>) =
        PreviewComputePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun utf8(raw: String): ByteArray? = try {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) { null }

    private fun number(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewComputeIssue>): JsonNode? {
        fun reject(problem: PreviewComputeProblem): JsonNode? {
            issues += PreviewComputeIssue(definition.key, problem)
            return null
        }
        val syntax = if (definition.type == PreviewSettingType.INTEGER) PreviewComputeProblem.INTEGER else PreviewComputeProblem.NUMBER
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewComputeProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewComputeProblem.REQUIRED)
        val bytes = utf8("{\"value\":" + raw + "}") ?: return reject(syntax)
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewComputeProblem.TOO_LONG else syntax)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(syntax)
        val exact = value.decimalValue()
        if (definition.type == PreviewSettingType.INTEGER && exact.stripTrailingZeros().scale() > 0) return reject(syntax)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewComputeProblem.RANGE)
        return value
    }
}
