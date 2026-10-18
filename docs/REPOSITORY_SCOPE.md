# Repository scope and unresolved decisions

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

This engineering disposition does not close
[Android #34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34).
The earlier Compose/Room product recommendation remains a later architecture option;
those libraries are not the current preview implementation. Production package
ownership, product/device receiving acceptance, service hosting, an accepted
operations owner and delivery commitments remain separate. Apple work does not
automatically include additional Apple platforms.

The existing Android acceptance record for repository/framework selection remains `docs/delivery/android/work-packages/01.03.md` in `Pick-Your-AI/pickyour.ai`. Other issue deliverable paths also remain authoritative; this engineering-state reconciliation does not migrate them.

The existing website remains static. Shared-service placement requires [Pick-Your-AI/pick-your-ai-android#35](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/35) and Apple integration requires [Pick-Your-AI/pick-your-ai-apple#23](https://github.com/Pick-Your-AI/pick-your-ai-apple/issues/23). Canonical website inputs remain [Pick-Your-AI/pickyour.ai#15](https://github.com/Pick-Your-AI/pickyour.ai/issues/15), [Pick-Your-AI/pickyour.ai#16](https://github.com/Pick-Your-AI/pickyour.ai/issues/16), [Pick-Your-AI/pickyour.ai#17](https://github.com/Pick-Your-AI/pickyour.ai/issues/17) and [Pick-Your-AI/pickyour.ai#18](https://github.com/Pick-Your-AI/pickyour.ai/issues/18). Reuse those outputs and Android/shared-service work; do not independently assign the same backend work twice. No runtime code belongs in the research repository.

Preserve frozen v1. Research authorities are [Pick-Your-AI/pick-your-ai-research#40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40) for versioned contracts/compatibility, [Pick-Your-AI/pick-your-ai-research#41](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/41) for evidence/freshness, [Pick-Your-AI/pick-your-ai-research#39](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39) for pricing, [Pick-Your-AI/pick-your-ai-research#42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42) for saved-build semantics and [Pick-Your-AI/pick-your-ai-research#60](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/60) for source-use rights. Refresh these authorities before changing their outputs.

T3–T5 remain optional; core T6 requirements survive their deferral. Accounts, purchases, alerts, community, runtime execution and additional platforms require their existing scope decisions. Unknown compatibility is not pass, unknown pricing is not zero, and synthetic fixtures are not product evidence. Merged research is not automatic catalog publication or delivery completion.

Repository creation grants no paid resources, outreach, infrastructure provisioning, deployment, store publication or production release authority. The subsequent authorized migration moves planning issues here; it preserves assignments, statuses, acceptance checkboxes, native parents and prerequisites. Acceptance-record paths are not moved by an issue transfer.

The implementation mandate permits project-focused local work, commits and reviewed
PR publication/merges. It does not supply another person's ownership commitment,
credentials, paid resources, deployment or Play Store release authority. Shared
service placement stays coordinated through #35, the shared-service coordinator and existing website
owners before dependent server implementation; the Android preview introduces no
competing service.
