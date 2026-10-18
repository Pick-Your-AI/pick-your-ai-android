# Service boundary and acceptance recommendation

Proposal for Android #35 and #47. See the [scope](01-product-scope.md) and
[architecture](02-native-architecture.md) proposals. No hosting account, production
endpoint, operation owner or device qualification is established by this document.

Implementation review baseline (2026-10-18): signed Android main
[`2f7973c5ec961dd4e092ffddeecbc22a0763a30a`](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/2f7973c5ec961dd4e092ffddeecbc22a0763a30a).
The accepted [native architecture](02-native-architecture.md) uses Kotlin, platform
Views and app-private filesystem stores. The implementation and historical checks
below are inputs to the still-open service and readiness decisions.

## Preview and production boundaries

Retain a local-only preview for accepted J1–J4 with labelled fixtures and app-private
filesystem storage. It requires no Android-specific backend or cloud account.
User-initiated file exchange belongs to conditional J5; native file adapters and
their installed journey still require separate acceptance. The preview does not
satisfy production catalog publication, permalink, remote backup or cross-device
sync work.

The website remains static on its existing host. A static JSON artifact can be read
there after authorized publication, but it is not an authenticated write API or
transactional database. Do not place a Node service in the shared static hosting
environment or create another shared-service implementation in Android/research.

For the later canonical shared service, recommend one modular TypeScript API with
PostgreSQL transactions and immutable published artifacts. This reuses the project's
current server-side language experience and supports compare-and-swap revisions.
It does not imply reusing the leaderboard's contributor data, permission model or
database. Website #15/#16/#17 and research #39/#40/#41/#42 retain their authorities;
Apple #23 receives the same service interfaces without a competing backend.

| Option | Assessment | Disposition |
| --- | --- | --- |
| Existing static website alone | Suitable for frontend/assets; cannot enforce private object access or multi-record write transactions | Retain for current website; insufficient for stateful requirements |
| Modular API + managed PostgreSQL + versioned artifact storage | Explicit transactions, revision checks, migrations and recoverable artifact pins; requires an operator, backups and service authorization | Recommended production shape, pending canonical owners' acceptance |
| Separate per-client backends or microservices per capability | Duplicates policy and operations before the shared boundary is stable | Reject for the first shared service |
| Client-direct managed database/auth SDK | Can reduce some server work, but policy, migrations, offline conflicts and portability still need evidence; does not directly reuse existing semantics | Do not adopt without an evidenced advantage and shared-owner review |

No provider, region, monthly price or recovery-time target is asserted without
current capability, rights and cost evidence. A production host selection requires
an available account, approved data location and an accepted operating owner.
Those are explicit open outputs of #35, not hidden assumptions. The reference
shape is concrete enough to review but does not close the hosting decision.

## Responsibility, authority and recovery

| Responsibility | Preview behavior | Later service gate |
| --- | --- | --- |
| Draft/revision/head transaction | `LocalDraftStore` filesystem protocol over `VerifiedArtifactCache`, with expected-head checks and distinct `Saved`, `Failed` and `Uncertain` outcomes | Server-side atomic commit plus expected-revision check and idempotency receipt |
| Catalog publication | Bundled synthetic input; no production promotion | #15/#41 review, immutable manifest and hashes, atomic promotion and rollback pointer |
| Resource/quality evidence | Missing data stays unavailable | #16 and accepted exact-version evidence; no generated fit claim |
| Remote save and private reads | Not exposed | #17 ownership/capability authorization checked by server, not UI |
| Share and revocation | Conditional J5: an exported copy would remain with its recipient; native file exchange is not established by this preview evidence | Server capability scope, expiry/revocation, sanitized projection and rate limits |
| Backup/restore | No promise of cloud backup; recovery and device-backup policy need testing | Encrypted backups, operator-approved retention and isolated restore drill with measured results |
| Credentials | No provider/account credentials in fixture preview | Server-side custody/rotation and least privilege; do not embed keys in APKs |
| Monitoring/incidents | Local diagnostic preview only; no automatic upload | Named responder, sanitized signals and exercised runbook under #44/#92 |

The local [store protocol](../../core/src/main/kotlin/ai/pickyour/core/drafts/LocalDraftStore.kt)
and its [fault tests](../../core/src/test/kotlin/ai/pickyour/core/drafts/LocalDraftStoreTest.kt)
distinguish failure before pointer activation from uncertainty after activation was
attempted. A pre-activation failure preserves the previously acknowledged head.
`Uncertain` means the new head may already be durable: reopen and verify before
retrying, without reporting a successful save or assuming the old head survived.
The caller must provision a durable app-private root and ancestors; directory sync,
atomic rename, process termination and power loss still need Android filesystem
qualification. JVM fault injection does not supply that installed-device evidence.

For the proposed service, a commit followed by a lost response must retry the same
idempotency key without creating another revision. A bad catalog release must
permit an active-pointer rollback without rewriting history. A database restore
must be tested in isolation and reconcile artifact identities before serving
traffic. These remain required service invariants awaiting operating evidence.

