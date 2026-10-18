# Pick Your AI — Android

The Android planning client for discovering, configuring, checking, costing, comparing and sharing AI stacks. The mobile device is not automatically the inference target.

This repository contains a testable Kotlin/JVM core and an immutable frozen-v1
contract/reference bundle. See [core setup and acceptance evidence](docs/CORE_FOUNDATION.md).
An [opt-in Android diagnostic](docs/ANDROID_PREVIEW.md) validates a bundled synthetic
fixture in an unsigned debug APK. There is no signed installable product,
production backend or release yet.

The [local draft library](docs/LOCAL_DRAFT_LIBRARY.md) creates, renames, duplicates,
opens and deletes multiple guest drafts, with explicit recovery of interrupted
operations and migration of the earlier single saved form. It preserves exact
fixture pins and uses the existing immutable revision store.

The [foundation review package](docs/proposals/README.md) proposes a bounded scope,
native architecture and service boundary against the existing delivery issues.
These proposals await receiving review and do not establish implementation or
release acceptance.

The [data inventory and threat model](docs/security/DATA_INVENTORY_AND_THREAT_MODEL.md)
maps the proposed preview and future service flows to privacy/security controls
and verification work. It remains pending receiving review under Android #23.

- [Delivery Project 4](https://github.com/orgs/Pick-Your-AI/projects/4) and program [Pick-Your-AI/pick-your-ai-android#1](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/1).
- Repository and framework decision: [Pick-Your-AI/pick-your-ai-android#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34). Repository creation is partial progress; framework, module layout and other architecture decisions remain open.
- Shared-service decision: [Pick-Your-AI/pick-your-ai-android#35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35); reuse canonical services rather than create a competing backend.
- [Repository scope](docs/REPOSITORY_SCOPE.md), [contributing](CONTRIBUTING.md), [agent instructions](AGENTS.md), [MIT license](LICENSE).

## Maintainer execution guidance

Use the [platform program prompt](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/d0a1f8b05b90e7e7087fb2f06890265ab5d42606/prompts/mobile/ANDROID.md), [shared rules](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/d0a1f8b05b90e7e7087fb2f06890265ab5d42606/prompts/mobile/SHARED.md), [launch instructions](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/d0a1f8b05b90e7e7087fb2f06890265ab5d42606/prompts/mobile/README.md), and [issue/acceptance/handoff index](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/d0a1f8b05b90e7e7087fb2f06890265ab5d42606/prompts/mobile/INDEX.md). These immutable links reference the [proposed migration revision](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/111), open for independent review and not merged. The original research PR 108 is merged; this newer revision reconciles transferred issue identities.

Planning issues now live in `Pick-Your-AI/pick-your-ai-android`. Refresh live assignees, native parents, prerequisites, Project fields, active work and linked PRs before execution. Do not create duplicate tracking or controllers. Repository creation does not launch a campaign or satisfy a delivery acceptance criterion.

The issue migration preserves the original history and native relationships. Existing Android acceptance-record locations remain in the website repository pending an explicit placement-record change; Apple filenames remain subject to its ADR.
