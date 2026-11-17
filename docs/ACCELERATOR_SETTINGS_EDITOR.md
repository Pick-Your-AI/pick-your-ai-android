# Typed accelerator CONFIGURE

Fresh **Configure settings** for an existing `accelerator` selection uses the two
exact integer fields in the frozen [Selection schema](../contracts/v1/schemas/selection.schema.json).
This is the bounded [Android #99 contribution](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#issuecomment-6284044609).
It does not infer a setting from catalog hardware, verify an accelerator, compute
capacity or establish model/runtime compatibility.

| Field | Required state and frozen bounds |
| --- | --- |
| `device_count` | Required mathematical integer of at least 1; no default is supplied |
| `tensor_parallel_groups` | Optional mathematical integer of at least 1; absence remains absent |

The required device count starts from the existing saved selection and has no
removal control. **Supply Tensor parallel groups** distinguishes a present value
from an absent field. Clearing Supply removes only that optional key. Empty or
whitespace input in either supplied field stays invalid correction text. There
is no inferred ordering or divisibility relation between the two counts and no
schema maximum. Unknown and not-applicable strings are not integers and do not
create a new answer-state representation.

The preparation helper reads the existing schema-derived metadata and bounded
JSON parser. Exact wide integers and mathematical integer spellings such as
`1.000` and `1e3` are admitted without floating-point conversion, narrowing,
rounding or locale coercion. Unchanged admitted nodes retain their exact value,
type and scale. Existing parser and settings-size limits still apply; they do
not amend the frozen schema. Fixed inline errors identify each field's problem
without echoing entered values.

Complete `PreviewSelectionForms.configure` admission and the existing managed
selection command retain save authority. Configuration preserves selection and
catalog identity, role, enabled state, notes, extensions, links and unrelated
draft content. Cancel, unchanged input, stale baselines, deleted drafts and
uncertain in-flight outcomes retain their distinct existing behavior.

## Explicit recovery and lifecycle

The private `typed-accelerator/1` checkpoint uses an exact string namespace, with
strict keys and primitive types. It stores both input strings, explicit optional
absence, original draft/baseline/selection/catalog identity, kind and the saved
in-flight flag. The tag is separate from integer recovery versions and other
typed component namespaces; it changes no persisted draft or contract version.
It never carries a command or replacement write token.

Both input strings share the existing 20,000 UTF-16-code-unit recovery budget.
Over-budget input becomes the fixed unavailable marker without retaining a
prefix. Bounded invalid count text remains private correction input.
View-state saving is disabled and the controls opt out of autofill. They request
no personalized keyboard learning through Android's
[`IME_FLAG_NO_PERSONALIZED_LEARNING`](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)
flag. The keyboard determines whether it honors that request; the bounded
checkpoint remains responsible for recovery. The API reference was checked on
20 October 2026.

An actual pause captures the input and retires the form and its callbacks.
Resume does not reopen the form. Explicit review may open a fresh editor only
when all original identities still match and no save was in flight. Otherwise,
valid retained checkpoints remain read-only. Saving freezes the controls;
closed, paused and stale controls cannot obtain new write authority.

Raw ADD and legacy raw version-1/version-2 CONFIGURE recovery keep their existing
routes, including malformed correction text. Existing typed context policy and
model recovery remain independent. Parallel compute-profile, inference-engine,
model-provider, cost-policy and hosting-platform implementations retain their
own source ownership and receiving review.

## Verification and remaining acceptance

The [12 Core cases](../core/src/test/kotlin/ai/pickyour/core/preview/PreviewAcceleratorSettingsTest.kt)
cover exact admission, required and optional state, preservation, managed
save/no-op/CAS, deletion and explicit validated Build-candidate round trips.
The [14 Activity cases](../preview/src/test/kotlin/ai/pickyour/android/preview/AcceleratorSettingsActivityTest.kt)
exercise actual native controls, worker and local storage paths in Robolectric,
including raw ADD, cancellation, invalid correction, stale identity, real pause,
in-flight saving, explicit review and retired callbacks.
The [7 checkpoint cases](../preview/src/test/kotlin/ai/pickyour/android/preview/AcceleratorFormCheckpointTest.kt)
exercise real Bundle/Parcel transport, exact namespaces, primitive types and
combined recovery limits. Two additive workflow steps require every named case
to execute successfully without omission, duplication, failure, error or skip.

Passing execution belongs to the original reports for the exact tested source.
JVM and Robolectric results do not establish installed accelerator-specific
journeys, physical-device behavior, TalkBack, release or whole-issue acceptance.
Broader metadata forms, answer-state UI/storage/export and the remaining #99
criteria retain their existing requirements. The
[canonical delivery record and receiving review](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#delivery-record-and-receiving-review)
retains `docs/delivery/android/work-packages/08.04.md` as an intended coordinate
under `Pick-Your-AI/pickyour.ai`.
