# Exact and bounded cost presentation

The synthetic cost inspector now displays locale-specific decimal separators,
grouping and digits while keeping each original decimal string visible. This is
a presentation change for Android [#65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65)
and [#103](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/103).
It consumes the existing immutable `CostEstimateAdapter` result; it does not
produce a quote, join rates, change coverage, or update the editable draft.
The separately selected [recorded exact-product observation](RECORDED_COST_ARITHMETIC.md)
now adds bounded, unrounded quantity × unit-price observations beside the unchanged
declared amounts. It is not a new admission rule or a billing-validity verdict.

## Decimal and currency meaning

`CostInspectionText` takes the locale from the screen's resource configuration.
It passes the original `BigDecimal` to `DecimalFormat`, retains every supplied
fractional digit including trailing zeroes, permits the complete integer part,
and requires `RoundingMode.UNNECESSARY`. No binary floating-point conversion,
currency default precision, currency symbol substitution or exchange-rate
conversion occurs. For example, German display of recorded `1234.50` is
`1.234,50`, with canonical `"1234.50"` alongside it. A recorded JPY amount keeps
its supplied decimals; this formatter does not round it to a conventional
currency precision or assert a valid charge.

Amounts and quantities use this same rule. Currency codes, canonical meter IDs
and the declared period unit remain explicit. The recorded estimate timestamp
remains a quoted exact value; it is not translated to another timezone or used
to infer freshness. Long decimal strings beyond 160 UTF-16 units skip localized
formatting and show a bounded canonical excerpt with an explicit truncation
notice. That excerpt is not represented as the complete value.

The frozen schema also permits quantity strings such as `"720\n"`, which the
accepted reader deliberately retains literally. Such a quantity is not a
whole-string `ContractDecimal`. The display keeps its quoted, escaped recorded
value and explicitly says decimal display is unavailable. It neither removes
the newline nor throws while presenting the accepted record. This fallback uses
the same literal bounds and leaves the estimate, money validation, declared
amounts and evidence classifications unchanged.

This follows the current Android
[DecimalFormat reference](https://developer.android.com/reference/java/text/DecimalFormat)
and [BidiFormatter reference](https://developer.android.com/reference/android/text/BidiFormatter),
inspected on 2026-10-18. The latter wraps display fragments for surrounding text
direction after source-provided control and format characters have been escaped.
The formatter uses only existing platform APIs and adds no dependency.

## Literal text and bounded work

Identifiers, source timestamps, assumptions and warnings use the existing
`PreviewComparedValue` literal boundary: quotes and backslashes are escaped,
control/format characters and Unicode line/paragraph separators (U+2028/U+2029)
are visible escapes, and text is limited to 160 UTF-16 units with visible
truncation. A source string saying `None declared` remains
quoted data, distinct from the application's empty-list status. Source text
cannot inject a newline, directional override or unquoted status label into the
surrounding display. Legitimate text is not executed, linkified or navigated.

The summary displays at most eight cost lines and eight entries from each
assumption, warning or selection-ID collection. Translatable plural resources
show both displayed and retained counts. The declared subtotal, tax and total
still come from the complete inspected record, not a recomputation over the
displayed prefix. The record retains every line and original decimal string.
These are rendering bounds, not retention, accounting or policy limits.

The [complete recorded-cost browser](COMPLETE_COST_INSPECTION.md) now exposes every
retained line, assumption, warning, selection reference and input document in
bounded pages and complete escaped value parts. It uses the same immutable result
as this summary and preserves all of the numeric and evidence limits above.

Declared complete, partial and unknown coverage retain their existing meanings.
Unclassified selections remain visible; an empty list, a recorded zero and
producer-declared complete coverage do not establish free/included treatment,
complete meter coverage, freshness or a passing budget.

## Reproduction and remaining acceptance

The dedicated simulated Android resource tests cover wide exact decimals,
twelve fractional places, tiny nonzero values, trailing zeroes, JPY precision,
German and Arabic screen locales, independence from the process default locale,
hostile literal strings including U+2028/U+2029 without changing the retained
record, truncation, bounded collections, totals retaining all lines, and unknown
coverage. An accepted terminal-newline quantity reaches the native formatter
without crashing or changing the retained quantity, estimate or budget status.
This regression failed with `ContractRejected(INVALID_DECIMAL)` at that actual
formatter boundary on accepted main `80f8c9f5a8e682874131a603002c44046583bbde`
before the fallback was added. The existing retired-Activity cost callback test
continues to cover the actual inspector adoption. The hosted report gate now
requires every dedicated cost case to execute without failures or skips.

With the documented JDK and Android toolchain:

```sh
./gradlew --offline --no-daemon --dependency-verification=strict \
  -PandroidPreview=true :core:check :core:assemble \
  :preview:testDebugUnitTest :preview:assembleDebug :preview:lintDebug
```

These checks exercise source and simulated resources. They do not establish
installed screen-reader traversal, physical RTL layout, an accepted language or
device matrix, real pricing acceptance, complete product localization or brand
approval. Canonical acceptance records remain
`docs/delivery/android/work-packages/06.06.md` and `10.03.md` in
`Pick-Your-AI/pickyour.ai`; this focused implementation does not close either
whole work package.
