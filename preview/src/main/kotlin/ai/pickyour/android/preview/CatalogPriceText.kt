package ai.pickyour.android.preview

import android.content.Context
import ai.pickyour.core.evidence.EvidenceLabel
import ai.pickyour.core.preview.PreviewCatalogItem
import ai.pickyour.core.preview.PreviewComparedValue
import ai.pickyour.core.preview.PreviewComparedValueKind
import ai.pickyour.core.pricing.RecordedPricebook

/** Bounded literal metadata from an exact item/version join. No eligibility, quote or URL dispatch. */
internal object CatalogPriceText {
    private const val MAX_RATES = 8
    private const val MAX_CONDITIONS = 8

    fun describe(context: Context, item: PreviewCatalogItem, book: RecordedPricebook?): String {
        fun text(raw: String?): String {
            val value = PreviewComparedValue.from(raw)
            val literal = when (value.kind) {
                PreviewComparedValueKind.ABSENT -> context.getString(R.string.catalog_detail_missing)
                PreviewComparedValueKind.EMPTY -> "\"\""
                PreviewComparedValueKind.TEXT -> value.quotedLiteral!!
            }
            return if (value.truncated) context.getString(R.string.catalog_detail_truncated, literal) else literal
        }
        if (book == null) return context.getString(R.string.catalog_price_unavailable)
        val rates = book.ratesFor(item.id, item.version)
        val shown = rates.take(MAX_RATES)
        val records = shown.mapIndexed { index, rate ->
            val recordedConditions = rate.conditions
            val conditions = when {
                recordedConditions == null -> context.getString(R.string.catalog_price_conditions_missing)
                recordedConditions.isEmpty() -> context.getString(R.string.catalog_price_conditions_empty)
                else -> context.getString(R.string.catalog_price_conditions,
                    minOf(recordedConditions.size, MAX_CONDITIONS), recordedConditions.size,
                    recordedConditions.take(MAX_CONDITIONS).mapIndexed { position, condition ->
                        context.getString(R.string.catalog_price_condition, position + 1, text(condition))
                    }.joinToString("\n"))
            }
            val source = rate.provenance
            val label = context.getString(when (source.label) {
                EvidenceLabel.INDEPENDENTLY_VERIFIED -> R.string.label_independently_verified
                EvidenceLabel.VERIFICATION_UNESTABLISHED -> R.string.label_verification_unestablished
                EvidenceLabel.UNVERIFIED -> R.string.label_unverified
                EvidenceLabel.USER_SUPPLIED -> R.string.label_user_supplied
                EvidenceLabel.ESTIMATED -> R.string.label_estimated
            })
            listOf(
                context.getString(R.string.catalog_price_rate, index + 1, text(rate.id), text(rate.itemId), text(rate.itemVersion)),
                context.getString(R.string.catalog_price_units, rate.meter, text(rate.unitPrice.amount.encoded),
                    rate.unitPrice.currency, text(rate.region), rate.priceType),
                context.getString(R.string.catalog_price_dates, text(rate.validFrom), text(rate.validUntil)),
                conditions,
                context.getString(R.string.catalog_price_source, text(source.sourceUri), text(source.checkedAt),
                    source.rawStatus.encoded, label, text(source.notes)),
            ).joinToString("\n")
        }
        return listOf(
            context.getString(R.string.catalog_price_notice),
            context.getString(R.string.catalog_price_snapshot, text(book.id), book.revision.toString(), text(book.capturedAt), book.hash),
            context.getString(R.string.catalog_price_count, shown.size, rates.size),
            if (rates.isEmpty()) context.getString(R.string.catalog_price_no_record) else records.joinToString("\n\n"),
            context.getString(R.string.catalog_price_limits),
        ).joinToString("\n\n")
    }
}
