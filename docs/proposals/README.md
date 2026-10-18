# Android foundation review package

These are proposed engineering decisions for the existing Android delivery issues,
prepared under the maintainer's instruction to progress the project. They are not
accepted delivery records, an application implementation, or release evidence.

| Proposal | Existing issues | Outcome |
| --- | --- | --- |
| [Product and scope](01-product-scope.md) | [#20](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20), [#24](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24), [#25](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/25) | A bounded, guest-first planning preview with explicit later capabilities |
| [Native architecture](02-native-architecture.md) | [#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) | Kotlin/Compose recommendation, alternatives, reuse boundaries and failure requirements |
| [Services and acceptance](03-service-and-readiness.md) | [#35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35), [#47](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47) | Local-first preview; explicit production service, operations and device gates |
| [Issue audit](issues-snapshot.md) | All open Android issues | Dated inventory of issue-body prerequisites and missing evidence |

The live issues still name `docs/delivery/android/work-packages/01.01.md` through
`01.06.md` in `Pick-Your-AI/pickyour.ai` as their acceptance records. These proposal
files are inputs to those records; they do not move them or replace their reviews.
No website files, issue fields, assignees, acceptance checkboxes or native
relationships are changed by this package.

The product decision authority and Android technical lead are the required receiving
roles. The inspected issues name no people for those roles. The maintainer's
execution authorization does not establish another person's review or commitment.

Read the proposals together. The architecture recommendation is conditional on the
bounded scope, and the service recommendation does not satisfy a production
deployment requirement. Framework implementation follows disposition of #34;
server implementation follows #35 and the canonical website service handoffs.

Validation for this documentation package is `git diff --check`, relative-link and
issue-reference checks, comparison of its acceptance tables with the captured live
issue bodies, and review of the pinned source files. There is no Android build or
device result to report. The [audit follow-up](issues-snapshot.md#access-and-completion-limits) records the
authenticated refresh and its remaining limits. Engineering documentation review
does not substitute for the named receiving roles or establish application acceptance.
