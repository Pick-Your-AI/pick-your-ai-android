# Android contributor instructions

Read [CONTRIBUTING.md](CONTRIBUTING.md), [repository scope](docs/REPOSITORY_SCOPE.md), and the canonical [shared mobile rules](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/SHARED.md) with the [platform prompt](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/ANDROID.md) before work. Keep shared execution rules canonical rather than maintaining a second prompt set here.

- Use live GitHub assignees as ownership authority. Research ownership does not assign mobile implementation. Preserve native relationships and Project vocabulary; distinguish Needs owner, Needs input, Awaiting decision and an evidenced Blocked state. Do not mark Ready without ownership, scope and prerequisites.
- Refresh the existing issue, acceptance record, linked PRs and active work. Coordinate with existing work. The maintainer-authorized issue migration is recorded in the canonical prompt index. Do not relocate issues again or create duplicate implementations without an explicit mandate.
- Follow the independently reviewed [Android #34 decision](docs/proposals/02-native-architecture.md) and its [receiving record](docs/delivery/android/work-packages/01.03.md) before dependent implementation. Its scoped Kotlin/Views/store choice takes effect through reviewed merge; it does not choose shared-service topology. Applicable shared-service decisions still require Android #35 and canonical coordination.
- Preserve acceptance paths and shared contract/evidence authorities in the scope record. Do not fabricate evidence, owners, dates, estimates or commitments.
- Address applicable lifecycle and process-death recovery; durable storage and migrations; offline synchronization; permissions; secure Android App Links; accessibility; emulator and physical-device testing; signing and Google Play delivery. Verify current platform and store requirements from primary sources during implementation/release.
- Separate local builds, simulator/emulator results, physical-device evidence, store submission, approval, authorized release, installed production verification and operational readiness. Report missing evidence explicitly.
- Follow signed commits, PR review and existing repository gates. Never commit credentials, signing keys, customer data, raw evidence downloads or model weights.
- Never shut down or terminate the shared WSL VM. Diagnose individual sessions/services without affecting other workloads.

The pinned prompt migration was reviewed and merged in https://github.com/Pick-Your-AI/pick-your-ai-research/pull/111 on 2026-10-18 at `da3c6045f84260506bd326bb24ddfa8a7e6a23e8`. Its publication preserves issue scope, ownership and acceptance paths; it does not establish implementation or release acceptance. Refresh live issues and active work before execution.

## Decisions and escalation

For unresolved decisions, follow [CONTRIBUTING.md#routing-decisions](CONTRIBUTING.md#routing-decisions)
and the [canonical escalation guide](https://github.com/Pick-Your-AI/.github/blob/main/docs/governance/ESCALATION.md).
Refresh private routing evidence before choosing a recipient. Report unresolved
roles and failed or stale lookups explicitly; do not infer accepted ownership from
membership or access. Preserve the existing assignee, local gates and private-reporting
boundaries. Central links depend on the governance change merging; no automatic
routing, permission change or recurring job is enabled by these instructions.
