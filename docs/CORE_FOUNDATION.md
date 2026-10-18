# JVM core implementation and acceptance limits

The [frozen CostEstimate boundary](FROZEN_COST_ESTIMATE_BOUNDARY.md) preserves every
recorded field, exact scalar string and ordered line as immutable Kotlin values.
Its shared structural cases keep schema validity separate from the existing
Build-bound cost inspector's stronger checks. No pricing calculation, evidence
acceptance or budget verdict follows; broader #21/#32 acceptance remains separate.

The [frozen Pricebook boundary](FROZEN_PRICEBOOK_BOUNDARY.md) retains every recorded
rate/provenance field, exact scalar string and ordered duplicate as immutable
Kotlin values. Shared structural cases preserve absence/empty distinctions and
keep the existing recorded-price reader's stricter interval, money and revision
checks intact. No screening or price/evidence acceptance follows; #21/#32 stay partial.

The [frozen Ruleset boundary](FROZEN_RULESET_BOUNDARY.md) adds an immutable typed
representation of every recorded rule, expression and operand. Shared reference
cases retain closed shapes, exact opaque literal JSON and ordered duplicate rules.
This structural reader performs no evaluation, reference resolution, metric
calculation or baseline activation; broader #21/#32 acceptance remains separate.

This is the executable, Android-SDK-independent Kotlin core for the bounded
guest-local client. The accepted [native decision](proposals/02-native-architecture.md)
selects Kotlin/platform Views, the existing local revision store and the
`:core`/`:preview` boundaries. Compose and Room remain documented alternatives.
The [repository scope](REPOSITORY_SCOPE.md) distinguishes the current preview ID
from the selected future product engineering namespace; publisher registration,
device qualification and installed release acceptance remain separate. The
[service decision](SERVICE_BASELINE.md) records the future shared-service placement
and its activation gates; this core does not deploy or operate that service.

## Reproduce

Install a full JDK 21 (a JRE alone is insufficient), Python 3.12 and dependencies:

```sh
python -m venv .venv
.venv/bin/python -m pip install -r requirements-contracts.txt
.venv/bin/python scripts/check_contracts.py
sha256sum --check gradle/wrapper/wrapper.sha256
./gradlew --no-daemon :core:check :core:assemble
```

