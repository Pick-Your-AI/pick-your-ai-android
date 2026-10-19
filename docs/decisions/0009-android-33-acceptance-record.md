# ADR 0009: Place Android #33's support receiving record in Android

Date: 2026-10-19. Effective event: independent receiving review and normal
reviewed merge of this ADR and the [17.04 record](../delivery/android/work-packages/17.04.md)
into Android main. Until that event this placement is proposed.

## Context and authority

The maintainer delegated focused project engineering decisions and reviewed
publication. The accepted [Android #34 decision](../delivery/android/work-packages/01.03.md)
places the current guest-local Kotlin/Views preview in this repository.
The [migration rule](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/MIGRATION.md#placement-and-shared-authority)
preserved intended website acceptance paths until an explicit placement decision;
transferring an issue did not move its record.

[Android #33](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/33)
still names `docs/delivery/android/work-packages/17.04.md` in
`Pick-Your-AI/pickyour.ai`. The same website repository, ID1422238606, is now
`Pick-Your-AI/pick-your-ai-website`. Its inspected valid-signed main
[5afc6a88ffafbb8ca7e411638009319dafc6f1e4](https://github.com/Pick-Your-AI/pick-your-ai-website/commit/5afc6a88ffafbb8ca7e411638009319dafc6f1e4),
tree `ef1fe788896b9fa971d4c68237b8ce80b3895a6e`, has 188 tracked file leaves
and no record at that path. The exact contents lookup also returned 404.
This observation is specific to that accepted revision.

The Help and diagnostic implementation, original verification, public intake
forms and their actual receiving history are all in Android. The complete file
lists of the 18 open Android PRs inspected before
[the bounded coordination notice](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/33#issuecomment-6257405076)
contained neither this ADR nor the proposed 17.04 path. Existing implementation
and acceptance controllers retain their branches and evidence.

## Decision

Place only Android #33 / 17.04 in this repository, preserving the relative path.

| Record | Historical intended repository | Destination after this ADR's effective event |
| --- | --- | --- |
| Android #33 / 17.04 | Pick-Your-AI/pickyour.ai | Pick-Your-AI/pick-your-ai-android |

The [17.04 receiving record](../delivery/android/work-packages/17.04.md) becomes
the single canonical source, criterion, failure/recovery and review record for
this support workflow. Every other acceptance path retains its existing
authority. No website record is deleted, copied or deployed. Shared evidence,
pricing, contract, source-rights and service authorities retain their repositories.

Keeping the record in the website would separate Android's implementation and
its support observations from the receiving review and would involve a separately
deployed repository. Duplicating the record would create competing acceptance
authorities. This bounded placement follows [ADR 0001](0001-android-23-acceptance-record.md)
and [ADR 0008](0008-android-48-acceptance-record.md) while adopting neither one's
other scope.

## Receiving and completion

Preserve all three literal #33 criteria, all four implementation tasks, both
validation methods and the accepted #23 privacy input. Review the current
guest-local outcome with the accepted in-session support/operations responsibility
and an independent reviewer of the affected local-data/service boundary.

The actual browser requests and separate triage establish the observed public
intake. The existing exact diagnostic preview and explicit chooser action retain
their simulated evidence. Neither is promoted into installed chooser delivery,
a future support rota, confidential reporting, production service or release
acceptance. The unavailable private route remains explicit. This decision adds
no application, dependency, workflow, service, account or operating commitment.

After normal reviewed delivery, bind the actual record commit, receiving reviews
and default-branch verification in the canonical delivery ledger before updating
#33. Preserve original criterion text/IDs, previous partial evidence, the
historical website destination, live assignees, native relationships and Project
fields. Merging the placement decision alone does not close the issue.
