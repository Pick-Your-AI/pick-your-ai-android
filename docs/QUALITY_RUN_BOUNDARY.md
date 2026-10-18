# Frozen quality-run decoding

`QualityEvaluationRunDecoder` adds a dedicated immutable Kotlin view of the frozen
`QualityEvaluationRun` document. It consumes one bounded, caller-owned UTF-8 stream,
checks the version and the fixed bundled schema, and retains every defined field.
It does not open artifact URIs, run an evaluation or accept a deployment gate.

This is partial implementation for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21),
[#49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49) and
[#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32).
The existing generic validator already supported structural validation of this
document. The new boundary supplies typed values, immutable retention and a
dedicated Python/Kotlin conformance set for future consumers.

## Preserved values and limits

Build revision and sample size use `BigInteger`; observed values and thresholds
use `BigDecimal`. No machine integer narrowing, binary floating-point conversion
or truncation supplies these values. Integral decimal/exponent representations
are accepted as integers when the frozen schema permits them. Measurements are
JSON numbers, distinct from the frozen contract's nonnegative decimal-string
money format; negative measurements are permitted.

Status and comparator values use closed enums with their original wire names.
The decoded status and per-measurement `passed` flag remain reported values.
The frozen schema does not enforce comparator arithmetic, consistency between
status and measurements, or timestamp ordering. This decoder does not add those
policies or convert a structurally valid document into certified quality evidence.
Selecting a suite still creates no measured run.

Optional `notes` and measurement `unit` preserve absence separately from empty
arrays and empty strings; explicit null is rejected. Artifact arrays remain
required but may be empty. All measurement/artifact/note entries and their order
are retained, including duplicates. URI values remain inert strings with the
schema's syntax acceptance; decoding grants no authority to fetch or disclose
them. Identifiers retain the frozen regular-expression behavior, including its
terminal-newline edge case. This is not path, navigation or object-authorization
validation.

The retained `json` is an immutable, reparseable JSON representation of the values.
It is not the original byte layout or numeric spelling and must not replace an
original artifact hash or signature. The normalized representation is checked
against the same bounded-reader limits before a decoded value is returned.
Normalization can lengthen a permitted numeric token; a resulting representation
limit remains `InvalidInput(LIMIT_EXCEEDED)`, not a frozen-schema failure.
Collections cannot be mutated through casts;
no parsed mutable tree escapes. Default diagnostic `toString()` values omit
document contents. Explicitly reading `json` or individual fields still requires
the caller's ordinary privacy and disclosure review.

The existing [bounded reader](../core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt)
retains its local preview budgets, duplicate-key/UTF-8 checks and exact-number
limits. These are consumer limits, not changes to the frozen schema. Streams stay
caller-owned on success and failure; perform work off the UI thread and provide
transport cancellation/timeouts. This decoder has no network, persistence or UI
adapter and does not bind a run to an independently supplied current build,
selected suite, evaluator or dataset.

| Decode outcome | Meaning |
| --- | --- |
| `Decoded` | The supported document's structural fields were copied without a new quality verdict |
| `InvalidInput` | The existing bounded parser rejected bytes, syntax, root, trailing data, input/normalized-representation limits or stream IO; only its sanitized enum escapes |
| `UnsupportedVersion` | The version is absent, nontextual or different from `1.0.0`; no migration is attempted |
| `SchemaMismatch` | Frozen fields, types, enums, number constraints or asserted formats fail; no rejected values or validator messages escape |

## Pinned conformance and reproduction

The unchanged [import manifest](../contracts/v1/IMPORT_MANIFEST.json) and hash-first
runner remain authoritative. The dedicated fixtures pin:

| Input | SHA-256 |
| --- | --- |
| `quality-evaluation-run.schema.json` | `ed6b3f60805428e3788ce27fa224fb3d5008998f0637f24723bf65eea546a959` |
| `quality-evaluation-run.json` | `bc0abc0b33e2b6bf77fc4609ac88a73981068eac6fabf604c255f58fec954a6d` |

[The shared mutation cases](../core/src/test/resources/contracts/quality-run-cases.json)
derive from that synthetic example. The Kotlin decoder and
[Python checker](../scripts/check_quality_run_fixtures.py) consume the same explicit
expected outcomes. Positive cases round-trip through retained JSON; malformed and
future-version cases retain their distinct rejection outcomes. Separate Kotlin
tests cover exact high-precision numbers, caller-owned streams, parser limits,
immutable lists and all typed fields. This finite set is not proof of agreement
for every schema input or of every other client's numeric/URI subset.

The original Python lock omitted the optional URI/date-time checkers. An actual
negative parity case exposed that absence: `FormatChecker` accepted an invalid
timestamp without checking its format. The
[jsonschema 4.25.1 documentation](https://python-jsonschema.readthedocs.io/en/v4.25.1/validate/#validating-formats)
describes this behavior. The reference lock now pins `rfc3339-validator` 0.1.4,
`rfc3986-validator` 0.1.1 and the former's `six` 1.17.0 dependency. These supply the
two formats used by the frozen schemas; no JVM/application dependency changes.
The new checker requires both formats to be registered and fails when either is
missing, so absent optional dependencies cannot become a false parity receipt.

With the toolchain in [CORE_FOUNDATION.md](CORE_FOUNDATION.md):

```sh
python -m pip install -r requirements-contracts.txt
python scripts/check_contracts.py
./gradlew --no-daemon :core:check :core:assemble
```

The first command installs the pinned reference environment. The hash-first runner
then checks all frozen files, executes the unchanged reference suite, checks the
existing reference-report cases and checks the new quality-run cases. Core CI
executes the dedicated Kotlin cases with the other core tests.

## Remaining acceptance

| Issue | This contribution | Still separate |
| --- | --- | --- |
| #21 | Frozen kind/version/type/enum/format decoding and immutable typed numeric values | Actual network/storage consumers, all other supported models and affected-owner acceptance |
| #49 | Shared Python/Kotlin expected outcomes with exact source pins and retained JSON round trips | Full cross-language matrix, explicit successor migration/rollback and consumer handoffs |
| #32 | Dedicated malformed/version/optional/numeric/immutability/stream regression cases | Full domain suite, integrated Android/device behavior and receiving quality acceptance |

Authenticity, restricted-artifact authorization, build/suite identity binding,
freshness, current-result coordination, producer evidence and quality policy
remain obligations of the eventual receiving service/client. JVM and synthetic
schema results do not prove measured quality, an installed Android journey,
physical-device behavior, store acceptance or release. Canonical acceptance-record
paths remain in `pickyour.ai`; this local document does not relocate them or close
the linked work packages.
