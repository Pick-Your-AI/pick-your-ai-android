# ADR 0001: place Android 14.01 acceptance with its implementation

Date: 2026-10-18.

Effective event: independent receiving review and the normal reviewed merge of this ADR and its linked acceptance record. Before that merge, this is a proposed placement decision.

Scope: the acceptance record for [Android #23, work package 14.01](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/23). This is a partial repository-placement decision under [Android #34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34); it does not complete that issue.

## Context and authority

The maintainer established an independent Android repository, migrated its planning issues, and subsequently delegated project engineering decisions and reviewed publication. The accepted [mobile migration rule](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/MIGRATION.md#placement-and-shared-authority) preserved the website's intended Android acceptance paths until an explicit placement decision. Issue migration alone did not move those paths.

The issue's original intended record is `docs/delivery/android/work-packages/14.01.md` in `Pick-Your-AI/pickyour.ai`. That path contains no tracked record in the inspected website revision [`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/tree/dc66a9e269917ab7241c7d08fd0e7f2a2f039872). The existing Android inventory, mitigation code and tests are versioned in `Pick-Your-AI/pick-your-ai-android`, which now has its own signed-commit, independent-review and native verification gates.

## Decision

Adopt [`docs/delivery/android/work-packages/14.01.md`](../delivery/android/work-packages/14.01.md) in `Pick-Your-AI/pick-your-ai-android` as the sole canonical receiving record for Android #23. The relative path is preserved; the repository changes through this explicit decision. The record remains adjacent to the implementation and its native review history, and can bind observations to exact Android commits without a separate website release.

| Record | Historical intended repository | Accepted destination after this ADR's reviewed merge |
| --- | --- | --- |
| Android #23 / 14.01 | `Pick-Your-AI/pickyour.ai`, `docs/delivery/android/work-packages/14.01.md` | `Pick-Your-AI/pick-your-ai-android`, `docs/delivery/android/work-packages/14.01.md` |

No existing canonical record is removed or copied from the website. Every other issue's acceptance path remains authoritative, including Android #34's `docs/delivery/android/work-packages/01.03.md` in `Pick-Your-AI/pickyour.ai`. Shared research contracts, compatibility, pricing, evidence and source-rights authorities retain their current repositories. This decision adds no framework, package, module, shared-service or deployment choice.

## Alternatives and consequences

Keeping this new record in the website repository would preserve the original intended repository, but would split the Android evidence and its receiving review across separate change histories and verification workflows. Maintaining copies in both repositories would create competing acceptance authorities. A single Android record gives the implementation and its receiving review one versioned home while preserving the historical website destination explicitly.

Only this bounded record is adopted. The broader repository/package and reusable-module outcome in Android #34, and the placement of all other acceptance records, retain their own criteria and review. The website checkout and preliminary draft are not published by this decision. No website workflow, runtime code, contract or deployment configuration is changed.

## Receiving review and issue reconciliation

The ChatGPT Android review agent prepared this placement decision and the linked criterion-by-criterion inventory receipt under the maintainer's delegated engineering authority. A separate coordinating-agent review must inspect the exact proposed source and evidence before normal repository publication. Reviewer identities must describe the actual review; no named person's assignment or operating commitment is inferred.

After the independently reviewed merge, reconcile the live Android #23 acceptance location and its delivery ledger using the accepted ADR, receipt and review URLs. Preserve all three criterion texts, the original intended location as history, ownership, native relationships and remaining implementation/release gates. Close the issue only when that reconciliation verifies the complete reviewed inventory outcome. Merging this ADR alone does not close Android #23 or Android #34.

The linked receipt distinguishes design acceptance from source observations, executed JVM checks and missing external evidence. It grants no real-data publication, live service, installed-device, legal, signing or Play acceptance. Changes to a data flow or trust authority require an updated inventory and receiving review at the new exact implementation version.
