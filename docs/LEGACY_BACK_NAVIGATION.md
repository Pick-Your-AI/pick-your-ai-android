# Pending fields and legacy Back navigation

This is a partial Android [#76](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/76)
implementation for the existing synthetic preview. It protects unacknowledged
editor input when the API 26–30 Activity Back action would finish the editor.
The canonical durable draft store, save acknowledgments, compare-and-swap rules,
incoming-entry policy and draft identities remain the existing owners of their
respective behavior.

## Platform boundary

The preview supports API 26 and later. Android's documented default root-launcher
Back behavior finishes the Activity before API 31; API 31 and later normally move
the root task to the background. The new override delegates immediately to the
existing platform behavior on API 31 and later. It introduces no predictive Back
callback, dependency or manifest opt-out, and makes no new claim about non-root
or installed-device navigation on those later versions.

Primary platform reference: [Android 12 Back behavior](https://developer.android.com/about/versions/12/behavior-changes-all#back-press).

## Older-platform editor behavior

An untouched blank editor and an editor whose fields match the acknowledged
record still use the ordinary platform Back action. A difference in the title,
selection identity, context presence/value or any raw workload field requires a
choice. Raw invalid text and rejected-paste state participate in this comparison;
they are never converted to a valid default to allow departure.

**Stay in editor** dismisses the choice and preserves the current input. Showing
or dismissing the choice does not pause, restart or dispatch autosave. A valid
form's existing deadline continues normally; an actual storage transition retires
the old choice. While storage is busy, the notice offers Stay without a Leave
action, so an unresolved write is not represented as an acknowledgment.

**Leave without saving** requires the same visible choice, draft identity,
acknowledged record, storage authority and complete raw input that opened it.
The choice is consumed before delegating to the platform. It cancels the queued
autosave request and does not write or delete a draft. No new save dispatch is
admitted once the Activity is finishing. An already acknowledged draft therefore
remains available on a later cold launch.

Edits, storage transitions, pause, destruction and replacement inspections retire
the choice. Returning input to an earlier value does not reactivate a retained
button. A recreated editor uses its existing pending-input checkpoint and needs a
new Back action; the old dialog's leave authority is never restored.

## Regression evidence and limits

At accepted source `6cbb9f1de99df4c8ee24ebc5acd32bc9b1ad77ad`, the original SDK 28
Activity regression saved an acknowledged draft, entered invalid requests text
and invoked `onBackPressed`. It failed because `isFinishing` became true before
an explicit choice. The original failing XML, log, test source and compiled
artifacts are retained separately from the correction. Two earlier local setup
attempts stopped before tests and are not product failures.

`LegacyBackNavigationTest` checks the original case, explicit Stay and Leave,
unchanged stored bytes, cold reopen after Leave, clean editors, input ABA,
replacement choices, pause, recreation, an in-flight local save and the existing
valid-form autosave deadline. CI requires all eleven exact identities with no
failure, error, skip or duplicate. Focused compatibility validation also exercises
the existing autosave suite and three existing comparison-dialog Back cases.

These are simulated SDK 28 Activity/resource checks. They do not establish
installed-device, physical-device, low-memory process-death, accessibility,
predictive-gesture or full #76 acceptance. The separately owned inspection-pause
and viewport/focus work remains separate, and #76's literal criteria and canonical
receiving record remain open until their complete outcomes are supported.
