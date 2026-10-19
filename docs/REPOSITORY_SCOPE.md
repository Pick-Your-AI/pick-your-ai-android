# Repository scope and decision boundaries

The maintainer requested creation of separate Apple and Android repositories according to their delivery Projects. `Pick-Your-AI/pick-your-ai-android` is the Android client repository created under that instruction. [Project 4](https://github.com/orgs/Pick-Your-AI/projects/4) and [Pick-Your-AI/pick-your-ai-android#1](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/1) remain the delivery authorities.

The maintainer subsequently authorized local implementation and delegated engineering
decisions. The bounded guest preview now uses the native Kotlin `:core` module and
an opt-in `:preview` module built with platform Android Views. These are the adopted implementation decisions for the bounded preview.
The experimental namespace/application ID is `ai.pickyour.android.preview`;
its debug artifact is unsigned and release variants are disabled. Current pins
are JDK 21, Java 17 bytecode, Gradle 8.11.1, Kotlin 2.1.20, AGP 8.9.2, compile/target
SDK 35 and minSdk 26. The pins describe this experiment, not Play compliance or a
qualified device matrix. See the [architecture record](proposals/02-native-architecture.md)
and [reproduction instructions](ANDROID_PREVIEW.md).

The [product decision](proposals/01-product-scope.md) and
[native decision](proposals/02-native-architecture.md) select the bounded guest-local
planning slice and retain Kotlin/platform Views plus the canonical local revision
store through independent receiving review and normal reviewed merge. The earlier
immediate Compose/Room recommendation is superseded for that slice; both remain
documented alternatives. `ai.pickyour.android` is the selected future product
engineering namespace, with source governance in this Android project. Publisher
identity, Play registration, product/device qualification, service hosting,
operations ownership and delivery commitments retain their own requirements.
Completing #20/#24/#34 requires their literal decision criteria and exact-version
receiving records; it does not accept implementation or release.

[ADR 0001](decisions/0001-android-23-acceptance-record.md) places #23's
`docs/delivery/android/work-packages/14.01.md` record in this repository.
[ADR 0002](decisions/0002-android-foundation-records.md) adds only #20/#24/#34's
`01.01.md`, `01.02.md` and `01.03.md` records under that same directory after its
independently reviewed merge. Their original intended website paths are preserved
as history; the pinned website tree contained none of these records.
[ADR 0003](decisions/0003-android-25-acceptance-record.md) separately places only
#25's `01.05.md` record here after its independent receiving review and normal
merge. The [account and revenue decision](delivery/android/work-packages/01.05.md)
selects free guest-local planning, excludes optional services from the first slice,
and retains canonical authority for any later remote branch. Every other issue
path retains its current authority. The canonical current acceptance schema must
bind exact delivered artifacts and reviews before issue completion, preserving
all original criteria, prior evidence and immutable ledger-version history.
This document delivery changes no ledger, validator or test.

[ADR 0004](decisions/0004-android-47-acceptance-record.md) separately adds only
#47's [01.06](delivery/android/work-packages/01.06.md) acceptance-baseline record
after its independent review and normal merge. Its
[selected device/quality requirements](ACCEPTANCE_BASELINE.md) are qualification
targets, not observed device or release results. The original intended website
path remains history.

[ADR 0005](decisions/0005-android-35-acceptance-record.md) separately places only
#35's [01.04 receiving record](delivery/android/work-packages/01.04.md) here after
its independent review and normal merge. The [service decision](SERVICE_BASELINE.md)
selects a future separately deployed TypeScript/Node API and paid PostgreSQL on
Render, with one canonical server package under the existing website service
lanes. Its named operating/recovery roles retain an explicit unfilled acceptance
gate; no account, deployment, person or ongoing commitment is supplied by the
decision. Android's guest-local first-slice scope and conditional remote features
remain; Apple and website activation follows their own accepted scope, without
waiting for Android feature or product completion.

[ADR 0006](decisions/0006-android-2-acceptance-record.md) proposes placement of only
#2's [integrated epic 01 receiving record](delivery/android/epics/01.md) here after
independent product/Android review and normal merge. The record connects all six
accepted decision children and their exact handoffs through a concrete decision
demonstration. It preserves all four parent criteria and the original website
placement, and supplies no application, device, operator or service acceptance.
Its later strict current-ledger reconciliation remains a separate reviewed step.

[ADR 0007](decisions/0007-android-26-acceptance-record.md) proposes the separate
placement of only #26's [03.02 local-model receiving record](delivery/android/work-packages/03.02.md)
in Android, effective through its independent receiving review and normal merge.
It preserves the original website path as history and changes no shared contract,
service placement or other work package. The executed draft/projection/private-
archive model and its exact evidence remain distinct from future creation/removal
UI, shared saved-build services, installed devices and release qualification.

[ADR 0008](decisions/0008-android-48-acceptance-record.md) separately proposes
placing only #48's [02.01 foundation record](delivery/android/work-packages/02.01.md)
in Android. Placement becomes effective only after independent receiving review
and normal reviewed delivery into Android main. The original website path remains
history; all other work-package locations retain their existing authority. The
selected `:core`/`:preview` toolchain and preview package evidence remain distinct
from future product, device and release qualification; full #48 acceptance stays
pending its literal criteria and current installed-delivery evidence.

[ADR 0009](decisions/0009-android-33-acceptance-record.md) separately proposes
placing only #33's [17.04 support receiving record](delivery/android/work-packages/17.04.md)
in Android. Its effective event is independent receiving review and normal
reviewed merge. The record preserves the original website destination, all three
literal criteria, actual diagnostic and public browser-intake evidence, separate
in-session support and affected local-data review, and unavailable-channel limits.
It changes no implementation, other acceptance path, shared authority, operating
commitment or release qualification. Canonical ledger reconciliation follows the
actual reviewed record delivery.

The existing website remains static. The selected future server package is
separately built/deployed at `Pick-Your-AI/pickyour.ai/services/planner-api` only
through canonical owner/coordinator and operations review before dependent code;
this document creates no directory or website deployment. Apple integration
requires [Pick-Your-AI/pick-your-ai-apple#23](https://github.com/Pick-Your-AI/pick-your-ai-apple/issues/23).
Canonical website inputs remain [Pick-Your-AI/pickyour.ai#15](https://github.com/Pick-Your-AI/pickyour.ai/issues/15), [Pick-Your-AI/pickyour.ai#16](https://github.com/Pick-Your-AI/pickyour.ai/issues/16), [Pick-Your-AI/pickyour.ai#17](https://github.com/Pick-Your-AI/pickyour.ai/issues/17) and [Pick-Your-AI/pickyour.ai#18](https://github.com/Pick-Your-AI/pickyour.ai/issues/18). Reuse those outputs and Android/shared-service work; do not independently assign the same backend work twice. No runtime code belongs in the research repository.

Preserve frozen v1. Research authorities are [Pick-Your-AI/pick-your-ai-research#40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40) for versioned contracts/compatibility, [Pick-Your-AI/pick-your-ai-research#41](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/41) for evidence/freshness, [Pick-Your-AI/pick-your-ai-research#39](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39) for pricing, [Pick-Your-AI/pick-your-ai-research#42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42) for saved-build semantics and [Pick-Your-AI/pick-your-ai-research#60](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/60) for source-use rights. Refresh these authorities before changing their outputs.

T3–T5 remain optional; core T6 requirements survive their deferral. Accounts, purchases, alerts, community, runtime execution and additional platforms require their existing scope decisions. Unknown compatibility is not pass, unknown pricing is not zero, and synthetic fixtures are not product evidence. Merged research is not automatic catalog publication or delivery completion.

Repository creation grants no paid resources, outreach, infrastructure provisioning, deployment, store publication or production release authority. The subsequent authorized migration moves planning issues here; it preserves assignments, statuses, acceptance checkboxes, native parents and prerequisites. Acceptance-record paths are not moved by an issue transfer.

The implementation mandate permits project-focused local work, commits and reviewed
PR publication/merges. It does not supply another person's ownership commitment,
credentials, paid resources, deployment or Play Store release authority. Shared
service placement stays coordinated through #35, the shared-service coordinator and existing website
owners before dependent server implementation; the Android preview introduces no
competing service.
