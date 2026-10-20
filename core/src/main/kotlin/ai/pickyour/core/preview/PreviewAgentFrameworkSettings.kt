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

/** Null is absent. Supplied numeric text needs admission and never supplies an inferred default. */
data class PreviewAgentFrameworkInput(
    val agentCount: String? = null,
    val maxIterations: String? = null,
    val timeoutMs: String? = null,
) {
    override fun toString() = "PreviewAgentFrameworkInput"
}

enum class PreviewAgentFrameworkProblem { REQUIRED, INTEGER, RANGE, TOO_LONG }
data class PreviewAgentFrameworkIssue(val field: String, val problem: PreviewAgentFrameworkProblem) {
    override fun toString() = "PreviewAgentFrameworkIssue"
}
class PreviewAgentFrameworkPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewAgentFrameworkIssue>,
) {
    val issues: List<PreviewAgentFrameworkIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewAgentFrameworkPreparation"
}

/** Frozen structural fields only; no agent execution, defaults, ceilings or cross-field inference. */
object PreviewAgentFrameworkSettings {
    const val KIND = "agent_framework"
    const val AGENT_COUNT = "agent_count"
    const val MAX_ITERATIONS = "max_iterations"
    const val TIMEOUT_MS = "timeout_ms"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewAgentFrameworkInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewAgentFrameworkInput(settings.get(AGENT_COUNT)?.toString(), settings.get(MAX_ITERATIONS)?.toString(),
            settings.get(TIMEOUT_MS)?.toString())
    }

    /** Preparation has no storage authority. The existing adapter admits and saves the complete selection. */
    fun configure(selection: LocalSelection, input: PreviewAgentFrameworkInput): PreviewAgentFrameworkPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewAgentFrameworkIssue>()
        for ((key, raw, retained) in listOf(Triple(AGENT_COUNT, input.agentCount, original.agentCount),
            Triple(MAX_ITERATIONS, input.maxIterations, original.maxIterations),
            Triple(TIMEOUT_MS, input.timeoutMs, original.timeoutMs))) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.INTEGER) {
                "Agent framework numeric metadata differs"
            }
            if (raw == null) settings.remove(key)
            else {
                val value = number(raw, definition, issues)
                // Keep the admitted node, including scale and integer aliases, when the control is untouched.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewAgentFrameworkPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewAgentFrameworkIssue>) =
        PreviewAgentFrameworkPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun utf8(raw: String): ByteArray? = try {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) { null }

    private fun number(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewAgentFrameworkIssue>): JsonNode? {
        fun reject(problem: PreviewAgentFrameworkProblem): JsonNode? {
            issues += PreviewAgentFrameworkIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewAgentFrameworkProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewAgentFrameworkProblem.REQUIRED)
        val bytes = utf8("{\"value\":" + raw + "}") ?: return reject(PreviewAgentFrameworkProblem.INTEGER)
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewAgentFrameworkProblem.TOO_LONG else PreviewAgentFrameworkProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewAgentFrameworkProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewAgentFrameworkProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewAgentFrameworkProblem.RANGE)
        return value
    }
}
