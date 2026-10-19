# Catalog count presentation

This is a bounded presentation contribution to
[Android #65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65).
The canonical work-package acceptance path remains
`docs/delivery/android/work-packages/06.06.md` in `Pick-Your-AI/pickyour.ai`.
The Android UX/accessibility receiving role, prerequisites and broader
localization/branding requirements remain unchanged.

## Quantity and state

The preview uses Android
[quantity resources](https://developer.android.com/guide/topics/resources/string-resource#Plurals)
for the five complete count messages below. Both English `one` and `other`
forms retain positional placeholders, so a translation can reorder the whole
message without concatenating a translated noun onto a number.

| Message | Quantity used for selection and formatting |
| --- | --- |
| Catalog browse status | Total matching items in the current validated snapshot |
| Comparison limit caption | The existing fixed tray limit |
| Source chooser page title | Total retained provenance records for the exact item |
| Recorded-rate page title | Total rates matching the exact item ID and version |
| Recorded conditions summary | Count of the supplied, nonempty conditions collection |

The two paged dialogs keep their full record totals on later pages, including a
last page containing one row. Page indices do not choose the noun's plural form.
The unavailable catalog, empty search result, missing pricebook, absent exact
rate, absent conditions and explicit empty conditions each retain their existing
separate messages. An unavailable count is never converted into zero.

Count placeholders use the Android resource locale. Recorded monetary strings,
currency codes, meter IDs, source labels and eligibility qualifications retain
their existing literal or canonical presentation. Count localization does not
convert a currency, reformat recorded money, infer a free rate or establish
eligibility. The change does not alter catalog selection, pinned revision
identity, draft persistence or navigation authority.

The obsolete `catalog_search_waiting`, `catalog_search_count` and
`catalog_search_changed` resources are removed. Their replacements were already
used by the current browse section; repository reference inspection found no
remaining callers or dynamic resource-name lookups.

## Bounded verification

[CatalogQuantityActivityTest](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogQuantityActivityTest.kt)
uses actual simulated Activity controls and validated synthetic inputs. Its five
cases cover unavailable/zero/one/multiple counts, full totals across pages,
literal condition order, unchanged unsaved draft state, and Arabic-locale count
digits while recorded `0.80` and `USD` remain literal. Existing source-navigation
and recorded-price cases continue to cover current-window authority, explicit
unknown values and paging. The existing native-graphics price-control case
checks the affected dialogs at 200% font scale in an Arabic/RTL 320×640dp
configuration.

The [preview workflow](../.github/workflows/core.yml) requires all five new cases
to execute without failures, errors, skips or duplicate names and checks the
reported count against the actual cases. All earlier named regression gates are
retained. The opt-in unsigned build and lint also compile the quantity resources
and check their callers; packaged resource inspection can confirm that the three
obsolete names are absent.

These are local build and JVM-simulated presentation checks. The resource forms
supply English defaults and a translation-ready quantity boundary; they do not
provide translated product copy, a supported language/device matrix, installed
TalkBack evidence, complete RTL/expanded-text acceptance or original branding
assets. #65 acceptance remains open.
