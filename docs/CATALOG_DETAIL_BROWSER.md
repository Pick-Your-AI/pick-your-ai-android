# Complete recorded catalog detail inspection (#97, partial)

**Review all recorded details** opens every field already retained by the
immutable selected-detail projection. The action is available from an exact
current catalog detail and from a successfully resolved
[local historical pin](CATALOG_PIN_RECOVERY.md). It supplies a complete inspection
path alongside the existing bounded summary. Browse-only labels and unavailable
pins cannot supply this action or borrow detail from a current or similar item.

## Recorded fields and complete values

The browser indexes the existing immutable detail without changing its parser,
schema, lookup, data or evidence policy. Field pages contain at most eight rows
in the following order:

| Group | Retained fields |
| --- | --- |
| Item and snapshot identity | Exact item ID, name, kind, tier and opaque version; snapshot ID, revision, capture time and SHA-256 |
| Item metadata | Recorded update time, lifecycle status, billing class and optional description |
| Specifications | Every existing schema-typed field, in the projection's stored order, including absent optional fields |
| License declarations | Every projected license field, including missing values and restrictive declarations |
| Provenance | Every indexed source URI, checked-at spelling, raw status, separately identified unassessed presentation label and notes |

Rows use fixed field names and local representation/state labels. Producer text
appears only after the user chooses a field. **Next fields** and **Previous
fields** retain the same item and snapshot; **Back to fields** returns to the
selected field's page.

Each value has numbered parts containing at most 160 UTF-16 units before the
existing literal encoder quotes and escapes them. **Next part** and **Previous
part** make every retained character reachable, including characters beyond the
summary's truncation marker and provenance entries after its first eight. The
encoder visibly escapes control, formatting and separator characters. If a
surrogate pair crosses a part boundary, both units remain as visible escapes;
neither is dropped. Paging changes only the inspected field or part.

Schema-typed fields display the projection's recorded JSON representation and its
existing type/state. The browser does not reparse it, round numbers, convert units
or reconstruct the original document's JSON whitespace. Missing values, explicit
unknowns, empty text, declared false, zero, empty collections and collections with
unknown entries remain distinguishable. The ordinary word `unknown` in an
identity or source note stays a supplied literal.

Producer provenance status and its derived presentation label are separate
fields. A recorded `verified` status continues to show **Verification
unestablished** under the unchanged unassessed evidence policy. This inspection
does not add a source check, source-use right, current availability, expiration
policy, provider/offering relationship or price applicability. Source references
remain plain text, with no outgoing Intent or automatic navigation. The existing
separate source-review adapter retains its own host policy and confirmation.

## Exact ownership and read-only navigation

A current detail retains its exact row, item/version/hash, catalog object, draft
identity, acknowledged baseline and non-reusable storage-operation authority.
Opening its child retires the visible parent's Select, Sources, Prices, Compare
and repeated full-detail controls. The parent identity remains the origin only
for the intentional child navigation. A newly opened detail, catalog reload,
snapshot replacement, storage operation or Activity destruction retires the
browser permanently. An unchanged Missing-to-Missing Reopen does not restore an
old dialog's authority.

A historical child belongs to the existing exact pin request and tray origin.
Its parent Retry and Browse controls become inactive when the child opens.
Replacing or removing the pin, starting storage work, cancellation or lifecycle
teardown retires the request and its child. The child shows the resolved old
snapshot and its recorded lifecycle status; it never activates retained bytes or
attaches the current pricebook. Closing it cannot resurrect the parent request.

Comparison and full-detail inspection also retire each other's visible dialogs.
A retained differences toggle cannot mutate the tray after full-detail
inspection starts, and an old field or value callback cannot reopen a dialog
over a subsequent comparison. Each page checks its current dialog identity and
visibility; any ordinary cancellation or dismissal permanently retires that
browser instance. A page transition installs its successor before dismissing the
previous page.

These paths do not save a draft, replace pending inputs, alter a selected version,
change comparison pins/preferences, write cache files, update an active pointer,
calculate a price or launch a source. Explicit unrelated selection, tray and
storage actions retain their existing behavior and authorization checks.

## Validation and receiving boundary

The 13 mandatory Activity cases exercise actual detail and recovery controls,
all field/provenance indices, complete Unicode and JSON value reconstruction,
missing/unknown/empty/false/zero states, retired parent/page/value callbacks,
sibling inspection, reciprocal comparison lifetime, reload/recreation and actual
storage reconciliation. Retained-byte coverage inspects an older retired record
while the newer cache pointer and every file remain unchanged. Unavailable pins
and row-only browsers have no borrowed complete-detail action. Draft store,
pending domain fields and tray state are compared where navigation must be
read-only.

The native graphics case measures current-detail and retained-detail headers,
field paging and three-button value paging at 320 x 640 dp, RTL and 2x font scale.
It requests each control's rectangle on screen, checks every ancestor viewport,
48 dp target dimensions, compound-padding-adjusted line extents and height,
zero ellipsis and the final text offset. The actual pre-correction run exposed a
clipped **Previous value part** footer label; the shorter **Previous part** label
passes the same measurements. A separate pre-correction run exposed a live
comparison dialog after full-detail entry; mutual dismissal now passes the
retained-toggle regression. No layout assertion or existing gate was weakened.

The hosted workflow requires every named case, the expected class identity,
unique executions, consistent counts and no failures, errors or skips. All
existing core/native, inventory, ignored-input and secret-scanning gates remain.
The delivery PR records exact source and independent review plus actual local and
hosted evidence. The first expanded local run failed during Robolectric temporary
native extraction because the filesystem was full; its failure report is kept
separately from the later passing execution and establishes no application result.

This remains a synthetic, unsigned Activity simulation. Accepted real catalog and
source rights, provider/offering identities, applicable prices, historical
acquisition and publication dispositions, installed-device and assistive-technology
behavior, and named receiving acceptance remain separate requirements. This
bounded implementation does not close #97 or change its canonical receiving path,
`docs/delivery/android/work-packages/07.03.md` in `Pick-Your-AI/pickyour.ai`.
