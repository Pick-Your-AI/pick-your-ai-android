# Typed comparison fixture profile 0.1

This is the explicit, synthetic `typed-comparison/0.1` consumer for
[Android #49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49).
Its implementation lives entirely in the Kotlin **test source set**. It receives
the separately selected Research comparison profile and its original 104 cases.
The existing [223 wire and 34 private migration observations](CONFORMANCE_FIXTURES.md)
retain their original producer, inputs, meanings and receiving history.

## Selected input and version boundary

[Research #493](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/493)
delivered the selected profile in signed commit
`8af15e63a1b71e4914008f607554aa264c3e0cc5`, tree
`0368f3873b3496ec1ee99f7e4fa409d7d40064ea`.
The complete decision is pinned in
[component-schema-compatibility.md](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/8af15e63a1b71e4914008f607554aa264c3e0cc5/research/T0-configuration/component-schema-compatibility.md),
Git blob `dc17d685225c548b30553c0aa6be909bd6a01155`.
This release selects typed comparison for a separate test profile. It supplies
no field-specific applicability, Answer lowering, evidence admission, default
evaluator switch or native production API.

The [input lock](../core/src/test/resources/conformance/typed-comparison-inputs.lock.json)
retains the exact version, source paths, Git blobs, byte lengths and SHA-256
digests. Both vendored Python files and the JSON corpus are byte-for-byte upstream
copies; Android adds no new neutral producer and changes no expected outcome.

| Input | Git blob | SHA-256 |
| --- | --- | --- |
| Original Python prototype | `bd2cb185013bc0b7c9455f7571c6d3dc62a44caf` | `5fc27536a5e97a9a76ad250a90015c4082269b54c6d56bbefd2615fc99639b82` |
| Original Python regression source | `78a54619dbf97e44628b6a64327971025b3f27fe` | `25b9268760957663c9b5c731149c3e93655d82ae72a0c59fc947186757ccf77a` |
| Original 104-case corpus | `da3fdb7427f15294ffd921018a7a197a103ded6f` | `a8a1e4cc8e80d5b45424e38b023cee46f394a8d55fd776b4ff267df2386b1554` |
| Existing frozen evaluator control | `0e01df6ada663b41019ffb7825290b47b142fa38` | `092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a` |

The frozen control is the already imported
[contracts/v1/reference/evaluate.py](../contracts/v1/reference/evaluate.py).
It is read unchanged by the original upstream regression source. Historical
Boolean/numeric behavior remains an explicit old-profile control; it does not
become the selected typed profile's expected result.

## Android test interface

[TypedComparison01](../core/src/test/kotlin/ai/pickyour/core/conformance/typedcomparison/TypedComparison01.kt)
exposes one internal entry point:

```kotlin
compare(profile, operator, leftJson, rightJson, limits)
```

The profile and operator are exact strings. Each operand is an independently
bounded original UTF-8 byte array, or Kotlin `null` for an unresolved operand.
A supplied JSON `null` is a known value and remains different from that unresolved
input. Mutable arrays are copied before decoding; results contain only a typed
outcome or fixed rejection reason. There is no decoded-object or callback entry
point that could bypass admission.

`Compared` contains `true`, `false` or `unknown`. `Rejected` contains a fixed
diagnostic for unsupported profile/operator, malformed encoding/JSON/Unicode,
duplicate decoded keys, nonfinite numbers, or the selected limits. Rejection is
never reported as an unknown comparison. Both supplied operands are admitted
before comparison, including children that a known match or empty collection
could otherwise bypass.

| Operation | Selected behavior |
| --- | --- |
| `eq`, `ne` | Typed scalar equality and its three-valued complement; arrays, objects and incompatible scalar domains remain unknown |
| `lt`, `lte`, `gt`, `gte` | Exact numeric comparison, including accepted numeric strings; nonnumeric operands remain unknown |
| `in`, `contains` | Directional membership or text containment within the selected compatible domains |
| `intersects`, `subset_of` | Full bounded array membership comparisons, with the selected any/all three-valued reduction |

Within membership, array and object elements use recursive equality; array
positions and exact object key sets remain significant.

Booleans and numbers remain different domains. JSON integers and decimals use
`BigInteger`/`BigDecimal`; finite canonical nonnegative decimal strings follow
the explicit upstream numeric-string rule. Other strings retain their text
meaning. Numeric admission, ordering and work accounting do not pass through
`Double`, `Long` or `UInt64`. A known false conjunction or true disjunction
retains the selected precedence over unrelated unknown comparisons, after
complete input admission and required work charging.

The private JSON grammar exists to preserve the selected reference's observable
admission order. It parses only the already bounded strict UTF-8 text, consumes
the complete document, retains escaped UTF-16 code units until the full Unicode
preflight, and checks object duplicates after their supplied children. JSON
number callbacks retain their earlier rejection precedence over later syntax.
This parser belongs to this test profile; the production bounded reader and all
frozen decoders keep their current implementation and dispatch.

## Explicit local bounds

The seven ceilings match the selected prototype. Callers may reduce them within
their stated nonnegative/positive ranges; they cannot enlarge them.

| Bound | Ceiling | Meaning |
| --- | ---: | --- |
| Original bytes | 65,536 per supplied operand | Includes original whitespace and escape spelling |
| Container depth | 32 | Quoted brackets do not add nesting |
| Admitted nodes | 10,000 per operand | Counts every key and value before comparison |
| String scalars | 4,096 per string/key | Unicode code points, with invalid surrogate sequences rejected |
| Number characters | 128 | Original numeric token or selected canonical decimal string |
| Absolute exponent/scale | 1,000 | Explicit exponent and resulting decimal scale are independently checked; this ceiling may be zero |
| Shared work | 200,000 | Both inputs, preflight and required comparisons share one budget |

All other configurable lower limits must be at least one. The same limits are
revalidated at entry. Work charges preserve original spelling, numeric precision,
text operations and the full necessary Cartesian array traversal; finding an
early match does not waive the remaining selected work charge. The returned
result does not expose input values or ambient locale-dependent diagnostics.

## Reference, native attendance and normal CI

The [checker](../scripts/check_typed_comparison_fixtures.py) hashes the fixed lock
and captures **all four** named inputs before compiling any original Python
source. It runs all 19 unchanged upstream methods and independently checks each
of the 104 original case outcomes. Only the original tests' input locations are
redirected to the already captured, verified bytes. The original test and
prototype content remains unchanged.

```sh
python scripts/check_typed_comparison_fixtures.py
python -m unittest discover -s scripts/tests -p 'test_typed_comparison_fixtures.py' -v
python -O -m unittest discover -s scripts/tests -p 'test_typed_comparison_fixtures.py' -v
```

The normal contract runner invokes that reference check after its existing
frozen fixture checks. All preceding checks, later catalog/dependency hooks and
failure propagation remain. Core CI runs the 18 checker controls before the
existing strict Gradle build. These controls use deliberately synthetic XML;
they cannot themselves establish that Kotlin cases executed.

The two native test classes declare **175 cases**: 104 immutable vectors, three
fixture/lock controls, 20 admission methods and 48 malformed-input cases. Each
successful vector emits one nine-field `ANDROID_TYPED_COMPARISON_CASE` object in
its own named Gradle XML case: profile, case, operator, outcome, both original
operand hashes, corpus hash, prototype hash and input-lock hash. Known JSON null
has an operand-byte hash; an unresolved operand has JSON null in that hash field.

After a successful normal Core test step, CI invokes:

```sh
mkdir -p build/conformance
python scripts/check_typed_comparison_fixtures.py \
  --reports core/build/test-results/test \
  --test-exit-code 0 > build/conformance/typed-comparison-qualified.json
```

The success-only step supplies the preceding test command's successful status.
A manual caller must pass its actual command status. The checker requires both
exact class inventories, unique complete case identities, zero failed/skipped/
retried outcomes, and each exact typed observation in its own case. It rejects
missing or foreign cases, relocated or duplicated observations, wrong JSON types,
changed pins, unexpected XML shapes, non-UTF-8 declarations and DTD/entity input.
A failed command exits nonzero; redirected output from that command is not a
passing receipt.

The existing artifact layout retains both original XML suites and the qualified
JSON. The checker authenticates those supplied bytes and named observations
only. Actual checkout identity, compiler/runtime pins, command execution, package
outputs and hosted artifacts still require the normal independent review.
A passing fixture receipt does not activate a default evaluator.

## Receiving and remaining scope

This contribution receives only the explicit typed-comparison portion of the
versioned-fixture work. The original Android #49 criteria, its #26/#27/#37
prerequisites, existing evaluator owners and website acceptance record at
`docs/delivery/android/work-packages/03.06.md` retain their authority.
Central and affected-consumer receiving must identify the exact published
profile/source and actual outcomes before any broader criterion reconciliation.

The selected Research decision and these synthetic vectors supply no source
eligibility, assumption policy, capability completeness, physical placement,
vendor compatibility, measured runtime result, live service, installed-device
or release acceptance. Existing frozen snapshots, historical fixture bytes and
default evaluator behavior remain unchanged. Any later production use requires
its own explicit interface, review, migration/version choice and actual gates.
