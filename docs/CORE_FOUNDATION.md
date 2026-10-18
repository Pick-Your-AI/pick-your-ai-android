# JVM core implementation and acceptance limits

This is an executable, platform-independent Kotlin foundation for the Android
client. It does not select a publisher identity, commit a production hosting
architecture, or claim an installed Android application. The maintainer's current
implementation mandate permits this isolated code while product/device receiving
acceptance remains pending. Kotlin is chosen for this core because it follows the
reviewed native recommendation and can be tested without Android APIs; Compose,
Room, the application ID and SDK support are not silently accepted by this choice.

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
  not a cross-document compatibility verdict.
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
| #48 pinned toolchain / clean checkout | JDK21, Gradle wrapper, Kotlin module, dependency locks and verification, JVM build | Approved product package, installed-app launch and full module boundaries; the separate unsigned fixture preview is only a diagnostic |
| #49 versioned fixtures and reference expectations | Exact upstream examples and 21 reference tests | Cross-language comparison, successor migration cases and accepted new semantics; frozen reference tests cannot approve queued changes |
| #58 cancelled/superseded work and typed failures | Synchronized identity/ticket coordinator and race tests | UI observation, dispatchers, storage/network adapters and cancellation propagation |
| #30/#31 untrusted import boundary | Bounded parser and adversarial tests | Disclosure projection, explicit user acceptance, Android document-provider lifecycle, actual draft import/export and receiving security review |
| #59 reproducible checks and untrusted execution | Reference/JVM workflow with restricted token and retained artifacts | Product Android/emulator coverage, required-status setting and observed controlled hosted failure; the unsigned fixture preview has a separate build/lint lane |
| #115 late/out-of-order analysis | Unique attempt identity, all input pins, cancellation and duplicate tests | Automatic reevaluation, durable-save sequencing, historical report persistence and integrated UI behavior |

The canonical acceptance records remain in `pickyour.ai`. Parent coordination can
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
total bytes can grow; continuous refresh is not ready until retention and pinned
reference handling are implemented/reviewed. A checksum alone proves no publisher
identity or vendor compatibility.

Parent handoff records: `12.03.md` (#87) and `12.02.md` (#104) under the canonical
website `docs/delivery/android/work-packages/` directory. Record this as partial
implementation with exact merged PR/test artifacts, not acceptance or closure.

The [reference report receiving boundary](REFERENCE_REPORT_BOUNDARY.md) adds
structural and cross-document checks before accepting frozen reference reports.
Its Python-generated cases and Kotlin negative tests are partial #32/#49/#84
evidence, not a local evaluator or deployment approval.
