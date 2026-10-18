# Frozen resource-estimate decoding

`ResourceEstimateDecoder` gives Android's JVM core an immutable Kotlin projection
of the existing frozen `ResourceEstimate` document. It consumes a bounded UTF-8
stream, discriminates version `1.0.0`, validates the fixed bundled schema, and
retains every defined field. This is a wire reader for future consumers; it does
not estimate resources, attach evidence to a build, or produce a memory-fit result.

This contributes to the supported-model work in
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) and the
regression/fixture work in [#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32)
and [#49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49).
The generic validator already supported this schema. No frozen file, wire field,
endpoint, dependency, confidence policy or acceptance-record placement changes.

## Values and receiving obligations

Build revision uses `BigInteger`, including integral decimal/exponent JSON-number
representations permitted by the frozen schema. Fractional values cannot be
truncated through a machine integer. The document's declared build ID, revision
and generation time are retained without asserting that they match the current
build or are fresh.

Each entry retains the metric, unit, declared confidence, estimator ID/version,
optional source URI, assumptions and selection IDs. Closed Kotlin enums preserve
the five frozen metric names, four unit names and four confidence labels. They
have no implicit confidence ordering or approval behavior. A `verified` label is
still the producer's recorded value, not an independently verified measurement.
The schema does not constrain metric/unit pairings, check estimator identities,
or require selection IDs to exist in an independently supplied build. The reader
does not silently add those cross-document policies.

Estimate `value` remains the exact decimal **string**. Magnitude and trailing
zeroes never pass through binary floating point. The frozen regular expression
also accepts one terminal LF; that string is preserved rather than trimmed or
converted into an arithmetic result. A future arithmetic consumer must apply its
explicit numeric acceptance boundary and retain controlled failure/unknown
behavior for unsupported spellings. This reader does not replace the existing
strict `ContractDecimal` parser or broaden its arithmetic input language.

`sourceUri == null` means the optional property was absent; an explicit JSON null
is rejected. Accepted URI values remain inert strings, including non-HTTPS
schemes permitted by the frozen schema. They are not navigation, fetch,
authorization or sharing instructions. Estimate ordering and duplicate entries
are preserved. Assumptions may be empty and may contain duplicate strings;
selection IDs must be nonempty and unique as the unchanged schema requires.
All retained collections, including nested lists, reject mutation through casts.
No mutable parsed tree escapes.

The immutable `json` value retains the document's JSON values and exact decimal
strings. It does not preserve original byte layout or JSON-number spelling and
must not replace an original artifact hash or signature. Normalization can
lengthen an integer token; the normalized representation must fit the same
bounded-reader limits before a decoded result is returned. Default `toString()`
values omit document contents. Explicit field or JSON access still requires the
caller's normal privacy and disclosure decisions.

The existing bounded reader retains its byte/depth/token/string/number budgets,
duplicate-key and malformed-UTF-8 checks. Streams remain caller-owned on every
outcome. A provider failure returns only a sanitized reason. Callers must perform
stream work off the UI thread and supply transport cancellation/timeouts.

| Outcome | Meaning |
| --- | --- |
| `Decoded` | The supported document's structural fields were retained without a new capacity verdict |
| `InvalidInput` | Existing byte/syntax/root/trailing-data/representation budgets or stream IO failed; only the sanitized parser reason escapes |
| `UnsupportedVersion` | The version was absent, nontextual or different from `1.0.0`; no migration was attempted |
| `SchemaMismatch` | Frozen field/type/enum/number/format requirements failed; input values and schema-library diagnostics do not escape |

## Shared fixtures and reproduction

The shared Python/Kotlin mutation set pins these unchanged synthetic inputs:

| Input | SHA-256 |
| --- | --- |
| `resource-estimate.schema.json` | `39ab5d061f9021ce14e1f987717441cff04d1a40f76db5582d343d599a5d876c` |
| `resource-estimate.json` | `41469a377c0b428e779c269eb052fa8f455b4768e2add3c137b5e465a79a362f` |

[The shared cases](../core/src/test/resources/contracts/resource-estimate-cases.json)
exercise all frozen enum members, required and extra fields, unsupported versions,
decimal spelling and precision, timestamps, URI syntax and optional absence,
selection uniqueness, and permitted structural contradictions. Positive cases
round-trip through retained JSON. The
[Python checker](../scripts/check_resource_estimate_fixtures.py) uses the unchanged
frozen schema and requires the pinned date-time and URI format checkers to be
registered. The hash-first contract runner includes this checker after validating
the complete frozen import.

[Dedicated Kotlin tests](../core/src/test/kotlin/ai/pickyour/core/contracts/ResourceEstimateTest.kt)
also cover every typed field, exact revisions beyond machine ranges, fractional
rejection, normalized representation growth, nested-list immutability, bounded
input rejection, caller-owned streams and sanitized provider errors. This finite
conformance set is not proof of every input or of other clients' supported subsets.

With the pinned toolchain from [CORE_FOUNDATION.md](CORE_FOUNDATION.md):

```sh
python -m pip install -r requirements-contracts.txt
python scripts/check_contracts.py
./gradlew --no-daemon --dependency-verification=strict :core:check :core:assemble
```

## Remaining capacity integration

[Android #102](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/102)
still requires accepted service/evidence output and independent receiving review.
The frozen document has no dedicated model-artifact, runtime, context or device
topology fields beyond its selection references and free-text assumptions. A
successful wire decode therefore supplies no such binding by itself. It does not
establish calibration, capacity ranges, uncertainty policy, freshness, authenticity,
new-evidence observation or current-result invalidation.

The eventual receiver must preserve unknown for absent, mismatched, stale or
insufficient-confidence evidence, and consume the canonical service and research
owners' accepted applicability contract. Adding new fields or meanings goes
through [research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40).
No installed Android, physical-device, production-estimator, release or whole-issue
completion follows from these synthetic wire tests. The linked work packages'
canonical acceptance records remain in `pickyour.ai`; this document does not move
or close them.
