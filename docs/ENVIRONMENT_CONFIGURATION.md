# Android environment configuration admission

This is engineering source for [Android #57](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/57).
It is not the historical `Pick-Your-AI/pickyour.ai/docs/delivery/android/work-packages/02.02.md`
acceptance record and does not relocate it. The issue remains partial until the
actual configured-variant, receiving and prerequisite evidence is complete.

## Accepted inputs and current environment matrix

| Input | Exact accepted boundary consumed |
| --- | --- |
| [#34 native architecture](proposals/02-native-architecture.md), [01.03](delivery/android/work-packages/01.03.md), signed `93bebf787a091fe4858c1c70fd07cabefe17170f` / #297 | Retained Kotlin/platform Views, `:core` plus opt-in `:preview`, current experimental `ai.pickyour.android.preview`; future product namespace is a separate boundary |
| [#35 service decision](SERVICE_BASELINE.md), [01.04](delivery/android/work-packages/01.04.md), signed `4c753bd69f3857d9b15051be86078948e61074ac` / #347 | One future canonical Render API/PostgreSQL in Frankfurt; local/staging/production isolation is selected, but O1–O6 supply no actual endpoint, credential, operating acceptance or client activation |
| [#48 foundation record](delivery/android/work-packages/02.01.md), signed `710d337fed2004689993c0b8bc01f12de780b04c` / #448 | Current unsigned debug preview is the accepted bounded identity/toolchain. Future `:app` / `ai.pickyour.android` is not a configured or published production app; installed receiving remains with #467 |
| [Frozen OpenAPI](../contracts/v1/api/openapi.yaml) and [catalog example](../contracts/v1/examples/catalog.json) | Contract version `1.0.0`; `/v1` is a relative contract path, not a deployed URL. Configured example is `catalog.fixture.1`, revision 1, schema `1.0.0`, explicitly synthetic |

Intake main is `d5c66d3f0ffb402c2b2222dcd9d57d07d9fd736d`. The live #57
comments were read in full, including the earlier Help identity owner
[6258376382](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/57#issuecomment-6258376382)
and this distinct admission scope
[6258488671](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/57#issuecomment-6258488671).
The former owns its finite loaded-identity display; this change preserves
`LocalHelp`, `CatalogFixtureData` and the existing ten-field exported diagnostic
report. Current live #34/#35 decisions supersede dated unresolved-decision text
in the pinned mobile prompts; #48 remains partial.

| Selected environment | Endpoint / identity / capabilities | Actual source disposition |
| --- | --- | --- |
| `preview` | No API URL or API version; `ai.pickyour.android.preview`, `0.0.1-synthetic` / code 1; local drafts and synthetic catalog only | The closed non-secret [packaged profile](../preview/src/main/assets/client-environment.json), actual BuildConfig and installed application package must agree |
| `staging` | Real approved endpoint, application identity and scoped capabilities not supplied by accepted #35/#48 | Gradle rejects selection before APK creation; runtime admission also rejects even a plausible self-declared profile. No copied preview profile or guessed staging ID |
| `production` | Real approved endpoint, application identity and scoped capabilities not supplied by accepted #35/#48 | Same explicit unavailable result; no deployment, automatic `/v1` URL resolution, preview fallback or product package creation |

The engineering selection is `-PandroidEnvironment=preview`; omission means the
existing explicitly synthetic module default. Every other spelling is rejected.
Selecting `staging` or `production` gives a fixed actionable missing-handoff
message. This is a complete fail-closed admission mechanism for the currently
available profile, not a claim that real variants are configured. The current
`:preview:assembleDebug`, unsigned APK path, debug classpath, release-disabled
policy, nine-scenario manifest and all existing inventory checks remain intact.
No flavor, endpoint override, signing key, authorization token or secret setting
is introduced. Unknown property values are never included in our error text.

## Runtime boundary and observability

[ClientEnvironmentAdmission](../core/src/main/kotlin/ai/pickyour/core/configuration/ClientEnvironmentAdmission.kt)
uses the existing bounded UTF-8 reader: at most 4,096 configuration bytes plus
one overflow byte, depth 2, 64 tokens, 128-character values, 40-character names
and 10-character numeric spellings. Duplicate keys, trailing roots, unsupported
format, extra/missing fields, numeric/type coercions and mismatches reject.
The capability set is exactly `local_drafts` and `synthetic_catalog`; preview API
fields must be explicitly null. Even a well-formed unapproved HTTPS URL cannot
authorize a remote environment. This is local application format
`pyai.android.environment.v1`, not a new shared contract or changed frozen schema.

The admitted immutable result exposes finite environment/application/version,
configuration/contract/catalog-schema versions and configured synthetic catalog
ID/revision, with explicit null API version/URL. Those are **configured** values,
not proof the catalog loaded, remains current, has source rights or fits a build.
The other Help contributor owns the actual loaded-identity display. The packaged
JSON and the CI receipt make configuration metadata independently inspectable;
no arbitrary configuration value or parser exception enters the error screen,
`toString` output or exported diagnostics.

[EnvironmentBootstrap](../preview/src/main/kotlin/ai/pickyour/android/preview/EnvironmentBootstrap.kt)
reads only the small packaged configuration before `PreviewActivity` constructs
any editor, schedules catalog reads or opens the draft library. A rejection shows
finite recovery text and one Close action. New intents cannot enable the editor;
saved state does not persist or restore admission. Save-state, pause, resume and
destruction remain safe after the early return. A later correctly configured
launch re-evaluates the source; the app never deletes private data as recovery.
No HTTP client, server authentication, operation authorization or data promotion
is implemented by this bootstrap.

## Required evidence and source tests

The original baseline source unconditionally called `loadCatalog` and local draft
restoration in `onCreate`, with no selected-environment admission. This is a source
counterfactual, not an executed failing Android test. Local Android/Gradle builds
were deliberately not run during preparation because of shared disk pressure.
All Kotlin compilation, 14 new core methods and seven new Robolectric methods
remain required actual hosted execution before delivery. A declared method count
is not a passed-result count.

The tests include wrong actual package/version, missing/malformed/duplicate and
oversized configuration, contract/catalog/capability mismatches, secret canaries,
explicitly unconfigured real modes, exact packaged configuration/catalog metadata,
error-screen actions and the actual Activity early rejection. The Activity case
wraps the simulated framework package identity before `onCreate`, seeds a real
local saved draft, then exercises restored state, new intent, save/pause/destroy
and re-creation while comparing every retained durable file byte. This creates no
production test override or public configuration writer.

[The packaging checker](../scripts/check_environment_configuration.py) compares
one bounded, unique configuration member in the **actual inventoried APK** with
the exact tracked profile. It requires the same Git commit/tree, APK byte count
and hash, stable inventory and post-read source/artifact fences. Its finite
profile check rejects unknown fields, credentials, non-null API settings and
wrong scalar types. The existing secret scanner remains necessary: a finite
configuration check cannot prove the absence of secrets throughout an APK.
The existing inventory independently rejects ignored/untracked packaged inputs;
this profile enters its unchanged tracked `preview/src` and full Git-tree scope.

The five pure Python methods use small synthetic ZIPs/inventories and can run
without Gradle. They do not represent an Android artifact. The hosted preview
job additionally executes both deliberately unconfigured Gradle selections and
requires their specific admission failure, rather than accepting any build
failure. Existing artifact paths stay unchanged; only the small mode-rejection
logs and configuration receipt are added to their current upload scope.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p test_environment_configuration.py -v
PYTHONDONTWRITEBYTECODE=1 python3 -O -m unittest discover -s scripts/tests -p test_environment_configuration.py -v
python3 scripts/check_environment_configuration.py --source-only
# After the actual unchanged strict preview build + runtimeInventory:
python3 scripts/check_environment_configuration.py
```

## Literal criterion disposition and remaining inputs

| #57 criterion | This source contribution | Still required for whole-work-package acceptance |
| --- | --- | --- |
| Separate preview/staging/production endpoints and IDs; no secrets in APK; configuration and contract versions observable. | Explicit environment selection/admission, exact finite preview identity/version metadata, absent real inputs fail closed, no secret fields | Actual canonical staging/production endpoint and identity/capability handoffs; their configured-build and full packaged evidence |
| Each selected variant resolves the intended endpoint/identity and cannot silently use preview fixtures in production mode. | Existing preview identity preserved; both unconfigured real selections reject at build configuration and runtime admission | Actual selected real variants, approved data channel and versions, source/rights/authentication/capability receiving inputs and positive integration evidence |
| Missing configuration produces an actionable failure before a wrong-environment mutation; packaged diagnostics contain no secrets. | Pre-editor/storage/fixture gate, finite error projection, no stored admission authority or destructive reset | Actual hosted core/native/packaging/scanner receipts and independent receiving review; real operation routing/authentication stays with canonical services |

O1–O6 and each service/data consumer retain their actual activation requirements.
#48's installed receiving, the earlier Help contributor, #494 support acceptance,
canonical API intake #451/#36, and the shared ledger remain independently owned.
Neither metadata readback nor a passing synthetic test supplies endpoint custody,
private-data admission, installed qualification, public release or operational
readiness. Preserve all literal #57 checkboxes/relationships/assignments and the
historical 02.02 record until its own deliberate receiving reconciliation.

Primary Android build guidance inspected 2026-10-19:
[Build configuration](https://developer.android.com/build),
[build variants](https://developer.android.com/build/build-variants), and
[BuildConfig fields](https://developer.android.com/build/gradle-tips).
These explain configuration mechanics; the pinned project settings and the
accepted decisions above supply the actual application identity and scope.
