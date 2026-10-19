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

/** Local raw input; null optional choices mean omitted keys, never a new wire sentinel. */
data class PreviewContextPolicyInput(
    val requestedContextTokens: String,
    val reservedOutputTokens: String,
    val truncationStrategy: String?,
    val kvCachePrecision: String?,
) {
    override fun toString() = "PreviewContextPolicyInput"
}

enum class PreviewSettingProblem { REQUIRED, INTEGER, MINIMUM, ENUM, TOO_LONG }
data class PreviewSettingIssue(val field: String, val problem: PreviewSettingProblem)

class PreviewContextPolicyPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewSettingIssue>,
) {
    val issues: List<PreviewSettingIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewContextPolicyPreparation"
}

/** Four-field CONFIGURE preparation. The existing whole-selection adapter remains authoritative. */
object PreviewContextPolicySettings {
    const val KIND = "context_policy"
    const val REQUESTED = "requested_context_tokens"
    const val RESERVED = "reserved_output_tokens"
    const val TRUNCATION = "truncation_strategy"
    const val PRECISION = "kv_cache_precision"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewContextPolicyInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewContextPolicyInput(settings[REQUESTED].toString(), settings[RESERVED].toString(),
            settings.get(TRUNCATION)?.textValue(), settings.get(PRECISION)?.textValue())
    }

    /** Runs in the existing preparation worker; never writes or supplies replacement CAS authority. */
    fun configure(selection: LocalSelection, input: PreviewContextPolicyInput): PreviewContextPolicyPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val issues = mutableListOf<PreviewSettingIssue>()
        val original = initial(selection)
        val settings = selection.settings
        for ((key, raw, retained) in listOf(Triple(REQUESTED, input.requestedContextTokens, original.requestedContextTokens),
            Triple(RESERVED, input.reservedOutputTokens, original.reservedOutputTokens))) {
            val definition = field(key)
            check(definition.type == PreviewSettingType.INTEGER && definition.required) { "Context integer metadata differs" }
            val value = integer(raw, definition, issues)
            // Retain an untouched admitted numeric node rather than narrowing or reformatting it.
            if (value != null && raw != retained) settings.set<JsonNode>(key, value)
        }
        for ((key, selected) in listOf(TRUNCATION to input.truncationStrategy, PRECISION to input.kvCachePrecision)) {
            val definition = field(key)
            check(definition.type == PreviewSettingType.STRING && !definition.required && definition.enumValues.isNotEmpty()) {
                "Context choice metadata differs"
            }
            if (selected == null) settings.remove(key)
            else if (selected !in definition.enumValues) issues += PreviewSettingIssue(key, PreviewSettingProblem.ENUM)
            else settings.put(key, selected)
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewContextPolicyPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewSettingIssue>) =
        PreviewContextPolicyPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun integer(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewSettingIssue>): JsonNode? {
        fun reject(problem: PreviewSettingProblem): JsonNode? {
            issues += PreviewSettingIssue(definition.key, problem)
            return null
        }
        if (raw.isBlank()) return reject(PreviewSettingProblem.REQUIRED)
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewSettingProblem.TOO_LONG)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewSettingProblem.INTEGER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewSettingProblem.TOO_LONG else PreviewSettingProblem.INTEGER)
        val objectValue = (parsed as JsonInputResult.Parsed).value
        val value = objectValue.get("value")
        // The object wrapper is only a parser transport. Extra/injected members are not field input.
        if (objectValue.size() != 1 || value?.isNumber != true) return reject(PreviewSettingProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewSettingProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewSettingProblem.MINIMUM)
        return value
    }
}
