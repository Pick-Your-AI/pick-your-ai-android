# Retired editor callbacks (#76 / #81, partial)

Activity destruction is terminal for editor callbacks. Entry points reject retained
buttons/dialogs before changing retired editor state, opening another dialog or
submitting to the shut-down worker. The optional-tier section closes permanently;
its opening, choice and confirmation callbacks recheck terminal/editable state and
the captured saved baseline. This does not cancel or falsely acknowledge a write
that was already dispatched before destruction. Existing save-in-flight Bundle
reconciliation still requires explicit reopen when durability is uncertain.

Context and workload text callbacks check the owner's lifetime before changing
their pending model. A retired workload filter returns the existing replacement
segment, so ordinary edits and oversized pastes cannot change its values or set a
rejection flag. The text watcher also rejects a retired callback before updating
state or notifying the Activity. Live editing and whole-replacement paste rejection
keep their existing behavior.

The hosted paused-looper gate includes retained editor buttons, a retained revision
comparison picker and a retained tier confirmation after recreation. Tests assert
no new dialog, no durable revision/activation change and retained current pending
fields. These are simulated callback-ordering regressions, not Android process-kill,
physical-device or power-loss qualification. Exact candidate build/lint and all
required Activity cases must pass before merge.

The retained-workload regressions keep the previous section and text controls,
recreate into a pending form, then deliver normal edits and an oversized paste.
They verify the retired model, current pending fields, save readiness and unchanged
durable baseline after draining queued callbacks.

Catalog, findings and cost sections have their own terminal guards in the accepted
catalog lifecycle delivery; this slice does not replace or duplicate those controllers.
No schema, persistence transaction, analysis semantics or issue status changes here.

## Canceled local library and reopen dialogs (#86 / #76, partial)

Cancellation or dismissal also ends the authority of a dialog in a live Activity.
The library picker, entry actions, delete/retry confirmation, new/duplicate
confirmation, pending-recovery copy and Reopen confirmation must still be showing
and the Activity must be idle before opening a child dialog or dispatching work.
A retained callback from **Keep edits**, Back, Close or another dismissal cannot
later create/delete a local draft, change the active pointer or replace pending
inputs. Reopen additionally retains its original draft identity and saved baseline;
a later saved revision cannot become the authority for an older confirmation.

These guards run before the existing canonical library/persistence operations.
Expected source, pending-form and compare-and-swap checks remain in force. A
confirmation accepted while live still dispatches through the same serial worker;
the dialog's subsequent normal dismissal does not cancel or misreport that write.

The separate `LibraryDialogAuthorityTest` suite has ten named CI-required cases.
Eight retain callbacks after actual dismissal and drain both the serial worker and
UI queue before checking the durable entry list, saved baseline, active pointer,
pending title/objective/workload and save readiness. Another exercises successful
new/duplicate/open/delete operations, source preservation and rejected duplicate
dispatch; the last rejects an older Reopen confirmation after a later save.
The tests use the actual local storage implementation in a paused-looper Activity
simulation. They establish neither installed process-death behavior nor device
filesystem, power-loss, dialog-accessibility or remote deletion acceptance.

The canonical modal/cleanup source originated in [PR #250](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/250).
Its composition with accepted main passed nine of the ten independent cases; the
still-visible Reopen baseline case failed by replacing newer pending title text.
The narrow identity fence makes all ten pass, alongside all 80 existing Activity
regressions in that composition. Full #86 and #76 acceptance remains open, with
canonical receiving paths unchanged.
