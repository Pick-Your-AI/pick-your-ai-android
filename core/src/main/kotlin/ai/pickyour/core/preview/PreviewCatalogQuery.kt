package ai.pickyour.core.preview

import java.util.Locale

/** Bounded literal lookup over the bundled preview, never a catalog/API search contract. */
class PreviewCatalogQuery(val text: String) {
    init { require(text.length <= MAX_LENGTH) }
    private val needle = text.trim().lowercase(Locale.ROOT)

    /** Each field is searched independently. Results retain their original order and exact pins. */
    fun matches(id: String, name: String, kind: String, version: String): Boolean =
        needle.isEmpty() || listOf(id, name, kind, version).any { needle in it.lowercase(Locale.ROOT) }

    override fun toString() = "PreviewCatalogQuery"
    companion object { const val MAX_LENGTH = 120 }
}