## Measurable acceptance without invented measurements

| Area | Required check and evidence | Current disposition |
| --- | --- | --- |
| Journeys and data | Execute workload → select → evaluate → estimate → save → compare with accepted catalog/contract pins and fixture disclosure checks; exercise conditional J5 sharing when accepted | Native Kotlin/Views preview and named core/Activity-simulation checks exist; full installed journey and native file-exchange acceptance remain outstanding |
| Durability | Kill/relaunch after acknowledged edits; rotation, backgrounding, task removal, reboot, failed writes and every supported migration | JVM failure/reopen tests and simulated Activity recreation exist; retain separate installed results for each required interruption, migration and filesystem case |
| Conformance | Golden/negative fixtures for all supported kinds; cycles, optional tiers, decimals, unknowns, version mismatch and stale responses | Kotlin core has frozen-fixture and negative tests; map each required kind and native integration to its exact-source evidence |
| Accessibility | Automated semantics plus manual TalkBack, focus, large text, contrast, RTL and keyboard, including failure states | Native control/label source and selected simulated checks exist; the cited evidence does not establish the manual installed matrix |
| Security/privacy | Manifest/exported-component review, no secret-bearing backups/logs, malformed imports, malicious links, permission denial and auth isolation where applicable | The manifest requests no permissions, disables backup/cleartext traffic and exports only the launcher; core negative tests exist, while installed local-data/privacy and applicable integration checks remain required |
| Performance | Measure cold/warm start, frame timing, memory, energy and representative catalog size on selected devices and a signed optimized build | Set decision thresholds before assessing results; no fabricated baseline or budget |
| Device coverage | Select min/latest supported API, at least relevant compact/expanded windows, rotation and physical low/mid/high capability targets based on intended users | Exact API/device model list and commitments remain open under #47/#107 |
| Distribution | Publisher/package/key custody, signed AAB, policy review, track-delivered install/update and release approval | No publisher evidence, signing custody or Play Console release work performed |
| Operations | Real-data freshness/correction, restore, alert delivery and response, signed installed-release verification | No accepted operations owner or production service |

Evidence entry points are the [core foundation](../CORE_FOUNDATION.md),
[preview implementation and validation record](../ANDROID_PREVIEW.md), and the
[historical #284 receiving receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/284#issuecomment-6248549786)
for 403 core and 88 simulated Activity tests at `cf36542d4eaeb3c3d48cf104851320481aac0a17`.
The [separate installed-app attempt](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255#issuecomment-6249661795)
for source `f180a53228b3206445328685c5011ccfd77f921d` failed its application-readiness
barrier before target installation; all four installed cases were `not_run`.
Each result applies to its recorded checkout, not a fresh execution of this review
baseline or a complete device/release qualification.

Do not claim support for an OS/device merely because a framework can target it.
The compact/expanded categories are test-planning dimensions, not an approved
support matrix. The Android lead and product authority must record exact devices,
API bounds and test dispositions before #47 is accepted. Research #63 remains a
missing demand input; any numeric performance target must be explicitly chosen
and justified before measurements are used to claim success.

Required review/operating roles are product decision authority, Android technical
lead, shared-service owner, release/security reviewer and incident responder.
No person is assigned by this proposal. The live issues are unassigned; obtain
their actual commitments and put accepted ownership in GitHub. Absent commitments
remain gaps even if code or documentation later passes a check.

## Release gate sequence

1. Accept scope, architecture, service boundaries and receiving ownership. Record
   handoffs at the existing website acceptance-record paths without changing its
   automatic deployment behavior.
2. Prove the local fixture workflow and failure cases. This is preview evidence.
3. Integrate an accepted rights-cleared real-data slice and canonical service outputs.
4. Qualify supported devices and the signed candidate; resolve security, privacy,
   store and operational findings.
5. Separately record internal/closed testing, submission, approval, authorized rollout,
   clean install/upgrade of the delivered production build and operating drills.

The later stages are not inferred from earlier stages, and no stage is passed by
this proposal. Conditional growth issues remain deferred until their own scope
decisions and evidence are accepted; they are not closed as implemented.

## Acceptance disposition

### Issue #35

Canonical record: `docs/delivery/android/work-packages/01.04.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Choose the minimum production API/data-store deployment and operations owner; distinguish the existing static GoDaddy site from a stateful backend. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Every required stateful responsibility maps to a service or explicit deferred feature; static hosting is not treated as a write API. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Recovery, credential custody and isolation are covered; operational assumptions are sourced or labeled estimates. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |


### Issue #47

Canonical record: `docs/delivery/android/work-packages/01.06.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Set measurable product/performance/reliability criteria, supported devices, review roles and release gates without inventing dates, budgets or assignees. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Every release criterion has a measurement/review method and inspectable evidence artifact. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Decided thresholds and devices are recorded; structural validation, factual acceptance, application behavior and live release verification remain separate gates. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
