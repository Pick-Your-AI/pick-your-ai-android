# Frozen CostEstimate structural boundary

This is a bounded contribution to Android [#21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21)
and [#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32).
`FrozenCostEstimateDecoder` reads an immutable recorded CostEstimate without requiring
a Build or interpreting its prices. The existing [CostEstimate inspection adapter](COST_ESTIMATE_INSPECTION.md)
continues to perform its separate Build binding and declared-amount checks unchanged.
Successful structural decoding supplies no accepted quote, pricing evidence or budget pass.

## Exact unchanged inputs

The [frozen import](CONTRACT_ADOPTION.md) remains at upstream
`b1c4eaa4037f2a1e02103849064a947e758a2b40`. The shared cases bind these exact bytes:

| Frozen input | SHA-256 |
| --- | --- |
| [CostEstimate schema](../contracts/v1/schemas/cost-estimate.schema.json) | `ef7cf8c0492022c1a8a1f971e49eb86dfad5b66ce6a4b2cf72a965fc9ba81259` |
| [Common definitions](../contracts/v1/schemas/common.schema.json) | `0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c` |
| [Synthetic CostEstimate example](../contracts/v1/examples/cost-estimate.json) | `4b7c3889881d3c424ed98cc47b146b25b05df21c5459c9558717f21b6198b7e4` |

No frozen file, dependency, validation keyword or consumer budget is changed.
Every fixture in this contribution is explicitly synthetic. Existing import
integrity checks still verify all 43 original files and both pinned manifests
before the unmodified reference suite and every accepted shared checker.

## Recorded values and structural admission

The existing bounded UTF-8 reader first checks syntax, duplicate keys and local
byte/depth/token/string/name/number limits. Missing or non-string `schema_version`,
or any value other than `1.0.0`, produces `UnsupportedVersion`. The fixed bundled
CostEstimate schema then validates the whole normalized document before typed
extraction. Schema failures produce `SchemaMismatch`; syntax, I/O and budget
failures retain the reader's sanitized `InvalidInput` reason. Original and normalized
input both pass their existing limits. No input may choose a schema or cause a
remote reference lookup. Caller streams remain open on every outcome; callers
remain responsible for background execution and transport timeout/cancellation.

All fifteen top-level fields and all ten fields in each line are retained. The
model exposes exact positive `BigInteger` build revisions and period days, all
thirteen meter members, four recorded methods, three confidence members and three
coverage members. Integral decimal/exponent JSON spellings admitted by the fixed
validator preserve their mathematical integer values without `Long` or `Double`
narrowing. There is no new machine-width or expanded-revision limit beyond the
existing parser/validator boundary.

Quantities, amount strings, currency strings and timestamps keep their exact
string content. `FrozenCostMoney` is an inert pair of recorded strings; it does
not call `ContractMoney` or convert amounts. In particular, the frozen reference
regex admits one terminal LF in its anchored decimal, currency and identifier
patterns. The [existing pattern boundary](FROZEN_PATTERN_PARITY.md) preserves that
schema behavior. This reader retains those strings without trimming them; the
separate `ContractDecimal`/`ContractMoney` arithmetic profile remains stricter.
Neither this reader nor that distinction changes the frozen grammar.

Arrays preserve order and copy their contents into unmodifiable lists. Empty
line, warning, assumption and unpriced-selection arrays are valid. Repeated line
IDs and references are preserved; only `unpriced_selection_ids` has the schema's
uniqueness constraint. Warning and assumption strings may be empty or repeated.
Default object/result formatting omits the recorded document values. The `json`
property preserves reparseable JSON values and string contents, not original
whitespace, numeric spelling, a source hash or an authenticated signature.

## Structural validity and cost reception remain separate

The frozen schema does not require any of these relationships. This decoder
therefore retains the recorded input when they are absent or inconsistent:

- Estimate identity/revision against a particular Build, or a pricebook/rate lookup.
- Unique line IDs, membership in active selections or complete per-selection coverage.
- Equality between top-level and nested money currencies, or an external currency registry.
- Quantity multiplied by unit price equaling the recorded amount.
- Line amounts summing to subtotal, or subtotal plus tax equaling total.
- Declared `complete` coverage agreeing with the recorded unpriced-selection list.

The unchanged `CostEstimateAdapter` keeps its stricter rejection outcomes where
already implemented. A dedicated regression passes the same schema-valid inputs
through both APIs and verifies that structural decoding does not weaken those
existing checks. Recorded `measured`, `high` or `complete` values are claims in
the document; the new model performs no measurement, evidence review, completeness
assessment or pricing decision. Zero remains a recorded amount and establishes
no free or included treatment. No arithmetic, rate eligibility, freshness, rounding,
FX, tax, current-result attachment, storage, networking or UI behavior is added.

## Validation and remaining acceptance

[The shared fixture panel](../core/src/test/resources/contracts/cost-estimate-cases.json)
has 224 explicit cases: 72 decoded, six unsupported-version and 146 schema-mismatch
expectations. It covers each required field, closed enum membership, nested money
fields, exact decimal grammar, array/type/uniqueness constraints, identifier and
timestamp boundaries, and the schema's intentionally absent semantic couplings.
Python checks these against the unchanged frozen schema. Kotlin executes the same
mutations and checks every typed field plus reparseable round trips on each positive.

Seven additional JVM methods check exact enum inventories, raw integral number
spellings and wide values, separation from the unchanged semantic adapter, copied
immutable lists, sanitized default formatting, original/normalized parser limits,
and caller-owned streams on short reads and I/O failures. These are structural
and local regression checks. Their actual execution results, independent review,
receiving composition and signed/default delivery belong to the exact PR receipts.
They are not pre-recorded here as completed native, device or service acceptance.

Use the commands in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). Broad #21 still needs
its remaining supported models, actual API/persistence/import boundaries and
affected-consumer receiving acceptance. Broad #32 still needs its accepted
cross-client/domain/migration coverage and prerequisites. This contribution
changes no criterion, checkbox, Project field, ledger entry or acceptance-record
location, and adopts no unreleased successor contract.
