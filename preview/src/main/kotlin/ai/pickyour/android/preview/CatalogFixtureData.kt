package ai.pickyour.android.preview

import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
import java.io.InputStream

/** Closed Help display states; no raw record identifier, version, digest or exception enters the UI. */
internal enum class HelpCatalogIdentity { BUNDLED_V1, UNRECOGNIZED, UNAVAILABLE_OR_LOADING }

/** Read together on the worker, then replace together on the UI thread; a failed read retains no old pricebook. */
internal class CatalogFixtureData private constructor(
    val catalog: PreviewCatalogBrowser?,
    val pricebook: RecordedPricebook?,
) {
    // This private container is created only by read(). The public browse-row constructor
    // cannot supply this projection; labels require the existing reader's exact original-byte hash.
    val helpIdentity: HelpCatalogIdentity = when (catalog?.hash) {
        null -> HelpCatalogIdentity.UNAVAILABLE_OR_LOADING
        BUNDLED_CATALOG_SHA256 -> HelpCatalogIdentity.BUNDLED_V1
        else -> HelpCatalogIdentity.UNRECOGNIZED
    }

    companion object {
        // Public frozen examples/catalog.json, pinned by contracts/v1/IMPORT_MANIFEST.json.
        // This value is comparison-only: neither it nor a different catalog's hash is displayed.
        private const val BUNDLED_CATALOG_SHA256 = "2e9120db823faef3e1e330a7d2e8067b3ab064328e2faed699d15cf41880fbf8"

        fun read(catalogBytes: () -> ByteArray, pricebookInput: () -> InputStream): CatalogFixtureData {
            val catalog = try { PreviewCatalogBrowser.read(catalogBytes()) } catch (_: Exception) { null }
            val prices = if (catalog == null) null else try {
                pricebookInput().use { RecordedPricebook.read(it) }
                    .let { (it as? RecordedPricebookResult.Parsed)?.pricebook }
            } catch (_: Exception) { null }
            return CatalogFixtureData(catalog, prices)
        }
    }
}
