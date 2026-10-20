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

/** Per-selection assumptions. Null means an omitted key, not zero or a new wire sentinel. */
data class PreviewWorkloadSelectionInput(
    val concurrency: String? = null,
    val requestsPerDay: String? = null,
    val averageInputTokens: String? = null,
    val averageOutputTokens: String? = null,
    val latencyBudgetMs: String? = null,
) {
    override fun toString() = "PreviewWorkloadSelectionInput"
}

enum class PreviewWorkloadSelectionProblem { REQUIRED, INTEGER, RANGE, TOO_LONG }
data class PreviewWorkloadSelectionIssue(val field: String, val problem: PreviewWorkloadSelectionProblem) {
    override fun toString() = "PreviewWorkloadSelectionIssue"
}
class PreviewWorkloadSelectionPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewWorkloadSelectionIssue>,
) {
    val issues: List<PreviewWorkloadSelectionIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewWorkloadSelectionPreparation"
}

/** Frozen workload Selection settings only; this does not edit the overall workload profile. */
object PreviewWorkloadSelectionSettings {
    const val KIND = "workload"
    const val CONCURRENCY = "concurrency"
    const val REQUESTS = "requests_per_day"
    const val INPUT = "avg_input_tokens"
    const val OUTPUT = "avg_output_tokens"
    const val LATENCY = "latency_budget_ms"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewWorkloadSelectionInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewWorkloadSelectionInput(settings.get(CONCURRENCY)?.toString(), settings.get(REQUESTS)?.toString(),
            settings.get(INPUT)?.toString(), settings.get(OUTPUT)?.toString(), settings.get(LATENCY)?.toString())
    }

    /** No partial command or write; final admission remains the existing whole-selection adapter. */
    fun configure(selection: LocalSelection, input: PreviewWorkloadSelectionInput): PreviewWorkloadSelectionPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewWorkloadSelectionIssue>()
        val entries = listOf(Triple(CONCURRENCY, input.concurrency, original.concurrency),
            Triple(REQUESTS, input.requestsPerDay, original.requestsPerDay),
            Triple(INPUT, input.averageInputTokens, original.averageInputTokens),
            Triple(OUTPUT, input.averageOutputTokens, original.averageOutputTokens),
            Triple(LATENCY, input.latencyBudgetMs, original.latencyBudgetMs))
        for ((key, raw, retained) in entries) {
            val definition = field(key)
            check(definition.type == PreviewSettingType.INTEGER && !definition.required) { "Workload integer metadata differs" }
            if (raw == null) settings.remove(key)
            else {
                val value = integer(raw, definition, issues)
                // An unchanged admitted node keeps its exact scale and numeric representation.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewWorkloadSelectionPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewWorkloadSelectionIssue>) =
        PreviewWorkloadSelectionPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun integer(raw: String, definition: PreviewSettingField,
        issues: MutableList<PreviewWorkloadSelectionIssue>): JsonNode? {
        fun reject(problem: PreviewWorkloadSelectionProblem): JsonNode? {
            issues += PreviewWorkloadSelectionIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewWorkloadSelectionProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewWorkloadSelectionProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewWorkloadSelectionProblem.INTEGER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewWorkloadSelectionProblem.TOO_LONG else PreviewWorkloadSelectionProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewWorkloadSelectionProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewWorkloadSelectionProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewWorkloadSelectionProblem.RANGE)
        return value
    }
}
