package ai.pickyour.core.navigation

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A candidate for a user-reviewed browser action, never permission to fetch or execute it. */
class ExternalLink internal constructor(val uri: URI) {
    // URLs can contain private query parameters. Logging a result must not disclose them.
    override fun toString(): String = "ExternalLink"
}

enum class LinkRejection { TOO_LONG, MALFORMED, DISALLOWED_SCHEME, DISALLOWED_AUTHORITY }
sealed interface LinkDecision {
    class Candidate(val link: ExternalLink) : LinkDecision
    data class Rejected(val reason: LinkRejection) : LinkDecision
}

/**
 * Strict local navigation boundary for an explicitly configured set of ASCII HTTPS hosts.
 * Exact host comparison, not substring/suffix matching. No DNS or network IO; no redirects,
 * WebViews, Android intents, App Link association or object authorization are implemented here.
 * Require a user action before opening the candidate. Browser redirects are not confined by this
 * policy; future controlled fetch/WebView adapters must validate every redirect independently.
 * An allowlisted host is not an assertion that its content is safe or has redistribution rights.
 */
class ExternalLinkPolicy(allowedHosts: Set<String>, private val maxLength: Int = 4096) {
    private val hosts: Set<String>
    init {
        require(maxLength in 1..65_536)
        hosts = allowedHosts.map { host ->
            require(host.isNotBlank() && host.all { it.code in 33..126 })
            val parsed = try { URI("https://$host") } catch (_: URISyntaxException) { null }
            require(parsed != null && parsed.host == host && parsed.rawAuthority == host &&
                parsed.rawPath.isEmpty() && parsed.rawQuery == null && parsed.rawFragment == null &&
                !host.endsWith('.'))
            host.lowercase(Locale.ROOT)
        }.toSet()
    }

    fun inspect(raw: String): LinkDecision {
        if (raw.length > maxLength) return reject(LinkRejection.TOO_LONG)
        // Reject ambiguous display/control characters before URI parsing, including backslashes.
        if (raw.isEmpty() || raw.any { it.code !in 33..126 || it == '\\' }) return reject(LinkRejection.MALFORMED)
        val uri = try { URI(raw) } catch (_: URISyntaxException) { return reject(LinkRejection.MALFORMED) }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque) return reject(LinkRejection.DISALLOWED_SCHEME)
        val host = uri.host ?: return reject(LinkRejection.DISALLOWED_AUTHORITY)
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
            host.lowercase(Locale.ROOT) !in hosts || host.endsWith('.')) return reject(LinkRejection.DISALLOWED_AUTHORITY)
        // Prevent downstream parser disagreement on encoded authority delimiters and empty ports.
        val expected = host + if (uri.port == 443) ":443" else ""
        if (uri.rawAuthority != expected) return reject(LinkRejection.DISALLOWED_AUTHORITY)
        return LinkDecision.Candidate(ExternalLink(uri))
    }

    private fun reject(reason: LinkRejection) = LinkDecision.Rejected(reason)
}
