# ADR 0007: Place Android #26's model acceptance record in Android

Date: 2026-10-18. Effective event: independent receiving review and normal reviewed
merge of this ADR and the [03.02 record](../delivery/android/work-packages/03.02.md).
Until that event the placement is proposed.

## Authority and inspected history

The maintainer delegated focused project engineering decisions and reviewed
publication. The accepted [Android #34 record](../delivery/android/work-packages/01.03.md)
places this client's Kotlin core, Android adapter and source governance in this
repository. This decision concerns its implemented local draft/snapshot model.

[Android #26](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26) preserves
the intended `docs/delivery/android/work-packages/03.02.md` record in
`Pick-Your-AI/pickyour.ai`. Issue migration did not move that record. On
2026-10-18 the website main was
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/commit/dc66a9e269917ab7241c7d08fd0e7f2a2f039872),
tree `d331679630302248f962e19c537c069cdf5eaa1e`. A complete untruncated
229-entry/188-leaf Git tree read contains no file at that path; the direct
contents request also returned 404. This is an exact-version absence, not a
claim about all history or another contributor's unpublished work.

ADRs 0001–0005 retain their individually accepted placements. Concurrent
[PR #404](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/404) owns ADR 0006
and the separate foundation-epic record. This ADR does not change those decisions.

## Decision and alternatives

Place **only Android #26 / 03.02** in `Pick-Your-AI/pick-your-ai-android`, retaining
the same relative path. It is the single criterion, implementation-evidence and
receiving record for the Android-local draft, explicit frozen projection and
private immutable archive. Preserve the original website repository/path as
historical placement in that record and the later ledger amendment.

Keeping the new record in the website would separate it from the adopted source,
tests and local-format decisions and involve the website's deployment boundary.
Duplicating it would create competing acceptance records. The scoped Android
placement keeps the exact implementation and its evidence together.

This changes no website file, shared service, issue relationship, assignee or
Project field. Website #15–#18 and research #40/#41/#42/#60 retain their canonical
contract, lifecycle, evidence and source-use authority. A private local archive
does not become a shared Draft, export payload, API or catalog publication. Every
other record keeps its independently governed placement.

## Receiving and completion

Review must cover the literal three #26 criteria, all four implementation steps,
named failure/migration checks, exact consumed #21 outputs, retained-only
acknowledgment, private retention and the distinction between a memory candidate
and an acknowledged archive. Record actual central-contract/saved-build and
affected Android/web/iOS technical dispositions under the delegated engineering
roles; do not invent a historical assignee's reply.

Normal signed source delivery, current-main review/checks and actual post-main
verification remain required. The current implementation and historical red/green
executions retain their literal identities. A creation/removal screen, shared
service, installed device or physical power-loss observation is not inferred from
this model record.

This document change does not alter the criterion ledger, validator, tests or
issue checkboxes. After reviewed delivery, extend the then-current strict ledger
through its own independent review using the actual delivered record, model
artifact and receiving receipts. Preserve all original criterion IDs/text/digests,
prior partial references, every other issue object and immutable version history.
A reviewed implementation outcome must be represented as implementation evidence,
not relabeled as one of the earlier documentary/design outcomes.
