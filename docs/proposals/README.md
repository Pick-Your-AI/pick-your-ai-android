# Android foundation review package

This package began as proposals for the existing Android delivery issues. The
maintainer has since authorized implementation and delegated engineering decisions.
The existing product/native documents contain concrete #20/#24/#34 decisions.
The #25 record adds its guest/account/revenue applicability decision; the #35
baseline adds the minimum shared-service hosting and operating-role decision. Each takes
effect through independent decision-content receiving review and normal merge.
Their exact criterion mappings are linked below. Implementation evidence remains
separate from decision acceptance, device, store or production completion.

| Proposal | Existing issues | Outcome |
| --- | --- | --- |
| [Product and scope](01-product-scope.md) | [#20](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20), [#24](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24) | Pinned evidence/hypotheses, five journeys and actual desk walkthrough, four-mode and capability decisions |
| [Accounts and revenue](../delivery/android/work-packages/01.05.md) | [#25](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/25) | Free guest-local baseline; explicit account/payment/analytics/notification dispositions, canonical remote authority, current policy and recovery cases |
| [Native architecture](02-native-architecture.md) | [#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) | Select retained Kotlin/Views/store boundary; compare Compose/shared clients; inspect native lifecycle/storage/lists and portable versus platform-specific reuse |
| [Service decision](../SERVICE_BASELINE.md) and [01.04 receiving record](../delivery/android/work-packages/01.04.md) | [#35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35) | Select one canonical Node API and paid PostgreSQL on Render; map frozen operations, recovery, isolation, cost assumptions and explicit operating acceptance gates. The [earlier proposal](03-service-and-readiness.md) keeps its historical and separately reviewed storage guidance |
| [Acceptance baseline](../ACCEPTANCE_BASELINE.md) | [#47](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47) | Concrete device/API/window targets, A01–A13 measurement/evidence criteria, explicit roles and separate structural/factual/application/release gates; effective through independent receiving review and normal merge |
| [Issue audit](issues-snapshot.md) | All open Android issues | Dated inventory of issue-body prerequisites and missing evidence |

The original intended acceptance locations for 01.01–01.06 were in
`Pick-Your-AI/pickyour.ai`. [ADR 0002](../decisions/0002-android-foundation-records.md)
explicitly places only [01.01](../delivery/android/work-packages/01.01.md),
[01.02](../delivery/android/work-packages/01.02.md) and
[01.03](../delivery/android/work-packages/01.03.md) in this Android repository after
reviewed merge, preserving all nine literal criteria.
[ADR 0003](../decisions/0003-android-25-acceptance-record.md) additionally places
only [01.05](../delivery/android/work-packages/01.05.md) here after its reviewed
merge, preserving its three literal criteria and original placement history.
[ADR 0004](../decisions/0004-android-47-acceptance-record.md) adds only
[01.06](../delivery/android/work-packages/01.06.md) after its independent review and
normal merge, preserving #47's three literal criteria.
[ADR 0005](../decisions/0005-android-35-acceptance-record.md) separately places only
[01.04](../delivery/android/work-packages/01.04.md) after independent review and
normal merge, preserving #35's three criteria and original website history.
Every other unmoved path retains its existing authority.
This source package changes no
website files, issue fields, assignees, checkboxes or native relationships.

The product decision authority and Android technical lead are the required receiving
roles. The inspected issues name no people for those roles. The maintainer's
execution authorization does not establish another person's review or commitment.
Independent agent receiving review under the user's delegated authority must
explicitly assess the actual decisions and limitations; it is not labelled as a
human approval. Record its exact source/PR receipt before completion metadata.

Read the records together. The bounded guest preview already has an engineering
disposition: Kotlin core, platform Views, experimental preview namespace and pinned
toolchain. The current decision adds the scoped product boundary and a concrete
reuse/ownership disposition; acceptance follows its recorded reviewed delivery. Server
implementation still follows coordinated #35 placement and canonical website
service handoffs; the service decision establishes no production deployment or
ongoing operating commitment. Its open account/custody/recovery gates do not
prevent completing the bounded decision and its actual receiving review.

Validation for this documentation package is `git diff --check`, relative-link and
issue-reference checks, comparison of its acceptance tables with the captured live
issue bodies, and review of the pinned source files. The original documentation
review had no application build; subsequent JVM and unsigned Android build/lint
evidence is documented in [CORE_FOUNDATION.md](../CORE_FOUNDATION.md) and
[ANDROID_PREVIEW.md](../ANDROID_PREVIEW.md). Those results do not supply installed-device
acceptance. The [audit follow-up](issues-snapshot.md#access-and-completion-limits)
remains historical. Source preparation and syntax checks do not substitute for
decision-content receiving review or establish application acceptance.
