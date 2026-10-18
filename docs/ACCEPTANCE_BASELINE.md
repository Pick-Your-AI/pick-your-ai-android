# Android acceptance and ownership baseline

Decision date: 2026-10-18. Issue: [Android #47](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47).
Effective event: independent product/Android decision review and normal reviewed
merge, recorded in [01.06](delivery/android/work-packages/01.06.md). This selects
requirements for qualification; it records no device or release pass. Numeric
choices below are explicit engineering requirements or named platform guidance,
never measurements inferred from the project's existence.

## Accepted input and decision

Consume [#34 / 01.03](delivery/android/work-packages/01.03.md) and its transitive
[#24 / 01.02](delivery/android/work-packages/01.02.md) scope, delivered through
[#297](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/297) at signed
`93bebf787a091fe4858c1c70fd07cabefe17170f`. The selected implementation is Kotlin,
platform Views and the canonical local filesystem revision store. The selected
product is an interruptible guest-local client for a bounded public-text planning
job, with J1–J4 core and J5 conditional. The phone is the configuration client,
not an implicitly selected inference runtime.

Choose phone and tablet windows, the existing API 26 engineering floor and a
separately inspected current-platform endpoint, explicit accessible completion and
durability requirements, and a fail-closed evidence sequence. Preserve the
[product decision's](proposals/01-product-scope.md) E1–E6 inputs and A1–A5
hypotheses. Android demand, market share, task-time satisfaction and willingness to
pay remain unvalidated; research #63 is not accepted here. These choices bound
engineering work without claiming an audience percentage, schedule or budget.

## Supported platform target and required witnesses

The selected qualification target is a standard Android phone/tablet application
on API 26 through API 37, with a usable app window at least 320 dp wide and 480 dp
high. This is a target to qualify before publishing support, not a statement that
the current diagnostic runs correctly across that range. Keep the current preview
compile/target SDK 35 pins unchanged. Target SDK and Play submission compliance
remain the separate, freshly checked #54/release decisions.

| ID | Selected environment and coverage | Required evidence and rationale |
| --- | --- | --- |
| E26–E37 | One emulator image for **each** integer API 26–37; compact 360 × 800 dp app window; record image revision, ABI and emulator version | Install/launch and execute J1–J4 plus the mode and interruption cases below on every API. The retained floor avoids an unsupported compatibility-floor change; testing every API avoids treating skipped intermediate releases as observed. Emulator output establishes only its named environment |
| EW35 / EW37 | API 35 and 37 resizable emulator; widths 320, 599, 600, 839, 840, 1199, 1200, 1599 and 1600 dp; heights 480 and 900 dp; portrait/landscape and live resizing | Exercise every core screen and dialog, including errors, at the listed boundary dimensions. These samples cross the current Android width classes [S3]; they are not market-share estimates or a requirement to rewrite Views in Compose |
| P1 | Physical **Google Pixel 6a, Android 17 / API 37**, with exact installed OS build recorded | Primary phone witness for actual touch, TalkBack, Switch Access, interruption, storage recovery and optimized-build performance. It is an explicitly selected older model in Android's current supported Pixel list [S4], not an unmeasured claim that it represents all low-end devices |
| P2 | Physical **Google Pixel Tablet, Android 17 / API 37**, with exact installed OS build recorded | Expanded-window, keyboard, rotation, multitasking, assistive interaction and optimized-build performance witness. The official Android 17 list includes this model [S4]. A tablet check complements the compact mobile context in A5 without inventing a tablet demand study |

P1/P2 are required future witnesses, not equipment known to be available or an
instruction to purchase, flash or reset a device. Access and a qualified operator
remain unresolved. An emulator named after either model cannot satisfy a physical
cell. Different hardware/API combinations need an explicit reviewed matrix
revision with rationale; record a missing cell as missing, never substitute a
convenient device silently. No single OEM matrix certifies all hardware or claims
physical performance on the older APIs. Published results must expose those limits.

At release review, re-read the latest public platform and official support pages.
Add a newer public API and its current reference OS builds through a reviewed
matrix update if API 37 is no longer current. Preview/beta images may add discovery
coverage but do not substitute for the selected public release. Android 17's setup
page identifies API 37 [S4]; ambiguous image names or availability must be resolved
from the actual SDK/device fingerprint before a run is accepted.

At each boundary, record the **actual app window** and insets, not merely the
physical display dimensions. Test 100% and 200% font scale; a smaller visible area
while the keyboard is open must keep the focused input and necessary action
reachable by scrolling. At widths/heights below the declared support window,
require a recoverable layout and preserved state rather than claiming qualified
comfort or inventing new support. Wear OS, TV, Auto and XR are outside the selected
application target. Fold posture, ChromeOS and desktop-specific interaction are
additional qualification work before advertising those form factors; ordinary
window resizing and rotation remain required now.

## Journey, mode and input coverage

Execute this table on every E26–E37 and P1/P2 cell. EW35/EW37 add layout checks for
the same states; they need not repeat every storage fault at every size. Each row
requires a recorded expected/actual outcome, not simply a screenshot of its entry
screen. Test data is public synthetic unless the exact real-data tuple has its own
accepted rights/evidence receipt.

| Journey | Required successful outcome | Required failed, interrupted or unsupported outcome |
| --- | --- | --- |
| J1 Start | Enter a named workload, preserve unanswered required inputs, and acknowledge a local revision only after durable completion | Invalid fields stay editable; interruption before acknowledgment makes no saved promise; failed/full/locked storage never reports saved or replaces the prior acknowledged head |
| J2 Discover/assemble | Inspect exact selected item/version/source and retain it through browsing and draft changes | Empty/offline catalog is visibly unavailable; unavailable historical pin is not replaced; unaccepted source or unsupported tier cannot become an endorsed selection/result |
| J3 Review tradeoffs | Show workload/target identity, versions, fixed outcomes/severity, missing evidence, covered/uncovered cost meters and core T6 prerequisites | Old revision result cannot appear current; unknown/unexecuted fit is not pass; partial subtotal is not a complete bill or zero; canceled change preserves the earlier draft |
| J4 Return | Library opens the intended draft and acknowledged revision; history stays historical; explicit comparison identifies both sides | Corrupt/unsupported entry remains visible with recovery limits; stale authority, uncertain write and canceled reset/delete require explicit reconciliation and preserve recoverable bytes |
| J5 Exchange/revisit — conditional later | Before activation, verify that the product makes no actionable promise of an unsupported import, export, public link or editable remote copy | Any diagnostic entry rejection or private diagnostic text-share is tested at its own boundary, not credited as J5. Activation requires its accepted envelope/projection, native adapters and any service authorization, then cancel/malformed/oversized/newer-input and recipient-delivery checks |

| Mode | Required planning result in J1–J4 | Coverage limit that must remain visible |
| --- | --- | --- |
| API | Preserve provider/model, unknown usage/rates/limits and the provider execution boundary | No credential, live call, free-use or privacy guarantee follows from planning |
| Self-hosted | Preserve the separate runtime computer, model/runtime versions, workload and missing fit/economics evidence | The Android phone, RAM value or local-file storage does not prove inference, throughput or full offline behavior of the planned system |
| Managed | Permit the scoped descriptive comparison with operator/host/region/capacity gaps | Dedicated-capacity, sizing and regional-cost analysis remain unavailable without matching accepted evidence |
| Hybrid | Retain the side-by-side alternatives and disclose missing placement/combined-analysis semantics | Do not silently relax an all-offline constraint or claim exhaustive hybrid validation from frozen self-hosting rules |

M1/API and M3/self-hosted are the bounded detailed-planning paths. Managed and
hybrid retain the accepted descriptive/conditional scope; their unavailable result
is a required case, not a skipped mode. T3/T4/T5 remain optional and later; their
absence cannot remove core T6 readiness checks. Account, revenue, analytics and
notification activation remains #25's separately reviewed enable/defer/reject
decision. This baseline grants none of those capabilities.

The input matrix includes: no local work; one acknowledged draft; pending edits;
two distinct drafts and retained revisions; corrupt/unsupported local data;
missing, usable and mismatched catalog pins; absent, partial and supported prices;
unknown/failed/unexecuted compatibility and evidence; and each published storage,
history, parser and catalog limit at its boundary and one unit beyond. Record
actual bytes/items/revisions for the chosen dataset. Existing synthetic examples
are contract fixtures, not evidence that the product serves a realistic catalog.
#71 must bind a representative and maximum **accepted** catalog workload before
performance qualification; an unspecified capacity has no passing result.

## Measurable release criteria

All criteria below apply to the selected core scope unless their activation
condition is explicitly stated. `Zero` means zero observed violations in the
declared, fully executed suite, not a statistical claim of impossibility. A missing
artifact, missing required case or skipped required environment fails the gate.

| ID / category | Selected requirement and measurement method | Inspectable artifact / receiving gate |
| --- | --- | --- |
| A01 Structure | Exact source, contract pins, dependency locks and packaged input inventories verify; every required fixture/test name executes once; zero test failures/errors/skips and zero unresolved lint errors. Review existing warnings individually; a warning count alone is no waiver | Source/tree/manifest hashes, complete named test XML, lint report, inventory and guard logs; G1 |
| A02 Data and factual coverage | Every displayed factual model/source/price/resource/quality claim resolves to its accepted item/version/applicability/rights tuple. Replay supported and uncovered cases; zero invented rates, fit, source permission or production promotion from fixtures | Per-claim coverage table, immutable source and handoff receipts, contract/rule/pricebook versions, expected/actual reports; G2 plus G3 display behavior |
| A03 Journeys and modes | Every J1–J4 row and all four mode dispositions above reaches its stated completion or explicit unavailable state, with exact draft/result identity. Execute J5's disabled-state test until its activation gates pass | Scenario trace with UI/state before and after, runtime/device metadata and assertion results; G3 |
| A04 Durable acknowledged work | Zero lost/duplicated acknowledged revisions, silent reset, stale overwrite or false save acknowledgment. Inject failure at every supported write/rename/commit boundary; separately exercise rotation, backgrounding, task removal, process termination, relaunch, reboot and supported upgrade/migration. Compare actual retained revision/head bytes | Fault-injection test output plus installed process/lifecycle transcripts, before/after identities and SHA-256 hashes, recovery UI and migration fixtures; G3. JVM recreation is not a process-kill result |
| A05 Offline and recovery | With network disabled, a usable permitted retained snapshot and acknowledged drafts remain usable; with no usable snapshot, report unavailable. Delayed/canceled/stale callbacks cannot replace newer state. Re-enable connectivity without duplicating acknowledged work | Network-state control, callback/fault cases and installed traces, retained pin comparison and recovery assertions; G3. Any later sync service adds its own authority/conflict tests |
| A06 Accessibility and layout | Every required action and recovery path is reachable and understandable with TalkBack, Switch Access and external keyboard on P1/P2. Touch targets at least 48 × 48 dp; normal text contrast at least 4.5:1, large text at least 3:1 under Android's size/bold definition [S1]. At 100%/200% font scale and every selected window boundary, zero obscured essential actions or lost values; outcomes remain understandable without color | Target-bounds/contrast measurements, semantics/label inspection, complete manual assistive transcripts and screenshots with font/window/locale, including errors; G3. Automated scans do not replace the manual traversal |
| A07 Startup and visible progress | On P1/P2 optimized non-debuggable builds, every recorded cold TTID is below 5,000 ms, warm below 2,000 ms, hot below 1,500 ms. Adopt Android's excessive-startup boundaries [S2] as initial failure ceilings, not a claim of excellent UX. Any operation still incomplete after 2,000 ms exposes truthful progress or a recoverable state [S5]; no endless empty first frame | Separate cold/warm/hot Macrobenchmark JSON and per-iteration traces, TTID plus actual usable-state/TTFD trace; G3. #71 must accept workload-specific full-display and operation budgets before release |
| A08 Frame responsiveness | On P1/P2, each measured J2 list/history traversal and J1 edit/J3 result transition has p95 `frameOverrunMs` at or below 0 and no frame above 700 ms. The p95 no-overrun requirement is a project choice for these small native surfaces; Android supplies the metric and frozen-frame boundary [S2/S6] | FrameTimingMetric distributions and raw traces with actual refresh/deadline configuration; G3. Do not apply a fixed 16 ms budget to every refresh rate or call emulator timing physical performance |
| A09 Resource use | Zero observed crash, ANR or OOM during the declared suite and repeated maximum-workload cycles. No unrequested background upload, polling, wake lock or job for the guest-local slice. Measure peak/settled PSS and heap, storage growth and foreground/idle energy with actual workload and device conditions; numeric resource and regression budgets require the #71 measured-baseline review | Memory/CPU/storage/energy traces, repeated-cycle data and job/network inspection; G3. Missing approved memory, energy or capacity budget is explicitly unresolved, not a pass |
| A10 Security and disclosure | Review actual manifest/exported components, backup rules, packaged dependencies/secrets, local storage, logs and incoming content for the candidate. Zero unauthorized private disclosure/mutation in applicable negative cases; no automatic upload/clipboard write. Explicit diagnostic sharing must match the visible reviewed text and retirement/consent behavior; J5 requires its own source/destination tests when activated | Threat-model revision, manifest/backup/dependency/secret reports, adversarial input and consent tests, actual destination observation if delivery is claimed; G1/G3. Scope follows the selected capabilities, not hypothetical accounts |
| A11 Platform coverage | Every required E/P/EW cell has the declared result; actual latest public API, target behavior, permissions, backup, supported ABI/native-page-size and configuration changes are checked for the candidate. No claim for absent or mismatched cells | Complete matrix with exact image/build/API/ABI/window evidence; #54 platform review and #107 coverage receipt; G3/G4 |
| A12 Distribution | Before release, verify publisher/package identity, signing custody, policy declarations and the actual optimized artifact; install and upgrade the exact track-delivered build, verify retained work and supported behavior, and record explicit release authorization | AAB/APK and signing-certificate hashes without secrets, policy review, store/track/version receipts, installed version/hash/upgrade traces and actual release decision; G4. Upload, approval, rollout and installed verification are separate events |
| A13 Ongoing responsibility | Before any supported release or activated service, each required release/security, data-correction/support and incident/operations responsibility has an actual accepted person/team commitment, escalation route and applicable exercised recovery method | Explicit owner acceptance links, coverage/escalation/runbook and measured drill receipts; G4. The local-only scope needs no invented backend operator, but does not remove publisher/support responsibilities |

English is the initial reviewed copy. Execute number/date/currency presentation
under `en-US`, `de-DE` and `ar-EG`, and layout under the supported pseudo-locales
including RTL. These are formatting/layout cases, not translation support claims.
Localized display must preserve canonical numeric values and exact round trips;
labels still distinguish unknown, unsupported and partial results. #65/label
handoffs govern additional translated-language support.

## Performance protocol and unresolved budgets

For A07, collect 30 measured iterations **per startup mode, per physical device,
per declared dataset** after the harness's recorded setup/warmup. For A08, use 30
repeated deterministic traversals of each named surface and report each journey's
distribution separately. Thirty is an explicitly chosen repeatability sample, not
a population confidence guarantee. Preserve all valid iterations, report p50,
p95 and maximum, and do not turn a failing tail into a pass by reporting a median
alone. Use nearest-rank p95 for the raw startup samples; retain the tool's frame
percentile method/version for frame results.

Use an optimized, non-debuggable, profileable candidate with recorded R8/profile
and compilation mode, exact app/test APK hashes, device OS build, refresh rate,
power/thermal state, background conditions and dataset. Choose and retain the same
conditions for before/after comparisons. Do not suppress emulator, low-battery or
configuration benchmark errors to manufacture acceptance [S6]. An invalid run is
retained with its reason and rerun after correcting that specific environment;
it is not a valid performance pass. This decision authorizes no new paid runner.

TTFD/usable-state completion must include the actual local read, draft identity and
enabled required actions, or an actionable error state. A premature
`reportFullyDrawn` signal cannot satisfy the gate. A07's visible-progress ceiling
does not accept an indefinitely slow operation. #71 must approve its representative
and maximum catalog sizes, full-display/edit/save/catalog latency, memory, energy
and regression budgets from real optimized-build baselines. Network transfer/API
latency applies only when a selected service is activated; a guest-local build
records that conditional absence instead of a fictitious zero-millisecond service.
No monetary budget, download entitlement, hardware purchase or operator schedule
is selected here.

## Evidence sequence and acceptance format

| Gate | Question answered | Evidence accepted | Cannot imply |
| --- | --- | --- | --- |
| G1 Structural validation | Are the exact inputs well formed, reproducible and internally consistent? | Source review, contract/hash checks, named JVM/simulated tests, compile/lint and artifact inventory | Truth of data, source rights, actual installed behavior or store compliance |
| G2 Factual acceptance | Is each claimed datum and its allowed use accepted for this identity/version/context? | Canonical research/source-use, catalog, price/resource/quality and service handoffs with named receiving review | Runtime correctness merely because a record decodes or a research PR merged |
| G3 Application behavior | Does the exact candidate behave as required in each declared environment? | Installed emulator and physical results explicitly distinguished; real accessibility, lifecycle, failure and optimized-performance evidence | Signed store distribution, real recipient delivery, production operation or coverage of untested hardware |
| G4 Live release verification | Was the exact authorized build delivered and does its operating boundary work? | Separate signing/policy, track install/upgrade, rollout authorization, production smoke checks and actual ownership/drills | Acceptance from an upload, approval, staging test or preparatory decision alone |

G1/G2 may progress independently; both applicable inputs must be accepted before a
G3 result can support a real-data product claim. G4 needs every applicable earlier
gate. Synthetic G3 work stays synthetic. A source-identical new candidate can reuse
a clearly scoped existing observation only after exact tree/input/configuration
comparison and reviewer acceptance; retain its original execution revision and
date. Required current-receiving checks and genuinely changed environments still
need fresh results.

Each receiving record must contain: criterion ID and exact expected result; actual
result and limitation; source commit/tree; application/test artifact hashes and
variant; runtime kind (desk, JVM simulation, emulator, physical, store-delivered or
production); device/image/OS/API/ABI and relevant window/font/locale; exact
dataset/contract/source pins and size; commands, scenario steps and raw artifacts;
failed/recovery before/after state; actual reviewer identity/scope and review URL.
Use durable, inspectable receipt links. Missing data is `not run`, `failed` or
`needs input`, with the specific reason, never an empty passing cell. Keep private
drafts, device identifiers, credentials and raw private captures out of public Git;
public synthetic reproduction and sanitized observations must retain the relevant
identity relationship without exposing the original private content.

## Review roles, acceptance and current readiness

The user delegated engineering decisions and reviewed publication in this session.
The ChatGPT Android agent (`/root/android`) accepts preparation, source
reconciliation and scoped verification of this decision. An independent
coordinating agent (`/root`) must actually accept its product/Android decision
review in a published review before delivery. A second source reviewer can add
evidence, but a role label is no review. These are the actual in-session roles;
they do not claim a human approval or an ongoing employment/support commitment.
GitHub assignees and Project fields remain unchanged.

| Responsibility / remaining input | Actual current acceptance | Consequence |
| --- | --- | --- |
| Prepare #47 decision and exact acceptance mapping | Android agent's [intake](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47#issuecomment-6249731051), under the user delegation | Concrete artifact is prepared here; independent receiving review and delivered current-index reconciliation still required |
| Product authority and Android technical review of this baseline | Required independent coordinating-agent review, to be linked at the exact published candidate | Proposed until that actual review and normal merge; no self-approval or implied human signature |
| Device/operator access and manual assistive qualification | No commitment or physical run supplied by this decision | A06/A11 physical cells remain release blockers; #107 and installed-test controllers retain their work |
| Measured resource, operation and regression budgets | Startup/frame requirements selected above; #71's measured resource/full-display/capacity budgets unresolved | A07/A09 performance release qualification cannot pass on debug or emulator impressions |
| Source rights, evidence and production catalog/service handoffs | Existing canonical owners/criteria remain authoritative; no new commitment recorded here | A02/G2 remains blocked for any unsupported real-data claim; #35 retains service/operator selection |
| Publisher/release/security, support/data correction and incident roles | No ongoing person/team acceptance recorded here | A12/A13/G4 remains blocked until actual commitments and applicable evidence are linked; no arbitrary assignee or response SLA |

Current source already has Kotlin and native simulation evidence. For example,
the separately recorded [#307 receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/307#issuecomment-6249631827)
binds 410 core and 151 simulated-native cases to its exact receiving tree and
subsequent signed `74fac80d69c62e1e2de3cc2a6884aa51f763dbcc` delivery. Those historical
checks are not executions of this decision, physical evidence or a full acceptance
run. Later source retains its own new checks and receipts. No row above is marked
qualified by this reference.

Accepted limitations for the initial slice are guest-local operation, explicit
unknown/unavailable results, J5 and later features remaining inactive, English
copy with cross-locale format/layout checks, and the declared device/form-factor
coverage limits. They are deliberate scope choices with tests of honest behavior.
Missing durable correctness, accessible completion, data authority, required device
coverage, performance review, signing/policy or operating acceptance is a blocker,
not an accepted limitation. Any later exception needs an explicit criterion,
affected scope, evidence, accountable acceptance and revised user-facing claim;
it cannot silently relabel an incomplete safety or disclosure check as passing.

No launch date, estimate, hardware/spending commitment or numeric demand target is
made. The full #47 outcome is this reviewed selection, traceable measurement plan
and truthful responsibility/readiness table. Completing its design criteria does
not complete #48, #54, #71, #107, research, service or release work.

## Primary-source inspection

The Android agent read these official sources on 2026-10-18. Their limited facts
inform this engineering decision; they do not certify this app. Revalidate current
platform/store facts before the corresponding execution or release gate.

| ID | Source | Fact used and limit |
| --- | --- | --- |
| S1 | [Android accessibility overview](https://developer.android.com/guide/topics/ui/accessibility/apps), [Views targets/labels](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views), [Views principles](https://developer.android.com/guide/topics/ui/accessibility/views/principles-views) | 48 dp touch targets; 4.5:1 normal / 3:1 large text contrast; labels and assistive interactions. For contrast, large means at least 18 sp regular or 14 sp bold. These facts inform explicit app checks, not an automatic conformance claim |
| S2 | [Startup vitals](https://developer.android.com/google/play/vitals/launch-time), [startup measurement](https://developer.android.com/topic/performance/issues/launch-time), [slow/frozen rendering](https://developer.android.com/topic/performance/issues/render) | Excessive cold/warm/hot TTID begins at 5/2/1.5 seconds; TTID and actual full usability differ; frozen frames exceed 700 ms. A07/A08 choose their stated project acceptance boundaries |
| S3 | [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes), [responsive Views](https://developer.android.com/develop/ui/views/layout/responsive-adaptive-design-with-views) | Dynamic window size matters; width boundaries are 600/840/1200/1600 dp, height boundaries 480/900 dp. The samples select geometry tests for existing Views, without adopting a new UI framework |
| S4 | [Get Android 17](https://developer.android.com/about/versions/17/get), [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk) | Official Android 17 device list includes Pixel 6a and Pixel Tablet; SDK setup identifies API 37. Device possession, exact installed builds, release policy and test success require separate evidence |
| S5 | [Core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality) | Inspect core flows, interruptions, state, accessibility, latest platform and privacy; long startup should expose progress. Only applicable capabilities are selected; this baseline is not a claim that every generic checklist item describes this app |
| S6 | [Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) | Use physical optimized/non-debuggable measurements with JSON/traces; preserve benchmark configuration errors. Frame overrun measures lateness against the real deadline (API 31+), separate from startup and raw CPU duration. The chosen devices satisfy the metric API floor; no emulator timing is accepted as physical performance |
