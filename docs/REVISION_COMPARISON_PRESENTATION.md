# Revision comparison presentation

This bounded continuation of
[Android #65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65)
formats mixed-direction literals in the synthetic preview's read-only saved
revision browsers. It covers displayed keys, before/after values and retained
draft titles. The complete localization criteria and language-launch decision
remain open.

## Literal text and direction

[`RevisionComparisonBrowser`](../preview/src/main/kotlin/ai/pickyour/android/preview/RevisionComparisonBrowser.kt)
and [`LibraryRevisionComparison`](../preview/src/main/kotlin/ai/pickyour/android/preview/LibraryRevisionComparison.kt)
first use the existing `PreviewComparedValue` representation. Absent values,
empty strings and literal text remain distinct. Text keeps the same quoted,
escaped UTF16 representation; controls and surrogate halves are not interpreted
as commands or silently replaced. Existing preview truncation and complete
160-code-unit inspection parts remain unchanged.

For text values, the browsers then reuse
[`ComponentSelectionText.format`](../preview/src/main/kotlin/ai/pickyour/android/preview/ComponentSelectionText.kt).
Android's `BidiFormatter` takes its context from the Activity resource layout
direction, including an explicitly forced RTL screen. Each escaped literal keeps
its natural direction. The process-wide default locale does not determine the
screen's wrapping context. The existing `revision_compare_text` resource owns the
surrounding label; the new
[`revision_compare_key_suffix`](../preview/src/main/res/values/revision_comparison_presentation.xml)
resource owns the optional key separator, punctuation and placeholder order.

| Display | Formatting boundary |
| --- | --- |
| Change-list and individual-field keys and values | Each quoted text literal is wrapped before insertion into the existing resource pattern |
| Complete key or value inspection | Each escaped 160-code-unit part is wrapped; part numbering and original boundaries stay exact |
| Current and retained saved titles in the library picker | Only the displayed title literal is wrapped |
| Captured before/after titles on every comparison page | The same literal formatter is used without choosing a newer saved revision |
| Absent and empty sentinels | Existing resource labels are used directly |

The formatter adds display markers only. Raw field names, keys, values, draft
IDs, revision numbers and captured baseline identities remain the same objects
and values. Source capture, comparison order, library-root checks, callback
identity, retirement and store compare-and-swap authority are unchanged. Neither
browser gains a save, active-draft selection, provider lookup or evaluation action.
The library title preview retains its previous bounded representation; this change
does not add a new full-title inspector or alter its truncation disclosure.

The platform behavior is documented in
[`BidiFormatter.unicodeWrap`](https://developer.android.com/reference/android/text/BidiFormatter#unicodeWrap(java.lang.String))
and [`getInstance(boolean)`](https://developer.android.com/reference/android/text/BidiFormatter#getInstance(boolean))
(consulted 2026-10-20). Formatting does not validate source text or authorize any
use of a displayed identifier.

## Regression scope

[`RevisionComparisonPresentationTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/RevisionComparisonPresentationTest.kt)
uses six actual dialog cases over saved local fixtures with Robolectric 4.17 and
the pinned API-28 runtime:

| Case | Assertions |
| --- | --- |
| Arabic keys and values in an LTR screen | Literal directional markers, unchanged raw values and exact before/after revisions |
| Latin text in an Arabic RTL screen | Isolated Latin literals and naturally directed Arabic text |
| Forced RTL with English resources and a different process locale | Escaped controls, a surrogate split at the original part boundary and retired controls |
| Absent, empty and sentinel-looking literal values | Distinct representations and irreversible retirement after authority revocation |
| Saved Arabic titles through library navigation | One captured listing, exact older revisions, unchanged files and no active-draft selection |
| Saved Latin titles in a forced RTL screen | Activity-direction formatting across picker and comparison pages, with no saved-head fallback |

Expected directional markers are literal API-28 strings; expected text does not
call the production formatter. The two title expectations in the existing narrow
RTL library test include the new markers. Its complete layout, reachability,
source-identity and navigation assertions remain intact. The workflow requires
all six new class-qualified methods exactly once, matching suite totals and no
failed, errored or skipped result, after the actual native unit-test command.

A focused local command is:

```sh
./gradlew --offline --dependency-verification=strict --no-daemon -PandroidPreview=true \
  :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.RevisionComparisonPresentationTest
```

These are simulated native-resource and dialog tests. Installed mixed-direction
rendering, physical-device review, TalkBack and complete pseudolocalized journeys
need their own evidence. Earlier installed smoke receipts do not become new
revision-bidi evidence through this change, and no broad #65 criterion is closed.
