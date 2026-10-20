# Edit an existing local component role

This implements a bounded portion of
[#112](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/112): an existing
T1/T2 selection can change its explicitly entered role without removing and adding
the component. The canonical receiving path remains the website record
`docs/delivery/android/work-packages/08.02.md`. The complete work package stays open.

## Visible behavior

**Manage saved components → Edit role** opens a dedicated, labeled role field for
the exact saved selection. **Commit local revision** explicitly confirms the
entered value. The role stays subject to the existing frozen schema and local
input budget, with no trimming, normalization, role registry or automatic choice.
Custom roles and the frozen pattern's exact accepted spelling are retained. A
duplicate `(kind, role)` rejects the entire edit even if the other selection is
disabled. Changing away from `primary` neither promotes nor enables another
selection.

The role form preserves the complete original settings object, including settings
larger than the small JSON editor's 16,384-character limit. The settings never pass
through that editor and are not copied into the recovery Bundle. Selection ID,
kind, catalog item/version, enabled state, notes, extensions and links are unchanged;
all other selections, guest input and metadata remain retained. Links remain
visible for review and do not establish that changed roles remain compatible.

An unchanged role is a byte-preserving no-op. Invalid or duplicate role text stays
in the same form with inline feedback until corrected or explicitly canceled.
Repeated taps, old canceled controls and replaced/recreated dialogs cannot produce
another revision or replace newer pending input. In-flight controls freeze until
the actual result arrives. Conflict or uncertain acknowledgement requires explicit
reopen; the UI never substitutes new authority into the old form.

## Command and local history

The existing `SelectionCommand.Configure` accepts an optional explicit role.
Absent or unchanged role preserves `CONFIGURE` and its settings-only meaning.
A real role change produces `CONFIGURE_ROLE`, with the exact role and supplied
settings applied together by the existing reducer and managed-library CAS adapter.
The dedicated role form uses a bounded role-only preparation helper that supplies
the original immutable settings.

Private local envelope **format 5** is introduced only when a real role change is
committed, or retained when writing an existing format-5 draft. New ordinary drafts
still use format 4. The decoder rejects `CONFIGURE_ROLE` in earlier formats and
requires that an actual role change occurred; the action cannot hide changes to
identity, catalog, activation, notes, extensions, metadata or relationships.
Format-4 `CONFIGURE` retains its original narrow meaning. All frozen v1 schemas,
examples and manifests remain unchanged.

Migration appends exactly one acknowledged revision and preserves every earlier
number, content, change, restore target, metadata map and immutable artifact. Reads,
rejections and no-ops do not upgrade an envelope. Current limits, stale/foreign CAS,
concurrent writes, pre-activation failures and uncertain activation keep their
existing outcomes. A later restore can recover an earlier role while retaining
current metadata and the full format-5 history. No history is pruned to fit an
upgrade and no later write downgrades the format. Future format 6 is unsupported
and preserves stored bytes while blocking writes.

## Recovery and accessibility

The [component checkpoint](COMPONENT_FORM_RECOVERY.md) uses **Bundle version 2**
and a distinct `ROLE` mode. It retains the exact raw role, original local draft ID,
baseline identity, selection ID and in-flight flag. Mode validation requires empty
settings, absent catalog pins and no enabled value to apply. Version-1 add and
settings recovery retain their existing semantics and cannot introduce a role edit.
The role's raw input shares the existing 20,000 UTF-16 character recovery bound;
over-budget input becomes an explicit unavailable marker without a valid prefix.

Pausing captures bounded role input and retires the old form and its callbacks
before dismissing it. Resuming restores availability without restoring consent.
After pause or recreation, review opens a fresh role form only for the same clean draft,
saved baseline and existing supported selection, and only if no write was in
flight. A changed context or in-flight checkpoint keeps the exact role selectable
in a read-only review, even after explicit reopen. No recovery step dispatches a
command, adopts a new saved head or copies settings from a different selection.
The user must give fresh confirmation. Discard clears only pending input.

The form uses the existing platform Views labels, polite inline status and
scrolling body. A measured native-graphics simulation exercises a 320-dp RTL
screen at double font scale, complete text and reachable input/commit controls.
That is bounded simulation evidence, not TalkBack or device qualification.

## Verification and remaining acceptance

The core suite adds 20 required cases across `DraftSelectionRoleTest` (7),
`LocalDraftRoleStorageTest` (8) and `PreviewSelectionRoleFormTest` (5). They cover
all eight supported kinds, exact role/settings immutability, unchanged old callers,
disabled-role collisions, no implicit promotion, exact frozen pattern boundaries,
large retained settings, format-4 predecessor preservation, tampered action/version
rejection, no-op/rejection/CAS, concurrent migration, activation failure/uncertainty,
restore and full-history bounds.

`ComponentRoleActivityTest` contains eleven actual platform-View/worker/storage cases in
API-28 Robolectric simulation. They exercise preserved large settings/links through
reopen, correction followed by one save, no-op/cancel/retired controls, repeated
Parcelled recreation with new consent, stale saved head, wrong draft/missing target,
an in-flight write that actually completes, unavailable oversized input, narrow
RTL large text, retained Commit replay after actual pause, and picker/confirmation
retirement without restoring consent. `ComponentRoleCheckpointTest` adds four cases for exact maximum
Unicode Parcel round-trip, version-1 compatibility, malformed mode-specific state
and over-budget unavailability. The hosted workflow requires all 15 native cases
by exact suite/name, unique identities, correct totals and no failure, error or skip.

This evidence does not evaluate dependent deployment requirements, compatibility,
cost, readiness or catalog source rights. Integrated domain/UX/data receiving
review, actual installed process-death and device/OS coverage, physical persistence
faults, TalkBack/keyboard qualification and release acceptance remain separate.
The synthetic preview remains unsigned and release-disabled; broad #112 is open.
