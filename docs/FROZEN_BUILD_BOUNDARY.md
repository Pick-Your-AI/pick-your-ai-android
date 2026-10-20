# Frozen Build snapshot receiving boundary

This is the immutable structural Build reader for partial Android [#21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21)
and [#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32).
It consumes the unchanged [frozen import](CONTRACT_ADOPTION.md), using
[the existing bounded parser and schema validator](CORE_FOUNDATION.md).
It does not replace the separately reviewed
[local Draft-to-Build projection](DRAFT_SNAPSHOT_PROJECTION.md), create a local
identity, persist a snapshot or implement an import/export format.

## Preserved fields

[FrozenBuildSnapshotDecoder](../core/src/main/kotlin/ai/pickyour/core/contracts/FrozenBuildSnapshot.kt)
accepts caller-owned UTF-8 input streams and returns one of `Decoded`,
`InvalidInput`, `UnsupportedVersion` or `SchemaMismatch`. Parsing, including the
existing byte/depth/token/string/number limits and duplicate-key checks, precedes
fixed-schema validation. Input cannot select another schema or trigger a fetch.
Unknown fields, kinds, enums and versions receive sanitized rejections.

| Frozen fields | Immutable receiving representation |
| --- | --- |
| Schema, build ID, name, owner, lifecycle and timestamps | Exact strings plus the closed lifecycle enum; timestamp spelling and recorded ordering are retained |
| Build and dependency-graph revisions | Arbitrary-precision integers; no conversion through a machine integer or binary float |
| Objectives | Exact region/currency/classification and optional decimal string, request/token counts, concurrency and latency; absence is distinct from zero |
| Artifact locks | Every declared ID, ruleset version and graph revision; optional pricebook ID retains absence |
| Selections | Ordered immutable list with exact IDs, kind, opaque item version, role, enabled state, kind-specific settings, optional notes and extensions |
| Relationships | Ordered immutable list of exact IDs/endpoints, closed relation enum and optional required flag |
| Policy | Optional object with independent optional booleans and confidence enum; absent policy, empty policy and explicit false remain distinct |
| Build/selection extensions and settings | Complete defensive JSON snapshots retaining nesting, arrays, strings, numbers, booleans and nulls admitted by the frozen schema |

Integral decimal/exponent number spellings accepted by the frozen schema become
exact `BigInteger` values. Fractional, negative or wrongly typed revisions reject.
Optional count bounds remain those of the frozen schema. Budget strings retain
trailing zeroes and the frozen pattern's admitted final LF; this reader does not
coerce them to `ContractDecimal`, perform money arithmetic or turn absence into
zero. A later typed-money consumer retains its own supported lexical boundary.

The `json` property preserves JSON values after bounded parsing, rather than the
original bytes, whitespace, numeric spelling, digest or signature. Hashes and
signatures must remain bound to separately retained original input bytes.
Nested object accessors return defensive copies; collection casts cannot alter
the retained selections or relationships. Diagnostic `toString()` values contain
class/outcome names, without private field contents.

## Structural acceptance and domain authority

The frozen Build schema does not enforce selection-ID uniqueness, kind/role
ambiguity, relationship-endpoint existence, chronological timestamp order or
cross-artifact pin agreement. The decoder preserves those recorded values and
array order, including structurally admitted contradictions, for the domain
receiver to inspect. It supplies no compatibility, capacity, price, rights,
publication, ownership or lifecycle verdict. Existing local domain invariants
remain in the canonical draft/projection implementations; this structural reader
does not weaken or replace them.

URI and secret-reference strings are inert recorded content. The reader opens no
reference, resolves no credential, executes no instruction or policy, and starts
no deployment even when the recorded lifecycle says `deployed`. Its full private
JSON and model properties are not a sanitized public export. Disclosure profiles,
user confirmation, Android document transport, new identity creation and durable
import remain Android #30's separate boundaries.

The caller supplies transport cancellation and timeout and performs stream work
off the UI thread. The reader leaves the stream open on success and rejection;
provider errors return only the finite `IO_FAILURE` reason.

## Regression and receiving evidence

The shared [215-case fixture](../core/src/test/resources/contracts/build-cases.json)
preserves all 183 original cases and adds 32 exact-number boundary cases. It pins
the exact canonical Build example and Build/Selection/Common schema bytes.
The [Python checker](../scripts/check_build_fixtures.py) uses the existing pinned
format-enabled frozen reference validator, while the
[Kotlin suite](../core/src/test/kotlin/ai/pickyour/core/contracts/FrozenBuildSnapshotTest.kt)
executes the actual reader. The matrix covers every recorded enum, required field,
unknown-field boundary, wide count, optional absence, invalid type, secret/URI
shape, namespaced extension and deliberate structural-only case.

Nine additional Kotlin methods inspect the canonical all-29-kind fixture, exact
field mapping and enum inventories, absent/empty/false/zero values, wide integral
spellings, preserved private references and decimal text, defensive nested copies,
structural contradictions, bounded malformed input and caller stream ownership.
The focused Kotlin suite therefore declares 224 cases: 215 dynamic cases and
nine methods. Its actual attendance and results must be established by the
source-bound execution receipt; a dynamic factory is not one executed case.

The Python checker reads decimal and exponent tokens exactly. Its private
Draft2020-12 integer check retains integral spellings and Boolean exclusion,
including numeric fields reached through Selection references. It does not round
a fraction into an integer, underflow a negative value to zero, or accept the
non-JSON constants NaN and Infinity. Frozen schema bytes and constraints remain
unchanged. The [nine-method CLI suite](../tests/test_build_fixture_precision.py)
checks these boundaries in copied inputs, verifies their byte and file-identity
preservation, and separately exercises the full shared corpus. Python results
supply no Kotlin or installed-device execution evidence.

Run the documented pinned Python environment with `scripts/check_contracts.py`,
then `./gradlew --no-daemon :core:check :core:assemble`. The ordinary contract gate
includes the new shared-fixture checker; all existing checkers remain required.
Actual source, core/preview, signing and post-merge execution receipts belong to
the reviewed PR and their exact checkout identities. Local JVM checks establish
no installed Android, physical-device, store, release or operational acceptance.

This is a bounded model/validation contribution. The broader #21 API/persistence/
import consumers, affected-client receiving review, remaining models and canonical
`docs/delivery/android/work-packages/03.01.md` acceptance record remain separate.
That record retains its existing website placement; this note does not relocate
it, close #21/#32/#49, or adopt successor contract semantics.
