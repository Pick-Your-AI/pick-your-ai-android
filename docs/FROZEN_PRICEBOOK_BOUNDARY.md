# Frozen Pricebook structural boundary

This is a bounded contribution to Android [#21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21)
and [#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32).
`FrozenPricebookDecoder` retains a complete immutable recorded Pricebook under the
unchanged frozen schema. The existing [recorded-price reader](CATALOG_PRICE_CONTEXT.md)
and [candidate screening](RATE_SCREENING.md) keep their stronger admission and
screening rules. No caller is switched to the new reader by this contribution.

## Exact unchanged inputs

The [frozen import](CONTRACT_ADOPTION.md) remains at upstream
`b1c4eaa4037f2a1e02103849064a947e758a2b40`. The shared panel binds these exact bytes:

| Frozen input | SHA-256 |
| --- | --- |
| [Pricebook schema](../contracts/v1/schemas/pricebook.schema.json) | `169e1a48847bee8b6af4b266a55bcebe317b5655dd6abb7718e06331b1df6288` |
| [Common definitions](../contracts/v1/schemas/common.schema.json) | `0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c` |
| [Synthetic Pricebook example](../contracts/v1/examples/pricebook.json) | `716a848822fc2b182d226c597f7e50f4b00a0fe01ba5c03879e858ca6f0c9b46` |

No frozen artifact, dependency, validation keyword or consumer budget is changed.
Every new fixture is explicitly synthetic. The existing hash-first runner still
verifies all 43 original files and both manifests before the unmodified reference
suite and all previously accepted shared checkers.

## Admission and recorded values

The existing bounded UTF-8 reader checks syntax, duplicate keys and local
byte/depth/token/string/name/number limits. Missing or non-string `schema_version`,
or any value other than `1.0.0`, returns `UnsupportedVersion`. The fixed bundled
Pricebook schema validates the whole normalized document before typed extraction.
Schema failures return `SchemaMismatch`; I/O, syntax and budget failures preserve
the reader's sanitized `InvalidInput` reason. Original and normalized input both
pass their existing limits. No input selects a schema or causes remote resolution.
Streams remain caller-owned; background execution and transport timeout/cancellation
remain the caller's responsibility.

All five book fields, all eleven rate fields and all four provenance fields are
retained. Revision uses an exact positive `BigInteger`, including admitted integral
decimal/exponent JSON spellings without `Long` or `Double` narrowing. The decoder
introduces no expanded-revision or machine-width restriction beyond the existing
parser and fixed validator. The existing local snapshot reader's supported revision
profile remains a separate, unchanged boundary.

Amounts and currencies are inert strings in `FrozenPricebookMoney`. They retain
trailing zeros and the exact content admitted by the frozen grammar, including
one terminal LF allowed by its anchored patterns. The [pattern boundary](FROZEN_PATTERN_PARITY.md)
is unchanged. No `ContractMoney` conversion, trimming, rounding or external ISO
currency lookup occurs. Catalog item version is the schema's nonempty string,
not a newly imposed semantic-version grammar. IDs, timestamps and URI text retain
their original string content. The `json` property preserves reparseable JSON
values, not original whitespace, numeric spelling, a source hash or signature.

Rates retain their input order and may contain duplicate IDs or identical whole
records. The required rates array is nonempty. Conditions retain order, empty
strings and repetitions. Returned rate and condition lists are copied and
unmodifiable; mutable parsed JSON never escapes. Default formatting of every
model and result omits the recorded values.

## Optional fields and independent constraints

The three optional rate fields are `region`, `valid_until` and `conditions`.
The two optional provenance fields are `source_uri` and `notes`.

| Field | Structural distinction preserved |
| --- | --- |
| `region` | Absent, empty, whitespace and arbitrary recorded text remain distinct; no global/default region is supplied. |
| `conditions` | Absent differs from an explicitly empty list; ordered empty/repeated strings remain recorded conditions. |
| `notes` | Absent differs from empty or whitespace text. |
| `source_uri` | Absence remains absent; supplied values must satisfy the existing URI format check. Empty/relative/malformed text and explicit null are not substituted with defaults. Admitted URI schemes remain inert text. |
| `valid_until` | Absence remains absent; supplied values must satisfy the existing date-time check. Empty or null values are not treated as absence. |

All object layers remain closed and no declared field is nullable. There is no
schema relationship between capture, check, start and end timestamps. Equal or
reversed declared intervals can therefore remain structurally valid input, even
though the existing screening snapshot reader rejects them. Mixed rate currencies
and unrelated item/version references likewise remain recorded declarations.

The thirteen meters, three price types and four verification statuses stay exact
closed enums. The existing `FrozenVerificationStatus` is reused only as a recorded
enum; the new model performs no source check or independent assessment. A raw
`verified` value, a URI, a `list` price or a zero amount supplies no accepted rate,
source-use right, freshness result or free/included treatment.

## Validation and remaining acceptance

[The shared panel](../core/src/test/resources/contracts/pricebook-cases.json) contains
188 explicit cases: 71 decoded, six unsupported-version and 111 schema-mismatch
expectations. It covers required and optional fields, closed shapes/enums, exact
amount and identifier grammar, malformed URI/timestamp/type inputs, duplicated
records, missing/empty distinctions and the schema's absent chronology/currency
couplings. Python checks the unchanged frozen schema; Kotlin applies the same
mutations and verifies every typed field and reparseable round trips on positives.

Eight additional JVM methods cover exact enum inventories, raw integral number
spellings, unchanged screening-snapshot rejections, the permanent evidence gap for
a recorded verified/list/zero-price candidate, copied immutable lists, safe default
formatting, parser budgets and caller-owned streams. Execution results and independent
review belong to the actual source/receiving/default PR receipts; no native,
installed-device, service, price or release acceptance is pre-recorded here.

Use the commands in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). `RecordedPricebook`,
`RateScreen`, their byte-hash semantics, current catalog UI and existing data stores
are unchanged. No price calculation, eligibility interpretation, catalog lookup,
URI fetch, clock, evidence approval, storage, UI mutation or successor adoption is
introduced. Broad #21/#32 and the existing pricing work packages keep their remaining
criteria, affected-consumer reviews and canonical acceptance-record paths. No issue
checkbox, Project status or ledger entry changes with this structural model.
