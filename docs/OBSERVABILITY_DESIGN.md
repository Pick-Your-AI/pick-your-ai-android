# Android and service observability design

This is a preliminary design contribution to
[Android #44](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/44).
The source baseline is accepted Android
[`126f8a217439038ebc3012401127a0237c9c87f3`](https://github.com/Pick-Your-AI/pick-your-ai-android/tree/126f8a217439038ebc3012401127a0237c9c87f3).
It maps the available failure boundaries to a minimal future signal design and
a verification plan. It adds no collector, SDK, endpoint, dashboard or alert.
The current ten-field diagnostic report remains unchanged.

## Accepted scope and responsibilities

The [01.05 decision delivered at `68e52dd9`](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/68e52dd9c79cefe97af65e8fd1e86e4b72d2c42c/docs/delivery/android/work-packages/01.05.md)
defers optional usage analytics and automatic remote crash/ANR/diagnostic upload,
including silent retry or an outbox. It permits bounded local review and deliberate
sharing with exact preview. Collection requires its own accepted purpose,
recipients, retention, disclosures and response qualification.

The [#23 inventory acceptance delivered at `e86e8bd7`](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/e86e8bd7645234e12b9887f1e6a0148a2d52073e/docs/delivery/android/work-packages/14.01.md)
and the [current inventory][inventory] supply F10/T08 for diagnostics and F12 for
service operational metadata. Apply the field allowlist before any sink. Service
logs also need operator roles, access audit, deletion exceptions and fixed expiry
before deployment. Acceptance of this inventory does not enable those flows.

The [service decision][service] selects the future canonical server location in
`Pick-Your-AI/pickyour.ai/services/planner-api`. Its O1–O6 operating gates remain
applicable. This Android document creates no second backend or service contract.
The required receiving roles are **accepted support/operations owner** and
**affected data/service owner**. Actual #44 reviewers, responder commitments and
service operating/recovery people remain unfilled; source authorship supplies none.

## Seven failure classes and their current producers

These are source observations at the pinned baseline, not executed incident or
delivery results. A local UI outcome is not a remotely collected signal.

| Failure class | Producer and current caller | Available observation and intended response |
| --- | --- | --- |
| Crash | Android runtime/process termination; no app crash-reporting collector is configured in the [preview build][preview-build] or [manifest][manifest] | No current diagnostic crash signal. After separate admission, classify a controlled crash by app release and a reviewed static failure category; the accepted responder reproduces it against that release. A handled local error is not a crash. |
| ANR | Android's responsiveness checks; no ANR collector or watchdog is installed by this preview | No current diagnostic ANR signal. Later qualification must distinguish an observed ANR from slow work or a cancelled operation, with OS and app identity. The responder investigates the affected execution path without receiving private traces by default. |
| API | [EnvironmentBootstrap][bootstrap] calls [ClientEnvironmentAdmission][environment]; [AnalysisCoordinator][analysis] provides typed local ordering/failure states, without transport | The admitted preview has no service URL or API version. Staging/production fail closed. No request latency, availability or backend error is measured. A future canonical client/service adapter must classify its actual result and identify the deployed version; a generic failure enum does not prove a service call. |
| Catalog | [PreviewActivity.loadCatalog][activity] calls [CatalogFixtureData.read][fixtures] and [CatalogRetainedSnapshots][catalog-retention] on its worker | Bundled synthetic input can be loaded/unavailable; retention can be unconfirmed separately. There is no production refresh request. The later publication lane needs distinct fetch, admission, activation and retention outcomes, with the last admitted version preserved after rejection. |
| Pricing | [RecordedPricebook.read][pricebook] is called by the fixture loader; [CatalogPriceBrowser][price-browser] displays its exact item/version lookup. [CostInspectionSection][cost-section] calls [CostEstimateAdapter][cost-adapter] for bundled estimate inspection | A missing book, no matching recorded rate and rejected estimate are distinct. Missing rates remain unpriced; declared coverage does not establish current pricing. Later routing should distinguish missing data from adapter failure, with reviewed price identity and no selected-component payload. |
| Evaluation | [SnapshotEvaluationSection][snapshot-section] supplies retained inputs through its local analysis adapter to [LocalSnapshotEvaluator][evaluation]; [DraftCurrentInputs][current-inputs] separately admits current-check inputs | Local preparation can withhold missing/mismatched inputs; execution can fail, cancel or produce a bounded diagnostic result. No provider inference or production evaluation is implied. A later signal can identify admission versus engine failure without serializing the build, findings or input digest. Unknown compatibility remains unknown. |
| Sharing | [LocalDiagnostics][diagnostics-source] calls the system text chooser only after review and a separate action; the Activity supplies current-owner/lifecycle checks | Launch can fail with a fixed safe message. Chooser launch gives no recipient-delivery acknowledgment. Remote build sharing and revocation are not this path. Later product sharing needs its own canonical service result, authority and receiver-acknowledgment evidence. |

Android documents [crashes](https://developer.android.com/topic/performance/issues/crash)
as unexpected exits from an unhandled exception or signal and
[ANRs](https://developer.android.com/topic/performance/issues/anr) as responsiveness
failures. These official concepts were checked on 2026-10-20. They select neither
a collection mechanism nor this project's alert thresholds. Platform detection,
app observation and delivery to a responder are separate facts.

## Version and identity matrix

Use the exact identity available at the failing boundary. These are existing
source/configuration fields and local binding concepts, not proposed wire keys.
The local report exposes only the entries explicitly marked below. Future use
of a public artifact identity requires a reviewed allowlist and disclosure;
arbitrary incoming strings or private-record hashes must never be copied into it.

| Identity | Exact source fields or binding | Availability and interpretation |
| --- | --- | --- |
| App | `BuildConfig.APPLICATION_ID`, `VERSION_NAME`, `VERSION_CODE` → `application_id`, `application_version`, `application_version_code` | Included in the current report. The compiled preview values are `ai.pickyour.android.preview`, `0.0.1-synthetic`, `1`; they identify this preview, not a qualified product release. |
| Android OS | `Build.VERSION.SDK_INT` → `android_api` | Included in the current report. This is the running OS API level, independent of service API version and compile/target SDK settings. |
| Environment | Configuration `configuration_version`, `environment`, `capabilities`; admitted `EnvironmentAdmission.SyntheticPreview` | Configuration is admitted before editor/store/fixture opening. The report's fixed `client_mode=synthetic_guest_preview` describes that scope; it does not echo configuration or rejected values. |
| Service API/runtime | Configuration `api_base_url`, `api_version` | Both are `null` in the [admitted file][environment-file]. No deployed service runtime identity is available. Future operating evidence must bind the actual deployment and approved API version; never substitute `android_api`, an example URL or the contract version. Neither field is in the report. |
| Shared schema/contract | Configuration `contract_version`, `catalog_schema_version`, both `1.0.0`; document `schema_version` | Describes the frozen structural contract. Successful decoding does not establish a deployed API, publisher authority, freshness or operational health. Not in the report. |
| Configured versus loaded catalog | Configuration `catalog_id`, `catalog_revision`; admitted catalog document `id`, `revision` plus original-byte hash | The profile configures `catalog.fixture.1`, revision `1`, even before loading. [DraftCurrentInputs][current-inputs] binds `catalogId`, `catalogRevision`, `catalogHash`; retained evaluation separately checks its supplied pin. The report contains only finite catalog UI state, never these IDs or hashes. |
| Rules | Ruleset document `id`, `version`; local `rulesetId`, `rulesetVersion`, `rulesetHash`; build locks `ruleset_id`, `ruleset_version` | Use the exact admitted rules bytes and string version. A ruleset version is not a catalog revision. Missing/mismatched input withholds the relevant evaluation; not in the report. |
| Dependency graph | Graph document `id`, `revision`; build locks `dependency_graph_id`, `dependency_graph_revision`; local original-byte hash | A separate evaluation input, checked independently of rules/catalog identity. Do not infer it from the app version or use a private build digest as a replacement. Not in the report. |
| Prices | Pricebook document `id`, `revision`; `RecordedPricebook.id`, `.revision`, `.hash`; build lock `pricebook_id` | Bind the actually admitted pricebook. The ID-only build lock or an estimate's `pricebook_id` cannot supply its revision, freshness or rate applicability. Local retained pins carry the separate exact binding; current diagnostics include none of it. |
| Evaluator/release evidence | `RetainedEvaluationProfile.ID` and its pinned evaluator/reference/schema/receiver source hashes; actual release commit/tree and package identity in qualification evidence | The local profile identifies a bounded calculation, not external evidence or service deployment. Its fixed reference time is not an incident time. Private request/bundle digests remain local. No evaluator, release digest or timestamp is added to the report. |

Keep configuration, loaded inputs and the request that actually failed distinct.
After replacement or cancellation, an old callback cannot be attributed to the
new input. If a version was unavailable, record that absence in the eventual
approved representation; do not fill it with the current/latest value or zero.
Public source hashes establish byte identity, not rights, independent verification
or production publication. Preserve the [research #40 contract authority](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40)
for any later shared field, semantic or endpoint change.

## Current report and deliberate sharing

[LocalDiagnosticReport][diagnostics-source] accepts only storage and catalog enums.
It constructs an immutable text report in memory with these ten fields:

| Field | Current value source |
| --- | --- |
| `diagnostic_format` | Fixed `pyai.android.preview.diagnostics.v1`; a local text format only |
| `application_id` | Compiled application ID |
| `application_version` | Compiled version name |
| `application_version_code` | Compiled version code |
| `android_api` | Running OS API integer |
| `client_mode` | Fixed `synthetic_guest_preview` |
| `storage_state` | `BUSY`, `READY`, `RECONCILIATION_REQUIRED` or `UNAVAILABLE` |
| `catalog_state` | `SYNTHETIC_LOADED` or `UNAVAILABLE_OR_LOADING` |
| `private_content` | Fixed `excluded` |
| `support_submission` | Fixed `unavailable` |

The complete captured text is displayed before the separate one-use
`ACTION_SEND` / `text/plain` chooser. Dispatch uses exactly those displayed bytes.
Cancel, dismiss, replacement, pause, destruction and the first dispatch retire
the old callback. A failed launch requires a new review before another attempt;
there is no automatic retry. Capture observes UI state and probes no filesystem
health; `READY` does not certify durability, and `SYNTHETIC_LOADED` does not certify
prices, freshness or production availability.

The component writes no report file, queue or persistent collection preference.
Retiring its callback revokes dispatch authority; it promises no secure memory
erasure. A selected external app controls any transmitted or retained copy.
The [diagnostic guide][diagnostics-guide] and [privacy disclosure][privacy] retain
that boundary. The separate [17.04 Help record][support-record] does not turn the
chooser into a monitored support endpoint or an incident-response agreement.

## Minimum future categories and excluded content

For a separately approved implementation, start with the failure class, a fixed
operation stage and a finite outcome/reason category supplied by its adapter.
Keep observed failure, unavailable input, disabled collection, cancellation,
rejected stale callback and unacknowledged delivery distinguishable. Classify
before a sink; do not serialize an object and attempt to scrub it afterward.
Use existing typed reasons where applicable, with an explicit safe unknown branch
for unmapped cases. An unrecognized exception message is never a new category.

Additional context should be limited to approved app/OS/environment and public
artifact version bindings from the matrix. Introduce each category only when it
helps the accepted responder choose a concrete action. Review cardinality and
bounded size before implementation. A correlation mechanism, count or timing
field needs a stated purpose and collection/retention review; none is silently
authorized by calling it anonymous. These categories are not implemented report
fields, a frozen-v1 extension or a new transport format.

Exclude draft/build IDs and revisions, titles, objectives, workload, selections,
component identities, query text, private dataset/endpoint references, file paths
and bytes, URIs, credentials, tokens, account/user/device identifiers, IP addresses,
raw request/response bodies, exception messages, stack traces, logs and arbitrary
metadata. Also exclude hashes of private content, bundles, requests and selections:
hashing does not make a stable private identifier safe. Source-owned public
artifact hashes require their own approved projection; a raw model's `toString()`
is never that projection. Existing incidental-string redaction work in
[PR #685](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/685) remains its
own change and supplies no collection permission or operational acceptance here.

## Admission, retention and actionable routing

Automatic collection remains deferred under 01.05. Before enabling any diagnostic
or analytics SDK, sink or service log flow, accept the following concrete inputs:

| Required input | Decision and verification needed |
| --- | --- |
| Purpose and collection control | Enumerate each signal/field, producer and recipient, why it is needed, its enabled environment, and the disclosure/control that applies. Check SDK initialization, startup/background behavior and denied/withdrawn collection; core local use must remain available. |
| Destination and access | Identify the actual destination, processor/subprocessor boundary, accepted responder and affected data/service receiver. Define least-privilege reader/export/admin access, access audit and credential custody. An existing public issue tracker is not automatically an approved telemetry sink. |
| Retention and deletion | Set explicit expiry and deletion behavior for each sink, local buffer, export and backup, including exceptions and their approval. Verify expiry and access revocation. No telemetry retention duration or indefinite default is selected here; local draft retention is a different policy. |
| O1–O3 | Obtain actual operating/recovery commitments; authorized account/location/cost and data categories; separate roles, environment isolation, secret/log qualification and exercised credential recovery. A hosting design alone supplies none of these receipts. |
| O4–O5 | Qualify backup/restore and deletion/authority reconciliation; receive the exact canonical service/data interfaces, artifact rights and affected-consumer handoffs. A fixture drill cannot admit production data. |
| O6 and response | Bind the deployed image/runtime/migrations and signal configuration; exercise delivery to the agreed destination and obtain the actual responder's acknowledgment and action. Keep health checks and alert response separately evidenced. |

For each admitted alert, the receiving record must identify its producer/version,
sanitized context, actual responder and escalation path, investigation action,
recovery/runbook link, and acknowledged handoff. Thresholds, aggregation windows,
sampling, deduplication limits and response objectives require measured operating
input and owner acceptance. This design assigns no numbers, people or addresses.

Any future dashboard should display collection coverage and freshness alongside
the observed count/rate. No signal, a disabled source, an unavailable receiver or
an unknown denominator cannot become a green health indicator or a zero failure
rate. Label controlled synthetic observations separately from real operational
observations. Distinguish successful processing from successful alert delivery.

## Verification plan and evidence boundaries

The following cases are planned work, not test results from this document.
Use disposable controlled inputs and an explicitly qualified test destination;
record the actual source/package, environment and input versions before a run.

| Case | Required observation and retained evidence |
| --- | --- |
| Crash and ANR | In an authorized controlled build, trigger each separately. Verify the expected platform event, admitted finite classification and exact app/OS identity. Inspect every emitted payload/sink; a generic handled exception must not count as a crash or ANR. |
| API and catalog failure | Once an actual adapter is admitted, exercise unavailable transport, rejected response and failed catalog admission/activation independently. Retain old admitted pins where applicable; verify missing API/catalog identity stays unavailable and does not borrow a successful/newer version. Current fixture failure tests qualify only the local branch. |
| Pricing and evaluation | Supply missing rates, unsupported/mismatched versions, invalid estimate bindings and withheld evaluation inputs. Verify missing rate is not zero, partial is not complete, unknown is not pass, and no external metric or production evidence is fabricated. Preserve distinct cancellation and engine-failure outcomes. |
| Sharing and receiver acknowledgment | Inspect exact preview-to-dispatch equality and safe handler failure. Then, only for an admitted route, follow a controlled signal through actual receipt, deduplication and responder action. Record chooser launch, receiver receipt and human/operating acknowledgment separately; test a missing or rejected acknowledgment. |
| Duplicate, retry and recovery | Replay the same controlled occurrence, a delayed duplicate and a distinct occurrence. Verify the approved bounded deduplication policy suppresses only duplicates, preserves separate incidents and exposes delivery failure. Test interrupted acknowledgment and allowed retry without a second incident or silent content outbox. No such automatic retry exists in the current report. |
| Opt-out and lifecycle | With collection absent/denied/withdrawn, inspect startup, foreground/background, offline/reconnect and recreation. No deferred upload may escape a changed control. For the current report, cancel/replace/pause/destroy and replay old callbacks; only a fresh visible resumed review can authorize another dispatch. |
| Privacy, access and expiry | Seed private text, paths, URLs and secrets in inputs and exception text; inspect report, Intent, logs, crash/SDK output, transport and receiving storage. Verify absence before every sink, least-privilege denial, audit, expiry/deletion and disclosed exceptions. Include external-copy limits without claiming recall. |
| Incident handoff and recovery | The actual accepted responder locates the failing release and admitted inputs, follows the relevant investigation/recovery instruction, and records acknowledgment and disposition. Exercise an unavailable responder/destination and the agreed escalation path; an unattended dashboard is insufficient. |

Retain three distinct evidence types: source/allowlist review; controlled local or
synthetic execution with exact artifacts and observed failures; and genuine
operational delivery with accepted receiver acknowledgment. Existing tests in
[LocalDiagnosticsActivityTest][diagnostics-tests] provide a place to inspect the
current report controls, not evidence that this future plan ran. Installed-device,
network, service-retention and release claims require their own observations.

## Three-criterion partial handoff

The canonical intended acceptance record remains
`docs/delivery/android/work-packages/17.01.md` in `Pick-Your-AI/pickyour.ai`.
This design does not create or relocate that record. Use partial PR references;
all three live criteria below remain incomplete pending implementation, exact
execution evidence and the required receiving review.

| Exact #44 criterion | Contribution here | Remaining acceptance |
| --- | --- | --- |
| Privacy-preserving crash/ANR/error/reliability signals tied to app/API/catalog versions; alerts have owners and actions. | Seven producer/caller boundaries, version matrix, minimum categories and admission/routing requirements | Approved collection/retention/access, working signals and dashboards/alerts with actual accepted owners and actions |
| A release-specific failure can be located by app/API/catalog version and reaches the documented responder with enough sanitized context to act. | Exact identity distinctions and controlled delivery/deduplication/handoff plan | Actual qualified release failure, preserved version bindings, sanitized receiver receipt and responder acknowledgment/action |
| Observability behavior matches privacy disclosures and approved collection controls; disabled or unavailable telemetry is not presented as evidence of health. | Current ten-field report preserved; proposed categories separated; absence/health, privacy and control verification rules | Executed disclosure/control, payload/traffic, lifecycle, access and retention checks for the enabled scope; affected data/service and support/operations acceptance |

The receiving handoff must attach this design's delivered commit, actual
implementation/payload decisions, original negative/recovery evidence and each
reviewer's scoped disposition. Source review may accept this preliminary design;
it cannot substitute for the working operational outcomes in #44.

[inventory]: security/DATA_INVENTORY_AND_THREAT_MODEL.md
[service]: SERVICE_BASELINE.md
[preview-build]: ../preview/build.gradle.kts
[manifest]: ../preview/src/main/AndroidManifest.xml
[bootstrap]: ../preview/src/main/kotlin/ai/pickyour/android/preview/EnvironmentBootstrap.kt
[environment]: ../core/src/main/kotlin/ai/pickyour/core/configuration/ClientEnvironmentAdmission.kt
[analysis]: ../core/src/main/kotlin/ai/pickyour/core/analysis/AnalysisCoordinator.kt
[activity]: ../preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt
[fixtures]: ../preview/src/main/kotlin/ai/pickyour/android/preview/CatalogFixtureData.kt
[catalog-retention]: ../preview/src/main/kotlin/ai/pickyour/android/preview/CatalogRetainedSnapshots.kt
[pricebook]: ../core/src/main/kotlin/ai/pickyour/core/pricing/RecordedPricebook.kt
[price-browser]: ../preview/src/main/kotlin/ai/pickyour/android/preview/CatalogPriceBrowser.kt
[cost-section]: ../preview/src/main/kotlin/ai/pickyour/android/preview/CostInspectionSection.kt
[cost-adapter]: ../core/src/main/kotlin/ai/pickyour/core/pricing/CostEstimateAdapter.kt
[snapshot-section]: ../preview/src/main/kotlin/ai/pickyour/android/preview/SnapshotEvaluationSection.kt
[evaluation]: ../core/src/main/kotlin/ai/pickyour/core/evaluation/RetainedSnapshotEvaluation.kt
[current-inputs]: ../core/src/main/kotlin/ai/pickyour/core/evaluation/DraftCurrentEvaluation.kt
[diagnostics-source]: ../preview/src/main/kotlin/ai/pickyour/android/preview/LocalDiagnostics.kt
[environment-file]: ../preview/src/main/assets/client-environment.json
[diagnostics-guide]: LOCAL_DIAGNOSTICS.md
[privacy]: LOCAL_PRIVACY_DISCLOSURE.md
[support-record]: delivery/android/work-packages/17.04.md
[diagnostics-tests]: ../preview/src/test/kotlin/ai/pickyour/android/preview/LocalDiagnosticsActivityTest.kt
