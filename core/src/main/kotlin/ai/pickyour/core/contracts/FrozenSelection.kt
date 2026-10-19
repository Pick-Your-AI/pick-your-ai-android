package ai.pickyour.core.contracts

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.io.InputStream

sealed interface FrozenSelectionDecoding {
    data class Decoded(val selection: FrozenBuildSelection) : FrozenSelectionDecoding
    data class InvalidInput(val reason: JsonRejection) : FrozenSelectionDecoding
    data object UnsupportedVersion : FrozenSelectionDecoding
    data object SchemaMismatch : FrozenSelectionDecoding
}

/**
 * Bounded standalone Selection decoding under an explicit caller-supplied contract version.
 * The frozen Selection schema has no schema_version member: the receiver must obtain that context
 * from its accepted enclosing contract, never infer it from a structurally similar payload.
 * Full syntax/EOF checks precede version and schema admission. The same immutable value type used
 * by [FrozenBuildSnapshotDecoder] retains every admitted field without applying private draft policy.
 *
 * This creates no Build, local identity, save token or import/export authority. URI and credential
 * references remain inert recorded values, and the result is not a public disclosure projection.
 * Streams remain caller-owned on all outcomes; transport timeout/cancellation and off-UI-thread
 * execution remain the caller's responsibility.
 */
class FrozenSelectionDecoder {
    private val reader = BoundedJsonReader()
    private val validator = FrozenSchemaValidator()

    fun decode(input: InputStream, contractVersion: String): FrozenSelectionDecoding {
        val parsed = when (val result = reader.read(input)) {
            is JsonInputResult.Rejected -> return FrozenSelectionDecoding.InvalidInput(result.reason)
            is JsonInputResult.Parsed -> result.value
        }
        if (contractVersion != "1.0.0") return FrozenSelectionDecoding.UnsupportedVersion
        // The retained representation must reopen within the same bounded parser profile.
        when (val result = validator.validate(FrozenDocument.SELECTION, parsed.toString().byteInputStream())) {
            is FrozenValidation.InvalidInput -> return FrozenSelectionDecoding.InvalidInput(result.reason)
            FrozenValidation.SchemaMismatch -> return FrozenSelectionDecoding.SchemaMismatch
            FrozenValidation.Valid -> Unit
        }
        return FrozenSelectionDecoding.Decoded(FrozenBuildSelection(parsed))
    }
}
