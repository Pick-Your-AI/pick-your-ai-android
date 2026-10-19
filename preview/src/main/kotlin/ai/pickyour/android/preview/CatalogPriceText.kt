package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
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
                PreviewComparedValueKind.ABSENT -> return context.getString(R.string.catalog_detail_missing)
                PreviewComparedValueKind.EMPTY -> "\"\""
                PreviewComparedValueKind.TEXT -> value.quotedLiteral!!
            }
            val displayed = ltr(context, literal)
            return if (value.truncated) context.getString(R.string.catalog_detail_truncated, displayed) else displayed
        }
        if (book == null) return context.getString(R.string.catalog_price_unavailable)
        val rates = book.ratesFor(item.id, item.version)
        val shown = rates.take(MAX_RATES)
        val records = shown.mapIndexed { index, rate ->
            val recordedConditions = rate.conditions
            val conditions = when {
                recordedConditions == null -> context.getString(R.string.catalog_price_conditions_missing)
                recordedConditions.isEmpty() -> context.getString(R.string.catalog_price_conditions_empty)
                else -> context.resources.getQuantityString(R.plurals.catalog_price_conditions, recordedConditions.size,
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
                context.getString(R.string.catalog_price_units, ltr(context, rate.meter), text(rate.unitPrice.amount.encoded),
                    ltr(context, rate.unitPrice.currency), text(rate.region), ltr(context, rate.priceType)),
                context.getString(R.string.catalog_price_dates, text(rate.validFrom), text(rate.validUntil)),
                conditions,
                context.getString(R.string.catalog_price_source, text(source.sourceUri), text(source.checkedAt),
                    ltr(context, source.rawStatus.encoded), label, text(source.notes)),
            ).joinToString("\n")
        }
        return listOf(
            context.getString(R.string.catalog_price_notice),
            context.getString(R.string.catalog_price_snapshot, text(book.id), ltr(context, book.revision.toString()),
                text(book.capturedAt), ltr(context, book.hash)),
            context.resources.getQuantityString(R.plurals.catalog_price_count, rates.size, shown.size, rates.size),
            if (rates.isEmpty()) context.getString(R.string.catalog_price_no_record) else records.joinToString("\n\n"),
            context.getString(R.string.catalog_price_limits),
        ).joinToString("\n\n")
    }

    // Direction controls are presentation only; they are added after literal escaping, never to recorded data.
    private fun ltr(context: Context, value: String): String =
        BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            .unicodeWrap(value, TextDirectionHeuristics.LTR)
}
