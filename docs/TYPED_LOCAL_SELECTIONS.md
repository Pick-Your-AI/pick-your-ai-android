# Typed local selections and atomic core commands

Partial [Android #112](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/112)
and [#98](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98), implemented
only in the existing Kotlin/JVM core. There is no new selection UI, external-link
handler, service, deployment policy, evaluator or BuildSnapshot conversion.

## Intake and limits

The live issues were refreshed on 2026-10-18. Both remain open with no assignee or
named receiving commitment. [#37](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37)
and [research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40)
continue to gate deployment/hybrid and successor-contract semantics. This slice
does not resolve them. Canonical receiving records remain `07.05.md` and `08.02.md`
under `docs/delivery/android/work-packages/` in `Pick-Your-AI/pickyour.ai`; this file
is implementation evidence, not a relocated acceptance record or closure receipt.

The exact command subset is the frozen registry's T1/T2 kinds: `model_provider`,
`model`, `context_policy`, `hosting_platform`, `accelerator`, `compute_profile`,
`inference_engine` and `storage`. All other valid frozen kinds can be retained,
compared, duplicated and restored; their mutation commands return
`UNSUPPORTED_KIND`. Optional legacy tier controls retain their existing meaning.

## Payload and aggregate

`LocalSelection` validates the complete unmodified frozen Selection schema.
Identity, kind, exact catalog item/version, role, enabled and settings are explicit.
Required typed settings are never guessed, including accelerator device count,
compute instances and context reservations. Numeric settings retain arbitrary
precision subject to existing parser budgets. Notes and namespaced extensions,
including nested null/boolean/numeric values, survive round trips. Objects exposed
to callers are defensive snapshots; `toString` excludes payload content.

`LocalSelectionLink` validates the fixed bundled Build links-item fragment without
inventing a complete Build. Endpoints are selection IDs. Optional `required` remains
absent or the entered boolean; it is never defaulted. No URL is followed, and
credential references remain opaque `secret://` strings.

`DraftSelections` permits zero or one selection because a local draft is incomplete.
It enforces unique selection IDs, unique `(kind, role)` pairs across both enabled
and disabled entries, unique link IDs, and existing endpoints. The role-scope
invariant also appears in the frozen reference conformance tests; roles such as
`primary` and `fallback` are examples, not a new enum. Only enabled selections count
against registry cardinality. `context_policy` is cardinality-one; the other T1/T2
kinds are many. Disabled entries cannot reuse an existing role.

Local bounds are 256 selections and 1,024 links, plus the existing 1 MiB envelope,
128-history-entry and JSON depth/token/string/name/number budgets. These limits do
not amend the frozen contract. Arbitrary caller nodes receive iterative bounded
preflight before recursive copying or serialization; cyclic/deep/oversized or
nonfinite values fail with a sanitized exception during construction. Schema-invalid
but bounded settings are rejected by the reducer as `INVALID_SETTINGS`.

## Intent, edits and acknowledgement

Capture one `DraftSelectionIntent` with the loaded `DraftVersion` when editing
begins. It binds directory identity, revision number and hash. Keep that intent on
replay; do not replace its token with a newly loaded head. A catalog add/replace
also carries an exact snapshot hash, kind, item ID and version. The caller supplies
its current captured accepted `SelectionCatalogSnapshot` when applying the intent.
The adapter verifies that snapshot's schema, exact bytes/hash and unambiguous item
pairs; it does not establish global freshness, authenticity or source-use rights.
There is no latest-version fallback or implicit substitution.

The pure `DraftSelectionCommands` reducer returns proposed content and a disclosure,
not a saved acknowledgement. `LocalDraftStore.applySelection` runs it inside the
existing transaction after CAS validation and before unchanged-content deduplication.
There is no second history or storage engine. Changed successful commands append
one revision; same-current-content commands reuse the prior revision and return no
new change disclosure. Replayed, stale or foreign intents cannot overwrite a newer
edit. Failed and rejected outcomes retain the previous acknowledged state;
`Uncertain` requires reopening before retry and must never be shown as saved.

The managed preview command adapter described below routes commands through the
existing outer library liveness/write guard, as save, restore and tier operations
do. Future UI adoption must use that adapter for library-managed drafts, not
instantiate an unguarded `LocalDraftStore` for a managed draft; the inner CAS alone
does not enforce library entry liveness during concurrent deletion.

- Add retains the supplied explicit role/settings and requires exact catalog pins.
- Replace keeps target selection ID, kind, role, enabled state, notes and extensions.
  Settings also stay intact unless an explicit replacement settings object is supplied.
- Configure with no explicit role replaces only the typed settings object. An
  explicit changed role is one atomic `CONFIGURE_ROLE` command that preserves
  identity and all other fields besides the supplied role/settings. The native
  [role editor](LOCAL_SELECTION_ROLES.md) supplies the complete original settings.
- Disable/re-enable changes only enabled state and rechecks active cardinality.
- Remove deletes the selected identity and exactly its incident links. It never
  promotes a secondary selection or changes an unrelated selection/link.

Change disclosures contain deterministic sorted affected relationship IDs and,
for removal, the separate removed IDs. Retained relationships after replacement,
configuration or activation changes need review; their presence is no assertion
that the bindings remain compatible. The private history decoder checks disclosures
against prior/current content, permitted operation changes, metadata and actual
incident links. Revision comparison independently derives differences from retained
content by sorted selection/link ID and stable JSON field order.

## Migration and preservation

New drafts use private local envelope version 4. A real explicit selection role
change upgrades to version 5 without changing format-4 `CONFIGURE` semantics;
[the role history boundary](LOCAL_SELECTION_ROLES.md) preserves earlier records
and keeps subsequent writes at version 5. Every content entry includes a
`components` object with `selections` and `links`; every history entry includes a
nullable `selection_change`. Unknown versions and unknown/missing/invalid fields
fail closed without rewriting or resetting stored bytes. Frozen v1 files are unchanged.

Supported v1/v2/v3 reads never rewrite. V2/v3 unchanged saves are byte-preserving
no-ops; the first changed save or explicit restore writes v4, preserving all prior
numbers, changes, restore targets, content, metadata and legacy-source hashes.
V1 keeps its existing explicit-save migration and original legacy anchor semantics.
All predecessor content gets an empty typed aggregate, never guessed from
`primaryModel`, opaque optional values or guest catalog metadata. A fixed hand-authored
v3 predecessor fixture exercises workload, guest metadata and restore history without
using the current writer to generate the predecessor during tests.

Old immutable artifacts remain available by their original hashes. History, byte
or parser limits can refuse a migration; no old entries are pruned to make it fit.
Pre-activation faults preserve the old pointer; activation/cleanup uncertainty is
reported explicitly. Tier, context, workload and guest form saves, domain restore,
saved duplication and pending recovery copies all preserve typed components.

## Coverage and remaining acceptance

| Criterion | Local evidence | Remaining work |
| --- | --- | --- |
| Role-aware core add/replace/configure/enable/remove | Exact schema/settings, same-kind distinct roles, cardinality-one enable conflicts, primary removal, immutable inputs | T1/T2 slot UI and catalog navigation integration; Android domain/UX receiving review |
| Exact catalog/draft target; replay commits at most once | Immutable intent, exact snapshot/item/version checks, missing pin and target, stale/foreign/concurrent replay | Trusted bounded incoming routes, actual navigation/back/process restoration, installed-device evidence |
| Atomic revision and deterministic link cleanup | Same-store CAS/history, stored verified disclosures, incident-link tests, failure/uncertainty and no-op tests | Evaluator/dependency integration and user-facing review guidance |
| Preserve work across domain/migration operations | Fixed v2/v3 fixtures, legacy anchors, size/history/depth failures, all-29-kind retention, restore/duplicate/recovery/context/workload/tier tests | Android filesystem/power-loss qualification and named receiving acceptance |
| Deployment changes adapt unmet requirements | No implementation or acceptance claimed | Accepted #37/shared-contract decisions, deployment-mode behavior and integrated tests |

Reproduce with `GRADLE_USER_HOME=/tmp/android-gradle ./gradlew --offline --no-daemon
:core:check :core:assemble` when dependencies are cached, plus the repository's
hash-first `python3 scripts/check_contracts.py` and delivery-ledger checks. These
are JVM/reference tests; no Android SDK, emulator, physical-device, release or
production evidence is claimed by this slice.

Artifact retention quotas were reviewed in
[PR #229](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/229) and accepted
through [PR #232](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/232).
This candidate is integrated on accepted main
`20877f7c8b2b96ec70321a60085ee4e72f71eac3`, preserving the retention constructor,
`LIMIT_EXCEEDED` mapping, library adoption and original retention tests. New combined
tests cover every changed command under count/retained-byte quotas, current no-op
and stale/foreign precedence, refusal/reopen/retry, v3 migration refusal and typed
pending recovery from a full library budget. No command expands quotas or evicts
artifacts automatically. Catalog-detail and lifecycle PRs retain their own scopes.

## Observed local verification (2026-10-18)

On accepted base `a4098ac743e2dcaca89373cbac7453f583f74e26`, the rebuilt candidate
passed all **280 JVM tests across 34 suites**, with zero failures, errors or skips,
and produced the core JAR. Twenty new tests cover typed payloads/commands, migration
and failures, preserved editing/copy flows and comparison. The initial full run
exposed an interruption-test setup error: constructing a store after interrupting
the test thread failed during filesystem initialization. The corrected test opens
the store first, then tests the command's cancellation boundary; the complete suite
passed afterward. A fresh `:core:clean :core:check :core:assemble` run also
passed, with all seven actionable tasks executed.

The hash-first check verified all 43 frozen files and both pinned manifests, passed
21 reference tests and matched four report fixtures. The unchanged delivery ledger
passed its 129-issue/441-criterion structural check and six regression tests; this
is not a live criterion-completion assertion. Wrapper checksum, changed-document
relative links and whitespace checks passed. Named Android receiving acceptance,
UI integration and exact final hosted checks remain pending.

The separate integration candidate on accepted base
`20877f7c8b2b96ec70321a60085ee4e72f71eac3` passed a fresh offline
`:core:clean :core:check :core:assemble`: **303 tests across 38 suites**, zero
failures, errors or skips, all seven actionable tasks executed, and the core JAR
produced. This includes all accepted retention tests and six additional combined
regressions, for 26 new selection-focused tests in total. The same frozen/reference,
ledger, wrapper, relative-link and whitespace checks passed on the integrated source.
The original independently reviewed 280-test candidate remains frozen separately;
these new results qualify the combined source rather than reusing prior evidence.

## Managed-library command adapter

`PreviewStoredDraft.components` exposes the same immutable local aggregate.
`PreviewDraftPersistence.applySelection` carries the captured record's original
revision/hash into the canonical command while holding the existing managed
library transaction and liveness guard. A retained adapter cannot acknowledge
any command, including a no-op, after its draft is marked for deletion or removed.
No separate storage path or library identity is created by this adapter.

The typed result preserves command rejection reasons and a successful command's
exact link-impact disclosure. The guest form is decoded from acknowledged stored
metadata; objective, context, workload, legacy optional tiers and unknown metadata
are untouched. An outer lock-cleanup failure after dispatch returns `Uncertain`,
including after an otherwise successful no-op. Callers must reopen and reconcile;
it is not permission to replay against a newer token.

Adapter regressions cover all five commands, changed/current/stale/foreign tokens,
completed and interrupted deletion, library lock contention and interruption,
unknown preview metadata, retention refusal/no-op, preactivation failure,
postactivation uncertainty and outer-cleanup uncertainty. This is a callable core
boundary only; no preview button, dialog, UI lifecycle acceptance or installed
Android evidence is added here. The existing UI remains unchanged.

The adapter candidate passed a fresh forced core check/assemble/test run: **311
tests across 39 suites**, zero failures/errors/skips, all six tasks executed in
32 seconds. These are local JVM results; final current-main integration and
hosted Android build/lint remain separate required gates.
