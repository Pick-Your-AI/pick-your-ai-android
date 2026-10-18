# ADR 0004: Android acceptance-baseline record

Date: 2026-10-18. Effective event: independent receiving review and normal reviewed
merge of this ADR, the [baseline](../ACCEPTANCE_BASELINE.md) and
[01.06](../delivery/android/work-packages/01.06.md). Until then the placement and
baseline are proposed.

## Context and authority

The maintainer explicitly delegated project engineering decisions and reviewed
publication. Android #34's accepted repository/native decision places this
client's implementation here. The accepted
[migration rule](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/MIGRATION.md#placement-and-shared-authority)
retains historical acceptance paths until an explicit reviewed placement decision;
moving an issue did not move its record.

Android [#47](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47) names
`docs/delivery/android/work-packages/01.06.md` in `Pick-Your-AI/pickyour.ai`.
A complete, untruncated read-only tree inspection at website
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/tree/dc66a9e269917ab7241c7d08fd0e7f2a2f039872)
on 2026-10-18 found no record at that path. Its accepted scope/native inputs and
the acceptance-baseline source live in Android.

## Decision and alternatives

Place **only #47 / 01.06** in `Pick-Your-AI/pick-your-ai-android` at the unchanged
relative path [docs/delivery/android/work-packages/01.06.md](../delivery/android/work-packages/01.06.md).
Keep all three literal criteria and their stable IDs. The baseline is the source
decision; 01.06 is its one canonical receiving record, not a second requirements
authority. No existing website file is copied, deleted or replaced.

Keeping this new Android-specific record in the website would separate it from
its accepted architecture and receiving tests; duplicating records would create
conflicting completion authorities. A single reviewed Android record retains
historical provenance and makes the exact decision review inspectable.

[ADR 0001](0001-android-23-acceptance-record.md) and
[ADR 0002](0002-android-foundation-records.md) retain their own narrow placements.
This ADR adds only the 01.06 exception; 01.04/#35, 01.05/#25 and every other record
retain their independently governed placement. A separately reviewed #25 decision
can change its own placement without extending this ADR. Shared research, website
service, contract, pricing, evidence, source-use and saved-build authorities remain
unchanged. No framework, API, source pin, workflow, store or deployment changes.

## Receiving and completion reconciliation

The Android agent prepares this decision under the actual user delegation. The
independent coordinating agent must accept its product/Android technical content,
including the specific devices/thresholds, all journey/mode dispositions, truthful
ownership table and separation of G1–G4. Record the actual reviewer identity and
exact candidate review; future human or operating commitments cannot be inferred.

After normal reviewed delivery, add the exact immutable ADR, baseline, 01.06 and
receiving-review receipts to a separately reviewed extension of the existing
strict [criterion ledger](../delivery/README.md). Preserve every original
criterion ID/text/hash, canonical relative path, historical website placement and
all prior acceptance evidence. This source change does not change ledger status,
validator rules, issue checkboxes or assignments. No issue closure follows merely
from a proposed record or green structural checks. Only the complete, delivered,
independently reviewed #47 design outcome can be reconciled; device qualification,
operating acceptance and release keep their own required evidence.
