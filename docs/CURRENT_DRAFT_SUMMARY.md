# Current local draft summary

This bounded implementation contributes to
[Android #113](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/113).
The [accepted product scope](proposals/01-product-scope.md), J3/W3, distinguishes
the edited draft from the separately inspected bundled and retained records.
The summary makes that distinction visible before the existing editor fields.
It stays inside the current scroll content and adds no overlay or inset policy.

## Which state is shown

The Activity captures its actual draft identity, acknowledged record, main form,
raw workload input object and storage authority. The summary describes that local
state; neither a Bundle nor a pending edit acknowledges a durable revision.

| Current boundary | Summary |
| --- | --- |
| Storage operation in progress | Loading; old component counts and objective assessment are withheld |
| Unconfirmed storage or unresolved local draft | Unavailable; no old revision or known-zero count is substituted |
| Ready main form without an acknowledged record | Unsaved; current raw objective inputs can be reviewed, saved component counts are unavailable |
| Exact acknowledged fields, context and workload | The main editor matches the stated saved revision |
| Other ready main form | Pending main-editor changes; structured counts remain explicitly tied to the stated acknowledged revision |

Structured counts include the captured record's actual enabled and disabled
selection entries separately. They do not assert that a required component exists
or that a disabled component is active. The opaque guest sample choice is shown
only as present/absent in the current main form and is not counted as a structured
selection. Its presence is not catalog availability, a resolved model or a valid
BuildSnapshot. Edits inside an open component dialog are not included in the
recorded count until the existing command path acknowledges them.

`PreviewObjectiveGuidance` remains the sole source of required-input wording.
It identifies region, currency and data classification for the frozen objective
contract. Its invalid/rejected-input result takes precedence over missing-input
counts. Empty optional workload values remain unanswered. Answering those three
fields does not establish overall T0/T1, compatibility or deployment readiness.
Unfinished forms remain saveable whenever the existing editor permits it.

## Current results and core planning

Current evaluation is **unavailable** and current price coverage is
**unavailable** in this implementation. There is no current-draft result receiver
or matching result authority in this slice. The summary cannot borrow findings,
severity, readiness, cost lines, known subtotal or price coverage from the
immutable fixture inspections or an independently selected retained snapshot.
Unknown does not become pass, skipped, free, zero, complete or a budget verdict.
Changing or saving a draft cannot turn those separate records into current data.

Guardrail, evaluation, deployment, monitoring and cost-control planning remains
visible through the existing T6 section. A direct action reaches its heading.
Those configured choices do not establish measured safety/quality, executed
deployment, active monitoring or enforced spending limits.

## Existing destinations and retired controls

The summary offers navigation in a fixed order:

1. The next missing required objective input in the existing order, or the
   existing workload error display when raw input is invalid/rejected. The latter
   identifies the input group; it does not invent which individual field failed.
2. The existing component chooser for an empty acknowledged structured list, or
   existing selection editor picker for a populated one. This action is disabled
   when the existing component controls cannot accept that exact unchanged form.
3. The existing core-planning heading.

The workload delegation preserves the original parser, text buffers and guarded
classification dialog. Component navigation preserves catalog, recovery, dialog,
source-record and final command/CAS checks. Opening a destination does not write,
select, enable, replace, discard or save an input. The original destination still
owns any later explicit edit or persistence action.

Each rendered action has a unique generation and captures the current Activity
draft, record, pending values and storage-authority identity. A replacement
summary disables and detaches its old controls. Pause retires every action;
resume constructs fresh controls from the current state. Destroyed, finishing,
backgrounded, replaced or unconfirmed states cannot reuse an old callback. The
receiver rechecks its own existing authority after the summary check.

## Source and validation scope

The original implementation baseline is accepted
`1c51313d7499118a31b0d3a5af322aed5fa64a59`, tree
`c46b180a00b4bfe6ea8915159d417361bd6ac847`. The first Activity regression ran against
unchanged production with only its new test staged, tree
`51fb804b46fe2812dd52f6a75ba81ef0dcf7b15e`. The real draft/catalog setup passed; the
single test then failed because the summary was absent. Its 5,884-byte original
log has SHA-256
`fc3b1337085ec9c0af2b34bfcf6987bca6bb4f9abee32a78ebc584a6a781ac61`.
That baseline failure remains separate from corrected execution.

The first implementation run executed 22 cases: 17 passed and five failed.
Four new navigation assertions inspected Robolectric's manually assigned
`ShadowActivity.currentFocus` instead of the actual view hierarchy; the fifth
checked a canceled dialog before its queued dismissal. A six-case diagnostic run
retained those focus assertions and confirmed that the actual destinations had
focus, while the separate shadow field was null; its other two cases passed.
The final assertions inspect both the destination's `hasFocus()` and the real
decor hierarchy's `findFocus()`, and await the existing dialog dismissal.
No production focus behavior is changed to accommodate these test corrections.
The measured layout checks require the entire control and destination rectangles
to be visible after scrolling, as well as complete labels and 48 dp targets.
Both earlier failed runs remain in the original evidence; neither reached the
requested APK/lint tasks.

`CurrentDraftSummaryActivityTest` covers the actual Activity/resources and local
store: initial absence/result limits, missing-input order and invalid handling,
acknowledged and pending state, delayed reads, actual component destinations,
draft replacement, pause/recreation, unconfirmed restoration, separate fixture
reports, private-value omission, and measured narrow RTL/large-text and wide
reduced-viewport navigation. The workflow requires each named case to execute and
rejects missing, duplicate, foreign-class, failed, skipped or contradictory result
records. Test existence and a declared expected count are not execution evidence.
Actual source/log/XML/artifact receipts belong to their executed checkout.

The reduced measured viewport is a simulation, not a real IME observation.
Native JVM tests do not establish installed Android, physical-device, TalkBack,
keyboard, sticky-layout or product-release acceptance. Use the existing verified
offline toolchain and [preview commands](ANDROID_PREVIEW.md); no new dependency,
schema, evaluator policy, provider input, network operation, telemetry or
[diagnostic export](SUPPORT_HELP.md) is introduced.

## Remaining acceptance and other owners

#113 acceptance remains open. A current evaluation/cost receiver, exact outcome
and independent severity presentation, genuine conflict and unknown-capacity
cases, complete/partial-price and unpriced-component semantics, relevant measured
T6 inputs, sticky/adaptive product behavior, installed accessibility/keyboard
evidence and the complete receiving review remain required. An unavailable
current-result label does not satisfy those absent integrations.

The canonical acceptance record remains
`docs/delivery/android/work-packages/08.05.md` in `Pick-Your-AI/pickyour.ai`; this
engineering note does not move that path. The #508 autosave, #504 T6 form, #472
classification, #467 viewport/installed-diagnostic, #502 environment and #507
load-recovery owners retain their source and delivery routes. The implementation
does not adopt their unaccepted branches, change their gates or infer new shared
contract/placement decisions.
