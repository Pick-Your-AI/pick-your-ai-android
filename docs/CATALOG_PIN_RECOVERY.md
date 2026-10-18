# Exact catalog pin recovery (#97, partial)

**Inspect pinned component** opens the exact item ID, opaque version and snapshot
SHA-256 retained in a comparison entry. The action is available even when that
snapshot is absent from the current catalog. It reads the captured immutable
catalog when its digest matches, otherwise it requests only the exact retained
local bytes. A matching display name, item ID or version in a different snapshot
cannot satisfy the pin.

The result is a separate read-only detail inspection. It does not activate a
catalog, enable comparison against a different snapshot, select a component,
change the tray, write a draft or recalculate a report. **Retry this exact pin**
starts a new read of the same captured identity. **Browse the current catalog**
closes the inspection and focuses the current catalog search; it preserves the
query, filters and pins. A later replacement requires the existing explicit
remove/add or selection actions.

## Local bytes and evidence boundary

`PreviewCatalogPinResolver` accepts a bounded `PreviewCatalogPin`, an optional
captured `PreviewCatalogBrowser`, and a callback that reads one `ArtifactHash`.
Current detail must come from the existing immutable validated projection;
manually constructed browse labels supply no detail evidence. Retained data must
match both the returned hash metadata and a fresh SHA-256 over an independent
copy of the bytes before schema or item fields are interpreted.

The Android adapter reads only an already existing
`catalog-snapshots-v1` directory under `noBackupFilesDir`, through
`VerifiedArtifactCache.pinned`. It rejects a symbolic-link directory and delegates
file type, size, hash and nonblocking-lock checks to the existing cache. The read
uses the cache's ordinary coordination lock; it never reads or activates the
active pointer, creates the catalog directory, installs an artifact, evicts a
snapshot or repairs corrupt bytes. The current fixture loader does not populate
this directory. Real catalog acquisition, authenticated publication and a reviewed
archive retention/installation path remain separate work; a missing directory
simply produces the explicit unavailable result.

Retained input uses the existing preview bounds: at most 1 MiB, 256 items and the
bounded JSON reader's depth, token, string and numeric budgets. The recognized
snapshot schema is frozen `1.0.0`. An unknown schema marker produces an
uninterpreted result; it does not validate a future document or trigger migration.
Supported bytes still pass the unchanged frozen catalog validator and exact-pair
uniqueness checks. The UI pin's existing 160-unit ID and 128-unit version bounds
are preserved. Version strings receive no semantic-version parsing or sorting.

| Observed result | Meaning shown to the user |
| --- | --- |
| Exact captured or retained detail | Identifies the actual source, exact item/version/hash and recorded snapshot ID, revision and dates |
| Snapshot missing | Those exact bytes are unavailable locally; no active/latest snapshot was substituted |
| Hash mismatch | The supplied bytes or returned hash metadata do not match the request; fields are not used |
| Invalid snapshot | The exact bytes fail supported syntax, schema or projection checks; bytes are not repaired |
| Unsupported schema | This reader cannot interpret the recorded schema version; no migration or replacement occurs |
| Preview limit | The local parser or presentation budget was exceeded; no partial detail is used |
| Exact version missing | The supported snapshot does not contain that exact item ID and version |
| Detail unavailable | Browse-only labels cannot establish recorded detail evidence |
| Busy, failed, cancelled or integrity failure | The exact read could not complete; retry retains the same pin |

An integrity failure is the cache's verification outcome, not proof of malicious
tampering. For example, its file reader also reports a file exceeding its size
budget as a failed integrity check. Missing items and unreadable bytes do not
establish retirement or publisher withdrawal.

Recovered detail preserves the snapshot's `active`, `preview`, `deprecated` or
`retired` declaration and its exact dates, with an explicit historical-status
disclosure. It does not infer a current publisher disposition or a time-to-live.
Frozen provenance statuses keep the existing conservative evidence labels. Hash
and schema validation identify/structure bytes; neither grants source-use rights
or proves publisher authenticity. No source is fetched or launched by this flow.

