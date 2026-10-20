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

## Inspecting saved components

The saved-component summary can be expanded without opening an editor. Every
recorded selection appears in its original order with its selection ID, kind,
role, exact catalog item/version reference and enabled or disabled configuration
state. The list names its acknowledged revision. A confirmed empty list is
explicitly empty; unsaved, loading and unconfirmed drafts do not borrow that
known-empty state or a prior list.

These are recorded references. The summary performs no catalog lookup and does
not infer availability, suitability, readiness or current evaluation. It reuses
the existing bounded literal renderer: control and format characters are escaped,
quoted values are directionally wrapped, and values longer than the existing
160-character display bound have an explicit truncation label. The stored value
is unchanged. Selection settings, notes, extensions and credential references
are not part of this display.

Pending main-form changes can inspect the same acknowledged list without the
inspection saving or discarding those changes. Expansion survives replacement
controls for that same record; a storage transition, different acknowledged record or recreated
Activity starts collapsed. Each toggle retains the current summary generation
and rechecks the existing Activity/storage/input authority. Pause disables its
retained control; a replaced, destroyed or unconfirmed owner cannot use an old
callback to reveal another record. Inspection writes no draft or history and
starts no external Activity. The separately scheduled form autosave retains its
existing deadline and authority; its later acknowledgement refreshes the summary
and collapses the prior list. Existing editor navigation retains its original
save and command guards.

The added `SummaryRecordedComponentsActivityTest` begins from accepted main
`56cc178e38f1fc0914db273468301e3431daef64`. Its first actual Activity case ran with
unchanged production and only the new test staged, tree
`fd0b5db145ebea608836bbb03853ccbb3c8ebd2c`. The real draft/catalog setup passed, then
one missing-inspection assertion failed, with zero errors or skips. That original
5,438-byte log has SHA-256
`94cc10bae63aad53e21fe3a975070b7586db3622e1fcffdcc1b082421cac4728`.
Two earlier setup failures remain separate: the restored JDK initially lacked
the environment certificate authority, and the new test initially lacked its
runner import. Neither executed a test. The environment uses its existing OS
Java trust store; no TLS bypass or machine path is added to source.

The independently reviewed initial implementation tree
`d0629c7788add44890b0bdb66234ec27b9fa54ab` passed 5,188 core cases across 95 suites
and 418 simulated Activity cases across 38 suites, with zero failures, errors or
skips. Its unsigned APK assembly and lint passed; lint reported 18 warnings and
no errors. The original 4,850-byte build log has SHA-256
`1ae05da8591f4f8d8fb1865d3dd084da4a4e0cdd3d7d30e2d53776fa42d2dfe5`.
These observations remain attributed to that earlier tree, separately from
receiving integration and hosted execution.

The initial eight named regressions cover exact recorded identities and private-field
omission, confirmed empty versus unavailable lists, pending edits and recreation,
delayed storage, retired controls and draft replacement, unconfirmed restoration,
arbitrary literal versions, and measured narrow RTL layout at twice the normal
font scale. Their separate workflow step requires every case to execute without
duplicates, failures, errors or skips. Receiving integration on accepted
`4da017adcc4112acfa1d254529b41b06fd98ccc0` retains the thirteen existing summary
cases and their complete execution guard, including the two component-form
refresh cases delivered since the initial baseline. The ninth new case advances
the accepted simulated UI clock to the actual autosave deadline: inspection must
not postpone that write, and the newly acknowledged revision must replace the
old list and its retained control. The pending-input cases observe their defined
pre-deadline interval; they do not assume pending edits remain unsaved indefinitely.
These simulated Activity cases do
not establish installed accessibility, screen-reader or product layout acceptance.

## Current results and core planning

The [saved-draft check receiver](CURRENT_DRAFT_REEVALUATION.md) now supplies the
summary's current private-rule status from the exact acknowledged revision and
loaded artifact inputs. Rechecking, unavailable and withheld states retire prior
current results; completed observations remain distinct from whole-draft readiness.
Current price coverage and resource estimates remain **unavailable**. The summary
cannot borrow findings, severity, readiness, cost lines, known subtotal or price
coverage from immutable fixture inspections or an independently selected retained
snapshot.
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

#113 acceptance remains open. The private local-rule receiver described above
adds current observations and independent severity within its bounded profile;
it does not supply an admitted current report or cost/resource producer. Complete
product outcome presentation, genuine conflict and unknown-capacity cases,
complete/partial-price and unpriced-component semantics, relevant measured T6
inputs, sticky/adaptive product behavior, installed accessibility/keyboard
evidence and the complete receiving review remain required. Neither an unavailable
label nor these limited observations satisfy those absent integrations or close
#113/#115. All dated execution evidence above remains attributed to its original
source and receiver boundaries.

The canonical acceptance record remains
`docs/delivery/android/work-packages/08.05.md` in `Pick-Your-AI/pickyour.ai`; this
engineering note does not move that path. The #508 autosave, #504 T6 form, #472
classification, #467 viewport/installed-diagnostic, #502 environment and #507
load-recovery owners retain their source and delivery routes. The implementation
does not adopt their unaccepted branches, change their gates or infer new shared
contract/placement decisions.
