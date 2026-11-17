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

/** Null is absent. Supplied numeric text needs admission; false is a supplied Boolean value. */
data class PreviewRagPipelineInput(
    val topK: String? = null,
    val maxChunks: String? = null,
    val rerankEnabled: Boolean? = null,
) {
    override fun toString() = "PreviewRagPipelineInput"
}

enum class PreviewRagPipelineProblem { REQUIRED, INTEGER, RANGE, TOO_LONG }
data class PreviewRagPipelineIssue(val field: String, val problem: PreviewRagPipelineProblem) {
    override fun toString() = "PreviewRagPipelineIssue"
}
class PreviewRagPipelinePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewRagPipelineIssue>,
) {
    val issues: List<PreviewRagPipelineIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewRagPipelinePreparation"
}

/** Frozen structural fields only; no retrieval execution, defaults, ceilings or cross-field inference. */
object PreviewRagPipelineSettings {
    const val KIND = "rag_pipeline"
    const val TOP_K = "top_k"
    const val MAX_CHUNKS = "max_chunks"
    const val RERANK_ENABLED = "rerank_enabled"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewRagPipelineInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewRagPipelineInput(settings.get(TOP_K)?.toString(), settings.get(MAX_CHUNKS)?.toString(),
            settings.get(RERANK_ENABLED)?.booleanValue())
    }

    /** Preparation has no storage authority. The existing adapter admits and saves the complete selection. */
    fun configure(selection: LocalSelection, input: PreviewRagPipelineInput): PreviewRagPipelinePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewRagPipelineIssue>()
        for ((key, raw, retained) in listOf(Triple(TOP_K, input.topK, original.topK),
            Triple(MAX_CHUNKS, input.maxChunks, original.maxChunks))) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.INTEGER) {
                "RAG pipeline numeric metadata differs"
            }
            if (raw == null) settings.remove(key)
            else {
                val value = number(raw, definition, issues)
                // Keep the admitted node, including scale and integer aliases, when the control is untouched.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        val rerank = field(RERANK_ENABLED)
        check(!rerank.required && rerank.type == PreviewSettingType.BOOLEAN) { "RAG pipeline Boolean metadata differs" }
        if (input.rerankEnabled == null) settings.remove(RERANK_ENABLED)
        else settings.put(RERANK_ENABLED, input.rerankEnabled)
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewRagPipelinePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewRagPipelineIssue>) =
        PreviewRagPipelinePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun utf8(raw: String): ByteArray? = try {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) { null }

    private fun number(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewRagPipelineIssue>): JsonNode? {
        fun reject(problem: PreviewRagPipelineProblem): JsonNode? {
            issues += PreviewRagPipelineIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewRagPipelineProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewRagPipelineProblem.REQUIRED)
        val bytes = utf8("{\"value\":" + raw + "}") ?: return reject(PreviewRagPipelineProblem.INTEGER)
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewRagPipelineProblem.TOO_LONG else PreviewRagPipelineProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewRagPipelineProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewRagPipelineProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewRagPipelineProblem.RANGE)
        return value
    }
}
