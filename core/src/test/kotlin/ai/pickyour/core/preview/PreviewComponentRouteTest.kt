package ai.pickyour.core.preview

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale

class PreviewComponentRouteTest {
    private val hash = "ab".repeat(32)
    private fun uri(version: String = "2026.10-demo", id: String = "cat.model.fixture", kind: String = "model",
        snapshot: String = hash) = "pickyourai-preview://component/v1?kind=$kind&id=$id&version=$version&snapshot=$snapshot"
    private fun parsed(raw: String = uri()) = requireNotNull(PreviewComponentRoute.parse(raw))

    @Test fun `the four explicit fields survive parsing with exact opaque version spelling`() {
        val route = parsed()
        assertEquals("model", route.kind)
        assertEquals("cat.model.fixture", route.itemId)
        assertEquals("2026.10-demo", route.version)
        assertEquals(hash, route.snapshotHash)
        assertEquals(" opaque+version/1=2&3 ", parsed(uri("%20opaque%2Bversion%2F1%3D2%263%20")).version)
        assertEquals("%2F", parsed(uri("%252F")).version)
    }

    @Test fun `parameter order transport case and hex spelling do not change identity`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val route = parsed("PICKYOURAI-PREVIEW://COMPONENT/v1?snapshot=${hash.uppercase(Locale.ROOT)}" +
                "&version=%E2%82%AC%2b1&id=cat.model.fixture&kind=model")
            assertEquals("€+1", route.version)
            assertEquals(hash, route.snapshotHash)
            assertEquals("model", route.kind)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `only the exact private route authority and path are recognized`() {
        val base = uri()
        for (raw in listOf(
            base.replace("pickyourai-preview:", "https:"), base.replace("pickyourai-preview:", "javascript:"),
            base.replace("//component", "//component.example"), base.replace("//component", "//component:443"),
            base.replace("//component", "//user@component"), base.replace("//component", "//component@evil"),
            base.replace("//component", "//%63omponent"), base.replace("//component", "///component"),
            base.replace("//component/v1", "component/v1"), base.replace("/v1?", "/v1/?"),
            base.replace("/v1?", "/v2?"), base.replace("/v1?", "/%76%31?"), base + "#fragment", base + "#",
        )) assertNull(PreviewComponentRoute.parse(raw), raw)
    }

    @Test fun `unknown duplicate missing empty and encoded parameter keys reject the complete route`() {
        for (raw in listOf(
            uri() + "&draft=guest-draft", uri() + "&role=primary", uri() + "&expectedRevision=1",
            uri() + "&replace=selected", uri() + "&kind=model", uri().replace("&id=cat.model.fixture", ""),
            uri().replace("kind=model", "kind=model&kind=model"), uri().replace("&id=cat.model.fixture", "&kind=model"),
            uri().replace("kind=", "%6bind="), uri().replace("kind=", "Kind="), uri().replace("kind=", "="),
            uri().replace("kind=model", "kind"), uri() + "&", uri().replace("kind=model", "kind="),
        )) assertNull(PreviewComponentRoute.parse(raw), raw)
    }

    @Test fun `reserved delimiters are unambiguous and values are decoded only once`() {
        for (version in listOf("a+b", "a=b", "a&b", "a#b", "a?b#c", "%", "%2", "%GG", "%0g"))
            assertNull(PreviewComponentRoute.parse(uri(version)), version)
        assertEquals("+", parsed(uri("%2B")).version)
        assertEquals("&=#", parsed(uri("%26%3D%23")).version)
        assertEquals("?", parsed(uri("?")).version)
    }

    @Test fun `malformed UTF8 never becomes replacement characters or a different version`() {
        for (version in listOf("%80", "%C0%AF", "%C1%BF", "%E0%80%AF", "%ED%A0%80", "%F0%80%80%AF",
            "%F4%90%80%80", "%F5%80%80%80", "%E2%82", "%C2", "%FF", "%FE", "%C3%28"))
            assertNull(PreviewComponentRoute.parse(uri(version)), version)
        assertEquals("é𐀀", parsed(uri("%C3%A9%F0%90%80%80")).version)
    }

    @Test fun `raw nonASCII whitespace backslashes and decoded control characters reject`() {
        for (raw in listOf(" " + uri(), uri() + " ", uri("é"), uri("\t"), uri("\r\n"), uri("\\"), uri("\u0000")))
            assertNull(PreviewComponentRoute.parse(raw))
        for (version in listOf("%00", "%09", "%0A", "%0D", "%1F", "%7F", "%C2%80", "%C2%9F"))
            assertNull(PreviewComponentRoute.parse(uri(version)), version)
    }