The Gradle 8.11.1 wrapper distribution and wrapper JAR have upstream SHA-256 pins;
Kotlin is pinned to 2.1.20. The tested language/toolchain combination is documented
by [Kotlin 2.1.20](https://kotlinlang.org/docs/whatsnew2120.html#gradle) and
[Gradle Java compatibility](https://docs.gradle.org/8.11.1/userguide/compatibility.html).
Dependencies are locked and their downloaded artifacts have SHA-256 verification
metadata. Initial hashes are provenance/integrity pins, not a security audit or
independent assertion that a dependency is safe. Update versions, locks and
verification metadata together after review. No dynamic versions or repositories
from client payloads are used.

On a managed proxy, configure Gradle's standard HTTP/HTTPS proxy properties in
your private user Gradle configuration; never commit machine-specific hostnames,
trust-store overrides or credentials. The cloud test environment uses its existing
trusted proxy and a workspace-local JDK; normal checkout commands need no such
paths. Dependency download requires network on the first run; subsequent cached
`--offline` runs do not.

## Boundaries and behavior

- `BoundedJsonReader` consumes actual bytes, rejects duplicate keys, malformed UTF-8,
  invalid surrogates, trailing input and excessive nesting/tokens/lengths. The
  caller owns the stream. This is syntax acceptance, not permission to import,
  fetch a URL, overwrite a draft or disclose fields. Limits are conservative local
  preview limits; they are not new frozen-contract semantics.
- `FrozenSchemaValidator` selects one of 13 fixed document schemas and resolves all
  references from bundled resources. Unknown schema locations fail closed; client
  data cannot select a dialect or cause a network lookup. Format assertions and
  extra-field/kind/enum checks use the unmodified contract. Structural validity is
  not a cross-document compatibility verdict. The finite frozen pattern set uses
  [LF-only anchor handling](FROZEN_PATTERN_PARITY.md) to preserve the pinned Python
  reference outcomes without trimming input or changing prefix-only matching.
  Bundled schema integers load at arbitrary precision so wide integral
  decimal/exponent inputs receive exact minimum checks; the input reader and
  original bytes remain unchanged.
- Contract money values retain decimal strings exactly and use `BigDecimal` only
  for exact numeric access. Missing or malformed values never become zero. This
  does not implement rate selection, aggregation, billing rounding or a full quote.
- The analysis coordinator binds callbacks to a unique attempt and all supplied
  input pins. Cancellation, a new draft/revision/pin, retry, duplicate callback or
  foreign ticket cannot replace the current result. Transport cancellation and
  reevaluation scheduling belong to adapters, and persistence must acknowledge
  edits before dispatch. Reports supplied by callers must be immutable snapshots.
- The original reference evaluator and fixtures remain frozen, synthetic inputs.
  Their passing tests prove reference conformance only, not vendor capacity,
  production catalog rights or complete Android evaluator parity.

## CI and evidence

`Core verification` runs on pull requests and main, with read-only repository
permissions, no persisted checkout credentials, no signing or production secrets,
and pinned action revisions. JUnit reports and the core JAR are retained for seven
days under the exact commit. This is a JVM/reference lane. A separate unsigned synthetic preview lane now
builds an APK and runs Android lint; it does not produce a release AAB. Emulator
and physical-device lanes are not yet present. Branch protection remains
a separate repository setting; publishing this workflow does not make it required.

## Criterion-level handoff

| Issue / criterion | Implemented evidence | Remaining work or external acceptance |
| --- | --- | --- |
| #21 exact upstream revision/hash, import integrity | 43 original files + two pinned manifests, hash-first reference runner | The schema adapter covers all 13 document kinds; actual API/storage consumers and receiving acceptance remain separate |
| #21 invalid fields/enums/versions and decimal round trip | Strict parsing, bundled JVM schema validation, immutable reference suite and typed decimal/version tests | Validate every actual network/storage consumer when those adapters exist |
| #48 pinned toolchain / clean checkout | JDK21, Gradle wrapper, Kotlin module, dependency locks and verification, JVM build; accepted #34 native/package/module decision | The future product application, publisher identity and installed-app build/launch qualification remain separate; the unsigned fixture preview is a diagnostic |
| #49 versioned fixtures and reference expectations | Exact upstream examples and 21 reference tests | Cross-language comparison, successor migration cases and accepted new semantics; frozen reference tests cannot approve queued changes |
| #58 cancelled/superseded work and typed failures | Synchronized identity/ticket coordinator and race tests | UI observation, dispatchers, storage/network adapters and cancellation propagation |
| #30/#31 untrusted import boundary | Bounded parser and adversarial tests | Disclosure projection, explicit user acceptance, Android document-provider lifecycle, actual draft import/export and receiving security review |
| #59 reproducible checks and untrusted execution | Reference/JVM workflow with restricted token and retained artifacts | Product Android/emulator coverage, required-status setting and observed controlled hosted failure; the unsigned fixture preview has a separate build/lint lane |
| #115 late/out-of-order analysis | Unique attempt identity, all input pins, cancellation and duplicate tests | Automatic reevaluation, durable-save sequencing, historical report persistence and integrated UI behavior |

The implementation acceptance records listed below retain their canonical paths
in `pickyour.ai`; the separately accepted decision-record placements are listed in
[repository scope](REPOSITORY_SCOPE.md). Parent coordination can
copy the criterion evidence above into `03.01.md`, `02.01.md`, `03.06.md`, `02.04.md`,
`11.03.md`, `14.05.md`, `02.05.md` and `09.06.md` under
`docs/delivery/android/work-packages/`, linking the merged PR and exact test runs.
These are partial entries, not issue-closing receipts. No website changes are made
by this repository.

## Observed local verification (2026-10-18)

The full JDK21 build ran 33 JVM tests with zero failures/errors and produced the
core JAR. The hash-first Python runner passed all 21 unmodified reference tests.
Wrapper JAR and distribution matched official SHA-256 checksums. This does not
prove Android install, lifecycle, emulator, device or release behavior.

## Exact numeric schema bounds (2026-10-18)

The #26 projection work reproduced a distinct #21 receiving defect on accepted
main `a037d2b94ed55c0946cd04383b68a3f1dc25875a`: changing only the original Catalog
fixture's revision to raw `1e19` failed `minimum: 1`, while `-1e19` incorrectly
passed. The equivalent plain negative integer rejected correctly. The strict
reader retained the exact `DecimalNode`; narrowing occurred later in the pinned
networknt 1.5.6 integer-schema minimum shortcut, which calls `asLong()` for that
input representation. A wrapped machine integer could reverse the decision.

The supported schema-reader configuration now loads integer constants from the
unchanged bundled schemas as `BigIntegerNode`. That selects the library's existing
exact numeric comparison path. No schema, dependency, parser budget, input value,
original byte spelling or validation keyword is replaced. In particular this
does not rewrite user JSON, weaken integer typing, normalize decimal strings or
choose a different shared contract. The separately owned #287 regex/money work
retains its own source and review.

`FrozenIntegerBoundsTest` enumerates all 74 integer-minimum locations in the frozen
document and kind-specific schemas and requires exact set equality with the
unchanged schema inventory. Each location executes 23 actual raw-number/type
cases: ordinary and integral decimal/exponent spellings, positive values beyond
machine width, negative counterparts, zero boundaries, fractional values, boolean
and string controls. Two further methods exercise the nested Build and actual
selection/catalog receivers, ordinary fractional bounds, strict syntax failures
and input-byte preservation. The focused matrix therefore executes **1,704 actual
cases**, rather than reporting only the dynamic-test factory invocation.

The unchanged focused suite had **327 failures before** the configuration fix and
**zero failures, errors or skips after** it on the original a037 base. Retained
XML identifies both executions. The 17 accepted incoming paths through
`f4d1487cb82921e8418d4474cc373c2b817e0a59` are then preserved before final core,
reference and unsigned-preview gates; their final exact-tree results belong in
the reviewed delivery receipt. This narrow correction supplies no complete #21
or #26 acceptance, installed-device result, compatibility verdict or release.

The first complete unsigned-preview run found a separate consumer dependency on
that schema defect: the existing catalog/pricebook expanded-revision-budget test
failed once `1e128` became correctly schema-valid. The catalog browser and recorded
pricebook now explicitly enforce their existing local limit of 128 expanded
decimal digits before materializing/displaying a revision. This limit remains a
local receiving policy; the general frozen validator still accepts valid larger
bounded raw integers. The original Activity regression is unchanged, and one
additional core method checks accepted `1e19`, the exact 128-digit boundary,
original pricebook hash and schema-valid but locally unsupported exponents. The
final numeric suite therefore contains **1,705 actual cases**. All other typed
core revision consumers retain their arbitrary-precision values and existing
identity/reference checks.

## Offline artifact primitive (#87 / #104, partial)

`VerifiedArtifactCache` stores a single immutable artifact or a complete bundle
already validated by its caller. It bounds actual input bytes, checks a trusted
expected SHA-256, forces staged bytes, and atomically changes an active pointer.
Failed pre-activation installs leave the previous pointer unchanged. A filesystem
failure after activation was attempted returns `ActivationUncertain`: callers must
reopen and verify, rather than falsely acknowledge failure or success. Verified
reads report missing/corrupt data explicitly. Previously installed hashes remain
readable; the cache does not evict pins.

All cooperating instances first claim a shared non-reentrant JVM guard, then use a
nonblocking OS lock. A separate-JVM regression proves an unsuccessful same-JVM
contender cannot release the active holder's process lock. Caller streams
remain open. Cancellation cannot unblock a blocking provider; the adapter must
provide timeouts/cancellation. The root must be an existing, durably provisioned dedicated app-private directory;
construction rejects a missing root and never implicitly creates ancestors. The
caller must ensure the root and ancestor directory entries are durable;
this class is not protection against a compromised app/OS or unrelated writers.
There is deliberately no unsafe fallback when atomic rename or directory sync is
unsupported. Storage errors are sanitized typed outcomes.

Fourteen cache tests exercise reopen, exact byte limits, bad hashes, corruption,
short reads, repeated installs, staging recovery, cross-instance locks,
interruption and injected failures around activation. Together with the prior core,
47 JVM tests pass. These are actual local filesystem results and injected failures,
not Android device, power-loss or forced-process-termination evidence.

Remaining #87 criteria: OS background scheduling/battery/network constraints,
complete manifest authentication and atomic multi-artifact snapshot policy,
rights/freshness acceptance, configured total retention and UI degraded states.
Remaining #104 criteria: integrated browse/edit/save behavior, reconciliation and
current-versus-cached display. This primitive does not supply network transport,
authenticity, rights, catalog semantics or a production retention policy. Retained
total bytes can grow with the original constructors. Explicit optional
[artifact retention budgets](CACHE_RETENTION_LIMITS.md) now refuse new payloads
before activation without eviction, and the preview library applies derived
128-artifact/128-MiB limits to each draft's revisions. Continuous catalog refresh
still needs configured product limits and pinned-reference handling. A checksum alone proves no publisher
identity or vendor compatibility.

Parent handoff records: `12.03.md` (#87) and `12.02.md` (#104) under the canonical
website `docs/delivery/android/work-packages/` directory. Record this as partial
implementation with exact merged PR/test artifacts, not acceptance or closure.

The [reference report receiving boundary](REFERENCE_REPORT_BOUNDARY.md) adds
structural and cross-document checks before accepting frozen reference reports.
Its Python-generated cases and Kotlin negative tests are partial #32/#49/#84
evidence, not a local evaluator or deployment approval.

The [quality-run decoder](QUALITY_RUN_BOUNDARY.md) preserves the separate frozen
measured-run wire fields as immutable Kotlin values, including exact numbers and
optional absence. Shared Python/Kotlin mutation cases and malformed-input tests
cover this structural boundary; decoding does not authenticate or execute an
evaluation, bind it to the current build, or certify its reported result.

The [frozen Event decoder](FROZEN_EVENT_BOUNDARY.md) retains every event and payload
field as immutable typed values, with exact subject revisions and explicit optional
presence. Shared Python/Kotlin cases exercise the unchanged schema. Decoding adds
no event execution, actor authority, idempotency, synchronization or telemetry.

The [resource-estimate decoder](RESOURCE_ESTIMATE_BOUNDARY.md) retains all frozen
capacity-estimate wire fields, exact decimal strings, arbitrary-size revisions and
declared confidence as immutable values. Shared Python/Kotlin fixtures and boundary
tests cover its structural behavior; decoding adds no arithmetic, current-build or
runtime/topology binding, calibrated capacity evidence or memory-fit verdict.

## Frozen deployment-manifest value boundary

The [immutable deployment-manifest reader](FROZEN_DEPLOYMENT_MANIFEST_BOUNDARY.md)
retains every frozen manifest/approval field, exact build revision, ordered arrays
and optional absence. Recorded statuses and approval decisions are unverified
input; decoding grants no approval, URI access, deployment or release authority.
Shared Python/Kotlin cases preserve frozen schema outcomes and sanitized failures.
