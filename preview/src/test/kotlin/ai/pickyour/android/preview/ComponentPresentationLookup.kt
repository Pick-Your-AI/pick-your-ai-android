package ai.pickyour.android.preview

import android.content.Context
import android.text.BidiFormatter
import android.view.View

/** Navigation only for existing fixture IDs/kinds; presentation assertions use literal expected text. */
internal fun componentTokenForLookup(context: Context, value: String): String =
    BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        .unicodeWrap(value)
