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

/** Null is absent. Supplied empty numeric text needs correction; false is an explicit declaration. */
data class PreviewKnowledgeBaseInput(
    val chunkSizeTokens: String? = null,
    val chunkOverlapTokens: String? = null,
    val refreshMinutes: String? = null,
    val aclEnforced: Boolean? = null,
) {
    override fun toString() = "PreviewKnowledgeBaseInput"
}

enum class PreviewKnowledgeBaseProblem { REQUIRED, INTEGER, RANGE, TOO_LONG }
data class PreviewKnowledgeBaseIssue(val field: String, val problem: PreviewKnowledgeBaseProblem) {
    override fun toString() = "PreviewKnowledgeBaseIssue"
}
class PreviewKnowledgeBasePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewKnowledgeBaseIssue>,
) {
    val issues: List<PreviewKnowledgeBaseIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewKnowledgeBasePreparation"
}

/** Frozen structural fields only; no overlap relationship, service, refresh or ACL enforcement inference. */
object PreviewKnowledgeBaseSettings {
    const val KIND = "knowledge_base"
    const val CHUNK_SIZE = "chunk_size_tokens"
    const val CHUNK_OVERLAP = "chunk_overlap_tokens"
    const val REFRESH = "refresh_minutes"
    const val ACL = "acl_enforced"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewKnowledgeBaseInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewKnowledgeBaseInput(settings.get(CHUNK_SIZE)?.toString(), settings.get(CHUNK_OVERLAP)?.toString(),
            settings.get(REFRESH)?.toString(), settings.get(ACL)?.booleanValue())
    }

    /** Preparation has no storage authority; existing whole-selection admission and managed saving still apply. */
    fun configure(selection: LocalSelection, input: PreviewKnowledgeBaseInput): PreviewKnowledgeBasePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewKnowledgeBaseIssue>()
        for ((key, raw, retained) in listOf(Triple(CHUNK_SIZE, input.chunkSizeTokens, original.chunkSizeTokens),
            Triple(CHUNK_OVERLAP, input.chunkOverlapTokens, original.chunkOverlapTokens),
            Triple(REFRESH, input.refreshMinutes, original.refreshMinutes))) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.INTEGER) { "Knowledge-base numeric metadata differs" }
            if (raw == null) settings.remove(key)
            else {
                val value = integer(raw, definition, issues)
                // Keep admitted node types and scale when the displayed number has not changed.
                if (value != null && raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        val acl = field(ACL)
        check(!acl.required && acl.type == PreviewSettingType.BOOLEAN) { "Knowledge-base ACL metadata differs" }
        if (input.aclEnforced == null) settings.remove(ACL) else settings.put(ACL, input.aclEnforced)
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewKnowledgeBasePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewKnowledgeBaseIssue>) =
        PreviewKnowledgeBasePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun utf8(raw: String): ByteArray? = try {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) { null }

    private fun integer(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewKnowledgeBaseIssue>): JsonNode? {
        fun reject(problem: PreviewKnowledgeBaseProblem): JsonNode? {
            issues += PreviewKnowledgeBaseIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewKnowledgeBaseProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewKnowledgeBaseProblem.REQUIRED)
        val bytes = utf8("{\"value\":" + raw + "}") ?: return reject(PreviewKnowledgeBaseProblem.INTEGER)
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewKnowledgeBaseProblem.TOO_LONG else PreviewKnowledgeBaseProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewKnowledgeBaseProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewKnowledgeBaseProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewKnowledgeBaseProblem.RANGE)
        return value
    }
}
