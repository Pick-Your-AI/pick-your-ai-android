package ai.pickyour.core.preview

import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URISyntaxException

/** The exact URI authority policy for new input; never a history reader or general secret scanner. */
internal object PreviewSelectionSensitiveInput {
    private enum class Authority { CLEAR, USERINFO, INVALID }

    internal fun uriField(kind: String): String? = when (kind) {
        "model_provider" -> "api_base_url"
        "model" -> "weights_uri"
        "mcp_server", "integration" -> "endpoint"
        "fine_tuned_model" -> "artifact_uri"
        else -> null
    }

    // Admission remains fail-closed after its existing bounded JSON and frozen schema checks.
    fun allows(kind: String, settings: ObjectNode): Boolean = authority(kind, settings) == Authority.CLEAR

    // Recovery must distinguish positively recognized userinfo from ordinary invalid editor input.
    fun hasRecognizedUserinfo(kind: String, settings: ObjectNode): Boolean = authority(kind, settings) == Authority.USERINFO

    private fun authority(kind: String, settings: ObjectNode): Authority {
        val field = uriField(kind) ?: return Authority.CLEAR
        val value = settings[field] ?: return Authority.CLEAR
        if (!value.isTextual) return Authority.INVALID
        return try {
            // rawUserInfo alone misses registry authorities that are not parsed as a server host.
            // An @ in a path, query, fragment or opaque URI is outside this authority boundary.
            if (URI(value.textValue()).rawAuthority?.contains('@') == true) Authority.USERINFO else Authority.CLEAR
        } catch (_: URISyntaxException) {
            Authority.INVALID
        }
    }
}
