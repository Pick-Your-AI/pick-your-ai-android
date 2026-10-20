# Complete text for current saved-draft findings

## Entry and meaning

In **Saved draft checks**, open **Inspect current rule observations**, choose an
observation, then choose **Full text**. **Explanation** displays the complete
recorded message from that current result. **Rule guidance** displays its complete
recorded remediation string. The existing detail still shows outcome, severity,
the bounded summary, affected selections and the absence of external evidence or
cost and resource estimates.

The field viewer has **Next part**, **Previous part** and **Back to fields**
controls. Returning through **Back to observation** preserves the selected rule;
**Back to observations** returns to its original list page. Cancel or Back on the
Android dialog closes the current inspection. A new inspection can be opened
while the same saved analysis remains current.

Each part renders at most 160 raw UTF-16 units through the existing quoted-literal
formatter. The part count comes from the captured String length, including its
entire tail. Quotes, backslashes, line breaks, directional controls and surrogate
units remain escaped plain text. A supplementary character can cross the raw-unit
boundary; its two escaped surrogate units remain in adjacent parts in their
original order. Escaped display text may be longer than 160 characters and uses
the platform dialog's scrollable message area. An empty guidance string has one
part containing `""`; it does not disappear or acquire invented advice.

## Result and window lifetime

Every retained row, field, part and Back callback checks the same captured source,
the exact current analysis state and its own showing dialog. Internal navigation
does not reenter the Activity's shared inspection hook. The next window becomes
the owner before the old one is dismissed; a late dismissal cannot clear a newer
window, and callbacks from a closed or superseded window cannot reopen it.

The existing analysis and Activity boundaries still retire inspection when an
observed edit, Retry, input replacement, source switch, sibling inspection or
lifecycle transition invalidates its authority. Returning to a current analysis
requires a fresh inspection entry. These are the existing observed source and
currentness checks, not a new synchronous filesystem read on every click. Paging
does not evaluate rules, save a draft, change a finding, alter stored bytes, or
make an old result current.

## Regression and attendance boundary

`CurrentDraftFindingTextActivityTest` creates an actual saved draft and supplies
labelled synthetic rules through the existing validated current-input seam. The
real worker and canonical source read must produce a new `Current` result before
the viewer assertions begin. Tests assert the actual admitted explanation,
guidance, rules hash, saved revision and stored bytes; they do not replace the
analysis state with a constructed outcome.

The nine methods cover the existing detail, complete independently decoded text,
160/161-unit boundaries, a supplementary pair split at the boundary, escaped
controls, RTL layout and an actual skipped result's empty guidance. They also
cover field/list Back navigation, retained callbacks after dismissal and
replacement, History sibling entry, a genuinely held Retry worker, pending edit
and save, input replacement, store switch, pause and recreation. The text is
reconstructed independently from the quoted displayed parts, with explicit
expected escaped boundary strings and complete tail checks. The finite loop in
the fixture is not a production pagination limit.

The additive **Require actual current-finding full-text regressions** CI step
requires all nine source names, unique nonempty case identities, matching class
and suite totals, and no failure, error or skipped outcome. Additional distinct
passing cases are allowed. It consumes the actual Preview JUnit XML after the
existing Gradle producer. The prior current-draft, presentation and sibling
attendance programs remain unchanged.

These Activity tests use Robolectric SDK 28, including a simulated RTL
configuration. Their execution does not establish installed Android pixels,
TalkBack behavior, physical-device input, release readiness or deployment
authority. Configured CI must qualify its own actual source and outputs.

## Acceptance scope

This is the current Findings text-access extension for [Android #101](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101).
It contributes to readable explanations and available recorded rule guidance;
it does not establish that a remediation is feasible or has been applied. The
[current diagnostic implementation](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/100),
fixed-fixture Findings, retained snapshot reports and their existing owners keep
their separate source and acceptance boundaries. Historical observations,
external evidence, cost and resource estimates, complete mobile accessibility and
the remaining issue criteria are not supplied by this text viewer.
