# Current finding to saved-selection management

This is a bounded navigation follow-up to [Android #101](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101).
A Current finding can already show the saved selections that its actual immutable
result reports as affected. The new **Manage this saved selection** action opens
the existing management menu for the exact selected ID in that captured saved
record. It does not edit a selection, select a candidate, change an evaluator
outcome, establish cause, or promise that a management action will resolve the
finding.

## Exact destination and retained presentation

The route uses the finding's reported selection IDs and the same captured
`PreviewStoredDraft`. It resolves a unique ID only within that record's
`components.selections`. Kind, role, catalog item, exact version, enabled state,
settings and links remain the existing saved values. There is no primary-role,
current-catalog or first-same-kind fallback.

The existing finding text and its Close, Back to observations and Full text
actions are unchanged. The selected context retains its original message and
Close, Back to affected selections and Complete version actions. A small
wrapping action in the same accessible custom-title pattern opens management
without competing with the scrollable message panel. The complete escaped
version route remains available before navigation. A finding with no affected
IDs offers no selection-management destination; an unresolved reported ID
remains explicitly unavailable.

## Authority and explicit actions

Every new entry callback requires the exact Current source object, Current
analysis state and showing dialog. The dialog owner is consumed before the
handoff, so replaying the old button cannot open another menu or replace a later
picker. The Activity checks its foreground lifetime, acknowledged saved-record
reference, current draft/storage/check authority, input and catalog containers,
pending-field equality and existing component-review availability both before
and after retiring sibling inspection windows.

The new SelectionEditSection entry checks the same current baseline and normal
management availability, resolves the exact ID again, and delegates to the
unchanged management menu. Its existing Edit settings, Edit role, Enable/Disable,
Remove and Compare choices actions retain their normal captured identities,
explicit confirmations, lifecycle guards and managed compare-and-swap writes.
Opening the menu itself performs no write or compatible-choice calculation.

**Compare choices for this slot** still requires another explicit action. It
uses the unchanged picker and typed evaluator with the selected ID, kind and
role. Viewing its choices does not commit one. The existing choice confirmation
and canonical management confirmation, where required by that route, remain
separate user actions. A local compatible comparison is not proof that the
whole draft is complete or ready.

Retry, observed source/input replacement, pending edits including an ABA return,
sibling inspection, pause, store switching and recreation retire the Current
window's callback authority. Navigation does not add filesystem monitoring or a
synchronous disk read on each click. The existing Current evaluation performs
its established fresh durable reads; a later edit uses the established managed
CAS boundary. An unseen concurrent durable revision is therefore rejected by
that write boundary rather than overwritten.

## Source regressions and qualification

`CurrentFindingSelectionManagementActivityTest` uses a real saved draft, the
schema-admitted synthetic catalog and original rules, real Retry/reopen and the
actual evaluator. It does not construct a Current result or replace the
management/picker producer. Its two-role model fixture has distinct catalog IDs
and exact matching versions; saved order differs from the evaluator's reported
ID order. Admission, source/catalog/rules bindings, actual foundation failure
and reported IDs are asserted before the missing-entry assertion.

The eleven declared methods cover the exact secondary destination, explicit
comparison slot, no-ID control, one-use and retired controls, sibling/retry/input
retirement, pending-field ABA, replacement/removal/disable, store switch and
lifecycle recovery, a real changed durable-head CAS rejection, and narrow RTL
large-text reachability. The geometry case measures the real attached window,
all four full action targets and complete labels, original message-tail
reachability, and native action-bar scrolling when an action needs revealing.

The new workflow step is additive after the existing affected-selection guard.
It requires every declared name, direct unique cases in the exact class, matching
test totals and zero failures, errors or skips. It permits additional distinct
passing methods without silently omitting any required case. All older workflow
programs and test files remain unchanged.

The tests-only baseline and corrected candidate must be executed and their
original console/XML and exact source inventories retained before delivery.
Source inspection does not establish a passing run. Local Robolectric geometry
and lifecycle checks remain distinct from installed finding-specific journeys,
TalkBack, physical devices, low-memory handling, release and whole-issue
acceptance. Existing retained-report navigation under #624, the separate #114 scope,
typed-editor owners and the original #101 acceptance
coordinate are preserved.
