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
import java.util.regex.Pattern

/** Null is absent; false is a supplied setting, independent of the outer Selection.enabled flag. */
data class PreviewDataSourceInput(
    val connectorSecretRef: String? = null,
    val refreshMinutes: String? = null,
    val enabled: Boolean? = null,
) {
    override fun toString() = "PreviewDataSourceInput"
}

enum class PreviewDataSourceProblem { REQUIRED, INTEGER, RANGE, REFERENCE, TOO_LONG }
data class PreviewDataSourceIssue(val field: String, val problem: PreviewDataSourceProblem) {
    override fun toString() = "PreviewDataSourceIssue"
}
class PreviewDataSourcePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewDataSourceIssue>,
) {
    val issues: List<PreviewDataSourceIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewDataSourcePreparation"
}

/** Three frozen structural settings; no credential resolution, refresh job or connector execution. */
object PreviewDataSourceSettings {
    const val KIND = "data_source"
    const val CONNECTOR_SECRET_REF = "connector_secret_ref"
    const val REFRESH_MINUTES = "refresh_minutes"
    const val ENABLED = "enabled"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val referencePattern by lazy {
        val definition = field(CONNECTOR_SECRET_REF)
        check(!definition.required && definition.type == PreviewSettingType.STRING)
        // Same find/final-LF handling as the frozen validator; never trim or resolve the reference.
        Pattern.compile(checkNotNull(definition.pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewDataSourceInput {
        require(selection.kind == KIND)
        val value = selection.settings
        return PreviewDataSourceInput(value.get(CONNECTOR_SECRET_REF)?.textValue(),
            value.get(REFRESH_MINUTES)?.toString(), value.get(ENABLED)?.booleanValue())
    }

    fun configure(selection: LocalSelection, input: PreviewDataSourceInput): PreviewDataSourcePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewDataSourceIssue>()
        val reference = input.connectorSecretRef
        when {
            reference == null -> settings.remove(CONNECTOR_SECRET_REF)
            reference.length > PreviewSelectionForms.MAX_SETTINGS_TEXT ->
                issues.add(PreviewDataSourceIssue(CONNECTOR_SECRET_REF, PreviewDataSourceProblem.TOO_LONG))
            !referencePattern.matcher(reference).find() ->
                issues.add(PreviewDataSourceIssue(CONNECTOR_SECRET_REF, PreviewDataSourceProblem.REFERENCE))
            else -> settings.put(CONNECTOR_SECRET_REF, reference)
        }
        if (input.refreshMinutes == null) settings.remove(REFRESH_MINUTES)
        else number(input.refreshMinutes, issues)?.let {
            // Untouched admitted integral decimal/exponent nodes retain their original scale and type.
            if (input.refreshMinutes != original.refreshMinutes) settings.set<JsonNode>(REFRESH_MINUTES, it)
        }
        val enabled = field(ENABLED)
        check(!enabled.required && enabled.type == PreviewSettingType.BOOLEAN)
        if (input.enabled == null) settings.remove(ENABLED) else settings.put(ENABLED, input.enabled)
        if (issues.isNotEmpty()) return invalid(issues)
        // Admission covers the final full Selection, including retained notes/extensions and parser budgets.
        return PreviewDataSourcePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** New typed recovery captures only an opaque reference; old raw checkpoints keep their own reader. */
    fun canRetainConnectorReference(raw: String?): Boolean = raw == null ||
        raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS && referencePattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewDataSourceIssue>) =
        PreviewDataSourcePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun number(raw: String, issues: MutableList<PreviewDataSourceIssue>): JsonNode? {
        fun reject(problem: PreviewDataSourceProblem): JsonNode? {
            issues += PreviewDataSourceIssue(REFRESH_MINUTES, problem)
            return null
        }
        val definition = field(REFRESH_MINUTES)
        check(!definition.required && definition.type == PreviewSettingType.INTEGER)
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewDataSourceProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewDataSourceProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewDataSourceProblem.INTEGER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewDataSourceProblem.TOO_LONG else PreviewDataSourceProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true || value.decimalValue().stripTrailingZeros().scale() > 0)
            return reject(PreviewDataSourceProblem.INTEGER)
        val exact = value.decimalValue()
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewDataSourceProblem.RANGE)
        return value
    }
}
