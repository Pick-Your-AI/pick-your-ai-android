# Frozen reference report receiving boundary

`ReferenceReportAdapter` accepts bounded caller-owned streams for a frozen-v1
Build, Ruleset and EvaluationReport. It validates each against the unmodified
bundled schema, compares exact build/revision and catalog/ruleset/graph locks,
and checks the report's internal consistency against the Python reference profile.
It retains outcomes, severity, messages and evidence as an immutable JSON string.
No revision is narrowed to a machine integer during comparison.

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
remediation is displayed as reported text, never executed. Selection IDs are
shown without fabricating a navigation destination. Work stays on the existing
worker; concurrent inspection is disabled and destroyed Activities ignore late
callbacks. Recreation resets this read-only diagnostic. This rendering is scoped
to the fixed bundled fixture, not a general untrusted large-report renderer.

Four core projection tests retain all generated reference outcomes, warning
failure independence, null/absence/large-number distinctions and immutable lists.
A required Robolectric Activity case checks labels, evidence limitations and no
implicit guest save. Installed TalkBack/focus/contrast and actual evaluation
integration remain unverified. This is partial #101: required-presence versus
component-conflict categorization, current-draft analysis tickets, actionable
role/version navigation and verified evidence/remediation remain separate work.
