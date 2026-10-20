# Findings inspection lifecycle and recovery

The fixed synthetic Findings inspector now retires its accepted report, pending
request and owned windows when the actual Preview Activity pauses. Returning to
the Activity permits an explicit inspection of the fixed fixture. A dismissed
picker, finding detail or recorded-selection control cannot reopen its former
report after that transition.

This is partial recovery work for Android
[#81 criteria 2 and 3](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/81)
and lifecycle protection for the selection context in
[#101 criterion 3](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101).
The inspector continues to read the packaged Build, ruleset and report through
the existing `ReferenceReportAdapter`. It does not evaluate the editable draft,
substitute current evidence for retained evidence, or execute remediation.

## Ownership and retry behavior

`PreviewActivity` now supplies the Findings section with its existing resumed
lifecycle predicate and calls its nonterminal `dismiss` operation during pause.
Dismissal clears the current request and accepted report before retiring the
finding dialog and selection navigator. It restores the fixed-fixture notice,
enables explicit inspection and disables browsing until a new report is accepted.
Final Activity destruction also closes the section, after which both actions
remain inactive.

Each worker completion must still belong to the exact current request and an
active Activity. Clearing the request at pause prevents a result from being
accepted both while paused and after the Activity has resumed. Merely returning
to an active lifecycle does not revive an old request or report. The existing
report-object, dialog-identity and navigator guards continue to protect captured
callbacks.

If the executor rejects a submitted inspection, the section uses the existing
`UNAVAILABLE` explanation and exposes the explicit inspection action again. No
accepted report remains and browsing stays disabled. The dispatch failure does
not leave the loading state indefinitely or trigger an automatic mutation.
An explicit retry runs the same receiving boundary when dispatch becomes
available.

## Regression evidence

The original actual-Activity pause test failed against accepted source
`78a454db9adae219d36fb12dfe16a8b521a4e3e0` because the Findings window remained
showing. The identical test passed after the production correction. Its original
failure and corrected execution are retained separately from the final class run.

Four required methods in `FindingsSelectionActivityTest` cover:

| Scenario | Checked behavior |
| --- | --- |
| Actual selection context across pause and resume | Owned window closes; retained row and browse callbacks remain inert; fresh inspection reaches the recorded role. |
| Actual finding picker and detail across pause and resume | Both dialog callbacks retire; pending editor state and private file bytes are preserved; explicit fresh navigation remains usable. |
| Actual queued worker completion before or after resume | Neither completion revives the old report; notice and actions recover; a saved draft and pending edits remain unchanged. |
| Controlled dispatch rejection and explicit retry | Unavailable replaces loading; retry accepts the real packaged fixture; close rejects late work and retained actions without changing saved or pending data. |

The final focused run executes all fifteen methods in that class, including the
eleven existing recorded identity, ambiguity, paging, replacement, destruction
and native-graphics layout cases. The workflow's existing attendance check now
requires the four new names as well. Missing, duplicate, failed, errored or
skipped cases remain failures.

```sh
./gradlew --offline --no-daemon --dependency-verification=strict \
  -PandroidPreview=true :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.FindingsSelectionActivityTest
```

These observations use simulated Android activities, real local fixture
receiving and controlled worker scheduling. Native-graphics simulation remains
separate from installed-emulator and physical-device evidence. TalkBack order,
announcements, the complete recoverable system-state matrix, real accepted
evidence and the broader Findings outcome retain their own acceptance work.
Canonical receiving records remain `docs/delivery/android/work-packages/06.05.md`
and `09.03.md` in `Pick-Your-AI/pickyour.ai`; this guide does not relocate or close
either work package.
