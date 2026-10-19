# Local diagnostic review and explicit sharing

The synthetic guest preview has **Review diagnostics**, followed by a separate
**Choose an app to share this report** action. This contributes to
[Android #33](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/33).
Direct support submission is explicitly unavailable: this diagnostic component
sends no support request and promises no chooser-recipient receipt or response.
The separate [Help and public-report workflow](SUPPORT_HELP.md) offers reviewed
browser navigation; its actual public intake and in-session triage are recorded
in [17.04](delivery/android/work-packages/17.04.md). The diagnostic report's
`support_submission=unavailable` field still describes the app's direct-submission
boundary accurately.

## Exact disclosure boundary

[`LocalDiagnosticReport`](../preview/src/main/kotlin/ai/pickyour/android/preview/LocalDiagnostics.kt)
constructs ten fixed fields from compiled application ID/version/version code,
the OS API-level integer, source-owned fixed declarations and finite storage and
catalog UI states. Its constructor accepts only the two state enums. It cannot
accept a draft, identity, path, URL, exception or arbitrary metadata map. No raw
record is serialized and no heuristic attempts to recognize or remove secrets.

The complete immutable report text is displayed before an external action is
available. Draft titles, objectives/context, workload and selection values,
component identities, query text, file paths/content, logs, exceptions, account
identifiers and credentials never enter the report. The captured storage state is
a UI observation (`BUSY`, `READY`, `RECONCILIATION_REQUIRED` or `UNAVAILABLE`), not
a filesystem-health probe or durability guarantee. Catalog state discloses only
whether the bundled synthetic snapshot is loaded. There is no timestamp, device
identifier, model, manufacturer, user identity or content-derived digest.

`diagnostic_format=pyai.android.preview.diagnostics.v1` versions this local
diagnostic text. It is not the frozen build interchange format or a shared service
contract. Stable machine field names and enum tokens remain ASCII across locales;
the explanation, controls and failure messages use Android string resources.

## Consent, cancellation and failure

The share action constructs one `ACTION_SEND`, MIME type `text/plain`, with only
`EXTRA_TEXT` equal to the previously displayed report. `Intent.createChooser`
provides the system Sharesheet. The app supplies no recipient, subject,
attachment, URI, clip data or URI grant. Android may itself migrate the same text
to a plain `ClipData` item during dispatch, adding its read-grant flag without a
URI; the simulated actual-dispatch check permits only the same text and no
URI, HTML, nested Intent or other flag. The app performs no network request, automatic clipboard
write, file export or automatic upload. Opening the chooser is not delivery to a
recipient; the selected receiving app controls any subsequent transfer and copy.

Every dispatch requires the same visible current dialog, a resumed Activity and an
unconsumed action. Cancel, dismiss, replacement, Activity destruction and the
first dispatch retire that report's callback. Pausing also retires the current
report; the review action remains unavailable until the Activity resumes.
Failed or forbidden chooser launch
shows a fixed safe failure without an exception message. Retry requires closing
and reviewing another report; no automatic retry occurs. Changes after capture
do not silently rewrite the report; its disclosure directs the user to reopen it
for a newer status. Viewing and sharing do not save or change a pending draft.

## Validation and remaining acceptance

[`LocalDiagnosticsActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/LocalDiagnosticsActivityTest.kt)
exercises the actual Activity controls with seeded private saved and pending
fields, verifies unchanged persisted file hashes, inspects the exact chooser
payload, and checks there is no outgoing Intent before explicit consent. It also
covers every finite report state, cancelled/dismissed/replaced callbacks,
immutable capture, repeated delivery attempts, safe handler failure, recreation
and paused/closed owners. A native-graphics JVM case measures 48 dp targets and complete
labels in a 320×640 dp RTL configuration with 200% font size, using ordinary scroll
requests to verify each ancestor viewport. CI requires all eight named cases to
execute without failures, errors or skips.

Reproduce the Android source and simulated checks with the existing documented
JDK 21, SDK 35, locked dependencies and strict checksum verification:

```sh
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :core:check :core:assemble :preview:assembleDebug :preview:lintDebug \
  :preview:testDebugUnitTest
```

These are bounded local/SDK/JVM checks. They do not establish installed
Sharesheet behavior, receipt by a support destination, network traffic from
another app, manual TalkBack traversal, physical-device privacy, a release build
or store acceptance. At the original diagnostic delivery, actual support receipt
and triage were still outstanding. The later [17.04 receiving record](delivery/android/work-packages/17.04.md)
separately records the accepted Help source, actual browser-created requests and
independent in-session dispositions; those observations do not prove chooser
recipient delivery. The historical intended path is
`docs/delivery/android/work-packages/17.04.md` in `Pick-Your-AI/pickyour.ai`.
[ADR 0009](decisions/0009-android-33-acceptance-record.md) proposes the same relative
path in Android only after its independent receiving review and normal merge.
The record retains all three criteria and required roles; this note does not
change the criterion ledger or close the issue.

Primary references checked 2026-10-18:

- [Android text sharing](https://developer.android.com/training/sharing/send)
  documents `ACTION_SEND`, `EXTRA_TEXT`, `text/plain` and `createChooser`.
- [Android Dialog API](https://developer.android.com/reference/android/app/Dialog)
  distinguishes cancellation from dismissal and provides the dismissal callback.
- [Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)
  describes destruction and recreation; the simulated lifecycle test does not
  substitute for installed process-death evidence.
