# Product and scope recommendation

Proposal for Android #20, #24 and #25. Receiving acceptance remains pending; see the
[review package](README.md). This is a scope recommendation, not validated demand
or an assertion that any journey works in an Android application.

## Evidence and intended users

The research inputs are pinned to
[`pick-your-ai-research@7acbefbf4e4e700231e88be272bc89e3a73dde15`](https://github.com/Pick-Your-AI/pick-your-ai-research/tree/7acbefbf4e4e700231e88be272bc89e3a73dde15):

- [Customers](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/7acbefbf4e4e700231e88be272bc89e3a73dde15/research/market/customers.md) explicitly describes desk personas and hypotheses, with no customer interviews.
- [MVP alternatives](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/7acbefbf4e4e700231e88be272bc89e3a73dde15/research/market/mvp-analysis.md) recommends a bounded hybrid decision record for validation. It does not approve an MVP. Research #58 and its substitute prerequisite #66 remain open in the live read.
- [Synthesis](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/7acbefbf4e4e700231e88be272bc89e3a73dde15/research/market/synthesis.md) labels its recommendation provisional and identifies unresolved demand, rights, sizing and pricing evidence.

Use an individual comparing a workload's local and API options as the design
hypothesis. A reviewer receiving that person's record is the secondary hypothesis.
Neither is a validated customer segment. Do not infer Android demand, willingness
to pay, frequency of use or an enterprise buying process from these inputs.

The phone is the planning client. Model execution targets belong to the proposed
stack and may be a separate computer or service. No model inference, hardware
benchmark or account credential is needed on the phone for this preview.

## Primary journeys

These are desk walkthroughs of the proposed behavior, not executed UI tests.

| Journey | Goal and entry | Observable completion | Failure or recovery behavior | Evidence and unresolved assumption |
| --- | --- | --- | --- | --- |
| Start a decision | Home → new local draft; describe workload and constraints | A named draft has workload, deployment choices and an acknowledged durable revision | Interrupted edit does not display a saved acknowledgement until committed; an empty draft remains editable | Research MVP hypothesis; whether mobile entry is useful remains untested |
| Compare options | Open catalog from a draft; compare local and API candidates | Selected versions, sources, dates and differences are visible without inventing missing capabilities | Offline uses the last verified snapshot; no snapshot produces an unavailable state; unsupported fit is unknown | Website preview supplies interaction/reference logic, not real catalog acceptance |
| Review a stack | Open draft summary | Show findings, cost units and uncovered meters, selected evidence versions, and core T6 prerequisites | Partial cost is labelled partial; stale analysis cannot replace a newer revision; no unsupported ready-to-deploy claim | Research #39/#40/#41 and website #15/#16 remain required handoffs |
| Return to a decision | Local library → draft/revision | The selected draft and acknowledged edits survive relaunch; old revisions retain pins | Failed migration/corruption preserves recoverable data and explains the failure; no silent reset | Requires Android persistence and device tests, not browser storage reuse |
| Exchange a decision | Explicit export → Android document/share destination; explicit import → read-only preview | User reviews the disclosure and can save a separate local copy after validation | Reject malformed/oversized/unsupported data before replacing anything; warn that exported copies cannot be revoked | Cross-client envelope/version and privacy review remain pending; no public upload is implied |

For a walkthrough, use a clearly labelled synthetic workload comparing one local
inference runtime/model selection with an API alternative. Leave price, fit and
quality unavailable unless supported by the selected fixture or accepted evidence.
Change context or usage, return to the summary, reopen the draft, and export a
copy. The expected observations above define future tests; this proposal has not
performed them in an Android app.

## Capability and mode boundary

Recommend a local planning preview first. A production release remains gated on an
accepted real-data slice, conformance, security, device results and store readiness.
Do not rename synthetic preview acceptance as real-data or launch acceptance.

| Capability | Preview disposition | Unsupported/unavailable behavior | Condition for later expansion |
| --- | --- | --- | --- |
| Workload, browse, select, compare and local revisions | Core, using labelled fixtures initially | Preserve incomplete inputs; show unavailable versions and evidence | Reviewed catalog/pricebook publication from website #15 and research owners |
| Compatibility | Core display of pass/fail/unknown/skipped and their evidence | Unknown stays unknown; unresolved rules do not pass | Frozen-v1 conformance and approved successor semantics where required |
| Cost | Core units, scenarios, coverage and uncertainty | Missing meters are uncovered, never zero; partial subtotal is not a complete bill | Research #39 acceptance and exact supported rate/meter coverage |
| Capacity/quality | Display accepted versioned evidence only | No estimated benchmark score or fit verdict to fill a gap | Website #16 and research capacity/quality handoffs |
| Save and exchange | Local durable save; explicit reviewed file exchange | No cloud backup, cross-device sync, permalink revocation or remote privacy guarantee | Canonical website #17 service and reviewed cross-client formats |
| Core T6 | Retain guardrail, evaluation, deployment-prerequisite and monitoring-plan fields | Show missing readiness inputs; do not imply execution | Accepted evidence and applicable issue-specific tests |
| T3 retrieval, T4 agents/tools and T5 training | Optional; defer their implementation in the first bounded preview | Existing unsupported configurations may be inspected without discarding their data; cannot claim complete analysis | Separate scope decision, contract support, rights and tests for each tier |
| Accounts, payments and subscriptions | Defer | No sign-in, upsell, purchase or entitlement promise | Demonstrated need plus privacy, billing, deletion and service acceptance |
| Alerts, community and recommendations | Defer | No notification permission or background alert promise; no publishing UI | Separate approved growth scope and corresponding policy/security work |
| Execution or provisioning | Exclude from preview | Export is a plan, not an executable deployment or authorized purchase | Explicit execution scope and threat/credential/cost controls |

| Deployment mode in a plan | Preview treatment | Production evidence gate |
| --- | --- | --- |
| API | Describe request/usage inputs and selected provider/model; no live provider calls | Supported provider versions, request economics and permitted data |
| Managed | Preserve host/model/service selection; no inferred service capacity | Reviewed managed-service capability and regional pricing |
| Self-hosted | Capture separate target hardware/runtime; phone is not the target by default | Matching resource evidence, licensing and deployment prerequisites |
| Hybrid | Compare a bounded local/API decision; mark unsupported relationships unknown | Versioned per-component placement and relationship semantics accepted through #40 |

An interface choice does not resolve the frozen contract's representation gaps.
Do not add a new mode, relationship or outcome enum to make these rows fit. Record
unsupported combinations, retain their input and obtain a versioned contract change.

## Accounts, revenue, analytics and privacy

Recommend guest-local operation without accounts, advertising, analytics SDKs,
remote crash upload, push notifications, paid plans or background source collection
in the preview. Research #63 contains no validated willingness-to-pay result. This
is a conservative product choice, not proof that a business model cannot work.

Local save writes only to app-private storage. A document export is an explicit
disclosure: preview fields, exclude credentials/private annotations by schema and
policy, and explain that a recipient's copy cannot be revoked. Import never
executes content, follows embedded network locators or modifies an existing draft
without a separate confirmation. Android permission and storage details still need
implementation/security review.

There is no remote write in this preview and therefore no unauthenticated write
API. If accounts or guest remote saving are later selected, the server must enforce
object ownership/capability scope, expiry/revocation, resource limits and conflict
semantics independently of the client. A guest identifier is not authorization.

Account sign-out/deletion UI is absent while accounts are absent. Local deletion
must remove the selected local data and explain the limit of deleting exported
copies. A later account upgrade must explicitly select what to upload; signing in
must not silently transfer local builds. Add the applicable store privacy,
data-safety, deletion and billing evidence before enabling those capabilities.

## Acceptance disposition

The following tables retain the exact live criteria. Evidence is the proposal
above; Android behavior, product validation and receiving review are not passed.

### Issue #20

Canonical record: `docs/delivery/android/work-packages/01.01.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Document target users, mobile context, first-release jobs and success measures; distinguish validated evidence from assumptions. The Android device is the configuration client, not automatically the selected inference target. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Every primary journey names its goal, entry point, completion state, evidence and unresolved assumptions. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| A recorded decision distinguishes first-release jobs from hypotheses; Android-local inference is not implied by hardware comparison. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |


### Issue #24

Canonical record: `docs/delivery/android/work-packages/01.02.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Record core, later and conditional scope across API, managed, self-hosted and hybrid modes; consume Pick-Your-AI/pick-your-ai-research#58/Pick-Your-AI/pick-your-ai-research#66 without claiming their acceptance. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Every capability has a release disposition and an explicit unsupported/unavailable behavior. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| The approved first slice runs from workload to an honest summary; planning/export and execution have separate scope gates. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |


### Issue #25

Canonical record: `docs/delivery/android/work-packages/01.05.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Decide guest-first behavior, optional accounts, monetization, analytics and notifications; enable only the dependent policy/implementation tasks that apply. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Every account, revenue, analytics and notification capability has an explicit disposition and rationale. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Remote mutations/private reads retain an authorization model even when full accounts are deferred; unselected services are absent from launch scope. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
