# Selected catalog details (#97, partial)

The bounded synthetic browser projects detail from the same validated catalog
snapshot used for its rows. Lookup requires the exact item identity, version,
complete row and snapshot SHA-256. It never looks up a latest version or joins a
similarly named record. A browser constructed only from row labels has no detail
evidence. The existing selection, editor-baseline and terminal lifecycle guards
remain responsible for draft changes.

The immutable projection retains snapshot ID/revision/capture date, item update
date/status, recorded billing class, optional description, schema-typed specs,
license declarations and every provenance entry. Snapshot revisions and integer
specifications retain arbitrary precision within the existing bounded JSON input
limits. Numerical fields retain their canonical units and receive no conversion.
Provider references are displayed as provider references; frozen v1 does not
establish a specific offering or serving route from them.

Specification fields come from the checked-in frozen schema, including optional
fields absent from the selected record. Missing, explicitly unknown, declared
false, zero and empty collections remain distinct. Arrays containing `unknown`
retain their other values and explicitly indicate the unknown entries; an empty
capability collection never means complete absence of support. License fields
retain absence and restrictive false values rather than inventing reuse rights.

Each provenance entry retains its source URI, exact checked-at spelling, notes
and raw frozen status. The shared evidence presentation policy supplies the
unassessed label: a raw `verified` declaration remains **Verification
unestablished** without an accepted independent assessment. Entries are not
collapsed into a best or combined grade. Dates remain pinned metadata and do not
invent an expiration threshold or a source-check event.

The dialog uses plain text. Identity and free-text strings retain their literal
spelling, including the word `unknown`; semantic unknown labels apply to typed
field states. Long literals are visibly truncated; control and
format characters are escaped. It shows at most eight provenance entries and
states the displayed/retained count. All entries remain in the immutable
projection. This is a bounded preview, not the complete real-data presentation.
Source strings in that message are not linkified. The separate **Review sources**
action opens an eight-record page with every provenance index still reachable.
Its [navigation adapter](NAVIGATION_BOUNDARY.md#android-source-navigation-adapter)
requires an allowed HTTPS host and a second explicit confirmation of the full
outgoing URL. The synthetic application configures no trusted source hosts, so
navigation remains blocked pending a reviewed real-source configuration. Missing
source URIs have a recoverable explanation. Historical recovery remains separate.

The detail view now joins [recorded pricebook context](CATALOG_PRICE_CONTEXT.md)
by exact item ID and version. It retains book identity/revision/hash/capture date,
canonical meter, exact decimal amount/currency, optional region/effective dates,
conditions and each rate's own unassessed provenance. Every alternative remains
recorded; up to eight rates and conditions per rate are visibly bounded in the
message. This is fictional historical metadata, without an applicability query,
accepted rate or total. Missing price data stays unknown, and a recorded zero or
free/included billing class is not an applicable zero-cost quote. Catalog records
receive no price fields. No evaluation, compatibility result, source assessment,
selected draft revision or pin changes when detail is opened or cancelled.

## Verification and receiving boundary

Core regressions cover all 29 kinds, exact versions and snapshot identity,
missing historical versions, retired records, restrictive/absent licenses,
missing/unknown/false/zero/empty values, multiple conflicting provenance entries,
wide numbers and collection immutability. The mandatory hosted Activity gate
includes selected model detail without a save, literal `unknown` identities
alongside explicitly unknown specs, and bounded literal provenance
rendering without automatic navigation. Additional source-adapter cases exercise
confirmation, cancellation, exact bounded destination display, hostile/unconfigured
hosts, paging, missing handlers, single-use dispatch and retired callbacks using an
explicit synthetic host policy. Existing browsing, selection and retired
callback regressions remain required. Local core results and simulated Activity
results do not establish installed-device or receiving acceptance.

| Exact live #97 acceptance criterion | Bounded result | Remaining acceptance |
| --- | --- | --- |
| Versioned specs, provider/offering identity, license conditions, source links, dates, price conditions and limitations visible. | Exact fixture specs, provider reference, license declarations, source strings/dates, recorded rate metadata and explicit rate/route limits are presented. Source navigation has an explicit policy/confirmation adapter. | Accepted real catalog labels/data, offering identity, applicable rates, full rate browsing, reviewed source hosts and installed external-handler behavior. |
| The displayed item version and source dates match the requested snapshot; a missing pinned version never silently resolves to the latest component. | Exact item/version/hash lookup and source-date retention; absent pins fail without substitution. | Installed historical recovery, withdrawn-publication behavior and receiving review. |
| Every presented factual field uses accepted evidence or is visibly unknown, and source verification labels follow the accepted evidence semantics. | All values are marked synthetic; missing and unknown distinctions and conservative evidence labels are preserved. | Approved real evidence, source-use rights and the Android catalog/UX/data-consumer acceptance. |
| Price context identifies currency, region, meter and effective period where supplied; absence of a usable rate is not shown as free. | Exact recorded fixture pricebook/item/version join retains supplied units, currency, region, effective dates and conditions. Missing records, unresolved applicability and zero amounts never become an accepted free quote. | Accepted real pricebook applicability/freshness policy, historical receiving cases and quote integration. |

This document does not replace the canonical acceptance record at
`docs/delivery/android/work-packages/07.03.md` in `Pick-Your-AI/pickyour.ai`.
No issue, assignee, Project state, contract, fixture, dependency, manifest or
signing configuration is changed by this partial implementation.
