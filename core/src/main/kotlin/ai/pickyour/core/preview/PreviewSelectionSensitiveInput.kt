package ai.pickyour.core.preview

import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URISyntaxException

/** New editor input only, after bounded JSON and frozen schema validation; never a history reader. */
internal object PreviewSelectionSensitiveInput {
    fun allows(kind: String, settings: ObjectNode): Boolean {
        val field = when (kind) {
            "model_provider" -> "api_base_url"
            "model" -> "weights_uri"
            "mcp_server", "integration" -> "endpoint"
            "fine_tuned_model" -> "artifact_uri"
            else -> return true
        }
        val value = settings[field] ?: return true
        if (!value.isTextual) return false
        return try {
            // rawUserInfo alone misses registry authorities that are not parsed as a server host.
            // An @ in a path, query, fragment or opaque URI is outside this authority boundary.
            URI(value.textValue()).rawAuthority?.contains('@') != true
        } catch (_: URISyntaxException) {
            false
        }
    }
}
