# ADR 0008: Place Android #48's toolchain record in Android

Date: 2026-10-18. Effective event: independent receiving review and normal reviewed
merge of this ADR and the [02.01 record](../delivery/android/work-packages/02.01.md)
into Android main.
Until that event the placement is proposed.

## Authority and inspected history

The maintainer delegated focused project engineering decisions and reviewed
publication. The accepted [Android #34 record](../delivery/android/work-packages/01.03.md)
selects this repository, its current `:core`/`:preview` modules and their
engineering identities for the bounded first client slice. This decision places
the evidence for that implementation beside its source; it changes no module,
package identity or acceptance criterion.

[Android #48](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48) retains
the intended `docs/delivery/android/work-packages/02.01.md` record in
`Pick-Your-AI/pickyour.ai`. The issue migration did not relocate it. On
2026-10-18, the website's canonical repository name was
`Pick-Your-AI/pickyour-ai` (repository ID `1422238606`); its current main remained
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour-ai/commit/dc66a9e269917ab7241c7d08fd0e7f2a2f039872),
tree `d331679630302248f962e19c537c069cdf5eaa1e`.
The complete, untruncated tree contains no file at the intended path. This is an
exact-version observation, not an assertion about every historical or unpublished
source. The original repository spelling and relative path remain in the record.

The complete current file lists of all 15 open Android PRs were inspected before
[reservation6254856283](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48#issuecomment-6254856283).
Neither new path was present. Existing #412/#404 guide and foundation records,
all prior ADRs, the strict-ledger controller and the #255 → #237 → #206 installed
delivery chain retain their owners and histories.

## Decision and alternatives

Place **only Android #48 / 02.01** in `Pick-Your-AI/pick-your-ai-android`, keeping
the same relative path. The [02.01 record](../delivery/android/work-packages/02.01.md)
is the single criterion, source, execution and receiving record for this toolchain
and module work package after the effective event above.

Keeping it in the website would separate the Android build and installed evidence
from their adopted source and involve the website's independently deployed
boundary. Duplicating it would create competing receiving records. This bounded
placement keeps the implementation and evidence together while leaving website
checks independently runnable.

The original website path remains historical authority until this ADR is accepted.
Every other record keeps its independently governed placement. This decision
changes no website file, service location, frozen contract, issue relationship,
assignee or Project field. It grants no new installed run, operating commitment,
publisher identity or release authority.

## Receiving and completion

Review the exact three #48 criteria, four implementation tasks and both validation
methods against the selected client in #34. The record explicitly distinguishes
`ai.pickyour.android.preview` from future `ai.pickyour.android`/`:app`, and the
ordinary unsigned build from its separately signed installed test copy.
Prior-stage build and installed evidence retain their actual checkout/tree;
neither signed staging nor this document's delivery is current-main installed
acceptance.

Normal signed delivery, current-main review and configured checks, and actual
post-main verification remain required. This document changes no ledger,
validator, test or issue checkbox. After full implementation receiving is
established, the canonical ledger owner can propose its own independently
reviewed amendment with the actual delivered record, source and review receipts.
Preserve every literal criterion ID/text/digest, original website placement,
previous partial evidence, other issue object and immutable ledger version.
