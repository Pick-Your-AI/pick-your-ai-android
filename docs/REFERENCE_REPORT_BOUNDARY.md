# Frozen reference report receiving boundary

`ReferenceReportAdapter` accepts bounded caller-owned streams for a frozen-v1
Build, Ruleset and EvaluationReport. It validates each against the unmodified
bundled schema, compares exact build/revision and catalog/ruleset/graph locks,
and checks the report's internal consistency against the Python reference profile.
It retains outcomes, severity, messages and evidence as an immutable JSON string.
No revision is narrowed to a machine integer during comparison.

Admission proceeds in Build, Ruleset, then EvaluationReport order. Each stream
must first complete the strict bounded UTF-8/object parse through EOF. A present
textual `schema_version` other than exactly `1.0.0` then returns
`UNSUPPORTED_BUILD_SCHEMA_VERSION`, `UNSUPPORTED_RULESET_SCHEMA_VERSION`, or
`UNSUPPORTED_REPORT_SCHEMA_VERSION` for the supplied input. Missing, null or
non-text versions, malformed/duplicate/trailing input, read failures and exceeded
budgets retain that input's `INVALID_*` result. Supported versions still require
the complete fixed schema before later input or semantic binding checks. Rejection
leaves later streams unread, all streams remain caller-owned, and diagnostics
disclose no supplied version or content. Ruleset `version` remains its separate
semantic binding; these diagnostics do not admit or migrate another wire schema.

The local retained-evaluation descriptor records the current receiver source hash.
This diagnostic change refreshes only `REPORT_RECEIVER_SHA256`; the reference,
schemas, evaluator, profile ID and reference time remain unchanged. Its inclusion
in the request digest intentionally distinguishes requests using this receiver
from the prior receiver. Historical source and execution receipts retain their
original descriptor identities.

The adapter requires one finding per rule in reference order, the rule's severity,
and the sorted active selection IDs for its affected kinds. Summary counts,
result and readiness must agree with findings, `on_unknown` and build policy.
An unknown never becomes a pass. Warning failures do not become error failures.
The reference only produces `blocked` or `needs_review`; `ready` is rejected by
this explicitly limited adapter even though the general frozen wire schema permits
it. This is a receiving profile, not a modification of that schema.

The adapter **does not execute assertions or prove a reported outcome correct**.
It cannot establish service authenticity, resource/cost evidence provenance or
freshness, or production readiness. Frozen reports lack complete evidence identity
binding; this adapter does not invent those fields. Callers must use a trusted
producer and retain evidence inputs separately. The existing `AnalysisCoordinator`
must still protect current UI state from stale attempts, and callers must perform
stream work off the UI thread. No transport is included; bounded read-only
fixture preview wiring is described below.

## Reproduce and provenance

Run `python scripts/check_contracts.py` and
`./gradlew --no-daemon :core:check :core:assemble` after installing the toolchain
and dependencies in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). The integrity runner
verifies all frozen bytes before executing the reference suite and checking the
four generated reports against the same pinned Python evaluator.

`core/src/test/resources/evaluation/reference-reports.json` is synthetic test data,
derived by `scripts/check_report_fixtures.py` from the frozen examples with absent
evidence, strict unknown policy and exact decimal metric overrides. The overrides
are explicitly development fixtures, not accepted cost evidence. Regeneration with
`--write` is intentional and must be reviewed. Kotlin tests consume those exact
reports; neither schemas nor original fixtures are changed.

## Partial criterion evidence

| Issue | Covered | Still required |
| --- | --- | --- |
| #84 | Malformed/version rejection; all report lock identities; reference findings/severity/summary/policy consistency | Actual local evaluator or trusted service integration; full cross-language parity; evidence identities; UI/error recovery and receiving review |
| #49 | Four Python-generated report cases replayed without loss in Kotlin and checked in the existing CI reference command | Full language-neutral conformance/migration matrix and affected-consumer acceptance |
| #32 | Wrong locks, unsupported versions, huge revisions, malformed fields, missing/duplicate/reordered findings and false-ready tests | Complete domain suite including graph cycles, money evaluation, migrations, CAS and integrated Android behavior |

No issue is complete from this slice. JVM/reference checks prove neither emulator
nor physical-device behavior, vendor compatibility, store acceptance or release.

## Read-only preview inspection

