# Frozen deployment-manifest reader

This is a partial Android [#21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21),
[#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32) and
[#49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49) consumer boundary.
It reads the unchanged frozen `deployment-manifest.schema.json`; it implements no
deployment, release, approval workflow or external service. The manifest's recorded
claims are unverified input, including a status of `approved` or `deployed`.

## Preserved input

`FrozenDeploymentManifestDecoder` uses the existing bounded UTF-8 reader, explicit
version discrimination and fixed bundled validator. Every required manifest field,
all approval fields, the optional rollback URI, deployment reference and notes are
retained. Build revisions use `BigInteger`, including integral decimal/exponent
spellings accepted by the frozen schema. Closed enum inventories exactly match
the frozen approval decisions/scopes, rollout strategies and deployment statuses.

Approval and note array order and duplicates are preserved. Optional absence stays
distinct from an empty deployment reference or an empty notes array. Timestamp and
URI strings remain exactly as supplied. No whitespace is trimmed, no approver is
chosen, and missing approval timestamps are not synthesized. Immutable lists and
value fields prevent callers from mutating the decoded record.

The retained `json` string preserves JSON values, not original whitespace, number
spelling, source hash or signature. The caller must retain original bytes and
trusted artifact identity separately when those matter. Original parsing and the
validator's normalized-input pass both enforce the existing local budgets. Those
budgets are a receiving limit, not a change to frozen schema semantics.

## Authority and failure boundary

The decoder does not open rollback URIs, resolve evidence IDs, verify an approver,
establish a role's authority, compare referenced build revisions, determine whether
an approval is current, infer aggregate approval, or initiate any operation.
Schema-valid recorded statuses can disagree with recorded approval decisions;
the reader preserves that input without manufacturing a policy or accepting a
deployment. Required report IDs establish only their structural representation.
Real readiness, approval/revocation, operator and release checks retain their own
canonical service and client acceptance.

Malformed bytes, duplicates, trailing content, unsupported roots and exceeded
budgets return sanitized `InvalidInput` reasons. An unsupported or missing version
returns `UnsupportedVersion`; frozen-field/type/format failures return
`SchemaMismatch`. Accidental `toString()` calls contain no manifest, approver,
reference, note, URI or provider exception content. Successful records still hold
the original supplied values and must not be logged indiscriminately.

Streams remain caller-owned on all outcomes. The caller supplies transport
timeouts/cancellation and schedules stream work off the UI thread. The decoder
does not grant import, export, persistence or disclosure authority.

## Validation

The shared synthetic [case file](../core/src/test/resources/contracts/deployment-manifest-cases.json)
pins the original example/schema SHA-256 values. The ordinary hash-first
[reference driver](../scripts/check_contracts.py) invokes the
[Python checker](../scripts/check_deployment_manifest_fixtures.py) against the
unchanged locally resolved schemas with both date-time and URI format assertions.
[Kotlin tests](../core/src/test/kotlin/ai/pickyour/core/contracts/FrozenDeploymentManifestTest.kt)
execute the same named outcomes and verify value-preserving decoding/redecoding.
They separately cover every typed field and enum, exact wide revisions, optional
distinctions, duplicate ordering, immutable lists, literal URI preservation, local
limits, malformed UTF-8, provider failures and stream ownership.

Use the pinned Python 3.12 environment and the repository's strict JDK21 commands
in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). Current-source core, unsigned-preview,
scanner and retained-artifact checks remain required before normal reviewed
delivery. JVM/reference conformance supplies no installed Android, physical-device,
service, deployment, release or complete issue acceptance. Existing acceptance
record locations and the strict criterion ledger are unchanged.
