# Frozen Event decoding

`FrozenEventDecoder` copies every field of one frozen v1 Event document into
immutable Kotlin values. It accepts one bounded UTF-8 stream, checks the explicit
version and the fixed bundled schema, and returns a typed result. The existing
generic validator already supported this schema; this boundary gives future
consumers a complete typed representation without exposing a mutable JSON tree.

This is partial implementation for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) and
[#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32). It does not
close those work packages or establish the unaccepted successor inputs required
by [#49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49).

## Recorded fields

| Field group | Retention and validation |
| --- | --- |
| Version and identifiers | Only `schema_version: "1.0.0"` is supported. Event/subject/payload identifiers use their unchanged frozen patterns; selection identifiers retain their distinct pattern. |
| Event and subject types | Closed enums retain all 13 event types and all five subject types in the frozen schema. Neither type determines the other. |
| Subject revision | `BigInteger` retains the exact positive mathematical integer, including integral decimal/exponent spellings and values beyond machine width. No `Long` or binary floating-point conversion supplies this value. |
| Occurrence time | The asserted `date-time` format is checked, while the original string and offset spelling are retained. This does not authenticate the recorded occurrence or establish ordering. |
| Actor, correlation and idempotency references | Original strings are retained. The frozen schema requires nonempty strings, without ID-pattern validation; whitespace is not trimmed or given operational meaning. |
| Optional subject version and causation ID | Absence and an explicitly supplied empty string remain distinct. No version grammar is added to `subject_version`. |
| Payload references and note | Each of `selection_id`, `catalog_item_id`, `evaluation_report_id`, `cost_estimate_id` and `note` retains its independent presence. An empty note is permitted. No reference is resolved. |
| Affected kinds | A nullable immutable list preserves absence separately from `[]`, and retains the supplied order of the 29 possible closed enum values. Duplicate or unknown kinds are rejected by the frozen schema. |

The envelope and payload are closed objects; unknown fields and explicit nulls
are rejected. The required payload may be `{}`. There is no schema-defined
event/subject coupling or requirement for a particular payload field for an event
type. The decoder adds neither constraint. Recorded event combinations that need
later semantic review remain visible rather than being silently rewritten.

Frozen identifier patterns retain their existing terminal-LF behavior. These
strings are not validated file paths, navigation targets or object authorization.
The schema title's description of a secret-free event does not prove that allowed
free-text fields contain no private data. Default model/result `toString()` values
omit document contents. Explicit field or `json` access still requires the
receiving caller's ordinary privacy and disclosure review.

## Input, immutability and result boundary

The decoder reuses the existing
[bounded reader](../core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt)
and [fixed validator](../core/src/main/kotlin/ai/pickyour/core/contracts/FrozenSchemaValidator.kt).
It changes no parser budget, dependency, schema byte or contract semantics. The
reader rejects duplicate keys, invalid UTF-8, malformed JSON, unsupported roots,
trailing content and its documented resource limits before a model is returned.

The retained `json` is an immutable, reparseable representation of the JSON values.
It preserves optional-field presence and decimal values, but it is not the
original byte layout, numeric spelling, content hash or signature. The normalized
representation is checked against the same input budgets. If normalization grows
a numeric token beyond those budgets, the result remains a representation-limit
rejection rather than a schema failure. No mutable tree or mutable backing list
escapes the decoder, including through a collection cast.

| Result | Meaning |
| --- | --- |
| `Decoded` | Supported frozen fields were validated and copied; no event was accepted for execution. |
| `InvalidInput` | The existing reader rejected syntax, bytes, root, trailing content, original/normalized resource limits or provider IO. Only its sanitized reason enum escapes. |
| `UnsupportedVersion` | The version is absent, nontextual or different from `1.0.0`; no migration is attempted. |
| `SchemaMismatch` | Frozen required fields, types, enums, uniqueness, exact numeric bounds or asserted formats fail; input fragments and validator diagnostics are withheld. |

The caller owns the stream on every outcome. Perform stream work off the UI
thread and supply transport timeouts and cancellation. Decoding performs no
network, storage or UI action. It neither emits telemetry nor interprets actor
references, correlation, causation or idempotency keys. Decoding the same event
twice returns two independent immutable representations; no deduplication,
ordering, replay policy or mutation acknowledgement is implied.

## Inspectable conformance evidence

The original [import manifest](../contracts/v1/IMPORT_MANIFEST.json), complete
43-file hash verification and canonical frozen package stay unchanged. The
[shared synthetic cases](../core/src/test/resources/contracts/event-cases.json)
pin these exact inputs:

| Input | SHA-256 |
| --- | --- |
| `event.schema.json` | `e169ad8d2f7417580ef63a5098f8ec54dd514af3ce609c59bdb7780b89d6039a` |
| `event.json` | `ed0a9e96da773bc74bceb774f576971275f6da525fa9b425c3b8d6e5f7406068` |

The Kotlin test factory and
[Python checker](../scripts/check_event_fixtures.py) consume the same 75 explicit
expected outcomes: 28 valid structural documents, four unsupported versions and
43 schema rejections. The cases include each event/subject enum member, every
required field, optional null/empty/absent distinctions, closed payloads, kind
uniqueness, mismatched event/subject combinations, exact wide integers and format
failures. The Python checker requires its pinned `date-time` checker to be
registered, so a missing optional format dependency cannot appear as parity.

The dedicated Kotlin methods additionally verify every typed field, exact enum
inventory equality with the frozen schema, order and immutability, large and
integral-exponent revisions, retained-value round trips, repeated decoding,
malformed bytes, normalization limits, caller-owned streams and sanitized
provider failures. This is a finite structural conformance set, not proof of
universal cross-client equivalence or of accepted domain behavior.

Use the toolchain in [CORE_FOUNDATION.md](CORE_FOUNDATION.md):

```sh
python -m pip install -r requirements-contracts.txt
python scripts/check_contracts.py
./gradlew --no-daemon :core:check :core:assemble
```

The hash-first Python runner invokes the new Event checker after the existing
unchanged reference, report, quality-run and pattern checks. Normal core CI runs
the Kotlin methods and retains JUnit results with the exact source. Actual run
results and independent review belong in the PR receipt; the test definitions in
this document are not themselves execution evidence.

## Remaining handoff

Event authenticity, actor/object authorization, semantic type/subject binding,
replay protection, idempotency, sequencing, transaction durability, synchronization
and telemetry admission remain responsibilities of eventual consumers. This
change implements no such consumer and gives no event permission to mutate a
draft, refresh a catalog, publish evidence, execute a deployment or disclose data.

The existing #312 ResourceEstimate, #341 dependency-graph and UI/storage work
retain their own owners and evidence. This source supplies no installed Android,
physical-device, measured domain, service or release result. Canonical acceptance
record paths stay where their governing decisions place them; this local
implementation document does not relocate a receiving record or close an issue.
