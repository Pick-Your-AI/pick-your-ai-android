package ai.pickyour.core.preview

import ai.pickyour.core.drafts.LocalSelection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Collections
import java.util.regex.Pattern

/** Null means not supplied; a supplied empty or whitespace-only region remains literal string data. */
data class PreviewHostingPlatformInput(
    val region: String? = null,
    val accountRef: String? = null,
    val networkIsolation: String? = null,
) {
    override fun toString() = "PreviewHostingPlatformInput"
}

enum class PreviewHostingPlatformProblem { TEXT, REFERENCE, ENUM, TOO_LONG }
data class PreviewHostingPlatformIssue(val field: String, val problem: PreviewHostingPlatformProblem) {
    override fun toString() = "PreviewHostingPlatformIssue"
}
class PreviewHostingPlatformPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewHostingPlatformIssue>,
) {
    val issues: List<PreviewHostingPlatformIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewHostingPlatformPreparation"
}

/** Frozen declared settings only; complete Selection admission and live command authority stay unchanged. */
object PreviewHostingPlatformSettings {
    const val KIND = "hosting_platform"
    const val REGION = "region"
    const val ACCOUNT = "account_ref"
    const val NETWORK = "network_isolation"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val mapper = ObjectMapper()
    private val accountPattern by lazy {
        // Match the frozen receiver's find and LF-only anchor semantics without trimming the reference.
        Pattern.compile(checkNotNull(field(ACCOUNT).pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewHostingPlatformInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewHostingPlatformInput(settings.get(REGION)?.textValue(), settings.get(ACCOUNT)?.textValue(),
            settings.get(NETWORK)?.textValue())
    }

    /** Preparation only: no store mutation, account lookup, region verification or network action. */
    fun configure(selection: LocalSelection, input: PreviewHostingPlatformInput): PreviewHostingPlatformPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val settings = selection.settings
        val issues = mutableListOf<PreviewHostingPlatformIssue>()
        for ((key, raw) in listOf(REGION to input.region, ACCOUNT to input.accountRef, NETWORK to input.networkIsolation)) {
            if (raw == null) settings.remove(key)
            else text(selection, key, raw, issues)?.let { settings.set<JsonNode>(key, it) }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewHostingPlatformPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** New typed recovery only. Reject over-budget input before evaluating its reference pattern. */
    fun canRetainAccountReference(raw: String?): Boolean =
        raw == null || raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS && accountPattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewHostingPlatformIssue>) =
        PreviewHostingPlatformPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun text(selection: LocalSelection, key: String, raw: String,
        issues: MutableList<PreviewHostingPlatformIssue>): JsonNode? {
        fun reject(problem: PreviewHostingPlatformProblem): JsonNode? {
            issues += PreviewHostingPlatformIssue(key, problem)
            return null
        }
        val definition = field(key)
        check(!definition.required && definition.type == PreviewSettingType.STRING) { "Hosting text metadata differs" }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewHostingPlatformProblem.TOO_LONG)
        val problem = when (key) {
            ACCOUNT -> PreviewHostingPlatformProblem.REFERENCE
            NETWORK -> PreviewHostingPlatformProblem.ENUM
            else -> PreviewHostingPlatformProblem.TEXT
        }
        if (key == ACCOUNT && !accountPattern.matcher(raw).find()) return reject(problem)
        if (key == NETWORK) {
            check(definition.enumValues.isNotEmpty()) { "Hosting choice metadata differs" }
            if (raw !in definition.enumValues) return reject(problem)
        }
        val value = mapper.nodeFactory.textNode(raw)
        // Field feedback still passes through the original complete Selection receiver.
        // Whole-settings preparation below independently enforces the aggregate JSON budget.
        val oneField = selection.settings.also { it.set<JsonNode>(key, value) }
        val accepted = try {
            LocalSelection.fromJson(selection.toJson().also { it.set<JsonNode>("settings", oneField) })
            true
        } catch (_: IllegalArgumentException) { false }
        return if (accepted) value else reject(problem)
    }
}
