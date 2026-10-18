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
inputs. Reopen additionally retains its original draft identity, saved baseline
and a private storage-operation identity. Every storage start replaces that
identity on the UI thread, including reloads that return the same missing draft
or saves that retain the baseline after a conflict. An older confirmation cannot
regain authority when visible values return to their previous state. A fresh
Reopen captures the current identity and can still reconcile a missing,
conflicted or uncertain draft.

These guards run before the existing canonical library/persistence operations.
Expected source, pending-form and compare-and-swap checks remain in force. A
confirmation accepted while live still dispatches through the same serial worker;
the dialog's subsequent normal dismissal does not cancel or misreport that write.

The separate `LibraryDialogAuthorityTest` suite has twelve named CI-required cases.
Eight retain callbacks after actual dismissal and drain both the serial worker and
UI queue before checking the durable entry list, saved baseline, active pointer,
pending title/objective/workload and save readiness. Another exercises successful
new/duplicate/open/delete operations, source preservation and rejected duplicate
dispatch. Three reject an older Reopen confirmation after a later save, a real
competing-writer conflict or a newer Missing-to-Missing reload. The conflict and
missing cases also require a fresh Reopen to reconcile successfully. Each test
starts with a missing draft; cases that need an acknowledged baseline explicitly
prepare the same saved record and pending fields before exercising callbacks.
The tests use the actual local storage implementation in a paused-looper Activity
simulation. They establish neither installed process-death behavior nor device
filesystem, power-loss, dialog-accessibility or remote deletion acceptance.

The canonical modal/cleanup source originated in [PR #250](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/250).
Its composition with accepted main passed nine of the ten independent cases; the
still-visible Reopen baseline case failed by replacing newer pending title text.
The narrow identity fence makes all ten pass, alongside all 80 existing Activity
regressions in that composition. Subsequent independent reviews
[5562286207](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/277#pullrequestreview-5562286207)
and [5562329803](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/277#pullrequestreview-5562329803)
reproduced conflict and repeated missing-state losses. A separate competing-writer
probe confirmed the conflict; the missing-state review also proved that comparing
the previous readiness value alone still fails. The operation-identity guard
passes both retained reproductions and their fresh-dialog controls. Those cases
are consolidated into the existing suite with their independent-review origin
preserved; current-main CI must execute all twelve and the accepted recorded-price
browser cases. Full #86 and #76 acceptance remains open, with canonical receiving
paths unchanged.
