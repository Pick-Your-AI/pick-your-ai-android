# Compare two saved local drafts

Partial [Android #117](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/117)
adds **Compare saved local drafts** beside the existing retained-history controls.
The user explicitly chooses a before draft, before revision, after draft and after
revision. Either revision may precede its saved head. The two draft IDs and revision
numbers remain visible even when both counters happen to be equal. The same local
source may be selected twice, and reversing the choices reverses the comparison.

## Captured sources and immutable results

The existing `PreviewDraftLibrary.list` runs once on the serial worker under its
canonical library lock. Its bounded result is a captured set of saved histories.
The choice pages show eight entries at a time with counts, complete wrapped local
IDs and explicit previous/next actions. Revision choices show the title recorded
in that revision, not the current head title. Missing, unsupported, unreadable and
deleting entries remain visible without a choose action. Failed listing offers
an explicit close-and-retry outcome instead of selecting another draft.

`PreviewLibraryRevision.capture` requires an available nondeleting entry, an exact
directory-ID match and the exact retained revision. It retains immutable content
and metadata with the local ID, selected revision, captured history identity and
historical-availability flag. It holds no `DraftVersion` or persistence adapter.
The private canonical library-root binding rejects pairs from different managed
libraries, including identical `guest-draft` directory names. Missing revisions
have no head, nearest-revision or current-metadata fallback.

The captured pair is pure. Later external edits or deletion do not replace its
contents; the UI explicitly labels that behavior. An operation started by the
current Activity retires the entire inspection before that operation proceeds.
Closing and reopening comparison performs a new explicit read. Captures cannot
be used to reopen a deleted draft, select an active draft, recover pending input,
restore a revision or acquire a newer writer's save authority.

Both the single-history API and this pair call the same exact local comparator.
Selections and links match only by their actual local IDs. Independent IDs with
the same catalog item or role appear as additions/removals; no role, title or
catalog heuristic invents correspondence. Exact JSON numeric equality, stable
field ordering, boolean/null/absence distinctions and literal decimal strings
remain the accepted behavior. Guest metadata comes from each selected revision.
If either guest form is unavailable, it remains unavailable without reconstructing
its title/sample pins from domain inputs or either current head. Arbitrary metadata
values stay undisclosed; only the existing metadata-divergence flag is shown.

The complete comparison browser is reused: every changed field has a numbered
page, and every full key/before/after value has ordered escaped literal parts.
Every page additionally shows both local draft IDs, selected revision numbers,
their own saved titles and captured history identities. Unsupported historical
guest metadata is distinct from an entered empty title. URLs remain opaque text.

## Editor and dialog lifetime

The entry point does not open or select a library row for editing. Active draft ID,
original saved baseline, pending title/context/workload fields and invalid raw
input remain in the editor. No library mutation, current-selection write,
component command, save, restore, duplicate or recovery operation is dispatched.
Unsupported cold external entry cannot open this inspection before explicit local
entry recovery resolves a draft identity.

Each inspection owns a single-use request and captures the Activity's nonreused
storage authority, current draft ID and saved baseline object. Every choice and
page requires its currently visible owned dialog. Replacement installs the next
owner before dismissing the old dialog. Back navigation discards the replaced
choice's authority; retained buttons cannot change another pair. Loading and
comparison each have a cancellable preparation page and verify that same page
before publishing a worker response. Entry and callbacks require a resumed
Activity. Cancellation, replacement history/comparison, storage-operation start,
pause or Activity destruction retires the context permanently. Resume does not
reopen inspection; a fresh explicit entry captures a new request. No history is
serialized into a Bundle and recreation does not replay inspection. See
[foreground inspection retirement](EDITOR_LIFECYCLE.md#foreground-history-and-revision-inspections-76-partial).

The scrollable native View layout wraps source IDs and choice labels and keeps
48-dp button targets. It uses the existing escaped literal formatter for historical
titles. No custom drawing, web view, automatic link, network request, permission,
new storage format or frozen-contract change is introduced.

## Validation and remaining acceptance

Eight core cases exercise older revisions from different local drafts, equal
counters with different identities, missing/failed/deleting/mislabelled entries,
foreign-root rejection, unavailable historical guest fields, numeric and opaque
value preservation, exact-ID matching, reverse/same-source results, immutable
captures after external writes/deletion, stale save-token rejection and duplicate
lineage without false content differences.

Twelve native simulated cases drive the actual entry point and choices, full
value inspection, pending-input and durable-byte preservation, old-control
rejection, cancellation at every picker and both worker boundaries, replacement
history, same-baseline storage starts, pause/resume, recreation, captured data after later
edits/deletion, failed/unavailable reads, bounded pagination, unsupported entry,
and measured narrow RTL/double-font layouts. The workflow requires every named
case with exact actual totals and rejects failures, errors, skips and duplicates
without freezing future suite growth. Run the normal strict core, preview,
assembly, lint and frozen/ledger gates for the final source composition.

These are local saved editing inputs. They are separate from shared BuildSnapshot
identity, recorded cost or resource estimates, rule outcomes, catalog/ruleset/
pricebook evidence binding and historical report availability. No shared build
equivalence, new evaluation, free price, latest evidence or recommendation is
inferred. Installed Android accessibility/lifecycle, physical-device behavior and
receiving work-package acceptance remain separate gates. This slice leaves
#117 and its broader acceptance criteria open.
