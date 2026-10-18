# ADR 0005: Place Android #35's decision record in Android

Date: 2026-10-18. Effective event: independent product/Android technical receiving
review and normal reviewed merge of this ADR, the
[service decision](../SERVICE_BASELINE.md) and
[01.04 receiving record](../delivery/android/work-packages/01.04.md).
Until that event the placement and decision are proposed.

## Inspected history and authority

[Android #35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35)
preserves its intended record at
`docs/delivery/android/work-packages/01.04.md` in `Pick-Your-AI/pickyour.ai`.
The maintainer's issue migration did not move acceptance records. ADRs
[0001](0001-android-23-acceptance-record.md),
[0002](0002-android-foundation-records.md),
[0003](0003-android-25-acceptance-record.md) and
[0004](0004-android-47-acceptance-record.md) each retain their own narrow scope;
none already authorizes this additional placement.

The website's default branch read on 2026-10-18 was
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/commit/dc66a9e269917ab7241c7d08fd0e7f2a2f039872),
tree `d331679630302248f962e19c537c069cdf5eaa1e`.
Its complete, nontruncated 229-entry recursive tree contains no 01.04 record.
This is an inspected-version absence, not a claim about every historical commit
or another contributor's unpublished files.

The maintainer delegated focused engineering decisions and reviewed project
publication. The current task selects the #35 design and its explicit operating
acceptance gate. It does not supply someone else's ongoing operating commitment
or authority to deploy the website, buy services or approve private-data custody.

## Decision and alternatives

Place **only #35 / 01.04** in `Pick-Your-AI/pick-your-ai-android`, retaining the
same relative path. The service baseline is the design artifact; 01.04 is its
single receiving record, with all three original criteria and the historical
website placement preserved.

Keeping this new decision record in the website would separate it from its
accepted Android scope, account and native inputs and involve a repository whose
main merges deploy the website. Duplicating it would create competing receiving
authorities. One reviewed Android record gives this decision a concrete location
while the canonical website service issues keep their implementation ownership.

The source-location decision in the baseline names one future separately
deployed `services/planner-api` package in `Pick-Your-AI/pickyour.ai`. It creates
no package, changes no static build or operation-owned file, and assigns no new
person. Its implementation must use the existing website #15–#18 lanes and their
coordinator/operations review before dependent code or deployment. This ADR moves
the **decision record**, not the canonical shared-service or research authority.

Every other acceptance path keeps its own independently governed placement.
No website file is copied, deleted or replaced. No issue relationship, Project
field, assignee, framework, schema, source-use rule or operating setting changes.

## Receiving and current-ledger transition

The independent reviewer must assess the exact placement and full hosting
decision in the required product decision and Android technical capacities.
The literal issue permits an accountable operating role with an explicit open
acceptance gate. Record the actual in-session review without calling it a human
operator's agreement or an executed production test.

This document delivery changes no criterion ledger, validator or adversarial
test. After normal signed delivery and exact default-branch verification, extend
the then-canonical current ledger through its own independent review. Reuse the
separately owned #47 v5 delivery when accepted; do not create a competing index
or race that owner's files. Bind only #35's exact reviewed decision artifact and
receipts, preserving all original criterion IDs/text/digests, partial evidence,
historical repository/path and immutable ledger-version history. The strict
completion guard remains intact. Only that subsequent evidence-backed
reconciliation can mark the #35 decision accepted in issue metadata.
