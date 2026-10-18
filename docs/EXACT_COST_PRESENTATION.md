# Exact locale presentation in the cost inspector

Partial [Android #65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65),
using the existing bounded [cost inspection](COST_ESTIMATE_INSPECTION.md) boundary
from [#103](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/103). This
change addresses decimal, quantity and period presentation in the synthetic
inspector. It does not change the accepted pricing model, serialized records or
the editable guest draft.

## Recorded values and displayed values

`ExactNumberFormatter` uses an explicit locale and `BigDecimal`/`BigInteger`
through `DecimalFormat`. Every integer digit and supplied fractional digit is
retained, including trailing zeroes and all twelve supported decimal places.
No binary floating point, rounding, grouping or currency conversion occurs.
For example, the same recorded `578.50` appears as `578.50`, `578,50` or
`٥٧٨٫٥٠` under US English, German or Arabic number conventions. The recorded
`ContractDecimal.encoded` string stays `578.50`.

`CostInspectionText` takes the locale from the current Android resources, not a
cached global default. Summary amounts, line amounts, unit prices, declared
quantities and day counts use this formatter. An externalized money pattern
keeps the original ISO currency code with the exact displayed amount; it never
substitutes a currency symbol or applies currency-specific rounding. Android's
bidirectional formatter isolates each numeric/currency expression from the
surrounding text. Identifiers and recorded timestamps keep their original form.

Day counts that fit Android's `Int` quantity API use a plural resource. Larger
frozen integers use the quantity-neutral wording “Declared period in days” with
the complete localized number. No narrowing, modulo approximation or fabricated
plural category occurs. Translation context describes these placeholders in the
resource file. This is localization readiness, not an additional language launch.

The renderer retains the existing declared-coverage, unknown/unclassified,
freshness, free/included and budget limitations. It cannot turn a recorded zero
into free service, establish a complete price or alter the estimate's currency.
Loading, rejected and unavailable outcomes keep their existing recovery behavior.

## Verification and remaining acceptance

Two actual paused-looper Activity regressions failed against unchanged source:
German expected `578,50 USD`, and Arabic expected `٥٧٨٫٥٠ USD`. The corrected
inspector cases also check an explicitly different process-default locale,
recreation, retained unsaved fields and an unchanged acknowledged revision.
Another resource-backed case covers singular/plural periods, a count above the
32-bit range, 512 integer digits, twelve decimal places, exact quantities and
unchanged canonical JSON/currency. Core regressions cover the same precision and
locale boundaries without Android.

All existing Activity regressions remain required, and the workflow explicitly
requires these three new cases. Run the strict core/unsigned-preview build, lint
and Activity suite described in [ANDROID_PREVIEW.md](ANDROID_PREVIEW.md), plus
the frozen/reference checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md).

This is local and simulated-Android evidence. Whole-app translation/plural
coverage, localized date/unit presentation, pseudolocalized and RTL visual review,
screen-reader behavior, approved branding and supported installed-device review
remain open under #65. No full #65 or #103 criterion is closed. The canonical
receiving path remains `docs/delivery/android/work-packages/06.06.md` in
`Pick-Your-AI/pickyour.ai`.

The implementation follows Android's [DecimalFormat
reference](https://developer.android.com/reference/java/text/DecimalFormat) for
arbitrary-precision inputs and explicit digit counts, its [quantity-string
guidance](https://developer.android.com/guide/topics/resources/string-resource#Plurals)
for plural selection and quantity-neutral alternatives, and the
[BidiFormatter API](https://developer.android.com/reference/android/text/BidiFormatter)
for surrounding-text isolation (checked 2026-10-18). These references describe
platform behavior; the test receipts establish only the scoped execution above.
