# Typed training compute settings

This is the local `training_compute` editor slice of
[Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
consuming the existing T5 selection path tracked by
[Android #129](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/129).
It provides typed configuration of an existing selection. The four-kind T5 ADD
flow, role editing, outer component enablement and optional-tier state remain
under their existing controls. This guide is an implementation reference; it does
not move either issue's designated acceptance record or close either issue.

## Contract and units

The authority is the unchanged frozen
[Selection schema](../contracts/v1/schemas/selection.schema.json), read through
[PreviewSettingsMetadata](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSettingsMetadata.kt).
The editor supplies exactly these optional fields:

| Setting | Native control | Accepted representation |
| --- | --- | --- |
| `gpu_hours_budget` | Explicit supply checkbox and GPU-hour budget text field | Exact JSON number, at least zero. No invented maximum, recommended value or default. |
| `checkpoint_interval_minutes` | Explicit supply checkbox and interval field labelled in minutes | Exact JSON integer, at least one. Mathematically integral decimal/exponent spellings remain governed by the existing JSON reader. |
| `spot_allowed` | Choice of Not supplied, False or True | Omitted key, JSON false and JSON true remain distinct. This is separate from the selection's outer `enabled` flag. |

A field starts omitted when its saved setting is absent. Choosing to supply a
numeric field makes a blank value invalid; clearing its supply checkbox explicitly
omits it. The literal text `unknown`, localized/grouped numbers, JSON strings,
objects and nonnumeric credential-shaped input cannot become numeric facts.
Neither a GPU-hour budget nor permission to request spot usage proves hardware
availability, entitlement, allocation, measured usage, price or execution.

## Admission and acknowledged storage

`PreviewTrainingComputeSettings` parses numeric input with the existing bounded
UTF-8 JSON reader and exact decimal arithmetic. It does not convert through binary
floating point or narrow integers. Field issues use fixed field/problem codes and
do not echo submitted text. Unchanged fields retain their original admitted JSON
nodes, so untouched numeric spelling does not create a synthetic settings change.

The final candidate passes the existing whole-Selection admission after all edits
and omissions are applied together. Unknown setting keys, aggregate byte limits,
invalid settings and incompatible kinds still fail there. A field error never
publishes a partial command. The preparation keeps the selection ID, kind, catalog
identity/version, role, outer enabled state and unrelated admitted metadata.

The Activity submits through its existing managed worker and durable draft store.
A successful changed commit acknowledges one revision; cancel and untouched commit
leave the acknowledged baseline unchanged. A stale or deleted head cannot be
replaced by a retained callback, implicit retry or a recovered form. The shared
store remains responsible for links, peers, workload inputs, optional-tier intent
and all other draft state.

## Pending input and lifecycle

`TrainingComputeFormCheckpoint` uses the distinct `typed-training-compute/1`
primitive Bundle shape. It captures the draft ID, saved baseline, selection ID,
catalog ID/version, optional raw numeric strings, explicit Boolean presence and
value, and whether a save was in flight. Its aggregate UTF-16 input limit is the
existing component recovery limit. Oversized or malformed state becomes unavailable
as a whole; no prefix, substituted identity or partial value is retained.

Automatic view-state saving and autofill are disabled for these private controls.
The input checkpoint is separate from durable saved settings. Recreation requires
explicit review before editing; it never grants save or replay authority. The
captured draft/baseline/selection/catalog identities must still match, and a
previously saving form is read-only. Changed identity or saved head preserves
bounded input for read-only review until explicit discard/reopen. Pausing retires
the form, its controls and any open Boolean picker. Consumed or canceled pickers
cannot change later input. Existing legacy raw checkpoints retain their original
correction reader rather than being silently migrated into typed save authority.

## Native access and regression coverage

The full component identity is selectable, unellipsized and scrollable beneath a
short dialog title. Numeric captions label their native fields; values keep their
units visible. Supply and Boolean controls use the established 48 dp target
minimum. The training guidance and explicit commit remain in the same form.

`PreviewTrainingComputeSettingsTest` covers metadata, presence combinations,
exact numeric bounds and aliases, malformed input, parser budgets, final Selection
admission, untouched values, sibling omission, managed no-op/stale/deleted state,
storage and explicit frozen Build projection.

`TrainingComputeSettingsActivityTest` covers native labels and privacy flags,
exact save/reopen, omitted/false/true states, independent field errors, zero and
negative GPU budgets, large exact values, cancellation, review-only recovery,
identity changes, stale/in-flight saves, retired controls/pickers, legacy raw
recovery and strict 320 dp RTL geometry at twice the font scale.
`TrainingComputeFormCheckpointTest` covers exact primitive shape, identity and
aggregate limits, invalid raw corrections, Boolean presence, saving state and
legacy-reader independence through Parcel serialization.

The existing `TrainingSelectionActivityTest` keeps every method and all four-kind
invalid-input loops. Only its two CONFIGURE readbacks adapt to the typed training
compute controls; they verify the exact wide budget/interval, both supplied flags
and false spot choice. The other three kinds keep their original raw readback.

The existing Core, Preview, installed API-35 and security workflows remain the
required gates. Added attendance steps require every declared new Core, Activity
and checkpoint method to appear exactly once, with no skipped or failing case.
Use the current PR's exact source/preparation identity and original CI results for
execution evidence; the presence of these tests is not a passing-test claim.

Robolectric Activity and Parcel results are simulated evidence. Existing installed
smoke checks have their stated scope and do not independently qualify this editor's
process-death recovery, TalkBack, physical devices or Google Play delivery. Those
acceptance stages, broader #99 kinds/objectives, #129 lineage/export/runtime
requirements, approved sensitive-input boundaries and exact prerequisite handoffs
remain with their original issues.
