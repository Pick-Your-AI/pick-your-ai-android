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
