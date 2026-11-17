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

/** Null omits the optional key. Supplied text is transient input, never an inferred measurement. */
data class PreviewMonitoringStackInput(
    val sampleRate: String? = null,
    val retentionDays: String? = null,
    val credentialRef: String? = null,
) {
    override fun toString() = "PreviewMonitoringStackInput"
}

enum class PreviewMonitoringStackProblem { REQUIRED, NUMBER, INTEGER, RANGE, REFERENCE, TOO_LONG }
data class PreviewMonitoringStackIssue(val field: String, val problem: PreviewMonitoringStackProblem) {
    override fun toString() = "PreviewMonitoringStackIssue"
}
class PreviewMonitoringStackPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewMonitoringStackIssue>,
) {
    val issues: List<PreviewMonitoringStackIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewMonitoringStackPreparation"
}

/** Frozen local settings only; no secret resolution, monitoring activation or coverage judgment. */
object PreviewMonitoringStackSettings {
    const val KIND = "monitoring_stack"
    const val SAMPLE = "sample_rate"
    const val RETENTION = "retention_days"
    const val CREDENTIAL = "credential_ref"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val credentialPattern by lazy {
        val definition = field(CREDENTIAL)
        check(!definition.required && definition.type == PreviewSettingType.STRING)
        // Match the frozen receiver's LF-only anchors without trimming or normalizing a reference.
        Pattern.compile(checkNotNull(definition.pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewMonitoringStackInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewMonitoringStackInput(settings.get(SAMPLE)?.toString(),
            settings.get(RETENTION)?.toString(), settings.get(CREDENTIAL)?.textValue())
    }

    /** Every field is prepared independently; only the exact final Selection can produce a command. */
    fun configure(selection: LocalSelection, input: PreviewMonitoringStackInput): PreviewMonitoringStackPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewMonitoringStackIssue>()
        for ((key, raw, retained) in listOf(Triple(SAMPLE, input.sampleRate, original.sampleRate),
            Triple(RETENTION, input.retentionDays, original.retentionDays))) {
            if (raw == null) settings.remove(key)
            else {
                val value = number(raw, field(key), issues)
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        val reference = input.credentialRef
        if (reference == null) settings.remove(CREDENTIAL)
        else if (reference.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) {
            issues += PreviewMonitoringStackIssue(CREDENTIAL, PreviewMonitoringStackProblem.TOO_LONG)
        } else if (!credentialPattern.matcher(reference).find()) {
            issues += PreviewMonitoringStackIssue(CREDENTIAL, PreviewMonitoringStackProblem.REFERENCE)
        } else settings.put(CREDENTIAL, reference)
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewMonitoringStackPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** Newly captured typed recovery never retains an unrecognized credential-shaped input. */
    fun canRetainCredentialReference(raw: String?): Boolean =
        raw == null || raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS &&
            credentialPattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewMonitoringStackIssue>) =
        PreviewMonitoringStackPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun number(raw: String, definition: PreviewSettingField,
        issues: MutableList<PreviewMonitoringStackIssue>): JsonNode? {
        check(!definition.required && definition.type in setOf(PreviewSettingType.NUMBER, PreviewSettingType.INTEGER))
        val invalidNumber = if (definition.type == PreviewSettingType.INTEGER)
            PreviewMonitoringStackProblem.INTEGER else PreviewMonitoringStackProblem.NUMBER
        fun reject(problem: PreviewMonitoringStackProblem): JsonNode? {
            issues += PreviewMonitoringStackIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewMonitoringStackProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewMonitoringStackProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(invalidNumber) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewMonitoringStackProblem.TOO_LONG else invalidNumber)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(invalidNumber)
        val exact = value.decimalValue()
        if (definition.type == PreviewSettingType.INTEGER && exact.stripTrailingZeros().scale() > 0)
            return reject(PreviewMonitoringStackProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewMonitoringStackProblem.RANGE)
        return value
    }
}
