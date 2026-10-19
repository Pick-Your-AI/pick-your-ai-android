# Local help and explicit public-report handoff

This engineering slice contributes to
[Android #33](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/33).
It adds offline help and constrained public app-bug/evidence-correction forms;
it does not provide a direct in-app submission service or verified private inbox.
The user-facing [support guide](../SUPPORT.md) describes the available behavior,
safe reproduction, category triage, correction authorities and deletion limits.

## Source and receiving boundary

The implementation starts from accepted Android
`e943a4dd959cc90a74acbc4a5a3d6b4736c5ca63`, tree
`c2acf3f97fdb9eb75632c465074593d97b3dacb0`. The current issue and open-PR snapshot
was read on 2026-10-18; no support implementation stack was present. The existing
issue chooser disables blank issues and links the Android program and delivery
Project. Those settings and links remain unchanged; two focused forms are added.

Project metadata identifies public, enabled Android repository `1422897313` with
Issues available. A read-only security-page observation on 2026-10-18 found no
detected security policy or visible reporting control; the inspected source has
no root or `.github/SECURITY.md`. This is not evidence about GitHub's global
feature configuration. No private destination is selected or invented.

Under delegated project authority, the current implementation session accepts
CompleteDotTech maintainer triage and category verification for these two public
forms. This is a bounded in-session responsibility, with no invented ongoing
support rota, incident commitment or SLA. Public form publication, actual receipt
and controlled triage have separate source-bound observations in the
[17.04 receiving record](delivery/android/work-packages/17.04.md), including the
actual browser submissions and independent in-session dispositions. The existing A13/G4
operating acceptance in [ACCEPTANCE_BASELINE.md](ACCEPTANCE_BASELINE.md) is not
supplied by this source change.

The original intended #33 receiving record is
`Pick-Your-AI/pickyour.ai/docs/delivery/android/work-packages/17.04.md`.
[ADR 0009](decisions/0009-android-33-acceptance-record.md) explicitly proposes the
same relative path in Android, effective only through its independent receiving
review and normal reviewed merge. The [17.04 record](delivery/android/work-packages/17.04.md)
preserves that historical destination and the original Help/diagnostic evidence.
Earlier narrow placement ADRs did not move this record. This engineering-note
reconciliation changes no criterion ledger, native relationship or completion state.

## Exact navigation and privacy contract

[`LocalHelp`](../preview/src/main/kotlin/ai/pickyour/android/preview/LocalHelp.kt)
accepts only an Activity, a current-owner predicate, an existing diagnostic-review
callback, a browser launcher and a closed catalog-identity callback. The later
[#57 identity contribution](LOCAL_PREVIEW_IDENTITY.md) renders only fixed public
labels from the current loaded fixture state; no raw catalog ID, hash or private
metadata enters Help. It has no draft/store, file, log, network,
clipboard, account or arbitrary URL input. Its three destination enum values are
the source-owned Android issues list and two exact issue-form selectors. Neither
title, body, recipient nor diagnostic report is inserted into a link.

The main help dialog and public-destination dialog each require a live resumed
Activity, the same current visible dialog and an unconsumed action. Cancel,
dismiss, replacement, pause, destruction and successful dispatch retire the
old callbacks. A failed dispatch is also consumed; retry requires a fresh
destination review. Exception messages never appear in the UI.

Before dispatch, the complete literal HTTPS URI and public-content disclosure are
shown. At dispatch, the unchanged core
[`ExternalLinkPolicy`](../core/src/main/kotlin/ai/pickyour/core/navigation/ExternalLinkPolicy.kt)
checks that same URI against the fixed exact-host configuration `github.com`.
The Intent uses `ACTION_VIEW` and `CATEGORY_BROWSABLE` only. It has no extras,
attachment, MIME type, selector, ClipData, URI grant or draft-derived query.
The browser controls any later navigation, sign-in and submission; this local
policy makes no redirect-confinement or receipt claim.

The app's **Review diagnostics** help action dismisses help and invokes the
existing `LocalDiagnostics.show` method. At the original Help delivery, the four
original #307 source, strings, test and documentation files were preserved
byte-for-byte. This later receiving reconciliation changes documentation only.
The report still says
`support_submission=unavailable`: the preview does not submit a support request.
Manually posting reviewed text in a separate public form is a distinct user
action, and arbitrary text entered there is not redacted by this app.

The Activity adds only the help entry point and its pause/destroy retirement.
There is no new dependency, manifest permission, WebView, background work,
telemetry, service, SDK setting or installed-emulator controller change.
English resource strings provide multiline controls, finite safe build metadata,
scrollable local guidance and a left-to-right selectable destination. Public
forms contain explicit private/security exclusions and make diagnostics optional.

## Meaningful verification and remaining evidence

[`LocalHelpActivityTest`](../preview/src/test/kotlin/ai/pickyour/android/preview/LocalHelpActivityTest.kt)
exercises actual controls in Robolectric: seeded saved/pending private inputs and
unchanged stored file hashes; all three exact reviewed destinations; one-use
dispatch; retained cancelled/dismissed/replaced callbacks at both levels; safe
missing/forbidden browser behavior; unchanged diagnostic preview; pause,
recreation and closed-owner retirement; and 320 dp RTL at 200% font with complete
text and reachable 48 dp actions. The existing eight #307 cases remain unchanged.
CI requires every new named case to pass without skips, errors or failures.

Focused local reproduction with the existing pinned toolchain and already cached
dependencies:

```sh
./gradlew --no-daemon --offline --dependency-verification=strict \
  --max-workers=2 -PandroidPreview=true \
  :preview:assembleDebug :preview:lintDebug :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.LocalHelpActivityTest \
  --tests ai.pickyour.android.preview.LocalDiagnosticsActivityTest
```

Actual command/results and source hashes belong in the reviewed PR receipt.
The focused local command completed on 2026-10-18: unsigned assembly and lint
passed, with 14 lint warnings and no errors; none of those warnings names the
new help source or resources. All eight new help cases and all eight unchanged
diagnostic cases passed with zero failures, errors or skips. Both exact workflow
receipt guards executed successfully against those XML results. Form/workflow
YAML, resource XML, changed-document relative links and whitespace checks passed.
The first run's single failure is retained: its new accessibility helper included
the platform dialog's unused hidden positive/neutral buttons. The corrected
helper explicitly requires every advertised action, the exact visible-action
count, full labels and 48 dp targets; no product assertion or action was removed.

These simulated controls and Intent observations do not launch a real browser,
submit an issue, establish receipt or triage, or prove installed accessibility.
No test sends a GitHub request. A missing browser case is distinct from the
explicitly unavailable private-reporting route.

Before calling an advertised public route delivered, verify the forms and exact
destinations on the accepted source. A separately authorized safe synthetic
submission must be read back and receive an attributable maintainer category and
disposition; a form-only screenshot or successful Intent is insufficient.
Receiving review must then reconcile #33's literal criteria, canonical record and
remaining installed/operating boundaries. This source slice alone does not close
#33 or claim the outstanding private/production service exists.
