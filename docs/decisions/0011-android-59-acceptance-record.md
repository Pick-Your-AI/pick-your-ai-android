# ADR 0011: Place Android #59's CI acceptance record in Android

Date: 2026-10-19. Effective event: independent receiving review and normal reviewed
merge of this ADR and the [02.05 record](../delivery/android/work-packages/02.05.md)
into Android main. Until that event this placement remains proposed.

## Authority and inspected history

The maintainer authorized focused engineering decisions and reviewed publication.
The accepted [Android #34 decision](../delivery/android/work-packages/01.03.md)
places the bounded native client in this repository. Its CI workflows, build
inputs and artifact provenance are maintained here. This decision places their
receiving record beside that source without changing the implementation scope.

[Android #59](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59) still
names `docs/delivery/android/work-packages/02.05.md` in
`Pick-Your-AI/pickyour.ai`. The issue transfer did not move that record.
[ADR0008](0008-android-48-acceptance-record.md) moved only #48 / 02.01 after its
reviewed delivery; it supplies no placement authority for 02.05.

The 2026-10-19 inspection read the complete #59 and #48 discussions,
the [existing foundation/ledger handoff](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/460#issuecomment-6257127851),
and every file page of all 17 then-open Android PRs. No open PR contained 02.05
or this ADR path. The complete Android tree at
[`57bb24d683677b02f6be911fa1fc5d6a57e5038c`](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/57bb24d683677b02f6be911fa1fc5d6a57e5038c)
also contained no 02.05 file. The website repository, ID `1422238606`, was
observed under its current name `Pick-Your-AI/pick-your-ai-website` at
`5afc6a88ffafbb8ca7e411638009319dafc6f1e4`; reading the intended path there
returned not found. These are exact-version observations, not a claim about
unpublished or every historical source. No website file is copied or removed.

The [bounded reservation](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59#issuecomment-6257649078)
covers this ADR, the new record and only their CI/foundation/scope references.
The existing #48 receiving owner, #467 installed correction and single
shared-ledger writer retain their scopes.

## Decision and alternatives

Place **only Android #59 / 02.05** in `Pick-Your-AI/pick-your-ai-android`, retaining
the relative path `docs/delivery/android/work-packages/02.05.md`. After the
effective event above, that file is the single criterion-level receiving record.
[CI_BASELINE.md](../CI_BASELINE.md) remains the operational check and failure-triage
reference; it is not a competing acceptance record.

Keeping the record in the website would separate Android source/check identities
from their receiving history and require an unrelated website change. Duplicating
the record would create competing authority. This scoped move preserves the
original website spelling/path as history and leaves every other work-package
placement under its existing decision.

## Receiving and completion boundaries

Preserve all four #59 implementation tasks, three criteria and both validation
methods literally. The initial record is partial: required-status application and
effective readback, actual credential-context review, the owned corrected
installed result, and complete receiving acceptance remain outstanding.
Passing checks or this document's merge cannot supply those results.

The [current additive protection proposal](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59#issuecomment-6257619397)
preserves existing review rules and exceptions. It must be reconciled with a fresh
read before any settings action; this ADR performs no settings change.

Normal signed delivery, independent current-main review, configured checks and
actual post-main verification remain required. Placement acceptance does not
complete #59, close its #48 prerequisite, establish installed/TalkBack/physical
evidence, or grant production signing, release or operating authority.

No issue body, criterion, checkbox, assignee, native parent/prerequisite, Project
field, website, ledger, validator, immutable ledger version, workflow or runtime
changes here. After the effective event, the live issue's migration text and the
ledger's original website mapping remain historical pending the existing owner's
deliberate reconciliation.
Any later ledger amendment must preserve original IDs/text/digests, prior partial
evidence and all unrelated records, and must not mark an incomplete criterion done.
