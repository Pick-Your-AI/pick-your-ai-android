package ai.pickyour.core.preview

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A positive exclusion for newly captured settings, not admission or redaction of retained history. */
object PreviewSelectionRecoveryPolicy {
    const val MAX_RAW_CHARACTERS = 20_000
    private val reader = BoundedJsonReader(JsonInputLimits(maxBytes = 65_536, maxStringLength = MAX_RAW_CHARACTERS))

    /** Malformed, unsupported and over-budget text remains unclassified; callers bound the whole form first. */
    fun hasRecognizedUserinfo(kind: String, settingsText: String): Boolean {
        if (settingsText.length > MAX_RAW_CHARACTERS || PreviewSelectionSensitiveInput.uriField(kind) == null) return false
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(settingsText))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) {
            return false
        }
        val parsed = reader.read(bytes.inputStream()) as? JsonInputResult.Parsed ?: return false
        return PreviewSelectionSensitiveInput.hasRecognizedUserinfo(kind, parsed.value)
    }
}
