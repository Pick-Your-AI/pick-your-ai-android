# Android native implementation and repository decision

Decision date: 2026-10-18. Effective through independent receiving review and the
normal reviewed merge of this #34 decision and its [01.03 acceptance record](../delivery/android/work-packages/01.03.md).
The maintainer delegated engineering decisions; the [#24 scope matrix](01-product-scope.md#capability-and-release-boundary)
is the named prerequisite. The earlier proposal and its `4080b04` observation remain
in Git history. This record selects the next implementation boundary without
claiming that the product or its device/release gates are complete.

Select **native Kotlin with platform Android Views for the first bounded planning
slice**, retaining the existing `:core` and opt-in `:preview` separation. Retain the
canonical filesystem draft/revision store. Compose and Room are evaluated
alternatives, not additional dependencies selected by this decision. Current source
already exercises the required boundaries; changing UI or storage technology before
qualifying the slice would add a rewrite/migration without demonstrated benefit.
This is a scope and maintainability judgment, not a framework benchmark.

## Requirement-to-choice map

The requirements below come from the same reviewed #20/#24 decision package;
acceptance of those named inputs is part of receiving review, not inferred from an
unmerged proposal.

| Requirement | Selected technology/boundary | Tradeoff and acceptance limit |
| --- | --- | --- |
| R1: J1/J4 retain confirmed local work and distinguish pending edits | Native lifecycle and explicit restoration tokens; canonical local revision store | Existing work can be qualified without a new UI framework. Recreated Views and in-memory state are not durable acknowledgment; installed termination/fault checks remain required |
| R2: J2/J3 preserve exact contracts, evidence and unknowns | SDK-independent Kotlin `:core`; frozen inputs and strict adapters | JVM logic and Java filesystem adapters are reusable within Android, not automatically Kotlin Multiplatform or Apple code. Canonical semantics remain outside UI |
| R3: J1–J4 work with intermittent connectivity | App-private local storage and bounded permitted snapshots; no network dependency for local drafts | Explicit local operation avoids inventing a backend. It does not provide sync, remote backup or source-use permission |
| R4: J2/J3 labelled, understandable lists and outcomes | Platform text/buttons, labelled inputs, text-based statuses and bounded catalog pages | Small existing UI surface is inspectable. TalkBack, keyboard focus, resizing and large-text behavior still need qualified device tests; Views alone prove no accessibility conformance |
| R5: J5 has explicit disclosure and authority | Android document/URI/share adapters at the UI boundary, activated only after accepted format/service inputs | Native integration avoids a WebView bridge. No file sharing, App Links or remote permission is claimed implemented by choosing the boundary |
| R6: Separate diagnostic from product/release identity | Existing Android repository and preview namespace; distinct reserved engineering name for future product | Keep unsigned synthetic diagnostics reproducible; do not turn their ID, SDK floor or test APK into a Play/publisher acceptance |

## Alternatives and rejected choices

| Candidate | Lifecycle/offline | Accessibility | Reuse and maintenance | Disposition |
| --- | --- | --- | --- | --- |
| Kotlin + existing platform Views | Direct Android lifecycle; existing revision store and worker boundaries | Platform controls/labels; real focus, text-size and TalkBack qualification remains | Preserves current Kotlin code, dependency locks and reviewed recovery work | Selected for the first bounded slice |
| Kotlin + Compose, optionally Room | Native lifecycle and repository approach; Compose state is not durable storage; Room would need migration from current records | Compose semantics and lazy lists are inspectable in the official sample; device behavior remains app-specific | Retains Kotlin core but adds UI migration and dependencies; Room adds storage migration rather than replacing its necessity | Reject as an immediate rewrite. Reconsider for a concrete UI scale/state problem or relational-storage requirement with migration evidence |
| Flutter shared client | Restoration APIs plus deliberate durable repository/storage integration | Semantics and automated guidance exist; native assistive tests remain necessary | Requires Dart/UI port and native adapters; neither current React nor Kotlin implementation is directly reused | Reject for this slice. Apple already accepted its native SwiftUI boundary, so a shared Dart client would require a second platform decision |
| React/WebView wrapper | Browser lifecycle/storage must be bridged to native durable state and document handling | Existing DOM UI is reusable, but web/native focus and platform behavior need qualification | Most direct TypeScript/React reuse; adds WebView/bridge, serialization and release integration obligations | Reject for the core native/offline requirement. The existing website remains an independent client |

None of these alternatives is claimed incapable of offline or accessible behavior.
The earlier Compose/Room recommendation was made before the present native store
and diagnostic existed. Its useful separation of UI, domain and adapters is retained;
its immediate framework/database choice is superseded for this bounded slice.

Apple's [accepted #22 ADR at `a73a933d`](https://github.com/Pick-Your-AI/pick-your-ai-apple/blob/a73a933dceea36bf7af35f95697acff0ab6d8e7f/docs/decisions/issue-22-platform.md)
and [receiving receipt](https://github.com/Pick-Your-AI/pick-your-ai-apple/issues/22#issuecomment-6244062120)
select SwiftUI/UIKit interoperability, Swift 6 and App/Core/Data separation with
system SQLite for local history. Consume that independent boundary; do not reopen
Apple architecture or imply identical client languages/stores. Share contracts,
fixtures and accepted semantics, not UI frameworks or a competing service.

## Repository, package and module ownership

Use `Pick-Your-AI/pick-your-ai-android` as the sole Android implementation repository.
Source/package governance belongs to that project's existing maintainer and normal
review process. Live assignees remain authority for individual work packages; this
decision does not assign a person or an operating role. The user-delegated Android
agent prepares this decision, with independent coordinating-agent receiving review.

| Location or identity | Selected responsibility | Dependency/ownership boundary |
| --- | --- | --- |
| `:core`, packages `ai.pickyour.core.*` | Contract adapters, domain state and analysis, plus the existing JVM filesystem/cache adapters | No Android SDK/UI dependency. UI does not own compatibility or revision policy; filesystem adapters are not pure functions or portable Swift/Dart implementations |
| `:preview`, `ai.pickyour.android.preview` | Current synthetic Android diagnostic, native controls, lifecycle and app-private path composition | Depends on `:core`; included only with `-PandroidPreview=true`; unsigned debug and release variants disabled |
| `ai.pickyour.android` | Selected engineering namespace for a future production application | Separate from preview. No Play availability, publisher account, release key or registration is asserted; resolve those exact identities before release |
| Future `:app` | Product composition and native screens when the release application is created | Depends on core and any extracted adapters. Keep preview synthetic; adopting the name creates no module in this change |
| Future `:data`, only when extraction has a concrete boundary benefit | Android storage/network/credential adapters behind explicit interfaces | Depends on core; preserve existing revision bytes/migrations. No second revision engine or mandatory database rewrite; core cannot depend on UI/data |

Feature packages need not become Gradle modules immediately. The selected current
store remains `LocalDraftStore` with `PreviewDraftPersistence`/library adapters over
`VerifiedArtifactCache`. A future storage change requires preserved histories,
explicit format migration, conflict semantics and failure/recovery tests. Room is
not selected merely because an official sample uses it.

Shared-service placement remains Android #35 / Apple #23 and the canonical website
services. No server, account system, catalog publisher or inference engine is added
to this client. The website's static hosting and existing checks remain unchanged.

## Inspection of the selected approach

Source baseline:
[`952eb015c391971ca4529ede2c1d99de320173c1`](https://github.com/Pick-Your-AI/pick-your-ai-android/tree/952eb015c391971ca4529ede2c1d99de320173c1).
The decision agent inspected these paths on 2026-10-18. This supplies #34's allowed
source-inspection method; it does not certify installed behavior.

| Required inspection | Source and observed mechanism | Negative/recovery limit |
| --- | --- | --- |
| Lifecycle restoration | [PreviewActivity](../../preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt) captures draft identity/baseline plus bounded pending fields, reloads storage, and requires explicit reconciliation when authority changes; [PreviewDraftRestoration](../../core/src/main/kotlin/ai/pickyour/core/preview/PreviewDraftPersistence.kt) separates baseline matching from saved acknowledgment | Pending form state does not establish a completed write. [Activity simulations](../../preview/src/test/kotlin/ai/pickyour/android/preview/PreviewActivityTest.kt) include recreation and a newer disk head; they are JVM simulations, not process-kill/device proof |
| Offline draft | [LocalDraftStore](../../core/src/main/kotlin/ai/pickyour/core/drafts/LocalDraftStore.kt), [PreviewDraftLibrary](../../core/src/main/kotlin/ai/pickyour/core/preview/PreviewDraftLibrary.kt) and persistence adapters retain content-addressed revisions and revision/hash authority, exposing conflict, unsupported/corrupt, failed and uncertain outcomes | No automatic destructive reset. Directory/rename durability still needs Android qualification; no server or cloud-sync result is supplied |
| Accessible list support | [CatalogBrowseSection](../../preview/src/main/kotlin/ai/pickyour/android/preview/CatalogBrowseSection.kt) renders item names/kinds as buttons, labelled paging/filter controls and a polite textual status; Activity and [WorkloadSection](../../preview/src/main/kotlin/ai/pickyour/android/preview/WorkloadSection.kt) associate captions with input IDs | Native semantic primitives are present. This is not a complete TalkBack, focus-order, target-size, large-text or device accessibility audit |

The independently reviewed controls delivery [#284](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/284)
records [actual receiving artifacts](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/284#issuecomment-6248549786):
403 core and 88 Android simulation cases, zero failures/errors/skips, on receiving
tree `236dd70d9f4f864efe355dd062887c918f48dcfe`, subsequently delivered as signed
`952eb015`. Those receipts establish their stated test scope; they are not newly run
tests of this documentation or whole-product acceptance.

## Inspected alternatives: pinned primary sources

The official [Android architecture sample at `ee66e152`](https://github.com/android/architecture-samples/tree/ee66e1526b84c026615df032c705842b7d2a521f)
was re-inspected on 2026-10-18, not built. Its README, `TasksViewModel.kt`,
`TasksScreen.kt`, `TasksScreenTest.kt`, `AddEditTaskViewModel.kt` and
`ToDoDatabase.kt` show the relevant alternative boundaries:

- Saved filter state and lifecycle-aware collection accompany a Compose task list
  and labelled actions; UI tests assert display and toggling.
- Unsaved title/description live in memory; explicit repository save precedes its
  saved flag. The sample does not prove durable autosave.
- Room is a demonstrated local store, but its sample `exportSchema = false` is
  explicitly unsuitable as the application's production migration policy.

Official Flutter documentation at
[`1791ca9a14dc66d4a340b1c991e3aa13346eb350`](https://github.com/flutter/website/tree/1791ca9a14dc66d4a340b1c991e3aa13346eb350)
was re-inspected, not executed:
[Android restoration](https://github.com/flutter/website/blob/1791ca9a14dc66d4a340b1c991e3aa13346eb350/sites/docs/src/content/platform-integration/android/restore-state-android.md)
separates transient restoration from long-lived storage and requires restorable
navigation; [offline architecture](https://github.com/flutter/website/blob/1791ca9a14dc66d4a340b1c991e3aa13346eb350/sites/docs/src/content/app-architecture/design-patterns/offline-first.md)
places local/remote data behind a repository;
[accessibility testing](https://github.com/flutter/website/blob/1791ca9a14dc66d4a340b1c991e3aa13346eb350/sites/docs/src/content/ui/accessibility/accessibility-testing.md)
describes semantics/contrast/target checks and platform inspection. Both options
still require application-specific storage, recovery and assistive-device tests.

## Existing website reuse audit

The inspected website remains
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/tree/dc66a9e269917ab7241c7d08fd0e7f2a2f039872).
Its `contracts/PROVENANCE.json` pins research `d4bdca479f78439b3b98e7418b37acbaccbaa63c`.
Android's [adoption record](../CONTRACT_ADOPTION.md) separately records
`9769fcd9ea380b1211669b2753181c7782444178` and unchanged 43-file v1 package/manifests.
Compare immutable bytes and semantics before reuse; do not silently replace either
consumer's pin or treat a reference client as contract authority.

| Website source at the pin above | Reuse decision and concrete inspection result |
| --- | --- |
| `contracts/v1/**`, provenance, graph/rules, examples | Reuse governed language-neutral inputs with exact hashes and original synthetic/source-use labels; research #40 alone governs extensions/compatibility semantics |
| `src/domain/build.ts` | Immutable revision operations are behavioral reference. Browser-generated identities, default region/policy/settings and its draft-as-BuildSnapshot representation are not silently imported into Android's incomplete drafts |
| `src/domain/evaluator.ts`, `src/domain/invalidation.ts` | Interpreter and before/after graph behavior are parity references. UI cannot create new compatibility policy; unsupported baseline behavior stays disclosed |
| `src/domain/cost.ts`, `src/domain/decimal.ts` | Inspect covered/uncovered meters and decimal handling; TypeScript's scale/default/projection choices are not automatically Android money semantics. Preserve frozen decimal-string rules and explicit assumptions through shared fixtures |
| `tests/unit/evaluator-parity.test.ts`, `tests/unit/scenarios.ts`, `tests/unit/scenarios.test.ts`, `tests/unit/contracts.test.ts` | Reuse scenario meaning after pin verification. The website API example omits T2–T6; Android's selected core T6 planning is not dropped by copying that example. TypeScript/Python parity does not validate Kotlin; optional local Python discovery is not an Android gate waiver |
| `src/data/local-builds.ts` | Browser `localStorage`, memory fallback and separate record/index writes are not native persistence/CAS. Do not copy placeholder selections or treat browser storage recovery as Android evidence |
| `src/domain/share.ts` | Version/envelope reference only. Its `text.length` limit counts JavaScript code units and JSON.parse has already collapsed duplicate keys; Android import requires its own original-byte, duplicate, depth and projection checks |
| React/router/DOM components and assets | No direct native UI reuse. Retain brand/label provenance and canonical website #18 handoff before any actual asset reuse |

No website tests were executed or modified for this read-only audit. Its static CI
and deployment setup are unchanged. No TypeScript implementation, third-party sample
code or new schema is copied by this decision.

## Shared authorities and implementation rules

Research `contracts/v1` is the shared contract source; research #40 governs
compatibility/versioned extensions, #39 pricing, #41 evidence and source intake,
#42 saved-build lifecycle, and #60 source-use restrictions. Canonical website #15
catalog, #16 resource/quality, #17 saved/shared builds and #18 labels remain their
respective shared-service/data handoffs. The Android repository owns its adapters
and UI, not a second instance of those authorities.

Require durable completion before saved acknowledgment; preserve explicit failed,
conflict and uncertain outcomes. Restored identity and bounded transient fields
must rejoin the correct durable baseline. Keep old revision bytes and migration
history, bind asynchronous results to exact revision/source pins, and activate a
complete validated snapshot atomically. Labels, focus, non-color-only outcomes,
text scaling and keyboard/TalkBack checks remain implementation requirements.

## Toolchain, remaining constraints and receiving review

Retain the current engineering pins in [build files](../../build.gradle.kts),
[preview configuration](../../preview/build.gradle.kts) and [reproduction guide](../ANDROID_PREVIEW.md):
JDK 21, Java 17 bytecode, Gradle 8.11.1, Kotlin 2.1.20, AGP 8.9.2; compile/target SDK
35, minSdk 26 and the recorded build-tools 35.0.0 environment. These are a bounded
build/runtime baseline, not measured audience coverage or current Play submission
approval. Supported product devices and fresh platform/store requirements remain
#47/#54 and release qualification; no paid runner, store registration or new
production identity is selected.

The independent reviewer must accept the selected scope, alternatives, R1–R6
mapping, source/reuse findings, package/governance disposition and exact #24 input.
That decision-content review can satisfy #34; no human signature or installed
release criterion is invented. Actual devices, physical accessibility, production
source/service acceptance, publisher identity and signing remain explicit
downstream requirements. Record the published artifact and receiving receipt in
[01.03](../delivery/android/work-packages/01.03.md) and the versioned acceptance
index before reconciling issue metadata.
