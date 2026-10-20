# Recorded selections from retained-snapshot findings

This is a bounded Android [#101 criterion 3](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101)
implementation. A current accepted saved-snapshot report now offers **Inspect
affected selections**. The selector contains every reported finding with its
independent outcome and severity. Choosing a finding opens its recorded selection
context through the existing `FindingsSelectionNavigator`.

## Source and identity

`SnapshotEvaluationSection` captures the accepted result from its own current
`PreviewSnapshotAnalysis` request and calls `result.report.inspect()`. The
navigator receives that exact immutable projection and finding index. Selection
IDs, kind, role, catalog item ID/version and enabled state come from the accepted
report's same-Build `selectionContexts`. Its pages retain the report ID, exact
Build revision, catalog snapshot, ruleset and dependency-graph bindings. Missing
or ambiguous contexts retain the navigator's explicit unavailable explanation.

The underlying report retains its explanations, remediation, retained-bundle
hash, captured archive-index identity and local-profile limitations. Closing the
selection navigator returns to that report. Navigation does not select a latest
catalog item, infer a component role, execute a remediation or evaluate the
current editor. Saved snapshots remain historical inputs after later editor
changes or deletion of their source draft.

## Callback and lifecycle ownership

The entry is enabled only while an accepted result is current. Loading,
cancellation, withheld inputs and failed or foreign results cannot open it.
Every finding-picker callback and navigator action checks the exact owning
dialog, analysis instance and captured result. Retrying an inspection retires
its child controls before a fresh result arrives, including when the same saved
entry is selected again.

Replacing or dismissing the report, opening another saved revision, pausing the
Activity or destroying it revokes the picker and navigator before dismissal.
Captured old buttons and list callbacks cannot reopen their context against a
new report. The route writes neither the archive nor the private draft store and
does not consume pending form changes or acquire save authority.

The new entry uses an externalized, wrapping label with a minimum 48 dp height
and appears before the long report inside the existing scrollable dialog. The
finding picker follows the current resource direction. The reused context
navigator retains its scrollable, paged controls and recorded-value escaping.

## Regression coverage and receiving boundary

`SnapshotEvaluationActivityTest` uses the actual Activity, retained archive,
frozen evaluator and receiving adapter. Its required cases cover:

| Case | Required assertion |
| --- | --- |
| `actualRetainedFindingNavigationKeepsRecordedIdentityAndPendingEdits` | Real saved inputs remain inspectable after source deletion; a revision above signed 64-bit range and recorded model role/version survive navigation; pending text and saved bytes remain unchanged. |
| `findingNavigationRequiresCurrentAcceptedResultAndFreshRetry` | Queued and cancelled inspections have no selection route; a fresh accepted retry enables it. |
| `retryAndDismissRetireRetainedFindingControls` | Old list callbacks and selection buttons cannot reopen after retry or report dismissal. |
| `switchingRetainedRevisionsRetiresCapturedFindingSelectionCallbacks` | Replacing revision 1 with revision 2 revokes every old route and displays the newly selected recorded identity. |
| `actualActivityPauseAndRecreationRetireRetainedFindingNavigation` | Real Activity pause, destruction and recreation dismiss child windows and revoke captured controls; a new explicit inspection works. |
| `retainedFindingEntryRemainsReachableWithLargeFontInNarrowRtl` | Native graphics simulation at 320 dp and 2x font scale checks a fully visible, wrapping entry and reachable RTL selection context. |

The existing foreign Build/revision result regression also requires the entry to
remain disabled and unable to open a finding selector. The workflow's existing
saved-snapshot guard requires all six new cases alongside the prior suite, with
no missing, repeated, failed, errored or skipped testcases.

The first route regression failed against accepted main `5c69ace0d72b7cf3983b2681d219a1989552053f`
after the real report was accepted because the selection entry did not exist.
The same case passed after the production change. Original source, log and XML
identities are retained separately from later regression results.

```sh
./gradlew --offline --no-daemon --dependency-verification=strict \
  -PandroidPreview=true :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.SnapshotEvaluationActivityTest \
  --tests ai.pickyour.android.preview.SnapshotEvaluationPresentationActivityTest \
  --tests ai.pickyour.android.preview.SnapshotFindingGuidanceActivityTest \
  --tests ai.pickyour.android.preview.FindingsSelectionActivityTest
```

These are source and simulated Android observations. Installed emulator coverage,
TalkBack order and announcements, physical-device behavior, accepted real-input
evidence, feasible remediation and whole-issue receiving review remain separate.
Canonical acceptance stays at `docs/delivery/android/work-packages/09.03.md` in
`Pick-Your-AI/pickyour.ai`. This technical guide does not close #101 or move its
acceptance record.
