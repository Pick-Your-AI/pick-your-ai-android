package ai.pickyour.android.preview

import ai.pickyour.core.preview.PreviewCatalogBrowser
import ai.pickyour.core.pricing.RecordedPricebook
import ai.pickyour.core.pricing.RecordedPricebookResult
import java.io.InputStream

/** Read together on the worker, then replace together on the UI thread; a failed read retains no old pricebook. */
internal class CatalogFixtureData private constructor(
    val catalog: PreviewCatalogBrowser?,
    val pricebook: RecordedPricebook?,
) {
    companion object {
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
