package ai.pickyour.core.preview

import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Null means not supplied; false, zero and exact decimal-string spelling remain supplied values. */
data class PreviewCostPolicyInput(
    val monthlyBudgetAmount: String? = null,
    val perRequestBudgetAmount: String? = null,
    val enforceHardCap: Boolean? = null,
    val notifyAtPct: String? = null,
) {
    override fun toString() = "PreviewCostPolicyInput"
}

enum class PreviewCostPolicyProblem { REQUIRED, DECIMAL_STRING, NUMBER, RANGE, TOO_LONG }
data class PreviewCostPolicyIssue(val field: String, val problem: PreviewCostPolicyProblem) {
    override fun toString() = "PreviewCostPolicyIssue"
}
class PreviewCostPolicyPreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewCostPolicyIssue>,
) {
    val issues: List<PreviewCostPolicyIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewCostPolicyPreparation"
}

/** Frozen planning inputs only; preparing a selection does not calculate prices or enforce a budget. */
object PreviewCostPolicySettings {
    const val KIND = "cost_policy"
    const val MONTHLY = "monthly_budget_amount"
    const val PER_REQUEST = "per_request_budget_amount"
    const val HARD_CAP = "enforce_hard_cap"
    const val NOTIFY = "notify_at_pct"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val mapper = ObjectMapper()

    fun initial(selection: LocalSelection): PreviewCostPolicyInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewCostPolicyInput(settings.get(MONTHLY)?.textValue(), settings.get(PER_REQUEST)?.textValue(),
            settings.get(HARD_CAP)?.booleanValue(), settings.get(NOTIFY)?.toString())
    }

    /** No write or partial command; final admission remains the existing whole-selection adapter. */
    fun configure(selection: LocalSelection, input: PreviewCostPolicyInput): PreviewCostPolicyPreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewCostPolicyIssue>()
        for ((key, raw) in listOf(MONTHLY to input.monthlyBudgetAmount, PER_REQUEST to input.perRequestBudgetAmount)) {
            if (raw == null) settings.remove(key)
            else decimalString(key, raw, issues)?.let { settings.set<JsonNode>(key, it) }
        }
        val boolean = field(HARD_CAP)
        check(!boolean.required && boolean.type == PreviewSettingType.BOOLEAN) { "Cost policy Boolean metadata differs" }
        if (input.enforceHardCap == null) settings.remove(HARD_CAP) else settings.put(HARD_CAP, input.enforceHardCap)
        val definition = field(NOTIFY)
        check(!definition.required && definition.type == PreviewSettingType.NUMBER) { "Cost policy number metadata differs" }
        if (input.notifyAtPct == null) settings.remove(NOTIFY)
        else {
            val value = number(input.notifyAtPct, definition, issues)
            // Untouched admitted nodes keep their original scale and numeric representation.
            if (value != null && input.notifyAtPct != original.notifyAtPct) settings.set<JsonNode>(NOTIFY, value)
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewCostPolicyPreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    private fun invalid(issues: List<PreviewCostPolicyIssue>) =
        PreviewCostPolicyPreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun decimalString(key: String, raw: String,
        issues: MutableList<PreviewCostPolicyIssue>): JsonNode? {
        fun reject(problem: PreviewCostPolicyProblem): JsonNode? {
            issues += PreviewCostPolicyIssue(key, problem)
            return null
        }
        val definition = field(key)
        check(!definition.required && definition.type == PreviewSettingType.STRING && definition.pattern != null) {
            "Cost policy decimal-string metadata differs"
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewCostPolicyProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewCostPolicyProblem.REQUIRED)
        val value = mapper.nodeFactory.textNode(raw)
        // Validate this field independently through the actual frozen receiver. Do not duplicate its
        // regex, trim terminal LF, impose the numeric-token budget on a string, or parse through Double.
        // This validation-only scaffold carries no saved identity or sibling settings. A sibling
        // removed or shrunk by the final edit must not make an independent field probe too large.
        // The complete original Selection still goes through configure() after every field is read.
        val probe = mapper.createObjectNode().put("id", "sel_validation").put("kind", KIND)
            .put("catalog_item_id", "validation.cost_policy").put("catalog_item_version", "1")
            .put("role", "primary").put("enabled", false)
        probe.set<JsonNode>("settings", mapper.createObjectNode().set<JsonNode>(key, value))
        val accepted = try {
            LocalSelection.fromJson(probe)
            true
        } catch (_: IllegalArgumentException) { false }
        return if (accepted) value else reject(PreviewCostPolicyProblem.DECIMAL_STRING)
    }

    private fun number(raw: String, definition: PreviewSettingField,
        issues: MutableList<PreviewCostPolicyIssue>): JsonNode? {
        fun reject(problem: PreviewCostPolicyProblem): JsonNode? {
            issues += PreviewCostPolicyIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewCostPolicyProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewCostPolicyProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewCostPolicyProblem.NUMBER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewCostPolicyProblem.TOO_LONG else PreviewCostPolicyProblem.NUMBER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewCostPolicyProblem.NUMBER)
        val exact = value.decimalValue()
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewCostPolicyProblem.RANGE)
        return value
    }
}
