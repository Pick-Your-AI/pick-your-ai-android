package ai.pickyour.core.preview

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.localSnapshotRevision
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** Exact frozen registry identifiers; these are fallback labels, not new product terminology. */
data class PreviewCatalogKind(val id: String, val tier: String)

object PreviewCatalogKinds {
    val all: List<PreviewCatalogKind> by lazy {
        val root = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/schemas/component-registry.json")).use {
            (BoundedJsonReader().read(it) as JsonInputResult.Parsed).value
        }
        Collections.unmodifiableList(root.path("kinds").properties().map { (id, value) ->
            PreviewCatalogKind(id, value.path("tier").asText())
        })
    }
    val tiers: List<String> get() = Collections.unmodifiableList(all.map { it.tier }.distinct())
}

data class PreviewCatalogItem(val id: String, val name: String, val kind: String, val version: String, val tier: String)
enum class PreviewCatalogSort { FIXTURE, NAME, KIND }

/** Small transient UI state only. It grants no draft mutation or catalog publication authority. */
data class PreviewCatalogBrowseState(
    val query: String = "",
    val tier: String? = null,
    val kind: String? = null,
    val sort: PreviewCatalogSort = PreviewCatalogSort.FIXTURE,
    val page: Int = 0,
) {
    init {
        require(query.length <= PreviewCatalogQuery.MAX_LENGTH)
        require(tier == null || tier in PreviewCatalogKinds.tiers)
        require(kind == null || PreviewCatalogKinds.all.any { it.id == kind })
        require(page in 0..25)
    }
    override fun toString() = "PreviewCatalogBrowseState"
}

data class PreviewCatalogPage(val items: List<PreviewCatalogItem>, val total: Int, val page: Int, val pages: Int)

/** Bounded, in-memory browsing of an already validated synthetic snapshot, with no remote paging. */
class PreviewCatalogBrowser private constructor(val hash: String, entries: List<PreviewCatalogItem>,
    details: Map<Pair<String, String>, PreviewCatalogDetails>,
) {
    constructor(hash: String, entries: List<PreviewCatalogItem>) : this(hash, entries, emptyMap())
    val items: List<PreviewCatalogItem> = Collections.unmodifiableList(entries.toList())
    private val details = Collections.unmodifiableMap(details.toMap())

    init {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        require(items.size <= 256)
        require(items.map { it.id to it.version }.toSet().size == items.size) // Reject ambiguous exact pairs, retain distinct versions.
        require(items.all { item -> PreviewCatalogKinds.all.any { it.id == item.kind && it.tier == item.tier } })
    }

    fun browse(state: PreviewCatalogBrowseState): PreviewCatalogPage {
        val query = PreviewCatalogQuery(state.query)
        val names = compareBy<PreviewCatalogItem> { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.name }.thenBy { it.id }.thenBy { it.version }
        val filtered = items.filter { item ->
            (state.tier == null || state.tier == item.tier) && (state.kind == null || state.kind == item.kind) &&
                query.matches(item.id, item.name, item.kind, item.version)
        }
        val matching = when (state.sort) {
            PreviewCatalogSort.FIXTURE -> filtered
            PreviewCatalogSort.NAME -> filtered.sortedWith(names)
            PreviewCatalogSort.KIND -> filtered.sortedWith(compareBy<PreviewCatalogItem> { it.tier }.thenBy { it.kind }.then(names))
        }
        val pages = maxOf(1, (matching.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = minOf(state.page, pages - 1)
        val start = page * PAGE_SIZE
        return PreviewCatalogPage(Collections.unmodifiableList(matching.subList(start, minOf(start + PAGE_SIZE, matching.size)).toList()),
            matching.size, page, pages)
    }

    fun contains(item: PreviewCatalogItem, snapshotHash: String) = hash == snapshotHash && item in items

    /** No latest-version fallback, name-only match or details for manually constructed browse rows. */
    fun details(item: PreviewCatalogItem, snapshotHash: String): PreviewCatalogDetails? =
        if (contains(item, snapshotHash)) details[item.id to item.version] else null

    companion object {
        const val PAGE_SIZE = 10

        fun read(bytes: ByteArray): PreviewCatalogBrowser? {
            if (bytes.size > 1_048_576) return null
            if (FrozenSchemaValidator().validate(FrozenDocument.CATALOG, bytes.inputStream()) != FrozenValidation.Valid) return null
            val root = (BoundedJsonReader().read(bytes.inputStream()) as? JsonInputResult.Parsed)?.value ?: return null
            if (localSnapshotRevision(root["revision"]) == null) return null
            if (root.path("items").size() > 256) return null
            val entries = root.path("items").map { item ->
                PreviewCatalogItem(item.path("id").asText(), item.path("name").asText(), item.path("kind").asText(),
                    item.path("version").asText(), item.path("tier").asText())
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            return try {
                val details = root.path("items").zip(entries).associate { (value, item) ->
                    (item.id to item.version) to PreviewCatalogDetails.from(root, value, item, hash)
                }
                PreviewCatalogBrowser(hash, entries, details)
            } catch (_: IllegalArgumentException) { null }
        }
    }
}
