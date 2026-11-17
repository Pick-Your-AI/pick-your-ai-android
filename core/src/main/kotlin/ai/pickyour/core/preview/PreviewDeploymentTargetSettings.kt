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

/** Null means absent; supplied empty text and supplied zero retain their own meanings. */
data class PreviewDeploymentTargetInput(
    val environment: String? = null,
    val replicasMin: String? = null,
    val replicasMax: String? = null,
    val region: String? = null,
    val approvalTicket: String? = null,
) {
    override fun toString() = "PreviewDeploymentTargetInput"
}

enum class PreviewDeploymentTargetProblem { REQUIRED, INTEGER, RANGE, ENUM, TEXT, TOO_LONG }
data class PreviewDeploymentTargetIssue(val field: String, val problem: PreviewDeploymentTargetProblem) {
    override fun toString() = "PreviewDeploymentTargetIssue"
}
class PreviewDeploymentTargetPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewDeploymentTargetIssue>,
) {
    val issues: List<PreviewDeploymentTargetIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewDeploymentTargetPreparation"
}

/** Frozen configuration only: no regional support, approval, capacity or deployment authority. */
object PreviewDeploymentTargetSettings {
    const val KIND = "deployment_target"
    const val ENVIRONMENT = "environment"
    const val REPLICAS_MIN = "replicas_min"
    const val REPLICAS_MAX = "replicas_max"
    const val REGION = "region"
    const val APPROVAL_TICKET = "approval_ticket"
    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }

    fun initial(selection: LocalSelection): PreviewDeploymentTargetInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewDeploymentTargetInput(settings.get(ENVIRONMENT)?.textValue(),
            settings.get(REPLICAS_MIN)?.toString(), settings.get(REPLICAS_MAX)?.toString(),
            settings.get(REGION)?.textValue(), settings.get(APPROVAL_TICKET)?.textValue())
    }

    /** Field feedback is independent; only the complete prospective Selection can produce a command. */
    fun configure(selection: LocalSelection, input: PreviewDeploymentTargetInput): PreviewDeploymentTargetPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewDeploymentTargetIssue>()
        val environment = field(ENVIRONMENT)
        check(!environment.required && environment.type == PreviewSettingType.STRING &&
            environment.enumValues.isNotEmpty()) { "Deployment environment metadata differs" }
        when {
            input.environment == null -> settings.remove(ENVIRONMENT)
            input.environment !in environment.enumValues ->
                issues.add(PreviewDeploymentTargetIssue(ENVIRONMENT, PreviewDeploymentTargetProblem.ENUM))
            else -> settings.put(ENVIRONMENT, input.environment)
        }
        for ((key, raw, retained) in listOf(Triple(REPLICAS_MIN, input.replicasMin, original.replicasMin),
            Triple(REPLICAS_MAX, input.replicasMax, original.replicasMax))) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.INTEGER) {
                "Deployment replica metadata differs"
            }
            if (raw == null) settings.remove(key)
            else number(raw, definition, issues)?.let { value ->
                // Preserve admitted scale and integer aliases when a numeric control is untouched.
                if (raw != retained) settings.set<JsonNode>(key, value)
            }
        }
        for ((key, raw) in listOf(REGION to input.region, APPROVAL_TICKET to input.approvalTicket)) {
            val definition = field(key)
            check(!definition.required && definition.type == PreviewSettingType.STRING &&
                definition.enumValues.isEmpty() && definition.pattern == null && definition.format == null) {
                "Deployment literal-text metadata differs"
            }
            when {
                raw == null -> settings.remove(key)
                raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT ->
                    issues.add(PreviewDeploymentTargetIssue(key, PreviewDeploymentTargetProblem.TOO_LONG))
                utf8(raw) == null -> issues.add(PreviewDeploymentTargetIssue(key, PreviewDeploymentTargetProblem.TEXT))
                else -> settings.put(key, raw)
            }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewDeploymentTargetPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewDeploymentTargetIssue>) =
        PreviewDeploymentTargetPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun utf8(raw: String): ByteArray? = try {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) { null }

    private fun number(raw: String, definition: PreviewSettingField,
        issues: MutableList<PreviewDeploymentTargetIssue>): JsonNode? {
        fun reject(problem: PreviewDeploymentTargetProblem): JsonNode? {
            issues += PreviewDeploymentTargetIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewDeploymentTargetProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewDeploymentTargetProblem.REQUIRED)
        val bytes = utf8("{\"value\":" + raw + "}") ?: return reject(PreviewDeploymentTargetProblem.INTEGER)
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewDeploymentTargetProblem.TOO_LONG
            else PreviewDeploymentTargetProblem.INTEGER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewDeploymentTargetProblem.INTEGER)
        val exact = value.decimalValue()
        if (exact.stripTrailingZeros().scale() > 0) return reject(PreviewDeploymentTargetProblem.INTEGER)
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewDeploymentTargetProblem.RANGE)
        return value
    }
}
