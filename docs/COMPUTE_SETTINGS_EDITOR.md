# Typed compute-profile settings in the local preview

The existing `compute_profile` **Configure settings** action has native labeled
controls for all three frozen fields. This is a bounded contribution to [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
using [accepted settings metadata](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSettingsMetadata.kt)
from the unchanged [Selection schema](../contracts/v1/schemas/selection.schema.json).
Raw Add, other component kinds, typed objective inputs and governed answer-state
handoffs from #27/#83 retain their separate scope. This screen does not establish
whole-issue completion, installed-device accessibility or release acceptance.

## Values and admission

| Field | Control and exact frozen meaning |
| --- | --- |
| `instances` | Required whole JSON number, at least 1. It has no absence toggle or inferred capacity ceiling. |
| `reserved_hours_per_month` | Optional JSON number, at least 0. Supply enables input; clearing Supply removes the property. Zero remains explicitly supplied. |
| `region` | Optional literal string. Supply distinguishes a supplied empty string from omission. Whitespace, case and text such as `unknown` remain literal. No region lookup or answer-state conversion occurs. |

The [Core preparation adapter](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewComputeSettings.kt)
reads exact `BigInteger`/`BigDecimal` nodes through the existing bounded reader.
It accepts mathematical integer forms such as `1.000` and `1e3`, keeps untouched
admitted numeric nodes, and never rounds through `Double`. The reader's existing
ASCII JSON syntax, 128-character number, Unicode and exponent budgets still apply;
locale grouping, decimal commas, strings, nulls and injected keys are rejected.
No calendar length, billing conversion, reserved-capacity policy, price or resource
fit is inferred from these structural values.

Blank numeric input stays visible with an actionable field error. Supplied empty
region text is valid. Optional controls never turn omission into a default value.
Field errors and incidental object descriptions do not echo entered values.
All proposed fields must pass the existing complete-selection adapter before a
canonical command is dispatched. Its 16,384-character settings JSON budget and
65,536-byte parse budget remain in force, including JSON escaping and combined
fields. Rejected preparation does not publish a partial settings change.

## Save and recovery authority

[The native editor](../preview/src/main/kotlin/ai/pickyour/android/preview/ComputeSettingsEditor.kt)
uses the existing Activity worker and managed save path. Cancel preserves the last
acknowledged draft; an unchanged commit retains its revision. Stale, replaced or
deleted draft authority cannot overwrite the current record. Other selections,
links, notes, extensions, roles, enabled flags and workload data remain intact.

[Private checkpoint version 5](../preview/src/main/kotlin/ai/pickyour/android/preview/ComputeFormCheckpoint.kt)
retains primitive raw input, exact supplied/absent states, original draft/baseline/
selection/catalog identity, and whether a save was in flight. Its combined limit
is 20,000 UTF-16 code units; exceeding the limit yields a fixed unavailable marker
with no retained prefix. Automatic child-view save state and autofill are disabled,
and the controls request no personalized IME learning. This is a platform request,
not a claim about a particular installed keyboard.

Recreation requires explicit pending-input review. Only the same editable draft,
selection and captured catalog item/version can reopen an editable form. A changed
identity or in-flight save restores read-only input and no new commit button.
The saved flag records uncertainty; it never retries or replays the operation.
Read-only region display states that an empty region was supplied. Discard follows
the existing recovery flow. Version 1/2 raw checkpoints, version 3 context-policy
input and version 4 model input keep their independent readers and privacy rules.

## Validation scope

The dedicated regressions exercise actual Core command/storage/projection paths,
primitive Android Parcel round trips, and the Activity/worker/storage workflow in
Robolectric at API 28. They cover exact numbers, presence, literal region text,
malformed input, immutable state, managed no-op/stale/deleted authority, cancel,
explicit recovery, identity mismatch, in-flight saves and retired controls.
The workflow requires every dedicated method to appear exactly once with no
failure, error or skip. These are simulated UI results; existing Installed Preview
and physical accessibility evidence remain separate.

```sh
./gradlew --no-daemon :core:check :core:assemble
./gradlew --no-daemon -PandroidPreview=true :preview:assembleDebug :preview:lintDebug
./gradlew --no-daemon -PandroidPreview=true :preview:testDebugUnitTest \
  --tests 'ai.pickyour.android.preview.ComputeSettingsActivityTest' \
  --tests 'ai.pickyour.android.preview.ComputeFormCheckpointTest'
```

The normal required workflow also executes the existing model, context-policy,
raw-form and current-draft suites so this additive dispatch does not weaken their
accepted save, inspection-retirement or recovery behavior. Source/test presence is
not passing execution; a review or delivery receipt must name its actual result.
