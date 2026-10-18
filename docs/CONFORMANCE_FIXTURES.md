# Android conformance and historical-storage fixtures

This is a fixture-only preparation artifact for
[Android #49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49).
It supplies an Android consumer for the existing neutral wire profile and fixed
private Android migration inputs. It exercises the accepted core readers and
storage APIs without replacing a production decoder or adding evaluator policy.
The original issue's three criteria, prerequisite handoffs and receiving roles
remain authoritative. This document is a partial implementation reference; it
does not relocate the website acceptance record at
`docs/delivery/android/work-packages/03.06.md` or mark the issue complete.

## One neutral producer, exact original bytes

The producer is the independently reviewed Apple #37 preparation at recovered
commit `6eec8878e1456d2e7cad78399702a33461093ce8`, tree
`08e54247b998d005ed831bfaeabad7731f3e7a8a`, sole parent
`7d9724b16136546b686d87f29acf9bd95ca9c2eb`. Its complete source has a durable
[archival transport and reconstruction record](https://github.com/Pick-Your-AI/pick-your-ai-apple/blob/78cb2675356e7d00167b645cc7c993d2425fd2fa/preparation/apple-37-reviewed-08e/README.md)
at container commit `78cb2675356e7d00167b645cc7c993d2425fd2fa`, tree
`b37348542be960a426acf2e4ec9307954643be0f`. Those are archival preparation
identities, not a claim of Apple default-branch delivery or native acceptance.

The [consumer lock](../core/src/test/resources/conformance/consumer-lock.json)
records that distinction and all transport, version, reference and private-input
identities. Android adds no second neutral generator. The 43,929-byte gzip
resource recovers the producer's exact 1,273,770-byte JSON, including its case
order, payload strings, original input pins, exclusions and declared limits.

| Identity | Pin |
| --- | --- |
| Neutral bundle version | `apple-cross-client-1` |
| Frozen shared contract version | `1.0.0` |
| Raw producer SHA-256 | `499fd9a01780ac42fa9bf2ab8cf9e1195db27321fec36040451b553a8b9ea5f9` |
| Raw producer Git blob | `69718936a83b83e91aca7a492e48720fd2fe6cce` |
| Lossless gzip SHA-256 | `c1e66aa954c20354ec607ceb8f90a90c599a8748ab10dcff17ce63fda8bf366c` |
| Frozen Research reference | `7acbefbf4e4e700231e88be272bc89e3a73dde15` |
| Existing website validator | `dc66a9e269917ab7241c7d08fd0e7f2a2f039872` |

The profile has 223 cases: 141 accepted and 82 rejected. It includes 85
BuildSnapshot, 61 CatalogItem, 33 Selection and 44 EvaluationReport cases. Those
counts overlap the producer's semantic groupings: its 98 placement cases retain
30 preserved, 20 unresolved, 24 unsupported and 24 invalid dispositions; its 37
evidence cases retain 28 accepted and nine rejected expectations. These groups
are not extra cases to add to 223.

The original six catalog and 18 quality representation exclusions remain in
their complete source suites. This profile does not prove arbitrary-number,
arbitrary-encoding, every-contract, source-admission or evaluator equivalence.
Placement and evidence values that are structurally preserved remain recorded
inputs. A successful structural read does not turn an unsupported or unresolved
policy into an executable fact.

## Android wire interface

The [test-only adapter](../core/src/test/kotlin/ai/pickyour/core/conformance/CrossClientFixtureAdapter.kt)
checks both compressed and uncompressed byte bounds and hashes before exposing
the cases. Each case binds its original `payloadJSON`, SHA-256, explicit
contract/schema, immutable case ID and Boolean expected admission. The existing
`FrozenSchemaValidator` determines admission. Accepted payloads also pass the
existing strict JSON reader, serialize and read back with the same typed JSON
value. A round-trip failure fails the test; it is never relabeled as an expected
wire rejection. The original payload bytes remain unchanged.

Each of the 223 dynamically named `wire:<case-id>` tests emits exactly one
`ANDROID_WIRE_CASE` object after its assertions. Its four fields are
`bundleVersion`, `id`, `accepted` and `preserved`. `accepted` is a Boolean;
`preserved` is Boolean `true` for accepted cases and JSON `null` for rejected
cases. Numeric `1`, text `"true"`, an absent field and `null` are different
protocol values. Two additional tests deny changed/truncated/oversized fixture
transport and a changed payload reusing an otherwise valid case identity.

This interface is available before a production Android evaluator is selected
or implemented. It adds no rule verdict, finding-severity policy, vendor claim,
deployment authorization or service request. Existing domain evaluators and
their separate conformance work keep their existing owners and scope.

## Fixed private Android history

The [private migration bundle](../core/src/test/resources/conformance/local-migrations-v1.json)
has version `android-local-migrations-1`, 183,586 bytes and SHA-256
`f480f236e7f93e737095c7f53c3fa30ab38df1e86bc41059305b0ca11591206b`.
Its 10 fixed original byte strings and 34 scenarios are separate from the neutral
wire bundle and from Apple's local SQLite schema versions. Its source basis is
accepted Android commit `ac8307a4b36e64008c5758621634c105e0078e6c`.

Versions 2 and 3 contain the exact existing historical fixture bytes from that
commit, with their original paths and Git blobs. Versions 1, 4 and 5 are explicit
synthetic historical inputs recorded once with their expected meaning. The
tests never invoke today's serializer to manufacture those historical originals.
The version-1 record retains an explicit earlier-history gap and legacy hash.
The version-4 record uses the five already frozen foundation selections and
their explicit links and workload inputs. The version-5 record appends an
explicit `CONFIGURE_ROLE` history event while retaining every earlier version-4
history entry. The remaining five inputs deliberately exercise unsupported
versions, role history mislabeled as an older format, and a duplicate decoded
private version key.

| Original private format | Read without writing | Same-content save | Explicit supported edit |
| --- | --- | --- | --- |
| 1 | Retains format 1, legacy anchor and history gap | Explicit migration to format 4 | Appends format-4 history, keeping the legacy source hash |
| 2 | Retains format 2 | Exact no-op, no migration or write | Appends format-4 history |
| 3 | Retains format 3 | Exact no-op, no migration or write | Appends format-4 history |
| 4 | Retains format 4 | Exact no-op, no migration or write | Ordinary edit retains format 4; explicit role edit uses format 5 |
| 5 | Retains format 5 | Exact no-op, no migration or write | Appends format-5 history |
| -1, 0, 99 | Explicit unsupported-version failure | Cannot reset or rewrite the original | No implicit migration |
| Mislabeled role history or duplicate key | Explicit corrupt-record failure | Cannot reset or rewrite the original | No permissive repair |

The [migration tests](../core/src/test/kotlin/ai/pickyour/core/conformance/LocalMigrationFixtureTest.kt)
exercise five historical reads, five same-content actions, five edit/restore
journeys, ten ordinary edit interruptions, an explicit role migration and both
of its interruptions, five incompatible-input rejections and one retained
snapshot journey. Every selected interruption must actually execute exactly once;
an unexpected ordinary-write callback fails the test. Before pointer activation,
the original head remains available; after activation, an uncertain result must
be reconciled by its actual reopened hash. Retry, stale-CAS denial and restoration
assertions execute before a scenario can emit a passing observation.

Every supported scenario compares its explicit original content, workload,
metadata, component/role/link values and entire history. Empty, absent, literal
`unknown`, disabled optional inputs and the exact decimal string `0.00` are
retained as entered. Restoring appends history and retains the current metadata;
it cannot silently replace the current revision or reuse a stale revision token.
Original content-addressed bytes remain readable and byte-identical after the
action and its recovery checks. The archive scenario also proves that an already
materialized version-4 source, BuildSnapshot bytes, profile version, artifact
pins and original source history remain unchanged after live version-5 role
editing and restoration.

Each `migration:<scenario-id>` test emits one `ANDROID_MIGRATION_CASE` object:
`bundleVersion`, `id`, `inputSha256`, `outcome`, `currentFormat` and
`originalPreserved`. The current format is the verified head at that scenario's
named action boundary. For an interruption it is the first reopened head, before
the subsequent retry/restoration assertions; incompatible input records `null`.
`originalPreserved` must be Boolean `true` after all the assertions. The 35th
migration-suite test rejects changed, truncated and oversized bundle transport.

## Strict receipts and CI

Run the independent input and receipt guards from the repository root:

```sh
python scripts/check_conformance_fixtures.py
python -m unittest discover -s scripts/tests -p 'test_conformance_fixtures.py' -v
```

The normal core Gradle test task now associates stdout with each named XML case.
After the existing successful strict core build, CI invokes:

```sh
mkdir -p build/conformance
python scripts/check_conformance_fixtures.py \
  --reports core/build/test-results/test \
  --exit-code 0 \
  --output build/conformance/qualified.json
```

The `0` is the status of the preceding successful CI test step; a manual caller
must pass its actual retained command status. A failed step never reaches the
normal success-only qualification step. The checker requires exactly 225 wire
suite tests and 35 private migration suite tests, correct full class names, all
257 typed observations in their own named cases, and zero missing, repeated,
failed, skipped or retried cases. Moving an observation into another case, a
guard, suite stdout or stderr is rejected. The remaining repository tests retain
their existing independent checks.

The 24 adversarial Python methods also cover changed source fixtures, malformed
XML, DTD/entity input, extra result elements, nonfinite numbers, escaped duplicate
keys, wrong types and version/hash changes. Failed requalification removes the
old receipt before checking; atomic publication leaves no success file after a
failed write. Source, report inputs, symlinks, hard links and directories cannot
be replaced as receipt outputs.

Reports have an explicit UTF-8 boundary, with an optional UTF-8 BOM. Raw NUL,
invalid UTF-8 and another declared encoding reject before XML parsing. The
DTD/entity guard inspects the same decoded text that the parser consumes;
UTF-16/32 auto-detection cannot bypass an ASCII byte scan. Plain UTF-8 controls
retain the original byte length/hash, including the BOM when present. This
report transport restriction changes no frozen payload or private draft format.

The receipt does not authenticate its own caller or source. The original command
logs, actual exit status, clean-checkout runtime inventory, prepared Git tree and
compiled outputs still need their normal independent receiving audit. CI retains
the two XML suites and qualified JSON alongside the existing core artifacts.
No workflow trigger, required check name, dependency lock, production resource,
frozen contract or Android-preview behavior is replaced.

## Actual local preparation evidence

The following are distinct executions, not a single combined runtime receipt.
All original logs and command/source manifests are retained for the receiving
review. This preparation's six Kotlin/fixture inputs are unchanged across the
two complete JVM executions described here.

| Actual execution | Result | Original log SHA-256 |
| --- | --- | --- |
| Existing Python producer/reference checker, exact frozen Research input | 223 cases; 141 accepted and 82 rejected; 154-byte log | `657f1c16fab4242714c0ec6beab293af66d898a9f31a056a4f95147da915cc5d` |
| Existing pinned TypeScript validator closure, Node 24.19.0 | 223 named cases with the same typed expectations; 31,305-byte log | `d17fbc2b3ad065c6893124c02fbef3aca9ab1a991fb84681899e1fbe48c5be80` |
| Direct Kotlin 2.1.20 / JDK 21 / JUnit 5.11.4 preparation on compiled `ac8307a4` core | All 260 tests pass, zero failures/skips/aborts; 5.247 seconds; 125,320-byte log | `2d9987cc47a41451539e382cb30ef979d7747c8b17981116ab77023188139927` |
| Offline Gradle 8.11.1 standalone Test task over those same compiled inputs | 225 wire and 35 migration cases pass; actual per-case XML; 661-byte log | `2c0205be87ceef53ba80d58728d4e20442cc9c3e05ac35c9aba275c41210a614` |
| Complete receipt/adversarial suite after valid-recovery control | All 21 methods pass; 1.995 seconds; 4,166-byte log | `5ceaaa1ce386081cf7cbba65a173090655aaba66df2a19dbb16684cedc31bb6e` |
| Complete source checks before the later XML-encoding review, in an isolated environment with all ten exact Android reference pins | 21 contract, four integrity, 42 ledger and 21 fixture-receipt methods pass; all existing fixture checks pass; 16,955-byte log | `df1c3822e37111d9ca18173360c879f124df0137800522853cb691bf2d158f4a` |
| Complete receipt suite after the independently found XML-encoding correction | All 24 methods pass; 1.775 seconds; 5,139-byte log | `6443b63313be8759fb83ab382d9aba2b6ab5cc93c6c1438f3135dce1a0795188` |

The complete source check used the repository's exact ten dependency pins,
verified from the installed package metadata before execution. An earlier attempt
in the existing Apple reference environment stopped at the quality-fixture check
because Android's required RFC format-checker dependencies were absent. Its
3,373-byte failure log/SHA-256
`b33ca9b817e27dc46fac7c9edb0f5ed6bc69749931ecbd6adc238b34ecdd57e2`
is retained separately. Installing the exact Android pins in a private target
allowed the unchanged source checks to complete; neither that failure nor its
recovery changes the independently recorded JVM and Gradle-probe executions.

Independent review of the original qualifier at tree
`41a5764ae18cebba09e40f0505f450e5af7705b7` found a real parser false acceptance:
an internal entity in synthetic UTF-16 versions of the actual wire XML passed
the byte-oriented declaration guard and expanded to all 223 expected observations.
All four little/big-endian variants, with and without BOM, now reject. The exact
UTF-8 entity controls still reject, and the original actual UTF-8 Gradle XML
still qualifies to the byte-identical 65,948-byte receipt/SHA-256
`bc9249782aa57c0c6314827fe264cc709343f95cc31d6aa02fe9ab7b48f538c8`.
The original red probe and 7,712-byte local regression log/SHA-256
`f2b9f82a39402e64773000a723a6b49d259979e21968ef49a0c2c439eb1eb249`
remain retained. This was an independently reproduced synthetic parser defect;
it is not described as an observed Gradle, JVM or native test failure.

The direct JVM run compiled every accepted main core Kotlin file and the four
new test files against 25 SHA-verified cached dependency JARs without modifying
another controller's cache. The small Gradle probe used its own temporary home,
the same compiled classes and pinned runtime files; it proves the actual XML
association format, not a full repository Gradle build. Its wire XML is 73,764
bytes/SHA-256 `7fe9862bab8e5f3d7269ed0704f18077b0004e276fdde9c09e296e516e6d9bff`;
its migration XML is 15,535 bytes/SHA-256
`2b8613982ae2762d4d8d0a40622c397a6e68a4720378204f08097ca91af4c0ca`.
The independently qualified JSON binds all 260 actual named tests and 257
observations. The original earlier wire-only 225-case probe remains a separate
historical receipt. Current repository CI, independent source review and final
receiving evidence remain pending until actually obtained.

## Criterion mapping and remaining handoffs

| Literal #49 criterion | Concrete preparation | Remaining receiving boundary |
| --- | --- | --- |
| Publish versioned neutral conformance/migration fixtures, Android expectations and reference-validated interfaces; evaluator/quality work owns later runtime parity | Reused byte-exact 223-case producer profile, published archival provenance, explicit private Android bundle and fixture-only consumer/receipt APIs | Deliver this candidate through reviewed normal CI; obtain central and affected-consumer receiving dispositions and the issue's exact prerequisite outputs |
| References agree on expected outcomes and Android can consume before its production evaluator exists | Actual Python and pinned website results agree with all 223 Android observations; no evaluator prerequisite was added | Preserve those exact versions and limitations in the authoritative acceptance record |
| Original snapshots retain version/meaning; migrations are explicit and incompatible input fails consistently | Fixed versions 1–5, exact prior history/hash checks, five incompatible inputs, real write interruptions/recovery and retained archive assertions | Review the stated private-format scope against accepted #26 migration output; do not equate it with wire or Apple database versions |

Android #26 retains its empty/partial editing, explicit valid-snapshot transition
and versioned-migration handoff. Android #27 and #37 retain the exact claim/evidence,
answer-state, capability-completeness, deployment, role, CPU/unified-memory,
context-budget and serving-override policy dispositions stated in the live issue.
This profile preserves values or rejects structural invalidity where already
specified; it invents none of those executable rules. Those native edges gate
full delivery acceptance, while this preliminary fixture work can proceed.

Semantic changes require a concrete versioned decision through the existing
[Research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40)
contract lane and review by affected Android, web and Apple consumers. Add a new
producer/private fixture version with explicit migration and expected outcomes;
retain the old bytes and run both old and new scenarios. Updating a hash alone
does not approve new semantics, upgrade an old observation, or change what an old
snapshot meant. Broader runtime conformance stays with Android #84 and #32;
synthetic cases establish no real vendor compatibility or research acceptance.
