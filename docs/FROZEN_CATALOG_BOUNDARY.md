# Frozen CatalogSnapshot and CatalogItem boundaries

This is a bounded implementation slice for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) and
[#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32). It uses the
unchanged [frozen import](CONTRACT_ADOPTION.md), the existing bounded UTF-8 reader
and bundled fixed-schema validator. Canonical source/compatibility authority,
broader acceptance records and the preview catalog's existing admission policy
remain separate.

## Resulting interface

`FrozenCatalogDecoder.decodeSnapshot(input)` returns an immutable
`FrozenCatalogSnapshot`; `decodeItem(input)` returns an immutable
`FrozenCatalogItem`. Each returns a typed `Decoded`, `InvalidInput`,
`UnsupportedVersion` or `SchemaMismatch` result. A missing, mistyped or unsupported
root version has the explicit version outcome. An invalid nested item version is
a snapshot schema mismatch. No source values or parser/provider diagnostics enter
failure results or default model formatting.

| Recorded data | Representation and preservation |
| --- | --- |
| Snapshot envelope | Exact schema version, ID, mathematical positive `BigInteger` revision, original timestamp text and ordered items |
| Item envelope | Every frozen field, all 29 canonical kind values, seven tiers, four recorded statuses and four billing classes |
| Kind-specific spec | Immutable reparseable JSON text after the complete `CatalogItem` schema, including its kind/tier/spec `allOf` branches, validates it; exact numbers and supplied unknown values remain literal |
| Provenance | Ordered source URI, checked-at text, declared verification enum and notes, retaining optional absence and duplicates |
| License | An absent license differs from an empty object; SPDX text and absent/false/true Boolean fields remain distinct |
| Optional fields | Absent/empty descriptions, tags and extensions remain distinct; arbitrary namespaced extension JSON retains null, Boolean, string, exact number, array and object values |
| Complete JSON | Reparseable values for the snapshot and each item; original byte layout, numeric spelling, artifact hash and signature are not reproduced |

Collections are defensively copied and unmodifiable. Mutable Jackson nodes never
escape the public result. Spec and extension values remain JSON text instead of
inventing a second typed specification schema or interpreting vendor extensions.

Both entry points retain the existing input budgets: 1 MiB, 32 nesting levels,
100,000 tokens, 16,384 UTF-16 string units, 256 name units and 128 number-token
characters. The parser also bounds exponent scale. Validation reparses the
normalized representation under those same limits; normalization that grows a
number beyond the limit returns `InvalidInput(LIMIT_EXCEEDED)`. Exact accepted
revision values are never narrowed to a machine integer or obtained by truncating
a fraction.

Streams stay caller-owned on every result. Callers must dispatch stream work off
the UI thread and provide any transport timeout/cancellation. Schemas are selected
by the entry point and loaded only from the bundled import; recorded URIs remain
inert. The reader introduces no network or filesystem work beyond the caller's
input stream and bundled resources.

## Structural acceptance and receiving policy

The frozen schema permits duplicate item identities and repeated provenance
records; the reader retains their order without a last-record-wins map. A caller
must separately establish identity/version joins, complete snapshot publication,
source-use rights, freshness and activation. The existing preview browser's stricter
catalog checks and data paths are unchanged.

Recorded `verified`, license flags, billing classes and literal `unknown` values
retain their supplied meaning. Decoding supplies no corroboration, allowed-use
decision, selected price, free-cost calculation, reference resolution or
compatibility result. No referenced source, model, tool or deployment is executed.

## Reproduction and meaningful coverage

Run the repository's ordinary [core checks](CORE_FOUNDATION.md):

```sh
python scripts/check_contracts.py
./gradlew --no-daemon --dependency-verification=strict :core:check :core:assemble
```

`check_contracts.py` includes the new
`scripts/check_frozen_catalog_fixtures.py` check. Its pinned synthetic mutation
corpus contains 225 named cases: 78 admitted, 12 unsupported root versions and 135
schema mismatches. Both Python and Kotlin consume the same unchanged catalog
example and explicit mutations. Every kind is exercised alone and within a
snapshot, with tier mismatch, closed spec fields and required fields; the corpus
also covers provenance/date/URI formats, optional values, nested versions, unknown
values, license fields and extension names. Source hashes bind the example and
snapshot/item/common schemas. These are the frozen Python schema outcomes, not a
new neutral conformance producer or a complete #49 fixture handoff.

Focused Kotlin tests compare every projected envelope field and exact serialized
value, all closed enums, absent/empty/false data, arbitrary-size integers and
integral exponents, rejection of fractional revisions, exact nested numeric
values, collection immutability, duplicate/order preservation, malformed UTF-8,
duplicate JSON keys, trailing data, byte limits and stream ownership/error
redaction. The complete Kotlin suite is still required; Python schema agreement
alone is not Kotlin execution evidence.

These reader tests supply no installed Android, physical-device, store, production
catalog, complete API/persistence integration or whole-issue acceptance. Actual
source, review, configured CI and post-merge receipts retain their own identities
in the delivery PR; this document does not manufacture a completed receipt.
