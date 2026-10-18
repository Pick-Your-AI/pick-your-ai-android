# Explicit local workload and deployment selections

This bounded [Android #83](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/83)
continuation makes the frozen registry's two T0 kinds, `workload` and
`deployment_mode`, editable through the existing component controls. It reuses
the accepted native Views architecture, typed selection commands, canonical
revision store and managed-library write boundary. It adds no alternative draft,
history, identity, catalog or storage engine.

The intake uses accepted main `e943a4dd959cc90a74acbc4a5a3d6b4736c5ca63` and the
unchanged frozen v1 import. The original #83 acceptance record remains
`docs/delivery/android/work-packages/08.01.md` in `Pick-Your-AI/pickyour.ai`.
This document is source/validation evidence for partial implementation and does
not relocate that record, claim an assignee commitment or close the issue.

## Explicit input and existing authority

The existing exact catalog component chooser now includes workload and
deployment-mode items. Opening a catalog detail or a chooser never creates a
selection. **Add** starts with an empty role, unchecked enabled state and visible
empty settings object. The user enters the role and enabled state explicitly.
Empty settings do not fill unanswered values from the catalog, overall workload,
device, region, pricebook or another component. The bundled chooser is still
explicitly synthetic; it is not a real-data mode.

| Selection kind | Exact editable settings | What remains separate |
| --- | --- | --- |
| `workload` | Optional `concurrency` and `latency_budget_ms` integers at least 1; optional `requests_per_day`, `avg_input_tokens` and `avg_output_tokens` integers at least 0 | Omitted keys remain absent. Overall Workload assumptions, objectives, catalog workload facts and measured capabilities are not copied or changed |
| `deployment_mode` | The empty object `{}` only, as required by frozen v1 | The exact pinned catalog item's `spec.mode` carries `api`, `managed`, `self_hosted` or `hybrid`. No new selection setting or draft-wide mode field is introduced |

The form validates the unchanged frozen Selection schema. The existing local
16,384-character settings editor, bounded JSON parser, duplicate-key rejection
and exact supported numbers remain. Invalid text stays visible for correction;
there is no trimming, valid-prefix save, guessed setting or schema expansion.
The new settings guidance explains the two shapes in add/configure forms. The
add form also explains explicit roles and activation without selecting either.

All existing operations now work for these two kinds: add, same-kind exact-pin
replace, settings edit, role edit, enable/disable and remove. Replacement preserves
selection identity, role, enabled state, settings, notes, extensions and links.
Removal deletes only that identity and its incident links. An alternative is
never promoted, enabled or assigned `primary` automatically.

Both T0 kinds already have cardinality one in the frozen registry. Only one
selection of each kind may be enabled; disabled alternatives need distinct roles.
The shared `(kind, role)` uniqueness rule includes disabled selections. A local
draft may remain empty, have one selection, use another role or keep a selection
disabled. Those are retained incomplete choices, not fabricated complete builds.
T1 `context_policy` remains the existing separately editable kind. The separate
[retrieval continuation](RETRIEVAL_SELECTIONS.md) subsequently adds the five frozen
T3 kinds to these commands while preserving optional-tier intent. The
[agent continuation](AGENT_SELECTIONS.md) adds the five frozen T4 kinds through the
same commands. T5–T6 typed mutation remains outside the editor; the original T0
continuation did not add optional-tier mutation or weaken its controls.

## Persistence, recovery and snapshot preparation

These are the existing `ADD`, `REPLACE`, `CONFIGURE`, `ENABLE`, `DISABLE` and
`REMOVE` commands with an expanded frozen-kind admission set. They require no
new private storage version: existing format 4 already retains both T0 payloads
and verifies the same history descriptions. An actual explicit role change uses
the already accepted format-5 `CONFIGURE_ROLE` boundary. Unchanged saves, earlier
formats, exact historical bytes, restored revisions and opaque metadata retain
their existing meaning.

The UI captures the current acknowledged record, local draft identity and exact
catalog snapshot for add/replace. The existing worker dispatch and managed adapter
retain the original revision/hash token. Stale, foreign, deleted, canceled or
catalog-replaced authority cannot acquire a newer token. A changed successful
edit creates one revision; replay cannot create another. No-op, rejected, failed,
conflicted and uncertain outcomes keep their existing distinct behavior.

The existing bounded primitive checkpoint includes the actual T0 kind, explicit
selection ID, raw settings/role, enabled state and original draft/catalog
identities. Recreation retains pending input for explicit review, not automatic
save. Changed baselines and in-flight checkpoints remain read-only; old dialogs
and buttons cannot revive consent. Acknowledged single-selection drafts reopen
under the same local identity without inserting any other component.

The accepted [snapshot projection](DRAFT_SNAPSHOT_PROJECTION.md) consumes exactly
one enabled `primary` selection for each of `workload`, `deployment_mode`, `model`
and `context_policy`. The editor does not choose those roles, infer missing
foundations or implement its own readiness policy. The integration test builds
the two T0 selections through actual form preparation and canonical commands,
checks that disabled/alternate entries remain missing foundations, and then
prepares an in-memory candidate only after explicit role and enabled edits.
Conflicting selection overrides and overall workload inputs remain separate and
cause the existing projection to withhold the candidate; neither side is silently
changed to match the other.

## Validation and remaining acceptance

Nine dedicated core cases cover empty and explicit inputs, large integers and
invalid settings, all four test-only catalog modes, exact pins, cardinality and
role scope, retained format-4/5 history, managed liveness/CAS, projection integration
and the retained unsupported-kind command boundary. That original T0 assertion
covered T3–T6; the retrieval and agent continuations narrow it to T5–T6 while
keeping all T0 cases. The four-mode catalog variants exist only
inside tests; the frozen examples and runtime catalog are unchanged.

Nine mandatory Activity simulations exercise a new empty managed draft and
single-workload recreation; exact overrides without changing overall inputs;
deployment settings rejection and exact replacement no-op; explicit enable,
disable, role-conflict and removal flows; parcel recovery; canceled/stale catalog
controls; concurrent-head recovery; in-flight acknowledgment; and narrow RTL
layout at twice the normal font scale. The committed report gate requires every
named case to execute and rejects failure, error, skip and duplicate identities
while allowing future additional cases.

Actual local/hosted results, source identities, initial failures and independent
receiving review are recorded in the PR receipts. Reproduction uses the strict
core check/assemble and opt-in preview test/build/lint commands in
[CI baseline](CI_BASELINE.md) and [preview instructions](ANDROID_PREVIEW.md), plus
the frozen import/reference and delivery-ledger checks. Simulation and unsigned
assembly are not installed process-death, filesystem durability, TalkBack,
physical-device, Play or release qualification.

Broader #83 remains open. Its [#37](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37)
placement/hybrid, context-budget and executable applicability decisions are not
supplied here. Selecting a frozen mode does not adapt other components, establish
supported deployment combinations, calculate compatibility or cost, or declare
T0/T1 readiness. Real catalog/price/source evidence, product forms, installed
lifecycle qualification and full domain/UX receiving acceptance retain their
separate requirements. The canonical durable snapshot archive/UI continuation
remains with the existing #26 owner.
