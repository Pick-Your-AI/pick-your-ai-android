# Pick Your AI — Android

The Android planning client for discovering, configuring, checking, costing, comparing and sharing AI stacks. The mobile device is not automatically the inference target.

This repository contains a testable Kotlin/JVM core and an immutable frozen-v1
contract/reference bundle. See [core setup and acceptance evidence](docs/CORE_FOUNDATION.md).
An [opt-in Android diagnostic](docs/ANDROID_PREVIEW.md) validates a bundled synthetic
fixture in an unsigned debug APK. There is no signed installable product,
production backend or release yet.

The [repository scope record](docs/REPOSITORY_SCOPE.md) records the adopted
Kotlin/JVM core and native Android Views preview. The broader product and service
proposals in the [foundation review package](docs/proposals/README.md) still await
receiving acceptance; the implemented preview does not establish product or
release acceptance.

The [data inventory and threat model](docs/security/DATA_INVENTORY_AND_THREAT_MODEL.md)
maps the implemented local preview and planned service flows to privacy/security controls
and verification work. It remains pending receiving review under Android #23.

- [Delivery Project 4](https://github.com/orgs/Pick-Your-AI/projects/4) and program [Pick-Your-AI/pick-your-ai-android#1](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/1).
- Repository and framework decision: [Pick-Your-AI/pick-your-ai-android#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34). The bounded native preview decision is implemented; full product architecture and receiving acceptance remain open in the scope record.
- Shared-service decision: [Pick-Your-AI/pick-your-ai-android#35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35); reuse canonical services rather than create a competing backend.
- [Repository scope](docs/REPOSITORY_SCOPE.md), [contributing](CONTRIBUTING.md), [agent instructions](AGENTS.md), [MIT license](LICENSE).

## Maintainer execution guidance

Use the [platform program prompt](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/ANDROID.md), [shared rules](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/SHARED.md), [launch instructions](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/README.md), and [issue/acceptance/handoff index](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/INDEX.md). These immutable links reference the reviewed migration guidance from [research PR 111](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/111), merged on 2026-10-18 at `da3c6045f84260506bd326bb24ddfa8a7e6a23e8`. It reconciles transferred issue identities and preserves the original guidance's implementation and acceptance boundaries.

Planning issues now live in `Pick-Your-AI/pick-your-ai-android`. Refresh live assignees, native parents, prerequisites, Project fields, active work and linked PRs before execution. Do not create duplicate tracking or controllers. Repository creation does not launch a campaign or satisfy a delivery acceptance criterion.

The issue migration preserves the original history and native relationships. Existing Android acceptance-record locations remain in the website repository pending an explicit placement-record change; Apple filenames remain subject to its ADR.
