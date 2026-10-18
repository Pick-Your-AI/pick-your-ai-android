# ADR 0002: Android foundation decision records

Date: 2026-10-18. Effective event: independent receiving review and normal reviewed
merge of this ADR and the three linked decision records. Before that event this is
a proposed placement decision, not accepted issue completion.

## Context and authority

The maintainer established this Android repository, migrated its planning issues,
and explicitly delegated project decisions and reviewed publication. The accepted
[mobile migration rule](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/MIGRATION.md#placement-and-shared-authority)
preserves the original website acceptance paths until an explicit placement
decision. Issue migration alone did not move those records.

Android #20, #24 and #34 name three work-package paths in `Pick-Your-AI/pickyour.ai`.
A read-only tracked-tree inspection at website
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/tree/dc66a9e269917ab7241c7d08fd0e7f2a2f039872)
found no existing record at any of those paths. Existing product/architecture
proposals and implementation evidence live in the Android repository. Its signed
merge and independent-review history can give these decisions one receiving home.

## Decision

Place only the following three canonical acceptance records in
`Pick-Your-AI/pick-your-ai-android`, preserving each relative path and literal
acceptance criteria:

| Issue | Historical intended repository/path | Accepted repository/path after reviewed merge |
| --- | --- | --- |
| [#20 / 01.01](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20) | `Pick-Your-AI/pickyour.ai`, `docs/delivery/android/work-packages/01.01.md` | `Pick-Your-AI/pick-your-ai-android`, [same path](../delivery/android/work-packages/01.01.md) |
| [#24 / 01.02](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24) | `Pick-Your-AI/pickyour.ai`, `docs/delivery/android/work-packages/01.02.md` | `Pick-Your-AI/pick-your-ai-android`, [same path](../delivery/android/work-packages/01.02.md) |
| [#34 / 01.03](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) | `Pick-Your-AI/pickyour.ai`, `docs/delivery/android/work-packages/01.03.md` | `Pick-Your-AI/pick-your-ai-android`, [same path](../delivery/android/work-packages/01.03.md) |

The two existing proposal paths remain the source decisions; the three work-package
records hold exact criterion mappings and receiving boundaries. No existing website
record is removed, copied or replaced. [ADR 0001](0001-android-23-acceptance-record.md)
continues to govern #23 / 14.01. Its statement that other paths remained in the
website described that earlier decision; this ADR supplies the explicit subsequent
exception for these three paths only. Every other path keeps its existing authority.

## Alternatives and consequences

Keeping the new records in the website would split Android decisions and receiving
evidence across repositories. Duplicating them would create conflicting acceptance
authorities. One Android record per issue keeps the source, exact evidence and
receiving review together, with historical placement recorded explicitly.

The [native decision](../proposals/02-native-architecture.md) selects this repository
and its bounded Kotlin/Views/store implementation. It does not relocate shared
research contracts, compatibility, pricing, evidence, saved-build semantics, labels
or services. No website source, workflow, runtime or deployment changes here.

## Receiving and completion reconciliation

The Android decision agent owns this preparation under delegated user authority.
An independent coordinating agent reviews the product and Android technical
decision content at its exact candidate tree. Actual reviewer identity and scope
must be recorded; role labels do not assign a person, support obligation or date.

Before closing an issue, confirm the complete literal criterion mapping, explicit
receiving acceptance and normal delivery of the same artifact version. Record
exact accepted artifact, placement, PR and review URLs from that delivery in the
issue and a separately versioned current acceptance index. A proposed record or
green syntax check alone cannot support completion.

Preserve the v1 partial-evidence ledger and its literal criterion hashes. Its
initial canonical paths and deliberately incomplete statuses are historical
baseline data once a reviewed current acceptance overlay is introduced; do not
silently switch those fields or relax its validator. Any current-record schema and
criterion-level receipts require their own review. No conditional feature,
parent/epic, research issue, production source, installed device or release is
accepted by closing these three decision tasks.
