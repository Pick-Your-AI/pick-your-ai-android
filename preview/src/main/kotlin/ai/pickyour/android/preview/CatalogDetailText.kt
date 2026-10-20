package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.preview.*
import ai.pickyour.core.pricing.RecordedPricebook

/** Plain bounded text only. Source strings are never linkified, resolved or launched. */
internal object CatalogDetailText {
    private const val MAX_VALUE = 320
    private const val MAX_PROVENANCE = 8

    fun literal(context: Context, raw: String?): String {
        val value = PreviewComparedValue.from(raw)
        val literal = when (value.kind) {
            PreviewComparedValueKind.ABSENT -> context.getString(R.string.catalog_detail_missing)
            PreviewComparedValueKind.EMPTY -> "\"\""
            PreviewComparedValueKind.TEXT -> value.quotedLiteral!!
        }
        return if (value.truncated) context.getString(R.string.catalog_detail_truncated, literal) else literal
    }

    private fun json(context: Context, raw: String): String {
        val safe = buildString {
            for (char in raw.take(MAX_VALUE)) {
                if (Character.isISOControl(char) || Character.isSurrogate(char) ||
                    Character.getType(char) == Character.FORMAT.toInt() || char == '\u2028' || char == '\u2029')
                    append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        val displayed = ltr(context, safe)
        return if (raw.length > MAX_VALUE) context.getString(R.string.catalog_detail_truncated, displayed) else displayed
    }

    fun fieldValue(context: Context, field: PreviewCatalogField): String = when (field.state) {
        PreviewCatalogValueState.MISSING -> context.getString(R.string.catalog_detail_missing)
        PreviewCatalogValueState.EXPLICIT_UNKNOWN -> context.getString(R.string.catalog_detail_explicit_unknown)
        PreviewCatalogValueState.EMPTY_COLLECTION -> context.getString(R.string.catalog_detail_empty_collection)
        PreviewCatalogValueState.CONTAINS_UNKNOWN -> context.getString(R.string.catalog_detail_contains_unknown,
            json(context, requireNotNull(field.valueJson)))
        PreviewCatalogValueState.SUPPLIED -> json(context, requireNotNull(field.valueJson))
    }

    fun describe(context: Context, detail: PreviewCatalogDetails, pricebook: RecordedPricebook? = null,
                 includeSpecifications: Boolean = true): String {
        fun text(raw: String?) = if (raw == null) literal(context, null) else ltr(context, literal(context, raw))
        fun fields(values: List<PreviewCatalogField>) = values.joinToString("\n") { field ->
            context.getString(R.string.catalog_detail_field, ltr(context, field.name), ltr(context, field.type), fieldValue(context, field))
        }
        val item = detail.item
        val sources = detail.provenance.take(MAX_PROVENANCE).mapIndexed { index, source ->
            val label = context.getString(when (source.label) {
                EvidenceLabel.INDEPENDENTLY_VERIFIED -> R.string.label_independently_verified
                EvidenceLabel.VERIFICATION_UNESTABLISHED -> R.string.label_verification_unestablished
                EvidenceLabel.UNVERIFIED -> R.string.label_unverified
                EvidenceLabel.USER_SUPPLIED -> R.string.label_user_supplied
                EvidenceLabel.ESTIMATED -> R.string.label_estimated
            })
            context.getString(R.string.catalog_detail_source, index + 1, text(source.sourceUri),
                text(source.checkedAt), ltr(context, source.rawStatus.encoded), label, text(source.notes))
        }
        return listOfNotNull(
            context.getString(R.string.item_details, text(item.id), ltr(context, item.kind), text(item.version)),
            context.getString(R.string.catalog_detail_snapshot, text(detail.snapshotId),
                ltr(context, detail.snapshotRevision.toString()), ltr(context, detail.snapshotHash)),
            context.getString(R.string.catalog_detail_dates, text(detail.snapshotCapturedAt), text(detail.updatedAt)),
            context.getString(R.string.catalog_detail_status, ltr(context, detail.status), ltr(context, detail.billingClass)),
            context.getString(R.string.catalog_detail_provider),
            context.getString(R.string.catalog_detail_description, text(detail.description)),
            if (includeSpecifications) context.getString(R.string.catalog_detail_specs, fields(detail.specs)) else null,
            context.getString(R.string.catalog_detail_license, fields(detail.license)),
            context.resources.getQuantityString(R.plurals.catalog_detail_source_count, detail.provenance.size,
                sources.size, detail.provenance.size),
            sources.joinToString("\n\n"),
            context.getString(R.string.evidence_support_unestablished),
            context.getString(R.string.evidence_pinned),
            context.getString(R.string.catalog_detail_price),
            CatalogPriceText.describe(context, item, pricebook),
            context.getString(R.string.catalog_detail_source_limits),
        ).joinToString("\n\n")
    }

    // Only escaped literals/JSON, closed schema tokens and canonical numeric/hash text reach this helper.
    private fun ltr(context: Context, value: String): String =
        BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            .unicodeWrap(value, TextDirectionHeuristics.LTR)
}