The existing fixed `build.json`, `ruleset.json` and `evaluation-report.json`
assets can now be inspected through this receiving boundary. No guest editor
content is sent to it, no rule execution occurs, and no draft is written. The
immutable inspection projection copies exact report/build/catalog/ruleset/graph
identities, arbitrary-precision revisions, outcomes, independent severity,
explanations, optional remediation, affected selection IDs and evidence fields.
Absent evidence values remain absent; explicit JSON null and numeric values stay
JSON rather than being coerced to a fact or a floating-point display number.

The UI exposes summary outcome counts and one finding dialog at a time, with
explicit raw outcome/severity labels and evidence/authority limitations. Optional
remediation is displayed as reported text, never executed. Selection IDs now link
to the same-build recorded context described below. Work stays on the existing
worker; concurrent inspection is disabled and destroyed Activities ignore late
callbacks. Recreation resets this read-only diagnostic. This rendering is scoped
to the fixed bundled fixture, not a general untrusted large-report renderer.

Four core projection tests retain all generated reference outcomes, warning
failure independence, null/absence/large-number distinctions and immutable lists.
A required Robolectric Activity case checks labels, evidence limitations and no
implicit guest save. Installed TalkBack/focus/contrast and actual evaluation
integration remain unverified. This is partial #101: required-presence versus
component-conflict categorization, current-draft analysis tickets and navigation,
and verified evidence/remediation remain separate work.

## Recorded selection context (#101, partial)

The finding's **Selections** action opens read-only details from the same Build
that passed `ReferenceReportAdapter` with that report. A minimal immutable projection
retains the exact selection ID, kind, recorded role, catalog item ID/version and
enabled flag. It copies no build owner, selection settings, notes, extensions or
credential references. Report/build revisions and catalog/ruleset/graph identities
remain those of the accepted report. The inspector never resolves an item against
the current catalog, substitutes a primary role, fetches evidence or edits/saves
the guest draft.

Every affected ID resolves only when exactly one selection in the accepted build
has that ID. Duplicate identities remain explicitly unavailable, including an
active row colliding with a disabled row; missing context has its own unavailable
reason. This is conservative presentation of the existing receiving result, not
a new frozen-schema restriction or a reinterpretation of the evaluator's findings.

Sixteen selections appear per page, with all affected selections reachable.
Identity text uses the existing literal helper: controls, formatting characters,
surrogates and delimiters are escaped; values longer than 160 UTF-16 units have
an explicit display-truncation marker. Complete immutable values remain retained.
The bounded receiver's supported integer revision digits are displayed without
machine-integer narrowing. The context contains textual labels and single-column,
scrollable actions with minimum 48dp targets. These primitives do not establish
installed accessibility or complete localization.

The showing finding authorizes the transition into its captured context. Every
context action then requires the same accepted report object, its current owned
showing dialog and a live Activity. Cancellation, replacement and destruction
retire the navigation permanently. A unique inspection request also rejects an
older worker completion even when its report's wire identities match. Recreation
resets the diagnostic and does not bind the old report to restored guest edits.
The existing report/finding text formatter remains separate from this context
projection and navigation.

Three focused core regressions exercise multiple roles/versions, ambiguity
including disabled rows, private-field omission and immutable copied collections.
The mandatory nine-case native suite covers actual fixture navigation and pending
draft authority, private/literal fields, ambiguity, full supported revision digits,
complete paging, canceled and superseded controls, reverse-order receiving work,
Activity recreation and measured 320dp RTL/200% text layout. Record actual
execution, source hashes and independent review on the delivering PR; these
tests are simulations and do not supply installed TalkBack or device acceptance.

This addresses the recorded-context portion of #101 criterion 3 only within the
fixed synthetic inspector. Current-draft evaluation/classification, accepted
evidence and feasible remediation, result-change announcements, installed
accessibility, named compatibility/Android receiving roles and all native
prerequisites remain outstanding. The original `09.03.md` acceptance-record path
in `Pick-Your-AI/pickyour.ai` remains authoritative; this source document does not
move it or close the issue.

Primary Android references checked 2026-10-18:
[Dialog lifetime](https://developer.android.com/reference/android/app/Dialog#isShowing()),
[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle)
and [accessible controls](https://developer.android.com/guide/topics/ui/accessibility/apps).
Dialog visibility and Activity callbacks provide in-memory navigation authority,
not durable persistence or evidence of process-death recovery.
