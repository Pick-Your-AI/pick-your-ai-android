# Android contributor instructions

Read [CONTRIBUTING.md](CONTRIBUTING.md), [repository scope](docs/REPOSITORY_SCOPE.md), and the canonical [shared mobile rules](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/d0a1f8b05b90e7e7087fb2f06890265ab5d42606/prompts/mobile/SHARED.md) with the [platform prompt](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/d0a1f8b05b90e7e7087fb2f06890265ab5d42606/prompts/mobile/ANDROID.md) before work. Keep shared execution rules canonical rather than maintaining a second prompt set here.

- Use live GitHub assignees as ownership authority. Research ownership does not assign mobile implementation. Preserve native relationships and Project vocabulary; distinguish Needs owner, Needs input, Awaiting decision and an evidenced Blocked state. Do not mark Ready without ownership, scope and prerequisites.
- Refresh the existing issue, acceptance record, linked PRs and active work. Coordinate with existing work. The maintainer-authorized issue migration is recorded in the canonical prompt index. Do not relocate issues again or create duplicate implementations without an explicit mandate.
- Resolve [Pick-Your-AI/pick-your-ai-android#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34) and applicable shared-service decisions before dependent implementation. This repository does not choose a framework or backend topology.
- Preserve acceptance paths and shared contract/evidence authorities in the scope record. Do not fabricate evidence, owners, dates, estimates or commitments.
- Address applicable lifecycle and process-death recovery; durable storage and migrations; offline synchronization; permissions; secure Android App Links; accessibility; emulator and physical-device testing; signing and Google Play delivery. Verify current platform and store requirements from primary sources during implementation/release.
- Separate local builds, simulator/emulator results, physical-device evidence, store submission, approval, authorized release, installed production verification and operational readiness. Report missing evidence explicitly.
- Follow signed commits, PR review and existing repository gates. Never commit credentials, signing keys, customer data, raw evidence downloads or model weights.
- Never shut down or terminate the shared WSL VM. Diagnose individual sessions/services without affecting other workloads.

The pinned prompt migration revision is proposed in https://github.com/Pick-Your-AI/pick-your-ai-research/pull/111; verify its review status before treating it as accepted.
