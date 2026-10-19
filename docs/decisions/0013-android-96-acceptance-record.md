# ADR 0013: Place Android #96's compatible-options receiving record in Android

Date: 2026-10-19. Effective event: independent receiving review and normal
reviewed merge of this ADR and the [07.02 record](../delivery/android/work-packages/07.02.md)
into Android main. Until that event this placement is proposed.

## Context and authority

The maintainer delegated focused project engineering decisions and reviewed
publication. The accepted [Android #34 decision](../delivery/android/work-packages/01.03.md)
places the current guest-local Kotlin/Views preview in this repository. The
[migration rule](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/MIGRATION.md#placement-and-shared-authority)
preserved intended website acceptance paths until an explicit placement decision;
transferring an issue did not move its record.

[Android #96](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/96)
names `docs/delivery/android/work-packages/07.02.md` in the historical
`Pick-Your-AI/pickyour.ai` repository. The same repository, ID1422238606, is now
`Pick-Your-AI/pick-your-ai-website`. Its inspected valid-signed main
[900c14d7e5101183a930834cb5249597837efb41](https://github.com/Pick-Your-AI/pick-your-ai-website/commit/900c14d7e5101183a930834cb5249597837efb41),
tree `34ef322f73fd7295bbfd9afbb2ab5db36ac97c23`, contains 204 tracked file leaves
and no record at that path. The exact contents lookup also returned 404.
This is an observation about that immutable revision, not an exhaustive search
of every historical website tree.

The local candidate engine, native picker, actual original verification and both
receiving decisions are in Android. The complete file lists of the 23 open
Android PRs inspected for this proposal contained neither ADR0013 nor 07.02.
The existing #502 and #550 controllers retain their separate ADR0011/0012
records, source histories and receiving work. No other record is selected here.

## Decision

Place only Android #96 / 07.02 in this repository, preserving the relative path.

| Record | Historical intended repository | Destination after the effective event |
| --- | --- | --- |
| Android #96 / 07.02 | Pick-Your-AI/pickyour.ai | Pick-Your-AI/pick-your-ai-android |

The [07.02 receiving record](../delivery/android/work-packages/07.02.md) becomes
the single canonical criterion, source, failure/recovery and review record for
this work package. Every other acceptance path retains its existing authority.
No website source is moved, copied, created, deleted or deployed. Shared Build,
graph, evidence, pricing, service and source-rights decisions retain their owners.

Keeping this record in the website would separate the actual Android code and
local-store observations from its receiving review. Duplicating the record would
create competing acceptance authorities. This bounded placement follows
[ADR 0009](0009-android-33-acceptance-record.md) and
[ADR 0010](0010-android-84-acceptance-record.md), without moving their other scope.

## Receiving and completion

Preserve all four literal #96 criteria, four implementation tasks and both test
requirements. Receive the delivered #82 catalog-browser and #84 frozen-interpreter
outputs for this exact consumer; changing their prerequisite edges or declaring
their unrelated work complete is unnecessary. The actual delegated data-consumer
and Android catalog/UX decisions accept the bounded
`local-stored-draft-slot-empty-diagnostics/1` behavior with explicit constraints.
Their public COMMENT reviews are technical decisions, not another person's
assignee commitment or distinct-account formal approval.

The record preserves the unchanged frozen archive boundary, typed incomplete
local draft, exact slot-empty comparison and current-result/canonical-save
authority. It distinguishes simulated native observations from the separately
governed product/device and release gates in [the acceptance baseline](../ACCEPTANCE_BASELINE.md).
It adds no shared compatibility policy, account, deployment, operating commitment,
or invented device criterion to the four literal #96 behaviors.

After normal reviewed implementation and record delivery, bind the actual signed
default-branch identities, original qualification and receiving reviews in the
canonical delivery ledger before updating #96. Preserve criterion IDs/text/hashes,
prior evidence, historical website destination, assignees and native relationships.
The placement decision alone does not close the issue.
