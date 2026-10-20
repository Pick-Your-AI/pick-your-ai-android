# Typed model CONFIGURE

Fresh **Configure settings** for an existing `model` selection presents all seven
optional settings from the frozen [Selection schema](../contracts/v1/schemas/selection.schema.json).
The controls do not infer values from the selected catalog item or a serving mode.
ADD still starts with the existing raw settings object. Role, replacement,
activation, typed context policy and other component kinds keep their own routes.

This is a partial contribution to [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
coordinated in the [existing issue lane](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#issuecomment-6280184915).
It introduces no new field registry, answer-state wire encoding or release claim.

## Optional values and exact validation

For each numeric or text field, **Supply** distinguishes a present value from an
absent field. Clear Supply to remove only that key. Supplied empty or whitespace
text remains invalid input for correction; it is never silently converted to
absence. Enum controls offer **Not supplied** and the exact frozen literals.

| Field | Frozen value and bounds |
| --- | --- |
| `temperature` | JSON number, 0 through 2 inclusive |
| `top_p` | JSON number, greater than 0 and at most 1 |
| `max_output_tokens` | Mathematical integer of at least 1; the schema provides no maximum |
| `precision` | `fp32`, `tf32`, `bf16`, `fp16`, `fp8`, `int8`, `int4` |
| `serving_mode` | `api` or `weights` |
| `credential_ref` | Opaque reference matching the frozen `^secret://[A-Za-z0-9/_:.-]+$` pattern |
| `weights_uri` | Frozen URI format plus the existing rejection of user information in URI authority |

The preparation helper reads the existing schema-derived metadata and exact
bounded JSON reader. It does not use floating-point conversion, integer
narrowing, rounding, locale coercion or catalog-derived caps. Mathematical
integer spellings such as `1.000` and `1e3` remain admissible, and unchanged
admitted numeric nodes retain their type and scale. Existing local JSON budgets
still apply; they are safety limits, not amendments to the frozen schema.

Changing serving mode preserves all other supplied values, including credential
reference and weights URI. The screen does not resolve references or open URIs.
Literal URI path, query and fragment characters remain data. Credential parsing
retains the frozen validator's `Pattern.UNIX_LINES` / `find` semantics, including
its accepted final LF, without trimming the reference.

Inline errors are fixed messages that identify the field and correction needed
without echoing its value. Preparation still calls the complete
`PreviewSelectionForms.configure` receiver. The existing managed selection
command and saved-baseline comparison remain the only save authority. Cancel,
unchanged input, stale save, deleted library and uncertain in-flight outcomes
keep their existing distinct behavior. Selection identity, catalog identity,
role, enabled state, notes, extensions, links and other draft content are retained.

## Private recovery and lifecycle

A new private version-4 `configure_model` checkpoint stores exact primitive
values and explicit absence for all seven fields, original draft/baseline/
selection/catalog identity, the model kind and whether a save was in flight.
It is not a Selection envelope or a new write token. Its exact key and primitive
type checks reject malformed or mixed protocol shapes.

The combined raw input budget is 20,000 UTF-16 code units. Some symbols count as
two. A larger checkpoint becomes a fixed unavailable marker; no prefix is
retained. Newly captured typed weights URI user information is checked
independently of any invalid numeric field and replaces the whole checkpoint
with the existing fixed URI exclusion marker.

For these new typed credential controls, only absence or a frozen-pattern-valid
opaque reference can be retained in recovery. An invalid supplied reference
becomes the fixed unavailable marker, avoiding storage of a pasted password.
This is a bounded new-field safeguard, not a general secret detector. Capturing
either marker leaves the same-process text available for correction until the
form closes.

Restored input requires explicit review. A changed draft, baseline, selection,
catalog identity or saved in-flight flag cannot acquire fresh save authority.
Stale input remains read-only where its checkpoint is otherwise valid. Every
control and nested picker freezes while saving and retires when its form closes.
View-state saving is disabled and the controls opt out of autofill. They request
no personalized keyboard learning through Android's
[`IME_FLAG_NO_PERSONALIZED_LEARNING`](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)
flag. The keyboard determines whether it honors that request; the bounded
checkpoint remains responsible for recovery. The API reference was checked on
20 October 2026.

Legacy raw version-1 and version-2 model checkpoints remain readable through
their original raw reader. Version-3 context policy checkpoints are unchanged.
This contribution does not rewrite previously stored raw input, draft history or
archives, nor does it implement public export dispatch.

## Verification and scope

The causal baseline uses existing Activity navigation and native widgets on the
unchanged `4b9efe1d` HEAD, staged test-only tree
`0f32752f4d7a3a6cee82d8e7aee617db8ad3e924`. Its actual local run on
2026-10-20, 00:33:24–00:36:10 UTC, executed ten cases: seven expected missing
typed-control failures and three passing raw-ADD/cancel/legacy controls, with no
errors or skips. Earlier transport/sandbox setup failures executed no tests and
remain separately recorded.

The new suites cover all seven exact fields, managed save/no-op/CAS and local
validated Build-candidate round trips, real Activity/worker/recreation behavior,
private primitive checkpoint budgets and privacy classification. Two existing
fresh-MODEL CONFIGURE tests use their new typed controls while retaining their
original repeated-recovery and URI-exclusion assertions. Raw ADD and legacy
reader tests remain intact. The workflow requires the named new Core, Activity
and checkpoint cases to execute without failure, error, duplication or skip.

Local verification on 2026-10-20 retains two separate source identities:

| Executed staged tree and base | Actual local result |
| --- | --- |
| `50ca22196d0895fa67b3f2a20447ab4bf89a98e8` on `4b9efe1d` | 99 focused cases in seven suites; 6,349 full Core cases in 123 suites; frozen/reference checks, Core assemble, unsigned Preview assemble and lint passed. Lint retained 13 warnings and no errors. |
| `3f7edabde82ef9e374c467a6257be72a19937f03` on `300be531` | 150 focused cases in eleven suites; 6,371 full Core cases in 125 suites; frozen/reference checks, Core assemble, unsigned Preview assemble and lint passed. Lint retained 14 warnings and no errors. |

All tests in the two implementation rows passed without failures, errors or
skips. The receiving focused run contains 19 Core and 131 simulated Preview
cases, including the CurrentDraftCheckActivityTest, SiblingInspectionActivityTest,
CostInspectionActivityTest and CostSiblingInspectionActivityTest suites and the
annotated RTL/large-text case. It preserves main's bounded BUSY-only read helper
and current-check retirement, with a typed-temperature adaptation in the existing
model-save test.
All assertions remain intact. The original and receiving tests are separate
executions; the earlier result is not attributed to the newer source.

The programmatically constructed model editor retains its `ViewConstructor`
lint warning without suppression. The required unsigned build does not rerun
the focused Preview tests. Commands, complete reports and source identities are
retained outside Git; this guide records their outcomes without creating another
execution. These local JVM/Robolectric results do not establish installed process
death, physical-device behavior, signed release or production acceptance. Typed
ADD, other component editors, broader answer-state UI/storage/export and the
remaining #99 criteria retain their own owners and acceptance.
The intended #99 acceptance destination remains
`docs/delivery/android/work-packages/08.04.md` in `Pick-Your-AI/pickyour.ai`.
The [live issue's delivery record](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#delivery-record-and-receiving-review)
tracks its placement and receiving evidence.
