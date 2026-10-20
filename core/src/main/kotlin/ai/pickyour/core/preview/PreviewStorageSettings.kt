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
import java.util.regex.Pattern

/** Null means not supplied. Supplied zero and literal empty region retain their separate meanings. */
data class PreviewStorageInput(
    val provisionedGb: String? = null,
    val region: String? = null,
    val encryptionKeyRef: String? = null,
) {
    override fun toString() = "PreviewStorageInput"
}

enum class PreviewStorageProblem { REQUIRED, NUMBER, RANGE, TEXT, REFERENCE, TOO_LONG }
data class PreviewStorageIssue(val field: String, val problem: PreviewStorageProblem) {
    override fun toString() = "PreviewStorageIssue"
}
class PreviewStoragePreparation internal constructor(
    val preparation: PreviewSelectionPreparation,
    issues: List<PreviewStorageIssue>,
) {
    val issues: List<PreviewStorageIssue> = Collections.unmodifiableList(issues.toList())
    override fun toString() = "PreviewStoragePreparation"
}

/** Frozen declared settings only; complete Selection admission and managed save authority stay unchanged. */
object PreviewStorageSettings {
    const val KIND = "storage"
    const val PROVISIONED = "provisioned_gb"
    const val REGION = "region"
    const val ENCRYPTION_KEY = "encryption_key_ref"

    val fields: List<PreviewSettingField> get() = checkNotNull(PreviewSettingsMetadata.fields(KIND))
    private fun field(key: String) = fields.single { it.key == key }
    private val mapper = ObjectMapper()
    private val referencePattern by lazy {
        // Preserve the frozen receiver's find and LF-only anchor semantics without trimming.
        Pattern.compile(checkNotNull(field(ENCRYPTION_KEY).pattern), Pattern.UNIX_LINES)
    }

    fun initial(selection: LocalSelection): PreviewStorageInput {
        require(selection.kind == KIND)
        val settings = selection.settings
        return PreviewStorageInput(settings.get(PROVISIONED)?.toString(), settings.get(REGION)?.textValue(),
            settings.get(ENCRYPTION_KEY)?.textValue())
    }

    /** Preparation only: no store mutation, capacity measurement, key lookup or storage access. */
    fun configure(selection: LocalSelection, input: PreviewStorageInput): PreviewStoragePreparation {
        if (selection.kind != KIND) return invalid(emptyList())
        val original = initial(selection)
        val settings = selection.settings
        val issues = mutableListOf<PreviewStorageIssue>()
        val definition = field(PROVISIONED)
        check(!definition.required && definition.type == PreviewSettingType.NUMBER) { "Storage numeric metadata differs" }
        val numeric = input.provisionedGb
        if (numeric == null) settings.remove(PROVISIONED)
        else {
            val value = number(numeric, definition, issues)
            // Retain the original admitted numeric node when the participant has not changed its text.
            if (value != null && numeric != original.provisionedGb) settings.set<JsonNode>(PROVISIONED, value)
        }
        for ((key, raw) in listOf(REGION to input.region, ENCRYPTION_KEY to input.encryptionKeyRef)) {
            if (raw == null) settings.remove(key)
            else text(selection, key, raw, issues)?.let { settings.set<JsonNode>(key, it) }
        }
        if (issues.isNotEmpty()) return invalid(issues)
        return PreviewStoragePreparation(PreviewSelectionForms.configure(selection, settings.toString()), emptyList())
    }

    /** New typed recovery only; check the bounded length before matching the opaque reference. */
    fun canRetainEncryptionKeyReference(raw: String?): Boolean =
        raw == null || raw.length <= PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS && referencePattern.matcher(raw).find()

    private fun invalid(issues: List<PreviewStorageIssue>) =
        PreviewStoragePreparation(PreviewSelectionPreparation.Invalid, issues)

    private fun number(raw: String, definition: PreviewSettingField, issues: MutableList<PreviewStorageIssue>): JsonNode? {
        fun reject(problem: PreviewStorageProblem): JsonNode? {
            issues += PreviewStorageIssue(definition.key, problem)
            return null
        }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewStorageProblem.TOO_LONG)
        if (raw.isBlank()) return reject(PreviewStorageProblem.REQUIRED)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap("{\"value\":" + raw + "}"))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) { return reject(PreviewStorageProblem.NUMBER) }
        val parsed = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536)).read(bytes.inputStream())
        if (parsed is JsonInputResult.Rejected) return reject(
            if (parsed.reason == JsonRejection.LIMIT_EXCEEDED) PreviewStorageProblem.TOO_LONG else PreviewStorageProblem.NUMBER)
        val wrapper = (parsed as JsonInputResult.Parsed).value
        val value = wrapper.get("value")
        if (wrapper.size() != 1 || value?.isNumber != true) return reject(PreviewStorageProblem.NUMBER)
        val exact = value.decimalValue()
        if (definition.minimum?.let { exact < it } == true ||
            definition.exclusiveMinimum?.let { exact <= it } == true ||
            definition.maximum?.let { exact > it } == true) return reject(PreviewStorageProblem.RANGE)
        return value
    }

    private fun text(selection: LocalSelection, key: String, raw: String,
        issues: MutableList<PreviewStorageIssue>): JsonNode? {
        fun reject(problem: PreviewStorageProblem): JsonNode? {
            issues += PreviewStorageIssue(key, problem)
            return null
        }
        val definition = field(key)
        check(!definition.required && definition.type == PreviewSettingType.STRING) { "Storage text metadata differs" }
        if (raw.length > PreviewSelectionForms.MAX_SETTINGS_TEXT) return reject(PreviewStorageProblem.TOO_LONG)
        val problem = if (key == ENCRYPTION_KEY) PreviewStorageProblem.REFERENCE else PreviewStorageProblem.TEXT
        if (key == ENCRYPTION_KEY && !referencePattern.matcher(raw).find()) return reject(problem)
        val value = mapper.nodeFactory.textNode(raw)
        val oneField = selection.settings.also { it.set<JsonNode>(key, value) }
        val accepted = try {
            LocalSelection.fromJson(selection.toJson().also { it.set<JsonNode>("settings", oneField) })
            true
        } catch (_: IllegalArgumentException) { false }
        return if (accepted) value else reject(problem)
    }
}
