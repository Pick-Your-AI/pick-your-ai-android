# Catalog summary counts and bidirectional source values

This bounded contribution supports [Android #65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65).
It applies to the existing catalog-detail and recorded-price **summaries**. The
complete-value browsers keep their existing literal paging, callbacks and source
representation. No record, draft, source permission or pricing decision changes.

## Summary behavior

The provenance, exact-price-match and nonempty condition counts use Android
plural resources. The quantity is the complete retained total, while the first
format argument is the bounded displayed count. One retained record or condition
uses singular copy; zero exact matches and multiple retained entries use the
appropriate resource category. The existing plural wording and eight-entry caps
are preserved. No exact match remains different from an unavailable pricebook.
Absent conditions and an explicit empty array keep their separate descriptions.

Recorded literals are escaped before trusted direction controls are added to the
display. Canonical JSON, identifiers, opaque versions, dates, amounts, currency
codes and closed schema tokens retain their spelling. The formatter follows the
screen configuration's layout direction, including an English locale with forced
RTL, instead of the process's default locale. App-owned missing-value and evidence
labels remain localized messages. No formatted text returns to stored records.

The full-value browser's `CatalogDetailText.literal` contract stays unchanged;
summary presentation does not add direction marks to its complete paged values.
Long summary values still disclose truncation and retain their original data.

This follows Android's [bidirectional formatting guidance](https://developer.android.com/training/basics/supporting-devices/languages#format-text)
and [framework BidiFormatter API](https://developer.android.com/reference/android/text/BidiFormatter),
read on 2026-10-19. These source references explain presentation behavior; they
provide no installed-device, pixel-layout or assistive-technology evidence.

## Verification and limits

`CatalogSummaryPresentationTest` uses accepted synthetic catalog and pricebook
inputs, the existing adapters, actual Android resources and Robolectric API 28.
Its seven methods cover singular/plural and bounded counts, zero exact matches,
missing/empty/zero distinctions, escaped RTL input, an independent process locale,
forced RTL layout, unchanged canonical money/snapshot values, truncation and the
unchanged complete-value literal boundary. The existing catalog quantity, detail
browser, price browser and Activity summary tests remain intact. The workflow
requires every new method to execute without failures, errors, skips or duplicates.

The test-only source on accepted `517025db301785f8e3689abdeeb47fc8d09b9afc`
ran six methods on 2026-10-19 before production changes: the singular-copy and
missing-RTL-boundary assertions failed, while four preservation controls passed.
There were no errors or skips. An earlier attempt retained two additional fixture
setup failures; they were corrected by respecting the frozen minimum array sizes
and representing zero exact matches with an accepted foreign rate. The initial
restricted Gradle invocation stopped at its loopback socket before executing tests.
These original attempts are retained separately from later corrected execution.

Whole #65 remains open for its full copy, original branding, date/unit
presentation, expanded-text/RTL journey, installed accessibility and receiving
criteria. The canonical `docs/delivery/android/work-packages/06.06.md` record in
`Pick-Your-AI/pickyour.ai` is unchanged. This technical note moves no acceptance
path and supplies no translation launch, currency conversion, source-use right,
real price or release qualification.
