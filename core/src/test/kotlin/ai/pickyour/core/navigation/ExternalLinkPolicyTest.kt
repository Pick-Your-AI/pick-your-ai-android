package ai.pickyour.core.navigation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExternalLinkPolicyTest {
    private val policy = ExternalLinkPolicy(setOf("docs.example.com"))

    @Test fun `accepts exact HTTPS authority preserving user-visible destination`() {
        listOf("https://docs.example.com/path?q=one%20two#section", "HTTPS://DOCS.EXAMPLE.COM:443/").forEach {
            val result = policy.inspect(it) as LinkDecision.Candidate
            assertEquals(it, result.link.uri.toString())
            assertEquals("ExternalLink", result.link.toString())
        }
    }

    @Test fun `rejects hostile authority and privileged schemes`() {
        listOf(
            "https://docs.example.com.attacker.test/", "https://attacker-docs.example.com/",
            "https://attacker.test/docs.example.com", "https://docs.example.com@attacker.test/",
            "https://user:secret@docs.example.com/", "https://docs.example.com:444/",
            "https://docs.example.com:/", "https://docs.example.com./", "https://docs%2eexample.com/",
            "https://docs.example.com%40attacker.test/", "https://docs.example.com\\@attacker.test/",
            "http://docs.example.com/", "javascript:alert(1)", "content://docs.example.com/a",
            "file:///etc/passwd", "intent://docs.example.com/", "//docs.example.com/",
            "https:docs.example.com", "https://dоcs.example.com/", " https://docs.example.com/",
            "https://docs.example.com/\n", "https://docs.example.com/\u202e", "https://docs.example.com/%xx",
        ).forEach { assertTrue(policy.inspect(it) is LinkDecision.Rejected, it) }
    }

    @Test fun `configuration is copied and every authority is explicit`() {
        val hosts = mutableSetOf("docs.example.com")
        val configured = ExternalLinkPolicy(hosts)
        hosts.add("attacker.test")
        assertTrue(configured.inspect("https://attacker.test/") is LinkDecision.Rejected)
        assertTrue(ExternalLinkPolicy(emptySet()).inspect("https://docs.example.com/") is LinkDecision.Rejected)
        listOf("", "*.example.com", "docs.example.com/path", "docs.example.com:443", "docs.example.com.", "docs.example.com?q=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { ExternalLinkPolicy(setOf(it)) }
        }
    }

    @Test fun `length bounded before parsing and failure output does not echo input`() {
        assertEquals(LinkRejection.TOO_LONG, (ExternalLinkPolicy(setOf("docs.example.com"), 8)
            .inspect("https://docs.example.com/secret") as LinkDecision.Rejected).reason)
        val result = policy.inspect("https://secret:password@docs.example.com/")
        assertFalse(result.toString().contains("password"))
    }
}
