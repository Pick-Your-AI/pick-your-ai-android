# Service boundary and acceptance recommendation

Proposal for Android #35 and #47. See the [scope](01-product-scope.md) and
[architecture](02-native-architecture.md) proposals. No hosting account, production
endpoint, operation owner or device qualification is established by this document.

## Preview and production boundaries

Recommend a local-only preview with labelled fixtures, app-private durable storage
and user-initiated file exchange. It requires no Android-specific backend or cloud
account. This reduces the initial implementation surface; it does not satisfy
production catalog publication, permalink, remote backup or cross-device sync work.

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
| Draft/revision/head transaction | Local Room transaction; failure leaves previous head | Server-side atomic commit plus expected-revision check and idempotency receipt |
| Catalog publication | Bundled synthetic input; no production promotion | #15/#41 review, immutable manifest and hashes, atomic promotion and rollback pointer |
| Resource/quality evidence | Missing data stays unavailable | #16 and accepted exact-version evidence; no generated fit claim |
| Remote save and private reads | Not exposed | #17 ownership/capability authorization checked by server, not UI |
| Share and revocation | Explicit exported copy; cannot revoke a recipient's copy | Server capability scope, expiry/revocation, sanitized projection and rate limits |
| Backup/restore | No promise of cloud backup; recovery and device-backup policy need testing | Encrypted backups, operator-approved retention and isolated restore drill with measured results |
| Credentials | No provider/account credentials in fixture preview | Server-side custody/rotation and least privilege; do not embed keys in APKs |
| Monitoring/incidents | Local diagnostic preview only; no automatic upload | Named responder, sanitized signals and exercised runbook under #44/#92 |

Desk failure review: a crash before a local transaction commits leaves the old
head; after commit it reloads the new one. A future server commit followed by a
lost response retries the same idempotency key and cannot create another revision.
A bad catalog release changes the active pointer back without rewriting history.
A database restore is tested in isolation and reconciles artifact identities before
serving traffic. These are required invariants, not observed runtime results.

## Measurable acceptance without invented measurements

| Area | Required check and evidence | Current disposition |
| --- | --- | --- |
| Journeys and data | Execute workload → select → evaluate → estimate → save → compare → share with the accepted catalog/contract pins and fixture disclosure checks | No Android implementation; synthetic desk walkthrough only |
| Durability | Kill/relaunch after acknowledged edits; rotation, backgrounding, task removal, reboot, failed writes and every supported migration | Unexecuted; retain separate results for each case |
| Conformance | Golden/negative fixtures for all supported kinds; cycles, optional tiers, decimals, unknowns, version mismatch and stale responses | Existing website/reference checks are inputs, not Kotlin coverage |
| Accessibility | Automated semantics plus manual TalkBack, focus, large text, contrast, RTL and keyboard, including failure states | Unexecuted; automated pass cannot replace manual evidence |
| Security/privacy | Manifest/exported-component review, no secret-bearing backups/logs, malformed imports, malicious links, permission denial and auth isolation where applicable | Unexecuted; missing account features do not waive local data/privacy review |
| Performance | Measure cold/warm start, frame timing, memory, energy and representative catalog size on selected devices and a signed optimized build | Set decision thresholds before assessing results; no fabricated baseline or budget |
| Device coverage | Select min/latest supported API, at least relevant compact/expanded windows, rotation and physical low/mid/high capability targets based on intended users | Exact API/device model list and commitments remain open under #47/#107 |
| Distribution | Publisher/package/key custody, signed AAB, policy review, track-delivered install/update and release approval | No publisher evidence, signing custody or Play Console release work performed |
| Operations | Real-data freshness/correction, restore, alert delivery and response, signed installed-release verification | No accepted operations owner or production service |

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
