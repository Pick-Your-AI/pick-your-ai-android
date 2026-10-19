# Standalone frozen Selection decoding

`FrozenSelectionDecoder` supplies the standalone Selection model boundary for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21).
It reads one bounded UTF-8 object and returns the same immutable
`FrozenBuildSelection` value used by the existing
[Build decoder](FROZEN_BUILD_BOUNDARY.md). A receiver can inspect a Selection
without fabricating a containing Build or applying the private draft store's
additional admission rules.

## Version and schema authority

The unchanged [Selection schema](../contracts/v1/schemas/selection.schema.json)
has no `schema_version` member. The decoder therefore requires the enclosing
contract version as an explicit argument:

```kotlin
val result = FrozenSelectionDecoder().decode(input, contractVersion = "1.0.0")
```

The caller must obtain this version from its accepted enclosing contract or
transport context. It must not guess from field names, add a version property to
the Selection, or copy an unsupported enclosing version into a supported default.
The example selects the pinned frozen contract deliberately; it is not a future
network envelope or version negotiation protocol.

Full bounded parsing, including EOF, happens before version admission. A parsed
object with any other caller-supplied version returns `UnsupportedVersion`.
Malformed syntax/UTF-8, duplicate keys, trailing input, exhausted parser budgets
and provider errors retain their sanitized `InvalidInput` result, including when
the supplied version is unsupported. With version `1.0.0`, additional fields such
as `schema_version`, unknown kinds/enums, missing required fields and invalid
kind-specific settings return `SchemaMismatch`.

The existing fixed `FrozenDocument.SELECTION` validator supplies the schema;
payload fields cannot select a schema, dialect or remote reference. Frozen files,
manifests, schema generators and private draft consumers are unchanged. Approved
successor work remains coordinated through
[Research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40),
including the enclosing version, schema, generator, fixtures and affected clients.

## Retained values and consumer limits

All 29 frozen kinds retain their ID, kind, catalog item/version, role, enabled
state and complete admitted settings. Notes and namespaced extensions preserve
absence separately from empty values, false, zero and null extension content.
Money remains the exact recorded decimal string; numeric JSON values use the
existing arbitrary-precision bounded reader. Serialization preserves values,
rather than original byte layout or every numeric spelling. Its normalized
representation must reopen within the same parser limits before admission.

Settings, extensions and `toJson()` return independent defensive copies. The
decoder supplies no catalog lookup, draft role/cardinality decision, new local
identity, save token, file import, persistence, URI access or execution. A decoded
Selection is not proof of compatibility, source rights or shared-contract
acceptance. Schema-admitted `secret://` and other URI references remain inert
recorded values. This object is not a public export projection and must not be
shared without the separate disclosure and redistribution checks in
[Android #30](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/30).

Streams remain owned by the caller on every result. The caller supplies provider
timeouts/cancellation and performs stream/schema work away from the UI thread.
Accidental result/value `toString()` output contains no payload or provider error.

## Verification and receiving scope

[FrozenSelectionTest](../core/src/test/kotlin/ai/pickyour/core/contracts/FrozenSelectionTest.kt)
exercises standalone round trips for all 29 canonical fixture kinds, every
required top-level field, invalid kind/settings/enum/extension shapes, explicit
version context, exact large numbers/decimal strings, defensive nested copies,
inert references, optional absence and empty values, strict malformed input,
byte/depth limits, late provider failure and caller stream ownership. The existing
Build and private draft suites continue to exercise their separate consumers.

Run the pinned [core verification](CORE_FOUNDATION.md#reproduce), including the
frozen-import check and complete core check/assembly. A focused local invocation
is `./gradlew --no-daemon :core:test --tests '*FrozenSelectionTest'`.
Observed results belong to the actual tested source and PR receipt; the test
declarations in this guide do not assert execution.

This implements a bounded part of #21's schema/kind/enum model and strict input
subtasks. Network/storage integration, Android document transport, import consent,
public export, coordinated successor adoption, criterion-specific receiving,
installed-device behavior and release qualification retain their existing gates.
The canonical acceptance-record location, ledger, issue criteria and Project state
are unchanged; this engineering guide does not complete the work package.
