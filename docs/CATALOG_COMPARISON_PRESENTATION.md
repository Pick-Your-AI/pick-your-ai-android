# Component comparison captions

This is a bounded [#65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65)
presentation continuation for the synthetic Kotlin/Views preview. It makes the
existing component-comparison identities and Remove captions use the screen's
text direction. The broader [component comparison](CATALOG_COMPARISON.md) and
[complete-value inspection](COMPLETE_COMPARISON_VALUES.md) contracts remain in
place; this does not complete #65 or #111.

## Display boundary

[`ComponentSelectionText.indexed`](../preview/src/main/kotlin/ai/pickyour/android/preview/ComponentSelectionText.kt)
uses Android's `BidiFormatter` with the Activity resource configuration's layout
direction. Each already escaped displayed value retains its natural direction.
An Arabic or Hebrew name/version is not assumed to be an LTR identifier. The
process-wide default locale does not decide the wrapping context.

The component number remains an `Int` passed to the existing numbered resource
placeholder. The resource locale formats that number, and the resource pattern
owns the surrounding wording and placeholder order. There is no translation or
directional transformation of a raw matching key.

| Caption | Text values wrapped individually | Existing numeric placeholder |
| --- | --- | --- |
| Tray component heading | Escaped name or localized unavailable-name label, ID and version | Component position |
| Comparison and full-value headings | Escaped name, ID and version from the captured side | Component position |
| Remove action | Escaped version from the exact pin | Component position |

Only these caption calls use the new indexed helper. The existing all-string
helper and its callers are unchanged. Bounded source escaping occurs before the
directional formatting. Complete recorded JSON, field names, conditions, price
context and unknown/missing distinctions retain their existing renderers.

The formatter adds display controls; it does not normalize, validate or rewrite
catalog values. Raw item/version/snapshot pins, comparison field values, indices,
Bundle contents and callback arguments stay unchanged. The same captured browser,
tray, owner and Activity lifetime checks govern every action. An unavailable
snapshot stays unavailable until the exact source is restored; formatting does
not grant recovery, selection, save, network or evaluation authority.

Android documents the behavior in
[`BidiFormatter.unicodeWrap`](https://developer.android.com/reference/android/text/BidiFormatter#unicodeWrap(java.lang.String)),
the explicit context direction in
[`getInstance(boolean)`](https://developer.android.com/reference/android/text/BidiFormatter#getInstance(boolean)),
and typed resource arguments in
[`Resources.getString`](https://developer.android.com/reference/android/content/res/Resources#getString(int,java.lang.Object...))
(consulted 2026-10-20).

## Regression scope

[`CatalogComparisonPresentationTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/CatalogComparisonPresentationTest.kt)
uses the actual tray and comparison dialogs over a validated local synthetic
catalog. Its four methods use Robolectric 4.17 and the pinned API-28 runtime.
Expected direction markers are literal strings; expected captions do not call
the production formatter.

| Method | Behavior checked |
| --- | --- |
| `ltrTrayIsolatesRtlNameAndVersionWithoutChangingExactPinRemoval` | Arabic fragments in an LTR tray, unchanged ASCII caption, raw exact pins, correct removal/reindexing and rejected detached Remove action |
| `forcedRtlCaptionsUseScreenDirectionAndKeepLocalizedIndexAndRawState` | English resources with forced RTL and a Japanese process locale, natural Arabic text, isolated LTR fragments, unchanged typed component numbers and raw Bundle reconstruction |
| `valueNavigationKeepsCapturedSidesAndRetiresControlsWithIsolatedCaptions` | Same Hebrew names with distinct exact versions, differences preference, captured JSON values on both sides, Back navigation and retired controls |
| `unavailableTrayCaptionsKeepExactRecoveryPinsAndCurrentness` | Localized unavailable-name caption, disabled comparison, exact pin/null-browser inspection, expired currentness and restoration of the original source |

The normal native workflow requires every class-qualified method exactly once,
matching suite totals and no failed, errored or skipped case. It retains all
existing comparison authority, complete-value and narrow-screen attendance.
A focused local command is:

```sh
./gradlew --offline --dependency-verification=strict --no-daemon \
  -PandroidPreview=true :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.CatalogComparisonPresentationTest
```

These are simulated native-control and resource assertions. Bundle reconstruction
is not physical process-death evidence. Actual installed mixed-direction
rendering, the supported locale/font/window matrix and TalkBack traversal remain
separate acceptance work. Earlier installed smoke cases do not become new
comparison-caption cases, and no canonical work-package criterion or receiving
decision is marked complete by this correction alone.
