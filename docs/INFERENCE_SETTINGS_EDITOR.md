# Typed inference-engine CONFIGURE

Fresh **Configure settings** for an existing `inference_engine` selection presents
all four optional settings from the frozen
[Selection schema](../contracts/v1/schemas/selection.schema.json). This is a bounded
contribution to [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
under its [existing scoped claim](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#issuecomment-6282668961).
Raw ADD, raw legacy recovery and the other component editors retain their routes.
The separately owned compute-profile editor is not part of this contribution.

## Optional values and exact validation

For each integer, **Supply** distinguishes an entered value from an absent field.
Clear Supply to remove that key. Supplied empty or malformed text stays available
for correction. Enum controls offer **Not supplied** plus the exact frozen
literals. The `quantization` value `none` is a supplied literal, distinct from
absence. Changing either enum preserves the other supplied fields.

| Field | Frozen value and bounds |
| --- | --- |
| `tensor_parallel_size` | Mathematical integer, minimum 1; no schema maximum |
| `max_batch_tokens` | Mathematical integer, minimum 1; no schema maximum |
| `precision` | `fp32`, `tf32`, `bf16`, `fp16`, `fp8`, `int8`, `int4` |
| `quantization` | `none`, `awq`, `gptq`, `gguf`, `bitsandbytes`, `other` |

[Core preparation](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewInferenceSettings.kt)
uses the existing schema-derived metadata and bounded JSON parser. Integer input
is never narrowed to a machine integer or floating-point value. Mathematical
integer spellings such as `1.000` and `1e3` remain admissible, and untouched
admitted numeric nodes retain their type and scale. Locale separators, fractional
values, invalid Unicode, extra keys, duplicates, trailing documents and input
beyond the existing parser budgets are rejected without rounding or coercion.
These local budgets do not amend the frozen schema.

Fixed inline errors identify the field without repeating its entered value.
The whole-selection `PreviewSelectionForms.configure` receiver still owns final
admission; the existing managed command and durable baseline comparison own
saving. Cancel, no-op, conflict, deleted-library and uncertain in-flight outcomes
retain their separate meanings. Identity, catalog identity, role, activation,
notes, extensions, links and unrelated draft data are preserved.

The form fills no defaults or catalog-derived capacities. Its values do not
establish hardware availability, engine compatibility, memory fit, throughput,
quality, costs or actual inference execution.

## Private recovery and lifecycle

[Version 6](../preview/src/main/kotlin/ai/pickyour/android/preview/InferenceFormCheckpoint.kt)
is a private `configure_inference_engine` checkpoint containing all four exact
nullable values, the original draft/baseline/selection/catalog identity, the
component kind and whether a save was in flight. Version 5 remains reserved for
the separately owned compute-profile contribution. This checkpoint is not a
Selection envelope or a fresh write token.

The reader requires the exact key set and primitive types. Its combined 20,000
UTF-16-code-unit budget counts every supplied value; some symbols count as two.
An over-budget checkpoint becomes a fixed unavailable marker with no retained
prefix. Same-process text remains available for correction while the form is
open. Malformed numeric text may be retained privately for correction, while
invalid enum values cannot create a valid checkpoint.

Restoration requires explicit review. A changed draft, baseline, selection,
catalog item or catalog version cannot acquire fresh save authority. A captured
in-flight save remains read-only after recreation and is never replayed. Every
control and nested picker freezes during a save and retires with its form.
Automatic View state, autofill and personalized-learning input are disabled;
the bounded checkpoint owns recovery.

Raw version-1/version-2, typed context version-3 and typed model version-4
readers remain unchanged. Previously stored raw input and revision history are
not migrated or rewritten. The new editor does not dispatch public exports.

## Verification and acceptance limits

The causal baseline ran on unchanged accepted main
`3b49f484ea848a635e1c8fee8edb5c9f427a9f3b` on 2026-10-20 from 03:33:04 to
03:34:49 UTC. It executed three Activity cases: two expected failures for missing
integer/enum controls and a passing unchanged raw-ADD control. An earlier sandbox
startup failure occurred before compilation because Gradle could not open its
local loopback socket; it executed no tests. Original logs, source hashes and
JUnit reports remain separate from later passing executions.

The [14 Core cases](../core/src/test/kotlin/ai/pickyour/core/preview/PreviewInferenceSettingsTest.kt)
cover exact numeric admission, all enum combinations, absence/removal, hostile
input, identity/content preservation, managed no-op/conflict/delete and an
explicit validated Build-candidate round trip. The
[7 private-checkpoint cases](../preview/src/test/kotlin/ai/pickyour/android/preview/InferenceFormCheckpointTest.kt)
exercise actual Parcel round trips, exact identities/types, combined budgets,
protocol separation and retained in-flight state. The
[15 Activity cases](../preview/src/test/kotlin/ai/pickyour/android/preview/InferenceSettingsActivityTest.kt)
use the real worker and local store in Robolectric to check native controls,
exact save, error correction, cancellation, no-op, raw ADD and legacy recovery,
private recreation, wrong identities, in-flight retirement, stale saves and
oversized state. The existing bounded BUSY-only draft-read helper remains in use.

The workflow adds named Core and Preview attendance checks; missing, duplicate,
failing, errored or skipped cases cannot satisfy those checks. Existing check
bodies and policy remain intact. Test source alone does not establish execution:
actual results belong to the corresponding original logs and source identity.

This is partial #99 software work. Local JVM/Robolectric and unsigned build
results do not establish installed process death, physical-device accessibility,
operational execution or signed release acceptance. Broader typed editors,
answer-state UX/storage/export, device qualification and remaining issue criteria
retain their own work and acceptance. The existing
[08.04 acceptance coordinate](https://github.com/Pick-Your-AI/pick-your-ai-website/blob/main/docs/delivery/android/work-packages/08.04.md)
is unchanged.
