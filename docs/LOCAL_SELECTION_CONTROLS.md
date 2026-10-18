# Explicit local T0/T1/T2 selection controls

Partial [#112](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/112) and
[#98](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98), using the
accepted native Views preview and canonical managed-library command adapter.
Canonical receiving records remain the existing website paths `08.02.md` and
`07.05.md`; this file does not relocate acceptance or close either issue.
The [foundation selection continuation](FOUNDATION_SELECTIONS.md) separately
references [#83](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/83).
It makes frozen `workload` and `deployment_mode` choices editable through these
same controls, with kind-specific settings guidance and explicit activation/roles.

The saved-component section exposes a separate exact-fixture component chooser.
It does not reinterpret the old guest sample-selection metadata as a typed domain
selection. Every listed item keeps its exact kind, catalog ID, version and captured
snapshot hash. The current frozen catalog is synthetic. There is no network
lookup, source-rights assertion, latest-version fallback, incoming component-link
route or automatic draft edit when a catalog detail opens.

Add requires an explicit role, visible enabled checkbox (initially unchecked) and
an exact settings JSON object. The form starts with `{}` as visible empty input;
required fields are never invented. Required accelerator/compute/context values
must be entered according to the frozen schema. The small technical settings
editor accepts at most 16,384 source characters and parses a single bounded JSON
object with duplicate-key rejection. Raw invalid or over-limit text is not prefix
truncated, is not saved and remains in the open form for correction. Existing
settings exceeding this small editor limit remain saved and cannot be truncated
through this editor. Raw credentials must not be entered; applicable settings
accept only opaque secret references under the frozen schema.

Replace chooses an existing selection of the same kind and retains its identity,
role, enabled state, settings, notes, extensions and links. **Edit settings** changes
only settings. **Edit role** changes the explicitly entered role while retaining
the complete saved settings, including values beyond the small settings editor's
limit. It keeps the selection identity and all other content. Duplicate roles for
the same kind reject the whole edit, including a collision with a disabled
selection. See [explicit selection roles](LOCAL_SELECTION_ROLES.md) for the local
history format and recovery boundaries. Disable/re-enable retains settings and links. Removal confirms the exact
selection and lists its incident link IDs; only that selection and those links are
removed. No secondary selection is promoted. All other frozen kinds remain stored
and visible but outside this T0/T1/T2 mutation UI.

Controls require a clean acknowledged guest form. A dialog captures the exact
record object, draft context and applicable catalog snapshot. Dispatch checks
these again, disables editing, prepares bounded input on the existing worker and
uses the library-bound adapter with original revision/hash. Repeated taps cannot
create another revision. External concurrent edits are detected by canonical CAS;
conflict and uncertainty revoke edit authority until explicit reopen. Invalid
forms and ordinary command rejections stay visible without acknowledging a save.
Every mutation dialog has its own polite inline feedback, cleared on retry. It
shows the exact outcome wording: correctable invalid/rejected input remains
distinct from conflict or uncertain acknowledgement requiring explicit reopen.
The latter never claims that no revision was committed.
Dismissing or canceling a dialog revokes its callbacks. While a command is in
flight, form fields, checkbox and cancellation are disabled so later user edits
cannot be silently discarded by completion. Invalid/rejected outcomes restore
the same form controls and raw input. Destroying the Activity terminally closes
this section and its dialogs; retained buttons cannot dispatch to a closed worker.
The [bounded component checkpoint](COMPONENT_FORM_RECOVERY.md) retains unfinished
add/settings/role input for explicit review after recreation. It supplies no command
or save authority. Completed cancellation and explicit discard clear that pending
input; destructive confirmations are never restored.

Successful status means only local persistence. UI copy explicitly withholds
compatibility, pricing, deployment requirements and readiness. Canonical v1,
role/cardinality rules and accepted storage history remain unchanged. Dependent
requirements after deployment changes, incoming trusted routes, full product
forms, installed process-death recovery, named domain/UX/data receiving review,
TalkBack/device qualification and complete issue acceptance remain outstanding.

## Validation scope

Three core form regressions exercise exact numbers/role/enabled state, missing
required settings, malformed/duplicate/oversized input, schema rejection and
replacement preservation. Seven mandatory paused-looper Activity simulations cover
add/configure/toggle/remove, invalid input retention, concurrent-head conflict,
repeated/retired and canceled callbacks, in-flight field freezing and exact
replacement no-op with dirty-form blocking.
The original source checkpoint's 43 existing Activity simulations and 50-case
Activity total are historical counts. Current-main receiving retains every named
Activity, cost-presentation, catalog-snapshot and complete recorded-price-browser
regression in the current workflow, alongside these seven component-control
additions. Hosted strict unsigned build/lint, all current named report guards and
artifact-linked inventory/source-input checks must pass on the reviewed receiving
source before this UI slice is accepted for merge. Local core tests do not
establish Android build, installed/emulated runtime, physical-device or release
evidence.