    @Test fun `only frozen canonical kinds and bounded canonical item identifiers are accepted`() {
        PreviewCatalogKinds.all.forEach { assertEquals(it.id, parsed(uri(kind = it.id)).kind) }
        for (kind in listOf("unknown", "Model", "MODEL", "model%20", "", "m".repeat(65)))
            assertNull(PreviewComponentRoute.parse(uri(kind = kind)), kind)
        for (id in listOf("ab", "1ab", "Cat.model.fixture", "cat/model", "cat%2Fmodel", "cat%20model", "a".repeat(121)))
            assertNull(PreviewComponentRoute.parse(uri(id = id)), id)
        assertEquals("a._:-0", parsed(uri(id = "a._:-0")).itemId)
        assertEquals("a".repeat(120), parsed(uri(id = "a".repeat(120))).itemId)
    }

    @Test fun `version budget counts strict UTF8 bytes without trimming or Unicode normalization`() {
        assertEquals("x".repeat(256), parsed(uri("x".repeat(256))).version)
        assertNull(PreviewComponentRoute.parse(uri("x".repeat(257))))
        assertEquals("é".repeat(128), parsed(uri("%C3%A9".repeat(128))).version)
        assertNull(PreviewComponentRoute.parse(uri("%C3%A9".repeat(129))))
        assertEquals("𐀀".repeat(64), parsed(uri("%F0%90%80%80".repeat(64))).version)
        assertNull(PreviewComponentRoute.parse(uri("%F0%90%80%80".repeat(65))))
        assertNotEquals(parsed(uri("%C3%A9")).version, parsed(uri("e%CC%81")).version)
        assertNull(PreviewComponentRoute.parse(uri("")))
    }

    @Test fun `snapshot identity requires exactly a full hexadecimal digest`() {
        for (snapshot in listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "sha256:$hash", " $hash"))
            assertNull(PreviewComponentRoute.parse(uri(snapshot = snapshot)), snapshot)
        assertEquals(hash, parsed(uri(snapshot = hash.uppercase(Locale.ROOT))).snapshotHash)
    }

    @Test fun `empty and oversized raw entry is rejected before any catalog use`() {
        assertNull(PreviewComponentRoute.parse(null))
        assertNull(PreviewComponentRoute.parse(""))
        assertEquals(4096, PreviewComponentRoute.MAX_URI_BYTES)
        assertNull(PreviewComponentRoute.parse(uri("x".repeat(4096))))
        assertNull(PreviewComponentRoute.parse("x".repeat(1_048_576)))
    }

    @Test fun `normalized checkpoint restoration repeats every identity admission check`() {
        fun restore(kind: String? = "model", id: String? = "cat.model.fixture", version: String? = "v1",
            snapshot: String? = hash) = PreviewComponentRoute.restore(kind, id, version, snapshot)
        assertEquals("v1", requireNotNull(restore()).version)
        assertEquals(" a+&= ", requireNotNull(restore(version = " a+&= ")).version)
        assertNull(restore(kind = null)); assertNull(restore(id = null))
        assertNull(restore(version = null)); assertNull(restore(snapshot = null))
        assertNull(restore(kind = "future")); assertNull(restore(id = "../payload"))
        assertNull(restore(version = "\uD800")); assertNull(restore(version = "\uDC00"))
        assertNull(restore(version = "\u0085")); assertNull(restore(version = "é".repeat(129)))
        assertNull(restore(snapshot = "a".repeat(63)))
    }

    @Test fun `matching requires the exact kind item version and raw snapshot identity`() {
        val route = parsed()
        val item = PreviewCatalogItem(route.itemId, "Display label", route.kind, route.version, "T1")
        assertTrue(route.matches(item, hash))
        assertTrue(route.matches(item.copy(name = "Changed display label"), hash))
        assertFalse(route.matches(item.copy(id = "cat.model.other"), hash))
        assertFalse(route.matches(item.copy(kind = "accelerator"), hash))
        assertFalse(route.matches(item.copy(version = "latest"), hash))
        assertFalse(route.matches(item, "c".repeat(64)))
        assertFalse(route.matches(item, hash.uppercase(Locale.ROOT)))
    }

    @Test fun `diagnostics disclose no incoming version or identity`() {
        val route = parsed(uri("private%2Fcomponent%3Fpayload"))
        assertEquals("PreviewComponentRoute", route.toString())
        assertFalse(route.toString().contains(route.version))
        assertFalse(route.toString().contains(route.itemId))
        assertFalse(route.toString().contains(route.snapshotHash))
    }
}
