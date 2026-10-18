# Android foundation review package

This package began as proposals for the existing Android delivery issues. The
maintainer has since authorized implementation and delegated engineering decisions.
The architecture record now distinguishes adopted guest-preview choices from the
earlier product recommendations. These documents remain inputs to receiving
acceptance; implementation evidence lives in the linked core/preview artifacts and
does not establish device, store or production completion.

| Proposal | Existing issues | Outcome |
| --- | --- | --- |
| [Product and scope](01-product-scope.md) | [#20](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20), [#24](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24), [#25](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/25) | A bounded, guest-first planning preview with explicit later capabilities |
| [Native architecture](02-native-architecture.md) | [#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) | Adopted Kotlin/platform-Views diagnostic, current pins and boundaries; earlier Compose/Room product alternatives |
| [Services and acceptance](03-service-and-readiness.md) | [#35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35), [#47](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47) | Local-first preview; explicit production service, operations and device gates |
| [Issue audit](issues-snapshot.md) | All open Android issues | Dated inventory of issue-body prerequisites and missing evidence |
| [Initial decisions and walkthrough](04-initial-decisions.md) | [#20](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20), [#24](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24), [#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) | Concrete first-slice product, mode, module and namespace choices; pinned desk/source walkthrough for receiving review |

The live issues still name `docs/delivery/android/work-packages/01.01.md` through
`01.06.md` in `Pick-Your-AI/pickyour.ai` as their acceptance records. These proposal
files are inputs to those records; they do not move them or replace their reviews.
No website files, issue fields, assignees, acceptance checkboxes or native
relationships are changed by this package.

The product decision authority and Android technical lead are the required receiving
roles. The inspected issues name no people for those roles. The maintainer's
execution authorization does not establish another person's review or commitment.

Read the records together. The bounded guest preview already has an engineering
disposition: Kotlin core, platform Views, experimental preview namespace and pinned
toolchain. These choices govern continued implementation within that scope. Product receiving acceptance under #34 remains incomplete. Server
implementation still follows coordinated #35 placement and canonical website
service handoffs; the service recommendation establishes no production deployment.

Validation for this documentation package is `git diff --check`, relative-link and
issue-reference checks, comparison of its acceptance tables with the captured live
issue bodies, and review of the pinned source files. The original documentation
review had no application build; subsequent JVM and unsigned Android build/lint
evidence is documented in [CORE_FOUNDATION.md](../CORE_FOUNDATION.md) and
[ANDROID_PREVIEW.md](../ANDROID_PREVIEW.md). Those results do not supply installed-device
acceptance. The [audit follow-up](issues-snapshot.md#access-and-completion-limits) records the
authenticated refresh and its remaining limits. Engineering documentation review
does not substitute for the named receiving roles or establish application acceptance.
