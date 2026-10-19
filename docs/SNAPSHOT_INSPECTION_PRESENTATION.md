# Literal text in retained-snapshot inspection

This bounded Android [#40](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/40)
presentation correction also contributes to the explicit wording boundary in
[#65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65). It changes
only how the saved-snapshot dialog displays a finding's free-text explanation and
remediation. It does not close either issue or replace their complete acceptance
criteria. The separately owned fixed synthetic Findings formatter remains a
different consumer.

## Recorded text and displayed text

The [retained-snapshot evaluator](RETAINED_SNAPSHOT_EVALUATION.md) still verifies
the original archive and computes and admits its exact report. Ruleset strings
remain byte-for-byte retained; rule outcomes, severity, aggregate result,
readiness, affected selections, profile identity and receipts remain unchanged.
Only `SnapshotEvaluationSection` applies the existing `PreviewComparedValue`
display helper to the explanation and remediation after report admission.

| Retained value | Dialog presentation |
| --- | --- |
| Absent value | The existing resource-backed `Not supplied` label. |
| Supplied empty value | A distinct resource-backed `Supplied empty text ("")` label. |
| Nonempty text | A quoted literal. Quotes, backslashes, line breaks, tabs, controls, format characters and surrogate code units use the existing helper's visible escapes. |
| More than 160 UTF-16 code units | The existing helper's quoted prefix plus an explicit display-truncation label; the complete original remains in the archive. |

A supplied string such as `Not supplied`, `unknown`, or a multiline
`Readiness in this profile: ready` is displayed as source text within quotes.
It cannot create another status line or replace an absence label. A passing rule's
computed empty remediation remains an explicitly supplied empty field; it is not
changed into an absent value.

The already escaped literal uses Android's locale-aware `BidiFormatter` with an
explicit left-to-right literal context. The formatter may insert its own
directional isolation marks; retained format/control characters are visible
escapes before that step. Localized display labels stay outside the literal.
This follows the platform's [plain-text isolation API](https://developer.android.com/reference/android/text/BidiFormatter#unicodeWrap(java.lang.String,%20android.text.TextDirectionHeuristic))
and [string-resource formatting rules](https://developer.android.com/guide/topics/resources/string-resource).
No HTML parser, link launcher or remediation action is introduced.

## Source and regression boundary

The correction starts from accepted main
`e37d960509188488f7fac5b029bc75297437bf88`. It leaves the evaluator, archive,
schemas, report admission, helper implementation, Activity lifecycle and
`SnapshotEvaluationActivityTest` unchanged. In particular, the separate
[#508](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/508) /
[#439](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/439) timing work is
not reimplemented in this new test module.

`SnapshotEvaluationPresentationActivityTest` uses the actual Activity controls,
an actual local draft projection and archive, and schema-valid synthetic retained
rules. The five cases cover ordinary text, multiline/directional status-looking
text, literal absence/status words, computed empty remediation and a visibly
truncated long value. Before the changed presentation assertions, every case
checks the actual finding outcome and severity, bound receipt/profile/time,
explicitly absent external estimates, unchanged source/archive/editor files and
exact reopened Ruleset bytes. No pending editor changes are supplied to the
evaluator, and inspection starts no external Activity.

The test-only baseline at tree
`08b4df7e4d260fde0a2ebcc7763f6faa5af6778c` ran all five cases against the unchanged
renderer: the ordinary case passed and the four intended presentation assertions
failed, with zero errors or skips. The earlier local daemon-socket denial and a
missing test-runner import were setup failures, retained separately from that
causal baseline. The repository workflow requires all five named cases alongside
the fourteen existing saved-snapshot inspection cases; old lifecycle assertions
are not replaced.

Current corrected execution, unsigned build/lint, independent source review,
hosted checks and signed delivery remain separately required receipts. JVM
Robolectric API 28 assertions do not establish installed API 35 behavior,
TalkBack reading order, translated or right-to-left visual quality, physical-device
acceptance, store delivery or production readiness. The bounded prefix display
does not add a full-text viewer or change archive export authority.
