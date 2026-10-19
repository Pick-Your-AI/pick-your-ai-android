# Play and Android SDK policy register

Official sources accessed **2026-10-19**. Repository observations are pinned to
[`785fe8a77e2b036614eec59b894fd990d74795fc`](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/785fe8a77e2b036614eec59b894fd990d74795fc).

This supplies the dated policy input for [Android #54](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/54).
The accepted [guest-local decision](delivery/android/work-packages/01.05.md)
(#25) governs feature disposition, and the accepted
[architecture decision](delivery/android/work-packages/01.03.md) (#34) governs
the native implementation. Their scope is unchanged. The issue's canonical
receiving location remains `docs/delivery/android/work-packages/16.02.md` in
`Pick-Your-AI/pickyour.ai`; this engineering register does not replace that
record or accept any #54 criterion.

## Observed preview configuration

These are source observations. No release artifact, Play Console configuration
or installed-device result was produced for this register.

| Source at the pinned commit | Observed state | Consequence for #54 |
| --- | --- | --- |
| [Preview build](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785fe8a77e2b036614eec59b894fd990d74795fc/preview/build.gradle.kts) | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`; application ID `ai.pickyour.android.preview`, version `0.0.1-synthetic`; debug signing configuration is null and release variants are disabled | This is the experimental preview. Its target does not satisfy the current new-mobile-app submission floor below. The configured minimum is not an evidence-backed product support decision |
| [Source manifest](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785fe8a77e2b036614eec59b894fd990d74795fc/preview/src/main/AndroidManifest.xml) | No declared permissions; `allowBackup`, `fullBackupContent` and `usesCleartextTraffic` are false; one exported launcher Activity; no orientation or aspect-ratio restriction is declared | Reinspect the merged manifest and actual packaged resources for the eventual candidate. These source settings do not establish platform behavior or native-library compatibility |
| [Help](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785fe8a77e2b036614eec59b894fd990d74795fc/preview/src/main/kotlin/ai/pickyour/android/preview/LocalHelp.kt) and [diagnostics](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/785fe8a77e2b036614eec59b894fd990d74795fc/preview/src/main/kotlin/ai/pickyour/android/preview/LocalDiagnostics.kt) | Help deliberately opens a fixed public project page in an external browser after confirmation. Diagnostic text has a separate preview and system-chooser handoff | Review these actual off-app flows in the final disclosure and audience assessment. Neither a browser launch nor a chooser handoff proves support submission or receipt |

## Platform and distribution requirements

Each source in this table was read on the access date above. Requirements are
conditional on the stated app category, target/runtime combination, publisher
and distribution route; an unavailable input stays unresolved.

| ID / official source | Current requirement and applicable scope | Evidence still needed |
| --- | --- | --- |
| S1 [Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) | Since 2026-08-31, new mobile apps and mobile-app updates must target Android 16 / API 36 or later. Existing-app availability has a different API 35 rule. Other device categories have separate floors. The page describes a possible extension to 2026-11-01; no extension for this project has been established | Select the actual mobile release candidate, update and qualify its target, and inspect its merged manifest. Do not use the existing-app availability rule or assume extension approval to justify a new API 35 submission |
| S2 [Android 16 target-specific changes](https://developer.android.com/about/versions/16/behavior-changes-16) | For an app targeting API 36 on Android 16, edge-to-edge enforcement cannot be opted out of. Predictive back is enabled by default; the legacy `onBackPressed` callback and `KEYCODE_BACK` dispatch no longer supply that default path. On displays with smallest width at least 600 dp, orientation, aspect-ratio and resizability restrictions are generally ignored, subject to the documented exceptions | Exercise the selected target on actual applicable OS/window configurations: system-bar and keyboard insets, back cancellation/commit, unsaved work, dialogs, resize and lifecycle recovery. Review the linked all-app Android 16 changes too. Existing simulated tests do not establish this qualification; no compatibility opt-out is selected here |
| S3 [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes) | The current page requires 16 KB support for Play apps targeting API 35 or later on 64-bit devices and states that unsupported updates cannot be released from 2027-02-01. Direct or transitive native libraries matter. Kotlin/Java source alone is insufficient evidence about the packaged dependency graph | Inspect the candidate's complete release dependencies and packaged `.so` files/ABIs. For native code, retain ELF and packaging-alignment results; then execute the candidate in a verified 16 KB environment. If no native libraries are packaged, retain that inspection and still check behavior in that environment. The later update-enforcement date does not change S1's target requirement |
| S4 [New personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB) | Personal Play developer accounts created after 2023-11-13 require a closed test with at least 12 testers continuously opted in for 14 days before applying for production access. Meeting that threshold permits an application; it is not production approval | Record the actual publisher account type, creation date and production-access status. If applicable, obtain the actual closed-track participation and production-access decision. No publisher status, participants or elapsed test period are asserted here |
| S5 [Android developer verification](https://developer.android.com/developer-verification) | The published rollout begins 2026-09-30 for apps installed from participating stores in Brazil, Indonesia, Singapore and Thailand on certified devices running Android 7 or later, with further rollout planned for 2027 | Determine the actual stores, countries, publisher verification and app-registration requirements at distribution time. No store, country, signing identity or registration status has been selected by this register; do not generalize this regional/store scope into a claim about every installation |

### Minimum SDK is a separate decision

S1 distinguishes the target API from the oldest supported OS. Raising the target
does not imply raising `minSdk` to 36. The preview's API 26 minimum is an observed
configuration, not a newly accepted support matrix. The product decision needs
its intended supported devices, platform/API and dependency constraints, and
successful behavior on the selected oldest and current supported environments.
Record that rationale and its receiving review in #54 before calling it settled.

## Conditional policies against the accepted features

The #25 record already contains the capability decisions and P1–P5 policy
assessment. Keep that decision authoritative. The following refresh records
what must be checked for #54 without enabling deferred features or substituting
an accepted product decision for release evidence.

| Policy / official source | Applicability for the accepted guest-local scope | Remaining qualification |
| --- | --- | --- |
| [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en-GB) and #25 P1/P5 | The Play form applies to closed, open and production tracks, including apps with no collected user data; exclusively internal testing has the stated exemption. A privacy-policy link is still required when the form is required | Use the actual app and SDK inventory, user-initiated transfers, off-device flows and selected track to complete accurate declarations and disclosures. Retain #25 P1 privacy requirements independently of the form exemption. The disabled analytics/automatic-upload baseline is not a completed form or a blanket no-data finding |
| [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en) and #25 P2 | Account creation and app-account signup redirects are deferred. If either is introduced, even optionally, the policy requires in-app and web paths to request deletion of the app account and associated data | Preserve local-record deletion/retention under #88 independently of the absent account feature. Inspect the actual release navigation, including external destinations; do not label a future signup link exempt merely because it opens a browser |
| [Payments](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en) and #25 P3 | Paid downloads, digital unlocks and subscriptions are deferred; commercial referrals are rejected for the initial scope. A later paid digital feature must be assessed against Play Billing and any actually applicable exception or enrolled regional program | Reopen the accepted feature decision and #45 before enabling a purchase or external-payment route. Hardware/physical-service exclusions do not establish an exception for digital cloud or software services |
| [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission) and #25 P4 | Notifications are deferred and the pinned source manifest declares no notification permission. Android 13 / API 33 and later use `POST_NOTIFICATIONS` for non-exempt notifications | If #56 activates a supported event, qualify contextual grant, denial, revocation and unchanged core behavior. The permission rule is independent of using Views rather than Compose |
| [UGC policy](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en) | Community publishing is rejected for the initial scope. The policy also covers apps featuring UGC and specialized clients directing users to UGC platforms; the current Help browser route is recorded above | Inspect the actual release experience and destinations before recording final applicability. Do not infer a blanket exemption from the absence of an in-app editor. Any activated UGC feature needs the applicable terms, reporting, blocking and moderation provisions |
| [Target audience and content](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en-en) | New apps and updates must declare their actual target age groups. Including children makes the Families requirements applicable; the app's actual content and presentation matter | The selected release audience has not been established here. Obtain a truthful audience/content-rating assessment; a technical planning purpose is not evidence of an adults-only audience |

## Receiving evidence and refresh triggers

The remaining #54 work is concrete: obtain the selected release source/artifact
identity and distribution inputs; qualify its target and minimum support
decision; inspect the release dependency graph and native packaging; exercise
the applicable platform and 16 KB behavior; and record each conditional policy's
final applicability with its fulfillment evidence. Use the current
[preview reproduction guide](ANDROID_PREVIEW.md),
[packaged-preview policy](PACKAGED_PREVIEW_POLICY.md) and
[runtime inventory guide](RUNTIME_INVENTORY.md) only for their stated
diagnostic scope. Their evidence does not turn this unsigned preview into a
release candidate.

Refresh the official pages at the next implementation assessment and immediately
before submission, and whenever the target, dependencies, permissions, external
destinations, enabled features, audience, publisher, track or regions change.
Retain the access date, applicable clause, exact artifact/source and actual
review or execution receipt. All #54 acceptance criteria remain open until the
canonical receiving record and current ledger accept the complete evidence.
