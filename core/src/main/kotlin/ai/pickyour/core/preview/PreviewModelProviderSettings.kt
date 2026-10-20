package ai.pickyour.core.preview

import ai.pickyour.core.drafts.LocalSelection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Collections
import java.util.regex.Pattern

/** Null means not supplied. A supplied empty or whitespace-only region remains literal string data. */
data class PreviewModelProviderInput(
    val region: String? = null,
    val credentialRef: String? = null,
    val apiBaseUrl: String? = null,
) {
    override fun toString() = "PreviewModelProviderInput"
}

enum class PreviewModelProviderProblem { TEXT, REFERENCE, URI, TOO_LONG }
data class PreviewModelProviderIssue(val field: String, val problem: PreviewModelProviderProblem) {
    override fun toString() = "PreviewModelProviderIssue"
}
class PreviewModelProviderPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewModelProviderIssue>,
) {
    val issues: List<PreviewModelProviderIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewModelProviderPreparation"
}

/** Three optional frozen strings; final admission and live command authority remain with existing adapters. */
object PreviewModelProviderSettings {
    const val KIND = "model_provider"
    const val REGION = "region"
    const val CREDENTIAL = "credential_ref"
    const val API_BASE_URL = "api_base_url"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val mapper = ObjectMapper()
    private val credentialPattern by lazy {
        // The frozen receiver uses find with LF-only anchors; do not trim or normalize references.
        Pattern.compile(checkNotNull(field(CREDENTIAL).pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewModelProviderInput {
        require(selection.kind == KIND)
        val value = selection.settings
        return PreviewModelProviderInput(value.get(REGION)?.textValue(), value.get(CREDENTIAL)?.textValue(),
            value.get(API_BASE_URL)?.textValue())
    }

    /** Preparation only: no storage write, credential resolution, URI access or provider compatibility claim. */
    fun configure(selection: LocalSelection, input: PreviewModelProviderInput): PreviewModelProviderPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val settings = selection.settings
        val issues = mutableListOf<PreviewModelProviderIssue>()
        for ((key, raw) in listOf(REGION to input.region, CREDENTIAL to input.credentialRef, API_BASE_URL to input.apiBaseUrl)) {
            if (raw == null) settings.remove(key)
            else text(selection, key, raw, issues)?.let { settings.set<JsonNode>(key, it) }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewModelProviderPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** Positive bounded classification does not depend on the validity of any other field. */
    fun hasRecognizedApiBaseUrlUserinfo(raw: String?): Boolean =
        raw != null && raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS &&
            PreviewSelectionSensitiveInput.hasRecognizedUserinfo(KIND, mapper.createObjectNode().put(API_BASE_URL, raw))

    /** Applies only to newly captured typed recovery, without changing historical raw readers. */
    fun canRetainCredentialReference(raw: String?): Boolean =
        raw == null || raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS && credentialPattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewModelProviderIssue>) =
        PreviewModelProviderPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun text(selection: LocalSelection, key: String, raw: String,
        issues: MutableList<PreviewModelProviderIssue>): JsonNode? {
        fun reject(problem: PreviewModelProviderProblem): JsonNode? {
            issues += PreviewModelProviderIssue(key, problem)
            return null
        }
        val definition = field(key)
        check(!definition.required && definition.type == PreviewSettingType.STRING) { "Model provider text metadata differs" }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewModelProviderProblem.TOO_LONG)
        val problem = when (key) {
            CREDENTIAL -> PreviewModelProviderProblem.REFERENCE
            API_BASE_URL -> PreviewModelProviderProblem.URI
            else -> PreviewModelProviderProblem.TEXT
        }
        if (key == CREDENTIAL && !credentialPattern.matcher(raw).find()) return reject(problem)
        val value = mapper.nodeFactory.textNode(raw)
        // Use the original complete Selection receiver for field feedback, then the whole-settings adapter
        // for aggregate admission. A failure cannot expose a partially configured command.
        val oneField = selection.settings.also { it.set<JsonNode>(key, value) }
        val accepted = try {
            LocalSelection.fromJson(selection.toJson().also { it.set<JsonNode>("settings", oneField) })
            key != API_BASE_URL || PreviewSelectionSensitiveInput.allows(KIND, mapper.createObjectNode().put(API_BASE_URL, raw))
        } catch (_: IllegalArgumentException) { false }
        return if (accepted) value else reject(problem)
    }
}
