# Android users, journeys and first-slice decision

Decision date: 2026-10-18. This develops the existing Android #20/#24 proposal under
the maintainer's explicit delegation of product and engineering decisions. The
decision takes effect through independent receiving review and the normal reviewed
merge. The original proposal remains in Git history; its path is retained to avoid
a second competing brief. Criterion records: [01.01](../delivery/android/work-packages/01.01.md)
and [01.02](../delivery/android/work-packages/01.02.md).

Select guest-local planning for one public text workload: describe the job, compare
a bounded model/runtime or provider choice, inspect an honest summary, and return
to an acknowledged local draft. The Android phone is the configuration client;
inference hardware is a separately selected computer or service. This decision
does not select on-phone execution, a production launch, or a market claim. `Core`
below means selected release scope, not implemented or accepted app behavior. The
existing synthetic diagnostic remains labelled as such.

## Evidence, observations and assumptions

Research was inspected at
[`ce388d7d4d38539eeccc5a0a8e740e74a17d96d2`](https://github.com/Pick-Your-AI/pick-your-ai-research/tree/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2).
The older proposal's `7acbefbf4e4e700231e88be272bc89e3a73dde15` pin remains historical.
These bounded readings do not establish Android customer validation.

| ID | Exact source | Input consumed | Limit retained |
| --- | --- | --- | --- |
| E1 | [Customers](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/market/customers.md), [customer-validation status](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/market/customer-validation.md) | Existing individual-developer/local-AI and reviewer roles are desk hypotheses; user, buyer and approver can differ | No interviews, Android demand, frequency or willingness to pay is established; #63's study remains separate |
| E2 | [MVP alternatives](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/market/mvp-analysis.md), [synthesis](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/market/synthesis.md) | A bounded local/API decision record is a candidate research direction | Research #58 is not accepted by this Android choice; its #66 prerequisite and other literal criteria remain |
| E3 | [Substitute observations and protocol](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/market/substitute-validation.md) | The 2026-10-18 guest Flocci test detected contradictory constraints and suggested alternatives. Its tested bookmark retained a catalog tool rather than the whole decision; the tested flow did not expose decision export/share | This is one bounded observation, not a general absence claim. It supersedes older E2 statements that these guest checks were unavailable. No unique contradiction-detection or paid-demand claim follows; #66 remains open |
| E4 | [PCPartPicker transfer](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/market/pcpartpicker.md) | Component comparison, explanations and versioned revisit are product hypotheses | The note did not observe those flows directly; Android does not relabel them as observed behavior or require new access to write this brief |
| E5 | [T0 workload scope/summary](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/T0-configuration/workload-use-case.md), [deployment scope/definitions](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/ce388d7d4d38539eeccc5a0a8e740e74a17d96d2/research/T0-configuration/deployment-mode.md) | Separate task requirements from methods, preserve unanswered inputs, and distinguish runtime operator/capacity from billing when discussing modes | These notes include proposals and open contract decisions. Per-component placement and new input axes do not become frozen-v1 fields here |
| E6 | [Apple journey map at `fd58e522`](https://github.com/Pick-Your-AI/pick-your-ai-apple/blob/fd58e5223dd51a9b49e9c99123736d0af5248dcc/docs/proposals/issue-20-journey-map.md), [accepted decision receipt](https://github.com/Pick-Your-AI/pick-your-ai-apple/issues/20#issuecomment-6244060433) | Reuse confirmed-versus-pending work, explicit recovery identity, keyboard interruption and reviewed disclosure boundaries | Apple acceptance covers its decision artifact, not Android behavior, participants, service authorization or this receiving review |

No raw customer data or external site content is copied into the app. Proposed
mobile roles and benefits remain assumptions even when technical failure modes
are supported by source inspection.

| ID | Design hypothesis | Decision and falsifying alternative |
| --- | --- | --- |
| A1 | An individual developer or local-AI enthusiast sometimes wants to frame/review a choice on an Android phone | Serve one guest decision at a time. A desktop tool, existing substitute or ordinary document may suffice; no mobile frequency is asserted |
| A2 | A second person needs the reasoning without inheriting edit authority | Preserve a read-only review/export boundary. Demand for sharing is unknown; local planning works without a public service |
| A3 | Versions, sources, uncertainty and consent to relax constraints improve a reusable decision | Show these in the summary. E3 already demonstrates contradiction handling elsewhere; versioned records remain a hypothesis |
| A4 | Recovering confirmed work is the useful interruption promise | Distinguish pending/saving/saved/reconciliation states. Do not promise that unacknowledged input survives every termination |
| A5 | Compact touch input, large text and occasional keyboard use are needed | Keep essential controls labelled and reachable. Usability, TalkBack, device coverage and completion times are unmeasured |

A1 is the primary role; A2 is a conditional recipient role. Enterprise procurement,
team workflows and automated recommendations remain later hypotheses. A
maintainer's choice to build this slice does not accept research go/no-go or demand
gates.

## Mobile context and success measures

Support short, interruptible editing, intermittent connectivity and a small window.
A larger window or keyboard changes presentation, not the selected draft or
inference target. Workload descriptions may be private: the local slice uses
app-private storage without accounts, analytics, automatic upload or model-provider
credentials. Input methods and an explicitly chosen export destination remain
separate disclosure surfaces. Demonstrations use public synthetic text.

Observable engineering completion measures are: identify the intended draft and
last confirmed revision; retain each selected item's exact version/source context;
show unanswered required inputs and unsupported checks; keep missing cost coverage
distinct from zero; and preserve the original draft after canceled or failed
actions. Future app checks must record actual state before/after, artifact and
runtime. This desk review supplies no task-time, satisfaction, conversion or
retention result.

## Primary journeys

| ID / disposition | Goal and entry point | Observable completion | Failure or recovery | Evidence / unresolved assumption |
| --- | --- | --- | --- | --- |
| J1 Start — core | Home → new local draft; frame workload and constraints | Named draft distinguishes answered/unknown inputs; durable revision is acknowledged only after a completed write | Invalid input remains correctable; interrupted or failed write cannot become saved | E1/E5; A1/A4, usefulness of phone entry untested |
| J2 Discover/assemble — core | Draft → catalog → versioned model/runtime or provider options | Selected versions, differences and permitted sources are visible; optional tiers have a disposition | No snapshot means unavailable; missing pinned item is not silently replaced; offline retains only usable permitted cached data | E2/E4/E5; A1/A3, value over substitutes untested |
| J3 Review tradeoffs — core | Draft → honest summary | Workload, target, versions, outcomes, missing evidence, cost coverage and core T6 prerequisites are distinguishable | Another revision's result is not current; partial subtotal, unknown fit and unexecuted checks remain visible | E2/E3/E5; A3/A5, comprehension untested |
| J4 Return — core | Local library → named draft or retained revision | Correct local identity and acknowledged content recover; historical pins stay historical | Corrupt entry remains visible with recovery limits; conflict/uncertain write requires reconciliation; no silent reset or stale overwrite | E6 and architecture source inspection; A4/A5, installed recovery unqualified |
| J5 Exchange/revisit — conditional later | Explicit document import/export or independently authorized shared-record route | Recipient inspects supported pinned snapshot; a new editable local copy requires a separate action | Cancel/failure preserves active draft; reject malformed/oversized/newer input; disclose unavailable dependencies and irrevocable recipient copies | E3/E6; A2/A3, cross-client envelope and service receipts remain activation prerequisites |

J5 remains a documented primary goal with an explicit release disposition. The
first slice ends at J3/J4 and promises no public link or import/export action before
its conditions are met.

## Recorded desk walkthrough — 2026-10-18

The Android decision agent walked these state transitions against the tables,
pinned inputs and native source at
[`952eb015c391971ca4529ede2c1d99de320173c1`](https://github.com/Pick-Your-AI/pick-your-ai-android/tree/952eb015c391971ca4529ede2c1d99de320173c1).
This is an executed desk review of a design, not participant validation, prototype
execution or an installed-app test. Results are decisions and remaining inputs;
no successful app action or screenshot is invented.

Common scenario: plan an assistant that summarizes short public paragraphs already
provided in a prompt. Compare an open-weight model/runtime on the person's separate
16-GB-RAM computer with a provider alternative. RAM is a synthetic scenario
constraint, not fit evidence. No vendor/model is recommended; `cat.model.fixture`
and `cat.inference_engine.fixture` in the [frozen catalog](../../contracts/v1/examples/catalog.json)
are identity examples, not a qualified stack. Price, quality, latency and throughput
remain unestablished. Retrieval, tool execution and training are unnecessary for
this task.

| Case | Steps walked | Recorded result / missing-decision disposition |
| --- | --- | --- |
| W1 Start/interrupt | J1: enter task, leave volume unknown, consider separate computer, interrupt before and after acknowledged save | Pending text is not guaranteed; confirmed work must reload by identity. Select A4. No Android-local inference is inferred; actual storage/device proof remains implementation work |
| W2 Discover/assemble | J2: inspect fixture model/runtime and provider alternative; lose connectivity before/after snapshot load; encounter missing pinned version | Preserve selection and show unavailable data. Missing production catalog/source acceptance is a data gate, not an invented recommendation or replacement |
| W3 Review/change | J3: inspect absent resource/quality evidence and uncovered meter; change workload and revisit summary | Show missing evidence, partial coverage and stale-result boundary. The diagnostic's separate recorded fixture reports are not analysis of the edited draft; current-draft integration remains implementation work |
| W4 Return/recover | J4: reopen A after viewing B; contrast old revision, corrupt entry, failed save and uncertain acknowledgment | Require explicit identity and reconciliation; preserve recoverable data. Source inspection does not prove migration/recovery. Large-text and keyboard checks remain real-UI work |
| W5 Exchange/revisit | J5: consider export, cancel, then consider newer/invalid import and unavailable historical dependency | Select conditional later. No initial public-upload promise. Accept format/projection/limits and remote authority before activation; cancellation/rejection cannot overwrite A |

## Capability and release boundary

Qualification of the selected core behavior and exact permitted data remains
necessary for a production-capable slice. The diagnostic uses fixtures; its
recorded reports remain separate from current-draft analysis. No implementation
task is claimed complete by this scope decision.

| Capability | Release disposition | Unsupported/unavailable behavior | Exit evidence |
| --- | --- | --- | --- |
| Workload, browse, T1/T2 selection/comparison | Core bounded local/API choice | Preserve incomplete/invalid input and unavailable pinned selections; label fixtures | Integrated workload-to-summary checks and accepted catalog tuple for any real-data release |
| Compatibility explanations | Core outcomes, separate fixed severity and provenance | Unresolved/unexecuted checks cannot pass; disclose frozen applicability gaps without changing the outcome enum | Frozen-v1 conformance; research #40 acceptance for successor semantics |
| Costs and assumptions | Core units, covered/uncovered meters and dated inputs | Missing rate/quantity is unavailable, never zero or a complete bill; no guessed currency conversion | Research #39 and exact supported rate/meter coverage for the selected source/use |
| Capacity/quality | Conditional display of accepted, applicable versioned evidence | No estimated rank or fit verdict fills a gap; decoding a record is not empirical validation | Resource/quality handoffs and canonical website #16, with exact identity/applicability |
| Local save/library/revisions | Core explicit acknowledged writes | Separate corruption, full/locked storage, conflict, cancellation and uncertainty; no cloud-backup/sync promise | Storage/migration/fault and installed lifecycle checks on supported devices |
| File exchange/shared-record revisit | Conditional later, J5 | No actionable import/export/share promise before support; rejection preserves active draft | Reviewed cross-client format/projection/limits and document adapters; website #17 and canonical service authorization for remote routes |
| Basic T6 guardrail, evaluation, deployment prerequisite, monitoring and cost-policy planning | Core visibility and honest readiness summary | Missing prerequisites stay missing; recorded evidence retains original identity; no execution/production-ready claim | Applicable T6 records and integrated summary checks; execution separately gated |
| T3 retrieval, T4 agents/tools, T5 training | Optional, later breadth | Omission retains existing data and required-foundation checks. Unsupported imported tiers have no complete-analysis claim | Explicit tier scope, contract support, source rights and negative/recovery checks; consume actual #58/#66 evidence |
| Accounts/payments/subscriptions | Later, absent initially | No sign-in, purchase, entitlement or silent upload | Separate #25 disposition and policy/service/billing/deletion implementation |
| Analytics, remote crash upload, notifications, community/recommendations | Later, absent initially | No telemetry, alert, publishing or personalized recommendation promise | Separate product evidence, policy and implementation decisions |
| Provisioning, inference, model downloads or purchases | Excluded initially | Plan/export cannot execute content, start a runtime or spend money | Explicit execution scope, credential/cost controls and implementation/release authority |

## Four-mode review

Use E5's operator/capacity distinction as planning language; do not adopt its
proposed wire fields. All four paths can reach an honest planning summary; only
supported applicable evidence can produce technical results. Billing does not
prove capacity or privacy.

| Mode / disposition | Representative desk traversal | Result and evidence readiness | Explicit limit |
| --- | --- | --- | --- |
| API — core bounded planning | M1: W1–W3 with provider-operated shared model; unknown volume; no credential | Preserve missing usage/rate/limit inputs and disclose provider execution | No live call, zero-cost or privacy guarantee; real data needs accepted provider/model/pricing inputs |
| Managed — conditional detailed analysis, core descriptive comparison | M2: same task on provider-operated reserved/dedicated capacity; inspect host/model/region; leave capacity unknown | Summary describes alternative and gaps; hourly price does not prove dedicated capacity | No sizing/regional-cost verdict without matching evidence; unsupported combinations remain unavailable |
| Self-hosted — core bounded planning | M3: W1–W4 with user-operated runtime on separate 16-GB computer; inspect context/concurrency and missing resource evidence | Fit, performance and full economics remain unknown; retain target separately from phone | RAM alone does not prove execution; self-hosting does not establish local-only data flows |
| Hybrid — conditional integrated analysis, core side-by-side alternatives | M4: retain local/API alternatives, then consider mixed placement plus an all-offline constraint | Preserve contradiction for review and missing placement/evidence semantics; do not silently relax the constraint | Frozen self-hosting rules do not cover every hybrid placement. Preserve actual recorded outcomes and separately disclose unsupported coverage; no new enum or exhaustive-validation claim |

Desk result: every row reaches a summary of supported evidence or explicit gaps.
M1/M3 bound the first detailed planning slice; M2/M4 do not silently expand it into
managed-capacity or integrated hybrid validation. T3/T4/T5 stay later in every row.
#58/#66 inform this decision without closing research issues or certifying their
protocol, demand, source rights or deployment feasibility.

## Accounts/privacy decision for #25

The separately reviewed [01.05 decision](../delivery/android/work-packages/01.05.md)
develops the earlier #25 recommendation under the same delegated project authority.
It selects free guest-local J1–J4 planning and gives every account, monetization,
analytics and notification capability an enable/defer/reject disposition.
Accounts/signup redirects, synchronization, payments, analytics, automatic remote
crash upload and notifications remain absent initially. Conditional J5 and core T6
retain their existing dispositions.

The #25 record includes the guest, share, upgrade, logout, deletion, consent,
notification and purchase desk cases; current official platform-policy inputs;
H1–H5 demand/value unknowns; and each dependent task's activation boundary.
It also consumes research #66's later accepted bounded result. E2/E3 above remain
dated observations at their original revision; their earlier pending/open-#66
statements do not override that later accepted receiving receipt.

Guest mode grants no server authority. Later private reads and mutations require
canonical principal/object scope, distinct creator/viewer powers, expiry/revocation,
resource limits and conflict/reconciliation semantics. Account upgrade must
explicitly choose records and prove both authorities where ownership changes.
Export discloses a copy; deletion of the original cannot revoke recipient copies.
An unavailable support destination remains unavailable even when local diagnostic
review and deliberate sharing are implemented.

[ADR 0003](../decisions/0003-android-25-acceptance-record.md) explicitly places only
the 01.05 record in Android after independent receiving review and normal merge,
preserving its original website path as history. The earlier #20/#24/#34
acceptance remains separate. #25's exact delivered record and receiving receipt
must enter the strict current ledger before its own closure.

| Exact #25 criterion | Concrete decision record |
| --- | --- |
| Decide guest-first behavior, optional accounts, monetization, analytics and notifications; enable only the dependent policy/implementation tasks that apply. | 01.05 selected baseline, disposition table and D1–D10 |
| Every account, revenue, analytics and notification capability has an explicit disposition and rationale. | 01.05 capability rationales, H1–H5, P1–P5 and re-entry conditions |
| Remote mutations/private reads retain an authorization model even when full accounts are deferred; unselected services are absent from launch scope. | 01.05 six authority/lifecycle requirements and D3–D7; no deployed service claim |

## Receiving decision and handoffs

The Android decision agent prepared the synthesis, choices and desk review under
delegated authority. An independent coordinating agent must review the actual
content in the product-decision and Android technical-review capacities, including
unsupported assumptions and all nine #20/#24/#34 criteria across this package.
Record actual agent identity and exact published artifact/review links; no human
assignment, date commitment or operating responsibility is invented.

After that reviewed delivery, [01.01](../delivery/android/work-packages/01.01.md)
supplies #24's exact #20 input and [01.02](../delivery/android/work-packages/01.02.md)
supplies #34's scope matrix in the same versioned package. App qualification,
participant research, production data, services and release retain their own
criteria; they are not extra criteria for these two decision records.
