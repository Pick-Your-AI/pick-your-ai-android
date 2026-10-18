# Recorded catalog price context (#97, partial)

The selected catalog detail now shows the bundled frozen Pricebook's recorded
metadata for the exact catalog item ID and version. The two original fixture
rates are fictional estimates. Displaying them establishes neither their
applicability to the draft nor a current quote. CatalogItem, BuildSnapshot and
the frozen examples remain unchanged.

## Snapshot and exact identity

`RecordedPricebook` reads one bounded, schema-validated frozen-v1 document. It
retains its ID, arbitrary-precision revision, exact capture timestamp and SHA-256
of the received bytes, including whitespace. This hash identifies bytes; it does
not authenticate a publisher or grant source-use rights. Parsed mutable JSON
never escapes the reader. Rate lists, exact item/version groups and conditions
are immutable; every alternative remains in its original order.

Each rate retains its own ID, catalog item ID/version, canonical meter,
`unit_price.amount` decimal string, currency, optional region, `valid_from`,
optional `valid_until`, price type, conditions and provenance. Decimal spelling,
including trailing zeroes, is preserved without floating-point conversion or
rounding. Optional absence is distinct from an empty string or empty condition
array. Literal `unknown` in an identity, region, condition or note stays literal.
No item-name match, newer-version fallback, default currency, global region,
GB/GiB conversion, FX rate or alternative summation is introduced.

The existing local input budgets apply: one MiB, bounded JSON depth, tokens and
literal lengths. Duplicate rate IDs and unusable or reversed intervals reject
the whole pricebook, including invalid rates unrelated to the selected item.
Supported `java.time` precision/range checks for capture and effective dates are
the same checks previously applied by `RateScreen`. Raw provenance dates remain
recorded metadata. Streams are caller-owned by the core reader; the asset adapter
closes the streams it opens. Failures disclose no input, exception or path.

`RateScreen` now uses this same immutable projection and also accepts it directly.
Its result includes the received-byte hash alongside pricebook ID/revision and
the caller's explicit as-of instant. Its exact query fields, conservative end
boundary, unresolved-region/conditions/non-list exclusions and permanent
`EVIDENCE_REVIEW_REQUIRED` gap remain unchanged. Metadata lookup returns all exact
item/version records; it does **not** perform a screening query. The Activity does
not derive a query from the device clock or partial pending workload fields.

## Android presentation and recovery

The existing single worker reads the catalog and pricebook as one result. The
current Activity installs both together. A failed pricebook read produces
unknown pricing while preserving a valid catalog; a failed catalog read exposes
neither catalog rows nor a previous pricebook. Each retry produces a fresh result
and replaces the previous pricebook, including replacing it with absence. A
destroyed Activity cannot apply the result.

Opening a selected detail presents the pricebook identity, revision, capture date,
hash, exact matching rate count, recorded amounts and all supplied pricing fields.
The message displays at most eight rate records and eight conditions per record,
with explicit displayed/retained counts. Long values carry a truncation label;
control and format characters are escaped. The projection retains the complete
bounded values and all alternatives. A fuller rate-inspection interface remains
separate from this bounded fixture detail.

No matching item/version and unreadable pricing have distinct visible recovery
messages. Neither means zero cost. A missing region does not mean global; a
missing end date does not promise perpetual validity. An empty conditions array
does not prove eligibility. Even a recorded zero, list price, or catalog
free/included billing class supplies no accepted free or complete quote.

Every rate keeps its own provenance and unassessed evidence label. A raw
`verified` value displays **Verification unestablished**. Dates do not prove that a
source was read, and conflicting alternatives are never collapsed into a winner.
Rate source URIs are plain text. The separate catalog-provenance navigation
adapter does not automatically authorize or dispatch a rate's URI.

Opening, cancelling, reloading or recreating this view does not save a draft,
change a selection or pin, derive totals, or alter an evaluation. Retired detail
callbacks retain the existing Activity, dialog, baseline and exact catalog
authority guards.

## Evidence and policy boundaries

The implementation consumes only the unchanged synthetic fixture. Research main
[`ca15501f`](https://github.com/Pick-Your-AI/pick-your-ai-research/tree/ca15501f3e3b09acc31462f9aba7cf0c9923f163)
was inspected for the current receiving boundaries:

- The [accepted T4 interface receipt](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/41#issuecomment-6242626857)
  retains selected dated snapshots and conservative source-check semantics.
  It supplies no universal TTL or source-use permission for missing rights rows.
- The [scoped cost receipt](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39#issuecomment-6246002487)
  accepts bounded unit/unknown-cost principles while retaining unresolved prices
  and consuming-owner acceptance. It does not approve new mobile rates or totals.
- The later price-eligibility/refresh/conflict supplement in
  [`catalog-data-sources.md`](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/67002efb9dba22baf34e56da895da6a40a0da003/research/T0-configuration/catalog-data-sources.md)
  and the [cost-estimate guidance](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/1c6f19d24976ac8f3dc88bdd43788bcfee3111c4/research/T6-deployment-operations/cost-estimate.md)
  do not establish accepted shared eligibility, region, FX or rounding defaults
  for this adapter. No proposed market rate is imported.

Core regressions cover exact versions, alternatives, immutable fields, wide
revisions and decimal lexemes, raw date offsets, conditions/region absence,
conservative evidence labels, byte hashes, invalid whole-document inputs, caller
stream ownership and equivalence between stream/object candidate screening.
The mandatory Activity regressions exercise actual compute/storage/model details,
preserved draft baselines and pending state, unknown versus recorded zero,
literal conditions and evidence, bounded alternatives, failed asset reads, retry,
recreation and retired detail callbacks. The usual strict unsigned compilation,
lint and simulated UI gate remain required with the core and frozen-reference
checks. Simulated results supply no installed-device evidence.

This is partial evidence for #97's price-context criterion and the pinned-input
portion of [candidate screening](RATE_SCREENING.md). Accepted real pricebooks,
structured eligibility, authenticated publication, current-state freshness,
source rights, historical withdrawal/recovery, complete quote integration, full
rate browsing and Android catalog/UX/data receiving review remain outstanding.
The canonical #97 acceptance record remains
`docs/delivery/android/work-packages/07.03.md` in `Pick-Your-AI/pickyour.ai`.
No whole work package is closed by this source change.
