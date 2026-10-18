# Service boundary recommendation

Proposal for Android #35. See the accepted [scope](01-product-scope.md) and
[architecture](02-native-architecture.md) decisions. The earlier #47 proposal below
is superseded by the independently reviewed [acceptance baseline](../ACCEPTANCE_BASELINE.md)
when that decision is normally merged. No hosting account, production
endpoint, operation owner or device qualification is established by this document.

## Preview and production boundaries

Retain the local-only preview with labelled fixtures and app-private durable
storage. Product file exchange remains conditional J5 under the accepted scope;
private diagnostic text sharing does not implement that journey. The preview
requires no Android-specific backend or cloud
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
| Draft/revision/head transaction | Canonical local filesystem revision/head store; explicit failed/conflict/uncertain outcomes | Server-side atomic commit plus expected-revision check and idempotency receipt |
| Catalog publication | Bundled synthetic input; no production promotion | #15/#41 review, immutable manifest and hashes, atomic promotion and rollback pointer |
| Resource/quality evidence | Missing data stays unavailable | #16 and accepted exact-version evidence; no generated fit claim |
| Remote save and private reads | Not exposed | #17 ownership/capability authorization checked by server, not UI |
| Share and revocation | Product file exchange remains conditional; an explicitly shared diagnostic text copy cannot be revoked from its recipient | Server capability scope, expiry/revocation, sanitized projection and rate limits |
| Backup/restore | No promise of cloud backup; recovery and device-backup policy need testing | Encrypted backups, operator-approved retention and isolated restore drill with measured results |
| Credentials | No provider/account credentials in fixture preview | Server-side custody/rotation and least privilege; do not embed keys in APKs |
| Monitoring/incidents | Local diagnostic preview only; no automatic upload | Named responder, sanitized signals and exercised runbook under #44/#92 |

Desk failure review: a crash before a local transaction commits leaves the old
head; after commit it reloads the new one. A future server commit followed by a
lost response retries the same idempotency key and cannot create another revision.
A bad catalog release changes the active pointer back without rewriting history.
A database restore is tested in isolation and reconciles artifact identities before
serving traffic. These are required invariants, not observed runtime results.

## Acceptance-baseline handoff

The [#47 baseline](../ACCEPTANCE_BASELINE.md) develops the earlier acceptance
proposal into explicit API/device/window targets, A01–A13 requirements, evidence
methods, review roles and the G1–G4 release sequence. Its
[01.06 receiving record](../delivery/android/work-packages/01.06.md) and narrow
[ADR 0004](../decisions/0004-android-47-acceptance-record.md) become effective through
their independent review and normal merge. Historical statements that no Android
implementation or Kotlin tests existed remain in Git history; actual later
simulation receipts retain their own exact scope and do not qualify devices.

This service proposal adopts none of the still-missing #35 hosting, account,
provider, source, operating-owner or recovery commitments. The baseline explicitly
keeps factual acceptance, application behavior and live release verification
separate from structural checks and decision acceptance. It selects no cloud
provider, service budget, response SLA, person or production rollout. Its missing
physical/resource/release/operating inputs remain release blockers.

## Acceptance disposition

### Issue #35

Canonical record: `docs/delivery/android/work-packages/01.04.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Choose the minimum production API/data-store deployment and operations owner; distinguish the existing static GoDaddy site from a stateful backend. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Every required stateful responsibility maps to a service or explicit deferred feature; static hosting is not treated as a write API. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Recovery, credential custody and isolation are covered; operational assumptions are sourced or labeled estimates. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
