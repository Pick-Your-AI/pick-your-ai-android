package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.view.View
import ai.pickyour.core.drafts.LocalSelection
import ai.pickyour.core.preview.PreviewCatalogItem

/** Presentation strings only. Display direction never becomes a selection or persistence key. */
internal object ComponentSelectionText {
    fun catalog(context: Context, item: PreviewCatalogItem): String =
        format(context, R.string.component_catalog_choice, item.kind, item.id, item.version)

    fun catalogIdentity(context: Context, item: PreviewCatalogItem): String =
        format(context, R.string.component_catalog_identity, item.id, item.version)

    fun selection(context: Context, selection: LocalSelection): String =
        format(context, R.string.component_saved_choice, selection.kind, selection.role, selection.id)

    fun format(context: Context, resource: Int, vararg values: String): String {
        val bidi = BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        // A catalog version is free-form text, not necessarily an LTR semantic-version token.
        // Let each value keep its natural direction; only the resource owns prose and word order.
        return context.getString(resource, *values.map { bidi.unicodeWrap(it) }.toTypedArray())
    }

    fun indexed(context: Context, resource: Int, index: Int, vararg values: String): String {
        val bidi = BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        // Keep the number typed so the resource locale formats it; isolate only displayed text values.
        return context.getString(resource, index, *values.map { bidi.unicodeWrap(it) }.toTypedArray())
    }
}
