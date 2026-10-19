package ai.pickyour.core.preview

import java.util.Collections

/** Small transient identity only. A pin is never a draft selection or evidence of an available row. */
data class PreviewCatalogPin(val id: String, val version: String, val snapshotHash: String) {
    init {
        require(id.isNotEmpty() && id.length <= 160)
        require(version.isNotEmpty() && version.length <= 128)
        require(snapshotHash.matches(Regex("[a-f0-9]{64}")))
    }
    override fun toString() = "PreviewCatalogPin"
}

enum class PreviewCatalogTrayResult { ADDED, ALREADY_PRESENT, FULL, UNAVAILABLE, IDENTITY_TOO_LARGE }
class PreviewCatalogTrayUpdate(val tray: PreviewCatalogTray, val result: PreviewCatalogTrayResult)
class PreviewCatalogTrayEntry(val pin: PreviewCatalogPin, val detail: PreviewCatalogDetails?)

/** Two is a bounded preview decision, not adoption of the website or production mobile limit. */
class PreviewCatalogTray(pins: List<PreviewCatalogPin> = emptyList()) {
    val pins: List<PreviewCatalogPin> = Collections.unmodifiableList(pins.toList())
    init { require(pins.size <= LIMIT && pins.distinct().size == pins.size) }

    fun add(browser: PreviewCatalogBrowser, item: PreviewCatalogItem, hash: String): PreviewCatalogTrayUpdate {
        val detail = browser.details(item, hash)
            ?: return PreviewCatalogTrayUpdate(this, PreviewCatalogTrayResult.UNAVAILABLE)
        val pin = try { PreviewCatalogPin(detail.item.id, detail.item.version, detail.snapshotHash) }
            catch (_: IllegalArgumentException) { return PreviewCatalogTrayUpdate(this, PreviewCatalogTrayResult.IDENTITY_TOO_LARGE) }
        if (pin in pins) return PreviewCatalogTrayUpdate(this, PreviewCatalogTrayResult.ALREADY_PRESENT)
        if (pins.size == LIMIT) return PreviewCatalogTrayUpdate(this, PreviewCatalogTrayResult.FULL)
        return PreviewCatalogTrayUpdate(PreviewCatalogTray(pins + pin), PreviewCatalogTrayResult.ADDED)
    }

    fun remove(pin: PreviewCatalogPin) = PreviewCatalogTray(pins.filterNot { it == pin })

    /** A new snapshot cannot silently replace an unavailable pinned version, even if its labels match. */
    fun entries(browser: PreviewCatalogBrowser?): List<PreviewCatalogTrayEntry> = Collections.unmodifiableList(pins.map { pin ->
        val item = browser?.items?.singleOrNull { it.id == pin.id && it.version == pin.version }
        PreviewCatalogTrayEntry(pin, if (item == null) null else browser?.details(item, pin.snapshotHash))
    })

    companion object { const val LIMIT = 2 }
    override fun toString() = "PreviewCatalogTray"
}

enum class PreviewCatalogComparisonGroup { SPECIFICATION, LICENSE }
enum class PreviewCatalogComparisonMatch { SAME_RECORDED_VALUE, DIFFERENT_RECORDED_VALUE, UNKNOWN, NOT_COMPARABLE }

class PreviewCatalogComparisonRow internal constructor(
    val group: PreviewCatalogComparisonGroup,
    val name: String,
    val left: PreviewCatalogField?,
    val right: PreviewCatalogField?,
    val match: PreviewCatalogComparisonMatch,
)

/**
 * Compares recorded values in the same frozen kind and field, with their existing canonical units.
 * No rate/measurement conversion, capability equivalence, source assessment or ranking is inferred.
 */
class PreviewCatalogComparison(val left: PreviewCatalogDetails, val right: PreviewCatalogDetails) {
    val sameKind = left.item.kind == right.item.kind
    val rows: List<PreviewCatalogComparisonRow>

    init {
        require(left.item.id != right.item.id || left.item.version != right.item.version || left.snapshotHash != right.snapshotHash)
        fun section(group: PreviewCatalogComparisonGroup, first: List<PreviewCatalogField>, second: List<PreviewCatalogField>) =
            (first.map { it.name } + second.map { it.name }).distinct().map { name ->
                val a = first.singleOrNull { it.name == name }
                val b = second.singleOrNull { it.name == name }
                val match = when {
                    !sameKind || a == null || b == null || a.type != b.type -> PreviewCatalogComparisonMatch.NOT_COMPARABLE
                    a.state != PreviewCatalogValueState.SUPPLIED || b.state != PreviewCatalogValueState.SUPPLIED ->
                        PreviewCatalogComparisonMatch.UNKNOWN
                    sameRecordedValue(a, b) -> PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE
                    else -> PreviewCatalogComparisonMatch.DIFFERENT_RECORDED_VALUE
                }
                PreviewCatalogComparisonRow(group, name, a, b, match)
            }
        rows = Collections.unmodifiableList(section(PreviewCatalogComparisonGroup.SPECIFICATION, left.specs, right.specs) +
            section(PreviewCatalogComparisonGroup.LICENSE, left.license, right.license))
    }

    /** Unknown and non-comparable rows remain visible; unknown equality is never manufactured. */
    fun visibleRows(differencesOnly: Boolean): List<PreviewCatalogComparisonRow> = if (!differencesOnly) rows else
        Collections.unmodifiableList(rows.filter { it.match != PreviewCatalogComparisonMatch.SAME_RECORDED_VALUE })

    private fun sameRecordedValue(first: PreviewCatalogField, second: PreviewCatalogField): Boolean {
        // JSON numeric spelling can differ without changing a recorded canonical-unit value.
        // BigDecimal comparison preserves arbitrary precision; it performs no unit conversion.
        if (first.type == "number" || first.type == "integer") {
            val a = requireNotNull(first.valueJson).toBigDecimalOrNull() ?: return false
            val b = requireNotNull(second.valueJson).toBigDecimalOrNull() ?: return false
            return a.compareTo(b) == 0
        }
        return first.valueJson == second.valueJson
    }
    override fun toString() = "PreviewCatalogComparison"
}