This inspection deliberately attaches no pricebook. The comparison pin contains
no pricebook digest, so the current book cannot stand in for a historical one.
The existing bounded detail formatter displays the recorded catalog facts and
unknown price context. It keeps its visible literal truncation and provenance
count disclosure. **Review all recorded details** opens every projected field and
provenance entry in a [complete-value browser](CATALOG_DETAIL_BROWSER.md) belonging
to this same exact historical request. It preserves every retained character
through bounded escaped parts without acquiring a pricebook or activating bytes.

## Native lifetime and presentation

Each request captures its exact pin, catalog object, tray object and originating
row. A replacement render, changed snapshot, explicit removal, catalog reload or
another detail retires the inspection. Opening inspection dismisses an existing
comparison dialog, and opening comparison retires inspection; their retained
controls cannot change the other's state. Starting any draft storage operation
permanently dismisses the current inspection, including a reload that returns the
same missing draft. This composes with the existing storage-operation authority
without changing a pending cleanup receipt. The Activity's draft identity, acknowledged
baseline, storage state and lifecycle must also remain current. The worker's
result cannot open a replacement window for a cancelled or superseded request.
Each visible dialog has its own identity and `isShowing` check; retained retry or
browse controls cannot revive a dismissed view. Activity destruction is terminal
and cancels only this controller's own future before the existing worker shutdown.

Inspection controls use a themed vertical header above the independently
scrolling native message. Their labels wrap, and all three available actions have
48 dp minimum dimensions. The comparison entry also has a bounded action label
and 48 dp target, with its exact item identity immediately above it. Dialog text
uses plain escaped literals, with no linkification, URI grant or outgoing Intent.
Entering complete values retires the parent's Retry and Browse callbacks while
preserving the exact request for its child. Closing or replacing that child is
terminal for the request; retained field/value callbacks cannot reopen it.

## Validation and delivery

The 12 core cases include real old/new retained artifacts, unchanged active
pointer and file bytes, exact opaque versions, matching labels in another
snapshot, metadata/byte digest mismatch, unsupported/invalid input, preview
budgets, corrupted storage, typed failures, immutable result values and
cancellation. The separate 14 Activity cases exercise the actual comparison
entry, current and retained detail, missing-to-exact retry, unsupported/invalid/
absent-version recovery, a real held cache lock, corrupted bytes, cancelled and
queued requests, retired parent actions, snapshot/reload/removal/recreation,
changed draft baselines and a rejected symbolic-link directory. The comparison
transition regression replays the old differences toggle after pin inspection
opens and proves that all four saved tray fields remain unchanged. Its actual
pre-correction execution changed `comparisonDifferencesOnly` from false to true;
the correction dismisses that previous comparison before inspection starts.
The storage-transition regression separately reproduces an inspection remaining
live across an actual Missing-to-Missing Reopen. The storage-start dismissal retires
both retained actions, preserves new pending fields and a restored cleanup ID,
and permits a fresh explicit inspection after storage becomes idle.

The native graphics case measures the entry and recovery controls at 320 x 640 dp,
RTL and 2x font scale. It uses ordinary rectangle-on-screen requests and every
measured ancestor's viewport, then verifies target dimensions, full label height,
horizontal line bounds after compound padding/scroll, zero ellipsis and the final
text offset. It does not fabricate a WindowManager frame or claim installed
TalkBack, device or physical-screen evidence.

The workflow requires all 14 new Activity names to execute without failures,
errors or skips, while preserving every previously required core/native,
runtime-inventory, ignored-input and secret-scanning gate. Exact source, independent
reviews and local/hosted artifact receipts belong to the delivery PR. A first
local native run encountered a bundled-schema ZIP EOF while a simultaneous
reviewer build rewrote the shared core JAR; that unsuccessful receipt is retained
separately, and sequential current-source execution supplies the final result.
After a later recreation timeout, the test setup explicitly awaits both catalog
controls and restored draft storage before dispatching inspection; catalog
completion alone does not establish the Activity's storage readiness.

Real historical catalog acquisition and publication status, applicable pinned
pricebook evidence, installed recovery and assistive-technology behavior, and
named catalog/UX/data-consumer receiving acceptance remain open. This bounded
implementation does not close #97 or #111 or alter a frozen contract or fixture.
The canonical #97 receiving path remains
`docs/delivery/android/work-packages/07.03.md` in `Pick-Your-AI/pickyour.ai`.
