package ai.pickyour.core.preview

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Private preview navigation identity only. No draft, permission, fetch or mutation authority. */
class PreviewComponentRoute private constructor(
    val kind: String,
    val itemId: String,
    val version: String,
    val snapshotHash: String,
) {
    fun matches(item: PreviewCatalogItem, hash: String): Boolean = hash == snapshotHash &&
        item.kind == kind && item.id == itemId && item.version == version

    override fun toString() = "PreviewComponentRoute"

    companion object {
        const val MAX_URI_BYTES = 4096
        const val MAX_VERSION_BYTES = 256
        private val keys = setOf("kind", "id", "version", "snapshot")
        private val itemPattern = Regex("[a-z][a-z0-9._:-]{2,119}")
        private val hashPattern = Regex("[a-fA-F0-9]{64}")

        /** Raw URI spelling is ASCII; non-ASCII values require strict UTF-8 percent encoding. */
        fun parse(raw: String?): PreviewComponentRoute? {
            if (raw == null || raw.isEmpty() || raw.length > MAX_URI_BYTES ||
                raw.any { it.code !in 33..126 || it == '\\' }) return null
            return try {
                val uri = URI(raw)
                if (uri.isOpaque || uri.scheme?.equals("pickyourai-preview", ignoreCase = true) != true ||
                    uri.rawAuthority?.equals("component", ignoreCase = true) != true ||
                    uri.rawPath != "/v1" || uri.rawFragment != null || uri.userInfo != null || uri.port != -1) return null
                val parts = uri.rawQuery?.split('&') ?: return null
                if (parts.size != keys.size) return null
                val values = linkedMapOf<String, String>()
                for (part in parts) {
                    val separator = part.indexOf('=')
                    if (separator <= 0 || part.indexOf('=', separator + 1) >= 0) return null
                    val key = part.substring(0, separator)
                    if (key !in keys || key in values) return null
                    values[key] = decode(part.substring(separator + 1)) ?: return null
                }
                restore(values["kind"], values["id"], values["version"], values["snapshot"])
            } catch (_: URISyntaxException) {
                null
            }
        }

        /** Revalidate a normalized checkpoint; restored identity cannot acknowledge any write. */
        fun restore(kind: String?, itemId: String?, version: String?, snapshotHash: String?): PreviewComponentRoute? {
            if (kind == null || kind.length !in 1..64 || PreviewCatalogKinds.all.none { it.id == kind } ||
                itemId == null || !itemPattern.matches(itemId) ||
                version == null || version.length !in 1..MAX_VERSION_BYTES ||
                version.any { Character.isISOControl(it) } || !StandardCharsets.UTF_8.newEncoder().canEncode(version) ||
                version.toByteArray(StandardCharsets.UTF_8).size > MAX_VERSION_BYTES ||
                snapshotHash == null || !hashPattern.matches(snapshotHash)) return null
            return PreviewComponentRoute(kind, itemId, version, snapshotHash.lowercase(Locale.ROOT))
        }

        private fun decode(raw: String): String? {
            val bytes = ByteArrayOutputStream(raw.length)
            var index = 0
            while (index < raw.length) {
                val value = raw[index]
                if (value == '+') return null // A literal plus must be %2B, never form-decoded as a space.
                if (value == '%') {
                    if (index + 2 >= raw.length) return null
                    val high = raw[index + 1].digitToIntOrNull(16) ?: return null
                    val low = raw[index + 2].digitToIntOrNull(16) ?: return null
                    bytes.write(high * 16 + low)
                    index += 3
                } else {
                    bytes.write(value.code)
                    index++
                }
            }
            return try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
