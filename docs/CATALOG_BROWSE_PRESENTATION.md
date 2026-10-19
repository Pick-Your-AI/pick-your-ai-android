# Catalog browse presentation

This is a bounded continuation of
[Android #65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65)
for the accepted synthetic Kotlin/Views preview. It externalizes the complete
tier/kind choice pattern and keeps mixed-direction catalog rows and selected
facets readable in the screen's text direction. It does not complete the whole
localization work package or change the language-launch scope.

## Display and identity

[`CatalogBrowseSection`](../preview/src/main/kotlin/ai/pickyour/android/preview/CatalogBrowseSection.kt)
uses the already accepted
[`ComponentSelectionText.format`](../preview/src/main/kotlin/ai/pickyour/android/preview/ComponentSelectionText.kt).
That helper selects Android's `BidiFormatter` from the Activity's resource layout
direction, including an explicitly forced RTL screen. Each raw value keeps its
natural direction; an Arabic catalog name is not forced into an LTR fragment.
The resource owns the surrounding wording and the order of numbered placeholders.
The process-wide default locale does not select the wrapping context.

| Display | Raw fragments wrapped for the screen | Resource-owned prose |
| --- | --- | --- |
| Catalog result row | Exact item name and canonical kind | Complete `item_button` pattern |
| Selected tier or kind facet | Exact non-null tier or kind | Complete `catalog_tier` or `catalog_kind` pattern |
| Canonical kind choice | Exact tier and kind, wrapped separately | Complete `catalog_browse_kind_choice` pattern |
| All tiers, All kinds and sort choices | None | Existing translated labels and sort patterns |

The new
[`catalog_browse_presentation.xml`](../preview/src/main/res/values/catalog_browse_presentation.xml)
includes translator context for both placeholders. Tier-only picker values keep
their existing literal labels. Existing count plurals and all pricing, units,
outcome and severity presentation are unchanged.

Formatting occurs only when constructing displayed strings. The captured
`PreviewCatalogItem` object, exact item/version pair and snapshot hash pass to the
existing detail callback unchanged. Filtering, sorting, checked indices and Bundle
fields continue to use raw query/tier/kind/sort/hash values. The existing browser
identity, rendered-row token, enabled-state and Activity/section lifetime checks
still decide whether a callback is current. No save, catalog publication,
permission, network or evaluation capability is added.

Android documents the wrapping behavior in
[`BidiFormatter.unicodeWrap`](https://developer.android.com/reference/android/text/BidiFormatter#unicodeWrap(java.lang.String))
and the explicit screen-context constructor in
[`getInstance(boolean)`](https://developer.android.com/reference/android/text/BidiFormatter#getInstance(boolean))
(consulted 2026-10-19). This use preserves text and adds directional formatting;
it is not a source validator or an HTML-escaping boundary.

## Regression scope

[`CatalogBrowsePresentationTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogBrowsePresentationTest.kt)
runs four JUnit methods with Robolectric 4.17 and the pinned API-28 runtime:

| Method | Behavior checked |
| --- | --- |
| `mixedDirectionRowsPreserveExactCatalogObjectsAndSnapshot` | Literal Arabic-in-LTR row output, exact object/version/hash dispatch, unavailable and replaced row retirement |
| `forcedRtlFacetsAndKindChoicesKeepCanonicalSavedState` | English resources with a forced RTL screen and a different process locale; complete kind choices, checked index, cancellation, exact canonical Bundle values and unwrapped translated All labels |
| `rtlRowsAndRecreatedChoicesKeepNaturalNamesAndRetireOldControls` | Natural Arabic names and wrapped LTR fragments; raw search/checkpoint reconstruction after Activity destruction; retired row/dialog callbacks and current exact detail dispatch |
| `ltrRowsAndResourceChoicesPreserveAllLabelsAndClosedState` | Original LTR appearance, All/sort labels, resource-owned kind choices, canceled callbacks and closed-section retirement |

Expected directional markers are literal API-28 values in the tests; expected
text does not call the production formatter. Catalog-row navigation helpers in
the existing
[`PreviewActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/PreviewActivityTest.kt),
[`CatalogComparisonValueActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogComparisonValueActivityTest.kt),
[`CatalogDetailBrowserActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogDetailBrowserActivityTest.kt),
[`CatalogPriceBrowserActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogPriceBrowserActivityTest.kt)
and [`CatalogPinRecoveryActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogPinRecoveryActivityTest.kt)
find the same synthetic rows inside the catalog section, allowing directional
markers around their names. Every existing selection, layout, accessibility,
inspection and durable-state assertion is retained.

The normal native CI job requires each of the four new class-qualified methods
exactly once in its XML report, with matching suite totals and no failed, errored
or skipped case. Existing catalog row-authority and checked-choice guards remain.
A focused local command is:

```sh
./gradlew --offline --no-daemon -PandroidPreview=true \
  :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.CatalogBrowsePresentationTest
```

The synthetic row tests exercise native resources and controls in JVM simulation.
Their Bundle reconstruction is not physical process-death evidence. Installed
mixed-direction rendering, pseudolocalized expanded-text journeys, TalkBack and
physical-device review remain separate acceptance work. The earlier installed
smoke cases do not become new installed catalog-bidi cases through this change.
The complete #65 criteria and canonical acceptance record remain open until
their own reviewed evidence is available.
